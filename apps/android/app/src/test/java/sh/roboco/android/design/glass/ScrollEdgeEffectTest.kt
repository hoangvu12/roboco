package sh.roboco.android.design.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.Motion
import sh.roboco.android.design.RobocoTheme

/**
 * The scroll-edge effect's pure logic (ticket 09): the band height, the
 * layer truth table and the scrim brush, as a **plain JUnit** class — no
 * Robolectric runner, no composition. `ScrollEdgeEffectDefaults.height(24.dp)`
 * compiling against the pure overload here also proves the overload
 * resolution the ticket's KDoc warns about (the parameterless composable
 * `height()` cannot capture a one-argument call).
 */
class ScrollEdgeEffectMathTest {

    @Test
    fun heightComposesStatusBarPlusBarPlusExtension() {
        assertEquals(84.dp, ScrollEdgeEffectDefaults.height(24.dp))
        assertEquals(60.dp, ScrollEdgeEffectDefaults.height(0.dp))
        assertEquals(90.dp, ScrollEdgeEffectDefaults.height(24.dp, barHeight = 50.dp))
    }

    @Test
    fun engageDurationIsTheGlassDematerializeTime() {
        assertEquals(250, ScrollEdgeEffectDefaults.EngageMillis)
        assertEquals(Motion.GlassOutMs, ScrollEdgeEffectDefaults.EngageMillis)
    }

    @Test
    fun layersTruthTable() {
        assertEquals(ScrollEdgeLayers.None, scrollEdgeLayers(engagedAlpha = 0f, blur = true, hasHazeState = true))
        assertEquals(ScrollEdgeLayers.BlurAndScrim, scrollEdgeLayers(engagedAlpha = 0.5f, blur = true, hasHazeState = true))
        assertEquals(ScrollEdgeLayers.ScrimOnly, scrollEdgeLayers(engagedAlpha = 1f, blur = false, hasHazeState = true))
        assertEquals(ScrollEdgeLayers.ScrimOnly, scrollEdgeLayers(engagedAlpha = 1f, blur = true, hasHazeState = false))
    }

    @Test
    fun scrimRunsTopAlphaToClearWithEngagedAlphaMultipliedIn() {
        val bg = Color(0xFFF3F3F5)
        assertEquals(
            Brush.verticalGradient(
                0f to bg.copy(alpha = ScrollEdgeEffectDefaults.TopScrimAlpha),
                1f to bg.copy(alpha = 0f),
            ),
            scrollEdgeScrim(bg, engagedAlpha = 1f),
        )
        assertEquals(
            Brush.verticalGradient(
                0f to bg.copy(alpha = ScrollEdgeEffectDefaults.TopScrimAlpha * 0.5f),
                1f to bg.copy(alpha = 0f),
            ),
            scrollEdgeScrim(bg, engagedAlpha = 0.5f),
        )
    }
}

