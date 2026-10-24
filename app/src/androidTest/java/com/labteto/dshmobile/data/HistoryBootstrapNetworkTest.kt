package com.labteto.dshmobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.connection.HarnessSessionStore
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.connection.RelayCredentialStore
import com.labteto.dshmobile.di.AppModule
import com.labteto.dshmobile.core.wire.*
import com.labteto.dshmobile.core.wire.dto.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Opt-in read-only benchmark against an idle session on a reachable harness. */
@RunWith(AndroidJUnit4::class)
class HistoryBootstrapNetworkTest {
    private data class Reading(val snapshot: SessionFollowFrame.Snapshot, val bytes: Long, val millis: Long, val gzip: Boolean)

    @Test fun compressedBootstrapPreservesHistory() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val baseUrl = args.getString("historyBaseUrl")
        val sessionId = args.getString("historySessionId")
        assumeTrue("provide an endpoint and an idle session for the network benchmark", baseUrl != null && sessionId != null)
        // Provision outside the APK in the debug app's private storage; never put a credential
        // in an instrumentation argument, a source file, or test output.
        val tokenFile = instrumentation.targetContext.getFileStreamPath("history-benchmark.token")
        assumeTrue("provision a private benchmark token first", tokenFile.isFile)
        val token = tokenFile.readText().trim()
        check(tokenFile.delete())
        val pairingHttp = OkHttpClient()
        try {
            val auth = HarnessSession.exchange(baseUrl!!, token, pairingHttp)
            check(auth is SessionExchange.Granted) { "benchmark authentication failed" }
            val address = SessionAddress.Session(sessionId = sessionId!!)
            val baseline = read(baseUrl, auth.cookie, address, optimized = false)
            val optimized = read(baseUrl, auth.cookie, address, optimized = true)
            assertEquals("use an idle session so both reads share a cut", baseline.snapshot.cursor, optimized.snapshot.cursor)
            assertTrue("history records differ", baseline.snapshot.records == optimized.snapshot.records)
            assertEquals(baseline.snapshot.hasMore, optimized.snapshot.hasMore)
            assertTrue("the history page must use HTTP compression", optimized.gzip)
            assertTrue("the compressed bootstrap should reduce network bytes for this history", optimized.bytes < baseline.bytes)
            println("HISTORY_BOOTSTRAP baseline_bytes=${baseline.bytes} optimized_bytes=${optimized.bytes} " +
                "baseline_ms=${baseline.millis} optimized_ms=${optimized.millis} records=${optimized.snapshot.records.size}")

            // Optional local debug-app setup uses the same stores and pairing path as the UI.
            // It never touches the installed release app or changes the remote harness.
            if (args.getString("rememberHistoryHost") == "true") {
                val context = instrumentation.targetContext
                val store = AppModule.provideDataStore(context)
                val hosts = HostsStore(store, RelayCredentialStore(store), context)
                val url = baseUrl.toHttpUrl()
                val host = hosts.rememberHost("DSH", url.host, url.port, isLoopback = false,
                    useTls = url.isHttps, basePath = url.encodedPath.trimEnd('/'))
                check(HarnessSessionStore(store, pairingHttp).pair(host.id, baseUrl, token) is SessionExchange.Granted)
                hosts.setLastSessionId(host.baseUrl, sessionId)
            }
        } finally {
            pairingHttp.dispatcher.executorService.shutdown()
            pairingHttp.connectionPool.evictAll()
        }
    }

    private suspend fun read(baseUrl: String, cookie: String, address: SessionAddress, optimized: Boolean): Reading {
        val bytes = AtomicLong()
        val gzip = AtomicBoolean()
        val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun responseBodyEnd(call: Call, byteCount: Long) { bytes.addAndGet(byteCount) }
            })
            .addNetworkInterceptor { chain ->
                chain.proceed(chain.request()).also { if (it.header("Content-Encoding") == "gzip") gzip.set(true) }
            }.build()
        val api = DshApiClient(OkHttpRpcTransport(baseUrl, http, cookie = cookie))
        val mux = RemoteStreamMux { sink ->
            WsChannel(baseUrl + REMOTE_STREAM_MUX_PATH, http, object : WsChannelSink {
                override fun onOpen() = sink.onOpen()
                override fun onClosed(cause: Throwable?) = sink.onClosed(cause)
                override fun onMessage(text: String) {
                    bytes.addAndGet(text.toByteArray(Charsets.UTF_8).size.toLong())
                    sink.onMessage(text)
                }
            }, cookie = cookie)
        }
        val start = System.nanoTime()
        try {
            return withTimeout(60_000) {
                mux.start()
                mux.awaitOpen()
                val request = SessionFollowRequest(address, maxMessages = if (optimized) INITIAL_FOLLOW_MESSAGES else 60,
                    assistantStream = true)
                val stream = mux.open("session/follow", buildJsonObject {
                    put("request", WireJson.encodeToJsonElement(SessionFollowRequest.serializer(), request))
                })
                try {
                    val opening = WireJson.decodeFromJsonElement(SessionFollowFrame.serializer(), stream.receive()!!)
                        as SessionFollowFrame.Snapshot
                    val snapshot = if (optimized) hydrateSessionSnapshot(address, opening, 60, api::sessionPage) else opening
                    Reading(snapshot, bytes.get(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), gzip.get())
                } finally { stream.cancel() }
            }
        } finally {
            mux.close()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
