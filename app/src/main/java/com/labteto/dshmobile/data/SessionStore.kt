package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.READER_VIEW
import com.labteto.dshmobile.core.wire.READER_MAX_ROWS
import com.labteto.dshmobile.core.wire.READER_MAX_BYTES
import com.labteto.dshmobile.core.wire.requireReaderCoverage
import com.labteto.dshmobile.core.wire.INITIAL_FOLLOW_MESSAGES

import com.labteto.dshmobile.core.wire.automationCatalog
import com.labteto.dshmobile.core.wire.pluginBundles

import android.util.Base64
import android.util.Log
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.core.session.AssistantLiveState
import com.labteto.dshmobile.core.session.ChunkRows
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.EventFold
import com.labteto.dshmobile.core.session.QueueItem
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.session.SessionRunningState
import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.dto.APPROVAL_REQUEST_EVENT
import com.labteto.dshmobile.core.wire.dto.AgentPresetListValue
import com.labteto.dshmobile.core.wire.dto.ApprovalOutcome
import com.labteto.dshmobile.core.wire.dto.ApprovalRequestEvent
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionAnswer
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionIntent
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionItem
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionRequestEvent
import com.labteto.dshmobile.core.wire.dto.CUSTOM_PRESET
import com.labteto.dshmobile.core.wire.dto.CommandDescriptor
import com.labteto.dshmobile.core.wire.dto.CommandSubmitAttachment
import com.labteto.dshmobile.core.wire.dto.ContentBlock
import com.labteto.dshmobile.core.wire.dto.ContextBreakdownView
import com.labteto.dshmobile.core.wire.dto.ContextPressureView
import com.labteto.dshmobile.core.wire.dto.EncodedFileUploadRequest
import com.labteto.dshmobile.core.wire.dto.EncodedImageAttachment
import com.labteto.dshmobile.core.wire.dto.FileUploadValue
import com.labteto.dshmobile.core.wire.dto.GoalRef
import com.labteto.dshmobile.core.wire.dto.HostDescription
import com.labteto.dshmobile.core.wire.dto.ImageLimitsView
import com.labteto.dshmobile.core.wire.dto.ImageRejection
import com.labteto.dshmobile.core.wire.dto.JobView
import com.labteto.dshmobile.core.wire.dto.PermissionSelect
import com.labteto.dshmobile.core.wire.dto.PlanStateView
import com.labteto.dshmobile.core.wire.dto.PluginInventorySnapshot
import com.labteto.dshmobile.core.wire.dto.PromptContentPart
import com.labteto.dshmobile.core.wire.dto.QUESTION_CANCELLED
import com.labteto.dshmobile.core.wire.dto.QueueAction
import com.labteto.dshmobile.core.wire.dto.ModelSelectionProjection
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import com.labteto.dshmobile.core.wire.dto.ModelCatalog
import com.labteto.dshmobile.core.wire.dto.InboxProjection
import com.labteto.dshmobile.core.wire.dto.QueuedInboxItem
import com.labteto.dshmobile.core.wire.dto.RemoteEventFrame
import com.labteto.dshmobile.core.wire.dto.RemoteEventOutcome
import com.labteto.dshmobile.core.wire.dto.RemoteEventRejection
import com.labteto.dshmobile.core.wire.dto.SessionAddress
import com.labteto.dshmobile.core.wire.dto.SessionAttachmentRequest
import com.labteto.dshmobile.core.wire.dto.SessionCancelRequest
import com.labteto.dshmobile.core.wire.dto.SessionControlFrame
import com.labteto.dshmobile.core.wire.dto.SessionControlFrameSerializer
import com.labteto.dshmobile.core.wire.dto.SessionCreateRequest
import com.labteto.dshmobile.core.wire.dto.SessionEvent
import com.labteto.dshmobile.core.wire.dto.SessionFollowFrame
import com.labteto.dshmobile.core.wire.dto.SessionFollowFrameSerializer
import com.labteto.dshmobile.core.wire.dto.SessionFollowRequest
import com.labteto.dshmobile.core.wire.dto.SessionForkRequest
import com.labteto.dshmobile.core.wire.dto.SessionHistoryRecord
import com.labteto.dshmobile.core.wire.dto.SessionModelsValue
import com.labteto.dshmobile.core.wire.dto.SessionPageRequest
import com.labteto.dshmobile.core.wire.dto.SessionProjectionsBlock
import com.labteto.dshmobile.core.wire.dto.SessionPromptRequest
import com.labteto.dshmobile.core.wire.dto.SessionRenameRequest
import com.labteto.dshmobile.core.wire.dto.SessionSelectModelRequest
import com.labteto.dshmobile.core.wire.dto.SessionStatsView
import com.labteto.dshmobile.core.wire.dto.SessionSummary
import com.labteto.dshmobile.core.wire.dto.SessionUpdateQueueRequest
import com.labteto.dshmobile.core.wire.dto.SkillEntry
import com.labteto.dshmobile.core.wire.dto.SkillListRequest
import com.labteto.dshmobile.core.wire.dto.SubagentListEntry
import com.labteto.dshmobile.core.wire.dto.JobFollowFrame
import com.labteto.dshmobile.core.wire.dto.JobFollowFrameSerializer
import com.labteto.dshmobile.core.wire.dto.JobFollowRequest
import com.labteto.dshmobile.core.wire.dto.JobListRequest
import com.labteto.dshmobile.core.wire.dto.SessionActivity
import com.labteto.dshmobile.core.wire.dto.WorkspaceSessionActiveDetails
import com.labteto.dshmobile.core.wire.dto.jobRowsOf
import com.labteto.dshmobile.core.wire.dto.subagentEntriesFromCatalog
import com.labteto.dshmobile.core.wire.dto.SubagentPromptRequest
import com.labteto.dshmobile.core.wire.dto.TokenUsageView
import com.labteto.dshmobile.core.wire.dto.USER_QUESTIONS_REQUEST_EVENT
import com.labteto.dshmobile.core.wire.dto.UnknownSubagentListEntry
import com.labteto.dshmobile.core.wire.dto.WorkspaceArchiveSessionRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceCreateRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceDeleteRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceFollowFrame
import com.labteto.dshmobile.core.wire.dto.WorkspaceFollowFrameSerializer
import com.labteto.dshmobile.core.wire.dto.WorkspaceRenameRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceValue
import com.labteto.dshmobile.core.wire.dto.WorkspaceView
import com.labteto.dshmobile.core.wire.dto.imageRejectionOf
import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.TransportFailures
import com.labteto.dshmobile.core.wire.encodeToJsonElement
import com.labteto.dshmobile.core.wire.newPromptRequestId
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.TimeZone
import com.labteto.dshmobile.core.wire.dto.PermissionCatalog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Current CAS identity from a bare goal/ref or the harness's `{ goal: ... }` projection. */
internal fun goalRefFromProjection(value: JsonElement?): GoalRef? {
    val projection = value as? JsonObject ?: return null
    // An explicit null goal means cleared; never fall back to an envelope's unrelated fields.
    val goal = if ("goal" in projection) projection["goal"] else projection
    return goal?.let {
        runCatching { decodeFromJsonElement(GoalRef.serializer(), it) }.getOrNull()
    }?.takeIf { it.id.isNotBlank() && it.revision > 0 }
}

/** One renderable session list row (manual order, live). */
data class SessionRow(
    val sessionId: String,
    val title: String?,
    val running: Boolean,
    val blank: Boolean,
    val parentSessionId: String?,
    val origin: String?,
    val cwd: String?,
    val agentPreset: String?,
    val updatedAt: Long,
    val pendingInteraction: String?, // "approval" | "plan-review" | "question" | null
    val formatStatus: String? = null,
)

/** One renderable workspace row. */
data class WorkspaceRow(
    val workspaceId: String,
    val path: String,
    val title: String,
    val sessionIds: List<String>,
    /**
     * `WorkspaceView.updatedAt` as epoch millis (0 when unparseable). This stamps the *registration
     * record* — a rename or a session being added — not conversation activity, so it is only a
     * tiebreak for recency ranking, never the primary key.
     */
    val updatedAtEpoch: Long = 0L,
)

/** What a slash command did, so the caller can report it without re-reading the wire. */
sealed interface CommandOutcome {
    /** The host executed the line; [text] is its settlement message, when it produced one. */
    data class Ok(val text: String?) : CommandOutcome

    /** The line named no registered command. */
    data class Unknown(val line: String) : CommandOutcome

    /** The command ran and reported a usage or state failure. */
    data class Failed(val message: String) : CommandOutcome
}

/** What the harness did with a prompt. */
sealed interface PromptOutcome {
    /** Accepted; the turn is the transcript's business now. */
    data object Ok : PromptOutcome

    /**
     * The host refused the images. Carried as its own case because it is a composer problem, not
     * a connection problem: raising the persistent connection banner for an image that is 200px
     * too wide tells the user their harness is broken when only their picture is.
     */
    data class Rejected(val rejection: ImageRejection, val reason: String?) : PromptOutcome

    /** Anything else; the connection banner already carries [message]. */
    data class Failed(val message: String) : PromptOutcome
}

/** What the harness did with an answer to a question request, or with a dismissal of one. */
sealed interface QuestionOutcome {
    /**
     * Taken. The wait behind the card is over, so the card goes with it — the host announces that
     * resolution to every *other* client and to this one never, so nothing else can take it away.
     */
    data object Accepted : QuestionOutcome

    /**
     * Refused by the host. `bad-response` means the payload did not match the request it
     * answered; [NOT_PENDING] means the wait had already settled; anything else is the code the
     * host sent, named rather than translated — a refusal this build has never heard of is still
     * worth showing, because the wait behind it stays open either way.
     */
    data class Refused(val reason: String) : QuestionOutcome

    /** The POST never completed, so nothing is known about the wait. */
    data object Unsent : QuestionOutcome
}

/** The refusal that means the request this answer addressed is already over. */
internal const val NOT_PENDING: String = "not-pending"

/**
 * Whether [outcome] means this client is done holding the request it answered.
 *
 * Two of the three do. [QuestionOutcome.Accepted] is the host taking the answer, and
 * [QuestionOutcome.Refused] with [NOT_PENDING] is the host saying the wait had already settled —
 * one is a card that did its job and the other a card that outlived its request, and neither has
 * anything left to send. Every other refusal leaves the host's wait open with the tool call behind
 * it still blocked, so the card has to stay: it is the only thing that can still answer. So does
 * [QuestionOutcome.Unsent], where nothing is known about the wait at all and taking the card away
 * would strand the session with no way to retry.
 *
 * File-level so the rule is testable without standing up the whole store, as [nextHasMore] is.
 */
internal fun settlesRequest(outcome: QuestionOutcome): Boolean = when (outcome) {
    is QuestionOutcome.Accepted -> true
    is QuestionOutcome.Refused -> outcome.reason == NOT_PENDING
    is QuestionOutcome.Unsent -> false
}

/** Wire workspace -> renderable row, parsing the ISO-8601 stamp once at the boundary. */
private fun WorkspaceView.toRow(): WorkspaceRow = WorkspaceRow(
    workspaceId = workspaceId,
    path = path,
    title = title,
    sessionIds = sessionIds,
    updatedAtEpoch = runCatching { Instant.parse(updatedAt).toEpochMilli() }.getOrDefault(0L),
)

/** A pending sandbox/permission approval the user can answer (allow-once / reject). */
data class PendingApproval(
    val sessionId: String,
    val approvalId: String,
    val rpcId: String,
    val toolName: String,
    val reason: String?,
)

/** A pending ask_user_question batch (a plan review rides the same channel via its intent). */
data class PendingQuestions(
    val sessionId: String,
    val rpcId: String,
    val items: List<AskUserQuestionItem>,
)

/**
 * Whether more history remains after folding a backwards page.
 *
 * The load-bearing clause is [freshCount]: history paging is driven by scroll position, so a page
 * that added nothing new has to end the paging regardless of what the host claims. Believing a
 * `hasMore` that a `beforeSeq` query can no longer advance past leaves the scroll trigger firing
 * against the same page forever.
 *
 * File-level so it is testable without standing up the whole store.
 */
internal fun nextHasMore(freshCount: Int, hostHasMore: Boolean, overDelivered: Boolean): Boolean =
    freshCount > 0 && (hostHasMore || overDelivered)

/** Projection key carrying the agent's pending input; the queue dock's source since 0.1.6-alpha.2. */
private const val INBOX_PROJECTION = "inbox"

/** Projection key carrying a session's agent preset; harness rows have no top-level field for it. */
private const val AGENT_PRESET_PROJECTION = "agentPreset"

/** Projection key carrying a parent's direct subagents; `subagents/list` was removed in 0.1.7. */
private const val SUBAGENT_CATALOG_PROJECTION = "subagentCatalog"

/**
 * What became of an archive request.
 *
 * [Busy] is harness 0.1.7's refusal to archive a session that still has work running; the caller
 * asks the person whether to stop that work, and archives again with `stopActivity` if they agree.
 */
sealed interface ArchiveOutcome {
    data object Archived : ArchiveOutcome
    data class Busy(val sessionId: String, val activity: List<SessionActivity>) : ArchiveOutcome
    data object Failed : ArchiveOutcome
}

/**
 * Single source of truth for the connected harness's live state. All public surface is
 * [StateFlow]; every RPC error becomes [connectionError] and never throws. The store survives
 * reconnects by re-baselining on the connection state transition and on `session/subscribed`.
 */
