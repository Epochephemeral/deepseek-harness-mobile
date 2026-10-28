package com.labteto.dshmobile.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.ConnectionUiState
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.notify.NotificationDestination
import com.labteto.dshmobile.notify.NotificationRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.labteto.dshmobile.termux.HarnessControl
import com.labteto.dshmobile.termux.TermuxHarnessController
import com.labteto.dshmobile.termux.TermuxScripts
import com.labteto.dshmobile.update.AvailableUpdate
import com.labteto.dshmobile.update.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    private val hostsStore: HostsStore,
    private val connectionManager: ConnectionManager,
    private val sessionStore: SessionStore,
    private val savedStateHandle: SavedStateHandle,
    private val updateChecker: UpdateChecker,
    private val termux: TermuxHarnessController,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = hostsStore.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings(),
    )

    val connectionState: StateFlow<ConnectionUiState> = connectionManager.state

    /** A newer release to offer, or null. See [UpdateChecker]. */
    val availableUpdate: StateFlow<AvailableUpdate?> = updateChecker.available

    private val _routingNotification = MutableStateFlow(savedStateHandle.get<String>(NOTIFICATION_ROUTE) != null)
    val routingNotification = _routingNotification.asStateFlow()
    private val _notificationRouteRevision = MutableStateFlow(0)
    val notificationRouteRevision = _notificationRouteRevision.asStateFlow()

    private val notificationRouter = NotificationRouter(
        savedHosts = { hostsStore.hosts.first() },
        connect = { host ->
            val state = connectionManager.state.value
            val current = state.host
            if (current == null || current.id != host.id || current.baseUrl != host.baseUrl || state.phase == ConnectionPhase.DISCONNECTED) {
                connectionManager.connect(host)
            }
        },
        openWhenReady = { destination ->
            val ready = connectionManager.state.first { state ->
                !destination.host.matches(state.host) || state.phase == ConnectionPhase.CONNECTED
            }
            destination.host.matches(ready.host) && sessionStore.openNotificationSession(
                destination.host.id, destination.host.baseUrl, destination.sessionId,
            )
        },
    )

    init {
        viewModelScope.launch {
            savedStateHandle.getStateFlow<String?>(NOTIFICATION_ROUTE, null).collectLatest { raw ->
                val destination = NotificationDestination.parse(raw)
                if (destination == null) {
                    _routingNotification.value = false
                    return@collectLatest
                }
                _routingNotification.value = true
                _notificationRouteRevision.value++
                try {
                    // Bound a stale/offline tap; a later tap can retry without leaving a permanent modal.
                    withTimeoutOrNull(60_000) { notificationRouter.route(destination) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    android.util.Log.w("NotificationRouter", "Could not open saved notification destination", failure)
                } finally {
                    if (savedStateHandle.get<String>(NOTIFICATION_ROUTE) == raw) {
                        savedStateHandle[NOTIFICATION_ROUTE] = null
                        _routingNotification.value = false
                    }
                }
            }
        }
    }

    fun openNotification(destination: NotificationDestination) {
        // Set before Compose mounts ConnectScreen, whose ordinary auto-connect must not win this tap.
        _routingNotification.value = true
        savedStateHandle[NOTIFICATION_ROUTE] = destination.uri()
    }

    fun cancelNotificationRoute() {
        savedStateHandle[NOTIFICATION_ROUTE] = null
        _routingNotification.value = false
    }

    fun recoverConnectionOnForeground() = connectionManager.recoverOnForeground()

    private companion object {
        const val NOTIFICATION_ROUTE = "pending_notification_destination"
    }

    /** Safe to call on every resume: the checker rate-limits itself. */
    fun checkForUpdate(currentVersion: String) {
        viewModelScope.launch { updateChecker.checkOnce(currentVersion) }
    }

    fun dismissUpdate(version: String) {
        viewModelScope.launch { updateChecker.dismiss(version) }
    }

    // ---- Install via Termux ---------------------------------------------------------

    val termuxInstalled: Boolean get() = termux.termuxInstalled

    /** The Termux controller's state, so the update dialog can report a failed install. */
    val harnessControl: StateFlow<HarnessControl> = termux.state

    fun hasTermuxPermission(): Boolean = termux.hasPermission()

    /** Download [update]'s APK inside Termux, verify it, and open the package installer. */
    fun installViaTermux(update: AvailableUpdate) {
        val apk = update.apk ?: return
        termux.installUpdate(update.version, apk.url, apk.name, update.checksums?.url)
    }

    /** The same download-and-open as one line, for a shell that reaches the phone over SSH. */
    fun installCommand(update: AvailableUpdate): String? {
        val apk = update.apk ?: return null
        return runCatching { TermuxScripts.installOneLiner(apk.url, apk.name) }.getOrNull()
    }

    fun acknowledgeHarnessControl() = termux.acknowledge()
}
