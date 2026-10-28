package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.core.session.*
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.formatDurationMs
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R

internal sealed interface TranscriptRow {
    val seq: Long
    data class Message(val node: ChatNode) : TranscriptRow { override val seq get() = node.seq }
    data class Tools(
        val calls: List<ToolCallNode>,
        // Keep a surviving group's first identity when older/newer members extend the run.
        val identitySeq: Long = calls.first().seq,
    ) : TranscriptRow { override val seq get() = calls.first().seq }
    data class Context(val nodes: List<ChatNode>) : TranscriptRow { override val seq get() = nodes.first().seq }

    /**
     * What a turn did before it answered, folded into one row.
     *
     * The prompt stays visible above this and the reply below it; the reasoning panels and tool
     * groups in between are one tap away instead of a wall of collapsed headers. See
     * [foldProcessRows] for exactly what gets folded.
     */
    data class Process(
        val rows: List<TranscriptRow>, val steps: Int,
        val turnStartSeq: Long = rows.first().seq, val turn: Long? = null,
    ) : TranscriptRow {
        override val seq get() = turnStartSeq
    }
}

/** Group only adjacent renderable calls; a visible message/disclosure is a boundary. */
internal fun groupToolRows(nodes: List<ChatNode>): List<TranscriptRow> {
    val rows = mutableListOf<TranscriptRow>()
    val calls = mutableListOf<ToolCallNode>()
    fun flush() { if (calls.isNotEmpty()) { rows.add(TranscriptRow.Tools(calls.toList())); calls.clear() } }
    val contexts = mutableListOf<ChatNode>()
    fun flushContext() { if (contexts.isNotEmpty()) { rows.add(TranscriptRow.Context(contexts.toList())); contexts.clear() } }
    nodes.forEach {
        val isContext = (it is UserMessageNode && it.isInjectedContext && it.sourceKind != REWIND_SOURCE_KIND) ||
            (it is OtherNode && it.type in setOf("system/message", "request/context"))
        when {
            it is ToolCallNode -> { flushContext(); calls.add(it) }
            isContext -> { flush(); contexts.add(it) }
            else -> { flush(); flushContext(); rows.add(TranscriptRow.Message(it)) }
        }
    }
    flushContext()
    flush()
    return rows
}

/** Build exactly the same bounded-window rows for rendering and history navigation. */
internal fun transcriptRows(
    nodes: List<ChatNode>,
    running: Boolean,
    turnOutline: kotlinx.serialization.json.JsonElement? = null,
): List<TranscriptRow> {
    val outline = parseTurnOutline(turnOutline).orEmpty()
    val localStarts = nodes.filterIsInstance<TurnStartNode>()
    val numbers = outline.associate { it.seq to it.turn } + localStarts.associate { it.seq to it.turn.toLong() }
    val starts = numbers.keys.sorted()
    // Group AFTER partitioning: invisible turn boundaries must also split adjacent tool calls.
    val rows = renderableChatNodes(nodes, running).groupBy { turnIndexOf(it.seq, starts) }
        .values.flatMap(::groupToolRows)
    // Public assistant messages stay visible, including commentary before the final answer.
    return rows
}

/**
 * One process per successfully closed runtime turn, never per contiguous tool chunk.
 * Outline entries establish boundaries, NOT completion: even error ends commit their preview.
 * Unknown endings stay open. No extra events are fetched or retained to manufacture certainty.
 */
internal fun foldProcessRows(
    rows: List<TranscriptRow>,
    turnStarts: List<Long>,
    running: Boolean = false,
    turnEnds: List<TurnEndNode> = emptyList(),
    turnNumbers: Map<Long, Long> = emptyMap(),
    loadedStarts: Set<Long> = turnStarts.toSet(),
): List<TranscriptRow> {
    if (rows.isEmpty() || turnStarts.isEmpty()) return rows
    val ends = turnEnds.associateBy { turnIndexOf(it.seq, turnStarts) }
    return rows.groupBy { turnIndexOf(it.seq, turnStarts) }.flatMap { (index, slice) ->
        val start = turnStarts.getOrNull(index)
        val end = ends[index]
        val number = start?.let { turnNumbers[it] }
        if (start == null || end?.reasonKind != "completed" ||
            (number != null && end.turn.toLong() != number) ||
            slice.any { row -> (row as? TranscriptRow.Message)?.node.let {
                it is AssistantMessageNode && (it.streaming || it.interrupted)
            } }) slice
        else foldTurnProcess(slice, start, number ?: end.turn.toLong(), start in loadedStarts, end.seq)
    }
}

