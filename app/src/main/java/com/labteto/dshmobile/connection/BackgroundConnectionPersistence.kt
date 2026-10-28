package com.labteto.dshmobile.connection

import android.content.Context
import android.util.AtomicFile
import com.labteto.dshmobile.core.wire.WireJson
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl

/** A stop is durable user/auth intent, not the transient state of a socket. */
@Serializable
enum class BackgroundConnectionStop { NONE, USER, AUTH }

@Serializable
data class BackgroundConnectionIntent(
    val hostId: String? = null,
    val baseUrl: String? = null,
    val stopped: BackgroundConnectionStop = BackgroundConnectionStop.NONE,
    val timedOut: Boolean = false,
) {
    /** Bind both identity and endpoint: editing/replacing a remembered host cannot redirect recovery. */
    fun rememberedHost(enabled: Boolean, hosts: List<HostConfig>): HostConfig? {
        if (!enabled || timedOut || stopped != BackgroundConnectionStop.NONE) return null
        return hosts.firstOrNull { it.id == hostId && it.baseUrl == baseUrl && validRecoveryHost(it) }
    }
}

data class BackgroundRecoverySnapshot(val enabled: Boolean, val hosts: List<HostConfig>)

internal data class BackgroundRestoreTicket(val epoch: Long, val intent: BackgroundConnectionIntent) {
    fun isCurrent(epoch: Long, intent: BackgroundConnectionIntent): Boolean = this.epoch == epoch && this.intent == intent
}

internal fun validRecoveryHost(host: HostConfig): Boolean = runCatching {
    val url = host.baseUrl.toHttpUrl()
    host.id.isNotBlank() && host.host.isNotBlank() && host.port in 1..65535 &&
        url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null
}.getOrDefault(false)

/**
 * Tiny synchronous, atomic journal. No coroutine writes are queued: disconnect/timeout is durable
 * before returning, so an older suspended connect cannot resurrect an intent after process death.
 * Kept outside backups: restoring app data must not re-arm background access on another device.
 * Hosts/settings remain in HostsStore; this file contains no credentials or duplicate host records.
 */
@Singleton
class BackgroundConnectionPersistence internal constructor(file: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, "background-connection.json"))

    private val file = AtomicFile(file)
    private var current = read()

    @Synchronized fun snapshot(): BackgroundConnectionIntent = current

    @Synchronized fun desire(host: HostConfig) = change(current.copy(
        hostId = host.id, baseUrl = host.baseUrl, stopped = BackgroundConnectionStop.NONE,
    ))

    @Synchronized fun stop(reason: BackgroundConnectionStop) = change(current.copy(
        hostId = null, baseUrl = null, stopped = reason,
    ))

    @Synchronized fun timeout() = change(current.copy(timedOut = true))
    @Synchronized fun foreground() = change(current.copy(timedOut = false))

    private fun read(): BackgroundConnectionIntent = try {
        file.openRead().bufferedReader().use { WireJson.decodeFromString(BackgroundConnectionIntent.serializer(), it.readText()) }
    } catch (_: Exception) {
        // Missing/corrupt journal never grants automatic access.
        BackgroundConnectionIntent(stopped = BackgroundConnectionStop.USER)
    }

    private fun change(next: BackgroundConnectionIntent) {
        if (next == current) return
        var output: java.io.FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(WireJson.encodeToString(BackgroundConnectionIntent.serializer(), next).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
            current = next
        } catch (failure: Exception) {
            output?.let { file.failWrite(it) }
            // Never keep a previous granted intent after a failed revocation/budget write.
            current = BackgroundConnectionIntent(stopped = BackgroundConnectionStop.USER, timedOut = next.timedOut)
            file.delete()
            throw failure
        }
    }
}
