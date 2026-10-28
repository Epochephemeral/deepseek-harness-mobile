package com.labteto.dshmobile.core.notify

import com.labteto.dshmobile.core.session.SessionEventEnvelope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionClassifierTest {

    private val classifier = CompletionClassifier()

    private fun event(type: String, seq: Long, data: kotlinx.serialization.json.JsonObject): SessionEventEnvelope =
        SessionEventEnvelope(type, seq, seq, data)

    @Test
    fun turnCompletedFires() {
        val result = classifier.classifyEvent("s1", event("turn/end", 4, buildJsonObject {
            put("turn", 1)
            putJsonObject("reason") { put("kind", "completed") }
        }))
        assertTrue(result is CompletionEvent.TurnComplete)
        assertEquals("turn:s1:4", result!!.dedupKey)
    }

    @Test
    fun abortedTurnDoesNotFire() {
        val result = classifier.classifyEvent("s1", event("turn/end", 4, buildJsonObject {
            put("turn", 1)
            putJsonObject("reason") { put("kind", "aborted") }
        }))
        assertNull(result)
    }

    @Test
    fun goalClearWithExplicitNullDoesNotInterruptLaterCompletion() {
        // GoalChangeData permits a null goal on operation=clear. Exercise the actual DTO,
        // including serializers configured to retain nulls, rather than an invented payload.
        val json = kotlinx.serialization.json.Json { explicitNulls = true; encodeDefaults = true }
        val clear = json.encodeToJsonElement(
            com.labteto.dshmobile.core.wire.dto.GoalChangeData.serializer(),
            com.labteto.dshmobile.core.wire.dto.GoalChangeData(
                operation = "clear",
                cleared = com.labteto.dshmobile.core.wire.dto.GoalRef("g1", 2),
                clearedAt = 10,
            ),
        ) as kotlinx.serialization.json.JsonObject
        assertEquals(kotlinx.serialization.json.JsonNull, clear["goal"])
        assertNull(classifier.classifyEvent("s1", event("goal/change", 10, clear)))
        assertTrue(classifier.classifyEvent("s1", event("turn/end", 11, buildJsonObject {
            put("turn", 2)
            putJsonObject("reason") { put("kind", "completed") }
        })) is CompletionEvent.TurnComplete)
    }

    @Test
    fun assistantStepsAndMessagesAreNotTaskCompletionSignals() {
        for (type in listOf("step/end", "assistant/message", "assistant/attempt", "user/message", "tool/result")) {
            assertNull(type, classifier.classifyEvent("s1", event(type, 4, buildJsonObject {
                put("turn", 1); put("step", 2)
            })))
        }
    }

    @Test
    fun goalCompleteFires() {
        val result = classifier.classifyEvent("s1", event("goal/change", 9, buildJsonObject {
            put("kind", "goal/change")
            putJsonObject("goal") {
                put("id", "g1"); put("revision", 2); put("objective", "ship it"); put("phase", "complete")
            }
        }))
        assertTrue(result is CompletionEvent.GoalComplete)
        assertEquals("ship it", (result as CompletionEvent.GoalComplete).objective)
    }

    @Test
    fun goalBlockedCarriesReason() {
        val result = classifier.classifyEvent("s1", event("goal/change", 9, buildJsonObject {
            put("kind", "goal/change")
            putJsonObject("goal") {
                put("id", "g1"); put("revision", 2); put("phase", "blocked")
                putJsonObject("blockedReason") { put("message", "no network") }
            }
        }))
        assertTrue(result is CompletionEvent.GoalBlocked)
        assertEquals("no network", (result as CompletionEvent.GoalBlocked).reason)
    }

    @Test
    fun approvalRequestedFires() {
        // The session is the frame's `agentId` and the correlation id is its `eventId`: 0.1.2
        // mints no separate approval id, so the event id is what an answer and a withdrawal both
        // name — and what the notification has to dedupe on.
        val request = buildJsonObject {
            put("toolName", "bash")
            put("reason", "justification")
        }
        val result = classifier.classifyWaterfall("approval/request", "evt-1", "s1", request)
        assertTrue(result is CompletionEvent.ReviewRequested)
        assertEquals("review:s1:evt-1", result!!.dedupKey)
    }

    @Test
    fun questionRequestedFires() {
        val request = buildJsonObject {
            putJsonArray("questions") {
                add(buildJsonObject { put("id", "q1"); put("question", "which one?") })
            }
        }
        val result = classifier.classifyWaterfall("user-questions/request", "evt-2", "s1", request)
        assertTrue(result is CompletionEvent.QuestionRequested)
        assertEquals("which one?", (result as CompletionEvent.QuestionRequested).firstQuestion)
    }

    @Test
    fun distinctQuestionEventIdsNotifyButReplayRetainsItsKey() {
        val request = buildJsonObject { putJsonArray("questions") { } }
        val first = classifier.classifyWaterfall("user-questions/request", "event-a", "same-session", request)!!
        val second = classifier.classifyWaterfall("user-questions/request", "event-b", "same-session", request)!!
        val replay = classifier.classifyWaterfall("user-questions/request", "event-a", "same-session", request)!!
        assertTrue(first.dedupKey != second.dedupKey)
        assertEquals(first.dedupKey, replay.dedupKey)
        assertEquals("question:same-session:event-a", first.dedupKey)
    }

    @Test
    fun followAndHostCollectorsCanUpdateClassifierConcurrently() {
        val workers = (0 until 8).map { worker ->
            Thread {
                repeat(1000) { index ->
                    val session = "$worker-$index"
                    classifier.markSessionRunning(session)
                    assertTrue(classifier.classifyNotification("api-session/status",
                        listOf(JsonPrimitive(session), JsonPrimitive(false))) is CompletionEvent.SessionIdle)
                }
            }
        }
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        workers.forEach { it.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure -> failures.add(failure) } }
        workers.forEach(Thread::start)
        workers.forEach(Thread::join)
        assertTrue("Concurrent classification failed: $failures", failures.isEmpty())
    }

    @Test
    fun anUnselectedWaterfallIsIgnored() {
        val result = classifier.classifyWaterfall("something/else", "evt-3", "s1", buildJsonObject { })
        assertNull(result)
    }

    @Test
    fun sessionIdleOnlyAfterRunning() {
        // Positional arguments now: the host forwards the Cordis listener's own argument list
        // rather than a named payload object.
        val stopped = listOf(JsonPrimitive("s1"), JsonPrimitive(false))
        val started = listOf(JsonPrimitive("s1"), JsonPrimitive(true))
        assertNull(classifier.classifyNotification("api-session/status", stopped))
        classifier.classifyNotification("api-session/status", started)
        assertTrue(
            classifier.classifyNotification("api-session/status", stopped) is CompletionEvent.SessionIdle,
        )
        // Second stop does not refire.
        assertNull(classifier.classifyNotification("api-session/status", stopped))
    }

    @Test
    fun consecutiveIdlesGetDistinctDedupKeys() {
        // The status notification carries no seq, so the classifier counts running→idle
        // transitions itself; a constant would let the observer's dedup silence every
        // completion after the first.
        val started = listOf(JsonPrimitive("s1"), JsonPrimitive(true))
        val stopped = listOf(JsonPrimitive("s1"), JsonPrimitive(false))

        classifier.classifyNotification("api-session/status", started)
        val first = classifier.classifyNotification("api-session/status", stopped)
        classifier.classifyNotification("api-session/status", started)
        val second = classifier.classifyNotification("api-session/status", stopped)

        assertTrue(first is CompletionEvent.SessionIdle)
        assertTrue(second is CompletionEvent.SessionIdle)
        assertTrue(first!!.dedupKey != second!!.dedupKey)
    }
}
