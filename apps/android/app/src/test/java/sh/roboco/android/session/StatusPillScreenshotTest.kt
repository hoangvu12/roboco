package sh.roboco.android.session

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.math.abs
import kotlin.math.max
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Palette
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass

/**
 * The status pill's Robolectric/Roborazzi tests (android-ui-parity #73):
 * the light/dark goldens of the ticket's Step-12 fixture column, the
 * measured geometry (density 3), the font-scale invariance of the fixed-13
 * label, the label ink colour, `.none` keeping the previous content, the
 * same-frame content swap, the 1 s Working ticker (and its cancellation),
 * the tap consumption (nothing reaches a sibling underneath, no press
 * feedback) and the TalkBack semantics contract.
 *
 * Every test follows the freeze discipline: `mainClock.autoAdvance =
 * false`, a `Snapshot.sendApplyNotifications()` + one frame after each
 * state write, live spinners under `ProvideGlyphClock(frozenAtNanos = 0L)`
 * (nested inside [RobocoTheme]), and the glass fallback tier (Robolectric
 * has no haze source anyway, `Glass.forceFallback` pins it).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h760dp-xxhdpi")
class StatusPillScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeClockAndForceFallbackGlass() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
    }

    @After
    fun restoreGlass() {
        Glass.forceFallback = false
    }

    /** The fixture's fixed clock: the Working row reads `Working · 1m 15s`. */
    private val fixedNow: () -> Long = { 1_000_000L }

    private fun host(appearance: Appearance = Appearance.Light, body: @Composable () -> Unit) {
        compose.setContent {
            RobocoTheme(appearance) {
                ProvideGlyphClock(frozenAtNanos = 0L) { body() }
            }
        }
        compose.waitForIdle()
    }

    /** State write + notification + one frame, the frozen-clock recipe. */
    private fun applyState() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
    }

    // -------------------------------------------------------------- goldens

    @Test
    fun lightGolden() {
        host(Appearance.Light) { StatusPillFixtureColumn() }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("status-pill-fixture").captureRoboImage("session/StatusPill_light.png")
    }

    @Test
    fun darkGolden() {
        host(Appearance.Dark) { StatusPillFixtureColumn() }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("status-pill-fixture").captureRoboImage("session/StatusPill_dark.png")
    }

    // ------------------------------------------------------------ geometry

    /** Height 30; glyph 12×12 at 11, centred; label 8 after the glyph, 12 before the end. */
    @Test
    fun sendingPillGeometry() {
        host { StatusPillRow(SessionChrome.Banner.Sending, onTap = {}, nowMs = fixedNow) }
        val pill = compose.onNodeWithTag("status-pill").getBoundsInRoot()
        assertEquals("the pill's height", 30f, pill.height.value, 0.5f)
        assertEquals("the pill hugs the row's start", 0f, pill.left.value, 0.5f)
        assertTrue("empty space to the pill's right: ${pill.right.value}", pill.right.value < 300f)

        val glyph = compose.onNodeWithTag("status-pill-glyph", useUnmergedTree = true).getBoundsInRoot()
        assertEquals("glyph left inset", 11f, glyph.left.value, 0.5f)
        assertEquals("glyph box width", 12f, glyph.width.value, 0.5f)
        assertEquals("glyph box height", 12f, glyph.height.value, 0.5f)
        assertEquals("glyph vertically centred", pill.top.value + 9f, glyph.top.value, 0.5f)

        val label = compose.onNodeWithText("Sending to host…", useUnmergedTree = true).getBoundsInRoot()
        assertEquals("label 8 dp after the glyph", glyph.right.value + 8f, label.left.value, 0.5f)
        assertEquals("label 12 dp before the end", pill.right.value - 12f, label.right.value, 0.5f)
    }

    /** The long-message fixture: one line, the `…` at the label's end, the pill exactly the row width. */
    @Test
    fun theLongMessageIsOneLineEndingInAnEllipsisAndFillsTheRow() {
        val longMessage = "Couldn't send: " + "a very long error message ".repeat(6)
        host { StatusPillRow(SessionChrome.Banner.Failed(longMessage), onTap = {}, nowMs = fixedNow) }
        val pill = compose.onNodeWithTag("status-pill").getBoundsInRoot()
        assertEquals("the pill is exactly the row width", 390f, pill.width.value, 0.5f)
        val label = compose.onNodeWithText(longMessage, useUnmergedTree = true).getBoundsInRoot()
        assertTrue("one line (the 1.3 × 13 line box): ${label.height.value}", label.height.value in 15.5f..18.5f)
        assertEquals("the label fills its slot", pill.right.value - 12f, label.right.value, 0.5f)

        // The visible truncation: ink in the last 5 dp of the label slot.
        val px = compose.onNodeWithTag("status-pill").captureToImage().toPixelMap()
        val d = compose.density.density
        val backdrop = px[((23f + 4f) * d).toInt(), (15f * d).toInt()]
        val x1 = ((label.right.value - pill.left.value) * d).toInt()
        val x0 = x1 - (5f * d).toInt()
        val y0 = ((label.top.value - pill.top.value) * d).toInt() + 1
        val y1 = ((label.bottom.value - pill.top.value) * d).toInt() - 1
        var inkAtTheEnd = false
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val c = px[x, y]
                if (abs(c.red - backdrop.red) > 0.1f ||
                    abs(c.green - backdrop.green) > 0.1f ||
                    abs(c.blue - backdrop.blue) > 0.1f
                ) {
                    inkAtTheEnd = true
                }
            }
        }
        assertTrue("the label ends in a visible ellipsis", inkAtTheEnd)
    }

    /** `fixed(13)`: the same 1.3 × 13 dp line box at font scale 2, height still 30. */
    @Test
    fun theLabelKeepsItsSizeAtFontScale2() {
        RuntimeEnvironment.setFontScale(2f)
        try {
            host { StatusPillRow(SessionChrome.Banner.Sending, onTap = {}, nowMs = fixedNow) }
            assertEquals(30f, compose.onNodeWithTag("status-pill").getBoundsInRoot().height.value, 0.5f)
            val label = compose.onNodeWithText("Sending to host…", useUnmergedTree = true).getBoundsInRoot()
            assertTrue("the label box ignores font scale: ${label.height.value}", label.height.value in 15.5f..18.5f)
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    // -------------------------------------------------------------- colour

    @Test
    fun theLabelInkIsSecondaryInLight() = assertLabelInk(Appearance.Light)

    @Test
    fun theLabelInkIsSecondaryInDark() = assertLabelInk(Appearance.Dark)

    /** The label's core ink pixel is exactly `Palette.secondary` (#62626A / #A9A9AE). */
    private fun assertLabelInk(appearance: Appearance) {
        host(appearance) {
            StatusPillRow(SessionChrome.Banner.NotDelivered, onTap = {}, nowMs = fixedNow)
        }
        val palette = Palette.of(appearance == Appearance.Dark)
        val pill = compose.onNodeWithTag("status-pill").getBoundsInRoot()
        val label = compose.onNodeWithText("Not delivered · Tap to retry", useUnmergedTree = true).getBoundsInRoot()
        val px = compose.onNodeWithTag("status-pill").captureToImage().toPixelMap()
        val d = compose.density.density
        // The glass fill sample: the 8 dp gap between the glyph and the
        // label at mid-height (clear of the dot, the rim and the text).
        val backdrop = px[((23f + 4f) * d).toInt(), (15f * d).toInt()]
        val x0 = ((label.left.value - pill.left.value) * d).toInt() + 1
        val x1 = ((label.right.value - pill.left.value) * d).toInt() - 1
        val y0 = ((label.top.value - pill.top.value) * d).toInt() + 1
        val y1 = ((label.bottom.value - pill.top.value) * d).toInt() - 1
        val ink = maxInk(px, backdrop, x0, y0, x1, y1)
            ?: throw AssertionError("no label ink found in the pill's pixel map")
        assertColorNear(ink, palette.secondary, "label ink")
    }

    // --------------------------------------------------- `.none` and swaps

    /** iOS `case .none: break`: the previous label keeps drawing, taps do nothing. */
    @Test
    fun noneKeepsThePreviousLabelAndSwallowsTaps() {
        var banner by mutableStateOf<SessionChrome.Banner>(SessionChrome.Banner.Sending)
        var taps = 0
        host { StatusPillRow(banner, onTap = { taps++ }, nowMs = fixedNow) }
        compose.onNodeWithText("Sending to host…", useUnmergedTree = true).assertExists()

        banner = SessionChrome.Banner.None
        applyState()
        compose.onNodeWithText("Sending to host…", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("status-pill").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals("a .none pill swallows the tap", 0, taps)
        compose.onNodeWithTag("status-pill").assert(hasNoClickAction())
    }

    /** The content swaps in the same frame — no crossfade, never both texts. */
    @Test
    fun sendingToNotDeliveredSwapsInTheSameFrame() {
        var banner by mutableStateOf<SessionChrome.Banner>(SessionChrome.Banner.Sending)
        host { StatusPillRow(banner, onTap = {}, nowMs = fixedNow) }
        compose.onNodeWithText("Sending to host…", useUnmergedTree = true).assertExists()

        banner = SessionChrome.Banner.NotDelivered
        applyState()
        compose.onNodeWithText("Not delivered · Tap to retry", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("Sending to host…", useUnmergedTree = true).assertCountEquals(0)
    }

    // ------------------------------------------------------------- the ticker

    /** `Working · 5s` → `Working · 6s`, then the ticker cancels with the banner. */
    @Test
    fun theWorkingLabelTicksEverySecondAndStopsWhenTheBannerChanges() {
        var fakeNow = 0L
        var banner by mutableStateOf<SessionChrome.Banner>(SessionChrome.Banner.Working(0L, "Working"))
        host { StatusPillRow(banner, onTap = {}, nowMs = { fakeNow }) }
        compose.onNodeWithText("Working…", useUnmergedTree = true).assertExists()

        fakeNow = 5_000L
        compose.mainClock.advanceTimeBy(1_000)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Working · 5s", useUnmergedTree = true).assertExists()

        fakeNow = 6_000L
        compose.mainClock.advanceTimeBy(1_000)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Working · 6s", useUnmergedTree = true).assertExists()

        // banner → None: the ticker is cancelled, so the frozen label never
        // advances again even after two minutes of virtual time (a live
        // ticker would have re-rendered `Working · 2m 6s` by now).
        banner = SessionChrome.Banner.None
        applyState()
        fakeNow = 126_000L
        compose.mainClock.advanceTimeBy(120_000)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Working · 6s", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("Working · 2m 6s", useUnmergedTree = true).assertCountEquals(0)
    }

    /** The ticker leaves composition with the pill: no clock reads afterwards. */
    @Test
    fun theTickerStopsWhenThePillLeavesComposition() {
        var fakeNow = 0L
        var reads = 0
        // Snapshot state: a plain `var` would never invalidate the host, so
        // the pill (and its 1 s ticker) would simply stay composed.
        var shown by mutableStateOf(true)
        host {
            if (shown) {
                StatusPillRow(
                    SessionChrome.Banner.Working(0L, "Working"),
                    onTap = {},
                    nowMs = { reads++; fakeNow },
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.mainClock.advanceTimeByFrame()
        val before = reads
        assertTrue("the ticker reads the clock at least twice: $before", before >= 2)

        shown = false
        applyState()
        compose.mainClock.advanceTimeBy(10_000)
        compose.mainClock.advanceTimeByFrame()
        assertEquals("no reads after the pill left composition", before, reads)
    }

    // --------------------------------------------------------- tap routing

    /** The pill consumes its tap; a sibling `pointerInput` under it never sees it. */
    @Test
    fun aTapOnThePillDoesNotReachASiblingUnderneath() {
        var under = 0
        var taps = 0
        host {
            Box(Modifier.fillMaxWidth().height(30.dp)) {
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) { detectTapGestures { under++ } }
                        .testTag("pill-under-box"),
                )
                StatusPillRow(SessionChrome.Banner.NotDelivered, onTap = { taps++ }, nowMs = fixedNow)
            }
        }
        compose.onNodeWithTag("status-pill").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(1, taps)
        assertEquals("the tap never reaches the sibling under the pill", 0, under)

        // The box under it IS live: the same tap on a bare spot of the row
        // (outside the pill) reaches it.
        compose.onRoot().performTouchInput { click(Offset(900f, 45f)) } // 300 dp, 15 dp
        compose.waitForIdle()
        assertEquals(1, under)
    }

    /** No ripple, highlight or scale while the pill is pressed (iOS `UIControl` has none). */
    @Test
    fun pressingThePillDrawsNoFeedback() {
        var taps = 0
        host { StatusPillRow(SessionChrome.Banner.NotDelivered, onTap = { taps++ }, nowMs = fixedNow) }
        val node = compose.onNodeWithTag("status-pill")
        val before = node.captureToImage().toPixelMap()

        node.performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(400) // any press animation would land by now
        compose.mainClock.advanceTimeByFrame()
        val during = node.captureToImage().toPixelMap()
        assertTrue("no press feedback — identical pixels", samePixels(before, during))

        node.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals("the held press still dispatched the tap", 1, taps)
    }

    // --------------------------------------------------------- semantics

    /**
     * One merged node reading the label; the click action exists for
     * `NotDelivered` / `Editing` / `EngineParked` and not for
     * `Reconnecting` / `EngineOff` / `Sending` / `Failed` (the screen's
     * `tapEnabled` gate).
     */
    @Test
    fun talkBackMergesTheLabelAndExposesClickOnlyForActionableBanners() {
        val actionability = { banner: SessionChrome.Banner -> bannerTapAction(banner, true) != BannerTap.None }
        var taps = 0
        var banner by mutableStateOf<SessionChrome.Banner>(SessionChrome.Banner.NotDelivered)
        host { StatusPillRow(banner, onTap = { taps++ }, tapEnabled = actionability, nowMs = fixedNow) }

        val node = compose.onNodeWithTag("status-pill")
        assertEquals(
            "Not delivered · Tap to retry",
            node.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text },
        )
        node.assert(hasClickAction())
        node.performClick()
        assertEquals(1, taps)

        for (actionable in listOf(
            SessionChrome.Banner.Editing,
            SessionChrome.Banner.EngineParked(SessionChrome.ParkedReason.Revoked, "e1"),
        )) {
            banner = actionable
            applyState()
            compose.onNodeWithTag("status-pill").assert(hasClickAction())
        }
        for (plain in listOf(
            SessionChrome.Banner.Failed("x"),
            SessionChrome.Banner.EngineOff,
            SessionChrome.Banner.Reconnecting,
            SessionChrome.Banner.Sending,
        )) {
            banner = plain
            applyState()
            compose.onNodeWithTag("status-pill").assert(hasNoClickAction())
        }
    }

    // ------------------------------------------------------------- helpers

    /** The pixel with the greatest channel distance from [bg] — full ink. */
    private fun maxInk(px: PixelMap, bg: Color, x0: Int, y0: Int, x1: Int, y1: Int): Color? {
        var best: Color? = null
        var bestDiff = 0f
        for (y in y0..y1) {
            for (x in x0..x1) {
                val c = px[x, y]
                val diff = max(abs(c.red - bg.red), max(abs(c.green - bg.green), abs(c.blue - bg.blue)))
                if (diff > bestDiff) {
                    bestDiff = diff
                    best = c
                }
            }
        }
        return best
    }

    private fun assertColorNear(actual: Color, expected: Color, what: String, tolerance: Float = 0.06f) {
        assertTrue(
            "$what: $actual, expected $expected (±$tolerance)",
            abs(actual.red - expected.red) <= tolerance &&
                abs(actual.green - expected.green) <= tolerance &&
                abs(actual.blue - expected.blue) <= tolerance,
        )
    }

    private fun samePixels(a: PixelMap, b: PixelMap): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (a[x, y] != b[x, y]) return false
            }
        }
        return true
    }
}
