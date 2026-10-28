package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.*
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ToolGroupDisclosureTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(count: Int) = context.getString(R.string.chat_tool_group_count, count)
    private fun calls(first: Long, last: Long) = (first..last).map {
        ToolCallNode(it, "call-$it", "tool-$it", "{}", 1, 1)
    }
    private class Harness(nodes: List<ChatNode>, running: Boolean) {
        var snapshot by mutableStateOf(ConversationSnapshot("tools", nodes = nodes, running = running))
        var showTranscript by mutableStateOf(true)
        var restore by mutableStateOf<HistoryViewportRestoreRequest?>(null)
        val groups = ChatToolGroupState()
        lateinit var listState: LazyListState
    }
    private fun harness(nodes: List<ChatNode>, running: Boolean = false, retainGroups: Boolean = false): Harness {
        val h = Harness(nodes, running)
        compose.setContent { DshTheme {
            if (h.showTranscript) {
                h.listState = rememberLazyListState()
                ChatTranscript(h.snapshot, loading = false, loadingOlder = false, loadOlderFailed = false,
                    context = ChatNodeContext(h.snapshot.nodes, h.snapshot.running, null,
                        onOpenSubagent = {}, onBranchFrom = {}, onFeedback = { _, _ -> }),
                    listState = h.listState, onLoadOlder = {},
                    viewportRestore = h.restore, onViewportRestored = { h.restore = null },
                    toolGroupState = if (retainGroups) h.groups else null)
            }
        } }
        compose.waitForIdle()
        return h
    }

    @Test fun fortyConsecutiveCallsHaveOneCollapsedDisclosureNotFixedSizeBatches() {
        harness(calls(1, 40))
        compose.onAllNodesWithText(label(40)).assertCountEquals(1)
        compose.onNodeWithText(label(40)).assertIsDisplayed().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.ux_b_collapsed)))
        for (seq in 1..40) compose.onNodeWithText("tool-$seq").assertDoesNotExist()
    }

    @Test fun evenOneToolIsCollapsedAndCanBeExpandedAndClosed() {
        harness(calls(1, 1))
        compose.onNodeWithText("tool-1").assertDoesNotExist()
        compose.onNodeWithText(label(1)).performClick()
        compose.onNodeWithText("tool-1").assertIsDisplayed()
        compose.onNodeWithText(label(1)).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.ux_b_expanded)))
        compose.onNodeWithText(label(1)).performClick()
        compose.onNodeWithText("tool-1").assertDoesNotExist()
    }

    @Test fun publicAssistantAndUserMessagesStayOutsideTheirSeparateGroups() {
        harness(calls(1, 2) + AssistantMessageNode(3, "a", 1, 1, listOf(ChatBlock("text", "Public progress"))) +
            calls(4, 5) + UserMessageNode(6, "u", listOf(ChatBlock("text", "User steering")), "user") + calls(7, 8))
        compose.onAllNodesWithText(label(2)).assertCountEquals(3)
        compose.onNodeWithText("Public progress").assertIsDisplayed()
        compose.onNodeWithText("User steering").assertIsDisplayed()
        compose.onNodeWithText("tool-1").assertDoesNotExist()
        compose.onNodeWithText("tool-4").assertDoesNotExist()
        compose.onNodeWithText("tool-7").assertDoesNotExist()
    }

    @Test fun liveUpdatesShowActivityWithoutOpeningTheGroup() {
        val h = harness(calls(1, 2), running = true)
        compose.onNodeWithText(context.getString(R.string.reader_tool_running) + " · tool-2").assertIsDisplayed()
        compose.onNodeWithText("tool-1").assertDoesNotExist()
        compose.runOnIdle { h.snapshot = h.snapshot.copy(nodes = calls(1, 3)) }
        compose.onNodeWithText(label(3)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.reader_tool_running) + " · tool-3").assertIsDisplayed()
        compose.onNodeWithText("tool-1").assertDoesNotExist()
        compose.runOnIdle { h.snapshot = h.snapshot.copy(hasNewer = true) }
        compose.onNodeWithText(context.getString(R.string.reader_tool_running), substring = true).assertDoesNotExist()
    }

    @Test fun failureIsVisibleInCollapsedSummaryButDoesNotForceItOpen() {
        harness(calls(1, 2) + listOf(
            ToolResultNode(3, "call-1", JsonPrimitive("Permission denied"), true, 1, 1),
            ToolResultNode(4, "call-2", null, false, 1, 1),
        ))
        compose.onNodeWithText(context.getString(R.string.chat_tool_group_failed, 1)).assertIsDisplayed()
        compose.onNodeWithText("Permission denied").assertDoesNotExist()
        compose.onNodeWithText(label(2)).performClick()
        compose.onNodeWithText("Permission denied").assertIsDisplayed()
    }

    @Test fun switchingSessionsDoesNotInheritAnotherSessionsExpandedGroup() {
        val h = harness(calls(1, 2))
        compose.onNodeWithText(label(2)).performClick()
        compose.onNodeWithText("tool-1").assertIsDisplayed()
        compose.runOnIdle { h.snapshot = h.snapshot.copy(sessionId = "other-session") }
        compose.onNodeWithText(label(2)).assertIsDisplayed()
        compose.onNodeWithText("tool-1").assertDoesNotExist()
    }

    @Test fun hoistedIdentityAndExpansionSurviveTranscriptDisposalAfterPrepend() {
        val h = harness(calls(5, 7), retainGroups = true)
        compose.onNodeWithText(label(3)).performClick()
        compose.runOnIdle { h.snapshot = h.snapshot.copy(nodes = calls(3, 8)) }
        compose.onNodeWithText("tool-5").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(5L, h.listState.layoutInfo.visibleItemsInfo.first { it.key is Long }.key)
            h.showTranscript = false
        }
        compose.waitForIdle() // outgoing Chat has actually left composition, as after a tab swap
        compose.runOnIdle { h.showTranscript = true }
        compose.waitForIdle()
        compose.onNodeWithText(label(6)).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.ux_b_expanded)))
        compose.onNodeWithText("tool-5").assertIsDisplayed()
        compose.runOnIdle { assertEquals(5L, h.listState.layoutInfo.visibleItemsInfo.first { it.key is Long }.key) }
    }

    @Test fun hundredsOfExpandedCallsAreLazyAndTheReadingChildSurvivesBothDirectionsAndRestore() {
        val h = harness(calls(128, 639), retainGroups = true)
        compose.onNodeWithText(label(512)).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(513, h.listState.layoutInfo.totalItemsCount) }
        // The entire retained run is in the item provider, not eagerly composed in one Column.
        compose.onNodeWithText("tool-384").assertDoesNotExist()
        compose.runOnIdle { runBlocking { h.listState.scrollToItem(639 - 384, 17) } }
        compose.waitForIdle()
        val before = compose.runOnIdle { captureHistoryReadingPosition(h.listState, true, false) }
        assertEquals(toolDetailKey(384), before.key)
        assertEquals(17, before.scrollOffset)
        val bounds = compose.onNodeWithText("tool-384").getBoundsInRoot()
        compose.runOnIdle { h.snapshot = h.snapshot.copy(nodes = calls(0, 639)) }
        compose.waitForIdle()
        assertEquals(bounds, compose.onNodeWithText("tool-384").getBoundsInRoot())
        compose.runOnIdle { h.snapshot = h.snapshot.copy(nodes = calls(0, 767)) }
        compose.waitForIdle()
        assertEquals(bounds, compose.onNodeWithText("tool-384").getBoundsInRoot())
        compose.runOnIdle {
            assertEquals(769, h.listState.layoutInfo.totalItemsCount)
            val after = captureHistoryReadingPosition(h.listState, true, false)
            assertEquals(before.key, after.key)
            assertEquals(before.scrollOffset, after.scrollOffset)
            h.showTranscript = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            h.restore = HistoryViewportRestoreRequest(1, before, "tools", 0, 767)
            h.showTranscript = true
        }
        compose.waitForIdle()
        assertEquals(bounds, compose.onNodeWithText("tool-384").getBoundsInRoot())
        compose.runOnIdle {
            assertNull(h.restore)
            assertEquals(769, h.listState.layoutInfo.totalItemsCount)
            // Structural collapse while reading a child maps to its actual header, not a nearby seq.
            h.groups.expansion(128).value = false
        }
        compose.waitForIdle()
        compose.onNodeWithText(label(768)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, h.listState.layoutInfo.totalItemsCount)
            assertEquals(128L, h.listState.layoutInfo.visibleItemsInfo.single().key)
        }
    }

    @Test fun standaloneToolGroupStillRendersItsLocalDetails() {
        val nodes = calls(1, 2)
        compose.setContent { DshTheme {
            ToolGroupRow(nodes, ChatNodeContext(nodes, false, null,
                onOpenSubagent = {}, onBranchFrom = {}, onFeedback = { _, _ -> }))
        } }
        compose.onNodeWithText(label(2)).performClick()
        compose.onNodeWithText("tool-1").assertIsDisplayed()
        compose.onNodeWithText("tool-2").assertIsDisplayed()
        compose.onNodeWithText(label(2)).performClick()
        compose.onNodeWithText("tool-1").assertDoesNotExist()
    }

    @Test fun openStateAndIdentitySurviveOlderPrependAndLiveAppend() {
        val h = harness(calls(5, 7))
        compose.onNodeWithText(label(3)).performClick()
        compose.onNodeWithText("tool-5").assertIsDisplayed()
        compose.runOnIdle { h.snapshot = h.snapshot.copy(nodes = calls(3, 8)) }
        compose.onNodeWithText(label(6)).assertIsDisplayed()
        compose.onNodeWithText("tool-3").assertIsDisplayed()
        compose.onNodeWithText("tool-8").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(5L, h.listState.layoutInfo.visibleItemsInfo.first { it.key is Long }.key)
        }
        compose.onNodeWithText(label(6)).performClick()
        compose.onNodeWithText("tool-3").assertDoesNotExist()
    }
}
