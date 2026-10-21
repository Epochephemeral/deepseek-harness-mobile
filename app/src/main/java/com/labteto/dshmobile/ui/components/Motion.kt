package com.labteto.dshmobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import com.labteto.dshmobile.ui.theme.DsAnimations

/**
 * Shows [content] for [value] while it is non-null, and animates it in and out.
 *
 * The point of this over a bare `AnimatedVisibility` is the exit: when the value goes to null the
 * last non-null one is held while the content leaves, so a dock or card shrinks away with what it
 * was showing instead of emptying out first and then collapsing.
 */
@Composable
fun <T : Any> AnimatedPresence(
    value: T?,
    modifier: Modifier = Modifier,
    enter: EnterTransition = DsAnimations.dockEnter,
    exit: ExitTransition = DsAnimations.dockExit,
    content: @Composable (T) -> Unit,
) {
    var last by remember { mutableStateOf(value) }
    SideEffect { if (value != null) last = value }
    val shown = value ?: last
    AnimatedVisibility(visible = value != null, modifier = modifier, enter = enter, exit = exit) {
        shown?.let { content(it) }
    }
}

/**
 * Shrinks the element slightly while [interactionSource] is pressed, the same press feedback
 * [DsButton] and [DsIconButton] give. For chips and cards that are clickable but not buttons.
 */
fun Modifier.pressScale(interactionSource: InteractionSource, pressed: Float = 0.96f): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressed else 1f,
        animationSpec = DsAnimations.pressScale,
        label = "pressScale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Haptic vocabulary for decisions. Compose's own `HapticFeedbackType` only offers a long press and a
 * text-handle tick, so a confirm and a reject used to feel the same as selecting text. The platform
 * has distinct confirm and reject effects from API 30; below that both fall back to a short tap.
 */
class DsHaptics(private val view: android.view.View) {
    fun confirm() = perform(if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    fun reject() = perform(if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.REJECT else android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    fun tick() = perform(android.view.HapticFeedbackConstants.CLOCK_TICK)
    /** Something arrived that needs the person: an approval, a question. */
    fun attention() = perform(android.view.HapticFeedbackConstants.LONG_PRESS)

    private fun perform(constant: Int) {
        view.performHapticFeedback(constant)
    }
}

@Composable
fun rememberDsHaptics(): DsHaptics {
    val view = androidx.compose.ui.platform.LocalView.current
    return remember(view) { DsHaptics(view) }
}
