package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.TurnStartNode
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.EmptyHero
import com.labteto.dshmobile.ui.components.skeleton
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * How close to the far end of the list — the oldest message — a reader must get before the next
 * page is fetched.
 *
 * Deliberately not zero: firing a row or two early means the page is on its way before the reader
 * reaches the end of what is loaded, so scrolling back stays continuous rather than stopping dead
 * at a spinner.
 *
 * In reverse layout this no longer has a second job. The list is anchored at the newest message,
 * older ones extend away from that anchor, and a page arriving at the far end cannot move anything
 * the reader is looking at — which is what the pre-0.11.2 comment here was working around.
 */
private const val LOAD_OLDER_THRESHOLD = 2

/**
 * The conversation itself.
 *
 * The list is laid out in reverse: the newest row is index 0, pinned at the bottom of the
 * viewport, and older rows extend upward from it. That is what makes following a streaming reply
 * free. A normally-ordered list anchors on its *first* visible row, so a tail row growing 20 times
 * a second displaces every row after it, and each displacement was being animated — the text under
 * the reader's eyes jittered, and a scroll was needed every frame just to stay at the bottom.
 * Reversed, the growing row is the anchor: it extends in place, nothing else moves, and no scroll
 * happens at all.
 *
 * It also makes reading history mid-turn stable for free. Scrolled up, the reader's anchor sits
 * above index 0, so the tail can grow as much as it likes without touching the viewport — no
 * freezing the tail and catching up later. And a page of older messages lands at the far end,
 * where it cannot move anything on screen.
 */