/** Which runtime turn owns a sequence, including when its local start is outside the window. */
private fun turnIndexOf(seq: Long, turnStarts: List<Long>): Int {
    var low = 0
    var high = turnStarts.size - 1
    var found = -1
    while (low <= high) {
        val mid = (low + high) / 2
        if (turnStarts[mid] <= seq) { found = mid; low = mid + 1 } else high = mid - 1
    }
    return found
}

private fun foldTurnProcess(
    rows: List<TranscriptRow>, start: Long, turn: Long, startLoaded: Boolean, endSeq: Long,
): List<TranscriptRow> {
    val firstWork = rows.indexOfFirst { row ->
        row !is TranscriptRow.Context && (row as? TranscriptRow.Message)?.node !is UserMessageNode
    }
    if (firstWork < 0) return rows
    // Match desktop's always-open interleaved-input rule. In partial windows even the first
    // human message may be steering. Context is also kept visible without splitting one turn
    // into several process disclosures or reordering its chronology.
    val users = rows.filterIsInstance<TranscriptRow.Message>().count { it.node is UserMessageNode }
    if (users > (if (startLoaded) 1 else 0) || rows.drop(firstWork).any {
        it is TranscriptRow.Context || (it as? TranscriptRow.Message)?.node is UserMessageNode
    }) return rows
    val answer = rows.indexOfLast { row ->
        val node = (row as? TranscriptRow.Message)?.node
        node is AssistantMessageNode && !node.streaming && node.plainText.isNotBlank() && node.seq < endSeq
    }
    val until = if (answer >= firstWork) answer else rows.indexOfFirst { it.seq >= endSeq }.let {
        if (it < 0) rows.size else it
    }
    if (until <= firstWork) return rows
    val work = rows.subList(firstWork, until)
    val process = TranscriptRow.Process(work.toList(), work.sumOf {
        if (it is TranscriptRow.Tools) it.calls.size else 1
    }, start, turn)
    return rows.take(firstWork) + process + rows.drop(until)
}

/** Desktop's inner process disclosure: any semantic run, never a fixed-size tool batch.
 * Running and failed tools update the summary without overriding the reader's open/closed choice.
 */
@Composable
internal fun ToolGroupRow(
    calls: List<ToolCallNode>,
    context: ChatNodeContext,
    identitySeq: Long = calls.first().seq,
    expandedState: MutableState<Boolean>? = null,
    renderDetails: Boolean = true,
    onExpansionChange: ((Boolean) -> Unit)? = null,
) {
    val localExpanded = rememberSaveable(identitySeq) { mutableStateOf(false) }
    var expanded by (expandedState ?: localExpanded)
    val results = remember(context.nodes) { context.nodes.filterIsInstance<ToolResultNode>().associateBy { it.callId } }
    val failed = calls.count { results[it.callId]?.isError == true }
    val active = calls.lastOrNull { it.callId !in results }?.takeIf { context.running && !context.hasNewer }
    val disclosureState = stringResource(if (expanded) R.string.ux_b_expanded else R.string.ux_b_collapsed)
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth().clip(DsShapes.block).background(colors.bgLayer1)
        .border(1.dp, colors.borderL2, DsShapes.block)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { stateDescription = disclosureState }
            .clickable(role = Role.Button) {
                val next = !expanded
                if (onExpansionChange != null) onExpansionChange(next) else expanded = next
            }
            .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(FeatherIcons.Activity, null, Modifier.size(16.dp), tint = colors.labelTertiary)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.chat_tool_group_count, calls.size),
                    style = DsType.small13, color = colors.labelSecondary)
                if (active != null) Text(
                    stringResource(R.string.reader_tool_running) + " · " +
                        (active.description?.takeIf { it.isNotBlank() } ?: active.name),
                    style = DsType.caption11, color = colors.labelTertiary, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            if (failed > 0) Text(stringResource(R.string.chat_tool_group_failed, failed),
                style = DsType.caption11, color = colors.error)
            Icon(FeatherIcons.ChevronRight, null, Modifier.size(16.dp)
                .graphicsLayer { rotationZ = if (expanded) 90f else 0f }, tint = colors.labelCaption)
        }
        if (expanded && renderDetails) Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            // Deliberately use the transcript's scroll surface on a phone, not a nested gesture trap.
            calls.forEach { call -> key(call.callId) { ToolCallRow(call, context, compact = true) } }
        }
    }
}

