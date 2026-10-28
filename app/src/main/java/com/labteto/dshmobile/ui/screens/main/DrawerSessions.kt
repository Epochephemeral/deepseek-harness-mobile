package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.data.SessionRow

/**
 * Navigation lists contain conversations, not agent implementation sessions.
 *
 * The wire's durable `origin` is the discriminator. A regular fork also has a parentSessionId,
 * so ancestry must never be used to hide one. This is a presentation rule only: the full store
 * catalog stays available to the parent's dedicated subagent panel.
 */
internal fun SessionRow.isMainConversation(): Boolean = origin != "subagent"

/** Shared source for workspace groups, pinned shortcuts, ungrouped/recent rows, and search. */
internal fun drawerConversationRows(
    sessions: List<SessionRow>,
    archivedIds: Set<String>,
): List<SessionRow> = sessions.filter {
    it.isMainConversation() && !it.blank && it.sessionId !in archivedIds
}

/** The drawer archive and the searchable archive screen use the same conversation-only catalog. */
internal fun archivedConversationRows(
    sessions: List<SessionRow>,
    archivedIds: Set<String>,
    query: String = "",
): List<SessionRow> = sessions.filter {
    it.isMainConversation() && it.sessionId in archivedIds &&
        (query.isBlank() || it.title.orEmpty().contains(query, true) || it.cwd.orEmpty().contains(query, true))
}
