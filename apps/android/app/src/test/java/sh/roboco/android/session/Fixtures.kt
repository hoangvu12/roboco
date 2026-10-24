package sh.roboco.android.session

import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.ChatConfig
import uniffi.roboco_core.ComposerState
import uniffi.roboco_core.Connectivity
import uniffi.roboco_core.ConnectivityState
import uniffi.roboco_core.ContextUsage
import uniffi.roboco_core.EngineDiagnostics
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.EngineView
import uniffi.roboco_core.HostCapabilities
import uniffi.roboco_core.HostInfo
import uniffi.roboco_core.LiveStatus
import uniffi.roboco_core.ProjectRef
import uniffi.roboco_core.PullRequest
import uniffi.roboco_core.PullRequestState
import uniffi.roboco_core.RoomState
import uniffi.roboco_core.SessionRow
import uniffi.roboco_core.SessionLimit
import uniffi.roboco_core.TranscriptView
import uniffi.roboco_core.NoHandle

/**
 * Fixture builders for the session tests (android-ui-parity #51):
 * the generated UniFFI records with neutral defaults for every field
 * the ticket does not exercise. The records are plain Kotlin data
 * classes whose initialisation touches no JNA, so these tests run on
 * the plain JVM (the same property `LiveCountsTest` relies on).
 */

/** A composer state with only the fields [baseChrome] reads set apart. */
internal fun composerState(
    title: String = "T",
    hostName: String? = null,
    deviceId: String = "dev",
    turnRunning: Boolean = false,
    midTurnSteering: Boolean? = null,
    transferProgress: Double? = null,
    queueError: String? = null,
    chatId: String = "chat-a",
    contextUsage: ContextUsage? = null,
    limit: SessionLimit? = null,
    scheduledResumeFireAtMs: Long? = null,
): ComposerState = ComposerState(
    chatId = chatId,
    revision = 1uL,
    title = title,
    host = HostInfo(
        deviceId = deviceId,
        name = hostName,
        online = true,
        capabilities = HostCapabilities(
            messageQueue = true,
            queueActions = true,
            queueAttachments = true,
            cleanAttachmentText = true,
            queueEditLease = true,
            queuedAttachments = true,
            midTurnSteering = midTurnSteering,
        ),
    ),
    live = LiveStatus(
        indicator = ChatIndicator.IDLE,
        turnRunning = turnRunning,
        workingSinceMs = null,
        streaming = false,
        canInterrupt = false,
    ),
    queue = emptyList(),
    pendingSends = emptyList(),
    sendState = null,
    deliveryDegraded = false,
    room = RoomState(connected = true, retryAtMs = null, degraded = false),
    transferProgress = transferProgress,
    openInput = null,
    queueError = queueError,
    lastSubmittedMessageId = null,
    contextUsage = contextUsage,
    limit = limit,
    scheduledResumeFireAtMs = scheduledResumeFireAtMs,
)

/** A session row with only the fields [baseChrome] and the chips read set apart. */
internal fun sessionRow(
    projectName: String? = null,
    harnessLabel: String? = null,
    id: String = "chat-a",
    pinned: Boolean = false,
    harness: String? = null,
    modelLabel: String? = null,
    reasoning: String? = null,
    branch: String? = null,
    pullRequest: PullRequest? = null,
): SessionRow = SessionRow(
    id = id,
    engineId = "engine:v1:alpha",
    revision = 1uL,
    title = "Session",
    hasTitle = true,
    preview = null,
    project = projectName?.let { ProjectRef(id = "project-1", name = it, colorIndex = 0u) },
    deviceId = "device",
    deviceName = null,
    deviceOnline = true,
    engineOnline = true,
    harness = harness,
    harnessLabel = harnessLabel,
    model = null,
    modelLabel = modelLabel,
    reasoning = reasoning,
    branch = branch,
    cwd = null,
    indicator = ChatIndicator.IDLE,
    hostIndicator = ChatIndicator.IDLE,
    workingSinceMs = null,
    lastActivityMs = 0L,
    timeLabel = "now",
    createdAtMs = 0L,
    unseen = false,
    archived = false,
    pinned = pinned,
    sectionId = null,
    pullRequest = pullRequest,
    sendState = null,
    parentChatId = null,
)

