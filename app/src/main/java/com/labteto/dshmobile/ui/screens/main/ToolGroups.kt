package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.core.session.*
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.formatDurationMs
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R

internal sealed interface TranscriptRow {
    val seq: Long
    data class Message(val node: ChatNode) : TranscriptRow { override val seq get() = node.seq }
    data class Tools(val calls: List<ToolCallNode>) : TranscriptRow { override val seq get() = calls.first().seq }
    data class Context(val nodes: List<ChatNode>) : TranscriptRow { override val seq get() = nodes.first().seq }
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

@Composable
internal fun ToolGroupRow(calls: List<ToolCallNode>, context: ChatNodeContext) {
    var expanded by remember(calls.first().callId) { mutableStateOf(false) }
    val colors = DsTheme.colors
    val results = context.nodes.filterIsInstance<ToolResultNode>().associateBy { it.callId }
    val failed = calls.count { results[it.callId]?.isError == true }
    val running = context.running && calls.any { it.callId !in results }
    val starts = calls.mapNotNull { context.eventTimes[it.seq] }
    val ends = calls.mapNotNull { results[it.callId]?.let { r -> context.eventTimes[r.seq] } }
    val elapsed = if (starts.isNotEmpty() && ends.isNotEmpty()) (ends.max() - starts.min()).coerceAtLeast(0) else null
    Column(Modifier.fillMaxWidth().clip(DsShapes.block).background(colors.bgLayer1)
        .border(1.dp, colors.borderL2, DsShapes.block)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable(role = Role.Button) { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(FeatherIcons.Tool, null, Modifier.size(14.dp), tint = colors.labelTertiary)
            Text(stringResource(R.string.chat_tool_group_count, calls.size), style = DsType.small13, color = colors.labelSecondary)
            if (failed > 0) Text(stringResource(R.string.chat_tool_group_failed, failed), style = DsType.caption11, color = colors.error)
            Spacer(Modifier.weight(1f))
            Text(if (running) stringResource(R.string.jobs_running) else elapsed?.let(::formatDurationMs).orEmpty(),
                style = DsType.caption11, color = if (running) colors.accent else colors.labelCaption)
            Icon(FeatherIcons.ChevronRight, null, Modifier.size(14.dp).graphicsLayer { rotationZ = if (expanded) 90f else 0f }, tint = colors.labelTertiary)
        }
        if (expanded) Column(Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) {
            calls.forEach { call -> key(call.callId) { ToolCallRow(call, context, compact = true) } }
        }
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
