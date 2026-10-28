package com.labteto.dshmobile.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.connection.*
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.storage.DiskCacheStore
import com.labteto.dshmobile.di.AppModule
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Public Store/reader integration. Reflection is confined to connection/lifecycle test harness setup. */
class SessionHistoryStoreTest {
    @Test fun publicReaderFacadeKeepsLoadedHistoryAcrossReopenAndClearsItOnHostChange() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "session-history-test-${UUID.randomUUID()}").apply { mkdirs() }
        val dataJob = SupervisorJob()
        val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + dataJob),
            produceFile = { File(directory, "test.preferences_pb") })
        val http = AppModule.provideOkHttpClient()
        val credentials = RelayCredentialStore(data)
        val hosts = HostsStore(data, credentials, context)
        val offlineContext = object : android.content.ContextWrapper(context) {
            override fun getSystemService(name: String): Any? =
                if (name == android.content.Context.CONNECTIVITY_SERVICE) null else super.getSystemService(name)
        }
        val disk = DiskCacheStore(File(directory, "managed"))
        val manager = ConnectionManager(context, HarnessClientFactory(http, credentials, HarnessSessionStore(data, http), disk), hosts,
            networkMonitor = ConnectionNetworkMonitor(offlineContext),
            backgroundIntent = BackgroundConnectionPersistence(File(directory, "background.json")),
            backgroundRuntime = object : ConnectionBackgroundRuntime(context) {
                override fun updateWork(enabled: Boolean) = Unit
                override fun startService() = Unit
                override fun stopService() = Unit
            })
        @Suppress("UNCHECKED_CAST")
        val states = ConnectionManager::class.java.getDeclaredField("_state").apply { isAccessible = true }
            .get(manager) as MutableStateFlow<ConnectionUiState>
        val host = HostConfig("history-initial", "Fixture", "initial.invalid", 3080)
        states.value = ConnectionUiState(host = host)
        val cache = HistoryCache(File(disk.root, "preview"), diskStore = disk)
        val store = SessionStore(context, manager, hosts, cache)
        val hostKey = "${host.baseUrl}|${host.id}"
        val archive = "reader-v1\u0000fixture"
        fun event(seq: Long): SessionEventEnvelope = when (seq % 64) {
            0L -> SessionEventEnvelope("turn/start", seq, seq, buildJsonObject { put("turn", seq / 64) })
            1L -> SessionEventEnvelope("user/message", seq, seq, buildJsonObject {
                put("source", buildJsonObject { put("kind", "user") })
                put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "prompt $seq") }) })
            })
            63L -> SessionEventEnvelope("turn/end", seq, seq, buildJsonObject { put("turn", seq / 64) })
            else -> SessionEventEnvelope("reader/skip", seq, seq, JsonNull)
        }
        try {
            cache.storeEvents(hostKey, archive, cache.ticket(), (0L..1023L).map(::event))
            store.openSession("fixture")
            val initial = withTimeout(5_000) {
                store.currentConversation.filterNotNull().first { it.loadedThroughSeq == 1023L }
            }
            assertEquals(896L, initial.loadedFromSeq)
            assertTrue(initial.fromCache)
            assertTrue(initial.journal.isEmpty())
            store.updateHistoryViewport(961, false)
            val bookmark = requireNotNull(store.captureHistoryViewport())
            store.loadOlder()
            val expanded = withTimeout(5_000) {
                store.currentConversation.filterNotNull().first { it.loadedFromSeq == 832L }
            }
            assertEquals(1023L, expanded.loadedThroughSeq)
            assertTrue(expanded.nodes.containsAll(initial.nodes))
            assertFalse(expanded.hasNewer)
            assertEquals(192, store.loadHistoryJournal().size)
            assertTrue(store.currentConversation.value!!.journal.isEmpty())

            store.openSession("fixture")
            assertTrue(store.restoreHistoryViewport(bookmark))
            assertEquals(832L, store.currentConversation.value!!.loadedFromSeq)
            assertEquals(961L, store.captureHistoryViewport()?.anchorSeq)
            assertTrue(store.showLatestHistory())
            assertEquals(832L, store.currentConversation.value!!.loadedFromSeq)
            cache.clear()
            assertTrue(store.restoreHistoryViewport(bookmark))
            assertEquals(expanded.nodes, store.currentConversation.value!!.nodes)
            assertTrue(runCatching { store.loadHistoryJournal() }.isFailure)

            states.value = ConnectionUiState(host = host.copy(id = "other-host", host = "other.invalid"))
            withTimeout(5_000) {
                combine(store.currentSessionId, store.currentConversation) { selected, conversation ->
                    selected == null && conversation == null
                }.first { it }
            }
            assertNull(store.currentConversation.value)
            assertNull(store.captureHistoryViewport())
            assertFalse(store.restoreHistoryViewport(bookmark))
        } finally {
            (SessionStore::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(store) as CoroutineScope)
                .coroutineContext[Job]?.cancelAndJoin()
            (ConnectionManager::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(manager) as CoroutineScope)
                .coroutineContext[Job]?.cancelAndJoin()
            dataJob.cancelAndJoin()
            cache.close()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }
}
