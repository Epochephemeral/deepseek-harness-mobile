package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.QueueItem
import com.labteto.dshmobile.core.wire.dto.GoalPhase
import com.labteto.dshmobile.core.wire.dto.GoalSnapshot
import com.labteto.dshmobile.core.wire.dto.SessionStatsView
import com.labteto.dshmobile.core.wire.dto.TokenUsageView
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsMenu
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.HoldAction
import com.labteto.dshmobile.ui.components.HoldMenu
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.components.holdable
import com.labteto.dshmobile.ui.components.rememberCopier
import com.labteto.dshmobile.ui.components.SectionHeader
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.formatDurationMs
import com.labteto.dshmobile.ui.components.formatTokens
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.launch

/**
 * The strip of persistent context between the transcript and the composer: to-dos, the ongoing
 * goal, the queue, and the run statistics.
 *
 * These render as collapsed one-line summaries and expand on tap, rather than appearing and
 * vanishing as their data arrives. A dock that pops into existence mid-turn shoves the transcript
 * and the composer around while you are reading or typing, which is most of what made the old
 * layout feel unsettled.
 */

@Composable
internal fun TodoDock(todos: List<TodoEntry>, modifier: Modifier = Modifier) {
    if (todos.isEmpty()) return
    // Not keyed on the list: every todo update used to fold an open dock shut under the reader.
    var expanded by rememberSaveable { mutableStateOf(false) }
    val completed = todos.count { it.status == "completed" }
    DisclosureRow(
        title = stringResource(R.string.chat_todo_title),
        summary = stringResource(R.string.chat_todo_progress, completed, todos.size),
        icon = FeatherIcons.CheckSquare,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = modifier,
    ) {
        todos.forEach { todo ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StateDot(todoStatusDot(todo.status), size = 8.dp)
                Spacer(Modifier.width(8.dp))
                Text(todo.content, style = DsType.small13, color = DsTheme.colors.labelSecondary)
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

/** The read-only goal summary shown inline in the transcript when a goal event lands. */
@Composable
internal fun GoalSummary(goal: GoalSnapshot) {
    val colors = DsTheme.colors
    SectionHeader(stringResource(R.string.goal_title))
    GoalPhaseBadge(goal.phase)
    Spacer(Modifier.height(4.dp))
    Text(goal.objective, style = DsType.small13, color = colors.labelSecondary)
    goal.blockedReason?.let {
        Text(
            stringResource(R.string.goal_blocked_reason, it.message),
            style = DsType.caption11,
            color = colors.warnText,
        )
    }
}

/** The live goal bar above the composer, with its phase verbs. */
@Composable
internal fun GoalBar(goal: GoalSnapshot, store: SessionStore, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val colors = DsTheme.colors
    var editing by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var editText by remember(goal.revision) { mutableStateOf(goal.objective) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StateDot(
            when (goal.phase) {
                GoalPhase.ACTIVE -> com.labteto.dshmobile.ui.components.StateDotState.Running
                GoalPhase.BLOCKED -> com.labteto.dshmobile.ui.components.StateDotState.Warning
                GoalPhase.COMPLETE -> com.labteto.dshmobile.ui.components.StateDotState.Done
                GoalPhase.PAUSED -> com.labteto.dshmobile.ui.components.StateDotState.Idle
                GoalPhase.UNKNOWN -> com.labteto.dshmobile.ui.components.StateDotState.Idle
            },
            size = 8.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            goal.objective,
            style = DsType.small13,
            color = colors.labelSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        GoalPhaseBadge(goal.phase)
        Spacer(Modifier.width(4.dp))
        DsMenu(
            anchor = {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.goal_edit),
                    tint = colors.labelTertiary,
                    modifier = Modifier.size(20.dp),
                )
            },
            items = buildList {
                when (goal.phase) {
                    GoalPhase.ACTIVE -> add(
                        MenuItem(stringResource(R.string.goal_pause)) {
                            scope.launch { store.goalAction("pause") }
                        },
                    )
                    GoalPhase.PAUSED, GoalPhase.BLOCKED -> add(
                        MenuItem(stringResource(R.string.goal_resume)) {
                            scope.launch { store.goalAction("resume") }
                        },
                    )
                    // Nothing to pause or resume: a finished goal has no next state, and a phase
                    // from a newer harness has none this build can name. Edit and clear stay.
                    GoalPhase.COMPLETE, GoalPhase.UNKNOWN -> Unit
                }
                add(MenuItem(stringResource(R.string.goal_edit)) { editing = true })
                add(
                    MenuItem(stringResource(R.string.goal_clear), danger = true) { clearing = true },
                )
            },
        )
    }
    if (clearing) {
        ConfirmDialog(
            title = stringResource(R.string.goal_clear),
            body = goal.objective,
            confirmLabel = stringResource(R.string.goal_clear),
            onDismiss = { clearing = false },
            onConfirm = {
                clearing = false
                scope.launch { store.goalAction("clear") }
            },
        )
    }
    if (editing) {
        DsDialog(title = stringResource(R.string.goal_edit), onDismiss = { editing = false }) {
            TextField(
                minLines = 3,
                value = editText,
                onValueChange = { editText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.goal_title), style = DsType.std14) },
                colors = dialogTextFieldColors(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DsButton(
                    text = stringResource(R.string.common_ok),
                    onClick = {
                        scope.launch { store.goalAction("edit", editText) }
                        editing = false
                    },
                    variant = DsButtonVariant.Info,
                )
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { editing = false },
                    variant = DsButtonVariant.Ghost,
                )
            }
        }
    }
}

