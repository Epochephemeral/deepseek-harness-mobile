package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.data.ArchivedTranscriptState
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The production raw-row component only: no SessionStore, files, network, or account state. */
class SubagentTranscriptViewportTest {
    @get:Rule val compose = createComposeRule()
    private fun rows(first: Long, count: Int, older: Boolean = false, newer: Boolean = false) = ConversationSnapshot(
        sessionId = "child-fixture", hasMore = older, hasNewer = newer,
        nodes = (first until first + count).map { seq ->
            UserMessageNode(seq, "u$seq", listOf(ChatBlock("text", "row $seq")), sourceKind = "user")
        },
    )
    private class Harness(initial: ConversationSnapshot) {
        var child by mutableStateOf(initial)
        var history by mutableStateOf(ArchivedTranscriptState(snapshot = initial, hasNewer = initial.hasNewer))
        lateinit var list: LazyListState
        lateinit var scope: CoroutineScope
        var older = 0
        var newer = 0
        var jumps = 0
        var latest: suspend () -> Boolean = { false }
        fun publish(value: ConversationSnapshot) {
            child = value
            history = history.copy(snapshot = value, hasNewer = value.hasNewer)
        }
    }
    private fun harness(initial: ConversationSnapshot): Harness {
        val harness = Harness(initial)
        compose.setContent {
            harness.list = rememberLazyListState()
            harness.scope = rememberCoroutineScope()
            DshTheme {
                SubagentTranscriptViewport(
                    child = harness.child, history = harness.history,
                    context = ChatNodeContext(harness.child.nodes, running = false, cwd = null,
                        onOpenSubagent = {}, onBranchFrom = {}, onFeedback = { _, _ -> }),
                    onLoadOlder = { harness.older++ }, onLoadNewer = { harness.newer++ },
                    onShowLatest = { harness.jumps++; harness.latest() },
                    onViewport = { _, _ -> }, onRetryFollow = {},
                    listState = harness.list, modifier = Modifier.testTag("child-viewport"),
                )
            }
        }
        compose.waitForIdle()
        return harness
    }
    private data class Position(val key: Any?, val scrollOffset: Int, val layoutOffset: Int?)
    private fun position(harness: Harness): Position = compose.runOnIdle {
        val exact = harness.list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == harness.list.firstVisibleItemIndex }
        Position(exact?.key, harness.list.firstVisibleItemScrollOffset, exact?.offset)
    }
    private fun scroll(harness: Harness, index: Int, offset: Int = 0) {
        compose.runOnIdle { harness.scope.launch { harness.list.scrollToItem(index, offset) } }
        compose.waitForIdle()
    }
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun thousandsOfNodesRemainVirtualizedInBoundedViewport() {
        val harness = harness(rows(0, 2_000))
        compose.onNodeWithText("row 0").assertIsDisplayed()
        compose.onNodeWithText("row 1999").assertDoesNotExist()
        val exposed = compose.onAllNodes(hasText("row ", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size
        assertTrue("only a small visible/prefetched subset should expose row semantics, got $exposed", exposed in 1..40)
        compose.runOnIdle {
            assertTrue(harness.list.layoutInfo.visibleItemsInfo.size in 1..20)
            assertEquals(2_000, harness.list.layoutInfo.totalItemsCount)
        }
        val bounds = compose.onNode(hasScrollAction()).getBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top <= 320.dp)
    }

    @Test fun overlappingDiskWindowShiftPreservesExactLongKeyAndPixelOffset() {
        val harness = harness(rows(100, 120, older = true, newer = true))
        // The older affordance occupies index 0, so row 140 is index 41.
        scroll(harness, index = 41, offset = 17)
        val before = position(harness)
        assertEquals(140L, before.key)
        assertEquals(17, before.scrollOffset)
        compose.runOnIdle { harness.publish(rows(40, 120, older = true, newer = true)) }
        compose.waitForIdle()
        assertEquals(before, position(harness))
        compose.onNodeWithText("row 140").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, harness.jumps) }
    }

    @Test fun manualPagingAndJumpRespectFailureThenAwaitPublishedLatestSnapshot() {
        val harness = harness(rows(0, 80, older = true, newer = true))
        compose.onNodeWithText(label(R.string.chat_load_older)).performClick()
        compose.runOnIdle { assertEquals(1, harness.older) }
        scroll(harness, compose.runOnIdle { harness.list.layoutInfo.totalItemsCount - 1 })
        compose.onNodeWithText(label(R.string.chat_load_newer)).performClick()
        compose.runOnIdle { assertEquals(1, harness.newer) }

        val beforeFailure = position(harness)
        compose.onNodeWithText(label(R.string.chat_jump_latest)).performClick()
        compose.waitForIdle()
        assertEquals(beforeFailure, position(harness))
        compose.runOnIdle { assertEquals(1, harness.jumps) }

        scroll(harness, index = 26, offset = 13) // older affordance + row 25
        val waitingPosition = position(harness)
        val returned = CompletableDeferred<Boolean>()
        compose.runOnIdle { harness.latest = { returned.await() } }
        compose.onNodeWithText(label(R.string.chat_jump_latest)).performClick()
        compose.runOnIdle { assertEquals(2, harness.jumps); returned.complete(true) }
        compose.waitForIdle()
        assertEquals("successful callback alone is not publication", waitingPosition, position(harness))

        // The view's rows can update before the parent's history collector. Keep this mismatch on
        // purpose: jumping must wait for the CURRENT history snapshot, not the old onClick capture.
        val latest = rows(0, 120, older = true, newer = false)
        compose.runOnIdle { harness.child = latest }
        compose.waitForIdle()
        assertEquals("new rows alone must not prematurely move the reader", waitingPosition, position(harness))
        compose.runOnIdle { harness.history = harness.history.copy(snapshot = latest, hasNewer = false) }
        compose.waitUntil(timeoutMillis = 5_000) {
            !harness.list.canScrollForward && harness.list.layoutInfo.visibleItemsInfo.lastOrNull()?.key == 119L
        }
        compose.waitForIdle()
        compose.onNodeWithText("row 119").assertIsDisplayed()
        compose.runOnIdle { assertFalse(harness.list.canScrollForward) }
    }
}
