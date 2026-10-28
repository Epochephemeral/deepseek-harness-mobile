package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Disk-window transitions must preserve the actual visible row, not just a numerical list index. */
class ChatTranscriptDiskPagingTest {
    @get:Rule val compose = createComposeRule()

    private fun rows(first: Long, last: Long): List<ChatNode> = (first..last).map { seq ->
        UserMessageNode(seq, "u$seq", listOf(ChatBlock("text", "row $seq")), sourceKind = "user")
    }

    private class Harness(initial: ConversationSnapshot) {
        var snapshot by mutableStateOf(initial)
        var loadingNewer by mutableStateOf(true)
        var loadNewerFailed by mutableStateOf(false)
        var latestSuccess = true
        var jump by mutableStateOf<Long?>(null)
        var sentTick by mutableIntStateOf(0)
        @Volatile var newerRequests = 0
        var olderRequests = 0
        var loadOlder: () -> Unit = {}
        var latestRequests = 0
        var lastReportedAnchor: Long? = null
        var lastReportedAtTail = false
        var showLatest: suspend () -> Unit = {}
        lateinit var listState: LazyListState
    }

    private fun harness(hasNewer: Boolean = true, initialRows: List<ChatNode> = rows(20, 79), hasMore: Boolean = false): Harness {
        val h = Harness(ConversationSnapshot(sessionId = "disk-session", nodes = initialRows,
            hasNewer = hasNewer, hasMore = hasMore))
        compose.setContent {
            h.listState = rememberLazyListState()
            DshTheme {
                ChatTranscript(
                    conversation = h.snapshot,
                    loading = false,
                    loadingOlder = false,
                    loadOlderFailed = false,
                    context = ChatNodeContext(nodes = h.snapshot.nodes, running = false, cwd = null,
                        onOpenSubagent = {}, onBranchFrom = {}, onFeedback = { _, _ -> }),
                    listState = h.listState,
                    onLoadOlder = { h.olderRequests++; h.loadOlder() },
                    modifier = Modifier.testTag(TRANSCRIPT),
                    jumpToSeq = h.jump,
                    onJumpConsumed = { h.jump = null },
                    sentTick = h.sentTick,
                    loadingNewer = h.loadingNewer,
                    loadNewerFailed = h.loadNewerFailed,
                    onLoadNewer = { h.newerRequests++; h.loadingNewer = true },
                    onShowLatest = { h.latestRequests++; h.showLatest(); h.latestSuccess },
                    onHistoryViewport = { anchor, atTail ->
                        h.lastReportedAnchor = anchor
                        h.lastReportedAtTail = atTail
                    },
                )
            }
        }
        compose.waitForIdle()
        return h
    }

