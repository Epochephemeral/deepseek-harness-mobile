package com.labteto.dshmobile.data

import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.storage.DiskCacheStore
import com.labteto.dshmobile.core.wire.*
import com.labteto.dshmobile.core.wire.dto.*
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Real SQLite and fake carriers only; no host, singleton preferences, or user data are touched. */
class ArchivedSubagentTranscriptTest {
    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "subagent-archive-${UUID.randomUUID()}")
        val disk = DiskCacheStore(root)
        val cache = HistoryCache(File(root, "preview"), diskStore = disk)
        val client = OkHttpClient()
        var calls = 0
        var answer: suspend (JsonObject) -> JsonElement = { error("Unexpected network history page") }
        val failures = ArrayList<Throwable>()
        private val controllers = ArrayList<ArchivedSubagentTranscript>()
        val api = DshApiClient(object : RpcTransport {
            override suspend fun post(path: String, body: String): RpcHttpResponse {
                calls++
                val request = WireJson.parseToJsonElement(body).jsonObject
                val args = request.getValue("payload").jsonObject.getValue("args").jsonObject.getValue("request").jsonObject
                val value = answer(args)
                return RpcHttpResponse(200, """{"rpcId":${request["rpcId"]},"result":{"ok":true,"value":$value}}""")
            }
            override suspend fun <T> download(path: String, consume: (String?, String?, InputStream) -> T): T = error("unused")
            override suspend fun upload(path: String, contentType: String, contentLength: Long, body: InputStream,
                onProgress: ((Long) -> Unit)?): RpcHttpResponse = error("unused")
        })
        val mux = RemoteStreamMux(disk) { sink ->
            object : WsChannel("http://fixture.invalid/api/remote.mux", client, sink) {
                override fun start() { sink.onOpen() }
                override fun close() = Unit
                // Offline cached tests must never wait forever for an unconfigured follow probe.
                override fun send(text: String): Boolean = false
            }
        }.also { it.start() }
        fun controller(parent: String = "parent", child: String = "child", mode: String = "continuable") =
            ArchivedSubagentTranscript(cache, disk, api, mux, "host", parent, child, mode, { true }, { failures += it })
                .also { controllers += it }
        fun seed(view: ArchivedSubagentTranscript, events: List<SessionEventEnvelope>) {
            assertTrue(cache.storeEvents(view.archiveHost, view.archiveKey, cache.ticket(), events))
        }
        fun close() {
            controllers.forEach { it.close() }
            mux.close(); cache.close()
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
            require(root.canonicalFile.parentFile == context.cacheDir.canonicalFile && root.name.startsWith("subagent-archive-"))
            root.deleteRecursively()
        }
    }
    private fun fixture(body: suspend (Fixture) -> Unit) = runBlocking<Unit> {
        val fixture = Fixture()
        try { body(fixture) } finally { fixture.close() }
    }
    private fun event(seq: Long, every: Int = 1) = SessionEventEnvelope(
        if (seq % every == 0L) "user/message" else "fixture/event", seq, seq,
        buildJsonObject { put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "event $seq") }) }) }
    )
    private fun record(event: SessionEventEnvelope): SessionHistoryRecord = SessionHistoryRecord.Event(
        event = SessionWireEvent(event.type, event.seq.toInt(), event.time, event.data))

    @Test fun fullSubagentAddressAndOrdinaryParentSessionNeverAlias() = fixture { f ->
        val first = f.controller("parent:1", "same", "continuable")
        val otherParent = f.controller("parent", "1:same", "continuable")
        val otherMode = f.controller("parent:1", "same", "one-shot")
        assertNotEquals(first.archiveKey, otherParent.archiveKey)
        assertNotEquals(first.archiveKey, otherMode.archiveKey)
        f.seed(first, (0L until 10).map { event(it) })
        f.seed(otherParent, (100L until 110).map { event(it) })
        f.seed(otherMode, (200L until 210).map { event(it) })
        f.cache.storeEvents("host", "same", f.cache.ticket(), listOf(event(999)))
        assertTrue(first.showLatest()); assertTrue(otherParent.showLatest()); assertTrue(otherMode.showLatest())
        assertEquals(9L, first.state.value.snapshot!!.lastSeq)
        assertEquals(109L, otherParent.state.value.snapshot!!.lastSeq)
        assertEquals(209L, otherMode.state.value.snapshot!!.lastSeq)
        assertEquals(0, f.calls)
    }

    @Test fun offlineBidirectionalWindowStaysBoundedWhileLiveTailContinuesOnDisk() = fixture { f ->
        val view = f.controller()
        val history = (0L until 12_000).map { event(it, 30) }.toMutableList()
        history[history.lastIndex] = SessionEventEnvelope("turn/start", 11_999, 11_999,
            buildJsonObject { put("turn", 777) }) // cached running metadata must never imply a live turn
        f.seed(view, history)
        assertTrue(view.showLatest())
        assertEquals(11_999L, view.state.value.snapshot!!.lastSeq)
        assertTrue(view.state.value.snapshot!!.fromCache)
        assertFalse(view.state.value.snapshot!!.running)
        assertEquals(view.state.value.hasNewer, view.state.value.snapshot!!.hasNewer)
        assertTrue(view.state.value.snapshot!!.journal.size <= 8_000)
        assertTrue(view.loadOlder())
        val visible = view.state.value.snapshot!!.journal
        val anchor = visible[visible.size / 2].seq
        view.updateViewport(anchor, atTail = false)
        view.acceptFrame(SessionFollowFrame.Entry(record(event(12_000, 30))))
        val older = view.state.value.snapshot!!
        assertTrue(older.journal.any { it.seq == anchor })
        assertTrue(older.journal.size <= 8_000)
        assertTrue(older.lastSeq < 12_000)
        assertTrue(view.state.value.hasNewer)
        assertEquals(view.state.value.hasNewer, older.hasNewer)
        assertEquals(12_000L, f.cache.latestHistory(view.archiveHost, view.archiveKey).lastSeq)
        assertTrue(view.loadNewer())
        assertTrue(view.showLatest())
        assertEquals(12_000L, view.state.value.snapshot!!.lastSeq)
        assertFalse(view.state.value.hasNewer)
        assertEquals(view.state.value.hasNewer, view.state.value.snapshot!!.hasNewer)
        assertEquals(0, f.calls)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun denseModernTurnPagesButLegacyChunkPrefixIsNeverTruncated() = fixture { f ->
        val modern = f.controller(child = "modern")
        val legacy = f.controller(child = "legacy")
        f.seed(modern, (0L..9_000).map { event(it, 20_000) })
        val old = listOf(event(0)) + (1L..9_000).map { seq ->
            SessionEventEnvelope("assistant/chunk", seq, seq, buildJsonObject {
                put("turn", 1); put("step", 1)
                put("chunk", buildJsonObject { put("type", "text-delta"); put("index", 0); put("text", "x") })
            })
        }
        f.seed(legacy, old)
        assertTrue(modern.showLatest()); assertTrue(legacy.showLatest())
        assertEquals(8_000, modern.state.value.snapshot!!.journal.size)
        assertTrue(modern.state.value.snapshot!!.hasMore)
        assertEquals(9_001, legacy.state.value.snapshot!!.journal.size)
        assertEquals(0L, legacy.state.value.snapshot!!.journal.first().seq)
        assertEquals(9_000L, legacy.state.value.snapshot!!.journal.last().seq)
    }

    @Test fun liveAppendsReuseEnvelopeIdentityUntilHighWaterThenRollToLowWater() = fixture { f ->
        val view = f.controller()
        f.seed(view, (0L until 60).map { event(it) })
        assertTrue(view.showLatest())
        val first = view.state.value.snapshot!!.journal.first()
        for (seq in 60L until 120) {
            view.acceptFrame(SessionFollowFrame.Entry(record(event(seq))))
            assertSame("ordinary append must not re-decode the old DB page", first,
                view.state.value.snapshot!!.journal.first())
        }
        val beforeRoll = view.state.value.snapshot!!.journal.first { it.seq == 61L }
        view.acceptFrame(SessionFollowFrame.Entry(record(event(120))))
        val rolled = view.state.value.snapshot!!.journal
        assertEquals(60, rolled.size)
        assertEquals(61L, rolled.first().seq)
        assertEquals(120L, rolled.last().seq)
        assertNotSame(beforeRoll, rolled.first())
        assertEquals(0L, f.cache.historyAround(view.archiveHost, view.archiveKey, 0, 120).firstSeq)

        // A reader within the current tail window rolls around their anchor, not to a new tail.
        view.updateViewport(80, atTail = false)
        for (seq in 121L..181) view.acceptFrame(SessionFollowFrame.Entry(record(event(seq))))
        assertTrue(view.state.value.hasNewer)
        val anchored = view.state.value.snapshot!!.journal.first { it.seq == 80L }
        view.acceptFrame(SessionFollowFrame.Entry(record(event(182))))
        assertSame("offscreen tail events must not re-read an old reader's page", anchored,
            view.state.value.snapshot!!.journal.first { it.seq == 80L })
        assertEquals(182L, f.cache.latestHistory(view.archiveHost, view.archiveKey).lastSeq)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun largeModernToolResultsRollByPayloadBytesWithoutRejectingOrEvictingRecords() = fixture { f ->
        val view = f.controller()
        f.seed(view, listOf(event(0)))
        assertTrue(view.showLatest())
        val bytes = view.javaClass.getDeclaredField("currentWindowBytes").apply { isAccessible = true }
        val payload = "x".repeat(2 * 1024 * 1024)
        for (seq in 1L..12) {
            val result = SessionEventEnvelope("tool/result", seq, seq, buildJsonObject {
                put("turn", 1); put("step", seq)
                putJsonObject("message") {
                    put("role", "tool"); put("toolCallId", "call-$seq")
                    putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", payload) }) }
                }
            })
            view.acceptFrame(SessionFollowFrame.Entry(record(result)))
            assertTrue("RAM payload target applies independently of sparse message boundaries",
                bytes.getLong(view) <= HistoryWindowBudget.payloadBytes(120))
        }
        val visible = view.state.value.snapshot!!.journal
        assertEquals(12L, visible.last().seq)
        assertEquals(visible.sumOf { encodeHistoryEvent(it).payload.size.toLong() }, bytes.getLong(view))
        var page = f.cache.latestHistory(view.archiveHost, view.archiveKey)
        var retained = page.events.size
        while (page.hasOlder) {
            val boundary = page.firstSeq!!
            page = f.cache.olderHistory(view.archiveHost, view.archiveKey, boundary)
            assertEquals(boundary - 1, page.lastSeq)
            retained += page.events.size
        }
        assertEquals(13, retained)
        assertEquals(0L, page.firstSeq)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun oversizedContentIsNotRepeatedlyDecodedForStructuralFramesButNewContentCanRoll() = fixture { f ->
        val view = f.controller()
        val payload = "界".repeat(6 * 1024 * 1024) // 18 MiB UTF-8: valid content, not a rejection case
        val huge = SessionEventEnvelope("tool/result", 0, 0, buildJsonObject {
            put("turn", 1); put("step", 1)
            putJsonObject("message") {
                put("role", "tool"); put("toolCallId", "huge-call")
                putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", payload) }) }
            }
        })
        f.seed(view, listOf(huge))
        assertTrue(view.showLatest())
        val original = view.state.value.snapshot!!.journal.first()
        val bytes = view.javaClass.getDeclaredField("currentWindowBytes").apply { isAccessible = true }
        assertTrue(bytes.getLong(view) > HistoryWindowBudget.payloadBytes(120))
        val types = listOf("step/start", "step/end", "turn/end", "assistant/attempt")
        for (seq in 1L..20) {
            view.acceptFrame(SessionFollowFrame.Entry(record(SessionEventEnvelope(types[((seq - 1) % types.size).toInt()],
                seq, seq, buildJsonObject { put("turn", 1); put("step", seq) }))))
            assertSame("a framing event must not re-decode the same oversized content", original,
                view.state.value.snapshot!!.journal.first())
        }
        view.acceptFrame(SessionFollowFrame.Entry(record(event(21))))
        assertEquals(21L, view.state.value.snapshot!!.lastSeq)
        assertFalse(view.state.value.snapshot!!.journal.any { it.seq == 0L })
        val retained = f.cache.historyAround(view.archiveHost, view.archiveKey, 0, 60)
        assertEquals(0L, retained.firstSeq)
        val restored = retained.events.first().data.jsonObject.getValue("message").jsonObject
            .getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content
        assertEquals(payload.length, restored.length)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun incrementalPreviewReusesDurableFoldAndReleasesDiskJournalOnClose() = fixture { f ->
        val view = f.controller()
        f.seed(view, listOf(event(0)))
        assertTrue(view.showLatest())
        val folded = view.javaClass.getDeclaredField("folded").apply { isAccessible = true }
        val durable = folded.get(view)
        view.acceptFrame(SessionFollowFrame.AssistantStream(SessionAssistantStreamFrame.Start(
            attemptId = "attempt", revision = 1, startedAfterSeq = 0, turn = 1, step = 1)))
        repeat(150) { index ->
            view.acceptFrame(SessionFollowFrame.AssistantStream(SessionAssistantStreamFrame.Chunk(
                attemptId = "attempt", revision = index + 2, index = index, time = index.toLong(),
                chunk = buildJsonObject { put("type", "text-delta"); put("index", 0); put("text", "$index,") })))
        }
        assertSame(durable, folded.get(view))
        val preview = view.state.value.snapshot!!.nodes.filterIsInstance<AssistantMessageNode>().single { it.streaming }
        assertEquals((0 until 150).joinToString("") { "$it," }, preview.plainText)
        assertTrue(f.disk.usage("stream").protectedBytes > 0)
        view.close()
        assertEquals(0L, f.disk.usage("stream").protectedBytes)
    }

    @Test fun delayedOldBaselineAfterClearCannotRefillButOneNewLiveEventCanPersist() = fixture { f ->
        val view = f.controller()
        val original = (0L until 120).map { event(it) }
        f.seed(view, original)
        assertTrue(view.showLatest())
        view.updateViewport(20, atTail = false)
        val oldTicket = f.cache.ticket()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.answer = {
            entered.complete(Unit)
            release.await()
            WireJson.encodeToJsonElement(SessionPage.serializer(), SessionPage(original.map(::record), hasMore = false))
        }
        val pending = CoroutineScope(currentCoroutineContext()).async(Dispatchers.Default) {
            view.acceptFrame(SessionFollowFrame.Snapshot(cursor = 119, records = listOf(record(original.last())), hasMore = true), oldTicket)
        }
        withTimeout(5_000) { entered.await() }
        f.cache.clear()
        release.complete(Unit)
        withTimeout(5_000) { pending.await() }
        assertTrue(f.cache.latestHistory(view.archiveHost, view.archiveKey).events.isEmpty())
        assertEquals(original.map { it.seq }, view.state.value.snapshot!!.journal.map { it.seq })
        view.acceptFrame(SessionFollowFrame.Entry(record(event(120))))
        assertEquals(listOf(120L), f.cache.latestHistory(view.archiveHost, view.archiveKey).events.map { it.seq })
        assertEquals(original.map { it.seq }, view.state.value.snapshot!!.journal.map { it.seq })
        assertTrue(view.state.value.hasNewer)
        assertTrue(view.showLatest())
        assertTrue("clearing local history must not hide the remote older-history affordance", view.state.value.snapshot!!.hasMore)
        assertEquals(120L, view.state.value.snapshot!!.lastSeq)
    }

    @Test fun disjointCachedPagesNeverPretendToBeAdjacentAndFailureRemainsRetryable() = fixture { f ->
        val view = f.controller()
        f.seed(view, (0L until 100).map { event(it) })
        f.seed(view, (200L until 300).map { event(it) })
        assertTrue(view.showLatest())
        val before = view.state.value.snapshot!!.journal
        assertFalse(view.loadOlder())
        assertEquals(before, view.state.value.snapshot!!.journal)
        assertTrue(view.state.value.loadOlderFailed)
        assertFalse(view.state.value.loadingOlder)
        assertFalse(view.loadOlder())
        assertEquals(2, f.failures.size)
    }
}
