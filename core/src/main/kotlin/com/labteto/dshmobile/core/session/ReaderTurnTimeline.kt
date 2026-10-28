package com.labteto.dshmobile.core.session

import java.util.TreeMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The currently loaded reader transcript, not its archive or wire journal.
 *
 * Coverage includes every raw sequence position, but skips and raw envelopes are not retained.
 * Standalone reader records are decoded once. Immutable public-node blocks are rebuilt only for
 * affected turns; adding a live record never replays old JSON or refolds completed earlier turns.
 * This eliminates retained raw envelopes, NOT public-text growth or a caller's temporary input list.
 * The owning reader serializes access. Raw/legacy archives (notably durable assistant/chunk) use EventFold.
 */
class ReaderTurnTimeline(private val sessionId: String) {
    private data class Record(val nodes: List<ChatNode>, val time: Long, val settlement: DurableSettlement?)
    private data class Block(val first: Long, val last: Long, val nodes: List<ChatNode>)

    private val records = TreeMap<Long, Record>()
    private var blocks = emptyList<Block>()
    private var current = ConversationSnapshot(sessionId)
    var firstSeq: Long? = null
        private set
    var lastSeq: Long? = null
        private set

    internal var decodedRecordCount = 0L
        private set
    internal var rebuiltTurnCount = 0L
        private set
    internal val retainedRecordCount: Int get() = records.size

    fun snapshot(): ConversationSnapshot = current

    fun clear() {
        records.clear()
        blocks = emptyList()
        firstSeq = null
        lastSeq = null
        current = ConversationSnapshot(sessionId)
    }

    fun append(event: SessionEventEnvelope): ConversationSnapshot = mergeRange(listOf(event))

    /** Same-sequence corrections replace their public representation, including a change to skip. */
    fun mergeRange(events: List<SessionEventEnvelope>): ConversationSnapshot {
        if (events.isEmpty()) return current
        val from = events.first().seq
        val through = events.last().seq
        require(from >= 0)
        events.forEachIndexed { index, event ->
            require(event.seq == from + index) { "Reader addition must have contiguous raw coverage" }
            require(event.type != "assistant/chunk") { "Durable assistant chunks are not reader-v1 records" }
        }
        val oldFirst = firstSeq
        val oldLast = lastSeq
        require(oldFirst == null || oldLast == null || (from <= oldLast + 1 && through >= oldFirst - 1)) {
            "Reader addition cannot silently bridge missing history"
        }
        // Parse before mutating the valid view. A malformed batch cannot partly replace its rows.
        val parsed = TreeMap<Long, Record>()
        val fold = EventFold(sessionId)
        for (event in events) {
            if (event.type == "reader/skip") continue
            val nodes = fold.fold(listOf(event)).nodes
            val settlement = if (event.type == "assistant/message" || event.type == "assistant/attempt") {
                val data = event.data as? JsonObject
                DurableSettlement(event.type, (data?.get("turn") as? JsonPrimitive)?.intOrNull,
                    (data?.get("step") as? JsonPrimitive)?.intOrNull, event.seq, event.surfaceOp)
            } else null
            if (nodes.isNotEmpty() || settlement != null) parsed[event.seq] = Record(nodes, event.time, settlement)
        }
        decodedRecordCount += events.count { it.type != "reader/skip" }
        if (parsed.isEmpty() && oldFirst != null && oldLast != null && from >= oldFirst &&
            records.subMap(from, true, through, true).isEmpty()) {
            // Hidden live traffic advances coverage, not presentation or completed-turn work.
            lastSeq = maxOf(oldLast, through)
            if (lastSeq != oldLast) {
                blocks = blocks.dropLast(1) + blocks.last().copy(last = checkNotNull(lastSeq))
                current = current.copy(lastSeq = checkNotNull(lastSeq), loadedThroughSeq = lastSeq)
            }
            return current
        }
        records.subMap(from, true, through, true).clear()
        records.putAll(parsed)
        firstSeq = oldFirst?.let { minOf(it, from) } ?: from
        lastSeq = oldLast?.let { maxOf(it, through) } ?: through
        rebuild(from, through)
        return current
    }

