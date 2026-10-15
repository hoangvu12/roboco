package sh.roboco.android.composer

import android.content.res.Configuration
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GalleryBackdrop
import sh.roboco.android.design.LocalToaster
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Palette
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.rememberSmoothRoundedShape
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.GlassSurface
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.design.uiStyle
import sh.roboco.android.session.ContextReading
import sh.roboco.android.session.contextChip
import sh.roboco.android.session.contextUsageCard
import java.util.Locale

/**
 * The "Composer chips" page of the debug design gallery (android-ui-parity
 * #59), registered by ticket 22's registry as
 * `GallerySection("Composer chips") { ChipStripGallerySection() }`.
 *
 * Lives in the main source set on purpose: it uses only main-set helpers
 * ([GalleryBackdrop], [GlassMenuHost]); the debug registry imports this
 * composable, never the other way round.
 *
 * Every fixture is a [ChipStrip] (which needs a [GlassMenuHost] above it —
 * the section wraps itself in one, and the host makes the window's content
 * the haze source its menu panels blur) drawn on a 26 dp-radius glass card
 * over the gallery backdrop: A the session row, B the four PR states, C
 * the four context-usage cases (android-next #01), D the new-session
 * row, E the offline (`canWrite = false`) look, F the 240 dp overflow
 * strip at rest / 12 dp in / scrolled to the end, G the locations-rail
 * sample on the low-level chip, H the live wiring (tap "model" for the
 * glass menu, "3% context" for the context card, "branch" for the toast).
 */

// ---------------------------------------------------------------------------
// Fixtures (shared with the Robolectric/Roborazzi tests)
// ---------------------------------------------------------------------------

/** A — the session toolbar row (`ios:Debug/TranscriptLabViewController.swift:29-31`). */
internal fun chipFixtureSession(): List<ComposerChip> = listOf(
    ComposerChip(id = "model", title = "Opus 4.5", icon = ChipIcon.Brand("claude-code")),
    ComposerChip(id = "effort", title = "High", symbol = "gauge.with.dots.needle.67percent"),
    ComposerChip(id = "branch", title = "ios-rewrite", icon = ChipIcon.Branch),
)

/**
 * B — the four PR states. Chip ids are unique within one keyed strip, so
 * each state is its own strip.
 */
internal fun chipFixturePrStrips(): List<List<ComposerChip>> = listOf(
    listOf(
        ComposerChip(id = "model", title = "GPT-5", icon = ChipIcon.Brand("codex")),
        ComposerChip(id = "pr", title = "1234", icon = ChipIcon.PullRequest, tint = ChipTint.PrOpen),
    ),
    listOf(ComposerChip(id = "pr", title = "1235", icon = ChipIcon.PullRequest, tint = ChipTint.PrMerged)),
    listOf(ComposerChip(id = "pr", title = "1236", icon = ChipIcon.PullRequest, tint = ChipTint.PrClosed)),
    listOf(ComposerChip(id = "pr", title = "1237", icon = ChipIcon.PullRequest, tint = ChipTint.PrDraft)),
)

/**
 * C — context usage (android-next #01: always shown), built by the real
 * rule so the gallery cannot drift from it: low, mid, high (`warning` from
 * 85 %) and a harness that never reported a window. Grouped in en-US so
 * the goldens do not follow the host locale.
 */
internal val chipFixtureContextReadings: List<Pair<String, ContextReading>> = listOf(
    "low (3%)" to ContextReading.Measured(tokens = 6_000uL, window = 200_000uL),
    "mid (62%)" to ContextReading.Measured(tokens = 620uL, window = 1000uL),
    "high (91%)" to ContextReading.Measured(tokens = 910uL, window = 1000uL),
    "window not reported" to ContextReading.TokensOnly(tokens = 48_210uL),
)

internal fun chipFixtureContextStrips(): List<List<ComposerChip>> =
    chipFixtureContextReadings.map { (_, reading) -> listOf(contextChip(reading, Locale.US)) }

