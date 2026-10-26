package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.theme.DsTheme

/** Renders session-owned delivery state; accepted receipts expire even while this UI is absent. */
@Composable
internal fun PromptDeliveryNotice(delivery: PromptDelivery) {
    if (delivery == PromptDelivery.Idle) return
    val colors = DsTheme.colors
    val color = when (delivery) {
        PromptDelivery.Accepted -> colors.labelCaption
        PromptDelivery.Rejected -> colors.error
        PromptDelivery.Unconfirmed -> colors.warnText
        else -> colors.labelCaption
    }
    val label = stringResource(when (delivery) {
        PromptDelivery.Sending -> R.string.chat_delivery_sending
        PromptDelivery.Accepted -> R.string.chat_delivery_accepted
        PromptDelivery.Rejected -> R.string.chat_delivery_rejected
        else -> R.string.chat_delivery_unconfirmed
    })
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (delivery == PromptDelivery.Sending) {
            CircularProgressIndicator(Modifier.size(14.dp), color = color, strokeWidth = 1.5.dp)
        } else {
            Icon(if (delivery == PromptDelivery.Accepted) Icons.Default.Check else Icons.Outlined.Info,
                contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        }
        Text(label, color = color, style = MaterialTheme.typography.labelMedium)
    }
}
