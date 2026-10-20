package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * Jumping the transcript to a message picked out of the history sheet.
 *
 * The sheet only knows sequences and the transcript is the only thing that knows row indices, so a
 * pick travels as a sequence and is resolved here. These pin what the reader gets for it: the
 * message arrives on screen, and following the tail gets out of the way — except when the message
 * they picked *is* the tail, where leaving following on is what they asked for.
 */
class ChatTranscriptJumpTest {
    @get:Rule val compose = createComposeRule()

    private fun user(seq: Long, text: String): ChatNode =
        UserMessageNode(seq = seq, messageId = "u$seq", blocks = listOf(ChatBlock("text", text)), sourceKind = "user")

    private fun assistant(seq: Long, text: String, streaming: Boolean = false): ChatNode =
        AssistantMessageNode(
            seq = seq,
            messageId = if (streaming) null else "a$seq",
            turn = 1,
            step = 1,
            blocks = listOf(ChatBlock("text", text)),
            streaming = streaming,
        )

    /** 60 rows: enough that a pick is genuinely off screen in both directions. */
    private fun longHistory(): List<ChatNode> =
        (0L until 30L).flatMap { listOf(user(it * 2, "question $it"), assistant(it * 2 + 1, "answer $it")) }

    private class Harness {
        var nodes by mutableStateOf<List<ChatNode>>(emptyList())
        var jump by mutableStateOf<Long?>(null)
        var consumed = 0
        lateinit var listState: LazyListState
    }

    private fun harness(initial: List<ChatNode>): Harness {
        val h = Harness()
        h.nodes = initial
        compose.setContent {
            h.listState = rememberLazyListState()
            DshTheme {
                ChatTranscript(
                    conversation = ConversationSnapshot(sessionId = "s1", nodes = h.nodes, running = true),
                    loading = false,
                    loadingOlder = false,
                    loadOlderFailed = false,
                    context = ChatNodeContext(
                        nodes = h.nodes,
                        running = true,
                        cwd = null,
                        onOpenSubagent = {},
                        onBranchFrom = {},
                        onFeedback = { _, _ -> },
                    ),
                    listState = h.listState,
                    onLoadOlder = {},
                    jumpToSeq = h.jump,
                    onJumpConsumed = {
                        h.jump = null
                        h.consumed++
                    },
                )
            }
        }
        compose.waitForIdle()
        return h
    }

    private fun Harness.atTail(): Boolean = compose.runOnIdle {
        listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
    }

    private fun Harness.firstVisibleKey(): Any? =
        compose.runOnIdle { listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key }

    @Test
    fun aPickBringsItsMessageIntoView() {
        val h = harness(longHistory())

        compose.runOnIdle { h.jump = 10L }
        compose.waitForIdle()

        compose.onNodeWithText("question 5").assertIsDisplayed()
        // Consumed exactly once: a pick that stayed pending would fire again on the next unrelated
        // row change and drag the reader back.
        compose.runOnIdle { assertEquals(1, h.consumed) }
    }

    @Test
    fun aPickOutOfTheTailStopsFollowing() {
        val history = longHistory()
        val h = harness(history)

        compose.runOnIdle { h.jump = 10L }
        compose.waitForIdle()
        assertFalse(h.atTail())
        val anchor = h.firstVisibleKey()

        // A reply continues while the reader is looking at history. Following is off, so the key
        // anchoring is left alone and nothing they are looking at moves.
        compose.runOnIdle { h.nodes = history + assistant(60, "a reply nobody is looking at") }
        compose.waitForIdle()

        assertEquals(anchor, h.firstVisibleKey())
        assertFalse(h.atTail())
    }

    @Test
    fun aPickOfTheNewestMessageKeepsFollowing() {
        val history = longHistory()
        val h = harness(history)

        // Picking the tail is the reader asking to be at the tail, so the next row must still be
        // pinned into view.
        compose.runOnIdle { h.jump = 59L }
        compose.waitForIdle()
        compose.runOnIdle { h.nodes = history + assistant(60, "the next reply") }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(0, h.listState.firstVisibleItemIndex)
            assertEquals(0, h.listState.firstVisibleItemScrollOffset)
        }
        compose.onNodeWithText("the next reply").assertIsDisplayed()
    }

    @Test
    fun aPickNoRowCarriesIsDroppedRatherThanRetried() {
        val h = harness(longHistory())

        // A message a rewind withdrew, or one the sheet outlived. There is nothing to scroll to and
        // nothing left to page in, so the pick is swallowed instead of pending forever.
        compose.runOnIdle { h.jump = 9999L }
        compose.waitForIdle()

        compose.runOnIdle { assertEquals(1, h.consumed) }
    }
}
