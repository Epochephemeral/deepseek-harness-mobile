package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.data.ArchivedTranscriptState
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.theme.DsSpacing
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Keep the child's existing raw row presentation, but compose only the visible disk-window rows. */
@Composable
internal fun SubagentTranscriptViewport(
    child: ConversationSnapshot,
    history: ArchivedTranscriptState,
    context: ChatNodeContext,
    onLoadOlder: () -> Unit,
    onLoadNewer: () -> Unit,
    onShowLatest: suspend () -> Boolean,
    onViewport: (Long?, Boolean) -> Unit,
    onRetryFollow: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val scope = rememberCoroutineScope()
    val latestHistory by rememberUpdatedState(history)
    val latestChild by rememberUpdatedState(child)
    val latestViewport by rememberUpdatedState(onViewport)
    LaunchedEffect(child.sessionId, listState, child.hasNewer) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            val exact = visible.firstOrNull { it.index == listState.firstVisibleItemIndex }
            val anchor = (exact?.key as? Long) ?: visible.firstNotNullOfOrNull { it.key as? Long }
            anchor to (!child.hasNewer && !listState.canScrollForward)
        }.collect { (anchor, atTail) -> latestViewport(anchor, atTail) }
    }
    Column(modifier) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp),
            state = listState, verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            if (child.hasMore) item(key = "older") {
                LoadOlderRow(history.loadingOlder, history.loadOlderFailed, true, onLoadOlder)
            }
            items(child.nodes, key = { it.seq }) { node -> ChatNodeItem(node, context.copy(hasNewer = child.hasNewer)) }
            if (child.hasNewer) item(key = "newer") {
                LoadNewerRow(history.loadingNewer, history.loadNewerFailed, onLoadNewer)
            }
        }
        if (child.hasNewer || listState.canScrollForward) DsButton(
            text = stringResource(R.string.chat_jump_latest), variant = DsButtonVariant.Ghost,
            size = DsButtonSize.Small,
            onClick = { scope.launch {
                if (onShowLatest()) {
                    // Publication crosses the store's collector before reaching this composition.
                    // A remembered latest value avoids waiting on the button's old captured snapshot.
                    snapshotFlow { !latestChild.hasNewer && latestHistory.snapshot?.hasNewer == false }.first { it }
                    withFrameNanos { }
                    listState.scrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                }
            } },
        )
        if (history.followFailed) DsButton(text = stringResource(R.string.common_retry),
            variant = DsButtonVariant.Ghost, size = DsButtonSize.Small, onClick = onRetryFollow)
    }
}
