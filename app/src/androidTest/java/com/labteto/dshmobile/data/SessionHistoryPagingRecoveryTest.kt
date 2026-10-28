package com.labteto.dshmobile.data

import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.core.storage.DiskCacheStore
import com.labteto.dshmobile.core.wire.*
import com.labteto.dshmobile.core.wire.dto.*
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real archive + reader contract tests: no reflective Store fields or connection fixture. */
class SessionHistoryPagingRecoveryTest {
    private class Fixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "reader-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        val disk = DiskCacheStore(File(directory, "managed"))
        val cache = HistoryCache(File(disk.root, "preview"), diskStore = disk)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val requestedBefore = mutableListOf<Int>()
        var acknowledge = true
        var refresh: (suspend () -> Boolean)? = null
        var answer: suspend (JsonObject) -> JsonObject = { page(it.getValue("beforeSeq").jsonPrimitive.int) }
        val api = DshApiClient(object : RpcTransport {
            override suspend fun post(path: String, body: String): RpcHttpResponse {
                val envelope = WireJson.parseToJsonElement(body).jsonObject
                val request = envelope.getValue("payload").jsonObject.getValue("args").jsonObject.getValue("request").jsonObject
                synchronized(requestedBefore) { requestedBefore.add(request.getValue("beforeSeq").jsonPrimitive.int) }
                assertEquals("/api/session/readerPage", path)
                assertFalse("reader view is selected by endpoint, not a wire request field", "view" in request)
                assertEquals(READER_MAX_ROWS, request.getValue("maxRows").jsonPrimitive.int)
                assertEquals(READER_MAX_BYTES, request.getValue("maxBytes").jsonPrimitive.int)
                val raw = answer(request)
                val value = if (acknowledge && "reader" !in raw) {
                    val records = raw.getValue("records").jsonArray
                    val first = records.firstOrNull()?.jsonObject?.get("event")?.jsonObject?.get("seq")?.jsonPrimitive?.int
                        ?: request.getValue("beforeSeq").jsonPrimitive.int
                    JsonObject(raw + ("reader" to buildJsonObject {
                        put("version", 1); put("fromSeq", first)
                        put("throughSeq", request.getValue("beforeSeq").jsonPrimitive.int - 1)
                        put("nextBeforeSeq", first); put("bytes", 100); put("oversized", false)
                        put("rawDetails", "session/page")
                    }))
                } else raw
                return RpcHttpResponse(200, """{"rpcId":${envelope["rpcId"]},"result":{"ok":true,"value":$value}}""")
            }
            override suspend fun <T> download(path: String, consume: (String?, String?, InputStream) -> T): T = error("unused")
            override suspend fun upload(path: String, contentType: String, contentLength: Long, body: InputStream,
                onProgress: ((Long) -> Unit)?): RpcHttpResponse = error("unused")
        })
        var source = ReaderSource("fixture-host", "session", 1, api)
        val reader = SessionHistoryReader(cache, scope, refreshFollow = { refresh?.invoke() ?: false })
        lateinit var token: ReaderFollowToken
        val current get() = reader.state.value
        val snapshot get() = requireNotNull(current.snapshot)
        val archive get() = source.archiveKey
        val calls get() = synchronized(requestedBefore) { requestedBefore.size }

        suspend fun open(first: Long, last: Long, latest: Long = last, withOutline: Boolean = true) {
            token = reader.beginFollow(source)
            reader.baseline(token, (first..last).map(::event), last.toInt(), first > 0, bridgeReconnect = false)
            if (latest > last) {
                val tailFirst = maxOf(last + 2, latest - 127)
                reader.baseline(token, (tailFirst..latest).map(::event), latest.toInt(), true, bridgeReconnect = false)
            }
            if (withOutline) reader.updateOutline(outline(latest))
        }
        suspend fun close() {
            reader.close()
            scope.coroutineContext[Job]?.cancelAndJoin()
            cache.close()
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }

