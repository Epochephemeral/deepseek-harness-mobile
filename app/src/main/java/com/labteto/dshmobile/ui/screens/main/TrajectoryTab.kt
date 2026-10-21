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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.ConversationSnapshot
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
) {
    val colors = DsTheme.colors
    val nodes = remember(conversation?.journal, conversation?.nodes) {
        if (conversation?.journal.isNullOrEmpty()) conversation?.nodes.orEmpty()
        else com.labteto.dshmobile.core.session.EventFold(conversation!!.sessionId).fold(
            conversation.journal.map { it.copy(surfaceOp = null, surfaceIntent = null) },
        ).nodes
    }
    // Structural markers are dropped before grouping, not just drawn as nothing: an empty row
    // still costs the list's spacing, and a turn of tool calls would gain a gap around each one.
    var rawOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val groups = remember(nodes) {
        groupByTurn(nodes.filterNot { it is com.labteto.dshmobile.core.session.OtherNode && it.type in STRUCTURAL_EVENT_TYPES })
            .map { (turn, turnNodes) -> LedgerTurn(turn, turnNodes, ledgerRows(turnNodes)) }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
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
                    title = stringResource(R.string.trajectory_turn, turn),
                    action = turnNodes.count { it is ToolCallNode }.let { pluralStringResource(R.plurals.trajectory_step_count, it, it) },
                )
            }
            items(
                count = rows.size,
                key = { index -> "n-${rows[index].seq}" },
            ) { index ->
                when (val row = rows[index]) {
                    is LedgerRow.Node -> TrajectoryRow(row.node, turnNodes, cwd)
                    is LedgerRow.Context -> ContextGroupRow(row.nodes, ChatNodeContext(turnNodes, false, cwd,
                        onOpenSubagent = {}, onBranchFrom = {}, onFeedback = { _, _ -> }))
                }
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
        val journal = conversation?.journal.orEmpty()
        if (journal.isNotEmpty()) {
            item(key = "raw-header") {
                com.labteto.dshmobile.ui.components.DisclosureRow(
                    title = stringResource(R.string.trajectory_tab_raw),
                    summary = journal.size.toString(),
                    icon = com.labteto.dshmobile.ui.components.FeatherIcons.List,
                    expanded = rawOpen,
                    onToggle = { rawOpen = !rawOpen },
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (rawOpen) {
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
private data class LedgerTurn(val turn: Int, val nodes: List<ChatNode>, val rows: List<LedgerRow>)

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
private fun TrajectoryRow(node: ChatNode, siblings: List<ChatNode>, cwd: String?) {
    val colors = DsTheme.colors
    when (node) {
        is UserMessageNode -> {
            val preview = node.previewText.trim()
            if (preview.isNotEmpty()) {
                Text("> $preview", style = DsType.caption11, color = colors.labelSecondary)
            }
        }
        is AssistantMessageNode -> {
            val snippet = node.plainText.trim().take(160)
            if (snippet.isNotEmpty()) {
                Text(
                    snippet,
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        is ToolCallNode -> {
            val result = siblings
                .filterIsInstance<ToolResultNode>()
                .firstOrNull { it.callId == node.callId }
            ToolLedgerRow(node, result, cwd)
        }
        // Step boundaries are already counted in the turn header; as rows they put a
        // `step/start` and a `step/end` around every tool call in the ledger. The changes marker
        // reads as the turn's changed files, as it does in Chat.
        is com.labteto.dshmobile.core.session.OtherNode -> when {
            node.type in STRUCTURAL_EVENT_TYPES -> Unit
            node.type == "workspace/changes" && changesTurn(node.data) != null ->
                ChangedFilesRow(siblings, changesTurn(node.data)!!, cwd)
            else -> JsonDisclosure(node.type, node.data)
        }
        else -> Unit
    }
}

@Composable
private fun ToolLedgerRow(call: ToolCallNode, result: ToolResultNode?, cwd: String?) {
    val colors = DsTheme.colors
    val row = remember(call.callId, cwd) { toolRowModel(call.name, call.arguments, cwd) }
    var expanded by remember(call.callId) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StateDot(
            when {
                result == null -> StateDotState.Running
                result.isError -> StateDotState.Error
                else -> StateDotState.Done
            },
            size = 8.dp,
        )
        Spacer(Modifier.width(6.dp))
        DisclosureRow(
            title = row.title,
            summary = row.summary,
            // The ledger already leads with its own state dot, so the slot keeps the glyph.
            icon = row.variant.featherIcon(),
            expanded = expanded,
            onToggle = { expanded = !expanded },
            modifier = Modifier.weight(1f),
        ) {
            Column(Modifier.padding(start = 28.dp, top = 2.dp)) {
                Text(stringResource(R.string.chat_input_placeholder), style = DsType.caption11, color = colors.labelCaption)
                JsonDisclosure(call.name, runCatching { kotlinx.serialization.json.Json.parseToJsonElement(call.arguments) }.getOrElse { kotlinx.serialization.json.JsonPrimitive(call.arguments) })
                result?.content?.let { content ->
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.chat_output_placeholder), style = DsType.caption11, color = colors.labelCaption)
                    Text(
                        content.toString(),
                        style = MonoCaption,
                        color = if (result.isError) colors.error else colors.labelTertiary,
                    )
                }
            }
        }
    }
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
