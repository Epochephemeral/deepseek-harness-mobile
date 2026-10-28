package com.labteto.dshmobile.notify

import com.labteto.dshmobile.connection.HostConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

class NotificationDestinationTest {
    private val host = HostConfig("host-1", "first", "harness.example", 443, useTls = true, basePath = "/proxy")
    private val destination = NotificationDestination(NotificationHost(host.id, host.baseUrl), "session/+?&中文")

    @Test fun identityRoundTripsWithoutChangingSessionOrProxyPath() {
        assertEquals(destination, NotificationDestination.parse(destination.uri()))
        assertEquals(host, destination.resolve(listOf(host)))
    }

    @Test fun forgottenReplacedAndUnknownEndpointsFailClosed() {
        assertNull(destination.resolve(emptyList()))
        assertNull(destination.resolve(listOf(host.copy(host = "other.example"))))
        assertNull(destination.resolve(listOf(host.copy(id = "new-pairing"))))
        assertNull(destination.resolve(listOf(host, host)))
        val arbitrary = destination.copy(host = NotificationHost(host.id, "https://attacker.example"))
        assertNull(arbitrary.resolve(listOf(host)))
    }

    @Test fun malformedAndLegacyLinksAreNotNotificationDestinations() {
        listOf(null, "dshmobile://host/current/session/a", "https://notification/session?host=h",
            destination.uri() + "&host=duplicate", destination.uri() + "#fragment",
            destination.uri().replace("notification/session", "notification:123/session"),
            destination.uri().replace("host=host-1", "host=%00"),
            destination.uri().replace("host=host-1", "host=%"),
        ).forEach { assertNull("Should reject $it", NotificationDestination.parse(it)) }
    }

    @Test fun identitiesSeparateSameSessionAcrossHosts() {
        assertNotEquals(destination.key, destination.copy(host = NotificationHost("host-2", host.baseUrl)).key)
        assertNotEquals(destination.key, destination.copy(host = NotificationHost(host.id, "https://replacement")).key)
    }

    @Test fun coldRouteWaitsForBaselineBeforeOpeningWantedSession() = runTest {
        val baseline = CompletableDeferred<Unit>()
        val opened = mutableListOf<String>()
        var connected: HostConfig? = null
        val router = NotificationRouter({ listOf(host) }, { connected = it }, {
            baseline.await()
            opened += it.sessionId
            true
        })
        val route = async { router.route(destination) }
        runCurrent()
        assertEquals(host, connected)
        assertTrue(opened.isEmpty())
        baseline.complete(Unit)
        assertTrue(route.await())
        assertEquals(listOf(destination.sessionId), opened)
    }

    @Test fun warmRouteUsesSameVerifiedHostAndGate() = runTest {
        var opened: NotificationDestination? = null
        val router = NotificationRouter({ listOf(host) }, { assertEquals(host, it) }, {
            opened = it
            true
        })
        assertTrue(router.route(destination))
        assertEquals(destination, opened)
    }

    @Test fun unknownHostNeverConnectsAndForgottenPendingHostNeverOpens() = runTest {
        var connected = false
        val unknown = NotificationRouter({ emptyList() }, { connected = true }, { error("Must not open") })
        assertFalse(unknown.route(destination))
        assertFalse(connected)
        var hosts = listOf(host)
        val forgotten = NotificationRouter({ hosts }, { hosts = emptyList() }, { error("Must not open") })
        assertFalse(forgotten.route(destination))
    }
}
