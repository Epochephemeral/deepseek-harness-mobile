package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.EventFold
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.wire.WireJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class HistoryCacheUsage(val bytes: Long = 0, val sessions: Int = 0)

/** A disposable, bounded preview store. Cached data is never used as a live protocol baseline. */
class HistoryCache(
    private val directory: File,
    maxBytes: Long = com.labteto.dshmobile.connection.DEFAULT_HISTORY_CACHE_LIMIT_BYTES,
) {
    private val lock = Any()
    private var maxBytes = maxBytes
    private var lastAccessAt = 0L
    private val epoch = AtomicLong()
    private val _usage = MutableStateFlow<HistoryCacheUsage?>(null)
    val usage = _usage.asStateFlow()

    /** Capture when a follow opens: clearing also invalidates its delayed writes and reads. */
    fun ticket(): Long = epoch.get()

    suspend fun refreshUsage() = withContext(Dispatchers.IO) {
        synchronized(lock) { publishUsage() }
    }

    suspend fun read(host: String, session: String, ticket: Long): List<SessionEventEnvelope>? =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (ticket != epoch.get()) return@synchronized null
                val file = entry(host, session)
                if (!file.isFile) return@synchronized null
                try {
                    // Bound decompression as well as disk use; a damaged file is just a miss.
                    val bytes = GZIPInputStream(file.inputStream()).use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(output.size() + count <= MAX_ENTRY_BYTES)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    check(bytes.size <= MAX_ENTRY_BYTES)
                    val value = WireJson.decodeFromString(CachedHistory.serializer(), bytes.toString(Charsets.UTF_8))
                    check(value.version == 1)
                    touch(file)
                    value.events.map { it.envelope() }
                } catch (_: Exception) {
                    file.delete()
                    publishUsage()
                    null
                }
            }
        }

    suspend fun write(host: String, session: String, ticket: Long, events: List<SessionEventEnvelope>) =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (ticket != epoch.get()) return@synchronized
                val file = entry(host, session)
                val temporary = File(directory, file.name + ".tmp")
                try {
                    check(directory.isDirectory || directory.mkdirs())
                    val value = CachedHistory(events = events.map(CachedEvent::from))
                    val bytes = WireJson.encodeToString(CachedHistory.serializer(), value).toByteArray()
                    if (bytes.size > MAX_ENTRY_BYTES) {
                        file.delete()
                        return@synchronized
                    }
                    GZIPOutputStream(temporary.outputStream()).use { it.write(bytes) }
                    check(temporary.renameTo(file))
                    touch(file)
                    evict()
                } finally {
                    temporary.delete()
                    publishUsage()
                }
            }
        }

    /** Apply a saved capacity immediately; a smaller limit evicts least recently used entries. */
    suspend fun setLimitBytes(bytes: Long) = withContext(Dispatchers.IO) {
        require(bytes > 0)
        synchronized(lock) {
            maxBytes = bytes
            evict()
            publishUsage()
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            epoch.incrementAndGet()
            val files = directory.listFiles().orEmpty()
            val failed = files.map { it.delete() || !it.exists() }.any { !it }
            publishUsage()
            check(!failed) { "Unable to clear history cache" }
        }
    }

    private fun entry(host: String, session: String): File {
        val key = "${host.length}:$host$session".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.gz")
    }

    private fun entries() = directory.listFiles().orEmpty().filter { it.isFile && it.extension == "gz" }

    private fun publishUsage() {
        val files = entries()
        _usage.value = HistoryCacheUsage(files.sumOf { it.length() }, files.size)
    }

    /** Persist a strict access order, including multiple accesses in one clock millisecond. */
    private fun touch(file: File) {
        if (lastAccessAt == 0L) lastAccessAt = entries().maxOfOrNull { it.lastModified() } ?: 0L
        lastAccessAt = maxOf(System.currentTimeMillis(), lastAccessAt + 1)
        check(file.setLastModified(lastAccessAt))
    }

    private fun evict() {
        val files = entries().sortedBy { it.lastModified() }
        var size = files.sumOf { it.length() }
        for (file in files) {
            if (size <= maxBytes) break
            val length = file.length()
            check(file.delete() || !file.exists())
            size -= length
        }
    }

    private companion object {
        const val MAX_ENTRY_BYTES = 16 * 1024 * 1024
    }
}

/** Fold only durable history; never restore live tools, queue, projections or approval state. */
internal fun historyPreview(session: String, events: List<SessionEventEnvelope>): ConversationSnapshot =
    EventFold(session).fold(events).copy(running = false, hasMore = false, fromCache = true)

@Serializable
private data class CachedHistory(val version: Int = 1, val events: List<CachedEvent>)

@Serializable
private data class CachedEvent(
    val type: String,
    val seq: Long,
    val time: Long,
    val data: JsonElement,
    val surfaceOp: String? = null,
    val surfaceIntent: JsonElement? = null,
    val sourceEventSeqs: List<Int>? = null,
    val ignorable: Boolean? = null,
) {
    fun envelope() = SessionEventEnvelope(type, seq, time, data, surfaceOp, surfaceIntent, sourceEventSeqs, ignorable)
    companion object {
        fun from(event: SessionEventEnvelope) = with(event) {
            CachedEvent(type, seq, time, data, surfaceOp, surfaceIntent, sourceEventSeqs, ignorable)
        }
    }
}
