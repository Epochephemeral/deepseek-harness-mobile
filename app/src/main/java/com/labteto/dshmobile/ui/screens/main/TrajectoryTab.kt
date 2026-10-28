package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.core.wire.dto.SessionStatsView
import com.labteto.dshmobile.core.wire.dto.TokenUsageView
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.components.SectionHeader
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.formatDurationMs
import com.labteto.dshmobile.ui.components.formatTokens
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

private val MonoCaption = DsType.caption11.copy(fontFamily = DsType.codeFont)

/**
 * The turn-by-turn ledger: what the agent did, in order, with its inputs and outputs.
 *
 * Where the Chat tab shows the conversation as it reads, this shows it as it *ran* — every tool
 * call with its arguments and result, grouped by turn. It replaces the cramped trajectory section
 * that used to sit inside the details panel; one home per fact.
 */
@Composable
internal fun TrajectoryTab(
    conversation: ConversationSnapshot?,
    stats: SessionStatsView?,
    usage: TokenUsageView?,
    cwd: String?,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    loadingOlder: Boolean = false,
    loadOlderFailed: Boolean = false,
    onLoadOlder: () -> Unit = {},
    loadingNewer: Boolean = false,
    loadNewerFailed: Boolean = false,
    onLoadNewer: () -> Unit = {},
    onHistoryViewport: (anchorSeq: Long?, atTail: Boolean) -> Unit = { _, _ -> },
    historyInteractionEnabled: Boolean = true,
    viewportRestore: HistoryViewportRestoreRequest? = null,
    onViewportRestored: (Long) -> Unit = {},
    /** Main reader loads diagnostic payloads only while Raw is expanded; legacy panes use journal. */
    loadJournal: (suspend () -> List<SessionEventEnvelope>)? = null,
) {
    val colors = DsTheme.colors
    val nodes = conversation?.nodes.orEmpty()
    // Structural markers are dropped before grouping, not just drawn as nothing: an empty row
    // still costs the list's spacing, and a turn of tool calls would gain a gap around each one.
    var rawOpen by androidx.compose.runtime.saveable.rememberSaveable(conversation?.sessionId) { mutableStateOf(false) }
    val groups = remember(nodes, conversation?.running) {
        groupLedgerTurns(nodes.filterNot { it is com.labteto.dshmobile.core.session.OtherNode && it.type in STRUCTURAL_EVENT_TYPES })
            .map { (turn, turnNodes) -> LedgerTurn(turn, turnNodes, ledgerRows(renderableChatNodes(turnNodes, conversation?.running == true))) }
            .filter { it.rows.isNotEmpty() }
    }

    val hasNewer = conversation?.hasNewer == true
    val firstSeq = conversation?.loadedFromSeq ?: conversation?.journal?.firstOrNull()?.seq ?: nodes.firstOrNull()?.seq
    val lastSeq = conversation?.loadedThroughSeq ?: conversation?.journal?.lastOrNull()?.seq ?: nodes.lastOrNull()?.seq
    var rawEvents by remember(conversation?.sessionId) { mutableStateOf<List<SessionEventEnvelope>?>(null) }
    var rawLoading by remember(conversation?.sessionId) { mutableStateOf(false) }
    var rawError by remember(conversation?.sessionId) { mutableStateOf<String?>(null) }
    var rawRetry by remember(conversation?.sessionId) { mutableStateOf(0) }
    val journalLoader by rememberUpdatedState(loadJournal)
    val rawAvailable = !conversation?.journal.isNullOrEmpty() || (loadJournal != null && firstSeq != null)
    val journal = if (loadJournal == null) conversation?.journal.orEmpty() else rawEvents.orEmpty()
    LaunchedEffect(conversation?.sessionId, rawOpen, firstSeq, lastSeq, rawRetry) {
        if (!rawOpen) { rawEvents = null; rawError = null; rawLoading = false; return@LaunchedEffect }
        val load = journalLoader ?: return@LaunchedEffect
        rawLoading = true
        rawError = null
        try {
            val records = load()
            currentCoroutineContext().ensureActive()
            rawEvents = records
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { rawError = failure.message ?: failure.javaClass.simpleName }
        finally { rawLoading = false }
    }
    val reportViewport by rememberUpdatedState(onHistoryViewport)
    LaunchedEffect(listState, conversation?.sessionId, hasNewer, firstSeq, lastSeq, historyInteractionEnabled) {
        if (!historyInteractionEnabled) return@LaunchedEffect
        var lastAnchor: Long? = null
        snapshotFlow {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) null else {
                val anchor = visible.firstNotNullOfOrNull { item ->
                    val key = item.key as? String
                    when {
                        key?.startsWith("n-") == true -> key.removePrefix("n-").toLongOrNull()
                        key?.startsWith("raw-") == true -> key.removePrefix("raw-").toLongOrNull()
                        else -> null
                    }
                }
                Triple(anchor, !hasNewer && !listState.canScrollForward, visible.first().index <= 1)
            }
        }.collect { viewport ->
            if (viewport == null) return@collect
            // Totals/disclosure headers have no event key. Keep the last real reading anchor,
            // or the corresponding loaded edge when this view first opens on metadata alone.
            val anchor = viewport.first ?: lastAnchor ?: if (viewport.third) firstSeq else lastSeq
            lastAnchor = anchor
            reportViewport(anchor, viewport.second)
        }
    }

    LaunchedEffect(viewportRestore, groups, hasNewer, conversation?.hasMore, firstSeq, lastSeq, rawOpen, rawEvents, rawLoading) {
        val request = viewportRestore ?: return@LaunchedEffect
        if ((request.position.rawExpanded || ((request.position.key as? String)?.startsWith("raw-") == true && request.position.key != "raw-header")) && !rawOpen) {
            rawOpen = true
            return@LaunchedEffect
        }
        if (rawOpen && loadJournal != null && (rawLoading || rawEvents == null)) return@LaunchedEffect
        val keys: List<Any> = buildList {
            if (conversation?.hasMore == true) add("load-older")
            if (groups.isEmpty()) add("empty")
            groups.forEach { group ->
                add("turn-${group.turn}")
                addAll(group.rows.map { "n-${it.seq}" })
            }
            if (hasNewer) add("load-newer")
            if (stats != null || usage != null) add("totals")
            if (rawAvailable) {
                add("raw-header")
                if (rawOpen) {
                    if (rawLoading || rawError != null) add("raw-status")
                    addAll(journal.map { "raw-${it.seq}" })
                }
            }
        }
        if (!historyRestoreWindowReady(conversation, keys, request)) return@LaunchedEffect
        val position = request.position.copy(atTail = request.position.atTail && !hasNewer)
        val index = historyRestoreIndex(keys, position, reverseLayout = false)
        if (index != null) listState.scrollToItem(index, if (position.atTail) 0 else position.scrollOffset)
        onViewportRestored(request.token)
    }

    val canPageEarlier by rememberUpdatedState(historyInteractionEnabled && viewportRestore == null &&
        conversation?.hasMore == true && !loadingOlder && !loadOlderFailed)
    val requestEarlier by rememberUpdatedState(onLoadOlder)
    val gestureGate = remember(conversation?.sessionId) { ReaderPagingGesture() }
    val userScroll = remember(listState, conversation?.sessionId) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput &&
                    gestureGate.request(consumed.y + available.y,
                        listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index?.let { it <= 2 } == true,
                        canPageEarlier)) requestEarlier()
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().readerPagingGestures(gestureGate).nestedScroll(userScroll),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // The ledger shows what the transcript has loaded, and Chat pages history in as you
        // scroll up. Nothing scrolls the ledger's top into a fetch, so earlier turns were simply
        // missing here until Chat had been scrolled back far enough. This row asks for them.
        if (conversation?.hasMore == true) {
            item(key = "load-older") {
                LoadOlderRow(loading = loadingOlder, failed = loadOlderFailed, offerManual = true, onRetry = onLoadOlder)
            }
        }
        if (groups.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.trajectory_empty),
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
            }
        }
        groups.forEach { (turn, turnNodes, rows) ->
            item(key = "turn-$turn") {
                SectionHeader(
                    title = if (turn != null) stringResource(R.string.trajectory_turn, turn) else stringResource(R.string.trajectory_ledger),
                    action = turnNodes.count { it is ToolCallNode }.let { pluralStringResource(R.plurals.trajectory_step_count, it, it) },
                )
            }
            items(
                count = rows.size,
                key = { index -> "n-${rows[index].seq}" },
            ) { index ->
                when (val row = rows[index]) {
                    is LedgerRow.Node -> TrajectoryRow(row.node, turnNodes, cwd, conversation?.running == true, hasNewer)
                    is LedgerRow.Context -> ContextGroupRow(row.nodes, ChatNodeContext(turnNodes, false, cwd,
                        onOpenSubagent = {}, onBranchFrom = {}, onFeedback = { _, _ -> }, hasNewer = hasNewer))
                }
            }
        }
        if (hasNewer) {
            item(key = "load-newer") {
                LoadNewerRow(loading = loadingNewer, failed = loadNewerFailed, onRetry = onLoadNewer)
            }
        }
        if (stats != null || usage != null) {
            item(key = "totals") {
                Spacer(Modifier.width(8.dp))
                TrajectoryTotals(stats, usage)
            }
        }
        // Every event as the host logged it, for the rare reader who needs the wire. Collapsed by
        // default and after the totals: open, it is hundreds of rows, and with no heading the
        // ledger above seemed to turn into a debug dump halfway down.
        if (rawAvailable) {
            item(key = "raw-header") {
                com.labteto.dshmobile.ui.components.DisclosureRow(
                    title = stringResource(R.string.trajectory_tab_raw),
                    summary = if (rawOpen || loadJournal == null) journal.size.toString() else "",
                    icon = com.labteto.dshmobile.ui.components.FeatherIcons.List,
                    expanded = rawOpen,
                    onToggle = {
                        rawOpen = !rawOpen
                        if (!rawOpen) { rawEvents = null; rawError = null }
                    },
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (rawOpen) {
                if (rawLoading || rawError != null || (loadJournal != null && rawEvents == null)) {
                    item(key = "raw-status") {
                        if (rawError == null) Text(stringResource(R.string.common_loading), style = DsType.caption11)
                        else Column {
                            Text(stringResource(R.string.err_generic, rawError.orEmpty()), style = DsType.caption11, color = colors.error)
                            com.labteto.dshmobile.ui.components.DsButton(
                                text = stringResource(R.string.common_retry), onClick = { rawRetry++ },
                                variant = com.labteto.dshmobile.ui.components.DsButtonVariant.Ghost,
                                size = com.labteto.dshmobile.ui.components.DsButtonSize.Small)
                        }
                    }
                }
                journal.forEach { event ->
                    item(key = "raw-${event.seq}") {
                        JsonDisclosure("#${event.seq} · ${event.type} · ${java.text.DateFormat.getTimeInstance().format(java.util.Date(event.time))}",
                            kotlinx.serialization.json.buildJsonObject {
                                put("data", event.data)
                                event.surfaceIntent?.let { put("surfaceOp", it) }
                                event.sourceEventSeqs?.let { put("sourceEventSeqs", kotlinx.serialization.json.JsonArray(it.map { seq -> kotlinx.serialization.json.JsonPrimitive(seq) })) }
                                event.ignorable?.let { put("ignorable", kotlinx.serialization.json.JsonPrimitive(it)) }
                            })
                    }
                }
            }
        }
    }
}

