package sh.roboco.android.composer

import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.ChatConfig
import uniffi.roboco_core.ComposerState
import uniffi.roboco_core.ContextUsage
import uniffi.roboco_core.HostCapabilities
import uniffi.roboco_core.HostInfo
import uniffi.roboco_core.LiveStatus
import uniffi.roboco_core.ModelInfo
import uniffi.roboco_core.ModelOption
import uniffi.roboco_core.ModelOptionChoice
import uniffi.roboco_core.PullRequest
import uniffi.roboco_core.PullRequestState
import uniffi.roboco_core.RoomState
import uniffi.roboco_core.SandboxLevel
import uniffi.roboco_core.SessionRow

/**
 * Fixtures shared by ticket 89's three test classes (the JVM rules test,
 * the Robolectric UI test and the Roborazzi goldens) and modelled on the
 * session package's `Fixtures.kt` conventions: the uniffi records are
 * plain Kotlin data classes whose construction touches no JNA, and every
 * FFI seam goes through a fake.
 */

/** The claude-code ladder (`catalog.rs:133-143`). */
val FULL_LADDER = listOf("low", "medium", "high", "xhigh", "max", "ultracode", "ultrathink")

/** Opus 4.7 / Sonnet 5's ladder (no `ultracode`). */
val CLAUDE_XHIGH = listOf("low", "medium", "high", "xhigh", "max", "ultrathink")

fun chipMenuModel(
    id: String,
    label: String,
    description: String?,
    reasoningLevels: List<String>,
    defaultReasoning: String?,
    options: List<ModelOption> = emptyList(),
): ModelInfo = ModelInfo(
    id = id,
    label = label,
    description = description,
    reasoningLevels = reasoningLevels,
    options = options,
    defaultReasoning = defaultReasoning,
)

fun chipMenuContextWindow(): ModelOption = ModelOption(
    id = "contextWindow",
    label = "Context Window",
    choices = listOf(
        ModelOptionChoice(id = "200k", label = "200K"),
        ModelOptionChoice(id = "1m", label = "1M"),
    ),
    defaultChoice = "200k",
)

fun chipMenuToggle(id: String, label: String): ModelOption = ModelOption(
    id = id,
    label = label,
    choices = listOf(
        ModelOptionChoice(id = "off", label = "Off"),
        ModelOptionChoice(id = "on", label = "On"),
    ),
    defaultChoice = "off",
)

/**
 * The claude-code fallback catalog (`mc::catalog::fallback_models`, the
 * static fallback both the gallery's M1 panel and the iOS UI tests use),
 * in its order, with the real ladders and options.
 */
fun claudeFallbackModels(): List<ModelInfo> = listOf(
    chipMenuModel(
        id = "claude-fable-5-1",
        label = "Fable 5.1",
        description = "Most intelligent model for building agents",
        reasoningLevels = FULL_LADDER,
        defaultReasoning = "high",
        options = listOf(chipMenuContextWindow()),
    ),
    chipMenuModel(
        id = "claude-fable-5",
        label = "Fable 5",
        description = "Previous generation Fable",
        reasoningLevels = FULL_LADDER,
        defaultReasoning = "high",
        options = listOf(chipMenuContextWindow()),
    ),
    chipMenuModel(
        id = "claude-opus-5",
        label = "Opus 5",
        description = "Powerful model for complex work",
        reasoningLevels = FULL_LADDER,
        defaultReasoning = "high",
        options = listOf(chipMenuContextWindow(), chipMenuToggle("fastMode", "Fast Mode")),
    ),
    chipMenuModel(
        id = "claude-opus-4-8",
        label = "Opus 4.8",
        description = "Previous generation Opus",
        reasoningLevels = FULL_LADDER,
        defaultReasoning = "high",
        options = listOf(chipMenuToggle("fastMode", "Fast Mode")),
    ),
    chipMenuModel(
        id = "claude-opus-4-7",
        label = "Opus 4.7",
        description = "Older generation Opus",
        reasoningLevels = CLAUDE_XHIGH,
        defaultReasoning = "high",
        options = listOf(chipMenuToggle("fastMode", "Fast Mode")),
    ),
    chipMenuModel(
        id = "claude-sonnet-5",
        label = "Sonnet 5",
        description = "Balanced speed and intelligence",
        reasoningLevels = CLAUDE_XHIGH,
        defaultReasoning = "high",
        options = listOf(chipMenuContextWindow()),
    ),
    chipMenuModel(
        id = "claude-haiku-4-5",
        label = "Haiku 4.5",
        description = "Fastest model for everyday tasks",
        reasoningLevels = emptyList(),
        defaultReasoning = null,
        options = listOf(chipMenuToggle("thinking", "Thinking")),
    ),
)

