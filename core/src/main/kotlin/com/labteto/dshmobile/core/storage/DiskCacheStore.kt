package com.labteto.dshmobile.core.storage

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/** Live files count toward usage; clear never revokes a reader/backlog lease. */
data class DiskCacheUsage(val totalBytes: Long, val protectedBytes: Long) {
    val activeBytes: Long get() = protectedBytes
}
data class DiskCacheClearResult(val deletedBytes: Long, val protectedBytes: Long, val totalBytes: Long)

class DiskLease internal constructor(val file: File, private val release: () -> Unit) : Closeable {
    override fun close() = release()
}

/**
 * Shared history/stream/RPC storage without a size cutoff. Canonical-root ownership and OS locks
 * protect leases across instances/processes. Temp generation files are NOT replay checkpoints.
 * No constructor sweep: inactive crash leftovers can safely be removed by clear().
 */
class DiskCacheStore(root: File) {
    val root: File = root.canonicalFile
    private class RootState { val active = HashSet<String>() }
    private val state = synchronized(states) { states.getOrPut(this.root.path) { RootState() } }
    // DI/app startup must remain possible when storage is full, so Settings can free old caches.
    // Directory/lock creation is deferred until an actual operation and fails explicitly there.

    fun createLease(category: String, suffix: String): DiskLease = guarded {
        require('/' !in suffix && '\\' !in suffix) { "Lease suffix must not contain a path" }
        val directory = directory(category)
        val file = File.createTempFile("lease-", suffix, directory).canonicalFile
        own(file, file, deleteOnClose = true)
    }

