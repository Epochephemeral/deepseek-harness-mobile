package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.wire.dto.SessionAddress
import com.labteto.dshmobile.core.wire.dto.SessionAssistantStreamBaseline
import com.labteto.dshmobile.core.wire.dto.SessionFollowFrame
import com.labteto.dshmobile.core.wire.dto.SessionHistoryRecord
import com.labteto.dshmobile.core.wire.dto.SessionPage
import com.labteto.dshmobile.core.wire.dto.SessionWireEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionHistoryBootstrapTest {
    private val address = SessionAddress.Session(sessionId = "session-a")
    private fun record(seq: Int) = SessionHistoryRecord.Event(
        event = SessionWireEvent(type = "assistant/message", seq = seq, time = 1),
    )

    @Test fun `complete short histories need no second request`() = runTest {
        val opening = SessionFollowFrame.Snapshot(cursor = 0, records = listOf(record(0)))
        val result = hydrateSessionSnapshot(address, opening, 60) {
            error("a complete opening must not fetch history again")
        }
        assertSame(opening, result)
    }

    @Test fun `history uses the exact opening cut and keeps projections and live baseline`() = runTest {
        val opening = SessionFollowFrame.Snapshot(cursor = 25, records = listOf(record(25)), hasMore = true,
            header = buildJsonObject { put("id", "session-a") },
            projections = buildJsonObject { put("asOfSeq", 25) },
            assistantStream = SessionAssistantStreamBaseline(revision = 7))
        val full = (10..25).map(::record)
        val result = hydrateSessionSnapshot(address, opening, 60) { request ->
            assertEquals(address, request.address)
            assertEquals(25, request.throughSeq)
            assertNull(request.beforeSeq)
            assertEquals(60, request.maxMessages)
            RpcResult.Ok(SessionPage(full, hasMore = true))
        }
        assertEquals(opening.copy(records = full), result)
    }

    @Test fun `the full page decides whether older history remains`() = runTest {
        val opening = SessionFollowFrame.Snapshot(cursor = 2, records = listOf(record(2)), hasMore = true)
        val full = (0..2).map(::record)
        val result = hydrateSessionSnapshot(address, opening, 60) { RpcResult.Ok(SessionPage(full)) }
        assertEquals(full, result.records)
        assertFalse(result.hasMore)
    }

    @Test fun `subagent history retains its parent authority`() = runTest {
        val child = SessionAddress.Subagent(parentSessionId = "parent", childSessionId = "child", mode = "one-shot")
        hydrateSessionSnapshot(child, SessionFollowFrame.Snapshot(cursor = 4, hasMore = true), 60) {
            assertEquals(child, it.address)
            RpcResult.Ok(SessionPage())
        }
    }

    @Test fun `a rejected history read is surfaced rather than marking the tail complete`() = runTest {
        val failure = RpcError("session/not-found", "session disappeared")
        try {
            hydrateSessionSnapshot(address, SessionFollowFrame.Snapshot(cursor = 4, hasMore = true), 60) {
                RpcResult.Err(failure)
            }
            fail("expected the history error")
        } catch (error: RemoteStreamException) {
            assertEquals(failure, error.error)
            assertFalse(error.carrier)
        }
    }

    @Test fun `switching sessions cancels the pending page and cannot publish its result`() = runTest {
        val page = CompletableDeferred<RpcResult<SessionPage>>()
        var published = false
        val job = launch {
            hydrateSessionSnapshot(address, SessionFollowFrame.Snapshot(cursor = 4, hasMore = true), 60) {
                page.await()
            }
            published = true
        }
        runCurrent()
        job.cancelAndJoin()
        page.complete(RpcResult.Ok(SessionPage(listOf(record(4)))))
        runCurrent()
        assertFalse(published)
    }

    @Test fun `the real mux retains live events arriving while history is downloaded`() = runTest {
        lateinit var sink: WsChannelSink
        val mux = RemoteStreamMux { receiver ->
            sink = receiver
            object : WsChannel("http://stub/api/remote.mux", OkHttpClient(), receiver) {
                override fun start() = receiver.onOpen()
                override fun send(text: String) = true
                override fun close() = Unit
            }
        }
        mux.start()
        val stream = mux.open("session/follow")
        val page = CompletableDeferred<RpcResult<SessionPage>>()
        val received = mutableListOf<SessionFollowFrame>()
        val reader = launch {
            val opening = WireJson.decodeFromJsonElement(SessionFollowFrame.serializer(), stream.receive()!!)
                as SessionFollowFrame.Snapshot
            received += hydrateSessionSnapshot(address, opening, 60) { page.await() }
            received += WireJson.decodeFromJsonElement(SessionFollowFrame.serializer(), stream.receive()!!)
        }
        fun send(frame: SessionFollowFrame) {
            val value = WireJson.encodeToJsonElement(SessionFollowFrame.serializer(), frame)
            sink.onMessage("""{"type":"item","streamId":"1","value":$value}""")
        }
        try {
            send(SessionFollowFrame.Snapshot(cursor = 25, records = listOf(record(25)), hasMore = true))
            runCurrent()
            send(SessionFollowFrame.Entry(record = record(26)))
            runCurrent()
            assertTrue(received.isEmpty())
            val full = (0..25).map(::record)
            page.complete(RpcResult.Ok(SessionPage(full)))
            reader.join()
            assertEquals(full, (received[0] as SessionFollowFrame.Snapshot).records)
            assertEquals(26, (received[1] as SessionFollowFrame.Entry).record.event.seq)
        } finally {
            reader.cancelAndJoin()
            stream.cancel()
            mux.close()
        }
    }
}
