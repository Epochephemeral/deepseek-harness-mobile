package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.storage.DiskCacheStore
import com.labteto.dshmobile.core.storage.DiskRecordQueue
import com.labteto.dshmobile.core.wire.dto.RemoteStreamClientMessage
import com.labteto.dshmobile.core.wire.dto.RemoteStreamClientMessageSerializer
import com.labteto.dshmobile.core.wire.dto.RemoteStreamServerMessage
import com.labteto.dshmobile.core.wire.dto.RemoteStreamServerMessageSerializer
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A logical business failure, or a failure of the socket/storage carrying the generation. */
class RemoteStreamException(val error: RpcError, val carrier: Boolean) : Exception(error.message)
class MuxClosedException : Exception("remote stream mux closed")

/** One single-consumer logical stream, owned until it is drained or explicitly cancelled. */
interface RemoteStream {
    suspend fun receive(): JsonElement?
    /** Explicit abandonment releases the unread backlog and wakes a suspended receive. */
    fun cancel()
    fun send(value: JsonElement): Boolean = false
    fun endUplink() = Unit
}

/**
 * One socket's ordered logical streams. Android supplies shared disk storage; compatibility callers
 * may omit it to keep the original RAM channel. No content/queue/stream size cutoffs are imposed.
 * The disk log is generation scratch data, NOT a process-death replay guarantee.
 */
