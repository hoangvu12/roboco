package sh.roboco.android.transcript

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import uniffi.roboco_core.Scroller

/**
 * The pure viewport (ticket 116) at `w320dp-h480dp-mdpi`, so 1 dp = 1 px
 * and the ticket's pixel table applies directly: a synthetic 200 dp
 * viewport over 600 dp of black content on a white parent (a background
 * inside the masked node would be faded with it), exactly like
 * `EdgeFadeScreenshotTest`'s fixtures. `NATIVE` graphics so
 * `captureToImage()` renders the offscreen mask.
 *
 * What is pinned: the viewport rect and clip, the 24 dp travel-ramped
 * fades at rest / 12 dp / the end (never moving with the content), no
 * mask when the content fits, the draw-phase-only offset (the layer draw
 * does not re-run while scrolling), 1:1 widget hosting, offset
 * persistence through contentWidth changes with the reset on a moved
 * origin, and identical rest pixels under RTL.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h480dp-mdpi")
class ScrollerViewportTest {

    @get:Rule
    val compose = createComposeRule()

    /** Black content 600 dp wide (the draw runs in the content box's scope). */
    private var draws = 0
    private val drawLayer: DrawScope.() -> Unit = {
        draws++
        drawRect(Color.Black, size = Size(600.dp.toPx(), 40.dp.toPx()))
    }

    // ------------------------------------------------------------- helpers

    private fun host(
        s: Scroller,
        state: ScrollState,
        rtl: Boolean = false,
        content: @Composable BoxScope.() -> Unit = {},
    ) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                val dir = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides dir) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        ScrollerViewport(s, state, drawLayer = drawLayer, content = content)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun pixels(): PixelMap =
        compose.onNodeWithTag(ScrollerDefaults.TestTag).captureToImage().toPixelMap()

    private fun assertSolid(px: PixelMap, x: Int, y: Int) {
        assertTrue("($x, $y) is not solid: ${px[x, y]}", px[x, y].red < 0.1f)
    }

    private fun assertFaded(px: PixelMap, x: Int, y: Int) {
        val c = px[x, y]
        assertTrue("($x, $y) is not faded: $c", c.red >= 0.94f && c.green >= 0.94f && c.blue >= 0.94f)
    }

    private fun scrollTo(state: ScrollState, value: Int) {
        compose.runOnIdle { runBlocking { state.scrollTo(value) } }
        compose.waitForIdle()
    }

    // --------------------------------------------------------------- tests

    @Test
    fun theViewportRectAndTheTravelRampedFades() {
        val state = ScrollState(0)
        host(Scroller(0f, 0f, 200f, 40f, 600f), state)

        // 200 × 40 at (0, 0); the content overflows by 400.
        val node = compose.onNodeWithTag(ScrollerDefaults.TestTag).fetchSemanticsNode()
        val b = node.boundsInRoot
        assertEquals(200f, b.width, 1f)
        assertEquals(40f, b.height, 1f)
        assertEquals(0f, b.left, 1f)
        assertEquals(0f, b.top, 1f)
        assertEquals(400, state.maxValue)

        // At rest: no fade on the left (solid), 24 dp of fade on the right
        // (column 199 sits inside the ramp).
        var px = pixels()
        assertSolid(px, 0, 20)
        assertFaded(px, 199, 20)

        // 12 dp in: the left fade is 12 dp wide, so column 0 is faded and
        // column 18 (past the ramp) is solid. The right fade is still 24.
        scrollTo(state, 12)
        px = pixels()
        assertFaded(px, 0, 20)
        assertSolid(px, 18, 20)

        // At the end: the left fade is fully in (column 0 faded), the right
        // fade is gone (column 199 solid).
        scrollTo(state, state.maxValue)
        px = pixels()
        assertFaded(px, 0, 20)
        assertSolid(px, 199, 20)
    }

    @Test
    fun aFittingScrollerHasNoMaskAndNoScrollRange() {
        val state = ScrollState(0)
        host(Scroller(0f, 0f, 200f, 40f, 150f), state)

        assertEquals(0, state.maxValue)
        // The 600 dp black rect overdraws the 150 dp content box; all three
        // columns solid proves there is no mask.
        val px = pixels()
        assertSolid(px, 0, 20)
        assertSolid(px, 149, 20)
        assertSolid(px, 199, 20)
    }

    @Test
    fun scrollingDoesNotReRunTheLayerDraw() {
        val state = ScrollState(0)
        host(Scroller(0f, 0f, 200f, 40f, 600f), state)
        compose.waitForIdle()
        val n = draws

        scrollTo(state, 200)
        compose.waitForIdle()
        assertEquals("the layer draw re-ran while scrolling", n, draws)
    }

    @Test
    fun widgetsInsideTheContentMoveOneToOneWithTheScroll() {
        val state = ScrollState(0)
        host(Scroller(0f, 0f, 200f, 40f, 600f), state) {
            Box(Modifier.absoluteOffset(400.dp, 0.dp).size(10.dp, 10.dp).testTag("fixture-widget"))
        }

        scrollTo(state, 250)
        val left = compose.onNodeWithTag("fixture-widget").fetchSemanticsNode().boundsInRoot.left
        assertEquals(400f - 250f, left, 1f)
    }

    @Test
    fun theOffsetPersistsAcrossContentChangesAndResetsWhenTheOriginMoves() {
        var s by mutableStateOf(Scroller(0f, 0f, 200f, 40f, 600f))
        var composed: ScrollState? = null
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    key(0) {
                        val state = rememberScrollerState(s)
                        SideEffect { composed = state }
                        ScrollerViewport(s, state, drawLayer = drawLayer)
                    }
                }
            }
        }
        compose.waitForIdle()
        val state = composed!!

        scrollTo(state, 150)
        assertEquals(150, state.value)

        // A longer content keeps the offset exactly (no snap-back to 0).
        s = s.copy(contentWidth = 700f)
        compose.waitForIdle()
        assertSame(state, composed)
        assertEquals(150, state.value)

        // A shorter content clamps it to the new max (100 = 300 − 200).
        s = s.copy(contentWidth = 300f)
        compose.waitForIdle()
        assertSame(state, composed)
        assertEquals(100, state.value)

        // A moved origin is a different scroller: it starts at 0.
        s = s.copy(y = 10f)
        compose.waitForIdle()
        assertNotSame(state, composed)
        assertEquals(0, composed!!.value)
    }

    @Test
    fun rtlAroundTheHostLeavesTheRestPixelsUnchanged() {
        val state = ScrollState(0)
        host(Scroller(0f, 0f, 200f, 40f, 600f), state, rtl = true)

        // The scroller is left-to-right inside whatever the app's direction
        // is: at rest the content's left edge shows and the fade is on the
        // right, byte-identical to the LTR host above.
        val px = pixels()
        assertSolid(px, 0, 20)
        assertFaded(px, 199, 20)
    }
}
