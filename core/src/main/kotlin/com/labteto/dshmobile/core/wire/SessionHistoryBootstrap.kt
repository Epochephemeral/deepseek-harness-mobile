package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.wire.dto.SessionAddress
import com.labteto.dshmobile.core.wire.dto.SessionFollowFrame
import com.labteto.dshmobile.core.wire.dto.SessionPage
import com.labteto.dshmobile.core.wire.dto.SessionPageRequest

/** Keep the uncompressed WebSocket opening small; the full window travels over gzip-capable HTTP. */
const val INITIAL_FOLLOW_MESSAGES = 1

/**
 * Read the initial history window at the follow stream's exact cut.
 *
 * The mux queues subsequent live frames while this suspends. Applying this snapshot before
 * consuming those frames therefore preserves the same ordering as a large follow opening,
 * without sending the whole history page over an uncompressed WebSocket. Projections and the
 * live assistant baseline belong to the opening frame and must not be replaced by the page.
 * Cancellation and read failures propagate: an incomplete window is never presented as complete.
 */
suspend fun hydrateSessionSnapshot(
    address: SessionAddress,
    snapshot: SessionFollowFrame.Snapshot,
    maxMessages: Int,
    readPage: suspend (SessionPageRequest) -> RpcResult<SessionPage>,
): SessionFollowFrame.Snapshot {
    if (!snapshot.hasMore) return snapshot
    val request = SessionPageRequest(address = address, throughSeq = snapshot.cursor, maxMessages = maxMessages)
    return when (val result = readPage(request)) {
        is RpcResult.Ok -> snapshot.copy(records = result.value.records, hasMore = result.value.hasMore)
        is RpcResult.Err -> throw RemoteStreamException(result.error, carrier = false)
    }
}
