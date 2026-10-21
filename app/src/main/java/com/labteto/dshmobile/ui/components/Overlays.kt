package com.labteto.dshmobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Modal dialog: dim overlay (platform scrim ~ overlayMask), r24 bgLayer2 plate
 * with a hairline border. [content] receives a [ColumnScope].
 */
@Composable
fun DsDialog(
    title: String?,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DsTheme.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = DsShapes.dialog,
            color = colors.bgLayer2,
            border = BorderStroke(1.dp, colors.borderL1),
            shadowElevation = 8.dp,
        ) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                title?.let {
                    Text(it, style = DsType.large20, color = colors.labelPrimary)
                }
                content()
            }
        }
    }
}

/**
 * Toast state pair: the current message ([State]) and a [show] lambda. The
 * message auto-clears 3s after the last [show] call.
 */
@Composable
fun rememberDsToast(): Pair<State<String?>, (String) -> Unit> {
    val flow = remember { MutableStateFlow<String?>(null) }
    val state = flow.collectAsState()
    val message = state.value
    LaunchedEffect(message) {
        if (message != null) {
            delay(3000)
            flow.value = null
        }
    }
    return state to { flow.value = it }
}

/**
 * Top-center toast plate driven by [rememberDsToast].
 *
 * It drops in from under the status bar and lifts away again, rather than blinking on and off:
 * a plate that appears in one frame over the top bar reads as a glitch in the chrome. The last
 * message is held while the exit runs, so the plate does not empty out as it leaves. Below the
 * status bar on purpose: the activity draws edge to edge, and a toast that starts at y = 0 hides
 * its first line behind the clock.
 */
@Composable
fun DsToastHost(state: Pair<State<String?>, (String) -> Unit>, modifier: Modifier = Modifier) {
    val message = state.first.value
    var last by remember { mutableStateOf<String?>(null) }
    val shown = message ?: last
    SideEffect { if (message != null) last = message }
    Box(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn(tween(160)) + slideInVertically(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)) { -it },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it / 2 },
        ) {
            Surface(
                shape = DsShapes.toast,
                color = DsTheme.colors.toastBg,
                shadowElevation = 6.dp,
                modifier = Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp),
            ) {
                Text(
                    shown.orEmpty(),
                    style = DsType.small13,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** One entry in a [DsMenu]. */
data class MenuItem(
    val text: String,
    val icon: ImageVector? = null,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Dropdown menu anchored to [anchor]; r12 bgLayer3 surface with h40 r10 cells,
 * hover fill, and danger rows in error/dangerHover.
 */
@Composable
fun DsMenu(anchor: @Composable () -> Unit, items: List<MenuItem>) {
    val colors = DsTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.clickable { expanded = true }) { anchor() }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = DsShapes.menu,
            containerColor = colors.bgLayer3,
            tonalElevation = 0.dp,
            border = BorderStroke(1.dp, colors.borderL1),
        ) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            item.text,
                            style = DsType.std14,
                            color = if (item.danger) colors.error else colors.labelPrimary,
                        )
                    },
                    leadingIcon = item.icon?.let { icon ->
                        {
                            Icon(
                                icon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (item.danger) colors.error else colors.labelSecondary,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        item.onClick()
                    },
                    modifier = Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun DsMenuPreview() {
    DshTheme {
        DsMenu(
            anchor = { DsButton("Menu", onClick = {}) },
            items = listOf(
                MenuItem("Open", icon = Icons.Filled.Edit, onClick = {}),
                MenuItem("Delete", danger = true, onClick = {}),
            ),
        )
    }
}
