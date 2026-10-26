package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class FollowFailureTest {
    @Test fun `a real mux close belongs to connection recovery`() = runTest {
        val http = OkHttpClient()
        val mux = RemoteStreamMux { sink ->
            object : WsChannel("http://stub/api/remote.mux", http, sink) {
                override fun start() = sink.onOpen()
                override fun send(text: String) = true
                override fun close() = Unit
            }
        }
        mux.start()
        val stream = mux.open("session/follow")
        mux.close()
        val failure = runCatching { stream.receive() }.exceptionOrNull()!!
        assertTrue(failure is RemoteStreamException && failure.carrier)
        assertFalse(shouldReportFollowFailure(failure, currentGeneration = true))
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }

    @Test fun `carrier errors are classified by their type rather than wording`() {
        for (message in listOf("remote stream mux closed", "Software caused connection abort", "arbitrary transport error")) {
            assertFalse(shouldReportFollowFailure(RemoteStreamException(RpcError("internal", message), carrier = true), true))
        }
    }

    @Test fun `current endpoint and authentication failures remain visible`() {
        for (code in listOf("forbidden", "unauthenticated", "session/not-found", "internal")) {
            // Even identical words must remain visible when this is an endpoint refusal.
            assertTrue(shouldReportFollowFailure(RemoteStreamException(RpcError(code, "remote stream mux closed"), carrier = false), true))
        }
    }

    @Test fun `a retired generation cannot publish any failure`() {
        assertFalse(shouldReportFollowFailure(IllegalStateException("old result"), false))
        assertFalse(shouldReportFollowFailure(RemoteStreamException(RpcError("forbidden", "old refusal"), false), false))
    }

    @Test fun `cancellation is not a failure while other current exceptions remain visible`() {
        assertFalse(shouldReportFollowFailure(CancellationException("switched session"), true))
        assertTrue(shouldReportFollowFailure(IllegalStateException("invalid history response"), true))
    }
}
