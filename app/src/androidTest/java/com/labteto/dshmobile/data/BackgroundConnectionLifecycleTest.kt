package com.labteto.dshmobile.data

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.work.NetworkType
import androidx.work.WorkManager
import com.labteto.dshmobile.MainActivity
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.ConnectionService
import com.labteto.dshmobile.connection.KeepAliveWorker
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Opt-in, no remote writes. Restores the user's background setting and connection in finally. */
@RunWith(AndroidJUnit4::class)
class BackgroundConnectionLifecycleTest {
    @Suppress("DEPRECATION")
    @Test fun backgroundServiceAndAutomaticRecoveryRespectConnectionIntent() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pairedAppRecovery") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val entry = EntryPointAccessors.fromApplication(context, KeepAliveWorker.WorkerEntryPoint::class.java)
        val manager = entry.connectionManager()
        val hosts = entry.hostsStore()
        val originalBackground = hosts.settingsOnce().keepConnectedInBackground
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        fun report(message: String) {
            instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "\n$message\n") })
        }
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        val power = context.getSystemService(android.os.PowerManager::class.java)
        report("BACKGROUND_LIFECYCLE waiting_for_unlocked_interactive_device")
        withTimeout(180_000) {
            while (keyguard.isKeyguardLocked || !power.isInteractive) delay(100)
        }
        val screenHold = power.newWakeLock(android.os.PowerManager.SCREEN_DIM_WAKE_LOCK, "dsh:connection-lifecycle-test")
        screenHold.acquire(180_000)
        report("BACKGROUND_LIFECYCLE unlocked_display_held=true")
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val workManager = WorkManager.getInstance(context)
        fun serviceForeground() = activityManager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == ConnectionService::class.java.name && it.foreground
        }
        suspend fun awaitCondition(check: () -> Boolean) = withTimeout(15_000) {
            while (!check()) delay(100)
        }
        suspend fun awaitWork(enabled: Boolean) = awaitCondition {
            val active = workManager.getWorkInfosForUniqueWork(KeepAliveWorker.WORK_NAME)
                .get(3, TimeUnit.SECONDS).filterNot { it.state.isFinished }
            if (enabled) active.size == 1 && active.single().constraints.requiredNetworkType == NetworkType.CONNECTED
            else active.isEmpty()
        }
        suspend fun foreground() {
            // Use the shell's explicit launch path: OEM background-launch policy may reject an
            // instrumentation process calling Context.startActivity before it has a visible task.
            val launch = instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n ${context.packageName}/${MainActivity::class.java.name}",
            )
            android.os.ParcelFileDescriptor.AutoCloseInputStream(launch).use { input ->
                val output = input.bufferedReader().readText()
                assertTrue("Activity launch failed: $output", output.contains("Status: ok"))
            }
            awaitCondition {
                var resumed = false
                instrumentation.runOnMainSync {
                    resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .any { it is MainActivity }
                }
                resumed
            }
        }
        suspend fun background() = awaitCondition {
            var moved = false
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull { it is MainActivity }?.let {
                        it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        moved = it.moveTaskToBack(true)
                    }
            }
            moved
        }
        report("BACKGROUND_LIFECYCLE requesting_foreground")
        foreground()
        report("BACKGROUND_LIFECYCLE activity_resumed=true")
        val ready = withTimeout(30_000) { manager.state.first { it.phase == ConnectionPhase.CONNECTED } }
        val originalHost = ready.host!!
        report("BACKGROUND_LIFECYCLE connected=true")
        try {
            repeat(3) { manager.recoverIfNeeded(); manager.recoverOnForeground() }
            assertEquals("healthy recovery must preserve client identity", ready.clientId, manager.state.value.clientId)
            val store = EntryPointAccessors.fromApplication(context,
                com.labteto.dshmobile.ui.SessionStoreEntryPoint::class.java).sessionStore()
            val initial = withTimeout(30_000) { store.currentConversation.first { it != null && !it.fromCache } }!!
            manager.generation!!.mux.close()
            manager.recoverOnForeground()
            withTimeout(30_000) {
                manager.state.first { it.phase == ConnectionPhase.CONNECTED && it.clientId != ready.clientId }
                store.currentConversation.first {
                    it != null && it !== initial && !it.fromCache && it.sessionId == initial.sessionId
                }
            }
            assertNull(store.connectionError.value)
            report("BACKGROUND_LIFECYCLE healthy_generation_preserved=true carrier_loss_recovered=true history_restored=true")

            hosts.setSetting { it.copy(keepConnectedInBackground = false) }
            awaitCondition { !serviceForeground() }
            awaitWork(false)
            hosts.setSetting { it.copy(keepConnectedInBackground = true) }
            awaitCondition { serviceForeground() }
            awaitWork(true)
            hosts.setSetting { it.copy(keepConnectedInBackground = false) }
            awaitCondition { !serviceForeground() }
            awaitWork(false)
            hosts.setSetting { it.copy(keepConnectedInBackground = true) }
            awaitCondition { serviceForeground() }
            awaitWork(true)
            report("BACKGROUND_LIFECYCLE setting_immediate=true service_foreground=true work_constraint=CONNECTED")

            background()
            val backgroundClient = manager.state.value.clientId
            val observedUntil = android.os.SystemClock.elapsedRealtime() + 45_000
            while (android.os.SystemClock.elapsedRealtime() < observedUntil) {
                assertEquals(ConnectionPhase.CONNECTED, manager.state.value.phase)
                assertEquals(backgroundClient, manager.state.value.clientId)
                assertTrue("opt-in service must remain foreground", serviceForeground())
                delay(500)
            }
            report("BACKGROUND_LIFECYCLE background_observed_ms=45000 healthy_generation_preserved=true")

            // Exercise the production timeout callback without changing device-wide Android budgets.
            instrumentation.runOnMainSync {
                ConnectionService().also { it.connectionManager = manager }
                    .onTimeout(0, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
            awaitCondition { !serviceForeground() }
            val beforeReconnect = manager.state.value.clientId
            manager.reconnectIfNeeded()
            withTimeout(30_000) {
                manager.state.first { it.phase == ConnectionPhase.CONNECTED && it.clientId != beforeReconnect }
            }
            hosts.setSetting { it.copy(keepConnectedInBackground = false) }
            awaitWork(false)
            hosts.setSetting { it.copy(keepConnectedInBackground = true) }
            awaitWork(true)
            assertFalse("reconnect and setting toggle must not reset timeout", serviceForeground())
            foreground()
            awaitCondition { serviceForeground() }
            report("BACKGROUND_LIFECYCLE timeout_callback_injected=true restart_blocked_until_foreground=true")

            background()
            manager.disconnect()
            repeat(3) { manager.recoverIfNeeded() }
            delay(300)
            assertEquals(ConnectionPhase.DISCONNECTED, manager.state.value.phase)
            awaitCondition { !serviceForeground() }
            report("BACKGROUND_LIFECYCLE explicit_disconnect_preserved=true")
        } finally {
            try {
                // Avoid an FGS start from the background while restoring the paired target.
                hosts.setSetting { it.copy(keepConnectedInBackground = false) }
                if (manager.state.value.phase == ConnectionPhase.DISCONNECTED) manager.connect(originalHost)
                foreground()
                withTimeout(30_000) { manager.state.first { it.phase == ConnectionPhase.CONNECTED } }
            } finally {
                hosts.setSetting { it.copy(keepConnectedInBackground = originalBackground) }
                if (screenHold.isHeld) screenHold.release()
            }
            awaitCondition { serviceForeground() == originalBackground }
            awaitWork(originalBackground)
            report("BACKGROUND_LIFECYCLE original_setting_restored=true connection_restored=true")
        }
    }
}
