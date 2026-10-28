package com.labteto.dshmobile.connection

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/** A build may supply a stable endpoint, never a launch token or other authentication material. */
internal fun configuredHost(raw: String): HostConfig? {
    val url = raw.trim().toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
    val path = url.encodedPath.trimEnd('/')
    val base = harnessBaseUrl(url.host, url.port, url.isHttps, path)
    return HostConfig(
        id = UUID.nameUUIDFromBytes(base.toByteArray(Charsets.UTF_8)).toString(),
        name = "DSH",
        host = url.host,
        port = url.port,
        useTls = url.isHttps,
        basePath = path,
        isLoopback = url.host in setOf("127.0.0.1", "localhost", "::1"),
    )
}

/** Saved endpoints take priority over the optional build default, within the selected mode. */
internal fun initialConnectionHost(mode: String, remembered: List<HostConfig>, fallback: String): HostConfig? =
    remembered.firstOrNull { it.belongsTo(mode) }
        ?: configuredHost(fallback)?.takeIf { it.belongsTo(mode) }
