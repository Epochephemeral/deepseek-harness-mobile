package com.labteto.dshmobile.core.session

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TurnOutlineTest {
    private fun outline(vararg starts: Long) = buildJsonArray {
        starts.forEachIndexed { index, seq -> add(buildJsonObject {
            put("turn", index + 1); put("seq", seq); put("prompt", "prompt $index"); put("response", "reply $index")
        }) }
    }
    private fun target(first: Long, last: Long, older: Boolean, value: JsonElement? = outline(10, 400, 900), latest: Long = 1400) =
        historyTurnLoadTarget(value, first, last, latest, older)

    @Test fun olderCompletesCurrentPartialTurnAcrossManyTransportBatches() {
        assertEquals(900L, target(1273, 1400, true))
        assertEquals(400L, target(850, 1400, true))
    }
    @Test fun olderAddsWholePreviousTurnWhenAlreadyAtBoundary() {
        assertEquals(400L, target(900, 1400, true))
        assertEquals(10L, target(400, 1400, true))
    }
    @Test fun olderCanRetainPreludeBeforeFirstTurn() {
        assertEquals(0L, target(10, 1400, true))
        assertNull(target(0, 1400, true))
    }
    @Test fun newerCompletesPartialTurnRatherThanOneRawBatch() {
        assertEquals(899L, target(400, 527, false))
        assertEquals(1400L, target(400, 1027, false))
    }
    @Test fun newerAddsWholeNextTurnWhenAlreadyAtBoundary() {
        assertEquals(899L, target(10, 399, false))
        assertEquals(1400L, target(10, 899, false))
    }
    @Test fun newerIncludesPreludeAndFirstTurnTogether() {
        assertEquals(399L, target(0, 3, false))
    }
    @Test fun unfinishedTurnEndsAtAuthoritativeKnownTail() {
        assertEquals(1000L, target(900, 950, false, latest = 1000))
        assertEquals(1000L, target(900, 1000, false, latest = 1000))
    }
    @Test fun fresherOutlineCannotTargetPastPinnedTail() {
        assertEquals(700L, target(400, 527, false, latest = 700))
        assertEquals(400L, target(600, 700, true, latest = 700))
        assertNull(target(0, 5, false, latest = 5))
    }
    @Test fun absentOrEmptyOutlineRequiresBoundaryDiscovery() {
        assertNull(target(400, 527, true, null))
        assertNull(target(400, 527, false, JsonArray(emptyList())))
        assertNull(target(400, 527, true, buildJsonObject { put("turns", outline(10, 400)) }))
    }
    @Test fun duplicateReorderedAndMalformedBoundariesAreRejected() {
        assertNull(parseTurnOutline(outline(10, 10)))
        assertNull(parseTurnOutline(outline(400, 10)))
        assertNull(parseTurnOutline(Json.parseToJsonElement("""[{"turn":1,"seq":"10","prompt":"a","response":"b"}]""")))
        assertNull(parseTurnOutline(Json.parseToJsonElement("""[{"turn":1,"seq":-1,"prompt":"a","response":"b"}]""")))
        assertNull(parseTurnOutline(Json.parseToJsonElement("""[{"turn":1,"seq":10,"prompt":"a"}]""")))
    }
    @Test fun directoryPreservesIdentityAndPublicSummaries() {
        val entries = requireNotNull(parseTurnOutline(outline(10, 400, 900)))
        assertEquals(listOf(1L, 2L, 3L), entries.map { it.turn })
        assertEquals(listOf(10L, 400L, 900L), entries.map { it.seq })
        assertEquals("prompt 1", entries[1].prompt)
        assertEquals("reply 1", entries[1].response)
    }
}
