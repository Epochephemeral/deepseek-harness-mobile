package com.labteto.dshmobile.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRecoveryPolicyTest {
    @Test fun `automatic signals never revive a deliberate or terminal disconnect`() {
        for (failed in listOf(false, true)) for (running in listOf(false, true)) {
            assertEquals(RecoveryAction.NONE, connectionRecoveryAction(ConnectionPhase.DISCONNECTED, failed, running))
        }
    }

    @Test fun `healthy connection is preserved and a running attempt only gets a wake signal`() {
        assertEquals(RecoveryAction.NONE, connectionRecoveryAction(ConnectionPhase.CONNECTED, false, true))
        assertEquals(RecoveryAction.WAKE, connectionRecoveryAction(ConnectionPhase.CONNECTED, true, true))
        assertEquals(RecoveryAction.WAKE, connectionRecoveryAction(ConnectionPhase.CONNECTING, false, true))
        assertEquals(RecoveryAction.WAKE, connectionRecoveryAction(ConnectionPhase.RECONNECTING, false, true))
    }

    @Test fun `only an eligible stopped loop gets restarted`() {
        for (phase in listOf(ConnectionPhase.CONNECTING, ConnectionPhase.RECONNECTING, ConnectionPhase.CONNECTED)) {
            assertEquals(RecoveryAction.RESTART, connectionRecoveryAction(phase, false, false))
        }
    }

    @Test fun `background setting immediately controls service for ready or recovering connection`() {
        for (phase in listOf(ConnectionPhase.CONNECTED, ConnectionPhase.RECONNECTING)) {
            assertTrue(shouldRunConnectionService(true, phase, true, false))
            assertFalse(shouldRunConnectionService(false, phase, true, false))
        }
        assertFalse(shouldRunConnectionService(true, ConnectionPhase.CONNECTING, false, false))
        assertFalse(shouldRunConnectionService(true, ConnectionPhase.DISCONNECTED, true, false))
    }

    @Test fun `service timeout cannot be bypassed by a reconnect or setting toggle`() {
        for (phase in ConnectionPhase.entries) {
            assertFalse(shouldRunConnectionService(true, phase, true, true))
            assertFalse(shouldRunConnectionService(false, phase, true, true))
        }
        assertTrue(shouldRunConnectionService(true, ConnectionPhase.CONNECTED, true, false))
    }
}