/** D — the new-session canvas row (`ios:Session/NewSessionViewController.swift:247-258`). */
internal fun chipFixtureNewSession(): List<ComposerChip> = listOf(
    ComposerChip(id = "project", title = "roboco", icon = ChipIcon.Project("roboco", 2)),
    ComposerChip(id = "host", title = "Workstation", symbol = "desktopcomputer"),
    ComposerChip(id = "branch", title = "New worktree", icon = ChipIcon.Branch),
    ComposerChip(id = "model", title = "Opus 4.5", icon = ChipIcon.Brand("claude-code")),
    ComposerChip(id = "effort", title = "Medium", symbol = "gauge.with.dots.needle.67percent"),
)

// ---------------------------------------------------------------------------
// The section
// ---------------------------------------------------------------------------

@Composable
fun ChipStripGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val toaster = LocalToaster.current

    // The menus blur this window's content; the cards blur the backdrop.
    val menuHaze = rememberHazeState()
    val cardHaze = rememberHazeState()

    GlassMenuHost(hazeState = menuHaze) {
        ProvideHazeState(cardHaze) {
            Box(Modifier.fillMaxSize()) {
                GalleryBackdrop(Modifier.fillMaxSize().hazeSource(cardHaze))
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Caption("Composer chips", uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text))
                    Caption(
                        "The toolbar row inside the composer glass: model, effort, branch, PR and " +
                            "context. Chips are 31.55 dp tall and centred in the 34 dp strip, " +
                            "never truncated, 6 dp apart; the strip's edges fade while it scrolls.",
                        uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                    )

                    ChipCard("A — session (model · effort · branch)") {
                        ChipStrip(chipFixtureSession(), emptyMap(), canWrite = true, onChipTap = {})
                    }

                    val prStrips = chipFixturePrStrips()
                    prStrips.forEachIndexed { index, chips ->
                        ChipCard("B — PR ${PR_STATE_NAMES[index]}") {
                            ChipStrip(chips, emptyMap(), canWrite = true, onChipTap = {})
                        }
                    }

                    chipFixtureContextStrips().forEachIndexed { index, chips ->
                        ChipCard("C — context usage ${chipFixtureContextReadings[index].first}") {
                            ChipStrip(chips, emptyMap(), canWrite = true, onChipTap = {})
                        }
                    }

                    ChipCard("D — new session (project · engine · checkout · model · effort)") {
                        ChipStrip(chipFixtureNewSession(), emptyMap(), canWrite = true, onChipTap = {})
                    }

                    ChipCard("E — offline engine (canWrite = false, ink tertiary)") {
                        ChipStrip(chipFixtureSession(), emptyMap(), canWrite = false, onChipTap = {})
                    }

                    // F — the same 240 dp overflow strip three times: at
                    // rest, 12 dp in (a partial start fade, 12 of 24 dp) and
                    // scrolled to the end.
                    ChipCard("F — overflow, at rest (end fade only)") {
                        OverflowStrip(chipFixtureNewSession(), rememberScrollState())
                    }
                    ChipCard("F — overflow, 12 dp in (partial start fade)") {
                        val initial = with(LocalDensity.current) { 12.dp.roundToPx() }
                        OverflowStrip(chipFixtureNewSession(), rememberScrollState(initial))
                    }
                    ChipCard("F — overflow, scrolled to the end (start fade only)") {
                        val endState = rememberScrollState()
                        LaunchedEffect(endState) {
                            // One frame so the first measure lands (maxValue
                            // is 0 until then), then jump to the end.
                            withFrameNanos { }
                            if (endState.maxValue > 0) endState.scrollTo(endState.maxValue)
                        }
                        OverflowStrip(chipFixtureNewSession(), endState)
                    }

                    ChipCard("G — locations rail sample (112: low-level chip + symbols)") {
                        Row(horizontalArrangement = Arrangement.spacedBy(ChipTokens.Spacing)) {
                            ContextChip(
                                "Home",
                                ChipLeading.Symbol(RobocoIcons.Home),
                                onClick = {},
                                tint = palette.accent,
                            )
                            ContextChip("C:", ChipLeading.Symbol(RobocoIcons.HardDrive), onClick = {})
                            ContextChip("D:", ChipLeading.Symbol(RobocoIcons.HardDrive), onClick = {})
                        }
                    }

                    Caption(
                        "H — live: tap \u201Cmodel\u201D for the glass menu (anchored to the chip), " +
                            "\u201C3% context\u201D for the context card, \u201Cbranch\u201D for a toast.",
                        uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                    )
                    ChipCard("H — live wiring") {
                        val low = chipFixtureContextReadings[0].second
                        ChipStrip(
                            chipFixtureSession() + contextChip(low, Locale.US),
                            chipMenus = mapOf(
                                "model" to { chipModelMenu() },
                                "context" to { contextUsageCard(low, Locale.US) },
                            ),
                            canWrite = true,
                            onChipTap = { id -> toaster.show("onChipTap: $id") },
                        )
                    }
                }
            }
        }
    }
}

