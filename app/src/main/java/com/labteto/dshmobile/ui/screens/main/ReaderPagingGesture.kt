package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** One directional page request per pointer gesture; semantics/layout/settlement cannot arm it. */
internal class ReaderPagingGesture {
    private var pointerActive = false
    private var requested = false

    fun beginGesture() { pointerActive = true; requested = false }

    /** Callers normalize the delta: positive means movement toward this gate's paging edge. */
    fun request(delta: Float, nearEdge: Boolean, allowed: Boolean): Boolean {
        if (!pointerActive || delta <= 0f || !nearEdge || !allowed || requested) return false
        requested = true
        return true
    }

    fun endGesture() { pointerActive = false; requested = false }
}

/**
 * Observe, never consume: Initial pass arms the gate before the scrollable handles movement.
 * DragInteraction/Compose state arrives asynchronously and may be absent at a clamped edge.
 * Semantics scrolling also says UserInput, but has no pointer lifecycle and must not page.
 */
internal fun Modifier.readerPagingGestures(vararg gates: ReaderPagingGesture): Modifier = pointerInput(*gates) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        gates.forEach { it.beginGesture() }
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
        } finally {
            gates.forEach { it.endGesture() }
        }
    }
}
