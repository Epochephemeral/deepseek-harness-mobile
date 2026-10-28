package com.labteto.dshmobile.connection

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.labteto.dshmobile.MainActivity
import com.labteto.dshmobile.R
import com.labteto.dshmobile.notify.DshNotifications
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.util.Log
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Foreground service that keeps the WebSocket connection alive while the app
 * is backgrounded (user opt-in via settings). The ConnectionManager owns the
 * actual connection; this service only pins the process with an ongoing
 * notification while it is running.
 */
@AndroidEntryPoint
class ConnectionService : Service() {

    @Inject lateinit var connectionManager: ConnectionManager
    @Inject lateinit var notifications: DshNotifications

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var restoreJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            connectionManager.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        // The tiny journal is loaded by the manager's constructor. A persisted exhausted budget
        // must not attempt startForeground again even if we died between onTimeout and stopSelf.
        if (connectionManager.backgroundServiceTimeLimitReached()) {
            stopSelf()
            return START_NOT_STICKY
        }
        // This also applies to a null sticky-restart Intent: never wait for DataStore before FGS.
        notifications.ensureChannels()
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (failure: IllegalStateException) {
            connectionManager.onBackgroundServiceStartRestricted()
            stopSelf()
            return START_NOT_STICKY
        } catch (failure: SecurityException) {
            connectionManager.onBackgroundServiceStartRestricted()
            stopSelf()
            return START_NOT_STICKY
        }
        restoreJob?.cancel()
        restoreJob = scope.launch {
            try {
                if (!connectionManager.restoreBackgroundConnection(serviceAlreadyStarted = true)) {
                    stopSelfResult(startId)
                } else {
                    // Keep a cold restore/reconnect notification honest as readiness changes.
                    connectionManager.state.map { it.phase to it.host }.distinctUntilChanged().collect {
                        getSystemService(android.app.NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification())
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("ConnectionService", "Background recovery unavailable", failure)
                stopSelfResult(startId)
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 limits background dataSync time; only a foreground return may re-enable it.
        try {
            connectionManager.onBackgroundServiceTimeout()
        } finally {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // The service dying does not tear the connection down; the manager owns it.
        scope.cancel()
        connectionManager.onBackgroundServiceDestroyed()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val state = connectionManager.state.value
        val host = state.host
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (state.phase == ConnectionPhase.CONNECTED && host != null) {
            getString(R.string.notif_connected_text, host.authority)
        } else getString(R.string.common_reconnecting)
        return NotificationCompat.Builder(this, DshNotifications.CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification_whale)
            .setContentTitle(getString(if (state.phase == ConnectionPhase.CONNECTED) R.string.notif_connected_title else R.string.notif_channel_connection))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.labteto.dshmobile.connection.STOP"
        private const val NOTIFICATION_ID = 10
    }
}
