package com.labteto.dshmobile.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.connection.*
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.wire.dto.RemoteEventFrame
import com.labteto.dshmobile.data.HistoryCache
import com.labteto.dshmobile.data.SessionStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** No paired host or remote writes. Preferences, connection state and cache are fixture-owned. */
class NotificationDeliveryTest {
    @Test fun startWiresRealStoreAndPostsGoalAndTurnNotifications() = fixture { store, observer, manager, notifications ->
        assertNotNull("Application observer start must attach the store callback", store.notificationSink)
        val method = SessionStore::class.java.getDeclaredMethod("handleSessionEvent", String::class.java, SessionEventEnvelope::class.java)
            .apply { isAccessible = true }
        val session = "fixture-session"
        val host = manager.state.value.host!!
        val destination = NotificationDestination(NotificationHost(host.id, host.baseUrl), session)
        val tag = "${DshNotifications.CHANNEL_COMPLETIONS}:${destination.key}"
        method.invoke(store, session, SessionEventEnvelope("goal/change", 1, 1, buildJsonObject {
            putJsonObject("goal") { put("phase", "complete"); put("objective", "fixture complete") }
        }))
        val goal = awaitTitle(notifications, tag, "fixture complete")
        assertNotNull(goal.notification.contentIntent)
        method.invoke(store, session, SessionEventEnvelope("goal/change", 2, 2, buildJsonObject {
            putJsonObject("goal") {
                put("phase", "blocked")
                putJsonObject("blockedReason") { put("message", "fixture blocked") }
            }
        }))
        awaitTitle(notifications, tag, "fixture blocked")
        method.invoke(store, session, SessionEventEnvelope("turn/end", 3, 3, buildJsonObject {
            put("turn", 1); putJsonObject("reason") { put("kind", "completed") }
        }))
        withTimeout(5_000) {
            while (notifications.activeNotifications.none { it.tag == tag && it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.contains("fixture blocked") == false }) delay(20)
        }
        observer.start() // idempotent; must not detach the sink or duplicate collectors
        assertNotNull(store.notificationSink)
    }

    @Test fun repeatedQuestionsReplayAndHostIdentityAreHandledByRealObserver() = fixture { _, _, manager, notifications ->
        @Suppress("UNCHECKED_CAST")
        val frames = manager.hostEventFrames as MutableSharedFlow<HostEventFrame>
        withTimeout(5_000) { while (frames.subscriptionCount.value == 0) delay(10) }
        val firstHost = manager.state.value.host!!
        suspend fun question(host: HostConfig, eventId: String, title: String) {
            frames.emit(HostEventFrame(host.id, host.baseUrl, RemoteEventFrame.Waterfall(
                event = "user-questions/request", eventId = eventId, agentId = "fixture-session",
                request = buildJsonObject { putJsonArray("questions") { add(buildJsonObject { put("question", title) }) } },
            )))
        }
        fun tag(host: HostConfig) = "${DshNotifications.CHANNEL_ACTION}:${NotificationDestination(NotificationHost(host.id, host.baseUrl), "fixture-session").key}"
        question(firstHost, "event-one", "fixture first")
        awaitTitle(notifications, tag(firstHost), "fixture first")
        question(firstHost, "event-one", "fixture replay must not replace")
        delay(150)
        assertTrue(notifications.activeNotifications.single { it.tag == tag(firstHost) }.notification.extras
            .getCharSequence(Notification.EXTRA_TITLE).toString().contains("fixture first"))
        question(firstHost, "event-two", "fixture second")
        awaitTitle(notifications, tag(firstHost), "fixture second")
        val secondHost = firstHost.copy(id = "fixture-host-two", host = "second.invalid")
        setState(manager, ConnectionUiState(host = secondHost))
        question(firstHost, "stale-host-event", "stale must not appear")
        question(secondHost, "event-two", "fixture other host")
        awaitTitle(notifications, tag(secondHost), "fixture other host")
        assertEquals(2, notifications.activeNotifications.count { it.tag?.startsWith("needs_action:") == true && it.tag.contains("fixture-") })
        assertFalse(notifications.activeNotifications.any { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("stale must not appear") })
    }

