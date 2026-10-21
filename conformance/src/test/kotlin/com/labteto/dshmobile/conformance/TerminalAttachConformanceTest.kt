package com.labteto.dshmobile.conformance

import com.labteto.dshmobile.core.wire.RemoteStreamException
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.TerminalInputLedger
import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.core.wire.dto.SessionCreateRequest
import com.labteto.dshmobile.core.wire.dto.TerminalCreateRequest
import com.labteto.dshmobile.core.wire.dto.TerminalFrame
import com.labteto.dshmobile.core.wire.dto.WorkspaceCreateRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * The duplex terminal, end to end, against a real harness.
 *
 * Harness 0.2.1-alpha.3 removed `terminal/follow`, `terminal/write` and `terminal/resize` and put
 * all three on one `terminal/attach` stream: the screen on its downlink, keystrokes and resizes on
 * its uplink as numbered commands, each answered by an `ack`. That is the first endpoint this
 * client writes *into* a logical stream, so this test exercises the mux's uplink as much as the
 * terminal: it types a command and waits to read its output back.
 *
 * Skipped on a host that predates the attach stream; the app falls back to the old calls there.
 */
class TerminalAttachConformanceTest {

    private lateinit var harness: HarnessProcess
    private lateinit var client: HarnessClient

    @Before
    fun boot() {
        assumeTrue("no built harness checkout; see HarnessProcess", HarnessProcess.available())
        harness = HarnessProcess.start()
        client = HarnessClient(harness)
    }

    @After
    fun stop() {
        if (this::client.isInitialized) client.close()
        if (this::harness.isInitialized) harness.close()
    }

    @Test
    fun `typed input travels the attach uplink and its output comes back acknowledged`() = runBlocking {
        client.api.workspaceCreate(WorkspaceCreateRequest(path = harness.workspacePath))
        val created = client.api.sessionCreate(SessionCreateRequest(cwd = harness.workspacePath))
        val sessionId = (created as RpcResult.Ok).value.sessionId
        val env = client.api.terminalEnvironment(sessionId).requireValue()
        val terminalId = UUID.randomUUID().toString()
        client.api.terminalCreate(sessionId, TerminalCreateRequest(id = terminalId, cols = 80, rows = 24)).requireValue()

        client.mux.start()
        withTimeoutOrNull(BUDGET_MS) { client.mux.awaitOpen() } ?: error("the mux did not open")
        val stream = client.mux.open("terminal/attach", client.api.terminalAttachArgs(sessionId, terminalId))
        try {
            val first = try {
                withTimeoutOrNull(BUDGET_MS) { stream.receive() } ?: error("no snapshot from terminal/attach")
            } catch (e: RemoteStreamException) {
                assumeTrue("host has no duplex terminal (${e.error.code})", e.error.code !in setOf(
                    "gateway/method-unavailable", "gateway/invocation-unavailable", "capability-unavailable",
                ))
                throw e
            }
            val snapshot = WireJson.decodeFromJsonElement(TerminalFrame.serializer(), first)
            assertEquals("an attachment opens with a screen snapshot", "snapshot", snapshot.type)
            assertEquals("a fresh attachment holds input control", true, snapshot.writable)

            val ledger = TerminalInputLedger(env.maxInputBytes, env.maxBufferedBytes)
            ledger.window = snapshot.inputWindow
            val marker = "dsh-conformance-${UUID.randomUUID().toString().take(8)}"
            assertTrue(ledger.resize(100, 30))
            assertTrue(ledger.write("echo $marker\r"))
            ledger.drain().forEach { assertTrue("uplink item refused locally", stream.send(it)) }

            var acks = 0
            var seen = ""
            withTimeoutOrNull(OUTPUT_BUDGET_MS) {
                while (acks < 2 || occurrences(seen, marker) < 2) {
                    val raw = stream.receive() ?: break
                    val frame = WireJson.decodeFromJsonElement(TerminalFrame.serializer(), raw)
                    when (frame.type) {
                        "ack" -> {
                            assertEquals("input was accepted, not refused", null, frame.refusal)
                            assertTrue("acks arrive in order", ledger.acknowledge(frame.sequence ?: -1))
                            acks++
                        }
                        "output" -> seen += frame.data.orEmpty()
                    }
                }
            }
            assertEquals("both commands were acknowledged", 2, acks)
            // Once as the typed command line, once as the command's own output.
            assertTrue("the echoed marker came back as output: $seen", occurrences(seen, marker) >= 2)
        } finally {
            stream.cancel()
            client.api.terminalClose(sessionId, terminalId)
        }
    }

    /** Occurrences of [marker] once terminal escape sequences are stripped. */
    private fun occurrences(screen: String, marker: String): Int =
        ANSI.replace(screen, "").windowed(marker.length).count { it == marker }

    private fun <T> RpcResult<T>.requireValue(): T = when (this) {
        is RpcResult.Ok -> value
        is RpcResult.Err -> error("${error.code}: ${error.message}")
    }

    private companion object {
        /** CSI sequences (colour, cursor) and OSC sequences (window titles). */
        val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007")
        const val BUDGET_MS = 10_000L
        const val OUTPUT_BUDGET_MS = 30_000L
    }
}
