package sh.roboco.android.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
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
import sh.roboco.android.design.LinearFontScaleDensity
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.TypeScale
import sh.roboco.android.shell.AppRouter
import sh.roboco.android.shell.DraftHandoff

/**
 * The connection line's geometry, semantics, pressed fill and entrance
 * (android-ui-parity #50), on Robolectric with NATIVE graphics at
 * **mdpi** (`w402dp-h480dp-mdpi`) — px = dp — the ticket's 402 dp width,
 * with the glyph clock frozen at t = 0 so the spinner rows are
 * byte-stable.
 *
 * The single-a11y-node checks read the **merged** tree (the merged node
 * is the whole item); the glyph/caption frames are measured through
 * child tags in the unmerged tree, which lists every original node even
 * under the item's `clearAndSetSemantics` (tickets 37/38/39 document the
 * same). The entrance checks compare captures of the node's pixels at
 * paused-clock checkpoints, like ticket 39's chevron checks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h480dp-mdpi")
class ConnectionLineTest {

    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------ fixtures

    // The shared debug fixtures (the same shapes the gallery and the
    // goldens draw); only the pixel-math dot row is local, because it
    // needs a static glyph and a short caption.
    private val reconnecting = ConnectionLineFixtures.reconnecting
    private val mixed = ConnectionLineFixtures.mixed
    private val overflow = ConnectionLineFixtures.fiveEngines

    /** The static dot fixture the pixel checks use (no spinner pixels). */
    private val dangerDot = ConnectionLineUi(
        listOf(ConnectionLineEntry(ConnectionGlyph.DangerDot, "Session revoked")),
    )

    // -------------------------------------------------------------- helpers

    /**
     * Hosts [content] the way 41's list does: on the page background,
     * inside the theme, glyph clock frozen at 0. [fontScale] overrides
     * both `LocalDensity` (outside the theme, so its `TypeScale.from` sees
     * it) and `LocalTypeScale` — ticket 22's linear trick, exact at
     * 0.85 / 1 / 1.6. It must be [LinearFontScaleDensity]: a plain
     * `Density(1f, 1.6f)` goes through Android's non-linear font-scale
     * table, not k = 1.6.
     */
    private fun host(fontScale: Float = 1f, reduce: Boolean = false, content: @Composable () -> Unit) {
        val d = LinearFontScaleDensity(1f, fontScale)
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides d,
                LocalTypeScale provides TypeScale.from(d),
            ) {
                RobocoTheme(Appearance.Light) {
                    // Inside the theme: RobocoTheme's ProvideReduceMotion
                    // re-provides the system value over anything outside it.
                    CompositionLocalProvider(LocalReduceMotion provides reduce) {
                        ProvideGlyphClock(frozenAtNanos = 0L) {
                            Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                                content()
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** The item's pixels at mdpi (px = dp), captured from its own bounds. */
    private fun pixels(): PixelMap =
        compose.onNodeWithTag("connection-line").captureToImage().toPixelMap()

    private fun assertPixel(px: PixelMap, x: Int, y: Int, r: Int, g: Int, b: Int, what: String) {
        val c = px[x, y]
        fun ok(v: Float, e: Int) = abs(v - e / 255f) <= 2f / 255f
        assertTrue(
            "$what at ($x, $y): rgb(${(c.red * 255f).roundToInt()}, ${(c.green * 255f).roundToInt()}, " +
                "${(c.blue * 255f).roundToInt()}), expected ($r, $g, $b) \u00B12",
            ok(c.red, r) && ok(c.green, g) && ok(c.blue, b),
        )
    }

    /**
     * The rendered alpha of the danger dot at its centre (26, 16): the
     * entrance composites the whole item over the page background, so
     * `alpha = (bg \u2212 px) / (bg \u2212 dot)` per channel (the dot is
     * pure #DC2626, the background #F3F3F5).
     */
    private fun dotAlpha(px: PixelMap): Float {
        val bgR = 243
        val dotR = 220
        val r = px[26, 16].red * 255f
        return (bgR - r) / (bgR - dotR)
    }

    /** An [AppRouter] fake that counts Settings opens (the tap target). */
    private class RecordingRouter : AppRouter {
        var settings = 0
        override fun openSession(chatId: String) {}
        override fun openSession(chatId: String, handoff: DraftHandoff?) {}
        override fun presentNewSession(prompt: String?) {}
        override fun showSettings() { settings++ }
        override fun showSearch() {}
        override fun showSessionOpenError() {}
        override fun popToFrontPage() {}
    }

    // ------------------------------------------------------------- heights

    @Test
    fun oneEntryIs32dpAtFontScale1() {
        host { ConnectionLine(reconnecting, onTap = {}) }
        compose.onNodeWithTag("connection-line").assertHeightIsEqualTo(32.dp)
    }

    @Test
    fun twoEntriesAre64dp() {
        host { ConnectionLine(mixed, onTap = {}) }
        compose.onNodeWithTag("connection-line").assertHeightIsEqualTo(64.dp)
    }

    @Test
    fun threeLinesAndTheOverflowAre128dp() {
        host { ConnectionLine(overflow, onTap = {}) }
        compose.onNodeWithTag("connection-line").assertHeightIsEqualTo(128.dp)
    }

    @Test
    fun atFontScale1_6OneLineIs51dp() {
        host(fontScale = 1.6f) { ConnectionLine(reconnecting, onTap = {}) }
        compose.onNodeWithTag("connection-line").assertHeightIsEqualTo(51.dp)
    }

    @Test
    fun atFontScale0_85OneLineIs27dp() {
        host(fontScale = 0.85f) { ConnectionLine(reconnecting, onTap = {}) }
        compose.onNodeWithTag("connection-line").assertHeightIsEqualTo(27.dp)
    }

    // ------------------------------------------------- the glyph and caption

    @Test
    fun theGlyphSlotSitsAtX20Y10AndStays12dp() {
        host { ConnectionLine(reconnecting, onTap = {}) }
        val b = compose.onNodeWithTag("connection-line-glyph", useUnmergedTree = true)
            .getBoundsInRoot()
        assertEquals(20f, b.left.value, 0.5f)
        assertEquals(10f, b.top.value, 0.5f)
        assertEquals(32f, b.right.value, 0.5f)
        assertEquals(22f, b.bottom.value, 0.5f)
    }

    // One test per clamp end: a test may set its content only once.
    @Test
    fun theGlyphStays12dpAtTheLowFontScaleClamp() = assertGlyph12dpAt(0.85f)

    @Test
    fun theGlyphStays12dpAtTheHighFontScaleClamp() = assertGlyph12dpAt(1.6f)

    private fun assertGlyph12dpAt(k: Float) {
        host(fontScale = k) { ConnectionLine(reconnecting, onTap = {}) }
        val b = compose.onNodeWithTag("connection-line-glyph", useUnmergedTree = true)
            .getBoundsInRoot()
        assertEquals("k = $k: 12 dp wide", 12f, b.width.value, 0.5f)
        assertEquals("k = $k: 12 dp tall", 12f, b.height.value, 0.5f)
        assertEquals("k = $k: x stays 20", 20f, b.left.value, 0.5f)
    }

    @Test
    fun theCaptionStartsAtX40() {
        host { ConnectionLine(reconnecting, onTap = {}) }
        // The text node itself (its own layout node, placed at the slot's
        // start edge) and the caption frame agree on the left edge. The
        // frame (FadingText) carries the caption as its own Text semantics
        // too, so the inner text node is the match that is not the frame.
        val text = compose.onNode(
            hasText("Reconnecting\u2026") and !hasTestTag("connection-line-caption"),
            useUnmergedTree = true,
        ).getBoundsInRoot()
        assertEquals(40f, text.left.value, 0.5f)
        val frame = compose.onNodeWithTag("connection-line-caption", useUnmergedTree = true)
            .getBoundsInRoot()
        assertEquals(40f, frame.left.value, 0.5f)
    }

    @Test
    fun theCaptionFrameEndsAtX382() {
        host { ConnectionLine(reconnecting, onTap = {}) }
        // W = 402: the frame is W − 40 − 20 = 342 wide, ending 20 short of
        // the right edge (the trailing-fade room).
        val frame = compose.onNodeWithTag("connection-line-caption", useUnmergedTree = true)
            .getBoundsInRoot()
        assertEquals(382f, frame.right.value, 0.5f)
        assertEquals(342f, frame.width.value, 0.5f)
    }

    // ----------------------------------------------------------- semantics

    @Test
    fun theItemIsOneButtonNodeWithTheJoinedCaptionsAndAPoliteLiveRegion() {
        host { ConnectionLine(mixed, onTap = {}) }
        val config = compose.onNodeWithTag("connection-line").fetchSemanticsNode().config
        assertEquals(Role.Button, config[SemanticsProperties.Role])
        assertEquals(
            "Studio \u2014 Session revoked, Laptop \u2014 Reconnecting\u2026",
            config[SemanticsProperties.ContentDescription].single(),
        )
        assertEquals(LiveRegionMode.Polite, config[SemanticsProperties.LiveRegion])
        // The children are cleared from the merged tree: glyphs are
        // decorative, the captions ride the item's own label.
        assertTrue(
            "the child captions are not separate merged nodes",
            compose.onAllNodesWithText("Laptop \u2014 Reconnecting\u2026").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun performClickOpensSettingsExactlyOnceAndFiresNoHaptic() {
        val router = RecordingRouter()
        val feedback = RecordingFeedback()
        compose.setContent {
            CompositionLocalProvider(LocalFeedback provides feedback) {
                RobocoTheme(Appearance.Light) {
                    ProvideGlyphClock(frozenAtNanos = 0L) {
                        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                            ConnectionLine(mixed, onTap = router::showSettings)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("connection-line").performClick()
        compose.waitForIdle()
        assertEquals(1, router.settings)
        compose.onNodeWithTag("connection-line")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(2, router.settings)
        assertTrue("a tap only navigates — no haptic", feedback.haptics.isEmpty())
        assertTrue(feedback.cues.isEmpty())
    }

    @Test
    fun aNullUiEmitsNoItemAndASetUiEmitsOne() {
        var ui by mutableStateOf<ConnectionLineUi?>(null)
        var listState: LazyListState? = null
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    val state = rememberLazyListState()
                    listState = state
                    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                        connectionLineItem(ui, onTap = {})
                        item(key = "other", contentType = "other") {
                            Box(Modifier.fillMaxWidth().height(10.dp))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(1, listState!!.layoutInfo.totalItemsCount)
        compose.onNodeWithTag("connection-line").assertDoesNotExist()

        compose.runOnUiThread { ui = reconnecting }
        compose.waitForIdle()
        assertEquals(2, listState!!.layoutInfo.totalItemsCount)
        compose.onNodeWithTag("connection-line").assertExists()
        compose.onNodeWithTag("connection-line").assertHeightIsEqualTo(32.dp)
    }

    // ------------------------------------------------------ the fill pixels

    @Test
    fun thePressedFillCoversTheItemInOneRoundedRectAndVanishesOnRelease() {
        host { ConnectionLine(dangerDot, onTap = {}) }
        compose.onNodeWithTag("connection-line").performTouchInput { down(center) }
        // The gesture stays down across the capture (no up event); the
        // fill appears and vanishes at once — no fade, no ripple.
        val pressed = pixels()
        val w = pressed.width
        // controlFill (0x1327272C) over background \u2248 #E4E4E6 inside
        // the 8 dp side inset \u2026
        assertPixel(pressed, w / 2, 4, 228, 228, 230, "pressed centre")
        // \u2026 and the page shows through the inset \u2026
        assertPixel(pressed, 4, 4, 243, 243, 245, "pressed side inset")
        // \u2026 and the 12 dp radius leaves the exact corner (8, 2)
        // outside the rounded rect (a sharp rect would fill it).
        assertPixel(pressed, 8, 2, 243, 243, 245, "pressed rounded corner")
        compose.onNodeWithTag("connection-line").performTouchInput { up() }
        compose.waitForIdle()
        val released = pixels()
        assertPixel(released, w / 2, 4, 243, 243, 245, "released centre")
        assertEquals("the fill snaps out with no fade", pressed.width, released.width)
    }

    // ------------------------------------------------------- the entrance

    @Test
    fun theEntranceStartsInvisibleAndSettlesOver500ms() {
        compose.mainClock.autoAdvance = false
        host { ConnectionLine(dangerDot, onTap = {}) }
        // First frame: alpha 0 — nothing but the page.
        val first = pixels()
        assertPixel(first, 26, 16, 243, 243, 245, "first frame: invisible")
        val firstBounds = compose.onNodeWithTag("connection-line").getBoundsInRoot()

        // 100 ms: ExpoOut at t = 0.2 \u2248 0.75 (assert 0.6\u20260.9).
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        val mid = pixels()
        assertTrue(
            "alpha at 100 ms is ${dotAlpha(mid)}",
            dotAlpha(mid) in 0.6f..0.9f,
        )

        // 700 ms: settled — alpha 1, full dot colour.
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val settled = pixels()
        assertPixel(settled, 26, 16, 220, 38, 38, "settled dot")
        // getBoundsInRoot includes the graphicsLayer's translationY, so the
        // first frame reads EntranceRise (4 dp) lower; the slot itself (the
        // settled bounds: at the top, same x and size) never moves.
        val settledBounds = compose.onNodeWithTag("connection-line").getBoundsInRoot()
        assertEquals("the layout slot never moves — only the layer does", 0f, settledBounds.top.value, 0.5f)
        assertEquals(settledBounds.left.value, firstBounds.left.value, 0.5f)
        assertEquals(settledBounds.width.value, firstBounds.width.value, 0.5f)
        assertEquals(settledBounds.height.value, firstBounds.height.value, 0.5f)
        assertEquals("the first frame is the layer's 4 dp rise", 4f, (firstBounds.top - settledBounds.top).value, 0.5f)
    }

    @Test
    fun theEntranceRisesAboutOneDpAt100ms() {
        compose.mainClock.autoAdvance = false
        host { ConnectionLine(dangerDot, onTap = {}) }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        val mid = pixels()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val settled = pixels()

        // translationY = 4 \u00D7 (1 \u2212 t) \u2248 1 dp at t = 0.75: the
        // dot's ink sits one mdpi pixel lower mid-flight than settled.
        fun topInkRow(px: PixelMap): Int {
            for (y in 0 until px.height) {
                if (abs(px[26, y].red - 243f / 255f) > 6f / 255f) return y
            }
            return -1
        }
        val midTop = topInkRow(mid)
        val settledTop = topInkRow(settled)
        assertTrue("mid ink row $midTop vs settled $settledTop", settledTop in (midTop - 2)..(midTop - 0))
    }

    @Test
    fun reduceMotionShowsTheFullFirstFrame() {
        compose.mainClock.autoAdvance = false
        host(reduce = true) { ConnectionLine(dangerDot, onTap = {}) }
        // No entrance at all: alpha 1, translationY 0, in the first frame.
        val first = pixels()
        assertPixel(first, 26, 16, 220, 38, 38, "reduced first frame: full dot")
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals(
            "nothing animates under reduce motion",
            0,
            differingPixels(first, pixels()),
        )
    }

    @Test
    fun aLabelChangeDoesNotRestartTheFade() {
        compose.mainClock.autoAdvance = false
        var ui by mutableStateOf(mixed)
        host { ConnectionLine(ui, onTap = {}) }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        val settled = pixels()
        assertPixel(settled, 26, 16, 220, 38, 38, "settled dot before the change")

        // The label changes while the item is shown: the composition (and
        // its Animatable) survive, so the fade does not run again.
        compose.runOnUiThread { ui = mixed.copy(entries = mixed.entries.map { it.copy(caption = "Renamed \u2014 Reconnecting\u2026") }) }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val after = pixels()
        assertPixel(after, 26, 16, 220, 38, 38, "the fade never restarts")
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
}
