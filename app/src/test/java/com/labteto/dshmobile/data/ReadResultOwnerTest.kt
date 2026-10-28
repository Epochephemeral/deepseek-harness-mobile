package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.*
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class ReadResultOwnerTest {
    private fun api() = DshApiClient(object : RpcTransport {
        override suspend fun post(path: String, body: String): RpcHttpResponse = error("unused")
        override suspend fun <T> download(path: String, consume: (String?, String?, InputStream) -> T): T = error("unused")
        override suspend fun upload(path: String, contentType: String, contentLength: Long, body: InputStream,
            onProgress: ((Long) -> Unit)?): RpcHttpResponse = error("unused")
    })

    @Test fun `success and failure have identical host API and selection ownership`() {
        val api = api()
        val owner = ReadResultOwner("host-a", api, ReadSelection("A", 1))
        val current = ReadResultOwner("host-a", api, ReadSelection("A", 1))
        val stale = listOf(null,
            ReadResultOwner("host-b", api, ReadSelection("A", 1)),
            ReadResultOwner("host-a", api(), ReadSelection("A", 1)),
            ReadResultOwner("host-a", api, ReadSelection("B", 2)),
            // A -> B -> A is not the selection which initiated this read.
            ReadResultOwner("host-a", api, ReadSelection("A", 3)))
        for (result in listOf(RpcResult.Ok("catalog"), RpcResult.Err(RpcError("network", "offline")),
            RpcResult.Err(RpcError("capability-unavailable", "missing")))) {
            var publications = 0
            assertTrue(owner.applyIfCurrent(current, result) { assertSame(result, it); publications++ })
            stale.forEach { assertFalse(owner.applyIfCurrent(it, result) { publications++ }) }
            assertEquals(1, publications)
        }
    }

    @Test fun `host catalogs deliberately survive selection changes but not reconnects`() {
        val api = api()
        val owner = ReadResultOwner("host", api)
        assertTrue(owner.applyIfCurrent(ReadResultOwner("host", api), RpcResult.Ok(Unit)) {})
        assertFalse(owner.applyIfCurrent(ReadResultOwner("host", api()), RpcResult.Ok(Unit)) {})
        assertFalse(owner.applyIfCurrent(ReadResultOwner("other", api), RpcResult.Ok(Unit)) {})
    }

    @Test fun `only missing capability and forbidden retire optional reads`() {
        for (code in listOf("capability-unavailable", "gateway/invocation-unavailable",
            "gateway/definition-unavailable", "forbidden")) {
            assertTrue(code, RpcError(code, "refused").isOptionalReadUnavailable())
        }
        for (code in listOf("transport", "network", "timeout", "internal", "unauthenticated",
            "gateway/lookup-not-found", "gateway/arguments-invalid", "unknown")) {
            assertFalse(code, RpcError(code, "temporary or other failure").isOptionalReadUnavailable())
        }
    }

    @Test fun `new query and blank clear invalidate both old success and old failure`() {
        val order = ReadQueryOrder()
        val first = order.next()
        val second = order.next()
        assertFalse(order.isLatest(first))
        assertTrue(order.isLatest(second))
        val blank = order.next()
        assertFalse(order.isLatest(first))
        assertFalse(order.isLatest(second))
        assertTrue(order.isLatest(blank))
    }
}
