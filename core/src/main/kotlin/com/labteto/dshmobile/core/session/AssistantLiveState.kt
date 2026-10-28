package com.labteto.dshmobile.core.session

import com.labteto.dshmobile.core.wire.dto.AssistantStreamOutcome
import com.labteto.dshmobile.core.wire.dto.SessionAssistantStreamBaseline
import com.labteto.dshmobile.core.wire.dto.SessionAssistantStreamFrame
import com.labteto.dshmobile.core.storage.DiskCacheStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull

/**
 * The reply being written right now, as far as the follow stream has shown it.
 *
 * Harness 0.1.3 no longer logs a model's deltas; it logs one settlement per attempt and hands a
 * follower that opted in a separate, process-local stream of `start` / `chunk` / `end` frames
 * (`docs/PROTOCOL.md`). This is the port of the web client's `ClientAssistantStream`
 * (`packages/api/session-controller/src/client/sessions/assistant-stream.ts`), reduced to what a
 * renderer that re-folds the whole window needs: the chunks of the one attempt that is open, so
 * the fold can show them as a provisional message until the durable settlement replaces them.
 *
 * Two departures from the web client are deliberate. It stages a durable settlement until the
 * matching `end` frame so the two swap atomically; here the settlement retires the attempt the
 * moment it lands, because the durable event and the transient rows say the same thing and a
 * fold that saw both would show the reply twice. And where it re-opens the follow stream on a
 * continuity fault, this simply drops the transient rows — the settlement arrives on the durable
 * path regardless, so the cost of a hole is the live preview and nothing else.
 *
 * Not thread-safe; the session store calls it under its own lock.
 */
class AssistantLiveState(private val diskCacheStore: DiskCacheStore? = null) {

    private class Attempt(
        val attemptId: String,
        val startedAfterSeq: Int,
        val turn: Int,
        val step: Int,
        var nextIndex: Int,
        val chunks: PreviewChunkJournal,
        val preview: EventFold.TransientAccumulator = EventFold.TransientAccumulator(),
        var checkedDurable: ConversationSnapshot? = null,
    )

    /** What one frame did to the visible state. */
    enum class Change {
        /** Nothing a renderer can see moved. */
        NONE,

        /** The open attempt gained a chunk. */
        CHANGED,

        /** The open attempt ended in a durable settlement, which the event path delivers. */
        SETTLED,

        /** The open attempt ended without a durable event; its rows are simply gone. */
        ABANDONED,

        /** A continuity fault dropped the open attempt's rows. */
        RESET,
    }

    private var attempt: Attempt? = null

    /** The host's last frame revision seen, for diagnostics; `0` before any. */
    var revision: Int = 0
        private set

    /** Whether an attempt is open and may have rows to show. */
    val active: Boolean get() = attempt != null

    /** Identity of the open attempt, when there is one. */
    val attemptId: String? get() = attempt?.attemptId

    /** Number of chunks the open attempt holds. */
    val chunkCount: Int get() = attempt?.chunks?.count ?: 0

    /** Forget everything; a new generation or a different session starts from nothing. */
    fun clear() {
        closeAttempt()
    }

    /**
     * Adopt the opening baseline of a follow generation.
     *
     * The baseline's `stream` is the compact prefix the attempt had accumulated at the cut and
     * `nextIndex` the position the next live chunk will carry, so at most that many members are
     * kept — the host promises the two agree, but the count is what the continuity check trusts.
     */
    private var sparseReader = false