/** The pure-Kotlin `reasoning_label` copy (the fake's label seam). */
fun kotlinReasoningLabel(level: String): String = when (level) {
    "minimal" -> "Minimal"
    "low" -> "Low"
    "medium" -> "Medium"
    "high" -> "High"
    "xhigh" -> "X-High"
    "max" -> "Max"
    "ultra" -> "Ultra"
    "ultracode" -> "Ultracode"
    "ultrathink" -> "Ultrathink"
    else -> level.replaceFirstChar(Char::uppercase)
}

/**
 * A `ComposerState` whose only set field is the host's device id (the
 * menus' snapshot reads `composer.host.deviceId` and nothing else).
 */
fun chipMenuComposerState(
    deviceId: String = "device-1",
    chatId: String = "chat-a",
    contextUsage: ContextUsage? = null,
    providerSwitch: Boolean = false,
    turnRunning: Boolean = false,
): ComposerState = ComposerState(
    chatId = chatId,
    revision = 1uL,
    title = "Session",
    host = HostInfo(
        deviceId = deviceId,
        name = "Vu-PC",
        online = true,
        capabilities = HostCapabilities(
            messageQueue = true,
            queueActions = true,
            queueAttachments = true,
            cleanAttachmentText = true,
            queueEditLease = true,
            queuedAttachments = true,
            midTurnSteering = null,
            providerSwitch = providerSwitch,
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
    transferProgress = null,
    openInput = null,
    queueError = null,
    lastSubmittedMessageId = null,
    contextUsage = contextUsage,
    limit = null,
    scheduledResumeFireAtMs = null,
)

/**
 * A `SessionRow` with the fields the menus and the chips read: harness,
 * model / modelLabel, reasoning, branch and the optional PR.
 */
fun chipMenuSessionRow(
    id: String = "chat-a",
    harness: String? = "claude-code",
    model: String? = "claude-fable-5",
    modelLabel: String? = "Fable 5",
    reasoning: String? = "xhigh",
    branch: String? = null,
    pullRequest: PullRequest? = null,
    deviceId: String = "device-1",
): SessionRow = SessionRow(
    id = id,
    engineId = "engine:v1:alpha",
    revision = 1uL,
    title = "Session",
    hasTitle = true,
    preview = null,
    project = null,
    deviceId = deviceId,
    deviceName = null,
    deviceOnline = true,
    engineOnline = true,
    harness = harness,
    harnessLabel = harness?.let { "Claude Code" },
    model = model,
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
    pinned = false,
    sectionId = null,
    pullRequest = pullRequest,
    sendState = null,
    parentChatId = null,
)

/** The PR fixture the chip + menu tests share. */
fun chipMenuPullRequest(
    title: String = "Fixture PR",
    url: String = "https://github.com/hoangvu12/roboco/pull/1234",
): PullRequest = PullRequest(
    provider = "github",
    number = 1234uL,
    title = title,
    url = url,
    state = PullRequestState.OPEN,
    baseRef = "main",
    headRef = "feature",
)

/** A chat config with only the fields a test sets. */
fun chatConfig(
    harness: String = "claude-code",
    model: String? = null,
    reasoning: String? = null,
    modelOptions: Map<String, String> = emptyMap(),
    sandbox: SandboxLevel = SandboxLevel.WORKSPACE_WRITE,
): ChatConfig = ChatConfig(
    harness = harness,
    model = model,
    reasoning = reasoning,
    modelOptions = modelOptions,
    sandbox = sandbox,
)
