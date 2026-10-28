package com.labteto.dshmobile.data

/** Shared RAM working targets only. Neither disk history nor a large individual record is cut.
 * Tail rollover uses high-water 120 messages and reloads low-water 60 to avoid per-event refolds. */
internal object HistoryWindowBudget {
    const val PAGE_MESSAGES = 60
    const val TAIL_HIGH_MESSAGES = 120
    const val TAIL_LOW_MESSAGES = 60

    fun rawEvents(maxMessages: Int): Long = 4_000L * pages(maxMessages)
    fun payloadBytes(maxMessages: Int): Long = 8L * 1024 * 1024 * pages(maxMessages)
    fun isMessageBoundary(type: String): Boolean =
        com.labteto.dshmobile.core.session.isHistoryMessageBoundary(type)

    private fun pages(messages: Int): Long {
        require(messages > 0)
        return (messages.toLong() + PAGE_MESSAGES - 1) / PAGE_MESSAGES
    }
}
