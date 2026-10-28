package com.labteto.dshmobile.ui.screens.main

/** One older-page request per genuine drag; layout/fill/settlement never arms this gate. */
internal class ReaderPagingGesture {
    private var requested = false

    /** Positive delta means the finger is moving toward earlier content in either pane. */
    fun request(delta: Float, nearEarlierEdge: Boolean, allowed: Boolean): Boolean {
        if (delta <= 0f || !nearEarlierEdge || !allowed || requested) return false
        requested = true
        return true
    }

    fun endGesture() { requested = false }
}
