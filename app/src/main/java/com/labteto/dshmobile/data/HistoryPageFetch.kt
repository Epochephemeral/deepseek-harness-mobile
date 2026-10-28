package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.session.ChunkRows
import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.READER_VIEW
import com.labteto.dshmobile.core.wire.requireReaderCoverage
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.SessionPageMetadata
import com.labteto.dshmobile.core.wire.dto.SessionPageRequest

/** Metadata only: record payloads are staged on disk, never retained as a whole page. */
internal data class HistoryPageFetchSummary(
    val firstSeq: Long?,
    val lastSeq: Long?,
    val metadata: SessionPageMetadata,
    val committed: Boolean = false,
)

/**
 * Shared ordinary/subagent paging primitive. The caller must invoke commit under its source/session
 * lock only while its captured API, generation and cursor are current. It may reject empty or
 * non-progressing pages using the summary. The stage itself fences the captured cache-clear ticket.
 * A rejected/stale/malformed/failed/canceled page is closed without publication. There is no retry.
 */
internal suspend fun fetchStagedHistoryPage(
    api: DshApiClient,
    cache: HistoryCache,
    host: String,
    archiveKey: String,
    ticket: Long,
    request: SessionPageRequest,
    commitIfCurrent: (summary: HistoryPageFetchSummary, commit: () -> Boolean) -> Boolean,
): RpcResult<HistoryPageFetchSummary> {
    var first: Long? = null
    var last: Long? = null
    var callbackFailure: Exception? = null
    var previous: Long? = null
    var contiguous = true
    cache.beginPage(host, archiveKey, ticket).use { staged ->
        val result = api.sessionPageEach(request) { record ->
            try {
                val events = ChunkRows.expand(record).map(::wireEventToEnvelope)
                for (event in events) {
                    if (previous != null && event.seq != previous!! + 1) contiguous = false
                    previous = event.seq
                    first = first?.let { minOf(it, event.seq) } ?: event.seq
                    last = last?.let { maxOf(it, event.seq) } ?: event.seq
                }
                staged.append(events)
            } catch (failure: Exception) {
                callbackFailure = failure
                throw failure
            }
        }
        // Unary decoding classifies malformed DTOs as protocol errors. A sink's real SQLite/IO
        // failure is a local storage fault instead and must reach the caller's recovery handler.
        callbackFailure?.let { throw it }
        return when (result) {
            is RpcResult.Err -> result
            is RpcResult.Ok -> {
                if (request.view == READER_VIEW) {
                    requireReaderCoverage(result.value.reader, first, last, result.value.recordCount,
                        request.throughSeq, request.beforeSeq, result.value.hasMore)
                    require(contiguous) { "Noncontiguous reader page" }
                }
                val summary = HistoryPageFetchSummary(first, last, result.value)
                RpcResult.Ok(summary.copy(committed = commitIfCurrent(summary) { staged.commit() }))
            }
        }
    }
}
