package com.labteto.dshmobile.core.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One raw model chunk paired with the wall-clock time the host observed it. */
data class TimedChunk(val time: Long, val chunk: JsonObject)

/**
 * Expansion of one compact assistant stream back into the chunks the model produced.
 *
 * Harness 0.1.3's session format v2 stores each model attempt as one durable settlement whose
 * `stream` is a lossless compaction of the raw chunks: a run of consecutive same-block deltas
 * becomes one `text-chunks` / `reasoning-chunks` / `tool-call-chunks` record carrying the
 * fragments and the inter-chunk time gaps, and anything else (`block-start`, `block-end`,
 * `usage`, `finish`) is kept verbatim as a `chunk` record. This mirrors the host's own
 * `expandAssistantStream` (`packages/llm/llm/src/assistant-stream.ts`).
 *
 * The one place a client needs the expansion is the reconnect baseline: a follower that joins
 * mid-attempt is handed the compact prefix it missed and rebuilds the partial reply from it. The
 * durable settlement itself is not expanded — its assembled `message` already says what the
 * chunks added up to, and the fold reads that.
 *
 * A malformed record drops the whole stream rather than half of it: a partial prefix would put
 * a fragment of an answer on screen with no way to say which part is missing.
 */
object AssistantStream {

    private const val TEXT = "text-chunks"
    private const val REASONING = "reasoning-chunks"
    private const val TOOL_CALL = "tool-call-chunks"
    private const val CHUNK = "chunk"

    /** Expand [stream] (the wire `stream` array) into its timed chunks, in order. */
    fun expand(stream: JsonElement?): List<TimedChunk> {
        val out = ArrayList<TimedChunk>()
        forEachExpanded(stream) { out.add(it) }
        return out
    }

    /**
     * Validate the complete stream before invoking [consume], then expand one chunk at a time.
     * A malformed tail must not publish a valid-looking but incomplete prefix. The two passes
     * retain no expanded list; the wire DTO is necessarily alive until this call returns.
     */
    fun forEachExpanded(stream: JsonElement?, consume: (TimedChunk) -> Unit): Boolean {
        if (!visit(stream, null)) return false
        return visit(stream, consume)
    }

    private fun visit(stream: JsonElement?, consume: ((TimedChunk) -> Unit)?): Boolean {
        val records = stream as? JsonArray ?: return false
        for (candidate in records) {
            val record = candidate as? JsonObject ?: return false
            when (record["type"]?.jsonPrimitive?.contentOrNull) {
                CHUNK -> {
                    val time = record["time"]?.time() ?: return false
                    val chunk = record["chunk"] as? JsonObject ?: return false
                    consume?.invoke(TimedChunk(time, chunk))
                }
                TEXT, REASONING, TOOL_CALL -> if (!expandRun(record, consume)) return false
                else -> return false
            }
        }
        return true
    }

    private fun expandRun(record: JsonObject, consume: ((TimedChunk) -> Unit)?): Boolean {
        val type = record["type"]?.jsonPrimitive?.contentOrNull ?: return false
        val members = (if (type == TOOL_CALL) record["args"] else record["texts"]) as? JsonArray ?: return false
        val index = record["index"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 } ?: return false
        val gaps = record["dt"] as? JsonArray ?: JsonArray(emptyList())
        if (members.isNotEmpty() && gaps.size != members.size - 1) return false
        val toolCallId = record["id"]?.jsonPrimitive?.contentOrNull
        if (type == TOOL_CALL && toolCallId == null) return false
        val hasName = record.containsKey("name")
        val toolName = record["name"]?.jsonPrimitive?.contentOrNull
        if (hasName && toolName == null) return false
        var time = record["time0"]?.time() ?: return false
        for (k in members.indices) {
            if (k > 0) time += gaps[k - 1].time() ?: return false
            val member = (members[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
            if (consume != null) {
                val chunk = buildJsonObject {
                    put("type", JsonPrimitive(when (type) {
                        TEXT -> "text-delta"
                        REASONING -> "reasoning-delta"
                        else -> "tool-call-delta"
                    }))
                    put("index", JsonPrimitive(index))
                    if (type == TOOL_CALL) {
                        put("id", JsonPrimitive(toolCallId))
                        if (hasName) put("name", JsonPrimitive(toolName))
                        put("argumentsDelta", JsonPrimitive(member))
                    } else put("text", JsonPrimitive(member))
                }
                consume(TimedChunk(time, chunk))
            }
        }
        return true
    }

    /** Read a wire time or gap; the host writes safe integers, but a float is not worth a crash. */
    private fun JsonElement.time(): Long? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.doubleOrNull?.toLong()
    }
}
