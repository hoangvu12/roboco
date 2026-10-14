package sh.roboco.android.newsession

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
import sh.roboco.android.composer.ChipStrip
import sh.roboco.android.composer.ComposerChip
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.uiStyle

/**
 * The "New-session chips" page of the debug design gallery
 * (android-ui-parity #109), registered by ticket 22's registry as
 * `GallerySection("New-session chips", screenshotHeightDp = 420) {
 * NewSessionChipsGallerySection() }`.
 *
 * Lives in the main source set on purpose: every fixture goes through
 * [newSessionChips] over [GalleryLabels] (no native library — the
 * preview and the Robolectric goldens stay FFI-free); the debug
 * registry imports this composable, never the other way round.
 *
 * Five fixtures, 16 dp apart, each a caption (ticket 03's
 * `uiStyle(Face.SansMedium, fixed(12))` in `secondary`) above a 400 dp
 * wide ticket 59 [ChipStrip] on `Palette.elevated` in a 26 dp-radius
 * rounded box — A's full row pins the order, titles, icons and the
 * `Xhigh` default effort; B the offline warning tint and the worktree
 * title; C the non-git row (no branch, no effort); D the projectless
 * pair over an offline host; E the nothing-paired `Choose host` row.
 * The strip itself (capsule, tone washes, fades) is 59's — this page
 * only pins what 109 decides: which chips, in which order, with which
 * title, icon and tint.
 */

/** The built-in Claude ladders of the fixtures (`catalog.rs` FULL_LADDER / Haiku's empty ladder). */
private val FullLadder = listOf("low", "medium", "high", "xhigh", "max", "ultracode", "ultrathink")

/** The 4-level Codex ladder of fixture B (`catalog.rs` CODEX_XHIGH). */
private val CodexXHigh = listOf("low", "medium", "high", "xhigh")

/** Fixture A's project (the ticket's row 1 fixture; B–C derive from it). */
private val GalleryProject = ProjectOption(
    id = "p1",
    name = "roboco",
    path = "/srv/roboco",
    device = "d1",
    deviceName = "Workstation",
    online = true,
    git = true,
    groupKey = "repo:r",
    groupName = "roboco",
    colorIndex = 2,
)

/** The listed models of fixtures A, C, D and E: Fable 5.1 and Haiku 4.5. */
private val GalleryModels = listOf(
    ModelChoice("claude-code", "Claude Code", "claude-fable-5-1", "Fable 5.1", FullLadder),
    ModelChoice("claude-code", "Claude Code", "claude-haiku-4-5", "Haiku 4.5", emptyList()),
)

/**
 * [ChipLabels] hard-coded for the fixtures — `CoreChipLabels`' behaviour
 * (`firstFallbackModelLabel` → the built-in first row, `modelLabel` → the
 * raw id, `harnessLabel` → the id) without the native library, so the
 * previews, the gallery and the Robolectric tests stay FFI-free.
 */
internal object GalleryLabels : ChipLabels {
    override fun modelLabel(harness: String, model: String): String = model

    override fun firstFallbackModelLabel(harness: String): String? =
        if (harness == "codex") "GPT-6-Astra" else "Fable 5.1"

    override fun harnessLabel(harness: String): String = harness
}

/** A — git project, online: the full five-chip row. */
internal fun newSessionChipsFixtureA(): List<ComposerChip> = newSessionChips(
    draft = NewSessionDraft(projectId = "p1"),
    project = GalleryProject,
    hosts = emptyList(),
    models = GalleryModels,
    labels = GalleryLabels,
)

/** B — offline engine, worktree: the host chip's warning tint. */
internal fun newSessionChipsFixtureB(): List<ComposerChip> = newSessionChips(
    draft = NewSessionDraft(
        projectId = "p1",
        branch = "main",
        worktree = true,
        harness = "codex",
        model = "gpt-5.5",
        effort = "low",
    ),
    project = GalleryProject.copy(online = false),
    hosts = emptyList(),
    models = listOf(ModelChoice("codex", "Codex", "gpt-5.5", "GPT-5.5", CodexXHigh)),
    labels = GalleryLabels,
)

