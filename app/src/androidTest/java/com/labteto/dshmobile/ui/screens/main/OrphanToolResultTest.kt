package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.*
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OrphanToolResultTest {
    @get:Rule val compose = createComposeRule()
    private class Clipboard : ClipboardManager {
        var text: String? = null
        override fun setText(annotatedString: AnnotatedString) { text = annotatedString.text }
        override fun getText(): AnnotatedString? = text?.let(::AnnotatedString)
    }
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun context(nodes: List<ChatNode>, newer: Boolean = false) = ChatNodeContext(nodes, true, null,
        onOpenSubagent = {}, onBranchFrom = {}, onFeedback = { _, _ -> }, hasNewer = newer)

    @Test fun orphanResultShowsStatusWithoutRenderingOrCopyingRawPayload() {
        val payload = "BEGIN-" + "x".repeat(131_072) + "-END"
        val result = ToolResultNode(900, "call-not-in-this-page", JsonPrimitive(payload), false, 1, 1)
        val clipboard = Clipboard()
        compose.setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                DshTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ChatNodeItem(result, context(listOf(result), newer = true))
                    }
                }
            }
        }
        compose.onNodeWithText(label(R.string.chat_input_placeholder)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.chat_output_placeholder)).assertDoesNotExist()
        compose.onNodeWithText(payload).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.reader_tool_result)).assertExists()
        compose.onNodeWithText(label(R.string.reader_tool_done)).assertExists()
        compose.runOnIdle { assertEquals(null, clipboard.text) }
    }

    @Test fun pairedResultIsStillRenderedExactlyOnceInsideItsExistingCall() {
        val call = ToolCallNode(1, "paired", "actual_tool", "{}", 1, 1)
        val result = ToolResultNode(2, call.callId, JsonPrimitive("paired-output"), false, 1, 1)
        val nodes = listOf(call, result)
        compose.setContent {
            DshTheme {
                Column {
                    ChatNodeItem(call, context(nodes))
                    ChatNodeItem(result, context(nodes))
                }
            }
        }
        compose.onNodeWithText(label(R.string.chat_output_placeholder)).assertDoesNotExist()
        compose.onNodeWithText(call.name).assertExists()
        compose.onAllNodesWithText(label(R.string.reader_tool_done)).assertCountEquals(1)
        compose.onNodeWithText("paired-output", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun missingResultInAHistoricalPartialWindowHasNeutralNotRunningOrCompletedState() {
        val call = ToolCallNode(1, "partial", "actual_tool", "{}", 1, 1)
        compose.setContent { DshTheme { ToolCallRow(call, context(listOf(call), newer = true), compact = true) } }
        compose.onNodeWithText(label(R.string.reader_tool_unknown)).assertExists()
        compose.onNodeWithText(label(R.string.reader_tool_running)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.reader_tool_done)).assertDoesNotExist()
    }
}
