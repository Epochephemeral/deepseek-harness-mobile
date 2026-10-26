package com.labteto.dshmobile.data

import android.content.Intent
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.MainActivity
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.ui.SessionStoreEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in verification of the actual activity's singleton against its already paired host. */
@RunWith(AndroidJUnit4::class)
class PairedAppRecoveryTest {
    @Test fun actualAppRestoresCurrentSessionAfterCarrierLossAndResume() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pairedAppRecovery") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = EntryPointAccessors.fromApplication(context, SessionStoreEntryPoint::class.java).sessionStore()
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        println("PAIRED_APP_STAGE activity_requested")
        try {
            withTimeout(30_000) { store.connectionState.first { it.phase == ConnectionPhase.CONNECTED } }
            println("PAIRED_APP_STAGE connected")
            val initial = withTimeout(30_000) { store.currentConversation.first { it != null && !it.fromCache } }!!
            println("PAIRED_APP_STAGE history_loaded")
            val clientId = store.connectionState.value.clientId
            assertNotNull(clientId)
            withTimeout(30_000) {
                var backgrounded = false
                while (!backgrounded) {
                    instrumentation.runOnMainSync {
                        val activity = ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(Stage.RESUMED).firstOrNull { it is MainActivity }
                        if (activity != null) {
                            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            backgrounded = activity.moveTaskToBack(true)
                        }
                    }
                    if (!backgrounded) delay(100)
                }
            }
            delay(300)
            val beforeLoss = store.currentConversation.value
            store.muxForHost(store.activeHostKey)!!.close()
            context.startActivity(intent)
            withTimeout(30_000) {
                store.connectionState.first { it.phase == ConnectionPhase.CONNECTED && it.clientId != clientId }
            }
            val restored = withTimeout(30_000) {
                store.currentConversation.first {
                    it != null && it !== beforeLoss && !it.fromCache && it.sessionId == initial.sessionId
                }
            }!!
            assertNull(store.connectionError.value)
            assertTrue(restored.journal.isNotEmpty())
            println("PAIRED_APP_RECOVERY actual_singleton=true fresh_connection=true selected_session_preserved=true history_events=${restored.journal.size} error=false")
        } finally { context.startActivity(intent) }
    }
}
