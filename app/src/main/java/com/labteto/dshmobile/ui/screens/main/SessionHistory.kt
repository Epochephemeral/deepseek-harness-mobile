package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.TurnStartNode
import com.labteto.dshmobile.core.session.UserMessageNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Stable picker identity; an outline entry targets a boundary, not an invisible transcript row. */
internal data class HistoryEntry(
    val seq: Long,
    val text: String,
    val hasImage: Boolean,
    val timeMs: Long?,
    val turn: Long? = null,
    val targetStartSeq: Long? = null,
    val endSeqExclusive: Long? = null,
    val response: String = "",
    val promptLoaded: Boolean = true,
)

internal data class HistoryTurn(val turn: Long, val seq: Long, val prompt: String, val response: String)

/** The host's wire view is an array (the internal {turns,draft} state is never sent). */
internal fun parseTurnOutline(value: JsonElement?): List<HistoryTurn>? {
    val array = value as? JsonArray ?: return null
    val turns = ArrayList<HistoryTurn>(array.size)
    for (item in array) {
        val obj = item as? JsonObject ?: return null
        fun number(key: String) = (obj[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }
        fun text(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val turn = number("turn") ?: return null
        val seq = number("seq") ?: return null
        val prompt = text("prompt") ?: return null
        val response = text("response") ?: return null
        // Reject ambiguous boundaries rather than silently offering a partial or incorrect directory.
        if (turns.lastOrNull()?.let { turn <= it.turn || seq <= it.seq } == true) return null
        turns += HistoryTurn(turn, seq, prompt, response)
    }
    return turns
}

/** Full projection directory when available; older hosts retain the loaded human-prompt list. */
internal fun historyEntries(
    nodes: List<ChatNode>,
    running: Boolean,
    eventTimes: Map<Long, Long> = emptyMap(),
    turnOutline: JsonElement? = null,
): List<HistoryEntry> {
    val loaded = renderableChatNodes(nodes, running).mapNotNull { node ->
        if (node !is UserMessageNode || node.isInjectedContext) return@mapNotNull null
        val text = node.displayText().trim()
        val hasImage = node.blocks.any { it.kind == "image" }
        if (text.isEmpty() && !hasImage) null else HistoryEntry(node.seq, text, hasImage, eventTimes[node.seq])
    }
    val outline = parseTurnOutline(turnOutline) ?: return loaded.asReversed()
    val loadedStarts = nodes.filterIsInstance<TurnStartNode>().mapTo(mutableSetOf()) { it.seq }
    return outline.mapIndexed { index, turn ->
        val end = outline.getOrNull(index + 1)?.seq
        // A window starting mid-turn may contain only a steering message, not its original prompt.
        val prompt = if (turn.seq in loadedStarts) loaded.firstOrNull {
            it.seq >= turn.seq && (end == null || it.seq < end)
        } else null
        HistoryEntry(
            seq = turn.seq,
            text = prompt?.text ?: turn.prompt,
            hasImage = prompt?.hasImage == true,
            timeMs = prompt?.timeMs ?: eventTimes[turn.seq],
            turn = turn.turn,
            targetStartSeq = turn.seq,
            endSeqExclusive = end,
            response = turn.response,
            promptLoaded = prompt != null,
        )
    }.asReversed()
}

/** Resolve only after seeking, and never fall through into the next turn or structural boundary. */
internal fun historyJumpTarget(
    entry: HistoryEntry, nodes: List<ChatNode>, running: Boolean, turnOutline: JsonElement? = null,
): Long? {
    val starts = (nodes.filterIsInstance<TurnStartNode>().map { it.seq } +
        parseTurnOutline(turnOutline).orEmpty().map { it.seq }).distinct().sorted()
    val rows = transcriptRows(nodes, running, turnOutline)
    val start = entry.targetStartSeq ?: return rows.firstOrNull { it.seq == entry.seq }?.seq
    val end = listOfNotNull(entry.endSeqExclusive, starts.firstOrNull { it > start }).minOrNull()
    val candidates = rows.filter { it.seq >= start && (end == null || it.seq < end) }
    return candidates.firstOrNull {
        val node = (it as? TranscriptRow.Message)?.node
        node is UserMessageNode && !node.isInjectedContext
    }?.seq ?: candidates.firstOrNull { it is TranscriptRow.Message }?.seq
        ?: candidates.firstOrNull()?.seq
}

internal fun historyRowIndex(rows: List<TranscriptRow>, seq: Long): Int? =
    rows.indexOfFirst { row ->
        row.seq == seq || when (row) {
            is TranscriptRow.Process -> historyRowIndex(row.rows, seq) != null
            is TranscriptRow.Tools -> row.calls.any { it.seq == seq }
            is TranscriptRow.Context -> row.nodes.any { it.seq == seq }
            is TranscriptRow.Message -> false
        }
    }.takeIf { it >= 0 }

/** Match every query word, not subsequences of prose. */
internal fun historyMatches(query: String, text: String): Boolean {
    val haystack = text.lowercase()
    return query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { it in haystack }
}
