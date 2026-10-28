package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.wire.dto.*
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReaderProtocolTest {
    private fun coverage() = SessionReaderCoverage(1, 10, 11, 10, 120, false, "session/page")

    @Test fun acknowledgementAndCoverageAreMandatory() {
        requireReaderCoverage(coverage(), 10, 11, 2, 11, hasMore = true)
        for (reader in listOf(null, coverage().copy(version = 2), coverage().copy(nextBeforeSeq = 11), coverage().copy(fromSeq = 9))) {
            assertTrue(runCatching { requireReaderCoverage(reader, 10, 11, 2, 11, hasMore = true) }.isFailure)
        }
        assertTrue(runCatching { requireReaderCoverage(coverage(), 10, 11, 1, 11, hasMore = true) }.isFailure)
        requireReaderCoverage(SessionReaderCoverage(1, 0, -1, 0, 2, false, "session/page"), null, null, 0, -1, hasMore = false)
    }

    @Test fun streamedReaderUsesDedicatedEndpointWithoutChangingRawRequest() = runTest {
        val paths = ArrayList<String>()
        val requests = ArrayList<JsonObject>()
        val transport = object : RpcTransport {
            override suspend fun post(path: String, body: String): RpcHttpResponse {
                paths += path
                val envelope = WireJson.parseToJsonElement(body).jsonObject
                requests += envelope.getValue("payload").jsonObject.getValue("args").jsonObject.getValue("request").jsonObject
                val reader = if (path.endsWith("readerPage")) ",\"reader\":{\"version\":1,\"fromSeq\":0,\"throughSeq\":-1,\"nextBeforeSeq\":0,\"bytes\":2,\"oversized\":false,\"rawDetails\":\"session/page\"}" else ""
                return RpcHttpResponse(200, """{"rpcId":${envelope["rpcId"]},"result":{"ok":true,"value":{"records":[],"hasMore":false$reader}}}""")
            }
            override suspend fun <T> download(path: String, consume: (String?, String?, InputStream) -> T): T = error("unused")
            override suspend fun upload(path: String, contentType: String, contentLength: Long, body: InputStream, onProgress: ((Long) -> Unit)?): RpcHttpResponse = error("unused")
        }
        val api = DshApiClient(transport)
        val raw = SessionPageRequest(SessionAddress.Session(sessionId = "s"), -1)
        val reduced = api.sessionPageEach(raw.copy(view = READER_VIEW, maxRows = 128, maxBytes = 262144)) {}
        assertEquals(1, (reduced as RpcResult.Ok).value.reader?.version)
        assertTrue(api.sessionPageEach(raw) {} is RpcResult.Ok)
        assertEquals(listOf("/api/session/readerPage", "/api/session/page"), paths)
        assertFalse(requests.any { "view" in it })
        assertEquals(128, requests.first().getValue("maxRows").jsonPrimitive.int)
        assertFalse("maxRows" in requests.last())
    }
}
