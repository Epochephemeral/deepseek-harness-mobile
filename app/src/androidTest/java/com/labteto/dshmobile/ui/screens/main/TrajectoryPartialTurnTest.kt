package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.*
import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TrajectoryPartialTurnTest {
    @get:Rule val compose = createComposeRule()
    private fun event(type: String, seq: Long, data: String) = SessionEventEnvelope(type, seq, seq, WireJson.parseToJsonElement(data))
    private val call = event("tool/call", 10, """{"turn":7,"step":2,"callId":"in-page","name":"ledger-call","arguments":"{}"}""")
    private val result = event("tool/result", 11, """{"turn":7,"step":2,"message":{"id":"result","role":"tool","toolCallId":"outside-page","isError":false,"content":"COMPLETE-ORPHAN-OUTPUT"},"meta":{"actual":true}}""")
    private val marker = event("turn/start", 1, """{"turn":7}""")
    private val older = event("tool/call", 5, """{"turn":7,"step":1,"callId":"older","name":"earlier-tool","arguments":"{}"}""")
    private fun window(events: List<SessionEventEnvelope>) = EventFold("partial").fold(events).copy(hasMore = true, hasNewer = true)
    private class Clipboard : ClipboardManager {
        var value: String? = null
        override fun setText(annotatedString: AnnotatedString) { value = annotatedString.text }
        override fun getText(): AnnotatedString? = value?.let(::AnnotatedString)
    }
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun markerlessPageKeepsRealCallAndOrphanInLedgerAndRawAndRetainsAnchorWhenRealStartLoads() {
        var snapshot by mutableStateOf(window(listOf(call, result)))
        lateinit var state: LazyListState
        val clipboard = Clipboard()
        compose.setContent {
            state = rememberLazyListState()
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                DshTheme {
                    Box(Modifier.height(180.dp)) {
                        TrajectoryTab(snapshot, null, null, null, state, modifier = Modifier.testTag("partial-ledger"))
                    }
                }
            }
        }
        compose.onNodeWithText("ledger-call").assertExists()
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.trajectory_turn, 7)).assertExists()
        compose.onNodeWithText(label(R.string.chat_output_placeholder)).assertDoesNotExist()
        compose.onNodeWithText("COMPLETE-ORPHAN-OUTPUT").assertDoesNotExist()
        // Expanding the result puts this later lazy item outside composition. Scroll the list by
        // its stable key first; performScrollTo on an absent text node cannot materialize it.
        compose.onNodeWithTag("partial-ledger").performScrollToKey("raw-header")
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(state.layoutInfo.visibleItemsInfo.any { it.key == "raw-header" }) }
        compose.onNodeWithText(label(R.string.trajectory_tab_raw)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("partial-ledger").performScrollToKey("n-10")
        compose.waitForIdle()
        // Resolve the index from the actual call identity, including whichever sentinels exist.
        compose.runOnIdle {
            val callIndex = state.layoutInfo.visibleItemsInfo.first { it.key == "n-10" }.index
            runBlocking { state.scrollToItem(callIndex, 7) }
        }
        compose.waitForIdle()
        val before = compose.runOnIdle {
            val key = state.layoutInfo.visibleItemsInfo.first { it.index == state.firstVisibleItemIndex }.key
            assertEquals("n-10", key)
            key to state.firstVisibleItemScrollOffset
        }
        compose.runOnIdle { snapshot = window(listOf(marker, older, call, result)) }
        compose.waitForIdle()
        compose.runOnIdle {
            val key = state.layoutInfo.visibleItemsInfo.first { it.index == state.firstVisibleItemIndex }.key
            assertEquals(before.first, key)
            assertEquals(before.second, state.firstVisibleItemScrollOffset)
        }
        compose.onAllNodesWithText("ledger-call").assertCountEquals(1)
        compose.onAllNodesWithText(label(R.string.chat_output_placeholder)).assertCountEquals(0)
        compose.onNodeWithTag("partial-ledger").performScrollToKey("raw-11")
        compose.waitForIdle()
        compose.onNode(hasText("#11 · tool/result", substring = true)).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText(label(R.string.common_copy)).onLast().performScrollTo().performClick()
        compose.runOnIdle {
            val copied = WireJson.parseToJsonElement(clipboard.value!!).jsonObject
            assertEquals(result.data, copied["data"])
        }
    }
}
