package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Jumping the transcript to a message picked out of the history sheet.
 *
 * The sheet only knows sequences and the transcript is the only thing that knows row indices, so a
 * pick travels as a sequence and is resolved here. These pin what the reader gets for it: the
 * message arrives at the top of the screen with its reply below, and following the tail gets out
 * of the way — except when the message they picked *is* the tail, where leaving following on is
 * what they asked for.
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
        /** False stands in for the Trajectory tab, which ChatScreen swaps the transcript out for. */
        var showTranscript by mutableStateOf(true)
        var consumed = 0
        lateinit var listState: LazyListState
    }

    private fun harness(initial: List<ChatNode>): Harness {
        val h = Harness()
        h.nodes = initial
        compose.setContent {
            // Hoisted above the swap, as ChatScreen hoists it, so it outlives the transcript.
            h.listState = rememberLazyListState()
            DshTheme {
                if (h.showTranscript) {
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
                        modifier = Modifier.testTag(TRANSCRIPT),
                    )
                } else {
                    Text("trajectory")
                }
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

    /** The picked prompt sits at the top of the transcript, and the reply it asked for is below it. */
    private fun assertLandedAtTop(prompt: String, reply: String) {
        val transcript = compose.onNodeWithTag(TRANSCRIPT).getBoundsInRoot()
        val asked = compose.onNodeWithText(prompt).getBoundsInRoot()
        val answered = compose.onNodeWithText(reply).getBoundsInRoot()
        // The list's own 8dp content padding, plus the bubble's padding around its text.
        assertTrue("$prompt is at ${asked.top}, transcript top ${transcript.top}", asked.top - transcript.top < 40.dp)
        assertTrue("$reply should follow $prompt", answered.top > asked.bottom)
        assertTrue("$reply should be on screen, ends at ${answered.bottom}", answered.bottom <= transcript.bottom)
    }

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
    fun aPickLandsAtTheTopWithItsReplyBelow() {
        val h = harness(longHistory())

        // Mid-list, so there is room on both sides and nothing clamps the landing.
        compose.runOnIdle { h.jump = 30L }
        compose.waitForIdle()

        assertLandedAtTop("question 15", "answer 15")
        compose.runOnIdle { assertEquals(1, h.consumed) }
    }

    @Test
    fun aPickMadeFromTheTrajectoryTabLandsWhenChatComesBack() {
        val h = harness(longHistory())
        compose.runOnIdle { h.showTranscript = false }
        compose.waitForIdle()

        // ChatScreen records the pick and swaps back to Chat in the same callback, so the jump is
        // already pending on the transcript's first composition, alongside the effects that land a
        // fresh composition on the tail.
        compose.runOnIdle {
            h.jump = 30L
            h.showTranscript = true
        }
        compose.waitForIdle()

        assertLandedAtTop("question 15", "answer 15")
        assertFalse(h.atTail())
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
    fun aPickWhoseTurnRunsToTheTailLeavesTheReaderFollowing() {
        val history = longHistory()
        val h = harness(history)

        // The newest prompt is not the tail itself (its reply is below it), but bringing it to the
        // top runs out of rows below and stops at the tail. The reader is at the tail, so the next
        // row must be pinned into view as it would be for anyone else sitting there.
        compose.runOnIdle { h.jump = 58L }
        compose.waitForIdle()
        assertTrue(h.atTail())

        compose.runOnIdle { h.nodes = history + user(60, "a new prompt") }
        compose.waitForIdle()

        assertTrue(h.atTail())
        compose.onNodeWithText("a new prompt").assertIsDisplayed()
    }

    @Test
    fun aPickNoRowCarriesIsDroppedRatherThanRetried() {
        val h = harness(longHistory())

        // A message a rewind withdrew under an open sheet. There is nothing to scroll to, so the
        // pick is swallowed instead of pending forever.
        compose.runOnIdle { h.jump = 9999L }
        compose.waitForIdle()

        compose.runOnIdle { assertEquals(1, h.consumed) }
        assertTrue(h.atTail())
    }

    private companion object {
        const val TRANSCRIPT = "transcript"
    }
}
