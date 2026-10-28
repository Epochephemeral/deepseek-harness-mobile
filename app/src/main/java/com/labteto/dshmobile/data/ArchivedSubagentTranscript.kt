package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.session.AssistantLiveState
import com.labteto.dshmobile.core.session.ChunkRows
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.EventFold
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.session.isHistoryContentEventType
import com.labteto.dshmobile.core.storage.DiskCacheStore
import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RemoteStream
import com.labteto.dshmobile.core.wire.RemoteStreamException
import com.labteto.dshmobile.core.wire.RemoteStreamMux
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.core.wire.dto.SessionAddress
import com.labteto.dshmobile.core.wire.dto.SessionFollowFrame
import com.labteto.dshmobile.core.wire.dto.SessionFollowFrameSerializer
import com.labteto.dshmobile.core.wire.dto.SessionFollowRequest
import com.labteto.dshmobile.core.wire.dto.SessionPageRequest
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Raw node ordering is unchanged; these flags describe paging, not a different transcript model. */
data class ArchivedTranscriptState(
    val snapshot: ConversationSnapshot? = null,
    val loadingOlder: Boolean = false,
    val loadingNewer: Boolean = false,
    val loadOlderFailed: Boolean = false,
    val loadNewerFailed: Boolean = false,
    val hasNewer: Boolean = false,
    val followFailed: Boolean = false,
)

/**
 * A subagent view owns only a disk-backed viewport, not a second unbounded session journal.
 * The caller owns the follow coroutine; close cancels its logical stream and releases preview data.
 * isCurrent must be a non-blocking generation/address check, not a callback taking an outer lock.
 */