    fun seed(baseline: SessionAssistantStreamBaseline?, sparseReader: Boolean = false) {
        this.sparseReader = sparseReader
        closeAttempt()
        revision = baseline?.revision ?: 0
        val opening = baseline?.activeAttempt ?: return
        val open = newAttempt(opening.attemptId, opening.startedAfterSeq, opening.turn, opening.step, opening.nextIndex)
        try {
            var seen = 0
            AssistantStream.forEachExpanded(opening.stream) { timed ->
                if (seen++ < opening.nextIndex) {
                    open.chunks.append(timed)
                    open.preview.append(open.turn, open.step, timed.chunk)
                }
            }
            attempt = open
        } catch (failure: Exception) {
            try { open.chunks.close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /** Fold one live frame. */
    fun accept(frame: SessionAssistantStreamFrame): Change {
        // Reader filtering preserves source watermarks but intentionally omits private chunks.
        // This relaxation is opt-in; raw followers still require dense chunk indices.
        if (sparseReader && frame.revision <= revision) return Change.NONE
        revision = frame.revision
        return when (frame) {
            is SessionAssistantStreamFrame.Start -> {
                // A start while one is open means the previous end never reached us. Its rows are
                // stale by definition, and the new attempt is the one worth showing.
                val dropped = (attempt?.chunks?.count ?: 0) > 0
                closeAttempt()
                attempt = newAttempt(
                    attemptId = frame.attemptId,
                    startedAfterSeq = frame.startedAfterSeq,
                    turn = frame.turn,
                    step = frame.step,
                    nextIndex = 0,
                )
                if (dropped) Change.RESET else Change.NONE
            }

            is SessionAssistantStreamFrame.Chunk -> {
                // A follower mounted after the host saw the start has nothing to attach the chunk
                // to; the settlement will publish directly, so the suffix is ignored until the next
                // known start.
                val open = attempt ?: return Change.NONE
                if (open.attemptId != frame.attemptId) return Change.NONE
                if (sparseReader && frame.index < open.nextIndex) return Change.NONE
                if (!sparseReader && frame.index != open.nextIndex) return reset()
                open.nextIndex = frame.index + 1
                val chunk = frame.chunk as? JsonObject ?: return Change.NONE
                try {
                    open.chunks.append(TimedChunk(frame.time, chunk))
                    open.preview.append(open.turn, open.step, chunk)
                } catch (failure: Exception) {
                    try { closeAttempt() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                    throw failure
                }
                Change.CHANGED
            }

            is SessionAssistantStreamFrame.End -> {
                val open = attempt ?: return Change.NONE
                if (open.attemptId != frame.attemptId) return Change.NONE
                closeAttempt()
                when {
                    (!sparseReader && frame.index != open.nextIndex) || frame.index < open.nextIndex -> Change.RESET
                    frame.outcome is AssistantStreamOutcome.Committed -> Change.SETTLED
                    else -> Change.ABANDONED
                }
            }

            is SessionAssistantStreamFrame.Unknown -> Change.NONE
        }
    }

    /**
     * Retire the open attempt when its durable settlement arrives on the event path.
     *
     * A settlement is an `assistant/message` appended to the surface (a positional replacement
     * is compaction, not a model attempt) or an `assistant/attempt`, for the open attempt's own
     * turn and step, logged after the attempt started. Anything else is some earlier step's and
     * leaves the live rows alone.
     *
     * @return whether the attempt was retired, which is the caller's cue to re-render.
     */
    fun acceptDurable(type: String, turn: Int?, step: Int?, seq: Long, surfaceOp: String?): Boolean {
        val open = attempt ?: return false
        if (type != "assistant/message" && type != "assistant/attempt") return false
        if (type == "assistant/message" && surfaceOp != null && surfaceOp != "append") return false
        if (seq <= open.startedAfterSeq || turn != open.turn || step != open.step) return false
        closeAttempt()
        return true
    }

    /**
     * The open attempt's chunks in the envelope shape the fold streams from.
     *
     * These are not durable events and carry no real sequence number: `seq` is the chunk's
     * position, and the fold applies them after every durable event without letting them touch
     * its cursor or its gap detection.
     */
    fun transientEnvelopes(): List<SessionEventEnvelope> {
        val open = attempt ?: return emptyList()
        val envelopes = ArrayList<SessionEventEnvelope>()
        open.chunks.replayFrom(0) { timed ->
            envelopes.add(SessionEventEnvelope(
                type = "assistant/chunk",
                seq = envelopes.size.toLong(),
                time = timed.time,
                data = buildJsonObject {
                    put("turn", JsonPrimitive(open.turn))
                    put("step", JsonPrimitive(open.step))
                    put("chunk", timed.chunk)
                },
            ))
        }
        return envelopes
    }

    /** Immutable live view without remapping/replaying the already accepted chunk prefix. */
    fun transientSnapshot(durable: ConversationSnapshot): ConversationSnapshot {
        val open = attempt ?: return durable
        if (open.checkedDurable !== durable) {
            open.checkedDurable = durable
            durableSettlementChecks++
            // A disk page can arrive ahead of this follow's live Entry. Use exactly the same
            // settlement checks as that event path, so the provisional reply is never duplicated.
            for (event in durable.journal.asReversed()) {
                if (event.type != "assistant/message" && event.type != "assistant/attempt") continue
                val data = event.data as? JsonObject ?: continue
                if (acceptDurable(event.type, (data["turn"] as? JsonPrimitive)?.intOrNull,
                    (data["step"] as? JsonPrimitive)?.intOrNull, event.seq, event.surfaceOp)) return durable
            }
        }
        return open.preview.snapshot(durable, open.turn, open.step, open.chunks)
    }

    /** Identity changes, not preview chunks or repeated renders, trigger reconciliation work. */
    internal var durableSettlementChecks: Long = 0
        private set

    /** Test-visible work count: snapshots never increase this, only accepted chunks/seed do. */
    internal val previewAppliedChunks: Long get() = attempt?.preview?.appliedChunks ?: 0

    private fun newAttempt(attemptId: String, startedAfterSeq: Int, turn: Int, step: Int, nextIndex: Int): Attempt =
        Attempt(attemptId, startedAfterSeq, turn, step, nextIndex, previewChunkJournal(diskCacheStore))

    private fun closeAttempt() {
        val old = attempt
        attempt = null
        old?.chunks?.close()
    }

    private fun reset(): Change {
        val dropped = (attempt?.chunks?.count ?: 0) > 0
        closeAttempt()
        return if (dropped) Change.RESET else Change.NONE
    }
}
