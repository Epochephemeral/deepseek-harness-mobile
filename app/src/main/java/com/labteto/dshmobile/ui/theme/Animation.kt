package com.labteto.dshmobile.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * The app's motion vocabulary. Screens reach for these instead of inlining `tween(…)` so that
 * everything expanding, swapping, or settling shares one feel — and so a timing can be retuned in
 * one place rather than hunted across twenty call sites.
 *
 * The house rule: motion explains a change, it does not decorate one. Nothing loops forever except
 * a loading skeleton, and only while something is genuinely loading.
 */
object DsAnimations {
    /** Quick, slightly springy — for direct manipulation that should feel physical. */
    val fastSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessHigh,
    )

    /** The default for general transitions: settles without overshooting. */
    val normalSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /** Press feedback on buttons, pills and icon buttons. */
    val pressScale = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessHigh,
    )

    /**
     * Disclosure and dock expansion. Deliberately springy-but-unbouncy: content growing downward
     * with an overshoot reads as a glitch, and a linear tween reads as mechanical.
     */
    val expand: FiniteAnimationSpec<IntSize> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Chevron rotation on a disclosure row. */
    val chevron: AnimationSpec<Float> = tween(180, easing = FastOutSlowInEasing)

    /** Tab and content swaps. Short — a long swap on a large list costs a full relayout. */
    val tabSwap: FiniteAnimationSpec<Float> = tween(150, easing = FastOutSlowInEasing)

    /** Label tint following a tab or segment selection. */
    val tabSwapColor: FiniteAnimationSpec<Color> = tween(150, easing = FastOutSlowInEasing)

    /** Item placement inside a lazy list when rows are inserted, removed, or reordered. */
    val listItem: FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Generic opacity change. */
    val fade: FiniteAnimationSpec<Float> = tween(fadeDuration, easing = FastOutSlowInEasing)

    /** Panel slide-in, faster than the platform default so it keeps up with a drag. */
    val panelSlide: FiniteAnimationSpec<IntOffset> = tween(220, easing = FastOutSlowInEasing)

    /**
     * Material's emphasized curves. Things arriving decelerate into place; things leaving
     * accelerate away. Mixing the two on one transition is what makes a swap read as one motion
     * instead of two unrelated fades.
     */
    val emphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val emphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /**
     * Screen-to-screen changes (connect, chat, settings, pairing). The incoming screen fades up
     * from slightly smaller while the outgoing one fades out quickly, so the two are never both
     * fully legible at once.
     */
    val screenEnter: EnterTransition =
        fadeIn(tween(260, delayMillis = 60, easing = emphasizedDecelerate)) +
            scaleIn(tween(320, easing = emphasizedDecelerate), initialScale = 0.96f)
    val screenExit: ExitTransition =
        fadeOut(tween(120, easing = emphasizedAccelerate)) +
            scaleOut(tween(180, easing = emphasizedAccelerate), targetScale = 1.02f)

    /** Cards that take over the bottom of the chat: approvals, questions, plan reviews. */
    val cardEnter: EnterTransition =
        fadeIn(tween(200, easing = emphasizedDecelerate)) +
            slideInVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { it / 3 }
    val cardExit: ExitTransition =
        fadeOut(tween(140, easing = emphasizedAccelerate)) +
            shrinkVertically(tween(200, easing = emphasizedAccelerate))

    /** Docks (todo, goal, queue) joining or leaving the stack above the composer. */
    val dockEnter: EnterTransition = fadeIn(tween(180)) + expandVertically(expand)
    val dockExit: ExitTransition = fadeOut(tween(120)) + shrinkVertically(expand)

    /** A floating control popping in, like the jump-to-latest button. */
    val popEnter: EnterTransition =
        fadeIn(tween(160)) + scaleIn(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), initialScale = 0.6f)
    val popExit: ExitTransition = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.6f)


    /** Scale animation duration for press effects */
    const val scaleDuration = 100

    /** Fade animation duration for opacity changes */
    const val fadeDuration = 150

    /** Standard transition duration for layout changes */
    const val transitionDuration = 200

    /** Scale values for interactive elements */
    object Scale {
        const val normal = 1f
        const val pressed = 0.95f
    }
}
