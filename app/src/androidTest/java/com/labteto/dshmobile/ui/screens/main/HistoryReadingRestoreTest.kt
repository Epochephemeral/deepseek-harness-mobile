package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.*
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Tests the real view/sheet lifecycle with windows deliberately losing every previous lazy key. */
class HistoryReadingRestoreTest {
    @get:Rule val compose = createComposeRule()
    private fun window(first: Long, last: Long) = ConversationSnapshot("reading", nodes =
        listOf(TurnStartNode(first - 1, 1)) + (first..last).map {
            UserMessageNode(it, "u$it", listOf(ChatBlock("text", "row $it")), "user")
        }, hasNewer = true)

    private data class Saved(val view: ChatTab, val window: ConversationSnapshot, val position: HistoryReadingPosition)
    private class Harness(initial: ConversationSnapshot) {
        var snapshot by mutableStateOf(initial)
        var tab by mutableStateOf(ChatTab.Chat)
        var sheet by mutableStateOf(false)
        var loadingNewer by mutableStateOf(true)
        var newerRequests = 0
        var draft = ""
        var editPosition: HistoryReadingPosition? = null
        val reader = HistoryReadingState()
        val saved = mutableMapOf<ChatTab, Saved>()
        var sheetSaved: Saved? = null
        lateinit var chat: LazyListState
        lateinit var ledger: LazyListState

        fun position(view: ChatTab = tab) = captureHistoryReadingPosition(
            if (view == ChatTab.Chat) chat else ledger, view == ChatTab.Chat, snapshot.hasNewer)
        fun openSheet() {
            sheetSaved = Saved(tab, snapshot, position())
            reader.rendered[tab] = HistoryPaneSnapshot(snapshot, null, null, null, true)
            sheet = true
        }
        fun dismissSheet(after: () -> Unit = {}) {
            val original = sheetSaved!!
            snapshot = original.window
            reader.request(original.view, original.position, snapshot) { sheet = false; after() }
        }
        fun switchTab(target: ChatTab) {
            saved[tab] = Saved(tab, snapshot, position())
            reader.rendered[tab] = HistoryPaneSnapshot(snapshot, null, null, null, true)
            saved[target]?.let { bookmark ->
                snapshot = bookmark.window
                reader.request(target, bookmark.position, snapshot)
            }
            tab = target
        }
    }