@Composable
internal fun ChatTranscript(
    conversation: ConversationSnapshot?,
    loading: Boolean,
    loadingOlder: Boolean,
    loadOlderFailed: Boolean,
    context: ChatNodeContext,
    listState: LazyListState,
    onLoadOlder: () -> Unit,
    modifier: Modifier = Modifier,
    /** A message the reader picked out of the history sheet, still waiting to be scrolled to. */
    jumpToSeq: Long? = null,
    onJumpConsumed: () -> Unit = {},
    /** Fills the composer with a starter prompt from the empty state; null offers none. */
    onSuggestion: ((String) -> Unit)? = null,
    /** Bumped each time the reader sends: their own message is always worth landing on. */
    sentTick: Int = 0,
    /** Zen keeps transport diagnostics out of the reading surface, without retrying failed pages. */
    showStatusNotices: Boolean = true,
    loadingNewer: Boolean = false,
    loadNewerFailed: Boolean = false,
    onLoadNewer: () -> Unit = {},
    onShowLatest: suspend () -> Boolean = { true },
    onHistoryViewport: (anchorSeq: Long?, atTail: Boolean) -> Unit = { _, _ -> },
    historyInteractionEnabled: Boolean = true,
    viewportRestore: HistoryViewportRestoreRequest? = null,
    onViewportRestored: (Long) -> Unit = {},
    /** Owned above the tab swap so extended tool runs retain identity when Chat is recreated. */
    toolGroupState: ChatToolGroupState? = null,
) {
    // Only the nodes that draw something: a zero-height item still costs its 4dp gap, and a turn's
    // worth of structural events stacks those gaps into a blank band under the chrome.
    //
    // Reversed here rather than at the call site: the fold's natural order is oldest-first, and
    // that is the order every other reader of `nodes` wants.
    val sessionId = conversation?.sessionId
    val turnOutline = conversation?.projections?.get("turnOutline")
    val rawRows = remember(conversation?.nodes, context.running, turnOutline) {
        transcriptRows(conversation?.nodes.orEmpty(), context.running, turnOutline).asReversed()
    }
    val localGroupState = remember(sessionId) { ChatToolGroupState() }
    val groupState = toolGroupState ?: localGroupState
    val rows = remember(rawRows, sessionId, groupState) { retainToolGroupIdentities(rawRows, groupState.rows) }
    SideEffect { groupState.rows = rows }
    val expandedGroups = rows.filterIsInstance<TranscriptRow.Tools>()
        .filter { groupState.expansion(it.identitySeq).value }.map { it.identitySeq }.toSet()
    val displayRows = remember(rows, expandedGroups) { transcriptDisplayRows(rows, expandedGroups) }
    val hasMore = conversation?.hasMore == true
    val hasNewer = conversation?.hasNewer == true
    val rowContext = context.copy(hasNewer = hasNewer)
    val itemCount = displayRows.size + (if (hasMore) 1 else 0) + (if (hasNewer) 1 else 0)

    // Opening a session lands on its newest message. In reverse layout that is index 0, which is
    // also where the list starts, so this only has to undo a position inherited from the session
    // that was open before. Following the tail is handled just below, where the anchoring the
    // reversal buys is overridden only when it would otherwise cost the reader the newest row.
    var lastSession by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sessionId) {
        if (sessionId == lastSession) return@LaunchedEffect
        lastSession = sessionId
        if (itemCount > 0) listState.scrollToItem(0)
    }

    // Following the tail is free only while the tail is one growing row. The layout keeps the first
    // visible *key* anchored, and in a reversed list that key is the bottom row: insert a row below
    // it — a tool card, the next message segment, a question card, the reader's own message — and
    // the new row takes index 0 while the viewport holds the old bottom row exactly where it was,
    // leaving the arrival one scroll past the edge. Nothing animated it into place either, so the
    // reader watching a reply arrives at a dead screen until they drag.
    //
    // Pin index 0 again while the reader is at the bottom. `requestScrollToItem` is the one call
    // that overrides the key anchoring for the coming measure; it is instant and schedules no
    // animation, so it cannot race the next delta the way a per-frame scroll would.
    //
    // Follow is durable user intent, never inferred from an idle/layout emission. A fold, paging
    // sentinel removal or interaction-gate restart can put index 0 under a historical reader.
    fun atTail(): Boolean =
        !hasNewer && listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
    var followTail by remember(sessionId) { mutableStateOf(!hasNewer) }
    LaunchedEffect(hasNewer) {
        // A historical entry/seek is not following, even if its newest loaded row is index zero.
        // Removing the newer sentinel after the last append must not silently rearm following.
        if (hasNewer) followTail = false
    }
    val currentAtTail by rememberUpdatedState(::atTail)
    val canFollowGesture by rememberUpdatedState(historyInteractionEnabled && viewportRestore == null && jumpToSeq == null)
    val olderPageAllowed by rememberUpdatedState(historyInteractionEnabled && viewportRestore == null &&
        jumpToSeq == null && hasMore && !loading && !loadingOlder && !loadingNewer && !loadOlderFailed)
    val newerPageAllowed by rememberUpdatedState(historyInteractionEnabled && viewportRestore == null &&
        jumpToSeq == null && hasNewer && !loading && !loadingOlder && !loadingNewer && !loadNewerFailed)
    val requestOlder by rememberUpdatedState(onLoadOlder)
    val requestNewer by rememberUpdatedState(onLoadNewer)
    val olderGestureGate = remember(sessionId) { ReaderPagingGesture() }
    val newerGestureGate = remember(sessionId) { ReaderPagingGesture() }
    val userScroll = remember(listState, sessionId) {
        object : NestedScrollConnection {
            private var returningFling = false
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Reverse layout: a finger moving down reveals history. Stop before measurement,
                // not after release, so an arrival during the drag cannot schedule a tail pin.
                if (source == NestedScrollSource.UserInput && available.y > 0f) followTail = false
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    val delta = consumed.y + available.y
                    val info = listState.layoutInfo
                    if (delta > 0f) {
                        val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                        val nearEdge = last >= 0 && info.totalItemsCount - 1 - last <= LOAD_OLDER_THRESHOLD
                        if (olderGestureGate.request(delta, nearEdge, olderPageAllowed)) requestOlder()
                    } else if (delta < 0f) {
                        val first = info.visibleItemsInfo.firstOrNull()?.index ?: Int.MAX_VALUE
                        if (newerGestureGate.request(-delta, first <= LOAD_OLDER_THRESHOLD, newerPageAllowed)) requestNewer()
                    }
                }
                if (canFollowGesture && (source == NestedScrollSource.UserInput || returningFling)) {
                    followTail = transcriptFollowAfterUserScroll(followTail, consumed.y, currentAtTail())
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                returningFling = available.y < 0f
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                returningFling = false
                return Velocity.Zero
            }
        }
    }
    LaunchedEffect(itemCount, followTail, hasNewer, historyInteractionEnabled, viewportRestore, jumpToSeq) {
        if (historyInteractionEnabled && viewportRestore == null && jumpToSeq == null &&
            followTail && !hasNewer && itemCount > 0 && !listState.isScrollInProgress) {
            listState.requestScrollToItem(0)
        }
    }

    // Capture before the item provider changes: reading in SideEffect can already see the NEW
    // measurement. Do not subscribe composition to layoutInfo (measure/recompose loop). Even a
    // surviving key needs preservation at index zero, where LazyColumn otherwise follows arrivals.
    val preMeasureAnchor = androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
        captureHistoryReadingPosition(listState, true, hasNewer)
    }
    val anchorKeys = remember(displayRows, hasNewer, hasMore) {
        buildList<Any> {
            if (hasNewer) add("load-newer")
            addAll(displayRows.map { it.key })
            if (hasMore) add("load-older")
        }
    }
    // Non-snapshot bookkeeping: only structural changes warrant a request, never every measure,
    // streaming text delta, or a later idle emission after a gesture we deliberately left alone.
    val previousAnchorKeys = remember(sessionId) { arrayOf<List<Any>?>(null) }
    SideEffect {
        val changed = previousAnchorKeys[0] != null && previousAnchorKeys[0] != anchorKeys
        previousAnchorKeys[0] = anchorKeys
        if (changed && !followTail && historyInteractionEnabled && viewportRestore == null &&
            jumpToSeq == null && !listState.isScrollInProgress) {
            val anchor = preMeasureAnchor
            val exactIndex = displayRows.indexOfFirst { it.key == anchor.key }.takeIf { it >= 0 }
            val index = exactIndex ?: transcriptDisplayAnchorIndex(displayRows, anchor.key)
            if (index != null) {
                // The old pixel offset remains meaningful only for a surviving row, not a summary.
                listState.requestScrollToItem(index + if (hasNewer) 1 else 0,
                    if (exactIndex != null) anchor.scrollOffset else 0)
            }
        }
    }

    // Both edges require a direction-matched gesture or an explicit load/retry action.
    // A sparse older page can expose both edges at once: layout-driven newer paging would
    // immediately replace the history the reader just requested. Neither short viewports nor
    // request settlement may choose a direction; the live stream still keeps draining.
    LaunchedEffect(listState, sessionId, rows, followTail, hasNewer, historyInteractionEnabled) {
        if (!historyInteractionEnabled) return@LaunchedEffect
        snapshotFlow {
            val streamingSeq = rows.firstOrNull { transcriptRowKey(it) == STREAMING_ROW_KEY }?.seq
            val anchor = captureHistoryReadingPosition(listState, true, hasNewer, streamingSeq).anchorSeq
            anchor to (followTail && !hasNewer)
        }.collect { (anchor, atTail) -> onHistoryViewport(anchor, atTail) }
    }

    // A pick from the history sheet. The sheet knows only sequences; which row index a sequence owns
    // is decided by the grouping above, so the lookup happens here rather than there.
    //
    // Keyed on the pick alone. Re-running on every row change would restart a jump halfway through
    // whenever a running reply added a row, and the sheet only offers prompts that are loaded, so
    // there is no later page worth waiting for. The pick is consumed however the jump ends —
    // landed, missed, or cut short by the reader's own drag — so a leftover cannot fire later and
    // pull them back.
    //
    // Following the tail is switched off first, or the next row a running reply adds would pin the
    // tail back under a reader who deliberately left it. Picking the tail itself keeps it on.
    LaunchedEffect(jumpToSeq, historyInteractionEnabled) {
        if (!historyInteractionEnabled) return@LaunchedEffect
        val seq = jumpToSeq ?: return@LaunchedEffect
        try {
            val index = (transcriptDisplayIndex(displayRows, seq) ?: return@LaunchedEffect) + if (hasNewer) 1 else 0
            if (index == 0) {
                followTail = true
                listState.requestScrollToItem(0)
                return@LaunchedEffect
            }
            followTail = false
            // The prompt goes to the top of the viewport, not the bottom. A reversed list puts the
            // item it scrolls to at its start, which is the bottom edge: the prompt would sit on the
            // composer with the reply it asked for out of view below it, and the screen filled with
            // the turn before. Both steps are instant; the second needs the prompt measured, since
            // its height decides how far back to come, and clamps at the tail on its own.
            listState.scrollToItem(index)
            val info = listState.layoutInfo
            val rowKey = displayRows[index - if (hasNewer) 1 else 0].key
            val item = info.visibleItemsInfo.firstOrNull { it.key == rowKey } ?: return@LaunchedEffect
            val contentEnd = info.viewportEndOffset - info.afterContentPadding
            listState.scrollBy((item.offset + item.size - contentEnd).toFloat())
            // Read where the jump came to rest rather than leaving it to the scroll-session watcher:
            // both scrolls start and finish inside one frame, so it never sees them. A newest prompt
            // whose turn is shorter than the screen stops at the tail, and a reader left at the tail
            // with following off would watch the next reply land out of view.
            followTail = atTail()
        } finally {
            onJumpConsumed()
        }
    }

    if (loading) {
        TranscriptSkeleton(modifier)
        return
    }

    // Rows that arrived below the reader since they last left the tail, counted against the
    // newest row they had seen. Paging adds rows too, but at the far end, so it cannot inflate this.
    var seenNewestKey by remember(sessionId) { mutableStateOf<Any?>(null) }
    // The streaming row's key is shared by every reply in turn, so it cannot mark a place.
    val newestKey = rows.firstNotNullOfOrNull { row -> transcriptRowKey(row).takeIf { it != STREAMING_ROW_KEY } }
    LaunchedEffect(followTail, newestKey) { if (followTail) seenNewestKey = newestKey }
    // A step the reader saw can fold into its turn's Process row when the turn answers, which is
    // exactly while they are away; that row then stands in for it, or the count drops to nothing
    // just as the reply lands.
    val unseen = remember(rows, seenNewestKey) {
        if (seenNewestKey == null) 0
        else rows.indexOfFirst { row ->
            transcriptRowKey(row) == seenNewestKey ||
                (row is TranscriptRow.Process && row.rows.any { transcriptRowKey(it) == seenNewestKey })
        }.coerceAtLeast(0)
    }
    val awayFromTail by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 240 }
    }
    val jumpScope = rememberCoroutineScope()
    // Sending from halfway up the history used to leave the new message, and the reply to it, out
    // of view below. A send is the clearest possible "take me to the end".
    var handledSentTick by remember(sessionId) { mutableIntStateOf(0) }
    LaunchedEffect(sentTick, historyInteractionEnabled, viewportRestore) {
        if (!historyInteractionEnabled || viewportRestore != null || sentTick == 0 || sentTick == handledSentTick) return@LaunchedEffect
        handledSentTick = sentTick
        if (!onShowLatest()) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        followTail = true
        if (listState.firstVisibleItemIndex > JUMP_SNAP_ROWS) listState.scrollToItem(JUMP_SNAP_ROWS)
        listState.animateScrollToItem(0)
    }

    // Apply only after the requested disk window is published. Actual keys include both paging
    // sentinels, so an overlapping/non-overlapping window never turns a saved index into another row.
    LaunchedEffect(viewportRestore, displayRows, hasMore, hasNewer, conversation?.loadedFromSeq,
        conversation?.loadedThroughSeq, conversation?.journal?.firstOrNull()?.seq, conversation?.journal?.lastOrNull()?.seq) {
        val request = viewportRestore ?: return@LaunchedEffect
        handledSentTick = sentTick // returning to a tab must not replay an already handled send
        val keys: List<Any> = buildList {
            if (hasNewer) add("load-newer")
            if (displayRows.isEmpty()) add("empty") else addAll(displayRows.map { it.key })
            if (hasMore) add("load-older")
        }
        if (!historyRestoreWindowReady(conversation, keys, request)) return@LaunchedEffect
        val position = request.position.copy(atTail = request.position.atTail && !hasNewer)
        val memberIndex = transcriptDisplayAnchorIndex(displayRows, position.key)
            ?.let { it + if (hasNewer) 1 else 0 }
        val index = when {
            position.atTail -> 0
            memberIndex != null -> memberIndex
            (position.key as? String)?.startsWith("tool-call-") == true -> null
            else -> historyRestoreIndex(keys, position, reverseLayout = true)
        }
        followTail = position.atTail
        val offset = if (position.atTail || (memberIndex != null && position.key !in keys)) 0 else position.scrollOffset
        if (index != null) listState.scrollToItem(index, offset)
        onViewportRestored(request.token)
    }

    Box(modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().readerPagingGestures(olderGestureGate, newerGestureGate).nestedScroll(userScroll),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        // Newest first, so the row the viewport anchors on is the one that grows.
        reverseLayout = true,
        // Still bottom-aligned: a transcript shorter than the viewport belongs above the composer,
        // not pinned under the tab strip with the empty half below it. The alignment is in visual
        // space, not the reversed one, so this reads the same as it always did — and it only has
        // any effect while the content is shorter than the viewport, which is exactly when no row
        // is growing under anyone's eyes.
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
    ) {
        if (hasNewer) {
            item(key = "load-newer") {
                LoadNewerRow(loading = loadingNewer, failed = loadNewerFailed, onRetry = {
                    followTail = false
                    onLoadNewer()
                })
            }
        }
        if (rows.isEmpty()) {
            item(key = "empty") {
                // A blank page with a blinking cursor is the hardest place to start from on a
                // phone keyboard, so a new session offers a few starters. They fill the composer
                // rather than sending, so each one is a draft to adjust, not a commitment.
                val suggestions = listOf(
                    stringResource(R.string.chat_suggest_overview),
                    stringResource(R.string.chat_suggest_tests),
                    stringResource(R.string.chat_suggest_changes),
                )
                EmptyHero(
                    headline = stringResource(R.string.chat_empty_title),
                    subtitle = stringResource(R.string.chat_empty_hint),
                    chips = if (onSuggestion != null) suggestions else emptyList(),
                    onChipClick = { onSuggestion?.invoke(it) },
                )
            }
        } else {
            items(displayRows, key = { it.key }) { item ->
                // Expanded tools are independently keyed lazy children. Extending a group must
                // neither compose every call nor change the geometry of a call being read.
                // All durable history is immediately readable, without arrival/placement fades.
                when (item) {
                    is TranscriptDisplayRow.ToolDetail -> ToolGroupDetailRow(item.call, rowContext)
                    is TranscriptDisplayRow.Semantic -> when (val row = item.row) {
                        is TranscriptRow.Message -> ChatNodeItem(node = row.node, context = rowContext)
                        is TranscriptRow.Tools -> androidx.compose.runtime.key(sessionId) {
                            val expansion = groupState.expansion(row.identitySeq)
                            ToolGroupRow(row.calls, rowContext, row.identitySeq, expansion,
                                renderDetails = false, onExpansionChange = { expanded ->
                                    followTail = false
                                    expansion.value = expanded
                                })
                        }
                        is TranscriptRow.Context -> ContextGroupRow(row.nodes, rowContext)
                        is TranscriptRow.Process -> ProcessRow(row, rowContext)
                    }
                }
            }
        }
        if (hasMore) {
            // The far end of a reversed list is the oldest message, so the paging row goes last.
            item(key = "load-older") {
                LoadOlderRow(
                    loading = loadingOlder,
                    failed = loadOlderFailed,
                    offerManual = true,
                    onRetry = {
                        followTail = false
                        onLoadOlder()
                    },
                )
            }
        }
    }

    // The way back down. Reading history mid-turn is supported on purpose (see the anchoring notes
    // above), but on a long session the only way back used to be dragging through everything in
    // between. Shown only once the reader is clearly away, so it does not flicker in on a nudge.
    AnimatedVisibility(
        visible = (awayFromTail || hasNewer) && rows.isNotEmpty(),
        enter = DsAnimations.popEnter,
        exit = DsAnimations.popExit,
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp),
    ) {
        JumpToLatestButton(
            unseen = unseen,
            running = context.running,
            onClick = {
                jumpScope.launch {
                    if (!onShowLatest()) return@launch
                    androidx.compose.runtime.withFrameNanos { }
                    followTail = true
                    // A long way back is mostly rows nobody needs to watch go by: land near the tail
                    // first, then animate the last stretch so the motion still reads as "down".
                    if (listState.firstVisibleItemIndex > JUMP_SNAP_ROWS) listState.scrollToItem(JUMP_SNAP_ROWS)
                    listState.animateScrollToItem(0)
                }
            },
        )
    }
    }
}

