package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.data.SessionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Subagent trees belong only in the dedicated panel, not in any drawer list projection. */
class SubagentTreeTest {

    private fun session(
        id: String,
        parent: String? = null,
        origin: String? = null,
        blank: Boolean = false,
        updatedAt: Long = 0L,
    ) = SessionRow(
        sessionId = id,
        title = id,
        running = false,
        blank = blank,
        parentSessionId = parent,
        origin = origin,
        cwd = null,
        agentPreset = null,
        updatedAt = updatedAt,
        pendingInteraction = null,
    )

    @Test
    fun `nested subagents are excluded while parents and regular forks remain`() {
        val rows = listOf(
            session("root"),
            session("child", parent = "root", origin = "subagent"),
            session("grandchild", parent = "child", origin = "subagent"),
            session("fork", parent = "root"),
            session("fork-from-child", parent = "child", origin = "fork"),
        )
        assertEquals(listOf("root", "fork", "fork-from-child"), drawerConversationRows(rows, emptySet()).map { it.sessionId })
        // Filtering navigation must not remove the store catalog used by the child panel.
        assertEquals(5, rows.size)
    }

    @Test
    fun `orphans and subagents without a parent never become ungrouped conversations`() {
        val rows = listOf(
            session("orphan", parent = "missing", origin = "subagent"),
            session("no-parent", origin = "subagent"),
            session("normal-orphan", parent = "missing"),
        )
        assertEquals(listOf("normal-orphan"), drawerConversationRows(rows, emptySet()).map { it.sessionId })
    }

    @Test
    fun `archived or blank ancestry does not make a child visible`() {
        val rows = listOf(
            session("archived-parent"),
            session("blank-parent", blank = true),
            session("archived-child", parent = "archived-parent", origin = "subagent"),
            session("blank-child", parent = "blank-parent", origin = "subagent"),
        )
        assertTrue(drawerConversationRows(rows, setOf("archived-parent")).isEmpty())
    }

    @Test
    fun `workspace pinned and recency projections share the filtered drawer catalog`() {
        val rows = listOf(
            session("root", updatedAt = 10),
            session("child", parent = "root", origin = "subagent", updatedAt = 100),
            session("fork", parent = "root", updatedAt = 20),
        )
        val listable = drawerConversationRows(rows, emptySet()).associateBy { it.sessionId }
        val workspaceOrder = listOf("child", "root", "fork")
        val pinnedOrder = listOf("child", "fork")
        assertEquals(listOf("root", "fork"), workspaceOrder.mapNotNull(listable::get).map { it.sessionId })
        assertEquals(listOf("fork"), pinnedOrder.mapNotNull(listable::get).map { it.sessionId })
        assertEquals(listOf("fork", "root"), listable.values.sortedByDescending { it.updatedAt }.map { it.sessionId })
    }

    @Test
    fun `archive excludes nested and orphan subagents but retains regular archived forks`() {
        val rows = listOf(
            session("root"),
            session("child", parent = "root", origin = "subagent"),
            session("grandchild", parent = "child", origin = "subagent"),
            session("orphan", parent = "missing", origin = "subagent"),
            session("fork", parent = "root"),
            session("active"),
        )
        val archived = rows.map { it.sessionId }.toSet() - "active"
        assertEquals(listOf("root", "fork"), archivedConversationRows(rows, archived).map { it.sessionId })
        assertTrue(archivedConversationRows(rows, archived, "child").isEmpty())
        assertEquals(listOf("fork"), archivedConversationRows(rows, archived, "FORK").map { it.sessionId })
    }

    @Test
    fun `unknown origins remain conversations and cycles need no tree traversal`() {
        val rows = listOf(
            session("a", parent = "b", origin = "subagent"),
            session("b", parent = "a", origin = "subagent"),
            session("future-origin", parent = "a", origin = "future"),
        )
        assertEquals(listOf("future-origin"), drawerConversationRows(rows, emptySet()).map { it.sessionId })
    }
}
