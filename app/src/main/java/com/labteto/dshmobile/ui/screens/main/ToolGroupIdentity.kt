package com.labteto.dshmobile.ui.screens.main

/**
 * Keep a disclosure's identity when paging or streaming only extends its complete old call run.
 * Semantic grouping has already happened: this never moves calls across message/turn boundaries.
 * A removed or reordered old member invalidates retention, as does joining two existing groups.
 */
internal fun retainToolGroupIdentities(
    rows: List<TranscriptRow>,
    previous: List<TranscriptRow>,
): List<TranscriptRow> {
    val oldGroups = previous.filterIsInstance<TranscriptRow.Tools>()
    val oldMembership = oldGroups.flatMap { group -> group.calls.map { it.callId to group } }.toMap()
    val usedIdentities = mutableSetOf<Long>()
    return rows.map { row ->
        if (row !is TranscriptRow.Tools) return@map row
        val firstOld = row.calls.indexOfFirst { it.callId in oldMembership }
        val old = row.calls.getOrNull(firstOld)?.let { oldMembership[it.callId] }
        val retained = old?.takeIf { candidate ->
            firstOld + candidate.calls.size <= row.calls.size &&
                candidate.calls.indices.all { index ->
                    candidate.calls[index].callId == row.calls[firstOld + index].callId
                } &&
                row.calls.drop(firstOld + candidate.calls.size).none { it.callId in oldMembership } &&
                candidate.identitySeq !in usedIdentities
        }
        val identity = retained?.identitySeq ?: row.seq
        usedIdentities.add(identity)
        if (row.identitySeq == identity) row else row.copy(identitySeq = identity)
    }
}