    /** Only an authoritative server cut may remove already loaded coverage. */
    fun truncateAuthoritative(cursor: Long): ConversationSnapshot {
        val first = firstSeq ?: return current
        val last = lastSeq ?: return current
        if (cursor >= last) return current
        if (cursor < first) { clear(); return current }
        records.tailMap(cursor, false).clear()
        lastSeq = cursor
        rebuild(cursor, last)
        return current
    }

    private fun rebuild(changedFrom: Long, changedThrough: Long) {
        val first = firstSeq ?: return
        val last = lastSeq ?: return
        val starts = buildList {
            add(first)
            for ((seq, record) in records) {
                if (seq != first && record.nodes.any { it is TurnStartNode }) add(seq)
            }
        }
        val previous = blocks.associateBy { it.first }
        blocks = starts.mapIndexed { index, start ->
            val end = starts.getOrNull(index + 1)?.minus(1) ?: last
            previous[start]?.takeIf {
                it.last == end && (changedThrough < start || changedFrom > end)
            } ?: run {
                rebuiltTurnCount++
                val nodes = records.subMap(start, true, end, true).values.flatMap { it.nodes }.toMutableList()
                // Reconcile the one cross-record reader fold rule without retaining/replaying JSON.
                // Base record nodes retain explicit interruption; inferred flags live only in blocks,
                // so correcting/removing an end marker also correctly removes inferred interruption.
                for (endNode in nodes.filterIsInstance<TurnEndNode>()) {
                    if (endNode.reasonKind !in INTERRUPTED_ENDS) continue
                    if (nodes.any { it is AssistantMessageNode && it.turn == endNode.turn && it.interrupted && it.seq < endNode.seq }) continue
                    val at = nodes.indexOfLast { it is AssistantMessageNode && it.turn == endNode.turn && it.seq < endNode.seq }
                    if (at >= 0) nodes[at] = (nodes[at] as AssistantMessageNode).copy(interrupted = true)
                }
                Block(start, end, nodes.toList())
            }
        }
        val nodes = BlockNodes(blocks.map { it.nodes })
        val lifecycle = nodes.lastOrNull { it is TurnStartNode || it is TurnEndNode }
        current = ConversationSnapshot(
            sessionId = sessionId,
            nodes = nodes,
            // Full raw records belong to the archive; explicit detail reads load them separately.
            lastSeq = last,
            loadedFromSeq = first,
            loadedThroughSeq = last,
            running = lifecycle is TurnStartNode,
            blank = nodes.none { it is UserMessageNode },
            eventTimes = records.mapNotNull { (seq, value) -> if (value.nodes.isEmpty()) null else seq to value.time }.toMap(),
            durableSettlements = records.values.mapNotNull { it.settlement },
        )
    }

    /** Share immutable completed blocks instead of copying every old node on each durable append. */
    private class BlockNodes(private val parts: List<List<ChatNode>>) : AbstractList<ChatNode>() {
        private val ends = IntArray(parts.size)
        override val size: Int
        init {
            var total = 0
            parts.forEachIndexed { index, part -> total += part.size; ends[index] = total }
            size = total
        }
        override fun get(index: Int): ChatNode {
            if (index !in 0 until size) throw IndexOutOfBoundsException("index=$index size=$size")
            var low = 0
            var high = ends.lastIndex
            while (low < high) {
                val middle = (low + high) ushr 1
                if (index < ends[middle]) high = middle else low = middle + 1
            }
            val start = if (low == 0) 0 else ends[low - 1]
            return parts[low][index - start]
        }
        override fun iterator(): Iterator<ChatNode> = parts.asSequence().flatten().iterator()
    }

    private companion object {
        val INTERRUPTED_ENDS = setOf("interrupted", "aborted", "error")
    }
}
