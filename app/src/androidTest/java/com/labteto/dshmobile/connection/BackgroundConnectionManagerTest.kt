package com.labteto.dshmobile.connection

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.core.wire.ConnectionLoop
import com.labteto.dshmobile.core.wire.GenerationFailure
import com.labteto.dshmobile.core.wire.LoopSinks
import com.labteto.dshmobile.core.wire.TransportFailure
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Fresh managers, real isolated storage, and a loopback fixture only. No real work/services/hosts. */
class BackgroundConnectionManagerTest {
    private class Fixture {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.CONNECTIVITY_SERVICE) null else super.getSystemService(name)
        }
        val directory = File(context.cacheDir, "cold-manager-${UUID.randomUUID()}").apply { mkdirs() }
        val journal = File(directory, "intent.json")
        val dataJob = SupervisorJob()
        val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + dataJob),
            produceFile = { File(directory, "hosts.preferences_pb") })
        var writeStarted: CompletableDeferred<Unit>? = null
        var releaseWrite: CompletableDeferred<Unit>? = null
        var writeCommitted: CompletableDeferred<Unit>? = null
        private val writeNumber = AtomicInteger()
        private val writeTrace = ConcurrentLinkedQueue<String>()
        val gatedData = object : DataStore<Preferences> {
            override val data = this@Fixture.data.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val number = writeNumber.incrementAndGet()
                writeTrace.add("$number:entered")
                try {
                    writeStarted?.complete(Unit)
                    releaseWrite?.await()
                    writeTrace.add("$number:forwarded")
                    return this@Fixture.data.updateData { before ->
                        writeTrace.add("$number:before=${before.asMap()}")
                        transform(before).also { after -> writeTrace.add("$number:transformed=${after.asMap()}") }
                    }.also { after ->
                        writeTrace.add("$number:committed=${after.asMap()}")
                        val raw = after[androidx.datastore.preferences.core.stringPreferencesKey("hosts_json")]
                        if (raw != null) {
                            try {
                                val decoded = com.labteto.dshmobile.core.wire.WireJson.decodeFromString(
                                    kotlinx.serialization.builtins.ListSerializer(HostConfig.serializer()), raw)
                                writeTrace.add("$number:decodedHostIds=${decoded.map { it.id }}")
                            } catch (failure: Throwable) {
                                writeTrace.add("$number:decodeFailed=${failure.javaClass.simpleName}:${failure.message}")
                            }
                        }
                        writeCommitted?.complete(Unit)
                    }
                } catch (failure: Throwable) {
                    writeTrace.add("$number:failed:${failure.javaClass.simpleName}")
                    throw failure
                }
            }
        }
        val hosts = HostsStore(gatedData, RelayCredentialStore(data), context)
        // Keep the ephemeral port reserved: connections cannot reach another local application.
        val endpoint = ServerSocket(0)
        val host = HostConfig("fixture", "Cold recovery fixture", "127.0.0.1", endpoint.localPort, isLoopback = true)
        val http = OkHttpClient()
        val factory = HarnessClientFactory(http, RelayCredentialStore(data), HarnessSessionStore(data, http))
        val managers = mutableListOf<ConnectionManager>()
        val runtime = object : ConnectionBackgroundRuntime(context) {
            override fun updateWork(enabled: Boolean) = Unit
            override fun startService() = Unit
            override fun stopService() = Unit
        }

        suspend fun background(enabled: Boolean) {
            // Avoid HostsStore.setSetting's intentional global theme mirror in this isolated fixture.
            data.edit { it[booleanPreferencesKey("background")] = enabled }
        }

        fun manager(): ConnectionManager = ConnectionManager(context, factory, hosts,
            ConnectionNetworkMonitor(context), BackgroundConnectionPersistence(journal), runtime).also(managers::add)

        fun loopRunning(manager: ConnectionManager): Boolean =
            (ConnectionManager::class.java.getDeclaredField("loop").apply { isAccessible = true }.get(manager) as? ConnectionLoop)?.isRunning == true

        fun persistenceTrace(): String = writeTrace.joinToString()

        suspend fun awaitStage(stage: String, manager: ConnectionManager, check: suspend () -> Unit) {
            try {
                withTimeout(5_000) { check() }
            } catch (timeout: TimeoutCancellationException) {
                val type = ConnectionManager::class.java
                val epoch = type.getDeclaredField("intentEpoch").apply { isAccessible = true }.get(manager)
                val scope = type.getDeclaredField("scope").apply { isAccessible = true }.get(manager) as CoroutineScope
                throw AssertionError("Timed out at $stage; state=${manager.state.value}; loopRunning=${loopRunning(manager)}; " +
                    "intentEpoch=$epoch; managerJob=${scope.coroutineContext[Job]}; gateEntered=${writeStarted?.isCompleted}; " +
                    "gateReleased=${releaseWrite?.isCompleted}; writeCommitted=${writeCommitted?.isCompleted}; writes=${writeTrace.joinToString()}", timeout)
            }
        }

        suspend fun kill(manager: ConnectionManager) {
            // Simulate process loss without invoking disconnect(), which would revoke desired intent.
            val type = ConnectionManager::class.java
            (type.getDeclaredField("loop").apply { isAccessible = true }.get(manager) as? ConnectionLoop)?.stop()
            val scope = type.getDeclaredField("scope").apply { isAccessible = true }.get(manager) as CoroutineScope
            scope.coroutineContext[Job]?.cancelAndJoin()
        }

        fun authFailure(manager: ConnectionManager) {
            val type = ConnectionManager::class.java
            val lock = type.getDeclaredField("lifecycleLock").apply { isAccessible = true }.get(manager)
            val sinks = type.getDeclaredField("sinks").apply { isAccessible = true }.get(manager) as LoopSinks
            synchronized(lock) {
                sinks.onGenerationFailed(1, GenerationFailure.MuxFailed(TransportFailure.UNAUTHENTICATED, "fixture 401"))
            }
        }

        suspend fun close() {
            managers.forEach { kill(it) }
            endpoint.close()
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
            dataJob.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    private fun fixture(check: suspend (Fixture) -> Unit) = runBlocking {
        val fixture = Fixture()
        try { check(fixture) } finally { fixture.close() }
    }

    @Test fun freshManagerRestoresOnlyDurableOptedInTarget() = fixture { f ->
        f.background(true)
        val original = f.manager()
        original.connect(f.host)
        f.kill(original)
        val cold = f.manager()
        assertTrue(cold.restoreBackgroundConnection(serviceAlreadyStarted = true))
        assertEquals(f.host, cold.state.value.host)
        assertNotEquals(ConnectionPhase.DISCONNECTED, cold.state.value.phase)
        assertEquals(BackgroundDeliveryState.SERVICE_ACTIVE, cold.state.value.backgroundDelivery)
    }

    @Test fun explicitDisconnectAndTerminalAuthRemainStoppedInFreshManagers() = fixture { f ->
        f.background(true)
        val original = f.manager()
        original.connect(f.host)
        original.disconnect()
        f.kill(original)
        val afterDisconnect = f.manager()
        assertFalse(afterDisconnect.restoreBackgroundConnection())
        assertEquals(ConnectionPhase.DISCONNECTED, afterDisconnect.state.value.phase)
        afterDisconnect.connect(f.host)
        f.authFailure(afterDisconnect)
        assertEquals(BackgroundConnectionStop.AUTH, BackgroundConnectionPersistence(f.journal).snapshot().stopped)
        afterDisconnect.reconnectIfNeeded()
        assertEquals(ConnectionPhase.DISCONNECTED, afterDisconnect.state.value.phase)
        f.kill(afterDisconnect)
        assertFalse(f.manager().restoreBackgroundConnection())
    }

    @Test fun timeLimitSurvivesColdManagerAndSettingsToggleUntilForegroundSignal() = fixture { f ->
        f.background(true)
        val original = f.manager()
        original.connect(f.host)
        original.onBackgroundServiceTimeout()
        assertEquals(BackgroundDeliveryState.TIME_LIMIT_REACHED, original.state.value.backgroundDelivery)
        f.kill(original)
        f.background(false)
        f.background(true)
        val cold = f.manager()
        assertFalse(cold.restoreBackgroundConnection())
        assertTrue(cold.backgroundServiceTimeLimitReached())
        assertEquals(BackgroundDeliveryState.TIME_LIMIT_REACHED, cold.state.value.backgroundDelivery)
        cold.recoverOnForeground() // This test injects the real UI lifecycle signal, not elapsed time.
        assertFalse(BackgroundConnectionPersistence(f.journal).snapshot().timedOut)
        assertTrue(cold.restoreBackgroundConnection())
    }

    @Test fun canceledRouteDuringRememberWriteCannotStrandSameHostConnectingState() = fixture { f ->
        f.background(true)
        f.writeStarted = CompletableDeferred()
        f.releaseWrite = CompletableDeferred()
        f.writeCommitted = CompletableDeferred()
        val manager = f.manager()
        val caller = CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch { manager.connect(f.host) }
        f.awaitStage("remember write entered", manager) { f.writeStarted!!.await() }
        caller.cancelAndJoin()
        assertEquals(f.host, manager.state.value.host)
        assertNotEquals(ConnectionPhase.DISCONNECTED, manager.state.value.phase)
        // A same-host navigation can reuse this state: its committed loop already exists.
        assertTrue(f.loopRunning(manager))
        f.releaseWrite!!.complete(Unit)
        f.awaitStage("remember write committed after caller cancellation", manager) { f.writeCommitted!!.await() }
        // Read through the same one-shot snapshot cold recovery consumes, after commit completion.
        // Do not conflate this cancellation/ownership test with concurrent Flow subscription timing.
        val saved = f.hosts.backgroundRecoverySnapshot()
        assertTrue(saved.enabled)
        assertEquals("Saved host mismatch; writes=${f.persistenceTrace()}", listOf(f.host), saved.hosts)
        assertEquals(f.host.id, BackgroundConnectionPersistence(f.journal).snapshot().hostId)
    }

    @Test fun pendingRememberWriteCannotResurrectExplicitDisconnect() = fixture { f ->
        f.background(true)
        f.writeStarted = CompletableDeferred()
        f.releaseWrite = CompletableDeferred()
        f.writeCommitted = CompletableDeferred()
        val manager = f.manager()
        val caller = CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch { manager.connect(f.host) }
        f.awaitStage("remember write entered", manager) { f.writeStarted!!.await() }
        manager.disconnect()
        f.releaseWrite!!.complete(Unit)
        f.awaitStage("stale remember write completed after disconnect", manager) { caller.join() }
        assertEquals(ConnectionPhase.DISCONNECTED, manager.state.value.phase)
        assertEquals(BackgroundConnectionStop.USER, BackgroundConnectionPersistence(f.journal).snapshot().stopped)
        assertTrue(f.hosts.backgroundRecoverySnapshot().hosts.isEmpty())
        assertFalse(f.manager().restoreBackgroundConnection())
    }

    @Test fun disabledBackgroundSettingBlocksColdRestoreDespiteRememberedIntent() = fixture { f ->
        val original = f.manager()
        original.connect(f.host)
        f.kill(original)
        val cold = f.manager()
        assertFalse(cold.restoreBackgroundConnection())
        assertEquals(ConnectionPhase.DISCONNECTED, cold.state.value.phase)
        assertEquals(BackgroundDeliveryState.DISABLED, cold.state.value.backgroundDelivery)
    }
}
