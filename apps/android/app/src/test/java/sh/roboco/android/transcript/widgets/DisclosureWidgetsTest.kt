package sh.roboco.android.transcript.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Cue
import sh.roboco.android.design.Feedback
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.NoFeedback
import sh.roboco.android.design.Palette
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
 * The three disclosure widgets' behaviour (ticket 120, Compose test rule at
 * 393 dp × xxhdpi → d = 3), through the test clock where timing matters:
 *
 * - the chevron: a first sighting never animates (the first frame reads
 *   the final angle); a flip driven by [ToolChevronMemory] starts at the
 *   old angle, is mid-way after `advanceTimeBy(70)` (a wide window — the
 *   test clock runs in 16 ms frames and the tween starts on the first
 *   frame after the effect launches), settles at exactly the target, and
 *   never overshoots when frame-stepped; the same happens when a
 *   persisted composable's `expanded` changes, an interrupted flip
 *   restarts from the current angle, and the reverse direction mirrors
 *   it; under reduce motion it snaps in the first frame while the memory
 *   is still written;
 * - the disclosure: one `Role.Button` node labelled `Collapse` / `Expand`
 *   by the painted state, filling its widget rect, drawing nothing at
 *   rest or pressed (pixel-identical), a click recording
 *   `haptic:Select` **before** the toggle;
 * - the tool toggle: one `Role.Button` node labelled `Show details` /
 *   `Hide details`, nothing at rest, the whole rect in `controlFill`
 *   (0x1327272C light / 0x13E8E8EA dark over the background) while
 *   pressed with square corners, the same click order, and a vertical
 *   drag that scrolls without toggling or leaving a fill;
 * - through ticket 65's real [WidgetBox] dispatch: the chevron at the
 *   exact Rust rect, and both targets wired to the state's handlers with
 *   `ctx.model.display.key` and the painted `open`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class DisclosureWidgetsTest {

    @get:Rule
    val compose = createComposeRule()

    private val states = mutableListOf<TranscriptState>()

    /** The row key every chevron test uses (the memory is cleared per test). */
    private val key = 77uL

    @Before
    fun reset() {
        ToolChevronMemory.clearForTest()
    }

    @After
    fun tearDown() {
        states.forEach { it.close() }
        states.clear()
    }

    // ------------------------------------------------------------- fixtures

    /**
     * The test host: `RobocoTheme` on the `background` role box, the
     * feedback recorder in `LocalFeedback`, `LocalReduceMotion` provided
     * explicitly per the ticket.
     */
    private fun host(
        appearance: Appearance = Appearance.Light,
        feedback: Feedback = NoFeedback,
        reduce: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            RobocoTheme(appearance) {
                CompositionLocalProvider(LocalFeedback provides feedback, LocalReduceMotion provides reduce) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) { content() }
                }
            }
        }
    }

    /** The chevron in its 15.75 dp widget rect, reading [expanded] so recomposition flips it. */
    @Composable
    private fun ChevronSlot(expanded: () -> Boolean) {
        ToolChevron(key, expanded(), Modifier.size(15.75.dp))
    }

    /** The no-op engine that records the toggle calls [TranscriptState] makes. */
    private class RecordingEngine : TranscriptView(NoHandle) {
        val toggles = ArrayList<ULong>()
        val detailToggles = ArrayList<Triple<ULong, ULong, Boolean>>()
        override fun toggle(key: ULong) {
            toggles += key
        }

        override fun toggleDetail(row: ULong, detail: ULong, open: Boolean) {
            detailToggles += Triple(row, detail, open)
        }

        override fun setViewport(width: Float, textScale: Float) {}
        override fun frame(): LayoutFrame = error("no frame staged")
        override fun shutdown() {}
        override fun close() {}
    }

    private fun newState(engine: RecordingEngine): TranscriptState =
        TranscriptState(TextSystem(NoHandle), ManualPoster()) { _, _ -> engine }
            .also { states += it }

    /**
     * One row's three widgets through ticket 65's real [WidgetBox]
     * dispatch, at the Rust rects: the chevron at (24.1875, 6.75) 15.75²,
     * the disclosure over the header (18, 0, 145, 29.25) and the tool
     * toggle over a tool row (18, 40, 357, 36).
     */
    @Composable
    private fun WidgetRow(state: TranscriptState, rowKey: ULong) {
        val widgets = listOf(
            Widget(1u, WidgetKind.Chevron(expanded = true), 24.1875f, 6.75f, 15.75f, 15.75f, null, null),
            Widget(2u, WidgetKind.Disclosure(expanded = true), 18f, 0f, 145f, 29.25f, null, null),
            Widget(3u, WidgetKind.ToolToggle(detail = 9uL, open = true), 18f, 40f, 357f, 36f, null, null),
        )
        Box(Modifier.size(393.dp, 100.dp)) {
            val placement = remember(rowKey) { RowPlacement(1u, rowKey, 1uL, 0f, 100f, RowKind.TOOLS) }
            val row = remember(rowKey) { RealizedRow(rowKey, state, placement) }
            val model = remember(rowKey) {
                RowModel(
                    RowDisplay(
                        rowKey, 1uL, 393f, 100f, "",
                        emptyList(), emptyList(), emptyList(), emptyList(),
                        widgets, emptyList(), "",
                    ),
                )
            }
            val ctx = RowContext(
                state = state,
                row = row,
                kind = RowKind.TOOLS,
                model = model,
                palette = IntArray(0),
                actions = { TranscriptActions() },
                live = false,
            )
            for (w in widgets) WidgetBox(ctx, w)
        }
    }

    // -------------------------------------------------------------- helpers

    /** The chevron's current rotation, through the test-only semantics key. */
    private fun angle(): Float =
        compose.onNodeWithTag("transcript-tool-chevron").fetchSemanticsNode().config[ChevronAngleKey]

    /**
     * A state write made outside composition while the clock is frozen:
     * under Robolectric the apply notification is posted to the paused
     * main Looper and the recomposition waits for a frame, neither of
     * which `advanceTimeBy` provides on its own. One frame recomposes and
     * relaunches the effect; the animation first ticks on the frame after.
     */
    private fun settleAfterWrite(write: () -> Unit) {
        compose.runOnUiThread {
            write()
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun assertDp(expected: Dp, actual: Dp, what: String, tolerance: Float = 0.5f) {
        assertTrue(
            "$what: $actual dp, expected $expected ± $tolerance dp",
            abs(actual.value - expected.value) <= tolerance,
        )
    }

    /** [DpRect] carries no width/height of its own in this Compose. */
    private fun widthOf(bounds: DpRect): Dp = bounds.right - bounds.left

    private fun heightOf(bounds: DpRect): Dp = bounds.bottom - bounds.top

    private fun hostPixels(): PixelMap = compose.onNodeWithTag("host").captureToImage().toPixelMap()

    /** Per-channel colour assertion; [tolerance] is in 8-bit steps. */
    private fun assertPx(what: String, actual: Color, expected: Color, tolerance: Int = 2) {
        val t = tolerance / 255f
        assertTrue(
            "$what: rgb(${(actual.red * 255f).toInt()}, ${(actual.green * 255f).toInt()}, " +
                "${(actual.blue * 255f).toInt()}), expected $expected ± $tolerance",
            abs(actual.red - expected.red) <= t &&
                abs(actual.green - expected.green) <= t &&
                abs(actual.blue - expected.blue) <= t,
        )
    }

    private fun assertMapsEqual(what: String, a: PixelMap, b: PixelMap) {
        assertEquals("$what: width", a.width, b.width)
        assertEquals("$what: height", a.height, b.height)
        // Compare first, build the message only on a mismatch: the map is
        // ~175k pixels and every interpolation would otherwise allocate.
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (a[x, y] != b[x, y]) assertEquals("$what: pixel at ($x, $y)", a[x, y], b[x, y])
            }
        }
    }

    // ------------------------------------------------------ the chevron flip

    @Test
    fun theFirstCompositionOfAKeyNeverAnimates() {
        compose.mainClock.autoAdvance = false
        host(reduce = false) { ChevronSlot(expanded = { false }) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("the first frame reads the final angle", -90f, angle(), 1e-3f)
        compose.mainClock.advanceTimeBy(300)
        assertEquals("still no animation for a first sighting", -90f, angle(), 1e-3f)
        assertEquals(false, ToolChevronMemory.peek(key))
    }

    @Test
    fun aCollapseFromTheMemoryStartsAtTheOldAngleAndSettles() {
        compose.mainClock.autoAdvance = false
        ToolChevronMemory.swap(key, true) // the last painted state
        host(reduce = false) { ChevronSlot(expanded = { false }) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("the first frame shows the old angle", 0f, angle(), 1e-3f)
        compose.mainClock.advanceTimeBy(70)
        val mid = angle()
        assertTrue("mid-flip after 70 ms of clock, was $mid", mid < -15f && mid > -75f)
        compose.mainClock.advanceTimeBy(200)
        assertEquals("settled at the collapsed angle", -90f, angle(), 1e-3f)
        assertEquals(false, ToolChevronMemory.peek(key))
    }

    @Test
    fun theCollapseNeverMovesBackUpWhenFrameStepped() {
        compose.mainClock.autoAdvance = false
        ToolChevronMemory.swap(key, true)
        host(reduce = false) { ChevronSlot(expanded = { false }) }
        compose.mainClock.advanceTimeByFrame()
        var last = angle()
        assertEquals(0f, last, 1e-3f)
        repeat(25) {
            compose.mainClock.advanceTimeByFrame()
            val v = angle()
            assertTrue("frame $it: $v must not exceed $last (no overshoot)", v <= last + 1e-4f)
            last = v
        }
        assertEquals(-90f, angle(), 1e-3f)
    }

    @Test
    fun anExpandedChangeOnAPersistedChevronFlipsTheSameWay() {
        compose.mainClock.autoAdvance = false
        val expanded = mutableStateOf(true)
        host(reduce = false) { ChevronSlot(expanded = { expanded.value }) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("a fresh key snaps to expanded", 0f, angle(), 1e-3f)
        compose.mainClock.advanceTimeBy(300)
        assertEquals(0f, angle(), 1e-3f)

        // The flip: the composable persists, only `expanded` changes.
        settleAfterWrite { expanded.value = false }
        assertEquals("the flip's first frame shows the old angle", 0f, angle(), 1e-3f)
        compose.mainClock.advanceTimeBy(70)
        val mid = angle()
        assertTrue("mid-flip after the change, was $mid", mid < -15f && mid > -75f)
        compose.mainClock.advanceTimeBy(200)
        assertEquals(-90f, angle(), 1e-3f)
    }

    @Test
    fun anExpandFromTheMemoryIsTheMirrorImage() {
        compose.mainClock.autoAdvance = false
        ToolChevronMemory.swap(key, false)
        host(reduce = false) { ChevronSlot(expanded = { true }) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("the first frame shows the old angle", -90f, angle(), 1e-3f)
        compose.mainClock.advanceTimeBy(70)
        val mid = angle()
        assertTrue("mid-flip after 70 ms of clock, was $mid", mid > -75f && mid < -15f)
        compose.mainClock.advanceTimeBy(200)
        assertEquals(0f, angle(), 1e-3f)
    }

    @Test
    fun theExpandNeverMovesBackDownWhenFrameStepped() {
        compose.mainClock.autoAdvance = false
        ToolChevronMemory.swap(key, false)
        host(reduce = false) { ChevronSlot(expanded = { true }) }
        compose.mainClock.advanceTimeByFrame()
        var last = angle()
        repeat(25) {
            compose.mainClock.advanceTimeByFrame()
            val v = angle()
            assertTrue("frame $it: $v must not fall below $last", v >= last - 1e-4f)
            last = v
        }
        assertEquals(0f, angle(), 1e-3f)
    }

    @Test
    fun anInterruptedFlipRestartsFromTheCurrentAngle() {
        compose.mainClock.autoAdvance = false
        ToolChevronMemory.swap(key, true)
        val expanded = mutableStateOf(false)
        host(reduce = false) { ChevronSlot(expanded = { expanded.value }) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(70)
        val mid = angle()
        assertTrue("mid-collapse before the interrupt, was $mid", mid < -15f && mid > -75f)

        // Flip back mid-flight: the restart keeps the current angle …
        settleAfterWrite { expanded.value = true }
        val restart = angle()
        assertTrue("the restart is still mid-flight, was $restart", restart < -15f && restart > -90f)
        // … and settles at the new target, moving only up.
        var last = restart
        repeat(20) {
            compose.mainClock.advanceTimeByFrame()
            val v = angle()
            assertTrue("frame $it: $v must not fall below $last", v >= last - 1e-4f)
            last = v
        }
        assertEquals(0f, angle(), 1e-3f)
    }

    @Test
    fun underReduceMotionTheFlipSnapsAndTheMemoryStillUpdates() {
        compose.mainClock.autoAdvance = false
        ToolChevronMemory.swap(key, true)
        val reduce = mutableStateOf(true)
        val expanded = mutableStateOf(false)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(
                    LocalFeedback provides NoFeedback,
                    LocalReduceMotion provides reduce.value,
                ) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        ChevronSlot(expanded = { expanded.value })
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("reduce motion: snapped in the first frame", -90f, angle(), 1e-3f)
        compose.mainClock.advanceTimeBy(300)
        assertEquals(-90f, angle(), 1e-3f)
        assertEquals("the memory is still written under reduce motion", false, ToolChevronMemory.peek(key))

        // A later non-reduced recomposition with the same state does not
        // animate: the memory holds `false`, previous == expanded.
        settleAfterWrite { reduce.value = false }
        compose.mainClock.advanceTimeBy(300)
        assertEquals("no animation when the memory matches the state", -90f, angle(), 1e-3f)
    }

    // ------------------------------------------------ the disclosure target

    @Test
    fun theLabelsFollowThePaintedState() {
        host {
            Column {
                Box(Modifier.fillMaxWidth().height(29.25.dp)) { DisclosureTarget(true, {}) }
                Box(Modifier.fillMaxWidth().height(29.25.dp)) { DisclosureTarget(false, {}) }
                Box(Modifier.fillMaxWidth().height(36.dp)) { ToolToggleTarget(true, {}) }
                Box(Modifier.fillMaxWidth().height(36.dp)) { ToolToggleTarget(false, {}) }
            }
        }
        compose.waitForIdle()

        val disclosures = compose.onAllNodesWithTag("transcript-disclosure").fetchSemanticsNodes()
        assertEquals(2, disclosures.size)
        assertEquals("Collapse", disclosures[0].config[SemanticsProperties.ContentDescription]?.firstOrNull())
        assertEquals("Expand", disclosures[1].config[SemanticsProperties.ContentDescription]?.firstOrNull())
        disclosures.forEach { assertEquals(Role.Button, it.config[SemanticsProperties.Role]) }

        val toggles = compose.onAllNodesWithTag("transcript-tool-toggle").fetchSemanticsNodes()
        assertEquals(2, toggles.size)
        assertEquals("Hide details", toggles[0].config[SemanticsProperties.ContentDescription]?.firstOrNull())
        assertEquals("Show details", toggles[1].config[SemanticsProperties.ContentDescription]?.firstOrNull())
        toggles.forEach { assertEquals(Role.Button, it.config[SemanticsProperties.Role]) }
    }

    @Test
    fun theTargetsFillTheirWidgetRects() {
        host {
            Column {
                Box(Modifier.size(145.dp, 29.25.dp)) { DisclosureTarget(true, {}) }
                Box(Modifier.size(357.dp, 36.dp)) { ToolToggleTarget(false, {}) }
            }
        }
        compose.waitForIdle()

        val d = compose.onNodeWithTag("transcript-disclosure").getUnclippedBoundsInRoot()
        assertDp(145.dp, widthOf(d), "disclosure width")
        assertDp(29.25.dp, heightOf(d), "disclosure height")
        val t = compose.onNodeWithTag("transcript-tool-toggle").getUnclippedBoundsInRoot()
        assertDp(357.dp, widthOf(t), "tool toggle width")
        assertDp(36.dp, heightOf(t), "tool toggle height")
    }

    @Test
    fun theDisclosureDrawsNothingAtRestOrPressed() {
        host {
            Box(
                Modifier
                    .testTag("host")
                    .size(373.dp, 45.25.dp)
                    .background(RobocoTheme.palette.background)
                    .padding(8.dp),
            ) {
                DisclosureTarget(false, {}, Modifier.size(145.dp, 29.25.dp))
            }
        }
        compose.waitForIdle()
        val idle = hostPixels()

        // Pressed (alpha 0.5 on the empty box): pixel-identical — the
        // chevron and the title are siblings, never dimmed on iOS either.
        compose.onNodeWithTag("transcript-disclosure").performTouchInput { down(center) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(200)
        assertMapsEqual("pressed", idle, hostPixels())

        compose.onNodeWithTag("transcript-disclosure").performTouchInput { up() }
        compose.waitForIdle()
        assertMapsEqual("released", idle, hostPixels())
    }

    @Test
    fun aDisclosureClickPlaysTheSelectHapticBeforeTheToggle() {
        val order = mutableListOf<String>()
        host(
            feedback = object : Feedback {
                override fun haptic(haptic: Haptic) {
                    order += "haptic:$haptic"
                }

                override fun cue(cue: Cue) = Unit
            },
        ) {
            Box(Modifier.fillMaxWidth().height(29.25.dp)) {
                DisclosureTarget(false, { order += "toggle" })
            }
        }
        compose.onNodeWithTag("transcript-disclosure").performClick()
        compose.waitForIdle()
        assertEquals(listOf("haptic:Select", "toggle"), order)
    }

    // ------------------------------------------------- the tool toggle

    @Test
    fun aToolToggleClickPlaysTheSelectHapticBeforeTheToggle() {
        val order = mutableListOf<String>()
        host(
            feedback = object : Feedback {
                override fun haptic(haptic: Haptic) {
                    order += "haptic:$haptic"
                }

                override fun cue(cue: Cue) = Unit
            },
        ) {
            Box(Modifier.fillMaxWidth().height(36.dp)) {
                ToolToggleTarget(false, { order += "toggle" })
            }
        }
        compose.onNodeWithTag("transcript-tool-toggle").performClick()
        compose.waitForIdle()
        assertEquals(listOf("haptic:Select", "toggle"), order)
    }

    @Test
    fun thePressedFillIsTheControlFillOverTheWholeRect() {
        pressedFillCheck(Appearance.Light, Palette.Light)
    }

    @Test
    fun thePressedFillIsTheControlFillOverTheWholeRectInDark() {
        pressedFillCheck(Appearance.Dark, Palette.Dark)
    }

    /** The AC's pixel check: nothing at rest, `controlFill` over the whole rect while pressed (d = 3). */
    private fun pressedFillCheck(appearance: Appearance, palette: Palette) {
        val background = palette.background
        val pressedFill = palette.controlFill.compositeOver(background)
        host(appearance = appearance) {
            Box(
                Modifier
                    .testTag("host")
                    .size(373.dp, 52.dp)
                    .background(background)
                    .padding(8.dp),
            ) {
                ToolToggleTarget(false, {}, Modifier.size(357.dp, 36.dp))
            }
        }
        compose.waitForIdle()

        // At rest: nothing drawn — the whole widget is the background.
        val idle = hostPixels()
        assertPx("idle corner", idle[24, 24], background)
        assertPx("idle centre", idle[(8 + 178) * 3, (8 + 18) * 3], background)
        assertPx("idle far corner", idle[(8 + 356) * 3, (8 + 35) * 3], background)

        // Pressed (down held): the whole rect fills with controlFill, square
        // corners, nothing bleeding past the rect.
        compose.onNodeWithTag("transcript-tool-toggle").performTouchInput { down(center) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(300)
        val pressed = hostPixels()
        assertPx("pressed corner", pressed[24, 24], pressedFill)
        assertPx("pressed centre", pressed[(8 + 178) * 3, (8 + 18) * 3], pressedFill)
        assertPx("pressed far corner", pressed[(8 + 356) * 3, (8 + 35) * 3], pressedFill)
        assertPx("no bleed left of the rect", pressed[21, 24], background)
        assertPx("no bleed above the rect", pressed[24, 21], background)

        // Released: the fill is gone at once (no fade, either direction).
        compose.onNodeWithTag("transcript-tool-toggle").performTouchInput { up() }
        compose.waitForIdle()
        val released = hostPixels()
        assertPx("released corner", released[24, 24], background)
        assertPx("released centre", released[(8 + 178) * 3, (8 + 18) * 3], background)
    }

    @Test
    fun aVerticalDragFromTheToolToggleScrollsAndNeverToggles() {
        var toggles = 0
        host {
            // Content 500 + 36 + 600 dp in an 852 dp viewport: the row
            // stays on screen even if the swipe's fling runs to the end.
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().height(500.dp))
                Box(Modifier.fillMaxWidth().height(36.dp)) {
                    ToolToggleTarget(false, { toggles++ })
                }
                Box(Modifier.fillMaxWidth().height(600.dp))
            }
        }
        compose.waitForIdle()
        val before = compose.onNodeWithTag("transcript-tool-toggle").getUnclippedBoundsInRoot()

        // A drag that starts on the toggle: the scroll takes it, the tap
        // never fires, and the press fill is cancelled.
        compose.onNodeWithTag("transcript-tool-toggle").performTouchInput {
            swipe(center, center - Offset(0f, 150.dp.toPx()), durationMillis = 400)
        }
        compose.waitForIdle()

        val after = compose.onNodeWithTag("transcript-tool-toggle").getUnclippedBoundsInRoot()
        assertTrue("the drag scrolled the container", after.top < before.top)
        assertEquals("a drag never toggles", 0, toggles)
        // No lingering fill: the toggle draws nothing again.
        val px = compose.onNodeWithTag("transcript-tool-toggle").captureToImage().toPixelMap()
        assertPx("no fill after the drag, corner", px[2, 2], Palette.Light.background)
        assertPx("no fill after the drag, centre", px[px.width / 2, px.height / 2], Palette.Light.background)
    }

    // -------------------------------------- the RowWidget wiring (ticket 65)

    @Test
    fun rowWidgetPlacesTheChevronAtTheRustRect() {
        val state = newState(RecordingEngine())
        host { WidgetRow(state, 55uL) }
        compose.waitForIdle()
        // Unmerged: WidgetBox clears a non-control widget's subtree (129),
        // so the chevron's tag exists in the unmerged tree alone.
        val b = compose.onNodeWithTag("transcript-tool-chevron", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertDp(24.1875.dp, b.left, "chevron left")
        assertDp(6.75.dp, b.top, "chevron top")
        assertDp(15.75.dp, widthOf(b), "chevron width")
        assertDp(15.75.dp, heightOf(b), "chevron height")
    }

    @Test
    fun rowWidgetWiresTheTargetsToTheStateHandlers() {
        val engine = RecordingEngine()
        val state = newState(engine)
        val rowKey = 42uL
        host { WidgetRow(state, rowKey) }
        compose.waitForIdle()

        // A disclosure tap: engine.toggle(rowKey), the fold marked with the row key.
        compose.onNodeWithTag("transcript-disclosure").performClick()
        compose.waitForIdle()
        assertEquals(listOf(rowKey), engine.toggles)
        assertEquals(rowKey, state.pendingFold)

        // A tool-toggle tap: engine.toggleDetail(rowKey, detail, open as painted).
        compose.onNodeWithTag("transcript-tool-toggle").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Triple(rowKey, 9uL, true)), engine.detailToggles)
        assertEquals("the fold mark is the row key", rowKey, state.pendingFold)
    }
}
