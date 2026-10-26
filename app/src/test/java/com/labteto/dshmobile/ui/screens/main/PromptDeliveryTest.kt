package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.data.PromptOutcome
import com.labteto.dshmobile.core.wire.dto.ImageRejection
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PromptDeliveryTest {
    @Test fun acceptanceWaitsForHostAndBelongsToItsSession() = runTest {
        val first = ComposerDraft(ComposerKey("host", "first"), deliveryScope = backgroundScope)
        val second = ComposerDraft(ComposerKey("host", "second"), deliveryScope = backgroundScope)
        val receipt = CompletableDeferred<PromptOutcome>()
        val task = async(start = CoroutineStart.UNDISPATCHED) { first.trackDelivery { receipt.await() } }
        assertEquals(PromptDelivery.Sending, first.delivery)
        assertEquals(PromptDelivery.Idle, second.delivery)
        receipt.complete(PromptOutcome.Ok)
        task.await()
        assertEquals(PromptDelivery.Accepted, first.delivery)
        assertEquals(PromptDelivery.Idle, second.delivery)
    }

    @Test fun lostResponseDoesNotClaimSuccessAndNextSendReplacesIt() = runTest {
        val draft = ComposerDraft(ComposerKey("host", "first"), deliveryScope = backgroundScope)
        try {
            draft.trackDelivery { throw IOException("response lost") }
            fail("expected transport failure")
        } catch (_: IOException) { }
        assertEquals(PromptDelivery.Unconfirmed, draft.delivery)
        draft.trackDelivery {
            assertEquals(PromptDelivery.Sending, draft.delivery)
            PromptOutcome.Ok
        }
        assertEquals(PromptDelivery.Accepted, draft.delivery)
    }

    @Test fun cancellationDoesNotLeaveSendingOrClaimDelivery() = runTest {
        val draft = ComposerDraft(ComposerKey("host", "first"), deliveryScope = backgroundScope)
        val task = launch(start = CoroutineStart.UNDISPATCHED) { draft.trackDelivery { awaitCancellation() } }
        task.cancelAndJoin()
        assertEquals(PromptDelivery.Unconfirmed, draft.delivery)
    }

    @Test fun acceptedReceiptExpiresThreeSecondsAfterAcceptanceInEveryMode() = runTest {
        for (mode in listOf("queue", "steer")) {
            val draft = ComposerDraft(ComposerKey("host", mode), deliveryScope = backgroundScope)
            draft.mode = mode
            draft.trackDelivery {
                delay(5_000)
                assertEquals(PromptDelivery.Sending, draft.delivery)
                PromptOutcome.Ok
            }
            assertEquals(PromptDelivery.Accepted, draft.delivery)
            runCurrent()
            advanceTimeBy(2_999)
            assertEquals(PromptDelivery.Accepted, draft.delivery)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(PromptDelivery.Idle, draft.delivery)
        }
    }

    @Test fun switchingSessionsAndRemountingCannotRestartAcceptedReceipt() = runTest {
        val repository = ComposerRepository(scope = backgroundScope)
        val key = ComposerKey("host", "first")
        val first = repository.get(key)
        first.trackDelivery { PromptOutcome.Ok }
        runCurrent()
        advanceTimeBy(1_000)
        assertEquals(PromptDelivery.Idle, repository.get(ComposerKey("host", "second")).delivery)
        assertSame(first, repository.get(key))
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(PromptDelivery.Idle, repository.get(key).delivery)
        advanceTimeBy(10_000)
        assertEquals(PromptDelivery.Idle, repository.get(key).delivery)
    }

    @Test fun newSendReplacesOldTimerAndGetsFullAcceptanceWindow() = runTest {
        val draft = ComposerDraft(ComposerKey("host", "first"), deliveryScope = backgroundScope)
        draft.trackDelivery { PromptOutcome.Ok }
        runCurrent()
        advanceTimeBy(2_000)
        val receipt = CompletableDeferred<PromptOutcome>()
        val task = async(start = CoroutineStart.UNDISPATCHED) { draft.trackDelivery { receipt.await() } }
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(PromptDelivery.Sending, draft.delivery)
        receipt.complete(PromptOutcome.Ok)
        task.await()
        runCurrent()
        advanceTimeBy(2_999)
        assertEquals(PromptDelivery.Accepted, draft.delivery)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(PromptDelivery.Idle, draft.delivery)
    }

    @Test fun failureAndUnconfirmedPersistPastAnOlderAcceptanceDeadline() = runTest {
        val draft = ComposerDraft(ComposerKey("host", "first"), deliveryScope = backgroundScope)
        for (outcome in listOf(PromptOutcome.Rejected(ImageRejection.UNKNOWN, "rejected"), PromptOutcome.Failed("lost"))) {
            draft.trackDelivery { PromptOutcome.Ok }
            runCurrent()
            advanceTimeBy(1_000)
            draft.trackDelivery { outcome }
            val expected = if (outcome is PromptOutcome.Rejected) PromptDelivery.Rejected else PromptDelivery.Unconfirmed
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(expected, draft.delivery)
        }
    }

    @Test fun lateResponseCannotReplaceNewerReceiptOrItsTimer() = runTest {
        val draft = ComposerDraft(ComposerKey("host", "first"), deliveryScope = backgroundScope)
        val oldReceipt = CompletableDeferred<PromptOutcome>()
        val oldTask = async(start = CoroutineStart.UNDISPATCHED) { draft.trackDelivery { oldReceipt.await() } }
        draft.trackDelivery { PromptOutcome.Ok }
        runCurrent()
        advanceTimeBy(2_000)
        oldReceipt.complete(PromptOutcome.Failed("late"))
        oldTask.await()
        assertEquals(PromptDelivery.Accepted, draft.delivery)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(PromptDelivery.Idle, draft.delivery)
    }

    @Test fun failedCallCannotProduceAcceptance() = runTest {
        val draft = ComposerDraft(ComposerKey("host", "first"), deliveryScope = backgroundScope)
        draft.trackDelivery { PromptOutcome.Failed("not connected") }
        assertEquals(PromptDelivery.Unconfirmed, draft.delivery)
    }
}
