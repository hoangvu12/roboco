package sh.roboco.android.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Palette
import sh.roboco.android.design.RobocoTheme

/**
 * The row-meta-line goldens, pixel probes and semantics (android-ui-parity
 * #38): the ticket's fixture section in light and dark, the k = 1 geometry
 * probed against `1-off.png`'s measured numbers (tile 54–68 × 39.3–53.3,
 * name origin 75.0/baseline 50.0, branch icon ink from 125.3, badge 38×18
 * at (344, 35)), the X1 overflow fade, the k = 1.6 clamp end, the dark
 * tone table, and the appearance flip recolouring the row in place.
 *
 * Robolectric with NATIVE graphics at xxhdpi (3 px/dp), `w402dp-h760dp`
 * as the ticket pins — the section is hosted with no gallery frame, so
 * row C starts at window (0, 0). The dp oracle is [metaSlotFrames]; these
 * probes check the px path on top of it. Roborazzi paths are relative to
 * `roborazzi { outputDir }` (`src/test/screenshots`), so the goldens land
 * at `src/test/screenshots/sessions/row_meta_{light,dark}.png`; recorded
 * by `./gradlew :app:recordRoborazziDebug --tests …SessionRowMetaScreenshotTest`,
 * verified by `:app:verifyRoborazziDebug`.
 *
 * **Unmerged-tree note.** The ticket's AC words the no-extra-node check as
 * `onAllNodesWithText(…, useUnmergedTree = true)`; Compose's unmerged tree
 * by design still lists every original node — including the ones a
 * `clearAndSetSemantics` ancestor replaced — so that variant is non-empty
 * by construction (37's `SessionRowSemanticsTest` documents the same). The
 * accessibility-visible tree is the **merged** tree, and that is where
 * these checks probe, mirroring 37.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h760dp-xxhdpi")
class SessionRowMetaScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------- goldens

    private fun shot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    SessionRowMetaGallerySection()
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    fun lightGolden() {
        shot(Appearance.Light, "sessions/row_meta_light.png")
    }

    @Test
    fun darkGolden() {
        shot(Appearance.Dark, "sessions/row_meta_dark.png")
    }

    // ----------------------------------------------------------- semantics

    /**
     * 37's label still holds with the slot filled: one row node, the meta
     * text / branch / badge add no node of their own (merged tree), and
     * the fixtures read back their verbatim labels.
     */
    @Test
    fun theRowIsOneNodeAndTheMetaLineAddsNoNodes() {
        hostSection(Appearance.Light)
        // The section shows C twice (k = 1 at y 0 and again at k = 1.6), so
        // both copies must be single labelled nodes.
        compose.onAllNodesWithTag("session-c").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Tool group header colors, zeron, Done, Pull request 77, closed")
            .assertCountEquals(2)
        compose.onNodeWithTag("session-h").assertExists()
        compose.onNodeWithContentDescription("Clean up ~/Downloads, MacBook Pro").assertExists()
        // No separate node for the meta text, the branch or the badge.
        assertTrue(compose.onAllNodesWithText("zeron", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("fix/tool-colors", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("77", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Pull request", substring = true).fetchSemanticsNodes().isEmpty())
    }

    // ------------------------------------------------- k = 1 pixel probes

    /** The tile: 14×14 at (54, 39.3) dp, emerald on C / slate on D / teal on H. */
    @Test
    fun theTileFrameAndTonesRowC() {
        hostSection(Appearance.Light)
        val p = Palette.of(false)
        val px = pixels()
        // Fill-only point (56, 41) dp: left of the letter, clear of the
        // rounded corners. C is colorIndex 5 (emerald #047857), D 0
        // (slate #475569, row y 62), H 6 (teal #0F766E, row y 186).
        assertColorNear(px[168, 123], fill(p, p.projectTone(5)), "C tile fill")
        assertColorNear(px[168, 123 + 186], fill(p, p.projectTone(0)), "D tile fill")
        assertColorNear(px[168, 123 + 558], fill(p, p.projectTone(6)), "H tile fill")
        val tile = inkBounds(px, p.background, 150, 105, 212, 166)!!
        assertPxNear("tile left (54 dp)", tile.x0, 162)
        assertPxNear("tile right (68 dp)", tile.x1, 204)
        assertPxNear("tile top (39.3 dp)", tile.y0, 118)
        assertPxNear("tile bottom (53.3 dp)", tile.y1, 160)
    }

    /** The name: origin x 75.0 (ink 75.7), baseline y 50.0, ink `secondary`. */
    @Test
    fun theNameStartsAtX75AndSitsItsBaselineAtY50() {
        hostSection(Appearance.Light)
        val p = Palette.of(false)
        val px = pixels()
        val name = inkBounds(px, p.background, 220, 120, 340, 165)!!
        // 'z' ink = origin 75.0 + lsb 0.714 → 75.7 dp.
        assertPxNear("name ink left ('z' lsb)", name.x0, 227)
        // "zeron" has no descenders: the lowest ink row is the 'z' baseline
        // bar — the SF-17 strut puts the baseline at 50.03 dp.
        assertPxNear("name baseline (50.0 dp)", name.y1, 150)
        assertColorNear(maxInk(px, p.background, 225, 128, 335, 152)!!, p.secondary, "name ink (#62626A)")
    }

    /** The branch icon: box x 123.6, ink from 125.3, top 39.4, tinted `subline`. */
    @Test
    fun theBranchIconInkStartsAtX125() {
        hostSection(Appearance.Light)
        val p = Palette.of(false)
        val px = pixels()
        val icon = inkBounds(px, p.background, 360, 108, 408, 158)!!
        // The SVG's ink starts 3.5/24 into its box (123.57 + 1.75 = 125.32
        // dp); the (6.5, 5.5) circle (r 2.25, stroke 1.5) has its outer
        // stroke edge at 3.25 − 0.75 = 2.5/24 in (zi_git_branch.xml), so
        // the top ink is 39.41 + 1.25 = 40.66 dp = 121.98 px.
        assertPxNear("branch icon ink left (125.3 dp)", icon.x0, 376)
        assertPxNear("branch icon ink top (40.66 dp)", icon.y0, 122)
        // Only the icon is tinted (the tile carries its own colours).
        assertColorNear(maxInk(px, p.background, 372, 115, 404, 155)!!, over(p.background, p.subline), "icon tint (subline)")
        // The branch text is subline too (#62626A @0.5).
        assertColorNear(
            maxInk(px, p.background, 417, 115, 700, 155)!!,
            over(p.background, p.subline),
            "branch text ink",
            4f / 255f,
        )
    }

    /** The badge: 38×18 at (344, 35) — it exactly fills the 18-tall slot. */
    @Test
    fun theBadgeSitsAtX344AndFillsTheMetaSlotHeight() {
        hostSection(Appearance.Light)
        val p = Palette.of(false)
        val px = pixels()
        val badge = inkBounds(px, p.background, 1020, 100, 1150, 170)!!
        assertPxNear("badge left (344 dp)", badge.x0, 1032)
        assertPxNear("badge right (382 dp)", badge.x1, 1146)
        assertPxNear("badge top (35 dp)", badge.y0, 105)
        assertPxNear("badge bottom (53 dp)", badge.y1, 159)
        // (363, 44) dp: the icon/label gap, a fill-only pixel. C is closed
        // → danger #DC2626 @0.08.
        assertColorNear(px[1089, 132], fill(p, p.danger), "badge fill (closed)")
    }

    /** H, J and X2: no branch icon, no gap; X2's empty branch shows nothing. */
    @Test
    fun rowsWithoutABranchShowNothingAfterTheName() {
        hostSection(Appearance.Light)
        val p = Palette.of(false)
        val px = pixels()
        // The meta band y = row + 35…53 dp. Each probe starts ~5 dp past
        // its name's advance end (tile end 68 + Geist-14 advance of
        // "  <name>"): H "MacBook Pro" 94.4 → 162.4 dp, J "blog" 35.4 →
        // 103.4 dp, X2 "No project" 75.2 → 143.2 dp. A branch would put
        // icon ink 13 + 1.75 dp past the name end, well inside each probe.
        assertTrue("row H has ink after the name", inkBounds(px, p.background, 500, 663, 1140, 717) == null)
        assertTrue("row J has ink after the name", inkBounds(px, p.background, 325, 849, 1140, 903) == null)
        assertTrue("row X2 has ink after the name", inkBounds(px, p.background, 445, 1221, 1140, 1275) == null)
        // Sanity: the names themselves are drawn.
        assertTrue("row H name missing", inkBounds(px, p.background, 225, 663, 430, 717) != null)
        assertTrue("row J name missing", inkBounds(px, p.background, 225, 849, 320, 903) != null)
        assertTrue("row X2 name missing", inkBounds(px, p.background, 225, 1221, 380, 1275) != null)
    }

    /**
     * X1 (row y 310, badge `412` at x 338): the meta text box is 274 dp
     * (54…328); its tail fades over the last 24 dp — never "…".
     */
    @Test
    fun theOverflowingMetaLineFadesItsTailWithNoEllipsis() {
        hostSection(Appearance.Light)
        val p = Palette.of(false)
        val px = pixels()
        // Full ink distance for the meta text (secondary over background).
        val full = maxDiff(p.secondary, p.background)
        val y0 = (310 + 35) * 3
        val y1 = (310 + 53) * 3
        // The ramp: opaque until 304 dp, clear at 328 dp, linear between
        // (EdgeFade). x 304…310 → alpha 0.75…1.0; x 320…327 → ≤ 0.33.
        val midFade = maxDiff(px, p.background, 304 * 3, y0, 310 * 3, y1)
        val endFade = maxDiff(px, p.background, 320 * 3, y0, 327 * 3, y1)
        assertTrue("the fade zone has ink", midFade > 0.7f * full)
        assertTrue("the tail is faded, not full ink (no '…')", endFade < 0.45f * full)
        assertTrue("the mask ramps down", endFade < midFade - 0.25f * full)
        // Rows that fit carry no mask: row J's meta line ends well before
        // its box, so there is no partially-faded ink anywhere in it.
        val jMax = maxDiff(px, p.background, 225, 849, 1140, 903)
        assertTrue("row J is unfaded, its ink is full", jMax >= full - 4f / 255f)
    }

    // ------------------------------------------------- dark tone probes

    /** The dark golden: the ticket's tone table. */
    @Test
    fun darkColoursMatchTheTicketTable() {
        hostSection(Appearance.Dark)
        val p = Palette.of(true)
        val px = pixels()
        // Name ink #A9A9AE; branch icon and text #A9A9AE @0.5.
        assertColorNear(maxInk(px, p.background, 225, 128, 335, 152)!!, p.secondary, "dark name ink")
        assertColorNear(maxInk(px, p.background, 372, 115, 404, 155)!!, over(p.background, p.subline), "dark icon tint")
        assertColorNear(
            maxInk(px, p.background, 417, 115, 700, 155)!!,
            over(p.background, p.subline),
            "dark branch ink",
            4f / 255f,
        )
        // Tiles: C emerald #6EE7B7, D slate #94A3B8, H teal #5EEAD4.
        assertColorNear(px[168, 123], fill(p, p.projectTone(5)), "C tile fill (emerald)")
        assertColorNear(px[168, 123 + 186], fill(p, p.projectTone(0)), "D tile fill (slate)")
        assertColorNear(px[168, 123 + 558], fill(p, p.projectTone(6)), "H tile fill (teal)")
        // Badge fills, one state each: C closed #F87171, E open #34D399
        // (row 124), X1 merged #8B7CF6 (row 310), X3 draft #A9A9AE (row
        // 434) — the (badgeX + 17, row + 44) fill-only points.
        assertColorNear(px[1083, 132], fill(p, p.danger), "C badge fill (closed)")
        assertColorNear(px[1065, (124 + 44) * 3], fill(p, p.success), "E badge fill (open)")
        assertColorNear(px[1065, (310 + 44) * 3], fill(p, p.accent), "X1 badge fill (merged)")
        assertColorNear(px[1104, (434 + 44) * 3], fill(p, p.secondary), "X3 badge fill (draft)")
    }

    // ---------------------------------------------------- k = 1.6 probes

    /**
     * The first k = 1.6 row (C, section y 496): tile 22 dp, baseline row
     * y 78.31, badge s 18 (59×30 at (323, 55.5)).
     */
    @Test
    fun theK16RowCGeometryMatchesTheTable() {
        hostSection(Appearance.Light)
        val p = Palette.of(false)
        val px = pixels()
        val top = 496 * 3
        val tile = inkBounds(px, p.background, 150, top + 175, 240, top + 265)!!
        assertPxNear("k1.6 tile left", tile.x0, 162)
        assertPxNear("k1.6 tile right (76 dp)", tile.x1, 228)
        assertPxNear("k1.6 tile top (61.5 dp)", tile.y0, top + 184, 2)
        assertPxNear("k1.6 tile bottom (83.5 dp)", tile.y1, top + 250, 2)
        // The name's baseline: row y 78.31 → the lowest 'z' ink row.
        val name = inkBounds(px, p.background, 258, top + 192, 350, top + 247)!!
        assertPxNear("k1.6 baseline (row y 78.31)", name.y1, top + 235, 2)
        // The badge: 59 wide from x 323, 30 tall from y 55.5 (the unrounded
        // dp half-point; the px placement rounds it to one px up).
        val badge = inkBounds(px, p.background, 950, top + 162, 1160, top + 272)!!
        assertPxNear("k1.6 badge left (323 dp)", badge.x0, 969)
        assertPxNear("k1.6 badge right (382 dp)", badge.x1, 1146)
        assertPxNear("k1.6 badge top (55.5 dp)", badge.y0, top + 167, 2)
        assertPxNear("k1.6 badge bottom (85.5 dp)", badge.y1, top + 257, 2)
    }

    // ------------------------------------------------- appearance switch

    /** Light ↔ Dark recolours the tile, name, icon and badge with no re-bind. */
    @Test
    fun switchingAppearanceRecoloursTheMetaLineWithoutRebinding() {
        var dark by mutableStateOf(false)
        compose.setContent {
            RobocoTheme(if (dark) Appearance.Dark else Appearance.Light) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    SessionRow(SessionRowMetaFixtures.c)
                }
            }
        }
        compose.waitForIdle()
        val light = Palette.of(false)
        val darkPalette = Palette.of(true)
        val pxLight = pixels()
        assertColorNear(pxLight[168, 123], fill(light, light.projectTone(5)), "light tile fill")
        assertColorNear(maxInk(pxLight, light.background, 225, 128, 335, 152)!!, light.secondary, "light name ink")
        assertColorNear(
            maxInk(pxLight, light.background, 372, 115, 404, 155)!!,
            over(light.background, light.subline),
            "light icon tint",
        )
        val badgeLight = pxLight[1083, 132]
        assertColorNear(badgeLight, fill(light, light.danger), "light badge fill")

        compose.runOnIdle { dark = true }
        compose.waitForIdle()
        val pxDark = pixels()
        assertColorNear(pxDark[168, 123], fill(darkPalette, darkPalette.projectTone(5)), "dark tile fill")
        assertColorNear(maxInk(pxDark, darkPalette.background, 225, 128, 335, 152)!!, darkPalette.secondary, "dark name ink")
        assertColorNear(
            maxInk(pxDark, darkPalette.background, 372, 115, 404, 155)!!,
            over(darkPalette.background, darkPalette.subline),
            "dark icon tint",
        )
        val badgeDark = pxDark[1083, 132]
        assertColorNear(badgeDark, fill(darkPalette, darkPalette.danger), "dark badge fill")
        // Same SessionRowVM instance (a val): the flip redraws in place.
        assertTrue("the tile did not re-render", pxLight[168, 123] != pxDark[168, 123])
        assertTrue("the badge did not re-render", badgeLight != badgeDark)
    }

    // -------------------------------------------------------------- helpers

    /** The section host: no gallery frame, so row C starts at (0, 0). */
    private fun hostSection(appearance: Appearance) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    SessionRowMetaGallerySection()
                }
            }
        }
        compose.waitForIdle()
    }

    private fun pixels(): PixelMap = compose.onRoot().captureToImage().toPixelMap()

    /** iOS `withAlphaComponent` on a tone, over the page background. */
    private fun fill(p: Palette, tone: Color): Color =
        over(p.background, Color(tone.red, tone.green, tone.blue, alpha = 20f / 255f))

    /** [ink] composited over [bg] (the ink keeps its own alpha, e.g. subline 0.5). */
    private fun over(bg: Color, ink: Color): Color = Color(
        red = bg.red * (1f - ink.alpha) + ink.red * ink.alpha,
        green = bg.green * (1f - ink.alpha) + ink.green * ink.alpha,
        blue = bg.blue * (1f - ink.alpha) + ink.blue * ink.alpha,
    )

    private fun assertColorNear(actual: Color, expected: Color, what: String, tol: Float = 2f / 255f) {
        fun ch(a: Float) = (a * 255f).toInt()
        assertTrue(
            "$what: rgb(${ch(actual.red)}, ${ch(actual.green)}, ${ch(actual.blue)}), " +
                "expected rgb(${ch(expected.red)}, ${ch(expected.green)}, ${ch(expected.blue)}) ±${(tol * 255f).toInt()}",
            abs(actual.red - expected.red) <= tol &&
                abs(actual.green - expected.green) <= tol &&
                abs(actual.blue - expected.blue) <= tol,
        )
    }

    private fun assertPxNear(what: String, actual: Int, expected: Int, tol: Int = 1) {
        assertTrue("$what: $actual px, expected $expected ±$tol", abs(actual - expected) <= tol)
    }

    /** The bounds of every pixel differing from [bg] by more than 5/255 in any channel. */
    private class Bounds(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

    private fun inkBounds(
        px: PixelMap,
        bg: Color,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        threshold: Float = 5f / 255f,
    ): Bounds? {
        var bx0 = -1
        var by0 = -1
        var bx1 = -1
        var by1 = -1
        for (y in y0..y1) {
            for (x in x0..x1) {
                val c = px[x, y]
                if (abs(c.red - bg.red) > threshold || abs(c.green - bg.green) > threshold || abs(c.blue - bg.blue) > threshold) {
                    if (bx0 < 0 || x < bx0) bx0 = x
                    if (bx1 < 0 || x > bx1) bx1 = x
                    if (by0 < 0 || y < by0) by0 = y
                    if (by1 < 0 || y > by1) by1 = y
                }
            }
        }
        return if (bx0 < 0) null else Bounds(bx0, by0, bx1, by1)
    }

    /** The pixel with the greatest channel distance from [bg] — full ink. */
    private fun maxInk(px: PixelMap, bg: Color, x0: Int, y0: Int, x1: Int, y1: Int): Color? {
        var best: Color? = null
        var bestDiff = 0f
        for (y in y0..y1) {
            for (x in x0..x1) {
                val c = px[x, y]
                val d = max(abs(c.red - bg.red), max(abs(c.green - bg.green), abs(c.blue - bg.blue)))
                if (d > bestDiff) {
                    bestDiff = d
                    best = c
                }
            }
        }
        return best
    }

    private fun maxDiff(a: Color, b: Color): Float =
        max(abs(a.red - b.red), max(abs(a.green - b.green), abs(a.blue - b.blue)))

    private fun maxDiff(px: PixelMap, bg: Color, x0: Int, y0: Int, x1: Int, y1: Int): Float {
        var m = 0f
        for (y in y0..y1) {
            for (x in x0..x1) {
                val c = px[x, y]
                m = max(m, maxDiff(c, bg))
            }
        }
        return m
    }
}