@Singleton
class SessionStore @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
    private val connectionManager: ConnectionManager,
    private val hostsStore: HostsStore,
    private val historyCache: HistoryCache,
    private val diskCacheStore: com.labteto.dshmobile.core.storage.DiskCacheStore? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var baselineJob: Job? = null
    private val noCurrentGoalMessage = { context.getString(com.labteto.dshmobile.R.string.goal_none) }
    private val migrationRequiredMessage = { context.getString(com.labteto.dshmobile.R.string.alpha2_migration) }
    private val featureAvailability = FeatureAvailability { setConnectionError(it.message) }
    val automationAvailable = featureAvailability.state(HarnessFeature.AUTOMATION)
    val pluginManagementAvailable = featureAvailability.state(HarnessFeature.PLUGIN_MANAGEMENT)
    val timedQuestionsAvailable = featureAvailability.state(HarnessFeature.TIMED_QUESTIONS)
    val sessionReferencesAvailable = featureAvailability.state(HarnessFeature.SESSION_REFERENCES)
    val questionSessions = QuestionSessions(scope, { connectionManager.connectedApi },
        { connectionManager.generation?.mux }, { connectionManager.generation?.clientId },
        onCapability = { clientId, result ->
            if (clientId != null && clientId == connectionManager.generation?.clientId) {
                featureAvailability.observe(HarnessFeature.TIMED_QUESTIONS, featureAvailability.generation(), result)
            }
        },
        onUnavailable = { sid, eventId, questions -> handleQuestionRequested(eventId, sid, questions) })
    private val _hostEvents = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, List<JsonElement>>>(extraBufferCapacity = 128)
    val hostEvents = _hostEvents as kotlinx.coroutines.flow.SharedFlow<Pair<String, List<JsonElement>>>

    val pluginOperations = PluginOperations(scope, ::apiForHost, context.getSharedPreferences("plugin-operation", android.content.Context.MODE_PRIVATE),
        observe = { featureObserver(HarnessFeature.PLUGIN_MANAGEMENT) },
        available = { pluginManagementAvailable.value != false })
    val connectionState = connectionManager.state
    val activeHostKey: String? get() = connectionManager.state.value.host?.let { "${it.baseUrl}|${it.id}" }
    internal val panels = com.labteto.dshmobile.ui.screens.main.PanelRepository()
    internal val composers = com.labteto.dshmobile.ui.screens.main.ComposerRepository(context.getSharedPreferences("composer-drafts", android.content.Context.MODE_PRIVATE))
    fun apiForHost(key: String?): DshApiClient? = if (key != null && key == activeHostKey) connectionManager.connectedApi else null
    fun muxForHost(key: String?) = if (key != null && key == activeHostKey) connectionManager.generation?.mux else null
    fun retryConnection() = connectionManager.reconnectIfNeeded()

    suspend fun <T> featureCall(feature: HarnessFeature, api: DshApiClient, call: suspend () -> RpcResult<T>): RpcResult<T> {
        val observe = featureObserver(feature)
        val result = call()
        observe(api, result)
        return result
    }

    private fun featureObserver(feature: HarnessFeature): (DshApiClient, RpcResult<*>) -> Unit {
        val epoch = featureAvailability.generation()
        val clientId = connectionManager.generation?.clientId
        return { api, result ->
            if (clientId != null && clientId == connectionManager.generation?.clientId && api === connectionManager.connectedApi) {
                featureAvailability.observe(feature, epoch, result)
            }
        }
    }

    private fun probeFeatures() {
        val api = connectionManager.connectedApi ?: return
        scope.launch { featureCall(HarnessFeature.AUTOMATION, api) { api.automationCatalog() } }
        scope.launch { featureCall(HarnessFeature.PLUGIN_MANAGEMENT, api) { api.pluginBundles() } }
    }
    private val permissionCatalog = MutableStateFlow<PermissionCatalog?>(null)
    private var permissionCatalogEpoch = 0L
    private val queuesBySession = MutableStateFlow<Map<String, List<QueueItem>>>(emptyMap())
    val sessionQueues: StateFlow<Map<String, List<QueueItem>>> = queuesBySession.asStateFlow()


    /** Coalesces transcript rebuilds during a stream; see [observeRebuildTicks]. */
    private val rebuildTicks = Channel<Unit>(Channel.CONFLATED)

    // ------------------------------------------------------------------ public StateFlows
    private val _sessions = MutableStateFlow<List<SessionRow>>(emptyList())
    val sessions: StateFlow<List<SessionRow>> = _sessions.asStateFlow()

    private val _workspaces = MutableStateFlow<List<WorkspaceRow>>(emptyList())
    val workspaces: StateFlow<List<WorkspaceRow>> = _workspaces.asStateFlow()

    private val _archivedSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val archivedSessionIds: StateFlow<Set<String>> = _archivedSessionIds.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val searchResults: StateFlow<List<Pair<String, String>>> = _searchResults.asStateFlow()

    private val _contentSearchAvailable = MutableStateFlow(true)

    /**
     * Whether this harness will answer `session.search`.
     *
     * Assumed true and latched false by the first refusal — see [search]. Reset on connect, because
     * it is a fact about the harness on the other end, not about the app.
     */
    val contentSearchAvailable: StateFlow<Boolean> = _contentSearchAvailable.asStateFlow()

    private val _currentConversation = MutableStateFlow<ConversationSnapshot?>(null)
    val currentConversation: StateFlow<ConversationSnapshot?> = _currentConversation.asStateFlow()

    private val _jobs = MutableStateFlow<List<JobView>>(emptyList())
    val jobs: StateFlow<List<JobView>> = _jobs.asStateFlow()

    /**
     * Whether jobs can be stopped and followed from here: true once the harness has answered a
     * `job/list` stream (0.1.7). A 0.1.6 host reports jobs on `session/control` and offers neither.
     */
    private val _jobControls = MutableStateFlow(false)
    val jobControls: StateFlow<Boolean> = _jobControls.asStateFlow()

    /**
     * The registry-global pin set, most recently pinned first, or null when the harness has no
     * pinning (anything before 0.1.7) and the drawer should offer none.
     */
    private val _pinnedSessionIds = MutableStateFlow<List<String>?>(null)
    val pinnedSessionIds: StateFlow<List<String>?> = _pinnedSessionIds.asStateFlow()

    private val _skills = MutableStateFlow<List<SkillEntry>>(emptyList())
    val skills: StateFlow<List<SkillEntry>> = _skills.asStateFlow()

    private val _models = MutableStateFlow<ModelCatalog?>(null)

    /** The host generation's routable model catalog, before the session's own selection is joined in. */
    val modelCatalog: StateFlow<ModelCatalog?> = _models.asStateFlow()

    private val _hostInfo = MutableStateFlow<HostDescription?>(null)
    val hostInfo: StateFlow<HostDescription?> = _hostInfo.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()


    /** A backwards page is in flight; the transcript shows a spinner and suppresses re-entry. */
    private val _loadingOlder = MutableStateFlow(false)
    val loadingOlder: StateFlow<Boolean> = _loadingOlder.asStateFlow()

    /**
     * The last backwards page failed.
     *
     * Paging is driven by scroll position, and `snapshotFlow` only emits distinct values — with the
     * index unchanged after a failure nothing re-fires until the reader scrolls again. So the retry
     * has to be an affordance rather than an automatic repeat.
     */
    private val _loadOlderFailed = MutableStateFlow(false)
    val loadOlderFailed: StateFlow<Boolean> = _loadOlderFailed.asStateFlow()

    private val _subagents = MutableStateFlow<List<SubagentListEntry>>(emptyList())
    val subagents: StateFlow<List<SubagentListEntry>> = _subagents.asStateFlow()

    private val _subagentConversation = MutableStateFlow<ConversationSnapshot?>(null)
    val subagentConversation: StateFlow<ConversationSnapshot?> = _subagentConversation.asStateFlow()
    private val _subagentHistoryState = MutableStateFlow(ArchivedTranscriptState())
    val subagentHistoryState: StateFlow<ArchivedTranscriptState> = _subagentHistoryState.asStateFlow()
    @Volatile private var archivedSubagent: ArchivedSubagentTranscript? = null

    private val _subagentMode = MutableStateFlow<String?>(null)
    val subagentMode: StateFlow<String?> = _subagentMode.asStateFlow()

    private val _pendingApproval = MutableStateFlow<PendingApproval?>(null)
    val pendingApproval: StateFlow<PendingApproval?> = _pendingApproval.asStateFlow()

    private val _pendingQuestions = MutableStateFlow<PendingQuestions?>(null)
    val pendingQuestions: StateFlow<PendingQuestions?> = _pendingQuestions.asStateFlow()

    private val _commands = MutableStateFlow<List<CommandDescriptor>>(emptyList())
    val commands: StateFlow<List<CommandDescriptor>> = _commands.asStateFlow()

    /** False once the harness has told us it has no command registry; the menu degrades, not errors. */
    private val _commandsAvailable = MutableStateFlow(true)
    val commandsAvailable: StateFlow<Boolean> = _commandsAvailable.asStateFlow()

    private val _agentPresets = MutableStateFlow<AgentPresetListValue?>(null)
    val agentPresets: StateFlow<AgentPresetListValue?> = _agentPresets.asStateFlow()

    private val _plugins = MutableStateFlow<PluginInventorySnapshot?>(null)

    /** The host's plugin inventory, or null when this deployment does not expose one. */
    val plugins: StateFlow<PluginInventorySnapshot?> = _plugins.asStateFlow()

    /** The preset a switch is in flight for, cleared when the projection reports it as effective. */
    private val _pendingPermission = MutableStateFlow<String?>(null)
    val pendingPermission: StateFlow<String?> = _pendingPermission.asStateFlow()

    // ------------------------------------------------------------------ projection views
    // These are folds of `currentConversation.projections`, not separate fetches: the harness
    // already pushes every one of them on `session/projection` frames and in the history tail, so
    // deriving keeps them in lockstep with the transcript and adds no round trips. A null value
    // means the key is absent — the harness composes no such service — and callers hide the UI.

    val permissions: StateFlow<PermissionSelect?> = combine(
        projectionOf(PermissionSelect.serializer(), "permissions"), permissionCatalog,
    ) { selection, catalog ->
        // The projection carries its own options; the catalog RPC (permissionPresets/catalog) is a
        // newer host endpoint that 404s on older deployments. When it is absent, keep what the
        // projection already has rather than blanking it.
        selection?.copy(options = catalog?.options ?: selection.options)
    }
        .stateIn(scope, SharingStarted.Eagerly, null)
    val sessionStats: StateFlow<SessionStatsView?> = projectionOf(SessionStatsView.serializer(), "sessionStats")
    val tokenUsage: StateFlow<TokenUsageView?> = projectionOf(TokenUsageView.serializer(), "tokenUsage")
    val contextPressure: StateFlow<ContextPressureView?> =
        projectionOf(ContextPressureView.serializer(), "contextPressure")
    val contextBreakdown: StateFlow<ContextBreakdownView?> =
        projectionOf(ContextBreakdownView.serializer(), "contextBreakdown")
    val imageLimits: StateFlow<ImageLimitsView?> = projectionOf(ImageLimitsView.serializer(), "imageLimits")
    val planState: StateFlow<PlanStateView?> = projectionOf(PlanStateView.serializer(), "plan")

    /** This session's durable model choice; the catalog alone no longer carries one. */
    val modelSelection: StateFlow<ModelSelectionProjection?> =
        projectionOf(ModelSelectionProjection.serializer(), "modelSelection")

    /**
     * The model surface every screen renders: the session's effective selection over the host's
     * catalog.
     *
     * A join rather than a wire value, because 0.1.2 answers the two halves separately — the
     * catalog belongs to the host generation and the selection to the session. `next` wins over
     * `lastUsed` (it is the choice that has not been spent yet), and the deployment default
     * stands in before a session has either.
     */
    val models: StateFlow<SessionModelsValue?> =
        combine(_models, modelSelection) { catalog, selection ->
            if (catalog == null) return@combine null
            val current = selection?.next ?: selection?.lastUsed ?: catalog.default
            SessionModelsValue(
                current = current,
                // `routableProviders` lists what can serve a request at all; whether *this*
                // session can start a turn is whether its own provider is in that list.
                routable = current.provider in catalog.routableProviders,
                groups = catalog.groups,
                failures = catalog.failures,
            )
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** One projection key, decoded leniently: unknown or malformed payloads read as absent. */
    private fun <T> projectionOf(serializer: KSerializer<T>, key: String): StateFlow<T?> =
        currentConversation
            .map { conversation ->
                conversation?.projections?.get(key)?.let { element ->
                    runCatching { decodeFromJsonElement(serializer, element) }.getOrNull()
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, null)

    // ------------------------------------------------------------------ internal state (guarded by `lock`)
    private val sessionRows = LinkedHashMap<String, SessionRow>()
    private val runningBySession = SessionRunningState()
    private val titleBySession = HashMap<String, String>()

    /** The newest agent preset seen per session, from either the list or the control stream. */
    private val presetBySession = HashMap<String, AgentPresetState>()
    private val workspaceRows = LinkedHashMap<String, WorkspaceRow>()
    private val workspaceOrder = ArrayList<String>()
    private var archived = emptySet<String>()
    private val pendingKinds = HashMap<String, MutableSet<String>>()
    private var modernQuestionKinds: Map<String, Set<String>> = emptyMap()

    // Pending Remote Event waterfalls this store can answer. Keyed by the frame's `eventId`,
    // which is both what an answer names and what a `cancel` frame withdraws — 0.1.2 mints no
    // separate approval id.
    private val approvalRequests = HashMap<String, ApprovalRequest>() // eventId -> request
    private val questionEvents = PendingQuestionRegistry()

    // Open-session fold state.
    private var currentId: String? = null
    private var historyHost: String? = null
    private var historyOpenEpoch = 0L
    private var historyCacheTicket = 0L
    private var historySaveJob: Job? = null
    private val currentEvents = ArrayList<SessionEventEnvelope>()
    private var currentEventsRevision = 0L
    private var currentWindowBytes = 0L
    private var currentOversizedWindow = false
    private var historyAnchorSeq: Long? = null
    private var historyAtTail = true
    private var currentHasNewer = false
    private var currentLegacyWindow = false
    private var historyViewportRequest = 0L
    private var latestObservedHistorySeq = -1L
    private var knownFirstHistorySeq: Long? = null
    private var followStreamEpoch = 0L
    private var remoteHasOlder = false
    private var remoteOldestSeq: Long? = null
    private val _loadingNewer = MutableStateFlow(false)
    val loadingNewer = _loadingNewer.asStateFlow()
    private val _loadNewerFailed = MutableStateFlow(false)
    val loadNewerFailed = _loadNewerFailed.asStateFlow()
    private val baselineReady = MutableStateFlow<String?>(null)
    private var foldedHistoryRevision = -1L
    private var foldedHistory: ConversationSnapshot? = null
    private var currentHasMore = false
    private var currentBlank = true
    private val currentProjections = HashMap<String, ProjectionValue>()
    private var currentQueue = emptyList<QueueItem>()

    /**
     * The open session's follow cursor: the log cut its current stream generation opened at.
     *
     * `session/page` will not answer without it. Paging is pinned to the same cut the live tail
     * started from, which is what lets an older page and the streaming tail be joined without a
     * gap — so a page requested before the snapshot arrives has nothing to send and is skipped.
     */
    private var followCursor: Int? = null

    /**
     * The reply being written in the open session, as the follow stream's assistant frames show
     * it. Harness 0.1.3 logs no deltas, so this is the only source of a streaming preview; it is
     * folded after the durable window and retired by the settlement. Guarded by [lock].
     */
    private val liveAssistant = AssistantLiveState(diskCacheStore)

    /** The open session's live journal. Cancelled and replaced whenever the open session changes. */
    private var followJob: Job? = null
    private var childFollowJob: Job? = null

    /** Host-wide live control (queue, jobs, projections). One per connection generation. */
    private var controlJob: Job? = null

    /** The open session's `job/list` stream (harness 0.1.7). Replaced with the open session. */
    private var jobsJob: Job? = null

    /** Workspace registry stream. One per connection generation. */
    private var workspaceJob: Job? = null

    private data class ApprovalRequest(
        val sessionId: String,
        val eventId: String,
        val toolName: String,
        val reason: String?,
    )
    private data class ProjectionValue(val seq: Int, val value: JsonElement)

    init {
        scope.launch {
            hostsStore.settings.map { it.historyCacheLimitBytes }.distinctUntilChanged().collect {
                runCatching { historyCache.setLimitBytes(it) }
            }
        }
        scope.launch {
            questionSessions.cards.collect { cards ->
                val kinds = cards.pendingQuestionKinds()
                synchronized(lock) {
                    if (modernQuestionKinds != kinds) {
                        modernQuestionKinds = kinds
                        emitSessionsLocked()
                    }
                }
            }
        }
        observeConnection()
        observeEvents()
        observePermissionSettlement()
        observeRebuildTicks()
    }

    /**
     * Clear the optimistic permission value once the harness's own projection agrees with it. The
     * chip shows the target immediately and stops pretending as soon as the truth arrives.
     */
    private fun observePermissionSettlement() {
        scope.launch {
            permissions.collect { select ->
                val pending = _pendingPermission.value ?: return@collect
                if (select?.currentValue == pending) _pendingPermission.value = null
            }
        }
    }

    /**
     * Drives [rebuildCurrentLocked] for the live event stream, at most once per
     * [REBUILD_INTERVAL_MS].
     *
     * A rebuild re-folds the whole transcript, so its cost is proportional to the length of the
     * session. Running one per event made streaming quadratic: a turn arrives as a long run of
     * `assistant/chunk` deltas, and each delta was re-folding every event before it and publishing
     * a fresh snapshot for the transcript to recompose against. On a session of any size that
     * allocated hundreds of megabytes a second and eventually exhausted the heap.
     *
     * The channel is conflated because a rebuild is idempotent and reads whatever state exists when
     * it runs: a burst of deltas collapses into one rebuild, and no delta can be lost by it — the
     * event is already in `currentEvents` before the tick is sent. The interval is a display frame
     * rather than a debounce, so the tail of a stream still lands promptly.
     */
    private fun observeRebuildTicks() {
        scope.launch {
            for (tick in rebuildTicks) {
                try { synchronized(lock) { rebuildCurrentLocked() } }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (failure !is java.io.IOException && failure !is android.database.sqlite.SQLiteException) throw failure
                    setConnectionError(failure.message)
                    connectionManager.reportStorageFailure(failure)
                }
                delay(REBUILD_INTERVAL_MS)
            }
        }
    }

    /** Never let another endpoint inherit the previous host's transcript or actionable session id. */
    private fun clearSelectedSessionLocked(stopHostStreams: Boolean = false) {
        historySaveJob?.cancel()
        followJob?.cancel()
        closeSubagentTranscript()
        _subagentHistoryState.value = ArchivedTranscriptState()
        jobsJob?.cancel()
        if (stopHostStreams) {
            controlJob?.cancel()
            workspaceJob?.cancel()
            approvalRequests.clear()
            questionEvents.clear()
            modernQuestionKinds = emptyMap()
        }
        historyOpenEpoch++
        currentId = null
        latestObservedHistorySeq = -1L
        historyHost = null
        followCursor = null
        currentEvents.clear()
        currentWindowBytes = 0
        currentOversizedWindow = false
        knownFirstHistorySeq = null
        currentEventsRevision++
        historyAnchorSeq = null
        historyAtTail = true
        currentHasNewer = false
        currentLegacyWindow = false
        historyViewportRequest++
        _loadingOlder.value = false
        _loadingNewer.value = false
        _loadNewerFailed.value = false
        remoteHasOlder = false
        remoteOldestSeq = null
        foldedHistory = null
        currentProjections.clear()
        currentQueue = emptyList()
        currentHasMore = false
        currentBlank = true
        liveAssistant.clear()
        baselineReady.value = null
        _currentSessionId.value = null
        _currentConversation.value = null
        _jobs.value = emptyList()
        _skills.value = emptyList()
        _models.value = null
        _subagents.value = emptyList()
        _subagentConversation.value = null
        _subagentMode.value = null
        _commands.value = emptyList()
        _pendingPermission.value = null
        _pendingQuestions.value = null
        _loadOlderFailed.value = false
        _connectionError.value = null
    }

    // ------------------------------------------------------------------ connection lifecycle
    private fun observeConnection() {
        scope.launch {
            var prev = connectionManager.state.value
            connectionManager.state.collect { state ->
                if (prev.host?.id != state.host?.id || prev.host?.baseUrl != state.host?.baseUrl ||
                    prev.clientId != state.clientId ||
                    (prev.phase != state.phase && (prev.phase == ConnectionPhase.CONNECTED || state.phase == ConnectionPhase.CONNECTED))) {
                    featureAvailability.reset()
                }
                if (prev.host?.id != state.host?.id || prev.host?.baseUrl != state.host?.baseUrl) {
                    questionSessions.reset()
                    synchronized(lock) {
                        clearSelectedSessionLocked(stopHostStreams = true)
                        sessionRows.clear()
                        titleBySession.clear()
                        runningBySession.clear()
                        pendingKinds.clear()
                        presetBySession.clear()
                        workspaceRows.clear()
                        workspaceOrder.clear()
                        archived = emptySet()
                        _archivedSessionIds.value = emptySet()
                        _pinnedSessionIds.value = emptyList()
                        emitSessionsLocked()
                        emitWorkspacesLocked()
                    }
                } else if (prev.phase == ConnectionPhase.CONNECTED && state.phase != ConnectionPhase.CONNECTED) questionSessions.disconnect()
                val newGeneration = state.phase == ConnectionPhase.CONNECTED && state.clientId != null &&
                    state.clientId != prev.clientId
                prev = state
                if (state.phase != ConnectionPhase.CONNECTED) {
                    baselineReady.value = null
                    baselineJob?.cancel()
                }
                if (newGeneration) {
                    probeFeatures()
                    triggerBaseline()
                    activeHostKey?.let { pluginOperations.resume(it) }
                }
            }
        }
    }

    private fun observeEvents() {
        scope.launch {
            connectionManager.collectHostFrames { scoped ->
                if (activeHostKey == "${scoped.hostBaseUrl}|${scoped.hostId}") handleEventFrame(scoped.frame)
            }
        }
    }

    /**
     * Open the two host-wide streams for this connection generation.
     *
     * Both replace things that used to arrive unbidden on the all-session mux, and both open with
     * a complete baseline — which is the point: a reconnect replaces the mirror wholesale rather
     * than leaving whatever the old generation last said. They are cancelled and reopened with
     * the generation, because a stream's items are only meaningful within the socket that carries
     * them.
     */
    private fun startHostStreams() {
        val mux = connectionManager.generation?.mux ?: return
        controlJob?.cancel()
        // Preset watermarks are log positions as this host reported them. A harness restored from
        // an older snapshot can hand back the same session ids at lower positions, so the new
        // stream's baseline and the list read that follows start them over.
        synchronized(lock) { presetBySession.clear() }
        controlJob = scope.launch {
            runCatching {
                mux.openStream("session/control").collect { item ->
                    decodeOrNull(SessionControlFrameSerializer, item)?.let { handleControlFrame(it) }
                }
            }.onFailure { log("session/control ended", it) }
        }
        workspaceJob?.cancel()
        workspaceJob = scope.launch {
            runCatching {
                mux.openStream("workspace/follow").collect { item ->
                    decodeOrNull(WorkspaceFollowFrameSerializer, item)?.let { handleWorkspaceFrame(it) }
                }
            }.onFailure { log("workspace/follow ended", it) }
        }
    }

    /** Decode one stream item, or null when it does not match the expected frame union. */
    private fun <T> decodeOrNull(serializer: kotlinx.serialization.KSerializer<T>, item: JsonElement): T? =
        runCatching { decodeFromJsonElement(serializer, item) }.getOrNull()

    private fun triggerBaseline() {
        baselineJob?.cancel()
        baselineReady.value = null
        val clientId = connectionManager.state.value.clientId ?: return
        val host = activeHostKey
        baselineJob = scope.launch {
            try {
                val succeeded = baseline(clientId, host)
                if (succeeded && connectionManager.state.value.clientId == clientId && activeHostKey == host) {
                    baselineReady.value = clientId
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log("baseline failed", e)
            }
        }
    }

    private suspend fun baseline(clientId: String, host: String?): Boolean {
        var succeeded = false
        // Whether content search works is a fact about the harness we just reached, so a fresh
        // connection re-earns the answer rather than inheriting the previous host's.
        _contentSearchAvailable.value = true
        _jobControls.value = false
        // Before the list read: the workspace and control streams each open with their own
        // complete baseline, and the list is what their increments are applied on top of.
        startHostStreams()
        _hostInfo.value = connectionManager.generation?.description
        coroutineScope {
            // Host-scoped and needed before anything is tapped, but needed by nothing on the way to
            // the transcript: the chat bar names the session's preset as soon as it renders, and
            // the permission chip wants its catalog, and both can land while the session opens.
            // Awaiting them here put two full phone→relay→host round trips in front of the first
            // thing the reader actually looks at. Neither throws — each reports its own failure —
            // so nothing downstream has to know whether they have landed yet.
            launch { refreshAgentPresets() }
            launch { refreshPermissionCatalog() }
            // The list is the one read the landing session is chosen from, so it alone is awaited.
            if (!refreshSessions() || activeHostKey != host || connectionManager.state.value.clientId != clientId) {
                return@coroutineScope
            }
            // On a reconnect `currentSessionId` is already set, so the resolver only ever runs on
            // the first connect of a process — no double-open, and reconnect keeps reopening what
            // was open.
            val sid = synchronized(lock) {
                currentId?.takeIf { historyHost == host && sessionRows.containsKey(it) }
            } ?: resolveInitialSession()
            if (sid != null) openSession(sid, expectedClientId = clientId)
            else synchronized(lock) {
                if (activeHostKey == host && connectionManager.state.value.clientId == clientId) clearSelectedSessionLocked()
            }
            succeeded = activeHostKey == host && connectionManager.state.value.clientId == clientId
        }
        return succeeded
    }

    /**
     * Which session to land on when the app has just connected and nothing is open.
     *
     * Mirrors the harness's own startup policy: the session you were last in, else the most
     * recently active workspace's newest session, else simply the newest session. Ranking is by
     * session `updatedAt` — `workspace.updatedAt` stamps the registration record (a rename, a
     * session being added), and `workspace.list` order is the manual display order, so neither
     * tracks conversation activity.
     *
     * Returns null when there is nothing worth opening, which leaves the empty hero on screen.
     */
    private suspend fun resolveInitialSession(): String? {
        val remembered = connectionManager.state.value.host?.let { hostsStore.lastSessionId(it) }
        val (rows, workspaces, archivedNow) = synchronized(lock) {
            Triple(
                sessionRows.values.toList(),
                workspaceOrder.mapNotNull { workspaceRows[it] },
                archived,
            )
        }
        return pickInitialSession(rows, workspaces, archivedNow, remembered)
    }

    /** Full endpoint identity: sessions behind different proxy roots belong to different hosts. */
    private fun hostKey(): String? =
        connectionManager.state.value.host?.baseUrl

    // ------------------------------------------------------------------ host event frames
    /**
     * One frame of the host's `$events` stream.
     *
     * This is the whole of what arrives unbidden in 0.1.2. Session events are not here — they
     * belong to a per-session `session/follow` stream — and neither is queue, job or projection
     * state, which belongs to `session/control`. What is left is notifications and the two
     * agent-scoped waterfalls.
     */
    private fun handleEventFrame(frame: RemoteEventFrame) {
        when (frame) {
            is RemoteEventFrame.Emit -> handleNotification(frame.event, frame.args)
            is RemoteEventFrame.Waterfall -> handleWaterfall(frame)
            is RemoteEventFrame.Cancel -> handleWaterfallCancelled(frame.eventId)
            // Consumed by the connection loop's handshake; it never forwards one.
            is RemoteEventFrame.Ready -> Unit
            is RemoteEventFrame.Unknown -> log("unknown host event frame ${frame.type}")
        }
    }

    /**
     * One ordinary host notification.
     *
     * Arguments are positional — the host forwards the Cordis listener's own argument list — so
     * these read by index rather than by key. None of them is replayed after a reconnect, which
     * is why every one of them is either repairable from the session list baseline or purely
     * advisory.
     */
    private fun handleNotification(event: String, args: List<JsonElement>) {
        _hostEvents.tryEmit(event to args)
        pluginOperations.event(activeHostKey, event, args)
        fun str(i: Int) = args.getOrNull(i)?.jsonPrimitive?.contentOrNull
        when (event) {
            "api-session/added" -> args.firstOrNull()?.let { onSessionAdded(it) }
            "api-session/removed" -> str(0)?.let { onSessionRemoved(it) }
            "api-session/status" -> {
                val sid = str(0) ?: return
                val running = (args.getOrNull(1) as? JsonPrimitive)?.booleanOrNull ?: return
                setRunning(sid, running)
            }
            "permission-presets/catalog-changed" -> scope.launch { refreshPermissionCatalog() }
            "api-session/activity" -> {
                // Only reorders the list; the durable value is the session's own projection, so a
                // missed one is corrected by the next list read rather than lost.
                val sid = str(0) ?: return
                val updatedAt = args.getOrNull(1)?.jsonPrimitive?.longOrNull ?: return
                setUpdatedAt(sid, updatedAt)
            }
            "api-session/error" -> setConnectionError(str(1))
            "commands/change" -> scope.launch { refreshCommands() }
            "agent-preset/selected" -> scope.launch {
                refreshAgentPresets()
                refreshCommands()
            }
            // The rest of the host's allowlist is either developer tooling (`cordis/*`), or a
            // change this client already learns from a projection, or a surface it does not
            // present — settings and credentials among them, which is why nothing here goes stale
            // when they change. Naming the unhandled one in the log is what makes the next gap
            // findable; dropping it silently is how `goal/activation-changed` went unnoticed.
            else -> log("unhandled emit $event")
        }
    }

    /** One pending agent-scoped request awaiting this client's answer. */
    private fun handleWaterfall(frame: RemoteEventFrame.Waterfall) {
        when (frame.event) {
            APPROVAL_REQUEST_EVENT -> {
                val request = runCatching {
                    decodeFromJsonElement(ApprovalRequestEvent.serializer(), frame.request)
                }.getOrNull() ?: return
                handleApprovalRequested(frame.eventId, frame.agentId, request)
            }
            USER_QUESTIONS_REQUEST_EVENT -> {
                val request = runCatching {
                    decodeFromJsonElement(AskUserQuestionRequestEvent.serializer(), frame.request)
                }.getOrNull() ?: return
                if (request.wait != null && timedQuestionsAvailable.value != false) questionSessions.requested(frame.agentId, frame.eventId, request)
                else handleQuestionRequested(frame.eventId, frame.agentId, request.questions)
            }
            else -> log("unhandled waterfall ${frame.event}")
        }
    }

    /**
     * A pending request was withdrawn: another client answered it, or the host's caller cancelled.
     *
     * Replaces the `approval/resolved` and `question/resolved` frames, and covers both — an
     * `eventId` identifies the request without saying which kind it was, so both registries are
     * checked.
     *
     * It is *not* the only way a request leaves: the host drops the answering client's delivery
     * before it cancels the rest, so this frame reaches every client except the one that acted.
     * That client settles its own card in [answerOutcome].
     */
    private fun handleWaterfallCancelled(eventId: String) {
        questionSessions.cancelled(eventId)
        forgetRequest(eventId)
    }

    /**
     * Drop one request this client is holding, whoever settled it.
     *
     * Idempotent by construction — every step is a remove or a null-if-matching — because the two
     * callers can both fire for one request: this client answers, forgets it here, and a `cancel`
     * for the same `eventId` may still arrive if the host had a second delivery open.
     */
    private fun forgetRequest(eventId: String) {
        val approval = synchronized(lock) { approvalRequests.remove(eventId) }
        if (approval != null) {
            synchronized(lock) {
                removePendingLocked(approval.sessionId, "approval")
                emitSessionsLocked()
            }
            if (_pendingApproval.value?.approvalId == eventId) _pendingApproval.value = null
            return
        }
        val sessionId = synchronized(lock) {
            questionEvents.sessionFor(eventId)
        } ?: return
        forgetQuestions(sessionId, eventId)
    }

    /**
     * Drop the question batch [sessionId] is holding — but only while it is still [eventId]'s, and
     * only the card drawn from that same request. [PendingQuestionRegistry.forget] holds the rule
     * and the reason for it; a null [eventId] is the corpse case it describes.
     */
    private fun forgetQuestions(sessionId: String, eventId: String?) {
        // Registry and card move together under the lock, so a replacement cannot land between
        // them and lose its card to this call.
        synchronized(lock) {
            if (!questionEvents.forget(sessionId, eventId)) return
            removePendingLocked(sessionId, "question")
            removePendingLocked(sessionId, "plan-review")
            emitSessionsLocked()
            val shown = _pendingQuestions.value
            if (shown?.sessionId == sessionId && (eventId == null || shown.rpcId == eventId)) {
                _pendingQuestions.value = null
            }
        }
    }

    // ------------------------------------------------------------------ control stream
    /**
     * One frame of the host-wide live-control stream.
     *
     * Queue and job values are complete replacements applied last-wins, never deltas, so an
     * empty value is a real "nothing pending" rather than an absent update.
     */
    private fun handleControlFrame(frame: SessionControlFrame) {
        when (frame) {
            is SessionControlFrame.Baseline -> {
                // A host that still computes queues wins for the sessions it names; every other
                // session's pending work is rebuilt from its own `inbox` projection.
                val legacy = frame.value.queues.mapValues { (_, items) -> items.map(::queuedInboxItemToQueueItem) }
                queuesBySession.value = inboxQueuesFrom(frame.value.projections) + legacy
                synchronized(lock) {
                    var changed = false
                    frame.value.projections.forEach { (sessionId, block) ->
                        val asOf = block["asOfSeq"]?.jsonPrimitive?.intOrNull ?: 0
                        (block["values"] as? JsonObject)?.get("userQuestions")?.let { questionSessions.projection(sessionId, asOf, it) }
                        (block["values"] as? JsonObject)?.get("inbox")?.let { questionSessions.inbox(sessionId, it) }
                        val value = (block["values"] as? JsonObject)?.get(AGENT_PRESET_PROJECTION)
                        if (foldAgentPresetLocked(sessionId, asOf, value)) changed = true
                    }
                    if (changed) emitSessionsLocked()
                }
                val sid = synchronized(lock) { currentId } ?: return
                frame.value.queues[sid]?.let { items -> applyQueue(sid, items) }
                frame.value.jobs[sid]?.let { jobs -> applyJobs(sid, jobs) }
                frame.value.projections[sid]?.let { block -> applyProjectionBaseline(sid, block) }
            }
            is SessionControlFrame.Queue -> applyQueue(frame.sessionId, frame.items)
            is SessionControlFrame.Jobs -> applyJobs(frame.sessionId, frame.jobs)
            is SessionControlFrame.Projection -> {
                if (frame.key == "userQuestions") questionSessions.projection(frame.sessionId, frame.seq, frame.value)
                if (frame.key == "inbox") questionSessions.inbox(frame.sessionId, frame.value)
                // The inbox projection is the queue now, and it arrives for every live session —
                // not just the open one — so the chat list's per-session dock reads it here.
                if (frame.key == INBOX_PROJECTION) {
                    val items = InboxProjection.itemsFrom(frame.value).map(::queuedInboxItemToQueueItem)
                    queuesBySession.value = queuesBySession.value + (frame.sessionId to items)
                }
                synchronized(lock) {
                    // Another client (the web GUI) can pick a preset after our list read, and the
                    // list row is what the top-bar chip and the details pill draw from.
                    if (frame.key == AGENT_PRESET_PROJECTION &&
                        foldAgentPresetLocked(frame.sessionId, frame.seq, frame.value)
                    ) {
                        emitSessionsLocked()
                    }
                    if (frame.sessionId == currentId) {
                        mergeProjectionLocked(frame.key, frame.seq, frame.value)
                        rebuildCurrentLocked()
                    }
                }
            }
            is SessionControlFrame.Unknown -> log("unknown control frame ${frame.type}")
        }
    }

    /**
     * Fold one `agentPreset` projection value seen at [seq] into the session's row. Returns whether
     * the row changed; the caller publishes. A session with no row yet keeps the value for the
     * list read that brings its row, rather than having a row invented here.
     */
    private fun foldAgentPresetLocked(sessionId: String, seq: Int, value: JsonElement?): Boolean {
        val incoming = agentPresetUpdate(seq, value) ?: return false
        val kept = newerAgentPreset(presetBySession[sessionId], incoming)
        presetBySession[sessionId] = kept
        val row = sessionRows[sessionId] ?: return false
        if (row.agentPreset == kept.preset) return false
        sessionRows[sessionId] = row.copy(agentPreset = kept.preset)
        return true
    }

    /**
     * The preset a list row should show: its own, unless the control stream already delivered one
     * from a later position in the session's log. A row without a projection block has no position
     * and yields to anything already seen.
     */
    private fun listedAgentPresetLocked(item: SessionSummary): String? {
        val kept = listedAgentPreset(
            held = presetBySession[item.sessionId],
            asOfSeq = item.projections?.asOfSeq ?: -1,
            topLevel = item.agentPreset,
            projected = item.projections?.values?.get(AGENT_PRESET_PROJECTION),
            cached = item.projections?.kind == "cached",
        ) ?: return null
        presetBySession[item.sessionId] = kept
        return kept.preset
    }

    /**
     * Rebuild every named session's pending queue from its projection baseline.
     *
     * The baseline's per-session block is `{asOfSeq, values}`; a session whose composition
     * publishes no inbox is simply absent, which reads as "nothing pending" rather than as an
     * error, exactly as an absent projection key does everywhere else.
     */
    private fun inboxQueuesFrom(projections: Map<String, JsonObject>): Map<String, List<QueueItem>> =
        projections.mapNotNull { (sessionId, block) ->
            val inbox = (block["values"] as? JsonObject)?.get(INBOX_PROJECTION) ?: return@mapNotNull null
            sessionId to InboxProjection.itemsFrom(inbox).map(::queuedInboxItemToQueueItem)
        }.toMap()

    private fun applyQueue(sessionId: String, items: List<QueuedInboxItem>) {
        queuesBySession.value = queuesBySession.value + (sessionId to items.map(::queuedInboxItemToQueueItem))
        synchronized(lock) {
            if (sessionId == currentId) {
                currentQueue = items.map { queuedInboxItemToQueueItem(it) }
                rebuildCurrentLocked()
            }
        }
    }

    private fun applyJobs(sessionId: String, jobs: List<JobView>) {
        synchronized(lock) {
            if (sessionId == currentId) _jobs.value = jobs
        }
    }

    /**
     * Merge a projection baseline for one session.
     *
     * The tail page's baseline and the control stream's are produced independently, so neither is
     * authoritative on its own; [mergeProjectionLocked] keeps whichever carries the higher
     * watermark.
     */
    private fun applyProjectionBaseline(sessionId: String, block: JsonObject) {
        if (block["kind"]?.jsonPrimitive?.contentOrNull in setOf("cached", "migration-required")) return
        synchronized(lock) {
            if (sessionId != currentId) return@synchronized
            val asOf = block["asOfSeq"]?.jsonPrimitive?.intOrNull ?: 0
            (block["values"] as? JsonObject)?.forEach { (key, value) ->
                mergeProjectionLocked(key, asOf, value)
            }
            rebuildCurrentLocked()
        }
    }

    // ------------------------------------------------------------------ workspace stream
    /**
     * One frame of the workspace registry stream.
     *
     * The `order` frame is complete and authoritative; display order is never inferred from the
     * arrival order of upserts, which is what makes the list converge after a reconnect baseline.
     */
    private fun handleWorkspaceFrame(frame: WorkspaceFollowFrame) {
        when (frame) {
            is WorkspaceFollowFrame.Baseline -> synchronized(lock) {
                workspaceRows.clear()
                workspaceOrder.clear()
                for (w in frame.workspaces) workspaceRows[w.workspaceId] = w.toRow()
                workspaceOrder.addAll(frame.workspaceIds.ifEmpty { frame.workspaces.map { it.workspaceId } })
                archived = frame.archivedSessionIds.toSet()
                _archivedSessionIds.value = archived
                _pinnedSessionIds.value = frame.pinnedSessionIds
                emitWorkspacesLocked()
            }
            is WorkspaceFollowFrame.Pinned -> _pinnedSessionIds.value = frame.pinnedSessionIds
            is WorkspaceFollowFrame.Upsert -> upsertWorkspace(frame.workspace)
            is WorkspaceFollowFrame.Remove -> removeWorkspace(frame.workspaceId)
            is WorkspaceFollowFrame.Order -> setWorkspaceOrder(frame.workspaceIds)
            is WorkspaceFollowFrame.Archived -> setArchived(frame.archivedSessionIds)
            is WorkspaceFollowFrame.Unknown -> log("unknown workspace frame ${frame.type}")
        }
    }

    /**
     * One event from the open session's follow stream.
     *
     * Through 0.1.1 this arrived for every session at once on the mux, which is how the store
     * kept list state for sessions nobody had opened. 0.1.2 has no such stream: an event is only
     * seen for the session actually being followed, and everything else about the list comes from
     * a notification or a list read.
     */
    private fun handleSessionEvent(sessionId: String, envelope: SessionEventEnvelope) {
        when (envelope.type) {
            "turn/start" -> {
                setRunning(sessionId, true, authoritative = false)
                setBlank(sessionId, false)
            }
            "turn/end" -> setRunning(sessionId, false, authoritative = false)
            "user/message" -> setBlank(sessionId, false)
            "session/title" -> {
                val title = envelope.data.jsonObject["title"]?.jsonPrimitive?.contentOrNull
                if (title != null) setTitle(sessionId, title)
            }
        }
        // Completion notifications used to be classified from the all-session mux. That stream is
        // gone, so the session that owns the event forwards it to whoever is watching for one.
        (historyHost ?: activeHostKey)?.let { notificationSink?.invoke(it, sessionId, envelope) }
        synchronized(lock) {
            if (sessionId == currentId) {
                // The durable settlement and the transient rows say the same thing; the moment
                // the settlement lands the preview is redundant, and a fold that saw both would
                // show the reply twice.
                val data = envelope.data as? JsonObject
                liveAssistant.acceptDurable(
                    type = envelope.type,
                    turn = data?.get("turn")?.jsonPrimitive?.intOrNull,
                    step = data?.get("step")?.jsonPrimitive?.intOrNull,
                    seq = envelope.seq,
                    surfaceOp = envelope.surfaceOp,
                )
                appendCurrentEventLocked(envelope)
            }
        }
    }

    /**
     * Where session events go for completion notifications.
     *
     * A hook rather than a direct dependency: the notification observer already depends on this
     * store, and 0.1.2 leaves no all-session stream for it to read instead.
     */
    @Volatile
    var notificationSink: ((String, String, SessionEventEnvelope) -> Unit)? = null

    private fun handleApprovalRequested(eventId: String, sessionId: String, request: ApprovalRequestEvent) {
        // Harness 0.1.7 sends a localized `displayReason` beside the raw one (an Auto review
        // denial, a sandbox escalation); the reader's own language wins, then English, then raw.
        val reason = request.reasonFor(java.util.Locale.getDefault().language)
        synchronized(lock) {
            approvalRequests[eventId] =
                ApprovalRequest(sessionId, eventId, request.toolName, reason)
            addPendingLocked(sessionId, "approval")
            emitSessionsLocked()
        }
        _pendingApproval.value = PendingApproval(
            sessionId = sessionId,
            // The event id is the approval id now: 0.1.2 correlates a pending request by the
            // frame's own `eventId` and mints nothing separate.
            approvalId = eventId,
            rpcId = eventId,
            toolName = request.toolName,
            reason = reason,
        )
    }

    private fun handleQuestionRequested(
        eventId: String,
        sessionId: String,
        questions: List<AskUserQuestionItem>,
    ) {
        synchronized(lock) {
            questionEvents.install(sessionId, eventId)
            val kind = if (questions.any { it.intent is AskUserQuestionIntent.PlanReview }) {
                "plan-review"
            } else {
                "question"
            }
            addPendingLocked(sessionId, kind)
            emitSessionsLocked()
            // Inside the lock, with the registration it belongs to: [forgetQuestions] decides
            // whether to clear the card by reading that registration, so a request that installed
            // one but not yet the other could have its card taken by an answer to the request it
            // just replaced.
            _pendingQuestions.value = PendingQuestions(sessionId, eventId, questions)
        }
    }

    // ------------------------------------------------------------------ session list state updates
    /**
     * One session became visible to list consumers.
     *
     * The notification carries the whole list row rather than the loose fields the old
     * `host/session-added` frame did, so this decodes a summary and folds it in.
     */
    private fun onSessionAdded(summary: JsonElement) {
        val item = runCatching {
            decodeFromJsonElement(SessionSummary.serializer(), summary)
        }.getOrNull() ?: return
        onSessionAdded(item)
    }

    private fun onSessionAdded(item: SessionSummary) {
        synchronized(lock) {
            runningBySession.added(item.sessionId, item.running)
            val existing = sessionRows[item.sessionId]
            val title = titleBySession[item.sessionId]
            val preset = listedAgentPresetLocked(item)
            val row = existing?.copy(
                title = title ?: existing.title,
                blank = item.blank,
                parentSessionId = item.parentSessionId,
                origin = item.origin,
                cwd = item.cwd,
                agentPreset = preset,
                formatStatus = item.formatStatus,
            ) ?: SessionRow(
                sessionId = item.sessionId,
                title = title,
                running = runningBySession[item.sessionId] ?: item.running,
                blank = item.blank,
                parentSessionId = item.parentSessionId,
                origin = item.origin,
                cwd = item.cwd,
                agentPreset = preset,
                updatedAt = item.updatedAt,
                pendingInteraction = null,
                formatStatus = item.formatStatus,
            )
            if (existing == null) {
                // New sessions appear at the front (most recent first).
                val copy = LinkedHashMap<String, SessionRow>(sessionRows.size + 1)
                copy[item.sessionId] = row
                copy.putAll(sessionRows)
                sessionRows.clear()
                sessionRows.putAll(copy)
            } else {
                sessionRows[item.sessionId] = row
            }
            emitSessionsLocked()
        }
    }

    private fun onSessionRemoved(sessionId: String) {
        questionSessions.removeSession(sessionId)
        synchronized(lock) {
            sessionRows.remove(sessionId)
            pendingKinds.remove(sessionId)
            runningBySession.remove(sessionId)
            presetBySession.remove(sessionId)
            questionEvents.discard(sessionId)
            emitSessionsLocked()
        }
    }

    private fun setRunning(sessionId: String, running: Boolean, authoritative: Boolean = true) {
        synchronized(lock) {
            if (authoritative) runningBySession.status(sessionId, running)
            else runningBySession.turn(sessionId, running)
            val resolved = runningBySession[sessionId] ?: false
            sessionRows[sessionId]?.let { if (it.running != resolved) sessionRows[sessionId] = it.copy(running = resolved) }
            if (sessionId == currentId) rebuildCurrentLocked()
            // A child's own status is what says whether it is running; the catalog only lists it.
            else publishCatalogSubagentsLocked()
            emitSessionsLocked()
        }
    }

    /** Reorder one session on a durable user message, without touching anything else about it. */
    private fun setUpdatedAt(sessionId: String, updatedAt: Long) {
        synchronized(lock) {
            val row = sessionRows[sessionId] ?: return@synchronized
            sessionRows[sessionId] = row.copy(updatedAt = updatedAt)
            emitSessionsLocked()
        }
    }

    private fun setBlank(sessionId: String, blank: Boolean) {
        synchronized(lock) {
            sessionRows[sessionId]?.let { if (it.blank != blank) sessionRows[sessionId] = it.copy(blank = blank) }
            if (sessionId == currentId) currentBlank = blank
            emitSessionsLocked()
        }
    }

    private fun setTitle(sessionId: String, title: String) {
        synchronized(lock) {
            titleBySession[sessionId] = title
            sessionRows[sessionId]?.let { if (it.title != title) sessionRows[sessionId] = it.copy(title = title) }
            emitSessionsLocked()
        }
    }

    private fun upsertWorkspace(workspace: WorkspaceView) {
        synchronized(lock) {
            val row = workspace.toRow()
            if (!workspaceRows.containsKey(workspace.workspaceId)) workspaceOrder.add(workspace.workspaceId)
            workspaceRows[workspace.workspaceId] = row
            emitWorkspacesLocked()
        }
    }

    private fun removeWorkspace(workspaceId: String) {
        synchronized(lock) {
            workspaceRows.remove(workspaceId)
            workspaceOrder.remove(workspaceId)
            emitWorkspacesLocked()
        }
    }

    private fun setWorkspaceOrder(ids: List<String>) {
        synchronized(lock) {
            workspaceOrder.clear()
            workspaceOrder.addAll(ids)
            emitWorkspacesLocked()
        }
    }

    private fun setArchived(ids: List<String>) {
        synchronized(lock) {
            archived = ids.toSet()
            _archivedSessionIds.value = archived
        }
    }

    private fun setConnectionError(message: String?) {
        _connectionError.value = message
    }

    /**
     * Drop a stale failure banner once something works again.
     *
     * Errors used to be set and never cleared, so one transient failure — a session that was still
     * cold when the app opened it, say — left a red banner across the whole session for the rest of
     * the run, long after the thing it described had resolved.
     */
    private fun clearConnectionError() {
        if (_connectionError.value != null) _connectionError.value = null
    }

    // ------------------------------------------------------------------ open-session fold
    /**
     * Fold one freshly-streamed event into the open session.
     *
     * The common case by far is a strictly-increasing append, which is why it is checked first:
     * the scan-and-sort below is O(n log n) and used to run for every delta of every turn. Out of
     * order or repeated sequence numbers still take the slow path, which is what makes a
     * re-delivery after a reconnect land in the right place.
     *
     * The rebuild is requested rather than performed — see [observeRebuildTicks].
     */
    // Namespace the archive key, NEVER the connection/bookmark host identity.
    private var readerMode = false
    private var readerNextBeforeSeq: Int? = null
    private fun historyArchiveKey(sid: String): String = if (readerMode) "reader-v1\u0000$sid" else sid
    private fun readerRequest(request: SessionPageRequest): SessionPageRequest = if (!readerMode) request else
        request.copy(view = READER_VIEW, maxRows = READER_MAX_ROWS, maxBytes = READER_MAX_BYTES, maxMessages = null)

    private fun observeHistoryBoundsLocked(first: Long?, last: Long?) {
        if (first != null) knownFirstHistorySeq = knownFirstHistorySeq?.let { minOf(it, first) } ?: first
        if (last != null) latestObservedHistorySeq = maxOf(latestObservedHistorySeq, last)
    }

    private fun commitHistoryPageLocked(summary: HistoryPageFetchSummary, commit: () -> Boolean): Boolean =
        commit().also { if (it) observeHistoryBoundsLocked(summary.firstSeq, summary.lastSeq) }

    private fun appendCurrentEventLocked(envelope: SessionEventEnvelope) {
        val host = historyHost
        val sid = currentId
        // One encoding is shared by durable insertion and RAM accounting. Choosing the fresh epoch
        // happens inside the cache lock, so a concurrent clear cannot reject an accepted live event.
        val bytes = if (host != null && sid != null) historyCache.storeLiveEvent(host, historyArchiveKey(sid), envelope)
            else encodeHistoryEvent(envelope).payload.size.toLong()
        if (!currentHasNewer || envelope.seq <= (currentEvents.lastOrNull()?.seq ?: -1L)) {
            val lastSeq = currentEvents.lastOrNull()?.seq
            if (lastSeq == null || envelope.seq > lastSeq) {
                currentEvents.add(envelope)
                currentWindowBytes += bytes
            } else {
                val index = currentEvents.indexOfFirst { it.seq == envelope.seq }
                if (index >= 0) {
                    currentWindowBytes += bytes - encodeHistoryEvent(currentEvents[index]).payload.size
                    currentEvents[index] = envelope
                } else {
                    currentEvents.add(envelope)
                    currentWindowBytes += bytes
                    currentEvents.sortBy { it.seq }
                }
            }
            currentEventsRevision++
        }
        observeHistoryBoundsLocked(envelope.seq, envelope.seq)
        val messageBoundary = HistoryWindowBudget.isMessageBoundary(envelope.type)
        val highMessages = HistoryWindowBudget.TAIL_HIGH_MESSAGES
        val rollover = if (readerMode) currentEvents.size > READER_MAX_ROWS ||
            (currentEvents.size > 1 && currentWindowBytes > READER_MAX_BYTES)
            else if (currentLegacyWindow) envelope.type == "user/message"
            else currentEvents.size.toLong() > HistoryWindowBudget.rawEvents(highMessages) ||
                (currentWindowBytes > HistoryWindowBudget.payloadBytes(highMessages) &&
                    (!currentOversizedWindow || envelope.type !in com.labteto.dshmobile.core.session.STRUCTURAL_EVENT_TYPES)) || (messageBoundary &&
                currentEvents.count { HistoryWindowBudget.isMessageBoundary(it.type) } > highMessages)
        if (host != null && sid != null && rollover) {
            val liveTicket = historyCache.ticket()
            historyCache.atTicket(liveTicket) {
                val anchor = historyAnchorSeq ?: currentEvents.first().seq
                // Reload below the high watermark once, rather than decoding the same 8000-event
                // viewport for every following tool frame. No archived content is evicted.
                val page = if (historyAtTail) historyCache.latestHistory(host, historyArchiveKey(sid),
                    HistoryWindowBudget.TAIL_LOW_MESSAGES, liveTicket)
                else historyCache.historyAround(host, historyArchiveKey(sid), anchor, highMessages, liveTicket)
                if (page.events.isNotEmpty() && (historyAtTail || anchor in page.firstSeq!!..page.lastSeq!!))
                    installHistoryWindowLocked(page)
            }
        }
        scheduleHistorySaveLocked()
        rebuildTicks.trySend(Unit)
    }

    /** Record intent without moving the reader; only complete off-screen pages may leave RAM. */
    fun updateHistoryViewport(anchorSeq: Long?, atTail: Boolean) = synchronized(lock) {
        // Layout/structural transitions can briefly expose no semantic row. They must not
        // erase the last reading anchor and make reconnect select the latest window.
        if (anchorSeq != null) historyAnchorSeq = anchorSeq
        historyAtTail = atTail && !currentHasNewer
    }

    internal fun captureHistoryViewport(): HistoryViewportBookmark? = synchronized(lock) {
        val host = historyHost ?: return@synchronized null
        val sid = currentId ?: return@synchronized null
        HistoryViewportBookmark(historyAtTail, historyAnchorSeq, host, sid, historyOpenEpoch,
            currentEvents.toList(), currentHasMore, currentHasNewer, currentLegacyWindow, historyCacheTicket, currentWindowBytes)
    }

    /** Restore presentation only. A saved window never restores projections, queues or approvals. */
    internal suspend fun restoreHistoryViewport(bookmark: HistoryViewportBookmark): Boolean = withContext(Dispatchers.Default) {
        var storageFailure: Exception? = null
        val restored = synchronized(lock) {
            if (historyHost != bookmark.host || activeHostKey != bookmark.host || currentId != bookmark.sessionId ||
                historyOpenEpoch != bookmark.openEpoch) return@synchronized false
            historyViewportRequest++
            _loadingOlder.value = false
            _loadingNewer.value = false
            _loadOlderFailed.value = false
            _loadNewerFailed.value = false
            historyAtTail = bookmark.followingTail
            historyAnchorSeq = bookmark.anchorSeq
            val fresh = try {
                val ticket = historyCache.ticket()
                historyCache.atTicket(ticket) {
                    val page = if (bookmark.followingTail || bookmark.anchorSeq == null)
                        historyCache.latestHistory(bookmark.host, historyArchiveKey(bookmark.sessionId), HISTORY_PAGE_SIZE * 2, ticket)
                    else historyCache.historyAround(bookmark.host, historyArchiveKey(bookmark.sessionId), bookmark.anchorSeq, HISTORY_PAGE_SIZE * 2, ticket)
                    val containsAnchor = bookmark.anchorSeq == null || page.events.any { it.seq == bookmark.anchorSeq }
                    if (page.events.isEmpty() || (!bookmark.followingTail && !containsAnchor)) false
                    else {
                        installHistoryWindowLocked(page)
                        historyCacheTicket = ticket
                        rebuildCurrentLocked()
                        true
                    }
                } ?: false
            } catch (failure: Exception) {
                if (failure !is java.io.IOException && failure !is android.database.sqlite.SQLiteException) throw failure
                storageFailure = failure
                false
            }
            if (!fresh) {
                val retained = if (followCursor != null) bookmark.events.filter { it.seq <= latestObservedHistorySeq }
                    else bookmark.events
                val fallback = retained.ifEmpty { currentEvents.filter { it.seq <= latestObservedHistorySeq } }
                currentEvents.clear()
                currentEvents.addAll(fallback)
                currentWindowBytes = if (retained.isNotEmpty() && retained.size == bookmark.events.size)
                    bookmark.payloadBytes else fallback.sumOf { encodeHistoryEvent(it).payload.size.toLong() }
                currentOversizedWindow = currentWindowBytes > HistoryWindowBudget.payloadBytes(HistoryWindowBudget.TAIL_HIGH_MESSAGES)
                observeHistoryBoundsLocked(fallback.firstOrNull()?.seq, fallback.lastOrNull()?.seq)
                currentEventsRevision++
                currentLegacyWindow = bookmark.legacy
                currentHasMore = bookmark.hasOlder || knownFirstHistorySeq?.let { first ->
                    (fallback.firstOrNull()?.seq ?: first) > first } == true
                currentHasNewer = bookmark.hasNewer || latestObservedHistorySeq > (fallback.lastOrNull()?.seq ?: -1L)
                // A clear invalidated this ticket. Never re-cache the saved RAM fallback under a
                // newer sheet read's ticket when saveHistoryLocked runs on the next live event.
                historyCacheTicket = bookmark.cacheTicket
                rebuildCurrentLocked()
            }
            true
        }
        storageFailure?.let { failure ->
            log("could not restore cached viewport; retained RAM bookmark", failure)
            setConnectionError(failure.message)
            connectionManager.reportStorageFailure(failure)
        }
        restored
    }

    private fun installHistoryWindowLocked(page: HistoryDiskPage) {
        currentEvents.clear()
        currentEvents.addAll(page.events)
        currentWindowBytes = page.payloadBytes
        currentOversizedWindow = currentWindowBytes > HistoryWindowBudget.payloadBytes(HistoryWindowBudget.TAIL_HIGH_MESSAGES)
        observeHistoryBoundsLocked(page.firstSeq, page.lastSeq)
        currentLegacyWindow = page.legacyUnbounded
        currentEventsRevision++
        currentHasNewer = page.hasNewer || latestObservedHistorySeq > (page.lastSeq ?: -1L)
        currentHasMore = page.hasOlder || knownFirstHistorySeq?.let { first ->
            (page.firstSeq ?: first) > first } == true || (remoteHasOlder &&
            (remoteOldestSeq == null || (page.firstSeq ?: Long.MAX_VALUE) >= remoteOldestSeq!!))
    }

    private fun markCurrentHistoryAtTailLocked() {
        historyAtTail = true
        historyAnchorSeq = currentEvents.lastOrNull()?.seq
        currentHasNewer = false
        currentHasMore = currentHasMore || knownFirstHistorySeq?.let { first ->
            (currentEvents.firstOrNull()?.seq ?: first) > first } == true
        if (followCursor == null) _currentConversation.value = _currentConversation.value?.copy(
            hasMore = currentHasMore, hasNewer = false)
        else rebuildCurrentLocked()
        clearConnectionError()
        historyViewportRequest++
        _loadingOlder.value = false
        _loadingNewer.value = false
        _loadNewerFailed.value = false
    }

    /** A partial post-clear cache is not the live tail. Only report success after actually landing. */
    suspend fun showLatestHistory(): Boolean = withContext(Dispatchers.Default) {
        var selected: Triple<String, String, Long>? = null
        var completion: CompletableDeferred<Boolean>? = null
        var pendingViewport: Long? = null
        try {
            val immediate = synchronized(lock) {
                val host = historyHost ?: return@synchronized false
                val sid = currentId ?: return@synchronized false
                selected = Triple(host, sid, historyOpenEpoch)
                val ramLast = currentEvents.lastOrNull()?.seq ?: -1L
                if (!currentHasNewer && ramLast >= latestObservedHistorySeq &&
                    (currentEvents.isNotEmpty() || followCursor == -1)) {
                    markCurrentHistoryAtTailLocked()
                    return@synchronized true
                }
                val ticket = historyCache.ticket()
                val installed = historyCache.atTicket(ticket) {
                    val page = historyCache.latestHistory(host, historyArchiveKey(sid), HISTORY_PAGE_SIZE * 2, ticket)
                    if (page.events.isEmpty() || (page.lastSeq ?: -1L) < latestObservedHistorySeq) false
                    else {
                        installHistoryWindowLocked(page)
                        markCurrentHistoryAtTailLocked()
                        true
                    }
                } ?: false
                if (installed) return@synchronized true
                if (connectionManager.connectedApi == null || muxForHost(host) == null) {
                    setConnectionError("Latest history is not available while reconnecting")
                    return@synchronized false
                }
                val waiting = CompletableDeferred<Boolean>()
                completion = waiting
                historySaveJob?.cancel()
                historyCacheTicket = ticket // an explicit read request, never an automatic refill
                pendingViewport = ++historyViewportRequest
                _loadingOlder.value = false
                _loadingNewer.value = true
                _loadNewerFailed.value = false
                startFollow(sid, waiting)
                false
            }
            if (immediate) true else completion?.await() ?: false
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            completion?.complete(false)
            throw cancelled
        } catch (failure: Exception) {
            completion?.complete(false)
            synchronized(lock) {
                selected?.let { (host, sid, epoch) ->
                    if (historyHost == host && currentId == sid && historyOpenEpoch == epoch)
                        setConnectionError(failure.message)
                }
            }
            if (failure is java.io.IOException || failure is android.database.sqlite.SQLiteException)
                connectionManager.reportStorageFailure(failure)
            false
        } finally {
            completion?.complete(false)
            synchronized(lock) {
                if (pendingViewport != null && pendingViewport == historyViewportRequest) _loadingNewer.value = false
            }
        }
    }

    private data class HistoryTurnRead(
        val host: String, val sid: String, val epoch: Long, val token: Long,
        val api: DshApiClient?, val cursor: Int?, val ticket: Long, val followEpoch: Long,
    )

    private fun ownsHistoryTurnRead(read: HistoryTurnRead, checkTicket: Boolean = true): Boolean =
        read.host == historyHost && read.host == activeHostKey && read.sid == currentId &&
            read.epoch == historyOpenEpoch && read.token == historyViewportRequest &&
            read.api === connectionManager.connectedApi && read.cursor == followCursor &&
            read.followEpoch == followStreamEpoch && (!checkTicket || read.ticket == historyCache.ticket())

    /**
     * Jump directly to a turn-outline sequence, without paging across the intervening transcript.
     * A cached start alone is not a hydrated turn: prefer a contiguous window containing its prompt
     * (which can occur AFTER turn/start), otherwise fetch a small region around the requested seq.
     * The archive stages network payloads on disk and applies its normal byte/event RAM budgets.
     * True means the published conversation contains the exact start/prompt, not a nearby fallback.
     */
    suspend fun seekHistoryTurn(seq: Long): Boolean = withContext(Dispatchers.Default) {
        if (seq < 0 || seq > Int.MAX_VALUE) return@withContext false
        val context = currentCoroutineContext()
        val read = synchronized(lock) {
            val host = historyHost ?: return@withContext false
            val sid = currentId ?: return@withContext false
            if (host != activeHostKey) return@withContext false
            historySaveJob?.cancel()
            _loadingOlder.value = false
            _loadingNewer.value = true // suppress automatic paging while the explicit seek owns it
            HistoryTurnRead(host, sid, historyOpenEpoch, ++historyViewportRequest,
                connectionManager.connectedApi, followCursor, historyCache.ticket(), followStreamEpoch)
        }
        fun fail(message: String): Boolean {
            synchronized(lock) {
                if (ownsHistoryTurnRead(read, checkTicket = false)) setConnectionError(message)
            }
            return false
        }
        fun install(remotePage: HistoryPageFetchSummary? = null): Boolean = synchronized(lock) {
            context.ensureActive()
            if (!ownsHistoryTurnRead(read)) return@synchronized false
            historyCache.atTicket(read.ticket) {
                val page = historyCache.historyAround(read.host, historyArchiveKey(read.sid), seq, HISTORY_PAGE_SIZE * 2, read.ticket)
                val index = page.events.indexOfFirst { it.seq == seq &&
                    (it.type == "turn/start" || it.type == "user/message") }
                if (index < 0) return@atTicket false
                // historyAround reads ONE indexed contiguous component (including legacy expansion),
                // never stitching a start and prompt across a cache gap. A lone start is not enough.
                val hasPrompt = page.events[index].type == "user/message" || page.events.asSequence()
                    .drop(index + 1).takeWhile { it.type != "turn/start" }.any { it.type == "user/message" }
                if (remotePage == null && !hasPrompt && page.lastSeq != read.cursor?.toLong()) return@atTicket false
                // A jump can read NEWER than the oldest page already inspected. Do not replace
                // that earlier boundary's hasMore proof with metadata about this later region.
                if (remotePage?.firstSeq != null &&
                    (remoteOldestSeq == null || remotePage.firstSeq <= remoteOldestSeq!!)) {
                    remoteHasOlder = remotePage.metadata.hasMore
                    remoteOldestSeq = remotePage.firstSeq
                }
                // Keep framing rows before the prompt; trimToUserPrompt would discard turn/start.
                historyAtTail = false
                historyAnchorSeq = seq
                historyCacheTicket = read.ticket
                installHistoryWindowLocked(page)
                rebuildCurrentLocked()
                val landed = _currentConversation.value?.let { snapshot ->
                    snapshot.sessionId == read.sid && snapshot.nodes.any { it.seq == seq &&
                        (it is com.labteto.dshmobile.core.session.TurnStartNode ||
                            it is com.labteto.dshmobile.core.session.UserMessageNode) }
                } == true
                if (landed) {
                    _loadOlderFailed.value = false
                    _loadNewerFailed.value = false
                    clearConnectionError()
                }
                landed
            } ?: false
        }
        try {
            if (install()) return@withContext true
            if (!synchronized(lock) { ownsHistoryTurnRead(read) })
                return@withContext fail("History changed; retry opening this turn")
            val api = read.api ?: return@withContext fail("This turn is not available while reconnecting")
            val cursor = read.cursor ?: return@withContext fail("This turn is waiting for a connected session snapshot")
            if (seq > cursor) return@withContext fail("This turn is outside the current history snapshot")
            // beforeSeq is exclusive; throughSeq remains the FOLLOW cursor, never the target seq.
            // Look slightly ahead so a later user/message is included. If message-heavy pages do not
            // reach the start, walk only this small target region backwards, never from the live tail.
            var before = minOf(cursor.toLong() + 1, seq + HISTORY_PAGE_SIZE + 1)
            while (true) {
                context.ensureActive()
                if (!synchronized(lock) { ownsHistoryTurnRead(read) })
                    return@withContext fail("History changed; retry opening this turn")
                val result = fetchStagedHistoryPage(api, historyCache, read.host, historyArchiveKey(read.sid), read.ticket,
                    readerRequest(SessionPageRequest(SessionAddress.Session(sessionId = read.sid), cursor,
                        beforeSeq = before.takeIf { it <= Int.MAX_VALUE }?.toInt(), maxMessages = HISTORY_PAGE_SIZE)),
                ) { summary, commit ->
                    context.ensureActive()
                    synchronized(lock) {
                        ownsHistoryTurnRead(read) && summary.firstSeq != null && summary.lastSeq != null &&
                            summary.firstSeq < before && summary.lastSeq == before - 1 &&
                            commitHistoryPageLocked(summary, commit)
                    }
                }
                val page = when (result) {
                    is RpcResult.Err -> return@withContext fail(result.error.message)
                    is RpcResult.Ok -> result.value
                }
                if (!page.committed) return@withContext fail("The server did not return the requested turn region")
                if (page.firstSeq!! <= seq) {
                    return@withContext if (install(remotePage = page)) true
                        else fail("The requested turn is not present in this history snapshot")
                }
                before = page.metadata.reader?.nextBeforeSeq?.toLong() ?: page.firstSeq
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) {
            fail(failure.message ?: "Could not open this history turn")
            if (failure is java.io.IOException || failure is android.database.sqlite.SQLiteException)
                connectionManager.reportStorageFailure(failure)
            false
        } finally {
            synchronized(lock) { if (historyViewportRequest == read.token) _loadingNewer.value = false }
        }
    }

    private data class NewerHistoryRead(
        val host: String, val sid: String, val epoch: Long, val token: Long,
        val api: DshApiClient?, val cursor: Int?, val ticket: Long,
        val anchor: Long, val windowEvents: Int,
    )

    private fun isCurrentNewerReadLocked(read: NewerHistoryRead, checkTicket: Boolean = true): Boolean =
        read.token == historyViewportRequest && read.epoch == historyOpenEpoch && read.sid == currentId &&
            read.host == historyHost && read.host == activeHostKey && read.api === connectionManager.connectedApi &&
            read.cursor == followCursor && currentEvents.lastOrNull()?.seq == read.anchor &&
            (!checkTicket || read.ticket == historyCache.ticket())

    private fun failNewerRead(read: NewerHistoryRead, message: String) = synchronized(lock) {
        if (isCurrentNewerReadLocked(read, checkTicket = false)) {
            _loadNewerFailed.value = true
            setConnectionError(message)
        }
    }

    /** Install only an adjacent component; around(anchor+1) alone may select the old side of a gap. */
    private fun installNewerHistoryLocked(read: NewerHistoryRead): Boolean {
        if (!isCurrentNewerReadLocked(read)) return false
        return historyCache.atTicket(read.ticket) {
            val next = historyCache.newerHistory(read.host, historyArchiveKey(read.sid), read.anchor, HISTORY_PAGE_SIZE, read.ticket)
            if (next.events.isEmpty() || next.firstSeq!! > read.anchor + 1) return@atTicket false
            val page = historyCache.historyAround(read.host, historyArchiveKey(read.sid), read.anchor + 1, HISTORY_PAGE_SIZE * 2, read.ticket)
            if ((page.lastSeq ?: Long.MIN_VALUE) <= read.anchor) return@atTicket false
            historyAtTail = false
            installHistoryWindowLocked(page)
            rebuildCurrentLocked()
            clearConnectionError()
            _loadNewerFailed.value = false
            true
        } ?: false
    }

    /** Automatic callers stop after failure; a retry button calls this method again deliberately. */
    suspend fun loadNewer() = withContext(Dispatchers.Default) {
        val read = synchronized(lock) {
            if (_loadingOlder.value || !_loadingNewer.compareAndSet(false, true)) return@withContext
            _loadNewerFailed.value = false
            val host = historyHost
            val sid = currentId
            val anchor = currentEvents.lastOrNull()?.seq
            if (host == null || sid == null || anchor == null || !currentHasNewer) {
                _loadingNewer.value = false
                return@withContext
            }
            NewerHistoryRead(host, sid, historyOpenEpoch, ++historyViewportRequest,
                connectionManager.connectedApi, followCursor, historyCache.ticket(), anchor, currentEvents.size)
        }
        try {
            if (synchronized(lock) { installNewerHistoryLocked(read) }) return@withContext
            if (!synchronized(lock) { isCurrentNewerReadLocked(read) }) {
                failNewerRead(read, "Local history changed; retry loading newer history")
                return@withContext
            }
            val cursor = read.cursor
            if (read.api == null || cursor == null) {
                failNewerRead(read, "Newer history is waiting for a connected session snapshot")
                return@withContext
            }
            if (read.anchor >= cursor.toLong()) {
                synchronized(lock) {
                    if (isCurrentNewerReadLocked(read)) {
                        _loadNewerFailed.value = true
                        historyAtTail = false
                        if (historyAnchorSeq == null) historyAnchorSeq = currentEvents.firstOrNull()?.seq
                        // This is a deliberate read request after a cache clear, not automatic refill.
                        historyCacheTicket = read.ticket
                        startFollow(read.sid)
                    }
                }
                return@withContext
            }
            fetchNewerHistoryGap(read)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) {
            failNewerRead(read, failure.message ?: "Could not load newer history")
            if (failure is java.io.IOException || failure is android.database.sqlite.SQLiteException)
                connectionManager.reportStorageFailure(failure)
        } finally {
            synchronized(lock) { if (historyViewportRequest == read.token) _loadingNewer.value = false }
        }
    }

    private suspend fun fetchNewerHistoryGap(read: NewerHistoryRead) {
        val api = read.api ?: return
        val cursor = read.cursor ?: return
        // Read near the viewport, not backwards from an arbitrarily distant cached component.
        var before = minOf(cursor.toLong() + 1, read.anchor + read.windowEvents.coerceAtLeast(1).toLong() + 1)
        while (true) {
            if (!synchronized(lock) { isCurrentNewerReadLocked(read) }) {
                failNewerRead(read, "Local history changed; retry loading newer history")
                return
            }
            val result = fetchStagedHistoryPage(api, historyCache, read.host, historyArchiveKey(read.sid), read.ticket,
                readerRequest(SessionPageRequest(address = SessionAddress.Session(sessionId = read.sid), throughSeq = cursor,
                    beforeSeq = before.takeIf { it <= Int.MAX_VALUE }?.toInt(), maxMessages = HISTORY_PAGE_SIZE)),
            ) { summary, commit ->
                synchronized(lock) {
                    if (!isCurrentNewerReadLocked(read) || summary.firstSeq == null || summary.lastSeq == null ||
                        summary.firstSeq >= before) false else commitHistoryPageLocked(summary, commit)
                }
            }
            val page = when (result) {
                is RpcResult.Err -> { failNewerRead(read, result.error.message); return }
                is RpcResult.Ok -> result.value
            }
            val min = page.firstSeq
            if (min == null || page.lastSeq == null || min >= before) {
                failNewerRead(read, "The server returned no progress while loading newer history")
                return
            }
            if (!page.committed) {
                failNewerRead(read, "Local history changed; retry loading newer history")
                return
            }
            if (synchronized(lock) { installNewerHistoryLocked(read) }) return
            if (!synchronized(lock) { isCurrentNewerReadLocked(read) }) {
                failNewerRead(read, "Local history changed; retry loading newer history")
                return
            }
            if (min <= read.anchor + 1) {
                failNewerRead(read, "The server page did not cover the next history event")
                return
            }
            // Strictly decreasing until the event next to the viewport is covered. Empty and
            // no-progress pages fail above; there is no automatic network retry loop on error.
            before = page.metadata.reader?.nextBeforeSeq?.toLong() ?: min
        }
    }

    private fun saveHistoryLocked() {
        if (followCursor == null || historyHost != activeHostKey) return
        val host = historyHost ?: return
        val sid = currentId ?: return
        val ticket = historyCacheTicket
        val events = trimToUserPrompt(historyTail(currentEvents.toList()))
        val archiveKey = historyArchiveKey(sid)
        scope.launch {
            runCatching { historyCache.write(host, archiveKey, ticket, events) }
        }
    }

    private fun scheduleHistorySaveLocked() {
        historySaveJob?.cancel()
        historySaveJob = scope.launch {
            delay(350)
            synchronized(lock) { saveHistoryLocked() }
        }
    }

    private fun mergeProjectionLocked(key: String, seq: Int, value: JsonElement) {
        val existing = currentProjections[key]
        if (existing == null || seq >= existing.seq) {
            currentProjections[key] = ProjectionValue(seq, value)
            currentId?.let { sid ->
                if (key == "userQuestions") questionSessions.projection(sid, seq, value)
                if (key == "inbox") questionSessions.inbox(sid, value)
            }
        }
    }

    private fun rebuildCurrentLocked() {
        // The preview is presentation only. Host control updates must not replace it with an
        // empty fold or mix cached events into a new follow generation.
        val sid = currentId ?: return
        if (followCursor == null) {
            if (currentEvents.isNotEmpty()) _currentConversation.value = historyPreview(sid, currentEvents)
                .copy(hasMore = currentHasMore, hasNewer = currentHasNewer)
            return
        }
        val fold = EventFold(sid)
        if (foldedHistoryRevision != currentEventsRevision || foldedHistory?.sessionId != sid) {
            foldedHistory = fold.fold(currentEvents.toList())
            foldedHistoryRevision = currentEventsRevision
        }
        val durable = foldedHistory ?: return
        val snapshot = if (currentHasNewer) durable else liveAssistant.transientSnapshot(durable)
        val blank = if (currentEvents.isEmpty()) currentBlank else snapshot.blank
        val running = runningBySession[sid] ?: snapshot.running
        // The queue is the `inbox` projection wherever the host publishes one — it reaches us from
        // both the control stream and the follow snapshot, whichever is ahead. `currentQueue` is
        // the older `session/control` queue frame, kept for a host that still sends them.
        val inbox = currentProjections[INBOX_PROJECTION]?.value
        val queue = if (inbox != null) {
            InboxProjection.itemsFrom(inbox).map(::queuedInboxItemToQueueItem)
        } else {
            currentQueue
        }
        val merged = snapshot.copy(
            blank = blank,
            running = running,
            hasMore = currentHasMore,
            hasNewer = currentHasNewer,
            queue = queue,
            projections = currentProjections.mapValues { it.value.value },
        )
        questionSessions.restoreSettled(merged)
        _currentConversation.value = merged
        publishCatalogSubagentsLocked()
    }

    /**
     * The open session's subagents from its `subagentCatalog` projection (harness 0.1.7), with
     * each child's activity read off that child's own running state.
     *
     * Does nothing when the projection is absent, which is a 0.1.6 host: there [refreshSubagents]
     * reads `subagents/list` instead, and that answer must not be overwritten with an empty list.
     */
    private fun publishCatalogSubagentsLocked() {
        val rows = subagentEntriesFromCatalog(currentProjections[SUBAGENT_CATALOG_PROJECTION]?.value) {
            runningBySession[it] == true
        } ?: return
        if (_subagents.value != rows) _subagents.value = rows
    }

    private fun emitSessionsLocked() {
        val rows = sessionRows.values.map { row ->
            row.copy(pendingInteraction = pendingInteractionOf(
                pendingKinds[row.sessionId].orEmpty() + modernQuestionKinds[row.sessionId].orEmpty(),
            ))
        }
        _sessions.value = rows
    }

    private fun emitWorkspacesLocked() {
        val ordered = workspaceOrder.mapNotNull { workspaceRows[it] } +
            workspaceRows.values.filter { it.workspaceId !in workspaceOrder }
        _workspaces.value = ordered
    }

    private fun pendingInteractionOf(kinds: Set<String>?): String? {
        if (kinds.isNullOrEmpty()) return null
        return when {
            "question" in kinds -> "question"
            "plan-review" in kinds -> "plan-review"
            "approval" in kinds -> "approval"
            else -> null
        }
    }

    private fun addPendingLocked(sessionId: String, kind: String) {
        pendingKinds.getOrPut(sessionId) { LinkedHashSet() }.add(kind)
    }

    private fun removePendingLocked(sessionId: String, kind: String) {
        pendingKinds[sessionId]?.remove(kind)
        if (pendingKinds[sessionId].isNullOrEmpty()) pendingKinds.remove(sessionId)
    }

    private fun extractTitle(block: SessionProjectionsBlock?): String? {
        val value = block?.values?.get("title") ?: return null
        return when (value) {
            is JsonPrimitive -> value.contentOrNull
            is JsonObject -> value["title"]?.jsonPrimitive?.contentOrNull
            else -> null
        }
    }

    // ------------------------------------------------------------------ public RPC surface
    /** Fetch the current host's catalog without changing the open conversation or draft. */
    suspend fun refreshSessions(): Boolean {
        val api = apiOrNull() ?: return false
        val host = activeHostKey
        val runningTicket = synchronized(lock) { runningBySession.baselineTicket() }
        val r = api.sessionList(null)
        // An answer from a disconnected/replaced client must not populate another host's drawer.
        if (host != activeHostKey || api !== connectionManager.connectedApi) return false
        return when (r) {
            is RpcResult.Ok -> {
                clearConnectionError()
                synchronized(lock) {
                    sessionRows.clear()
                    for (item in r.value.items) {
                        val title = titleBySession[item.sessionId]
                            ?: extractTitle(item.projections)?.also { titleBySession[item.sessionId] = it }
                        runningBySession.baseline(item.sessionId, item.running, runningTicket)
                        sessionRows[item.sessionId] = SessionRow(
                            sessionId = item.sessionId,
                            title = title,
                            running = runningBySession[item.sessionId] ?: item.running,
                            blank = item.blank,
                            parentSessionId = item.parentSessionId,
                            origin = item.origin,
                            cwd = item.cwd,
                            agentPreset = listedAgentPresetLocked(item),
                            updatedAt = item.updatedAt,
                            pendingInteraction = null,
                            formatStatus = item.formatStatus,
                        )
                    }
                    rebuildCurrentLocked()
                    publishCatalogSubagentsLocked()
                    emitSessionsLocked()
                }
                true
            }
            is RpcResult.Err -> { setConnectionError(r.error.message); false }
        }
    }

    /**
     * Apply one workspace mutation's own answer immediately.
     *
     * `workspace.list` no longer exists; the registry is a stream, and a mutation answers with the
     * value it produced. Applying it here keeps the UI responsive without waiting for the stream
     * to commit, and the stream's next frame — which is authoritative — corrects anything this
     * guessed. Deleting is the one case that must not be optimistic in reverse: a delayed upsert
     * could otherwise resurrect a row, which is why removal goes through the same path as the
     * stream's own.
     */
    private fun applyWorkspaceValue(value: WorkspaceValue) = upsertWorkspace(value.workspace)

    suspend fun openSession(sessionId: String, expectedClientId: String? = null) = withContext(Dispatchers.Default) {
        // Opening cached content is observation, not a network command. A reconnecting host can
        // still switch between its cached sessions; notification navigation keeps its ready-client fence.
        val api = connectionManager.connectedApi
        if (expectedClientId != null && api == null) return@withContext
        val host = activeHostKey ?: return@withContext
        _loadOlderFailed.value = false
        synchronized(lock) {
            if (host != activeHostKey || api !== connectionManager.connectedApi ||
                (expectedClientId != null && expectedClientId != connectionManager.state.value.clientId)) return@withContext
            saveHistoryLocked()
            historySaveJob?.cancel()
            followJob?.cancel()
            val same = currentId == sessionId && historyHost == host
            historyOpenEpoch++
            historyHost = host
            historyCacheTicket = historyCache.ticket()
            followCursor = null
            currentId = sessionId
            latestObservedHistorySeq = -1L
            _currentSessionId.value = sessionId
            currentEvents.clear()
            currentWindowBytes = 0
            currentOversizedWindow = false
            knownFirstHistorySeq = null
            currentEventsRevision++
            historyAnchorSeq = null
            historyAtTail = true
            currentHasNewer = false
            currentLegacyWindow = false
            historyViewportRequest++
            _loadingOlder.value = false
            _loadingNewer.value = false
            _loadNewerFailed.value = false
            remoteHasOlder = false
            remoteOldestSeq = null
            foldedHistory = null
            currentHasMore = false
            currentBlank = sessionRows[sessionId]?.blank ?: true
            currentProjections.clear()
            currentQueue = emptyList()
            liveAssistant.clear()
            _currentConversation.value = null
            if (!same) {
                closeSubagentTranscript()
                _subagentHistoryState.value = ArchivedTranscriptState()
                _jobs.value = emptyList()
                _skills.value = emptyList()
                _models.value = null
                _subagents.value = emptyList()
                _subagentConversation.value = null
                _subagentMode.value = null
                _commands.value = emptyList()
                _pendingPermission.value = null
            }
            startFollow(sessionId)
            startJobs(sessionId)
        }
        // Everything past the follow stream furnishes the chrome around the transcript — the skill
        // and model pickers, the subagent list, the command catalog — and none of it is needed to
        // paint a single message. Run in series they stacked four round trips onto every session
        // tap, which is what made switching sessions feel like loading them. The follow stream is
        // already open by this point, so the transcript arrives while these are still in flight.
        //
        // `refreshSubagents` and `refreshCommands` read the open session from `currentId`, which
        // was set synchronously above, so they still target this session rather than a stale one.
        coroutineScope {
            launch { loadSkills(sessionId) }
            launch { loadModels(sessionId) }
            launch { refreshSubagents() }
            launch { refreshCommands() }
            launch { rememberLastSession(sessionId) }
        }
    }

    /**
     * Open the live journal for one session, replacing whatever was open.
     *
     * `session/readerFollow` opens with a row/byte-bounded reduced tail, projections, and the
     * generation's raw log cut. Reader-v1 acknowledgement is mandatory; unsupported hosts get an
     * explicit error, never an implicit raw fallback. The baseline is the entire initial load:
     * older HTTP pages are requested only by explicit reader navigation, not background hydration.
     *
     * The stream is opened with `assistantStream`, because since harness 0.1.3 that is the only
     * way to see a reply while it is written: the durable log holds one settlement per model
     * attempt and no deltas. The frames it adds are process-local presentation — never replayed,
     * never paged — and are folded after the durable window as a provisional message.
     *
     * Following does not resume a stopped agent: the host publishes a cold session's prepared
     * snapshot immediately and promotes it in the background, so opening a transcript is an
     * observation rather than an execution.
     */
    private fun startFollow(sessionId: String) = startFollow(sessionId, null)

    /** latestCompletion belongs only to an explicit jump, not to the lifetime of the live follow. */
    private fun startFollow(sessionId: String, latestCompletion: CompletableDeferred<Boolean>?) {
        followJob?.cancel()
        readerMode = true
        readerNextBeforeSeq = null
        followCursor = null
        val host = historyHost ?: run { latestCompletion?.complete(false); return }
        val epoch = historyOpenEpoch
        val ticket = historyCacheTicket
        val streamEpoch = ++followStreamEpoch
        val requestedViewport = historyViewportRequest
        fun isCurrent() = historyOpenEpoch == epoch && currentId == sessionId && activeHostKey == host &&
            followStreamEpoch == streamEpoch
        if (latestCompletion == null) scope.launch {
            try {
                val anchor = synchronized(lock) { historyAnchorSeq?.takeIf { !historyAtTail } }
                val archived = if (anchor == null) historyCache.latestHistory(host, historyArchiveKey(sessionId), INITIAL_FOLLOW_MESSAGES, ticket)
                    else historyCache.historyAround(host, historyArchiveKey(sessionId), anchor, HISTORY_PAGE_SIZE * 2, ticket)
                // Do not eagerly deserialize an old full-window preview just to open the tail.
                val cached = archived.events.takeIf { it.isNotEmpty() } ?: return@launch
                val bytes = archived.payloadBytes
                synchronized(lock) {
                    if (isCurrent() && followCursor == null) historyCache.atTicket(ticket) {
                        currentEvents.clear()
                        currentEvents.addAll(cached)
                        currentWindowBytes = bytes
                        currentOversizedWindow = currentWindowBytes > HistoryWindowBudget.payloadBytes(HistoryWindowBudget.TAIL_HIGH_MESSAGES)
                        observeHistoryBoundsLocked(cached.firstOrNull()?.seq, cached.lastOrNull()?.seq)
                        currentLegacyWindow = archived.legacyUnbounded || cached.any { it.type == "assistant/chunk" }
                        currentEventsRevision++
                        currentHasMore = archived.hasOlder || knownFirstHistorySeq?.let { first ->
                            (cached.firstOrNull()?.seq ?: first) > first } == true
                        currentHasNewer = archived.hasNewer || latestObservedHistorySeq > (cached.lastOrNull()?.seq ?: -1L)
                        _currentConversation.value = historyPreview(sessionId, cached).copy(
                            hasMore = currentHasMore, hasNewer = currentHasNewer)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                log("could not read local history", failure)
                if (isCurrent()) setConnectionError(failure.message)
            }
        }
        val api = connectionManager.connectedApi ?: run { latestCompletion?.complete(false); return }
        val mux = connectionManager.generation?.mux
        if (mux == null) {
            log("cannot follow $sessionId: no connection generation")
            latestCompletion?.complete(false)
            return
        }
        fun isCurrentFollow() = isCurrent() && api === connectionManager.connectedApi && muxForHost(host) === mux && mux.failure == null
        val args = buildJsonObject {
            put("request", encodeToJsonElement(SessionFollowRequest.serializer(), SessionFollowRequest(
                address = SessionAddress.Session(sessionId = sessionId),
                maxRows = READER_MAX_ROWS, maxBytes = READER_MAX_BYTES, assistantStream = true)))
        }
        followJob = scope.launch {
            // Watch only while the caller awaits the first baseline. Clearing must neither keep the
            // jump spinning nor terminate the useful live follow that continues after that clear.
            val invalidated = latestCompletion?.let { waiting ->
                launch(start = CoroutineStart.UNDISPATCHED) {
                    historyCache.ticketChanges.first { it != ticket }
                    waiting.complete(false)
                }
            }
            val completed = latestCompletion?.invokeOnCompletion { invalidated?.cancel() }
            var readerAcknowledged = false
            try {
                runCatching {
                    mux.openStream("session/readerFollow", args).collect { item ->
                        when (val frame = decodeOrNull(SessionFollowFrameSerializer, item)) {
                            is SessionFollowFrame.Snapshot -> {
                                requireReaderCoverage(frame.reader, frame.records.firstOrNull()?.event?.seq?.toLong(),
                                    frame.records.lastOrNull()?.event?.seq?.toLong(), frame.records.size.toLong(),
                                    frame.cursor, hasMore = frame.hasMore)
                                require(frame.records.zipWithNext().all { (a, b) -> b.event.seq == a.event.seq + 1 }) {
                                    "Noncontiguous reader baseline"
                                }
                                readerAcknowledged = true
                                synchronized(lock) {
                                    if (!isCurrentFollow()) latestCompletion?.complete(false)
                                    else if (latestCompletion?.isActive == true && requestedViewport == historyViewportRequest) {
                                        val landed = historyCache.atTicket(ticket) {
                                            historyAtTail = true
                                            historyAnchorSeq = null
                                            applyFollowSnapshotAtTicket(sessionId, frame, ticket)
                                            !currentHasNewer && (currentEvents.lastOrNull()?.seq ?: -1L) >= frame.cursor
                                        } ?: run {
                                            applyFollowSnapshotAtTicket(sessionId, frame, ticket)
                                            false
                                        }
                                        latestCompletion.complete(landed)
                                    } else {
                                        applyFollowSnapshotAtTicket(sessionId, frame, ticket)
                                        latestCompletion?.complete(false)
                                    }
                                }
                            }
                            is SessionFollowFrame.Entry -> synchronized(lock) {
                                check(readerAcknowledged) { "Reader stream did not acknowledge reader v1; upgrade the host" }
                                if (isCurrentFollow()) applyFollowEntry(sessionId, frame.record)
                            }
                            is SessionFollowFrame.AssistantStream -> synchronized(lock) {
                                check(readerAcknowledged) { "Reader stream did not acknowledge reader v1; upgrade the host" }
                                if (isCurrentFollow()) applyAssistantFrame(sessionId, frame)
                            }
                            null -> error("Invalid lightweight reader frame; reader v1 is required")
                        }
                    }
                }.onFailure { failure ->
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    log("session/follow ended for $sessionId", failure)
                    if (failure is java.io.IOException || failure is android.database.sqlite.SQLiteException) {
                        if (isCurrent()) setConnectionError(failure.message)
                        connectionManager.reportStorageFailure(failure)
                    }
                    synchronized(lock) {
                        if (shouldReportFollowFailure(failure, isCurrentFollow())) setConnectionError(
                            if (readerAcknowledged) failure.message else
                                "Lightweight reader unavailable: ${failure.message}. The host must support reader v1; upgrade it if needed. No raw fallback was loaded.")
                    }
                }
            } finally {
                completed?.dispose()
                invalidated?.cancel()
                latestCompletion?.complete(false)
            }
        }.also { job -> latestCompletion?.let { waiting -> job.invokeOnCompletion { waiting.complete(false) } } }
    }

    /**
     * Mirror the open session's background jobs from `job/list` (harness 0.1.7).
     *
     * 0.1.7 took jobs off `session/control`; the stream answers with the complete set the session
     * can see on open and after every change. A 0.1.6 host refuses the endpoint, which only ends
     * this stream: its control-stream jobs frames still feed [jobs] through [applyJobs].
     */
    private fun startJobs(sessionId: String) {
        jobsJob?.cancel()
        val mux = connectionManager.generation?.mux ?: return
        val args = buildJsonObject {
            put("request", encodeToJsonElement(JobListRequest.serializer(), JobListRequest(sessionId)))
        }
        jobsJob = scope.launch {
            runCatching {
                mux.openStream("job/list", args).collect { item ->
                    val rows = jobRowsOf(item) ?: return@collect
                    _jobControls.value = true
                    applyJobs(sessionId, rows)
                }
            }.onFailure { failure ->
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                log("job/list ended for $sessionId", failure)
            }
        }
    }

    /**
     * One job's retained output and then its settlement, from `job/follow` (harness 0.1.7).
     *
     * Cold: nothing is opened until the flow is collected, and cancelling the collector cancels
     * the stream. Frames this build cannot read are skipped.
     */
    fun followJob(jobId: String): kotlinx.coroutines.flow.Flow<JobFollowFrame> = kotlinx.coroutines.flow.flow {
        val sid = currentSessionId.value ?: return@flow
        val mux = connectionManager.generation?.mux ?: return@flow
        val args = buildJsonObject {
            put("request", encodeToJsonElement(JobFollowRequest.serializer(), JobFollowRequest(jobId = jobId, sessionId = sid)))
        }
        mux.openStream("job/follow", args).collect { item ->
            decodeOrNull(JobFollowFrameSerializer, item)?.let { emit(it) }
        }
    }

    /** Stop one background job of the open session. False when the harness refused. */
    suspend fun killJob(jobId: String): Boolean {
        val sid = currentSessionId.value ?: return false
        val api = apiOrNull() ?: return false
        return when (val r = api.jobKill(sid, jobId)) {
            is RpcResult.Ok -> true
            // The row went away first; the next `job/list` frame shows that, so it is not a failure.
            is RpcResult.Err -> if (r.error.code == "job/not-found") true else { setConnectionError(r.error.message); false }
        }
    }

    /** Compatibility entry for existing reflective tests; production carries the follow ticket. */
    private fun applyFollowSnapshot(sessionId: String, frame: SessionFollowFrame.Snapshot) =
        applyFollowSnapshotAtTicket(sessionId, frame, historyCacheTicket)

    /** A cleared cache never receives old baseline writes, while the live stream remains usable. */
    private fun applyFollowSnapshotAtTicket(sessionId: String, frame: SessionFollowFrame.Snapshot, ticket: Long) {
        synchronized(lock) {
            if (currentId != sessionId) return@synchronized
            val host = historyHost ?: return@synchronized
            latestObservedHistorySeq = frame.cursor.toLong()
            if (knownFirstHistorySeq?.let { it > frame.cursor } == true || frame.cursor < 0) knownFirstHistorySeq = null
            val incoming = expandRecords(frame.records)
            observeHistoryBoundsLocked(incoming.firstOrNull()?.seq, null)
            val cacheCurrent = historyCache.atTicket(ticket) {
                check(historyCache.removeHistoryAfter(host, historyArchiveKey(sessionId), frame.cursor.toLong(), ticket))
                check(historyCache.storeEvents(host, historyArchiveKey(sessionId), ticket, incoming))
                // A fresh tail is exactly the server's small baseline, not the accumulated cache.
                val page = HistoryDiskPage(incoming, frame.hasMore, false,
                    legacyUnbounded = incoming.any { it.type == "assistant/chunk" },
                    payloadBytes = incoming.sumOf { encodeHistoryEvent(it).payload.size.toLong() })
                remoteOldestSeq = page.firstSeq
                val visible = historyAnchorSeq?.takeIf { !historyAtTail }?.let {
                    historyCache.historyAround(host, historyArchiveKey(sessionId), it, HISTORY_PAGE_SIZE * 2, ticket)
                }?.takeIf { it.events.isNotEmpty() } ?: page
                remoteHasOlder = frame.hasMore
                installHistoryWindowLocked(visible)
                true
            } == true
            if (!cacheCurrent) {
                // Keep the reader's RAM viewport. A tiny live baseline may paint an empty screen,
                // but cannot repopulate old disk content or claim that missing history is cached.
                if (currentEvents.removeAll { it.seq > frame.cursor }) {
                    currentWindowBytes = currentEvents.sumOf { encodeHistoryEvent(it).payload.size.toLong() }
                    currentEventsRevision++
                    currentLegacyWindow = currentEvents.any { it.type == "assistant/chunk" }
                }
                if (currentEvents.isEmpty()) {
                    currentEvents.addAll(incoming)
                    currentWindowBytes = incoming.sumOf { encodeHistoryEvent(it).payload.size.toLong() }
                    currentLegacyWindow = currentEvents.any { it.type == "assistant/chunk" }
                    currentEventsRevision++
                }
                currentOversizedWindow = currentWindowBytes > HistoryWindowBudget.payloadBytes(HistoryWindowBudget.TAIL_HIGH_MESSAGES)
                currentHasNewer = (currentEvents.lastOrNull()?.seq ?: -1L) < frame.cursor.toLong()
                currentHasMore = currentHasMore || frame.hasMore ||
                    (frame.cursor >= 0 && (currentEvents.firstOrNull()?.seq ?: 1L) > 0)
                remoteHasOlder = frame.hasMore
                remoteOldestSeq = currentEvents.firstOrNull()?.seq
            }
            followCursor = frame.cursor
            readerNextBeforeSeq = frame.reader?.nextBeforeSeq
            _loadOlderFailed.value = false
            val asOf = frame.projections["asOfSeq"]?.jsonPrimitive?.intOrNull ?: frame.cursor
            (frame.projections["values"] as? JsonObject)?.forEach { (key, value) ->
                mergeProjectionLocked(key, asOf, value)
            }
            liveAssistant.seed(frame.assistantStream, sparseReader = frame.reader?.version == 1)
            rebuildCurrentLocked()
            clearConnectionError()
            historyViewportRequest++
            _loadingOlder.value = false
            _loadingNewer.value = false
            _loadNewerFailed.value = false
            if (cacheCurrent) saveHistoryLocked()
        }
    }

    /** One live event. */
    private fun applyFollowEntry(sessionId: String, record: SessionHistoryRecord) {
        for (envelope in expandRecords(listOf(record))) {
            handleSessionEvent(sessionId, envelope)
        }
    }

    /** One process-local assistant frame: the reply being written, a chunk at a time. */
    private fun applyAssistantFrame(sessionId: String, frame: SessionFollowFrame.AssistantStream) {
        val changed = synchronized(lock) {
            if (currentId != sessionId) return
            liveAssistant.accept(frame.frame) != AssistantLiveState.Change.NONE
        }
        if (changed) rebuildTicks.trySend(Unit)
    }

    /**
     * History records are plain events since harness 0.1.3, but a 0.1.2 host still packs runs of
     * consecutive assistant deltas into one record. Expanding those back into scalar events is
     * what keeps the journal's sequence numbers contiguous; see
     * [com.labteto.dshmobile.core.session.ChunkRows].
     */
    private fun expandRecords(records: List<SessionHistoryRecord>): List<SessionEventEnvelope> =
        ChunkRows.expandAll(records).map { wireEventToEnvelope(it) }

    /** Persist the landing session for this harness; a write failure is not worth surfacing. */
    private suspend fun rememberLastSession(sessionId: String) {
        val key = hostKey() ?: return
        runCatching { hostsStore.setLastSessionId(key, sessionId) }
            .onFailure { log("could not remember last session", it) }
    }

    /**
     * Page one screen further back.
     *
     * Called from the transcript's scroll position, so it has to be safe to call repeatedly: the
     * in-flight flag collapses a burst of scroll emissions into one request, and a page that adds
     * nothing new ends the paging rather than leaving `hasMore` set for the trigger to fire on
     * again.
     */
    private data class OlderHistoryRead(
        val host: String, val sid: String, val epoch: Long, val viewport: Long,
        val oldest: Long, val cursor: Int?, val api: DshApiClient?, val ticket: Long,
    )
    private fun ownsOlderRead(read: OlderHistoryRead) = currentId == read.sid && historyHost == read.host &&
        historyOpenEpoch == read.epoch && historyViewportRequest == read.viewport

    suspend fun loadOlder() = withContext(Dispatchers.Default) {
        val read = synchronized(lock) {
            if (_loadingOlder.value || _loadingNewer.value) return@withContext
            val sid = currentId ?: return@withContext
            val host = historyHost ?: return@withContext
            val oldest = currentEvents.firstOrNull()?.seq ?: return@withContext
            _loadingOlder.value = true
            OlderHistoryRead(host, sid, historyOpenEpoch, historyViewportRequest, oldest,
                followCursor, connectionManager.connectedApi, historyCache.ticket())
        }
        try {
            val fromDisk = synchronized(lock) {
                if (!ownsOlderRead(read)) return@synchronized false
                historyCache.atTicket(read.ticket) {
                    val cached = historyCache.olderHistory(read.host, historyArchiveKey(read.sid), read.oldest, HISTORY_PAGE_SIZE, read.ticket)
                    if (cached.events.isEmpty() || (cached.lastSeq ?: -1L) < read.oldest - 1) false
                    else {
                        val page = historyCache.historyAround(read.host, historyArchiveKey(read.sid), read.oldest - 1, HISTORY_PAGE_SIZE * 2, read.ticket)
                        historyAtTail = false
                        installHistoryWindowLocked(page)
                        _loadOlderFailed.value = false
                        rebuildCurrentLocked()
                        true
                    }
                } ?: false
            }
            if (fromDisk) return@withContext
            val api = read.api
            val cursor = read.cursor
            // No authoritative cut yet is waiting, not a failed user page.
            if (cursor == null) return@withContext
            if (api == null) {
                synchronized(lock) { if (ownsOlderRead(read)) _loadOlderFailed.value = true }
                return@withContext
            }
            val result = fetchStagedHistoryPage(api, historyCache, read.host, historyArchiveKey(read.sid), read.ticket,
                readerRequest(SessionPageRequest(SessionAddress.Session(sessionId = read.sid), cursor,
                    beforeSeq = readerNextBeforeSeq?.takeIf { it.toLong() == read.oldest } ?: read.oldest.toInt(),
                    maxMessages = HISTORY_PAGE_SIZE))) { summary, commit ->
                synchronized(lock) {
                    ownsOlderRead(read) && api === connectionManager.connectedApi && followCursor == cursor &&
                        (summary.metadata.recordCount == 0L || (summary.firstSeq ?: Long.MAX_VALUE) < read.oldest) &&
                        commitHistoryPageLocked(summary, commit)
                }
            }
            synchronized(lock) {
                if (!ownsOlderRead(read) || api !== connectionManager.connectedApi || followCursor != cursor) return@synchronized
                when (result) {
                    is RpcResult.Err -> { _loadOlderFailed.value = true; setConnectionError(result.error.message) }
                    is RpcResult.Ok -> {
                        if (!result.value.committed) { _loadOlderFailed.value = true; return@synchronized }
                        historyCache.atTicket(read.ticket) {
                            val page = historyCache.historyAround(read.host, historyArchiveKey(read.sid), read.oldest - 1, HISTORY_PAGE_SIZE * 2, read.ticket)
                            val progressed = result.value.metadata.recordCount == 0L ||
                                (page.firstSeq ?: Long.MAX_VALUE) < read.oldest
                            if (!progressed) { _loadOlderFailed.value = true; return@atTicket }
                            readerNextBeforeSeq = result.value.metadata.reader?.nextBeforeSeq
                            remoteHasOlder = result.value.metadata.hasMore
                            remoteOldestSeq = page.firstSeq
                            historyAtTail = false
                            installHistoryWindowLocked(page)
                            if (result.value.metadata.recordCount == 0L) currentHasMore = false
                            _loadOlderFailed.value = false
                            rebuildCurrentLocked()
                        } ?: run { _loadOlderFailed.value = true }
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val report = synchronized(lock) {
                if (!ownsOlderRead(read)) false else {
                    _loadOlderFailed.value = true
                    setConnectionError(failure.message)
                    true
                }
            }
            if (report && (failure is java.io.IOException || failure is android.database.sqlite.SQLiteException))
                connectionManager.reportStorageFailure(failure)
        } finally {
            synchronized(lock) { if (ownsOlderRead(read)) _loadingOlder.value = false }
        }
    }

    suspend fun createSession(cwd: String? = null, workspaceId: String? = null) {
        // Reuse the workspace's existing blank session instead of leaving another empty one behind
        // — the harness's own New Session does this, and it is why its list stays clean.
        if (workspaceId != null) {
            val reusable = synchronized(lock) {
                workspaceRows[workspaceId]?.sessionIds
                    ?.mapNotNull { sessionRows[it] }
                    ?.firstOrNull { it.blank && it.sessionId !in archived && it.origin != "subagent" }
                    ?.sessionId
            }
            if (reusable != null) {
                openSession(reusable)
                return
            }
        }
        val api = apiOrNull() ?: return
        when (val r = api.sessionCreate(SessionCreateRequest(workspaceId = workspaceId, cwd = cwd))) {
            is RpcResult.Ok -> {
                refreshSessions()
                openSession(r.value.sessionId)
            }
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun renameSession(sessionId: String, title: String) {
        val api = apiOrNull() ?: return
        when (val r = api.sessionRename(SessionRenameRequest(sessionId, title))) {
            is RpcResult.Ok -> setTitle(sessionId, r.value.title)
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun forkSession(sessionId: String, atSeq: Long? = null) {
        val api = apiOrNull() ?: return
        when (val r = api.sessionFork(SessionForkRequest(sessionId, atSeq?.toInt(), allowMigration = false))) {
            is RpcResult.Ok -> refreshSessions()
            is RpcResult.Err -> setConnectionError(if (r.error.code == "session/migration-required") migrationRequiredMessage() else r.error.message)
        }
    }

    /**
     * Archive one session.
     *
     * Harness 0.1.7 refuses a session that still has work running (its turn, a subagent, a job) as
     * `workspace/session-active`, naming the work, and that comes back as [ArchiveOutcome.Busy]
     * for the caller to confirm. Archiving again with [stopActivity] stops the work first.
     */
    suspend fun archiveSession(sessionId: String, stopActivity: Boolean = false): ArchiveOutcome {
        val api = apiOrNull() ?: return ArchiveOutcome.Failed
        val request = WorkspaceArchiveSessionRequest(sessionId, stopActivity = stopActivity.takeIf { it })
        return when (val r = api.workspaceArchiveSession(request)) {
            is RpcResult.Ok -> {
                setArchived(r.value.archivedSessionIds)
                refreshSessions()
                ArchiveOutcome.Archived
            }
            is RpcResult.Err -> {
                if (r.error.code == SESSION_ACTIVE && !stopActivity) {
                    val details = runCatching {
                        decodeFromJsonElement(WorkspaceSessionActiveDetails.serializer(), r.error.details)
                    }.getOrNull()
                    ArchiveOutcome.Busy(sessionId, details?.activity.orEmpty())
                } else {
                    setConnectionError(r.error.message)
                    ArchiveOutcome.Failed
                }
            }
        }
    }

    /** Pin or unpin one session (harness 0.1.7); the answer is the complete pin set. */
    suspend fun setPinned(sessionId: String, pinned: Boolean) {
        val api = apiOrNull() ?: return
        val result = if (pinned) api.workspacePinSession(sessionId) else api.workspaceUnpinSession(sessionId)
        when (result) {
            is RpcResult.Ok -> _pinnedSessionIds.value = result.value.pinnedSessionIds
            is RpcResult.Err -> setConnectionError(result.error.message)
        }
    }

    suspend fun prompt(text: String, mode: String, targetSessionId: String? = currentSessionId.value, targetHost: String? = activeHostKey) =
        promptContent(mode, listOf(PromptContentPart.Text(text)), targetSessionId, targetHost)

    /**
     * Prompt with attachments: raster images (bytes submitted base64, as the browser wire does)
     * and files already staged through [uploadFile], cited by receipt.
     *
     * All of them ride *one* call. `session/prompt` takes a list of content parts and the host
     * admits that list as a single batch, which is where its per-message image count and
     * aggregate-size limits live — sending one image per call, as this client used to, split one
     * message into several and meant those two limits could never fire at all. A file's bytes
     * never ride the prompt: the receipt names an upload the host already holds, and the host
     * refuses one it did not mint for this session.
     */
    suspend fun promptWithAttachments(
        text: String,
        mode: String,
        images: List<EncodedImageAttachment>,
        fileReceipts: List<String> = emptyList(),
        targetSessionId: String? = currentSessionId.value,
        targetHost: String? = activeHostKey,
    ): PromptOutcome {
        val parts = mutableListOf<PromptContentPart>()
        if (text.isNotBlank()) parts.add(PromptContentPart.Text(text))
        images.mapTo(parts) { PromptContentPart.Image(it.mediaType, it.data, it.name) }
        fileReceipts.mapTo(parts) { PromptContentPart.File(it) }
        return promptContent(mode, parts, targetSessionId, targetHost)
    }

    /**
     * Stage one file for the open session and answer with its receipt.
     *
     * The bytes are streamed to the raw-byte route the way the web client does it, so a large
     * file never sits in memory as base64. A deployment or relay that does not serve that route
     * answers 404, which is a missing capability rather than a broken link; a file that fits
     * comfortably in an RPC body is then retried through the `fileUploads/upload` Remote, which
     * every 0.1.3 host composes beside the route. [open] is called once per attempt and must
     * answer a fresh stream positioned at the first byte.
     *
     * A refusal is the composer's problem, not the connection's, so nothing here raises the
     * connection banner: the chip that owns the file shows the failure and offers a retry.
     */
    suspend fun uploadFile(
        name: String,
        size: Long,
        open: () -> InputStream?,
        onProgress: (sent: Long) -> Unit = {},
        targetSessionId: String? = currentSessionId.value,
        targetHost: String? = activeHostKey,
    ): RpcResult<FileUploadValue> = withContext(Dispatchers.IO) {
        val sid = targetSessionId
            ?: return@withContext RpcResult.Err(RpcError("internal", "no open session"))
        val api = apiForHost(targetHost)
            ?: return@withContext RpcResult.Err(RpcError("internal", "not connected"))
        val stream = open()
            ?: return@withContext RpcResult.Err(RpcError("internal", "could not read the file"))
        val streamed = stream.use { api.uploadFileBinary(sid, name, size, it, onProgress) }
        if (streamed !is RpcResult.Err || streamed.error.code != "capability-unavailable") return@withContext streamed
        if (size !in 0..MAX_ENCODED_UPLOAD_BYTES) return@withContext streamed
        log("upload route unavailable; falling back to fileUploads/upload for ${size}B")
        val bytes = open()?.use { it.readBytes() }
            ?: return@withContext RpcResult.Err(RpcError("internal", "could not read the file"))
        api.fileUploadEncoded(
            sid,
            EncodedFileUploadRequest(data = Base64.encodeToString(bytes, Base64.NO_WRAP), name = name),
        ).also { onProgress(bytes.size.toLong()) }
    }

    private suspend fun promptContent(mode: String, content: List<PromptContentPart>, targetSessionId: String?, targetHost: String?): PromptOutcome {
        val sid = targetSessionId ?: return PromptOutcome.Failed("no open session")
        val api = apiForHost(targetHost) ?: return PromptOutcome.Failed("not connected")
        val safeMode = if (mode == "steer") "steer" else "queue"
        val zone = TimeZone.getDefault().id
        val request = SessionPromptRequest(
            requestId = newPromptRequestId(),
            sessionId = sid,
            mode = safeMode,
            content = content,
            clientTimeZone = zone,
        )
        return when (val r = api.sessionPrompt(request)) {
            is RpcResult.Ok -> if (r.value.accepted) PromptOutcome.Ok
                else PromptOutcome.Failed("Host did not confirm message receipt")
            is RpcResult.Err -> if (r.error.code == ATTACHMENT_INVALID) {
                // The host declined the attachments, not the connection. Report it where they are
                // so the composer can keep them and say which bound they crossed.
                val reason = (r.error.details as? JsonObject)
                    ?.get("reason")?.jsonPrimitive?.contentOrNull
                PromptOutcome.Rejected(imageRejectionOf(reason.orEmpty()), reason)
            } else {
                setConnectionError(r.error.message)
                PromptOutcome.Failed(r.error.message)
            }
        }
    }

    suspend fun cancelTurn() {
        val sid = currentSessionId.value ?: return
        val api = apiOrNull() ?: return
        when (val r = api.sessionCancel(SessionCancelRequest(sid))) {
            is RpcResult.Ok -> Unit
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun updateQueue(itemId: String, action: String, contentText: String? = null, sessionId: String? = currentSessionId.value): Boolean {
        if (action == "edit" && contentText.isNullOrBlank()) return false
        val sid = sessionId ?: return false
        val api = apiOrNull() ?: return false
        val queueAction: QueueAction = when (action) {
            "remove" -> QueueAction.Remove()
            "steer" -> QueueAction.Steer()
            else -> QueueAction.Edit(listOf(ContentBlock.Text(contentText.orEmpty())))
        }
        return when (val r = api.sessionUpdateQueue(SessionUpdateQueueRequest(sid, itemId, queueAction))) {
            is RpcResult.Ok -> true
            is RpcResult.Err -> { setConnectionError(r.error.message); false }
        }
    }

    /**
     * Allows or refuses one pending approval.
     *
     * Reports its verdict in the same vocabulary a question answer does, and for the same reason:
     * an approval that the host would not take leaves the tool call behind it blocked, and a panel
     * that swallowed the refusal would sit there looking like a button that does nothing. A taken
     * one takes the panel with it — see [answerOutcome] for why no frame does that here.
     */
    suspend fun respondApproval(sessionId: String, approvalId: String, allow: Boolean): QuestionOutcome {
        val api = apiOrNull() ?: return QuestionOutcome.Unsent
        val request = synchronized(lock) { approvalRequests[approvalId] }
        if (request == null) {
            log("no pending approval for id $approvalId")
            // Nothing to answer with, so nothing can arrive to take the panel away either.
            if (_pendingApproval.value?.approvalId == approvalId) _pendingApproval.value = null
            return QuestionOutcome.Refused(NOT_PENDING)
        }
        val clientId = connectionManager.generation?.clientId
        if (clientId == null) {
            log("cannot answer approval $approvalId: no connection generation")
            return QuestionOutcome.Unsent
        }
        val outcome = if (allow) ApprovalOutcome.ALLOWED_ONCE else ApprovalOutcome.REJECTED
        // The waterfall's own return value *is* the outcome string, so this claims the request
        // with a bare value rather than the object 0.1.1 posted to /api/respond.
        val result = api.answerEvent(
            clientId = clientId,
            eventId = request.eventId,
            outcome = RemoteEventOutcome.Result(value = JsonPrimitive(outcome)),
        )
        return answerOutcome(result, "approval response", sessionId) { forgetRequest(request.eventId) }
    }

    /**
     * Answers a pending question batch.
     *
     * The payload is serialized from a typed DTO rather than assembled by hand, and that is the
     * whole point of the type: `custom` belongs to the answer *item*, and the host's schema strips
     * keys it does not recognise instead of objecting to them. A `custom` written one level out
     * therefore reached the wire, was accepted, and simply never reached the model — the user's
     * typed answer deleted in transit with nothing to show for it.
     */
    suspend fun answerQuestions(sessionId: String, answer: AskUserQuestionAnswer): QuestionOutcome {
        val api = apiOrNull() ?: return QuestionOutcome.Unsent
        val eventId = pendingQuestionEvent(sessionId) ?: return abandonQuestions(sessionId)
        val clientId = connectionManager.generation?.clientId ?: return QuestionOutcome.Unsent
        // The waterfall returns the answer object itself; there is no envelope around it now.
        return answerOutcome(
            api.answerEvent(
                clientId = clientId,
                eventId = eventId,
                outcome = RemoteEventOutcome.Result(
                    value = encodeToJsonElement(AskUserQuestionAnswer.serializer(), answer),
                ),
            ),
            "question response",
            sessionId,
        ) { forgetQuestions(sessionId, eventId) }
    }

    /**
     * Dismisses a pending question batch instead of answering it.
     *
     * Answering every item with an empty selection is a perfectly valid *answer*, and the model
     * reads it as "no preference". A dismissal fails the wait instead, and the host then settles
     * the tool call as cancelled. The code has to be exactly `cancelled`; the proxy refuses an
     * `ok:false` carrying any other.
     */
    suspend fun dismissQuestions(sessionId: String): QuestionOutcome {
        val api = apiOrNull() ?: return QuestionOutcome.Unsent
        val eventId = pendingQuestionEvent(sessionId) ?: return abandonQuestions(sessionId)
        val clientId = connectionManager.generation?.clientId ?: return QuestionOutcome.Unsent
        // A rejection, not an empty answer, and not `next`: `next` would delegate to the host's
        // own later listeners, which is a different thing from the user closing the prompt.
        return answerOutcome(
            api.answerEvent(
                clientId = clientId,
                eventId = eventId,
                outcome = RemoteEventOutcome.Rejected(
                    error = RemoteEventRejection(
                        name = "UserQuestionError",
                        message = QUESTION_CANCELLED.message,
                        code = QUESTION_CANCELLED.code,
                    ),
                ),
            ),
            "question dismissal",
            sessionId,
        ) { forgetQuestions(sessionId, eventId) }
    }

    private fun pendingQuestionEvent(sessionId: String): String? {
        val eventId = synchronized(lock) { questionEvents.eventFor(sessionId) }
        if (eventId == null) log("no pending question for session $sessionId")
        return eventId
    }

    /**
     * There is a card on screen for [sessionId] but no event left to address it to.
     *
     * A card in that state can never be answered — every path through here needs the `eventId` the
     * waterfall arrived with — so it is a corpse, and leaving it up would be the same dead end by a
     * shorter route. Reported as [NOT_PENDING] all the same: the wait, wherever it went, is not
     * this client's to settle any more.
     */
    private fun abandonQuestions(sessionId: String): QuestionOutcome {
        // Null, not an event: the corpse is defined by having no registration, and a request that
        // arrived in the meantime has one and must be left alone.
        forgetQuestions(sessionId, null)
        return QuestionOutcome.Refused(NOT_PENDING)
    }

    /**
     * Map one `$events/result` answer onto the store's outcome vocabulary, and run [forget] when
     * that answer ended the request behind it.
     *
     * [forget] is the card's only exit on this client, and it is a caller's lambda rather than an
     * `eventId` because the two kinds are held differently — an approval by its event alone, a
     * question by its session *and* its event, so that an answer cannot take away the card of the
     * request that replaced the one it answered. The web client has no equivalent because it never needs
     * one: its `PendingQuestion.answer()` resolves the waiting promise in the same process, so the
     * card's life ends with the call. Here the answer is a POST, and the host settles it by
     * *removing this client's delivery first* and then pushing `cancel` to the deliveries that
     * remain — so the client that acted is the only one the resolution is never announced to.
     * Waiting for a frame that cannot arrive is what left an answered card frozen on "Submitting…"
     * with no way out but a force-stop.
     *
     * A failure here is not retried: upstream fails the whole connection generation on it and
     * replays the pending request on the next one, so a retry would answer the same question
     * twice. Nor is a failing card taken away — see [settlesRequest].
     */
    private fun answerOutcome(
        result: RpcResult<JsonElement>,
        what: String,
        sessionId: String,
        forget: () -> Unit,
    ): QuestionOutcome {
        val outcome = when (result) {
            is RpcResult.Ok -> QuestionOutcome.Accepted
            is RpcResult.Err -> {
                log("$what failed for $sessionId: ${result.error.code}: ${result.error.message}")
                // The split is "did the host answer at all", not a list of codes. A carrier failure
                // carries a [TransportFailure] marker and nothing is known about the wait; anything
                // else reached the host and came back `ok:false`, so the refusal is reported with
                // the host's own code. Folding those into [QuestionOutcome.Unsent] is what made a
                // malformed envelope read as "could not reach the harness" and sent reporters to
                // debug their network for a protocol fault.
                if (TransportFailures.of(result.error) != null) {
                    QuestionOutcome.Unsent
                } else {
                    QuestionOutcome.Refused(result.error.code)
                }
            }
        }
        if (settlesRequest(outcome)) forget()
        return outcome
    }


    suspend fun selectModel(provider: String, model: String, reasoningEffort: String? = null) {
        val sid = currentSessionId.value ?: return
        val api = apiOrNull() ?: return
        val request = SessionSelectModelRequest(sid, provider, model, reasoningEffort)
        when (val r = api.sessionSelectModel(request)) {
            is RpcResult.Ok -> loadModels(sid)
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    /**
     * Full-text search across message content.
     *
     * This is the *optional* half of search, and most deployments do not have it: the shipped
     * `session-query-sqlite` row is configured `openAt: never`, which keeps exact reads, titles and
     * lineage traces working while `session.search` fails outright. So a failure here is a normal
     * condition, not a fault — it is latched into [contentSearchAvailable], never raised as a
     * connection error, and never retried for the life of the connection. The drawer's own title
     * and workspace filtering is unaffected and remains the primary way to find a session, exactly
     * as it is in the harness's web sidebar under the same configuration.
     */
    suspend fun search(query: String) {
        val trimmed = query.trim()
        // The host schema is query.trim().min(1).max(500); a blank or overlong query is an
        // invalid payload, so never send one — a blank query just clears the result set.
        if (trimmed.isEmpty()) {
            _searchResults.value = emptyList()
            return
        }
        if (!_contentSearchAvailable.value) return
        val api = apiOrNull() ?: run {
            // Disconnected: stale hits would otherwise sit under a query that never ran.
            _searchResults.value = emptyList()
            return
        }
        val bounded = trimmed.take(SESSION_SEARCH_QUERY_MAX_CHARS)
        when (val r = api.sessionSearch(bounded)) {
            is RpcResult.Ok -> _searchResults.value = r.value.items.map { it.sessionId to it.snippet }
            is RpcResult.Err -> {
                _contentSearchAvailable.value = false
                _searchResults.value = emptyList()
            }
        }
    }

    suspend fun fetchAttachment(attachmentId: String, sessionId: String? = currentSessionId.value, host: String? = activeHostKey): ByteArray? {
        val sid = sessionId ?: return null
        val api = host?.let(::apiForHost) ?: return null
        return when (val r = api.sessionAttachment(SessionAttachmentRequest(sid, attachmentId))) {
            is RpcResult.Ok -> runCatching { Base64.decode(r.value.data, Base64.DEFAULT) }.getOrNull()
            is RpcResult.Err -> {
                setConnectionError(r.error.message)
                null
            }
        }
    }

    suspend fun listSkills() {
        val sid = currentSessionId.value ?: return
        loadSkills(sid)
    }

    /**
     * Bring the open session's subagent list up to date.
     *
     * From harness 0.1.7 the list is the session's `subagentCatalog` projection, which arrives with
     * the rest of its projections and is republished on every change; there is nothing to fetch.
     * A 0.1.6 host publishes no such projection and answers `subagents/list` instead. On 0.1.7 that
     * call is a 404, which only means the snapshot carrying the catalog has not landed yet.
     */
    suspend fun refreshSubagents() {
        val sid = currentSessionId.value ?: return
        val hasCatalog = synchronized(lock) {
            currentId == sid && currentProjections.containsKey(SUBAGENT_CATALOG_PROJECTION)
        }
        if (hasCatalog) {
            synchronized(lock) { publishCatalogSubagentsLocked() }
            return
        }
        val api = apiOrNull() ?: return
        when (val r = api.subagentList(sid)) {
            is RpcResult.Ok -> synchronized(lock) {
                if (currentId == sid && !currentProjections.containsKey(SUBAGENT_CATALOG_PROJECTION)) {
                    _subagents.value = r.value.entries
                }
            }
            is RpcResult.Err -> if (r.error.code != CAPABILITY_UNAVAILABLE) setConnectionError(r.error.message)
        }
    }

    suspend fun interruptSubagent(childSessionId: String) {
        val sid = currentSessionId.value ?: return
        val api = apiOrNull() ?: return
        when (val r = api.subagentInterrupt(childSessionId = childSessionId, parentSessionId = sid)) {
            is RpcResult.Ok -> Unit
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun promptSubagent(childSessionId: String, text: String, delivery: String = "queue"): Boolean {
        val sid = currentSessionId.value ?: return false
        val api = apiOrNull() ?: return false
        val zone = TimeZone.getDefault().id
        val request = SubagentPromptRequest(
            requestId = newPromptRequestId(),
            parentSessionId = sid,
            childSessionId = childSessionId,
            mode = "continuable",
            delivery = delivery,
            content = listOf(PromptContentPart.Text(text)),
            clientTimeZone = zone,
        )
        return when (val r = api.subagentPrompt(request)) {
            is RpcResult.Ok -> true
            is RpcResult.Err -> { setConnectionError(r.error.message); false }
        }
    }

    suspend fun openSubagentTranscript(childSessionId: String) {
        val sid = currentSessionId.value ?: return
        val api = apiOrNull() ?: return
        val entry = _subagents.value.firstOrNull { subagentEntryId(it) == childSessionId }
        val mode = when (entry) {
            is SubagentListEntry.ChildOneShot -> "one-shot"
            is SubagentListEntry.ChildContinuable -> "continuable"
            else -> null
        }
        closeSubagentTranscript()
        _subagentMode.value = mode
        _subagentConversation.value = null
        _subagentHistoryState.value = ArchivedTranscriptState()
        if (mode == null) {
            log("subagent $childSessionId has no readable transcript mode")
            return
        }
        val host = activeHostKey ?: return
        val mux = muxForHost(host) ?: return
        lateinit var owner: ArchivedSubagentTranscript
        fun isCurrentFollow() = archivedSubagent === owner && activeHostKey == host &&
            currentSessionId.value == sid && muxForHost(host) === mux && mux.failure == null
        owner = ArchivedSubagentTranscript(historyCache, diskCacheStore, api, mux, host, sid,
            childSessionId, mode, ::isCurrentFollow) { failure ->
            if (shouldReportFollowFailure(failure, isCurrentFollow())) {
                log("subagent follow failed for $childSessionId", failure)
                setConnectionError(failure.message)
                if (failure is java.io.IOException || failure is android.database.sqlite.SQLiteException) {
                    connectionManager.reportStorageFailure(failure)
                }
            }
        }
        archivedSubagent = owner
        childFollowJob = scope.launch {
            try {
                coroutineScope {
                    launch {
                        owner.state.collect { state ->
                            synchronized(lock) {
                                if (isCurrentFollow()) {
                                    _subagentHistoryState.value = state
                                    _subagentConversation.value = state.snapshot
                                }
                            }
                        }
                    }
                    owner.follow()
                }
            } finally { owner.close() }
        }
    }

    private fun subagentReader(childId: String?): ArchivedSubagentTranscript? = archivedSubagent?.takeIf {
        childId == null || it.state.value.snapshot?.sessionId == childId
    }
    suspend fun loadSubagentOlder(childId: String? = null): Boolean = subagentReader(childId)?.loadOlder() ?: false
    suspend fun loadSubagentNewer(childId: String? = null): Boolean = subagentReader(childId)?.loadNewer() ?: false
    suspend fun showLatestSubagentHistory(childId: String? = null): Boolean = subagentReader(childId)?.showLatest() ?: false
    fun updateSubagentHistoryViewport(anchor: Long?, atTail: Boolean, childId: String? = null) {
        subagentReader(childId)?.updateViewport(anchor, atTail)
    }
    fun closeSubagentTranscript() {
        val previous = archivedSubagent
        archivedSubagent = null
        childFollowJob?.cancel()
        childFollowJob = null
        previous?.close()
    }


    suspend fun createWorkspace(path: String) {
        val api = apiOrNull() ?: return
        when (val r = api.workspaceCreate(WorkspaceCreateRequest(path))) {
            is RpcResult.Ok -> upsertWorkspace(r.value.workspace)
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun renameWorkspace(id: String, title: String) {
        val api = apiOrNull() ?: return
        when (val r = api.workspaceRename(WorkspaceRenameRequest(id, title))) {
            is RpcResult.Ok -> applyWorkspaceValue(r.value)
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun deleteWorkspace(id: String) {
        val api = apiOrNull() ?: return
        when (val r = api.workspaceDelete(WorkspaceDeleteRequest(id))) {
            is RpcResult.Ok -> removeWorkspace(id)
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun goalAction(action: String, objective: String? = null) {
        val sid = currentSessionId.value ?: return
        val api = apiOrNull() ?: return
        when (action) {
            "create" -> {
                val obj = objective
                if (obj.isNullOrBlank()) {
                    log("goal create requires an objective")
                    return
                }
                handleResult(
                    api.goalCreate(sid, buildJsonObject { put("objective", JsonPrimitive(obj)) }),
                )
            }
            "edit", "pause", "resume", "complete", "clear" -> {
                val ref = synchronized(lock) { goalRefFromProjectionLocked() }
                if (ref == null) {
                    log("goal $action requires a current goal (no goal projection)")
                    setConnectionError(noCurrentGoalMessage())
                    return
                }
                when (action) {
                    "edit" -> handleResult(
                        api.goalEdit(
                            sid,
                            ref,
                            buildJsonObject {
                                if (objective != null) put("objective", JsonPrimitive(objective))
                            },
                        ),
                    )
                    "pause" -> handleResult(api.goalPause(sid, ref))
                    "resume" -> handleResult(api.goalResume(sid, ref))
                    "complete" -> handleResult(api.goalComplete(sid, ref))
                    "clear" -> handleResult(api.goalClear(sid, ref))
                }
            }
            else -> log("unknown goal action $action")
        }
    }

    /**
     * Reload the session's slash-command catalog.
     *
     * A harness with no command registry answers 404 and a LAN-refused method answers 403; neither
     * is a connection fault, so this degrades the menu to its static fallback rather than raising a
     * failure banner on an otherwise healthy session.
     */
    suspend fun refreshCommands() {
        val sid = currentSessionId.value ?: return
        val api = apiOrNull() ?: return
        when (val r = api.commandsList(sid)) {
            is RpcResult.Ok -> synchronized(lock) {
                if (currentId == sid) {
                    _commands.value = r.value
                    _commandsAvailable.value = true
                }
            }
            is RpcResult.Err -> {
                _commandsAvailable.value = false
                _commands.value = emptyList()
                log("commands/list unavailable (${r.error.code}): ${r.error.message}")
            }
        }
    }

    /**
     * Run one complete slash-command line, optionally carrying the composer's images.
     *
     * The typert remote is the *only* command write path: `session.prompt` does not inspect its
     * content, so a leading-slash prompt reaches the model as ordinary user text (this store used
     * to send commands that way, which is why picking a permission preset made the agent shell out
     * to figure out what `/permission` meant). See `docs/PROTOCOL.md`.
     *
     * The remote answers `undefined` when the line parses to no registered command, and the wire
     * codec folds an absent `value` slot into an empty object — so the discriminator is the
     * presence of `commandId`, not the emptiness of the value.
     *
     * [attachments] must be empty unless the command's descriptor declares it takes them — see
     * `CommandDescriptor.acceptsAttachments`. A host that admits them but whose handler will not
     * use them (`/plan off`, `/goal pause`) answers with an ordinary error result, which is the
     * harness's own division of labour and not worth mirroring here.
     */
    suspend fun runCommand(
        line: String,
        attachments: List<CommandSubmitAttachment> = emptyList(),
        targetSessionId: String? = currentSessionId.value,
        targetHost: String? = activeHostKey,
    ): CommandOutcome {
        val sid = targetSessionId ?: return CommandOutcome.Failed("no open session")
        val api = apiForHost(targetHost) ?: return CommandOutcome.Failed("not connected")
        return when (val r = api.commandsExecute(sid, line, attachments)) {
            is RpcResult.Ok -> {
                val execution = r.value as? JsonObject
                val commandId = execution?.get("commandId")
                if (commandId == null || commandId is JsonNull) {
                    CommandOutcome.Unknown(line)
                } else {
                    val result = execution["result"] as? JsonObject
                    val text = (result?.get("text") as? JsonPrimitive)?.contentOrNull
                    if ((result?.get("kind") as? JsonPrimitive)?.contentOrNull == "error") {
                        CommandOutcome.Failed(text ?: "command failed")
                    } else {
                        CommandOutcome.Ok(text)
                    }
                }
            }
            is RpcResult.Err -> when (r.error.code) {
                // The attachments were refused, by the host or by the client's own guard. A
                // composer problem, so it must not raise the connection banner.
                ATTACHMENT_INVALID -> CommandOutcome.Failed(r.error.message)
                // No command gateway in this build (404) or the trust fence refused it (403).
                // Neither is a connection fault, so the menu retires rather than the session.
                "capability-unavailable", "forbidden" -> {
                    _commandsAvailable.value = false
                    _commands.value = emptyList()
                    log("commands/execute unavailable (${r.error.code}): ${r.error.message}")
                    CommandOutcome.Failed(r.error.message)
                }
                else -> {
                    setConnectionError(r.error.message)
                    CommandOutcome.Failed(r.error.message)
                }
            }
        }
    }

    /**
     * Switch the session's permission preset. The read side is the `permissions` projection, so
     * there is nothing to refresh — the harness pushes the new value back on a projection frame.
     */
    suspend fun setPermissionPreset(value: String): CommandOutcome {
        if (value == CUSTOM_PRESET) {
            return CommandOutcome.Failed("`$CUSTOM_PRESET` is a derived state, not a preset")
        }
        _pendingPermission.value = value
        val outcome = runCommand("/permission $value")
        if (outcome !is CommandOutcome.Ok) _pendingPermission.value = null
        return outcome
    }

    /**
     * Reload the host's plugin inventory.
     *
     * Host-scoped and read-only — the harness offers no way to change it from here. A deployment
     * that does not compose `@deepseek-ai/dsh-host-plugin-inventory` answers 404, which leaves the
     * flow null and takes the settings section off the screen: absence of the capability, not a
     * failure to report.
     */
    suspend fun refreshPlugins() {
        val api = apiOrNull() ?: return
        when (val r = api.pluginInventoryList()) {
            is RpcResult.Ok -> _plugins.value = r.value
            is RpcResult.Err -> {
                _plugins.value = null
                log("pluginInventory/list unavailable (${r.error.code}): ${r.error.message}")
            }
        }
    }

    /** Reload the agent-preset roster (host-scoped, so it survives session switches). */
    suspend fun refreshAgentPresets() {
        val api = apiOrNull() ?: return
        when (val r = api.agentPresetList()) {
            is RpcResult.Ok -> _agentPresets.value = r.value
            is RpcResult.Err -> log("agentPreset.list unavailable (${r.error.code}): ${r.error.message}")
        }
    }

    /**
     * Pin an agent preset onto the open session. The harness only allows this while the session is
     * blank; on a started session it answers `agent-preset-locked`, which surfaces as a normal error.
     */
    suspend fun selectAgentPreset(agentPreset: String): Boolean {
        val sid = currentSessionId.value ?: return false
        val api = apiOrNull() ?: return false
        return when (val r = api.agentPresetSelect(sid, agentPreset)) {
            is RpcResult.Ok -> {
                refreshSessions()
                true
            }
            is RpcResult.Err -> {
                setConnectionError(r.error.message)
                false
            }
        }
    }

    /**
     * Stream the open session's log ZIP into [sink]. The caller owns [sink] and should close it;
     * the harness answers this as a plain attachment download, not an RPC.
     */
    suspend fun exportSessionTo(sink: OutputStream, includeDescendants: Boolean = false): Boolean {
        val sid = currentSessionId.value ?: return false
        val api = apiOrNull() ?: return false
        val result = api.sessionExport(sid, includeDescendants) { _, _, body -> body.copyTo(sink) }
        return when (result) {
            is RpcResult.Ok -> true
            is RpcResult.Err -> {
                setConnectionError(result.error.message)
                false
            }
        }
    }

    suspend fun exportSessionUrl(): String? {
        val sid = currentSessionId.value ?: return null
        val host = connectionManager.state.value.host ?: return null
        return "${host.baseUrl}/api/session.export?sessionId=$sid"
    }

    /** Open a saved-host notification only after this generation's landing-session work finishes. */
    suspend fun openNotificationSession(hostId: String, hostBaseUrl: String, sessionId: String): Boolean {
        fun matchesHost(): Boolean = connectionManager.state.value.let {
            it.phase == ConnectionPhase.CONNECTED && it.host?.id == hostId && it.host.baseUrl == hostBaseUrl
        }
        if (!matchesHost()) return false
        val clientId = connectionManager.state.value.clientId ?: return false
        val ready = kotlinx.coroutines.withTimeoutOrNull(30_000) {
            kotlinx.coroutines.flow.combine(baselineReady, connectionManager.state) { baseline, state ->
                baseline == clientId || state.clientId != clientId || state.phase != ConnectionPhase.CONNECTED
            }.first { it }
        } ?: return false
        if (!ready || !matchesHost() || baselineReady.value != clientId ||
            connectionManager.state.value.clientId != clientId) return false
        if (!synchronized(lock) { sessionRows.containsKey(sessionId) }) return false
        openSession(sessionId, expectedClientId = clientId)
        return matchesHost() && connectionManager.state.value.clientId == clientId && currentSessionId.value == sessionId
    }

    /** True while [sessionId] is the session currently open in the foreground. */
    fun isSessionOpen(sessionId: String): Boolean = currentSessionId.value == sessionId

    // ------------------------------------------------------------------ internal helpers
    private fun goalRefFromProjectionLocked(): GoalRef? {
        return goalRefFromProjection(currentProjections["goal"]?.value)
    }

    private suspend fun loadSkills(sessionId: String) {
        val api = apiOrNull() ?: return
        when (val r = api.skillList(SkillListRequest(sessionId))) {
            is RpcResult.Ok -> synchronized(lock) {
                if (currentId == sessionId) _skills.value = r.value.skills
            }
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    private suspend fun loadModels(sessionId: String) {
        val api = apiOrNull() ?: return
        // Host-scoped now, not session-scoped: `session/modelCatalog` describes the generation's
        // routable models, and the session's own current selection comes from its projections.
        when (val r = api.sessionModelCatalog()) {
            is RpcResult.Ok -> synchronized(lock) {
                if (currentId == sessionId) _models.value = r.value
            }
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    /**
     * The tail slice of a history page the host over-delivered.
     *
     * `maxMessages` is a bound on *messages*, and not every harness build honours it — one was
     * observed answering a 60-message request with ~29k events (several MB), which folds slowly
     * enough to stall the first paint. Trimming is not as simple as keeping the last N events
     * though: a single assistant message can be hundreds of `assistant/chunk` deltas, so a fixed
     * event count yields a page with almost nothing readable in it. This walks back until it has
     * [HISTORY_PAGE_SIZE] actual messages, with a hard event ceiling so a pathological log still
     * cannot stall the fold. Anything trimmed is reported as `hasMore`, which is what
     * "Load older" is for.
     */
    /**
     * Whether one envelope is a genuine user prompt rather than harness-injected context.
     *
     * The harness tags a real prompt with `source.kind` of `user`; everything else
     * (`agent-instructions`, `skill-invocation`, `goal`, …) is context. Deliberately stricter than
     * `UserMessageNode.isInjectedContext`: this decides where the transcript *starts*, and an
     * untagged message must not be mistaken for the reader's own last turn — so unlike the node
     * predicate, an absent tag is a "no" here rather than a "yes".
     *
     * `user-rpc` is not a `source.kind` value — it is a key in the harness's `MessageSourceMap`
     * whose variant carries `kind: 'user'` itself, so `"user"` covers both paths.
     */
    private fun isRealUserPrompt(e: SessionEventEnvelope): Boolean =
        e.type == "user/message" &&
            ((e.data as? JsonObject)?.get("source") as? JsonObject)
                ?.get("kind")?.jsonPrimitive?.contentOrNull == "user"

    /**
     * Drop everything strictly before the oldest real user prompt in [envelopes].
     *
     * The harness opens a window of a fixed number of *events*, and a tool-heavy turn is mostly
     * events — so a session opened cold used to start mid-tool-call-loop, with the message that
     * prompted the work somewhere above the fold. Anchoring on the reader's last prompt instead
     * makes the first screen read as a conversation. No prompt in the window means no anchor, and
     * the list is returned unchanged rather than emptied.
     */
    private fun trimToUserPrompt(envelopes: List<SessionEventEnvelope>): List<SessionEventEnvelope> {
        val sorted = envelopes.sortedBy { it.seq }
        val index = sorted.indexOfFirst { isRealUserPrompt(it) }
        return if (index < 0) sorted else sorted.subList(index, sorted.size)
    }

    private fun historyTail(entries: List<SessionEventEnvelope>): List<SessionEventEnvelope> {
        if (entries.size <= MAX_PAGE_EVENTS) return entries
        var messages = 0
        var index = entries.lastIndex
        while (index > 0 && entries.size - index < MAX_PAGE_EVENTS) {
            if (entries[index].type in SURFACE_EVENT_TYPES) {
                messages++
                if (messages >= HISTORY_PAGE_SIZE) break
            }
            index--
        }
        return entries.subList(index.coerceAtLeast(0), entries.size)
    }

    private fun subagentEntryId(entry: SubagentListEntry): String? = when (entry) {
        is SubagentListEntry.ChildExternal -> entry.id
        is SubagentListEntry.ChildOneShot -> entry.id
        is SubagentListEntry.ChildContinuable -> entry.id
        is SubagentListEntry.Diagnostic -> entry.id
        is UnknownSubagentListEntry -> null
    }

    /**
     * Whether this connection's harness carries attachments on a slash command.
     *
     * Always true from harness 0.1.2: `commands/execute` declares the parameter unconditionally,
     * and the shape-derived capability check this used to perform depended on `host.describe`,
     * which no longer exists. Kept as a property so the composer's adjudication has one place to
     * consult if a future release makes it conditional again.
     */
    val commandAttachmentsSupported: Boolean get() = connectionManager.connectedApi != null

    suspend fun refreshPermissionCatalog() {
        val epoch = ++permissionCatalogEpoch
        val key = activeHostKey
        val api = apiForHost(key) ?: return
        // Not cleared before the read. Blanking first made the permission chip lose its options for
        // the length of a round trip on every baseline, which reads as the chip breaking rather
        // than as a refresh. The epoch and host guards below already stop a slow answer from
        // overwriting a newer host's catalog, which is what the clear was standing in for.
        val result = api.permissionCatalog()
        if (epoch == permissionCatalogEpoch && key == activeHostKey) {
            permissionCatalog.value = (result as? RpcResult.Ok)?.value
        }
    }

    suspend fun unarchiveSession(sessionId: String): Boolean {
        val key = activeHostKey
        val result = apiForHost(key)?.workspaceUnarchiveSession(sessionId) ?: return false
        if (key != activeHostKey) return false
        return when (result) {
            is RpcResult.Ok -> { setArchived(result.value.archivedSessionIds); refreshSessions(); true }
            is RpcResult.Err -> { setConnectionError(result.error.message); false }
        }
    }

    private fun apiOrNull(): DshApiClient? {
        val api = connectionManager.connectedApi
        if (api == null) log("not connected — ignoring request")
        return api
    }

    private fun <T> handleResult(result: RpcResult<T>) {
        when (result) {
            is RpcResult.Ok -> Unit
            is RpcResult.Err -> setConnectionError(result.error.message)
        }
    }

    private fun log(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(TAG, message, throwable) else Log.w(TAG, message)
    }

    private companion object {
        const val TAG = "SessionStore"

        /**
         * The host's refusal of a prompt's or command's attachments (harness 0.1.3; it was
         * `attachment-error` through 0.1.2). Every business code is namespaced now.
         */
        const val ATTACHMENT_INVALID = "session/attachment-invalid"

        /** Harness 0.1.7's refusal to archive a session with running work; see [archiveSession]. */
        const val SESSION_ACTIVE = "workspace/session-active"

        /** Minted by the client from a 404: no route claimed the endpoint. */
        const val CAPABILITY_UNAVAILABLE = "capability-unavailable"

        /** Largest file the base64 Remote fallback will carry; anything bigger needs the route. */
        const val MAX_ENCODED_UPLOAD_BYTES = 20L * 1024 * 1024
        const val HISTORY_PAGE_SIZE = 60

        /** Ceiling on events folded per page, whatever the host sends. */
        const val MAX_PAGE_EVENTS = 4_000

        /** The event types that produce a visible message; everything else frames them. */
        val SURFACE_EVENT_TYPES = setOf("user/message", "assistant/message", "tool/result")

        /** Host-side wire bound for `session.search` (SESSION_SEARCH_QUERY_MAX_CHARS). */
        const val SESSION_SEARCH_QUERY_MAX_CHARS = 500

        /**
         * Floor on the gap between transcript rebuilds while a turn streams.
         *
         * One display frame. Nothing is gained by republishing a transcript faster than it can be
         * drawn, and the deltas of a single turn arrive far faster than that.
         */
        const val REBUILD_INTERVAL_MS = 50L
    }
}
