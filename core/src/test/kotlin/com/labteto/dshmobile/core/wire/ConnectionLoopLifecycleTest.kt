package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.wire.dto.RemoteEventFrame
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionLoopLifecycleTest {
    private class Recorder : LoopSinks {
        val failures = mutableListOf<Pair<Int, GenerationFailure>>()
        var connected = 0
        override fun onEventFrame(frame: RemoteEventFrame) = Unit
        override fun onConnected(generation: HostGeneration) { connected++ }
        override fun onStateChange(state: ConnectionState) = Unit
        override fun onGenerationFailed(attempt: Int, failure: GenerationFailure) {
            failures += attempt to failure
        }
    }

    private class Socket(
        val sink: WsChannelSink,
        private val startFailure: Throwable? = null,
        private val ready: Boolean = true,
    ) : WsChannel("http://stub/api/remote.mux", client, sink) {
        var starts = 0
        var closes = 0
        private var streamId: String? = null
        override fun start() {
            starts++
            startFailure?.let { throw it }
            sink.onOpen()
        }
        override fun close() { closes++ }
        override fun send(text: String): Boolean {
            if (text.contains("\"type\":\"open\"")) {
                streamId = Regex("\"streamId\":\"([^\"]+)\"").find(text)!!.groupValues[1]
                if (ready) {
                    sink.onMessage(
                        """{"type":"item","streamId":"$streamId","value":{"type":"ready","clientId":"c","host":{"home":"/tmp"}}}""",
                    )
                }
            }
            return true
        }
        fun end() {
            sink.onMessage("""{"type":"end","streamId":"$streamId"}""")
        }
    }

    private fun config(scheduler: TestCoroutineScheduler, sleeps: MutableList<Long>) = LoopConfig(
        baseDelayMs = 100,
        maxDelayMs = 10_000,
        streamOpenTimeoutMs = 50,
        readyTimeoutMs = 50,
        delay = { sleeps += it; kotlinx.coroutines.delay(it) },
        stableConnectionMs = 1_000,
        monotonicTimeMs = { scheduler.currentTime },
        // Always pick cap/2, so the exact backoff is independent of random seeds.
        random = object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
        },
    )

    @Test
    fun `a late factory result is closed without touching the replacement generation`() = runTest {
        val release = CompletableDeferred<Unit>()
        val sockets = mutableListOf<Socket>()
        var calls = 0
        val loop = ConnectionLoop(
            muxFactory = {
                val late = calls++ == 0
                val mux = RemoteStreamMux { sink -> Socket(sink).also { sockets += it } }
                if (late) {
                    // A factory may have already opened its resource before suspending.
                    mux.start()
                    withContext(NonCancellable) { release.await() }
                }
                mux
            },
            sinks = Recorder(),
            config = config(testScheduler, mutableListOf()),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            loop.start()
            runCurrent()
            loop.stop()
            loop.start()
            runCurrent()
            assertEquals(2, sockets.size)
            assertEquals(0, sockets[1].closes)
            release.complete(Unit)
            runCurrent()
            assertEquals(1, sockets[0].closes)
            assertEquals(0, sockets[1].closes)
            assertTrue(loop.isRunning)
        } finally {
            release.complete(Unit)
            loop.stop()
            runCurrent()
        }
        assertEquals(1, sockets[1].closes)
    }

    @Test
    fun `factory and start exceptions each report failure and retry`() = runTest {
        val recorder = Recorder()
        val sockets = mutableListOf<Socket>()
        var calls = 0
        val loop = ConnectionLoop(
            muxFactory = {
                when (calls++) {
                    0 -> error("factory failed")
                    1 -> RemoteStreamMux { sink ->
                        Socket(sink, IllegalStateException("start failed")).also { sockets += it }
                    }
                    else -> RemoteStreamMux { sink -> Socket(sink).also { sockets += it } }
                }
            },
            sinks = recorder,
            config = config(testScheduler, mutableListOf()),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            loop.start()
            runCurrent()
            assertEquals(listOf(1), recorder.failures.map { it.first })
            assertTrue(recorder.failures[0].second is GenerationFailure.MuxFailed)
            loop.requestRetry()
            runCurrent()
            assertEquals(listOf(1, 2), recorder.failures.map { it.first })
            assertTrue(recorder.failures[1].second is GenerationFailure.MuxFailed)
            assertEquals(1, sockets.single().closes)
            loop.requestRetry()
            runCurrent()
            assertEquals(1, recorder.connected)
        } finally {
            loop.stop()
            runCurrent()
        }
    }

    @Test
    fun `factory cancellation is not reported as failure and the loop can restart`() = runTest {
        val recorder = Recorder()
        var calls = 0
        val loop = ConnectionLoop(
            muxFactory = {
                if (calls++ == 0) throw CancellationException("cancel factory")
                RemoteStreamMux { Socket(it) }
            },
            sinks = recorder,
            config = config(testScheduler, mutableListOf()),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            loop.start()
            runCurrent()
            assertFalse(loop.isRunning)
            assertTrue(recorder.failures.isEmpty())
            loop.start()
            runCurrent()
            assertEquals(1, recorder.connected)
        } finally {
            loop.stop()
            runCurrent()
        }
    }

    @Test
    fun `start cancellation closes its socket without reporting a failure`() = runTest {
        val recorder = Recorder()
        lateinit var socket: Socket
        val loop = ConnectionLoop(
            muxFactory = {
                RemoteStreamMux {
                    Socket(it, CancellationException("cancel start")).also { socket = it }
                }
            },
            sinks = recorder,
            config = config(testScheduler, mutableListOf()),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            loop.start()
            runCurrent()
            assertFalse(loop.isRunning)
            assertEquals(1, socket.closes)
            assertTrue(recorder.failures.isEmpty())
        } finally {
            loop.stop()
            runCurrent()
        }
    }

    @Test
    fun `cancel during ready wait closes the mux without reporting failure`() = runTest {
        val recorder = Recorder()
        lateinit var socket: Socket
        val loop = ConnectionLoop(
            muxFactory = { RemoteStreamMux { Socket(it, ready = false).also { socket = it } } },
            sinks = recorder,
            config = config(testScheduler, mutableListOf()),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        loop.start()
        runCurrent()
        loop.stop()
        runCurrent()
        assertFalse(loop.isRunning)
        assertEquals(1, socket.closes)
        assertTrue(recorder.failures.isEmpty())
    }

    @Test
    fun `quick ready then end grows backoff and a stable generation resets it`() = runTest {
        val sockets = mutableListOf<Socket>()
        val sleeps = mutableListOf<Long>()
        val loop = ConnectionLoop(
            muxFactory = { RemoteStreamMux { Socket(it).also { sockets += it } } },
            sinks = Recorder(),
            config = config(testScheduler, sleeps),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            loop.start()
            runCurrent()
            repeat(3) { index ->
                sockets.last().end()
                runCurrent()
                assertEquals(listOf(50L, 100L, 200L).take(index + 1), sleeps)
                loop.requestRetry()
                runCurrent()
            }
            advanceTimeBy(1_000)
            runCurrent()
            sockets.last().end()
            runCurrent()
            assertEquals(listOf(50L, 100L, 200L, 50L), sleeps)
        } finally {
            loop.stop()
            runCurrent()
        }
    }

    @Test
    fun `retry wakes injected backoff but is not queued during a handshake or connection`() = runTest {
        val sockets = mutableListOf<Socket>()
        var calls = 0
        var sleeps = 0
        val loop = ConnectionLoop(
            muxFactory = {
                calls++
                RemoteStreamMux { Socket(it, ready = calls != 1).also { sockets += it } }
            },
            sinks = Recorder(),
            config = LoopConfig(
                readyTimeoutMs = 50,
                delay = { sleeps++; awaitCancellation() },
            ),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            loop.start()
            loop.start()
            runCurrent()
            loop.requestRetry() // Still waiting for ready: must not queue a future retry.
            advanceTimeBy(50)
            runCurrent()
            assertEquals(1, calls)
            assertEquals(1, sleeps)
            loop.requestRetry()
            runCurrent()
            assertEquals(2, calls)
            loop.requestRetry() // Online: must neither close it nor queue a future retry.
            runCurrent()
            assertEquals(0, sockets.last().closes)
            sockets.last().end()
            runCurrent()
            assertEquals(2, calls)
            loop.stop() // Stop also cancels the custom infinite sleep.
            runCurrent()
            assertFalse(loop.isRunning)
        } finally {
            loop.stop()
            runCurrent()
        }
    }

    @Test
    fun `readiness is retired before a failed generation waits in backoff`() = runTest {
        val states = mutableListOf<ConnectionState>()
        lateinit var socket: Socket
        val loop = ConnectionLoop(
            muxFactory = { RemoteStreamMux { Socket(it).also { socket = it } } },
            sinks = object : LoopSinks {
                override fun onEventFrame(frame: RemoteEventFrame) = Unit
                override fun onConnected(generation: HostGeneration) = Unit
                override fun onStateChange(state: ConnectionState) { states += state }
            },
            config = LoopConfig(delay = { awaitCancellation() }),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            loop.start()
            runCurrent()
            assertEquals(ConnectionState.CONNECTED, states.last())
            socket.end()
            runCurrent()
            assertEquals(ConnectionState.RECONNECTING, states.last())
        } finally {
            loop.stop()
            runCurrent()
        }
    }

    @Test
    fun `external readiness gate suspends attempts outside handshake timeout and respects stop`() = runTest {
        val permit = CompletableDeferred<Unit>()
        val recorder = Recorder()
        var calls = 0
        val loop = ConnectionLoop(
            muxFactory = { calls++; RemoteStreamMux { Socket(it) } },
            sinks = recorder,
            config = config(testScheduler, mutableListOf()),
            dispatcher = StandardTestDispatcher(testScheduler),
            awaitAttempt = { permit.await() },
        )
        try {
            loop.start()
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            loop.requestRetry()
            runCurrent()
            assertEquals(0, calls)
            assertTrue(recorder.failures.isEmpty())
            loop.stop()
            runCurrent()
            permit.complete(Unit)
            runCurrent()
            assertEquals(0, calls)
            loop.start()
            runCurrent()
            assertEquals(1, recorder.connected)
            loop.stop()
            runCurrent()
            assertFalse(loop.isRunning)
        } finally {
            loop.stop()
            runCurrent()
        }
    }

    companion object {
        private val client = OkHttpClient()
    }
}
