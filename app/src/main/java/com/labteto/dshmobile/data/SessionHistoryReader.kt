package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.ReaderTurnTimeline
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.session.TurnEndNode
import com.labteto.dshmobile.core.session.TurnStartNode
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.core.session.historyTurnLoadTarget
import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.READER_MAX_BYTES
import com.labteto.dshmobile.core.wire.READER_MAX_ROWS
import com.labteto.dshmobile.core.wire.READER_VIEW
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.SessionAddress
import com.labteto.dshmobile.core.wire.dto.SessionPageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement

/** Selection authority is supplied by SessionStore; reconnect does not change selectionEpoch. */
internal data class ReaderSource(
    val host: String,
    val sessionId: String,
    val selectionEpoch: Long,
    val api: DshApiClient?,
) {
    val archiveKey: String get() = "reader-v1\u0000$sessionId"
    fun sameSelection(other: ReaderSource?) = other != null && host == other.host &&
        sessionId == other.sessionId && selectionEpoch == other.selectionEpoch
}

internal data class ReaderFollowToken(
    val source: ReaderSource,
    internal val generation: Long,
    internal val cacheTicket: Long,
)

internal enum class HistoryDirection { OLDER, NEWER }

internal data class SessionHistoryState(
    val source: ReaderSource? = null,
    val snapshot: ConversationSnapshot? = null,
    val loadedRange: LongRange? = null,
    val cut: Int? = null,
    val latestSeq: Long = -1,
    val loading: HistoryDirection? = null,
    val failed: HistoryDirection? = null,
    val error: Exception? = null,
) {
    val hasOlder: Boolean get() = snapshot?.hasMore == true
    val hasNewer: Boolean get() = snapshot?.hasNewer == true
}

/**
 * Owns one selected session's reader data and read lifetime. Transport batches are bounded; loaded
 * coverage and public messages grow with explicit reading. No UI window is replaced or evicted.
 * The materializer retains public turn blocks, not a second copy of the archived raw journal.
 */