/** A PR fixture for the chip tests (78). */
internal fun pullRequest(
    number: Long,
    state: PullRequestState,
): PullRequest = PullRequest(
    provider = "github",
    number = number.toULong(),
    title = "Fixture PR",
    url = "https://github.com/roboco/roboco/pull/$number",
    state = state,
    baseRef = "main",
    headRef = "feature",
)

/** An engine view (73/79 read its state and device id); the state defaults to `Connected`. */
internal fun engineView(
    engineId: String = "engine:v1:alpha",
    deviceId: String? = "dev",
    state: EngineState = EngineState.Connected,
): EngineView =
    EngineView(
        engineId = engineId,
        name = "Vu-PC",
        host = "Vu-PC",
        baseUrl = "https://engine.invalid",
        deviceId = deviceId,
        platform = "android",
        version = "1.0.0",
        state = state,
        lastError = null,
        retryAtMs = null,
        lastConnectedMs = null,
        capabilities = emptyList(),
        notice = null,
        fromCache = false,
        diagnostics = EngineDiagnostics(
            repairedRows = 0u,
            skippedRows = 0u,
            lastDecodeError = null,
            missingStreams = emptyList(),
            generation = 0uL,
        ),
    )

/** The payload of a `ConnectivityChanged` event. */
internal fun connectivity(state: ConnectivityState = ConnectivityState.CONNECTED): Connectivity =
    Connectivity(state = state, retryAtMs = null, lastFailure = null, degradedChats = emptyList())

/**
 * The fake FFI seam: mutable return values, a call log and a
 * `composerCalls` counter. `composerFailures` makes the next N
 * `composer()` calls throw *after being counted*, so the failure paths
 * are observable. `attachTranscriptResult` exercises the logged
 * `false` branch of [SessionController.attach].
 */
internal class FakeSessionCore(
    override val chatId: String = "chat-a",
    /** 84's draft key reads this; a UUID-shaped engine id, like the real `PairedEngine.engineId`. */
    override val engineId: String = "11111111-1111-4111-8111-111111111111",
) : SessionCore {
    var composerResult: ComposerState = composerState(chatId = chatId)
    var sessionRowResult: SessionRow? = null

    /** Ticket 89: the chat's local config; `null` = no config yet. */
    var sessionConfigResult: ChatConfig? = null
    var engineResult: EngineView? = null
    var attachTranscriptResult: Boolean = true
    var composerFailures: Int = 0
    /** When > 0, the next that many attachTranscript() calls throw. */
    var attachTranscriptFailures: Int = 0

    val callLog = mutableListOf<String>()

    var composerCalls: Int = 0
        private set

    override fun composer(): ComposerState {
        callLog += "composer"
        composerCalls++
        if (composerFailures > 0) {
            composerFailures--
            throw RuntimeException("composer boom")
        }
        return composerResult
    }

    override fun sessionRow(): SessionRow? {
        callLog += "sessionRow"
        return sessionRowResult
    }

    override fun sessionConfig(): ChatConfig? {
        callLog += "sessionConfig"
        return sessionConfigResult
    }

    override fun engine(): EngineView? {
        callLog += "engine"
        return engineResult
    }

    override fun attachTranscript(view: TranscriptView): Boolean {
        callLog += "attachTranscript"
        if (attachTranscriptFailures > 0) {
            attachTranscriptFailures--
            throw RuntimeException("attach boom")
        }
        return attachTranscriptResult
    }

    override fun setViewAttached(attached: Boolean) {
        callLog += "setViewAttached($attached)"
    }

    override fun markSeen() {
        callLog += "markSeen"
    }
}

/** Two identity-distinct transcript views without a Rust engine behind them. */
internal fun fakeTranscriptView(): TranscriptView = TranscriptView(NoHandle)
