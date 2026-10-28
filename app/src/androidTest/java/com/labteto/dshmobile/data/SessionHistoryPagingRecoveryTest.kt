package com.labteto.dshmobile.data

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.connection.*
import com.labteto.dshmobile.core.session.AssistantLiveState
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.storage.DiskCacheStore
import com.labteto.dshmobile.core.wire.*
import com.labteto.dshmobile.core.wire.dto.*
import com.labteto.dshmobile.di.AppModule
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Isolated real-SQLite/real-store checks; no real host, preferences, or network is used. */
class SessionHistoryPagingRecoveryTest {
    private class FollowSocket(client: okhttp3.OkHttpClient, private val sink: WsChannelSink) :
        WsChannel("http://fixture.invalid/api/remote.mux", client, sink) {
        val opened = CompletableDeferred<JsonObject>()
        override fun start() { sink.onOpen() }
        override fun close() = Unit
        override fun send(text: String): Boolean {
            val message = WireJson.parseToJsonElement(text).jsonObject
            if (message["type"]?.jsonPrimitive?.content == "open" &&
                message["endpoint"]?.jsonPrimitive?.content == "session/readerFollow") opened.complete(message)
            return true
        }
        suspend fun emit(frame: SessionFollowFrame, acknowledge: Boolean = true) {
            val value = if (acknowledge && frame is SessionFollowFrame.Snapshot) frame.copy(reader =
                SessionReaderCoverage(1, frame.records.firstOrNull()?.event?.seq ?: 0, frame.cursor,
                    frame.records.firstOrNull()?.event?.seq ?: 0, 100, false, "session/page")) else frame
            val streamId = opened.await().getValue("streamId")
            sink.onMessage(buildJsonObject {
                put("type", "item"); put("streamId", streamId)
                put("value", WireJson.encodeToJsonElement(SessionFollowFrameSerializer, value))
            }.toString())
        }
    }

    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "history-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        val dataJob = SupervisorJob()
        val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + dataJob),
            produceFile = { File(directory, "fixture.preferences_pb") })
        val http = AppModule.provideOkHttpClient()
        val credentials = RelayCredentialStore(data)
        val hosts = HostsStore(data, credentials, context)
        val disk = DiskCacheStore(File(directory, "managed"))
        val manager = ConnectionManager(context,
            HarnessClientFactory(http, credentials, HarnessSessionStore(data, http), disk), hosts,
            networkMonitor = ConnectionNetworkMonitor(object : ContextWrapper(context) {
                override fun getSystemService(name: String): Any? =
                    if (name == Context.CONNECTIVITY_SERVICE) null else super.getSystemService(name)
            }),
            backgroundIntent = BackgroundConnectionPersistence(File(directory, "background.json")),
            backgroundRuntime = object : ConnectionBackgroundRuntime(context) {
                override fun updateWork(enabled: Boolean) = Unit
                override fun startService() = Unit
                override fun stopService() = Unit
            })
        val host = HostConfig("paging-fixture", "Fixture", "fixture.invalid", 3080)
        val key = "${host.baseUrl}|${host.id}"
        val cache = HistoryCache(File(disk.root, "preview"), diskStore = disk)
        val store: SessionStore
        private val muxes = ArrayList<RemoteStreamMux>()
        fun installFollowSocket(): FollowSocket {
            lateinit var socket: FollowSocket
            val mux = RemoteStreamMux(disk) { sink -> FollowSocket(http, sink).also { socket = it } }
            mux.start()
            muxes += mux
            ConnectionManager::class.java.getDeclaredField("generation").apply { isAccessible = true }
                .set(manager, HostGeneration(HostDescription("/fixture"), "fixture-client", mux))
            return socket
        }
        var calls = 0
        var acknowledgePages = true
        val requestedBefore = ArrayList<Int?>()
        var answer: suspend (JsonObject) -> JsonElement = { JsonObject(emptyMap()) }
        val api = DshApiClient(object : RpcTransport {
            override suspend fun post(path: String, body: String): RpcHttpResponse {
                calls++
                val request = WireJson.parseToJsonElement(body).jsonObject
                val args = request.getValue("payload").jsonObject.getValue("args").jsonObject.getValue("request").jsonObject
                requestedBefore += args["beforeSeq"]?.jsonPrimitive?.intOrNull
                val result = answer(args)
                val value = if (path.endsWith("readerPage") && acknowledgePages) {
                    val page = result.jsonObject
                    val rows = page["records"]!!.jsonArray
                    val first = rows.firstOrNull()?.jsonObject?.get("event")?.jsonObject?.get("seq")?.jsonPrimitive?.int ?: 0
                    val end = minOf(args.getValue("throughSeq").jsonPrimitive.int,
                        args["beforeSeq"]?.jsonPrimitive?.int?.minus(1) ?: Int.MAX_VALUE)
                    JsonObject(page + ("reader" to WireJson.encodeToJsonElement(SessionReaderCoverage.serializer(),
                        SessionReaderCoverage(1, first, end, first, 100, false, "session/page"))))
                } else result
                return RpcHttpResponse(200, """{"rpcId":${request["rpcId"]},"result":{"ok":true,"value":$value}}""")
            }
            override suspend fun <T> download(path: String, consume: (String?, String?, InputStream) -> T): T = error("unused")
            override suspend fun upload(path: String, contentType: String, contentLength: Long, body: InputStream,
                onProgress: ((Long) -> Unit)?): RpcHttpResponse = error("unused")
        })
        init {
            @Suppress("UNCHECKED_CAST")
            val state = ConnectionManager::class.java.getDeclaredField("_state").apply { isAccessible = true }
                .get(manager) as MutableStateFlow<ConnectionUiState>
            state.value = ConnectionUiState(host = host)
            ConnectionManager::class.java.getDeclaredField("api").apply { isAccessible = true }.set(manager, api)
            store = SessionStore(context, manager, hosts, cache)
            synchronized(lock()) {
                field("currentId").set(store, "s")
                field("historyHost").set(store, key)
                field("followCursor").set(store, 10_010)
                field("historyCacheTicket").setLong(store, cache.ticket())
                @Suppress("UNCHECKED_CAST")
                (field("_currentSessionId").get(store) as MutableStateFlow<String?>).value = "s"
            }
        }
        fun startFollow() = synchronized(lock()) {
            SessionStore::class.java.getDeclaredMethod("startFollow", String::class.java)
                .apply { isAccessible = true }.invoke(store, "s")
        }
        fun followJob() = field("followJob").get(store) as Job
        val archiveSid: String get() = if (field("readerMode").getBoolean(store)) "reader-v1\u0000s" else "s"
        fun field(name: String) = SessionStore::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun lock(): Any = requireNotNull(field("lock").get(store))
        fun visible(): List<SessionEventEnvelope> = synchronized(lock()) {
            @Suppress("UNCHECKED_CAST")
            (field("currentEvents").get(store) as List<SessionEventEnvelope>).toList()
        }
        fun selectWindow(events: List<SessionEventEnvelope>, newer: Boolean = true, older: Boolean = false) = synchronized(lock()) {
            @Suppress("UNCHECKED_CAST")
            val current = field("currentEvents").get(store) as MutableList<SessionEventEnvelope>
            current.clear(); current.addAll(events)
            field("currentWindowBytes").setLong(store, events.sumOf { encodeHistoryEvent(it).payload.size.toLong() })
            field("currentOversizedWindow").setBoolean(store, false)
            events.firstOrNull()?.seq?.let { first ->
                val previous = field("knownFirstHistorySeq").get(store) as? Long
                field("knownFirstHistorySeq").set(store, previous?.let { minOf(it, first) } ?: first)
            }
            field("currentEventsRevision").setLong(store, field("currentEventsRevision").getLong(store) + 1)
            field("currentHasNewer").setBoolean(store, newer)
            field("currentHasMore").setBoolean(store, older)
            field("latestObservedHistorySeq").setLong(store, maxOf(
                field("latestObservedHistorySeq").getLong(store), events.lastOrNull()?.seq ?: -1L))
            field("historyAtTail").setBoolean(store, false)
            field("historyAnchorSeq").set(store, events.firstOrNull()?.seq)
        }
        fun append(event: SessionEventEnvelope) = synchronized(lock()) {
            SessionStore::class.java.getDeclaredMethod("handleSessionEvent", String::class.java, SessionEventEnvelope::class.java)
                .apply { isAccessible = true }.invoke(store, "s", event)
        }
        fun apply(frame: SessionFollowFrame.Snapshot, ticket: Long) = synchronized(lock()) {
            SessionStore::class.java.getDeclaredMethod("applyFollowSnapshotAtTicket", String::class.java,
                SessionFollowFrame.Snapshot::class.java, Long::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(store, "s", frame, ticket)
        }
        suspend fun close() {
            (field("scope").get(store) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
            (ConnectionManager::class.java.getDeclaredField("scope").apply { isAccessible = true }
                .get(manager) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
            dataJob.cancelAndJoin()
            muxes.forEach { it.close() }
            cache.close()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
            directory.deleteRecursively()
        }
    }

    private fun event(seq: Long) = SessionEventEnvelope("fixture", seq, seq, JsonObject(emptyMap()))
    private fun record(seq: Int) = SessionHistoryRecord.Event(event = SessionWireEvent("fixture", seq, seq.toLong()))
    private fun page(first: Int, last: Int) = buildJsonObject {
        put("records", buildJsonArray { for (seq in first..last) add(encodeToJsonElement(SessionHistoryRecord.serializer(), record(seq))) })
        put("hasMore", first > 0)
    }

    private fun turnEvent(seq: Long) = event(seq).copy(type = when (seq) {
        100L -> "turn/start"
        103L -> "user/message"
        else -> "fixture"
    })

    private fun turnPage(first: Int, last: Int) = buildJsonObject {
        put("records", buildJsonArray {
            for (seq in first..last) {
                val e = turnEvent(seq.toLong())
                add(encodeToJsonElement(SessionHistoryRecord.serializer(), SessionHistoryRecord.Event(
                    event = SessionWireEvent(e.type, seq, seq.toLong(), e.data))))
            }
        })
        put("hasMore", first > 0)
    }

    @Test fun seekFarTurnHydratesIsolatedCachedStartAndKeepsLaterPromptAndPrefix() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val tail = (10_000L..10_010L).map(::event)
            f.cache.storeEvents(f.key, f.archiveSid, f.cache.ticket(), listOf(event(20), turnEvent(100)) + tail)
            f.selectWindow(tail, newer = false, older = true)
            f.field("remoteOldestSeq").set(f.store, 20L)
            f.field("remoteHasOlder").setBoolean(f.store, true)
            f.answer = { request ->
                assertEquals(10_010, request.getValue("throughSeq").jsonPrimitive.int)
                val before = request.getValue("beforeSeq").jsonPrimitive.int
                assertTrue("seek must be near the target, not the tail", before in 104..300)
                turnPage(98, before - 1)
            }
            assertTrue(f.store.seekHistoryTurn(100))
            assertEquals(1, f.calls)
            assertTrue(f.visible().any { it.seq == 98L })
            assertTrue(f.visible().any { it.seq == 100L && it.type == "turn/start" })
            assertTrue(f.visible().any { it.seq == 103L && it.type == "user/message" })
            assertTrue(f.store.currentConversation.value!!.nodes.any { it.seq == 100L })
            assertTrue(f.store.currentConversation.value!!.hasNewer)
            assertEquals(100L, f.field("historyAnchorSeq").get(f.store))
            assertFalse(f.field("historyAtTail").getBoolean(f.store))
            assertTrue(f.store.seekHistoryTurn(100))
            assertEquals("complete indexed window must not refetch", 1, f.calls)
            assertEquals("seek must preserve the older remote boundary", 20L, f.field("remoteOldestSeq").get(f.store))
            assertTrue(f.field("remoteHasOlder").getBoolean(f.store))
        } finally { f.close() }
    }

    @Test fun seekRejectsCacheClearDuringRemoteFetchWithoutMovingViewport() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val tail = (10_000L..10_010L).map(::event)
            f.cache.storeEvents(f.key, f.archiveSid, f.cache.ticket(), tail)
            f.selectWindow(tail, newer = false, older = true)
            f.answer = { request ->
                f.cache.clear()
                turnPage(98, request.getValue("beforeSeq").jsonPrimitive.int - 1)
            }
            assertFalse(f.store.seekHistoryTurn(100))
            assertEquals(tail, f.visible())
            assertTrue(f.cache.latestHistory(f.key, f.archiveSid).events.isEmpty())
            assertFalse(f.store.loadingNewer.value)
        } finally { f.close() }
    }

    @Test fun cancelledSeekAndReopenedSessionCannotPublishOldTarget() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val tail = (10_000L..10_010L).map(::event)
            f.cache.storeEvents(f.key, f.archiveSid, f.cache.ticket(), tail)
            f.selectWindow(tail, newer = false, older = true)
            val entered = CompletableDeferred<Unit>()
            f.answer = { entered.complete(Unit); awaitCancellation() }
            val seek = async { f.store.seekHistoryTurn(100) }
            entered.await()
            seek.cancelAndJoin()
            assertEquals(tail, f.visible())
            assertFalse(f.store.loadingNewer.value)
            f.answer = { request ->
                synchronized(f.lock()) {
                    f.field("historyOpenEpoch").setLong(f.store, f.field("historyOpenEpoch").getLong(f.store) + 1)
                }
                turnPage(98, request.getValue("beforeSeq").jsonPrimitive.int - 1)
            }
            assertFalse(f.store.seekHistoryTurn(100))
            assertEquals(tail, f.visible())
            assertEquals(10_000L, f.cache.latestHistory(f.key, f.archiveSid).firstSeq)
        } finally { f.close() }
    }

    @Test fun seekMissingTurnDoesNotClaimLatestAsSuccess() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val tail = (10_000L..10_010L).map(::event)
            f.cache.storeEvents(f.key, f.archiveSid, f.cache.ticket(), tail)
            f.selectWindow(tail, newer = false, older = true)
            f.answer = { request -> page(98, request.getValue("beforeSeq").jsonPrimitive.int - 1) }
            assertFalse(f.store.seekHistoryTurn(100))
            assertEquals(tail, f.visible())
            assertFalse(f.store.seekHistoryTurn(Long.MAX_VALUE))
            assertEquals(1, f.calls)
        } finally { f.close() }
    }

    @Test fun disjointCacheGapFetchesNearViewportRatherThanFarFuture() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val old = (0L..10L).map(::event)
            f.cache.storeEvents(f.key, f.archiveSid, f.cache.ticket(), old + (10_000L..10_010L).map(::event))
            f.selectWindow(old)
            f.answer = { request ->
                val before = request.getValue("beforeSeq").jsonPrimitive.int
                assertTrue("must read near seq 10, not the far component", before <= 22)
                page(11, before - 1)
            }
            f.store.loadNewer()
            assertEquals(listOf(22), f.requestedBefore)
            assertEquals(1, f.calls)
            assertTrue(f.visible().last().seq > 10)
            assertTrue(f.visible().last().seq < 10_000)
            assertFalse(f.store.loadNewerFailed.value)
            assertFalse(f.store.loadingNewer.value)
        } finally { f.close() }
    }

    @Test fun emptyOrNonProgressingServerPageFailsOnceAndAllowsExplicitRetry() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val old = (0L..10L).map(::event)
            f.cache.storeEvents(f.key, f.archiveSid, f.cache.ticket(), old + (100L..110L).map(::event))
            f.selectWindow(old)
            f.answer = { page(22, 23) }
            f.store.loadNewer()
            assertTrue(f.store.loadNewerFailed.value)
            assertEquals(old, f.visible())
            assertEquals(1, f.calls)
            assertEquals(110L, f.cache.latestHistory(f.key, f.archiveSid).lastSeq)
            f.answer = { page(0, -1) }
            f.store.loadNewer()
            assertTrue(f.store.loadNewerFailed.value)
            assertFalse(f.store.loadingNewer.value)
            assertEquals(2, f.calls)
            assertEquals(old, f.visible())
        } finally { f.close() }
    }

    @Test fun clearBeforeBaselineDoesNotRefillOldPageOrPreviewAndNewLiveOnlyPersists() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val old = (0L..10L).map(::event)
            val ticket = f.cache.ticket()
            f.cache.storeEvents(f.key, f.archiveSid, ticket, old)
            f.selectWindow(old)
            val tiny = SessionFollowFrame.Snapshot(cursor = 20, records = listOf(record(19), record(20)), hasMore = true)
            f.cache.clear()
            f.apply(tiny, ticket)
            assertEquals(0, f.calls)
            assertEquals(old, f.visible())
            assertTrue(f.cache.latestHistory(f.key, f.archiveSid).events.isEmpty())
            val scopeJob = (f.field("scope").get(f.store) as CoroutineScope).coroutineContext[Job]!!
            val existingJobs = scopeJob.children.toSet()
            f.append(event(21))
            assertEquals(ticket, f.field("historyCacheTicket").getLong(f.store))
            (f.field("historySaveJob").get(f.store) as? Job)?.join()
            withTimeout(5_000) { scopeJob.children.filter { it !in existingJobs }.toList().forEach { it.join() } }
            assertEquals(21L, f.cache.latestHistory(f.key, f.archiveSid).firstSeq)
            assertEquals(21L, f.cache.latestHistory(f.key, f.archiveSid).lastSeq)
            assertNull("old full-window preview must keep its invalid ticket", f.cache.read(f.key, f.archiveSid, f.cache.ticket()))
            assertEquals(0, f.calls)
        } finally { f.close() }
    }

    @Test fun clearedSnapshotWithoutRamPaintsTinyBaselineWithoutArchivingIt() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val ticket = f.cache.ticket()
            f.cache.clear()
            f.selectWindow(emptyList())
            val tiny = SessionFollowFrame.Snapshot(cursor = 20, records = listOf(record(19), record(20)), hasMore = true)
            f.apply(tiny, ticket)
            assertEquals(listOf(19L, 20L), f.visible().map { it.seq })
            assertTrue(f.cache.latestHistory(f.key, f.archiveSid).events.isEmpty())
            assertEquals(20, f.field("followCursor").get(f.store))
        } finally { f.close() }
    }

    @Test fun newerPageAfterClearRetainsKnownOlderAffordanceEvenWhenRemoteBeginningWasReached() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val prefix = (0L..119L).map(::event)
            f.cache.storeEvents(f.key, f.archiveSid, f.cache.ticket(), prefix)
            f.selectWindow(prefix, newer = true, older = false)
            f.field("followCursor").set(f.store, 359)
            f.field("latestObservedHistorySeq").setLong(f.store, 359)
            f.field("remoteHasOlder").setBoolean(f.store, false)
            f.cache.clear()
            f.answer = { request ->
                assertEquals(240, request.getValue("beforeSeq").jsonPrimitive.int)
                page(120, 239)
            }

            f.store.loadNewer()
            assertEquals((120L..239L).toList(), f.visible().map { it.seq })
            val snapshot = requireNotNull(f.store.currentConversation.value)
            assertTrue("known seq 0 remains reachable despite no older local DB component", snapshot.hasMore)
            assertTrue("known live tail 359 is not the end of this partial DB component", snapshot.hasNewer)
            assertEquals(0L, f.field("knownFirstHistorySeq").get(f.store))
            assertFalse(f.store.loadNewerFailed.value)
        } finally { f.close() }
    }

    @Test fun olderOnlyComponentAfterClearIsNotLiveTailAndDoesNotAttachLatestAssistantPreview() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val prefix = (0L..119L).map(::event)
            val tail = (240L..359L).map(::event)
            f.cache.storeEvents(f.key, f.archiveSid, f.cache.ticket(), prefix + tail)
            f.selectWindow(prefix)
            f.selectWindow(tail, newer = false, older = true)
            f.field("followCursor").set(f.store, 359)
            f.store.updateHistoryViewport(300, false)
            synchronized(f.lock()) {
                (f.field("liveAssistant").get(f.store) as AssistantLiveState).seed(SessionAssistantStreamBaseline(
                    activeAttempt = SessionAssistantStreamAttempt("latest-attempt", 359, 1, 1, 1,
                        WireJson.parseToJsonElement("""[{"type":"text-chunks","time0":1,"index":0,"dt":[],"texts":["latest-only preview"]}]""").jsonArray)))
                SessionStore::class.java.getDeclaredMethod("rebuildCurrentLocked").apply { isAccessible = true }.invoke(f.store)
            }
            assertTrue(requireNotNull(f.store.currentConversation.value).nodes.any { it is AssistantMessageNode })
            f.cache.clear()
            f.answer = { request ->
                assertEquals(240, request.getValue("beforeSeq").jsonPrimitive.int)
                page(120, 239)
            }

            f.store.loadOlder()
            assertEquals((120L..239L).toList(), f.visible().map { it.seq })
            val snapshot = requireNotNull(f.store.currentConversation.value)
            assertTrue(snapshot.hasNewer)
            assertTrue(snapshot.hasMore)
            assertFalse("live assistant prefix belongs to seq 359, not this historical component",
                snapshot.nodes.any { it is AssistantMessageNode })
            assertFalse(f.store.loadingOlder.value)
        } finally { f.close() }
    }

    @Test fun smallerAuthoritativeSnapshotResetsKnownLatestBeforeInstallingItsWindow() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.cache.storeEvents(f.key, f.archiveSid, f.cache.ticket(), (0L..20L).map(::event))
            f.selectWindow((10L..20L).map(::event), newer = false, older = true)
            f.apply(SessionFollowFrame.Snapshot(cursor = 5, records = (0..5).map(::record)), f.cache.ticket())
            val snapshot = requireNotNull(f.store.currentConversation.value)
            assertEquals(5L, f.field("latestObservedHistorySeq").getLong(f.store))
            assertEquals(0L, f.field("knownFirstHistorySeq").get(f.store))
            assertEquals((0L..5L).toList(), f.visible().map { it.seq })
            assertFalse(snapshot.hasNewer)
            assertFalse(snapshot.hasMore)
        } finally { f.close() }
    }

    @Test fun latestJumpRejectsPartialDiskTailButCanUseCompleteRamTailAfterClear() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val partial = (0L..10L).map(::event)
            f.selectWindow(partial)
            f.field("latestObservedHistorySeq").setLong(f.store, 20)
            f.cache.clear()
            f.cache.storeEvents(f.key, f.archiveSid, f.cache.ticket(), partial)
            // Fixture has no live mux: a partial range must fail rather than report a false landing.
            assertFalse(f.store.showLatestHistory())
            assertEquals(partial, f.visible())
            assertTrue(f.field("currentHasNewer").getBoolean(f.store))
            val complete = (10L..20L).map(::event)
            f.selectWindow(complete, newer = false, older = true)
            val previewTicket = f.field("historyCacheTicket").getLong(f.store)
            f.cache.clear()
            assertTrue(f.store.showLatestHistory())
            assertEquals(complete, f.visible())
            assertTrue(f.field("historyAtTail").getBoolean(f.store))
            assertEquals(previewTicket, f.field("historyCacheTicket").getLong(f.store))
            assertTrue(f.cache.latestHistory(f.key, f.archiveSid).events.isEmpty())
            assertEquals(0, f.calls)
        } finally { f.close() }
    }

    @Test fun byteHighWaterReloadsLowWaterOnceInsteadOfOnEveryFollowingToolFrame() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val payload = buildJsonObject { put("text", "x".repeat(64 * 1024)) }
            val events = (0L..259L).map { SessionEventEnvelope("fixture", it, it, payload) }
            f.cache.storeEvents(f.key, f.archiveSid, f.cache.ticket(), events)
            f.selectWindow(events, newer = false)
            f.store.updateHistoryViewport(null, true)
            assertTrue(f.field("currentWindowBytes").getLong(f.store) > HistoryWindowBudget.payloadBytes(120))
            f.append(event(260))
            val lowFirst = f.visible().first().seq
            assertTrue(lowFirst > 0)
            assertTrue(f.field("currentWindowBytes").getLong(f.store) <= HistoryWindowBudget.payloadBytes(60))
            val revision = f.field("currentEventsRevision").getLong(f.store)
            val bytes = f.field("currentWindowBytes").getLong(f.store)
            val next = event(261)
            f.append(next)
            assertEquals("no second archive install/refold", revision + 1, f.field("currentEventsRevision").getLong(f.store))
            assertEquals(lowFirst, f.visible().first().seq)
            assertEquals(bytes + encodeHistoryEvent(next).payload.size, f.field("currentWindowBytes").getLong(f.store))
            assertEquals(0L, f.cache.historyAround(f.key, f.archiveSid, 0).firstSeq)
            assertEquals(261L, f.cache.latestHistory(f.key, f.archiveSid).lastSeq)
        } finally { f.close() }
    }

    @Test fun transientMissingViewportRowRetainsReadingAnchor() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.selectWindow((0L..10L).map(::event))
            f.store.updateHistoryViewport(5, false)
            f.store.updateHistoryViewport(null, false)
            assertEquals(5L, f.store.captureHistoryViewport()?.anchorSeq)
            assertFalse(f.field("historyAtTail").getBoolean(f.store))
            f.store.updateHistoryViewport(7, false)
            assertEquals(7L, f.store.captureHistoryViewport()?.anchorSeq)
        } finally { f.close() }
    }

    @Test fun warmReconnectPartialCachePreviewKeepsKnownNewerFlagAndCannotClaimLiveTail() = runBlocking<Unit> {
        val f = Fixture()
        try {
            // A reconnect restores an existing reader archive, never the legacy raw namespace.
            f.field("readerMode").setBoolean(f.store, true)
            val partial = (0L..10L).map(::event)
            f.selectWindow(partial)
            f.field("latestObservedHistorySeq").setLong(f.store, 200)
            f.store.updateHistoryViewport(5, false)
            f.cache.clear()
            f.cache.storeEvents(f.key, f.archiveSid, f.cache.ticket(), partial)
            f.field("historyCacheTicket").setLong(f.store, f.cache.ticket())
            ConnectionManager::class.java.getDeclaredField("api").apply { isAccessible = true }.set(f.manager, null)
            synchronized(f.lock()) {
                SessionStore::class.java.getDeclaredMethod("startFollow", String::class.java)
                    .apply { isAccessible = true }.invoke(f.store, "s")
            }
            val preview = requireNotNull(withTimeout(5_000) {
                f.store.currentConversation.first { it?.fromCache == true }
            })
            assertEquals(partial, f.visible())
            assertTrue("partial DB cache cannot override the known seq 200 bound", preview.hasNewer)
            assertTrue(f.field("currentHasNewer").getBoolean(f.store))
            f.store.updateHistoryViewport(5, true)
            assertFalse("a partial preview's viewport callback cannot claim the live tail",
                f.field("historyAtTail").getBoolean(f.store))
            assertEquals(5L, f.field("historyAnchorSeq").get(f.store))
            assertEquals(200L, f.field("latestObservedHistorySeq").getLong(f.store))
            assertNull(f.field("followCursor").get(f.store))
            assertEquals(0, f.calls)
        } finally { f.close() }
    }

    @Test fun freshLatestJumpWaitsForSnapshotThenRebasesPastOldAnchorAndSeedsAssistantPreview() = runBlocking<Unit> {
        val f = Fixture()
        var jump: Deferred<Boolean>? = null
        try {
            val historic = (0L..10L).map(::event)
            f.selectWindow(historic)
            f.field("latestObservedHistorySeq").setLong(f.store, 200)
            f.field("followCursor").set(f.store, 200)
            f.store.updateHistoryViewport(5, false)
            val oldTicket = f.cache.ticket()
            f.cache.clear()
            val partialDisk = (100L..110L).map(::event)
            f.cache.storeEvents(f.key, f.archiveSid, f.cache.ticket(), partialDisk)
            val socket = f.installFollowSocket()
            f.answer = { error("opening latest must not fetch history") }
            jump = async(Dispatchers.Default) { f.store.showLatestHistory() }
            withTimeout(5_000) { socket.opened.await() }
            assertFalse("opening a stream alone is not a successful jump", requireNotNull(jump).isCompleted)
            assertEquals(historic, f.visible())
            assertFalse(f.field("historyAtTail").getBoolean(f.store))
            assertEquals(5L, f.field("historyAnchorSeq").get(f.store))
            assertTrue(f.field("historyCacheTicket").getLong(f.store) > oldTicket)
            assertTrue(f.store.loadingNewer.value)
            val assistant = SessionAssistantStreamBaseline(activeAttempt = SessionAssistantStreamAttempt(
                "fresh-latest", 200, 1, 1, 1,
                WireJson.parseToJsonElement("""[{"type":"text-chunks","time0":1,"index":0,"dt":[],"texts":["fresh latest prefix"]}]""").jsonArray))
            socket.emit(SessionFollowFrame.Snapshot(cursor = 200, records = listOf(record(199), record(200)),
                hasMore = true, assistantStream = assistant))
            assertTrue(withTimeout(5_000) { requireNotNull(jump).await() })
            assertEquals("latest means the authoritative cut, not a 60-message HTTP page",
                listOf(199L, 200L), f.visible().map { it.seq })
            assertEquals(0, f.calls)
            assertTrue(f.field("historyAtTail").getBoolean(f.store))
            assertEquals(200, f.field("followCursor").get(f.store))
            assertFalse(f.store.loadingNewer.value)
            val snapshot = requireNotNull(f.store.currentConversation.value)
            assertFalse(snapshot.hasNewer)
            assertTrue("the new live follow must seed its authoritative assistant prefix",
                snapshot.nodes.any { it is AssistantMessageNode })
            assertEquals("raw archive remains untouched", 100L, f.cache.historyAround(f.key, "s", 0).firstSeq)
            assertEquals(0, f.calls)
        } finally {
            jump?.cancelAndJoin()
            f.close()
        }
    }

    @Test fun initialFollowNeverFetchesOlderAndLiveFramesRemainUsable() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val socket = f.installFollowSocket()
            f.answer = { error("initial follow must not fetch older history") }
            f.startFollow()
            socket.emit(SessionFollowFrame.Snapshot(cursor = 20, records = listOf(record(19), record(20)), hasMore = true))
            withTimeout(5_000) { f.store.currentConversation.first { f.visible().lastOrNull()?.seq == 20L } }
            assertEquals(listOf(19L, 20L), f.visible().map { it.seq })
            assertFalse(f.store.loadingOlder.value)
            assertFalse(f.store.loadOlderFailed.value)
            socket.emit(SessionFollowFrame.Entry(record(21)))
            withTimeout(5_000) { f.store.currentConversation.first { f.visible().lastOrNull()?.seq == 21L } }
            assertEquals(0, f.calls)
            assertEquals(19L, f.cache.latestHistory(f.key, f.archiveSid).firstSeq)
            assertTrue(f.followJob().isActive)
            // Only a user paging request may cross the older-history boundary.
            f.answer = { page(0, 18) }
            f.store.loadOlder()
            assertEquals(1, f.calls)
            assertEquals(listOf(19), f.requestedBefore)
            assertFalse(f.store.loadOlderFailed.value)
        } finally { f.close() }
    }

    @Test fun readerCacheIsIsolatedAndLatestIsBoundedEvenWithoutMessageBoundaries() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val raw = event(500).copy(data = buildJsonObject { put("secretRawOutput", "unchanged") })
            f.cache.storeEvents(f.key, "s", f.cache.ticket(), listOf(raw))
            f.field("readerMode").setBoolean(f.store, true)
            val reduced = (0L..500L).map { event(it).copy(type = "reader/skip", data = JsonNull) }
            f.cache.storeEvents(f.key, f.archiveSid, f.cache.ticket(), reduced)
            val page = f.cache.latestHistory(f.key, f.archiveSid, 120)
            assertEquals(128, page.events.size)
            assertEquals(500L, page.lastSeq)
            assertTrue(page.hasOlder)
            assertEquals(listOf(raw), f.cache.latestHistory(f.key, "s").events)
            assertTrue(page.payloadBytes <= READER_MAX_BYTES)
            f.cache.storeEvents(f.key, f.archiveSid, f.cache.ticket(), (501L..510L).map {
                event(it).copy(data = buildJsonObject { put("text", "x".repeat(70_000)) })
            })
            val bytes = f.cache.latestHistory(f.key, f.archiveSid, 120)
            assertTrue(bytes.events.size <= 3)
            assertTrue(bytes.payloadBytes <= READER_MAX_BYTES)
        } finally { f.close() }
    }

    @Test fun missingReaderAcknowledgementFailsExplicitlyWithoutRawFallbackOrWrites() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val raw = listOf(event(0))
            f.cache.storeEvents(f.key, "s", f.cache.ticket(), raw)
            val socket = f.installFollowSocket()
            f.startFollow()
            socket.emit(SessionFollowFrame.Snapshot(cursor = 20, records = listOf(record(20)), hasMore = true), acknowledge = false)
            val error = withTimeout(5_000) { f.store.connectionError.first { it != null } }
            assertTrue(error.toString().contains("reader", ignoreCase = true))
            assertEquals(0, f.calls)
            assertTrue(f.cache.latestHistory(f.key, f.archiveSid).events.isEmpty())
            assertEquals(raw, f.cache.latestHistory(f.key, "s").events)
        } finally { f.close() }
    }

    @Test fun readerPageWithoutAcknowledgementNeverPublishesStagedRawRecords() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.field("readerMode").setBoolean(f.store, true)
            f.cache.storeEvents(f.key, f.archiveSid, f.cache.ticket(), listOf(event(20)))
            f.selectWindow(listOf(event(20)), older = true)
            f.acknowledgePages = false
            f.answer = { page(0, 19) }
            f.store.loadOlder()
            assertEquals(1, f.calls)
            assertTrue(f.store.loadOlderFailed.value)
            assertEquals(listOf(20L), f.cache.latestHistory(f.key, f.archiveSid).events.map { it.seq })
            assertTrue(f.cache.latestHistory(f.key, "s").events.isEmpty())
        } finally { f.close() }
    }

    @Test fun initialBaselineDoesNotEagerlyWidenToPreviouslyCachedOlderHistory() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.cache.storeEvents(f.key, f.archiveSid, f.cache.ticket(), (0L..20L).map(::event))
            f.field("historyAtTail").setBoolean(f.store, true)
            f.apply(SessionFollowFrame.Snapshot(cursor = 20, records = listOf(record(19), record(20)), hasMore = true), f.cache.ticket())
            assertEquals(listOf(19L, 20L), f.visible().map { it.seq })
            assertEquals(0, f.calls)
            assertEquals(0L, f.cache.latestHistory(f.key, f.archiveSid).firstSeq)
            f.store.loadOlder()
            assertTrue(f.visible().first().seq < 19L)
            assertEquals("explicit paging may reuse the raw archive without a request", 0, f.calls)
        } finally { f.close() }
    }

    @Test fun prebaselineOlderWaitIsNotFailureAndSuccessfulBaselineClearsPriorError() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.selectWindow(listOf(event(19), event(20)), older = true)
            f.field("followCursor").set(f.store, null)
            f.store.loadOlder()
            assertFalse(f.store.loadOlderFailed.value)
            assertFalse(f.store.loadingOlder.value)
            assertEquals(0, f.calls)
            @Suppress("UNCHECKED_CAST")
            val failure = f.field("_loadOlderFailed").get(f.store) as MutableStateFlow<Boolean>
            failure.value = true
            f.apply(SessionFollowFrame.Snapshot(cursor = 20, records = listOf(record(20)), hasMore = true), f.cache.ticket())
            assertFalse(f.store.loadOlderFailed.value)
            assertEquals(0, f.calls)
        } finally { f.close() }
    }

    @Test fun explicitOlderReadIsFencedByClearAndSessionGenerationAndCancellation() = runBlocking<Unit> {
        for (retire in listOf("clear", "session", "cancel")) {
            val f = Fixture()
            try {
                val socket = f.installFollowSocket()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val cancelled = CompletableDeferred<Unit>()
                f.answer = {
                    entered.complete(Unit)
                    try { release.await(); page(0, 20) }
                    finally { cancelled.complete(Unit) }
                }
                f.startFollow()
                socket.emit(SessionFollowFrame.Snapshot(cursor = 20, records = listOf(record(20)), hasMore = true))
                withTimeout(5_000) { f.store.currentConversation.first { f.visible().lastOrNull()?.seq == 20L } }
                assertEquals(0, f.calls)
                val older = async(Dispatchers.Default) { f.store.loadOlder() }
                withTimeout(5_000) { entered.await() }
                when (retire) {
                    "clear" -> f.cache.clear()
                    "session" -> synchronized(f.lock()) {
                        f.field("historyOpenEpoch").setLong(f.store, f.field("historyOpenEpoch").getLong(f.store) + 1)
                        f.field("currentId").set(f.store, "other")
                    }
                    "cancel" -> {
                        older.cancelAndJoin()
                        withTimeout(5_000) { cancelled.await() }
                    }
                }
                release.complete(Unit)
                older.join()
                assertEquals(listOf(20L), f.visible().map { it.seq })
                val cached = f.cache.latestHistory(f.key, f.archiveSid).events.map { it.seq }
                assertEquals(if (retire == "clear") emptyList<Long>() else listOf(20L), cached)
                if (retire == "clear") {
                    socket.emit(SessionFollowFrame.Entry(record(21)))
                    withTimeout(5_000) { f.store.currentConversation.first { f.visible().lastOrNull()?.seq == 21L } }
                    assertEquals(listOf(21L), f.cache.latestHistory(f.key, f.archiveSid).events.map { it.seq })
                }
            } finally { f.close() }
        }
    }

    @Test fun historicBookmarkRestoresExactWindowAnchorAndMetadataAfterNonoverlappingMove() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val historic = (100L..110L).map(::event)
            val distant = (1_000L..1_010L).map(::event)
            f.cache.storeEvents(f.key, f.archiveSid, f.cache.ticket(), (0L..10L).map(::event) + historic + distant)
            f.selectWindow(historic, newer = true, older = true)
            f.store.updateHistoryViewport(105, false)
            val bookmark = requireNotNull(f.store.captureHistoryViewport())
            assertFalse(bookmark.followingTail)
            assertEquals(105L, bookmark.anchorSeq)
            assertTrue(bookmark.hasOlder)
            assertTrue(bookmark.hasNewer)
            f.selectWindow(distant, newer = false, older = true)
            assertFalse(f.visible().any { it.seq == bookmark.anchorSeq })

            assertTrue(f.store.restoreHistoryViewport(bookmark))
            assertEquals(historic, f.visible())
            assertTrue(f.visible().any { it.seq == bookmark.anchorSeq })
            assertEquals(bookmark, f.store.captureHistoryViewport())
            assertFalse(f.store.loadingOlder.value)
            assertFalse(f.store.loadingNewer.value)
        } finally { f.close() }
    }

    @Test fun clearedBookmarkUsesRamAndKeepsOldPreviewTicketWhenOnlyNewLiveEventIsArchived() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val historic = (0L..10L).map(::event)
            val distant = (100L..110L).map(::event)
            val oldTicket = f.cache.ticket()
            f.cache.storeEvents(f.key, f.archiveSid, oldTicket, historic + distant)
            f.selectWindow(historic)
            f.store.updateHistoryViewport(5, false)
            val bookmark = requireNotNull(f.store.captureHistoryViewport())
            f.selectWindow(distant, newer = false, older = true)
            f.cache.clear()
            // Another view may have acquired the current cache epoch before the bookmark returns.
            f.field("historyCacheTicket").setLong(f.store, f.cache.ticket())
            assertTrue(f.store.restoreHistoryViewport(bookmark))
            assertEquals(historic, f.visible())
            assertEquals(5L, f.store.captureHistoryViewport()?.anchorSeq)
            assertEquals(oldTicket, f.field("historyCacheTicket").getLong(f.store))
            assertTrue(f.cache.latestHistory(f.key, f.archiveSid).events.isEmpty())

            val scopeJob = (f.field("scope").get(f.store) as CoroutineScope).coroutineContext[Job]!!
            val existingJobs = scopeJob.children.toSet()
            f.append(event(111))
            (f.field("historySaveJob").get(f.store) as? Job)?.join()
            withTimeout(5_000) { scopeJob.children.filter { it !in existingJobs }.toList().forEach { it.join() } }
            assertEquals(historic, f.visible())
            assertEquals(oldTicket, f.field("historyCacheTicket").getLong(f.store))
            assertEquals(listOf(111L), f.cache.latestHistory(f.key, f.archiveSid).events.map { it.seq })
            assertNull("bookmark content must not refill a cleared preview", f.cache.read(f.key, f.archiveSid, f.cache.ticket()))
            assertEquals(0, f.calls)
        } finally { f.close() }
    }

    @Test fun corruptDatabaseRestoreRetainsRamBookmarkAndReportsStorageError() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val historic = (100L..110L).map(::event)
            val distant = (1_000L..1_010L).map(::event)
            f.cache.storeEvents(f.key, f.archiveSid, f.cache.ticket(), (0L..10L).map(::event) + historic + distant)
            f.selectWindow(historic, newer = true, older = true)
            f.store.updateHistoryViewport(105, false)
            val bookmark = requireNotNull(f.store.captureHistoryViewport())
            f.selectWindow(distant, newer = false, older = true)
            f.cache.close()
            File(f.disk.root, "history/sessions.sqlite").writeText("corrupt isolated bookmark test database")

            assertTrue("storage failure must fall back to the saved RAM window", f.store.restoreHistoryViewport(bookmark))
            assertEquals(historic, f.visible())
            assertEquals(bookmark, f.store.captureHistoryViewport())
            assertFalse(f.store.connectionError.value.isNullOrBlank())
            assertFalse(f.store.loadingOlder.value)
            assertFalse(f.store.loadingNewer.value)
        } finally { f.close() }
    }

    @Test fun lateOlderRequestCannotOverwriteRestoredBookmarkOrNewRequestLoadingFlag() = runBlocking<Unit> {
        val f = Fixture()
        val oldStarted = CompletableDeferred<Unit>()
        val oldRelease = CompletableDeferred<Unit>()
        val currentStarted = CompletableDeferred<Unit>()
        val currentRelease = CompletableDeferred<Unit>()
        var oldRequest: Deferred<Unit>? = null
        var currentRequest: Deferred<Unit>? = null
        try {
            val historic = (100L..110L).map(::event)
            val temporary = (500L..510L).map(::event)
            f.cache.storeEvents(f.key, f.archiveSid, f.cache.ticket(), (0L..10L).map(::event) + historic + temporary)
            f.selectWindow(historic, newer = true, older = true)
            f.store.updateHistoryViewport(105, false)
            val bookmark = requireNotNull(f.store.captureHistoryViewport())
            f.selectWindow(temporary, newer = false, older = true)
            f.answer = { request ->
                when (request.getValue("beforeSeq").jsonPrimitive.int) {
                    500 -> { oldStarted.complete(Unit); oldRelease.await(); page(450, 499) }
                    100 -> { currentStarted.complete(Unit); currentRelease.await(); page(50, 99) }
                    else -> error("unexpected older page request: $request")
                }
            }
            oldRequest = async(Dispatchers.Default) { f.store.loadOlder() }
            withTimeout(5_000) { oldStarted.await() }
            assertTrue(f.store.loadingOlder.value)
            assertTrue(f.store.restoreHistoryViewport(bookmark))
            assertEquals(historic, f.visible())
            assertFalse(f.store.loadingOlder.value)
            currentRequest = async(Dispatchers.Default) { f.store.loadOlder() }
            withTimeout(5_000) { currentStarted.await() }
            assertTrue(f.store.loadingOlder.value)

            oldRelease.complete(Unit)
            withTimeout(5_000) { requireNotNull(oldRequest).await() }
            assertEquals(historic, f.visible())
            assertEquals(bookmark, f.store.captureHistoryViewport())
            assertTrue("retired request must not clear the new request's loading flag", f.store.loadingOlder.value)
            assertFalse(f.store.loadOlderFailed.value)
            assertEquals("retired page must not be committed", 500L, f.cache.newerHistory(f.key, f.archiveSid, 110).firstSeq)

            currentRelease.complete(Unit)
            withTimeout(5_000) { requireNotNull(currentRequest).await() }
            assertFalse(f.store.loadingOlder.value)
            assertFalse(f.store.loadOlderFailed.value)
            assertEquals(50L, f.visible().first().seq)
            assertTrue(f.visible().any { it.seq == bookmark.anchorSeq })
            assertEquals(listOf(500, 100), f.requestedBefore)
        } finally {
            oldRelease.complete(Unit)
            currentRelease.complete(Unit)
            oldRequest?.cancelAndJoin()
            currentRequest?.cancelAndJoin()
            f.close()
        }
    }

    @Test fun latestHistoryStorageErrorReturnsFalseInsteadOfEscaping() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.cache.storeEvents(f.key, f.archiveSid, f.cache.ticket(), listOf(event(0)))
            f.cache.close()
            File(f.disk.root, "history/sessions.sqlite").writeText("corrupt isolated test database")
            assertFalse(f.store.showLatestHistory())
            assertFalse(f.store.connectionError.value.isNullOrBlank())
        } finally { f.close() }
    }
}
