package com.labteto.dshmobile.core.storage

import java.io.Closeable
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

/**
 * Ordered length-prefixed UTF-8 records. Only offsets and a conflated wakeup remain in memory.
 * Appends are synchronous (no hidden growing executor queue); reads/compaction run on IO.
 * This scratch log is owned by one connection generation and is never reopened as a replay log.
 */
internal class DiskRecordQueue(store: DiskCacheStore) : Closeable {
    private val lease = store.createLease("stream", ".backlog")
    internal val file get() = lease.file
    private val owner = try { RandomAccessFile(lease.file, "rw") } catch (error: Exception) {
        try { lease.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
        throw error
    }
    private val lock = Any()
    private val changed = Channel<Unit>(Channel.CONFLATED)
    private var readOffset = 0L
    private var writeOffset = 0L
    private var stopped: Throwable? = null

    fun append(record: String) {
        try {
            synchronized(lock) {
                ensureOpen()
                val bytes = record.toByteArray(Charsets.UTF_8)
                owner.seek(writeOffset)
                owner.writeInt(bytes.size)
                owner.write(bytes)
                writeOffset = owner.filePointer
            }
            changed.trySend(Unit)
        } catch (error: IOException) {
            abortPreserving(error)
            throw error
        }
    }

    suspend fun receive(): String {
        while (true) {
            val record = try {
                withContext(Dispatchers.IO) {
                    synchronized(lock) {
                        ensureOpen()
                        if (readOffset == writeOffset) null else {
                            owner.seek(readOffset)
                            val length = owner.readInt()
                            if (length < 0 || length.toLong() > writeOffset - readOffset - 4) {
                                throw IOException("Corrupt disk backlog record at $readOffset")
                            }
                            val bytes = ByteArray(length)
                            owner.readFully(bytes)
                            readOffset = owner.filePointer
                            reclaimConsumedPrefix()
                            bytes.toString(Charsets.UTF_8)
                        }
                    }
                }
            } catch (error: IOException) {
                abortPreserving(error)
                throw error
            }
            if (record != null) return record
            changed.receive()
        }
    }

    /** Explicit abandonment wakes readers with the cause, not an invented successful end. */
    fun abort(cause: Throwable) {
        synchronized(lock) {
            if (stopped != null) return
            stopped = cause
            var failure: IOException? = null
            try { owner.close() } catch (error: IOException) { failure = error }
            try { lease.close() } catch (error: IOException) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            changed.trySend(Unit)
            failure?.let { throw it }
        }
    }

    override fun close() = abort(CancellationException("Disk backlog cancelled"))

    private fun ensureOpen() { stopped?.let { throw it } }
    private fun abortPreserving(cause: IOException) {
        try { abort(cause) } catch (cleanup: IOException) { cause.addSuppressed(cleanup) }
    }

    /** Internal compaction scheduling, not a content limit: every unread byte is retained. */
    private fun reclaimConsumedPrefix() {
        if (readOffset == writeOffset) {
            owner.setLength(0)
            readOffset = 0
            writeOffset = 0
        } else if (readOffset >= 1024 * 1024 && readOffset >= writeOffset - readOffset) {
            val remaining = writeOffset - readOffset
            val buffer = ByteArray(64 * 1024)
            var copied = 0L
            while (copied < remaining) {
                val count = minOf(buffer.size.toLong(), remaining - copied).toInt()
                owner.seek(readOffset + copied)
                owner.readFully(buffer, 0, count)
                owner.seek(copied)
                owner.write(buffer, 0, count)
                copied += count
            }
            owner.setLength(remaining)
            readOffset = 0
            writeOffset = remaining
        }
    }
}
