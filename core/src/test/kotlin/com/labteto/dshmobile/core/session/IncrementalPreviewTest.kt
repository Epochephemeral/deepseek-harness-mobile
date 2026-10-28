package com.labteto.dshmobile.core.session

import com.labteto.dshmobile.core.wire.dto.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class IncrementalPreviewTest {
    private fun event(type: String, seq: Long, data: String, op: String? = null) =
        SessionEventEnvelope(type, seq, seq, Json.parseToJsonElement(data), surfaceOp = op)
    private fun start(id: String = "a") = SessionAssistantStreamFrame.Start(attemptId = id, revision = 1, startedAfterSeq = 0, turn = 2, step = 1)
    private fun chunk(index: Int, data: JsonObject, id: String = "a") =
        SessionAssistantStreamFrame.Chunk(attemptId = id, revision = index + 2, index = index, time = index.toLong(), chunk = data)
    private fun text(index: Int, text: String, block: Int = 0) = chunk(index, buildJsonObject {
        put("type", "text-delta"); put("index", block); put("text", text)
    })
    private val journal = listOf(
        event("user/message", 9, """{"content":[{"type":"text","text":"hello"}]}"""),
        event("turn/start", 10, """{"turn":2}"""),
    )

    @Test fun `every accepted indexed block prefix matches original complete fold`() {
        val fold = EventFold("s")
        val durable = fold.fold(journal)
        val live = AssistantLiveState().apply { accept(start()) }
        val random = Random(31)
        repeat(300) { index ->
            val block = random.nextInt(4)
            val kind = listOf("text-delta", "reasoning-delta", "tool-call-delta", "usage", "block-end")[random.nextInt(5)]
            val data = buildJsonObject {
                put("type", kind); put("index", block)
                put("text", "片段-$index "); put("name", "tool"); put("id", "call-$block")
                put("argumentsDelta", "$index,")
                if (kind == "block-end") putJsonObject("block") {
                    put("type", "text"); put("text", "assembled-$index"); put("arguments", "{\"done\":$index}")
                }
            }
            live.accept(chunk(index, data))
            assertEquals("prefix $index", fold.fold(journal, live.transientEnvelopes()), live.transientSnapshot(durable))
            assertEquals((index + 1).toLong(), live.previewAppliedChunks)
        }
        val before = live.previewAppliedChunks
        val snapshot = live.transientSnapshot(durable)
        repeat(20) { assertSame(snapshot, live.transientSnapshot(durable)) }
        assertEquals(before, live.previewAppliedChunks)
        assertSame(durable.journal, snapshot.journal)
        assertSame(durable.nodes.first(), snapshot.nodes.first())
    }

    @Test fun `old snapshots and unchanged indexed blocks remain immutable and shared`() {
        val fold = EventFold("s")
        val durable = fold.fold(journal)
        val live = AssistantLiveState().apply { accept(start()); accept(text(0, "fixed", 0)); accept(text(1, "growing", 1)) }
        val old = live.transientSnapshot(durable)
        val oldNode = old.nodes.last() as AssistantMessageNode
        live.accept(text(2, " tail", 1))
        val next = live.transientSnapshot(durable)
        val nextNode = next.nodes.last() as AssistantMessageNode
        assertEquals("growing", oldNode.blocks[1].text)
        assertEquals("growing tail", nextNode.blocks[1].text)
        assertSame(oldNode.blocks[0], nextNode.blocks[0])
        assertNotSame(oldNode.blocks[1], nextNode.blocks[1])
        live.clear()
        assertSame(durable, live.transientSnapshot(durable))
        assertEquals("growing", oldNode.blocks[1].text)
    }

    @Test fun `surface replacement and rewind rebases match full fold without replaying chunks`() {
        val fold = EventFold("s")
        val live = AssistantLiveState().apply { accept(start()); accept(text(0, "live")) }
        val versions = listOf(journal,
            journal + event("user/message", 11, """{"content":[]}""", "replace"),
            journal + event("user/message", 11, """{"source":{"kind":"dsh-rewind"},"content":[]}""", "replace"),
            listOf(event("turn/start", 20, """{"turn":2}""")),
        )
        versions.forEach { events ->
            assertEquals(fold.fold(events, live.transientEnvelopes()), live.transientSnapshot(fold.fold(events)))
        }
        assertEquals(1L, live.previewAppliedChunks)
    }

    @Test fun `legacy durable block prefix and appended transient chunks merge once per baseline`() {
        val fold = EventFold("s")
        val events = journal + event("assistant/chunk", 11,
            """{"turn":2,"step":1,"chunk":{"type":"text-delta","index":0,"text":"legacy "}}""")
        val durable = fold.fold(events)
        val live = AssistantLiveState().apply { accept(start()) }
        repeat(100) { index ->
            live.accept(text(index, "part-$index "))
            assertEquals(fold.fold(events, live.transientEnvelopes()), live.transientSnapshot(durable))
        }
        val changed = events + event("session/title", 12, """{"title":"changed"}""")
        assertEquals(fold.fold(changed, live.transientEnvelopes()), live.transientSnapshot(fold.fold(changed)))
    }

    @Test fun `compact reconnect baseline is consumed once and live tail keeps original semantics`() {
        val baseline = com.labteto.dshmobile.core.wire.decodeFromJsonElement<SessionAssistantStreamBaseline>(
            Json.parseToJsonElement("""{"revision":7,"activeAttempt":{"attemptId":"a","startedAfterSeq":10,"turn":2,"step":1,"nextIndex":2,"stream":[{"type":"text-chunks","time0":1,"index":0,"dt":[1,1],"texts":["so"," far","ignored"]}]}}"""))
        val fold = EventFold("s")
        val durable = fold.fold(journal)
        val live = AssistantLiveState().apply { seed(baseline) }
        assertEquals(2L, live.previewAppliedChunks)
        assertEquals(fold.fold(journal, live.transientEnvelopes()), live.transientSnapshot(durable))
        live.accept(text(2, " tail"))
        assertEquals(3L, live.previewAppliedChunks)
        assertEquals(fold.fold(journal, live.transientEnvelopes()), live.transientSnapshot(durable))
        val snapshot = live.transientSnapshot(durable)
        repeat(100) { assertSame(snapshot, live.transientSnapshot(durable)) }
        assertEquals(3L, live.previewAppliedChunks)
    }

    @Test fun `long streams merge each chunk once without capping content`() {
        val durable = EventFold("s").fold(journal)
        val live = AssistantLiveState().apply { accept(start()) }
        repeat(5_000) { index ->
            live.accept(text(index, "x"))
            if (index % 10 == 0) live.transientSnapshot(durable)
        }
        assertEquals(5_000L, live.previewAppliedChunks)
        assertEquals(5_000, (live.transientSnapshot(durable).nodes.last() as AssistantMessageNode).blocks.single().text!!.length)
    }

    @Test fun `continuity faults new attempts and settlements retire cached previews`() {
        val durable = EventFold("s").fold(journal)
        val live = AssistantLiveState().apply { accept(start()); accept(text(0, "old")) }
        val old = live.transientSnapshot(durable)
        live.accept(text(3, "gap"))
        assertSame(durable, live.transientSnapshot(durable))
        live.accept(start("next"))
        live.accept(chunk(0, buildJsonObject { put("type", "text-delta"); put("text", "new") }, "next"))
        assertEquals("old", (old.nodes.last() as AssistantMessageNode).blocks.single().text)
        assertEquals("new", (live.transientSnapshot(durable).nodes.last() as AssistantMessageNode).blocks.single().text)
        live.acceptDurable("assistant/message", 2, 1, 15, null)
        assertSame(durable, live.transientSnapshot(durable))
    }
}
