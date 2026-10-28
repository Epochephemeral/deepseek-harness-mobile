package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.classifyCapability

/** Selection identity, not just a session id: leaving A and returning to A retires A's old reads. */
internal data class ReadSelection(val sessionId: String, val epoch: Long)

/**
 * Ownership of an observation, never a write outcome. Host reads omit [selection]; session reads
 * include it. API reference identity fences reconnects even when the host/session strings match.
 * Capture and [applyIfCurrent] must run under the same caller-owned state lock; the RPC runs outside.
 */
internal class ReadResultOwner(
    val host: String,
    val api: DshApiClient,
    val selection: ReadSelection? = null,
) {
    fun owns(current: ReadResultOwner?): Boolean = current != null && host == current.host &&
        api === current.api && selection == current.selection

    fun <T> applyIfCurrent(current: ReadResultOwner?, result: RpcResult<T>, apply: (RpcResult<T>) -> Unit): Boolean {
        if (!owns(current)) return false
        // One gate for the entire result: failures have exactly the same ownership as successes.
        apply(result)
        return true
    }
}

/** Optional read surfaces may hide on a missing capability/trust refusal, never a carrier failure. */
internal fun RpcError.isOptionalReadUnavailable(): Boolean =
    classifyCapability().code in setOf("capability-unavailable", "forbidden")

/** Latest submitted query wins, including an empty query that makes no RPC. Caller holds its lock. */
internal class ReadQueryOrder {
    private var epoch = 0L
    fun next(): Long = ++epoch
    fun isLatest(ticket: Long): Boolean = ticket == epoch
}