/** How far from the tail a jump snaps before animating the rest of the way. */
private const val JUMP_SNAP_ROWS = 6

/** The lazy key of a transcript row; see the keying note in [ChatTranscript]. */
internal fun transcriptRowKey(row: TranscriptRow): Any {
    val node = (row as? TranscriptRow.Message)?.node
    return when {
        node is AssistantMessageNode && node.streaming -> STREAMING_ROW_KEY
        row is TranscriptRow.Tools -> row.identitySeq
        else -> row.seq
    }
}

/** A round "back to the newest message" button, with a count of what arrived while away. */
@Composable
private fun JumpToLatestButton(unseen: Int, running: Boolean, onClick: () -> Unit) {
    val colors = DsTheme.colors
    val label = if (unseen > 0) {
        pluralStringResource(R.plurals.chat_jump_latest_new, unseen, unseen)
    } else {
        stringResource(R.string.chat_jump_latest)
    }
    Surface(
        onClick = onClick,
        shape = DsShapes.pillFull,
        color = colors.bgLayer2,
        border = BorderStroke(1.dp, colors.borderL2),
        shadowElevation = 6.dp,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 40.dp)
                .padding(horizontal = if (unseen > 0) 14.dp else 10.dp)
                .animateContentSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (running) StateDot(StateDotState.Running, size = 8.dp)
            if (unseen > 0) {
                Text(
                    pluralStringResource(R.plurals.chat_jump_latest_new, unseen, unseen),
                    style = DsType.small13Strong,
                    color = colors.accent,
                    maxLines = 1,
                )
            }
            Icon(
                Icons.Filled.ArrowDownward,
                contentDescription = null,
                tint = colors.labelSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Stable identity for the one provisional streaming row; see the keying note above. */
private const val STREAMING_ROW_KEY = "streaming-tail"

/** Shared newer-page loading/retry affordance in Chat and Trajectory. */
@Composable
internal fun LoadNewerRow(loading: Boolean, failed: Boolean, onRetry: () -> Unit) {
    if (loading) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = DsTheme.colors.labelTertiary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.chat_loading_newer), style = DsType.caption11, color = DsTheme.colors.labelTertiary)
        }
    } else DsButton(
        text = stringResource(if (failed) R.string.common_retry else R.string.chat_load_newer), onClick = onRetry,
        variant = DsButtonVariant.Ghost, size = DsButtonSize.Small, allowWrap = true,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    )
}

/** Earlier-edge action with stable space for its loading and retry states.
 * A single explicit read may cross several internal pages before finding conversation content.
 * Keep its indicator in place throughout that read instead of repeatedly collapsing the button.
 */
@Composable
internal fun LoadOlderRow(
    loading: Boolean,
    failed: Boolean,
    offerManual: Boolean,
    onRetry: () -> Unit,
) {
    val colors = DsTheme.colors
    when {
        loading -> Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = colors.labelTertiary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.chat_loading_older),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }

        failed || offerManual -> DsButton(
            text = stringResource(if (failed) R.string.chat_load_older_retry else R.string.chat_load_older),
            onClick = onRetry,
            variant = DsButtonVariant.Ghost,
            size = DsButtonSize.Small,
            allowWrap = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        )

        else -> Spacer(Modifier.height(1.dp))
    }
}

/** Placeholder bubbles while a session's history loads, instead of an empty white screen. */
@Composable
private fun TranscriptSkeleton(modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(0.55f, 0.9f, 0.75f, 0.4f).forEach { fraction ->
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(14.dp)
                    .skeleton(colors.bgLayer2, colors.hover),
            )
        }
    }
}
