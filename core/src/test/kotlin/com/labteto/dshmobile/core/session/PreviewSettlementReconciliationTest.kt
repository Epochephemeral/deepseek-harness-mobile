package com.labteto.dshmobile.core.session

import com.labteto.dshmobile.core.storage.DiskCacheStore
import com.labteto.dshmobile.core.wire.dto.SessionAssistantStreamFrame
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreviewSettlementReconciliationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun start(id: String = "attempt", turn: Int = 2, after: Int = 0) =
        SessionAssistantStreamFrame.Start(attemptId = id, revision = 1, startedAfterSeq = after, turn = turn, step = 1)
    private fun chunk(index: Int, id: String = "attempt") = SessionAssistantStreamFrame.Chunk(
        attemptId = id, revision = index + 2, index = index, time = index.toLong(),
        chunk = buildJsonObject { put("type", "text-delta"); put("index", 0); put("text", "preview-$index") })
    private fun event(type: String, seq: Long, turn: Int = 2, step: Int = 1, op: String? = null) =
        SessionEventEnvelope(type, seq, seq, buildJsonObject {
            put("turn", turn); put("step", step)
            putJsonObject("message") { putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "settled") }) } }
        }, surfaceOp = op)

    @Test fun `paged matching settlement retires preview exactly like durable entry and releases disk`() {
        val disk = DiskCacheStore(temporary.newFolder())
        val live = AssistantLiveState(disk).apply { accept(start()); accept(chunk(0)) }
        assertTrue(disk.usage("stream").protectedBytes > 0)
        val durable = EventFold("s").fold(listOf(event("assistant/message", 10, op = "append")))
        assertSame(durable, live.transientSnapshot(durable))
        assertFalse(live.active)
        assertEquals(0L, disk.usage("stream").protectedBytes)
        assertEquals(AssistantLiveState.Change.NONE, live.accept(chunk(1)))
        assertSame(durable, live.transientSnapshot(durable))
        assertFalse(durable.nodes.filterIsInstance<AssistantMessageNode>().any { it.streaming })

        // A settlement with no surface message follows the same existing attempt-retirement path.
        live.accept(start("next")); live.accept(chunk(0, "next"))
        val attempted = EventFold("s").fold(listOf(event("assistant/attempt", 11)))
        assertSame(attempted, live.transientSnapshot(attempted))
        assertFalse(live.active)
        assertEquals(0L, disk.usage("stream").totalBytes)
    }

    @Test fun `compact reader metadata retires paged settlements without retaining raw journal`() {
        val live = AssistantLiveState().apply { accept(start()); accept(chunk(0)) }
        val timeline = ReaderTurnTimeline("s")
        val durable = timeline.mergeRange(listOf(event("assistant/message", 10, op = "append")))
        assertTrue(durable.journal.isEmpty())
        assertSame(durable, live.transientSnapshot(durable))
        assertFalse(live.active)
        live.accept(start("next", after = 10)); live.accept(chunk(0, "next"))
        val attempted = timeline.append(event("assistant/attempt", 11))
        assertSame(attempted, live.transientSnapshot(attempted))
        assertFalse(live.active)
        live.clear()
    }

    @Test fun `compact reader live preview follows raw coverage not last visible message`() {
        val live = AssistantLiveState().apply { accept(start(after = 99)); accept(chunk(0)) }
        val timeline = ReaderTurnTimeline("s")
        val durable = timeline.mergeRange((100L..227).map { SessionEventEnvelope("reader/skip", it, it, JsonNull) })
        val preview = live.transientSnapshot(durable).nodes.filterIsInstance<AssistantMessageNode>().single()
        assertTrue(preview.streaming)
        assertEquals(228L, preview.seq)
        assertTrue(live.active)
        live.clear()
    }

    @Test fun `old wrong turn wrong step and positional replacement cannot retire open attempt`() {
        val live = AssistantLiveState().apply { accept(start(after = 10)); accept(chunk(0)) }
        val journal = listOf(
            event("assistant/message", 10), // logged no later than this attempt's start
            event("assistant/message", 11, turn = 3),
            event("assistant/message", 12, step = 2),
            event("assistant/message", 13, op = "replace"),
            event("assistant/message", 14, op = "{\"op\":\"replace\",\"start\":0,\"end\":1}"),
            event("assistant/attempt", 15, step = 2),
            event("fixture/event", 16),
        )
        val durable = EventFold("s").fold(journal)
        val result = live.transientSnapshot(durable)
        assertTrue(live.active)
        assertNotSame(durable, result)
        assertTrue(result.nodes.filterIsInstance<AssistantMessageNode>().any { it.streaming })
        assertEquals(EventFold("s").fold(journal, live.transientEnvelopes()), result)
        live.clear()
    }

    @Test fun `reconciliation runs once per durable identity and is reset for each new attempt`() {
        val live = AssistantLiveState().apply { accept(start()); accept(chunk(0)) }
        val durable = EventFold("s").fold(listOf(event("assistant/message", 15, turn = 3)))
        live.transientSnapshot(durable)
        assertEquals(1L, live.durableSettlementChecks)
        repeat(30) { index -> live.accept(chunk(index + 1)); live.transientSnapshot(durable) }
        repeat(30) { live.transientSnapshot(durable) }
        assertTrue(live.active)
        assertEquals(1L, live.durableSettlementChecks)
        val changed = durable.copy(lastSeq = 16)
        live.transientSnapshot(changed)
        assertEquals(2L, live.durableSettlementChecks)
        live.accept(start("new-turn", turn = 3)); live.accept(chunk(0, "new-turn"))
        assertSame(changed, live.transientSnapshot(changed))
        assertEquals(3L, live.durableSettlementChecks)
        assertFalse(live.active)
    }
}
