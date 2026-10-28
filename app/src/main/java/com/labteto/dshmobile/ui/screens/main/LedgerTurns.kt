package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.*

/** A disk page may start inside a turn. Preserve its nodes without manufacturing a start event. */
internal fun groupLedgerTurns(nodes: List<ChatNode>): List<Pair<Int?, List<ChatNode>>> {
    val groups = mutableListOf<Pair<Int?, MutableList<ChatNode>>>()
    nodes.forEach { node ->
        val turn = when (node) {
            is TurnStartNode -> node.turn
            is TurnEndNode -> node.turn
            is ToolCallNode -> node.turn
            is ToolResultNode -> node.turn
            is AssistantMessageNode -> node.turn
            else -> null
        }
        if (groups.isEmpty() || (turn != null && groups.last().first != turn)) {
            // Unknown prefix content stays neutral; a subsequent real start does not retroactively
            // claim it. Known partial turns keep the same identity when their real marker arrives.
            groups += turn to mutableListOf<ChatNode>()
        }
        if (node !is TurnStartNode) groups.last().second += node
    }
    return groups.map { (turn, content) -> turn to content.toList() }
}
