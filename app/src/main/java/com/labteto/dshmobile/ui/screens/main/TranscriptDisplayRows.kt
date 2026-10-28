package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.ToolCallNode

/** Rendering expansion only; semantic grouping and history navigation retain TranscriptRow. */
internal sealed interface TranscriptDisplayRow {
    val key: Any
    data class Semantic(val row: TranscriptRow) : TranscriptDisplayRow {
        override val key: Any get() = transcriptRowKey(row)
    }
    data class ToolDetail(val call: ToolCallNode) : TranscriptDisplayRow {
        override val key: Any get() = toolDetailKey(call.seq)
    }
}

internal fun toolDetailKey(seq: Long): String = "tool-call-$seq"

/** Input and output are newest-first; reverse layout places the header above chronological calls. */
internal fun transcriptDisplayRows(rows: List<TranscriptRow>, expandedGroups: Set<Long>): List<TranscriptDisplayRow> = buildList {
    rows.forEach { row ->
        if (row is TranscriptRow.Tools && row.identitySeq in expandedGroups) {
            row.calls.asReversed().forEach { add(TranscriptDisplayRow.ToolDetail(it)) }
        }
        add(TranscriptDisplayRow.Semantic(row))
    }
}

/** An expanded call owns its own index; a collapsed call belongs to its semantic group header. */
internal fun transcriptDisplayIndex(rows: List<TranscriptDisplayRow>, seq: Long): Int? {
    rows.indexOfFirst { it is TranscriptDisplayRow.ToolDetail && it.call.seq == seq }
        .takeIf { it >= 0 }?.let { return it }
    return rows.indexOfFirst { it is TranscriptDisplayRow.Semantic && historyRowIndex(listOf(it.row), seq) != null }
        .takeIf { it >= 0 }
}

/** On collapse/regrouping, only exact membership may replace the removed reading anchor. */
internal fun transcriptDisplayAnchorIndex(rows: List<TranscriptDisplayRow>, key: Any?): Int? {
    rows.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.let { return it }
    val seq = historySequenceOfKey(key) ?: return null
    return rows.indexOfFirst { item ->
        val row = (item as? TranscriptDisplayRow.Semantic)?.row
        when (row) {
            is TranscriptRow.Tools -> row.calls.any { it.seq == seq }
            is TranscriptRow.Process -> historyRowIndex(row.rows, seq) != null
            else -> false
        }
    }.takeIf { it >= 0 }
}
