package sh.roboco.android.newsession

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GalleryBackdrop
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuPanel
import sh.roboco.android.design.glass.MenuLine
import sh.roboco.android.design.glass.reservesLeadingSlot
import sh.roboco.android.design.uiStyle

/**
 * The New-session menus' goldens (android-ui-parity #114), recorded by
 * `recordRoboazziDebug` and verified by `verifyRoborazziDebug`: the
 * ticket's six panels — K1 (Checkout, loading), K2 (loaded, worktree
 * on), K3 (loaded, branch picked), M (Model), E1 (effort, nothing
 * picked) and E2 (effort, picked) — side by side over ticket 22's
 * [GalleryBackdrop], in light and dark, on a 1000 × 900 dp mdpi window
 * (2 images under `src/test/screenshots/newsession/`).
 *
 * In the golden, check against the Spec: K1's titles sit at 20 dp
 * (nothing checked yet) and K2/K3/M/E2's at 61 dp; K1–K3 carry exactly
 * one separator, between "New worktree" and the "Branch" header; in M
 * the Claude mark keeps its `#D97757` in both modes, the Codex mark
 * takes the icon ink, each harness header is ≥ 30 dp with its 16 dp
 * mark centred 36 dp from the panel's leading edge, and the sections
 * read Claude Code before Codex with Opus 5.5 checked.
 *
 * The same file pins the panels' flattened line shapes and the
 * leading-slot rule (the "titles at 20 / 61" consequence of ticket 15's
 * slot rule) as plain assertions, so a red golden is easier to read.
 * Robolectric with NATIVE graphics and ticket 07's fallback fill
 * (`Glass.forceFallback = true`, the golden convention): blur does not
 * run there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w1000dp-h900dp-mdpi")
class CheckoutModelEffortMenusScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun stableGoldens() {
        Glass.forceFallback = true
    }

    @After
    fun restoreTiers() {
        Glass.forceFallback = false
    }

    // ── goldens ───────────────────────────────────────────────────────

    @Test
    fun galleryLight() {
        goldenShot(Appearance.Light, "newsession/new_session_menus_light.png")
    }

    @Test
    fun galleryDark() {
        goldenShot(Appearance.Dark, "newsession/new_session_menus_dark.png")
    }

    private fun goldenShot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    GalleryBackdrop(Modifier.fillMaxSize())
                    Box(Modifier.padding(16.dp)) { GoldenPanels() }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    /** The golden's content: the six panels in three columns, captioned. */
    @Composable
    private fun GoldenPanels() {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                GoldenPanel("K1 · Checkout, loading", checkoutLoadingLines())
                GoldenPanel("K2 · Checkout, loaded, worktree on", checkoutLoadedLines(worktree = true, branch = null))
            }
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                GoldenPanel(
                    "K3 · Checkout, branch picked",
                    checkoutLoadedLines(worktree = false, branch = "fix/ws-reconnect"),
                )
                GoldenPanel("M · Model", modelMenuLines())
            }
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                GoldenPanel("E1 · Effort, nothing picked", effortMenuLines(NewSessionMenusFable51, null))
                GoldenPanel("E2 · Effort, picked", effortMenuLines(NewSessionMenusFable51, "low"))
            }
        }
    }

    @Composable
    private fun GoldenPanel(caption: String, lines: List<MenuLine>) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(
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

    // ── the panels' line shapes (the goldens' content, asserted) ───────

    @Test
    fun k1FlattensToTitleRowSeparatorHeaderAndTheLoadingRow() {
        assertEquals(
            listOf("Title", "Row", "Sep", "Header", "Loading"),
            checkoutLoadingLines().map(::lineShape),
        )
    }

    @Test
    fun k2AndMFlattenToTheSpecLineOrder() {
        assertEquals(
            listOf("Title", "Row", "Sep", "Header", "Row", "Row", "Row", "Row"),
            checkoutLoadedLines(worktree = true, branch = null).map(::lineShape),
        )
        assertEquals(
            listOf("Title", "Header", "Row", "Row", "Row", "Row", "Row", "Row", "Sep", "Header", "Row", "Row", "Row"),
            modelMenuLines().map(::lineShape),
        )
    }

    /** The leading-slot rule's consequence: 20 dp titles where nothing is checked, 61 dp where something is. */
    @Test
    fun theLeadingSlotFollowsEachPanelsCheckedState() {
        assertFalse("K1: nothing checked yet, no icon — titles at 20", reservesLeadingSlot(checkoutLoadingLines()))
        assertTrue("K2: the worktree row is checked", reservesLeadingSlot(checkoutLoadedLines(worktree = true, branch = null)))
        assertTrue("K3: a branch is checked", reservesLeadingSlot(checkoutLoadedLines(worktree = false, branch = "fix/ws-reconnect")))
        assertTrue("M: every header carries a brand mark", reservesLeadingSlot(modelMenuLines()))
        assertFalse("E1: nothing picked", reservesLeadingSlot(effortMenuLines(NewSessionMenusFable51, null)))
        assertTrue("E2: low is picked", reservesLeadingSlot(effortMenuLines(NewSessionMenusFable51, "low")))
    }

    /** One line of a flattened level, as a shape string (lambdas compare by identity). */
    private fun lineShape(line: MenuLine): String = when (line) {
        is MenuLine.Title -> "Title"
        is MenuLine.Back -> "Back"
        is MenuLine.Header -> "Header"
        MenuLine.Separator -> "Sep"
        is MenuLine.Row -> "Row"
        is MenuLine.Submenu -> "Submenu"
        is MenuLine.Loading -> "Loading"
    }
}
