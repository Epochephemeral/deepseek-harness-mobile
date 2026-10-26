package com.labteto.dshmobile.data

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.screens.main.PromptDelivery
import com.labteto.dshmobile.ui.screens.main.PromptDeliveryNotice
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Rule
import org.junit.Test

class PromptDeliveryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun receiptIsVisibleOnlyAfterConfirmationAndUncertainSendIsExplicit() {
        var state by mutableStateOf(PromptDelivery.Idle)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sent = context.getString(R.string.chat_delivery_accepted)
        val sending = context.getString(R.string.chat_delivery_sending)
        val uncertain = context.getString(R.string.chat_delivery_unconfirmed)
        compose.setContent { DshTheme { PromptDeliveryNotice(state) } }
        compose.onNodeWithText(sent).assertDoesNotExist()
        compose.runOnIdle { state = PromptDelivery.Sending }
        compose.onNodeWithText(sending).assertIsDisplayed()
        compose.onNodeWithText(sent).assertDoesNotExist()
        compose.runOnIdle { state = PromptDelivery.Accepted }
        compose.onNodeWithText(sent).assertIsDisplayed()
        compose.onNodeWithText(sending).assertDoesNotExist()
        compose.runOnIdle { state = PromptDelivery.Unconfirmed }
        compose.onNodeWithText(uncertain).assertIsDisplayed()
        compose.onNodeWithText(sent).assertDoesNotExist()
    }
}
