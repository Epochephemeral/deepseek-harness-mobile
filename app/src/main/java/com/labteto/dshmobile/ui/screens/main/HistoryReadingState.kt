package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.wire.dto.SessionStatsView
import com.labteto.dshmobile.core.wire.dto.TokenUsageView
import com.labteto.dshmobile.data.HistoryViewportBookmark
import com.labteto.dshmobile.data.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal data class HistoryPaneSnapshot(
    val conversation: ConversationSnapshot?,
    val stats: SessionStatsView?,
    val usage: TokenUsageView?,
    val cwd: String?,
    val historyReady: Boolean,
)

internal data class HistoryPaneCapture(val position: HistoryReadingPosition, val pane: HistoryPaneSnapshot)
internal data class HistoryViewBookmark(val view: ChatTab, val window: HistoryViewportBookmark, val position: HistoryReadingPosition)

/** Identity/disclosure state lives with the reading owner, not a lazy item or an outgoing tab. */
internal class ChatToolGroupState {
    var rows: List<TranscriptRow> = emptyList()
    private val expansions = mutableMapOf<Long, MutableState<Boolean>>()
    fun expansion(identitySeq: Long): MutableState<Boolean> =
        expansions.getOrPut(identitySeq) { mutableStateOf(false) }
}

/** Stateless data boundary. Loaded coverage, authority and read tokens belong to the store. */
internal interface HistoryReadingAccess {
    val conversation: ConversationSnapshot?
    fun updateViewport(anchorSeq: Long?, atTail: Boolean)
    fun captureViewport(): HistoryViewportBookmark?
    suspend fun restoreViewport(bookmark: HistoryViewportBookmark): Boolean
    suspend fun seekTurn(startSeq: Long): Boolean
}

/**
 * One host/session's reading coordinator. Owns modal/tab bookmarks, seek/restore jobs and UI acks;
 * never owns or replaces loaded events. Disposal cancels every operation, not just the last seek.
 */
