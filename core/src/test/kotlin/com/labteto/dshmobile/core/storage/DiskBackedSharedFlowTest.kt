package com.labteto.dshmobile.core.storage

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiskBackedSharedFlowTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `slow subscriber keeps every event while fast subscriber proceeds and clear protects backlog`() = runBlocking {
        val store = DiskCacheStore(temporary.newFolder())
        val bus = DiskBackedSharedFlow(store, Int::toString, String::toInt)
        val gate = CompletableDeferred<Unit>()
        val fastDone = CompletableDeferred<Unit>()
        val slowDone = CompletableDeferred<Unit>()
        val fastValues = ArrayList<Int>()
        val slowValues = ArrayList<Int>()
        val fast = launch(start = CoroutineStart.UNDISPATCHED) {
            bus.collect { value -> fastValues += value; if (fastValues.size == 5_000) fastDone.complete(Unit) }
        }
        val slow = launch(start = CoroutineStart.UNDISPATCHED) {
            bus.collect { value ->
                if (value == 0) gate.await()
                slowValues += value
                if (slowValues.size == 5_000) slowDone.complete(Unit)
            }
        }
        assertEquals(2, bus.subscriptionCount.value)
        try {
            val producer = launch(Dispatchers.Default) { repeat(5_000) { assertTrue(bus.tryEmit(it)) } }
            withTimeout(20_000) { producer.join(); fastDone.await() }
            assertEquals((0 until 5_000).toList(), fastValues)
            assertTrue(slowValues.isEmpty())
            assertTrue(store.usage().totalBytes > 0)
            val cleared = store.clear()
            assertEquals(0L, cleared.deletedBytes)
            assertEquals(store.usage().totalBytes, cleared.protectedBytes)
            gate.complete(Unit)
            withTimeout(20_000) { slowDone.await() }
            assertEquals(fastValues, slowValues)
        } finally {
            fast.cancelAndJoin(); slow.cancelAndJoin()
        }
        assertEquals(0, bus.subscriptionCount.value)
        assertEquals(0L, store.usage().totalBytes)
    }

    @Test fun `zero subscriber events are not replayed and do not allocate backlog files`() = runBlocking {
        val store = DiskCacheStore(temporary.newFolder())
        val bus = DiskBackedSharedFlow(store, Int::toString, String::toInt)
        repeat(5_000) { bus.emit(it) }
        assertEquals(0L, store.usage().totalBytes)
        assertTrue(bus.replayCache.isEmpty())
        val received = CompletableDeferred<Int>()
        val reader = launch(start = CoroutineStart.UNDISPATCHED) { bus.collect { received.complete(it) } }
        try {
            bus.emit(7_000)
            assertEquals(7_000, withTimeout(5_000) { received.await() })
        } finally { reader.cancelAndJoin() }
        assertEquals(0L, store.usage().totalBytes)
    }

    @Test fun `producer storage failure reaches subscriber explicitly and never continues with skipped value`() = runBlocking {
        val store = DiskCacheStore(temporary.newFolder())
        val bus = DiskBackedSharedFlow(store, Int::toString, String::toInt)
        val failed = CompletableDeferred<IOException>()
        val reader = launch(start = CoroutineStart.UNDISPATCHED) {
            try { bus.collect { fail("failed value cannot appear") } }
            catch (error: IOException) { failed.complete(error) }
        }
        @Suppress("UNCHECKED_CAST")
        val subscribers = bus.javaClass.getDeclaredField("subscribers").apply { isAccessible = true }.get(bus) as Set<Any>
        val subscriber = subscribers.single()
        val queue = subscriber.javaClass.getDeclaredField("disk").apply { isAccessible = true }.get(subscriber) as DiskRecordQueue
        (queue.javaClass.getDeclaredField("owner").apply { isAccessible = true }.get(queue) as RandomAccessFile).close()
        try { bus.tryEmit(1); fail("producer must observe disk failure") } catch (_: IOException) { }
        withTimeout(5_000) { failed.await(); reader.join() }
        assertEquals(0, bus.subscriptionCount.value)
        assertEquals(0L, store.usage().totalBytes)
    }

    @Test fun `subscriber registration storage failure does not pretend successful subscription`() = runBlocking {
        val store = DiskCacheStore(temporary.newFolder())
        File(store.root, "stream").writeText("blocked")
        val bus = DiskBackedSharedFlow(store, Int::toString, String::toInt)
        try { bus.collect { fail() }; fail() } catch (_: IOException) { }
        assertEquals(0, bus.subscriptionCount.value)
    }
}
