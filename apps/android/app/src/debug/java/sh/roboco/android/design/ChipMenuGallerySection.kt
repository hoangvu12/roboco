package sh.roboco.android.design

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
import java.util.Locale
import kotlinx.coroutines.delay
import sh.roboco.android.composer.ChipIcon
import sh.roboco.android.composer.ChipMenuCore
import sh.roboco.android.composer.ChipStrip
import sh.roboco.android.composer.ChipTint
import sh.roboco.android.composer.ComposerChip
import sh.roboco.android.composer.SessionChipMenus
import sh.roboco.android.composer.rememberChipMenuPlatform
import sh.roboco.android.core.Face
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.GlassMenuPanel
import sh.roboco.android.design.glass.MenuElement
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.design.glass.MenuLine
import sh.roboco.android.design.glass.flattenLevel
import sh.roboco.android.design.uiStyle
import sh.roboco.android.session.ChromeInput
import sh.roboco.android.session.ContextReading
import sh.roboco.android.session.SessionChrome
import sh.roboco.android.session.contextChip
import sh.roboco.android.session.contextUsageCard
import uniffi.roboco_core.ChatConfig
import uniffi.roboco_core.ChatIndicator
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
 * The "Session chip menus" page of the debug design gallery
 * (android-ui-parity #89), registered by ticket 22's registry as
 * `GallerySection("Session chip menus", GalleryLayout.Fill) { ChipMenuGallerySection() }`
 * (slug `session_chip_menus`).
 *
 * The section wraps itself in a [GlassMenuHost] over [GalleryBackdrop]
 * (ticket 15's host + the haze source the panels blur) and scrolls its
 * own content:
 *
 * - the **live demo** — ticket 59's [ChipStrip] (`model` / `effort` /
 *   `pr`) wired to a real [SessionChipMenus] over a fake
 *   [ChipMenuCore]: `listModels` delays 1.5 s (the "Loading…" row),
 *   a pick updates the chip titles through the optimistic override, the
 *   "Fail writes" toggle makes the write throw, and the caption under
 *   the strip shows the last write's outcome. The real
 *   [rememberChipMenuPlatform] opens the PR's Custom Tab. The `context`
 *   chip (android-next #01) opens the read-only details card.
 * - the **static panels** M1 (model, resolved), M2 (model, loading), E
 *   (reasoning effort) and P (pull request), each a stateless
 *   [GlassMenuPanel] of the flattened lines — the same fixtures the
 *   Roborazzi goldens capture (`ChipMenuScreenshotTest`) — plus the
 *   context cards C1 (measured) and C2 (window not reported).
 *
 * No Material controls anywhere: plain `BasicText` and rows.
 */
@Composable
fun ChipMenuGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val haze = rememberHazeState()

    // The fake chat state: snapshot-backed so a pick recomposes the chips.
    var config by remember { mutableStateOf(galleryChatConfig(model = "claude-opus-5", reasoning = "high")) }
    var modelLabel by remember { mutableStateOf("Opus 5") }
    var failWrites by remember { mutableStateOf(false) }
    var lastWrite by remember { mutableStateOf("no write yet") }

    val core = remember {
        GalleryChipMenuCore(
            config = { config },
            modelLabel = { modelLabel },
            onConfig = { next ->
                config = next
                modelLabel = galleryModels().firstOrNull { it.id == next.model }?.label ?: modelLabel
            },
            failWrites = { failWrites },
        )
    }
    val menus = remember(core) { SessionChipMenus(core, locale = { Locale.US }) }
    menus.onSettingResult = { t ->
        lastWrite = if (t == null) "Saved" else "Couldn't change session settings: ${t.message}"
    }
    // The controller-refresh side of the contract: one fill with the
    // snapshot the provider reads (row + config + host device).
    menus.fill(
        SessionChrome(),
        ChromeInput(
            chatId = "gallery-session",
            composer = galleryComposerState(),
            row = core.sessionRow(),
            engine = null,
            config = config,
        ),
    )

    val platform = rememberChipMenuPlatform()
    val chips = listOf(
        ComposerChip(id = "model", title = modelLabel, icon = ChipIcon.Brand("claude-code")),
        ComposerChip(
            id = "effort",
            title = galleryReasoningLabel(config.reasoning ?: "high"),
            symbol = "gauge.with.dots.needle.67percent",
        ),
        ComposerChip(id = "pr", title = "1234", icon = ChipIcon.PullRequest, tint = ChipTint.PrOpen),
        contextChip(GALLERY_CONTEXT_MEASURED, Locale.US),
    )

    GlassMenuHost(hazeState = haze) {
        Box(Modifier.fillMaxSize()) {
            GalleryBackdrop(Modifier.fillMaxSize())
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ChipMenuLabel(
                    "Session chip menus",
                    uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text),
                )
                ChipMenuLabel(
                    "Model, Reasoning effort and Pull request — the composer chips' menus " +
                        "(78's chips, 15's panel, 59's anchoring, this ticket's content).",
                    uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                )

                // --------------------------------------------- live demo
                ChipMenuLabel(
                    "Live: tap a chip — the catalog loads 1.5 s, a pick updates the chip, " +
                        "\"Fail writes\" fails the write.",
                    uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                )
                ChipStrip(
                    chips = chips,
                    chipMenus = mapOf(
                        "model" to { menus.menu("model", platform) },
                        "effort" to { menus.menu("effort", platform) },
                        "pr" to { menus.menu("pr", platform) },
                        "context" to { menus.menu("context", platform) },
                    ),
                    canWrite = true,
                    onChipTap = {},
                    modifier = Modifier.width(376.dp),
                )
                ChipMenuLabel(
                    lastWrite,
                    uiStyle(Face.Mono, typeScale.fixed(12f), palette.secondary),
                )
                ChipMenuToggleRow("Fail writes", if (failWrites) "on" else "off") { failWrites = !failWrites }

                // ------------------------------------------- static panels
                ChipMenuLabel(
                    "Static fixtures (the Roborazzi goldens):",
                    uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                )
                ChipMenuStaticPanel("M1 — model, resolved", chipMenuM1Lines())
                ChipMenuStaticPanel("M2 — model, loading", chipMenuM2Lines())
                ChipMenuStaticPanel("E — reasoning effort", chipMenuELines())
                ChipMenuStaticPanel("P — pull request", chipMenuPLines())
                ChipMenuStaticPanel("C1 — context card", chipMenuContextLines(GALLERY_CONTEXT_MEASURED))
                ChipMenuStaticPanel("C2 — context, window not reported", chipMenuContextLines(GALLERY_CONTEXT_NO_WINDOW))
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** One static fixture panel with its caption. */
@Composable
private fun ChipMenuStaticPanel(caption: String, lines: List<MenuLine>) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ChipMenuLabel(caption, uiStyle(Face.Sans, typeScale.fixed(12f), palette.secondary))
        GlassMenuPanel(
            lines = lines,
            onRow = {},
            onSubmenu = {},
            onBack = {},
        )
    }
}

@Composable
private fun ChipMenuLabel(text: String, style: TextStyle) {
    BasicText(text, style = style)
}

/** One gallery toggle row: label left, current value in accent right. */
@Composable
private fun ChipMenuToggleRow(label: String, value: String, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClickLabel = label, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.text))
        Spacer(Modifier.weight(1f))
        BasicText(value, style = uiStyle(Face.SansMedium, typeScale.fixed(15f), palette.accent))
    }
}

