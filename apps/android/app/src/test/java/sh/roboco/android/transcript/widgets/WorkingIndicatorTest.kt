package sh.roboco.android.transcript.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LinearFontScaleDensity
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.Motion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.transcript.ManualPoster
import sh.roboco.android.transcript.RealizedRow
import sh.roboco.android.transcript.RowContext
import sh.roboco.android.transcript.RowModel
import sh.roboco.android.transcript.TranscriptActions
import sh.roboco.android.transcript.TranscriptState
import sh.roboco.android.transcript.WidgetBox
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.NoHandle
import uniffi.roboco_core.RowDisplay
import uniffi.roboco_core.RowKind
import uniffi.roboco_core.RowPlacement
import uniffi.roboco_core.TextSystem
import uniffi.roboco_core.TranscriptView
import uniffi.roboco_core.Widget
import uniffi.roboco_core.WidgetKind

/**
 * The Working trailer's geometry, ticking, lifecycle and semantics
 * (android-ui-parity #123; Robolectric + compose-ui-test at **mdpi** —
 * 1 dp = 1 px — through the test clock wherever timing matters, with a
 * fake [nowMs] clock and an owned [MutableLifecycleOwner] so the
 * STARTED/CREATED cases are deterministic). The runner is the repo's
 * Robolectric convention, like every other behaviour test in the app.
 *
 * What the ticket pins:
 * - the trailer glyph is 14 × 14 at y = 11 in a 36-dp row (y = 20 at 54),
 *   the label at x = 22, vertically centred, its size 13.5 × bodyFactor;
 * - ticks redraw only: `compositions`/`measures` constant, `labelBuilds`
 *   +1 per second, the label advancing `1m 14s` → `1m 15s` …;
 * - no ticker when `sinceMs` is null, none below STARTED, none after the
 *   widget leaves composition, and a resample the moment STARTED returns;
 * - the trailer is silent (no text, description or live region) and the
 *   semantics node survives the Working ↔ Writing flip (TRANSCRIPT-D1);
 * - taps fall through to the host, and reduce motion keeps the clock.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-mdpi")
class WorkingIndicatorTest {

    @get:Rule
    val compose = createComposeRule()

    /** The fake wall clock: epoch ms the tests advance by hand. */
    private var nowMs = 1_000_000_000_000L

    /** The row fixtures' states, closed in [After] (the repo's convention). */
    private val states = mutableListOf<TranscriptState>()

    /** The owned lifecycle, STARTED from [Before]. */
    private val owner = MutableLifecycleOwner()

    /** The no-op engine the RowWidget dispatch fixture needs (never called). */
    private class NoopEngine : TranscriptView(NoHandle) {
        override fun toggle(key: ULong) {}
        override fun toggleDetail(row: ULong, detail: ULong, open: Boolean) {}
        override fun setViewport(width: Float, textScale: Float) {}
        override fun frame(): LayoutFrame = error("no frame staged")
        override fun shutdown() {}
        override fun close() {}
    }

    /** A [LifecycleOwner] the test can drive, for the STARTED/CREATED cases. */
    private class MutableLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

    @Before
    fun freezeClock() {
        compose.mainClock.autoAdvance = false
        WorkingIndicatorProbe.reset()
        owner.moveTo(Lifecycle.State.STARTED)
    }

    @After
    fun closeStates() {
        states.forEach { it.close() }
        states.clear()
    }

    // ------------------------------------------------------------- the hosts

    /** The trailer in its 354 × [boxH] dp row on the background colour. */
    private fun hostWorking(
        sinceMs: Long?,
        streaming: Boolean = false,
        boxH: Dp = 36.dp,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                RobocoTheme(Appearance.Light) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        Box(Modifier.size(354.dp, boxH)) {
                            WorkingIndicator(sinceMs = sinceMs, streaming = streaming, clock = { nowMs })
                        }
                    }
                }
            }
        }
        settleFrame()
    }

    /**
     * Composes, then runs exactly one frame: with `autoAdvance = false`
     * nothing measures, lays out or draws on its own, so a node query
     * needs this (the repo's frozen-clock idiom).
     */
    private fun settleFrame() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        draw()
    }

    /**
     * Draws the root once. Under Robolectric a compose frame measures and
     * lays out but never draws the view (no Choreographer traversal runs
     * on the paused looper), so the label's draw-phase cache — where the
     * probe is written — would never run; a capture draws the hierarchy,
     * re-running only an invalidated cache.
     */
    private fun draw() {
        compose.onRoot().captureToImage()
    }

    /** A test-side state write with the clock frozen (the repo's idiom). */
    private fun writeFrozen(write: () -> Unit) {
        compose.runOnUiThread {
            write()
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        draw()
    }

    /** One second of virtual time, the fake clock moving with it. */
    private fun tick() {
        nowMs += Motion.StatusTickMs
        compose.mainClock.advanceTimeBy(Motion.StatusTickMs)
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        draw()
    }

    /** A lifecycle move, drained so the repeatOnLifecycle relaunches. */
    private fun moveTo(state: Lifecycle.State) {
        compose.runOnUiThread { owner.moveTo(state) }
        compose.mainClock.advanceTimeBy(0)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        draw()
    }

    // ------------------------------------------------------------- geometry

    @Test
    fun theTrailerGlyphAndLabelPositionsInA36dpRow() {
        hostWorking(sinceMs = nowMs - 75_000L)
        val glyph = compose.onNodeWithTag("transcript-working-glyph", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertDp(0.dp, glyph.left, "glyph left")
        assertDp(11.dp, glyph.top, "glyph top: (36 − 14) / 2")
        assertDp(14.dp, widthOf(glyph), "glyph width")
        assertDp(14.dp, heightOf(glyph), "glyph height")
        assertDp(25.dp, glyph.bottom, "glyph bottom")
        assertEquals("label left: x = 22", 22f, WorkingIndicatorProbe.lastLabelTopLeftPx.x, 0.5f)
        assertEquals("label top: (36 − 1.3 · 13.5) / 2 ≈ 9.225", 9.225f, WorkingIndicatorProbe.lastLabelTopLeftPx.y, 0.5f)
        assertEquals("font size: 13.5 × bodyFactor 1", 13.5f, WorkingIndicatorProbe.lastLabelFontSizePx, 0.05f)
    }

    @Test
    fun fontScale15GrowsTheLabelButNotTheTrailer() {
        // LinearFontScaleDensity, not Density(1f, 1.5f): the plain factory
        // routes sp→dp through Android 14's non-linear FontScaleConverter
        // (active at ≥ 1.03), which yields bodyFactor ≈ 1.46 — the ticket's
        // table (label 20.25 px, row 54) assumes the exact 1.5, so the test
        // uses ticket 22's linear override (as TypeGalleryDensityTest does)
        // and provides it OUTSIDE RobocoTheme so the theme's
        // remember(density) { TypeScale.from(density) } sees it.
        compose.setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides owner,
                LocalDensity provides LinearFontScaleDensity(1f, 1.5f),
            ) {
                RobocoTheme(Appearance.Light) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        Box(Modifier.size(354.dp, 54.dp)) {
                            WorkingIndicator(sinceMs = nowMs - 75_000L, streaming = false, clock = { nowMs })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        draw()
        val glyph = compose.onNodeWithTag("transcript-working-glyph", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertDp(0.dp, glyph.left, "glyph left")
        assertDp(20.dp, glyph.top, "glyph top: (54 − 14) / 2 — the glyph never scales")
        assertDp(14.dp, heightOf(glyph), "glyph height: the fixed 14 dp")
        assertEquals("label size: 13.5 × 1.5", 20.25f, WorkingIndicatorProbe.lastLabelFontSizePx, 0.05f)
        assertEquals("label left: still x = 22", 22f, WorkingIndicatorProbe.lastLabelTopLeftPx.x, 0.5f)
    }

    @Test
    fun theDoneToolStatusIconIsA12dpBoxCentredInThe15_75Rect() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    Box(Modifier.testTag("tool-box").size(15.75.dp)) {
                        ToolStatusWidget(running = false, failed = false)
                    }
                }
            }
        }
        settleFrame()
        val parent = compose.onNodeWithTag("tool-box").getUnclippedBoundsInRoot()
        val icon = compose.onNodeWithTag("tool-status-icon", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertDp(12.dp, widthOf(icon), "icon width: 10 pt × 1.2")
        assertDp(12.dp, heightOf(icon), "icon height")
        assertDp(1.875.dp, icon.left - parent.left, "icon left: (15.75 − 12) / 2")
        assertDp(1.875.dp, icon.top - parent.top, "icon top")
    }

    @Test
    fun runningToolStatusBeatsFailedAndBothSpinnersFillTheRect() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Box(Modifier.testTag("tool-box").size(15.75.dp)) {
                            ToolStatusWidget(running = true, failed = true)
                        }
                        Box(Modifier.testTag("spin-box").size(15.75.dp)) {
                            SpinnerWidget()
                        }
                    }
                }
            }
        }
        settleFrame()
        // running beats failed: the mini spinner, never the cross.
        assertTrue(
            "ToolStatus(running = true) draws no icon node",
            compose.onAllNodesWithTag("tool-status-icon", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
        val toolBox = compose.onNodeWithTag("tool-box").getUnclippedBoundsInRoot()
        val running = compose.onNodeWithTag("tool-status-spinner", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertDp(15.75.dp, widthOf(running), "tool-status spinner width: the widget rect")
        assertDp(15.75.dp, heightOf(running), "tool-status spinner height")
        assertDp(toolBox.left, running.left, "tool-status spinner left: flush with the rect")
        assertDp(toolBox.top, running.top, "tool-status spinner top")
        val spinBox = compose.onNodeWithTag("spin-box").getUnclippedBoundsInRoot()
        val spin = compose.onNodeWithTag("transcript-spinner", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertDp(15.75.dp, widthOf(spin), "spinner width: the widget rect")
        assertDp(15.75.dp, heightOf(spin), "spinner height")
        assertDp(spinBox.left, spin.left, "spinner left: flush with the rect")
        assertDp(spinBox.top, spin.top, "spinner top")
    }

    // -------------------------------------------------------------- ticking

    @Test
    fun theFirstFrameDraws1m14sAndTicksRedrawOnly() {
        hostWorking(sinceMs = nowMs - 74_500L)
        assertEquals("Working…  1m 14s", WorkingIndicatorProbe.lastLabel)
        val compositions = WorkingIndicatorProbe.compositions
        val measures = WorkingIndicatorProbe.measures
        val builds = WorkingIndicatorProbe.labelBuilds
        for (i in 1..5) {
            tick()
            assertEquals("tick $i", "Working…  1m ${14 + i}s", WorkingIndicatorProbe.lastLabel)
        }
        assertEquals("no recomposition per tick", compositions, WorkingIndicatorProbe.compositions)
        assertEquals("no remeasure per tick", measures, WorkingIndicatorProbe.measures)
        assertEquals("one label build per tick", builds + 5, WorkingIndicatorProbe.labelBuilds)
    }

    @Test
    fun aNullSinceRunsNoTicker() {
        hostWorking(sinceMs = null)
        assertEquals("Working…", WorkingIndicatorProbe.lastLabel)
        val builds = WorkingIndicatorProbe.labelBuilds
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        draw()
        assertEquals("no ticker to run", builds, WorkingIndicatorProbe.labelBuilds)
        assertEquals("Working…", WorkingIndicatorProbe.lastLabel)
    }

    @Test
    fun removingTheWidgetStopsTheTicker() {
        var shown by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                RobocoTheme(Appearance.Light) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        if (shown) {
                            Box(Modifier.size(354.dp, 36.dp)) {
                                WorkingIndicator(sinceMs = nowMs - 74_500L, streaming = false, clock = { nowMs })
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        writeFrozen { shown = false }
        val builds = WorkingIndicatorProbe.labelBuilds
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        assertEquals("the ticker left with the widget", builds, WorkingIndicatorProbe.labelBuilds)
    }

    @Test
    fun belowStartedTheTickerStopsAndStartedResamplesAtOnce() {
        hostWorking(sinceMs = nowMs - 74_500L)
        moveTo(Lifecycle.State.CREATED)
        val builds = WorkingIndicatorProbe.labelBuilds
        nowMs += 3_000
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        draw()
        assertEquals("CREATED: no ticks", builds, WorkingIndicatorProbe.labelBuilds)
        // 4 s of wall time pass while stopped; STARTED resamples at once.
        nowMs += 1_000
        moveTo(Lifecycle.State.STARTED)
        assertEquals("the resample redraws once", builds + 1, WorkingIndicatorProbe.labelBuilds)
        assertEquals("Working…  1m 18s", WorkingIndicatorProbe.lastLabel)
    }

    // ------------------------------------------------------------ semantics

    @Test
    fun theTrailerIsSilentAndSurvivesTheStreamingFlip() {
        var streaming by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                RobocoTheme(Appearance.Light) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        Box(Modifier.size(354.dp, 36.dp)) {
                            WorkingIndicator(sinceMs = nowMs - 74_500L, streaming = streaming, clock = { nowMs })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        draw()
        val node = compose.onNodeWithTag("transcript-working").fetchSemanticsNode()
        assertNull("no ContentDescription (TRANSCRIPT-D1)", node.config.getOrNull(SemanticsProperties.ContentDescription))
        assertNull("no Text: the label is paint, not a text node", node.config.getOrNull(SemanticsProperties.Text))
        assertNull("no live region", node.config.getOrNull(SemanticsProperties.LiveRegion))
        val id = node.id

        writeFrozen { streaming = true }
        assertEquals(
            "the same semantics node across the flip",
            id,
            compose.onNodeWithTag("transcript-working").fetchSemanticsNode().id,
        )
        assertEquals("the word swapped", "Writing…  1m 14s", WorkingIndicatorProbe.lastLabel)

        tick()
        val after = compose.onNodeWithTag("transcript-working").fetchSemanticsNode()
        assertEquals("ticks change no semantics identity", id, after.id)
        assertNull(after.config.getOrNull(SemanticsProperties.Text))
        assertNull(after.config.getOrNull(SemanticsProperties.ContentDescription))
    }

    @Test
    fun theDecorativeWidgetsExposeNoActionsOrDescriptions() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Box(Modifier.testTag("tool-box").size(15.75.dp)) {
                            ToolStatusWidget(running = false, failed = true)
                        }
                        Box(Modifier.testTag("tool-running-box").size(15.75.dp)) {
                            ToolStatusWidget(running = true, failed = false)
                        }
                        Box(Modifier.testTag("spin-box").size(15.75.dp)) {
                            SpinnerWidget()
                        }
                    }
                }
            }
        }
        settleFrame()
        // The done xmark draws no semantics node at all (contentDescription
        // null); the two test-tagged glyph nodes carry no descriptions,
        // actions or focus state.
        for (tag in listOf("tool-box", "tool-running-box", "spin-box")) {
            val box = compose.onNodeWithTag(tag).fetchSemanticsNode()
            assertNull("$tag has no ContentDescription", box.config.getOrNull(SemanticsProperties.ContentDescription))
            assertNull("$tag has no OnClick", box.config.getOrNull(SemanticsActions.OnClick))
            assertNull("$tag is not focusable", box.config.getOrNull(SemanticsProperties.Focused))
        }
        val spin = compose.onNodeWithTag("transcript-spinner", useUnmergedTree = true).fetchSemanticsNode()
        assertNull(spin.config.getOrNull(SemanticsProperties.ContentDescription))
        assertNull(spin.config.getOrNull(SemanticsActions.OnClick))
    }

    // ------------------------------------------------------------ the wiring

    @Test
    fun rowWidgetDispatchesAllThreeStatusWidgets() {
        val state = TranscriptState(TextSystem(NoHandle), ManualPoster()) { _, _ -> NoopEngine() }
            .also { states += it }
        val workingSince = System.currentTimeMillis() - 60_000L
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                RobocoTheme(Appearance.Light) {
                    Box(Modifier.size(390.dp, 76.dp)) {
                        val widgets = listOf(
                            Widget(1u, WidgetKind.ToolStatus(running = false, failed = true), 18f, 0f, 15.75f, 15.75f, null, null),
                            Widget(2u, WidgetKind.Spinner, 18f, 30f, 15.75f, 15.75f, null, null),
                            Widget(3u, WidgetKind.Working(workingSince, false), 18f, 40f, 354f, 36f, null, null),
                        )
                        val placement = remember { RowPlacement(1u, 77uL, 1uL, 0f, 76f, RowKind.MARKDOWN) }
                        val row = remember { RealizedRow(77uL, state, placement) }
                        val model = remember {
                            RowModel(
                                RowDisplay(
                                    77uL, 1uL, 390f, 76f, "",
                                    emptyList(), emptyList(), emptyList(), emptyList(),
                                    widgets, emptyList(), "",
                                ),
                            )
                        }
                        val ctx = RowContext(
                            state = state,
                            row = row,
                            kind = RowKind.MARKDOWN,
                            model = model,
                            palette = IntArray(0),
                            actions = { TranscriptActions() },
                            live = false,
                        )
                        for (w in widgets) WidgetBox(ctx, w)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        draw()
        // ToolStatus(false, true) → the failed xmark node, centred in the
        // 15.75-dp rect Rust placed at (18, 0): the icon box is 12 dp, so
        // its origin is 18 + 1.875 (± the px rounding).
        val icon = compose.onNodeWithTag("tool-status-icon", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertDp(19.875.dp, icon.left, "icon box left: rect x 18 + 1.875 centring")
        assertDp(1.875.dp, icon.top, "icon box top: rect y 0 + 1.875 centring")
        assertDp(12.dp, widthOf(icon), "icon box width")
        // Spinner → its glyph box is the whole widget rect.
        val spin = compose.onNodeWithTag("transcript-spinner", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertDp(18.dp, spin.left, "spinner left: the Rust rect x")
        assertDp(30.dp, spin.top, "spinner top: the Rust rect y")
        assertDp(15.75.dp, widthOf(spin), "spinner width")
        // Working → the trailer row. Unmerged: WidgetBox clears a non-control
        // widget's subtree from the tree (129 / TRANSCRIPT-D1), so the tag
        // exists in the unmerged tree alone.
        compose.onNodeWithTag("transcript-working", useUnmergedTree = true).assertExists()
        val glyph = compose.onNodeWithTag("transcript-working-glyph", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertDp(18.dp, glyph.left, "glyph left: rect x + 0")
        assertDp(51.dp, glyph.top, "glyph top: rect y 40 + 11")
        assertEquals("Working…  1m 0s", WorkingIndicatorProbe.lastLabel)
    }

    // -------------------------------------------------------- pointer input

    @Test
    fun tapsOnTheTrailerFallThroughToTheHost() {
        val taps = intArrayOf(0)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                RobocoTheme(Appearance.Light) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(RobocoTheme.palette.background)
                            .clickable(interactionSource = null, indication = null) { taps[0]++ },
                    ) {
                        Box(Modifier.size(354.dp, 36.dp)) {
                            WorkingIndicator(sinceMs = null, streaming = false, clock = { nowMs })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        settleFrame()
        // Unmerged: the clickable host merges its descendants, so the
        // tag-only trailer node exists in the unmerged tree alone.
        compose.onNodeWithTag("transcript-working", useUnmergedTree = true).performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals("the widget consumed nothing", 1, taps[0])
    }

    // --------------------------------------------------------- reduce motion

    @Test
    fun reduceMotionFreezesTheGlyphsButTheClockKeepsTicking() {
        // The glyph statics are ticket 11's; here the ticket's rule is that
        // the elapsed label keeps advancing under reduced motion.
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                RobocoTheme(Appearance.Light) {
                    CompositionLocalProvider(LocalReduceMotion provides true) {
                        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                            Box(Modifier.size(354.dp, 36.dp)) {
                                WorkingIndicator(sinceMs = nowMs - 74_500L, streaming = false, clock = { nowMs })
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        draw()
        assertEquals("Working…  1m 14s", WorkingIndicatorProbe.lastLabel)
        tick()
        assertEquals("the seconds still tick under reduce motion", "Working…  1m 15s", WorkingIndicatorProbe.lastLabel)
        assertEquals("the glyph node still exists (static alphas)", 1, countTaggedNodes("transcript-working-glyph"))
    }

    // -------------------------------------------------------------- helpers

    private fun countTaggedNodes(tag: String): Int =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun assertDp(expected: Dp, actual: Dp, what: String, tolerance: Float = 0.5f) {
        assertTrue(
            "$what: $actual dp, expected $expected ± $tolerance dp",
            abs(actual.value - expected.value) <= tolerance,
        )
    }

    /** [DpRect] carries no width/height of its own in this Compose. */
    private fun widthOf(bounds: DpRect): Dp = bounds.right - bounds.left

    private fun heightOf(bounds: DpRect): Dp = bounds.bottom - bounds.top
}
