package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.LocalFileOpener
import com.labteto.dshmobile.ui.components.clickableWithHold
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The files turn [turn] changed, in the order it first touched them.
 *
 * `workspace/changes` carries only `{turn}`: it is the harness saying "this turn's file changes
 * are settled", and the web client answers it by listing what the turn's write and edit calls
 * produced. Only calls whose result came back without an error count, so a refused or failed edit
 * does not show up as a change.
 */
internal fun changedFiles(nodes: List<ChatNode>, turn: Int): List<String> {
    val succeeded = nodes.asSequence()
        .filterIsInstance<ToolResultNode>()
        .filter { it.turn == turn && !it.isError }
        .mapTo(HashSet()) { it.callId }
    return nodes.asSequence()
        .filterIsInstance<ToolCallNode>()
        .filter { it.turn == turn && it.callId in succeeded }
        .filter { classifyTool(it.name).let { v -> v == ToolRowVariant.Write || v == ToolRowVariant.Edit } }
        .mapNotNull { call -> changedPath(call.arguments) }
        .distinct()
        .toList()
}

private fun changedPath(argumentsJson: String): String? {
    val arguments = runCatching { com.labteto.dshmobile.core.wire.WireJson.parseToJsonElement(argumentsJson).jsonObject }
        .getOrNull() ?: return null
    return listOf("file_path", "path")
        .firstNotNullOfOrNull { key -> (arguments[key] as? JsonPrimitive)?.takeIf { it.isString }?.content }
        ?.takeIf { it.isNotBlank() }
}

/** The `turn` a `workspace/changes` event closes, or null for a payload this build cannot read. */
internal fun changesTurn(data: kotlinx.serialization.json.JsonElement): Int? =
    ((data as? JsonObject)?.get("turn") as? JsonPrimitive)?.content?.toIntOrNull()

/**
 * "Changed 2 files", then one pill per file — behind a fold that starts closed.
 *
 * A turn that touched a dozen files used to end with a dozen pills stacked over the reply, which is
 * the one thing the reader is trying to read. The count is the part that belongs in the transcript;
 * the list is reference material, wanted only when the reader goes looking for it, so the row opens
 * on a tap. A pill opens the file in the workspace preview.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChangedFilesRow(
    nodes: List<ChatNode>,
    turn: Int,
    cwd: String?,
    onMention: ((String, Boolean) -> Unit)? = null,
) {
    val files = remember(nodes, turn) { changedFiles(nodes, turn) }
    if (files.isEmpty()) return
    val colors = DsTheme.colors
    val open = LocalFileOpener.current
    var held by remember(turn) { mutableStateOf<String?>(null) }
    // Closed by default, and remembered per turn so opening one turn's list does not open the next.
    var expanded by remember(turn) { mutableStateOf(false) }
    val holdLabel = stringResource(R.string.hold_file_actions)
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = DsAnimations.chevron,
        label = "changedFilesChevron",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.bgLayer1,
        border = BorderStroke(1.dp, colors.borderL2),
    ) {
        Column {
            // The padding sits inside the clickable so the whole header takes the tap, not just the
            // line of text in the middle of it.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.labelTertiary,
                    modifier = Modifier
                        .size(14.dp)
                        .graphicsLayer { rotationZ = rotation },
                )
                Icon(
                    FeatherIcons.Edit3,
                    contentDescription = null,
                    tint = colors.success,
                    modifier = Modifier.padding(start = 6.dp).size(14.dp),
                )
                Text(
                    pluralStringResource(R.plurals.chat_changed_files, files.size, files.size),
                    style = DsType.small13Strong,
                    color = colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(DsAnimations.expand) + fadeIn(DsAnimations.fade),
                exit = shrinkVertically(DsAnimations.expand) + fadeOut(DsAnimations.fade),
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    files.forEach { path ->
                        Row(
                            Modifier
                                .heightIn(min = 32.dp)
                                .clip(DsShapes.pill)
                                .background(colors.bgModulePlatform)
                                .border(1.dp, colors.borderL3, DsShapes.pill)
                                .clickableWithHold(holdLabel, onHold = { held = path }, role = Role.Button) { open(path) }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(FeatherIcons.FileText, null, Modifier.size(13.dp), tint = colors.labelSecondary)
                            Text(
                                technicalDisplay(relativizeToCwd(path, cwd)),
                                style = DsType.small13.copy(fontFamily = DsType.codeFont),
                                color = colors.labelPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    held?.let { path ->
        FileHoldMenu(
            path = mentionPath(path, cwd),
            folder = false,
            onMention = onMention,
            copyPath = path,
            onDismiss = { held = null },
        )
    }
}
