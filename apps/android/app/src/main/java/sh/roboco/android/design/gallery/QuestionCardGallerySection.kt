package sh.roboco.android.design.gallery

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import sh.roboco.android.composer.QuestionCard
import sh.roboco.android.composer.QuestionPageActions
import sh.roboco.android.composer.QuestionPageModel
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GlassStage
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState

/**
 * The "Question card" page of the debug design gallery (android-ui-parity
 * #90), registered by ticket 22's registry as
 * `GallerySection("Question card", screenshotHeightDp = 4200) { QuestionCardGallerySection() }`.
 *
 * The phone's question panel at **388 dp wide** (the panel is the bottom
 * stack's width, no extra inset): fixtures A, B1, B2, B3, C, D and E
 * stacked in [GlassStage]s over the striped backdrop (the card is the
 * backdrop's sibling, never inside its `hazeSource`), plus one interactive
 * card that keeps a local `picked`/`custom` state so taps visibly toggle
 * rows. It is a toy, not ticket 91's logic: single-select replace, no
 * 220 ms auto-advance, no submit.
 *
 * Lives in the main source set on purpose (the gallery-section convention):
 * the `@Preview`s and the Roborazzi goldens use the same
 * [QuestionCardFixtures].
 */

/**
 * The ticket's fixtures (step 8), shared by the gallery, the previews and
 * the tests:
 *
 *  - **A** (synthetic): the geometry fixture — "Pick a tone", three
 *    one-line options, nothing picked, Next disabled, no Back. Card
 *    height 342.85 dp at 388 dp width.
 *  - **B1** (Roboco's `ROBOCO_MOCK_QUESTION` page 1, single pick): one
 *    option picked, the two long options wrap.
 *  - **B2** (mock page 2, multi): page 2 of 2 — Back shown, Submit, two
 *    of three picked (check squares).
 *  - **B3**: B1 with nothing picked but custom text — the Other field
 *    carries "Something else entirely".
 *  - **C**: the editor — no options (both gaps around the empty column),
 *    `multiline`, two lines prefilled, Submit.
 *  - **D** (long): a 3-line question, 12 options (the last ring reads
 *    "12"), one option wrapping to 2 lines, multi-select with one pick.
 *  - **E** (upstream's demo `?ask` question, the one the iOS UI-test
 *    snapshot shows): "Scope" header without "· 1 of 1", Submit disabled.
 *    Height 294.05 dp.
 */
internal object QuestionCardFixtures {

    val A = QuestionPageModel(
        header = "Question",
        page = 0,
        pageCount = 2,
        question = "Pick a tone",
        options = listOf("Formal", "Casual", "Playful"),
        picked = emptySet(),
        multiSelect = false,
        multiline = false,
        customText = "",
        canContinue = false,
    )

    val B1 = QuestionPageModel(
        header = "Question",
        page = 0,
        pageCount = 2,
        question = "Which sync strategy should the rewrite use?",
        options = listOf(
            "Poll the doc host every 120ms",
            "Event-driven fold with coalesced commits",
            "Hybrid: event-driven with a polling fallback",
        ),
        picked = setOf("Event-driven fold with coalesced commits"),
        multiSelect = false,
        multiline = false,
        customText = "",
        canContinue = true,
    )

    val B2 = QuestionPageModel(
        header = "Question",
        page = 1,
        pageCount = 2,
        question = "Which suites should gate the merge?",
        options = listOf("Unit tests", "End-to-end (two-device)", "Golden screenshots"),
        picked = setOf("Unit tests", "Golden screenshots"),
        multiSelect = true,
        multiline = false,
        customText = "",
        canContinue = true,
    )

    val B3 = QuestionPageModel(
        header = "Question",
        page = 0,
        pageCount = 2,
        question = "Which sync strategy should the rewrite use?",
        options = listOf(
            "Poll the doc host every 120ms",
            "Event-driven fold with coalesced commits",
            "Hybrid: event-driven with a polling fallback",
        ),
        picked = emptySet(),
        multiSelect = false,
        multiline = false,
        customText = "Something else entirely",
        canContinue = true,
    )

    val C = QuestionPageModel(
        header = "",
        page = 0,
        pageCount = 1,
        question = "Describe the change you want",
        options = emptyList(),
        picked = emptySet(),
        multiSelect = false,
        multiline = true,
        customText = "Line one\nLine two",
        canContinue = true,
    )

    val D = QuestionPageModel(
        header = "Question",
        page = 0,
        pageCount = 2,
        question = "Which failure modes should the reconciler recover from when two " +
            "engines write the same session while queued work is still in flight?",
        options = listOf(
            "Crash mid-commit",
            "Two engines write the same session and both commits land",
            "Network partition",
            "Stale snapshot",
            "Lost queued work",
            "Corrupt index",
            "Slow disk",
            "Clock skew",
            "Full disk",
            "OOM kill",
            "Watchdog timeout",
            "Unknown symbol",
        ),
        picked = setOf("Crash mid-commit"),
        multiSelect = true,
        multiline = false,
        customText = "",
        canContinue = true,
    )

