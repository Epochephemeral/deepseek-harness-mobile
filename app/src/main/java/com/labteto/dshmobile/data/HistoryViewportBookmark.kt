package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.session.SessionEventEnvelope

/** One view's bounded durable window. Never holds live projections or restores action authority. */
internal data class HistoryViewportBookmark(
    val followingTail: Boolean,
    val anchorSeq: Long?,
    internal val host: String,
    internal val sessionId: String,
    internal val openEpoch: Long,
    internal val events: List<SessionEventEnvelope>,
    internal val hasOlder: Boolean,
    internal val hasNewer: Boolean,
    internal val legacy: Boolean,
    internal val cacheTicket: Long,
    internal val payloadBytes: Long = 0,
)
