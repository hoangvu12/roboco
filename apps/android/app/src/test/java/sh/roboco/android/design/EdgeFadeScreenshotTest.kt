package sh.roboco.android.design

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The rendering side of the edge fades (android-ui-parity #10): the two
 * gallery goldens plus pixel checks of the three primitives at mdpi, where
 * 1 dp = 1 px, so the ticket's pixel tables apply directly.
 *
 * Every fixture draws black content on a **parent** `Box` with a white
 * background — a background inside the masked node would be faded with it.
 * Record with `:app:recordRoborazziDebug --tests sh.roboco.android.design.EdgeFadeScreenshotTest`,
 * verify with `:app:verifyRoborazziDebug --tests …`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h900dp-mdpi")
class EdgeFadeScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val longTitle = "Refactor the session list so long titles fade instead of ellipsizing"

    /** Black 16.sp default-family text: the mask's alpha is directly the pixel darkness. */
    private fun fixtureStyle(): TextStyle =
        TextStyle(color = Color.Black, fontFamily = FontFamily.Default, fontSize = 16.sp)

    private fun pixels(tag: String): PixelMap =
        compose.onNodeWithTag(tag).captureToImage().toPixelMap()

    private fun assertFaded(px: PixelMap, x: Int, y: Int, where: String) {
        val c = px[x, y]
        assertTrue("$where ($x,$y) is not faded: $c", c.red >= 0.94f && c.green >= 0.94f && c.blue >= 0.94f)
    }

    private fun assertSolid(px: PixelMap, x: Int, y: Int, where: String) {
        assertTrue("$where ($x,$y) is not solid: ${px[x, y]}", px[x, y].red < 0.1f)
    }

    private fun assertHasDarkPixel(px: PixelMap, xRange: IntRange, where: String) {
        var dark = false
        for (x in xRange) {
            for (y in 0 until px.height) {
                if (px[x, y].red < 0.3f) dark = true
            }
        }
        assertTrue("$where: columns ${xRange.first}..${xRange.last} hold no text pixel", dark)
    }

    // ------------------------------------------------------------ goldens

    private fun galleryShot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    EdgeFadeGallerySection()
                }
            }
        }
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    // The section is ≈ 1100 dp tall (its registration line), so the golden
    // needs a taller window than the class-level 900 dp the pixel checks
    // use; 1400 dp matches the theme gallery's 1100 dp pages.
    @Config(sdk = [36], qualifiers = "w400dp-h1400dp-mdpi")
    fun galleryLight() {
        galleryShot(Appearance.Light, "design/edge_fades_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w400dp-h1400dp-mdpi")
    fun galleryDark() {
        galleryShot(Appearance.Dark, "design/edge_fades_dark.png")
    }

    // ------------------------------------------------------- FadingText

    @Test
    fun overflowingTextFadesItsLtrTailOnly() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.testTag("t").size(200.dp, 24.dp).background(Color.White)) {
                    FadingText(longTitle, fixtureStyle(), Modifier.fillMaxSize())
                }
            }
        }
        val px = pixels("t")
        // b = 1 − 24/200 = 0.88: the tail fades over x = 176..200, and at
        // x = 199.5 the mask alpha is 0.5/24 ≈ 2 % — nothing there is dark.
        for (y in 0 until px.height) assertFaded(px, 199, y, "LTR tail")
        // The head of the text sits at the start (physical left) edge.
        assertHasDarkPixel(px, 0..150, "LTR head")
    }

    @Test
    fun overflowingTextFadesItsRtlTailOnly() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.testTag("t").size(200.dp, 24.dp).background(Color.White)) {
                        FadingText(longTitle, fixtureStyle(), Modifier.fillMaxSize())
                    }
                }
            }
        }
        val px = pixels("t")
        // a = 24/200 = 0.12: the fade covers x = 0..24; the text's right
        // edge is flush with the slot's right edge.
        for (y in 0 until px.height) assertFaded(px, 0, y, "RTL tail")
        assertHasDarkPixel(px, 50..199, "RTL head")
    }

    @Test
    fun fittingTextCentresWithNoMask() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.testTag("t").size(200.dp, 24.dp).background(Color.White)) {
                    FadingText(
                        "Hi",
                        fixtureStyle(),
                        Modifier.fillMaxSize(),
                        fitsAlignment = Alignment.CenterHorizontally,
                    )
                }
            }
        }
        val px = pixels("t")
        // "Hi" (≈ 15 px wide) is centred around x = 100 with no mask, so the
        // flanks are pure white.
        for (x in 0..80) {
            for (y in 0 until px.height) assertFaded(px, x, y, "centred flank")
        }
        for (x in 120..199) {
            for (y in 0 until px.height) assertFaded(px, x, y, "centred flank")
        }
    }

    @Test
    fun onlyTheFirstHardLineIsMeasuredAndRead() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.testTag("t").size(100.dp, 24.dp).background(Color.White)) {
                    FadingText(
                        "short\n" + "m".repeat(48),
                        fixtureStyle(),
                        Modifier.fillMaxSize(),
                        fitsAlignment = Alignment.CenterHorizontally,
                    )
                }
            }
        }
        val px = pixels("t")
        // The overflow verdict measures "short" (the text before the first
        // \n), not the widest line: it fits, so it is centred with no fade —
        // an un-cut label would start at x = 0 with a 24 px tail fade.
        for (x in 0..25) {
            for (y in 0 until px.height) assertFaded(px, x, y, "first-line cut")
        }
        assertHasDarkPixel(px, 30..70, "the first line")
        // TalkBack reads the full original text, newline included (UILabel
        // parity: the visual clip never affects semantics).
        compose.onNode(
            SemanticsMatcher("text keeps both lines") { node: SemanticsNode ->
                node.config.contains(SemanticsProperties.Text) &&
                    node.config[SemanticsProperties.Text].single().text == "short\n" + "m".repeat(48)
            },
        ).assertExists()
    }

    @Test
    fun widthInSlotsHugShortTextAndCapLongText() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Column {
                    Box(Modifier.testTag("hug").background(Color.White)) {
                        FadingText("Hi", fixtureStyle(), Modifier.widthIn(max = 100.dp))
                    }
                    Box(Modifier.testTag("cap").background(Color.White)) {
                        FadingText(longTitle, fixtureStyle(), Modifier.widthIn(max = 100.dp))
                    }
                }
            }
        }
        // A widthIn(max = …) slot hugs the text up to the max (the box wraps
        // the node, so its width is the slot width)…
        val hug = pixels("hug")
        assertTrue("short text fills the max instead of hugging (width ${hug.width})", hug.width in 2..99)
        // …and caps at the max, with the tail fading from there.
        val cap = pixels("cap")
        assertEquals(100, cap.width)
        for (y in 0 until cap.height) assertFaded(cap, 99, y, "widthIn cap tail")
    }

    // ------------------------------------------- FadingHorizontalScroll

    /**
     * A 200dp viewport with [boxes] black 60×28dp boxes at 6dp spacing —
     * 10 boxes give content 654dp and `maxValue` 454px — scrolled to
     * [initial] px, optionally RTL.
     */
    private fun scroller(initial: Int = 0, rtl: Boolean = false, boxes: Int = 10): Pair<ScrollState, PixelMap> {
        val state = ScrollState(initial)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Box(Modifier.testTag("t").size(200.dp, 28.dp).background(Color.White)) {
                        FadingHorizontalScroll(
                            modifier = Modifier.fillMaxSize(),
                            state = state,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            repeat(boxes) {
                                Box(Modifier.size(60.dp, 28.dp).background(Color.Black))
                            }
                        }
                    }
                }
            }
        }
        return state to pixels("t")
    }

    @Test
    fun scrollerAtRestFadesOnlyTheEnd() {
        val (_, px) = scroller()
        // Box 1 (x 0..60) is solid at rest: no fade on the leading edge.
        for (y in 2..25) assertSolid(px, 0, y, "start edge at rest")
        // The end fade covers x = 176..200; column 199 is inside box 4
        // (198..258) at ~2 % alpha.
        for (y in 0 until px.height) assertFaded(px, 199, y, "end fade at rest")
    }

    @Test
    fun scrollerScrolled12pxStartsTheLeadingRamp() {
        val (_, px) = scroller(initial = 12)
        // The start fade is now 12 px wide: alpha at x = 0.5 is 0.5/12 ≈ 4 %.
        for (y in 0 until px.height) assertFaded(px, 0, y, "start ramp at 12px")
        for (y in 0 until px.height) assertFaded(px, 199, y, "end fade at 12px")
    }

    @Test
    fun scrollerAtTheEndFadesOnlyTheStart() {
        val (state, _) = scroller()
        compose.runOnIdle { runBlocking { state.scrollTo(state.maxValue) } }
        compose.waitForIdle()
        val px = pixels("t")
        // The last box (594..654) ends the content: column 199 maps to it,
        // solid; the start fade is the full 24 px.
        for (y in 2..25) assertSolid(px, 199, y, "end edge at the end")
        for (y in 0 until px.height) assertFaded(px, 0, y, "start fade at the end")
    }

    @Test
    fun rtlScrollerMirrorsTheFadesAtRest() {
        val (_, px) = scroller(rtl = true)
        // At rest in RTL, box 1 sits at x 140..200 (Compose mirrors the
        // scroll): the leading edge is the physical right one.
        for (y in 2..25) assertSolid(px, 199, y, "RTL start edge at rest")
        for (y in 0 until px.height) assertFaded(px, 0, y, "RTL end fade at rest")
    }

    @Test
    fun rtlScrollerMirrorsTheFadesAtTheEnd() {
        val (state, _) = scroller(rtl = true)
        compose.runOnIdle { runBlocking { state.scrollTo(state.maxValue) } }
        compose.waitForIdle()
        val px = pixels("t")
        for (y in 2..25) assertSolid(px, 0, y, "RTL end edge at the end")
        for (y in 0 until px.height) assertFaded(px, 199, y, "RTL start fade at the end")
    }

    @Test
    fun scrollerThatFitsHasNoMask() {
        // 2 boxes: content 126dp < the 200dp viewport, no mask anywhere.
        val (_, px) = scroller(boxes = 2)
        for (y in 2..25) assertSolid(px, 0, y, "fits start")
        for (y in 2..25) assertSolid(px, 125, y, "fits end")
    }

    @Test
    fun scrollingDoesNotRecomposeTheWrapper() {
        val state = ScrollState(0)
        val recompositions = intArrayOf(0)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.size(200.dp, 28.dp).background(Color.White)) {
                    recompositions[0]++
                    FadingHorizontalScroll(
                        modifier = Modifier.fillMaxSize(),
                        state = state,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        repeat(10) {
                            Box(Modifier.size(60.dp, 28.dp).background(Color.Black))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val before = recompositions[0]
        assertTrue("the wrapper never composed", before >= 1)
        compose.runOnIdle { runBlocking { state.scrollTo(200) } }
        compose.waitForIdle()
        assertEquals("scrolling recomposed the wrapper", before, recompositions[0])
    }

    // -------------------------------------------------- EdgeFadeOverlay

    @Test
    fun overlayRampsPaletteBackgroundOverBlack() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.testTag("t").size(100.dp).background(Color.Black)) {
                    EdgeFadeOverlay(Modifier.matchParentSize())
                }
            }
        }
        val px = pixels("t")
        for (x in 0 until px.width) {
            // y = 0: pixel centre 0.005 → alpha 0.0075 ≈ clear.
            val top = px[x, 0]
            assertTrue("top row ($x,0) is not clear: $top", top.red <= 0.02f && top.green <= 0.02f && top.blue <= 0.02f)
            // y = 24: pixel centre 0.245 → alpha ≈ 0.513: #F3F3F5 at ~51 %
            // over black lands in 0x78..0x7F per channel.
            val mid = px[x, 24]
            assertTrue("row 24 ($x,24) is off the ramp: $mid", mid.red in 0.47f..0.499f)
            assertTrue("row 24 ($x,24) is off the ramp: $mid", mid.green in 0.47f..0.499f)
            assertTrue("row 24 ($x,24) is off the ramp: $mid", mid.blue in 0.47f..0.499f)
        }
        // Solid from 55 % down: exactly Palette.background light (#F3F3F5).
        val f3 = 243f / 255f
        val f5 = 245f / 255f
        for (y in 56 until px.height) {
            for (x in 0 until px.width) {
                val c = px[x, y]
                assertTrue("solid row ($x,$y): $c", abs(c.red - f3) < 0.005f)
                assertTrue("solid row ($x,$y): $c", abs(c.green - f3) < 0.005f)
                assertTrue("solid row ($x,$y): $c", abs(c.blue - f5) < 0.005f)
            }
        }
    }

    @Test
    fun overlayPassesTouchesThrough() {
        var clicked by mutableStateOf(false)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.size(100.dp).testTag("t")) {
                    Spacer(Modifier.matchParentSize().clickable { clicked = true })
                    EdgeFadeOverlay(Modifier.matchParentSize())
                }
            }
        }
        // The overlay is drawn on top of the clickable spacer but is no hit
        // target, so the tap lands on the node below (center of the 100dp
        // box, not of the window).
        compose.onNodeWithTag("t").performTouchInput { click(center) }
        compose.runOnIdle { assertTrue(clicked) }
    }
}