    private fun harness(): Harness {
        val h = Harness(window(20, 139))
        compose.setContent {
            h.chat = rememberLazyListState()
            h.ledger = rememberLazyListState()
            DshTheme {
                Box(Modifier.fillMaxSize()) {
                    AnimatedContent(targetState = h.tab, label = "reading-test-tabs") { view ->
                        val enabled = view == h.tab && !h.sheet && !h.reader.restoring
                        val request = if (view == ChatTab.Chat) h.reader.chatRestore else h.reader.trajectoryRestore
                        val retained = h.reader.rendered[view]?.conversation
                        val covered = view != h.tab || ((h.sheet || h.reader.restoring) && request == null)
                        val pane = if (covered && retained != null) retained else h.snapshot
                        when (view) {
                            ChatTab.Chat -> ChatTranscript(
                                conversation = pane, loading = false, loadingOlder = false, loadOlderFailed = false,
                                context = ChatNodeContext(pane.nodes, false, null, onOpenSubagent = {}, onBranchFrom = {}, onFeedback = { _, _ -> }),
                                listState = h.chat, onLoadOlder = {}, loadingNewer = h.loadingNewer,
                                onLoadNewer = { h.newerRequests++; h.loadingNewer = true },
                                historyInteractionEnabled = enabled,
                                viewportRestore = h.reader.chatRestore,
                                onViewportRestored = h.reader::consumed,
                            )
                            ChatTab.Trajectory -> TrajectoryTab(
                                conversation = pane, stats = null, usage = null, cwd = null, listState = h.ledger,
                                historyInteractionEnabled = enabled,
                                viewportRestore = h.reader.trajectoryRestore,
                                onViewportRestored = h.reader::consumed,
                            )
                        }
                    }
                    if (h.sheet) HistorySheet(
                        entries = historyEntries(h.snapshot.nodes, false, emptyMap()), hasMore = true,
                        loadingOlder = false, loadOlderFailed = false,
                        onLoadOlder = { h.snapshot = window(1000, 1119) },
                        onPick = {}, onDismiss = { h.dismissSheet() },
                        onDismissThen = { after -> h.dismissSheet(after) },
                        onEdit = { text -> h.draft = text },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { runBlocking { h.chat.scrollToItem(40, 17) } }
        compose.waitForIdle()
        return h
    }

    private fun assertPosition(h: Harness, view: ChatTab, before: HistoryReadingPosition) = compose.runOnIdle {
        val actual = h.position(view)
        assertEquals("wrong lazy key in $view", before.key, actual.key)
        assertEquals("wrong pixel offset in $view", before.scrollOffset, actual.scrollOffset)
        assertFalse(h.reader.restoring)
    }

    @Test fun dismissingHistoryRestoresExactReadingKeyAndOffsetAcrossOverlappingAndDisjointWindows() {
        val h = harness()
        val before = compose.runOnIdle { h.position() }
        assertEquals(17, before.scrollOffset)
        listOf(window(10, 129), window(1000, 1119)).forEach { older ->
            compose.runOnIdle { h.openSheet() }
            compose.waitForIdle()
            compose.runOnIdle { h.snapshot = older; h.loadingNewer = false }
            compose.waitForIdle()
            // The sheet sees the other window, while its translucent background keeps the original
            // position even if Material hides the modal before invoking its dismissal callback.
            compose.runOnIdle {
                assertEquals(older, h.snapshot)
                assertEquals(before.key, h.position().key)
                assertEquals(before.scrollOffset, h.position().scrollOffset)
                assertEquals(0, h.newerRequests)
                h.dismissSheet()
            }
            compose.waitForIdle()
            compose.runOnIdle { assertFalse(h.sheet) }
            assertPosition(h, ChatTab.Chat, before)
        }
    }

    @Test fun editingFromHistoryRunsOnlyAfterTheCoveredReadingPositionHasBeenRestored() {
        val h = harness()
        val before = compose.runOnIdle { h.position() }
        compose.runOnIdle { h.openSheet() }
        compose.waitForIdle()
        val load = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_load_older)
        compose.onNodeWithText(load).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            h.dismissSheet {
                h.editPosition = h.position()
                h.draft = "edit this prompt"
            }
            assertEquals("", h.draft)
        }
        compose.waitForIdle()
        assertPosition(h, ChatTab.Chat, before)
        compose.runOnIdle {
            assertEquals(before.key, h.editPosition!!.key)
            assertEquals(before.scrollOffset, h.editPosition!!.scrollOffset)
            assertEquals("edit this prompt", h.draft)
        }
    }

    @Test fun tabRoundTripRestoresBothViewsByKeyAfterTheirWindowsNoLongerOverlap() {
        val h = harness()
        val chatBefore = compose.runOnIdle { h.position() }
        compose.runOnIdle { h.switchTab(ChatTab.Trajectory) }
        compose.waitForIdle()
        compose.runOnIdle { h.snapshot = window(1000, 1119) }
        compose.waitForIdle()
        compose.runOnIdle { runBlocking { h.ledger.scrollToItem(41, 11) } }
        compose.waitForIdle()
        val ledgerBefore = compose.runOnIdle { h.position() }
        compose.runOnIdle { h.switchTab(ChatTab.Chat) }
        compose.waitForIdle()
        assertPosition(h, ChatTab.Chat, chatBefore)
        compose.runOnIdle { h.switchTab(ChatTab.Trajectory) }
        compose.waitForIdle()
        assertPosition(h, ChatTab.Trajectory, ledgerBefore)
    }
}
