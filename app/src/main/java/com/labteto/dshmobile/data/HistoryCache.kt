package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.EventFold
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.core.storage.DiskCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class HistoryCacheUsage(
    val bytes: Long = 0,
    val sessions: Int = 0,
    val previewBytes: Long = bytes,
    val historyBytes: Long = 0,
    val backlogBytes: Long = 0,
    val rpcBytes: Long = 0,
    /** Processing bytes retained by clear, excluding archive rows cleared by their DB owner. */
    val protectedBytes: Long = 0,
)

/** Legacy bounded previews plus an indexed disk archive without automatic content eviction.
 * Neither cached presentation is used as a live protocol baseline. */
class HistoryCache(
    private val directory: File,
    maxBytes: Long = com.labteto.dshmobile.connection.DEFAULT_HISTORY_CACHE_LIMIT_BYTES,
    private val diskStore: DiskCacheStore = DiskCacheStore(directory),
) {
    private val lock = Any()
    private var maxBytes = maxBytes
    private var lastAccessAt = 0L
    private val epoch = MutableStateFlow(0L)
    internal val ticketChanges = epoch.asStateFlow()
    private var historyDisk: SessionHistoryDisk? = null
    private class PageBuffer(val host: String, val sid: String) {
        val events = ArrayList<EncodedHistoryEvent>()
        var bytes = 0L
        fun clear() { events.clear(); bytes = 0 }
    }
    private val pageBuffers = HashMap<String, PageBuffer>()
    private fun archive(): SessionHistoryDisk = historyDisk ?: SessionHistoryDisk(diskStore).also { historyDisk = it }
    private val _usage = MutableStateFlow<HistoryCacheUsage?>(null)
    val usage = _usage.asStateFlow()

    /** Capture when a follow opens: clearing also invalidates its delayed writes and reads. */
    fun ticket(): Long = epoch.value

    /** Fence a complete archive read/write/install against clear, not just individual DB calls.
     * Callers may hold their store lock; this class never calls back into that store on clear. */
    internal fun <T> atTicket(ticket: Long, block: () -> T): T? = synchronized(lock) {
        if (ticket != epoch.value) null else block()
    }

    /** Every received durable event is indexed; unlike previews this never applies historyTail/LRU. */
    fun storeEvents(host: String, sid: String, ticket: Long, events: List<SessionEventEnvelope>): Boolean = synchronized(lock) {
        if (ticket != epoch.value) return@synchronized false
        archive().store(host, sid, events)
        true
    }

    /** Persist exactly one newly received live record at the epoch current under this lock.
     * Never use this to replay a previously loaded window after clear. Serialization happens once. */
    fun storeLiveEvent(host: String,sid: String,event: SessionEventEnvelope): Long = synchronized(lock) {
        val encoded=encodeHistoryEvent(event)
        archive().storeEncoded(host,sid,encoded)
        encoded.payload.size.toLong()
    }

    fun latestHistory(host: String, sid: String, maxMessages: Int = 60, ticket: Long = ticket()): HistoryDiskPage = synchronized(lock) {
        if (ticket != epoch.value) HistoryDiskPage(emptyList(), false, false) else archive().latest(host, sid, maxMessages)
    }
    fun olderHistory(host: String, sid: String, beforeSeq: Long, maxMessages: Int = 60, ticket: Long = ticket()): HistoryDiskPage = synchronized(lock) {
        if (ticket != epoch.value) HistoryDiskPage(emptyList(), false, false) else archive().older(host, sid, beforeSeq, maxMessages)
    }
    fun newerHistory(host: String, sid: String, afterSeq: Long, maxMessages: Int = 60, ticket: Long = ticket()): HistoryDiskPage = synchronized(lock) {
        if (ticket != epoch.value) HistoryDiskPage(emptyList(), false, false) else archive().newer(host, sid, afterSeq, maxMessages)
    }
    fun historyAround(host: String, sid: String, seq: Long, maxMessages: Int = 60, ticket: Long = ticket()): HistoryDiskPage = synchronized(lock) {
        if (ticket != epoch.value) HistoryDiskPage(emptyList(), false, false) else archive().around(host, sid, seq, maxMessages)
    }
    /** Exact already-loaded addition, not a transfer page. Never bridge an uncached gap implicitly. */
    fun historyRange(host: String, sid: String, fromSeq: Long, throughSeq: Long, ticket: Long = ticket()): HistoryDiskPage? = synchronized(lock) {
        if (ticket != epoch.value) null else archive().range(host, sid, fromSeq, throughSeq)
    }
    fun removeHistoryAfter(host: String, sid: String, throughSeq: Long, ticket: Long = ticket()): Boolean = synchronized(lock) {
        if (ticket != epoch.value) return@synchronized false
        archive().removeAfter(host, sid, throughSeq)
        true
    }

    fun beginPage(host: String, sid: String, ticket: Long): HistoryPageWriter {
        val request = java.util.UUID.randomUUID().toString()
        synchronized(lock) {
            if (ticket == epoch.value) pageBuffers[request] = PageBuffer(host,sid)
        }
        return HistoryPageWriter(
            appendAction = { events -> synchronized(lock) {
                // Only beginPage creates this entry. Never getOrPut here: an append may have
                // passed the writer's finished check before close/commit removed its buffer.
                // Absence under this lock is final, so a late callback cannot resurrect staging.
                val pending = pageBuffers[request]
                if (ticket != epoch.value || pending == null) {
                    pageBuffers.remove(request)?.clear()
                    false
                } else {
                    for (event in events) {
                        val encoded = encodeHistoryEvent(event)
                        // Batch sizes bound only our unpublished working memory/fsync frequency.
                        // A single large record is flushed alone; no record is rejected or cut.
                        if (pending.events.isNotEmpty() &&
                            (pending.events.size >= STAGE_BATCH_EVENTS || pending.bytes + encoded.payload.size > STAGE_BATCH_BYTES)) {
                            flushStage(request,pending)
                        }
                        pending.events.add(encoded)
                        pending.bytes += encoded.payload.size
                        if (pending.events.size >= STAGE_BATCH_EVENTS || pending.bytes >= STAGE_BATCH_BYTES) flushStage(request,pending)
                    }
                    true
                }
            } },
            commitAction = { synchronized(lock) {
                val pending = pageBuffers[request]
                if (ticket != epoch.value || pending == null) false else {
                    flushStage(request,pending)
                    archive().commitStage(request)
                    pageBuffers.remove(request)?.clear()
                    true
                }
            } },
            abortAction = { synchronized(lock) {
                pageBuffers.remove(request)?.clear()
                historyDisk?.abortStage(request)
                Unit
            } },
        )
    }

    private fun flushStage(request: String,pending: PageBuffer) {
        if (pending.events.isEmpty()) return
        archive().appendStage(request,pending.host,pending.sid,pending.events)
        pending.clear()
    }

    private fun discardPageBuffers() {
        pageBuffers.values.forEach { it.clear() }
        pageBuffers.clear()
    }

    /** Primarily for fixture/process teardown. Durable files remain until explicit clear. */
    fun close() = synchronized(lock) {
        epoch.value += 1
        discardPageBuffers()
        val database = historyDisk
        historyDisk = null
        database?.close()
    }

    suspend fun refreshUsage() = withContext(Dispatchers.IO) {
        synchronized(lock) { publishUsage() }
    }

    suspend fun read(host: String, session: String, ticket: Long): List<SessionEventEnvelope>? =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (ticket != epoch.value) return@synchronized null
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
                if (ticket != epoch.value) return@synchronized
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
            epoch.value += 1
            discardPageBuffers()
            // No archive operation can run outside this lock. Release our DB pin, then use the
            // shared clear path; unlike VACUUM this does not need extra free disk space.
            val database = historyDisk
            historyDisk = null
            database?.close()
            val files = directory.listFiles().orEmpty().filter { it.isFile && (it.extension == "gz" || it.extension == "tmp") }
            val failed = files.map { it.delete() || !it.exists() }.any { !it }
            check(diskStore.root.isDirectory || diskStore.root.mkdirs())
            diskStore.clear() // Active stream/RPC leases (and any other process owner) stay protected.
            publishUsage()
            check(!failed) { "Unable to clear history previews" }
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
        check(diskStore.root.isDirectory || diskStore.root.mkdirs())
        val managed = diskStore.usage()
        val preview = directory.listFiles().orEmpty()
            .filter { it.isFile && (it.extension == "gz" || it.extension == "tmp") }.sumOf { it.length() }
        val history = diskStore.usage("history").totalBytes
        val backlog = diskStore.usage("stream")
        val rpc = diskStore.usage("rpc")
        // The DB is physically pinned while open, but clear() releases our pin and deletes it.
        // Report only processing data that cleanup really must retain, not the entire archive.
        val retained = backlog.protectedBytes + rpc.protectedBytes + diskStore.usage("preview").protectedBytes
        val previewInsideManaged = directory.canonicalFile == diskStore.root ||
            directory.canonicalPath.startsWith(diskStore.root.path + File.separator)
        val total = managed.totalBytes + if (previewInsideManaged) 0 else preview
        _usage.value = HistoryCacheUsage(total, files.size,
            preview, history, backlog.totalBytes, rpc.totalBytes, retained)
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
        const val STAGE_BATCH_EVENTS = 64
        const val STAGE_BATCH_BYTES = 1024 * 1024
    }
}

/** Fold only durable history; never restore live tools, queue, projections or approval state. */
internal fun historyPreview(session: String, events: List<SessionEventEnvelope>): ConversationSnapshot =
    EventFold(session).fold(events).copy(running = false, hasMore = false, fromCache = true)

@Serializable
private data class CachedHistory(val version: Int = 1, val events: List<CachedEvent>)

@Serializable
internal data class CachedEvent(
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