/** A status is descriptive, not a disabled action; suppress the shared pill's click semantics. */
@Composable
private fun GoalPhaseBadge(phase: GoalPhase) {
    val label = stringResource(goalPhaseLabelRes(phase))
    Box(Modifier.clearAndSetSemantics { contentDescription = label }) { DsPill(text = label) }
}

/** Pending turns, with the edit / remove / steer verbs the harness exposes. */
@Composable
internal fun QueueDock(queue: List<QueueItem>, store: SessionStore, modifier: Modifier = Modifier, sessionId: String? = store.currentSessionId.value, blocked: Boolean = false, operationState: androidx.compose.runtime.MutableState<Boolean>? = null) {
    if (queue.isEmpty()) return
    val scope = rememberCoroutineScope()
    val colors = DsTheme.colors
    val busyState: androidx.compose.runtime.MutableState<Boolean> = operationState ?: remember(sessionId) { mutableStateOf(false) }
    var busy by busyState
    fun change(id: String, action: String, text: String? = null) {
        if (busy || blocked) return
        busy = true
        scope.launch { try { store.updateQueue(id, action, text, sessionId) } finally { busy = false } }
    }
    // Kept open while turns join or leave the queue; it closes only for another session.
    var expanded by rememberSaveable(sessionId) { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    // A queued turn is something the reader already sent, so it holds like their bubble does.
    var heldId by remember { mutableStateOf<String?>(null) }
    val copy = rememberCopier()
    val holdLabel = stringResource(R.string.hold_message_actions)
    fun fullText(item: QueueItem): String =
        (item.content as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.get("text").asString() }
            ?.joinToString("\n")
            ?.takeIf { it.isNotBlank() }
            ?: item.previewText

    DisclosureRow(
        title = stringResource(R.string.chat_queue_title),
        summary = queue.size.toString(),
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = modifier,
    ) {
        DsButton(text = stringResource(R.string.chat_queue_steer), enabled = !busy && !blocked,
            onClick = {
                busy = true
                val ids = queue.map { it.id }
                scope.launch { try { ids.forEach { store.updateQueue(it, "steer", sessionId = sessionId) } } finally { busy = false } }
            }, variant = DsButtonVariant.Ghost)
        queue.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, top = 2.dp)
                    .holdable(holdLabel) { heldId = item.id },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.previewText,
                    style = DsType.small13,
                    color = colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                DsPill(text = queuePlacementLabel(item.placement))
                if (!busy && !blocked && item.placement != "steering") DsMenu(
                    anchor = {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.chat_queue_edit),
                            tint = colors.labelTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    items = listOf(
                        MenuItem(stringResource(R.string.chat_queue_edit)) {
                            editingId = item.id
                            editText = fullText(item)
                        },
                        MenuItem(stringResource(R.string.chat_queue_remove), danger = true) {
                            change(item.id, "remove")
                        },
                        MenuItem(stringResource(R.string.chat_queue_steer)) {
                            change(item.id, "steer")
                        },
                    ),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
    }

    // Looked up by id on every pass, so a turn that leaves the queue while its menu is open takes
    // the menu with it instead of leaving actions aimed at something that is gone.
    queue.firstOrNull { it.id == heldId }?.let { item ->
        val text = fullText(item)
        val changeable = !busy && !blocked && item.placement != "steering"
        HoldMenu(
            onDismiss = { heldId = null },
            preview = text,
            actions = buildList {
                add(HoldAction(stringResource(R.string.common_copy), Icons.Outlined.ContentCopy) { copy(text) })
                if (changeable) {
                    add(HoldAction(stringResource(R.string.chat_queue_edit), Icons.Outlined.Edit) {
                        editingId = item.id
                        editText = text
                    })
                    add(HoldAction(stringResource(R.string.chat_queue_steer), Icons.Outlined.Bolt) { change(item.id, "steer") })
                    add(HoldAction(stringResource(R.string.chat_queue_remove), Icons.Outlined.Delete, danger = true) {
                        change(item.id, "remove")
                    })
                }
            },
        )
    }

    editingId?.let { id ->
        DsDialog(title = stringResource(R.string.chat_queue_edit), onDismiss = { editingId = null }) {
            TextField(
                value = editText,
                onValueChange = { editText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.chat_composer_hint), style = DsType.std14) },
                colors = dialogTextFieldColors(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DsButton(
                    text = stringResource(R.string.common_ok),
                    enabled = editText.isNotBlank() && !busy && !blocked,
                    onClick = {
                        busy = true
                        scope.launch {
                            try { if (store.updateQueue(id, "edit", editText, sessionId)) editingId = null }
                            finally { busy = false }
                        }
                    },
                    variant = DsButtonVariant.Info,
                )
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { editingId = null },
                    variant = DsButtonVariant.Ghost,
                )
            }
        }
    }
}

