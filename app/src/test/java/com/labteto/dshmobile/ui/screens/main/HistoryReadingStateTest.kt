package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.data.HistoryViewportBookmark
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryReadingStateTest {
    private class Access : HistoryReadingAccess {
        override var conversation = ConversationSnapshot("s", nodes = listOf(
            UserMessageNode(50, "u50", listOf(ChatBlock("text", "prompt")), "user")),
            loadedFromSeq = 0, loadedThroughSeq = 100)
        var anchor: Long? = 50
        var atTail = false
        var restores = 0
        var seeks = 0
        var restore: suspend () -> Boolean = { true }
        var seek: suspend () -> Boolean = { true }
        override fun updateViewport(anchorSeq: Long?, atTail: Boolean) { anchor = anchorSeq; this.atTail = atTail }
        override fun captureViewport() = HistoryViewportBookmark(atTail, anchor, "host", "s", 1)
        override suspend fun restoreViewport(bookmark: HistoryViewportBookmark): Boolean { restores++; return restore() }
        override suspend fun seekTurn(startSeq: Long): Boolean { seeks++; return seek() }
        fun capture(key: Long = 50, offset: Int = 17) = HistoryPaneCapture(
            HistoryReadingPosition(key, offset, key, false), HistoryPaneSnapshot(conversation, null, null, null, true))
    }
    private val entry = HistoryEntry(50, "prompt", false, null)

    @Test fun `dismiss cancels and joins seek then waits for viewport ack before editing`() = runTest {
        val access = Access()
        val reader = HistoryReadingState(access, this, "s")
        var cancelled = false
        var activated = false
        var edited = false
        access.seek = { try { awaitCancellation() } finally { cancelled = true } }
        reader.openHistory(ChatTab.Chat) { access.capture() }
        reader.pick(entry) { activated = true }
        runCurrent()
        assertTrue(reader.seeking)
        reader.dismissHistory { edited = true }
        runCurrent()
        assertTrue(cancelled)
        assertEquals(1, access.restores)
        assertFalse(activated)
        assertFalse(edited)
        assertTrue(reader.sheetOpen)
        assertTrue(reader.restoring)
        val restore = requireNotNull(reader.chatRestore)
        assertEquals(0L, restore.expectedFirstSeq)
        assertEquals(100L, restore.expectedLastSeq)
        reader.consumed(restore.token + 1)
        assertFalse(edited)
        reader.consumed(restore.token)
        assertTrue(edited)
        assertFalse(reader.sheetOpen)
        assertFalse(reader.restoring)
        reader.dispose()
    }

    @Test fun `disposed owner rejects late seek completion and stale restore callbacks`() = runTest {
        val access = Access()
        val reader = HistoryReadingState(access, this, "s")
        val release = CompletableDeferred<Unit>()
        var activated = false
        access.seek = { withContext(NonCancellable) { release.await() }; true }
        reader.openHistory(ChatTab.Chat) { access.capture() }
        reader.pick(entry) { activated = true }
        runCurrent()
        reader.dispose()
        release.complete(Unit)
        runCurrent()
        assertFalse(activated)
        assertNull(reader.pendingJumpSeq)
        assertFalse(reader.sheetOpen)
        assertFalse(reader.interactionAllowed(ChatTab.Chat, ChatTab.Chat))
        var callback = false
        reader.request(ChatTab.Chat, access.capture().position, access.conversation) { callback = true }
        reader.consumed(1)
        assertFalse(callback)
    }

    @Test fun `failed seek stays on sheet and explicit retry owns one pending jump`() = runTest {
        val access = Access()
        val reader = HistoryReadingState(access, this, "s")
        access.seek = { false }
        reader.openHistory(ChatTab.Chat) { access.capture() }
        reader.pick(entry) { fail("failed seek activated Chat") }
        runCurrent()
        assertEquals(entry, reader.failedEntry)
        assertTrue(reader.sheetOpen)
        assertFalse(reader.seeking)
        access.seek = { true }
        var activated = false
        reader.pick(requireNotNull(reader.failedEntry)) { activated = true }
        runCurrent()
        assertEquals(2, access.seeks)
        assertTrue(activated)
        assertNull(reader.failedEntry)
        assertEquals(50L, reader.pendingJumpSeq)
        assertFalse(reader.sheetOpen)
        reader.jumpConsumed()
        assertNull(reader.pendingJumpSeq)
        reader.dispose()
    }

    @Test fun `tab restore blocks competing actions and disposal cancels delayed restoration`() = runTest {
        val access = Access()
        val reader = HistoryReadingState(access, this, "s")
        var tab = ChatTab.Chat
        reader.switchView(tab, ChatTab.Trajectory, { access.capture() }) { tab = ChatTab.Trajectory }
        assertEquals(ChatTab.Trajectory, tab)
        val release = CompletableDeferred<Unit>()
        access.restore = { withContext(NonCancellable) { release.await() }; true }
        reader.switchView(tab, ChatTab.Chat, { access.capture(70, 11) }) { tab = ChatTab.Chat }
        runCurrent()
        assertTrue(reader.restoring)
        assertFalse(reader.openHistory(tab) { access.capture() })
        reader.dispose()
        release.complete(Unit)
        runCurrent()
        assertEquals(ChatTab.Trajectory, tab)
        assertNull(reader.chatRestore)
    }
}