/**
 * The scroll-edge effect's rendering behaviour (ticket 09): pixel probes of
 * the scrim-only fallback over a solid `#FF0000` source at **mdpi** (1 dp =
 * 1 px), the pass-through hit testing, the no-semantics rule, the 250 ms
 * easeInOut engagement fade and its Reduce Motion snap.
 *
 * The probes read the effect at `blur = false` with **no** `ProvideHazeState`
 * (the default `LocalHazeState` is null), which also pins the null-state
 * safety: layer 2 only, no crash. The 0.85/0.425/0 alphas of the acceptance
 * are the gradient's stops; each probe's pixel sits half a pixel inside its
 * row, which stays within the ±3/255 tolerance.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h400dp-mdpi")
class ScrollEdgeEffectTest {

    @get:Rule
    val compose = createComposeRule()

    private val red = Color(0xFFFF0000)
    private val lightBackground = Color(0xFFF3F3F5)
    private val darkBackground = Color(0xFF060606)

    // ----------------------------------------------------------- helpers

    /** The 120 × 60 dp red source with the effect over it, as a pixel map. */
    private fun bandPixels(): PixelMap =
        compose.onNodeWithTag("band").captureToImage().toPixelMap()

    /** Composes the 120 × 60 dp red source with a scrim-only effect on top. */
    private fun redSource(appearance: Appearance = Appearance.Light, engaged: () -> Boolean) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.testTag("band").size(120.dp, 60.dp).background(red)) {
                    ScrollEdgeEffect(height = 60.dp, engaged = engaged(), blur = false)
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * Asserts the pixel at (x, y) is the source-over blend of the scrim
     * colour over the red source at [alpha]: per channel
     * `red + alpha · (bg − red)`, within ±3/255.
     */
    private fun assertBlend(px: PixelMap, x: Int, y: Int, alpha: Float, bg: Color, where: String) {
        val c = px[x, y]
        val tolerance = 3f / 255f
        fun channel(name: String, source: Float, target: Float, actual: Float) {
            val expected = source + alpha * (target - source)
            assertTrue(
                "$where ($x,$y): $name $actual not within ±3/255 of $expected",
                abs(actual - expected) <= tolerance,
            )
        }
        channel("red", 1f, bg.red, c.red)
        channel("green", 0f, bg.green, c.green)
        channel("blue", 0f, bg.blue, c.blue)
    }

    /** Asserts the pixel is exactly the red source (no draw), ±1/255. */
    private fun assertPureRed(px: PixelMap, x: Int, y: Int, where: String) {
        val c = px[x, y]
        assertTrue("$where ($x,$y) is not pure red: $c", abs(c.red - 1f) <= 1f / 255f)
        assertTrue("$where ($x,$y) is not pure red: $c", c.green <= 1f / 255f)
        assertTrue("$where ($x,$y) is not pure red: $c", c.blue <= 1f / 255f)
    }

    /**
     * Solves the engaged alpha from the band's top pixel row: the source has
     * no green, so `green = 0.85 · a · bg.green`.
     */
    private fun engagedAlphaAtTop(bg: Color): Float {
        val green = bandPixels()[60, 0].green
        return (green / (ScrollEdgeEffectDefaults.TopScrimAlpha * bg.green)).coerceIn(0f, 1f)
    }

    // ------------------------------------------------- the parameterless height

    @Test
    fun parameterlessHeightReadsTheStatusBarInset() {
        val observed = mutableListOf<Dp>()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                observed.add(ScrollEdgeEffectDefaults.height())
            }
        }
        compose.waitForIdle()
        // Robolectric test windows report no status bar, so this pins
        // 0 + 44 + 16 = 60 dp; on a device the real inset adds on top (the
        // composable reads WindowInsets.statusBars, unlike the pure overload).
        assertTrue("height() never composed", observed.isNotEmpty())
        assertTrue("height() ≠ statusBar + 60 dp: $observed", observed.all { it == 60.dp })
        assertEquals(ScrollEdgeEffectDefaults.height(0.dp), observed.last())
    }

    // ------------------------------------------------------- the pixel probes

    @Test
    fun scrimFadesRedIntoTheLightBackground() {
        var engaged by mutableStateOf(true)
        redSource { engaged }
        val px = bandPixels()
        assertBlend(px, 60, 0, ScrollEdgeEffectDefaults.TopScrimAlpha, lightBackground, "top row")
        assertBlend(px, 60, 30, ScrollEdgeEffectDefaults.TopScrimAlpha / 2f, lightBackground, "middle row")
        assertBlend(px, 60, 59, 0f, lightBackground, "bottom row")
    }

    @Test
    fun scrimFadesRedIntoTheDarkBackground() {
        redSource(Appearance.Dark) { true }
        val px = bandPixels()
        assertBlend(px, 60, 0, ScrollEdgeEffectDefaults.TopScrimAlpha, darkBackground, "dark top row")
        assertBlend(px, 60, 30, ScrollEdgeEffectDefaults.TopScrimAlpha / 2f, darkBackground, "dark middle row")
        assertBlend(px, 60, 59, 0f, darkBackground, "dark bottom row")
    }

    @Test
    fun disengagedBandDrawsNothingOnceSettled() {
        var engaged by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        redSource { engaged }
        compose.runOnIdle { engaged = false }
        // With the clock frozen, waitForIdle is what delivers the write to
        // the Recomposer (it sends the snapshot apply notifications); a bare
        // advanceTimeBy never recomposes it (CI r17: still fully engaged).
        compose.waitForIdle()
        // Let the 250 ms fade-out finish (the None branch of the layer
        // table) — plus the recompose frame and the animation's start frame
        // — then every pixel must be the untouched source.
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        val px = bandPixels()
        for (x in intArrayOf(0, 30, 60, 90, 119)) {
            for (y in intArrayOf(0, 1, 15, 30, 45, 58, 59)) {
                assertPureRed(px, x, y, "disengaged band")
            }
        }
    }

    // --------------------------------------------------- hit testing, semantics

    @Test
    fun tapsInsideTheBandReachTheContentUnderneath() {
        var taps = 0
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .testTag("row")
                            .fillMaxWidth()
                            .height(60.dp)
                            .background(red)
                            .clickable { taps++ },
                    )
                    ScrollEdgeEffect(height = 60.dp, engaged = true, blur = false)
                }
            }
        }
        compose.waitForIdle()
        // y = 10 dp and y = height − 2 dp, at the horizontal centre: the band
        // is no hit target, so both taps land on the row's clickable.
        compose.onNodeWithTag("row").apply {
            performTouchInput { click(Offset(centerX, 10.dp.toPx())) }
            performTouchInput { click(Offset(centerX, 58.dp.toPx())) }
        }
        compose.runOnIdle { assertEquals("the band swallowed a tap", 2, taps) }
    }

    @Test
    fun addsNoSemanticsNodes() {
        var withEffect by mutableStateOf(false)
        var engaged by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .testTag("row")
                            .fillMaxWidth()
                            .height(60.dp)
                            .background(red)
                            .clickable { },
                    )
                    if (withEffect) ScrollEdgeEffect(height = 60.dp, engaged = engaged, blur = false)
                }
            }
        }
        compose.waitForIdle()
        val withoutEffect = compose.onAllNodes(isRoot().not()).fetchSemanticsNodes().size
        // The row's clickable is the only non-root node to begin with.
        assertEquals(1, withoutEffect)

        compose.runOnIdle { withEffect = true }
        compose.waitForIdle()
        assertEquals(
            "the drawing branch added semantics nodes",
            withoutEffect,
            compose.onAllNodes(isRoot().not()).fetchSemanticsNodes().size,
        )

        // And the None branch (disengaged, settled): the empty spacer adds none.
        compose.runOnIdle { engaged = false }
        compose.waitForIdle() // delivers the write to the frozen-clock Recomposer
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertEquals(
            "the None branch added semantics nodes",
            withoutEffect,
            compose.onAllNodes(isRoot().not()).fetchSemanticsNodes().size,
        )
    }

    // ------------------------------------------------- the engagement timing

    @Test
    fun engageFadeTakes250MillisWithEaseInOut() {
        var engaged by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        redSource { engaged }

        // t = 0: the flip has not advanced a frame — nothing is drawn yet.
        assertEquals(0f, engagedAlphaAtTop(lightBackground), 0.01f)

        engaged = true
        compose.waitForIdle()
        // Two frames of latency before the tween's playtime 0: the first
        // recomposes `engaged = true` (animateFloatAsState hands the target
        // to its effect after that frame), the second is the animation's
        // start frame (CI r17 read 0.30 at 128 ms after the write, i.e.
        // 96 ms of playtime). The same two frames precede the fade on a
        // device; the 250 ms is measured from the start frame.
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        // 8 frames = 128 ms of playtime on the 16 ms grid — the frame nearest
        // the 125 ms midpoint — where the UIKit easeInOut curve is ≈0.52:
        // dead centre of the acceptance's 0.4–0.6, and far from both a snap
        // (1) and a long tween.
        compose.mainClock.advanceTimeBy(128)
        val midway = engagedAlphaAtTop(lightBackground)
        assertTrue("alpha at ~125 ms should be 0.4–0.6, was $midway", midway in 0.4f..0.6f)

        // 256 ms: past the 250 ms end, the tween finished at exactly 1 (a
        // 500 ms tween would still be ≈0.5 here; a snap would have been 1
        // at 128 ms; the 0.4–0.6 read rules both out).
        compose.mainClock.advanceTimeBy(128)
        assertEquals(1f, engagedAlphaAtTop(lightBackground), 0.01f)
    }

    @Test
    fun reduceMotionSnapsTheEngage() {
        var engaged by mutableStateOf(false)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    Box(Modifier.testTag("band").size(120.dp, 60.dp).background(red)) {
                        ScrollEdgeEffect(height = 60.dp, engaged = engaged, blur = false)
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(0f, engagedAlphaAtTop(lightBackground), 0.01f)

        // No 250 ms tween: full strength on the next frame.
        engaged = true
        compose.waitForIdle()
        assertBlend(bandPixels(), 60, 0, ScrollEdgeEffectDefaults.TopScrimAlpha, lightBackground, "reduced top row")
    }
}
