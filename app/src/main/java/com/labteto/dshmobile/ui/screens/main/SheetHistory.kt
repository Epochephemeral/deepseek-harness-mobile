package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.clickableWithHold
import com.labteto.dshmobile.ui.components.relativeTime
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * Whole-session turn directory on projection-capable hosts, loaded prompts on older hosts.
 * The owner seeks and resolves a renderable row before closing; failures remain retryable here.
 * Paging controls are only needed for the older-host fallback, in either direction.
 */
@Composable
internal fun HistorySheet(
    entries: List<HistoryEntry>,
    hasMore: Boolean,
    loadingOlder: Boolean,
    loadOlderFailed: Boolean,
    onLoadOlder: () -> Unit,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
    /** Puts a listed prompt back in the composer; null offers only the copy. */
    onEdit: ((String) -> Unit)? = null,
    /** Allows the owner to restore the covered reading window before editing the composer. */
    onDismissThen: ((() -> Unit) -> Unit)? = null,
    hasNewer: Boolean = false,
    loadingNewer: Boolean = false,
    loadNewerFailed: Boolean = false,
    onLoadNewer: () -> Unit = {},
    seeking: Boolean = false,
    seekFailed: Boolean = false,
    onRetrySeek: () -> Unit = {},
) {
    val colors = DsTheme.colors
    var query by remember { mutableStateOf("") }
    var held by remember { mutableStateOf<HistoryEntry?>(null) }
    val shown = remember(entries, query) {
        if (query.isBlank()) entries else entries.filter { historyMatches(query, "${it.text} ${it.response}") }
    }
    DsBottomSheet(
        title = stringResource(R.string.chat_history_title),
        subtitle = if (entries.isEmpty()) null else entries.size.toString(),
        onDismiss = onDismiss,
    ) {
        if (entries.size > SEARCH_FROM) ChatSearchField(query, { query = it })
        if (seeking || seekFailed) {
            androidx.compose.material3.TextButton(onClick = onRetrySeek, enabled = !seeking) {
                Text(stringResource(if (seeking) R.string.history_turn_loading else R.string.history_turn_failed))
            }
        }
        if (hasNewer) LoadNewerRow(loadingNewer, loadNewerFailed, onLoadNewer)
        when {
            // Nothing loaded yet is not "nothing sent" while there is history left to page in.
            entries.isEmpty() -> {
                Text(
                    stringResource(if (hasMore || hasNewer) R.string.history_window_empty else R.string.chat_history_empty),
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
            }
            shown.isEmpty() -> Text(
                stringResource(R.string.chat_history_no_match),
                style = DsType.small13,
                color = colors.labelSecondary,
            )
            else -> LazyColumn(
                modifier = Modifier.heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                items(shown, key = { it.seq }) { entry ->
                    HistoryRow(
                        entry = entry,
                        onClick = { onPick(entry.seq) },
                        enabled = !seeking,
                        // Outline previews are clipped: never offer them as an editable full prompt.
                        onHold = if (entry.promptLoaded && entry.text.isNotEmpty() && !seeking) ({ held = entry }) else null,
                    )
                }
            }
        }
        if (hasMore) {
            LoadOlderRow(
                loading = loadingOlder,
                failed = loadOlderFailed,
                offerManual = true,
                onRetry = onLoadOlder,
            )
        }
    }
    held?.let { entry ->
        PromptHoldMenu(
            text = entry.text,
            onEdit = onEdit?.let { edit -> { text: String ->
                if (onDismissThen != null) onDismissThen { edit(text) }
                else { onDismiss(); edit(text) }
            } },
            onDismiss = { held = null },
        )
    }
}

/** One prompt: when it was sent, and enough of it to recognise. */
@Composable
private fun HistoryRow(entry: HistoryEntry, onClick: () -> Unit, onHold: (() -> Unit)?, enabled: Boolean = true) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (onHold != null) Modifier.clickableWithHold(stringResource(R.string.hold_message_actions), onHold = onHold, onClick = onClick)
                else Modifier.clickable(enabled = enabled, onClick = onClick),
            )
            .padding(vertical = DsSpacing.small),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            entry.turn?.let { turn ->
                Text(stringResource(R.string.history_turn_label, turn), style = DsType.caption11, color = colors.labelSecondary)
                Spacer(Modifier.height(DsSpacing.tiny))
            }
            entry.timeMs?.let { timestamp ->
                Text(
                    relativeTime(timestamp),
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
                Spacer(Modifier.height(DsSpacing.tiny))
            }
            val text = entry.text.ifEmpty {
                stringResource(if (entry.hasImage) R.string.chat_history_image else R.string.history_turn_no_prompt)
            }
            Text(
                text,
                style = DsType.small13,
                color = colors.labelPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.response.isNotBlank()) {
                Spacer(Modifier.height(DsSpacing.tiny))
                Text(entry.response, style = DsType.caption11, color = colors.labelSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (entry.hasImage && entry.text.isNotEmpty()) {
                Spacer(Modifier.height(DsSpacing.tiny))
                DsPill(text = stringResource(R.string.chat_history_image))
            }
        }
    }
}

/**
 * Below this many prompts the whole list fits in a thumb-flick, so a search field would cost more
 * attention than it saves.
 */
private const val SEARCH_FROM = 8
