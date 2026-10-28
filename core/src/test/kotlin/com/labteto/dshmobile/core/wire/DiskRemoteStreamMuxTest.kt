package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.storage.DiskCacheStore
import com.labteto.dshmobile.core.storage.DiskRecordQueue
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiskRemoteStreamMuxTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Socket(val sink: WsChannelSink) : WsChannel("http://stub/api/remote.mux", client, sink) {
        val sent = ArrayList<String>()
        var closes = 0
        override fun start() { sink.onOpen() }
        override fun close() { closes++ }
        override fun send(text: String): Boolean { sent += text; return true }
        fun item(stream: Int, value: Int) = sink.onMessage("""{"type":"item","streamId":"$stream","value":$value}""")
        fun end(stream: Int) = sink.onMessage("""{"type":"end","streamId":"$stream"}""")
        fun error(stream: Int) = sink.onMessage("""{"type":"error","streamId":"$stream","error":{"code":"test","message":"business failure"}}""")
    }

    @Test fun `disk backlog delivers all items before business error with no cutoff or reconnect`() = runBlocking {
        val store = DiskCacheStore(temporary.newFolder())
        lateinit var socket: Socket
        val mux = RemoteStreamMux(store) { Socket(it).also { socket = it } }
        mux.start()
        val stream = mux.open("test")
        repeat(3_000) { socket.item(1, it) }
        socket.error(1)
        socket.item(1, 99_999) // late frame cannot cross a terminal
        val bytes = store.usage().totalBytes
        assertTrue(bytes > 0)
        assertEquals(bytes, store.clear().protectedBytes)
        repeat(3_000) { assertEquals(JsonPrimitive(it), stream.receive()) }
        try { stream.receive(); fail("business error must remain ordered after items") }
        catch (error: RemoteStreamException) { assertFalse(error.carrier); assertEquals("business failure", error.error.message) }
        assertEquals(0L, store.usage().totalBytes)
        assertEquals(null, mux.failure)
        assertEquals(0, socket.closes)
        mux.close()
    }

    @Test fun `multiple streams drain independently and release each terminal file`() = runBlocking {
        val store = DiskCacheStore(temporary.newFolder())
        lateinit var socket: Socket
        val mux = RemoteStreamMux(store) { Socket(it).also { socket = it } }
        mux.start()
        val first = mux.open("one")
        val second = mux.open("two")
        repeat(500) { socket.item(1, it); socket.item(2, 10_000 + it) }
        socket.end(1); socket.end(2)
        first.cancel()
        assertTrue(store.usage().protectedBytes > 0)
        repeat(500) { assertEquals(JsonPrimitive(10_000 + it), second.receive()) }
        assertNull(second.receive())
        assertEquals(0L, store.usage().totalBytes)
        assertEquals(0, socket.sent.count { it.contains("\"type\":\"cancel\"") })
        mux.close()
    }

    @Test fun `close releases ended but undrained and active files and wakes readers`() = runBlocking {
        val store = DiskCacheStore(temporary.newFolder())
        lateinit var socket: Socket
        val mux = RemoteStreamMux(store) { Socket(it).also { socket = it } }
        mux.start()
        val ended = mux.open("ended")
        val waiting = mux.open("waiting")
        socket.item(1, 1); socket.end(1)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            try { waiting.receive(); false } catch (error: RemoteStreamException) { error.carrier }
        }
        mux.close()
        assertTrue(withTimeout(5_000) { pending.await() })
        try { ended.receive(); fail("explicit close ends ownership of undrained files") }
        catch (error: RemoteStreamException) { assertTrue(error.carrier) }
        assertEquals(0L, store.usage().totalBytes)
        assertEquals(1, socket.closes)
    }

    @Test fun `socket failure is queued after accepted records until consumed or owner closes`() = runBlocking {
        val store = DiskCacheStore(temporary.newFolder())
        lateinit var socket: Socket
        val mux = RemoteStreamMux(store) { Socket(it).also { socket = it } }
        mux.start()
        val stream = mux.open("test")
        socket.item(1, 1); socket.item(1, 2)
        socket.sink.onClosed(IOException("socket gone"))
        assertEquals(store.usage().totalBytes, store.clear().protectedBytes)
        assertEquals(JsonPrimitive(1), stream.receive())
        assertEquals(JsonPrimitive(2), stream.receive())
        try { stream.receive(); fail("carrier error expected") }
        catch (error: RemoteStreamException) { assertTrue(error.carrier) }
        assertEquals(0L, store.usage().totalBytes)
        mux.close()
    }

    @Test fun `cancel is idempotent and wakes disk reader as cancellation`() = runBlocking {
        val store = DiskCacheStore(temporary.newFolder())
        lateinit var socket: Socket
        val mux = RemoteStreamMux(store) { Socket(it).also { socket = it } }
        mux.start()
        val stream = mux.open("test")
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            try { stream.receive(); false } catch (_: CancellationException) { true }
        }
        stream.cancel(); stream.cancel()
        assertTrue(withTimeout(5_000) { pending.await() })
        assertEquals(1, socket.sent.count { it.contains("\"type\":\"cancel\"") })
        assertEquals(0L, store.usage().totalBytes)
        mux.close()
    }

    @Test fun `disk write failure fails generation explicitly rather than dropping and continuing`() = runBlocking {
        val store = DiskCacheStore(temporary.newFolder())
        lateinit var socket: Socket
        val mux = RemoteStreamMux(store) { Socket(it).also { socket = it } }
        mux.start()
        val stream = mux.open("test")
        val queue = stream.javaClass.getDeclaredField("disk").apply { isAccessible = true }.get(stream) as DiskRecordQueue
        (queue.javaClass.getDeclaredField("owner").apply { isAccessible = true }.get(queue) as RandomAccessFile).close()
        socket.item(1, 1)
        assertTrue(mux.failure is IOException)
        assertEquals(1, socket.closes)
        try { stream.receive(); fail("storage failure cannot pretend the item was accepted") }
        catch (error: RemoteStreamException) { assertTrue(error.carrier) }
        socket.item(1, 2)
        assertEquals(0L, store.usage().totalBytes)
        mux.close()
    }

    @Test fun `single large frame is preserved rather than rejected by size`() = runBlocking {
        val store = DiskCacheStore(temporary.newFolder())
        lateinit var socket: Socket
        val mux = RemoteStreamMux(store) { Socket(it).also { socket = it } }
        mux.start()
        val stream = mux.open("test")
        val payload = "a".repeat(3 * 1024 * 1024)
        socket.sink.onMessage("""{"type":"item","streamId":"1","value":"$payload"}""")
        socket.end(1)
        assertEquals(JsonPrimitive(payload), stream.receive())
        assertNull(stream.receive())
        assertNull(mux.failure)
        assertEquals(0L, store.usage().totalBytes)
        mux.close()
    }

    @Test fun `cannot open stream when disk category is blocked and no RAM fallback occurs`() {
        val store = DiskCacheStore(temporary.newFolder())
        File(store.root, "stream").writeText("not a directory")
        lateinit var socket: Socket
        val mux = RemoteStreamMux(store) { Socket(it).also { socket = it } }
        mux.start()
        try { mux.open("test"); fail("disk failure must be explicit") }
        catch (error: RemoteStreamException) { assertTrue(error.carrier) }
        assertTrue(mux.failure is IOException)
        assertEquals(1, socket.closes)
        mux.close()
    }

    companion object { private val client = OkHttpClient() }
}
