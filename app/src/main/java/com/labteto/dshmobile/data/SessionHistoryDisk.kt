package com.labteto.dshmobile.data

import android.database.sqlite.SQLiteDatabase
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.session.isHistoryContentEventType
import com.labteto.dshmobile.core.storage.DiskCacheStore
import com.labteto.dshmobile.core.wire.WireJson
import java.io.ByteArrayOutputStream
import java.io.Closeable

/** A physical contiguous journal viewport. Modern pages contain whole standalone event records;
 * legacy durable chunks expand to complete user turns so their text prefix is never lost. */
data class HistoryDiskPage(
    val events: List<SessionEventEnvelope>,
    val hasOlder: Boolean,
    val hasNewer: Boolean,
    /** Legacy durable chunks need their entire user turn until fold checkpoints exist. */
    val legacyUnbounded: Boolean = false,
    /** Exact serialized payload bytes of this selected range, measured before JSON decoding. */
    val payloadBytes: Long = 0,
) {
    val firstSeq: Long? get() = events.firstOrNull()?.seq
    val lastSeq: Long? get() = events.lastOrNull()?.seq
    val contiguousRanges: List<LongRange> get() {
        if (events.isEmpty()) return emptyList()
        val ranges = ArrayList<LongRange>()
        var first = events.first().seq
        var last = first
        for (index in 1 until events.size) {
            val event=events[index]
            if (event.seq != last + 1) { ranges += first..last; first = event.seq }
            last = event.seq
        }
        ranges += first..last
        return ranges
    }
}