/** One lazy child on Chat's existing scroll surface, never a nested scrolling tool list. */
@Composable
internal fun ToolGroupDetailRow(call: ToolCallNode, context: ChatNodeContext) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp)
        .clip(DsShapes.block).background(DsTheme.colors.bgLayer1)
        .padding(horizontal = 10.dp, vertical = 4.dp)) {
        ToolCallRow(call, context, compact = true)
    }
}

@Composable
internal fun ContextGroupRow(nodes: List<ChatNode>, context: ChatNodeContext) {
    var expanded by remember(nodes.first().seq) { mutableStateOf(false) }
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth().clip(DsShapes.block).background(colors.bgLayer1)
        .border(1.dp, colors.borderL2, DsShapes.block)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable(role = Role.Button) { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(FeatherIcons.Layers, null, Modifier.size(14.dp), tint = colors.labelCaption)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.chat_context_title) + " · " + nodes.size, style = DsType.small13, color = colors.labelTertiary)
            Spacer(Modifier.weight(1f))
            Icon(FeatherIcons.ChevronRight, null, Modifier.size(14.dp).graphicsLayer { rotationZ = if (expanded) 90f else 0f }, tint = colors.labelCaption)
        }
        if (expanded) Column(Modifier.padding(8.dp)) { nodes.forEach { key(it.seq) { ChatNodeItem(it, context) } } }
    }
}

/**
 * The folded steps of one turn.
 *
 * The same visual language as the tool/context disclosures, with a full-size touch target
 * and a stable runtime-turn identity across bounded disk windows.
 */
@Composable
internal fun ProcessRow(row: TranscriptRow.Process, context: ChatNodeContext) {
    var expanded by remember(row.seq) { mutableStateOf(false) }
    val disclosureState = stringResource(if (expanded) R.string.ux_b_expanded else R.string.ux_b_collapsed)
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth().clip(DsShapes.block).background(colors.bgLayer1)
        .border(1.dp, colors.borderL2, DsShapes.block)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { stateDescription = disclosureState }
            .clickable(role = Role.Button) { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(FeatherIcons.Activity, null, Modifier.size(14.dp), tint = colors.labelTertiary)
            Text(
                (row.turn?.let { stringResource(R.string.trajectory_turn, it) + " · " }.orEmpty()) +
                    stringResource(R.string.chat_process_title) + " · " +
                    pluralStringResource(R.plurals.trajectory_step_count, row.steps, row.steps),
                style = DsType.small13,
                color = colors.labelSecondary,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Icon(FeatherIcons.ChevronRight, null, Modifier.size(14.dp).graphicsLayer { rotationZ = if (expanded) 90f else 0f }, tint = colors.labelTertiary)
        }
        if (expanded) Column(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            row.rows.forEach { step ->
                key(step.seq) {
                    when (step) {
                        is TranscriptRow.Message -> ChatNodeItem(node = step.node, context = context)
                        is TranscriptRow.Tools -> ToolGroupRow(calls = step.calls, context = context)
                        is TranscriptRow.Context -> ContextGroupRow(nodes = step.nodes, context = context)
                        is TranscriptRow.Process -> Unit
                    }
                }
            }
        }
    }
}
