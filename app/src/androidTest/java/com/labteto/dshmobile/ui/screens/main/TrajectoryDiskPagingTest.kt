package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.core.session.TurnStartNode
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TrajectoryDiskPagingTest {
    @get:Rule val compose = createComposeRule()

    private fun window(first: Long, last: Long, raw: Boolean = false, newer: Boolean = true): ConversationSnapshot {
        val nodes: List<ChatNode> = listOf(TurnStartNode(first - 1, 1)) + (first..last).map { UserMessageNode(it, "u$it", listOf(ChatBlock("text", "row $it")), "user") }
        val journal = if (!raw) emptyList() else listOf(SessionEventEnvelope("turn/start", first - 1, first - 1,
            buildJsonObject { put("turn", 1) })) + (first..last).map { seq ->
            SessionEventEnvelope("user/message", seq, seq, buildJsonObject {
                put("id", "u$seq")
                put("content", "row $seq")
                putJsonObject("source") { put("kind", "user") }
            })
        }
        return ConversationSnapshot(sessionId = "disk-ledger", nodes = nodes, journal = journal, hasNewer = newer)
    }

    private class Harness(initial: ConversationSnapshot) {
        var snapshot by mutableStateOf(initial)
        var loadingNewer by mutableStateOf(false)
        var loadNewerFailed by mutableStateOf(false)
        var newerRequests = 0
        var reportedAnchor: Long? = null
        var reportedAtTail = false
        lateinit var listState: LazyListState
    }

    private fun harness(initial: ConversationSnapshot): Harness {
        val h = Harness(initial)
        compose.setContent {
            h.listState = rememberLazyListState()
            DshTheme {
                TrajectoryTab(
                    conversation = h.snapshot,
                    stats = null, usage = null, cwd = null,
                    listState = h.listState,
                    modifier = Modifier.testTag(TRAJECTORY),
                    loadingNewer = h.loadingNewer,
                    loadNewerFailed = h.loadNewerFailed,
                    onLoadNewer = { h.newerRequests++; h.loadingNewer = true },
                    onHistoryViewport = { anchor, atTail -> h.reportedAnchor = anchor; h.reportedAtTail = atTail },
                )
            }
        }
        compose.waitForIdle()
        return h
    }

    private data class Anchor(val key: String, val offset: Int) {
        val seq: Long get() = key.substringAfter('-').toLong()
    }
    private fun anchor(h: Harness, prefix: String): Anchor = compose.runOnIdle {
        val item = h.listState.layoutInfo.visibleItemsInfo.first { (it.key as? String)?.startsWith(prefix) == true }
        Anchor(item.key as String, item.offset)
    }
    private fun assertAnchor(h: Harness, before: Anchor) = compose.runOnIdle {
        val item = h.listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == before.key }
        assertNotNull("${before.key} left the viewport", item)
        assertEquals("${before.key} moved", before.offset, item!!.offset)
        assertEquals(before.seq, h.reportedAnchor)
    }
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun newerDiskPageButtonIsAccessibleAndSharesLoadingAndRetryStates() {
        val h = harness(window(20, 21))
        compose.runOnIdle { assertEquals(0, h.newerRequests) }
        compose.onNodeWithText(label(R.string.chat_load_newer)).assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.chat_loading_newer)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, h.newerRequests)
            h.loadingNewer = false
            h.loadNewerFailed = true
        }
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.common_retry)).assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, h.newerRequests) }
    }

    @Test fun ledgerViewportReportsSequenceKeysAndPreservesTheReadingAnchorWhenDiskWindowShifts() {
        val h = harness(window(20, 139))
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(41)
        compose.waitForIdle()
        val before = anchor(h, "n-")
        compose.runOnIdle {
            assertEquals(before.seq, h.reportedAnchor)
            assertFalse(h.reportedAtTail)
            h.snapshot = window(10, 129)
        }
        compose.waitForIdle()
        assertAnchor(h, before)
        compose.runOnIdle { assertEquals(0, h.newerRequests) }
    }

    @Test fun expandedRawJournalReportsItsOwnSequenceKeysAndKeepsThemAcrossWindowReplacement() {
        val h = harness(window(20, 139, raw = true))
        val rawHeaderIndex = compose.runOnIdle { h.listState.layoutInfo.totalItemsCount - 1 }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(rawHeaderIndex)
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.trajectory_tab_raw)).performClick()
        compose.waitForIdle()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(rawHeaderIndex + 16)
        compose.waitForIdle()
        val before = anchor(h, "raw-")
        compose.runOnIdle {
            assertEquals(before.seq, h.reportedAnchor)
            assertFalse(h.reportedAtTail)
            h.snapshot = window(10, 129, raw = true)
        }
        compose.waitForIdle()
        assertAnchor(h, before)
    }

    @Test fun onlyTheNormalListEndWithoutANewerDiskPageReportsFollowingLatest() {
        val h = harness(window(20, 139, newer = false))
        compose.runOnIdle { assertFalse(h.reportedAtTail) }
        val last = compose.runOnIdle { h.listState.layoutInfo.totalItemsCount - 1 }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(last)
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(h.reportedAtTail)
            h.snapshot = h.snapshot.copy(hasNewer = true)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(h.reportedAtTail) }
    }

    private companion object { const val TRAJECTORY = "disk-trajectory" }
}
