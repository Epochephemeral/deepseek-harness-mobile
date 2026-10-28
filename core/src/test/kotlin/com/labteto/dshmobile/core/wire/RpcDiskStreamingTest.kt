package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.storage.DiskCacheStore
import com.labteto.dshmobile.core.wire.dto.SessionAddress
import com.labteto.dshmobile.core.wire.dto.SessionPageRequest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withContext
import java.util.zip.GZIPOutputStream
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RpcDiskStreamingTest {
    @get:Rule val temp = TemporaryFolder()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val emptyArgs = JsonObject(emptyMap())

    private suspend fun fixture(
        body: () -> ResponseBody,
        status: Int = 200,
        block: suspend (DshApiClient, OkHttpRpcTransport, DiskCacheStore) -> Unit,
    ) {
        val store = DiskCacheStore(temp.newFolder())
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val answer = body()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("fixture").header("Content-Type", answer.contentType().toString())
                .header("Retry-After", "17").body(answer).build()
        }.build()
        try {
            val transport = OkHttpRpcTransport("http://fixture.invalid", http, diskCacheStore = store)
            block(DshApiClient(transport), transport, store)
        } finally {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
        }
    }

    /** Produces a large answer incrementally, without allocating the response fixture as a String. */
    private fun generated(prefix: String, count: Int, item: (Int) -> String, suffix: String): ResponseBody =
        object : ResponseBody() {
            private var index = -1
            private val pending = Buffer()
            private val source = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (byteCount == 0L) return 0
                    while (pending.exhausted()) {
                        when {
                            index == -1 -> { pending.writeUtf8(prefix); index = 0 }
                            index < count -> { pending.writeUtf8(item(index)); index++ }
                            index == count -> { pending.writeUtf8(suffix); index++ }
                            else -> return -1
                        }
                    }
                    return pending.read(sink, byteCount)
                }
                override fun timeout() = Timeout.NONE
                override fun close() = Unit
            }.buffer()
            override fun contentType(): MediaType = jsonType
            override fun contentLength(): Long = -1
            override fun source(): BufferedSource = source
        }

    @Test fun `large page streams records without page list and releases protected file`() = runBlocking<Unit> {
        val count = 20_000
        val text = "x".repeat(2048)
        fixture({ generated("""{"type":"server-response","rpcId":"fixture","result":{"value":{"records":[""", count,
            { i -> (if (i > 0) "," else "") + """{"type":"event","event":{"type":"note","seq":$i,"time":0,"data":{"text":"$text"}}}""" },
            """],"hasMore":true},"ok":true}}""") }) { api, _, store ->
            var seen = 0
            val request = SessionPageRequest(SessionAddress.Session(sessionId = "s"), count - 1)
            val result = api.sessionPageEach(request) { record ->
                assertEquals(seen, record.event.seq)
                if (seen == 0) {
                    val usage = store.usage()
                    assertTrue("fixture must exceed withdrawn size cutoff", usage.totalBytes > 32L * 1024 * 1024)
                    assertEquals(usage.totalBytes, usage.protectedBytes)
                    assertEquals(usage.totalBytes, store.clear().protectedBytes)
                }
                seen++
            }
            assertTrue(result.toString(), result is RpcResult.Ok)
            val metadata = (result as RpcResult.Ok).value
            assertEquals(count, seen)
            assertEquals(count.toLong(), metadata.recordCount)
            assertTrue(metadata.hasMore)
            assertEquals(count - 1, metadata.throughSeq)
            assertEquals(0L, store.usage().totalBytes)
        }
    }

    @Test fun `typed value receives stream decoder not an envelope value tree`() = runBlocking<Unit> {
        fixture({ """{"rpcId":"fixture","future":{"ignored":[1,2]},"result":{"ok":true,"value":"hello"},"type":"server-response"}""".toResponseBody(jsonType) }) { api, _, store ->
            val probe = object : KSerializer<String> {
                override val descriptor = PrimitiveSerialDescriptor("Probe", PrimitiveKind.STRING)
                override fun deserialize(decoder: Decoder): String {
                    assertFalse(decoder.javaClass.name, decoder.javaClass.name.contains("Tree", ignoreCase = true))
                    assertTrue(store.usage().protectedBytes > 0)
                    return decoder.decodeString()
                }
                override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
            }
            assertEquals(RpcResult.Ok("hello"), api.call("fixture/typed", emptyArgs, probe))
            assertEquals(0L, store.usage().totalBytes)
        }
    }

    @Test fun `response reader and legacy body respect non UTF8 charset and lease lifetime`() = runBlocking<Unit> {
        val expected = "\"caf\u00e9\""
        fixture({ expected.toByteArray(Charsets.ISO_8859_1).toResponseBody("application/json; charset=iso-8859-1".toMediaType()) }) { _, transport, store ->
            val response = transport.post("/api/fixture", "{}")
            assertTrue(response.storedOnDisk)
            assertEquals(expected, response.openReader().use { it.readText() })
            assertEquals(expected, response.body)
            assertEquals(store.usage().totalBytes, store.clear().protectedBytes)
            response.close()
            response.close()
            assertEquals(0L, store.usage().totalBytes)
        }
    }

    @Test fun `BOM normalization and gzip are handled before typed decoding`() = runBlocking<Unit> {
        val text = """{"type":"server-response","rpcId":"fixture","result":{"ok":true,"value":"caf\u00e9"}}"""
        val zipped = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())); it.write(text.toByteArray()) }
        }.toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/fixture") { exchange ->
            exchange.requestBody.use { it.copyTo(ByteArrayOutputStream()) }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.sendResponseHeaders(200, zipped.size.toLong())
            exchange.responseBody.use { it.write(zipped) }
        }
        server.start()
        val store = DiskCacheStore(temp.newFolder())
        val http = OkHttpClient()
        try {
            val api = DshApiClient(OkHttpRpcTransport("http://127.0.0.1:${server.address.port}", http, diskCacheStore = store))
            assertEquals(RpcResult.Ok("caf\u00e9"), api.call("fixture", emptyArgs, String.serializer()))
            assertEquals(0L, store.usage().totalBytes)
        } finally {
            server.stop(0)
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
        }
    }

    @Test fun `malformed envelopes and typed values release files with explicit protocol failure`() = runBlocking<Unit> {
        val cases = listOf(
            """{"type":"other","rpcId":"fixture","result":{"ok":true,"value":"x"}}""",
            """{"type":"server-response","result":{"ok":true,"value":"x"}}""",
            """{"type":"server-response","rpcId":"fixture","result":{"ok":true,"value":{}}}""",
            """{"type":"server-response","rpcId":"fixture","result":{"ok":true,"value":"x"}} trailing""",
        )
        for (text in cases) fixture({ text.toResponseBody(jsonType) }) { api, _, store ->
            val result = api.call("fixture", emptyArgs, String.serializer())
            assertTrue(text, result is RpcResult.Err)
            assertEquals(TransportFailure.NOT_A_HARNESS, TransportFailures.of((result as RpcResult.Err).error))
            assertEquals(0L, store.usage().totalBytes)
        }
    }

    @Test fun `business error and absent void value retain compatibility`() = runBlocking<Unit> {
        fixture({ """{"rpcId":"fixture","result":{"error":{"code":"gateway/invocation-unavailable","message":"not installed"},"ok":false}}""".toResponseBody(jsonType) }) { api, _, store ->
            val result = api.call("fixture", emptyArgs, String.serializer()) as RpcResult.Err
            assertEquals("capability-unavailable", result.error.code)
            assertEquals(0L, store.usage().totalBytes)
        }
        fixture({ """{"rpcId":"fixture","result":{"ok":true}}""".toResponseBody(jsonType) }) { api, _, store ->
            assertEquals(RpcResult.Ok(emptyArgs), api.call("fixture", emptyArgs, JsonObject.serializer()))
            assertEquals(0L, store.usage().totalBytes)
        }
    }

    @Test fun `carrier refusal and retry header survive disk path without leaks`() = runBlocking<Unit> {
        fixture({ """{"error":"relay-locked","unknown":[1,2,3]}""".toResponseBody(jsonType) }, 403) { _, transport, store ->
            try {
                transport.post("/api/fixture", "{}")
                fail("expected HTTP error")
            } catch (error: RpcTransportException) {
                assertEquals(403, error.status)
                assertTrue(error.message!!, error.message!!.contains("relay-locked"))
            }
            assertEquals(0L, store.usage().totalBytes)
        }
        fixture({ "{}".toResponseBody(jsonType) }, 429) { _, transport, store ->
            try { transport.post("/api/fixture", "{}"); fail("expected HTTP error") }
            catch (error: RpcTransportException) { assertEquals(17L, error.retryAfterSeconds) }
            assertEquals(0L, store.usage().totalBytes)
        }
    }

    @Test fun `failed disk spool and callback failure clean partial files`() = runBlocking<Unit> {
        val broken = object : ResponseBody() {
            private var first = true
            private val source = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (!first) throw IOException("fixture read failure")
                    first = false
                    sink.writeUtf8("{\"partial\":")
                    return 11
                }
                override fun timeout() = Timeout.NONE
                override fun close() = Unit
            }.buffer()
            override fun contentType() = jsonType
            override fun contentLength() = -1L
            override fun source() = source
        }
        fixture({ broken }) { api, _, store ->
            val result = api.call("fixture", emptyArgs, String.serializer()) as RpcResult.Err
            assertTrue(result.error.message, result.error.message.contains("fixture read failure"))
            assertEquals(0L, store.usage().totalBytes)
        }
        fixture({ """{"rpcId":"fixture","result":{"ok":true,"value":{"records":[{"type":"event","event":{"type":"note","seq":0,"time":0}}]}}}""".toResponseBody(jsonType) }) { api, _, store ->
            val result = api.sessionPageEach(SessionPageRequest(SessionAddress.Session(sessionId = "s"), 0)) {
                throw IOException("staged page write failed")
            } as RpcResult.Err
            assertTrue(result.error.message.contains("staged page write failed"))
            assertEquals(0L, store.usage().totalBytes)
        }
    }

    @Test fun `cancellation during typed record decode releases answer`() = runBlocking<Unit> {
        fixture({ generated("""{"rpcId":"fixture","result":{"ok":true,"value":{"records":[""", 10_000,
            { i -> (if (i > 0) "," else "") + """{"type":"event","event":{"type":"note","seq":$i,"time":0}}""" }, "]}}}") }) { api, _, store ->
            val first = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val request = async(Dispatchers.Default) {
                api.sessionPageEach(SessionPageRequest(SessionAddress.Session(sessionId = "s"), 9999)) { record ->
                    if (record.event.seq == 0) {
                        first.complete(Unit)
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                }
            }
            try {
                withTimeout(5_000) { first.await() }
                request.cancel()
                release.countDown()
                request.join()
                assertTrue(request.isCancelled)
            } finally { release.countDown(); request.cancelAndJoin() }
            assertEquals(0L, store.usage().totalBytes)
        }
    }

    @Test fun `cancel after network resume before caller dispatch releases unclaimed lease`() = runBlocking<Unit> {
        for (upload in listOf(false, true)) {
            val enqueued = CountDownLatch(1)
            val queue = ConcurrentLinkedQueue<Runnable>()
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    queue.add(block)
                    enqueued.countDown()
                }
            }
            fixture({ "{}".toResponseBody(jsonType) }) { _, transport, store ->
                val request = async(dispatcher, start = CoroutineStart.UNDISPATCHED) {
                    val response = if (upload) transport.upload("/api/fixture", "application/octet-stream", 0,
                        byteArrayOf().inputStream(), null) else transport.post("/api/fixture", "{}")
                    response.use { fail("canceled receiver must not run") }
                }
                withContext(Dispatchers.IO) { assertTrue(enqueued.await(5, TimeUnit.SECONDS)) }
                assertTrue(store.usage().protectedBytes > 0)
                request.cancel()
                while (true) (queue.poll() ?: break).run()
                request.join()
                assertTrue(request.isCancelled)
                assertEquals(0L, store.usage().totalBytes)
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `in memory typed custom and upload replies stay on caller scheduler`() = runTest {
        val transport = object : RpcTransport {
            override suspend fun post(path: String, body: String): RpcHttpResponse {
                val value = if (path.endsWith("readBytes"))
                    """{"absolutePath":"/a","version":"v","bytes":1,"offset":0,"data":"eA==","eof":true}"""
                    else "\"hello\""
                return RpcHttpResponse(200, """{"rpcId":"fixture","result":{"ok":true,"value":$value}}""")
            }
            override suspend fun <T> download(path: String, consume: (String?, String?, InputStream) -> T): T = error("unused")
            override suspend fun upload(path: String, contentType: String, contentLength: Long, body: InputStream,
                onProgress: ((Long) -> Unit)?): RpcHttpResponse = RpcHttpResponse(200,
                """{"ok":true,"value":{"receiptId":"r","file":{"attachmentId":"a","name":"tiny","bytes":1}}}""")
        }
        val api = DshApiClient(transport)
        val finished = ArrayList<String>()
        val task = launch {
            assertEquals(RpcResult.Ok("hello"), api.call("fixture", emptyArgs, String.serializer()))
            finished += "typed"
            val content = api.workspaceFileReadBytes("s", "a")
            assertTrue(content.toString(), content is RpcResult.Ok)
            assertArrayEquals(byteArrayOf('x'.code.toByte()), (content as RpcResult.Ok).value.data)
            finished += "custom"
            assertTrue(api.uploadFileBinary("s", "tiny", 1, byteArrayOf(1).inputStream()) is RpcResult.Ok)
            finished += "upload"
        }
        runCurrent()
        assertTrue("in-memory decoding must not escape the caller scheduler", task.isCompleted)
        assertEquals(listOf("typed", "custom", "upload"), finished)
    }

    @Test fun `bare upload response uses typed streaming decoder and releases file`() = runBlocking<Unit> {
        fixture({ """{"ok":true,"value":{"receiptId":"r","file":{"attachmentId":"a","name":"tiny","bytes":1}}}""".toResponseBody(jsonType) }) { api, _, store ->
            val result = api.uploadFileBinary("s", "tiny", 1, byteArrayOf(1).inputStream())
            assertTrue(result.toString(), result is RpcResult.Ok)
            assertEquals("r", (result as RpcResult.Ok).value.receiptId)
            assertEquals(0L, store.usage().totalBytes)
        }
    }
}
