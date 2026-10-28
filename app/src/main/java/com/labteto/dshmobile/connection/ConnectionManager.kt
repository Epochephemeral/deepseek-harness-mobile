package com.labteto.dshmobile.connection

import android.content.Context
import android.util.Log
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

/** Service protection, not a promise of notification delivery or immunity from process death. */
enum class BackgroundDeliveryState { DISABLED, BEST_EFFORT, SERVICE_ACTIVE, START_RESTRICTED, TIME_LIMIT_REACHED }

@kotlinx.serialization.Serializable
data class HostEventFrame(val hostId: String, val hostBaseUrl: String, val frame: RemoteEventFrame)

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
    val backgroundDelivery: BackgroundDeliveryState = BackgroundDeliveryState.DISABLED,
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
    private val backgroundIntent: BackgroundConnectionPersistence = BackgroundConnectionPersistence(context),
    private val backgroundRuntime: ConnectionBackgroundRuntime = ConnectionBackgroundRuntime(context),
    private val diskCacheStore: com.labteto.dshmobile.core.storage.DiskCacheStore? = null,
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
    private var serviceTimedOut = backgroundIntent.snapshot().timedOut
    private var serviceActive = false
    private var serviceStartRestricted = false
    private var backgroundRestoreActive = false
    // A cold restore may suspend reading DataStore. Any explicit intent retires its read ticket.
    private var intentEpoch = 0L

    /** Events are generation-local; durable state is restored from stream baselines. */
    val eventFrames = com.labteto.dshmobile.core.storage.DiskBackedSharedFlow<RemoteEventFrame>(diskCacheStore,
        encode = { com.labteto.dshmobile.core.wire.WireJson.encodeToString(com.labteto.dshmobile.core.wire.dto.RemoteEventFrameSerializer, it) },
        decode = { com.labteto.dshmobile.core.wire.WireJson.decodeFromString(com.labteto.dshmobile.core.wire.dto.RemoteEventFrameSerializer, it) })
    private val _hostEventFrames = com.labteto.dshmobile.core.storage.DiskBackedSharedFlow<HostEventFrame>(diskCacheStore,
        encode = { com.labteto.dshmobile.core.wire.WireJson.encodeToString(HostEventFrame.serializer(), it) },
        decode = { com.labteto.dshmobile.core.wire.WireJson.decodeFromString(HostEventFrame.serializer(), it) })
    val hostEventFrames: kotlinx.coroutines.flow.SharedFlow<HostEventFrame> = _hostEventFrames
    val connectedApi: DshApiClient? get() = api

    init {
        scope.launch {
            hostsStore.settings.map { it.keepConnectedInBackground }.distinctUntilChanged().collect { enabled ->
                synchronized(lifecycleLock) {
                    keepInBackground = enabled
                    syncServiceLocked()
                }
                backgroundRuntime.updateWork(enabled)
            }
        }
        scope.launch {
            networkMonitor.network.collect { network ->
                if (network != null) recoverIfNeeded()
            }
        }
    }

    /** Storage faults stop the generation explicitly; a failed write is never treated as delivered. */
    fun reportStorageFailure(failure: Throwable) {
        Log.e("DshConnection", "Local cache I/O failed; stream must resynchronize", failure)
        generation?.mux?.close(java.io.IOException("Local cache I/O failed: ${failure.message}", failure))
    }

    /** Re-register failed disk readers only after a fresh, authoritative connection baseline. */
    suspend fun collectHostFrames(consumer: suspend (HostEventFrame) -> Unit) {
        while (true) {
            currentCoroutineContext().ensureActive()
            try { hostEventFrames.collect { consumer(it) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (failure !is java.io.IOException && failure !is android.database.sqlite.SQLiteException) throw failure
                val failedClient = state.value.clientId
                reportStorageFailure(failure)
                state.first { it.phase == ConnectionPhase.CONNECTED && it.clientId != failedClient }
            }
        }
    }

    private val sinks = object : LoopSinks {
        override fun onEventFrame(frame: RemoteEventFrame) {
            val host = activeHost ?: return
            try {
                eventFrames.tryEmit(frame)
                _hostEventFrames.tryEmit(HostEventFrame(host.id, host.baseUrl, frame))
            } catch (failure: java.io.IOException) {
                reportStorageFailure(failure)
            }
        }

        override fun onConnected(generation: HostGeneration) {
            this@ConnectionManager.generation = generation
            backgroundRestoreActive = false
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
            if (host != null && isTerminalFailure(failure)) {
                intentEpoch++
                persistStopLocked(BackgroundConnectionStop.AUTH)
                backgroundRestoreActive = false
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
        require(validRecoveryHost(config)) { "Invalid host endpoint" }
        val owner = synchronized(lifecycleLock) {
            intentEpoch++
            retireLoopLocked()
            stopServiceLocked()
            backgroundRestoreActive = false
            backgroundIntent.desire(config) // Does not reset a previously exhausted dataSync budget.
            activeHost = config
            _state.value = ConnectionUiState(
                phase = ConnectionPhase.CONNECTING,
                host = config,
                stage = ConnectStage.OpeningStreams,
            )
            publishBackgroundStateLocked()
            // Committed user intent owns its loop independently of the UI coroutine. Publishing
            // CONNECTING and then suspending before start would leave a canceled route half-open.
            startLoopLocked(config, connectionEpoch)
            intentEpoch
        }
        val remember = scope.launch {
            try {
                hostsStore.upsertHost(config) { synchronized(lifecycleLock) { owner == intentEpoch } }
                synchronized(lifecycleLock) {
                    // A live service may have started before this brand-new host was remembered.
                    if (owner == intentEpoch) syncServiceLocked()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("ConnectionManager", "Could not remember desired host; cold restore unavailable", failure)
            }
        }
        // Caller cancellation cannot cancel this manager-owned write or strand the committed loop.
        remember.join()
    }

    fun disconnect() = synchronized(lifecycleLock) {
        intentEpoch++
        retireLoopLocked()
        backgroundRestoreActive = false
        activeHost = null
        _state.value = ConnectionUiState()
        persistStopLocked(BackgroundConnectionStop.USER)
        syncServiceLocked()
    }

    /** Call only from a real Activity foreground lifecycle event, never a worker/service/retry. */
    fun recoverOnForeground() = synchronized(lifecycleLock) {
        backgroundIntent.foreground()
        serviceTimedOut = false
        serviceStartRestricted = false
        syncServiceLocked()
        recoverIfNeeded()
    }

    /**
     * Sticky service/periodic work may restore only a durable desired endpoint and current opt-in.
     * No recent-host guessing, UI auto-connect, Termux command, or authentication bypass is involved.
     * The epoch and journal equality check fence a suspended read against any newer user intent.
     */
    suspend fun restoreBackgroundConnection(serviceAlreadyStarted: Boolean = false): Boolean {
        val ticket = synchronized(lifecycleLock) { BackgroundRestoreTicket(intentEpoch, backgroundIntent.snapshot()) }
        val recovery = hostsStore.backgroundRecoverySnapshot()
        return synchronized(lifecycleLock) {
            if (!ticket.isCurrent(intentEpoch, backgroundIntent.snapshot())) return@synchronized false
            val desired = ticket.intent
            keepInBackground = recovery.enabled
            serviceTimedOut = desired.timedOut
            if (serviceAlreadyStarted) {
                serviceRequested = true
                serviceActive = true
                serviceStartRestricted = false
            }
            val restoredHost = desired.rememberedHost(recovery.enabled, recovery.hosts)
            if (restoredHost == null) {
                syncServiceLocked()
                return@synchronized false
            }
            if (state.value.phase == ConnectionPhase.DISCONNECTED) {
                // No explicit disconnect can reach here: it cleared the durable desired endpoint.
                activeHost = restoredHost
                backgroundRestoreActive = true
                _state.value = ConnectionUiState(
                    phase = ConnectionPhase.CONNECTING, host = restoredHost,
                    stage = ConnectStage.OpeningStreams,
                )
                startLoopLocked(restoredHost, connectionEpoch)
            } else {
                recoverIfNeeded()
            }
            syncServiceLocked()
            true
        }
    }

    fun backgroundServiceTimeLimitReached(): Boolean = synchronized(lifecycleLock) { serviceTimedOut }

    fun onBackgroundServiceStartRestricted() = synchronized(lifecycleLock) {
        serviceRequested = false
        serviceActive = false
        serviceStartRestricted = true
        publishBackgroundStateLocked()
    }

    fun onBackgroundServiceDestroyed() = synchronized(lifecycleLock) {
        serviceRequested = false
        serviceActive = false
        publishBackgroundStateLocked()
        // Do not immediately restart here: Android may have stopped/restricted the service.
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
        // Terminal auth/user stops require an explicit connect/sign-in, never a generic retry.
        if (state.value.phase == ConnectionPhase.DISCONNECTED) return
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
        backgroundRestoreActive = false
        runCatching { backgroundIntent.timeout() }
            .onFailure { Log.e("ConnectionManager", "Could not persist background time limit", it) }
        stopServiceLocked()
        publishBackgroundStateLocked()
        Log.w("ConnectionManager", "Background connection service reached its Android time limit")
    }

    private fun persistStopLocked(reason: BackgroundConnectionStop) {
        runCatching { backgroundIntent.stop(reason) }
            .onFailure { Log.e("ConnectionManager", "Could not persist stopped connection", it) }
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

    private fun isTerminalFailure(failure: GenerationFailure): Boolean {
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
        val wanted = shouldRunConnectionService(keepInBackground, current.phase, current.hasConnected || backgroundRestoreActive, serviceTimedOut)
        if (!wanted) {
            stopServiceLocked()
        } else if (!serviceRequested && !serviceStartRestricted) {
            try {
                backgroundRuntime.startService()
                serviceRequested = true
                serviceStartRestricted = false
            } catch (e: IllegalStateException) {
                serviceStartRestricted = true
                // Android can refuse an FGS start while backgrounded. Retry on the next foreground.
                Log.w("ConnectionManager", "Android refused the background connection service", e)
            } catch (e: SecurityException) {
                serviceStartRestricted = true
                Log.w("ConnectionManager", "Background connection service permission unavailable", e)
            }
        }
        publishBackgroundStateLocked()
    }

    private fun publishBackgroundStateLocked() {
        val delivery = backgroundDeliveryState(keepInBackground, serviceTimedOut,
            state.value.phase, serviceActive, serviceStartRestricted)
        _state.value = _state.value.copy(backgroundDelivery = delivery)
    }

    private fun stopServiceLocked() {
        if (serviceRequested) {
            backgroundRuntime.stopService()
            serviceRequested = false
        }
        serviceActive = false
    }
}

internal fun backgroundDeliveryState(
    enabled: Boolean, timedOut: Boolean, phase: ConnectionPhase, serviceActive: Boolean, restricted: Boolean,
): BackgroundDeliveryState = when {
    !enabled -> BackgroundDeliveryState.DISABLED
    timedOut -> BackgroundDeliveryState.TIME_LIMIT_REACHED
    phase == ConnectionPhase.DISCONNECTED -> BackgroundDeliveryState.DISABLED
    serviceActive -> BackgroundDeliveryState.SERVICE_ACTIVE
    restricted -> BackgroundDeliveryState.START_RESTRICTED
    else -> BackgroundDeliveryState.BEST_EFFORT
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
