package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.UserMessageNode
import org.junit.Assert.*
import org.junit.Test

class ToolGroupIdentityTest {
    private fun tools(vararg seqs: Long) = TranscriptRow.Tools(
        seqs.map { ToolCallNode(it, "call-$it", "read", "{}", 1, 1) },
    )

    private fun identities(rows: List<TranscriptRow>) =
        rows.filterIsInstance<TranscriptRow.Tools>().map { it.identitySeq }

    private fun retain(current: TranscriptRow.Tools, previous: TranscriptRow.Tools) =
        retainToolGroupIdentities(listOf(current), listOf(previous)).single() as TranscriptRow.Tools

    @Test fun `append retains disclosure identity`() {
        val previous = tools(4, 6)
        val result = retain(tools(4, 6, 8), previous)
        assertEquals(4L, result.identitySeq)
        assertEquals(listOf(4L, 6L, 8L), result.calls.map { it.seq })
    }

    @Test fun `repeated prepends retain original identity but not first actual sequence`() {
        val first = retain(tools(2, 4, 6), tools(4, 6))
        val second = retain(tools(1, 2, 4, 6), first)
        assertEquals(4L, first.identitySeq)
        assertEquals(4L, second.identitySeq)
        assertEquals(1L, second.seq)
        assertEquals(4L, transcriptRowKey(second))
    }

    @Test fun `new prefix and suffix retain the complete old group`() {
        val result = retain(tools(2, 4, 6, 8), tools(4, 6))
        assertEquals(4L, result.identitySeq)
        assertEquals(2L, result.seq)
    }

    @Test fun `separator split cannot reuse one identity or cross the message`() {
        val previous = retain(tools(2, 4, 6), tools(4, 6))
        val message = TranscriptRow.Message(UserMessageNode(5, "steer", listOf(ChatBlock("text", "wait")), "user"))
        val rows = retainToolGroupIdentities(listOf(tools(2, 4), message, tools(6)), listOf(previous))
        assertEquals(listOf(2L, 6L), identities(rows))
        assertEquals(2, identities(rows).toSet().size)
        assertSame(message, rows[1])
        assertEquals(listOf(2L, 4L), (rows[0] as TranscriptRow.Tools).calls.map { it.seq })
        assertEquals(listOf(6L), (rows[2] as TranscriptRow.Tools).calls.map { it.seq })
    }

    @Test fun `new older group separated by visible message leaves existing identity alone`() {
        val message = TranscriptRow.Message(UserMessageNode(3, "steer", listOf(ChatBlock("text", "wait")), "user"))
        val rows = retainToolGroupIdentities(listOf(tools(1, 2), message, tools(4, 6)), listOf(tools(4, 6)))
        assertEquals(listOf(1L, 4L), identities(rows))
        assertSame(message, rows[1])
    }

    @Test fun `removed old prefix falls back to actual first call`() {
        val previous = retain(tools(2, 4, 6), tools(4, 6))
        val result = retain(tools(4, 6, 8), previous)
        assertEquals(4L, result.identitySeq)
        val truncatedAgain = retain(tools(6, 8), result)
        assertEquals(6L, truncatedAgain.identitySeq)
    }

    @Test fun `removed prefix invalidates identity even when a new prefix is added`() {
        val result = retain(tools(1, 6, 8), tools(4, 6))
        assertEquals(1L, result.identitySeq)
    }

    @Test fun `reordering or dropping a middle member cannot retain identity`() {
        assertEquals(2L, retain(tools(2, 6, 4), tools(4, 6)).identitySeq)
        assertEquals(2L, retain(tools(2, 4, 8), tools(4, 6, 8)).identitySeq)
    }

    @Test fun `different groups extend independently without aliasing`() {
        val message = TranscriptRow.Message(UserMessageNode(10, "u", listOf(ChatBlock("text", "next")), "user"))
        val previous = listOf(tools(4, 6), message, tools(14, 16))
        val rows = retainToolGroupIdentities(listOf(tools(2, 4, 6, 8), message, tools(12, 14, 16, 18)), previous)
        assertEquals(listOf(4L, 14L), identities(rows))
        assertSame(message, rows[1])
    }

    @Test fun `joining previously separate groups is not a new-member extension`() {
        val rows = retainToolGroupIdentities(listOf(tools(2, 4, 6, 8)), listOf(tools(4, 6), tools(8)))
        assertEquals(listOf(2L), identities(rows))
    }

    @Test fun `empty history uses actual first call and leaves other rows unchanged`() {
        val message = TranscriptRow.Message(UserMessageNode(1, "u", listOf(ChatBlock("text", "hello")), "user"))
        val rows = retainToolGroupIdentities(listOf(message, tools(2, 4)), emptyList())
        assertSame(message, rows[0])
        assertEquals(listOf(2L), identities(rows))
        assertTrue(retainToolGroupIdentities(emptyList(), rows).isEmpty())
    }
}
