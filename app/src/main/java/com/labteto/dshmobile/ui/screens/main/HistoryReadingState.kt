package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.data.HistoryViewportBookmark
import com.labteto.dshmobile.core.wire.dto.SessionStatsView
import com.labteto.dshmobile.core.wire.dto.TokenUsageView

internal data class HistoryPaneSnapshot(
    val conversation: ConversationSnapshot?,
    val stats: SessionStatsView?,
    val usage: TokenUsageView?,
    val cwd: String?,
    val historyReady: Boolean,
)

internal data class HistoryViewBookmark(val view: ChatTab, val window: HistoryViewportBookmark, val position: HistoryReadingPosition)

/** One owner/session's tab and modal bookmarks. Window records never include transient preview. */
internal class HistoryReadingState {
    val views = mutableMapOf<ChatTab, HistoryViewBookmark>()
    // Display-only references keep the covered/outgoing pane unchanged while a different window
    // feeds the sheet/incoming tab. Actions always use the live caller's context, never this cache.
    val rendered = mutableMapOf<ChatTab, HistoryPaneSnapshot>()
    var sheetBookmark: HistoryViewBookmark? = null
    var restoring by mutableStateOf(false)
    var chatRestore by mutableStateOf<HistoryViewportRestoreRequest?>(null)
        private set
    var trajectoryRestore by mutableStateOf<HistoryViewportRestoreRequest?>(null)
        private set
    private var serial = 0L
    private var afterRestore: (() -> Unit)? = null

    fun request(view: ChatTab, position: HistoryReadingPosition, snapshot: ConversationSnapshot?, after: () -> Unit = {}) {
        restoring = true
        val request = HistoryViewportRestoreRequest(++serial, position, snapshot?.sessionId,
            snapshot?.journal?.firstOrNull()?.seq ?: snapshot?.nodes?.firstOrNull()?.seq,
            snapshot?.journal?.lastOrNull()?.seq ?: snapshot?.nodes?.lastOrNull()?.seq)
        afterRestore = after
        chatRestore = null
        trajectoryRestore = null
        when (view) {
            ChatTab.Chat -> chatRestore = request
            ChatTab.Trajectory -> trajectoryRestore = request
        }
    }

    fun consumed(token: Long) {
        if (chatRestore?.token != token && trajectoryRestore?.token != token) return
        chatRestore = null
        trajectoryRestore = null
        val after = afterRestore
        afterRestore = null
        restoring = false
        after?.invoke()
    }

    fun cancelRestore() {
        chatRestore = null
        trajectoryRestore = null
        afterRestore = null
        restoring = false
    }
}