    @Test fun notificationIntentsRoundTripAndLegacyOrWrongActionFailsClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = NotificationDestination(NotificationHost("saved", "https://example.test/proxy"), "session /中文")
        val intent = DshNotifications.sessionIntent(context, destination)
        assertEquals(destination, DshNotifications.destinationFromIntent(intent))
        assertEquals(destination, DshNotifications.destinationFromIntent(Intent(intent))) // warm delivery is identical
        assertNull(DshNotifications.destinationFromIntent(Intent(intent).setAction(Intent.ACTION_VIEW)))
        assertNull(DshNotifications.destinationFromIntent(Intent(intent).setData(android.net.Uri.parse("dshmobile://host/current/session/old"))))
    }

    private suspend fun awaitTitle(manager: NotificationManager, tag: String, text: String): android.service.notification.StatusBarNotification =
        withTimeout(5_000) {
            while (true) {
                val notification = manager.activeNotifications.firstOrNull { it.tag == tag && it.notification.extras
                    .getCharSequence(Notification.EXTRA_TITLE).toString().contains(text) }
                if (notification != null) return@withTimeout notification
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

    @Suppress("UNCHECKED_CAST")
    private fun setState(manager: ConnectionManager, state: ConnectionUiState) {
        val field = ConnectionManager::class.java.getDeclaredField("_state").apply { isAccessible = true }
        (field.get(manager) as MutableStateFlow<ConnectionUiState>).value = state
    }

    private fun fixture(test: suspend (SessionStore, NotificationObserver, ConnectionManager, NotificationManager) -> Unit) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val prefix = "notification-fixture-${UUID.randomUUID()}"
        val preferenceNames = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = "$prefix-$name"
                preferenceNames += isolated
                return super.getSharedPreferences(isolated, mode)
            }
        }
        val file = File(base.cacheDir, "$prefix.preferences_pb")
        val cache = File(base.cacheDir, "$prefix-history")
        val dataJob = SupervisorJob()
        val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + dataJob), produceFile = { file })
        val credentials = RelayCredentialStore(data)
        val hosts = HostsStore(data, credentials, context)
        val client = OkHttpClient()
        val manager = ConnectionManager(context, HarnessClientFactory(client, credentials, HarnessSessionStore(data, client)), hosts,
            backgroundIntent = BackgroundConnectionPersistence(File(base.cacheDir, "$prefix-background.json")),
            backgroundRuntime = object : ConnectionBackgroundRuntime(context) {
                override fun updateWork(enabled: Boolean) = Unit
                override fun startService() = Unit
                override fun stopService() = Unit
            })
        val store = SessionStore(context, manager, hosts, HistoryCache(cache))
        val notifications = base.getSystemService(NotificationManager::class.java)
        val observer = NotificationObserver(store, manager, DshNotifications(context), hosts, context)
        val scopes = listOf(observer, store, manager)
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.POST_NOTIFICATIONS)
        try {
            setState(manager, ConnectionUiState(host = HostConfig("fixture-host-one", "fixture", "first.invalid", 3080)))
            observer.start()
            test(store, observer, manager, notifications)
        } finally {
            scopes.forEach { instance ->
                val field = instance.javaClass.getDeclaredField("scope").apply { isAccessible = true }
                (field.get(instance) as CoroutineScope).coroutineContext[Job]?.cancelAndJoin()
            }
            val monitorField = ConnectionManager::class.java.getDeclaredField("networkMonitor").apply { isAccessible = true }
            val monitor = monitorField.get(manager)
            val callbackField = monitor.javaClass.getDeclaredField("callback").apply { isAccessible = true }
            base.getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(callbackField.get(monitor) as android.net.ConnectivityManager.NetworkCallback)
            notifications.activeNotifications.filter { it.tag?.contains("fixture-") == true }.forEach { notifications.cancel(it.tag, it.id) }
            if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.dropShellPermissionIdentity()
            dataJob.cancelAndJoin()
            file.delete()
            cache.listFiles()?.forEach { it.delete() }; cache.delete()
            preferenceNames.forEach { base.deleteSharedPreferences(it) }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