internal class SessionHistoryReader(
    private val cache: HistoryCache,
    private val scope: CoroutineScope,
    private val refreshFollow: suspend (ReaderSource) -> Boolean = { false },
    /** Storage fault notification only; user-visible read errors belong to state. */
    private val onFailure: (ReaderSource, Exception) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private val _state = MutableStateFlow(SessionHistoryState())
    val state = _state.asStateFlow()
    private var source: ReaderSource? = null
    private var followGeneration = 0L
    private var requestGeneration = 0L
    private var activeRead: Job? = null
    private var bootstrap: Job? = null
    private var reconnectBridge: Job? = null
    private var timeline: ReaderTurnTimeline? = null
    private var cut: Int? = null
    private var latest = -1L
    private var beginning: Long? = null
    private var outline: JsonElement? = null
    private data class Viewport(val source: ReaderSource, val anchor: Long?, val followingTail: Boolean)
    @Volatile private var viewport: Viewport? = null

    fun isCurrent(token: ReaderFollowToken): Boolean = synchronized(lock) {
        token.generation == followGeneration && token.source == source
    }

    /** Bind a fresh stream generation while keeping the same selection's growing loaded range. */
    fun beginFollow(next: ReaderSource, loadingLatest: Boolean = false): ReaderFollowToken = synchronized(lock) {
        val same = next.sameSelection(source)
        retireReadLocked()
        bootstrap?.cancel()
        reconnectBridge?.cancel()
        followGeneration++
        source = next
        cut = null
        if (!same) {
            timeline = ReaderTurnTimeline(next.sessionId)
            latest = -1
            beginning = null
            outline = null
            viewport = Viewport(next, null, true)
        } else viewport = viewport?.copy(source = next)
        val token = ReaderFollowToken(next, followGeneration, cache.ticket())
        publishLocked(loading = if (loadingLatest) HistoryDirection.NEWER else null, failed = null, error = null)
        if (timeline?.firstSeq == null) bootstrap = scope.launch {
            try {
                val page = cache.latestHistory(next.host, next.archiveKey, ticket = token.cacheTicket)
                synchronized(lock) {
                    if (isCurrent(token) && cut == null && timeline?.firstSeq == null) cache.atTicket(token.cacheTicket) {
                        if (page.events.isNotEmpty()) {
                            timeline!!.mergeRange(page.events)
                            latest = page.lastSeq ?: -1
                            if (page.firstSeq == 0L) beginning = 0
                            publishLocked()
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                synchronized(lock) {
                    if (isCurrent(token)) {
                        publishLocked(error = failure)
                        onFailure(next, failure)
                    }
                }
            }
        }
        token
    }

    /** Retire transport authority immediately; loaded public data and reading intent stay usable. */
    fun disconnect() = synchronized(lock) {
        retireReadLocked()
        bootstrap?.cancel()
        reconnectBridge?.cancel()
        followGeneration++
        cut = null
        publishLocked(loading = null, failed = null, error = null)
    }

    fun close() = synchronized(lock) {
        retireReadLocked()
        bootstrap?.cancel()
        reconnectBridge?.cancel()
        followGeneration++
        source = null
        timeline = null
        cut = null
        latest = -1
        beginning = null
        outline = null
        viewport = null
        _state.value = SessionHistoryState()
    }

    fun updateOutline(value: JsonElement?) = synchronized(lock) { outline = value }

    /** Live control/assistant projections remain SessionStore's authority, not cached reader state. */
    suspend fun baseline(
        token: ReaderFollowToken,
        events: List<SessionEventEnvelope>,
        cursor: Int,
        hasOlder: Boolean,
        bridgeReconnect: Boolean = true,
    ): Boolean = withContext(Dispatchers.Default) {
        val bridge = synchronized(lock) {
            if (!isCurrent(token)) return@withContext false
            val previous = _state.value
            val resumeTail = previous.loadedRange != null && !previous.hasNewer
            retireReadLocked()
            timeline!!.truncateAuthoritative(cursor.toLong())
            latest = cursor.toLong()
            cut = cursor
            if (!hasOlder) beginning = events.firstOrNull()?.seq ?: 0L
            val cached = cache.atTicket(token.cacheTicket) {
                cache.removeHistoryAfter(token.source.host, token.source.archiveKey, cursor.toLong(), token.cacheTicket)
                cache.storeEvents(token.source.host, token.source.archiveKey, token.cacheTicket, events)
            } == true
            if (events.isNotEmpty() && (cached || timeline!!.firstSeq == null) && adjacentLocked(events.first().seq, events.last().seq))
                timeline!!.mergeRange(events)
            publishLocked(loading = null, failed = null, error = null)
            resumeTail && bridgeReconnect && cached && _state.value.hasNewer
        }
        if (bridge) {
            val request = synchronized(lock) { requestGeneration }
            reconnectBridge = scope.launch {
                if (synchronized(lock) { isCurrent(token) && requestGeneration == request })
                    extend(HistoryDirection.NEWER, cursor.toLong())
            }
        }
        true
    }

    /** The stream collector awaits insertion, so live records retain wire order without a RAM queue. */
    suspend fun append(token: ReaderFollowToken, event: SessionEventEnvelope) = withContext(Dispatchers.Default) {
        synchronized(lock) {
            if (!isCurrent(token)) return@withContext
            cache.storeLiveEvent(token.source.host, token.source.archiveKey, event)
            latest = maxOf(latest, event.seq)
            if (adjacentLocked(event.seq, event.seq)) timeline!!.append(event)
            publishLocked()
        }
    }

    fun updateViewport(anchor: Long?, followingTail: Boolean) {
        // Layout callbacks never wait for the archive/materializer monitor.
        val current = _state.value
        val owner = current.source ?: return
        val previous = viewport?.takeIf { owner.sameSelection(it.source) }
        viewport = Viewport(owner, anchor ?: previous?.anchor, followingTail && !current.hasNewer)
    }

    fun captureViewport(): HistoryViewportBookmark? {
        val current = _state.value.source ?: return null
        val intent = viewport?.takeIf { current.sameSelection(it.source) } ?: return null
        return HistoryViewportBookmark(intent.followingTail, intent.anchor, current.host, current.sessionId, current.selectionEpoch)
    }

    fun restoreViewport(bookmark: HistoryViewportBookmark): Boolean = synchronized(lock) {
        val owner = source ?: return@synchronized false
        if (owner.host != bookmark.host || owner.sessionId != bookmark.sessionId || owner.selectionEpoch != bookmark.openEpoch)
            return@synchronized false
        retireReadLocked()
        viewport = Viewport(owner, bookmark.anchorSeq, bookmark.followingTail && !_state.value.hasNewer)
        publishLocked(loading = null, failed = null, error = null)
        true
    }

    suspend fun loadOlder() { extend(HistoryDirection.OLDER) }
    suspend fun loadNewer() { extend(HistoryDirection.NEWER) }

    /** An explicit detail panel owns this temporary journal; the normal reader never retains it. */
    suspend fun readEvents(from: Long, through: Long): List<SessionEventEnvelope> = withContext(Dispatchers.IO) {
        val captured = synchronized(lock) {
            val owner = source ?: return@withContext emptyList()
            val range = _state.value.loadedRange ?: return@withContext emptyList()
            require(from <= through && from >= range.first && through <= range.last) { "Details are outside loaded history" }
            ReaderFollowToken(owner, followGeneration, cache.ticket())
        }
        val page = cache.historyRange(captured.source.host, captured.source.archiveKey, from, through, captured.cacheTicket)
            ?: error("History details are no longer cached; load this range again before opening details")
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            check(isCurrent(captured) && captured.cacheTicket == cache.ticket()) { "History changed while opening details" }
            page.events
        }
    }

    suspend fun seek(seq: Long): Boolean {
        if (seq < 0 || seq > Int.MAX_VALUE) return false
        val initial = _state.value
        val owner = initial.source ?: return false
        val range = initial.loadedRange ?: return false
        val target = synchronized(lock) {
            if (seq > range.last) historyTurnLoadTarget(outline, range.first, seq - 1, latest, false) ?: seq else seq
        }
        if (seq !in range && !extend(if (seq < range.first) HistoryDirection.OLDER else HistoryDirection.NEWER,
                target, replaceRequest = true)) return false
        return synchronized(lock) {
            if (source != owner) return@synchronized false
            val found = timeline!!.snapshot().nodes.any { it.seq == seq && (it is TurnStartNode || it is UserMessageNode) }
            if (!found) {
                val failure = IllegalStateException("The requested turn is not present in this history snapshot")
                publishLocked(loading = null, failed = null, error = failure)
                return@synchronized false
            }
            retireReadLocked()
            viewport = Viewport(owner, seq, false)
            publishLocked(loading = null, failed = null, error = null)
            true
        }
    }

    suspend fun latest(): Boolean {
        val owner = _state.value.source ?: return false
        val needsFollow = synchronized(lock) {
            if (!_state.value.hasNewer && (_state.value.loadedRange != null || cut == -1)) {
                markTailLocked(owner)
                return true
            }
            (cut == null || latest > cut!!.toLong() || timeline?.firstSeq == null).also {
                if (it) publishLocked(loading = HistoryDirection.NEWER, failed = null, error = null)
            }
        }
        try {
            if (needsFollow && !refreshFollow(owner)) return false
            val current = _state.value
            if (current.source != owner) return false
            if (!extend(HistoryDirection.NEWER, current.latestSeq, replaceRequest = true)) return false
            return synchronized(lock) {
                if (source != owner || _state.value.hasNewer) false else { markTailLocked(owner); true }
            }
        } finally {
            synchronized(lock) {
                if (source == owner && activeRead == null) publishLocked(loading = null)
            }
        }
    }

    private fun markTailLocked(owner: ReaderSource) {
        retireReadLocked()
        viewport = Viewport(owner, timeline?.lastSeq, true)
        publishLocked(loading = null, failed = null, error = null)
    }

    private data class Read(
        val token: ReaderFollowToken,
        val request: Long,
        val cut: Int?,
        val ticket: Long,
        val range: LongRange,
        val latest: Long,
        val target: Long?,
        val openTurn: Boolean,
    )

    private fun owns(read: Read, checkTicket: Boolean = true) = isCurrent(read.token) &&
        read.request == requestGeneration && read.cut == cut && (!checkTicket || read.ticket == cache.ticket())

    private fun retireReadLocked() {
        requestGeneration++
        activeRead?.cancel()
        activeRead = null
    }

    private fun adjacentLocked(first: Long, last: Long): Boolean {
        val from = timeline?.firstSeq ?: return true
        val through = timeline!!.lastSeq!!
        return first <= through + 1 && last >= from - 1
    }

    private fun publishLocked(
        loading: HistoryDirection? = _state.value.loading,
        failed: HistoryDirection? = _state.value.failed,
        error: Exception? = _state.value.error,
    ) {
        val owner = source ?: return
        val from = timeline?.firstSeq
        val through = timeline?.lastSeq
        val range = if (from != null && through != null) from..through else null
        val snapshot = timeline?.snapshot()?.takeIf { range != null || cut != null }?.copy(
            running = cut != null && timeline!!.snapshot().running,
            hasMore = from != null && from > (beginning ?: 0L),
            hasNewer = latest > (through ?: -1L),
            fromCache = cut == null,
        )
        // Loading/error transitions are not transcript publications. Preserve its immutable value.
        val published = _state.value.snapshot.takeIf { it == snapshot } ?: snapshot
        _state.value = SessionHistoryState(owner, published, range, cut, latest, loading, failed, error)
    }

    private suspend fun fetchBefore(read: Read, before: Long): HistoryPageFetchSummary {
        val context = currentCoroutineContext()
        val source = read.token.source
        val api = source.api ?: error("History is not available while reconnecting")
        val cursor = read.cut ?: error("History is waiting for a connected session snapshot")
        check(before > 0 && before <= cursor.toLong() + 1) { "History is outside the current follow snapshot" }
        val result = fetchStagedHistoryPage(api, cache, source.host, source.archiveKey, read.ticket,
            SessionPageRequest(SessionAddress.Session(sessionId = source.sessionId), cursor,
                beforeSeq = before.takeIf { it <= Int.MAX_VALUE }?.toInt(), view = READER_VIEW,
                maxRows = READER_MAX_ROWS, maxBytes = READER_MAX_BYTES, maxMessages = null)) { summary, commit ->
            context.ensureActive()
            synchronized(lock) {
                owns(read) && (summary.metadata.recordCount == 0L ||
                    (summary.firstSeq != null && summary.firstSeq < before && summary.lastSeq == before - 1)) && commit()
            }
        }
        context.ensureActive()
        val page = when (result) {
            is RpcResult.Err -> error(result.error.message)
            is RpcResult.Ok -> result.value
        }
        check(page.committed) { "Local history changed; retry loading history" }
        synchronized(lock) {
            check(owns(read)) { "Local history changed; retry loading history" }
            if (!page.metadata.hasMore) beginning = page.firstSeq ?: before
        }
        return page
    }

    private suspend fun extend(direction: HistoryDirection, target: Long? = null, replaceRequest: Boolean = false): Boolean =
        withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            val older = direction == HistoryDirection.OLDER
            val read = synchronized(lock) {
                if (!replaceRequest && activeRead != null) return@withContext false
                val owner = source ?: return@withContext false
                val range = _state.value.loadedRange ?: return@withContext false
                if (target != null && (if (older) target >= range.first else target <= range.last)) return@withContext true
                if (target == null && (if (older) !_state.value.hasOlder else !_state.value.hasNewer)) return@withContext false
                if (replaceRequest) retireReadLocked()
                val nodes = timeline!!.snapshot().nodes
                val start = nodes.lastOrNull { it is TurnStartNode }?.seq
                val end = nodes.lastOrNull { it is TurnEndNode }?.seq
                activeRead = context[Job]
                publishLocked(loading = direction, failed = null, error = null)
                Read(ReaderFollowToken(owner, followGeneration, cache.ticket()), ++requestGeneration, cut, cache.ticket(), range,
                    latest, target ?: historyTurnLoadTarget(outline, range.first, range.last, latest, older),
                    end == null || (start != null && start > end))
            }
            var edge = if (older) read.range.first else read.range.last
            var enteredTurn = read.openTurn
            try {
                while (true) {
                    context.ensureActive()
                    check(synchronized(lock) { owns(read) }) { "Local history changed; retry loading history" }
                    val source = read.token.source
                    fun adjacentPage(): HistoryDiskPage? {
                        val page = if (older) cache.olderHistory(source.host, source.archiveKey, edge, ticket = read.ticket)
                            else cache.newerHistory(source.host, source.archiveKey, edge, ticket = read.ticket)
                        return page.takeIf { it.events.isNotEmpty() && (if (older) it.lastSeq == edge - 1 else it.firstSeq == edge + 1) }
                    }
                    var page = adjacentPage()
                    var exhausted = older && edge <= (synchronized(lock) { beginning } ?: 0L)
                    if (page == null && !exhausted) {
                        if (older && read.cut == null) return@withContext false
                        if (older) exhausted = fetchBefore(read, edge).metadata.recordCount == 0L
                        else {
                            val cursor = read.cut ?: error("Newer history is waiting for a connected session snapshot")
                            var before = minOf(cursor.toLong() + 1, edge + READER_MAX_ROWS + 1, read.target?.plus(1) ?: Long.MAX_VALUE)
                            check(before > edge + 1) { "Newer history needs a refreshed session snapshot" }
                            while (before > edge + 1) {
                                val response = fetchBefore(read, before)
                                val first = response.firstSeq ?: error("The server returned no progress while loading newer history")
                                check(first < before) { "Newer history did not advance" }
                                before = first
                            }
                        }
                        page = if (exhausted) null else adjacentPage()
                        check(exhausted || page != null) { "The server page did not cover the adjacent history range" }
                    }
                    var boundary: Long? = null
                    if (page != null) {
                        val candidate = page
                        if (read.target != null) {
                            if (if (older) candidate.firstSeq!! <= read.target else candidate.lastSeq!! >= read.target) boundary = read.target
                        } else if (older) boundary = candidate.events.lastOrNull { it.type == "turn/start" }?.seq
                        else for (event in candidate.events) {
                            when (event.type) {
                                "turn/start" -> {
                                    if (enteredTurn && event.seq > read.range.last + 1) { boundary = event.seq - 1; break }
                                    enteredTurn = true
                                }
                                "turn/end" -> { boundary = event.seq; break }
                            }
                        }
                        edge = if (older) candidate.firstSeq!! else candidate.lastSeq!!
                        exhausted = if (older) edge <= (synchronized(lock) { beginning } ?: 0L)
                            else edge >= read.latest && !candidate.hasNewer
                    }
                    if (boundary != null || exhausted) {
                        val through = boundary ?: edge
                        val addition = if (if (older) through < read.range.first else through > read.range.last)
                            cache.historyRange(source.host, source.archiveKey,
                                if (older) through else read.range.last + 1,
                                if (older) read.range.first - 1 else through, read.ticket)
                                ?: error("The loaded history addition is incomplete") else null
                        return@withContext synchronized(lock) {
                            context.ensureActive()
                            check(owns(read)) { "Local history changed; retry loading history" }
                            cache.atTicket(read.ticket) {
                                if (addition != null) {
                                    check(adjacentLocked(addition.firstSeq!!, addition.lastSeq!!)) { "History addition is not adjacent" }
                                    timeline!!.mergeRange(addition.events)
                                }
                                if (older && exhausted && (boundary == null || through <= edge)) beginning = through
                                publishLocked(loading = null, failed = null, error = null)
                                true
                            } ?: error("Local history changed; retry loading history")
                        }
                    }
                    check(page != null) { "History did not advance" }
                    yield()
                }
                @Suppress("UNREACHABLE_CODE") false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                synchronized(lock) {
                    if (owns(read, checkTicket = false)) {
                        publishLocked(loading = null, failed = direction, error = failure)
                        onFailure(read.token.source, failure)
                    }
                }
                false
            } finally {
                synchronized(lock) {
                    if (requestGeneration == read.request) {
                        activeRead = null
                        publishLocked(loading = null)
                    }
                }
            }
        }
}
