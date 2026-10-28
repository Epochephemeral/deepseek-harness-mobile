package com.labteto.dshmobile.data

/** A view's position and follow intent. Loaded data is owned only by SessionHistoryReader. */
internal data class HistoryViewportBookmark(
    val followingTail: Boolean,
    val anchorSeq: Long?,
    internal val host: String,
    internal val sessionId: String,
    internal val openEpoch: Long,
)
