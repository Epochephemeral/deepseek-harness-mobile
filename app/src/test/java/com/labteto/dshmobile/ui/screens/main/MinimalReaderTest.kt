package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.*
import org.junit.Test

class MinimalReaderTest {
    @Test fun `all public assistant segments survive completion with no process disclosure`() {
        val messages = (2L..4L).map { AssistantMessageNode(it, "a$it", 1, 1,
            listOf(ChatBlock("text", "public $it"), ChatBlock("reasoning", "private"))) }
        val rows = transcriptRows(listOf(TurnStartNode(1, 1)) + messages + TurnEndNode(5, 1, "completed"), false)
        assertEquals(messages, rows.filterIsInstance<TranscriptRow.Message>().map { it.node })
        assertTrue(rows.none { it is TranscriptRow.Process })
    }

    @Test fun `injected context and historical task goal updates do not become reading rows`() {
        val nodes = listOf(
            UserMessageNode(1, "u", listOf(ChatBlock("text", "human")), "user"),
            UserMessageNode(2, "s", listOf(ChatBlock("text", "internal")), "skill-invocation"),
            OtherNode(3, "request/context", JsonNull),
            OtherNode(4, "deliverables/presented", Json.parseToJsonElement("""{"files":[{"path":"report.pdf"}]}""")),
        )
        assertEquals(listOf(1L, 4L), renderableChatNodes(nodes, false).map { it.seq })
    }

    @Test fun `task status schema has conservative explicit defaults`() {
        val todos = parseTodos(Json.parseToJsonElement("""{"todos":[{"content":"one"},{"content":"two","status":"unexpected"},{"content":"three","status":"in_progress"},{"content":"four","status":"completed"}]}"""))!!
        assertEquals(listOf("pending", "pending", "in_progress", "completed"), todos.map { it.status })
    }

    @Test fun `paging only responds once per drag toward earlier edge`() {
        val gate = ReaderPagingGesture()
        assertFalse(gate.request(4f, true, true)) // semantics ScrollBy without a pointer
        gate.beginGesture()
        assertFalse(gate.request(0f, true, true)) // layout alone
        assertFalse(gate.request(-4f, true, true)) // toward latest
        assertFalse(gate.request(4f, false, true)) // not at earlier edge
        assertFalse(gate.request(4f, true, false)) // loading/restoring
        assertTrue(gate.request(4f, true, true))
        assertFalse(gate.request(4f, true, true)) // page arriving under stationary finger
        gate.endGesture()
        assertFalse(gate.request(4f, true, true)) // release/cancel disarms semantics and fling emissions
        gate.beginGesture()
        assertTrue(gate.request(4f, true, true))
    }
}
