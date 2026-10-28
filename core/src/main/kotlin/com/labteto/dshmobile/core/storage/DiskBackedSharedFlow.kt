package com.labteto.dshmobile.core.storage

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Broadcast with an ordered disk backlog per active subscriber. A slow subscriber does not make a
 * fast subscriber skip values, and no in-memory executor queue accumulates serialized frames.
 * Like replay=0 SharedFlow, emissions with no subscribers have no owner and are not retained.
 * File I/O failure is explicit to producer AND collectors; the application owns error/recovery UI.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class DiskBackedSharedFlow<T>(
    private val diskStore: DiskCacheStore? = null,
    private val encode: (T) -> String,
    private val decode: (String) -> T,
) : MutableSharedFlow<T> {
    private inner class Subscriber {
        val disk = diskStore?.let(::DiskRecordQueue)
        val ram = if (disk == null) Channel<T>(Channel.UNLIMITED) else null
        @Volatile var failed: IOException? = null

        suspend fun receive(): T {
            failed?.let { throw it }
            return try { if (disk == null) ram!!.receive() else decode(disk.receive()) }
            catch (cancelled: CancellationException) { failed?.let { throw it }; throw cancelled }
            catch (error: IOException) { throw error }
            catch (error: Exception) { throw IOException("Cannot decode persisted broadcast event", error) }
        }

        fun abort(error: IOException) {
            failed = error
            try { disk?.abort(error) } catch (cleanup: IOException) { error.addSuppressed(cleanup) }
            ram?.cancel()
        }

        fun close() { try { disk?.close() } finally { ram?.cancel() } }
    }

    private val lock = Any()
    private val subscribers = LinkedHashSet<Subscriber>()
    private val count = MutableStateFlow(0)
    override val subscriptionCount: StateFlow<Int> = count.asStateFlow()
    override val replayCache: List<T> get() = emptyList()
    override fun resetReplayCache() = Unit

    override fun tryEmit(value: T): Boolean = synchronized(lock) {
        if (subscribers.isEmpty()) return@synchronized true
        try {
            val serialized = if (diskStore == null) null else encode(value)
            subscribers.forEach { subscriber ->
                if (subscriber.disk != null) subscriber.disk.append(serialized!!)
                else check(subscriber.ram!!.trySend(value).isSuccess) { "Active broadcast subscriber unexpectedly closed" }
            }
            true
        } catch (error: Exception) {
            val failure = if (error is IOException) error else IOException("Cannot persist broadcast event", error)
            subscribers.forEach { it.abort(failure) }
            throw failure
        }
    }

    override suspend fun emit(value: T) { tryEmit(value) }

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        val subscriber = synchronized(lock) {
            Subscriber().also { subscribers += it; count.value = subscribers.size }
        }
        try {
            while (true) {
                val value = try { subscriber.receive() } catch (error: IOException) {
                    synchronized(lock) { subscribers.forEach { it.abort(error) } }
                    throw error
                }
                collector.emit(value)
            }
        } finally {
            synchronized(lock) {
                subscribers -= subscriber
                count.value = subscribers.size
                subscriber.close()
            }
        }
    }
}
