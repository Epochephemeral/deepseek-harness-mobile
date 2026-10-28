package com.labteto.dshmobile.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostStreamSupervisorTest {
    @Test fun `alive failure has a finite delayed budget even with a frame before every failure`() = runTest {
        val waits = mutableListOf<Long>()
        var attempts = 0
        var frames = 0
        val supervisor = HostStreamSupervisor(backgroundScope, Any(), waitBeforeRetry = { waits += it })
        supervisor.start(listOf(HostStreamSource("control") { publish ->
            attempts++
            publish { frames++ }
            error("logical stream failed")
        }), owns = { true }, alive = { true })
        runCurrent()
        assertEquals(4, attempts)
        assertEquals(4, frames)
        assertEquals(listOf(500L, 1_500L, 3_000L), waits)
        val terminal = supervisor.failures.value.getValue("control")
        assertEquals(4, terminal.attempt)
        assertFalse(terminal.retrying)
        assertTrue(terminal.describe("control").contains("stopped"))
        supervisor.stop()
    }

    @Test fun `normal EOF is a lost mirror and is retried only within budget`() = runTest {
        var opens = 0
        val supervisor = HostStreamSupervisor(backgroundScope, Any(), listOf(10), waitBeforeRetry = {})
        supervisor.start(listOf(HostStreamSource("workspace") { opens++ }), { true }, { true })
        runCurrent()
        assertEquals(2, opens)
        assertFalse(supervisor.failures.value.getValue("workspace").retrying)
        assertTrue(supervisor.failures.value.getValue("workspace").message.contains("stream ended"))
        supervisor.stop()
    }

    @Test fun `cancellation is not a failure and never retries`() = runTest {
        var opens = 0
        var waits = 0
        val supervisor = HostStreamSupervisor(backgroundScope, Any(), waitBeforeRetry = { waits++ })
        supervisor.start(listOf(HostStreamSource("control") {
            opens++
            throw CancellationException("retired")
        }), { true }, { true })
        runCurrent()
        assertEquals(1, opens)
        assertEquals(0, waits)
        assertTrue(supervisor.failures.value.isEmpty())
        supervisor.stop()
    }

    @Test fun `dead carrier leaves reconnection to the connection manager`() = runTest {
        var alive = true
        var opens = 0
        var waits = 0
        val supervisor = HostStreamSupervisor(backgroundScope, Any(), waitBeforeRetry = { waits++ })
        supervisor.start(listOf(HostStreamSource("control") {
            opens++
            alive = false
            error("mux died")
        }), { true }, { alive })
        runCurrent()
        assertEquals(1, opens)
        assertEquals(0, waits)
        assertTrue(supervisor.failures.value.isEmpty())
        supervisor.stop()
    }

    @Test fun `recovery clears only the recovered source fault`() = runTest {
        var controlOpens = 0
        val supervisor = HostStreamSupervisor(backgroundScope, Any(), listOf(10))
        supervisor.start(listOf(
            HostStreamSource("control") { publish ->
                if (++controlOpens == 1) error("control failed")
                publish {}
                awaitCancellation()
            },
            HostStreamSource("workspace") { error("workspace failed") },
        ), { true }, { true })
        runCurrent()
        assertEquals(setOf("control", "workspace"), supervisor.failures.value.keys)
        advanceTimeBy(10); runCurrent()
        assertEquals(setOf("workspace"), supervisor.failures.value.keys)
        assertFalse(supervisor.failures.value.getValue("workspace").retrying)
        supervisor.stop()
    }

    @Test fun `retired lifetime rejects noncooperative late frames and failures`() = runTest {
        var oldPublish: ((() -> Unit) -> Unit)? = null
        val releaseOldFailure = CompletableDeferred<Unit>()
        var frames = 0
        val supervisor = HostStreamSupervisor(backgroundScope, Any())
        supervisor.start(listOf(HostStreamSource("control") { publish ->
            oldPublish = publish
            withContext(NonCancellable) { releaseOldFailure.await() }
            error("old failure")
        }), { true }, { true })
        runCurrent()
        supervisor.start(listOf(HostStreamSource("control") { publish ->
            publish { frames++ }
            awaitCancellation()
        }), { true }, { true })
        runCurrent()
        try {
            oldPublish!! { frames++ }
            fail("A retired source must not publish")
        } catch (_: CancellationException) { }
        releaseOldFailure.complete(Unit); runCurrent()
        assertEquals(1, frames)
        assertTrue(supervisor.failures.value.isEmpty())
        supervisor.stop()
    }

    @Test fun `owner loss during backoff prevents reopening and late publication`() = runTest {
        var owns = true
        var opens = 0
        val supervisor = HostStreamSupervisor(backgroundScope, Any(), listOf(10))
        supervisor.start(listOf(HostStreamSource("control") { opens++; error("failed") }), { owns }, { true })
        runCurrent()
        owns = false
        advanceTimeBy(10); runCurrent()
        assertEquals(1, opens)
        supervisor.stop()
        assertTrue(supervisor.failures.value.isEmpty())
    }
}
