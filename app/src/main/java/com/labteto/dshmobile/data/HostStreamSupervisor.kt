package com.labteto.dshmobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A subscription must route every state-changing frame through publish, not mutate before it. */
internal class HostStreamSource(val name: String, val collect: suspend (publish: (() -> Unit) -> Unit) -> Unit)

internal data class HostStreamFailure(val message: String, val attempt: Int, val retrying: Boolean) {
    fun describe(source: String): String = "$source: $message (${if (retrying) "retrying" else "stopped"}; attempt $attempt)"
}

/**
 * One replaceable lifetime for the host-wide mirrors. Logical stream failures retry only while the
 * owner and carrier remain current. The connection manager, never this class, replaces a dead mux.
 * A finite budget applies to the whole lifetime, even if a repeatedly failing source emits a frame
 * before each failure. Recovery removes only that source's fault, never another operation's error.
 * All ownership checks, publications and fault updates share the Store's [lock].
 */
internal class HostStreamSupervisor(
    private val scope: CoroutineScope,
    private val lock: Any,
    private val retryDelays: List<Long> = listOf(500L, 1_500L, 3_000L),
    private val waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
) {
    private class Lifetime(val job: Job, val owns: () -> Boolean, val alive: () -> Boolean)
    private var current: Lifetime? = null
    private val _failures = MutableStateFlow<Map<String, HostStreamFailure>>(emptyMap())
    val failures: StateFlow<Map<String, HostStreamFailure>> = _failures.asStateFlow()

    init { require(retryDelays.all { it > 0 }) }

    fun start(sources: List<HostStreamSource>, owns: () -> Boolean, alive: () -> Boolean) = synchronized(lock) {
        require(sources.map { it.name }.distinct().size == sources.size)
        stop()
        val lifetime = Lifetime(SupervisorJob(scope.coroutineContext[Job]), owns, alive)
        current = lifetime
        val childScope = CoroutineScope(scope.coroutineContext + lifetime.job)
        sources.forEach { source -> childScope.launch { collect(lifetime, source) } }
    }

    fun stop() = synchronized(lock) {
        val previous = current
        current = null // Retire before cancellation: non-cooperative callbacks cannot publish.
        previous?.job?.cancel()
        _failures.value = emptyMap()
    }

    private fun owns(lifetime: Lifetime): Boolean = current === lifetime && lifetime.owns()
    private fun canRead(lifetime: Lifetime): Boolean = owns(lifetime) && lifetime.alive()

    private suspend fun collect(lifetime: Lifetime, source: HostStreamSource) {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!synchronized(lock) { canRead(lifetime) }) return
            try {
                source.collect { publish ->
                    synchronized(lock) {
                        if (!canRead(lifetime)) throw CancellationException("Host stream owner retired")
                        publish()
                        _failures.value = _failures.value - source.name
                    }
                }
                // These streams are infinite mirrors: clean EOF is still a lost subscription.
                throw IllegalStateException("stream ended before the connection closed")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                val retry = synchronized(lock) {
                    if (!canRead(lifetime)) return
                    val retrying = attempt < retryDelays.size
                    _failures.value = _failures.value + (source.name to HostStreamFailure(
                        failure.message ?: failure.javaClass.simpleName, attempt + 1, retrying))
                    retrying
                }
                if (!retry) return
                waitBeforeRetry(retryDelays[attempt++])
            }
        }
    }
}
