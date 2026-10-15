package sh.roboco.android.newsession

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.delay
import sh.roboco.android.composer.ChipStrip
import sh.roboco.android.composer.ComposerChip
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GalleryBackdrop
import sh.roboco.android.design.HarnessMark
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.GlassMenuPanel
import sh.roboco.android.design.glass.MenuElement
import sh.roboco.android.design.glass.MenuLine
import sh.roboco.android.design.glass.flattenLevel
import sh.roboco.android.design.uiStyle

/**
 * The "New-session menus" page of the debug design gallery
 * (android-ui-parity #114), registered by ticket 22's registry as
 * `GallerySection("New-session menus", GalleryLayout.Fill) {
 * CheckoutModelEffortMenusGallerySection() }` — `Fill`, like ticket 15's
 * own GlassMenu section, because the live demo needs a [GlassMenuHost]
 * over the whole area; the static panels live in their own
 * `verticalScroll` column inside it.
 *
 * Lives in the main source set on purpose: every fixture goes through
 * the pure builders of `CheckoutModelEffortMenus.kt` and ticket 109's
 * [GalleryLabels] (no native library — the previews, the gallery and the
 * Robolectric tests stay FFI-free); the debug registry imports this
 * composable, never the other way round.
 *
 * The panels are ticket 15's [GlassMenuPanel] over pre-flattened lines
 * ([flattenLevel]), so the panel chrome, the checkmarks, the separators
 * and the "Loading…" row are exactly what the real menus show: K1
 * Checkout while the refs load (nothing reserved, titles at 20 dp), K2
 * loaded with the worktree on, K3 with a picked branch, M the Model
 * menu (harness sections sorted by id, 16 dp brand marks, Opus 5.5
 * checked), E1 the effort ladder with nothing picked (titles at 20 dp)
 * and E2 with `low` picked (titles at 61 dp). The live demo under them
 * drives the real [CheckoutModelEffortMenus] over an in-memory draft.
 */

// ---------------------------------------------------------------------------
// Fixtures (the ticket's M demo set; Roboco's static catalog values)
// ---------------------------------------------------------------------------

/** `claude/catalog.rs` FULL_LADDER — 7 levels. */
internal val NewSessionMenusFullLadder = listOf(
    "low", "medium", "high", "xhigh", "max", "ultracode", "ultrathink",
)

/** `claude/catalog.rs` XHIGH_LADDER — the Sonnet 5 tier (no ultracode). */
internal val NewSessionMenusClaudeXHigh = listOf("low", "medium", "high", "xhigh", "max", "ultrathink")

/** `codex/catalog.rs` XHIGH_LADDER — 4 levels. */
internal val NewSessionMenusCodexXHigh = listOf("low", "medium", "high", "xhigh")

/** The Claude rows of the M fixture (`claude/catalog.rs:328-380` order). */
internal val NewSessionMenusClaudeModels = listOf(
    ModelChoice("claude-code", "Claude Code", "claude-fable-5-1", "Fable 5.1", NewSessionMenusFullLadder),
    ModelChoice("claude-code", "Claude Code", "claude-fable-5", "Fable 5", NewSessionMenusFullLadder),
    ModelChoice("claude-code", "Claude Code", "claude-opus-5-5", "Opus 5.5", NewSessionMenusFullLadder),
    ModelChoice("claude-code", "Claude Code", "claude-opus-4-8", "Opus 4.8", NewSessionMenusFullLadder),
    ModelChoice("claude-code", "Claude Code", "claude-sonnet-5", "Sonnet 5", NewSessionMenusClaudeXHigh),
    ModelChoice("claude-code", "Claude Code", "claude-haiku-4-5", "Haiku 4.5", emptyList()),
)

/** The Codex rows of the M fixture (`codex/catalog.rs:171-191`). */
internal val NewSessionMenusCodexModels = listOf(
    ModelChoice("codex", "Codex", "gpt-5.5", "GPT-5.5", NewSessionMenusCodexXHigh),
    ModelChoice("codex", "Codex", "gpt-5.4", "GPT-5.4", NewSessionMenusCodexXHigh),
    ModelChoice("codex", "Codex", "gpt-5.4-mini", "GPT-5.4-Mini", NewSessionMenusCodexXHigh),
)

/** The M fixture: **Codex first**, so the menu's harness-id sort is visible. */
internal val NewSessionMenusModels = NewSessionMenusCodexModels + NewSessionMenusClaudeModels

