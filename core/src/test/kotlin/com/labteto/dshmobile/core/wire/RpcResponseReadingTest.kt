package com.labteto.dshmobile.core.wire

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcResponseReadingTest {
    private fun client(body: ResponseBody): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Content-Type", "application/json").body(body).build()
    }.build()

    @Test
    fun `JSON response larger than withdrawn 32MiB threshold is returned intact`() = runBlocking<Unit> {
        val length = 33L * 1024 * 1024 + 2
        val body = object : ResponseBody() {
            private var position = 0L
            private val block = ByteArray(8_192) { 'x'.code.toByte() }
            private val source = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (byteCount == 0L) return 0
                    if (position >= length) return -1
                    if (position == 0L || position == length - 1) {
                        sink.writeByte('"'.code)
                        position++
                        return 1
                    }
                    val count = minOf(byteCount, block.size.toLong(), length - position - 1).toInt()
                    sink.write(block, 0, count)
                    position += count
                    return count.toLong()
                }
                override fun timeout(): Timeout = Timeout.NONE
                override fun close() = Unit
            }.buffer()
            override fun contentType(): MediaType = "application/json; charset=utf-8".toMediaType()
            override fun contentLength(): Long = length
            override fun source(): BufferedSource = source
        }
        val http = client(body)
        try {
            val response = withTimeout(10_000) { OkHttpRpcTransport("http://fixture.invalid", http).post("/api/test", "{}") }
            assertEquals(length, response.body.length.toLong())
            assertEquals('"', response.body.first())
            assertEquals('x', response.body[response.body.lastIndex - 1])
            assertEquals('"', response.body.last())
        } finally {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
        }
    }

    @Test
    fun `body read failure completes RPC caller rather than escaping callback`() = runBlocking<Unit> {
        val body = object : ResponseBody() {
            private val source = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("fixture read failure")
                override fun timeout(): Timeout = Timeout.NONE
                override fun close() = Unit
            }.buffer()
            override fun contentType(): MediaType = "application/json".toMediaType()
            override fun contentLength(): Long = -1
            override fun source(): BufferedSource = source
        }
        val http = client(body)
        try {
            try {
                withTimeout(5_000) { OkHttpRpcTransport("http://fixture.invalid", http).post("/api/test", "{}") }
                throw AssertionError("body failure must complete exceptionally")
            } catch (failure: RpcTransportException) {
                assertTrue(failure.message!!.contains("fixture read failure"))
            }
        } finally {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
        }
    }
}
