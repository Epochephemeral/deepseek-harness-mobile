package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.UserMessageNode
import org.junit.Assert.*
import org.junit.Test

class TranscriptDisplayRowsTest {
    private fun calls(first: Long, last: Long) = (first..last).map {
        ToolCallNode(it, "call-$it", "tool-$it", "{}", 1, 1)
    }
    private fun message(seq: Long) = TranscriptRow.Message(
        UserMessageNode(seq, "u$seq", listOf(ChatBlock("text", "message $seq")), "user"))

    @Test fun `expanded reverse items keep header above chronological children with distinct keys`() {
        val semantic = listOf(message(10), TranscriptRow.Tools(calls(5, 7)), message(4))
        val rows = transcriptDisplayRows(semantic, setOf(5))
        assertEquals(listOf(10L, "tool-call-7", "tool-call-6", "tool-call-5", 5L, 4L), rows.map { it.key })
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        assertEquals(2, transcriptDisplayIndex(rows, 6))
        assertEquals(5, transcriptDisplayIndex(rows, 4))
        assertEquals(6L, historySequenceOfKey("tool-call-6"))
    }

    @Test fun `collapse resolves a removed child by group membership not sequence proximity`() {
        val semantic = listOf(message(100), TranscriptRow.Tools(calls(5, 7), identitySeq = 6), message(4))
        val rows = transcriptDisplayRows(semantic, emptySet())
        assertEquals(1, transcriptDisplayIndex(rows, 7))
        assertEquals(1, transcriptDisplayAnchorIndex(rows, toolDetailKey(7)))
        assertEquals(1, transcriptDisplayAnchorIndex(rows, toolDetailKey(5)))
        assertNull(transcriptDisplayAnchorIndex(rows, toolDetailKey(8)))
        assertNull(transcriptDisplayAnchorIndex(rows, toolDetailKey(99)))
    }

    @Test fun `prepend append and boundary splits keep each expanded call exactly once`() {
        val original = listOf(TranscriptRow.Tools(calls(128, 639)))
        val extended = retainToolGroupIdentities(listOf(TranscriptRow.Tools(calls(0, 767))), original)
        val rows = transcriptDisplayRows(extended, setOf(128))
        assertEquals(769, rows.size)
        assertEquals(128L, rows.last().key)
        assertEquals(383, transcriptDisplayAnchorIndex(rows, toolDetailKey(384)))
        val split = listOf(TranscriptRow.Tools(calls(5, 7)), message(4), TranscriptRow.Tools(calls(1, 3)))
        val splitRows = transcriptDisplayRows(split, setOf(5, 1))
        assertEquals(9, splitRows.size)
        assertEquals(splitRows.size, splitRows.map { it.key }.toSet().size)
        assertEquals(6, splitRows.filterIsInstance<TranscriptDisplayRow.ToolDetail>().size)
    }

    @Test fun `collapsed groups do not materialize display children`() {
        val rows = transcriptDisplayRows(listOf(TranscriptRow.Tools(calls(0, 2047))), emptySet())
        assertEquals(1, rows.size)
        assertTrue(rows.single() is TranscriptDisplayRow.Semantic)
    }
}