/** The live demo's project (the ticket's row-1 fixture shape). */
internal val NewSessionMenusProject = ProjectOption(
    id = "demo:p1",
    name = "roboco",
    path = "/home/me/roboco",
    device = "demo:dev",
    deviceName = "Workstation",
    online = true,
    git = true,
    groupKey = "roboco",
    groupName = "roboco",
    colorIndex = 2,
)

/** The K2 refs: `main` current, then the engine's own order. */
internal val NewSessionMenusRefs = listOf("main", "feature/android-menus", "fix/ws-reconnect", "release/0.9")

/**
 * The live demo's in-memory draft — also the UI tests' page: a git
 * project with the Claude pick and nothing else chosen, so the first
 * frame reads `roboco`, `Workstation`, `Current branch`, `Opus 5.5`
 * and the FULL ladder's middle level `Xhigh`.
 */
internal val NewSessionMenusDemoDraft = NewSessionDraft(
    projectId = "demo:p1",
    harness = "claude-code",
    model = "claude-opus-5-5",
)

/**
 * The live demo's page (and the UI tests'): the in-memory draft the
 * picks mutate, the [CheckoutModelEffortMenus] over it — the same
 * wiring the New Session screen uses, minus the persistence — and
 * ticket 109's chip set over the same state, so the chips, the hero
 * and the menus can never disagree. Main thread only.
 */
internal class NewSessionMenusDemoPage(refSource: RefSource) {

    var draft by mutableStateOf(NewSessionMenusDemoDraft)

    val menus = CheckoutModelEffortMenus(
        draft = { draft },
        project = { NewSessionMenusProject },
        models = { NewSessionMenusModels },
        selectedModel = {
            NewSessionMenusModels.firstOrNull { m -> m.harness == draft.harness && m.id == draft.model }
                ?: NewSessionMenusModels.firstOrNull { m -> m.harness == draft.harness }
        },
        update = { t -> draft = t(draft) },
        refSource = refSource,
    )

    val chips: List<ComposerChip>
        get() = newSessionChips(
            draft = draft,
            project = NewSessionMenusProject,
            hosts = emptyList(),
            models = NewSessionMenusModels,
            labels = GalleryLabels,
        )
}

// ---------------------------------------------------------------------------
// The static panels' lines (K1–K3, M, E1, E2) — pure, shared with the
// Roborazzi goldens (CheckoutModelEffortMenusScreenshotTest).
// ---------------------------------------------------------------------------

/** K1 — Checkout while the refs load: worktree off, the Branch deferred pending. */
internal fun checkoutLoadingLines(): List<MenuLine> {
    val pending = MenuElement.Deferred(cached = true) { emptyList() }
    val menu = checkoutMenu(worktree = false, branchSection = pending, onToggleWorktree = {})
    return flattenLevel(menu.title, menu.children, resolved = { null }, isRoot = true, backTitle = null)
}

/** K2/K3 — Checkout with the refs in: the `branch ?? refs.first` check, the worktree row's own state. */
internal fun checkoutLoadedLines(worktree: Boolean, branch: String?): List<MenuLine> {
    val rows = branchRows(NewSessionMenusRefs, branch) {}
    val deferred = MenuElement.Deferred(cached = true) { rows }
    val menu = checkoutMenu(worktree, deferred) {}
    return flattenLevel(menu.title, menu.children, resolved = { rows }, isRoot = true, backTitle = null)
}

/** M — the Model menu over the demo fixture: Claude Code before Codex, Opus 5.5 checked. */
internal fun modelMenuLines(
    draft: NewSessionDraft = NewSessionDraft(harness = "claude-code", model = "claude-opus-5-5"),
    models: List<ModelChoice> = NewSessionMenusModels,
): List<MenuLine> {
    val menu = modelMenu(models, draft) {}
    return flattenLevel(menu.title, menu.children, resolved = { null }, isRoot = true, backTitle = null)
}

/** E1/E2 — the Reasoning effort menu over Fable 5.1's ladder. */
internal fun effortMenuLines(model: ModelChoice?, effort: String?): List<MenuLine> {
    val menu = effortMenu(model, effort) {}
    return flattenLevel(menu.title, menu.children, resolved = { null }, isRoot = true, backTitle = null)
}

/** Fable 5.1 — the effort fixtures' ladder owner (the first Claude row). */
internal val NewSessionMenusFable51: ModelChoice = NewSessionMenusClaudeModels.first()

// ---------------------------------------------------------------------------
// The section
// ---------------------------------------------------------------------------

