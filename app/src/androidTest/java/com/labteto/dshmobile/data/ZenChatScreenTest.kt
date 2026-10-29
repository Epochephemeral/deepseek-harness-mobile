package com.labteto.dshmobile.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.*
import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.QueueItem
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.core.storage.DiskCacheStore
import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionItem
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionOption
import com.labteto.dshmobile.core.wire.dto.ContentBlock
import com.labteto.dshmobile.core.wire.dto.GoalPhase
import com.labteto.dshmobile.core.wire.dto.GoalSnapshot
import com.labteto.dshmobile.core.wire.dto.TodoItem
import com.labteto.dshmobile.core.wire.dto.TodoWriteData
import com.labteto.dshmobile.di.AppModule
import com.labteto.dshmobile.ui.screens.main.ChatScreen
import com.labteto.dshmobile.ui.screens.main.ComposerKey
import com.labteto.dshmobile.ui.theme.DshTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ZenChatScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)

    @Test fun docksHideTogetherAndReturnWithLatestStateWithoutLosingConversationOrDraft() = withScreen { fixture ->
        assertDocksVisible("Goal 1")
        assertConversationAndDraft()
        compose.onNodeWithContentDescription(label(R.string.chat_zen_enter)).performClick()
        assertDocksHidden("Goal 1")
        assertConversationAndDraft()
        assertTrue(fixture.chatPreferences.getBoolean("zen_mode", false))

        // Model a new server projection while the real screen stays in Zen Mode.
        compose.runOnIdle { fixture.publish(2) }
        assertDocksHidden("Goal 2")
        assertConversationAndDraft()
        exitZen()

        assertDocksVisible("Goal 2")
        compose.onNodeWithText("Goal 1").assertDoesNotExist()
        assertConversationAndDraft()
        assertFalse(fixture.chatPreferences.getBoolean("zen_mode", true))
        compose.onNodeWithText(label(R.string.chat_todo_title)).performClick()
        compose.onNodeWithText("Todo 2").assertIsDisplayed()
        compose.onNodeWithText("Todo 1").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.chat_todo_title)).performClick()
        compose.onNodeWithText(label(R.string.chat_queue_title)).performClick()
        compose.onNodeWithText("Queued 2").assertIsDisplayed()
        compose.onNodeWithText("Queued 1").assertDoesNotExist()
    }

    @Test fun persistedZenStartupKeepsBlockingApprovalAndQuestionVisible() = withScreen(zen = true) { fixture ->
        assertDocksHidden("Goal 1")
        assertConversationAndDraft()
        compose.onNodeWithContentDescription(label(R.string.chat_zen_enter)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.chat_zen_menu)).assertIsDisplayed()

        compose.runOnIdle {
            fixture.approval.value = PendingApproval(SESSION, "approval", "approval-rpc", "bash", "Approve fixture action")
        }
        compose.onNodeWithText(label(R.string.approval_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.approval_allow_once)).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(label(R.string.approval_reject)).assertIsDisplayed().assertIsEnabled()
        assertDocksHidden("Goal 1")

        compose.runOnIdle {
            fixture.approval.value = null
            fixture.questions.value = PendingQuestions(SESSION, "question-rpc", listOf(
                AskUserQuestionItem("choice", "Which fixture option?", options = listOf(AskUserQuestionOption("Keep draft"))),
            ))
        }
        compose.onNodeWithText("Which fixture option?").assertIsDisplayed()
        // Selecting an option is local UI state; do not submit an RPC from this offline fixture.
        compose.onNodeWithText("Keep draft").assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.questions_submit)).assertIsDisplayed().assertIsEnabled()
        assertDocksHidden("Goal 1")
        compose.runOnIdle { fixture.questions.value = null }
        assertConversationAndDraft()
        exitZen()
        assertDocksVisible("Goal 1")
        assertConversationAndDraft()
    }

    private fun assertDocksVisible(goal: String) {
        compose.onNodeWithText(goal).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.chat_todo_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.chat_queue_title)).assertIsDisplayed()
    }

    private fun assertDocksHidden(goal: String) {
        compose.onNodeWithText(goal).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.chat_todo_title)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.chat_queue_title)).assertDoesNotExist()
    }

    private fun assertConversationAndDraft() {
        compose.onNodeWithText(TRANSCRIPT).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextEquals(DRAFT)
    }

    private fun exitZen() {
        compose.onNodeWithContentDescription(label(R.string.chat_zen_menu)).performClick()
        compose.onNodeWithText(label(R.string.chat_zen_exit)).performClick()
    }

    private fun withScreen(zen: Boolean = false, test: (Fixture) -> Unit) {
        val fixture = Fixture(context)
        var mounted by mutableStateOf(true)
        try {
            assertTrue(fixture.chatPreferences.edit().putBoolean("zen_mode", zen).commit())
            compose.setContent {
                if (mounted) {
                    // Retain the Activity as the base for launchers, window and lifecycle services.
                    val activityContext = LocalContext.current
                    val isolatedContext = remember(activityContext) {
                        object : ContextWrapper(activityContext) {
                            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                                fixture.isolatedContext.getSharedPreferences(name, mode)
                        }
                    }
                    CompositionLocalProvider(LocalContext provides isolatedContext) {
                        DshTheme { ChatScreen({}, {}, detailsOpen = false, store = fixture.store) }
                    }
                }
            }
            test(fixture)
            compose.runOnIdle {
                assertEquals(DRAFT, fixture.store.composers.get(ComposerKey("", SESSION)).text)
            }
        } finally {
            try {
                compose.runOnIdle { mounted = false }
                compose.waitForIdle()
            } finally {
                fixture.close()
            }
        }
    }

    /** Only this test owns these preferences, cache, DataStore and offline connection. */
    private class Fixture(private val base: Context) {
        private val prefix = "zen-chat-test-${UUID.randomUUID()}"
        private val directory = File(base.cacheDir, prefix).apply { check(mkdirs()) }
        private val preferenceNames = mutableSetOf<String>()
        val isolatedContext = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = "${prefix}_$name"
                synchronized(preferenceNames) { preferenceNames.add(isolatedName) }
                return base.getSharedPreferences(isolatedName, mode)
            }
            override fun getSystemService(name: String): Any? =
                if (name == Context.CONNECTIVITY_SERVICE) null else super.getSystemService(name)
        }
        val chatPreferences = isolatedContext.getSharedPreferences("chat_ui", Context.MODE_PRIVATE)
        private val dataJob = SupervisorJob()
        private val data = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + dataJob),
            produceFile = { File(directory, "test.preferences_pb") },
        )
        private val http = AppModule.provideOkHttpClient()
        private val credentials = RelayCredentialStore(data)
        private val hosts = HostsStore(data, credentials, isolatedContext)
        private val disk = DiskCacheStore(File(directory, "managed"))
        private val manager = ConnectionManager(
            isolatedContext, HarnessClientFactory(http, credentials, HarnessSessionStore(data, http), disk), hosts,
            networkMonitor = ConnectionNetworkMonitor(isolatedContext),
            backgroundIntent = BackgroundConnectionPersistence(File(directory, "background.json")),
            backgroundRuntime = object : ConnectionBackgroundRuntime(isolatedContext) {
                override fun updateWork(enabled: Boolean) = Unit
                override fun startService() = Unit
                override fun stopService() = Unit
            },
        )
        private val cache = HistoryCache(File(disk.root, "preview"), diskStore = disk)
        val store = SessionStore(isolatedContext, manager, hosts, cache, disk)
        val approval = flow<PendingApproval?>("_pendingApproval")
        val questions = flow<PendingQuestions?>("_pendingQuestions")
        private val conversation = flow<ConversationSnapshot?>("_currentConversation")

        init {
            // Freeze background owners before seeding UI flows: no live accounts, RPCs or races.
            runBlocking {
                ownerScope(store).coroutineContext[Job]?.cancelAndJoin()
                ownerScope(manager).coroutineContext[Job]?.cancelAndJoin()
            }
            flow<String?>("_currentSessionId").value = SESSION
            store.composers.get(ComposerKey("", SESSION)).text = DRAFT
            publish(1)
        }

        fun publish(revision: Int) {
            conversation.value = ConversationSnapshot(
                sessionId = SESSION,
                nodes = listOf(UserMessageNode(1, "message", listOf(ChatBlock("text", TRANSCRIPT)), "user")),
                blank = false,
                lastSeq = 1,
                projections = mapOf(
                    "goal" to WireJson.encodeToJsonElement(GoalSnapshot.serializer(),
                        GoalSnapshot("goal", revision, "Goal $revision", GoalPhase.ACTIVE, maxGoalRounds = 5)),
                    "todos" to WireJson.encodeToJsonElement(TodoWriteData.serializer(),
                        TodoWriteData(listOf(TodoItem("Todo $revision", "in_progress")))),
                ),
                queue = listOf(QueueItem("queued", "queued", "Queued $revision",
                    WireJson.encodeToJsonElement(ListSerializer(ContentBlock.serializer()),
                        listOf(ContentBlock.Text("Queued $revision"))))),
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> flow(name: String): MutableStateFlow<T> =
            SessionStore::class.java.getDeclaredField(name).apply { isAccessible = true }.get(store) as MutableStateFlow<T>

        private fun ownerScope(owner: Any): CoroutineScope =
            owner.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(owner) as CoroutineScope

        fun close() {
            runBlocking {
                ownerScope(store).coroutineContext[Job]?.cancelAndJoin()
                ownerScope(manager).coroutineContext[Job]?.cancelAndJoin()
                store.composers.scope.coroutineContext[Job]?.cancelAndJoin()
                dataJob.cancelAndJoin()
            }
            cache.close()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
            synchronized(preferenceNames) {
                preferenceNames.forEach { name ->
                    check(name.startsWith("${prefix}_"))
                    base.deleteSharedPreferences(name)
                }
            }
            check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && directory.name == prefix)
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val SESSION = "zen-fixture"
        const val TRANSCRIPT = "Conversation survives"
        const val DRAFT = "Unsent draft survives"
    }
}
