package com.labteto.dshmobile.conformance

import com.labteto.dshmobile.core.wire.*
import com.labteto.dshmobile.core.wire.dto.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/** Opt-in real Desktop reader-v1 artifact, never an official-host fallback or Kotlin fake. */
class ReaderHostConformanceTest {
    private lateinit var harness: HarnessProcess
    private lateinit var client: HarnessClient

    @Before fun boot() {
        Assume.assumeTrue("Set DSH_READER_CONFORMANCE=true with a verified Desktop artifact",
            System.getenv("DSH_READER_CONFORMANCE") == "true")
        check(HarnessProcess.builtCli() != null) { "Reader conformance requires explicit DSH_HARNESS_CLI; official fallback is forbidden" }
        check(HarnessProcess.available()) { "Reader artifact or Node unavailable" }
        val resolver = File(requireNotNull(System.getenv("DSH_READER_RESOLVER")) {
            "DSH_READER_RESOLVER must name the verified Desktop runtime's package.json"
        }).canonicalFile
        check(resolver.isFile) { "Missing Desktop package resolver: $resolver" }
        harness = HarnessProcess.start(prepareHome = { home, workspace ->
            val root = File(home, "reader-fixture")
            val seed = File(home, "reader-host-seed.mjs")
            seed.writeText(requireNotNull(javaClass.getResource("/reader-host-seed.mjs")).readText())
            val log = File(home, "reader-seed.log")
            val builder = ProcessBuilder(requireNotNull(HarnessProcess.node()), seed.absolutePath,
                resolver.absolutePath, root.absolutePath, workspace.absolutePath)
                .redirectErrorStream(true).redirectOutput(log)
            builder.environment().keys.removeAll { it.contains(Regex("(?i)KEY|SECRET|TOKEN|PASSWORD")) }
            val process = builder.start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Reader seed timed out") }
            check(process.exitValue() == 0) { "Desktop backend refused reader fixture: ${log.readText()}" }
            // Override only storage location/encoding in this disposable profile, not endpoint code.
            buildJsonArray { add(buildJsonObject {
                put("id", "session-persistence-jsonl")
                put("config", buildJsonObject { put("root", root.absolutePath); put("compression", "none") })
            }) }.toString()
        })
        client = HarnessClient(harness)
    }

    @After fun close() {
        if (this::client.isInitialized) client.close()
        if (this::harness.isInitialized) harness.close()
    }

    @Test fun `real reader follow and HTTP pages acknowledge bounded complete filtered coverage`() = runBlocking {
        val address = SessionAddress.Session(sessionId = "reader-contract")
        client.mux.start()
        withTimeout(15_000) { client.mux.awaitOpen() }
        val stream = client.mux.open("session/readerFollow", buildJsonObject {
            put("request", WireJson.encodeToJsonElement(SessionFollowRequest.serializer(),
                SessionFollowRequest(address, maxRows = READER_MAX_ROWS, maxBytes = READER_MAX_BYTES, assistantStream = true)))
        })
        try {
            val wire = withTimeout(15_000) { stream.receive() ?: error("readerFollow ended before acknowledgement") }
            val snapshot = WireJson.decodeFromJsonElement(SessionFollowFrameSerializer, wire) as? SessionFollowFrame.Snapshot
                ?: error("readerFollow did not start with a snapshot: $wire")
            assertEquals("seeded complete log", 405, snapshot.cursor)
            validate(snapshot.records, snapshot.reader, snapshot.cursor, null, snapshot.hasMore)
            assertEquals(128, snapshot.records.size)
            val all = snapshot.records.toMutableList()
            var before = requireNotNull(requireNotNull(snapshot.reader).nextBeforeSeq) {
                "readerFollow omitted its required next-before cursor"
            }
            var requests = 0
            while (before > 0) {
                assertTrue("reader cursor must converge, not loop", requests++ < 10)
                val records = mutableListOf<SessionHistoryRecord>()
                val response = client.api.sessionPageEach(SessionPageRequest(address, snapshot.cursor,
                    beforeSeq = before, view = READER_VIEW, maxRows = READER_MAX_ROWS, maxBytes = READER_MAX_BYTES)) {
                    records += it
                }
                val metadata = when (response) {
                    is RpcResult.Ok -> response.value
                    is RpcResult.Err -> error("real readerPage refused: ${response.error}")
                }
                validate(records, metadata.reader, snapshot.cursor, before, metadata.hasMore)
                assertEquals(minOf(128, before), records.size)
                all.addAll(0, records)
                val next = requireNotNull(requireNotNull(metadata.reader).nextBeforeSeq) {
                    "readerPage omitted its required next-before cursor"
                }
                assertTrue(next < before)
                before = next
            }
            assertEquals((0..405).toList(), all.map { it.event.seq })
            assertEquals(400, all.count { it.event.type == "reader/skip" && it.event.seq in 3..402 })
            val projected = all.joinToString { it.event.data.toString() }
            assertTrue(projected.contains("READER_PUBLIC_QUESTION"))
            assertTrue(projected.contains("READER_PUBLIC_ANSWER"))
            for (secret in listOf("PRIVATE_TITLE_", "REASONING_SECRET", "REPLAY_SECRET")) {
                assertFalse("reader leaked $secret", projected.contains(secret))
            }
            // Positive control: these secrets really exist in the host's raw stored history.
            val raw = when (val result = client.api.sessionPage(SessionPageRequest(address, snapshot.cursor, maxMessages = 1000))) {
                is RpcResult.Ok -> result.value
                is RpcResult.Err -> error("raw positive control refused: ${result.error}")
            }.records.joinToString { it.event.data.toString() }
            for (secret in listOf("PRIVATE_TITLE_", "REASONING_SECRET", "REPLAY_SECRET")) assertTrue(raw.contains(secret))
        } finally { stream.cancel() }
    }

    private fun validate(records: List<SessionHistoryRecord>, reader: SessionReaderCoverage?, cut: Int,
        before: Int?, hasMore: Boolean) {
        requireReaderCoverage(reader, records.firstOrNull()?.event?.seq?.toLong(),
            records.lastOrNull()?.event?.seq?.toLong(), records.size.toLong(), cut, before, hasMore)
        assertTrue(records.size <= READER_MAX_ROWS)
        assertEquals(records.map { it.event.seq }.sorted(), records.map { it.event.seq })
    }
}
