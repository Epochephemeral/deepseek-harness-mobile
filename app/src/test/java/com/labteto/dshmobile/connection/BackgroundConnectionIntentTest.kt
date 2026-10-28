package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.WireJson
import org.junit.Assert.*
import org.junit.Test

class BackgroundConnectionIntentTest {
    private val host = HostConfig("host-a", "Harness", "harness.example", 3080)
    private val desired = BackgroundConnectionIntent(host.id, host.baseUrl)

    @Test fun `only an opted-in remembered exact endpoint can recover`() {
        assertEquals(host, desired.rememberedHost(true, listOf(host)))
        assertNull(desired.rememberedHost(false, listOf(host)))
        assertNull(desired.rememberedHost(true, emptyList()))
        assertNull(desired.rememberedHost(true, listOf(host.copy(id = "replacement"))))
        assertNull(desired.rememberedHost(true, listOf(host.copy(useTls = true))))
        assertNull(desired.rememberedHost(true, listOf(host.copy(basePath = "/changed"))))
    }

    @Test fun `intent is independent from a recent or configured host`() {
        assertNull(BackgroundConnectionIntent().rememberedHost(true, listOf(host)))
        for (reason in listOf(BackgroundConnectionStop.USER, BackgroundConnectionStop.AUTH)) {
            assertNull(desired.copy(stopped = reason).rememberedHost(true, listOf(host)))
        }
    }

    @Test fun `timeout is durable and cannot be bypassed by target or setting changes`() {
        val timedOut = desired.copy(timedOut = true)
        val restored = WireJson.decodeFromString(BackgroundConnectionIntent.serializer(),
            WireJson.encodeToString(BackgroundConnectionIntent.serializer(), timedOut))
        assertTrue(restored.timedOut)
        assertNull(restored.rememberedHost(true, listOf(host)))
        assertNull(restored.copy(hostId = "other").rememberedHost(true, listOf(host.copy(id = "other"))))
        assertEquals(host, restored.copy(timedOut = false).rememberedHost(true, listOf(host)))
    }

    @Test fun `suspended restore cannot overwrite disconnect new host or budget change`() {
        val ticket = BackgroundRestoreTicket(10, desired)
        assertTrue(ticket.isCurrent(10, desired))
        assertFalse(ticket.isCurrent(11, desired)) // Same endpoint was explicitly disconnected/reselected.
        assertFalse(ticket.isCurrent(10, desired.copy(stopped = BackgroundConnectionStop.USER)))
        assertFalse(ticket.isCurrent(10, desired.copy(hostId = "host-b")))
        assertFalse(ticket.isCurrent(10, desired.copy(timedOut = true)))
    }

    @Test fun `a connected socket never masks degraded background protection`() {
        val phase = ConnectionPhase.CONNECTED
        assertEquals(BackgroundDeliveryState.TIME_LIMIT_REACHED, backgroundDeliveryState(true, true, phase, true, false))
        assertEquals(BackgroundDeliveryState.START_RESTRICTED, backgroundDeliveryState(true, false, phase, false, true))
        assertEquals(BackgroundDeliveryState.BEST_EFFORT, backgroundDeliveryState(true, false, phase, false, false))
        assertEquals(BackgroundDeliveryState.SERVICE_ACTIVE, backgroundDeliveryState(true, false, phase, true, false))
        assertEquals(BackgroundDeliveryState.DISABLED, backgroundDeliveryState(false, true, phase, false, false))
    }

    @Test fun `malformed persisted hosts never qualify for recovery`() {
        for (invalid in listOf(host.copy(port = 0), host.copy(port = 65536), host.copy(host = ""), host.copy(id = ""))) {
            assertFalse(validRecoveryHost(invalid))
        }
    }
}
