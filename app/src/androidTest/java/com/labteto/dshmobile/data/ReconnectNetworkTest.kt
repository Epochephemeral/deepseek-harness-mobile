package com.labteto.dshmobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.connection.*
import com.labteto.dshmobile.di.AppModule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in read-only regression using the production connection lifecycle and an idle session. */
@RunWith(AndroidJUnit4::class)
class ReconnectNetworkTest {
    @Test fun repeatedReconnectsRestoreHistoryWithoutPublishingCarrierErrors() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val baseUrl = args.getString("historyBaseUrl")
        val sid = args.getString("historySessionId")
        assumeTrue("provide an endpoint and idle session", baseUrl != null && sid != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dataStore = AppModule.provideDataStore(context)
        val credentials = RelayCredentialStore(dataStore)
        val hosts = HostsStore(dataStore, credentials, context)
        val host = hosts.hosts.first().firstOrNull { it.baseUrl == baseUrl }
        assumeTrue("pair this debug app first", host != null)
        val http = AppModule.provideOkHttpClient()
        val manager = ConnectionManager(context,
            HarnessClientFactory(http, credentials, HarnessSessionStore(dataStore, http)), hosts)
        var observer: Job? = null
        try {
            manager.connect(host!!)
            withTimeout(60_000) { manager.state.first { it.phase == ConnectionPhase.CONNECTED } }
            val store = SessionStore(context, manager, hosts, HistoryCache(File(context.cacheDir, "session-history")))
            val errors = mutableListOf<String>()
            observer = launch(start = CoroutineStart.UNDISPATCHED) {
                store.connectionError.filterNotNull().collect { errors += it }
            }
            store.openSession(sid!!)
            val initial = withTimeout(60_000) {
                store.currentConversation.filterNotNull().first { it.sessionId == sid && !it.fromCache }
            }
            repeat(3) {
                val previous = store.currentConversation.value
                val generation = manager.generation
                val reconnecting = async(start = CoroutineStart.UNDISPATCHED) {
                    manager.state.first { it.phase == ConnectionPhase.RECONNECTING }
                }
                manager.reconnectIfNeeded()
                withTimeout(60_000) { reconnecting.await() }
                withTimeout(60_000) {
                    manager.state.first { it.phase == ConnectionPhase.CONNECTED && manager.generation !== generation }
                }
                val restored = withTimeout(60_000) {
                    store.currentConversation.filterNotNull().first {
                        it !== previous && it.sessionId == sid && !it.fromCache
                    }
                }
                assertTrue("idle history must survive reconnect intact", initial.journal == restored.journal)
                // Let the host's remaining catalog reads settle before the next user-like retry.
                delay(500)
                assertTrue("normal recovery must not publish a session error", errors.isEmpty())
            }
            println("RECONNECT_RECOVERY cycles=3 errors=${errors.size} events=${initial.journal.size}")
        } finally {
            observer?.cancelAndJoin()
            manager.disconnect()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }
}