    private data class Anchor(val seq: Long, val offset: Int)
    private fun anchor(h: Harness): Anchor = compose.runOnIdle {
        val item = h.listState.layoutInfo.visibleItemsInfo.first { it.key is Long }
        Anchor(item.key as Long, item.offset)
    }
    private fun assertAnchor(h: Harness, before: Anchor) = compose.runOnIdle {
        val item = h.listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == before.seq }
        assertNotNull("row ${before.seq} left the viewport", item)
        assertEquals("row ${before.seq} moved during window transition", before.offset, item!!.offset)
    }
    private fun readHistory(h: Harness) {
        compose.runOnIdle { h.jump = 40L }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(h.listState.firstVisibleItemIndex > 2) }
    }
    private fun assertLatest(h: Harness) {
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, h.latestRequests)
            assertEquals(0, h.listState.firstVisibleItemIndex)
            assertEquals(0, h.listState.firstVisibleItemScrollOffset)
            assertFalse(h.snapshot.hasNewer)
            assertTrue(h.lastReportedAtTail)
        }
        compose.onNodeWithText("row 159").assertIsDisplayed()
    }

    @Test fun overlappingOlderAndNewerDiskWindowsKeepTheReadingAnchorAndPixelOffset() {
        val h = harness()
        readHistory(h)
        val before = anchor(h)
        val olderFirst = (before.seq - 45).coerceAtLeast(0)
        val newerFirst = (before.seq - 25).coerceAtLeast(0)
        compose.runOnIdle { h.snapshot = h.snapshot.copy(nodes = rows(olderFirst, olderFirst + 59)) }
        compose.waitForIdle()
        assertAnchor(h, before)
        compose.runOnIdle { h.snapshot = h.snapshot.copy(nodes = rows(newerFirst, newerFirst + 59)) }
        compose.waitForIdle()
        assertAnchor(h, before)
        compose.runOnIdle {
            assertEquals(before.seq, h.lastReportedAnchor)
            assertFalse(h.lastReportedAtTail)
            assertEquals(0, h.latestRequests)
        }
    }

    @Test fun newerDiskEdgeWaitsForGenuineGestureAndRequestsOncePerDrag() {
        val h = harness()
        compose.runOnIdle { h.loadingNewer = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("layout alone must not page forward", 0, h.newerRequests) }
        compose.onNodeWithTag(TRANSCRIPT).performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, h.newerRequests)
            assertTrue(h.loadingNewer)
            assertEquals(0, h.latestRequests)
            h.loadingNewer = false
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("settling a request must not start another", 1, h.newerRequests) }
        compose.onNodeWithTag(TRANSCRIPT).performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("a new deliberate drag can page again", 2, h.newerRequests) }
    }

    private fun assertExplicitOlderDoesNotBounceForward(olderRows: List<ChatNode>) {
        val h = harness(hasNewer = false, initialRows = rows(100, 100), hasMore = true)
        compose.runOnIdle {
            h.loadingNewer = false
            h.loadOlder = { h.snapshot = h.snapshot.copy(nodes = olderRows, hasNewer = true) }
        }
        compose.waitForIdle()
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_load_older)
        compose.onNodeWithText(label).assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, h.olderRequests)
            assertEquals("publishing older history must not request the opposite direction", 0, h.newerRequests)
            assertEquals(olderRows, h.snapshot.nodes)
            assertTrue(h.snapshot.hasNewer)
            assertFalse(h.lastReportedAtTail)
            // An unrelated publication must not arm a layout-driven request either.
            h.snapshot = h.snapshot.copy(lastSeq = 99)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, h.newerRequests) }
    }

    @Test fun explicitOlderShortWindowDoesNotImmediatelyPageForward() {
        assertExplicitOlderDoesNotBounceForward(rows(80, 80))
    }

    @Test fun explicitOlderAllSkipWindowDoesNotImmediatelyPageForward() {
        // EventFold emits no nodes for reader/skip coverage rows.
        assertExplicitOlderDoesNotBounceForward(emptyList())
    }

    @Test fun explicitOlderTallSingleRowDoesNotImmediatelyPageForward() {
        assertExplicitOlderDoesNotBounceForward(listOf(UserMessageNode(80, "u80",
            listOf(ChatBlock("text", "Older message line\n".repeat(100))), sourceKind = "user")))
    }

    @Test fun explicitOlderClickDisarmsFollowingEvenBeforeItsPageArrives() {
        val h = harness(hasNewer = false, initialRows = rows(100, 100), hasMore = true)
        compose.runOnIdle { assertTrue(h.lastReportedAtTail) }
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_load_older)
        compose.onNodeWithText(label).assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, h.olderRequests)
            assertFalse("an explicit older click is reading intent, not tail following", h.lastReportedAtTail)
        }
    }

    @Test fun jumpToLatestWaitsForDiskWindowPublicationBeforeMovingTheReader() {
        val h = harness()
        readHistory(h)
        val before = anchor(h)
        val ready = CompletableDeferred<Unit>()
        compose.runOnIdle {
            h.showLatest = {
                ready.await()
                h.snapshot = h.snapshot.copy(nodes = rows(100, 159), hasNewer = false)
            }
        }
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_jump_latest)
        compose.onNodeWithContentDescription(label).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, h.latestRequests); assertTrue(h.snapshot.hasNewer) }
        assertAnchor(h, before)
        compose.runOnIdle { ready.complete(Unit) }
        assertLatest(h)
    }

    @Test fun sendingWhileReadingDiskHistoryLoadsLatestBeforeScrolling() {
        val h = harness()
        readHistory(h)
        val before = anchor(h)
        val ready = CompletableDeferred<Unit>()
        compose.runOnIdle {
            h.showLatest = {
                ready.await()
                h.snapshot = h.snapshot.copy(nodes = rows(100, 159), hasNewer = false)
            }
            h.sentTick++
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, h.latestRequests); assertTrue(h.snapshot.hasNewer) }
        assertAnchor(h, before)
        compose.runOnIdle { ready.complete(Unit) }
        assertLatest(h)
    }

    @Test fun liveRowsArrivingBelowHistoricalReaderDoNotForceScrollOrFreezeTheWindow() {
        val h = harness()
        readHistory(h)
        val before = anchor(h)
        compose.runOnIdle { h.snapshot = h.snapshot.copy(nodes = h.snapshot.nodes + rows(80, 80)) }
        compose.waitForIdle()
        assertAnchor(h, before)
        compose.runOnIdle {
            assertEquals(80L, h.snapshot.nodes.last().seq)
            assertFalse(h.lastReportedAtTail)
            assertEquals(0, h.latestRequests)
        }
    }

    @Test fun historyPickAccountsForTheNewerPageSentinelBeforeMessageRows() {
        val h = harness()
        readHistory(h)
        val root = compose.onNodeWithTag(TRANSCRIPT).getBoundsInRoot()
        val selected = compose.onNodeWithText("row 40").getBoundsInRoot()
        assertTrue("picked row is not at viewport top: $selected in $root", selected.top - root.top < 40.dp)
        compose.runOnIdle { assertFalse(h.lastReportedAtTail) }
    }

    @Test fun failedLatestDiskLoadLeavesReadingAnchorAndDataIntactForJumpAndSend() {
        val h = harness()
        readHistory(h)
        val before = anchor(h)
        val original = compose.runOnIdle { h.snapshot }
        compose.runOnIdle { h.latestSuccess = false }
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_jump_latest)
        compose.onNodeWithContentDescription(label).performClick()
        compose.waitForIdle()
        assertAnchor(h, before)
        compose.runOnIdle {
            assertEquals(1, h.latestRequests)
            assertSame(original, h.snapshot)
            assertTrue(h.snapshot.hasNewer)
            assertFalse(h.lastReportedAtTail)
            h.sentTick++
        }
        compose.waitForIdle()
        assertAnchor(h, before)
        compose.runOnIdle {
            assertEquals(2, h.latestRequests)
            assertSame(original, h.snapshot)
            assertFalse(h.lastReportedAtTail)
        }
    }

    @Test fun failedNewerPageStopsAutomaticRetriesButKeepsAnExplicitRetryAvailable() {
        val h = harness()
        compose.runOnIdle { h.loadingNewer = false }
        compose.waitForIdle()
        compose.onNodeWithTag(TRANSCRIPT).performTouchInput { swipeUp() }
        compose.waitUntil(5_000) { h.newerRequests == 1 }
        compose.runOnIdle {
            h.loadNewerFailed = true
            h.loadingNewer = false
        }
        compose.waitForIdle()
        // An unrelated disk-state publication must not restart the failed automatic request.
        compose.runOnIdle { h.snapshot = h.snapshot.copy(lastSeq = 123L) }
        compose.waitForIdle()
        compose.onNodeWithTag(TRANSCRIPT).performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, h.newerRequests)
            assertFalse(h.loadingNewer)
            assertTrue(h.snapshot.hasNewer)
        }
        val retry = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.common_retry)
        compose.onNodeWithText(retry).assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(2, h.newerRequests)
            assertTrue(h.loadingNewer)
            assertEquals(0, h.latestRequests)
        }
    }

    private companion object { const val TRANSCRIPT = "disk-transcript" }
}
