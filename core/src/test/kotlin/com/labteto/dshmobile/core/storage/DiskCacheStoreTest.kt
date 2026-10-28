package com.labteto.dshmobile.core.storage

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiskCacheStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store() = DiskCacheStore(temporary.newFolder())

    @Test fun `active files remain protected across store instances and clear`() {
        val first = store()
        val second = DiskCacheStore(File(first.root, "."))
        val lease = first.createLease("stream", ".queue")
        lease.file.writeText("unread backlog")
        val bytes = lease.file.length()
        assertEquals(DiskCacheUsage(bytes, bytes), second.usage())
        val cleared = second.clear()
        assertEquals(0L, cleared.deletedBytes)
        assertEquals(bytes, cleared.protectedBytes)
        assertEquals("unread backlog", lease.file.readText())
        lease.close()
        lease.close()
        assertFalse(lease.file.exists())
        assertEquals(0L, second.usage().totalBytes)
    }

    @Test fun `inactive leftovers clear while active RPC and stream remain`() {
        val store = store()
        val inactive = File(store.root, "stream/orphan.backlog").apply { parentFile.mkdirs(); writeText("orphan") }
        val active = store.createLease("rpc", ".json").apply { file.writeText("rpc body") }
        val result = store.clear()
        assertEquals(6L, result.deletedBytes)
        assertEquals(active.file.length(), result.protectedBytes)
        assertFalse(inactive.exists())
        assertTrue(active.file.exists())
        active.close()
    }

    @Test fun `external file locks protect unregistered active files`() {
        val store = store()
        val file = File(store.root, "stream/foreign.backlog").apply { parentFile.mkdirs(); writeText("leased") }
        RandomAccessFile(file, "rw").use { owner ->
            owner.channel.lock().use {
                assertEquals(6L, store.usage().protectedBytes)
                assertEquals(0L, store.clear().deletedBytes)
                assertTrue(file.exists())
            }
        }
        assertEquals(6L, store.clear().deletedBytes)
    }

    @Test fun `persistent DB pin protects SQLite sidecars without locking actual DB`() {
        val store = store()
        val database = store.openPersistentLease("history", "events.sqlite")
        database.file.writeText("database")
        val wal = File(database.file.parentFile, "events.sqlite-wal").apply { writeText("pending transaction") }
        val shm = File(database.file.parentFile, "events.sqlite-shm").apply { writeText("index") }
        val preview = store.createLease("preview", ".gz").also { it.file.writeText("preview") }
        // SQLite is free to lock its DB: the cache's persistent lock uses a different inode.
        RandomAccessFile(database.file, "rw").use { it.channel.lock().use { } }
        val expected = database.file.length() + wal.length() + shm.length()
        assertEquals(expected, store.usage("history").protectedBytes)
        assertEquals(expected + preview.file.length(), store.clear().protectedBytes)
        assertTrue(wal.exists())
        preview.close()
        database.close()
        assertTrue(database.file.exists())
        assertEquals(0L, store.usage().protectedBytes)
        assertEquals(expected, store.clear().deletedBytes)
        assertFalse(wal.exists())
    }

    @Test fun `root legacy and preview bytes are included once in total accounting`() {
        val store = store()
        File(store.root, "legacy.gz").writeText("old")
        File(store.root, "preview/new.gz").apply { parentFile.mkdirs(); writeText("newer") }
        File(store.root, "unknown/nested/file").apply { parentFile.mkdirs(); writeText("other") }
        assertEquals(8L, store.usage("preview").totalBytes)
        assertEquals(13L, store.usage().totalBytes)
        assertEquals(13L, store.clear().deletedBytes)
        assertEquals(0L, store.usage().totalBytes)
    }

    @Test fun `cannot silently fall back when root or category cannot be opened`() {
        val file = temporary.newFile()
        val unavailable = DiskCacheStore(file) // constructing dependencies must not crash app startup
        try { unavailable.usage(); fail("unavailable root usage must fail") } catch (_: IOException) { }
        try { unavailable.createLease("stream", ".queue"); fail("file root operation must fail") } catch (_: IOException) { }
        val store = store()
        File(store.root, "stream").writeText("not a directory")
        try { store.createLease("stream", ".queue"); fail("blocked category must fail") } catch (_: IOException) { }
    }

    @Test fun `constructing store and broadcast dependencies does not create missing managed root`() {
        val root = File(temporary.newFolder(), "managed-cache")
        val store = DiskCacheStore(root)
        DiskBackedSharedFlow(store, Int::toString, String::toInt)
        assertFalse("startup must not allocate files before an actual cache operation", root.exists())
        assertEquals(0L, store.usage().totalBytes)
        assertTrue(root.isDirectory)
    }

    @Test fun `blocked root still permits dependency construction but operations fail explicitly`() {
        val blocker = temporary.newFile()
        val store = DiskCacheStore(blocker)
        DiskBackedSharedFlow(store, Int::toString, String::toInt)
        assertTrue(blocker.isFile)
        try { store.clear(); fail("clear must report inaccessible storage") } catch (_: IOException) { }
    }

    @Test fun `lease names cannot escape category`() {
        val store = store()
        try { store.createLease("../outside", ".queue"); fail() } catch (_: IllegalArgumentException) { }
        try { store.openPersistentLease("history", "../outside"); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(0L, store.usage().totalBytes)
    }
}
