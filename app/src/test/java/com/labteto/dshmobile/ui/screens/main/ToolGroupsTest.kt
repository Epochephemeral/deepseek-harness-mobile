package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.*
import org.junit.Assert.*
import org.junit.Test

class ToolGroupsTest {
    private fun call(seq: Long) = ToolCallNode(seq, "c$seq", "read", "{}", 1, 1)
    private fun user(seq: Long) = UserMessageNode(seq, "u$seq", listOf(ChatBlock("text", "hello")), "user")
    private fun assistant(seq: Long) = AssistantMessageNode(seq, "a$seq", 1, 1, listOf(ChatBlock("text", "done")))
    @Test fun `adjacent calls group without crossing visible message boundaries`() {
        val rows = groupToolRows(listOf(user(1), call(2), call(3), assistant(4), call(5), user(6)))
        assertEquals(5, rows.size)
        assertEquals(listOf(2L, 3L), (rows[1] as TranscriptRow.Tools).calls.map { it.seq })
        assertEquals(listOf(5L), (rows[3] as TranscriptRow.Tools).calls.map { it.seq })
    }
    @Test fun `injected context groups apart from tools and the rewind marker stays a message`() {
        val skill = UserMessageNode(2, "s", listOf(ChatBlock("text", "skill body")), "skill-invocation")
        val system = OtherNode(3, "system/message", kotlinx.serialization.json.JsonNull)
        val marker = UserMessageNode(5, "r", emptyList(), REWIND_SOURCE_KIND)
        val rows = groupToolRows(listOf(user(1), skill, system, call(4), marker))
        assertEquals(listOf(2L, 3L), (rows[1] as TranscriptRow.Context).nodes.map { it.seq })
        assertEquals(listOf(4L), (rows[2] as TranscriptRow.Tools).calls.map { it.seq })
        assertTrue(rows[3] is TranscriptRow.Message)
    }
    /** Protocol bookkeeping and tool-only assistant shells never impose a fixed call-count chunk. */
    private fun longToolRun(): List<ChatNode> = buildList {
        add(TurnStartNode(1, 1))
        repeat(40) { index ->
            val seq = 10L + index * 10
            val id = "call-$index"
            add(AssistantMessageNode(seq, "a$index", 1, index + 1, listOf(
                ChatBlock("text", "  "), ChatBlock("reasoning", ""),
                ChatBlock("tool-call", toolCallId = id, toolName = "read"),
            )))
            add(OtherNode(seq + 1, "step/start", kotlinx.serialization.json.JsonNull))
            add(OtherNode(seq + 2, "reader/skip", kotlinx.serialization.json.JsonNull))
            add(ToolCallNode(seq + 3, id, if (index % 2 == 0) "read" else "bash", "{}", 1, index + 1))
            add(ToolResultNode(seq + 4, id, kotlinx.serialization.json.JsonPrimitive("ok"), false, 1, index + 1))
            add(OtherNode(seq + 5, "step/end", kotlinx.serialization.json.JsonNull))
        }
        add(TurnEndNode(500, 1, "completed"))
    }

    @Test fun `forty calls across empty assistant steps skips and results form one group`() {
        for (running in listOf(false, true)) {
            val rows = transcriptRows(longToolRun(), running)
            assertEquals(1, rows.size)
            val tools = rows.single() as TranscriptRow.Tools
            assertEquals(40, tools.calls.size)
            assertEquals((0 until 40).map { "call-$it" }, tools.calls.map { it.callId })
        }
    }

    @Test fun `visible public replies and users split a long semantic run not six or eight call chunks`() {
        val reply = assistant(107).copy(blocks = listOf(ChatBlock("text", "Progress so far")))
        val prompt = user(257)
        val rows = transcriptRows((longToolRun() + reply + prompt).sortedBy { it.seq }, running = false)
        assertEquals(5, rows.size)
        assertEquals(listOf(10, 15, 15), rows.filterIsInstance<TranscriptRow.Tools>().map { it.calls.size })
        assertEquals(listOf(reply, prompt), rows.filterIsInstance<TranscriptRow.Message>().map { it.node })
        assertFalse(rows.any { it is TranscriptRow.Process })
    }

    @Test fun `invisible runtime turn boundary splits otherwise adjacent calls`() {
        val rows = transcriptRows(listOf(
            TurnStartNode(1, 1), call(2), TurnEndNode(3, 1, "completed"),
            TurnStartNode(4, 2), call(5).copy(turn = 2),
        ), running = false)
        assertEquals(listOf(listOf(2L), listOf(5L)), rows.filterIsInstance<TranscriptRow.Tools>().map { group -> group.calls.map { it.seq } })
    }

    @Test fun `group key remains first call as stream grows`() {
        assertEquals(groupToolRows(listOf(call(1))).first().seq, groupToolRows(listOf(call(1), call(2))).first().seq)
        assertTrue(groupToolRows(emptyList()).isEmpty())
    }
}