internal class HistoryReadingState(
    private val access: HistoryReadingAccess,
    parentScope: CoroutineScope,
    private val sessionId: String?,
) {
    constructor(store: SessionStore, parentScope: CoroutineScope, sessionId: String?) : this(
        object : HistoryReadingAccess {
            override val conversation get() = store.currentConversation.value
            override fun updateViewport(anchorSeq: Long?, atTail: Boolean) = store.updateHistoryViewport(anchorSeq, atTail)
            override fun captureViewport() = store.captureHistoryViewport()
            override suspend fun restoreViewport(bookmark: HistoryViewportBookmark) = store.restoreHistoryViewport(bookmark)
            override suspend fun seekTurn(startSeq: Long) = store.seekHistoryTurn(startSeq)
        }, parentScope, sessionId,
    )

    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private var actionJob: Job? = null
    private val views = mutableMapOf<ChatTab, HistoryViewBookmark>()
    // Display-only snapshots keep the covered/outgoing pane stable while another view is read.
    private val rendered = mutableMapOf<ChatTab, HistoryPaneSnapshot>()
    private var sheetBookmark: HistoryViewBookmark? = null
    val chatToolGroups = ChatToolGroupState()
    var sheetOpen by mutableStateOf(false)
        private set
    var seeking by mutableStateOf(false)
        private set
    var failedEntry by mutableStateOf<HistoryEntry?>(null)
        private set
    var pendingJumpSeq by mutableStateOf<Long?>(null)
        private set
    var restoring by mutableStateOf(false)
        private set
    var chatRestore by mutableStateOf<HistoryViewportRestoreRequest?>(null)
        private set
    var trajectoryRestore by mutableStateOf<HistoryViewportRestoreRequest?>(null)
        private set
    private var serial = 0L
    private var afterRestore: (() -> Unit)? = null

    val canReadSheet: Boolean get() = lifetime.isActive && sheetOpen && !restoring && !seeking
    fun interactionAllowed(view: ChatTab, activeView: ChatTab): Boolean =
        lifetime.isActive && view == activeView && !sheetOpen && !restoring

    fun readyRestore(view: ChatTab, snapshot: ConversationSnapshot?): HistoryViewportRestoreRequest? =
        (if (view == ChatTab.Chat) chatRestore else trajectoryRestore)
            ?.takeIf { historyRestoreWindowReady(snapshot, emptyList(), it) }

    fun pane(view: ChatTab, activeView: ChatTab, current: HistoryPaneSnapshot): HistoryPaneSnapshot {
        val covered = view != activeView || ((sheetOpen || restoring) && readyRestore(view, current.conversation) == null)
        return if (covered) rendered[view] ?: current else current
    }

    private fun captureReading(view: ChatTab, value: HistoryPaneCapture): HistoryViewBookmark? {
        access.updateViewport(value.position.anchorSeq, value.position.atTail)
        val window = access.captureViewport() ?: return null
        rendered[view] = value.pane
        return HistoryViewBookmark(view, window, value.position).also { views[view] = it }
    }

    fun openHistory(view: ChatTab, capture: () -> HistoryPaneCapture): Boolean {
        if (!interactionAllowed(view, view)) return false
        sheetBookmark = captureReading(view, capture())
        failedEntry = null
        sheetOpen = true
        return true
    }

    fun switchView(from: ChatTab, target: ChatTab, capture: () -> HistoryPaneCapture, activate: () -> Unit) {
        if (!interactionAllowed(from, from)) return
        if (target == from) { activate(); return }
        captureReading(from, capture())
        val bookmark = views[target]
        if (bookmark == null) { activate(); return }
        restoring = true
        launchAction(onFailure = { views.remove(target); cancelRestore() }) {
            val restored = access.restoreViewport(bookmark.window)
            currentCoroutineContext().ensureActive()
            if (restored) request(target, bookmark.position, access.conversation)
            else { views.remove(target); cancelRestore() }
            activate()
        }
    }

    fun dismissHistory(after: () -> Unit = {}) {
        if (!lifetime.isActive || !sheetOpen || restoring) return
        val previous = actionJob
        previous?.cancel()
        val bookmark = sheetBookmark
        restoring = true
        launchAction(onFailure = { cancelRestore() }) {
            // A cancelled seek cannot publish after restoring the covered viewport.
            previous?.join()
            currentCoroutineContext().ensureActive()
            val finish = { sheetBookmark = null; sheetOpen = false; after() }
            if (bookmark == null) { cancelRestore(); finish(); return@launchAction }
            val restored = access.restoreViewport(bookmark.window)
            currentCoroutineContext().ensureActive()
            if (restored) request(bookmark.view, bookmark.position, access.conversation, finish)
            else { cancelRestore(); finish() }
        }
    }

    fun pick(entry: HistoryEntry, activateChat: () -> Unit) {
        if (!canReadSheet) return
        seeking = true
        failedEntry = null
        launchAction(onFailure = { failedEntry = entry }) {
            val loaded = access.seekTurn(entry.targetStartSeq ?: entry.seq)
            currentCoroutineContext().ensureActive()
            val current = access.conversation
            val target = if (loaded && current != null && current.sessionId == sessionId)
                historyJumpTarget(entry, current.nodes, current.running, current.projections["turnOutline"])
            else null
            if (target == null) { failedEntry = entry; return@launchAction }
            access.updateViewport(target, false)
            sheetBookmark = null
            pendingJumpSeq = target
            sheetOpen = false
            activateChat()
        }
    }

    fun jumpConsumed() { pendingJumpSeq = null }

    private fun launchAction(onFailure: () -> Unit, block: suspend () -> Unit) {
        actionJob?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (lifetime.isActive && currentCoroutineContext()[Job]?.isActive == true) onFailure()
            }
            finally {
                if (actionJob === currentCoroutineContext()[Job]) {
                    actionJob = null
                    seeking = false
                    if (chatRestore == null && trajectoryRestore == null) restoring = false
                }
            }
        }
        actionJob = job
        job.start()
    }

    fun request(view: ChatTab, position: HistoryReadingPosition, snapshot: ConversationSnapshot?, after: () -> Unit = {}) {
        if (!lifetime.isActive) return
        restoring = true
        val request = HistoryViewportRestoreRequest(++serial, position, snapshot?.sessionId,
            snapshot?.loadedFromSeq ?: snapshot?.journal?.firstOrNull()?.seq ?: snapshot?.nodes?.firstOrNull()?.seq,
            snapshot?.loadedThroughSeq ?: snapshot?.journal?.lastOrNull()?.seq ?: snapshot?.nodes?.lastOrNull()?.seq)
        afterRestore = after
        chatRestore = null
        trajectoryRestore = null
        when (view) {
            ChatTab.Chat -> chatRestore = request
            ChatTab.Trajectory -> trajectoryRestore = request
        }
    }

    fun consumed(token: Long) {
        if (!lifetime.isActive || (chatRestore?.token != token && trajectoryRestore?.token != token)) return
        chatRestore = null
        trajectoryRestore = null
        val after = afterRestore
        afterRestore = null
        restoring = false
        after?.invoke()
    }

    private fun cancelRestore() {
        chatRestore = null
        trajectoryRestore = null
        afterRestore = null
        restoring = false
    }

    fun dispose() {
        lifetime.cancel()
        actionJob = null
        seeking = false
        sheetOpen = false
        pendingJumpSeq = null
        cancelRestore()
    }
}
