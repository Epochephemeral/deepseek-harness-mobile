package com.labteto.dshmobile.core.session

import com.labteto.dshmobile.core.storage.DiskCacheStore
import com.labteto.dshmobile.core.wire.dto.*
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiskPreviewChunksTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun start(id: String = "a") = SessionAssistantStreamFrame.Start(attemptId = id, revision = 1, startedAfterSeq = 0, turn = 2, step = 1)
    private fun chunk(index: Int, text: String) = SessionAssistantStreamFrame.Chunk(attemptId = "a", revision = index + 2, index = index,
        time = index.toLong(), chunk = buildJsonObject { put("type", "text-delta"); put("index", 0); put("text", text) })
    private fun event(type: String, seq: Long, data: String) = SessionEventEnvelope(type, seq, seq, Json.parseToJsonElement(data))

    @Test fun `leased raw chunks survive cache clear and streaming snapshots retain exact content`() {
        val store = DiskCacheStore(temporary.newFolder())
        val live = AssistantLiveState(store).apply { accept(start()) }
        val durable = EventFold("s").fold(emptyList())
        repeat(2_000) { index -> live.accept(chunk(index, "片段-$index ")) }
        assertTrue(store.usage("stream").protectedBytes > 0)
        val cleared = store.clear()
        assertTrue(cleared.protectedBytes > 0)
        assertEquals(0L, cleared.deletedBytes)
        assertEquals(EventFold("s").fold(emptyList(), live.transientEnvelopes()), live.transientSnapshot(durable))
        assertEquals(2_000L, live.previewAppliedChunks)
        val snapshot = live.transientSnapshot(durable)
        repeat(100) { assertSame(snapshot, live.transientSnapshot(durable)) }
        live.clear()
        assertEquals(0L, store.usage("stream").totalBytes)
    }

    @Test fun `legacy baseline replays disk journal incrementally and can rebase without materializing all chunks`() {
        val store = DiskCacheStore(temporary.newFolder())
        val live = AssistantLiveState(store).apply { accept(start()) }
        val fold = EventFold("s")
        val events = listOf(event("assistant/chunk", 1,
            """{"turn":2,"step":1,"chunk":{"type":"text-delta","index":0,"text":"legacy "}}"""))
        val durable = fold.fold(events)
        repeat(100) { index ->
            live.accept(chunk(index, "$index "))
            assertEquals(fold.fold(events, live.transientEnvelopes()), live.transientSnapshot(durable))
        }
        val rebased = events + event("session/title", 2, """{"title":"updated"}""")
        assertEquals(fold.fold(rebased, live.transientEnvelopes()), live.transientSnapshot(fold.fold(rebased)))
        live.clear()
        assertEquals(0L, store.usage("stream").totalBytes)
    }

    @Test fun `every attempt retirement releases and deletes its raw journal`() {
        val retirements: List<(AssistantLiveState) -> Unit> = listOf(
            { it.clear() },
            { it.seed(null) },
            { it.accept(start("new")); it.clear() },
            { it.accept(chunk(3, "gap")) },
            { it.acceptDurable("assistant/message", 2, 1, 2, null) },
            { it.accept(SessionAssistantStreamFrame.End(attemptId = "a", revision = 9, index = 1,
                outcome = AssistantStreamOutcome.Committed(eventType = "assistant/message", seq = 2))) },
            { it.accept(SessionAssistantStreamFrame.End(attemptId = "a", revision = 9, index = 1,
                outcome = AssistantStreamOutcome.Abandoned())) },
        )
        retirements.forEach { retire ->
            val root = temporary.newFolder()
            val store = DiskCacheStore(root)
            val live = AssistantLiveState(store).apply { accept(start()); accept(chunk(0, "hi")) }
            val file = File(root, "stream").listFiles()!!.single()
            assertTrue(file.exists())
            retire(live)
            assertFalse(file.exists())
            assertEquals(0L, store.usage("stream").protectedBytes)
            assertEquals(0L, store.usage("stream").totalBytes)
        }
    }
}
