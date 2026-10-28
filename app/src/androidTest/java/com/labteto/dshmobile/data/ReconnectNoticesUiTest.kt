package com.labteto.dshmobile.data

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.*
import com.labteto.dshmobile.ui.screens.connect.ConnectFailure
import com.labteto.dshmobile.ui.screens.main.ChatConnectionNotices
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReconnectNoticesUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun briefReconnectStaysSilentEvenAfterItsTimerWouldHaveExpired() {
        compose.mainClock.autoAdvance = false
        var connection by mutableStateOf(ConnectionUiState(phase = ConnectionPhase.RECONNECTING, hasConnected = true))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reconnecting = context.getString(R.string.common_reconnecting)
        compose.setContent { DshTheme { Column { ChatConnectionNotices(connection, null, false) {} } } }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText(reconnecting).assertDoesNotExist()
        compose.onNodeWithText("remote stream mux closed").assertDoesNotExist()
        compose.runOnIdle { connection = connection.copy(phase = ConnectionPhase.CONNECTED) }
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithText(reconnecting).assertDoesNotExist()
    }

    @Test fun continuousReconnectShowsStatusAfterGraceAndClearsWhenConnected() {
        compose.mainClock.autoAdvance = false
        var connection by mutableStateOf(ConnectionUiState(phase = ConnectionPhase.RECONNECTING, hasConnected = true))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reconnecting = context.getString(R.string.common_reconnecting)
        compose.setContent { DshTheme { Column { ChatConnectionNotices(connection, null, false) {} } } }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText(reconnecting).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithText(reconnecting).assertIsDisplayed()
        compose.runOnIdle { connection = connection.copy(phase = ConnectionPhase.CONNECTED) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText(reconnecting).assertDoesNotExist()
    }

    @Test fun transientHandshakeFailureStaysSilent() {
        compose.mainClock.autoAdvance = false
        val host = HostConfig(id = "test", name = "Harness", host = "example.test", port = 3080)
        var connection by mutableStateOf(ConnectionUiState(phase = ConnectionPhase.RECONNECTING,
            host = host, failure = ConnectFailure.Refused, hasConnected = true))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val message = context.getString(R.string.connect_fail_refused, host.baseUrl)
        compose.setContent { DshTheme { Column { ChatConnectionNotices(connection, null, false) {} } } }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText(message).assertDoesNotExist()
        compose.runOnIdle { connection = connection.copy(phase = ConnectionPhase.CONNECTED, failure = null) }
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithText(message).assertDoesNotExist()
    }

    @Test fun changingRetryDiagnosticsDoesNotPostponeTheNoticeIndefinitely() {
        compose.mainClock.autoAdvance = false
        val host = HostConfig(id = "test", name = "Harness", host = "example.test", port = 3080)
        var connection by mutableStateOf(ConnectionUiState(phase = ConnectionPhase.RECONNECTING,
            host = host, failure = ConnectFailure.Timeout, hasConnected = true))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { DshTheme { Column { ChatConnectionNotices(connection, null, false) {} } } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { connection = connection.copy(failure = ConnectFailure.Refused) }
        compose.mainClock.advanceTimeBy(2_500)
        compose.onNodeWithText(context.getString(R.string.connect_fail_refused, host.baseUrl)).assertIsDisplayed()
    }

    @Test fun authenticationFailureRemainsVisibleUntilConnectionRecovers() {
        compose.mainClock.autoAdvance = false
        val host = HostConfig(id = "test", name = "Harness", host = "example.test", port = 3080)
        var connection by mutableStateOf(ConnectionUiState(phase = ConnectionPhase.RECONNECTING,
            host = host, failure = ConnectFailure.Unauthenticated, hasConnected = true))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val message = context.getString(R.string.connect_fail_unauthenticated, host.baseUrl)
        compose.setContent { DshTheme { Column { ChatConnectionNotices(connection, null, false) {} } } }
        compose.mainClock.advanceTimeBy(3_500)
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.common_retry)).assertIsDisplayed()
        compose.runOnIdle { connection = connection.copy(phase = ConnectionPhase.CONNECTED, failure = null) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText(message).assertDoesNotExist()
    }

    @Test fun endpointFailureRetainsItsMessageAndRetryAction() {
        var retries = 0
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val message = "permission denied by host"
        compose.setContent { DshTheme { Column {
            ChatConnectionNotices(ConnectionUiState(phase = ConnectionPhase.CONNECTED), message, false) { retries++ }
        } } }
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.common_retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
