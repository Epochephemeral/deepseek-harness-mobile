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

/** Opt-in: observes an idle real session using the production SessionStore and saved login. */
@RunWith(AndroidJUnit4::class)
class HistoryCacheNetworkTest {
    @Test fun cachedPreviewIsReplacedByAuthoritativeHistoryAndCanBeCleared() = runBlocking {
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
        val cache = HistoryCache(File(context.cacheDir, "session-history"))
        try {
            manager.connect(host!!)
            withTimeout(60_000) { manager.state.first { it.phase == ConnectionPhase.CONNECTED } }
            cache.clear()

            data class Reading(val firstMs: Long, val syncedMs: Long, val preview: Boolean,
                val journal: List<com.labteto.dshmobile.core.session.SessionEventEnvelope>)
            suspend fun open(): Reading = coroutineScope {
                // Construct after the manager is ready so no automatic landing-session baseline
                // races the explicit open being measured.
                val store = SessionStore(context, manager, hosts, cache)
                val start = System.nanoTime()
                val first = async(start = CoroutineStart.UNDISPATCHED) {
                    val snapshot = store.currentConversation.filterNotNull().first { it.sessionId == sid }
                    Pair((System.nanoTime() - start) / 1_000_000, snapshot.fromCache)
                }
                val final = async(start = CoroutineStart.UNDISPATCHED) {
                    store.currentConversation.filterNotNull().first { !it.fromCache && it.sessionId == sid }
                }
                val opening = async { store.openSession(sid!!) }
                val firstContent = withTimeout(60_000) { first.await() }
                val authoritative = withTimeout(60_000) { final.await() }
                val syncedMs = (System.nanoTime() - start) / 1_000_000
                opening.await()
                Reading(firstContent.first, syncedMs, firstContent.second, authoritative.journal)
            }

            val cold = open()
            withTimeout(10_000) { cache.usage.filterNotNull().first { it.bytes > 0 } }
            val warm = open()
            assertTrue("idle history must be identical after sync", cold.journal == warm.journal)
            assertFalse("a cold read must not present cached history", cold.preview)
            assertTrue("first warm content must be a preview, not the network result", warm.preview)
            assertTrue("cached content should arrive before network sync", warm.firstMs < warm.syncedMs)
            val cached = cache.read("${host.baseUrl}|${host.id}", sid!!, cache.ticket())!!
            assertTrue("cached journal differs from the authoritative window", cached == warm.journal)
            val bytes = cache.usage.value!!.bytes
            cache.clear()
            delay(500)
            assertEquals(HistoryCacheUsage(), cache.usage.value)
            assertNull(cache.read("${host.baseUrl}|${host.id}", sid, cache.ticket()))
            val afterClear = open()
            assertFalse("clearing must remove the preview", afterClear.preview)
            assertTrue("clearing must preserve access to remote history and login", cold.journal == afterClear.journal)
            println("HISTORY_CACHE cold_first_ms=${cold.firstMs} warm_first_ms=${warm.firstMs} " +
                "warm_synced_ms=${warm.syncedMs} after_clear_first_ms=${afterClear.firstMs} " +
                "cache_bytes=$bytes events=${warm.journal.size}")
        } finally {
            manager.disconnect()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }
}