/**
 * The run statistics line under the transcript.
 *
 * Two of these numbers are aggregates on the wire, not averages: `ttftMs` is a *sum* across
 * `ttftSteps`, and there is no throughput field at all — it comes from decoded tokens over decode
 * milliseconds. Printing them raw would have shown a time-to-first-token of several minutes.
 */
@Composable
internal fun StatsFooter(
    stats: SessionStatsView?,
    usage: TokenUsageView?,
    modifier: Modifier = Modifier,
) {
    if (stats == null && usage == null) return
    val colors = DsTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val parts = buildList {
        stats?.let {
            add(pluralStringResource(R.plurals.chat_stats_turn_count, it.turns, it.turns) + " · " + pluralStringResource(R.plurals.trajectory_step_count, it.steps, it.steps))
            add(
                stringResource(
                    R.string.chat_stats_speed,
                    formatDurationMs(it.meanTtftMs),
                    it.tokensPerSecond?.let { rate -> String.format(java.util.Locale.US, "%.0f", rate) } ?: "—",
                ),
            )
        }
        usage?.cacheHitRatio?.let { add(stringResource(R.string.chat_stats_cache, (it * 100).toInt())) }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Text(
            parts.joinToString(" · "),
            style = DsType.statsText,
            color = colors.labelCaption,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
        )
        if (expanded) {
            stats?.let {
                DetailLine(
                    stringResource(
                        R.string.chat_stats_timing,
                        formatDurationMs(it.llmMs),
                        formatDurationMs(it.toolMs),
                    ),
                )
            }
            usage?.let {
                DetailLine(
                    stringResource(
                        R.string.chat_stats_tokens,
                        formatTokens(it.inputTokens),
                        formatTokens(it.outputTokens),
                    ),
                )
            }
        }
    }
}

@Composable
private fun DetailLine(text: String) {
    Text(
        text,
        style = DsType.statsText,
        color = DsTheme.colors.labelCaption,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The wire's placement words (`queued`, `steering`, `context`) in the reader's language. */
@Composable
private fun queuePlacementLabel(placement: String): String = when (placement) {
    com.labteto.dshmobile.core.wire.dto.InboxProjection.QUEUED -> stringResource(R.string.chat_queue_placement_queued)
    com.labteto.dshmobile.core.wire.dto.InboxProjection.STEERING -> stringResource(R.string.chat_queue_placement_steering)
    com.labteto.dshmobile.core.wire.dto.InboxProjection.CONTEXT -> stringResource(R.string.chat_queue_placement_context)
    else -> placement
}
