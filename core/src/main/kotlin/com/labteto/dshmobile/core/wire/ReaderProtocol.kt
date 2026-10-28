package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.wire.dto.SessionReaderCoverage

const val READER_VIEW = "reader"
const val READER_MAX_ROWS = 128
const val READER_MAX_BYTES = 262144

/** Only acknowledgements with complete raw coverage can enter the separate reader archive. */
fun requireReaderCoverage(
    reader: SessionReaderCoverage?, first: Long?, last: Long?, count: Long,
    throughSeq: Int, beforeSeq: Int? = null, hasMore: Boolean,
) {
    require(reader?.version == 1) {
        "This host does not support lightweight reader v1. Upgrade the host; raw history was not loaded."
    }
    val coverage = requireNotNull(reader)
    require(count <= READER_MAX_ROWS && (coverage.bytes <= READER_MAX_BYTES || (coverage.oversized && count == 1L))) {
        "Reader page exceeded requested row or byte bounds"
    }
    val end = minOf(throughSeq, beforeSeq?.minus(1) ?: throughSeq)
    require(coverage.throughSeq == end && coverage.bytes >= 0 && coverage.rawDetails == "session/page") {
        "Invalid reader coverage metadata"
    }
    require(if (count == 0L) coverage.fromSeq > coverage.throughSeq && first == null && last == null
        else first == coverage.fromSeq.toLong() && last == coverage.throughSeq.toLong() &&
            count == coverage.throughSeq.toLong() - coverage.fromSeq + 1) { "Incomplete reader coverage" }
    require(coverage.nextBeforeSeq == coverage.fromSeq && hasMore == (coverage.fromSeq > 0)) {
        "Invalid reader paging cursor"
    }
}