@Composable
fun CheckoutModelEffortMenusGallerySection() {
    // The host the live demo's ChipStrip opens its menus through (the
    // static panels need none, but the backdrop stays the haze source).
    GlassMenuHost(hazeState = rememberHazeState()) {
        Box(Modifier.fillMaxSize()) {
            GalleryBackdrop(Modifier.fillMaxSize())
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SectionLabel(
                    "New-session menus",
                    style = uiStyle(Face.SansSemibold, LocalTypeScale.current.fixed(17f), RobocoTheme.palette.text),
                )
                SectionLabel(
                    "Checkout / Model / Reasoning effort — ticket 15 draws, 114 builds the content. " +
                        "The live demo at the bottom drives the real menus.",
                    style = uiStyle(Face.Sans, LocalTypeScale.current.fixed(13f), RobocoTheme.palette.secondary),
                )
                StaticPanel("K1 · Checkout, loading", checkoutLoadingLines())
                StaticPanel("K2 · Checkout, loaded, worktree on", checkoutLoadedLines(worktree = true, branch = null))
                StaticPanel(
                    "K3 · Checkout, loaded, branch picked",
                    checkoutLoadedLines(worktree = false, branch = "fix/ws-reconnect"),
                )
                StaticPanel("M · Model", modelMenuLines())
                StaticPanel("E1 · Reasoning effort, nothing picked", effortMenuLines(NewSessionMenusFable51, null))
                StaticPanel("E2 · Reasoning effort, picked", effortMenuLines(NewSessionMenusFable51, "low"))
                LiveDemo()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * The live demo: a 34 dp [HarnessMark] stand-in for 93's hero above
 * ticket 59's [ChipStrip], both over the in-memory draft — a fake
 * [RefSource] that answers after 1.5 s, so Checkout shows the
 * "Loading…" row first and the branches after; reopening without a pick
 * is instant (the branch cache), and every pick updates the chips, the
 * hero and the JSON caption in the same frame.
 */
@Composable
private fun LiveDemo() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val page = remember {
        NewSessionMenusDemoPage(refSource = RefSource {
            delay(1500)
            NewSessionMenusRefs
        })
    }
    SectionLabel(
        "Live demo — tap a chip (the refs load after 1.5 s):",
        style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The stand-in for 93's hero mark: same side, same tint rule
        // (Claude keeps its orange; every other mark takes the ink).
        HarnessMark(page.draft.harness, side = 34.dp, tint = palette.text)
        Box(
            Modifier
                .width(400.dp)
                .background(palette.elevated, RoundedCornerShape(26.dp)),
        ) {
            ChipStrip(
                chips = page.chips,
                chipMenus = page.menus.providers(),
                canWrite = true,
                onChipTap = {},
            )
        }
    }
    SectionLabel(
        "Draft: ${NewSessionDraftJson.encode(page.draft)}",
        style = uiStyle(Face.Mono, typeScale.fixed(11f), palette.secondary),
    )
}

/** One static fixture panel: the caption above ticket 15's panel. */
@Composable
private fun StaticPanel(caption: String, lines: List<MenuLine>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel(
            caption,
            style = uiStyle(Face.SansMedium, LocalTypeScale.current.fixed(12f), RobocoTheme.palette.secondary),
        )
        GlassMenuPanel(
            lines = lines,
            onRow = {},
            onSubmenu = {},
            onBack = {},
        )
    }
}

@Composable
private fun SectionLabel(text: String, style: TextStyle) {
    BasicText(text, style = style)
}

// ---------------------------------------------------------------------------
// Previews (the BRIEF's component-ticket convention): K2, M and E2 side
// by side, light and dark. The goldens themselves come from
// CheckoutModelEffortMenusScreenshotTest.
// ---------------------------------------------------------------------------

@Preview(name = "New-session menus light")
@Composable
private fun NewSessionMenusLightPreview() {
    RobocoTheme(Appearance.Light) { NewSessionMenusPreviewBody() }
}

@Preview(name = "New-session menus dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NewSessionMenusDarkPreview() {
    RobocoTheme(Appearance.Dark) { NewSessionMenusPreviewBody() }
}

@Composable
private fun NewSessionMenusPreviewBody() {
    Box(Modifier.background(RobocoTheme.palette.background).padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            StaticPanel("K2 · Checkout, worktree on", checkoutLoadedLines(worktree = true, branch = null))
            StaticPanel("M · Model", modelMenuLines())
            StaticPanel("E2 · Reasoning effort, picked", effortMenuLines(NewSessionMenusFable51, "low"))
        }
    }
}