// ---------------------------------------------------------------------------
// The fixtures — M1, M2, E, P — shared with the Roborazzi goldens
// (ChipMenuScreenshotTest) and the @Preview block below.
// ---------------------------------------------------------------------------

/** The claude-code ladder (`catalog.rs:133-143`). */
private val GALLERY_FULL_LADDER = listOf("low", "medium", "high", "xhigh", "max", "ultracode", "ultrathink")

/** Opus 4.7 / Sonnet 5's ladder (no `ultracode`). */
private val GALLERY_CLAUDE_XHIGH = listOf("low", "medium", "high", "xhigh", "max", "ultrathink")

private fun galleryOption(id: String, label: String, choices: List<Pair<String, String>>, default: String): ModelOption =
    ModelOption(
        id = id,
        label = label,
        choices = choices.map { (choiceId, choiceLabel) -> ModelOptionChoice(id = choiceId, label = choiceLabel) },
        defaultChoice = default,
    )

private fun galleryContextWindow(): ModelOption =
    galleryOption("contextWindow", "Context Window", listOf("200k" to "200K", "1m" to "1M"), "200k")

private fun galleryToggle(id: String, label: String): ModelOption =
    galleryOption(id, label, listOf("off" to "Off", "on" to "On"), "off")

/**
 * The claude-code fallback catalog, M1's rows (`mc::catalog::fallback_models`,
 * order, labels, descriptions, ladders and options verbatim).
 */
