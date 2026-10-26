package com.labteto.dshmobile.connection

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.labteto.dshmobile.core.wire.ConnectionLoop
import com.labteto.dshmobile.core.wire.ConnectionState
import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.GenerationFailure
import com.labteto.dshmobile.core.wire.HandshakeStep
import com.labteto.dshmobile.core.wire.HostGeneration
import com.labteto.dshmobile.core.wire.LoopConfig
import com.labteto.dshmobile.core.wire.LoopSinks
import com.labteto.dshmobile.core.wire.TransportFailure
import com.labteto.dshmobile.core.wire.TransportFailures
import com.labteto.dshmobile.core.wire.dto.HostDescription
import com.labteto.dshmobile.core.wire.dto.RemoteEventFrame
import com.labteto.dshmobile.ui.screens.connect.ConnectFailure
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** UI-facing connection state. */
enum class ConnectionPhase { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

enum class ConnectStage { Idle, Validating, Reaching, OpeningStreams, Verifying, Connected }

data class ConnectionUiState(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val host: HostConfig? = null,
    val description: HostDescription? = null,
    val stage: ConnectStage = ConnectStage.Idle,
    /** Retained across backoff ticks until a generation succeeds or the user disconnects. */
    val failure: ConnectFailure? = null,
    val attempts: Int = 0,
    val hasConnected: Boolean = false,
    /** Identifies a ready generation even when StateFlow conflates intermediate phases. */
    val clientId: String? = null,
)

/**
 * Owns one desired host, its connection loop and background-service policy.
 * Automatic recovery never overrides DISCONNECTED, including a terminal authentication failure.
 */
@Singleton
class ConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clientFactory: HarnessClientFactory,
    private val hostsStore: HostsStore,
    private val networkMonitor: ConnectionNetworkMonitor = ConnectionNetworkMonitor(context),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleLock = Any()
    private val _state = MutableStateFlow(ConnectionUiState())
    val state: StateFlow<ConnectionUiState> = _state.asStateFlow()
    private var connectionEpoch = 0L
    private var loop: ConnectionLoop? = null
    private var activeHost: HostConfig? = null
    @Volatile private var api: DshApiClient? = null
    @Volatile var generation: HostGeneration? = null
        private set
    private var keepInBackground = false
    private var serviceRequested = false
    private var serviceTimedOut = false

    /** Events are generation-local; durable state is restored from stream baselines. */
    val eventFrames = kotlinx.coroutines.flow.MutableSharedFlow<RemoteEventFrame>(extraBufferCapacity = 256)
    val connectedApi: DshApiClient? get() = api

    init {
        scope.launch {
            hostsStore.settings.map { it.keepConnectedInBackground }.distinctUntilChanged().collect { enabled ->
                synchronized(lifecycleLock) {
                    keepInBackground = enabled
                    syncServiceLocked()
                }
                if (enabled) KeepAliveWorker.schedule(context) else KeepAliveWorker.cancel(context)
            }
        }
        scope.launch {
            networkMonitor.network.collect { network ->
                if (network != null) recoverIfNeeded()
            }
        }
    }

    private val sinks = object : LoopSinks {
        override fun onEventFrame(frame: RemoteEventFrame) {
            eventFrames.tryEmit(frame)
        }

        override fun onConnected(generation: HostGeneration) {
            this@ConnectionManager.generation = generation
            val host = activeHost
            if (host != null) scope.launch { hostsStore.touchHost(host.id) }
            _state.value = ConnectionUiState(
                phase = ConnectionPhase.CONNECTED,
                host = host,
                description = generation.description,
                stage = ConnectStage.Connected,
                hasConnected = true,
                clientId = generation.clientId,
            )
            syncServiceLocked()
        }

        override fun onStateChange(state: ConnectionState) {
            if (state == ConnectionState.RECONNECTING) {
                api = null
                generation = null
            }
            val current = _state.value
            _state.value = current.copy(
                phase = when {
                    state == ConnectionState.CONNECTED -> ConnectionPhase.CONNECTED
                    current.hasConnected -> ConnectionPhase.RECONNECTING
                    else -> ConnectionPhase.CONNECTING
                },
            )
        }

        override fun onHandshakeStep(step: HandshakeStep) {
            _state.value = _state.value.copy(stage = when (step) {
                HandshakeStep.OPENING_MUX -> ConnectStage.OpeningStreams
                HandshakeStep.AWAITING_READY -> ConnectStage.Verifying
            })
        }

        override fun onGenerationFailed(attempt: Int, failure: GenerationFailure) {
            val host = activeHost
            _state.value = _state.value.copy(
                failure = ConnectFailure.from(failure, relay = host?.isRelay == true),
                attempts = attempt,
            )
            if (host != null && isTerminalForRelay(host, failure)) {
                retireLoopLocked()
                _state.value = _state.value.copy(
                    phase = ConnectionPhase.DISCONNECTED,
                    stage = ConnectStage.Idle,
                    attempts = 0,
                )
                syncServiceLocked()
            }
        }
    }

    /** Explicit user intent starts a fresh ownership epoch, even after a terminal failure. */
    suspend fun connect(config: HostConfig) {
        synchronized(lifecycleLock) {
            retireLoopLocked()
            stopServiceLocked()
            activeHost = config
            _state.value = ConnectionUiState(
                phase = ConnectionPhase.CONNECTING,
                host = config,
                stage = ConnectStage.OpeningStreams,
            )
            startLoopLocked(config, connectionEpoch)
        }
        hostsStore.upsertHost(config)
    }

