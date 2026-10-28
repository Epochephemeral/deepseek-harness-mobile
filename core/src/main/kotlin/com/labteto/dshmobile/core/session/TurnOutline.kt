package com.labteto.dshmobile.core.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A conversation-turn boundary, shared by the history directory and history loading policy. */
data class TurnOutlineEntry(val turn: Long, val seq: Long, val prompt: String, val response: String)

/** The wire directory is an ordered array, not the host's internal {turns,draft} projection state. */
fun parseTurnOutline(value: JsonElement?): List<TurnOutlineEntry>? {
    val array = value as? JsonArray ?: return null
    val turns = ArrayList<TurnOutlineEntry>(array.size)
    for (item in array) {
        val obj = item as? JsonObject ?: return null
        fun number(key: String) = (obj[key] as? JsonPrimitive)?.takeUnless { it.isString }
            ?.longOrNull?.takeIf { it >= 0 }
        fun text(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val turn = number("turn") ?: return null
        val seq = number("seq") ?: return null
        val prompt = text("prompt") ?: return null
        val response = text("response") ?: return null
        if (turns.lastOrNull()?.let { turn <= it.turn || seq <= it.seq } == true) return null
        turns += TurnOutlineEntry(turn, seq, prompt, response)
    }
    return turns
}

/**
 * One reading action completes a turn, regardless of how many bounded transport batches it takes.
 * Older completes a partially loaded first turn, or adds the preceding turn when already aligned.
 * Newer completes the turn containing the next missing event, up to the authoritative known tail
 * for an unfinished last turn. The returned bound is inclusive. No valid directory means the
 * caller must discover real turn boundaries in fetched events; it does not mean "one raw page".
 */
fun historyTurnLoadTarget(
    outline: JsonElement?, firstSeq: Long, lastSeq: Long, latestSeq: Long, older: Boolean,
): Long? {
    require(firstSeq >= 0 && lastSeq >= firstSeq)
    val turns = parseTurnOutline(outline)?.takeIf { it.isNotEmpty() } ?: return null
    // A directory may be fresher than a pinned read. Never target a future, unavailable turn.
    val available = turns.takeWhile { it.seq <= latestSeq }
    if (available.isEmpty()) return null
    if (older) {
        if (firstSeq == 0L) return null
        return available.lastOrNull { it.seq < firstSeq }?.seq ?: 0L
    }
    if (lastSeq >= latestSeq) return latestSeq
    val next = lastSeq + 1
    val containing = available.indexOfLast { it.seq <= next }.coerceAtLeast(0)
    return available.getOrNull(containing + 1)?.seq?.minus(1) ?: latestSeq
}
