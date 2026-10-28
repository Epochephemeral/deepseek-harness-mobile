package com.labteto.dshmobile.core.storage

import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiskRecordQueueTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store() = DiskCacheStore(temporary.newFolder())

    @Test fun `thousands of arbitrary UTF8 records remain ordered and clear cannot delete them`() = runBlocking {
        val store = store()
        val queue = DiskRecordQueue(store)
        val values = List(5_000) { "$it:中文\u0000\n😀" }
        values.forEach(queue::append)
        val written = store.usage().totalBytes
        assertTrue(written > 0)
        assertEquals(written, store.clear().protectedBytes)
        values.forEach { assertEquals(it, queue.receive()) }
        assertEquals(0L, queue.file.length())
        queue.append("")
        assertEquals("", queue.receive())
        queue.close()
        assertEquals(0L, store.usage().totalBytes)
    }

    @Test fun `consumed prefixes compact without losing unread records`() = runBlocking {
        val queue = DiskRecordQueue(store())
        val payload = "x".repeat(12_000)
        repeat(240) { queue.append("$it:$payload") }
        val original = queue.file.length()
        repeat(180) { assertEquals("$it:$payload", queue.receive()) }
        assertTrue("consumed disk space is reclaimed before terminal drain", queue.file.length() < original)
        repeat(60) { assertEquals("${it + 180}:$payload", queue.receive()) }
        queue.close()
    }

    @Test fun `close wakes a suspended reader and deletes its leased file`() = runBlocking {
        val store = store()
        val queue = DiskRecordQueue(store)
        val waiting = async { queue.receive() }
        queue.close()
        try { waiting.await(); fail("reader must be cancelled") } catch (_: CancellationException) { }
        assertFalse(queue.file.exists())
        assertEquals(0L, store.usage().protectedBytes)
    }

    @Test fun `truncated record is an explicit failure not an invented end or skipped record`() = runBlocking {
        val store = store()
        val queue = DiskRecordQueue(store)
        queue.append("must not disappear silently")
        RandomAccessFile(queue.file, "rw").use { it.setLength(2) }
        try { queue.receive(); fail("corruption must fail") } catch (_: IOException) { }
        try { queue.receive(); fail("queue must remain failed") } catch (_: IOException) { }
        assertFalse(queue.file.exists())
        assertEquals(0L, store.usage().totalBytes)
    }

    @Test fun `append IO failure is sticky and releases the active file`() = runBlocking {
        val store = store()
        val queue = DiskRecordQueue(store)
        val owner = DiskRecordQueue::class.java.getDeclaredField("owner").apply { isAccessible = true }.get(queue) as RandomAccessFile
        owner.close() // deterministic write failure, independent of test user/root chmod privileges
        try { queue.append("not accepted"); fail("write must fail") } catch (_: IOException) { }
        try { queue.receive(); fail("failed write cannot become a clean empty queue") } catch (_: IOException) { }
        assertEquals(0L, store.usage().totalBytes)
    }
}