    fun disconnect() = synchronized(lifecycleLock) {
        retireLoopLocked()
        activeHost = null
        _state.value = ConnectionUiState()
        syncServiceLocked()
    }

    fun recoverOnForeground() = synchronized(lifecycleLock) {
        serviceTimedOut = false
        syncServiceLocked()
        recoverIfNeeded()
    }

    /** Worker, network and foreground signals share this non-destructive recovery decision. */
    fun recoverIfNeeded() = synchronized(lifecycleLock) {
        val current = state.value
        when (connectionRecoveryAction(current.phase, generation?.mux?.failure != null, loop?.isRunning == true)) {
            RecoveryAction.NONE -> Unit
            RecoveryAction.WAKE -> loop?.requestRetry()
            RecoveryAction.RESTART -> restartLoopLocked()
        }
    }

    /** Explicit Retry may replace a ready connection; automatic recovery must use [recoverIfNeeded]. */
    fun reconnectIfNeeded() = synchronized(lifecycleLock) {
        restartLoopLocked()
    }

    private fun restartLoopLocked() {
        val host = activeHost ?: return
        retireLoopLocked()
        _state.value = state.value.copy(
            phase = if (state.value.hasConnected) ConnectionPhase.RECONNECTING else ConnectionPhase.CONNECTING,
            stage = ConnectStage.OpeningStreams,
        )
        startLoopLocked(host, connectionEpoch)
    }

    /** Android's dataSync budget cannot be reset by reconnecting a socket. */
    fun onBackgroundServiceTimeout() = synchronized(lifecycleLock) {
        serviceTimedOut = true
        stopServiceLocked()
        Log.w("ConnectionManager", "Background connection service reached its Android time limit")
    }

    private fun retireLoopLocked() {
        connectionEpoch++
        loop?.stop()
        loop = null
        api = null
        generation = null
    }

    private fun withOwner(owner: Long, action: () -> Unit) = synchronized(lifecycleLock) {
        if (owner == connectionEpoch) action()
    }

    private fun startLoopLocked(host: HostConfig, owner: Long) {
        val ownedSinks = object : LoopSinks {
            override fun onEventFrame(frame: RemoteEventFrame) = withOwner(owner) { sinks.onEventFrame(frame) }
            override fun onConnected(generation: HostGeneration) = withOwner(owner) { sinks.onConnected(generation) }
            override fun onStateChange(state: ConnectionState) = withOwner(owner) { sinks.onStateChange(state) }
            override fun onHandshakeStep(step: HandshakeStep) = withOwner(owner) { sinks.onHandshakeStep(step) }
            override fun onGenerationFailed(attempt: Int, failure: GenerationFailure) =
                withOwner(owner) { sinks.onGenerationFailed(attempt, failure) }
        }
        val next = ConnectionLoop(
            muxFactory = {
                val carriers = clientFactory.connectionFor(host)
                try {
                    currentCoroutineContext().ensureActive()
                    synchronized(lifecycleLock) {
                        if (owner != connectionEpoch) throw CancellationException("Retired connection owner")
                        api = carriers.api
                    }
                    carriers.mux
                } catch (e: Throwable) {
                    carriers.mux.close()
                    throw e
                }
            },
            sinks = ownedSinks,
            config = LoopConfig(),
            awaitAttempt = {
                if (!host.isLoopback) networkMonitor.network.first { it != null }
            },
        )
        loop = next
        next.start()
    }

    private fun isTerminalForRelay(host: HostConfig, failure: GenerationFailure): Boolean {
        if (!host.isRelay) return false
        val kind = when (failure) {
            is GenerationFailure.MuxFailed -> failure.kind
            is GenerationFailure.ReadyFailed -> TransportFailures.of(failure.error)
            is GenerationFailure.MuxTimedOut -> null
        }
        return kind == TransportFailure.TRUST_FENCE ||
            kind == TransportFailure.CERTIFICATE_PIN ||
            kind == TransportFailure.UNAUTHENTICATED
    }

    private fun syncServiceLocked() {
        val current = state.value
        val wanted = shouldRunConnectionService(keepInBackground, current.phase, current.hasConnected, serviceTimedOut)
        if (!wanted) {
            stopServiceLocked()
        } else if (!serviceRequested) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
                serviceRequested = true
            } catch (e: IllegalStateException) {
                // Android can refuse an FGS start while backgrounded. Retry on the next foreground.
                Log.w("ConnectionManager", "Android refused the background connection service", e)
            } catch (e: SecurityException) {
                Log.w("ConnectionManager", "Background connection service permission unavailable", e)
            }
        }
    }

    private fun stopServiceLocked() {
        if (serviceRequested) {
            context.stopService(Intent(context, ConnectionService::class.java))
            serviceRequested = false
        }
    }
}

internal enum class RecoveryAction { NONE, WAKE, RESTART }

/** DISCONNECTED is a stop condition, not an invitation for a periodic worker to reconnect. */
internal fun connectionRecoveryAction(phase: ConnectionPhase, carrierFailed: Boolean, loopRunning: Boolean): RecoveryAction = when {
    phase == ConnectionPhase.DISCONNECTED -> RecoveryAction.NONE
    phase == ConnectionPhase.CONNECTED && !carrierFailed && loopRunning -> RecoveryAction.NONE
    loopRunning -> RecoveryAction.WAKE
    else -> RecoveryAction.RESTART
}

internal fun shouldRunConnectionService(keepInBackground: Boolean, phase: ConnectionPhase, hasConnected: Boolean, timedOut: Boolean): Boolean =
    keepInBackground && hasConnected && phase != ConnectionPhase.DISCONNECTED && !timedOut
