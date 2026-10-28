package com.labteto.dshmobile.connection

import org.junit.Assert.*
import org.junit.Test

class ConfiguredHostTest {
    @Test fun `configured endpoint retains scheme port and proxy root`() {
        val host = configuredHost("https://harness.example:8443/team/")!!
        assertEquals("harness.example", host.host)
        assertEquals(8443, host.port)
        assertTrue(host.useTls)
        assertEquals("/team", host.basePath)
        assertEquals("https://harness.example:8443/team", host.baseUrl)
        assertFalse(host.isLoopback)
    }

    @Test fun `authentication and non HTTP input cannot become a build default`() {
        for (url in listOf("", "host:3081", "ftp://harness.example", "https://user:password@harness.example",
            "https://harness.example/?token=secret", "https://harness.example/#secret")) {
            assertNull(configuredHost(url))
        }
    }

    @Test fun `default identity follows the complete canonical endpoint`() {
        assertEquals(configuredHost("http://harness.example:3081")!!.id,
            configuredHost("http://harness.example:3081/")!!.id)
        assertNotEquals(configuredHost("http://harness.example:3081")!!.id,
            configuredHost("http://harness.example:3081/another-root")!!.id)
        assertTrue(configuredHost("http://127.0.0.1:3081")!!.isLoopback)
    }

    @Test fun `saved endpoint wins and initial values respect the chosen mode`() {
        val saved = HostConfig("saved", "My host", "saved.example", 3081)
        val loopback = configuredHost("http://127.0.0.1:3080")!!
        val fallback = "https://harness.example"
        assertEquals(saved, initialConnectionHost(ConnectMode.LAN, listOf(loopback, saved), fallback))
        assertEquals(loopback, initialConnectionHost(ConnectMode.LOOPBACK, listOf(loopback, saved), fallback))
        assertEquals("harness.example", initialConnectionHost(ConnectMode.LAN, emptyList(), fallback)!!.host)
        assertNull(initialConnectionHost(ConnectMode.RELAY, emptyList(), fallback))
        assertNull(initialConnectionHost(ConnectMode.LAN, emptyList(), ""))
    }
}
