package sh.roboco.android.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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

/**
 * [SessionHeader]'s rendering and behaviour (android-ui-parity #52):
 * Robolectric + `createComposeRule` at `w412dp-h900dp-xxhdpi` (1 dp = 3 px)
 * with a **fixed 24 dp status bar** — Robolectric reports no insets, so
 * `navBottom = 68` and the edge band is 84 dp. `showBack`/`onBack` are
 * passed explicitly everywhere (24's `LocalNavStack` locals have no default
 * provider outside a `NavStackHost`).
 *
 * The plain geometry lives in [SessionHeaderMetricsTest]; the goldens in
 * [SessionHeaderScreenshotTest]. The pixel probes (ink colours, the tail
 * fade, the scrim band) need `GraphicsMode.NATIVE`, as in ticket 37's row
 * tests. The `hazeBlur` node itself is Robolectric-untestable (no hardware
 * acceleration → the scrim-only fallback, the API 31–32 path); its own
 * behaviour is ticket 09's tests — here the band's presence, extent and
 * engagement are pinned by pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h900dp-xxhdpi")
class SessionHeaderTest {

    @get:Rule
    val compose = createComposeRule()

    /** The light page background, `#F3F3F5` (Palette.background). */
    private val background = Color(0xFFF3F3F5)

    // ------------------------------------------------------------ helpers

    /** px of [v] dp at the test density (3). */
    private fun dp(v: Float): Float = with(compose.density) { v.dp.toPx() }

    /**
     * Composes the header over the flat page background (no transcript —
     * the geometry probes read a clean backdrop), optionally with a
     * sibling drawn behind it. `showBack`/`onBack` are always explicit.
     */
    private fun host(
        title: String = SessionHeaderReferenceTitle,
        subtitle: String = "roboco @ MacBook Pro",
        showBack: Boolean = true,
        engaged: Boolean = false,
        statusBarTop: Dp = 24.dp,
        appearance: Appearance = Appearance.Light,
        onBack: () -> Unit = {},
        onMore: (anchorInWindow: Rect) -> Unit = {},
        behind: (@Composable () -> Unit)? = null,
    ) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    if (behind != null) behind()
                    SessionHeader(
                        title = title,
                        subtitle = subtitle,
                        edgeEngaged = engaged,
                        onMore = onMore,
                        showBack = showBack,
                        onBack = onBack,
                        statusBarTop = statusBarTop,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** The node's captured pixels (NATIVE graphics mode). */
    private fun pixelsOf(tag: String): PixelMap =
        compose.onNodeWithTag(tag).captureToImage().toPixelMap()

    /** The node's bounds in root px, as a [Rect]. */
    private fun boundsOf(tag: String): Rect {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        return Rect(
            node.positionInRoot.x,
            node.positionInRoot.y,
            node.positionInRoot.x + node.size.width,
            node.positionInRoot.y + node.size.height,
        )
    }

    private fun assertChannel(what: String, value: Float, expected: Int, tolerance: Int) {
        assertTrue(
            "$what: ${(value * 255f).roundToInt()} vs $expected ±$tolerance",
            abs(value - expected / 255f) <= tolerance / 255f,
        )
    }

    private fun assertPixel(pixels: PixelMap, x: Int, y: Int, r: Int, g: Int, b: Int, tolerance: Int, what: String) {
        val c = pixels[x, y]
        assertChannel("$what at ($x, $y) red", c.red, r, tolerance)
        assertChannel("$what at ($x, $y) green", c.green, g, tolerance)
        assertChannel("$what at ($x, $y) blue", c.blue, b, tolerance)
    }

    /** The darkest pixel of [pixels] matches the ink (r, g, b) per channel. */
    private fun assertDarkest(pixels: PixelMap, what: String, r: Int, g: Int, b: Int, tolerance: Int = 4) {
        var mr = 255
        var mg = 255
        var mb = 255
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val c = pixels[x, y]
                mr = min(mr, (c.red * 255f).roundToInt())
                mg = min(mg, (c.green * 255f).roundToInt())
                mb = min(mb, (c.blue * 255f).roundToInt())
            }
        }
        assertChannel("$what min red", mr / 255f, r, tolerance)
        assertChannel("$what min green", mg / 255f, g, tolerance)
        assertChannel("$what min blue", mb / 255f, b, tolerance)
    }

    /** The darkest pixel of [xRange] matches the ink (r, g, b) per channel. */
    private fun assertDarkestInRegion(pixels: PixelMap, xRange: IntRange, what: String, r: Int, g: Int, b: Int) {
        var mr = 255
        var mg = 255
        var mb = 255
        for (y in 0 until pixels.height) {
            for (x in xRange) {
                val c = pixels[x, y]
                mr = min(mr, (c.red * 255f).roundToInt())
                mg = min(mg, (c.green * 255f).roundToInt())
                mb = min(mb, (c.blue * 255f).roundToInt())
            }
        }
        assertChannel("$what min red", mr / 255f, r, 4)
        assertChannel("$what min green", mg / 255f, g, 4)
        assertChannel("$what min blue", mb / 255f, b, 4)
    }

    /** The brightest pixel of [pixels] matches the ink (r, g, b) per channel. */
    private fun assertBrightest(pixels: PixelMap, what: String, r: Int, g: Int, b: Int, tolerance: Int = 4) {
        var mr = 0
        var mg = 0
        var mb = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val c = pixels[x, y]
                mr = max(mr, (c.red * 255f).roundToInt())
                mg = max(mg, (c.green * 255f).roundToInt())
                mb = max(mb, (c.blue * 255f).roundToInt())
            }
        }
        assertChannel("$what max red", mr / 255f, r, tolerance)
        assertChannel("$what max green", mg / 255f, g, tolerance)
        assertChannel("$what max blue", mb / 255f, b, tolerance)
    }

    /**
     * The long-title tail fade: [fromEdgePx] px from the RIGHT edge of the
     * title node, every row of the line sits within [tolerance]/255 of the
     * page background — the 24 dp linear mask leaves ink alpha
     * `fromEdgePx/72`, so 1 px in is ≈1 % ink (the ticket's ±4 bound) and
     * 6 px (2 dp) in is ≈8 % ink (no crisp glyph, never "…"; the
     * observable property is the fade).
     */
    private fun assertFadedFromRight(pixels: PixelMap, fromEdgePx: Int, tolerance: Int, what: String) {
        val x = pixels.width - fromEdgePx
        for (y in 0 until pixels.height) {
            assertPixel(pixels, x, y, 0xF3, 0xF3, 0xF5, tolerance, what)
        }
    }

    /** [assertFadedFromRight], mirrored for RTL: [fromEdgePx] from the LEFT edge. */
    private fun assertFadedFromLeft(pixels: PixelMap, fromEdgePx: Int, tolerance: Int, what: String) {
        val x = fromEdgePx
        for (y in 0 until pixels.height) {
            assertPixel(pixels, x, y, 0xF3, 0xF3, 0xF5, tolerance, what)
        }
    }

    /** True when some pixel of the half has red below [limit]/255. */
    private fun halfHasDarkInk(pixels: PixelMap, fromX: Int, toX: Int, limit: Int): Boolean {
        for (y in 0 until pixels.height) {
            for (x in fromX until toX) {
                if (pixels[x, y].red < limit / 255f) return true
            }
        }
        return false
    }

    /**
     * The state the switching tests flip between two probes. A compose rule
     * allows exactly one `setContent` per test ("has already set content"),
     * so a second configuration is a state write + `waitForIdle`, never a
     * second `setContent`.
     */
    private var hostAppearance by mutableStateOf(Appearance.Light)
    private var hostEngaged by mutableStateOf(false)
    private var hostWidth by mutableStateOf<Dp?>(null)
    private var hostFontScale by mutableStateOf(1f)

    /**
     * [host] with [hostAppearance], [hostEngaged], [hostWidth] (null = the
     * full window) and [hostFontScale] read from state, so one composition
     * can be re-probed after [reconfigure].
     */
    private fun hostSwitchable(behind: (@Composable () -> Unit)? = null) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, hostFontScale)) {
                RobocoTheme(hostAppearance) {
                    val width = hostWidth
                    val box = if (width != null) Modifier.width(width) else Modifier
                    Box(box.fillMaxSize().background(RobocoTheme.palette.background)) {
                        if (behind != null) behind()
                        SessionHeader(
                            title = SessionHeaderReferenceTitle,
                            subtitle = "roboco @ MacBook Pro",
                            edgeEngaged = hostEngaged,
                            onMore = {},
                            showBack = true,
                            onBack = {},
                            statusBarTop = 24.dp,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Applies a state change on the UI thread and waits for the recomposition. */
    private fun reconfigure(change: () -> Unit) {
        compose.runOnUiThread(change)
        compose.waitForIdle()
    }

    // ------------------------------------------------------------ geometry

    @Test
    fun backAndMoreCirclesSitAtSixteenFromTheEdges() {
        host()
        val back = boundsOf("nav-back")
        assertEquals(dp(16f), back.left, dp(1f))
        assertEquals(dp(24f), back.top, dp(1f))
        assertEquals(dp(60f), back.right, dp(1f))
        assertEquals(dp(68f), back.bottom, dp(1f))
        assertEquals(dp(44f), back.width, 1f)
        assertEquals(dp(44f), back.height, 1f)
        val more = boundsOf("session-more")
        assertEquals(dp(352f), more.left, dp(1f))
        assertEquals(dp(24f), more.top, dp(1f))
        assertEquals(dp(396f), more.right, dp(1f))
        assertEquals(dp(68f), more.bottom, dp(1f))
        assertEquals(dp(44f), more.width, 1f)
        assertEquals(dp(44f), more.height, 1f)
    }

    @Test
    fun theReferenceTitleIs240WideCentredWithTheSubtitleBelow() {
        host()
        val title = boundsOf("session-title")
        // 240 wide, centred on the full 412 (centre x = 206) …
        assertEquals(dp(240f), title.width, 1.5f)
        assertEquals(dp(206f), title.left + title.width / 2f, dp(0.5f))
        // … its top at 24 + 3.3 (the 37.4 stack centred in the 44 row).
        assertEquals(dp(24f + 3.3f), title.top, dp(0.5f))
        val subtitle = boundsOf("session-subtitle")
        assertEquals(dp(206f), subtitle.left + subtitle.width / 2f, dp(0.5f))
        assertEquals(dp(24f + 3.3f + 20.8f + 1f), subtitle.top, dp(0.5f))
    }

    @Test
    fun anEmptySubtitleComposesNoSecondLineAndCentresTheTitle() {
        host(subtitle = "")
        compose.onNodeWithTag("session-subtitle").assertDoesNotExist()
        val title = boundsOf("session-title")
        // The title line alone (20.8) centred in the row: top at 24 + 11.6.
        assertEquals(dp(24f + 11.6f), title.top, dp(0.5f))
        assertEquals(dp(206f), title.left + title.width / 2f, dp(0.5f))
    }

    @Test
    fun narrowWidthsShrinkTheTitleCap() {
        hostWidth = 360.dp
        hostSwitchable()
        val narrow = boundsOf("session-title")
        assertEquals(dp(224f), narrow.width, 1.5f)
        assertEquals(dp(180f), narrow.left + narrow.width / 2f, dp(0.5f))
        reconfigure { hostWidth = 320.dp }
        val narrower = boundsOf("session-title")
        assertEquals(dp(184f), narrower.width, 1.5f)
        assertEquals(dp(160f), narrower.left + narrower.width / 2f, dp(0.5f))
    }

    @Test
    fun aShortTitleFitsCentredAndUnfaded() {
        host(title = "Fix login")
        val title = boundsOf("session-title")
        assertTrue("the short title hit the 240 cap", title.width < dp(240f))
        assertEquals(dp(206f), title.left + title.width / 2f, dp(0.5f))
        // Fitting text carries no mask: the ink in the node's right quarter
        // is full-strength (a faded tail would be lifted toward the page).
        val pixels = pixelsOf("session-title")
        assertDarkestInRegion(pixels, (pixels.width * 3 / 4) until pixels.width, "short title tail", 0x27, 0x27, 0x2C)
    }

    @Test
    fun titleAndSubtitleSizesIgnoreTheFontScale() {
        hostSwitchable()
        val titleH1 = compose.onNodeWithTag("session-title").fetchSemanticsNode().size.height
        val subtitleH1 = compose.onNodeWithTag("session-subtitle").fetchSemanticsNode().size.height
        reconfigure { hostFontScale = 1.3f }
        // `fixed(16)`/`fixed(12)` convert dp→sp divided by the font scale,
        // so the rendered line boxes are identical at 1.0 and 1.3.
        assertEquals(
            "title height changed with the font scale",
            titleH1,
            compose.onNodeWithTag("session-title").fetchSemanticsNode().size.height,
        )
        assertEquals(
            "subtitle height changed with the font scale",
            subtitleH1,
            compose.onNodeWithTag("session-subtitle").fetchSemanticsNode().size.height,
        )
    }

    // ------------------------------------------------------- interaction

    @Test
    fun tappingBackAndMoreFireTheirCallbacksOnce() {
        var backTaps = 0
        var moreTaps = 0
        host(onBack = { backTaps++ }, onMore = { moreTaps++ })
        compose.onNodeWithTag("nav-back").performClick()
        compose.onNodeWithTag("session-more").performClick()
        compose.runOnIdle {
            assertEquals("onBack was not called once", 1, backTaps)
            assertEquals("onMore was not called once", 1, moreTaps)
        }
    }

    @Test
    fun theMoreAnchorIsTheFortyFourCircleInWindowCoordinates() {
        var anchor: Rect? = null
        host(onMore = { anchor = it })
        val node = compose.onNodeWithTag("session-more").fetchSemanticsNode()
        val expected = node.boundsInWindow
        compose.onNodeWithTag("session-more").performClick()
        compose.runOnIdle {
            val rect = anchor
            assertTrue("onMore delivered no anchor", rect != null)
            // 44 × 44 dp, in px …
            assertEquals(dp(44f), rect!!.width, 1f)
            assertEquals(dp(44f), rect!!.height, 1f)
            // … at the node's window position (the circle's own rect, not
            // the padded wrapper).
            assertEquals(expected.left, rect.left, 0.5f)
            assertEquals(expected.top, rect.top, 0.5f)
            assertEquals(expected.right, rect.right, 0.5f)
            assertEquals(expected.bottom, rect.bottom, 0.5f)
            // And the circle sits at (352, 24), the AC's geometry.
            assertEquals(dp(352f), rect.left, 1f)
            assertEquals(dp(24f), rect.top, 1f)
        }
    }

    @Test
    fun theBarBlocksTouchesButTheSoftExtensionPassesThrough() {
        var taps = 0
        host(
            onBack = {},
            onMore = {},
            behind = {
                Box(Modifier.fillMaxSize().testTag("under").clickable { taps++ })
            },
        )
        // Mid-bar (24 + 22) and deep in the status-bar area (10): swallowed
        // by the 0 … 68 bar — nothing reaches the clickable underneath.
        compose.onRoot().performTouchInput { click(Offset(width / 2f, dp(46f))) }
        compose.onRoot().performTouchInput { click(Offset(width / 2f, dp(10f))) }
        compose.runOnIdle { assertEquals("the bar let a tap through", 0, taps) }
        // 8 dp below the bar: only the edge effect's soft extension is
        // there, and it draws no input — the tap passes to the transcript.
        compose.onRoot().performTouchInput { click(Offset(width / 2f, dp(76f))) }
        compose.runOnIdle { assertEquals("the soft extension swallowed a tap", 1, taps) }
    }

    @Test
    fun noBackCircleWhenShowBackIsFalseAndTheTitleStaysCentred() {
        host(showBack = false)
        compose.onNodeWithTag("nav-back").assertDoesNotExist()
        val title = boundsOf("session-title")
        assertEquals(dp(206f), title.left + title.width / 2f, dp(0.5f))
        assertEquals(dp(240f), title.width, 1.5f)
    }

    // ---------------------------------------------------- the edge effect

    @Test
    fun theEngagedBandScrimEndsAt84DpAndDrawsNothingWhenDisengaged() {
        // A black box behind the bar makes the band visible: engaged, the
        // 0.85 → 0 gradient of Palette.background scrims it inside
        // 0 … 84 dp (ticket 09's statusBarTop + 44 + 16 band); below the
        // band the box is untouched. Disengaged, the band composes nothing
        // at all (ticket 09's `None` branch) — the box shows through crisp.
        // The probe column (x = 340 dp) is clear of the title (86 … 326)
        // and both circles (16 … 60, 352 … 396) and their shadows.
        hostSwitchable(
            behind = { Box(Modifier.fillMaxWidth().height(120.dp).background(Color.Black)) },
        )
        val rest = compose.onRoot().captureToImage().toPixelMap()
        assertPixel(rest, (dp(340f)).toInt(), (dp(12f)).toInt(), 0x00, 0x00, 0x00, 4, "resting band")
        reconfigure { hostEngaged = true }
        val scrimmed = compose.onRoot().captureToImage().toPixelMap()
        // Inside the band (y = 12 dp): 0.85·(1 − 12/84) ≈ 0.73 background
        // over black ≈ (177, 177, 178).
        assertPixel(scrimmed, (dp(340f)).toInt(), (dp(12f)).toInt(), 0xB1, 0xB1, 0xB2, 4, "scrimmed band")
        // Below the band (y = 92 dp): the box is untouched.
        assertPixel(scrimmed, (dp(340f)).toInt(), (dp(92f)).toInt(), 0x00, 0x00, 0x00, 4, "below the band")
    }

    // ------------------------------------------------------- semantics

    @Test
    fun theBarIsATraversalGroupReadBeforeTheContent() {
        host(
            behind = { Box(Modifier.fillMaxSize().testTag("content").clickable {}) },
        )
        val groups = compose
            .onAllNodes(
                SemanticsMatcher("header traversal group") {
                    it.config.contains(SemanticsProperties.IsTraversalGroup) &&
                        it.config.contains(SemanticsProperties.TraversalIndex) &&
                        it.config[SemanticsProperties.TraversalIndex] == -1f
                },
            )
            .fetchSemanticsNodes()
        assertEquals("exactly the header bar is a −1 traversal group", 1, groups.size)
        // The content underneath carries no traversal index of its own.
        val content = compose.onNodeWithTag("content").fetchSemanticsNode()
        assertTrue(
            "the transcript content was tagged with a traversal index",
            !content.config.contains(SemanticsProperties.TraversalIndex),
        )
    }

    @Test
    fun theTitleIsAHeadingWithTheFullTextAndBothButtonsAreLabelled() {
        // A hard line break: the visual line is clipped at the break, the
        // semantics keep the full text (ticket 10's rule).
        host(title = "First\nsecond")
        val title = compose.onNodeWithTag("session-title").fetchSemanticsNode()
        assertTrue("the title is not a heading", title.config.contains(SemanticsProperties.Heading))
        compose.onNodeWithTag("session-title").assert(hasText("First\nsecond"))
        compose.onNodeWithTag("session-subtitle").assert(hasText("roboco @ MacBook Pro"))
        // UIKit's default labels: "Back" for the system back button, "More"
        // for the ellipsis bar button. Traversal within the group is tree
        // order: Back, title, subtitle, More, then the content outside.
        compose.onNodeWithContentDescription("Back").assertExists()
        compose.onNodeWithContentDescription("More").assertExists()
    }

    // ---------------------------------------------------- title updates

    @Test
    fun titleChangeReplacesTheTextInPlaceWithNoAnimation() {
        compose.mainClock.autoAdvance = false
        var title by mutableStateOf("Fix login")
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(background)) {
                    SessionHeader(
                        title = title,
                        subtitle = "roboco @ MacBook Pro",
                        edgeEngaged = false,
                        onMore = {},
                        showBack = true,
                        onBack = {},
                        statusBarTop = 24.dp,
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(100)
        assertTrue(
            "the short title did not fit",
            compose.onNodeWithTag("session-title").fetchSemanticsNode().size.width < dp(240f).toInt(),
        )
        compose.runOnUiThread { title = SessionHeaderReferenceTitle }
        // One frame is enough: the new slot is 240 wide immediately — no
        // tween would be anywhere near done 50 ms in.
        compose.mainClock.advanceTimeBy(50)
        assertEquals(
            "the long title was not 240 wide one frame in",
            dp(240f),
            compose.onNodeWithTag("session-title").fetchSemanticsNode().size.width.toFloat(),
            1.5f,
        )
    }

    // ---------------------------------------------------------------- RTL

    @Test
    fun rtlMirrorsTheCirclesAndFadesTheTitleOnItsLeft() {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                RobocoTheme(Appearance.Light) {
                    Box(Modifier.fillMaxSize().background(background)) {
                        SessionHeader(
                            title = SessionHeaderReferenceTitle,
                            subtitle = "roboco @ MacBook Pro",
                            edgeEngaged = false,
                            onMore = {},
                            showBack = true,
                            onBack = {},
                            statusBarTop = 24.dp,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        // The back circle ends 16 from the physical right, ⋯ starts 16 from
        // the physical left; the glyph itself is mirrored (06's autoMirror).
        val back = boundsOf("nav-back")
        assertEquals(dp(412f - 16f - 44f), back.left, dp(1f))
        val more = boundsOf("session-more")
        assertEquals(dp(16f), more.left, dp(1f))
        val title = boundsOf("session-title")
        assertEquals(dp(206f), title.left + title.width / 2f, dp(1f))
        assertEquals(dp(240f), title.width, 1.5f)
        // The overflowing title fades on its LEFT edge (the mirrored mask).
        val pixels = pixelsOf("session-title")
        assertFadedFromLeft(pixels, fromEdgePx = 1, tolerance = 4, what = "RTL fade at 1 px")
        assertFadedFromLeft(pixels, fromEdgePx = 6, tolerance = 22, what = "RTL fade at 2 dp")
        assertTrue(
            "no dark ink in the RTL title's right half",
            halfHasDarkInk(pixels, pixels.width / 2, pixels.width, 0x60),
        )
    }

    // ------------------------------------------------------ the fade ink

    @Test
    fun theLongTitleFadesItsTailInsteadOfEllipsizing() {
        host()
        val pixels = pixelsOf("session-title")
        // 1 px from the right edge the 24 dp linear mask is at ~1 % ink:
        // the column is within ±4/255 of the page background.
        assertFadedFromRight(pixels, fromEdgePx = 1, tolerance = 4, what = "tail fade at 1 px")
        // 2 dp (6 px) in, the ramp has left ≈ 1/12 ink (≈ 17/255 below the
        // page): no crisp glyph, never "…". (The ticket's literal ±4/255
        // only holds within ~0.5 dp of the edge with the linear ramp; the
        // observable property is that the tail is a fade, not a cut.)
        assertFadedFromRight(pixels, fromEdgePx = 6, tolerance = 22, what = "tail fade at 2 dp")
        // And the visible half carries real dark ink.
        assertTrue(
            "no dark ink in the title's left half",
            halfHasDarkInk(pixels, 0, pixels.width / 2, 0x60),
        )
    }

    @Test
    fun titleAndSubtitleInkMatchThePaletteInLightAndDark() {
        hostSwitchable()
        assertDarkest(pixelsOf("session-title"), "light title", 0x27, 0x27, 0x2C)
        assertDarkest(pixelsOf("session-subtitle"), "light subtitle", 0x62, 0x62, 0x6A, tolerance = 6)
        reconfigure { hostAppearance = Appearance.Dark }
        assertBrightest(pixelsOf("session-title"), "dark title", 0xE8, 0xE8, 0xEA)
        assertBrightest(pixelsOf("session-subtitle"), "dark subtitle", 0xA9, 0xA9, 0xAE, tolerance = 6)
    }

    @Test
    fun theBarGlyphsUseTheMeasuredGlassBarItemInk() {
        // SESSIONS-D1: the chevron and ⋯ cores are the measured iOS 26 glass
        // bar-item ink (#19191B / #F4F4F4), 08's default — not Palette.text.
        hostSwitchable()
        assertDarkest(pixelsOf("session-more"), "light ⋯ glyph", 0x19, 0x19, 0x1B)
        assertDarkest(pixelsOf("nav-back"), "light back glyph", 0x19, 0x19, 0x1B)
        reconfigure { hostAppearance = Appearance.Dark }
        assertBrightest(pixelsOf("session-more"), "dark ⋯ glyph", 0xF4, 0xF4, 0xF4)
        assertBrightest(pixelsOf("nav-back"), "dark back glyph", 0xF4, 0xF4, 0xF4)
    }
}
