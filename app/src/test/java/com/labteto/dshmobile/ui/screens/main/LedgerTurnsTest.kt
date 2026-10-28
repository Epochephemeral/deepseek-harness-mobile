package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.*
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class LedgerTurnsTest {
    private val call = ToolCallNode(10, "call", "actual_tool", "{}", 7, 2)
    private val orphan = ToolResultNode(11, "outside-page", JsonPrimitive("all output"), false, 7, 2)

    @Test fun `partial turn retains call and orphan using their real turn identity`() {
        val groups = groupLedgerTurns(listOf(call, orphan))
        assertEquals(7, groups.single().first)
        assertEquals(listOf(call, orphan), groups.single().second)
        val complete = groupLedgerTurns(listOf(TurnStartNode(1, 7), call, orphan))
        assertEquals(groups, complete)
    }

    @Test fun `unknown prefix stays present and is not assigned an invented turn`() {
        val prefix = UserMessageNode(1, "u", listOf(ChatBlock("text", "prompt")), "user")
        val groups = groupLedgerTurns(listOf(prefix, TurnStartNode(2, 7), call, orphan))
        assertNull(groups.first().first)
        assertEquals(listOf(prefix), groups.first().second)
        assertEquals(7, groups.last().first)
        assertEquals(listOf(call, orphan), groups.last().second)
    }

    @Test fun `ordinary complete turn grouping is unchanged`() {
        val nodes = listOf(TurnStartNode(1, 7), call, orphan, TurnStartNode(12, 8), call.copy(seq = 13, turn = 8))
        assertEquals(groupByTurn(nodes), groupLedgerTurns(nodes))
    }
}