    /**
     * Pin a persistent category until the DB/reader is closed. A separate lock protects DB/WAL/SHM
     * together, without imposing Java FileLocks on SQLite's own locking bytes. close preserves data.
     * The owner must clear DB rows itself while open; generic clear reports protected bytes.
     */
    fun openPersistentLease(category: String, name: String): DiskLease = guarded {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name) { "Invalid persistent cache name" }
        val directory = directory(category)
        val file = File(directory, name).canonicalFile
        verifyOwnedPath(file)
        if (!file.exists() && !file.createNewFile()) throw IOException("Cannot create persistent cache: $file")
        own(file, File(directory, ".pin-$name.lock"), deleteOnClose = false)
    }

    fun usage(): DiskCacheUsage = guarded { usageLocked() }

    fun usage(category: String): DiskCacheUsage = guarded {
        require(category.isNotEmpty() && category != "." && category != ".." && '/' !in category && '\\' !in category) { "Invalid cache category" }
        usageLocked(listOf(File(root, category)), if (category == "preview") rootFiles().filter { it.name.endsWith(".gz") } else emptyList())
    }

    fun clear(): DiskCacheClearResult = guarded {
        var deleted = 0L
        categoryDirectories().forEach { directory ->
            if (!categoryPinned(directory)) {
                files(directory).forEach { file ->
                    if (file.path !in state.active) {
                        val bytes = file.length()
                        withInactiveLock(file) {
                            verifyOwnedPath(file)
                            if (file.exists() && !file.delete()) throw IOException("Cannot clear inactive cache file: $file")
                            deleted += bytes
                        }
                    }
                }
            }
        }
        rootFiles().forEach { file ->
            if (file.path !in state.active) {
                val bytes = file.length()
                withInactiveLock(file) {
                    verifyOwnedPath(file)
                    if (file.exists() && !file.delete()) throw IOException("Cannot clear inactive root cache file: $file")
                    deleted += bytes
                }
            }
        }
        val remaining = usageLocked()
        DiskCacheClearResult(deleted, remaining.protectedBytes, remaining.totalBytes)
    }

    private fun own(file: File, lockFile: File, deleteOnClose: Boolean): DiskLease {
        verifyOwnedPath(file)
        verifyOwnedPath(lockFile)
        var owner: RandomAccessFile? = null
        var held: FileLock? = null
        try {
            owner = RandomAccessFile(lockFile, "rw")
            held = try { owner.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (held == null) throw IOException("Cache file is already leased: $file")
            state.active.add(lockFile.canonicalPath)
            val handle = owner
            val lock = held
            var released = false
            return DiskLease(file) {
                guarded {
                    if (!released) {
                        released = true
                        var problem: IOException? = null
                        fun record(error: IOException) { if (problem == null) problem = error else problem!!.addSuppressed(error) }
                        try { lock.release() } catch (error: IOException) { record(error) }
                        try { handle.close() } catch (error: IOException) { record(error) }
                        try {
                            if (deleteOnClose) {
                                verifyOwnedPath(file)
                                if (file.exists() && !file.delete()) record(IOException("Cannot delete released cache lease: $file"))
                            }
                        } finally { state.active.remove(lockFile.canonicalPath) }
                        problem?.let { throw it }
                    }
                }
            }
        } catch (error: Exception) {
            try { held?.release() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            try { owner?.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            if (deleteOnClose) {
                verifyOwnedPath(file)
                if (file.exists() && !file.delete()) error.addSuppressed(IOException("Cannot delete failed lease: $file"))
            }
            throw error
        }
    }

    private fun usageLocked(directories: List<File> = categoryDirectories(), extras: List<File> = rootFiles()): DiskCacheUsage {
        var total = 0L
        var protected = 0L
        directories.forEach { directory ->
            if (directory.exists()) {
                val pinned = categoryPinned(directory)
                files(directory).forEach { file ->
                    val bytes = file.length()
                    total += bytes
                    if (pinned || file.path in state.active || !withInactiveLock(file) { }) protected += bytes
                }
            }
        }
        extras.forEach { file ->
            val bytes = file.length()
            total += bytes
            if (file.path in state.active || !withInactiveLock(file) { }) protected += bytes
        }
        return DiskCacheUsage(total, protected)
    }

    private fun rootFiles(): List<File> =
        (root.listFiles() ?: throw IOException("Cannot list cache root: $root")).filter { child ->
            verifyOwnedPath(child)
            child.isFile && child.name != ".store.lock"
        }

    private fun categoryDirectories(): List<File> =
        (root.listFiles() ?: throw IOException("Cannot list cache root: $root")).filter { child ->
            verifyOwnedPath(child)
            child.isDirectory
        }

    private fun categoryPinned(directory: File): Boolean = files(directory).any { file ->
        file.name.startsWith(".pin-") && file.name.endsWith(".lock") &&
            (file.path in state.active || !withInactiveLock(file) { })
    }

    private fun directory(category: String): File {
        require(category in categories) { "Unknown disk cache category: $category" }
        return File(root, category).also { verifyOwnedPath(it); ensureDirectory(it) }
    }

    private fun files(directory: File): List<File> {
        val found = ArrayList<File>()
        fun visit(parent: File) {
            val children = parent.listFiles() ?: throw IOException("Cannot list cache directory: $parent")
            children.forEach { child ->
                verifyOwnedPath(child)
                if (child.isDirectory) visit(child) else if (child.isFile) found += child.canonicalFile
            }
        }
        visit(directory)
        return found
    }

    private fun withInactiveLock(file: File, action: () -> Unit): Boolean {
        if (!file.exists()) return true
        RandomAccessFile(file, "rw").use { owner ->
            val lock = try { owner.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock == null) return false
            try { action() } finally { lock.release() }
        }
        return true
    }

    /** Short metadata mutex closes the cross-process create-before-lock vs clear race. */
    private fun <T> guarded(action: () -> T): T = synchronized(state) {
        ensureDirectory(root)
        RandomAccessFile(File(root, ".store.lock"), "rw").use { owner ->
            owner.channel.lock().use { action() }
        }
    }

    private fun verifyOwnedPath(file: File) {
        if (!file.canonicalPath.startsWith(root.path + File.separator) || file.absoluteFile.normalize() != file.canonicalFile) {
            throw IOException("Cache path is not an owned non-symlink path: $file")
        }
    }

    companion object {
        private val categories = setOf("history", "stream", "rpc", "preview")
        private val states = HashMap<String, RootState>()
        private fun ensureDirectory(file: File) {
            if ((!file.exists() && !file.mkdirs()) || !file.isDirectory) throw IOException("Cannot create cache directory: $file")
        }
    }
}
