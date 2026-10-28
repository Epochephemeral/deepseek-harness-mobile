package com.labteto.dshmobile.core.session

import com.labteto.dshmobile.core.wire.WireJson
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.*
import org.junit.Test

class ReaderFoldTest {
    private fun event(seq: Long, type: String, data: String) = SessionEventEnvelope(type, seq, 0, WireJson.parseToJsonElement(data))

    @Test fun skipsPreserveCoverageWithoutVisibleRowsAndAttemptsAreNotFinalAnswers() {
        val snapshot = EventFold("reader").fold(listOf(
            SessionEventEnvelope("reader/skip", 0, 0, JsonNull),
            event(1, "assistant/attempt", """{"turn":1,"step":1,"message":{"content":[{"type":"text","text":"public interrupted text"}]},"reader":{"uncommittedAttempt":true}}"""),
            event(2, "tool/call", """{"callId":"c","name":"bash","description":"Inspect files","reader":{"argumentsOmitted":true}}"""),
            event(3, "tool/result", """{"message":{"role":"tool","callId":"c","content":[],"isError":false},"reader":{"outputOmitted":true,"status":"completed"}}"""),
        ))
        assertFalse(snapshot.nodes.any { it is OtherNode })
        val attempt = snapshot.nodes.filterIsInstance<AssistantMessageNode>().single()
        assertTrue(attempt.uncommittedAttempt)
        assertTrue(attempt.interrupted)
        assertEquals("public interrupted text", attempt.plainText)
        val call = snapshot.nodes.filterIsInstance<ToolCallNode>().single()
        assertEquals("Inspect files", call.description)
        assertEquals("", call.arguments)
        assertTrue(snapshot.nodes.filterIsInstance<ToolResultNode>().single().outputOmitted)
    }
}
