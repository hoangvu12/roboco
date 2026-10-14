package sh.roboco.android.newsession

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.composer.ChipStrip
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.HarnessMark
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuHost

/**
 * The checkout / model / effort menus' end-to-end behaviour
 * (android-ui-parity #114) — Robolectric + compose-ui-test over the real
 * [CheckoutModelEffortMenus] and the gallery demo's in-memory page
 * ([NewSessionMenusDemoPage]: the M fixture models, the demo project, a
 * git draft with the Claude pick — no native library, the refs faked).
 *
 * Ports of the iOS `SessionFlowTests`:
 * - `testEffortPickerInNewSession` (`iosui:SessionFlowTests.swift:424-434`):
 *   the effort chip exists, the first row appears within 5 s, tapping it
 *   closes the menu and the chip keeps existing, now reading `Low`;
 * - the effort half of `testNewSessionRemembersDraftWhenClosed`
 *   (`:388-422`): the row labelled `Low` is tapped by **label**, and the
 *   chip follows.
 *
 * Plus the Checkout loading row and cache lifetime (the `BranchKey`
 * hoist: reopening with no pick shows the branches at once, a pick
 * reloads), the worktree toggle's same-frame chip title, and the model
 * pick's triple update (chip, hero, effort default).
 *
 * The clock runs (`autoAdvance = true`) because the branch load hops to
 * a real IO dispatcher, and `waitUntil` mirrors the iOS tests' 5 s
 * windows. Brand-mark pixels are golden-only
 * ([CheckoutModelEffortMenusScreenshotTest]); the hero stand-in carries
 * its harness in the test tag so the mark switch is assertable.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class CheckoutModelEffortMenusUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun runClockAndForceFallbackGlass() {
        compose.mainClock.autoAdvance = true
        Glass.forceFallback = true
    }

    @After
    fun restoreGlass() {
        Glass.forceFallback = false
    }

    // ── the fake ref sources ───────────────────────────────────────────

    /** The instant engine: the K2 refs, counting calls. */
    private class ImmediateRefSource : RefSource {
        var calls = 0
        override suspend fun refs(project: ProjectOption): List<String> {
            calls++
            return NewSessionMenusRefs
        }
    }

    /** The suspended engine: "Loading…" until [gate] completes, then [result]. */
    private class GatedRefSource(
        private val gate: CompletableDeferred<Unit>,
        private val result: List<String>,
    ) : RefSource {
        var calls = 0
        override suspend fun refs(project: ProjectOption): List<String> {
            calls++
            gate.await()
            return result
        }
    }

    // ── the harness ────────────────────────────────────────────────────

    /**
     * The gallery demo's live harness: the hero stand-in (34 dp mark,
     * harness in the tag) above ticket 59's strip, both reading the same
     * in-memory draft the menus pick into.
     */
    @Composable
    private fun Harness(page: NewSessionMenusDemoPage) {
        val palette = RobocoTheme.palette
        GlassMenuHost(hazeState = rememberHazeState()) {
            Column(Modifier.fillMaxSize().background(Color.White).padding(16.dp)) {
                Box(Modifier.testTag("new-session-hero-${page.draft.harness}")) {
                    HarnessMark(page.draft.harness, side = 34.dp, tint = palette.text)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
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
        }
    }

    private fun launch(page: NewSessionMenusDemoPage) {
        compose.setContent {
            RobocoTheme(Appearance.Light) { Harness(page) }
        }
        compose.waitForIdle()
    }

    private fun rowsOf(tag: String): Int = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun closeTheMenu() {
        Espresso.pressBack()
        compose.waitUntil(5_000) { rowsOf("glass-menu") == 0 }
    }

    // ── the iOS port: testEffortPickerInNewSession ─────────────────────

    @Test
    fun theEffortPickerLoadsPicksAndTheChipSurvives() {
        val page = NewSessionMenusDemoPage(refSource = ImmediateRefSource())
        launch(page)

        // The chip exists (the FULL ladder's middle level).
        val chip = compose.onNodeWithTag("composer-chip-effort")
        chip.assertExists()
        chip.assertTextEquals("Xhigh")

        // The effort chip is the strip's last chip: on this 393 dp page it
        // sits past the strip's scroll viewport, where a centre click
        // lands outside the strip and never reaches it. Scroll it in first
        // (a user swipes the strip the same way).
        chip.performScrollTo()
        chip.performClick()
        compose.waitUntil(5_000) { rowsOf("new-session-menu-effort-low") > 0 }

        // The first row is the first effort level ("Low" on the Claude ladders).
        compose.onNodeWithTag("new-session-menu-effort-low").performClick()
        compose.waitUntil(5_000) { rowsOf("glass-menu") == 0 }

        // The chip still exists — no crash — and now reads Low.
        compose.onNodeWithTag("composer-chip-effort").assertExists()
        compose.onNodeWithTag("composer-chip-effort").assertTextEquals("Low")
        compose.onNodeWithTag("composer-chip-strip").assertExists()
    }

    // ── the iOS port: the effort half of testNewSessionRemembersDraftWhenClosed ──

    @Test
    fun theRowLabelledLowIsTappableAndTheChipFollows() {
        val page = NewSessionMenusDemoPage(refSource = ImmediateRefSource())
        launch(page)

        // Scrolled in first: see theEffortPickerLoadsPicksAndTheChipSurvives.
        compose.onNodeWithTag("composer-chip-effort").performScrollTo()
        compose.onNodeWithTag("composer-chip-effort").performClick()
        compose.waitUntil(5_000) { rowsOf("new-session-menu-effort-low") > 0 }

        // The iOS test finds the row by its label, not by index.
        compose.onNodeWithText("Low").performClick()
        compose.waitUntil(5_000) { rowsOf("glass-menu") == 0 }

        compose.onNodeWithTag("composer-chip-effort").assertTextContains("Low", substring = true)
        assertEquals("low", page.draft.effort)
    }

    // ── Checkout: the Loading row, the sorted branches ─────────────────

    @Test
    fun checkoutShowsTheWorktreeRowTheBranchHeaderAndOneLoadingRow() {
        val gate = CompletableDeferred<Unit>()
        val refs = GatedRefSource(gate, listOf("main", "dev"))
        val page = NewSessionMenusDemoPage(refSource = refs)
        launch(page)

        compose.onNodeWithTag("composer-chip-branch").performClick()
        compose.waitUntil(5_000) { rowsOf("glass-menu-loading") > 0 }
        assertEquals("exactly one loading row", 1, rowsOf("glass-menu-loading"))
        compose.onNodeWithTag("new-session-menu-worktree").assertExists()
        compose.onNodeWithText("Branch").assertExists()
        assertEquals("no branch rows while the load is suspended", 0, rowsOf("new-session-menu-branch-main"))

        gate.complete(Unit)
        compose.waitUntil(5_000) { rowsOf("new-session-menu-branch-main") > 0 }
        compose.waitUntil(5_000) { rowsOf("glass-menu-loading") == 0 }
        compose.onNodeWithTag("new-session-menu-branch-main").assertIsSelected()
        compose.onNodeWithTag("new-session-menu-branch-dev").assertExists()
        assertEquals("the engine was asked once", 1, refs.calls)
    }

    // ── Checkout: the cache lifetime ───────────────────────────────────

    @Test
    fun reopeningWithoutAPickServesTheCacheAndAPickReloads() {
        val refs = ImmediateRefSource()
        val page = NewSessionMenusDemoPage(refSource = refs)
        launch(page)

        compose.onNodeWithTag("composer-chip-branch").performClick()
        compose.waitUntil(5_000) { rowsOf("new-session-menu-branch-main") > 0 }
        closeTheMenu()
        assertEquals("the first open loaded once", 1, refs.calls)

        // Reopen with no pick in between: the rows at once, no loading
        // frame, no second engine call, main still checked.
        compose.onNodeWithTag("composer-chip-branch").performClick()
        compose.waitUntil(5_000) { rowsOf("new-session-menu-branch-main") > 0 }
        assertEquals("the cache served the second open", 1, refs.calls)
        assertEquals("no loading row on the cached open", 0, rowsOf("glass-menu-loading"))
        compose.onNodeWithTag("new-session-menu-branch-main").assertIsSelected()

        // Pick another ref: the draft changed, so the next open loads again
        // and the picked branch is the selected row. (The ref must be one
        // of NewSessionMenusRefs — ImmediateRefSource serves that list,
        // which has no `dev`.)
        val picked = "fix/ws-reconnect"
        compose.onNodeWithTag("new-session-menu-branch-$picked").performClick()
        compose.waitUntil(5_000) { rowsOf("glass-menu") == 0 }
        compose.onNodeWithTag("composer-chip-branch").assertTextContains(picked, substring = true)
        // Diagnostics (round 35; CI 4d59594c timed out at the `calls == 2`
        // wait, not diagnosed statically): pin WHERE the third open stops.
        // The longer "fix/ws-reconnect" title pushes the chip past the
        // strip's right edge (≈391 vs ≈377 dp by Geist metrics) — the one
        // difference from the passing worktree test. Bounds are captured
        // before the click so the message shows whether the chip was clipped.
        val chipBounds = compose.onNodeWithTag("composer-chip-branch").getUnclippedBoundsInRoot()
        val stripBounds = compose.onNodeWithTag("composer-chip-strip").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("composer-chip-branch").performClick()
        val presented = runCatching { compose.waitUntil(5_000) { rowsOf("glass-menu") > 0 } }.isSuccess
        assertTrue(
            "the third open never presented the menu — chip $chipBounds vs strip $stripBounds " +
                "(a chip right edge past the strip's means the click landed on a clipped chip)",
            presented,
        )
        val loaded = runCatching { compose.waitUntil(5_000) { refs.calls == 2 } }.isSuccess
        assertTrue(
            "the menu is up but the ref source was not called again: calls=${refs.calls}, " +
                "loading rows=${rowsOf("glass-menu-loading")}, " +
                "picked rows=${rowsOf("new-session-menu-branch-$picked")}",
            loaded,
        )
        compose.waitUntil(5_000) { rowsOf("new-session-menu-branch-$picked") > 0 }
        compose.onNodeWithTag("new-session-menu-branch-$picked").assertIsSelected()
    }

    // ── the worktree toggle ────────────────────────────────────────────

    @Test
    fun theWorktreeToggleFlipsTheBranchChipTitleAndStaysChecked() {
        val page = NewSessionMenusDemoPage(refSource = ImmediateRefSource())
        launch(page)

        compose.onNodeWithTag("composer-chip-branch").assertTextEquals("Current branch")
        compose.onNodeWithTag("composer-chip-branch").performClick()
        compose.waitUntil(5_000) { rowsOf("new-session-menu-branch-main") > 0 }
        compose.onNodeWithTag("new-session-menu-worktree").performClick()
        compose.waitUntil(5_000) { rowsOf("glass-menu") == 0 }

        // Ticket 109's title rule, updated by the pick itself.
        compose.onNodeWithTag("composer-chip-branch").assertTextEquals("New worktree")

        // Reopening: the worktree row is selected — and the branch pick
        // (or its absence) survived.
        compose.onNodeWithTag("composer-chip-branch").performClick()
        compose.waitUntil(5_000) { rowsOf("new-session-menu-worktree") > 0 }
        compose.onNodeWithTag("new-session-menu-worktree").assertIsSelected()
    }

    // ── the model pick: chip, hero and the effort default ──────────────

    @Test
    fun aModelPickSwitchesTheChipTheHeroAndTheEffortMiddle() {
        val page = NewSessionMenusDemoPage(refSource = ImmediateRefSource())
        launch(page)

        compose.onNodeWithTag("composer-chip-model").assertTextEquals("Opus 5.5")
        compose.onNodeWithTag("new-session-hero-claude-code").assertExists()

        compose.onNodeWithTag("composer-chip-model").performClick()
        compose.waitUntil(5_000) { rowsOf("new-session-menu-model-codex-gpt-5.5") > 0 }
        compose.onNodeWithTag("new-session-menu-model-claude-code-claude-opus-5-5").assertIsSelected()
        compose.onNodeWithTag("new-session-menu-model-codex-gpt-5.5").performClick()
        compose.waitForIdle()

        // All three in the same frame: the model chip, the hero stand-in's
        // harness, and the effort chip on the codex ladder's middle level
        // (4 levels, index 4 / 2 = 2 → high).
        compose.onNodeWithTag("composer-chip-model").assertTextEquals("GPT-5.5")
        compose.onNodeWithTag("new-session-hero-codex").assertExists()
        compose.onNodeWithTag("composer-chip-effort").assertTextEquals("High")

        // A model without a ladder removes the effort chip entirely.
        compose.onNodeWithTag("composer-chip-model").performClick()
        compose.waitUntil(5_000) { rowsOf("new-session-menu-model-claude-code-claude-haiku-4-5") > 0 }
        compose.onNodeWithTag("new-session-menu-model-claude-code-claude-haiku-4-5").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("composer-chip-effort").assertDoesNotExist()
    }
}
