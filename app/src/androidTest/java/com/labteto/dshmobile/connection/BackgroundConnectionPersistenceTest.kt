package com.labteto.dshmobile.connection

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Isolated journal/preferences only: no paired endpoint or user's settings are modified. */
class BackgroundConnectionPersistenceTest {
    private val host = HostConfig("host-a", "Harness", "harness.example", 3080)

    private fun withJournal(check: (File, BackgroundConnectionPersistence) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "background-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(directory, "intent.json")
        try { check(file, BackgroundConnectionPersistence(file)) } finally { directory.deleteRecursively() }
    }

    @Test fun desiredTargetAndStopsSurviveNewPersistenceInstances() = withJournal { file, journal ->
        journal.desire(host)
        assertEquals(host, BackgroundConnectionPersistence(file).snapshot().rememberedHost(true, listOf(host)))
        journal.stop(BackgroundConnectionStop.USER)
        val stopped = BackgroundConnectionPersistence(file).snapshot()
        assertEquals(BackgroundConnectionStop.USER, stopped.stopped)
        assertNull(stopped.rememberedHost(true, listOf(host)))
        journal.desire(host)
        journal.stop(BackgroundConnectionStop.AUTH)
        assertEquals(BackgroundConnectionStop.AUTH, BackgroundConnectionPersistence(file).snapshot().stopped)
    }

    @Test fun exhaustedBudgetSurvivesNewTargetAndProcessUntilForeground() = withJournal { file, journal ->
        journal.desire(host)
        journal.timeout()
        val cold = BackgroundConnectionPersistence(file)
        assertTrue(cold.snapshot().timedOut)
        cold.stop(BackgroundConnectionStop.USER)
        cold.desire(host.copy(id = "host-b"))
        assertTrue(BackgroundConnectionPersistence(file).snapshot().timedOut)
        cold.foreground()
        assertFalse(BackgroundConnectionPersistence(file).snapshot().timedOut)
    }

    @Test fun missingAndCorruptJournalsDoNotArmRecovery() = withJournal { file, journal ->
        assertNull(journal.snapshot().rememberedHost(true, listOf(host)))
        file.writeText("not a background connection record")
        assertNull(BackgroundConnectionPersistence(file).snapshot().rememberedHost(true, listOf(host)))
    }

    @Test fun recoverySnapshotRequiresOptInAndStillRememberedHost() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "background-hosts-${UUID.randomUUID()}.preferences_pb")
        val job = SupervisorJob()
        val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
        val hosts = HostsStore(data, RelayCredentialStore(data), context)
        val intent = BackgroundConnectionIntent(host.id, host.baseUrl)
        try {
            hosts.upsertHost(host)
            var snapshot = hosts.backgroundRecoverySnapshot()
            assertFalse(snapshot.enabled)
            assertNull(intent.rememberedHost(snapshot.enabled, snapshot.hosts))
            data.edit { it[booleanPreferencesKey("background")] = true }
            snapshot = hosts.backgroundRecoverySnapshot()
            assertEquals(host, intent.rememberedHost(snapshot.enabled, snapshot.hosts))
            // A stale pending connect's guard cannot reinsert or replace a target after disconnect.
            hosts.upsertHost(host.copy(port = 3081)) { false }
            assertEquals(host, hosts.backgroundRecoverySnapshot().hosts.single())
            hosts.removeHost(host.id)
            snapshot = hosts.backgroundRecoverySnapshot()
            assertNull(intent.rememberedHost(snapshot.enabled, snapshot.hosts))
        } finally {
            job.cancelAndJoin()
            file.delete()
        }
    }
}
