package com.labteto.dshmobile.core.session

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReaderTurnTimelineTest {
    private fun event(seq: Long, type: String, data: String = "{}") =
        SessionEventEnvelope(type, seq, seq * 10, Json.parseToJsonElement(data))
    private fun skip(seq: Long) = SessionEventEnvelope("reader/skip", seq, seq * 10, JsonNull)
    private fun start(seq: Long, turn: Int) = event(seq, "turn/start", """{"turn":$turn}""")
    private fun end(seq: Long, turn: Int, reason: String = "completed") =
        event(seq, "turn/end", """{"turn":$turn,"reason":{"kind":"$reason"}}""")
    private fun user(seq: Long, text: String = "prompt $seq") = event(seq, "user/message",
        """{"id":"u$seq","source":{"kind":"user"},"content":[{"type":"text","text":"$text"}]}""")
    private fun assistant(seq: Long, turn: Int, step: Int = 1) = event(seq, "assistant/message",
        """{"turn":$turn,"step":$step,"message":{"id":"a$seq","content":[{"type":"text","text":"reply $seq"}]}}""")
    private fun call(seq: Long, turn: Int = 1) = event(seq, "tool/call",
        """{"turn":$turn,"step":1,"callId":"c$seq","name":"bash","description":"Inspect $seq","reader":{"argumentsOmitted":true}}""")
    private fun turns() = listOf(start(0, 1), user(1), call(2), skip(3), assistant(4, 1), end(5, 1),
        start(6, 2), user(7), assistant(8, 2), end(9, 2, "interrupted"))

    @Test fun prependAndAppendMatchModernFoldWithoutKeepingItsRawJournal() {
        val source = turns()
        val timeline = ReaderTurnTimeline("s")
        timeline.mergeRange(source.subList(3, 7))
        val middle = timeline.snapshot()
        timeline.mergeRange(source.take(3))
        timeline.mergeRange(source.drop(7))
        val actual = timeline.snapshot()
        assertEquals(EventFold("s").fold(source).nodes, actual.nodes)
        assertEquals(0L, actual.loadedFromSeq); assertEquals(9L, actual.loadedThroughSeq)
        assertTrue(actual.journal.isEmpty()); assertTrue(actual.effectiveSurface.isEmpty())
        assertEquals(8L, actual.durableSettlements.last().seq)
        assertEquals(80L, actual.eventTimes[8])
        assertEquals(3L, middle.loadedFromSeq); assertEquals(6L, middle.loadedThroughSeq)
        assertEquals(6L, middle.lastSeq)
    }

    @Test fun liveAppendOnlyDecodesNewRecordAndLeavesCompletedTurnNodesUnchanged() {
        val timeline = ReaderTurnTimeline("s")
        val source = turns().take(8)
        timeline.mergeRange(source)
        val old = timeline.snapshot()
        val work = timeline.decodedRecordCount
        val blocks = timeline.rebuiltTurnCount
        val appended = timeline.append(assistant(8, 2))
        assertEquals(work + 1, timeline.decodedRecordCount)
        assertEquals(blocks + 1, timeline.rebuiltTurnCount)
        old.nodes.filter { it.seq < 6 }.forEach { node -> assertSame(node, appended.nodes.first { it.seq == node.seq }) }
        assertEquals(7L, old.loadedThroughSeq)
        assertEquals(8L, appended.loadedThroughSeq)
    }

    @Test fun hundredThousandSkippedPositionsCostCoverageNotRetainedRecordsOrRefolds() {
        val timeline = ReaderTurnTimeline("s")
        timeline.mergeRange(listOf(start(0, 1), user(1)))
        val nodes = timeline.snapshot().nodes
        val work = timeline.rebuiltTurnCount
        for (first in 2L until 100_002L step 128) {
            timeline.mergeRange((first until minOf(first + 128, 100_002)).map(::skip))
        }
        assertEquals(2, timeline.retainedRecordCount)
        assertEquals(2L, timeline.decodedRecordCount)
        assertEquals(work, timeline.rebuiltTurnCount)
        assertSame(nodes, timeline.snapshot().nodes)
        assertEquals(100_001L, timeline.lastSeq)
        assertTrue(timeline.snapshot().journal.isEmpty())
    }

    @Test fun eightThousandPositionsDoNotRefoldCompletedTurnsOnLiveUpdates() {
        val timeline = ReaderTurnTimeline("s")
        val source = (0L until 8224).map { seq -> when (seq % 512) {
            0L -> start(seq, (seq / 512 + 1).toInt())
            1L -> user(seq)
            2L -> assistant(seq, (seq / 512 + 1).toInt())
            511L -> end(seq, (seq / 512 + 1).toInt())
            else -> skip(seq)
        } }
        source.chunked(128).forEach(timeline::mergeRange)
        assertEquals(EventFold("s").fold(source).nodes, timeline.snapshot().nodes)
        val work = timeline.decodedRecordCount
        val blocks = timeline.rebuiltTurnCount
        timeline.append(call(8224, 17))
        assertEquals(work + 1, timeline.decodedRecordCount)
        assertEquals(blocks + 1, timeline.rebuiltTurnCount)
        assertTrue(timeline.retainedRecordCount < 100)
        assertEquals(0L, timeline.firstSeq); assertEquals(8224L, timeline.lastSeq)
    }

    @Test fun overlapCorrectionsReplaceNodesAndCanRemoveThemWithoutChangingCoverage() {
        val timeline = ReaderTurnTimeline("s")
        timeline.mergeRange(turns())
        timeline.append(user(7, "corrected"))
        assertEquals("corrected", timeline.snapshot().nodes.filterIsInstance<UserMessageNode>().last().previewText)
        timeline.append(skip(7))
        assertFalse(timeline.snapshot().nodes.any { it.seq == 7L })
        assertEquals(0L, timeline.firstSeq); assertEquals(9L, timeline.lastSeq)
    }

    @Test fun interruptedTailReconcilesAcrossPrependAndCanBeCorrected() {
        val timeline = ReaderTurnTimeline("s")
        timeline.mergeRange(listOf(skip(3), end(4, 1, "error")))
        timeline.mergeRange(listOf(start(0, 1), user(1), assistant(2, 1)))
        assertTrue(timeline.snapshot().nodes.filterIsInstance<AssistantMessageNode>().single().interrupted)
        timeline.append(end(4, 1))
        assertFalse(timeline.snapshot().nodes.filterIsInstance<AssistantMessageNode>().single().interrupted)
    }

    @Test fun authoritativeTruncationRemovesInvalidRowsAndInferredInterruptionOnly() {
        val timeline = ReaderTurnTimeline("s")
        timeline.mergeRange(turns())
        val old = timeline.snapshot()
        timeline.truncateAuthoritative(8)
        assertEquals(8L, timeline.lastSeq)
        assertFalse(timeline.snapshot().nodes.filterIsInstance<AssistantMessageNode>().last().interrupted)
        assertTrue(old.nodes.filterIsInstance<AssistantMessageNode>().last().interrupted)
        timeline.truncateAuthoritative(-1)
        assertNull(timeline.firstSeq); assertNull(timeline.lastSeq)
        assertTrue(timeline.snapshot().nodes.isEmpty())
    }

    @Test fun failedOrDisjointBatchCannotPartlyMutatePublishedState() {
        val timeline = ReaderTurnTimeline("s")
        timeline.mergeRange(listOf(start(0, 1), user(1)))
        val old = timeline.snapshot()
        assertThrows(IllegalArgumentException::class.java) { timeline.mergeRange(listOf(skip(10))) }
        assertThrows(IllegalArgumentException::class.java) { timeline.mergeRange(listOf(skip(2), skip(4))) }
        assertThrows(IllegalArgumentException::class.java) { timeline.append(event(2, "assistant/chunk")) }
        assertThrows(Exception::class.java) { timeline.mergeRange(listOf(call(2), event(3, "turn/start", "null"))) }
        assertSame(old, timeline.snapshot())
        assertEquals(1L, timeline.lastSeq)
    }

    @Test fun emptyPublicAttemptStillReconcilesSettlementAndSkipCoverage() {
        val timeline = ReaderTurnTimeline("s")
        val attempt = event(10, "assistant/attempt", """{"turn":2,"step":3}""")
        timeline.mergeRange(listOf(attempt, skip(11)))
        assertTrue(timeline.snapshot().nodes.isEmpty())
        assertEquals(DurableSettlement("assistant/attempt", 2, 3, 10, null), timeline.snapshot().durableSettlements.single())
        assertEquals(10L, timeline.firstSeq); assertEquals(11L, timeline.lastSeq)
    }
}
