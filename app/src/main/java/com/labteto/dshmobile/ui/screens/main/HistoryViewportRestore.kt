package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.lazy.LazyListState
import com.labteto.dshmobile.core.session.ConversationSnapshot
import kotlin.math.abs

/** A list position uses identity plus offset; a numeric index belongs only to one disk window. */
internal data class HistoryReadingPosition(
    val key: Any?,
    val scrollOffset: Int,
    val anchorSeq: Long?,
    val atTail: Boolean,
    val rawExpanded: Boolean = false,
)

internal data class HistoryViewportRestoreRequest(
    val token: Long,
    val position: HistoryReadingPosition,
    val expectedSessionId: String? = null,
    val expectedFirstSeq: Long? = null,
    val expectedLastSeq: Long? = null,
)

/** A StateFlow collector may publish after the restore request; never resolve against a wrong window. */
internal fun historyRestoreWindowReady(conversation: ConversationSnapshot?, keys: List<Any>, request: HistoryViewportRestoreRequest): Boolean {
    if (request.expectedSessionId == null) return true
    if (conversation?.sessionId != request.expectedSessionId) return false
    val first = conversation.journal.firstOrNull()?.seq ?: conversation.nodes.firstOrNull()?.seq
    val last = conversation.journal.lastOrNull()?.seq ?: conversation.nodes.lastOrNull()?.seq
    val expectedLast = request.expectedLastSeq ?: return true
    if (last == null || last < expectedLast) return false
    return (first == request.expectedFirstSeq) || keys.contains(request.position.key) ||
        (request.position.atTail && !conversation.hasNewer)
}

/** Only consumed user movement changes follow intent; layout and idle are deliberately not inputs. */
internal fun transcriptFollowAfterUserScroll(following: Boolean, consumedY: Float, atTail: Boolean): Boolean = when {
    consumedY > 0f -> false // reverse layout: dragging down reveals history
    consumedY < 0f -> atTail
    else -> following
}

/** An exact surviving key is already anchored by LazyColumn; never guess a nearby unrelated row. */
internal fun transcriptFoldAnchorIndex(rows: List<TranscriptRow>, key: Any?): Int? {
    if (rows.any { transcriptRowKey(it) == key }) return null
    val seq = historySequenceOfKey(key) ?: return null
    return rows.indexOfFirst { it is TranscriptRow.Process && historyRowIndex(it.rows, seq) != null }
        .takeIf { it >= 0 }
}

internal fun historySequenceOfKey(key: Any?, streamingSeq: Long? = null): Long? = when (key) {
    is Long -> key
    is String -> when {
        key == "streaming-tail" -> streamingSeq
        key.startsWith("n-") -> key.removePrefix("n-").toLongOrNull()
        key.startsWith("raw-") -> key.removePrefix("raw-").toLongOrNull()
        else -> null
    }
    else -> null
}

internal fun captureHistoryReadingPosition(
    listState: LazyListState,
    reverseLayout: Boolean,
    hasNewer: Boolean,
    streamingSeq: Long? = null,
): HistoryReadingPosition {
    val visible = listState.layoutInfo.visibleItemsInfo
    // visibleItemsInfo may include a preceding item in content padding. The offset belongs to the
    // firstVisibleItemIndex, so it must be paired with that exact item's key.
    val key = visible.firstOrNull { it.index == listState.firstVisibleItemIndex }?.key
    val anchor = historySequenceOfKey(key, streamingSeq) ?:
        visible.firstNotNullOfOrNull { historySequenceOfKey(it.key, streamingSeq) }
    val atTail = !hasNewer && if (reverseLayout) {
        listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
    } else !listState.canScrollForward
    return HistoryReadingPosition(key, listState.firstVisibleItemScrollOffset, anchor, atTail,
        rawExpanded = visible.any { (it.key as? String)?.startsWith("raw-") == true && it.key != "raw-header" })
}

/** Exact identity wins. Sequence proximity is only for a row legitimately replaced/grouped away. */
internal fun historyRestoreIndex(keys: List<Any>, position: HistoryReadingPosition, reverseLayout: Boolean): Int? {
    if (keys.isEmpty()) return null
    if (position.atTail) return if (reverseLayout) 0 else keys.lastIndex
    keys.indexOf(position.key).takeIf { it >= 0 }?.let { return it }
    val seq = position.anchorSeq ?: return null
    val raw = (position.key as? String)?.startsWith("raw-") == true
    val candidates = keys.indices.filter { historySequenceOfKey(keys[it]) != null &&
        (!raw || (keys[it] as? String)?.startsWith("raw-") == true) }
    return candidates.minByOrNull { abs(historySequenceOfKey(keys[it])!! - seq) }
}
