package com.labteto.dshmobile.core.session

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TransientFoldReuseTest {
    private fun event(type: String, seq: Long, body: String, op: String? = null) =
        SessionEventEnvelope(type, seq, seq, Json.parseToJsonElement(body), surfaceOp = op)
    private val transient = listOf(
        event("assistant/chunk", 0, """{"turn":2,"step":1,"chunk":{"type":"text-delta","index":0,"text":"live reply"}}"""),
    )

    @Test fun `live preview reuses journal and durable nodes and matches full fold`() {
        val events = listOf(
            event("user/message", 10, """{"content":[{"type":"text","text":"hello"}]}"""),
            event("turn/start", 11, """{"turn":2}"""),
        )
        val fold = EventFold("s")
        val durable = fold.fold(events)
        val composed = fold.withTransient(durable, transient)
        assertEquals(fold.fold(events, transient), composed)
        assertSame(durable.journal, composed.journal)
        assertSame(durable.nodes.first(), composed.nodes.first())
        assertSame(durable, fold.withTransient(durable, emptyList()))
        assertEquals(11L, composed.lastSeq)
        assertFalse(composed.gap)
    }

    @Test fun `surface-only tail preserves original provisional row sequence`() {
        val events = listOf(
            event("turn/start", 20, """{"turn":2}"""),
            event("user/message", 21, """{"content":[]}""", op = "replace"),
        )
        val fold = EventFold("s")
        assertEquals(fold.fold(events, transient), fold.withTransient(fold.fold(events), transient))
    }

    @Test fun `legacy durable chunks use original merge semantics`() {
        val events = listOf(
            event("turn/start", 0, """{"turn":2}"""),
            event("assistant/chunk", 1, """{"turn":2,"step":1,"chunk":{"type":"text-delta","index":0,"text":"prefix "}}"""),
        )
        val fold = EventFold("s")
        assertEquals(fold.fold(events, transient), fold.withTransient(fold.fold(events), transient))
    }
}
