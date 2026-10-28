package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.*
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MinimalReaderUiTest {
    @get:Rule val compose = createComposeRule()
    private fun context(nodes: List<ChatNode>) = ChatNodeContext(nodes, false, null,
        onOpenSubagent = {}, onBranchFrom = {}, onFeedback = { _, _ -> })
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun toolRowsNeverRenderArgumentsReasoningOrOutputAndSlimResultIsDone() {
        val call = ToolCallNode(1, "c", "read", "{\"path\":\"SECRET_ARGUMENT\"}", 1, 1)
        val result = ToolResultNode(2, "c", null, false, 1, 1)
        val answer = AssistantMessageNode(3, "a", 1, 1,
            listOf(ChatBlock("reasoning", "SECRET_REASONING"), ChatBlock("text", "Public answer")))
        val nodes = listOf(call, result, answer)
        compose.setContent { DshTheme { Column { nodes.forEach { ChatNodeItem(it, context(nodes)) } } } }
        compose.onNodeWithText("read").assertExists()
        compose.onNodeWithText(label(R.string.reader_tool_done)).assertExists()
        compose.onNodeWithText("Public answer").assertExists()
        compose.onNodeWithText("SECRET_ARGUMENT", substring = true).assertDoesNotExist()
        compose.onNodeWithText("SECRET_REASONING", substring = true).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.chat_output_placeholder)).assertDoesNotExist()
    }

    @Test fun shortLatestWindowDoesNotFetchOlderUntilUserDrags() {
        var requests = 0
        val nodes = listOf(UserMessageNode(100, "u", listOf(ChatBlock("text", "Latest question")), "user"))
        compose.setContent { DshTheme {
            ChatTranscript(ConversationSnapshot("reader", nodes = nodes, hasMore = true),
                loading = false, loadingOlder = false, loadOlderFailed = false,
                context = context(nodes), listState = rememberLazyListState(), onLoadOlder = { requests++ })
        } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun trajectoryAlsoWaitsForGenuineEarlierEdgeDrag() {
        var requests = 0
        val nodes = listOf(UserMessageNode(100, "u", listOf(ChatBlock("text", "Latest question")), "user"))
        compose.setContent { DshTheme {
            TrajectoryTab(ConversationSnapshot("reader", nodes = nodes, hasMore = true),
                stats = null, usage = null, cwd = null, listState = rememberLazyListState(),
                onLoadOlder = { requests++ })
        } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun zenEmptyReaderPageKeepsExplicitRetryWithoutAutomaticFetch() {
        var requests = 0
        val nodes = listOf(OtherNode(128, "reader/skip", kotlinx.serialization.json.JsonNull))
        compose.setContent { DshTheme {
            ChatTranscript(ConversationSnapshot("reader", nodes = nodes, hasMore = true),
                loading = false, loadingOlder = false, loadOlderFailed = true,
                showStatusNotices = false, context = context(nodes),
                listState = rememberLazyListState(), onLoadOlder = { requests++ })
        } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithText(label(R.string.chat_load_older_retry)).assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun imageLoaderIsNotComposedBeforeExplicitPreviewTap() {
        var loads = 0
        compose.setContent { DshTheme {
            ToolImageReference("image-id", "screenshot.png") {
                LaunchedEffect(Unit) { loads++ }
                androidx.compose.material3.Text("Loaded image preview")
            }
        } }
        compose.waitForIdle()
        compose.onNodeWithText("screenshot.png").assertExists()
        compose.onNodeWithText("Loaded image preview").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, loads) }
        compose.onNodeWithText("screenshot.png").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Loaded image preview").assertExists()
        compose.runOnIdle { assertEquals(1, loads) }
    }

    @Test fun descriptionIsPreferredWithoutReadingArguments() {
        val call = ToolCallNode(1, "c", "read", "SECRET_ARGUMENT", 1, 1,
            description = "Read project overview")
        compose.setContent { DshTheme { ToolCallRow(call, context(listOf(call))) } }
        compose.onNodeWithText("Read project overview").assertExists()
        compose.onNodeWithText("SECRET_ARGUMENT", substring = true).assertDoesNotExist()
    }

    @Test fun errorShowsOnlyShortActionableSummary() {
        val result = ToolResultNode(2, "c", JsonPrimitive("Permission denied: grant access\n" + "RAW".repeat(1000)), true, 1, 1)
        compose.setContent { DshTheme { ChatNodeItem(result, context(listOf(result))) } }
        compose.onNodeWithText("Permission denied: grant access").assertExists()
        compose.onNodeWithText("RAW", substring = true).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.reader_tool_error)).assertExists()
    }
}
