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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only: replaces a ready connection while its previous initialization is still stalled. */
@RunWith(AndroidJUnit4::class)
class WakeRecoveryNetworkTest {
    @Test fun foregroundRecoveryPreservesHealthyConnectionsAndRespectsDisconnect() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val baseUrl = args.getString("historyBaseUrl")
        assumeTrue("provide a paired endpoint", baseUrl != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dataStore = AppModule.provideDataStore(context)
        val credentials = RelayCredentialStore(dataStore)
        val hosts = HostsStore(dataStore, credentials, context)
        val host = hosts.hosts.first().firstOrNull { it.baseUrl == baseUrl }
        assumeTrue("pair this debug app first", host != null)
        val http = AppModule.provideOkHttpClient()
        val manager = ConnectionManager(context,
            HarnessClientFactory(http, credentials, HarnessSessionStore(dataStore, http)), hosts)
        try {
            manager.connect(host!!)
            val initial = withTimeout(15_000) { manager.state.first { it.phase == ConnectionPhase.CONNECTED } }
            manager.recoverOnForeground()
            delay(200)
            assertEquals(initial.clientId, manager.state.value.clientId)
            manager.generation!!.mux.close()
            manager.recoverOnForeground()
            withTimeout(15_000) {
                manager.state.first { it.phase == ConnectionPhase.CONNECTED && it.clientId != initial.clientId }
            }
            manager.disconnect()
            manager.recoverOnForeground()
            delay(200)
            assertEquals(ConnectionPhase.DISCONNECTED, manager.state.value.phase)
            println("WAKE_FOREGROUND healthy_preserved=true closed_carrier_recovered=true disconnect_preserved=true")
        } finally {
            manager.disconnect()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }

    @Test fun aNewGenerationRestoresHistoryWithoutWaitingForRetiredInitialization() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val baseUrl = args.getString("historyBaseUrl")
        val sid = args.getString("historySessionId")
        assumeTrue("provide a paired endpoint and idle session", baseUrl != null && sid != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dataStore = AppModule.provideDataStore(context)
        val credentials = RelayCredentialStore(dataStore)
        val hosts = HostsStore(dataStore, credentials, context)
        val host = hosts.hosts.first().firstOrNull { it.baseUrl == baseUrl }
        assumeTrue("pair this debug app first", host != null)
        hosts.setLastSessionId(host!!.baseUrl, sid!!)
        val blocked = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val http = AppModule.provideOkHttpClient().newBuilder().addInterceptor { chain ->
            if (chain.request().url.encodedPath.endsWith("/session/list") && first.compareAndSet(true, false)) {
                blocked.complete(Unit)
                check(release.await(20, TimeUnit.SECONDS)) { "initialization test gate timed out" }
            }
            chain.proceed(chain.request())
        }.build()
        val manager = ConnectionManager(context,
            HarnessClientFactory(http, credentials, HarnessSessionStore(dataStore, http)), hosts)
        val store = SessionStore(context, manager, hosts, HistoryCache(File(context.cacheDir, "session-history")))
        try {
            manager.connect(host)
            withTimeout(15_000) { blocked.await() }
            val previous = manager.state.value.clientId
            assertNotNull(previous)
            manager.reconnectIfNeeded()
            withTimeout(15_000) {
                manager.state.first { it.phase == ConnectionPhase.CONNECTED && it.clientId != previous }
            }
            val restored = withTimeout(10_000) {
                store.currentConversation.filterNotNull().first { it.sessionId == sid && !it.fromCache }
            }
            assertEquals("the old initialization must still be blocked", 1L, release.count)
            assertNull(store.connectionError.value)
            println("WAKE_RECOVERY replaced_initialization=true events=${restored.journal.size}")
        } finally {
            release.countDown()
            manager.disconnect()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }
}
