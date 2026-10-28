package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.ui.components.HOLD_MENU_TAG
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Holding things in the transcript (issue #60): a sent message could not be copied at all, so a
 * prompt stopped for a typo had to be retyped. These pin the hold on a bubble and on a tool call,
 * and that a scroll passing over a bubble is still only a scroll.
 */
class HoldGestureTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun string(id: Int) = context.getString(id)

    private class FakeClipboard : ClipboardManager {
        var text: String? = null
        override fun setText(annotatedString: AnnotatedString) { text = annotatedString.text }
        override fun getText(): AnnotatedString? = text?.let(::AnnotatedString)
    }

    private val prompt = "Rename the parser module to lexer and update every import"

    private fun user(seq: Long, text: String): ChatNode =
        UserMessageNode(seq = seq, messageId = "u$seq", blocks = listOf(ChatBlock("text", text)), sourceKind = "user")

    private fun assistant(seq: Long, text: String): ChatNode =
        AssistantMessageNode(seq = seq, messageId = "a$seq", turn = 1, step = 1, blocks = listOf(ChatBlock("text", text)))

    private val clipboard = FakeClipboard()
    private val edited = mutableListOf<String>()
    private val rewound = mutableListOf<Long>()

    private fun transcript(nodes: List<ChatNode>, rewind: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                DshTheme {
                    ChatTranscript(
                        conversation = ConversationSnapshot(sessionId = "s1", nodes = nodes),
                        loading = false,
                        loadingOlder = false,
                        loadOlderFailed = false,
                        context = ChatNodeContext(
                            nodes = nodes,
                            running = false,
                            cwd = "/home/me/project",
                            onOpenSubagent = {},
                            onBranchFrom = {},
                            onFeedback = { _, _ -> },
                            onEditPrompt = { edited += it },
                            onRewindTo = if (rewind) ({ seq, _ -> rewound += seq }) else null,
                        ),
                        listState = rememberLazyListState(),
                        onLoadOlder = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun holdOn(text: String) {
        compose.onNodeWithText(text).performTouchInput { longClick() }
        compose.waitForIdle()
    }

    @Test
    fun holdingASentMessageCopiesItWhole() {
        transcript(listOf(user(1, prompt), assistant(2, "Done.")))

        holdOn(prompt)
        compose.onNode(hasTestTag(HOLD_MENU_TAG)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.common_copy)).performClick()

        compose.runOnIdle { assertEquals(prompt, clipboard.text) }
        compose.onNode(hasTestTag(HOLD_MENU_TAG)).assertDoesNotExist()
    }

    @Test
    fun editingASentMessageHandsItsTextToTheComposer() {
        transcript(listOf(user(1, prompt)))

        holdOn(prompt)
        compose.onNodeWithText(string(R.string.hold_edit_as_new)).performClick()

        compose.runOnIdle { assertEquals(listOf(prompt), edited) }
    }

    /** Rewind exists only with the plugin; the screen says so by passing no callback. */
    @Test
    fun rewindIsNotOfferedWithoutThePlugin() {
        transcript(listOf(user(1, prompt), assistant(2, "Done."), user(3, "and the tests")))
        holdOn(prompt)
        compose.onNode(hasTestTag(HOLD_MENU_TAG)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.hold_rewind_here)).assertDoesNotExist()
    }

    @Test
    fun rewindTargetsTheMessageThatWasHeld() {
        transcript(listOf(user(1, prompt), assistant(2, "Done."), user(3, "and the tests")), rewind = true)
        holdOn(prompt)
        compose.onNodeWithText(string(R.string.hold_rewind_here)).performClick()
        compose.runOnIdle { assertEquals(listOf(1L), rewound) }
    }

    /** A screen reader reaches the same menu through the bubble's long-press action. */
    @Test
    fun theHoldIsAnAccessibilityAction() {
        transcript(listOf(user(1, prompt)))

        compose.onNodeWithText(prompt).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNode(hasTestTag(HOLD_MENU_TAG)).assertIsDisplayed()
    }

    /** A drag that starts on a bubble is a scroll; it must not open the menu on its way past. */
    @Test
    fun aScrollStartingOnABubbleOpensNothing() {
        val nodes = (0L until 20L).flatMap { listOf(user(it * 2, "question $it"), assistant(it * 2 + 1, "answer $it")) }
        transcript(nodes)

        compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.onNode(hasTestTag(HOLD_MENU_TAG)).assertDoesNotExist()
    }

    /** The row shows the description; the copy is the command that actually ran, and its output. */
    @Test
    fun holdingAToolCallCopiesTheCommandItRan() {
        val call = ToolCallNode(
            seq = 2,
            callId = "c1",
            name = "bash",
            arguments = """{"command":"ls -la src","description":"List the sources"}""",
            turn = 1,
            step = 1,
        )
        val result = ToolResultNode(
            seq = 3,
            callId = "c1",
            content = Json.parseToJsonElement("""[{"type":"text","text":"main.kt"}]"""),
            isError = false,
            turn = 1,
            step = 1,
        )
        transcript(listOf(user(1, prompt), call, result))
        // Minimal reader rows have no hidden raw-output/argument hold actions.
        compose.onNodeWithText("bash").assertExists()
        compose.onNodeWithText("List the sources").assertDoesNotExist()
        compose.onNodeWithText("main.kt").assertDoesNotExist()
        holdOn("bash")
        compose.onNode(hasTestTag(HOLD_MENU_TAG)).assertDoesNotExist()
        compose.runOnIdle { assertNull(clipboard.text) }
    }

    @Test
    fun aTapOnABubbleStillDoesNothing() {
        transcript(listOf(user(1, prompt)))

        compose.onNodeWithText(prompt).performClick()
        compose.onNode(hasTestTag(HOLD_MENU_TAG)).assertDoesNotExist()
        compose.runOnIdle { assertNull(clipboard.text) }
    }
}