    val E = QuestionPageModel(
        header = "Scope",
        page = 0,
        pageCount = 1,
        question = "Should the fix cover Android too?",
        options = listOf("Yes, both platforms", "iOS only"),
        picked = emptySet(),
        multiSelect = false,
        multiline = false,
        customText = "",
        canContinue = false,
    )

    /** No-op callbacks for the static fixtures. */
    val NoActions = QuestionPageActions(
        onPick = {},
        onCustomChange = {},
        onOtherDone = {},
        onBack = {},
        onNext = {},
    )
}

// ---------------------------------------------------------------------------
// The section
// ---------------------------------------------------------------------------

/** The section: the seven static fixtures, then the interactive card. */
@Composable
fun QuestionCardGallerySection() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for (stage in QuestionCardStages) QuestionCardStage(stage.model, stage.height)
        QuestionCardInteractiveStage()
    }
}

/** One gallery stage: the fixture, its Roborazzi golden key and the stage height. */
internal class QuestionCardFixtureStage(val key: String, val model: QuestionPageModel, val height: Dp)

/**
 * The static fixtures in gallery order with their stage heights (the
 * card's known height plus slack for the glass shadow, ≈ 20 dp, ticket
 * 07) — shared with the Roborazzi goldens so the two never diverge.
 */
internal val QuestionCardStages: List<QuestionCardFixtureStage> = listOf(
    QuestionCardFixtureStage("a", QuestionCardFixtures.A, 420.dp),
    QuestionCardFixtureStage("b1", QuestionCardFixtures.B1, 480.dp),
    QuestionCardFixtureStage("b2", QuestionCardFixtures.B2, 420.dp),
    QuestionCardFixtureStage("b3", QuestionCardFixtures.B3, 480.dp),
    QuestionCardFixtureStage("c", QuestionCardFixtures.C, 380.dp),
    QuestionCardFixtureStage("d", QuestionCardFixtures.D, 920.dp),
    QuestionCardFixtureStage("e", QuestionCardFixtures.E, 380.dp),
)

/** One fixture in its own 388 dp [GlassStage]. */
@Composable
private fun QuestionCardStage(model: QuestionPageModel, height: Dp) {
    QuestionCardGlassStage(height) {
        QuestionCard(model, QuestionCardFixtures.NoActions, Modifier.fillMaxWidth())
    }
}

/** The shared 388 dp stage: the striped backdrop's sibling carries the card. */
@Composable
private fun QuestionCardGlassStage(height: Dp, content: @Composable () -> Unit) {
    ProvideHazeState {
        GlassStage(
            modifier = Modifier.width(388.dp),
            height = height,
            backdropModifier = Modifier.hazeSource(LocalHazeState.current!!),
        ) {
            content()
        }
    }
}

/**
 * The interactive card: a local `picked`/`custom` state so taps visibly
 * toggle rows — a toy, not ticket 91's logic. The pick mirrors iOS
 * `pick()` (`QuestionPanel.swift:156-164`): single questions replace,
 * multi questions toggle; no 220 ms auto-advance, no submit.
 */
@Composable
private fun QuestionCardInteractiveStage() {
    QuestionCardGlassStage(420.dp) { QuestionInteractiveCard() }
}

@Composable
private fun QuestionInteractiveCard() {
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var custom by remember { mutableStateOf("") }
    val model = QuestionPageModel(
        header = "Question",
        page = 0,
        pageCount = 2,
        question = "Pick a tone",
        options = listOf("Formal", "Casual", "Playful"),
        picked = picked,
        multiSelect = false,
        multiline = false,
        customText = custom,
        canContinue = picked.isNotEmpty() || custom.isNotBlank(),
    )
    val actions = remember {
        QuestionPageActions(
            onPick = { option -> picked = setOf(option) },
            onCustomChange = { custom = it },
            onOtherDone = {},
            onBack = {},
            onNext = {},
        )
    }
    QuestionCard(model, actions, Modifier.fillMaxWidth())
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

/** The preview body: fixtures A and B2, each in a 420 dp stage at 388 dp. */
@Composable
private fun QuestionCardPreviewBody() {
    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            QuestionCardStage(QuestionCardFixtures.A, 420.dp)
            QuestionCardStage(QuestionCardFixtures.B2, 420.dp)
        }
    }
}

@Preview(name = "Question card – light")
@Composable
private fun QuestionCardLightPreview() {
    RobocoTheme(Appearance.Light) { QuestionCardPreviewBody() }
}

@Preview(name = "Question card – dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun QuestionCardDarkPreview() {
    RobocoTheme(Appearance.Dark) { QuestionCardPreviewBody() }
}
