package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.UserMessageNode
import org.junit.Assert.*
import org.junit.Test

class HistoryViewportRestoreTest {
    private fun window(first: Long, last: Long) = ConversationSnapshot("s", nodes = (first..last).map {
        UserMessageNode(it, "u$it", listOf(ChatBlock("text", "$it")), "user")
    }, hasNewer = true)

    @Test fun `away intent survives idle layout clamp and gate restarts`() {
        var follow = transcriptFollowAfterUserScroll(true, 1f, true)
        assertFalse(follow) // immediately, even before the away scroll is measured
        repeat(4) {
            follow = transcriptFollowAfterUserScroll(follow, 0f, true)
            assertFalse(follow) // being clamped to index zero is not user intent
        }
        assertFalse(transcriptFollowAfterUserScroll(follow, -5f, false))
        assertTrue(transcriptFollowAfterUserScroll(follow, -5f, true))
    }

    @Test fun `streaming key maps to provisional sequence not a neighboring durable row`() {
        assertEquals(1000L, historySequenceOfKey("streaming-tail", 1000L))
        assertNull(historySequenceOfKey("streaming-tail"))
        assertNull(historySequenceOfKey("load-newer", 1000L))
        assertEquals(42L, historySequenceOfKey(42L, 1000L))
    }

    @Test fun `fold anchor follows exact child membership rather than nearest turn start`() {
        fun row(seq: Long) = TranscriptRow.Message(UserMessageNode(seq, "u$seq", listOf(ChatBlock("text", "$seq")), "user"))
        val rows = listOf(row(100), TranscriptRow.Process(listOf(row(30), row(70)), 2, 10), row(5))
        assertEquals(1, transcriptFoldAnchorIndex(rows, 70L))
        assertEquals(1, transcriptFoldAnchorIndex(rows, 30L))
        assertNull(transcriptFoldAnchorIndex(rows, 100L)) // surviving exact identity
        assertNull(transcriptFoldAnchorIndex(rows, 10L)) // already a Process
        assertNull(transcriptFoldAnchorIndex(rows, 69L)) // no proximity guessing
        assertNull(transcriptFoldAnchorIndex(rows, "load-newer"))
    }

    @Test fun `restore resolves actual identity rather than old numeric index with paging sentinels`() {
        val position = HistoryReadingPosition(50L, 17, 50L, false)
        assertEquals(2, historyRestoreIndex(listOf("load-newer", 60L, 50L, 40L, "load-older"), position, true))
        assertEquals(4, historyRestoreIndex(listOf("load-newer", 80L, 70L, 60L, 50L, 40L), position, true))
        assertEquals(17, position.scrollOffset)
    }

    @Test fun `raw journal restore never aliases a ledger row with the same sequence`() {
        val position = HistoryReadingPosition("raw-40", 3, 40, false, rawExpanded = true)
        assertEquals(3, historyRestoreIndex(listOf("n-40", "raw-header", "raw-39", "raw-40"), position, false))
        assertEquals(2, historyRestoreIndex(listOf("n-40", "raw-header", "raw-41"), position, false))
    }

    @Test fun `a following bookmark targets the appropriate list end`() {
        val position = HistoryReadingPosition(40L, 0, 40, true)
        val keys: List<Any> = listOf(60L, 50L, 40L)
        assertEquals(0, historyRestoreIndex(keys, position, true))
        assertEquals(2, historyRestoreIndex(keys, position, false))
    }

    @Test fun `restore accepts a cumulatively widened range before lazy keys are available`() {
        val request = HistoryViewportRestoreRequest(1, HistoryReadingPosition(50L, 19, 50, false), "s", 20, 79)
        // ChatScreen checks readiness before supplying the pane's lazy keys.
        assertTrue(historyRestoreWindowReady(window(0, 99), emptyList(), request))
        assertFalse(historyRestoreWindowReady(window(0, 78), emptyList(), request))
        assertFalse(historyRestoreWindowReady(window(21, 99), emptyList(), request))
        assertFalse(historyRestoreWindowReady(window(0, 99).copy(sessionId = "another"), emptyList(), request))
    }

    @Test fun `restore request waits for nonoverlapping window publication`() {
        val request = HistoryViewportRestoreRequest(1, HistoryReadingPosition(50L, 19, 50, false), "s", 20, 79)
        val wrong = window(100, 159)
        assertFalse(historyRestoreWindowReady(wrong, wrong.nodes.map { it.seq }, request))
        val right = window(20, 79)
        assertTrue(historyRestoreWindowReady(right, right.nodes.map { it.seq }, request))
        assertFalse(historyRestoreWindowReady(right.copy(sessionId = "another"), right.nodes.map { it.seq }, request))
    }
}
