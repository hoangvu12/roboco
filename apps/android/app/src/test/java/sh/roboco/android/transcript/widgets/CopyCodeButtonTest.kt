package sh.roboco.android.transcript.widgets

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
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
 * The code-block copy button's behaviour (ticket 119; Compose test rule at
 * 393 dp × xxhdpi → d = 3), through the test clock wherever timing
 * matters. The runner is the repo's Robolectric convention
 * (`RobolectricTestRunner` + compose rule), like every other behaviour
 * test in the app; the ticket's `AndroidJUnit4` note maps onto it (the
 * ext-junit runner is an androidTest-only dependency here).
 *
 * The clock is frozen ([Before]); a tap's click handler runs
 * synchronously (clipboard, haptic, the swap write and the timer launch).
 * `tap` then delivers the write and runs everything currently due at
 * that clock value (`advanceTimeBy(0)` is runCurrent, no frame — the
 * glass-menu tests' trick), so the just-launched timer coroutine arms
 * its `delay(1400)` at the tap's clock, and one frame composes the swap.
 * The timeline rows of the ticket's acceptance table are therefore
 * checked at exact millisecond boundaries (+1399 checked, +1400 idle),
 * with the tap's frame's 16 ms subtracted. Those advances pass
 * `ignoreFrameDuration = true`: plain `advanceTimeBy(n)` rounds up to a
 * 16 ms frame, which would already have fired the timer at "+1399".
 *
 * Pixel probes host the button (or the stateless glyph) in a 44 × 32 dp
 * `codeBackground` cell: the glyph ink is `tertiary` idle / `accent`
 * copied in both appearances, anti-aliased blends of nothing else, and
 * the widget itself draws no background, border, ripple or press overlay.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class CopyCodeButtonTest {

    @get:Rule
    val compose = createComposeRule()

    private val feedback = RecordingFeedback()
    private val states = mutableListOf<TranscriptState>()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun freezeClock() {
        compose.mainClock.autoAdvance = false
    }

    @After
    fun tearDown() {
        states.forEach { it.close() }
        states.clear()
    }

    // ------------------------------------------------------------- fixtures

    /** Ticket 05's recorder: appends every haptic, ignores cues. */
    private class RecordingFeedback : Feedback {
        val haptics = mutableListOf<Haptic>()
        override fun haptic(haptic: Haptic) {
            haptics += haptic
        }

        override fun cue(cue: Cue) = Unit
    }

    /** The no-op engine the row fixture's state needs (never called). */
    private class NoopEngine : TranscriptView(NoHandle) {
        override fun toggle(key: ULong) {}
        override fun toggleDetail(row: ULong, detail: ULong, open: Boolean) {}
        override fun setViewport(width: Float, textScale: Float) {}
        override fun frame(): LayoutFrame = error("no frame staged")
        override fun shutdown() {}
        override fun close() {}
    }

    /**
     * The live button in its 44 × 32 dp cell on the code-card colour. The
     * `payload` and `bump` readers let a test recompose the slot with the
     * same payload (a simulated new row version) or a different one.
     */
    private fun hostLive(
        payload: () -> String = { PAYLOAD },
        bump: () -> Int = { 0 },
        writeClip: (Context, String) -> Boolean = ::writeCodeToClipboard,
    ) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(LocalFeedback provides feedback) {
                    Box(
                        Modifier
                            .testTag("cell")
                            .size(44.dp, 32.dp)
                            .background(RobocoTheme.palette.codeBackground),
                    ) {
                        bump()
                        CopyCodeButton(payload(), Modifier, writeClip)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
    }

    /** The stateless glyph in the same cell, for the pixel probes. */
    private fun hostGlyph(appearance: Appearance, copied: Boolean) {
        compose.setContent {
            RobocoTheme(appearance) {
                CompositionLocalProvider(LocalFeedback provides feedback) {
                    Box(
                        Modifier
                            .testTag("cell")
                            .size(44.dp, 32.dp)
                            .background(RobocoTheme.palette.codeBackground),
                    ) {
                        CopyCodeGlyph(copied)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
    }

    /**
     * The widget through ticket 65's real [WidgetBox] dispatch, at the
     * exact Rust rect the ticket's acceptance table names: (313, 0, 44,
     * 32) on a 393 × 104 dp markdown row (a code card at text scale 1).
     */
    private fun hostRow(payload: String?) {
        val state = TranscriptState(TextSystem(NoHandle), ManualPoster()) { _, _ -> NoopEngine() }
            .also { states += it }
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(LocalFeedback provides feedback) {
                    Box(Modifier.size(393.dp, 104.dp)) {
                        val widgets = listOf(
                            Widget(1u, WidgetKind.CopyCode, 313f, 0f, 44f, 32f, payload, null),
                        )
                        val placement = remember { RowPlacement(1u, 77uL, 1uL, 0f, 104f, RowKind.MARKDOWN) }
                        val row = remember { RealizedRow(77uL, state, placement) }
                        val model = remember {
                            RowModel(
                                RowDisplay(
                                    77uL, 1uL, 393f, 104f, "",
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
    }

    // -------------------------------------------------------------- helpers

    /**
     * One tap: the click handler runs synchronously (clipboard, haptic,
     * the swap write, the timer launch); the write is delivered and
     * everything currently due runs at this clock value — the just-
     * launched timer coroutine included, so its 1400 ms deadline counts
     * from this moment (`advanceTimeBy(0)` is runCurrent, no frame; the
     * frame after it composes the glyph swap).
     */
    private fun tap() {
        compose.onNodeWithTag("copy-code").performClick()
        // runOnUiThread, not waitForIdle: the click just scheduled a
        // recomposition, which only runs on the next frame (the clock is
        // frozen), so an idle wait here could stall.
        compose.runOnUiThread { Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(0)
        compose.mainClock.advanceTimeByFrame()
    }

    /**
     * A test-side state write with the clock frozen: Robolectric's paused
     * looper never posts the apply notifications by itself, and the
     * recomposition runs on the next frame.
     */
    private fun writeFrozen(write: () -> Unit) {
        compose.runOnUiThread {
            write()
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun assertCheck() {
        compose.onNodeWithTag("copy-code-check", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("copy-code-copy", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun assertIdle() {
        compose.onNodeWithTag("copy-code-copy", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("copy-code-check", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun clipboard(): ClipboardManager =
        context.getSystemService(ClipboardManager::class.java) ?: error("no ClipboardManager")

    private fun clipText(): String = clipboard().primaryClip!!.getItemAt(0).text.toString()

    private fun clipLabel(): String = clipboard().primaryClip!!.description.label.toString()

    private fun cellPixels(): PixelMap = compose.onNodeWithTag("cell").captureToImage().toPixelMap()

    private fun assertDp(expected: Dp, actual: Dp, what: String, tolerance: Float = 0.5f) {
        assertTrue(
            "$what: $actual dp, expected $expected ± $tolerance dp",
            abs(actual.value - expected.value) <= tolerance,
        )
    }

    /** [DpRect] carries no width/height of its own in this Compose. */
    private fun widthOf(bounds: DpRect): Dp = bounds.right - bounds.left

    private fun heightOf(bounds: DpRect): Dp = bounds.bottom - bounds.top

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

    /** Whether [c] is within [tolerance] 8-bit steps of [ink] per channel. */
    private fun near(c: Color, ink: Color, tolerance: Int): Boolean {
        val t = tolerance / 255f
        return abs(c.red - ink.red) <= t &&
            abs(c.green - ink.green) <= t &&
            abs(c.blue - ink.blue) <= t
    }

    /**
     * The cell's ink is [ink] and the widget draws nothing else: every
     * pixel is a blend of [bg] and [ink] (anti-aliased strokes included),
     * the corners are [bg], and at least one pixel is near-solid [ink].
     */
    private fun assertCellGlyph(what: String, px: PixelMap, bg: Color, ink: Color) {
        assertPx("$what corner (1, 1)", px[1, 1], bg)
        assertPx("$what corner (w-2, 1)", px[px.width - 2, 1], bg)
        assertPx("$what corner (1, h-2)", px[1, px.height - 2], bg)
        assertPx("$what corner (w-2, h-2)", px[px.width - 2, px.height - 2], bg)
        var solid = 0
        for (y in 0 until px.height) {
            for (x in 0 until px.width) {
                val c = px[x, y]
                assertBlend("$what pixel at ($x, $y)", c, bg, ink)
                if (near(c, ink, 3)) solid++
            }
        }
        assertTrue("$what: no near-solid $ink ink in the glyph", solid >= 8)
    }

    /** [c] lies per channel between [a] and [b] (an alpha blend of the two). */
    private fun assertBlend(what: String, c: Color, a: Color, b: Color, tolerance: Int = 3) {
        val t = tolerance / 255f
        fun out(v: Float, p: Float, q: Float): Boolean = v < minOf(p, q) - t || v > maxOf(p, q) + t
        if (out(c.red, a.red, b.red) || out(c.green, a.green, b.green) || out(c.blue, a.blue, b.blue)) {
            assertTrue(
                "$what: rgb(${(c.red * 255f).toInt()}, ${(c.green * 255f).toInt()}, " +
                    "${(c.blue * 255f).toInt()}) is not a blend of $a and $b ± $tolerance",
                false,
            )
        }
    }

    private fun assertMapsEqual(what: String, a: PixelMap, b: PixelMap) {
        assertEquals("$what: width", a.width, b.width)
        assertEquals("$what: height", a.height, b.height)
        // Compare first, build the message only on a mismatch.
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (a[x, y] != b[x, y]) assertEquals("$what: pixel at ($x, $y)", a[x, y], b[x, y])
            }
        }
    }

    // -------------------------------------------------- the wiring (ticket 65)

    @Test
    fun rowWidgetPlacesTheButtonAtTheRustRect() {
        hostRow(payload = PAYLOAD)
        val b = compose.onNodeWithTag("copy-code").getUnclippedBoundsInRoot()
        assertDp(313.dp, b.left, "button left")
        assertDp(0.dp, b.top, "button top")
        assertDp(44.dp, widthOf(b), "button width")
        assertDp(32.dp, heightOf(b), "button height")
        // The glyph box: 13 pt × 1.2 = 15.6 dp, centred — its centre is
        // (335, 16), the cell's centre.
        val g = compose.onNodeWithTag("copy-code-copy", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertDp(327.2.dp, g.left, "glyph left")
        assertDp(8.2.dp, g.top, "glyph top")
        assertDp(15.6.dp, widthOf(g), "glyph width")
        assertDp(15.6.dp, heightOf(g), "glyph height")
        assertDp(335.dp, (g.left + g.right) / 2f, "glyph centre x")
        assertDp(16.dp, (g.top + g.bottom) / 2f, "glyph centre y")
    }

    @Test
    fun aNullPayloadCopiesTheEmptyString() {
        hostRow(payload = null)
        tap()
        assertEquals("the clip is the empty string, like w.payload ?: \"\"", "", clipText())
        assertEquals(listOf(Haptic.Light), feedback.haptics)
        assertCheck()
    }

    // ------------------------------------------------------- the tap behaviour

    @Test
    fun aTapWritesTheClipLabelAndPlaysOneLightHaptic() {
        hostLive()
        tap()
        assertEquals(PAYLOAD, clipText())
        assertEquals("the invisible ClipData label", "Code", clipLabel())
        assertEquals(listOf(Haptic.Light), feedback.haptics)
        assertCheck()
    }

    @Test
    fun theSwapIsInstantInAccentAndBack() {
        hostLive()
        tap()
        assertCheck()
        // The first frame after the tap already shows pure accent ink —
        // a crossfade would still be part tertiary here.
        assertCellGlyph("swapped", cellPixels(), Palette.Light.codeBackground, Palette.Light.accent)
        compose.mainClock.advanceTimeBy(1383, ignoreFrameDuration = true) // t = 1399 (the tap's frame ran 16)
        compose.mainClock.advanceTimeBy(1, ignoreFrameDuration = true)    // t = 1400: the timer fires
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertIdle()
        assertCellGlyph("reverted", cellPixels(), Palette.Light.codeBackground, Palette.Light.tertiary)
    }

    // -------------------------------------------------------------- the timing

    @Test
    fun theCheckHoldsUntil1399msAndRevertsAt1400ms() {
        hostLive()
        tap()                                  // t = 0: the timer is armed
        assertCheck()
        compose.mainClock.advanceTimeBy(1383, ignoreFrameDuration = true)  // t = 1399 (the tap's frame ran 16)
        compose.waitForIdle()
        assertCheck()                          // still checked at +1399 ms
        compose.mainClock.advanceTimeBy(1, ignoreFrameDuration = true)     // t = 1400: the timer fires
        compose.mainClock.advanceTimeByFrame() // the swap-back recomposes
        compose.waitForIdle()
        assertIdle()                           // idle at +1400 ms
    }

    @Test
    fun tapsAt0And1000msRevertAt1400msAndWriteTwice() {
        hostLive()
        tap()                                  // t = 0 (deadline 1400)
        compose.mainClock.advanceTimeBy(984, ignoreFrameDuration = true)   // t = 1000 (the tap's frame ran 16)
        tap()                                  // t = 1000 (its own deadline: 2400)
        compose.mainClock.advanceTimeBy(383, ignoreFrameDuration = true)   // t = 1399
        compose.waitForIdle()
        assertCheck()                          // the 1000 ms tap's check is still up
        compose.mainClock.advanceTimeBy(1, ignoreFrameDuration = true)     // t = 1400: the FIRST timer fires
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertIdle()                           // idle at +1400 ms
        assertEquals("each tap copied once", 2, feedback.haptics.size)
        assertEquals(PAYLOAD, clipText())
    }

    @Test
    fun tapsAt01000And1500msStayCheckedUntil2400ms() {
        hostLive()
        tap()                                  // t = 0 (deadline 1400)
        compose.mainClock.advanceTimeBy(984, ignoreFrameDuration = true)   // t = 1000
        tap()                                  // t = 1000 (deadline 2400)
        compose.mainClock.advanceTimeBy(484, ignoreFrameDuration = true)   // t = 1500
        tap()                                  // t = 1500 (deadline 2900)
        assertCheck()                          // checked from +1500 ms
        compose.mainClock.advanceTimeBy(883, ignoreFrameDuration = true)   // t = 2399
        compose.waitForIdle()
        assertCheck()                          // checked at +2399 ms
        compose.mainClock.advanceTimeBy(1, ignoreFrameDuration = true)     // t = 2400: the 1000 ms tap's timer fires
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertIdle()                           // idle at +2400 ms
        assertEquals("each tap copied once", 3, feedback.haptics.size)
    }

    @Test
    fun aFailedClipboardWritePlaysNoHapticAndStaysIdle() {
        hostLive(writeClip = { _, _ -> false })
        tap()
        compose.waitForIdle()
        assertTrue("no haptic on a failed write", feedback.haptics.isEmpty())
        assertIdle()
        assertTrue("no clip written", clipboard().primaryClip == null)
    }

    // ------------------------------------------------- the payload-change rules

    @Test
    fun theSamePayloadKeepsTheCheckAcrossRecompositions() {
        val payload = mutableStateOf(PAYLOAD)
        val bump = mutableStateOf(0)
        hostLive(payload = { payload.value }, bump = { bump.value })
        tap()
        assertCheck()
        // A new row version with the same payload: the state survives
        // (the ticket's ADAPT; a re-layout while streaming).
        writeFrozen { bump.value = 1 }
        assertCheck()
        // … and still reverts on the original timer: t = 1400.
        compose.mainClock.advanceTimeBy(1367, ignoreFrameDuration = true)  // t = 1399 (two frames ran: 32)
        compose.waitForIdle()
        assertCheck()
        compose.mainClock.advanceTimeBy(1, ignoreFrameDuration = true)     // t = 1400
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertIdle()
    }

    @Test
    fun aDifferentPayloadResetsToIdleAndTheOldTimerCannotReachIt() {
        val payload = mutableStateOf(PAYLOAD)
        hostLive(payload = { payload.value })
        tap()
        assertCheck()
        writeFrozen { payload.value = OTHER_PAYLOAD }
        assertIdle()                          // the new block starts idle
        compose.mainClock.advanceTimeBy(2000) // the old timer fires into the discarded state
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        assertIdle()                          // never checked again
        // The new payload copies its own text.
        tap()
        assertEquals(OTHER_PAYLOAD, clipText())
    }

    // ------------------------------------------------------------- accessibility

    private val hasCopyCodeLabel = SemanticsMatcher("labelled Copy code") { node: SemanticsNode ->
        // getOrNull: onAllNodes walks every node (the root first), and config[] throws
        // "Key not present" on any node without a content description.
        node.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Copy code")
    }

    @Test
    fun theNodeIsOneLabelledButtonAndItsClickActionCopies() {
        hostLive()
        val node = compose.onNodeWithTag("copy-code").fetchSemanticsNode()
        assertEquals(listOf("Copy code"), node.config[SemanticsProperties.ContentDescription])
        assertEquals(Role.Button, node.config[SemanticsProperties.Role])
        assertTrue("the node has a click action", node.config.contains(SemanticsActions.OnClick))
        assertEquals(
            "exactly one node carries the label",
            1,
            compose.onAllNodes(hasCopyCodeLabel).fetchSemanticsNodes().size,
        )
        // Performing the click action copies, like a tap.
        compose.onNodeWithTag("copy-code").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnUiThread { Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(0)
        compose.mainClock.advanceTimeByFrame()
        assertEquals(PAYLOAD, clipText())
        assertEquals(listOf(Haptic.Light), feedback.haptics)
        assertCheck()
    }

    // ----------------------------------------------------------- press and drag

    @Test
    fun pressingDrawsNoOverlayAndACancelledPressNeverCopies() {
        compose.mainClock.autoAdvance = true
        hostLive()
        val idle = cellPixels()
        compose.onNodeWithTag("copy-code").performTouchInput { down(center) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        assertMapsEqual("pressed", idle, cellPixels())
        compose.onNodeWithTag("copy-code").performTouchInput { cancel() }
        compose.waitForIdle()
        assertMapsEqual("after the cancelled press", idle, cellPixels())
        assertTrue("a cancelled press never copies", feedback.haptics.isEmpty())
        assertTrue("a cancelled press never writes", clipboard().primaryClip == null)
    }

    @Test
    fun aVerticalDragThatScrollsTheTranscriptNeverCopies() {
        compose.mainClock.autoAdvance = true
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(LocalFeedback provides feedback) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Box(Modifier.fillMaxWidth().height(500.dp))
                        Box(Modifier.fillMaxWidth().height(32.dp)) {
                            CopyCodeButton(PAYLOAD)
                        }
                        Box(Modifier.fillMaxWidth().height(600.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        val before = compose.onNodeWithTag("copy-code").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("copy-code").performTouchInput {
            swipe(center, center - Offset(0f, 150.dp.toPx()), durationMillis = 400)
        }
        compose.waitForIdle()
        val after = compose.onNodeWithTag("copy-code").getUnclippedBoundsInRoot()
        assertTrue("the drag scrolled the container", after.top < before.top)
        assertTrue("a drag never copies", feedback.haptics.isEmpty())
        assertTrue("a drag never writes the clipboard", clipboard().primaryClip == null)
    }

    // ------------------------------------------------------------- the glyph ink

    @Test
    fun theIdleGlyphInkIsTertiaryLight() = glyphInk(Appearance.Light, copied = false)

    @Test
    fun theIdleGlyphInkIsTertiaryDark() = glyphInk(Appearance.Dark, copied = false)

    @Test
    fun theCopiedGlyphInkIsAccentLight() = glyphInk(Appearance.Light, copied = true)

    @Test
    fun theCopiedGlyphInkIsAccentDark() = glyphInk(Appearance.Dark, copied = true)

    private fun glyphInk(appearance: Appearance, copied: Boolean) {
        hostGlyph(appearance, copied)
        val palette = Palette.of(appearance == Appearance.Dark)
        val what = (if (copied) "copied" else "idle") + " " +
            (if (appearance == Appearance.Dark) "dark" else "light")
        assertCellGlyph(
            what,
            cellPixels(),
            palette.codeBackground,
            if (copied) palette.accent else palette.tertiary,
        )
    }

    private companion object {
        /** One fenced block's source, as Rust would hand it over (one trailing newline stripped). */
        const val PAYLOAD = "let band = floor((offset - 700f) / 256f) * 256f"

        /** A second block: a payload change resets the confirmed state. */
        const val OTHER_PAYLOAD = "let step = max(1.0, ceil(count / 12.0))"
    }
}
