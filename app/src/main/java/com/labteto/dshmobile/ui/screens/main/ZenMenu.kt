package com.labteto.dshmobile.ui.screens.main

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.theme.DsTheme

internal data class ZenAction(@StringRes val label: Int, val enabled: Boolean = true, val run: () -> Unit)

/** Secondary controls are available on demand, without reserving transcript space. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ZenMenu(actions: List<ZenAction>, permissions: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    DsIconButton(
        icon = Icons.Outlined.MoreHoriz,
        contentDescription = stringResource(R.string.chat_zen_menu),
        onClick = { open = true },
        tint = DsTheme.colors.labelSecondary,
    )
    if (open) ModalBottomSheet(onDismissRequest = { open = false }, containerColor = DsTheme.colors.bgBase) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.chat_zen_menu), style = MaterialTheme.typography.titleMedium,
                color = DsTheme.colors.labelPrimary, modifier = Modifier.padding(8.dp))
            permissions()
            actions.forEach { action ->
                TextButton(
                    onClick = { open = false; action.run() },
                    enabled = action.enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(action.label)) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
