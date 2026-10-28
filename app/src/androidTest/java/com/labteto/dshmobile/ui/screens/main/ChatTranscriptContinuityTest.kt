package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** UI half of the cumulative-store contract: the item provider must never re-window loaded rows. */
class ChatTranscriptContinuityTest {
    @get:Rule val compose = createComposeRule()
    private fun rows(first: Long, last: Long): List<ChatNode> = (first..last).map {
        UserMessageNode(it, "u$it", listOf(ChatBlock("text", "row $it")), "user")
    }
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private class Harness(initial: ConversationSnapshot) {
        var snapshot by mutableStateOf(initial)
        var loadingOlder by mutableStateOf(false)
        var loadingNewer by mutableStateOf(false)
        var jump by mutableStateOf<Long?>(null)
        var olderRequests = 0
        var newerRequests = 0
        var latestRequests = 0
        var reportedAtTail = false
        lateinit var list: LazyListState
    }
    private fun harness(hasNewer: Boolean): Harness {
        val h = Harness(ConversationSnapshot("continuity", nodes = rows(1024, 1151),
            hasMore = true, hasNewer = hasNewer))
        compose.setContent {
            h.list = rememberLazyListState()
            DshTheme {
                ChatTranscript(h.snapshot, loading = false, loadingOlder = h.loadingOlder, loadOlderFailed = false,
                    context = ChatNodeContext(h.snapshot.nodes, false, null,
                        onOpenSubagent = {}, onBranchFrom = {}, onFeedback = { _, _ -> }),
                    listState = h.list,
                    onLoadOlder = { h.olderRequests++; h.loadingOlder = true },
                    loadingNewer = h.loadingNewer,
                    onLoadNewer = { h.newerRequests++; h.loadingNewer = true },
                    onShowLatest = { h.latestRequests++; true },
                    jumpToSeq = h.jump, onJumpConsumed = { h.jump = null },
                    onHistoryViewport = { _, atTail -> h.reportedAtTail = atTail })
            }
        }
        compose.waitForIdle()
        return h
    }
    private data class Anchor(val key: Any, val offset: Int)
    private fun anchor(h: Harness): Anchor = compose.runOnIdle {
        val item = h.list.layoutInfo.visibleItemsInfo.first { it.key is Long }
        Anchor(item.key, item.offset)
    }
    private fun assertAnchor(h: Harness, before: Anchor) = compose.runOnIdle {
        val item = h.list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == before.key }
        assertNotNull("reading row ${before.key} disappeared", item)
        assertEquals("reading row ${before.key} moved", before.offset, item!!.offset)
        assertFalse(h.reportedAtTail)
    }
    private fun scrollTo(h: Harness, index: Int, offset: Int = 0) {
        compose.runOnIdle { runBlocking { h.list.scrollToItem(index, offset) } }
        compose.waitForIdle()
    }
    private fun reach(h: Harness, seq: Long) {
        // performScrollToNode walks descendants on the instrumentation thread while lazy layout
        // can still be measuring. Use the supported index action, with all state reads on Main.
        val index = compose.runOnIdle {
            val chronologicalIndex = h.snapshot.nodes.indexOfFirst { it.seq == seq }
            assertTrue("loaded row $seq was dropped", chronologicalIndex >= 0)
            h.snapshot.nodes.lastIndex - chronologicalIndex + if (h.snapshot.hasNewer) 1 else 0
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(index)
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("loaded row $seq is not reachable in the lazy provider",
                h.list.layoutInfo.visibleItemsInfo.any { it.key == seq })
        }
        compose.onNodeWithText("row $seq").assertIsDisplayed()
    }

    @Test fun semanticsTraversalDoesNotPageButAGenuineEdgeDragStillDoes() {
        val h = harness(hasNewer = true)
        // Exercise ScrollBy explicitly: unlike ScrollToIndex this reports nested UserInput, but
        // must not be mistaken for a pointer gesture when it traverses a loaded boundary.
        compose.onNode(hasScrollToIndexAction()).performSemanticsAction(SemanticsActions.ScrollBy) {
            assertTrue(it(0f, 100_000f))
        }
        compose.waitForIdle()
        val firstEdge = compose.runOnIdle { h.list.firstVisibleItemIndex }
        compose.onNode(hasScrollToIndexAction()).performSemanticsAction(SemanticsActions.ScrollBy) {
            assertTrue(it(0f, -100_000f))
        }
        compose.waitForIdle()
        compose.runOnIdle { assertNotEquals(firstEdge, h.list.firstVisibleItemIndex) }
        compose.runOnIdle {
            assertEquals(0, h.olderRequests)
            assertEquals(0, h.newerRequests)
        }
        scrollTo(h, compose.runOnIdle { h.list.layoutInfo.totalItemsCount - 1 })
        compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, h.olderRequests)
            assertEquals(0, h.newerRequests)
        }
    }

    @Test fun sixOlderBatchesGrowTheListAndEveryBatchRemainsReachableWithoutNewerRequests() {
        val h = harness(hasNewer = false)
        val markers = mutableListOf(1151L)
        repeat(6) { batch ->
            scrollTo(h, compose.runOnIdle { h.list.layoutInfo.totalItemsCount - 1 })
            compose.onNodeWithText(label(R.string.chat_load_older)).performClick()
            compose.waitForIdle()
            val before = anchor(h)
            val oldCount = compose.runOnIdle { h.list.layoutInfo.totalItemsCount }
            val first = 1024L - (batch + 1) * 128L
            compose.runOnIdle {
                h.snapshot = h.snapshot.copy(nodes = rows(first, first + 127) + h.snapshot.nodes)
                h.loadingOlder = false // flags and data may be observed together
            }
            compose.waitForIdle()
            assertAnchor(h, before)
            compose.runOnIdle {
                assertEquals(oldCount + 128, h.list.layoutInfo.totalItemsCount)
                assertEquals(128 * (batch + 2), h.snapshot.nodes.size)
                assertFalse(h.snapshot.hasNewer)
                assertEquals(0, h.newerRequests)
                assertEquals(0, h.latestRequests)
            }
            markers += first
        }
        (markers + markers.asReversed()).forEach { reach(h, it) }
        compose.runOnIdle {
            assertEquals(6, h.olderRequests)
            assertEquals(0, h.newerRequests)
            assertEquals(897, h.list.layoutInfo.totalItemsCount)
        }
    }

    @Test fun sixManualNewerBatchesKeepTheVisibleMessageEvenWhenTheFinalSentinelDisappears() {
        val h = harness(hasNewer = true)
        val markers = mutableListOf(1024L)
        repeat(6) { batch ->
            // No preceding genuine gesture: historical opening and a button click must not follow.
            scrollTo(h, 0)
            compose.onNodeWithText(label(R.string.chat_load_newer)).performClick()
            compose.waitForIdle()
            val before = anchor(h)
            val oldCount = compose.runOnIdle { h.list.layoutInfo.totalItemsCount }
            val first = 1152L + batch * 128L
            compose.runOnIdle {
                h.snapshot = h.snapshot.copy(nodes = h.snapshot.nodes + rows(first, first + 127),
                    hasNewer = batch != 5)
                h.loadingNewer = false
            }
            compose.waitForIdle()
            assertAnchor(h, before)
            compose.runOnIdle {
                assertEquals(oldCount + 128 - if (batch == 5) 1 else 0, h.list.layoutInfo.totalItemsCount)
                assertEquals(0, h.olderRequests)
                assertEquals(0, h.latestRequests)
            }
            markers += first + 127
        }
        (markers.asReversed() + markers).forEach { reach(h, it) }
        compose.runOnIdle {
            assertEquals(6, h.newerRequests)
            assertEquals(0, h.olderRequests)
            assertEquals(897, h.list.layoutInfo.totalItemsCount)
        }
    }

    @Test fun alternatingPrependAppendAndLiveRowsKeepAnInteriorAnchorAndAllLoadedRows() {
        val h = harness(hasNewer = true)
        compose.runOnIdle { h.jump = 1080L }
        compose.waitForIdle()
        scrollTo(h, compose.runOnIdle { h.list.firstVisibleItemIndex }, 17)
        val before = anchor(h)
        repeat(6) { batch ->
            val first = 1024L - (batch + 1) * 128L
            val next = 1152L + batch * 129L
            compose.runOnIdle {
                h.snapshot = h.snapshot.copy(nodes = rows(first, first + 127) + h.snapshot.nodes + rows(next, next + 127))
            }
            compose.waitForIdle()
            assertAnchor(h, before)
            // A durable live arrival must not evict the cumulatively loaded oldest page either.
            compose.runOnIdle { h.snapshot = h.snapshot.copy(nodes = h.snapshot.nodes + rows(next + 128, next + 128)) }
            compose.waitForIdle()
            assertAnchor(h, before)
            compose.runOnIdle {
                assertEquals(128 + (batch + 1) * 257 + 2, h.list.layoutInfo.totalItemsCount)
                assertEquals(first, h.snapshot.nodes.first().seq)
            }
        }
        listOf(256L, 1024L, 1080L, 1151L, 1925L).forEach { reach(h, it) }
        compose.runOnIdle {
            assertEquals(0, h.olderRequests)
            assertEquals(0, h.newerRequests)
            assertEquals(0, h.latestRequests)
        }
    }
}
