package com.labteto.dshmobile.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.connection.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Isolated preferences: these tests never clear the user's real hosts or credentials. */
class ConfiguredHostStorageTest {
    private fun withHosts(check: suspend (HostsStore) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "configured-host-${UUID.randomUUID()}.preferences_pb")
        val job = SupervisorJob()
        val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
        val hosts = HostsStore(data, RelayCredentialStore(data), context)
        try { check(hosts) } finally { job.cancelAndJoin(); file.delete() }
    }

    @Test fun freshInstallSeedsHostAndModeAndKeepsSavedEdits() = withHosts { hosts ->
        hosts.ensureConfiguredHost("http://harness.example:3081")
        val seeded = hosts.hosts.first().single()
        assertEquals("http://harness.example:3081", seeded.baseUrl)
        assertEquals(ConnectMode.LAN, hosts.settingsOnce().connectMode)
        hosts.upsertHost(seeded.copy(port = 8443, useTls = true))
        hosts.setSetting { it.copy(connectMode = ConnectMode.RELAY, autoConnectLast = false) }
        hosts.ensureConfiguredHost("https://another.example")
        assertEquals("https://harness.example:8443", hosts.hosts.first().single().baseUrl)
        assertEquals(ConnectMode.RELAY, hosts.settingsOnce().connectMode)
        assertFalse(hosts.settingsOnce().autoConnectLast)
    }

    @Test fun explicitlyForgottenHostsStayForgotten() = withHosts { hosts ->
        hosts.ensureConfiguredHost("http://harness.example:3081")
        hosts.removeHost(hosts.hosts.first().single().id)
        hosts.ensureConfiguredHost("http://harness.example:3081")
        assertTrue(hosts.hosts.first().isEmpty())
    }
}
