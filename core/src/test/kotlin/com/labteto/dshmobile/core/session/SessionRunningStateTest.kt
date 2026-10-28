package com.labteto.dshmobile.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionRunningStateTest {
    @Test fun `fresh list repairs a missed completion notification`() {
        val state = SessionRunningState()
        state.status("s", true)
        state.baseline("s", false, state.baselineTicket())
        assertEquals(false, state["s"])
    }

    @Test fun `status received during list request wins in both directions`() {
        for (running in listOf(false, true)) {
            val state = SessionRunningState()
            val ticket = state.baselineTicket()
            state.status("s", running)
            state.baseline("s", !running, ticket)
            assertEquals(running, state["s"])
        }
    }

    @Test fun `race protection is per session not global`() {
        val state = SessionRunningState()
        state.status("completed", true)
        val ticket = state.baselineTicket()
        state.status("other", true)
        state.baseline("completed", false, ticket)
        assertEquals(false, state["completed"])
    }

    @Test fun `late history start cannot resurrect authoritative idle`() {
        val state = SessionRunningState()
        state.status("s", false)
        state.turn("s", true)
        assertEquals(false, state["s"])
    }

    @Test fun `turn completion does not stop actual session background work`() {
        val state = SessionRunningState()
        state.status("s", true)
        state.turn("s", false)
        assertEquals(true, state["s"])
        state.status("s", false)
        assertEquals(false, state["s"])
    }

    @Test fun `turn lifecycle is fallback only until host status arrives`() {
        val state = SessionRunningState()
        assertNull(state["s"])
        state.turn("s", true)
        assertEquals(true, state["s"])
        state.turn("s", false)
        assertEquals(false, state["s"])
        state.turn("s", true)
        state.baseline("s", false, state.baselineTicket())
        assertEquals(false, state["s"])
    }

    @Test fun `stale child activity never makes idle parent run`() {
        val state = SessionRunningState()
        state.status("parent", false)
        state.status("child", true)
        assertEquals(false, state["parent"])
        assertEquals(true, state["child"])
        state.baseline("child", false, state.baselineTicket())
        assertEquals(false, state["child"])
    }

    @Test fun `added summary cannot undo status and a subsequent baseline can`() {
        val state = SessionRunningState()
        state.status("s", false)
        state.added("s", true)
        assertEquals(false, state["s"])
        state.baseline("s", true, state.baselineTicket())
        assertEquals(true, state["s"])
    }

    @Test fun `new sessions get summary status rather than stale turn state`() {
        val state = SessionRunningState()
        state.turn("s", true)
        state.added("s", false)
        assertEquals(false, state["s"])
    }

    @Test fun `removed sessions and host changes discard both sources`() {
        val state = SessionRunningState()
        state.status("s", true)
        state.turn("s", true)
        state.remove("s")
        assertNull(state["s"])
        state.status("s", true)
        state.turn("s", true)
        state.clear()
        assertNull(state["s"])
    }
}
