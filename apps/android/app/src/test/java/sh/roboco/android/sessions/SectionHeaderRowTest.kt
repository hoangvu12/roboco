package sh.roboco.android.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LinearFontScaleDensity
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.TypeScale

/**
 * The foldable header row's semantics, height, pressed fill and chevron
 * spring (android-ui-parity #39), on Robolectric with NATIVE graphics at
 * **mdpi** (`w402dp-h480dp-mdpi`) — px = dp — the ticket's width, with
 * the glyph clock frozen at t = 0 so the spinner row is byte-stable.
 *
 * The single-a11y-node and children-cleared checks read the **merged**
 * tree (the tree accessibility services see): the row's
 * `clearAndSetSemantics` clears the descendants there. The ticket's AC
 * words the check as `useUnmergedTree = true`, but Compose's unmerged
 * tree by design still lists every original node — including the ones a
 * clearing ancestor replaced — so that variant is non-empty by
 * construction (tickets 37 and 38 document the same); the merged tree is
 * where the observable result lives.
 *
 * The chevron checks compare captures of the row's pixels at frozen-clock
 * checkpoints: the settled expanded (0°) and collapsed (−90°) renders,
 * the mid-flight frame (≈ −63° at 80 ms, `Motion.SectionChevron`), and a
 * row composed already-collapsed, which must render the collapsed chevron
 * in its **first** frame (no animation on first composition).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h480dp-mdpi")
class SectionHeaderRowTest {

    @get:Rule
    val compose = createComposeRule()

    /** The ticket's "P0 2" fixture with a working session. */
    private val p0 = SectionHeaderState("p0", "P0", 2, collapsed = false, live = SectionLive.Spinner)

    // -------------------------------------------------------------- helpers

    /**
     * Hosts [row] the way ticket 41 does: on the page background, inside
     * the theme, with the glyph clock frozen at 0. [fontScale] overrides
     * both `LocalDensity` (outside the theme, so its `TypeScale.from` sees
     * it) and `LocalTypeScale` — ticket 22's linear trick, exact at
     * 0.85 / 1 / 1.6. It must be [LinearFontScaleDensity]: a plain
     * `Density(1f, 1.6f)` goes through Android's non-linear font-scale
     * table (17 sp ≈ 24.6 dp → k ≈ 1.45, a 58 dp row), not k = 1.6.
     */
    private fun host(fontScale: Float = 1f, row: @Composable () -> Unit) {
        val d = LinearFontScaleDensity(1f, fontScale)
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides d,
                LocalTypeScale provides TypeScale.from(d),
            ) {
                RobocoTheme(Appearance.Light) {
                    ProvideGlyphClock(frozenAtNanos = 0L) {
                        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                            row()
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** The row's pixels at mdpi (px = dp), captured from its own bounds. */
    private fun pixels(tag: String = "section-p0"): PixelMap =
        compose.onNodeWithTag(tag).captureToImage().toPixelMap()

    private fun assertPixel(px: PixelMap, x: Int, y: Int, r: Int, g: Int, b: Int, what: String) {
        val c = px[x, y]
        fun ok(v: Float, e: Int) = abs(v - e / 255f) <= 2f / 255f
        assertTrue(
            "$what at ($x, $y): rgb(${(c.red * 255f).roundToInt()}, ${(c.green * 255f).roundToInt()}, " +
                "${(c.blue * 255f).roundToInt()}), expected ($r, $g, $b) ±2",
            ok(c.red, r) && ok(c.green, g) && ok(c.blue, b),
        )
    }

    /** Pixels that differ between two same-size captures. */
    private fun differingPixels(a: PixelMap, b: PixelMap): Int {
        assertEquals("capture widths", a.width, b.width)
        assertEquals("capture heights", a.height, b.height)
        var diff = 0
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (a[x, y] != b[x, y]) diff++
            }
        }
        return diff
    }

    // ------------------------------------------------------------- heights

    @Test
    fun theRowIs40dpAtFontScale1() {
        host { SectionHeaderRow(p0, highlight = { 0f }) }
        compose.onNodeWithTag("section-p0").assertHeightIsEqualTo(40.dp)
    }

    @Test
    fun atFontScale1_6TheRowIs64dp() {
        host(fontScale = 1.6f) { SectionHeaderRow(p0, highlight = { 0f }) }
        compose.onNodeWithTag("section-p0").assertHeightIsEqualTo(64.dp)
    }

    @Test
    fun atFontScale0_85TheRowIs34dp() {
        host(fontScale = 0.85f) { SectionHeaderRow(p0, highlight = { 0f }) }
        compose.onNodeWithTag("section-p0").assertHeightIsEqualTo(34.dp)
    }

    // ----------------------------------------------------------- semantics

    /**
     * The 41-style host: the `clickable` (indication = null, Role.Button)
     * sits in the row's modifier, outside the row's own semantics.
     */
    private fun hostClickable(state: () -> SectionHeaderState, onClick: () -> Unit = {}) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        val src = remember { MutableInteractionSource() }
                        SectionHeaderRow(
                            state(),
                            highlight = { 0f },
                            Modifier.clickable(
                                interactionSource = src,
                                indication = null,
                                role = Role.Button,
                            ) { onClick() },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theRowIsOneButtonNodeWithTheJoinedLabelAndState() {
        var collapsed by mutableStateOf(false)
        hostClickable({ p0.copy(collapsed = collapsed) })
        val config = compose.onNodeWithTag("section-p0").fetchSemanticsNode().config
        assertEquals(Role.Button, config[SemanticsProperties.Role])
        assertEquals(listOf("P0, 2"), config[SemanticsProperties.ContentDescription])
        assertEquals("Expanded", config[SemanticsProperties.StateDescription])

        compose.runOnUiThread { collapsed = true }
        compose.waitForIdle()
        assertEquals(
            "Collapsed",
            compose.onNodeWithTag("section-p0").fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
    }

    @Test
    fun theChildrenAreClearedFromTheMergedTree() {
        hostClickable({ p0 })
        // The merged tree carries neither the title nor the count — one
        // node, whose label already holds both (the live glyph is not
        // part of the label, iOS parity).
        assertTrue(compose.onAllNodesWithText("P0").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("2").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun performClickFiresTheHostsClickableExactlyOnce() {
        var clicks = 0
        hostClickable({ p0 }, onClick = { clicks++ })
        compose.onNodeWithTag("section-p0").performClick()
        compose.waitForIdle()
        assertEquals(1, clicks)
        compose.onNodeWithTag("section-p0").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(2, clicks)
    }

    @Test
    fun withoutAClickableTheRowHasNoClickActionOfItsOwn() {
        host { SectionHeaderRow(p0, highlight = { 0f }) }
        assertNull(compose.onNodeWithTag("section-p0").fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick))
    }

    // ------------------------------------------------------ the fill pixels

    @Test
    fun atRestThePageShowsThrough() {
        host { SectionHeaderRow(p0, highlight = { 0f }) }
        val px = pixels()
        // background #F3F3F5 at the centre and left of the 8 dp inset.
        assertPixel(px, px.width / 2, px.height / 2, 243, 243, 245, "rest centre")
        assertPixel(px, 4, px.height / 2, 243, 243, 245, "rest side inset")
    }

    @Test
    fun fullHighlightCompositesControlFillOverBackground() {
        host { SectionHeaderRow(p0, highlight = { 1f }) }
        val px = pixels()
        // controlFill (0x1327272C) over background ≈ #E4E4E6 at the
        // centre… and the 8 dp side inset stays the page.
        assertPixel(px, px.width / 2, px.height / 2, 228, 228, 230, "pressed centre")
        assertPixel(px, 4, px.height / 2, 243, 243, 245, "pressed side inset")
    }

    @Test
    fun aHighlightChangeRedrawsWithoutRecomposingTheRow() {
        var h by mutableStateOf(1f)
        var recompositions = 0
        host {
            Column {
                SideEffect { recompositions++ }
                SectionHeaderRow(p0, highlight = { h })
            }
        }
        val px1 = pixels()
        assertPixel(px1, px1.width / 2, px1.height / 2, 228, 228, 230, "before the change")
        val counted = recompositions

        // Only the draw phase reads h: the change redraws, the wrapper
        // (and the row) never recomposes. No ripple anywhere (there is no
        // indication; the fill appears and vanishes at once).
        compose.runOnUiThread { h = 0f }
        compose.waitForIdle()
        val px2 = pixels()
        assertPixel(px2, px2.width / 2, px2.height / 2, 243, 243, 245, "after the change")
        assertEquals("recompositions during the change", counted, recompositions)
    }

    // ------------------------------------------------------- chevron spring

    @Test
    fun aFoldedRowRendersTheRightChevronInItsFirstFrameAndNeverAnimates() {
        compose.mainClock.autoAdvance = false
        // A row composed already-collapsed: the first render must be the
        // settled −90° (right-pointing) chevron, not an animation start.
        host { SectionHeaderRow(p0.copy(collapsed = true), highlight = { 0f }) }
        val first = pixels()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val settled = pixels()
        assertEquals("the first frame is already the collapsed render", 0, differingPixels(first, settled))
    }

    @Test
    fun togglingCollapsedOnAComposedRowAnimatesTheChevronWithTheSectionSpring() {
        compose.mainClock.autoAdvance = false
        var collapsed by mutableStateOf(false)
        // A second row, composed collapsed from scratch, is hosted alongside
        // (setContent may run only once per test): its render is the
        // reference the settled spring must equal.
        host {
            Column {
                SectionHeaderRow(p0.copy(collapsed = collapsed), highlight = { 0f })
                SectionHeaderRow(p0.copy(id = "fresh", collapsed = true), highlight = { 0f })
            }
        }
        val expandedRest = pixels() // 0°, settled

        compose.runOnUiThread { collapsed = true }
        // The write happened outside composition; on the paused clock the
        // Recomposer only learns of it once apply notifications go out
        // (GlobalSnapshotManager posts that to the main Looper, which
        // advanceTimeBy* never idles), so send them before the frames.
        Snapshot.sendApplyNotifications()
        // One frame of launch alignment, then 80 ms: the spring is
        // analytically at ≈ −63° there — strictly between the ends. (No
        // waitForIdle between the flip and the frame: with the clock
        // paused, the pending recomposition runs on the advanced frame.)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        val mid = pixels()

        compose.mainClock.advanceTimeBy(600) // 680 ms total: well past settle
        compose.waitForIdle()
        val settledCollapsed = pixels()

        assertTrue(
            "the 80 ms frame is mid-flight (differs from the 0° rest)",
            differingPixels(mid, expandedRest) > 4,
        )
        assertTrue(
            "the 80 ms frame has not reached −90° yet",
            differingPixels(mid, settledCollapsed) > 4,
        )

        // The settled render equals a row composed collapsed from
        // scratch: the spring rests at exactly the first-frame render.
        val freshCollapsed = pixels("section-fresh")
        assertEquals("settled == first-frame collapsed render", 0, differingPixels(settledCollapsed, freshCollapsed))
    }
}
