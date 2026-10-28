package com.labteto.dshmobile.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.connection.*
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.di.AppModule
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.serialization.json.*
import com.labteto.dshmobile.core.storage.DiskCacheStore
import org.junit.Assert.*
import org.junit.Test

/** Drives the real store without a remote host, credentials, or the app's saved preferences. */
class SessionHistoryStoreTest {
    @Test fun longSessionUsesDiskViewportWhileAllTurnsAndLiveUpdatesRemainAccessible() = runBlocking<Unit> {
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
        val disk = DiskCacheStore(File(directory,"managed"))
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
            .get(manager) as kotlinx.coroutines.flow.MutableStateFlow<ConnectionUiState>
        val initialHost = HostConfig("history-initial", "Fixture", "initial.invalid", 3080)
        states.value = ConnectionUiState(host = initialHost)
        val cache = HistoryCache(File(disk.root,"preview"),diskStore=disk)
        val store = SessionStore(context, manager, hosts, cache)
        fun field(name: String) = SessionStore::class.java.getDeclaredField(name).apply { isAccessible = true }
        val event = SessionStore::class.java.getDeclaredMethod("handleSessionEvent", String::class.java, SessionEventEnvelope::class.java)
            .apply { isAccessible = true }
        val rebuild = SessionStore::class.java.getDeclaredMethod("rebuildCurrentLocked").apply { isAccessible = true }
        val lock = requireNotNull(field("lock").get(store))
        val hostKey="${initialHost.baseUrl}|${initialHost.id}"
        fun visible(): List<SessionEventEnvelope> = synchronized(lock) {
            @Suppress("UNCHECKED_CAST")
            (field("currentEvents").get(store) as List<SessionEventEnvelope>).toList()
        }
        fun fixtureEvent(seq: Long): SessionEventEnvelope {
            val turn=seq/20
            val type=when (seq%20) { 0L -> "user/message"; 19L -> "assistant/message"; else -> "tool/result" }
            val message=buildJsonObject {
                put("id","message-$seq")
                put("role",when(type) { "user/message" -> "user"; "assistant/message" -> "assistant"; else -> "tool" })
                put("source",buildJsonObject { put("kind",when(type) { "user/message" -> "user"; "assistant/message" -> "model"; else -> "tool" }); put("callId","call-$seq") })
                if(type=="tool/result") put("toolCallId","call-$seq")
                put("content",buildJsonArray { add(buildJsonObject { put("type","text"); put("text","fixture $seq " + "x".repeat(256)) }) })
            }
            val data=if(type=="user/message") message else buildJsonObject { put("turn",turn); put("step",1); put("message",message) }
            return SessionEventEnvelope(type,seq,seq,data)
        }
        fun appendTurns(first: Int,count: Int) = synchronized(lock) {
            for (seq in first*20L until (first+count)*20L) event.invoke(store,"fixture",fixtureEvent(seq))
            rebuild.invoke(store)
        }
        val seenUsers=HashSet<Long>()
        fun rememberUsers() { visible().filter { it.type=="user/message" }.forEach { seenUsers += it.seq } }
        try {
            synchronized(lock) {
                field("currentId").set(store,"fixture")
                field("historyHost").set(store,hostKey)
                field("followCursor").set(store,0)
                @Suppress("UNCHECKED_CAST")
                (field("_currentSessionId").get(store) as kotlinx.coroutines.flow.MutableStateFlow<String?>).value="fixture"
            }
            appendTurns(0,550)
            assertEquals(10_999L,cache.latestHistory(hostKey,"fixture").lastSeq)
            assertEquals(0L,cache.historyAround(hostKey,"fixture",0).firstSeq)
            assertTrue("RAM holds a viewport, not the full archive",visible().size<=120*20)
            assertEquals(10_999L,visible().last().seq)
            synchronized(lock) {
                val cachedFold=field("foldedHistory").get(store)
                rebuild.invoke(store)
                assertSame("unchanged durable history must reuse its fold",cachedFold,field("foldedHistory").get(store))
            }
            rememberUsers()
            var pages=0
            while (visible().first().seq>0) {
                val before=visible().first().seq
                store.loadOlder()
                assertTrue("older paging must advance",visible().first().seq<before)
                rememberUsers()
                assertTrue("paging must terminate",++pages<30)
            }
            val oldWindow=visible()
            assertTrue(field("currentHasNewer").getBoolean(store))
            store.updateHistoryViewport(oldWindow.first().seq,false)
            appendTurns(550,10)
            assertEquals("live arrival must not move or append to an old reading viewport",oldWindow,visible())
            assertEquals(11_199L,cache.latestHistory(hostKey,"fixture").lastSeq)
            pages=0
            while (field("currentHasNewer").getBoolean(store)) {
                val before=visible().last().seq
                store.loadNewer()
                assertTrue("newer paging must advance",visible().last().seq>before)
                rememberUsers()
                assertTrue("paging must terminate",++pages<30)
            }
            assertEquals(560,seenUsers.size)
            assertEquals(11_199L,visible().last().seq)
            assertTrue(visible().size<=120*20)
            store.updateHistoryViewport(visible().last().seq,true)
            synchronized(lock) {
                val cachedFold=field("foldedHistory").get(store)
                appendTurns(560,1)
                assertNotSame("new durable data invalidates the fold cache",cachedFold,field("foldedHistory").get(store))
            }
            // The production opening path restores the indexed archive as preview, before a live
            // baseline. It does not fabricate running tools/questions or depend on a remote host.
            synchronized(lock) {
                SessionStore::class.java.getDeclaredMethod("startFollow",String::class.java).apply { isAccessible=true }.invoke(store,"fixture")
            }
            val preview=withTimeout(5_000) { store.currentConversation.filterNotNull().first { it.fromCache } }
            assertEquals(11_219L,preview.lastSeq)
            assertFalse(preview.running)
            assertTrue(preview.queue.isEmpty())
            // A new empty/hidden-only host has no landing transcript to replace the old one.
            // The host transition itself must clear every actionable selection before its list arrives.
            val firstHost = HostConfig("history-cleanup-host-a", "Fixture A", "first.invalid", 3080)
            states.value = ConnectionUiState(host = firstHost)
            withTimeout(5_000) { store.currentConversation.first { it == null } }
            synchronized(lock) {
                field("currentId").set(store, "old-session")
                field("historyHost").set(store, "${firstHost.baseUrl}|${firstHost.id}")
                field("followCursor").set(store, 0)
                @Suppress("UNCHECKED_CAST")
                (field("_currentSessionId").get(store) as kotlinx.coroutines.flow.MutableStateFlow<String?>).value = "old-session"
                event.invoke(store, "old-session", SessionEventEnvelope("fixture", 10L, 0, JsonObject(emptyMap())))
                rebuild.invoke(store)
            }
            assertNotNull(store.currentConversation.value)
            states.value = ConnectionUiState(host = firstHost.copy(id = "empty-host-b", host = "empty.invalid"))
            withTimeout(5_000) { store.currentSessionId.first { it == null } }
            synchronized(lock) {
                assertNull(store.currentConversation.value)
                assertNull(field("followCursor").get(store))
                assertTrue((field("currentEvents").get(store) as List<*>).isEmpty())
            }
        } finally {
            (field("scope").get(store) as CoroutineScope).coroutineContext[Job]?.cancelAndJoin()
            val managerScope = ConnectionManager::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(manager) as CoroutineScope
            managerScope.coroutineContext[Job]?.cancelAndJoin()
            dataJob.cancelAndJoin()
            cache.close()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
            directory.deleteRecursively()
        }
    }
}
