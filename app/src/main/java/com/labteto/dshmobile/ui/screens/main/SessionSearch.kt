package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.data.SessionRow
import com.labteto.dshmobile.data.WorkspaceRow
import java.util.Locale

/** One row of the drawer's search result list. */
internal data class SearchHit(
    val session: SessionRow,
    /** The workspace title, or the working directory's folder when the session belongs to none. */
    val workspaceLabel: String,
    /** The matching excerpt from `session.search`, when the host supplied one. */
    val snippet: String?,
)

/** Bounded, deduplicated results plus the bit that says the query was too broad to show it all. */
internal data class SearchResultSet(
    val items: List<SearchHit>,
    val hasMore: Boolean,
)

/**
 * How many rows the drawer will show before asking for a narrower query.
 *
 * The harness web sidebar caps its merged list the same way. Past a screenful the list stops being
 * an answer and starts being the thing you were trying to search.
 */
internal const val SEARCH_RESULT_LIMIT = 30

/**
 * Merge immediate title/workspace matches with the host's ranked content matches.
 *
 * This is the half of search the app was missing. `session.search` is full-text over *message
 * content*, and it is opt-in: the shipped harness configures its query index `openAt: never`, so
 * the call fails outright and the drawer — whose only source of results was that call — appeared to
 * do nothing at all. The harness's own sidebar has always matched titles and workspace names
 * locally and treated content hits as an extra, which is what this reproduces.
 *
 * Local rows lead, newest first; content-only rows follow in the host's ranking. A session matched
 * both ways keeps its local position and gains the host's snippet.
 *
 * The same eligibility as the drawer applies before either source is merged: subagent transcripts
 * belong in the parent's dedicated panel, archived sessions are out of the list being searched, and
 * blank sessions have a localized placeholder rather than a searchable conversation title.
 *
 * A free function so the ordering and dedup rules can be tested without a device, in the same
 * spirit as `sameSubnet` in the discovery engine.
 */
internal fun deriveSearchResults(
    sessions: List<SessionRow>,
    workspaces: List<WorkspaceRow>,
    archivedIds: Set<String>,
    query: String,
    contentHits: List<Pair<String, String>>,
    limit: Int = SEARCH_RESULT_LIMIT,
): SearchResultSet {
    val q = query.trim().lowercase(Locale.ROOT)
    if (q.isEmpty()) return SearchResultSet(emptyList(), hasMore = false)

    val workspaceTitleOf = workspaces
        .flatMap { ws -> ws.sessionIds.map { it to ws.title.ifBlank { basename(ws.path) } } }
        .toMap()
    val eligible = drawerConversationRows(sessions, archivedIds)
    val sessionsById = eligible.associateBy { it.sessionId }
    fun labelOf(session: SessionRow): String =
        workspaceTitleOf[session.sessionId] ?: session.cwd?.let { basename(it) } ?: ""

    val local = eligible
        .filter {
            sessionTitle(it).lowercase(Locale.ROOT).contains(q) ||
                labelOf(it).lowercase(Locale.ROOT).contains(q)
        }
        .sortedByDescending(SessionRow::updatedAt)

    val snippetOf = contentHits.toMap()
    val ordered = LinkedHashMap<String, SessionRow>()
    local.forEach { ordered.putIfAbsent(it.sessionId, it) }
    contentHits.forEach { (sessionId, _) ->
        sessionsById[sessionId]?.let { ordered.putIfAbsent(sessionId, it) }
    }

    val rows = ordered.values.map { session ->
        SearchHit(
            session = session,
            workspaceLabel = labelOf(session),
            snippet = snippetOf[session.sessionId]?.takeIf { it.isNotBlank() },
        )
    }
    return SearchResultSet(items = rows.take(limit), hasMore = rows.size > limit)
}