/**
 * What the harness put in front of the model rather than what anyone typed: its system prompt,
 * the request context, and the runtime, skill and time snapshots it injects as user messages.
 * Chat folds these into one "Context" row; the ledger used to print each as a `> ` line, which
 * read as though the person had written a page of system text before every turn.
 */
private fun isHarnessContext(node: ChatNode): Boolean =
    (node is UserMessageNode && node.isInjectedContext && node.sourceKind != com.labteto.dshmobile.core.session.REWIND_SOURCE_KIND) ||
        (node is com.labteto.dshmobile.core.session.OtherNode && node.type in setOf("system/message", "request/context"))

/** One turn of the ledger: its nodes, and the rows drawn for them. */
private data class LedgerTurn(val turn: Int?, val nodes: List<ChatNode>, val rows: List<LedgerRow>)

/** A ledger row: one node, or a run of harness context drawn as Chat draws it. */
private sealed interface LedgerRow {
    val seq: Long
    data class Node(val node: ChatNode) : LedgerRow { override val seq get() = node.seq }
    data class Context(val nodes: List<ChatNode>) : LedgerRow { override val seq get() = nodes.first().seq }
}

/** The turn's nodes with each run of harness context folded into one row. */
private fun ledgerRows(nodes: List<ChatNode>): List<LedgerRow> {
    val out = mutableListOf<LedgerRow>()
    var run = mutableListOf<ChatNode>()
    fun flush() { if (run.isNotEmpty()) { out += LedgerRow.Context(run.toList()); run = mutableListOf() } }
    nodes.forEach { node -> if (isHarnessContext(node)) run += node else { flush(); out += LedgerRow.Node(node) } }
    flush()
    return out
}

