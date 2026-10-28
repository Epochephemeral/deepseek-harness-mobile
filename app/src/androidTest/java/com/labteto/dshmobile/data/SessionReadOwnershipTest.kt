package com.labteto.dshmobile.data

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.connection.*
import com.labteto.dshmobile.core.wire.*
import com.labteto.dshmobile.core.wire.dto.SessionSearchItem
import com.labteto.dshmobile.core.wire.dto.SessionSearchValue
import com.labteto.dshmobile.di.AppModule
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real Store + controllable RPC transport, following the isolated history fixture pattern. */
class SessionReadOwnershipTest {
    private fun searchHits(sessionId: String, snippet: String): String = WireJson.encodeToString(
        SessionSearchValue.serializer(), SessionSearchValue(listOf(SessionSearchItem(sessionId, snippet)), hasMore = false))

    private class Pending(val request: JsonObject) {
        val response = CompletableDeferred<RpcHttpResponse>()
        fun ok(value: String) = respond("""{"ok":true,"value":$value}""")
        fun fail(code: String) = respond("""{"ok":false,"error":{"code":"$code","message":"fixture $code"}}""")
        private fun respond(result: String) {
            response.complete(RpcHttpResponse(200, """{"rpcId":${request["rpcId"]},"result":$result}"""))
        }
    }

    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "read-owner-${UUID.randomUUID()}").apply { mkdirs() }
        val dataJob = SupervisorJob()
        val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + dataJob),
            produceFile = { File(directory, "fixture.preferences_pb") })
        val http = AppModule.provideOkHttpClient()
        val credentials = RelayCredentialStore(data)
        val hosts = HostsStore(data, credentials, context)
        val manager = ConnectionManager(context,
            HarnessClientFactory(http, credentials, HarnessSessionStore(data, http)), hosts,
            networkMonitor = ConnectionNetworkMonitor(object : ContextWrapper(context) {
                override fun getSystemService(name: String): Any? =
                    if (name == Context.CONNECTIVITY_SERVICE) null else super.getSystemService(name)
            }),
            backgroundIntent = BackgroundConnectionPersistence(File(directory, "background.json")),
            backgroundRuntime = object : ConnectionBackgroundRuntime(context) {
                override fun updateWork(enabled: Boolean) = Unit
                override fun startService() = Unit
                override fun stopService() = Unit
            })
        val cache = HistoryCache(File(directory, "preview"))
        val host = HostConfig("read-fixture", "Fixture", "fixture.invalid", 3080)
        @Suppress("UNCHECKED_CAST")
        val states = ConnectionManager::class.java.getDeclaredField("_state").apply { isAccessible = true }
            .get(manager) as MutableStateFlow<ConnectionUiState>
        val pending = Channel<Pending>(Channel.UNLIMITED)
        val transport = object : RpcTransport {
            override suspend fun post(path: String, body: String): RpcHttpResponse {
                val call = Pending(WireJson.parseToJsonElement(body).jsonObject)
                pending.send(call)
                return call.response.await()
            }
            override suspend fun <T> download(path: String, consume: (String?, String?, InputStream) -> T): T = error("unused")
            override suspend fun upload(path: String, contentType: String, contentLength: Long, body: InputStream,
                onProgress: ((Long) -> Unit)?): RpcHttpResponse = error("unused")
        }
        val api = DshApiClient(transport)
        val store: SessionStore
        init {
            states.value = ConnectionUiState(host = host)
            store = SessionStore(context, manager, hosts, cache)
        }
        fun installApi(api: DshApiClient?) {
            ConnectionManager::class.java.getDeclaredField("api").apply { isAccessible = true }.set(manager, api)
        }
        private suspend fun stopScope(target: Any) {
            (target.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(target) as CoroutineScope)
                .coroutineContext[Job]!!.cancelAndJoin()
        }
        suspend fun ready() {
            // The manager stays disconnected, so no baseline/network work competes with reads.
            // Keep Store collectors alive: connectionError also combines source-scoped stream faults.
            stopScope(manager)
            select("A")
        }
        suspend fun select(id: String) {
            installApi(null)
            store.openSession(id) // Exercise the production selection epoch, including A -> B -> A.
            installApi(api)
        }
        suspend fun next(): Pending = withTimeout(5_000) { pending.receive() }
        val permissionCatalog: Any? get() =
            (SessionStore::class.java.getDeclaredField("permissionCatalog").apply { isAccessible = true }
                .get(store) as MutableStateFlow<*>).value
        // Models have no public read-only refresh entry point; avoid executing a model-selection write.
        suspend fun loadModels(): Unit = kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { continuation ->
            SessionStore::class.java.getDeclaredMethod("loadModels", String::class.java, kotlin.coroutines.Continuation::class.java)
                .apply { isAccessible = true }.invoke(store, "A", continuation)
        }
        suspend fun close() {
            stopScope(store)
            stopScope(manager)
            dataJob.cancelAndJoin()
            cache.close()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test fun commandsRejectBothResultsAfterReturningToTheSameSession() = runBlocking {
        val f = Fixture()
        try {
            f.ready()
            for (failure in listOf(false, true)) {
                val read = launch { f.store.refreshCommands() }
                val pending = f.next()
                f.select("B"); f.select("A")
                if (failure) pending.fail("forbidden") else pending.ok("""[{"name":"stale"}]""")
                read.join()
                assertTrue(f.store.commands.value.isEmpty())
                assertTrue(f.store.commandsAvailable.value)
                assertNull(f.store.connectionError.value)
            }
        } finally { f.close() }
    }

    @Test fun hostCatalogsRejectOldApiSuccessAndFailure() = runBlocking {
        val f = Fixture()
        try {
            f.ready()
            for (readCatalog in listOf<suspend () -> Unit>({ f.store.refreshPlugins() }, { f.store.refreshAgentPresets() })) {
                for (failure in listOf(false, true)) {
                    f.installApi(f.api)
                    val read = launch { readCatalog() }
                    val pending = f.next()
                    f.installApi(DshApiClient(f.transport))
                    if (failure) pending.fail("internal") else pending.ok("{}")
                    read.join()
                    assertNull(f.store.plugins.value)
                    assertNull(f.store.agentPresets.value)
                    assertNull(f.store.connectionError.value)
                }
            }
        } finally { f.close() }
    }

    @Test fun skillAndModelReadsRejectOldSelectionSuccessAndFailure() = runBlocking {
        val f = Fixture()
        try {
            f.ready()
            val reads = listOf<suspend () -> Unit>({ f.store.listSkills() }, { f.loadModels() })
            val values = listOf("""{"skills":[{"name":"stale","description":"old","modelInvocable":true}]}""",
                """{"default":{"provider":"old","model":"stale"}}""")
            for ((readCatalog, value) in reads.zip(values)) {
                for (failure in listOf(false, true)) {
                    val read = launch { readCatalog() }
                    val pending = f.next()
                    f.select("B"); f.select("A")
                    if (failure) pending.fail("internal") else pending.ok(value)
                    read.join()
                    assertTrue(f.store.skills.value.isEmpty())
                    assertNull(f.store.modelCatalog.value)
                    assertNull(f.store.connectionError.value)
                }
            }
        } finally { f.close() }
    }

    @Test fun retiredHostCatalogFailureCannotClearTheNewGenerationCatalog() = runBlocking {
        val f = Fixture()
        try {
            f.ready()
            val old = launch { f.store.refreshPlugins() }
            val oldReply = f.next()
            f.states.value = ConnectionUiState(host = f.host.copy(id = "other-host", host = "other.invalid"))
            withTimeout(5_000) { f.store.currentSessionId.first { it == null } }
            f.installApi(DshApiClient(f.transport))
            val current = launch { f.store.refreshPlugins() }
            f.next().ok("{}"); current.join()
            assertNotNull(f.store.plugins.value)
            oldReply.fail("forbidden"); old.join()
            assertNotNull(f.store.plugins.value)
            assertNull(f.store.connectionError.value)
        } finally { f.close() }
    }

    @Test fun commandNetworkFaultPreservesCatalogAndAvailability() = runBlocking {
        val f = Fixture()
        try {
            f.ready()
            val initial = launch { f.store.refreshCommands() }
            f.next().ok("""[{"name":"help"}]"""); initial.join()
            val refresh = launch { f.store.refreshCommands() }
            f.next().fail("transport"); refresh.join()
            assertEquals("help", f.store.commands.value.single().name)
            assertTrue(f.store.commandsAvailable.value)
            assertEquals("fixture transport", withTimeout(5_000) {
                f.store.connectionError.first { it == "fixture transport" }
            })
        } finally { f.close() }
    }

    @Test fun legacySubagentsRejectOldSelectionSuccessAndFailure() = runBlocking {
        val f = Fixture()
        try {
            f.ready()
            for (failure in listOf(false, true)) {
                val read = launch { f.store.refreshSubagents() }
                val pending = f.next()
                f.select("B"); f.select("A")
                if (failure) pending.fail("internal")
                else pending.ok("""{"entries":[{"kind":"diagnostic","id":"stale","reason":"old"}]}""")
                read.join()
                assertTrue(f.store.subagents.value.isEmpty())
                assertNull(f.store.connectionError.value)
            }
        } finally { f.close() }
    }

    @Test fun permissionCatalogOrdersResultsPreservesTransientAndRejectsOldApi() = runBlocking {
        val f = Fixture()
        try {
            f.ready()
            val old = launch { f.store.refreshPermissionCatalog() }
            val oldReply = f.next()
            val latest = launch { f.store.refreshPermissionCatalog() }
            f.next().ok("{}"); latest.join()
            val catalog = f.permissionCatalog
            assertNotNull(catalog)
            oldReply.fail("forbidden"); old.join()
            assertSame(catalog, f.permissionCatalog)
            val transient = launch { f.store.refreshPermissionCatalog() }
            f.next().fail("transport"); transient.join()
            assertSame(catalog, f.permissionCatalog)
            val unavailable = launch { f.store.refreshPermissionCatalog() }
            f.next().fail("capability-unavailable"); unavailable.join()
            assertNull(f.permissionCatalog)
            val stale = launch { f.store.refreshPermissionCatalog() }
            val staleReply = f.next()
            f.installApi(DshApiClient(f.transport))
            staleReply.ok("{}"); stale.join()
            assertNull(f.permissionCatalog)
        } finally { f.close() }
    }

    @Test fun searchOrdersQueriesAndDoesNotLatchTransientFailure() = runBlocking {
        val f = Fixture()
        try {
            f.ready()
            val old = launch { f.store.search("old") }
            val oldReply = f.next()
            val latest = launch { f.store.search("latest") }
            f.next().ok(searchHits("new", "latest")); latest.join()
            assertEquals("latest successful response must decode before stale failure arrives",
                listOf("new" to "latest"), f.store.searchResults.value)
            oldReply.fail("forbidden"); old.join()
            assertEquals(listOf("new" to "latest"), f.store.searchResults.value)
            assertTrue(f.store.contentSearchAvailable.value)
            val beforeBlank = launch { f.store.search("pending") }
            val blankReply = f.next()
            f.store.search(" ")
            blankReply.ok(searchHits("old", "stale")); beforeBlank.join()
            assertTrue(f.store.searchResults.value.isEmpty())
            val transient = launch { f.store.search("retryable") }
            f.next().fail("transport"); transient.join()
            assertTrue(f.store.contentSearchAvailable.value)
            assertEquals("fixture transport", withTimeout(5_000) {
                f.store.connectionError.first { it == "fixture transport" }
            })
            val unavailable = launch { f.store.search("missing") }
            f.next().fail("capability-unavailable"); unavailable.join()
            assertFalse(f.store.contentSearchAvailable.value)
        } finally { f.close() }
    }
}
