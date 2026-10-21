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
import com.labteto.dshmobile.ui.components.relativeTime
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * The prompts the reader sent in this session, so they can jump back to something asked a hundred
 * screens ago without dragging through the replies it produced.
 *
 * One row per prompt, because a prompt is the only unit here with a stable identity: the transcript
 * draws a turn as a run of assistant steps and tool cards, and listing those would fill the sheet
 * with the machinery the reader is trying to scroll past. The transcript is still where the answers
 * live — this sheet only answers "where did I ask that".
 *
 * The transcript is the only place the rows exist, and the sheet has no access to its layout, so a
 * pick is reported as the node's sequence and [ChatTranscript] turns that into a scroll. That keeps
 * the sheet a pure list of what was asked: it never has to know how tool calls group, which rows are
 * still loading, or where the streamed tail currently sits.
 *
 * Search is offered only once the list is long enough that scanning it would be slower than typing,
 * matching how the model sheet decides the same thing.
 *
 * The list is the loaded history, not the whole session: a session opens on its last sixty
 * messages, which in a tool-heavy one is only a few prompts. While older history exists, the foot
 * of the sheet pages it in — the transcript's own row, so loading, failure and retry read the same
 * in both places — and the prompts it carries join the list below the ones already shown.
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
) {
    val colors = DsTheme.colors
    var query by remember { mutableStateOf("") }
    val shown = remember(entries, query) {
        if (query.isBlank()) entries else entries.filter { historyMatches(query, it.text) }
    }
    DsBottomSheet(
        title = stringResource(R.string.chat_history_title),
        subtitle = if (entries.isEmpty()) null else entries.size.toString(),
        onDismiss = onDismiss,
    ) {
        if (entries.size > SEARCH_FROM) ChatSearchField(query, { query = it })
        when {
            // Nothing loaded yet is not "nothing sent" while there is history left to page in.
            entries.isEmpty() -> if (!hasMore) {
                Text(
                    stringResource(R.string.chat_history_empty),
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
                    HistoryRow(entry = entry, onClick = { onPick(entry.seq) })
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
}

/** One prompt: when it was sent, and enough of it to recognise. */
@Composable
private fun HistoryRow(entry: HistoryEntry, onClick: () -> Unit) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = DsSpacing.small),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            entry.timeMs?.let { timestamp ->
                Text(
                    relativeTime(timestamp),
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
                Spacer(Modifier.height(DsSpacing.tiny))
            }
            val text = entry.text.ifEmpty { stringResource(R.string.chat_history_image) }
            Text(
                text,
                style = DsType.small13,
                color = colors.labelPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
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