@Composable
private fun TrajectoryRow(node: ChatNode, siblings: List<ChatNode>, cwd: String?, running: Boolean, hasNewer: Boolean) {
    ChatNodeItem(node, ChatNodeContext(siblings, running, cwd,
        onOpenSubagent = {}, onBranchFrom = {}, onFeedback = { _, _ -> }, hasNewer = hasNewer))
}

@Composable
private fun TrajectoryTotals(stats: SessionStatsView?, usage: TokenUsageView?) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        SectionHeader(stringResource(R.string.trajectory_tab_usage))
        stats?.let {
            Text(
                pluralStringResource(R.plurals.chat_stats_turn_count, it.turns, it.turns) + " · " + pluralStringResource(R.plurals.trajectory_step_count, it.steps, it.steps),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
            Text(
                stringResource(
                    R.string.chat_stats_timing,
                    formatDurationMs(it.llmMs),
                    formatDurationMs(it.toolMs),
                ),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
            Text(
                stringResource(
                    R.string.chat_stats_speed,
                    formatDurationMs(it.meanTtftMs),
                    it.tokensPerSecond?.let { rate -> String.format(java.util.Locale.US, "%.0f", rate) } ?: "—",
                ),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }
        usage?.let {
            Text(
                stringResource(
                    R.string.trajectory_usage,
                    formatTokens(it.inputTokens),
                    formatTokens(it.outputTokens),
                    formatTokens(it.cacheReadTokens),
                    formatTokens(it.cacheWriteTokens),
                ),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }
    }
}
