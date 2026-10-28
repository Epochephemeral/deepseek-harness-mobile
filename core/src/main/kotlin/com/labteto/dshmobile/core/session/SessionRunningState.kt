package com.labteto.dshmobile.core.session

/**
 * Session execution status, not goal activation or transcript/child presentation state.
 *
 * Host status notifications and list baselines are authoritative. Turn events are only a fallback
 * for sessions not yet described by the host: a turn ending need not stop background session work,
 * and a replayed start must not resurrect a completed session. Callers serialize access.
 */
class SessionRunningState {
    private data class Status(val running: Boolean, val revision: Long)
    private val authoritative = HashMap<String, Status>()
    private val turns = HashMap<String, Boolean>()
    private var revision = 0L

    operator fun get(sessionId: String): Boolean? = authoritative[sessionId]?.running ?: turns[sessionId]

    /** Capture before requesting a list, so notifications received during the RPC win its race. */
    fun baselineTicket(): Long = revision

    fun status(sessionId: String, running: Boolean) {
        authoritative[sessionId] = Status(running, ++revision)
    }

    fun baseline(sessionId: String, running: Boolean, ticket: Long) {
        if ((authoritative[sessionId]?.revision ?: -1L) <= ticket) status(sessionId, running)
    }

    /** An added-row notification seeds status without undoing a newer status already received. */
    fun added(sessionId: String, running: Boolean) {
        if (sessionId !in authoritative) status(sessionId, running)
    }

    fun turn(sessionId: String, running: Boolean) {
        turns[sessionId] = running
    }

    fun remove(sessionId: String) {
        authoritative.remove(sessionId)
        turns.remove(sessionId)
    }

    fun clear() {
        authoritative.clear()
        turns.clear()
        // Do not reuse tickets when the host changes.
        revision++
    }
}