/** C — non-git project: no branch chip (the picked branch is ignored), no effort (Haiku has no ladder). */
internal fun newSessionChipsFixtureC(): List<ComposerChip> = newSessionChips(
    draft = NewSessionDraft(projectId = "p1", branch = "main", harness = "claude-code", model = "claude-haiku-4-5"),
    project = GalleryProject.copy(git = false),
    hosts = emptyList(),
    models = GalleryModels,
    labels = GalleryLabels,
)

/** D — no project, offline host: the projectless pair over `Laptop`, no effort (no mock model listed). */
internal fun newSessionChipsFixtureD(): List<ComposerChip> = newSessionChips(
    draft = NewSessionDraft(hostId = "h1", harness = "mock"),
    project = null,
    hosts = listOf(HostOption("h1", "Laptop", online = false)),
    models = GalleryModels,
    labels = GalleryLabels,
)

/** E — nothing paired: `No project` · `Choose host`, the built-in model and default effort. */
internal fun newSessionChipsFixtureE(): List<ComposerChip> = newSessionChips(
    draft = NewSessionDraft(),
    project = null,
    hosts = emptyList(),
    models = GalleryModels,
    labels = GalleryLabels,
)

@Composable
fun NewSessionChipsGallerySection() {
    // The host every ChipStrip needs (its menus would open through it;
    // these fixtures pass none, so it only satisfies ticket 15's rule).
    GlassMenuHost(hazeState = rememberHazeState()) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            NewSessionChipsGalleryFixture("A · git project, online", newSessionChipsFixtureA())
            NewSessionChipsGalleryFixture("B · offline engine, worktree", newSessionChipsFixtureB())
            NewSessionChipsGalleryFixture("C · non-git project", newSessionChipsFixtureC())
            NewSessionChipsGalleryFixture("D · no project, offline host", newSessionChipsFixtureD())
            NewSessionChipsGalleryFixture("E · nothing paired", newSessionChipsFixtureE())
        }
    }
}

/** One fixture: a caption above the 400 dp strip on the elevated card. */
@Composable
private fun NewSessionChipsGalleryFixture(caption: String, chips: List<ComposerChip>) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(caption, style = uiStyle(Face.SansMedium, typeScale.fixed(12f), palette.secondary))
        Box(
            Modifier
                .width(400.dp)
                .background(palette.elevated, RoundedCornerShape(26.dp)),
        ) {
            ChipStrip(chips, emptyMap(), canWrite = true, onChipTap = {})
        }
    }
}

// ---------------------------------------------------------------------------
// Previews (the BRIEF's component-ticket convention): fixtures A, B and D,
// light and dark, each wrapped in the GlassMenuHost every ChipStrip needs.
// The goldens themselves come from ticket 22's parameterized
// GalleryScreenshotTest plus NewSessionChipsScreenshotTest.
// ---------------------------------------------------------------------------

@Preview(name = "New-session chips light")
@Composable
private fun NewSessionChipsLightPreview() {
    RobocoTheme(Appearance.Light) { NewSessionChipsPreviewBody() }
}

@Preview(name = "New-session chips dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NewSessionChipsDarkPreview() {
    RobocoTheme(Appearance.Dark) { NewSessionChipsPreviewBody() }
}

@Composable
private fun NewSessionChipsPreviewBody() {
    GlassMenuHost(hazeState = rememberHazeState()) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            NewSessionChipsGalleryFixture("A · git project, online", newSessionChipsFixtureA())
            NewSessionChipsGalleryFixture("B · offline engine, worktree", newSessionChipsFixtureB())
            NewSessionChipsGalleryFixture("D · no project, offline host", newSessionChipsFixtureD())
        }
    }
}
