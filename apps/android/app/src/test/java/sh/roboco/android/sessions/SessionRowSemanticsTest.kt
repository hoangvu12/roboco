package sh.roboco.android.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.TypeScale

/**
 * The row's semantics and its highlight pixels (android-ui-parity #37), on
 * the ticket's `w411dp` phone at xxhdpi (1 dp = 3 px), font scale 1.
 * NATIVE graphics mode for [androidx.compose.ui.test.SemanticsNodeInteraction.captureToImage].
 *
 * The single-a11y-node checks read the **merged** tree (the tree
 * accessibility services see): the row's `clearAndSetSemantics` clears the
 * descendants' semantics there, so the title, the corner word and the time
 * label are not separately focusable. The unmerged tree by design still
 * contains every original node "including even the ones replaced by
 * `clearAndSetSemantics`" (Android docs), so it is not the place to probe
 * exposure — the merged tree is.
 *
 * The highlight pixel checks probe `(W/2, H/2)` of an empty-title row over
 * the page background and `(4dp, H/2)` inside the 8 dp side inset, with the
 * ticket's composited light-mode hexes (±2 per channel), and the fade
 * redrawing without recomposing the row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class SessionRowSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    /** A quiet row: seen, Idle, no harness (the Claude mark), 1 day old. */
    private fun vm(title: String = "T") = SessionRowVM(
        id = "abc",
        title = title,
        projectName = "zeron",
        hasProject = true,
        colorIndex = 6,
        harness = null,
        branch = null,
        pr = null,
        prNumber = null,
        status = SessionRowVM.Status.Idle,
        timeLabel = "1d",
        unseen = false,
        pinned = false,
        sendFailed = false,
    )

    private fun host(
        vm: SessionRowVM,
        isCurrent: Boolean = false,
        highlight: () -> Float = { 0f },
        onRecompose: (() -> Unit)? = null,
    ) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                // Opaque page background behind the row: the fill's rounded
                // corners are transparent, so probes must read a known page.
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    Column {
                        if (onRecompose != null) SideEffect(onRecompose)
                        SessionRow(vm, isCurrent = isCurrent, highlight = highlight)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    // ------------------------------------------------------------ a11y node

    @Test
    fun theRowIsOneTaggedNodeWithTheJoinedLabel() {
        val vm = vm()
        host(vm)
        compose.onNodeWithTag("session-abc").assertExists()
        compose.onNodeWithContentDescription(sessionRowA11yLabel(vm)).assertExists()
    }

    @Test
    fun noDescendantSemanticsAreExposed() {
        val vm = vm()
        host(vm)
        // The merged tree carries neither the title text nor the time
        // label — only the row node, whose label holds the corner word
        // (never the time) per SessionCell.swift:152.
        compose.onNodeWithText("T").assertDoesNotExist()
        compose.onNodeWithText("1d").assertDoesNotExist()
        assertTrue(compose.onAllNodesWithText("T").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("1d").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theRowIs62dpAtFontScale1() {
        host(vm())
        compose.onNodeWithTag("session-abc").assertHeightIsEqualTo(62.dp)
    }

    @Test
    fun atFontScale1_3TheRowHeightFollowsTheTypeScale() {
        // LocalDensity is provided OUTSIDE RobocoTheme, so the theme's
        // TypeScale.from(density) sees the font scale.
        val expected = TypeScale.from(Density(3f, 1.3f)).scaledDp(62f)
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(3f, 1.3f),
            ) {
                RobocoTheme(Appearance.Light) { SessionRow(vm()) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("session-abc").assertHeightIsEqualTo(expected)
    }

    // -------------------------------------------------- highlight pixels

    /** The row's image at 3 px/dp. */
    private fun pixels(): PixelMap = compose.onNodeWithTag("session-abc").captureToImage().toPixelMap()

    private fun assertPixel(px: PixelMap, x: Int, y: Int, r: Int, g: Int, b: Int, what: String) {
        val c = px[x, y]
        fun ok(v: Float, e: Int) = abs(v - e / 255f) <= 2f / 255f
        assertTrue(
            "$what at ($x, $y): rgb(${(c.red * 255f).roundToInt()}, ${(c.green * 255f).roundToInt()}, " +
                "${(c.blue * 255f).roundToInt()}), expected ($r, $g, $b) ±2",
            ok(c.red, r) && ok(c.green, g) && ok(c.blue, b),
        )
    }

    @Test
    fun atRestThePageShowsThrough() {
        host(vm(title = ""), highlight = { 0f })
        val px = pixels()
        // background #F3F3F5 at the centre and left of the 8 dp side inset.
        assertPixel(px, px.width / 2, px.height / 2, 243, 243, 245, "rest centre")
        assertPixel(px, 12, px.height / 2, 243, 243, 245, "rest side inset")
    }

    @Test
    fun fullHighlightCompositesControlFillOverBackground() {
        host(vm(title = ""), highlight = { 1f })
        val px = pixels()
        // controlFill (0x1327272C) over background ≈ #E4E4E6 at the centre…
        assertPixel(px, px.width / 2, px.height / 2, 228, 228, 230, "pressed centre")
        // …and the 8 dp side inset stays the page.
        assertPixel(px, 12, px.height / 2, 243, 243, 245, "pressed side inset")
    }

    @Test
    fun currentDrawsRowActiveAtRest() {
        host(vm(title = ""), isCurrent = true, highlight = { 0f })
        val px = pixels()
        // rowActive (white @ 0.9) over background ≈ #FEFEFE.
        assertPixel(px, px.width / 2, px.height / 2, 254, 254, 254, "current centre")
    }

    @Test
    fun currentAndPressedShowsOnlyControlFill() {
        host(vm(title = ""), isCurrent = true, highlight = { 1f })
        val px = pixels()
        // controlFill replaces rowActive — no white underneath.
        assertPixel(px, px.width / 2, px.height / 2, 228, 228, 230, "current+pressed centre")
    }

    @Test
    fun aMidFadeSitsBetweenCurrentAndPressed() {
        host(vm(title = ""), isCurrent = true, highlight = { 0.5f })
        val px = pixels()
        // Half rowActive under half controlFill: rowActive @0.45 over the
        // page ≈ 248, then controlFill @0.0375 over that ≈ 240–242 —
        // clearly between #FEFEFE (current) and #E4E4E6 (pressed).
        assertPixel(px, px.width / 2, px.height / 2, 241, 241, 242, "mid-fade centre")
    }

    @Test
    fun aFadeRedrawsWithoutRecomposingTheRow() {
        var h by mutableFloatStateOf(1f)
        var recompositions = 0
        host(vm(title = ""), highlight = { h }) { recompositions++ }
        val px1 = pixels()
        assertPixel(px1, px1.width / 2, px1.height / 2, 228, 228, 230, "before the fade")
        val counted = recompositions

        // Only the draw phase reads h: the change redraws, the wrapper
        // (and the row) never recomposes.
        compose.runOnUiThread { h = 0f }
        compose.waitForIdle()
        val px2 = pixels()
        assertPixel(px2, px2.width / 2, px2.height / 2, 243, 243, 245, "after the fade")
        assertEquals("recompositions during the fade", counted, recompositions)
    }
}
