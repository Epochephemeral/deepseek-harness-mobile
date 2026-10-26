package com.labteto.dshmobile.data

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.screens.main.Composer
import com.labteto.dshmobile.ui.screens.main.ZenAction
import com.labteto.dshmobile.ui.screens.main.ZenMenu
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ZenComposerUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)

    @Test fun zenHidesToolRowAndKeepsDraftWhenReturningToNormal() {
        var zen by mutableStateOf(true)
        var draft by mutableStateOf("draft survives")
        compose.setContent {
            DshTheme {
                Composer(
                    draft = draft, onDraftChange = { draft = it }, attachments = emptyList(),
                    onRemoveAttachment = {}, onRetryAttachment = {}, permissions = null,
                    pendingPermission = null, onPermissionPick = {}, contextBreakdown = null,
                    contextPressure = null, running = false, enabled = true, onOpenSheet = {},
                    onSend = {}, onStop = {}, zenMode = zen,
                    zenMenu = { ZenMenu(listOf(ZenAction(R.string.chat_zen_exit) { zen = false }), {}) },
                )
            }
        }
        compose.onNodeWithContentDescription(label(R.string.chat_composer_commands)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.harness_add_reference)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.chat_composer_send)).assertIsEnabled()
        compose.onNodeWithContentDescription(label(R.string.chat_zen_menu)).performClick()
        compose.onNodeWithText(label(R.string.chat_zen_exit)).performClick()
        compose.onNodeWithContentDescription(label(R.string.chat_composer_commands)).assertIsDisplayed()
        compose.onNodeWithText("draft survives").assertIsDisplayed()
    }

    @Test fun zenUsesOneSlotForStopOrSendAndAllowsMidTurnMessages() {
        var running by mutableStateOf(true)
        var draft by mutableStateOf("")
        var sent = ""
        var stopped = 0
        compose.setContent {
            DshTheme {
                Composer(
                    draft = draft, onDraftChange = { draft = it }, attachments = emptyList(),
                    onRemoveAttachment = {}, onRetryAttachment = {}, permissions = null,
                    pendingPermission = null, onPermissionPick = {}, contextBreakdown = null,
                    contextPressure = null, running = running, enabled = true, onOpenSheet = {},
                    onSend = { sent = it }, onStop = { stopped++; running = false }, zenMode = true,
                )
            }
        }
        compose.onNodeWithContentDescription(label(R.string.chat_composer_stop)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.chat_composer_send)).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("hello")
        compose.onNodeWithContentDescription(label(R.string.chat_composer_send)).performClick()
        compose.runOnIdle { assertEquals("hello", sent) }
        compose.onNodeWithContentDescription(label(R.string.chat_composer_stop)).performClick()
        compose.runOnIdle { assertEquals(1, stopped) }
        compose.onNodeWithContentDescription(label(R.string.chat_composer_send)).assertIsNotEnabled()
    }
}