    companion object {
        private fun event(seq: Long): SessionEventEnvelope {
            val turn = (seq / 512).toInt()
            val data = when (seq % 512) {
                0L -> "turn/start" to buildJsonObject { put("turn", turn) }
                1L, 256L -> "user/message" to buildJsonObject {
                    put("source", buildJsonObject { put("kind", "user") })
                    put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "prompt $seq") }) })
                }
                510L -> "assistant/message" to buildJsonObject {
                    put("turn", turn); put("step", 1)
                    put("message", buildJsonObject { put("content", buildJsonArray { add(buildJsonObject {
                        put("type", "text"); put("text", "answer $turn")
                    }) }) })
                }
                511L -> "turn/end" to buildJsonObject {
                    put("turn", turn); put("reason", buildJsonObject { put("kind", "completed") })
                }
                else -> "reader/skip" to JsonNull
            }
            return SessionEventEnvelope(data.first, seq, seq, data.second)
        }
        private fun outline(latest: Long) = buildJsonArray {
            for (seq in 0L..latest step 512) add(buildJsonObject {
                put("turn", seq / 512); put("seq", seq); put("prompt", "turn $seq"); put("response", "answer")
            })
        }
        private fun page(before: Int, transform: (SessionEventEnvelope) -> SessionEventEnvelope = { it }) = buildJsonObject {
            put("records", buildJsonArray {
                for (seq in maxOf(0, before - READER_MAX_ROWS) until before) {
                    val event = transform(event(seq.toLong()))
                    add(encodeToJsonElement(SessionHistoryRecord.serializer(), SessionHistoryRecord.Event(
                        event = SessionWireEvent(event.type, seq, event.time, event.data))))
                }
            })
            put("hasMore", before > READER_MAX_ROWS)
        }
    }

    @Test fun olderTurnSpansFourBoundedRequestsAndPublishesOneGrowingRange() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.open(1024, 1151)
            val original = f.snapshot
            f.answer = { request ->
                assertSame("no physical page replaces the published transcript", original, f.snapshot)
                page(request.getValue("beforeSeq").jsonPrimitive.int)
            }
            f.reader.loadOlder()
            assertNull(f.current.error?.stackTraceToString(), f.current.error)
            assertEquals(listOf(1024, 896, 768, 640), f.requestedBefore)
            assertEquals(512L..1151L, f.current.loadedRange)
            assertTrue(f.snapshot.nodes.containsAll(original.nodes))
            assertFalse(f.current.hasNewer)
            assertTrue(f.current.hasOlder)
            assertNull(f.current.error?.stackTraceToString(), f.current.failed)
            assertTrue(f.snapshot.journal.isEmpty())
            assertTrue(f.snapshot.effectiveSurface.isEmpty())
        } finally { f.close() }
    }

    @Test fun newerTurnAppends512EventsOfCoverageWithoutLosingOldestPublicNodes() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.open(0, 511, latest = 1151)
            val original = f.snapshot
            f.reader.loadNewer()
            assertNull(f.current.error?.stackTraceToString(), f.current.error)
            assertEquals(listOf(640, 768, 896, 1024), f.requestedBefore)
            assertEquals(0L..1023L, f.current.loadedRange)
            assertTrue(f.snapshot.nodes.containsAll(original.nodes))
            assertFalse(f.current.hasOlder)
            assertTrue(f.current.hasNewer)
            assertNull(f.current.error?.stackTraceToString(), f.current.failed)
        } finally { f.close() }
    }

    @Test fun missingOutlineFindsTurnStartNotSteeringPrompt() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.open(400, 511, withOutline = false)
            f.reader.loadOlder()
            assertEquals(listOf(400, 272, 144, 16), f.requestedBefore)
            assertEquals(0L..511L, f.current.loadedRange)
            assertFalse(f.current.hasOlder)
            assertNull(f.current.error?.stackTraceToString(), f.current.failed)
        } finally { f.close() }
    }

    @Test fun missingOutlineNewerStopsAtTurnEndNotSteeringPrompt() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.open(0, 127, latest = 511, withOutline = false)
            f.reader.loadNewer()
            assertEquals(0L..511L, f.current.loadedRange)
            assertTrue(f.calls >= 2)
            assertFalse(f.current.hasNewer)
            assertNull(f.current.error?.stackTraceToString(), f.current.failed)
        } finally { f.close() }
    }

    @Test fun nextTurnStartClosesPartialTurnWithoutStartOrEndMarker() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.open(400, 499, latest = 1023, withOutline = false)
            f.answer = { request -> page(request.getValue("beforeSeq").jsonPrimitive.int) {
                if (it.seq == 511L) it.copy(type = "reader/skip", data = JsonNull) else it
            } }
            f.reader.loadNewer()
            assertEquals(400L..511L, f.current.loadedRange)
            assertTrue(f.current.hasNewer)
        } finally { f.close() }
    }

    @Test fun liveAppendDuringOlderScanSurvivesPublicationAndKeepsViewport() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.open(1024, 1151)
            f.reader.updateViewport(1100, false)
            f.answer = { request ->
                val before = request.getValue("beforeSeq").jsonPrimitive.int
                if (before == 896) f.reader.append(f.token, event(1152))
                page(before)
            }
            f.reader.loadOlder()
            assertEquals(512L..1152L, f.current.loadedRange)
            assertEquals(1100L, f.reader.captureViewport()?.anchorSeq)
            assertFalse(f.current.hasNewer)
        } finally { f.close() }
    }

    @Test fun all8224CoverageAndPublicMessagesSurviveWithoutRawJournalRetention() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.open(8096, 8223)
            var actions = 0
            while (f.current.loadedRange!!.first > 0) {
                val previous = f.current.loadedRange!!.first
                f.reader.loadOlder()
                assertNull(f.current.error?.stackTraceToString(), f.current.failed)
                assertTrue(f.current.loadedRange!!.first < previous)
                assertEquals(8223L, f.current.loadedRange!!.last)
                check(++actions <= 18)
            }
            val users = f.snapshot.nodes.filterIsInstance<UserMessageNode>()
            for (seq in 8224L..8255L) f.reader.append(f.token, event(seq))
            assertEquals(0L..8255L, f.current.loadedRange)
            assertTrue(f.snapshot.nodes.containsAll(users))
            assertTrue(f.snapshot.journal.isEmpty())
            assertTrue(f.snapshot.effectiveSurface.isEmpty())
            assertTrue(f.snapshot.nodes.size < 200)
            assertTrue(f.reader.latest())
            assertEquals(0L..8255L, f.current.loadedRange)
        } finally { f.close() }
    }

    @Test fun bookmarkRestoresPositionAfterGrowthAndCacheClearWithoutReplacingData() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.open(512, 639)
            f.reader.updateViewport(600, false)
            val bookmark = f.reader.captureViewport()!!
            f.reader.loadOlder()
            val publicNodes = f.snapshot.nodes
            f.cache.clear()
            f.reader.updateViewport(1, false)
            assertTrue(f.reader.restoreViewport(bookmark))
            assertEquals(0L..639L, f.current.loadedRange)
            assertEquals(publicNodes, f.snapshot.nodes)
            assertEquals(600L, f.reader.captureViewport()?.anchorSeq)
            assertTrue(f.cache.latestHistory(f.source.host, f.archive).events.isEmpty())
        } finally { f.close() }
    }

    @Test fun clearRejectsOldBaselineWritesButAcceptsOnlyNewLiveRecord() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.open(0, 639)
            val retained = f.snapshot.nodes
            f.cache.clear()
            f.reader.baseline(f.token, (512L..639L).map(::event), 639, true)
            assertEquals(retained, f.snapshot.nodes)
            assertTrue(f.cache.latestHistory(f.source.host, f.archive).events.isEmpty())
            f.reader.append(f.token, event(640))
            assertEquals(0L..640L, f.current.loadedRange)
            assertEquals(listOf(640L), f.cache.latestHistory(f.source.host, f.archive).events.map { it.seq })
            assertNull(f.cache.read(f.source.host, f.archive, f.cache.ticket()))
        } finally { f.close() }
    }

    @Test fun clearDuringPageCannotPublishAndRetryDoesNotRepersistRetainedRam() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.open(512, 639)
            val original = f.snapshot.nodes
            f.answer = { request -> f.cache.clear(); page(request.getValue("beforeSeq").jsonPrimitive.int) }
            f.reader.loadOlder()
            assertEquals(HistoryDirection.OLDER, f.current.failed)
            assertEquals(original, f.snapshot.nodes)
            assertEquals(512L..639L, f.current.loadedRange)
            assertTrue(f.cache.latestHistory(f.source.host, f.archive).events.isEmpty())
            f.answer = { page(it.getValue("beforeSeq").jsonPrimitive.int) }
            f.reader.loadOlder()
            assertEquals(0L..639L, f.current.loadedRange)
            assertNull(f.current.error?.stackTraceToString(), f.current.failed)
            assertNull(f.cache.historyRange(f.source.host, f.archive, 0, 639))
        } finally { f.close() }
    }

    @Test fun intermediateFailureKeepsPublishedRangeAndRetryReusesCommittedPrefix() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.open(512, 639)
            val original = f.snapshot.nodes
            f.answer = { request ->
                val before = request.getValue("beforeSeq").jsonPrimitive.int
                if (before == 384) error("fixture page failed")
                page(before)
            }
            f.reader.loadOlder()
            assertEquals(512L..639L, f.current.loadedRange)
            assertEquals(original, f.snapshot.nodes)
            assertEquals(HistoryDirection.OLDER, f.current.failed)
            assertNotNull(f.cache.historyRange(f.source.host, f.archive, 384, 511))
            f.answer = { page(it.getValue("beforeSeq").jsonPrimitive.int) }
            f.reader.loadOlder()
            assertEquals(listOf(512, 384, 384, 256, 128), f.requestedBefore)
            assertEquals(0L..639L, f.current.loadedRange)
            assertNull(f.current.error?.stackTraceToString(), f.current.failed)
        } finally { f.close() }
    }

    @Test fun retiredReadCannotPublishOrClearSuccessorLoading() = runBlocking<Unit> {
        val f = Fixture()
        val oldStarted = CompletableDeferred<Unit>(); val oldRelease = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>(); val newRelease = CompletableDeferred<Unit>()
        var old: Job? = null; var newer: Job? = null
        try {
            f.open(512, 639)
            val bookmark = f.reader.captureViewport()!!
            var count = 0
            f.answer = { request ->
                when (++count) {
                    1 -> withContext(NonCancellable) { oldStarted.complete(Unit); oldRelease.await() }
                    2 -> { newStarted.complete(Unit); newRelease.await() }
                }
                page(request.getValue("beforeSeq").jsonPrimitive.int)
            }
            old = launch(Dispatchers.Default) { f.reader.loadOlder() }
            withTimeout(5_000) { oldStarted.await() }
            assertTrue(f.reader.restoreViewport(bookmark))
            newer = launch(Dispatchers.Default) { f.reader.loadOlder() }
            withTimeout(5_000) { newStarted.await() }
            oldRelease.complete(Unit)
            withTimeout(5_000) { requireNotNull(old).join() }
            assertEquals(HistoryDirection.OLDER, f.current.loading)
            assertEquals(512L..639L, f.current.loadedRange)
            assertNull(f.cache.historyRange(f.source.host, f.archive, 384, 511))
            newRelease.complete(Unit)
            withTimeout(5_000) { requireNotNull(newer).join() }
            assertEquals(0L..639L, f.current.loadedRange)
            assertNull(f.current.loading)
        } finally {
            oldRelease.complete(Unit); newRelease.complete(Unit)
            old?.cancelAndJoin(); newer?.cancelAndJoin(); f.close()
        }
    }

    @Test fun generationAndSelectionRetirementRejectLatePageIncludingSameSessionReturn() = runBlocking<Unit> {
        for (retire in listOf("follow", "selection", "disconnect", "close")) {
            val f = Fixture(); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var request: Job? = null
            try {
                f.open(512, 639)
                f.answer = { args -> withContext(NonCancellable) {
                    started.complete(Unit); release.await(); page(args.getValue("beforeSeq").jsonPrimitive.int)
                } }
                request = launch(Dispatchers.Default) { f.reader.loadOlder() }
                withTimeout(5_000) { started.await() }
                when (retire) {
                    "follow" -> f.token = f.reader.beginFollow(f.source)
                    "selection" -> {
                        f.reader.beginFollow(f.source.copy(sessionId = "other", selectionEpoch = 2))
                        f.source = f.source.copy(selectionEpoch = 3)
                        f.token = f.reader.beginFollow(f.source)
                    }
                    "disconnect" -> f.reader.disconnect()
                    else -> f.reader.close()
                }
                release.complete(Unit)
                withTimeout(5_000) { requireNotNull(request).join() }
                assertNull(f.cache.historyRange(f.source.host, f.archive, 384, 511))
                assertNull(f.current.loading)
                if (retire == "disconnect") {
                    assertEquals(512L..639L, f.current.loadedRange)
                    assertNull(f.current.cut)
                    val calls = f.calls
                    f.reader.loadOlder()
                    assertEquals("disconnected reads use cache only", calls, f.calls)
                }
            } finally { release.complete(Unit); request?.cancelAndJoin(); f.close() }
        }
    }

    @Test fun reconnectPreservesBookmarkAndBridgesOnlyMissingNewerCoverage() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.open(0, 639)
            f.reader.updateViewport(500, false)
            val bookmark = f.reader.captureViewport()!!
            f.token = f.reader.beginFollow(f.source)
            assertEquals(0L..639L, f.current.loadedRange)
            f.reader.baseline(f.token, (1024L..1151L).map(::event), 1151, true)
            withTimeout(5_000) { f.reader.state.first { it.loadedRange == 0L..1151L } }
            assertEquals(listOf(768, 896, 1024), f.requestedBefore)
            assertEquals(500L, f.reader.captureViewport()?.anchorSeq)
            assertTrue(f.reader.restoreViewport(bookmark))
            assertEquals(0L..1151L, f.current.loadedRange)
        } finally { f.close() }
    }

    @Test fun authoritativeTruncationRemovesInvalidSuffixOnly() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.open(0, 639)
            f.reader.baseline(f.token, (173L..300L).map(::event), 300, true, bridgeReconnect = false)
            assertEquals(0L..300L, f.current.loadedRange)
            assertTrue(f.snapshot.nodes.all { it.seq <= 300 })
            assertNull(f.cache.historyRange(f.source.host, f.archive, 301, 639))
            assertFalse(f.current.hasNewer)
        } finally { f.close() }
    }

    @Test fun seekAndLatestKeepPreviousLoadedPublicContent() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.open(1024, 1151)
            val oldNodes = f.snapshot.nodes
            assertTrue(f.reader.seek(0))
            assertEquals(0L..1151L, f.current.loadedRange)
            assertTrue(f.snapshot.nodes.containsAll(oldNodes))
            val calls = f.calls
            assertTrue(f.reader.seek(512))
            assertEquals(calls, f.calls)
            assertTrue(f.reader.latest())
            assertEquals(0L..1151L, f.current.loadedRange)
        } finally { f.close() }
    }

    @Test fun cancelledLatestRefreshClearsLoadingAndKeepsLoadedRange() = runBlocking<Unit> {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); var request: Job? = null
        try {
            f.open(0, 127)
            f.reader.append(f.token, event(640))
            f.refresh = { entered.complete(Unit); awaitCancellation() }
            request = launch(Dispatchers.Default) { f.reader.latest() }
            withTimeout(5_000) { entered.await() }
            assertEquals(HistoryDirection.NEWER, f.current.loading)
            request.cancelAndJoin()
            assertNull(f.current.loading)
            assertEquals(0L..127L, f.current.loadedRange)
        } finally { request?.cancelAndJoin(); f.close() }
    }

    @Test fun malformedPageDoesNotPublishOrLeakIntoRawNamespace() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.open(512, 639)
            f.acknowledge = false
            f.reader.loadOlder()
            assertEquals(HistoryDirection.OLDER, f.current.failed)
            assertEquals(512L..639L, f.current.loadedRange)
            assertNull(f.cache.historyRange(f.source.host, f.archive, 384, 511))
            assertTrue(f.cache.latestHistory(f.source.host, f.source.sessionId).events.isEmpty())
        } finally { f.close() }
    }

    @Test fun exhaustedHiddenPrefixAddsCoverageNotRetainedSkipObjects() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.open(128, 255, withOutline = false)
            f.answer = { request -> page(request.getValue("beforeSeq").jsonPrimitive.int) {
                it.copy(type = "reader/skip", data = JsonNull)
            } }
            f.reader.loadOlder()
            assertEquals(0L..255L, f.current.loadedRange)
            assertTrue(f.snapshot.nodes.isEmpty())
            assertFalse(f.current.hasOlder)
            assertTrue(f.snapshot.journal.isEmpty())
        } finally { f.close() }
    }

    @Test fun journalDetailsAreExplicitAndClearKeepsPublicTranscriptReadable() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.open(0, 639)
            assertTrue(f.snapshot.journal.isEmpty())
            assertEquals((0L..639L).toList(), f.reader.readEvents(0, 639).map { it.seq })
            assertTrue(f.snapshot.journal.isEmpty())
            val public = f.snapshot.nodes
            f.cache.clear()
            assertTrue(runCatching { f.reader.readEvents(0, 639) }.isFailure)
            assertEquals(public, f.snapshot.nodes)
            assertEquals(0L..639L, f.current.loadedRange)
        } finally { f.close() }
    }
}
