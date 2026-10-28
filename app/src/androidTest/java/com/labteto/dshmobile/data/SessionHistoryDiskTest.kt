package com.labteto.dshmobile.data

import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.storage.DiskCacheStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import com.labteto.dshmobile.core.session.EventFold
import com.labteto.dshmobile.core.session.AssistantMessageNode
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Real SQLite + isolated managed files. No account, remote host, or singleton settings are used. */
class SessionHistoryDiskTest {
    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir,"session-disk-${UUID.randomUUID()}")
        val disk = DiskCacheStore(root)
        val preview = File(root,"preview")
        val cache = HistoryCache(preview,diskStore=disk)
        fun close() { cache.close(); root.deleteRecursively() }
    }
    private fun fixture(check: suspend (Fixture) -> Unit) = runBlocking<Unit> {
        val f=Fixture()
        try { check(f) } finally { f.close() }
    }
    private fun event(seq: Long, messageEvery: Int = 30) = SessionEventEnvelope(
        if (seq % messageEvery == 0L) "user/message" else "fixture/event",seq,seq,
        JsonObject(mapOf("text" to JsonPrimitive("event $seq"))),
        surfaceOp="append",sourceEventSeqs=listOf(seq.toInt()),ignorable=false)

    @Test fun retainsMoreThanTenThousandEventsAndPagesBothWaysAtWholeTurns() = fixture { f ->
        val ticket=f.cache.ticket()
        assertTrue(f.cache.storeEvents("host-a","session",ticket,(0L until 12_000).map { event(it) }))
        val latest=f.cache.latestHistory("host-a","session",maxMessages=2)
        assertEquals(11_940L,latest.firstSeq)
        assertEquals(11_999L,latest.lastSeq)
        assertEquals(60,latest.events.size)
        assertTrue(latest.hasOlder); assertFalse(latest.hasNewer)
        val older=f.cache.olderHistory("host-a","session",latest.firstSeq!!,maxMessages=2)
        assertEquals(11_880L,older.firstSeq)
        assertEquals(11_939L,older.lastSeq)
        assertTrue(older.hasNewer)
        assertEquals(latest.events,f.cache.newerHistory("host-a","session",older.lastSeq!!,maxMessages=2).events)
        val first=f.cache.historyAround("host-a","session",0,maxMessages=2)
        assertEquals(0L,first.firstSeq)
        assertEquals(59L,first.lastSeq)
        // Shrinking the existing preview LRU never removes archived events.
        f.cache.setLimitBytes(1)
        assertEquals(first.events,f.cache.historyAround("host-a","session",0,maxMessages=2).events)
        f.cache.close()
        val reopened=HistoryCache(f.preview,diskStore=f.disk)
        try { assertEquals(first.events,reopened.historyAround("host-a","session",0,maxMessages=2).events) }
        finally { reopened.close() }
    }

    @Test fun replacementAndExactHostSessionIdentityAreIsolated() = fixture { f ->
        f.cache.storeEvents("http://host/team-a|id","same",f.cache.ticket(),(0L until 120).map { event(it) })
        val replacement=event(42).copy(data=JsonObject(mapOf("text" to JsonPrimitive("replaced"))))
        f.cache.storeEvents("http://host/team-a|id","same",f.cache.ticket(),listOf(replacement,replacement))
        val page=f.cache.historyAround("http://host/team-a|id","same",42,maxMessages=1)
        assertEquals(30,page.events.size)
        assertEquals(replacement,page.events.single { it.seq == 42L })
        assertTrue(f.cache.latestHistory("http://host/team-b|id","same").events.isEmpty())
        assertTrue(f.cache.latestHistory("http://host/team-a|other","same").events.isEmpty())
        assertTrue(f.cache.latestHistory("http://host/team-a|id","other").events.isEmpty())
    }

    @Test fun disjointPhysicalRangesAreNotStitchedAndFillingGapMergesIndex() = fixture { f ->
        val ticket=f.cache.ticket()
        f.cache.storeEvents("h","s",ticket,(0L until 100).map { event(it) })
        f.cache.storeEvents("h","s",ticket,(200L until 300).map { event(it) })
        assertEquals(listOf(200L..299L),f.cache.latestHistory("h","s").contiguousRanges)
        assertEquals(listOf(0L..99L),f.cache.olderHistory("h","s",200).contiguousRanges)
        f.cache.storeEvents("h","s",ticket,(100L until 200).map { event(it) })
        assertEquals(listOf(0L..299L),f.cache.latestHistory("h","s").contiguousRanges)
        f.cache.removeHistoryAfter("h","s",145,ticket)
        assertEquals(145L,f.cache.latestHistory("h","s").lastSeq)
        assertEquals(listOf(0L..145L),f.cache.latestHistory("h","s").contiguousRanges)
    }

    @Test fun enormousSingleModernTurnRemainsReachableAndGiantPayloadIsNotCursorWindowLimited() = fixture { f ->
        f.cache.storeEvents("h","s",f.cache.ticket(),(0L until 10_100).map { event(it,20_000) })
        val huge=event(10_100,20_000).copy(data=JsonObject(mapOf("text" to JsonPrimitive("界".repeat(1_100_000)))))
        f.cache.storeEvents("h","s",f.cache.ticket(),listOf(huge))
        val page=f.cache.latestHistory("h","s",maxMessages=1)
        assertTrue(page.events.size<=4_000)
        assertFalse(page.legacyUnbounded)
        assertEquals(huge,page.events.last())
        var count=page.events.size
        var current=page
        while (current.hasOlder) {
            val before=current.firstSeq!!
            current=f.cache.olderHistory("h","s",before,maxMessages=1)
            assertEquals(before-1,current.lastSeq)
            assertTrue(current.events.size<=4_000)
            count+=current.events.size
        }
        assertEquals(10_101,count)
        assertEquals(0L,current.firstSeq)
    }

    @Test fun hundredThousandModernToolRecordsInOnePromptHaveBoundedBidirectionalPages() = fixture { f ->
        val ticket=f.cache.ticket()
        f.cache.storeEvents("h","s",ticket,listOf(event(0)))
        for (batch in 0 until 100) {
            val records=(1+batch*1_000L until 1+(batch+1)*1_000L).map { seq ->
                SessionEventEnvelope("tool/result",seq,seq,buildJsonObject {
                    put("turn",1); put("step",seq)
                    put("message",buildJsonObject {
                        put("role","tool"); put("id","tool-$seq"); put("toolCallId","call-$seq")
                        put("source",buildJsonObject { put("kind","tool"); put("callId","call-$seq") })
                        put("content",buildJsonArray { add(buildJsonObject { put("type","text"); put("text","result $seq") }) })
                    })
                })
            }
            f.cache.storeEvents("h","s",ticket,records)
        }
        var page=f.cache.latestHistory("h","s")
        assertEquals(100_000L,page.lastSeq)
        var total=page.events.size
        while (page.hasOlder) {
            val before=page.firstSeq!!
            page=f.cache.olderHistory("h","s",before)
            assertEquals(before-1,page.lastSeq)
            assertTrue(page.events.size<=4_000); assertFalse(page.legacyUnbounded)
            total+=page.events.size
        }
        assertEquals(100_001,total)
        assertEquals(0L,page.firstSeq)
        total=page.events.size
        while (page.hasNewer) {
            val after=page.lastSeq!!
            page=f.cache.newerHistory("h","s",after)
            assertEquals(after+1,page.firstSeq)
            assertTrue(page.events.size<=4_000); assertFalse(page.legacyUnbounded)
            total+=page.events.size
        }
        assertEquals(100_001,total)
        val around=f.cache.historyAround("h","s",50_000,maxMessages=120)
        assertTrue(around.events.size<=8_000)
        assertTrue(50_000L in around.firstSeq!!..around.lastSeq!!)
    }

    @Test fun serializedByteWindowBoundsLargeToolHistoryWithoutLosingNeighborsOrOversizedRecords() = fixture { f ->
        val target=8L*1024*1024
        val body="x".repeat(100*1024)
        fun record(seq: Long,text: String=body): SessionEventEnvelope {
            val message=buildJsonObject {
                put("id","message-$seq"); put("role",if(seq==0L) "user" else "tool")
                put("source",buildJsonObject { put("kind",if(seq==0L) "user" else "tool"); put("callId","call-$seq") })
                if(seq!=0L) put("toolCallId","call-$seq")
                put("content",buildJsonArray { add(buildJsonObject { put("type","text"); put("text",text) }) })
            }
            return SessionEventEnvelope(if(seq==0L) "user/message" else "tool/result",seq,seq,
                if(seq==0L) message else buildJsonObject { put("turn",1); put("step",seq); put("message",message) })
        }
        fun assertWorkingBytes(page: HistoryDiskPage,budget: Long=target) {
            assertFalse(page.legacyUnbounded)
            val bytes=page.events.sumOf { encodeHistoryEvent(it).payload.size.toLong() }
            assertEquals(bytes,page.payloadBytes)
            assertTrue("only one complete oversized record may exceed the working-byte target",
                bytes<=budget || page.events.size==1)
        }
        f.cache.storeEvents("h","s",f.cache.ticket(),(0L until 300).map { record(it) })
        var page=f.cache.latestHistory("h","s")
        assertEquals(299L,page.lastSeq)
        assertTrue("serialized bytes must limit pages before the raw-event/message targets",page.events.size<100)
        assertWorkingBytes(page)
        var count=page.events.size
        while(page.hasOlder) {
            val before=page.firstSeq!!
            page=f.cache.olderHistory("h","s",before)
            assertEquals(before-1,page.lastSeq)
            assertWorkingBytes(page)
            count+=page.events.size
        }
        assertEquals(300,count)
        count=page.events.size
        while(page.hasNewer) {
            val after=page.lastSeq!!
            page=f.cache.newerHistory("h","s",after)
            assertEquals(after+1,page.firstSeq)
            assertWorkingBytes(page)
            count+=page.events.size
        }
        assertEquals(300,count)
        val centered=f.cache.historyAround("h","s",150)
        assertWorkingBytes(centered)
        assertTrue(centered.firstSeq!!<150 && centered.lastSeq!!>150)
        assertWorkingBytes(f.cache.historyAround("h","s",150,maxMessages=120),target*2)
        val giant=record(150,"z".repeat(target.toInt()+1024))
        f.cache.storeEvents("h","s",f.cache.ticket(),listOf(giant))
        val oversized=f.cache.historyAround("h","s",150)
        assertEquals(listOf(giant),oversized.events)
        assertTrue(oversized.hasOlder && oversized.hasNewer)
        assertTrue(encodeHistoryEvent(oversized.events.single()).payload.size>target)
        assertEquals(149L,f.cache.olderHistory("h","s",150).lastSeq)
        assertEquals(151L,f.cache.newerHistory("h","s",150).firstSeq)
        // Traversing after replacement still reaches every sequence exactly once, including the
        // individual record larger than the target and the neighbors on both sides.
        page=f.cache.latestHistory("h","s")
        count=page.events.size
        while(page.hasOlder) {
            val before=page.firstSeq!!
            page=f.cache.olderHistory("h","s",before)
            assertEquals(before-1,page.lastSeq)
            assertWorkingBytes(page)
            count+=page.events.size
        }
        assertEquals(300,count)
        assertEquals(0L,page.firstSeq)
    }

    @Test fun trailingStructuralFrameCannotHideOnlyOversizedContentRecord() = fixture { f ->
        val text="x".repeat(HistoryWindowBudget.payloadBytes(60).toInt()+1024)
        val giant=SessionEventEnvelope("tool/result",1,1,buildJsonObject {
            put("turn",1); put("step",1)
            put("message",buildJsonObject {
                put("role","tool"); put("toolCallId","giant-call")
                put("content",buildJsonArray { add(buildJsonObject { put("type","text"); put("text",text) }) })
            })
        })
        val end=SessionEventEnvelope("turn/end",2,2,buildJsonObject {
            put("turn",1); put("reason",buildJsonObject { put("kind","completed") })
        })
        f.cache.storeEvents("h","s",f.cache.ticket(),listOf(event(0),giant,end))
        val latest=f.cache.latestHistory("h","s")
        assertEquals(listOf(giant,end),latest.events)
        assertEquals(latest.events.sumOf { encodeHistoryEvent(it).payload.size.toLong() },latest.payloadBytes)
        assertTrue(latest.payloadBytes>HistoryWindowBudget.payloadBytes(60))
        val nodes=EventFold("s").fold(latest.events).nodes
        val output=nodes.filterIsInstance<com.labteto.dshmobile.core.session.ToolResultNode>().single()
        val content=(output.content as JsonArray).single().jsonObject["text"]!!.jsonPrimitive.content
        assertEquals(text,content)
        val aroundEnd=f.cache.historyAround("h","s",2)
        assertTrue(aroundEnd.events.any { it.seq==giant.seq })
        assertEquals(2L,aroundEnd.lastSeq)
        assertTrue(com.labteto.dshmobile.core.session.isHistoryContentEventType("future/unknown"))
    }

    @Test fun atomicLivePersistenceUsesFreshEpochAndClearSignalsEvenUnchangedEmptyUsage() = fixture { f ->
        f.cache.refreshUsage()
        val emptyUsage=f.cache.usage.value
        val before=f.cache.ticket()
        kotlinx.coroutines.coroutineScope {
            val changed=async(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { f.cache.ticketChanges.first { it>before } }
            f.cache.clear()
            assertEquals(before+1,kotlinx.coroutines.withTimeout(5_000) { changed.await() })
            assertEquals(emptyUsage,f.cache.usage.value)
        }
        val current=f.cache.ticket()
        assertEquals(current,f.cache.ticketChanges.value)
        val first=event(100)
        assertEquals(encodeHistoryEvent(first).payload.size.toLong(),f.cache.storeLiveEvent("h","s",first))
        assertEquals(listOf(first),f.cache.latestHistory("h","s").events)
        f.cache.clear()
        assertFalse(f.cache.storeEvents("h","s",current,listOf(first)))
        val fresh=event(101)
        val bytes=f.cache.storeLiveEvent("h","s",fresh)
        val page=f.cache.latestHistory("h","s")
        assertEquals(listOf(fresh),page.events)
        assertEquals(bytes,page.payloadBytes)
        val beforeClose=f.cache.ticket()
        kotlinx.coroutines.coroutineScope {
            val changed=async(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { f.cache.ticketChanges.first { it>beforeClose } }
            f.cache.close()
            assertEquals(beforeClose+1,kotlinx.coroutines.withTimeout(5_000) { changed.await() })
        }
        assertEquals(f.cache.ticket(),f.cache.ticketChanges.value)
    }

    @Test fun legacyDurableChunksKeepCompletePrefixAndEmptySettlementFallback() = fixture { f ->
        val records=ArrayList<SessionEventEnvelope>()
        records+=event(0)
        fun chunk(seq: Long,type: String,text: String?=null)=SessionEventEnvelope("assistant/chunk",seq,seq,buildJsonObject {
            put("turn",1); put("step",1)
            put("chunk",buildJsonObject { put("type",type); put("index",0); put("blockType","text"); text?.let { put("text",it) } })
        })
        records+=chunk(1,"block-start")
        for (seq in 2L..9_001L) records+=chunk(seq,"text-delta","x")
        records+=SessionEventEnvelope("assistant/message",9_002,9_002,buildJsonObject {
            put("turn",1); put("step",1); put("message",buildJsonObject { put("id","legacy-settlement") })
        })
        records+=SessionEventEnvelope("turn/end",9_003,9_003,buildJsonObject {
            put("turn",1); put("reason",buildJsonObject { put("kind","completed") })
        })
        f.cache.storeEvents("h","s",f.cache.ticket(),records)
        val latest=f.cache.latestHistory("h","s",maxMessages=1)
        assertTrue(latest.legacyUnbounded)
        assertEquals(records,latest.events)
        val full=EventFold("s").fold(records)
        val paged=EventFold("s").fold(latest.events)
        assertEquals(full.nodes,paged.nodes)
        assertEquals("x".repeat(9_000),paged.nodes.filterIsInstance<AssistantMessageNode>().single().plainText)
        assertEquals(records,f.cache.historyAround("h","s",5_000,maxMessages=1).events)
    }

    @Test fun stagedRpcPagesAreInvisibleUntilCommitAndAbortDoesNotLeak() = fixture { f ->
        f.cache.beginPage("h","s",f.cache.ticket()).use { writer ->
            assertTrue(writer.append((0L until 60).map { event(it) }))
            assertTrue(f.cache.latestHistory("h","s").events.isEmpty())
            assertTrue(writer.commit())
        }
        assertEquals(60,f.cache.latestHistory("h","s").events.size)
        f.cache.beginPage("h","s",f.cache.ticket()).use { writer -> writer.append((60L until 260).map { event(it) }) }
        assertEquals(59L,f.cache.latestHistory("h","s").lastSeq)
    }

    @Test fun streamedSingleRecordAppendsBatchBeforeOpeningSQLiteAndCommitAllRecords() = fixture { f ->
        val records=(0L until 257).map { event(it) }
        f.cache.beginPage("h","s",f.cache.ticket()).use { writer ->
            records.take(63).forEach { assertTrue(writer.append(listOf(it))) }
            assertFalse("partial working batch must not create/fsync SQLite per incoming record",
                File(f.root,"history/sessions.sqlite").exists())
            assertTrue(writer.append(listOf(records[63])))
            assertTrue("a full working batch is flushed to indexed staging",
                File(f.root,"history/sessions.sqlite").exists())
            assertTrue(f.cache.latestHistory("h","s").events.isEmpty())
            records.drop(64).forEach { assertTrue(writer.append(listOf(it))) }
            assertTrue(f.cache.latestHistory("h","s").events.isEmpty())
            assertTrue(writer.commit())
        }
        assertEquals(records,f.cache.latestHistory("h","s").events)
    }

    @Test fun stagingByteBatchFlushesEarlyAndSingleOversizedEventPassesIntact() = fixture { f ->
        val large=event(0).copy(data=JsonObject(mapOf("text" to JsonPrimitive("x".repeat(600_000)))))
        val giant=event(2).copy(data=JsonObject(mapOf("text" to JsonPrimitive("界".repeat(1_100_000)))))
        val records=listOf(large,large.copy(seq=1),giant)
        f.cache.beginPage("h","s",f.cache.ticket()).use { writer ->
            writer.append(listOf(records[0]))
            assertFalse(File(f.root,"history/sessions.sqlite").exists())
            writer.append(listOf(records[1]))
            assertTrue("byte working budget must flush before 64 large records accumulate",
                File(f.root,"history/sessions.sqlite").exists())
            writer.append(listOf(giant))
            assertTrue(f.cache.latestHistory("h","s").events.isEmpty())
            assertTrue(writer.commit())
        }
        assertEquals(records,f.cache.latestHistory("h","s").events)
    }

    @Test fun lateAppendAfterCloseOrCommitCannotRecreateCompletedStaging() = fixture { f ->
        // Wrap the actual cache actions only to pause between the writer finished check and the
        // cache-lock action. This deterministically exercises a late producer, not a timing sleep.
        for (commit in listOf(false,true)) {
            val sid=if(commit) "commit" else "abort"
            val original=f.cache.beginPage("h",sid,f.cache.ticket())
            original.append(listOf(event(0)))
            fun action(name: String)=HistoryPageWriter::class.java.getDeclaredField(name)
                .apply { isAccessible=true }.get(original)
            @Suppress("UNCHECKED_CAST")
            val appendAction=action("appendAction") as (List<SessionEventEnvelope>) -> Boolean
            @Suppress("UNCHECKED_CAST")
            val commitAction=action("commitAction") as () -> Boolean
            @Suppress("UNCHECKED_CAST")
            val abortAction=action("abortAction") as () -> Unit
            val entered=java.util.concurrent.CountDownLatch(1)
            val release=java.util.concurrent.CountDownLatch(1)
            val racing=HistoryPageWriter(
                appendAction={ records ->
                    entered.countDown()
                    check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))
                    appendAction(records)
                },commitAction=commitAction,abortAction=abortAction)
            try {
                kotlinx.coroutines.coroutineScope {
                    val appending=async(kotlinx.coroutines.Dispatchers.Default) { racing.append(listOf(event(1))) }
                    try {
                        assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
                        if(commit) assertTrue(racing.commit()) else racing.close()
                    } finally { release.countDown() }
                    assertFalse("a callback delayed past completion must not resurrect its buffer",appending.await())
                }
                val buffers=HistoryCache::class.java.getDeclaredField("pageBuffers").apply { isAccessible=true }.get(f.cache) as Map<*,*>
                assertTrue(buffers.isEmpty())
                assertEquals(if(commit) listOf(event(0)) else emptyList<SessionEventEnvelope>(),
                    f.cache.latestHistory("h",sid).events)
            } finally { release.countDown(); racing.close(); original.close() }
        }
    }

    @Test fun clearInvalidatesOldQueuedWritesAndStagedPagesWithoutRefillingDeletedHistory() = fixture { f ->
        val old=f.cache.ticket()
        f.cache.storeEvents("h","s",old,(0L until 60).map { event(it) })
        val writer=f.cache.beginPage("h","s",old)
        writer.append((60L until 90).map { event(it) })
        f.cache.clear()
        val buffers=HistoryCache::class.java.getDeclaredField("pageBuffers").apply { isAccessible=true }.get(f.cache) as Map<*,*>
        assertTrue("clear must release unpublished serialized working batches",buffers.isEmpty())
        assertFalse(f.cache.storeEvents("h","s",old,listOf(event(90))))
        assertFalse(writer.append(listOf(event(91))))
        assertFalse(writer.commit())
        writer.close()
        assertTrue(f.cache.latestHistory("h","s").events.isEmpty())
        f.cache.storeEvents("h","s",f.cache.ticket(),listOf(event(1_000)))
        assertEquals(listOf(event(1_000)),f.cache.latestHistory("h","s").events)
    }

    @Test fun unifiedStatsAndClearReleaseOwnedArchiveButRetainActiveStreamRpcFiles() = fixture { f ->
        f.cache.storeEvents("h","s",f.cache.ticket(),(0L until 60).map { event(it) })
        f.cache.write("h","s",f.cache.ticket(),listOf(event(0)))
        val stream=f.disk.createLease("stream",".backlog")
        val rpc=f.disk.createLease("rpc",".response")
        try {
            stream.file.writeText("pending stream")
            rpc.file.writeText("pending rpc")
            val orphan=File(rpc.file.parentFile,"crash-leftover.response").apply { writeText("inactive") }
            f.cache.refreshUsage()
            val usage=f.cache.usage.value!!
            assertTrue(usage.previewBytes > 0)
            assertTrue(usage.historyBytes > 0)
            assertTrue(usage.backlogBytes > 0)
            assertTrue(usage.rpcBytes > 0)
            assertEquals("only active processing bytes survive cleanup, not the entire mutable archive",
                stream.file.length()+rpc.file.length(),usage.protectedBytes)
            assertEquals(f.disk.usage().totalBytes,usage.bytes)
            f.disk.clear() // DB pin protects its rows from generic cleanup.
            assertEquals(59L,f.cache.latestHistory("h","s").lastSeq)
            assertFalse(orphan.exists())
            f.cache.clear() // Owner releases its DB pin; shared cleanup needs no VACUUM/free space.
            assertFalse(File(f.root,"history/sessions.sqlite").exists())
            assertEquals(0L,f.cache.usage.value!!.historyBytes)
            assertEquals(stream.file.length()+rpc.file.length(),f.cache.usage.value!!.bytes)
            assertTrue(stream.file.exists()); assertTrue(rpc.file.exists())
            assertTrue(f.cache.usage.value!!.protectedBytes > 0)
            assertTrue(f.cache.latestHistory("h","s").events.isEmpty())
        } finally { stream.close(); rpc.close() }
        f.cache.refreshUsage()
        assertEquals(0L,f.cache.usage.value!!.backlogBytes)
        assertEquals(0L,f.cache.usage.value!!.rpcBytes)
        f.cache.clear()
        assertEquals(HistoryCacheUsage(),f.cache.usage.value)
    }

    @Test fun archiveTicketFencesWriteAndInstallAgainstConcurrentClear() = fixture { f ->
        val ticket=f.cache.ticket()
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val clearStarted=java.util.concurrent.CountDownLatch(1)
        kotlinx.coroutines.coroutineScope {
            val installing=async(kotlinx.coroutines.Dispatchers.Default) {
                f.cache.atTicket(ticket) {
                    entered.countDown()
                    check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))
                    f.cache.storeEvents("h","s",ticket,listOf(event(0)))
                    "installed"
                }
            }
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            val clearing=async(kotlinx.coroutines.Dispatchers.IO) {
                clearStarted.countDown()
                f.cache.clear()
            }
            try {
                assertTrue(clearStarted.await(5,java.util.concurrent.TimeUnit.SECONDS))
                assertFalse("clear must not interleave write/read/install under one ticket",clearing.isCompleted)
            } finally { release.countDown() }
            assertEquals("installed",installing.await())
            clearing.await()
        }
        assertNull(f.cache.atTicket(ticket) { fail("stale block must never execute") })
        assertFalse(File(f.root,"history/sessions.sqlite").exists())
        assertTrue(f.cache.latestHistory("h","s").events.isEmpty())
    }

    @Test fun corruptHistoryIsReportedWithoutDeletingOrReplacingItsFiles() = fixture { f ->
        f.cache.storeEvents("h","s",f.cache.ticket(),listOf(event(0)))
        f.cache.close()
        val database=File(f.root,"history/sessions.sqlite")
        val broken="not a SQLite database".toByteArray()
        database.writeBytes(broken)
        try {
            f.cache.latestHistory("h","s")
            fail("Corrupt full history must not turn into an empty cache miss")
        } catch (_: android.database.sqlite.SQLiteException) {
            assertTrue(database.exists())
            assertArrayEquals(broken,database.readBytes())
        }
        f.cache.clear() // Explicit recovery may remove corruption; opening must never do so itself.
        assertFalse(database.exists())
        assertEquals(HistoryCacheUsage(),f.cache.usage.value)
    }
}
