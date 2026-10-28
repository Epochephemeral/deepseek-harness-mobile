package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.session.EventFold
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HistoryCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun directory() = File(temporary.root, "history")
    private fun events(text: String = "hello") = listOf(
        SessionEventEnvelope("user/message", 10, 123,
            kotlinx.serialization.json.buildJsonObject {
                put("id", JsonPrimitive("message"))
                put("content", kotlinx.serialization.json.JsonArray(listOf(JsonObject(mapOf(
                    "type" to JsonPrimitive("text"), "text" to JsonPrimitive(text))))))
                put("source", JsonObject(mapOf("kind" to JsonPrimitive("user"))))
            },
            surfaceOp = "append", sourceEventSeqs = listOf(1, 2), ignorable = true),
        SessionEventEnvelope("turn/start", 11, 124, JsonObject(emptyMap())),
    )

    @Test fun `round trip retains every envelope field and reports actual disk use`() = runBlocking {
        val cache = HistoryCache(directory())
        cache.write("host", "session", cache.ticket(), events())
        assertEquals(events(), cache.read("host", "session", cache.ticket()))
        assertEquals(directory().listFiles()!!.sumOf { it.length() }, cache.usage.value!!.bytes)
        assertEquals(1, cache.usage.value!!.sessions)
    }

    @Test fun `cache survives recreation and separates complete host identity and session`() = runBlocking {
        val cache = HistoryCache(directory())
        cache.write("http://host:3081/team-a|id-a", "same", cache.ticket(), events())
        val reopened = HistoryCache(directory())
        assertEquals(events(), reopened.read("http://host:3081/team-a|id-a", "same", reopened.ticket()))
        assertNull(reopened.read("http://host:3081/team-b|id-a", "same", reopened.ticket()))
        assertNull(reopened.read("http://host:3081/team-a|id-b", "same", reopened.ticket()))
        assertNull(reopened.read("https://host:3081/team-a|id-a", "same", reopened.ticket()))
        assertNull(reopened.read("http://host:3081/team-a|id-a", "other", reopened.ticket()))
    }

    @Test fun `clear rejects pending writes and reads from the previous follow`() = runBlocking {
        val cache = HistoryCache(directory())
        val before = cache.ticket()
        cache.write("h", "s", before, events())
        cache.clear()
        cache.write("h", "s", before, events("late reply"))
        assertNull(cache.read("h", "s", before))
        assertEquals(HistoryCacheUsage(), cache.usage.value)
        cache.write("h", "s", cache.ticket(), events("new follow"))
        assertEquals(events("new follow"), cache.read("h", "s", cache.ticket()))
    }

    @Test fun `eviction retains most recently read conversations`() = runBlocking {
        val cache = HistoryCache(directory())
        cache.write("h", "a", cache.ticket(), events())
        cache.setLimitBytes(cache.usage.value!!.bytes * 2)
        directory().listFiles()!!.forEach { it.setLastModified(1) }
        cache.write("h", "b", cache.ticket(), events())
        directory().listFiles()!!.filter { it.lastModified() != 1L }.forEach { it.setLastModified(2) }
        assertNotNull(cache.read("h", "a", cache.ticket()))
        cache.write("h", "c", cache.ticket(), events())
        assertNull(cache.read("h", "b", cache.ticket()))
        assertNotNull(cache.read("h", "a", cache.ticket()))
        assertNotNull(cache.read("h", "c", cache.ticket()))
        assertEquals(2, cache.usage.value!!.sessions)
    }

    @Test fun `capacity admits arbitrary conversation counts`() = runBlocking {
        val cache = HistoryCache(directory())
        assertEquals(2_000_000_000L, com.labteto.dshmobile.connection.AppSettings().historyCacheLimitBytes)
        repeat(30) { cache.write("h", "session-$it", cache.ticket(), events()) }
        assertEquals(30, cache.usage.value!!.sessions)
        assertNotNull(cache.read("h", "session-0", cache.ticket()))
    }

    @Test fun `reducing capacity immediately evicts by last access`() = runBlocking {
        val cache = HistoryCache(directory())
        cache.write("h", "a", cache.ticket(), events())
        val entryBytes = cache.usage.value!!.bytes
        cache.write("h", "b", cache.ticket(), events())
        cache.read("h", "a", cache.ticket())
        cache.setLimitBytes(entryBytes)
        assertNull(cache.read("h", "b", cache.ticket()))
        assertNotNull(cache.read("h", "a", cache.ticket()))
        assertTrue(cache.usage.value!!.bytes <= entryBytes)
    }

    @Test fun `byte cap also evicts an oversized compressed entry`() = runBlocking {
        val cache = HistoryCache(directory(), maxBytes = 1)
        cache.write("h", "s", cache.ticket(), events())
        assertEquals(HistoryCacheUsage(), cache.usage.value)
        assertNull(cache.read("h", "s", cache.ticket()))
    }

    @Test fun `corrupt entries are discarded and stale content is never substituted`() = runBlocking {
        val cache = HistoryCache(directory())
        cache.write("h", "s", cache.ticket(), events())
        directory().listFiles()!!.single { it.extension == "gz" }.writeText("truncated gzip")
        assertNull(cache.read("h", "s", cache.ticket()))
        assertEquals(HistoryCacheUsage(), cache.usage.value)
    }

    @Test fun `preview has identical durable content but no live state or paging authority`() {
        val events = events()
        val authoritative = EventFold("s").fold(events)
        val preview = historyPreview("s", events)
        assertTrue(preview.nodes.filterIsInstance<com.labteto.dshmobile.core.session.UserMessageNode>().isNotEmpty())
        assertEquals(authoritative.nodes, preview.nodes)
        assertEquals(events, preview.journal)
        assertTrue(preview.fromCache)
        assertFalse(authoritative.fromCache)
        assertFalse(preview.running)
        assertFalse(preview.hasMore)
        assertTrue(preview.projections.isEmpty())
        assertTrue(preview.queue.isEmpty())
    }

    @Test fun `system cache eviction is a miss and usage can be refreshed`() = runBlocking {
        val cache = HistoryCache(directory())
        cache.write("h", "s", cache.ticket(), events())
        directory().deleteRecursively()
        assertNull(cache.read("h", "s", cache.ticket()))
        cache.refreshUsage()
        assertEquals(HistoryCacheUsage(), cache.usage.value)
        cache.clear()
        assertEquals(HistoryCacheUsage(), cache.usage.value)
    }
}
