package sh.roboco.android.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Parses and rasterises every mark (android-ui-parity #13). The bounds and
 * pixel checks exist because a packed arc flag misparses **silently** in
 * androidx PathParser — the shape shifts instead of throwing
 * (`ios:Design/BrandMarks.swift:103-373` parses packed flags natively, iOS
 * does not misparse).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class BrandMarkRenderTest {

    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------- parsing

    @Test
    fun everyPathBuilds() {
        for (mark in BrandMark.entries) {
            // Accessing .path parses via PathParser; a malformed path throws.
            assertTrue("${mark.svgName} bounds are empty", !mark.path.getBounds().isEmpty)
        }
    }

    @Test
    fun hermesPathIsParsedOnceAndCached() {
        assertSame(BrandMark.HERMES.path, BrandMark.HERMES.path)
    }

    @Test
    fun boundsStayInsideTheViewBoxAndCoverHalfOfIt() {
        for (mark in BrandMark.entries) {
            val b = mark.path.getBounds()
            val w = mark.viewBoxWidth
            val h = mark.viewBoxHeight
            val margin = 0.10f
            assertTrue(
                "${mark.svgName} bounds $b escape the viewBox (0 0 $w $h) + 10%: misparse?",
                b.left >= -w * margin && b.top >= -h * margin &&
                    b.right <= w * (1f + margin) && b.bottom <= h * (1f + margin),
            )
            assertTrue(
                "${mark.svgName} bounds $b cover less than 50% of the viewBox: misparse?",
                (b.right - b.left) >= 0.5f * w && (b.bottom - b.top) >= 0.5f * h,
            )
        }
    }

    // ------------------------------------------------------------- pixels

    /** 80×80 px, density 1: 1 px = 1 dp, so the ticket's pixel map applies. */
    private fun render(mark: BrandMark, color: Color): PixelMap {
        val bmp = ImageBitmap(80, 80)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(80f, 80f)) {
            drawBrandMark(mark, color)
        }
        return bmp.toPixelMap()
    }

    private fun assertTransparent(px: PixelMap, x: Int, y: Int) {
        assertEquals("pixel ($x,$y) expected transparent", 0f, px[x, y].alpha)
    }

    private fun assertOpaque(px: PixelMap, x: Int, y: Int) {
        assertEquals("pixel ($x,$y) expected opaque", 1f, px[x, y].alpha)
    }

    @Test
    fun piHoleStaysOpenWithEvenOddFill() {
        val px = render(BrandMark.PI, Color.Black)
        assertTransparent(px, 34, 34)   // viewBox (345,345): the even-odd hole
        assertOpaque(px, 20, 20)
        assertOpaque(px, 57, 51)        // third subpath block
        assertTransparent(px, 5, 5)     // outside the mark
    }

    @Test
    fun opencodeFrameHoleStaysOpenAndCentres() {
        // s = 80/30 = 2.667, dx = 8: drawn 8..72 × 0..80.
        val px = render(BrandMark.OPENCODE, Color.Black)
        assertTransparent(px, 40, 40)   // inner square: frame hole
        assertOpaque(px, 16, 40)
        assertTransparent(px, 4, 40)    // aspect-fit margin
        assertTransparent(px, 76, 40)   // aspect-fit margin
    }

    @Test
    fun claudeCodeKeepsItsOrange() {
        val (mark, color) = resolveHarnessMark("claude-code", Color.Black)!!
        val px = render(mark, color)
        var opaqueCount = 0
        for (y in 0 until px.height) {
            for (x in 0 until px.width) {
                if (px[x, y].alpha == 1f) {
                    opaqueCount++
                    assertEquals("opaque pixel ($x,$y)", 0xFFD97757.toInt(), px[x, y].toArgb())
                }
            }
        }
        assertTrue("fewer than 100 opaque pixels ($opaqueCount): misparse?", opaqueCount >= 100)
    }

    @Test
    fun codexTakesTheCallerTint() {
        val (mark, color) = resolveHarnessMark("codex", Color(0xFF27272C))!!
        val px = render(mark, color)
        var opaqueCount = 0
        for (y in 0 until px.height) {
            for (x in 0 until px.width) {
                if (px[x, y].alpha == 1f) {
                    opaqueCount++
                    assertEquals("opaque pixel ($x,$y)", 0xFF27272C.toInt(), px[x, y].toArgb())
                }
            }
        }
        assertTrue("no opaque pixels: misparse?", opaqueCount > 0)
    }

    // --------------------------------------------------- composable layout

    @Test
    @Config(sdk = [36], qualifiers = "w720dp-h240dp-mdpi")
    fun nullHarnessTakesItsBoxAndDrawsNothing() {
        // captureToImage reads the window, so the host activity's opaque
        // window background shows through; paint a known backdrop and assert
        // the mark left every pixel of it untouched.
        val backdrop = Color(0xFFFF00FF)
        compose.setContent {
            Box(Modifier.background(backdrop)) {
                HarnessMark(null, 20.dp, Color.Black, Modifier.testTag("null-mark"))
            }
        }
        compose.onNodeWithTag("null-mark")
            .assertWidthIsEqualTo(20.dp)
            .assertHeightIsEqualTo(20.dp)
        val px = compose.onNodeWithTag("null-mark").captureToImage().toPixelMap()
        for (y in 0 until px.height) {
            for (x in 0 until px.width) {
                assertEquals("pixel ($x,$y) expected the backdrop", backdrop.toArgb(), px[x, y].toArgb())
            }
        }
    }
}