/** RPC records stay invisible until its complete envelope has been validated and committed. */
class HistoryPageWriter internal constructor(
    private val appendAction: (List<SessionEventEnvelope>) -> Boolean,
    private val commitAction: () -> Boolean,
    private val abortAction: () -> Unit,
) : Closeable {
    // Archive actions serialize under the cache lock. Do not add a writer monitor: callers can
    // already hold cache.atTicket while committing, so writer -> cache would invert that order.
    private val finished = java.util.concurrent.atomic.AtomicBoolean(false)
    fun append(events: List<SessionEventEnvelope>): Boolean {
        check(!finished.get()) { "History page is already finished" }
        return appendAction(events)
    }
    fun commit(): Boolean {
        check(finished.compareAndSet(false,true)) { "History page is already finished" }
        return try { commitAction() } catch (failure: Exception) {
            try { abortAction() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
    override fun close() {
        if (finished.compareAndSet(false,true)) abortAction()
    }
}

/** One serialization reused by the bounded staging working buffer and SQLite insertion. */
internal data class EncodedHistoryEvent(val seq: Long, val type: String, val payload: ByteArray)
internal fun encodeHistoryEvent(event: SessionEventEnvelope) = EncodedHistoryEvent(event.seq,event.type,
    WireJson.encodeToString(CachedEvent.serializer(),CachedEvent.from(event)).toByteArray(Charsets.UTF_8))

/** Indexed durable local history. No preview-cache capacity or automatic history eviction. */
internal class SessionHistoryDisk(store: DiskCacheStore) : Closeable {
    private val lock = Any()
    private val lease = store.openPersistentLease("history", "sessions.sqlite")
    private var opening: SQLiteDatabase? = null
    private val db = try {
        SQLiteDatabase.openOrCreateDatabase(lease.file.path, null, android.database.DatabaseErrorHandler {
            // Android's default handler deletes corrupt databases. History is not disposable: retain
            // its bytes and surface the failure for explicit user recovery/cleanup instead.
            throw android.database.sqlite.SQLiteDatabaseCorruptException("Local session history is corrupt; existing files were retained")
        }).also { database ->
            opening = database
            check(database.version in 0..2) { "Unsupported local history database version ${database.version}" }
            database.execSQL("CREATE TABLE IF NOT EXISTS events (host TEXT NOT NULL, sid TEXT NOT NULL, seq INTEGER NOT NULL, type TEXT NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(host,sid,seq)) WITHOUT ROWID")
            database.execSQL("CREATE INDEX IF NOT EXISTS user_boundaries ON events(host,sid,type,seq)")
            database.execSQL("CREATE INDEX IF NOT EXISTS message_boundaries ON events(host,sid,seq) WHERE type IN ('user/message','assistant/message')")
            database.execSQL("CREATE TABLE IF NOT EXISTS staged (request TEXT NOT NULL, host TEXT NOT NULL, sid TEXT NOT NULL, seq INTEGER NOT NULL, type TEXT NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(request,seq)) WITHOUT ROWID")
            database.execSQL("CREATE TABLE IF NOT EXISTS ranges (host TEXT NOT NULL, sid TEXT NOT NULL, start_seq INTEGER NOT NULL, end_seq INTEGER NOT NULL, PRIMARY KEY(host,sid,start_seq)) WITHOUT ROWID")
            if (database.version < 2) {
                // One-time metadata-only migration; normal viewport reads never scan the journal.
                database.beginTransaction()
                try {
                    database.rawQuery("SELECT host,sid,seq FROM events ORDER BY host,sid,seq", null).use { rows ->
                        var host: String? = null; var sid: String? = null; var first = 0L; var last = 0L
                        fun flush() { if (host != null) database.execSQL("INSERT OR REPLACE INTO ranges VALUES(?,?,?,?)", arrayOf(host,sid,first,last)) }
                        while (rows.moveToNext()) {
                            val h=rows.getString(0); val session=rows.getString(1); val seq=rows.getLong(2)
                            if (host != h || sid != session || seq != last+1) { flush(); host=h; sid=session; first=seq }
                            last=seq
                        }
                        flush()
                    }
                    database.version = 2
                    database.setTransactionSuccessful()
                } finally { database.endTransaction() }
            }
            // The exclusive persistent lease means no other live writer owns these crash leftovers.
            database.delete("staged", null, null)
        }
    } catch (failure: Exception) {
        try { opening?.close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
        try { lease.close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
        throw failure
    }

    fun store(host: String, sid: String, events: List<SessionEventEnvelope>) = synchronized(lock) {
        transaction {
            events.forEach { insert("events", host, sid, encodeHistoryEvent(it)) }
            var first: Long? = null; var last = 0L
            for (seq in events.asSequence().map { it.seq }.distinct().sorted()) {
                if (first != null && seq != last+1) { mergeRange(host,sid,first,last); first=null }
                if (first == null) first=seq
                last=seq
            }
            first?.let { mergeRange(host,sid,it,last) }
        }
    }

    fun storeEncoded(host: String,sid: String,event: EncodedHistoryEvent) = synchronized(lock) {
        transaction {
            insert("events",host,sid,event)
            mergeRange(host,sid,event.seq,event.seq)
        }
    }

    fun appendStage(request: String, host: String, sid: String, events: List<EncodedHistoryEvent>) = synchronized(lock) {
        transaction { events.forEach { insert("staged", host, sid, it, request) } }
    }

    fun commitStage(request: String) = synchronized(lock) {
        transaction {
            db.execSQL("INSERT OR REPLACE INTO events(host,sid,seq,type,payload) SELECT host,sid,seq,type,payload FROM staged WHERE request=?", arrayOf(request))
            db.rawQuery("SELECT host,sid,seq FROM staged WHERE request=? ORDER BY seq", arrayOf(request)).use { rows ->
                var host: String? = null; var sid: String? = null; var first: Long? = null; var last=0L
                while (rows.moveToNext()) {
                    val seq=rows.getLong(2)
                    if (first != null && seq != last+1) { mergeRange(host!!,sid!!,first,last); first=null }
                    if (first == null) { first=seq; host=rows.getString(0); sid=rows.getString(1) }
                    last=seq
                }
                first?.let { mergeRange(host!!,sid!!,it,last) }
            }
            db.delete("staged", "request=?", arrayOf(request))
        }
    }

    fun abortStage(request: String) = synchronized(lock) { db.delete("staged", "request=?", arrayOf(request)); Unit }

    fun removeAfter(host: String, sid: String, throughSeq: Long) = synchronized(lock) {
        transaction {
            db.delete("events", "host=? AND sid=? AND seq>?", args(host, sid, throughSeq))
            db.delete("ranges", "host=? AND sid=? AND start_seq>?", args(host,sid,throughSeq))
            db.execSQL("UPDATE ranges SET end_seq=? WHERE host=? AND sid=? AND end_seq>?", arrayOf(throughSeq,host,sid,throughSeq))
        }
    }

    fun latest(host: String, sid: String, maxMessages: Int): HistoryDiskPage = synchronized(lock) {
        require(maxMessages > 0)
        val anchor = one("SELECT MAX(seq) FROM events WHERE host=? AND sid=?", args(host,sid)) ?: return@synchronized empty()
        val range = component(host,sid,anchor)
        if (sid.startsWith("reader-v1\u0000")) return@synchronized readerWindow(host, sid, anchor, range, WindowDirection.BACKWARD)
        val logicalStart = nthMessageBefore(host,sid,anchor,range.first,maxMessages-1) ?: range.first
        val rawStart = nthEventBefore(host,sid,anchor,range.first,eventBudget(maxMessages)-1) ?: range.first
        readPage(host,sid,maxOf(logicalStart,rawStart),range.last,range,maxMessages,anchor,WindowDirection.BACKWARD)
    }

    fun older(host: String, sid: String, beforeSeq: Long, maxMessages: Int): HistoryDiskPage = synchronized(lock) {
        require(maxMessages > 0)
        val anchor = one("SELECT MAX(seq) FROM events WHERE host=? AND sid=? AND seq<?", args(host,sid,beforeSeq))
            ?: return@synchronized empty(hasNewer = exists(host,sid))
        val range = component(host,sid,anchor)
        if (sid.startsWith("reader-v1\u0000")) return@synchronized readerWindow(host, sid, anchor, range, WindowDirection.BACKWARD)
        val logicalStart = nthMessageBefore(host,sid,anchor,range.first,maxMessages-1) ?: range.first
        val rawStart = nthEventBefore(host,sid,anchor,range.first,eventBudget(maxMessages)-1) ?: range.first
        readPage(host,sid,maxOf(logicalStart,rawStart),anchor,range,maxMessages,anchor,WindowDirection.BACKWARD)
    }

    fun newer(host: String, sid: String, afterSeq: Long, maxMessages: Int): HistoryDiskPage = synchronized(lock) {
        require(maxMessages > 0)
        val anchor = one("SELECT MIN(seq) FROM events WHERE host=? AND sid=? AND seq>?", args(host,sid,afterSeq))
            ?: return@synchronized empty(hasOlder = exists(host,sid))
        val range = component(host,sid,anchor)
        if (sid.startsWith("reader-v1\u0000")) return@synchronized readerWindow(host, sid, anchor, range, WindowDirection.FORWARD)
        val logicalEnd = endAfterMessages(host,sid,anchor,range.last,maxMessages)
        val rawEnd = nthEventAfter(host,sid,anchor,range.last,eventBudget(maxMessages)-1) ?: range.last
        readPage(host,sid,anchor,minOf(logicalEnd,rawEnd),range,maxMessages,anchor,WindowDirection.FORWARD)
    }

    fun around(host: String, sid: String, seq: Long, maxMessages: Int): HistoryDiskPage = synchronized(lock) {
        require(maxMessages > 0)
        val anchor = one("SELECT MAX(seq) FROM events WHERE host=? AND sid=? AND seq<=?", args(host,sid,seq))
            ?: one("SELECT MIN(seq) FROM events WHERE host=? AND sid=?", args(host,sid)) ?: return@synchronized empty()
        val range = component(host,sid,anchor)
        if (sid.startsWith("reader-v1\u0000")) return@synchronized readerWindow(host, sid, anchor, range, WindowDirection.AROUND)
        val logicalStart = nthMessageBefore(host,sid,anchor,range.first,(maxMessages-1)/2) ?: range.first
        val rawStart = nthEventBefore(host,sid,anchor,range.first,eventBudget(maxMessages)/2) ?: range.first
        val start = maxOf(logicalStart,rawStart)
        val logicalEnd = endAfterMessages(host,sid,start,range.last,maxMessages)
        val rawEnd = nthEventAfter(host,sid,start,range.last,eventBudget(maxMessages)-1) ?: range.last
        readPage(host,sid,start,minOf(logicalEnd,rawEnd),range,maxMessages,anchor,WindowDirection.AROUND)
    }

    /** Read a complete inclusive loaded interval. The wire/page budget does not cap retained history.
     * Missing edges or gaps return null; selecting a nearby component would fabricate continuity.
     */
    fun range(host: String, sid: String, fromSeq: Long, throughSeq: Long): HistoryDiskPage? = synchronized(lock) {
        require(fromSeq >= 0 && fromSeq <= throughSeq)
        val covered = db.rawQuery(
            "SELECT end_seq FROM ranges WHERE host=? AND sid=? AND start_seq<=? ORDER BY start_seq DESC LIMIT 1",
            args(host, sid, fromSeq),
        ).use { it.moveToFirst() && it.getLong(0) >= throughSeq }
        if (!covered) return@synchronized null
        val legacy = one("SELECT 1 FROM events WHERE host=? AND sid=? AND type='assistant/chunk' AND seq BETWEEN ? AND ? LIMIT 1",
            args(host, sid, fromSeq, throughSeq)) != null
        read(host, sid, fromSeq, throughSeq, legacyUnbounded = legacy)
    }

    /** Modern durable records stand alone. Legacy chunks need context before and after the raw
     * window; retain those complete user turns rather than showing a false/truncated message. */
    private enum class WindowDirection { BACKWARD, FORWARD, AROUND }
    private data class RecordSize(val seq: Long,val type: String,val bytes: Long)

    /** Reader rows count raw positions, including skips. Never widen to a whole long turn. */
    private fun readerWindow(host: String, sid: String, anchor: Long, range: LongRange,
                             direction: WindowDirection): HistoryDiskPage {
        val count = com.labteto.dshmobile.core.wire.READER_MAX_ROWS.toLong()
        val target = com.labteto.dshmobile.core.wire.READER_MAX_BYTES.toLong()
        val start = maxOf(range.first, when (direction) {
            WindowDirection.BACKWARD -> anchor - count + 1
            WindowDirection.FORWARD -> anchor
            WindowDirection.AROUND -> anchor - count / 2
        })
        val end = minOf(range.last, if (direction == WindowDirection.BACKWARD) anchor else start + count - 1)
        val sizes = ArrayList<Pair<Long, Long>>()
        db.rawQuery("SELECT seq,length(payload) FROM events WHERE host=? AND sid=? AND seq BETWEEN ? AND ? ORDER BY seq",
            args(host, sid, start, end)).use { rows ->
            while (rows.moveToNext()) sizes += rows.getLong(0) to rows.getLong(1)
        }
        val pivot = sizes.indexOfFirst { it.first == anchor }
        check(pivot >= 0)
        var first = pivot
        var last = pivot
        var bytes = sizes[pivot].second
        // The anchor is indivisible; an oversized row is retained alone, without truncating text.
        while (bytes < target) {
            val left = first > 0 && bytes + sizes[first - 1].second <= target
            val right = last < sizes.lastIndex && bytes + sizes[last + 1].second <= target
            if (!left && !right) break
            if (left && (!right || anchor - sizes[first - 1].first <= sizes[last + 1].first - anchor)) {
                first--; bytes += sizes[first].second
            } else { last++; bytes += sizes[last].second }
        }
        return read(host, sid, sizes[first].first, sizes[last].first)
    }

    private fun readPage(host: String,sid: String,start: Long,end: Long,range: LongRange,
                         messages: Int,anchor: Long,direction: WindowDirection): HistoryDiskPage {
        val contextStart = nthUserBefore(host,sid,start,range.first,0) ?: range.first
        val contextEnd = nextUser(host,sid,end,range.last)?.minus(1) ?: range.last
        val legacy = one("SELECT 1 FROM events WHERE host=? AND sid=? AND type='assistant/chunk' AND seq BETWEEN ? AND ? LIMIT 1",args(host,sid,contextStart,contextEnd)) != null
        if (legacy) return read(host,sid,contextStart,contextEnd,legacyUnbounded=true)
        val bytes = byteWindow(host,sid,start,end,anchor,byteBudget(messages),direction)
        return read(host,sid,bytes.first,bytes.last)
    }

    /** Select only seq/length metadata before materializing JSON. Serialized bytes are a working
     * window target, not a content limit: an oversized individual event is always kept intact. */
    private fun byteWindow(host: String,sid: String,start: Long,end: Long,anchor: Long,
                           target: Long,direction: WindowDirection): LongRange {
        if (direction != WindowDirection.AROUND) {
            val order=if(direction==WindowDirection.FORWARD) "ASC" else "DESC"
            var first: Long?=null; var last=0L; var bytes=0L; var hasContent=false
            db.rawQuery("SELECT seq,type,length(payload) FROM events WHERE host=? AND sid=? AND seq BETWEEN ? AND ? ORDER BY seq $order",args(host,sid,start,end)).use { rows ->
                while (rows.moveToNext()) {
                    val seq=rows.getLong(0); val content=isHistoryContentEventType(rows.getString(1)); val size=rows.getLong(2)
                    // Small framing rows must not displace the only actual content record. The
                    // first content record may exceed the working target, intact with its frame.
                    if (first!=null && bytes+size>target && (hasContent || !content)) break
                    if (first==null) first=seq
                    last=seq; bytes+=size; hasContent=hasContent || content
                    if (bytes>=target && hasContent) break
                }
            }
            val edge=checkNotNull(first) { "Local history byte window is empty" }
            return minOf(edge,last)..maxOf(edge,last)
        }
        // Candidate count is already constrained by the raw-event viewport. This list contains
        // small metadata pairs only, never payloads or a session-sized in-memory event index.
        val records=ArrayList<RecordSize>()
        db.rawQuery("SELECT seq,type,length(payload) FROM events WHERE host=? AND sid=? AND seq BETWEEN ? AND ? ORDER BY seq",args(host,sid,start,end)).use { rows ->
            while(rows.moveToNext()) records+=RecordSize(rows.getLong(0),rows.getString(1),rows.getLong(2))
        }
        val index=records.indexOfFirst { it.seq==anchor }
        check(index>=0) { "Requested history anchor is outside its byte window" }
        var bytes=records[index].bytes
        var hasContent=isHistoryContentEventType(records[index].type)
        var first=index; var last=index; var left=index-1; var right=index+1
        while(bytes<target || !hasContent) {
            val canLeft=left>=0 && (bytes+records[left].bytes<=target || (!hasContent && isHistoryContentEventType(records[left].type)))
            val canRight=right<records.size && (bytes+records[right].bytes<=target || (!hasContent && isHistoryContentEventType(records[right].type)))
            if(!canLeft && !canRight) break
            if(canLeft && (!canRight || anchor-records[left].seq<=records[right].seq-anchor)) {
                bytes+=records[left].bytes; hasContent=hasContent || isHistoryContentEventType(records[left].type); first=left; left--
            } else {
                bytes+=records[right].bytes; hasContent=hasContent || isHistoryContentEventType(records[right].type); last=right; right++
            }
        }
        return records[first].seq..records[last].seq
    }

    fun sessionCount(): Int = synchronized(lock) {
        one("SELECT COUNT(*) FROM (SELECT host,sid FROM events GROUP BY host,sid)", emptyArray())!!.toInt()
    }

    override fun close() = synchronized(lock) { try { db.close() } finally { lease.close() } }

    private fun insert(table: String, host: String, sid: String, event: EncodedHistoryEvent, request: String? = null) {
        val payload = event.payload
        if (request == null) {
            db.execSQL("INSERT OR REPLACE INTO events(host,sid,seq,type,payload) VALUES(?,?,?,?,?)", arrayOf(host,sid,event.seq,event.type,payload))
        } else {
            check(table == "staged")
            db.execSQL("INSERT OR REPLACE INTO staged(request,host,sid,seq,type,payload) VALUES(?,?,?,?,?,?)", arrayOf(request,host,sid,event.seq,event.type,payload))
        }
    }

    private fun component(host: String, sid: String, anchor: Long): LongRange =
        db.rawQuery("SELECT start_seq,end_seq FROM ranges WHERE host=? AND sid=? AND start_seq<=? ORDER BY start_seq DESC LIMIT 1",args(host,sid,anchor)).use {
            check(it.moveToFirst() && it.getLong(1) >= anchor) { "Local history range index is inconsistent" }
            it.getLong(0)..it.getLong(1)
        }

    private fun mergeRange(host: String,sid: String,first: Long,last: Long) {
        var start=first; var end=last
        db.rawQuery("SELECT start_seq,end_seq FROM ranges WHERE host=? AND sid=? AND start_seq<=? ORDER BY start_seq DESC LIMIT 1",args(host,sid,start)).use {
            if (it.moveToFirst() && it.getLong(1) >= start-1) {
                start=it.getLong(0); end=maxOf(end,it.getLong(1))
                db.delete("ranges","host=? AND sid=? AND start_seq=?",args(host,sid,start))
            }
        }
        while (true) {
            val next=db.rawQuery("SELECT start_seq,end_seq FROM ranges WHERE host=? AND sid=? AND start_seq>? AND start_seq<=? ORDER BY start_seq LIMIT 1",args(host,sid,start,end+1)).use {
                if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null
            } ?: break
            end=maxOf(end,next.second)
            db.delete("ranges","host=? AND sid=? AND start_seq=?",args(host,sid,next.first))
        }
        db.execSQL("INSERT OR REPLACE INTO ranges VALUES(?,?,?,?)",arrayOf(host,sid,start,end))
    }

    private fun nthUserBefore(host: String,sid: String,at: Long,min: Long,offset: Int): Long? =
        one("SELECT seq FROM events WHERE host=? AND sid=? AND type='user/message' AND seq BETWEEN ? AND ? ORDER BY seq DESC LIMIT 1 OFFSET ?",args(host,sid,min,at,offset))
    private fun nextUser(host: String,sid: String,after: Long,max: Long): Long? =
        one("SELECT MIN(seq) FROM events WHERE host=? AND sid=? AND type='user/message' AND seq>? AND seq<=?",args(host,sid,after,max))
    private fun nthMessageBefore(host: String,sid: String,at: Long,min: Long,offset: Int): Long? =
        one("SELECT seq FROM events INDEXED BY message_boundaries WHERE host=? AND sid=? AND type IN ('user/message','assistant/message') AND seq BETWEEN ? AND ? ORDER BY seq DESC LIMIT 1 OFFSET ?",args(host,sid,min,at,offset))
    private fun endAfterMessages(host: String,sid: String,start: Long,max: Long,messages: Int): Long =
        one("SELECT seq FROM events INDEXED BY message_boundaries WHERE host=? AND sid=? AND type IN ('user/message','assistant/message') AND seq BETWEEN ? AND ? ORDER BY seq LIMIT 1 OFFSET ?",args(host,sid,start,max,messages))?.minus(1) ?: max
    private fun nthEventBefore(host: String,sid: String,at: Long,min: Long,offset: Long): Long? =
        one("SELECT seq FROM events WHERE host=? AND sid=? AND seq BETWEEN ? AND ? ORDER BY seq DESC LIMIT 1 OFFSET ?",args(host,sid,min,at,offset))
    private fun nthEventAfter(host: String,sid: String,start: Long,max: Long,offset: Long): Long? =
        one("SELECT seq FROM events WHERE host=? AND sid=? AND seq BETWEEN ? AND ? ORDER BY seq LIMIT 1 OFFSET ?",args(host,sid,start,max,offset))
    // Existing history-page raw budget, used only as a RAM viewport, never disk eviction.
    private fun eventBudget(messages: Int): Long = HistoryWindowBudget.rawEvents(messages)
    private fun byteBudget(messages: Int): Long = HistoryWindowBudget.payloadBytes(messages)

    private fun read(host: String,sid: String,start: Long,end: Long,legacyUnbounded: Boolean=false): HistoryDiskPage {
        val payloadBytes=one("SELECT COALESCE(SUM(length(payload)),0) FROM events WHERE host=? AND sid=? AND seq BETWEEN ? AND ?",args(host,sid,start,end)) ?: 0
        val events = ArrayList<SessionEventEnvelope>()
        db.rawQuery("SELECT seq,length(payload) FROM events WHERE host=? AND sid=? AND seq BETWEEN ? AND ? ORDER BY seq", args(host,sid,start,end)).use { rows ->
            while (rows.moveToNext()) {
                val seq = rows.getLong(0)
                val length = rows.getInt(1)
                // Android CursorWindow has its own row-size ceiling. Read the BLOB in pieces rather
                // than refusing/truncating a legitimate event larger than a cursor window.
                val output = ByteArrayOutputStream()
                var offset = 0
                while (offset < length) {
                    db.rawQuery("SELECT substr(payload,?,65536) FROM events WHERE host=? AND sid=? AND seq=?",arrayOf((offset+1).toString(),host,sid,seq.toString())).use { part ->
                        check(part.moveToFirst()) { "Session history payload disappeared" }
                        val bytes = part.getBlob(0)
                        check(bytes.isNotEmpty()) { "Session history payload is incomplete" }
                        output.write(bytes); offset += bytes.size
                    }
                }
                events += WireJson.decodeFromString(CachedEvent.serializer(),output.toByteArray().toString(Charsets.UTF_8)).envelope()
            }
        }
        return HistoryDiskPage(events,
            one("SELECT 1 FROM events WHERE host=? AND sid=? AND seq<? LIMIT 1",args(host,sid,start)) != null,
            one("SELECT 1 FROM events WHERE host=? AND sid=? AND seq>? LIMIT 1",args(host,sid,end)) != null,legacyUnbounded,payloadBytes)
    }

    private fun exists(host: String,sid: String) = one("SELECT 1 FROM events WHERE host=? AND sid=? LIMIT 1",args(host,sid)) != null
    private fun one(sql: String,args: Array<String>): Long? = db.rawQuery(sql,args).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    private fun args(vararg values: Any): Array<String> = values.map { it.toString() }.toTypedArray()
    private fun empty(hasOlder: Boolean=false,hasNewer: Boolean=false) = HistoryDiskPage(emptyList(),hasOlder,hasNewer)
    private inline fun transaction(block: () -> Unit) {
        db.beginTransaction()
        try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
}
