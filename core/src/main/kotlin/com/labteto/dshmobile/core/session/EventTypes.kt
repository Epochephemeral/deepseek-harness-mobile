package com.labteto.dshmobile.core.session

/** Framing/bookkeeping types, not standalone transcript content. Unknown types remain content.
 * Typed turn-end nodes may still render interruption/error notices; this classification only
 * keeps framing rows from displacing actual message/tool content in a disk viewport. */
val STRUCTURAL_EVENT_TYPES: Set<String> = setOf(
    "turn/start",
    "turn/end",
    "step/start",
    "step/end",
    "session/end-seed",
    "session/title-llm-request",
    "agent/inbox/spliced",
    "assistant/chunk",
    "assistant/attempt",
)

fun isHistoryContentEventType(type: String): Boolean = type !in STRUCTURAL_EVENT_TYPES
fun isHistoryMessageBoundary(type: String): Boolean = type == "user/message" || type == "assistant/message"