/** The PR-state captions, shared by the section and the preview. */
private val PR_STATE_NAMES = listOf("open", "merged", "closed", "draft")

/** Fixture F's fixed-width strip: the row overflows 240 dp and scrolls. */
@Composable
private fun OverflowStrip(chips: List<ComposerChip>, state: ScrollState) {
    ChipStrip(
        chips,
        emptyMap(),
        canWrite = true,
        onChipTap = {},
        modifier = Modifier.width(240.dp),
        state = state,
    )
}

/** The demo model menu of fixture H (the real one is ticket 89's). */
internal fun chipModelMenu(): Menu = Menu(
    title = "Model",
    children = listOf(
        MenuItem("Opus 4.5", checked = true) {},
        MenuItem("Sonnet 4.5") {},
        MenuItem("Haiku 4.5") {},
    ),
)

/** One fixture: a small caption above a 26 dp-radius glass card holding the row. */
@Composable
private fun ChipCard(caption: String, content: @Composable () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Caption(caption, uiStyle(Face.Sans, typeScale.fixed(12f), palette.secondary))
        GlassSurface(
            shape = rememberSmoothRoundedShape(26.dp),
            tint = null,
        ) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { content() }
        }
    }
}

@Composable
private fun Caption(text: String, style: TextStyle) {
    BasicText(text, style = style)
}

// ---------------------------------------------------------------------------
// Previews (step 7): fixtures A + B + E, light and dark, each wrapped in
// the GlassMenuHost every ChipStrip needs.
// ---------------------------------------------------------------------------

@Preview(name = "Chips A+B+E light")
@Composable
private fun ChipStripLightPreview() {
    ChipStripPreview(Appearance.Light)
}

@Preview(name = "Chips A+B+E dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChipStripDarkPreview() {
    ChipStripPreview(Appearance.Dark)
}

@Composable
private fun ChipStripPreview(appearance: Appearance) {
    val palette = Palette.of(appearance == Appearance.Dark)
    RobocoTheme(appearance) {
        val typeScale = LocalTypeScale.current
        GlassMenuHost(hazeState = rememberHazeState()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(palette.background)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Caption("A — session", uiStyle(Face.Sans, typeScale.fixed(12f), palette.secondary))
                ChipStrip(chipFixtureSession(), emptyMap(), canWrite = true, onChipTap = {})
                val prNames = PR_STATE_NAMES
                chipFixturePrStrips().forEachIndexed { index, chips ->
                    Caption("B — PR ${prNames[index]}", uiStyle(Face.Sans, typeScale.fixed(12f), palette.secondary))
                    ChipStrip(chips, emptyMap(), canWrite = true, onChipTap = {})
                }
                Caption("E — offline", uiStyle(Face.Sans, typeScale.fixed(12f), palette.secondary))
                ChipStrip(chipFixtureSession(), emptyMap(), canWrite = false, onChipTap = {})
            }
        }
    }
}
