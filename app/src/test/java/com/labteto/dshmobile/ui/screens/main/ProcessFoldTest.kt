package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ProcessFoldTest {
    private fun call(seq: Long) = ToolCallNode(seq, "c$seq", "read", "{}", 1, 1)
    private fun user(seq: Long) = UserMessageNode(seq, "u$seq", listOf(ChatBlock("text", "hello")), "user")
    private fun answer(seq: Long) = AssistantMessageNode(seq, "a$seq", 1, 1, listOf(ChatBlock("text", "done")))
    private fun reasoning(seq: Long) = answer(seq).copy(blocks = listOf(ChatBlock("reasoning", "thinking")))
    private val outline = Json.parseToJsonElement("""[{"turn":7,"seq":10,"prompt":"hello","response":"done"},{"turn":8,"seq":30,"prompt":"next","response":""}]""")

    @Test fun `completed runtime turn preserves prompt and final with one numbered process`() {
        val rows = transcriptRows(listOf(TurnStartNode(1, 1), user(2), reasoning(3), call(4), answer(5), TurnEndNode(6, 1, "completed")), false)
        assertEquals(listOf(2L, 4L, 5L), rows.map { it.seq })
        assertTrue(rows.none { it is TranscriptRow.Process })
        assertEquals(1, historyRowIndex(rows, 4))
    }

    @Test fun `outline supplies missing local start and stable key as page expands`() {
        val tail = listOf(call(14), reasoning(15), call(16), answer(18), TurnEndNode(20, 7, "completed"))
        val partial = transcriptRows(tail, true, outline)
        val full = transcriptRows(listOf(TurnStartNode(10, 7), user(11), reasoning(12)) + tail, true, outline)
        assertTrue(partial.none { it is TranscriptRow.Process })
        assertTrue(full.none { it is TranscriptRow.Process })
        assertEquals(18L, partial.last().seq)
    }

    @Test fun `outline response and idle state do not prove successful completion`() {
        val rows = transcriptRows(listOf(call(14), reasoning(15), answer(18)), false, outline)
        assertTrue(rows.none { it is TranscriptRow.Process })
    }

    @Test fun `failed interrupted aborted and unknown endings remain open`() {
        for (reason in listOf("error", "interrupted", "aborted", "forked", "unknown")) {
            val rows = transcriptRows(listOf(call(14), reasoning(15), answer(18), TurnEndNode(20, 7, reason)), false, outline)
            assertTrue(reason, rows.none { it is TranscriptRow.Process })
        }
    }

    @Test fun `streaming and interrupted messages never fold`() {
        for (message in listOf(answer(18).copy(streaming = true), answer(18).copy(interrupted = true))) {
            val rows = transcriptRows(listOf(call(14), reasoning(15), message, TurnEndNode(20, 7, "completed")), true, outline)
            assertTrue(rows.none { it is TranscriptRow.Process })
        }
    }

    @Test fun `steering remains visible without multiple process headers`() {
        val rows = transcriptRows(listOf(TurnStartNode(10, 7), user(11), call(12), reasoning(13), user(14), call(15), answer(18), TurnEndNode(20, 7, "completed")), false, outline)
        assertTrue(rows.none { it is TranscriptRow.Process })
        assertNotNull(historyRowIndex(rows, 14))
    }

    @Test fun `partial window human input is not assumed to be opening prompt`() {
        val rows = transcriptRows(listOf(user(14), call(15), reasoning(16), answer(18), TurnEndNode(20, 7, "completed")), false, outline)
        assertTrue(rows.none { it is TranscriptRow.Process })
    }

    @Test fun `context stays accessible in chronological order`() {
        val context = UserMessageNode(13, "s", listOf(ChatBlock("text", "skill")), "skill-invocation")
        val rows = transcriptRows(listOf(TurnStartNode(10, 7), user(11), call(12), context, call(15), answer(18), TurnEndNode(20, 7, "completed")), false, outline)
        assertTrue(rows.none { it is TranscriptRow.Process })
        assertEquals(listOf(11L, 12L, 18L), rows.map { it.seq })
    }

    @Test fun `leading injected context stays outside the single process`() {
        val context = UserMessageNode(12, "s", listOf(ChatBlock("text", "skill")), "skill-invocation")
        val rows = transcriptRows(listOf(TurnStartNode(10, 7), user(11), context, call(15), answer(18), TurnEndNode(20, 7, "completed")), false, outline)
        assertTrue(rows.none { it is TranscriptRow.Context || it is TranscriptRow.Process })
    }

    @Test fun `adjacent calls across invisible boundaries never merge`() {
        val rows = transcriptRows(listOf(call(14), TurnEndNode(20, 7, "completed"), TurnStartNode(30, 8), call(31)), true, outline)
        assertEquals(2, rows.size)
        assertEquals(listOf(14L), (rows[0] as TranscriptRow.Tools).calls.map { it.seq })
        assertEquals(listOf(31L), (rows[1] as TranscriptRow.Tools).calls.map { it.seq })
    }

    @Test fun `sparse completed work without prompt or answer remains expandable`() {
        val rows = transcriptRows(listOf(reasoning(14), call(16), TurnEndNode(20, 7, "completed")), false, outline)
        val tools = rows.single() as TranscriptRow.Tools
        assertEquals(listOf(16L), tools.calls.map { it.seq })
        val entry = HistoryEntry(10, "hello", false, null, 7, 10, 30)
        assertEquals(16L, historyJumpTarget(entry, listOf(reasoning(14), call(16), TurnEndNode(20, 7, "completed")), false, outline))
    }

    @Test fun `mismatched runtime end does not close outline turn`() {
        val rows = transcriptRows(listOf(call(14), reasoning(16), TurnEndNode(20, 6, "completed")), false, outline)
        assertTrue(rows.none { it is TranscriptRow.Process })
    }
}