internal fun galleryModels(): List<ModelInfo> = listOf(
    ModelInfo(
        id = "claude-fable-5-1", label = "Fable 5.1", description = "Most intelligent model for building agents",
        reasoningLevels = GALLERY_FULL_LADDER, options = listOf(galleryContextWindow()), defaultReasoning = "high",
    ),
    ModelInfo(
        id = "claude-fable-5", label = "Fable 5", description = "Previous generation Fable",
        reasoningLevels = GALLERY_FULL_LADDER, options = listOf(galleryContextWindow()), defaultReasoning = "high",
    ),
    ModelInfo(
        id = "claude-opus-5", label = "Opus 5", description = "Powerful model for complex work",
        reasoningLevels = GALLERY_FULL_LADDER,
        options = listOf(galleryContextWindow(), galleryToggle("fastMode", "Fast Mode")),
        defaultReasoning = "high",
    ),
    ModelInfo(
        id = "claude-opus-4-8", label = "Opus 4.8", description = "Previous generation Opus",
        reasoningLevels = GALLERY_FULL_LADDER, options = listOf(galleryToggle("fastMode", "Fast Mode")),
        defaultReasoning = "high",
    ),
    ModelInfo(
        id = "claude-opus-4-7", label = "Opus 4.7", description = "Older generation Opus",
        reasoningLevels = GALLERY_CLAUDE_XHIGH, options = listOf(galleryToggle("fastMode", "Fast Mode")),
        defaultReasoning = "high",
    ),
    ModelInfo(
        id = "claude-sonnet-5", label = "Sonnet 5", description = "Balanced speed and intelligence",
        reasoningLevels = GALLERY_CLAUDE_XHIGH, options = listOf(galleryContextWindow()), defaultReasoning = "high",
    ),
    ModelInfo(
        id = "claude-haiku-4-5", label = "Haiku 4.5", description = "Fastest model for everyday tasks",
        reasoningLevels = emptyList(), options = listOf(galleryToggle("thinking", "Thinking")), defaultReasoning = null,
    ),
)

