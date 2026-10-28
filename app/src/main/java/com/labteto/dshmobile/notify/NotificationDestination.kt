package com.labteto.dshmobile.notify

import com.labteto.dshmobile.connection.HostConfig
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** An identity, never connection configuration. Only an exact saved endpoint may resolve it. */
data class NotificationHost(val id: String, val baseUrl: String) {
    fun matches(host: HostConfig?): Boolean = host != null && host.id == id && host.baseUrl == baseUrl
    val key: String get() = "${id.length}:$id$baseUrl"
}

data class NotificationDestination(val host: NotificationHost, val sessionId: String) {
    val key: String get() = "${host.key.length}:${host.key}$sessionId"

    fun uri(): String = "$PREFIX?host=${encode(host.id)}&endpoint=${encode(host.baseUrl)}&session=${encode(sessionId)}"

    /** Reject forgotten/replaced hosts; notification data never supplies an arbitrary endpoint. */
    fun resolve(hosts: List<HostConfig>): HostConfig? = hosts.singleOrNull(host::matches)

    companion object {
        private const val PREFIX = "dshmobile://notification/session"
        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
        private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
        private fun valid(value: String, max: Int): Boolean =
            value.isNotBlank() && value.length <= max && value.none { it.isISOControl() }

        fun parse(raw: String?): NotificationDestination? = runCatching {
            if (raw == null || raw.length > 8192) return null
            val uri = URI(raw)
            if (uri.scheme != "dshmobile" || uri.rawAuthority != "notification" ||
                uri.rawPath != "/session" || uri.rawFragment != null) return null
            val pairs = uri.rawQuery?.split('&')?.map { part ->
                val bits = part.split('=', limit = 2)
                if (bits.size != 2) return null
                decode(bits[0]) to decode(bits[1])
            } ?: return null
            if (pairs.size != 3 || pairs.map { it.first }.toSet() != setOf("host", "endpoint", "session")) return null
            val values = pairs.toMap()
            val id = values.getValue("host")
            val endpoint = values.getValue("endpoint")
            val session = values.getValue("session")
            if (!valid(id, 512) || !valid(endpoint, 4096) || !valid(session, 512)) return null
            NotificationDestination(NotificationHost(id, endpoint), session)
        }.getOrNull()
    }
}

/** Shared cold/warm route: resolve a saved identity, connect it, then await the store's baseline gate. */
internal class NotificationRouter(
    private val savedHosts: suspend () -> List<HostConfig>,
    private val connect: suspend (HostConfig) -> Unit,
    private val openWhenReady: suspend (NotificationDestination) -> Boolean,
) {
    suspend fun route(destination: NotificationDestination): Boolean {
        val saved = destination.resolve(savedHosts()) ?: return false
        connect(saved)
        // Recheck after suspension: forgetting/replacing a host must invalidate a pending route.
        if (destination.resolve(savedHosts()) == null) return false
        return openWhenReady(destination)
    }
}
