package com.labteto.dshmobile.core.wire

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteStreamMuxLifecycleTest {
    private class Socket(val sink: WsChannelSink) : WsChannel("http://stub/api/remote.mux", client, sink) {
        val sent = mutableListOf<String>()
        var starts = 0
        var closes = 0
        override fun start() { starts++; sink.onOpen() }
        override fun close() { closes++ }
        override fun send(text: String): Boolean { sent += text; return true }
        fun item(value: String) {
            sink.onMessage("""{"type":"item","streamId":"1","value":"$value"}""")
        }
    }

    @Test
    fun `cancel wakes a suspended receive and sends exactly one cancellation`() = runTest {
        lateinit var socket: Socket
        val mux = RemoteStreamMux { Socket(it).also { socket = it } }
        mux.start()
        val stream = mux.open("test")
        var cancelled = false
        val receiver = launch {
            try {
                stream.receive()
            } catch (e: CancellationException) {
                cancelled = true
            }
        }
        runCurrent()
        assertTrue(receiver.isActive)
        stream.cancel()
        stream.cancel()
        runCurrent()
        assertTrue(receiver.isCompleted)
        assertTrue(cancelled)
        assertEquals(1, socket.sent.count { it.contains("\"type\":\"cancel\"") })
        assertFalse(stream.send(JsonPrimitive("late")))
        mux.close()
    }

    @Test
    fun `cancel discards queued items including after host end`() = runTest {
        lateinit var socket: Socket
        val mux = RemoteStreamMux { Socket(it).also { socket = it } }
        mux.start()
        val stream = mux.open("test")
        repeat(100) { socket.item("buffered-$it") }
        socket.sink.onMessage("""{"type":"end","streamId":"1"}""")
        stream.cancel()
        socket.item("in flight after cancel")
        try {
            stream.receive()
            throw AssertionError("cancel must discard the buffer instead of yielding an item")
        } catch (_: CancellationException) {
            // Channel cancellation releases its queued item references, not merely its waiter.
        }
        assertEquals(0, socket.sent.count { it.contains("\"type\":\"cancel\"") })
        mux.close()
    }

    @Test
    fun `close while channel factory is running closes late channel without starting it`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val thrown = AtomicReference<Throwable?>()
        val created = AtomicReference<Socket>()
        val mux = RemoteStreamMux { sink ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "factory release timed out" }
            Socket(sink).also { created.set(it) }
        }
        val starter = Thread {
            try {
                mux.start()
            } catch (e: Throwable) {
                thrown.set(e)
            }
        }
        starter.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            mux.close()
        } finally {
            release.countDown()
            starter.join(5_000)
        }
        assertFalse("start thread must finish", starter.isAlive)
        thrown.get()?.let { throw AssertionError("start threw", it) }
        assertEquals(0, created.get().starts)
        assertEquals(1, created.get().closes)
        mux.close()
        assertEquals(1, created.get().closes)
    }

    @Test
    fun `closing before start does not create a channel`() {
        var calls = 0
        val mux = RemoteStreamMux { calls++; Socket(it) }
        mux.close()
        mux.start()
        assertEquals(0, calls)
    }

    @Test
    fun `startup failure closes the channel and also completes awaitOpen`() = runTest {
        val failure = IllegalStateException("failed to start")
        var closes = 0
        val mux = RemoteStreamMux { sink ->
            object : WsChannel("http://stub/api/remote.mux", client, sink) {
                override fun start(): Unit = throw failure
                override fun close() { closes++ }
            }
        }
        try {
            mux.start()
            throw AssertionError("start should fail")
        } catch (e: IllegalStateException) {
            assertTrue(e === failure)
        }
        try {
            mux.awaitOpen()
            throw AssertionError("awaitOpen should fail without suspension")
        } catch (e: IllegalStateException) {
            assertTrue(e === failure)
        }
        mux.close()
        assertEquals(1, closes)
    }

    @Test
    fun `slow readers retain queued items without an assistant imposed cutoff`() = runTest {
        lateinit var socket: Socket
        val mux = RemoteStreamMux { Socket(it).also { socket = it } }
        mux.start()
        val stream = mux.open("test")
        repeat(1_100) { socket.item("buffered-$it") }
        assertEquals(null, mux.failure)
        repeat(1_100) { assertEquals(JsonPrimitive("buffered-$it"), stream.receive()) }
        stream.cancel()
        mux.close()
    }

    @Test
    fun `logical streams are not rejected at the withdrawn stream count threshold`() {
        val mux = RemoteStreamMux { Socket(it) }
        mux.start()
        val streams = List(140) { mux.open("test-$it") }
        assertEquals(null, mux.failure)
        streams.forEach { it.cancel() }
        mux.close()
    }

    companion object {
        private val client = OkHttpClient()
    }
}