/** The pure Kotlin `reasoning_label` copy (the fake's label seam — no FFI in the gallery). */
internal fun galleryReasoningLabel(level: String): String = when (level) {
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

private fun galleryChatConfig(model: String, reasoning: String?): ChatConfig = ChatConfig(
    harness = "claude-code",
    model = model,
    reasoning = reasoning,
    modelOptions = emptyMap(),
    sandbox = SandboxLevel.WORKSPACE_WRITE,
)

private fun galleryComposerState(): ComposerState = ComposerState(
    chatId = "gallery-session",
    revision = 1uL,
    title = "Session",
    host = HostInfo(
        deviceId = "device-1",
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
        ),
    ),
    live = LiveStatus(
        indicator = ChatIndicator.IDLE,
        turnRunning = false,
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
    contextUsage = ContextUsage(tokens = GALLERY_CONTEXT_MEASURED.tokens, window = GALLERY_CONTEXT_MEASURED.window),
    limit = null,
    scheduledResumeFireAtMs = null,
)

internal fun gallerySessionRow(config: ChatConfig, modelLabel: String): SessionRow = SessionRow(
    id = "gallery-session",
    engineId = "engine:v1:alpha",
    revision = 1uL,
    title = "Session",
    hasTitle = true,
    preview = null,
    project = null,
    deviceId = "device-1",
    deviceName = null,
    deviceOnline = true,
    engineOnline = true,
    harness = "claude-code",
    harnessLabel = "Claude Code",
    model = config.model,
    modelLabel = modelLabel,
    reasoning = config.reasoning,
    branch = null,
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
    pullRequest = PullRequest(
        provider = "github",
        number = 1234uL,
        title = "Android: session chip menus for model, reasoning effort and pull request with a long title",
        url = "https://github.com/hoangvu12/roboco/pull/1234",
        state = PullRequestState.OPEN,
        baseRef = "main",
        headRef = "feature",
    ),
    sendState = null,
    parentChatId = null,
)

/**
 * The live demo's fake [ChipMenuCore]: `listModels` delays 1.5 s (the
 * "Loading…" row), `setSessionConfig` applies the optimistic override
 * (the caller flips the snapshot state the chips read) and throws when
 * "Fail writes" is on. No FFI anywhere.
 */
private class GalleryChipMenuCore(
    private val config: () -> ChatConfig,
    private val modelLabel: () -> String,
    private val onConfig: (ChatConfig) -> Unit,
    private val failWrites: () -> Boolean,
) : ChipMenuCore {
    override fun sessionRow(): SessionRow = gallerySessionRow(config(), modelLabel())

    override fun sessionConfig(): ChatConfig = config()

    override suspend fun listModels(deviceId: String, harness: String): List<ModelInfo> {
        delay(1500)
        return galleryModels()
    }

    override fun fallbackModels(harness: String): List<ModelInfo> = galleryModels()

    override fun reasoningLabel(level: String): String = galleryReasoningLabel(level)

    override suspend fun setSessionConfig(config: ChatConfig) {
        if (failWrites()) throw IllegalStateException("Engine is offline; reconnecting")
        onConfig(config)
    }
}

// ---------------------------------------------------------------------------
// The static menus → flattened lines (the panels and the goldens).
// ---------------------------------------------------------------------------

/** M1 — the model menu, resolved: Opus 5 checked. */
fun chipMenuM1Lines(): List<MenuLine> = flattenLevel(
    "Model",
    galleryModels().map { m ->
        MenuItem(
            title = m.label,
            subtitle = m.description,
            checked = m.id == "claude-opus-5",
            testTag = "chip-menu-model-${m.id}",
        ) {}
    },
    resolved = { null },
    isRoot = true,
    backTitle = null,
)

/** M2 — the model menu while the catalog loads: one "Loading…" row. */
fun chipMenuM2Lines(): List<MenuLine> = flattenLevel(
    "Model",
    listOf(
        MenuElement.Deferred(cached = true) { galleryModels().map { m -> MenuItem(m.label) {} } },
    ),
    resolved = { null },
    isRoot = true,
    backTitle = null,
)

/** E — the reasoning effort menu: High checked. */
fun chipMenuELines(): List<MenuLine> = flattenLevel(
    "Reasoning effort",
    GALLERY_FULL_LADDER.map { level ->
        MenuItem(
            title = galleryReasoningLabel(level),
            checked = level == "high",
            testTag = "chip-menu-effort-$level",
        ) {}
    },
    resolved = { null },
    isRoot = true,
    backTitle = null,
)

/** P — the PR menu: the long title wraps, both rows iconed. */
fun chipMenuPLines(): List<MenuLine> = flattenLevel(
    "Android: session chip menus for model, reasoning effort and pull request with a long title",
    listOf(
        MenuItem("Open Pull Request", icon = MenuIcon.Symbol(RobocoIcons.Safari), testTag = "chip-menu-pr-open") {},
        MenuItem("Copy Link", icon = MenuIcon.Symbol(RobocoIcons.Link), testTag = "chip-menu-pr-copy") {},
    ),
    resolved = { null },
    isRoot = true,
    backTitle = null,
)

/** C1's numbers: desktop's worked example (`5,417 / 1,048,576 tokens`). */
private val GALLERY_CONTEXT_MEASURED = ContextReading.Measured(tokens = 5_417uL, window = 1_048_576uL)

/** C2: a harness that reports tokens but never a window. */
private val GALLERY_CONTEXT_NO_WINDOW = ContextReading.TokensOnly(tokens = 48_210uL)

/** C1 / C2 — the context details card (android-next #01), grouped in en-US. */
fun chipMenuContextLines(reading: ContextReading): List<MenuLine> {
    val card = contextUsageCard(reading, Locale.US)
    return flattenLevel(card.title, card.children, resolved = { null }, isRoot = true, backTitle = null)
}

// ---------------------------------------------------------------------------
// Previews (step 10): M1 + E + P, light and dark.
// ---------------------------------------------------------------------------

@Composable
private fun ChipMenuPanelsPreviewBody(appearance: Appearance) {
    RobocoTheme(appearance) {
        Column(
            Modifier
                .background(RobocoTheme.palette.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GlassMenuPanel(lines = chipMenuM1Lines(), onRow = {}, onSubmenu = {}, onBack = {})
            GlassMenuPanel(lines = chipMenuELines(), onRow = {}, onSubmenu = {}, onBack = {})
            GlassMenuPanel(lines = chipMenuPLines(), onRow = {}, onSubmenu = {}, onBack = {})
        }
    }
}

@Preview(name = "Session chip menus light")
@Composable
private fun ChipMenuPanelsLightPreview() {
    ChipMenuPanelsPreviewBody(Appearance.Light)
}

@Preview(name = "Session chip menus dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChipMenuPanelsDarkPreview() {
    ChipMenuPanelsPreviewBody(Appearance.Dark)
}