class ArchivedSubagentTranscript(
    private val cache: HistoryCache,
    diskStore: DiskCacheStore? = null,
    private val api: DshApiClient,
    private val mux: RemoteStreamMux,
    host: String,
    parentId: String,
    private val childId: String,
    mode: String,
    private val isCurrent: () -> Boolean,
    private val onFailure: (Throwable) -> Unit,
) : Closeable {
    private val address = SessionAddress.Subagent(parentSessionId = parentId, childSessionId = childId, mode = mode)
    // Separate the archive namespace from every ordinary-session host key, then length-prefix all
    // address components. Identical child IDs under different parents/modes cannot alias each other.
    val archiveHost: String = "\u0000subagent-host:${host.length}:$host"
    val archiveKey: String = listOf(parentId, childId, mode).joinToString("") { "${it.length}:$it" }
    private val lock = Any()
    private val live = AssistantLiveState(diskStore)
    private val events = ArrayList<SessionEventEnvelope>()
    private var revision = 0L
    private var foldedRevision = -1L
    private var folded: ConversationSnapshot? = null
    private var closed = false
    private var started = false
    private var stream: RemoteStream? = null
    private val probes = HashSet<RemoteStream>()
    private var cursor: Int? = null
    private var latestSeq = -1L
    private var knownFirstSeq: Long? = null
    private var remoteHasOlder = false
    private var remoteOldest: Long? = null
    private var atTail = true
    private var anchor: Long? = null
    private var legacy = false
    private var currentWindowBytes = 0L
    private var oversizedWindow = false
    private var currentMessages = 0
    private var currentWindowTicket = cache.ticket()
    private var hasOlder = false
    private var hasNewer = false
    private var request = 0L
    private val mutableState = MutableStateFlow(ArchivedTranscriptState())
    val state: StateFlow<ArchivedTranscriptState> = mutableState.asStateFlow()
    private fun current() = !closed && isCurrent()

    suspend fun follow() = withContext(Dispatchers.IO) {
        val ticket = synchronized(lock) {
            check(!started) { "Subagent follow already started" }
            started = true
            if (!current()) return@withContext
            cache.ticket()
        }
        var following: RemoteStream? = null
        try {
            synchronized(lock) {
                cache.atTicket(ticket) {
                    val cached = cache.latestHistory(archiveHost, archiveKey, WINDOW_MESSAGES, ticket)
                    if (current() && cached.events.isNotEmpty()) { install(cached); publish() }
                }
            }
            val active = mux.open("session/follow", followArgs(assistant = true))
            following = active
            synchronized(lock) {
                if (!current()) throw CancellationException("Retired subagent transcript")
                stream = active
            }
            while (true) {
                val item = active.receive() ?: break
                if (!synchronized(lock) { current() }) throw CancellationException("Retired subagent transcript")
                acceptFrame(WireJson.decodeFromJsonElement(SessionFollowFrameSerializer, item), ticket)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val report = synchronized(lock) {
                if (current()) { mutableState.value = mutableState.value.copy(followFailed = true); true } else false
            }
            if (report) onFailure(failure)
        } finally {
            following?.cancel()
            synchronized(lock) { if (stream === following) stream = null }
        }
    }

    /** Shared by the follow decoder and real-SQLite fixtures; all DB work stays off the UI thread. */
    internal suspend fun acceptFrame(frame: SessionFollowFrame, ticket: Long = cache.ticket()) = withContext(Dispatchers.IO) {
        if (!synchronized(lock) { current() }) return@withContext
        when (frame) {
            is SessionFollowFrame.Snapshot -> {
                var more = frame.hasMore
                if (frame.hasMore && frame.cursor >= 0 && ticket == cache.ticket()) {
                    val result = fetchStagedHistoryPage(api, cache, archiveHost, archiveKey, ticket,
                        SessionPageRequest(address, frame.cursor, maxMessages = PAGE_MESSAGES)) { _, commit ->
                        synchronized(lock) { current() && ticket == cache.ticket() && commit() }
                    }
                    when (result) {
                        is RpcResult.Err -> throw RemoteStreamException(result.error, carrier = false)
                        is RpcResult.Ok -> if (result.value.committed) more = result.value.metadata.hasMore
                    }
                }
                synchronized(lock) {
                    if (!current()) return@synchronized
                    cursor = frame.cursor
                    latestSeq = frame.cursor.toLong()
                    remoteHasOlder = more
                    val installed = cache.atTicket(ticket) {
                        check(cache.removeHistoryAfter(archiveHost, archiveKey, frame.cursor.toLong(), ticket))
                        check(cache.storeEvents(archiveHost, archiveKey, ticket, expand(frame.records)))
                        val tail = cache.latestHistory(archiveHost, archiveKey, WINDOW_MESSAGES, ticket)
                        remoteOldest = tail.firstSeq
                        val visible = anchor?.takeIf { !atTail }?.let {
                            cache.historyAround(archiveHost, archiveKey, it, WINDOW_MESSAGES, ticket)
                        }?.takeIf { it.events.isNotEmpty() } ?: tail
                        install(visible)
                        true
                    } == true
                    if (!installed) {
                        // A clear during RPC owns the new ticket: never refill it with the old cut.
                        // Keep a reader's existing viewport; a maxMessages=1 baseline can paint an
                        // otherwise empty view without being written back to the cleared archive.
                        var changed = events.removeAll { it.seq > frame.cursor }
                        if (changed) revision++
                        if (events.isEmpty()) {
                            events.addAll(expand(frame.records)); revision++
                            events.firstOrNull()?.seq?.let { knownFirstSeq = knownFirstSeq?.let { first -> minOf(first, it) } ?: it }
                            currentWindowTicket = ticket
                            changed = true
                        }
                        if (changed) measureWindow()
                        hasNewer = (events.lastOrNull()?.seq ?: -1L) < latestSeq
                        hasOlder = hasOlder || more
                    }
                    live.seed(frame.assistantStream)
                    mutableState.value = mutableState.value.copy(followFailed = false)
                    publish()
                }
            }
            is SessionFollowFrame.Entry -> synchronized(lock) {
                if (!current()) return@synchronized
                for (event in expand(listOf(frame.record))) appendLive(event)
                publish()
            }
            is SessionFollowFrame.AssistantStream -> synchronized(lock) {
                if (current()) { live.accept(frame.frame); publish() }
            }
        }
    }

    /** Persist once, then update the small visible vector without re-decoding its entire DB page. */
    private fun appendLive(event: SessionEventEnvelope) {
        val encodedBytes = cache.storeLiveEvent(archiveHost, archiveKey, event)
        val ticket = cache.ticket()
        val index = events.indexOfFirst { it.seq == event.seq }
        val canAppend = events.isEmpty() || !hasNewer && event.seq == events.last().seq + 1 &&
            (atTail || currentWindowTicket == ticket)
        var changed = false
        if (index >= 0) {
            val old = events[index]
            currentWindowBytes += encodedBytes - encodeHistoryEvent(old).payload.size.toLong()
            if (currentWindowBytes <= HistoryWindowBudget.payloadBytes(WINDOW_MESSAGES)) oversizedWindow = false
            currentMessages += messageCount(event) - messageCount(old)
            events[index] = event
            if (old.type == "assistant/chunk" || event.type == "assistant/chunk") legacy = events.any { it.type == "assistant/chunk" }
            changed = true
        } else if (canAppend) {
            if (events.isEmpty()) currentWindowTicket = ticket
            events.add(event)
            currentWindowBytes += encodedBytes
            currentMessages += messageCount(event)
            legacy = legacy || event.type == "assistant/chunk"
            changed = true
        }
        latestSeq = maxOf(latestSeq, event.seq)
        val data = event.data as? JsonObject
        live.acceptDurable(event.type, data?.get("turn")?.jsonPrimitive?.intOrNull,
            data?.get("step")?.jsonPrimitive?.intOrNull, event.seq, event.surfaceOp)
        if (changed) {
            revision++
            // A legitimate giant record stays intact with its framing. Once installed, another
            // step/end must not decode that same giant again; new content can change the byte cut.
            val bytePressure = currentWindowBytes > HistoryWindowBudget.payloadBytes(WINDOW_MESSAGES) &&
                (!oversizedWindow || isHistoryContentEventType(event.type))
            val overHighWater = currentMessages > WINDOW_MESSAGES || events.size.toLong() > HistoryWindowBudget.rawEvents(WINDOW_MESSAGES) || bytePressure
            if (overHighWater && (!legacy || event.type == "user/message")) {
                cache.atTicket(ticket) {
                    val readerAnchor = anchor ?: events.firstOrNull()?.seq
                    val page = if (atTail) cache.latestHistory(archiveHost, archiveKey, PAGE_MESSAGES, ticket)
                        else readerAnchor?.let { cache.historyAround(archiveHost, archiveKey, it, WINDOW_MESSAGES, ticket) }
                    if (page != null && page.events.isNotEmpty() &&
                        (atTail || readerAnchor != null && readerAnchor in page.firstSeq!!..page.lastSeq!!)) install(page)
                }
            }
        }
        hasNewer = hasNewer || latestSeq > (events.lastOrNull()?.seq ?: -1L)
    }

    private fun messageCount(event: SessionEventEnvelope) =
        if (HistoryWindowBudget.isMessageBoundary(event.type)) 1 else 0

    private fun measureWindow() {
        currentWindowBytes = events.sumOf { encodeHistoryEvent(it).payload.size.toLong() }
        oversizedWindow = currentWindowBytes > HistoryWindowBudget.payloadBytes(WINDOW_MESSAGES)
        currentMessages = events.sumOf(::messageCount)
        legacy = events.any { it.type == "assistant/chunk" }
    }

    fun updateViewport(anchor: Long?, atTail: Boolean) = synchronized(lock) {
        this.anchor = anchor
        this.atTail = atTail && !hasNewer
    }

    private data class Read(val token: Long, val ticket: Long, val anchor: Long, val older: Boolean, val cursor: Int?)

    suspend fun loadOlder(): Boolean = load(older = true)
    suspend fun loadNewer(): Boolean = load(older = false)

    private suspend fun load(older: Boolean): Boolean = withContext(Dispatchers.IO) {
        val read = synchronized(lock) {
            val state = mutableState.value
            if (!current() || state.loadingOlder || state.loadingNewer) return@withContext false
            val edge = (if (older) events.firstOrNull() else events.lastOrNull())?.seq ?: return@withContext false
            if (older && !hasOlder || !older && !hasNewer) return@withContext false
            mutableState.value = if (older) state.copy(loadingOlder = true, loadOlderFailed = false)
                else state.copy(loadingNewer = true, loadNewerFailed = false)
            Read(++request, cache.ticket(), edge, older, cursor)
        }
        try {
            if (synchronized(lock) { installAdjacent(read) }) return@withContext true
            val cut = if (read.cursor != null &&
                (if (read.older) read.anchor <= read.cursor.toLong() + 1 else latestKnown() <= read.cursor)) read.cursor
                else readFreshCursor()
            synchronized(lock) { if (valid(read)) latestSeq = maxOf(latestSeq, cut.toLong()) }
            var before = if (older) read.anchor else minOf(cut.toLong() + 1, read.anchor + WINDOW_EVENTS + 1)
            while (true) {
                if (!synchronized(lock) { valid(read) }) return@withContext false
                var progress: Long? = null
                val result = fetchStagedHistoryPage(api, cache, archiveHost, archiveKey, read.ticket,
                    SessionPageRequest(address, cut, before.takeIf { it <= Int.MAX_VALUE }?.toInt(), PAGE_MESSAGES)) { summary, commit ->
                    synchronized(lock) {
                        if (!valid(read)) false else {
                            val first = summary.firstSeq
                            if (first == null || summary.lastSeq == null || first >= before) {
                                throw IOException("The server returned no progress while loading subagent history")
                            }
                            progress = first
                            val committed = commit()
                            if (committed && read.older) { remoteHasOlder = summary.metadata.hasMore; remoteOldest = first }
                            committed
                        }
                    }
                }
                when (result) {
                    is RpcResult.Err -> throw RemoteStreamException(result.error, carrier = false)
                    is RpcResult.Ok -> if (!result.value.committed) return@withContext false
                }
                if (synchronized(lock) { installAdjacent(read) }) return@withContext true
                val first = progress ?: throw IOException("No adjacent subagent history was returned")
                if (older || first <= read.anchor + 1) throw IOException("The server page did not cover the adjacent subagent history event")
                before = first
            }
            @Suppress("UNREACHABLE_CODE") false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val report = synchronized(lock) {
                if (valid(read, checkTicket = false)) {
                    mutableState.value = if (older) mutableState.value.copy(loadOlderFailed = true)
                        else mutableState.value.copy(loadNewerFailed = true)
                    true
                } else false
            }
            if (report) onFailure(failure)
            false
        } finally {
            synchronized(lock) {
                if (request == read.token) mutableState.value = mutableState.value.copy(loadingOlder = false, loadingNewer = false)
            }
        }
    }

    private fun valid(read: Read, checkTicket: Boolean = true): Boolean =
        current() && read.token == request && read.cursor == cursor &&
            (if (read.older) events.firstOrNull()?.seq else events.lastOrNull()?.seq) == read.anchor &&
            (!checkTicket || read.ticket == cache.ticket())

    private fun installAdjacent(read: Read): Boolean {
        if (!valid(read)) return false
        return cache.atTicket(read.ticket) {
            val page = if (read.older) cache.olderHistory(archiveHost, archiveKey, read.anchor, PAGE_MESSAGES, read.ticket)
                else cache.newerHistory(archiveHost, archiveKey, read.anchor, PAGE_MESSAGES, read.ticket)
            if (page.events.isEmpty()) return@atTicket false
            if (read.older && page.lastSeq != read.anchor - 1 || !read.older && page.firstSeq != read.anchor + 1) return@atTicket false
            val next = cache.historyAround(archiveHost, archiveKey,
                if (read.older) read.anchor - 1 else read.anchor + 1, WINDOW_MESSAGES, read.ticket)
            atTail = false
            anchor = if (read.older) read.anchor - 1 else read.anchor + 1
            install(next)
            publish()
            true
        } ?: false
    }

    suspend fun showLatest(): Boolean = withContext(Dispatchers.IO) {
        val token = synchronized(lock) {
            if (!current()) return@withContext false
            mutableState.value = mutableState.value.copy(loadingOlder = false, loadingNewer = false)
            ++request
        }
        val ticket = cache.ticket()
        try {
            fun installLatest(): Boolean = synchronized(lock) {
                if (!current() || token != request) false else cache.atTicket(ticket) {
                    val page = cache.latestHistory(archiveHost, archiveKey, WINDOW_MESSAGES, ticket)
                    if (page.events.isEmpty() || (page.lastSeq ?: -1L) < latestSeq) false else {
                        atTail = true; anchor = page.lastSeq
                        install(page)
                        mutableState.value = mutableState.value.copy(loadNewerFailed = false, loadingNewer = false, loadingOlder = false)
                        publish()
                        true
                    }
                } ?: false
            }
            if (installLatest()) return@withContext true
            val cut = readFreshCursor()
            when (val result = fetchStagedHistoryPage(api, cache, archiveHost, archiveKey, ticket,
                SessionPageRequest(address, cut, maxMessages = PAGE_MESSAGES)) { _, commit ->
                synchronized(lock) { current() && token == request && ticket == cache.ticket() && commit() }
            }) {
                is RpcResult.Err -> throw RemoteStreamException(result.error, carrier = false)
                is RpcResult.Ok -> result.value.committed && installLatest()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (synchronized(lock) { current() && token == request }) onFailure(failure); false }
    }

    /** An explicit gap/latest request may need a newer server cut than the long-lived follow's. */
    private suspend fun readFreshCursor(): Int {
        val probe = mux.open("session/follow", followArgs(assistant = false))
        try {
            synchronized(lock) {
                if (!current()) throw CancellationException("Retired subagent history read")
                probes += probe
            }
            val item = probe.receive() ?: throw IOException("Subagent follow ended before its snapshot")
            val opening = WireJson.decodeFromJsonElement(SessionFollowFrameSerializer, item) as? SessionFollowFrame.Snapshot
                ?: throw IOException("Subagent follow did not open with a snapshot")
            return opening.cursor
        } finally {
            try { probe.cancel() } finally { synchronized(lock) { probes -= probe } }
        }
    }

    private fun latestKnown(): Long = synchronized(lock) { latestSeq }
    private fun followArgs(assistant: Boolean) = buildJsonObject {
        put("request", WireJson.encodeToJsonElement(SessionFollowRequest.serializer(),
            SessionFollowRequest(address, maxMessages = 1, assistantStream = true.takeIf { assistant })))
    }
    private fun expand(records: List<com.labteto.dshmobile.core.wire.dto.SessionHistoryRecord>): List<SessionEventEnvelope> =
        records.flatMap { ChunkRows.expand(it).map(::wireEventToEnvelope) }

    private fun install(page: HistoryDiskPage) {
        if (events != page.events) {
            events.clear(); events.addAll(page.events); revision++
        }
        legacy = page.legacyUnbounded
        currentWindowBytes = page.payloadBytes
        oversizedWindow = currentWindowBytes > HistoryWindowBudget.payloadBytes(WINDOW_MESSAGES)
        currentMessages = events.sumOf(::messageCount)
        currentWindowTicket = cache.ticket()
        latestSeq = maxOf(latestSeq, page.lastSeq ?: -1L)
        hasNewer = page.hasNewer || latestSeq > (page.lastSeq ?: -1L)
        page.firstSeq?.let { first -> knownFirstSeq = knownFirstSeq?.let { minOf(it, first) } ?: first }
        hasOlder = page.hasOlder || knownFirstSeq?.let { first -> (page.firstSeq ?: first) > first } == true ||
            remoteHasOlder && (remoteOldest == null || (page.firstSeq ?: Long.MAX_VALUE) >= remoteOldest!!)
    }

    private fun publish() {
        if (foldedRevision != revision || folded == null) {
            folded = EventFold(childId).fold(events.toList())
            foldedRevision = revision
        }
        val durable = folded ?: return
        val current = if (hasNewer) durable else live.transientSnapshot(durable)
        val snapshot = current.copy(
            hasMore = hasOlder, hasNewer = hasNewer, fromCache = cursor == null,
            running = if (cursor == null) false else current.running,
        )
        mutableState.value = mutableState.value.copy(snapshot = snapshot, hasNewer = hasNewer)
    }

    override fun close() {
        val following = synchronized(lock) {
            if (closed) return
            closed = true; request++
            (probes.toList() + listOfNotNull(stream)).also { stream = null; probes.clear() }
        }
        var failure: Exception? = null
        following.forEach {
            try { it.cancel() } catch (error: Exception) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        try { synchronized(lock) { live.clear() } } catch (error: Exception) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    companion object {
        private const val PAGE_MESSAGES = HistoryWindowBudget.TAIL_LOW_MESSAGES
        private const val WINDOW_MESSAGES = HistoryWindowBudget.TAIL_HIGH_MESSAGES
        private val WINDOW_EVENTS = HistoryWindowBudget.rawEvents(WINDOW_MESSAGES)
    }
}
