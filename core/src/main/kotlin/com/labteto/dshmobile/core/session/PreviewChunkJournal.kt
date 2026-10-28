package com.labteto.dshmobile.core.session

import com.labteto.dshmobile.core.storage.DiskCacheStore
import com.labteto.dshmobile.core.storage.DiskLease
import com.labteto.dshmobile.core.wire.WireJson
import java.io.Closeable
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.serialization.json.JsonObject

/** Raw preview replay is needed only for legacy rebases and the compatibility envelope API. */
internal interface PreviewChunkJournal : Closeable {
    val count: Int
    fun append(chunk: TimedChunk)
    /** Cursor is opaque: an item index in memory, a byte offset on disk. */
    fun replayFrom(cursor: Long, consume: (TimedChunk) -> Unit): Long
}

internal fun previewChunkJournal(store: DiskCacheStore?): PreviewChunkJournal =
    if (store == null) MemoryPreviewChunks() else DiskPreviewChunks(store.createLease("stream", ".preview-chunks"))

private class MemoryPreviewChunks : PreviewChunkJournal {
    private val chunks = ArrayList<TimedChunk>()
    override val count: Int get() = chunks.size
    override fun append(chunk: TimedChunk) { chunks.add(chunk) }
    override fun replayFrom(cursor: Long, consume: (TimedChunk) -> Unit): Long {
        for (index in cursor.toInt() until chunks.size) consume(chunks[index])
        return chunks.size.toLong()
    }
    override fun close() = chunks.clear()
}

private class DiskPreviewChunks(private val lease: DiskLease) : PreviewChunkJournal {
    private val file = try { RandomAccessFile(lease.file, "rw") } catch (failure: Exception) {
        try { lease.close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
        throw failure
    }
    override var count: Int = 0
        private set

    override fun append(chunk: TimedChunk) {
        val bytes = WireJson.encodeToString(JsonObject.serializer(), chunk.chunk).toByteArray(Charsets.UTF_8)
        file.seek(file.length())
        file.writeLong(chunk.time)
        file.writeInt(bytes.size)
        file.write(bytes)
        count++
    }

    override fun replayFrom(cursor: Long, consume: (TimedChunk) -> Unit): Long {
        file.seek(cursor)
        while (file.filePointer < file.length()) {
            if (file.length() - file.filePointer < 12L) throw IOException("Incomplete preview chunk journal header")
            val time = file.readLong()
            val size = file.readInt()
            if (size < 0 || size.toLong() > file.length() - file.filePointer) throw IOException("Incomplete preview chunk journal record")
            val bytes = ByteArray(size)
            file.readFully(bytes)
            val chunk = WireJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
                ?: throw IOException("Preview chunk journal record is not an object")
            consume(TimedChunk(time, chunk))
        }
        return file.filePointer
    }

    override fun close() {
        try { file.close() } finally { lease.close() }
    }
}
