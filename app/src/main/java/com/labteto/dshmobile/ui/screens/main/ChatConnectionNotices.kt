package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.ConnectionUiState
import com.labteto.dshmobile.ui.components.AnimatedPresence
import com.labteto.dshmobile.ui.components.ConnectionBanner
import com.labteto.dshmobile.ui.screens.connect.connectionFailureMessage

/** Recovery status comes from the connection owner; endpoint failures retain their retry action. */
@Composable
internal fun ChatConnectionNotices(
    connection: ConnectionUiState,
    operationError: String?,
    historyGap: Boolean,
    onRetry: () -> Unit,
) {
    when (connection.backgroundDelivery) {
        com.labteto.dshmobile.connection.BackgroundDeliveryState.TIME_LIMIT_REACHED ->
            ConnectionBanner(stringResource(R.string.background_time_limit_reached), warning = true)
        com.labteto.dshmobile.connection.BackgroundDeliveryState.START_RESTRICTED ->
            ConnectionBanner(stringResource(R.string.background_start_restricted), warning = true)
        else -> Unit
    }
    val reconnecting = connection.phase == ConnectionPhase.RECONNECTING
    val recovering = reconnecting || historyGap
    var noticeReady by remember(recovering, connection.host?.id, connection.host?.baseUrl) { mutableStateOf(false) }
    // A retry updates its diagnostic without restarting this continuous recovery window.
    LaunchedEffect(recovering, connection.host?.id, connection.host?.baseUrl) {
        if (recovering) {
            delay(RECOVERY_NOTICE_DELAY_MS)
            noticeReady = true
        }
    }
    val failure = connection.failure.takeIf { reconnecting && noticeReady }
    AnimatedPresence(operationError) {
        ConnectionBanner(it, actionLabel = stringResource(R.string.common_retry), onAction = onRetry)
    }
    if (failure != null) {
        ConnectionBanner(
            connectionFailureMessage(failure, connection.host?.baseUrl.orEmpty()),
            actionLabel = stringResource(R.string.common_retry),
            onAction = onRetry,
        )
    }
    if (recovering && noticeReady && failure == null && operationError == null) {
        ConnectionBanner(stringResource(R.string.common_reconnecting), warning = true)
    }
}

private const val RECOVERY_NOTICE_DELAY_MS = 3_000L