class RemoteStreamMux(
    private val diskStore: DiskCacheStore? = null,
    private val channelFactory: (sink: WsChannelSink) -> WsChannel,
) {
    private sealed class Signal {
        data class Item(val value: JsonElement) : Signal()
        data class Failed(val error: RpcError, val carrier: Boolean) : Signal()
        object Ended : Signal()
    }

    private inner class Stream(private val streamId: String) : RemoteStream {
        private val disk = diskStore?.let(::DiskRecordQueue)
        private val signals = if (disk == null) Channel<Signal>(Channel.UNLIMITED) else null
        @Volatile private var cancelled = false
        @Volatile private var forcedFailure: RemoteStreamException? = null
        private var done = false
        private var terminalQueued = false
        private var terminalCarrier = false

        fun enqueue(message: RemoteStreamServerMessage, text: String? = null, carrier: Boolean = false) {
            if (done || terminalQueued) return
            if (message !is RemoteStreamServerMessage.Item) {
                // Publish before the record: an IO reader may consume it immediately after append.
                terminalCarrier = carrier
                terminalQueued = true
            }
            if (disk != null) disk.append(text ?: WireJson.encodeToString(RemoteStreamServerMessageSerializer, message))
            else signals!!.trySend(signal(message, carrier))
        }

        override suspend fun receive(): JsonElement? {
            forcedFailure?.let { throw it }
            if (cancelled) throw CancellationException("Logical stream cancelled")
            val next = try {
                if (disk == null) signals!!.receive()
                else signal(WireJson.decodeFromString(RemoteStreamServerMessageSerializer, disk.receive()), terminalCarrier)
            } catch (error: CancellationException) {
                forcedFailure?.let { throw it }
                throw error
            } catch (error: IOException) {
                storageFailed(error)
                throw carrierFailure(error)
            } catch (error: SerializationException) {
                storageFailed(IOException("Cannot decode persisted stream backlog", error))
                throw carrierFailure(error)
            }
            forcedFailure?.let { throw it }
            return when (next) {
                is Signal.Item -> next.value
                is Signal.Failed -> {
                    finish()
                    throw RemoteStreamException(next.error, next.carrier)
                }
                Signal.Ended -> { finish(); null }
            }
        }

        private fun finish() {
            try {
                synchronized(lifecycleLock) {
                    done = true
                    streams.remove(streamId)
                    disk?.close()
                    signals?.cancel()
                }
            } catch (error: IOException) {
                storageFailed(error)
                throw carrierFailure(error)
            }
        }

        override fun send(value: JsonElement): Boolean = synchronized(lifecycleLock) {
            if (done || terminalQueued || closedCause != null || streams[streamId] !== this) false
            else this@RemoteStreamMux.send(RemoteStreamClientMessage.Item(streamId = streamId, value = value))
        }

        override fun endUplink() = synchronized(lifecycleLock) {
            if (!done && !terminalQueued && closedCause == null && streams[streamId] === this) {
                this@RemoteStreamMux.send(RemoteStreamClientMessage.End(streamId = streamId))
            }
            Unit
        }

        override fun cancel() = synchronized(lifecycleLock) {
            if (cancelled) return@synchronized
            cancelled = true
            val wasActive = !done && !terminalQueued
            done = true
            streams.remove(streamId)
            try { disk?.close() } finally { signals?.cancel() }
            if (wasActive && closedCause == null) this@RemoteStreamMux.send(RemoteStreamClientMessage.Cancel(streamId = streamId))
            Unit
        }

        /** Explicit generation teardown, including ended-but-not-yet-drained streams. */
        fun abort(cause: RemoteStreamException) {
            forcedFailure = cause
            done = true
            try { disk?.abort(cause) } catch (cleanup: IOException) { cause.addSuppressed(cleanup) }
            signals?.cancel()
        }
    }

    private fun signal(message: RemoteStreamServerMessage, carrier: Boolean): Signal = when (message) {
        is RemoteStreamServerMessage.Item -> Signal.Item(message.value ?: JsonObject(emptyMap()))
        is RemoteStreamServerMessage.Error -> Signal.Failed(message.error, carrier)
        is RemoteStreamServerMessage.End -> Signal.Ended
    }

    private val streams = HashMap<String, Stream>()
    private val nextStreamId = AtomicLong(1)
    private val started = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val opened = CompletableDeferred<Unit>()
    @Volatile private var closedCause: Throwable? = null
    @Volatile private var channel: WsChannel? = null

    private val sink = object : WsChannelSink {
        override fun onOpen() { opened.complete(Unit) }
        override fun onMessage(text: String) {
            if (closedCause != null) return
            val message = try {
                WireJson.decodeFromString(RemoteStreamServerMessageSerializer, text)
            } catch (error: SerializationException) {
                failAll(error); closeChannel(); return
            } catch (error: IllegalArgumentException) {
                failAll(error); closeChannel(); return
            }
            try {
                synchronized(lifecycleLock) {
                    if (closedCause != null) return
                    // A late item crossing host end or local cancellation is no longer live.
                    streams[message.streamId]?.enqueue(message, text)
                }
            } catch (error: IOException) { storageFailed(error) }
        }
        override fun onClosed(cause: Throwable?) { failAll(cause) }
    }

    val failure: Throwable? get() = closedCause

    fun start() {
        if (!started.compareAndSet(false, true) || closedCause != null) return
        try {
            val created = channelFactory(sink)
            synchronized(lifecycleLock) {
                if (closedCause != null) { created.close(); return }
                channel = created
                created.start()
            }
        } catch (error: Throwable) {
            failAll(error)
            runCatching { closeChannel() }
            throw error
        }
    }

    suspend fun awaitOpen() {
        opened.await()
        closedCause?.let { throw it }
    }

    fun open(endpoint: String, args: JsonElement = JsonObject(emptyMap())): RemoteStream = synchronized(lifecycleLock) {
        closedCause?.let { throw carrierFailure(it) }
        val streamId = nextStreamId.getAndIncrement().toString()
        val stream = try { Stream(streamId) } catch (error: IOException) {
            storageFailed(error)
            throw carrierFailure(error)
        }
        streams[streamId] = stream
        val sent = send(RemoteStreamClientMessage.Open(streamId = streamId, endpoint = endpoint,
            payload = JsonObject(mapOf("args" to args))))
        if (!sent) {
            stream.cancel()
            throw carrierFailure(closedCause)
        }
        stream
    }

    fun openStream(endpoint: String, args: JsonElement = JsonObject(emptyMap())): Flow<JsonElement> = flow {
        val stream = open(endpoint, args)
        try {
            while (true) emit(stream.receive() ?: break)
        } finally { stream.cancel() }
    }

    /** Local ownership ends here: no scratch file, including an undrained terminal, stays leased. */
    fun close() {
        failAll(null, releaseImmediately = true)
        closeChannel()
    }

    /** Surface an external disk-backed consumer failure as this generation's actual cause. */
    fun close(cause: Throwable) {
        failAll(cause, releaseImmediately = true)
        closeChannel()
    }

    private fun closeChannel() {
        val closing = synchronized(lifecycleLock) { channel.also { channel = null } }
        closing?.close()
    }

    private fun send(message: RemoteStreamClientMessage): Boolean =
        channel?.send(WireJson.encodeToString(RemoteStreamClientMessageSerializer, message)) ?: false

    private fun storageFailed(cause: IOException) {
        // A real I/O failure is explicit and terminal, not an overflow policy or a skipped event.
        failAll(cause, releaseImmediately = true)
        closeChannel()
    }

    private fun failAll(cause: Throwable?, releaseImmediately: Boolean = false) {
        synchronized(lifecycleLock) {
            if (closedCause != null && !releaseImmediately) return
            val closure = closedCause ?: cause ?: MuxClosedException()
            closedCause = closure
            opened.complete(Unit)
            val error = carrierError(closure)
            if (releaseImmediately) {
                streams.values.forEach { it.abort(RemoteStreamException(error, carrier = true)) }
                streams.clear()
            } else {
                // A socket failure stays behind every accepted item in its stream. Explicit owner
                // close/cancel remains responsible for any backlog whose consumer was abandoned.
                streams.forEach { (id, stream) ->
                    try { stream.enqueue(RemoteStreamServerMessage.Error(streamId = id, error = error), carrier = true) }
                    catch (storage: IOException) { stream.abort(carrierFailure(storage)) }
                }
            }
        }
    }

    private fun carrierFailure(cause: Throwable?): RemoteStreamException =
        RemoteStreamException(carrierError(cause ?: MuxClosedException()), carrier = true)

    private fun carrierError(cause: Throwable): RpcError {
        val status = (cause as? RpcTransportException)?.status ?: 0
        return RpcError(code = "internal", message = cause.message ?: "remote stream carrier failed",
            details = TransportFailures.details(TransportFailures.classify(cause), status))
    }
}
