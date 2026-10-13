package sh.roboco.android.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.withKeysDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalFonts
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.NoFeedback
import sh.roboco.android.design.Palette
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle

/**
 * [ComposerTextField] — android-ui-parity #57's acceptance criteria:
 * Robolectric + compose-ui-test with `@GraphicsMode(NATIVE)` (real text
 * measurement, so Geist's real advance widths drive the wrapping) at
 * **mdpi** (`w411dp-h891dp-mdpi`, 1 dp = 1 px — the
 * ComposerMorphBehaviourTest convention; the 891 dp window keeps the
 * height class regular). The harness:
 *
 * - the field in a `Box(Modifier.requiredWidth(slotWidth))` (268 dp
 *   capsule / 338 dp card — the slots of a 370 dp composer), on
 *   `Palette.Light.background`, inside `RobocoTheme`;
 * - `isCard`, the slot width and `textHidden` are test-held state, so a
 *   test flips them with `runOnUiThread` exactly as 55/56 would;
 * - a **probe** `BasicText` below, same text, same style, same width —
 *   the ticket's "second BasicText with the same style" hook: its
 *   [TextLayoutResult] gives `L` (`getLineBottom(0) − getLineTop(0)`),
 *   the first baseline and the full content height, so the rendered
 *   expectations are computed from the *measured* metrics, never pasted;
 * - a "Blur" text button (foundation `BasicText` + `clickable`, never a
 *   Material button) that clears the focus — the port of the iOS UI
 *   test's keyboard-wait.
 *
 * Clock: the timing tests freeze `mainClock` and step
 * `advanceTimeByFrame` (the effect's launch alignment, the
 * ComposerMorphBehaviourTest convention) then `advanceTimeBy(ms)`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-mdpi")
class ComposerTextFieldTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() {
        ReduceMotion.resetForTest()
    }

    // ------------------------------------------------------------- state

    /** Flipped by the tests; read by the harness (56's `isCard` input). */
    private var card by mutableStateOf(false)

    /** The slot width — follows the mode, as 55's layout does. */
    private var slotWidth by mutableStateOf(268.dp)

    /** 95's `textHidden`, flipped by the tests. */
    private var hidden by mutableStateOf(false)

    /** The mounted controller, assigned during composition. */
    private lateinit var controller: ComposerController

    /** The probe's layout (same text/style/width as the field's own). */
    private var probeLayout: TextLayoutResult? = null

    /** The Geist family the composition resolved (the font check). */
    private var probeFamily: FontFamily? = null

    private var heightCalls = 0
    private val focusEvents = mutableListOf<Boolean>()

    // ----------------------------------------------------------- harness

    /**
     * Mounts the field. [wire] configures the controller inside its
     * `remember` (onSend counters and friends) before the field sees it.
     */
    private fun mount(
        text: String,
        isCard: Boolean,
        width: Dp,
        wire: (ComposerController.() -> Unit)? = null,
        onTextRect: ((Rect) -> Unit)? = null,
    ) {
        card = isCard
        slotWidth = width
        hidden = false
        heightCalls = 0
        focusEvents.clear()
        probeLayout = null
        probeFamily = null
        // A compose rule accepts one setContent per test ("has already set
        // content"): a later mount swaps the harness inside the first one,
        // and key(generation) makes it a fresh first composition (a new
        // controller, a new field, a new height animator).
        val holder = mounted
        if (holder == null) {
            val h = mutableStateOf(Mounted(0, text, wire, onTextRect))
            mounted = h
            compose.setContent {
                val m = h.value
                key(m.generation) {
                    RobocoTheme(Appearance.Light) { Harness(m.text, m.wire, m.onTextRect) }
                }
            }
        } else {
            compose.runOnUiThread {
                holder.value = Mounted(holder.value.generation + 1, text, wire, onTextRect)
            }
        }
        compose.waitForIdle()
        if (!compose.mainClock.autoAdvance) {
            // Frozen clock: the first layout's TextLayoutResult (the
            // measured L and line count) reaches the height target only on
            // a recomposition, and waitForIdle pumps no frame without
            // autoAdvance — so the slot would still sit at its pre-layout
            // fallback height. Frame 1 recomposes with the layout and the
            // effect snaps (a reflow); frame 2 relays the slot out.
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    /** One mount's inputs; [generation] keys the harness so each mount composes fresh. */
    private class Mounted(
        val generation: Int,
        val text: String,
        val wire: (ComposerController.() -> Unit)?,
        val onTextRect: ((Rect) -> Unit)?,
    )

    /** The single setContent's current mount, swapped by every later [mount]. */
    private var mounted: MutableState<Mounted>? = null

    /**
     * A test-side snapshot write with the clock frozen. Robolectric runs
     * the test on the main thread with the Looper paused, so nothing posts
     * the apply notifications, and `advanceTimeBy*` only advances the
     * coroutine scheduler: without [Snapshot.sendApplyNotifications] the
     * Recomposer never sees the write.
     */
    private fun writeFrozen(write: () -> Unit) {
        compose.runOnUiThread {
            write()
            Snapshot.sendApplyNotifications()
        }
    }

    /**
     * A text edit with the clock frozen, then the frames before the 180 ms
     * tween's first frame: the edit recomposes the field (frame 1), whose
     * new TextLayoutResult moves the height target on the next
     * recomposition, where the effect launches `animateTo` (frame 2); the
     * tween's own clock starts on frame 3. The target is derived from the
     * *measured* layout, so this feedback frame is inherent.
     */
    private fun editFrozen(write: () -> Unit) {
        writeFrozen(write)
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
    }

    @Composable
    private fun Harness(
        text: String,
        wire: (ComposerController.() -> Unit)?,
        onTextRect: ((Rect) -> Unit)?,
    ) {
        val palette = RobocoTheme.palette
        val scope = rememberCoroutineScope()
        val ctrl = remember { ComposerController(scope, NoFeedback, text).also { wire?.invoke(it) } }
        controller = ctrl
        probeFamily = LocalFonts.current.geist
        val focusManager = LocalFocusManager.current
        Box(Modifier.fillMaxSize().background(palette.background)) {
            Column {
                Box(Modifier.requiredWidth(slotWidth)) {
                    ComposerTextField(
                        controller = ctrl,
                        isCard = card,
                        focusRequester = remember { FocusRequester() },
                        onFocusChange = { focusEvents += it },
                        onHeightChange = { heightCalls++ },
                        textHidden = hidden,
                        onTextRect = onTextRect,
                    )
                }
                // The probe: same text, same style, same width — L, the
                // baseline and the full height come from here.
                BasicText(
                    text,
                    Modifier.width(slotWidth),
                    style = probeStyle(),
                    onTextLayout = { probeLayout = it },
                )
                BlurButton { focusManager.clearFocus() }
            }
        }
    }

    /** A non-Material blur trigger: a `BasicText` with `clickable`. */
    @Composable
    private fun BlurButton(onBlur: () -> Unit) {
        BasicText(
            "Blur",
            style = uiStyle(Face.SansMedium, LocalTypeScale.current.fixed(14f), RobocoTheme.palette.accent),
            modifier = Modifier
                .testTag("blur")
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onBlur,
                ),
        )
    }

    /** The field's own style, rebuilt from the same 03 helpers. */
    @Composable
    private fun probeStyle(): TextStyle =
        uiStyle(Face.Sans, LocalTypeScale.current.bodyScaled(16.5f), Palette.Light.text)
            .copy(lineBreak = LineBreak.Simple, hyphens = Hyphens.None)

    // ------------------------------------------------------------ probes

    private val probe: TextLayoutResult
        get() = probeLayout ?: error("the probe has not laid out yet")

    private fun pxToDp(px: Float): Float = with(compose.density) { px.toDp().value }

    /** The measured line height `L` (dp). */
    private fun lineDp(): Float = pxToDp(probe.getLineBottom(0) - probe.getLineTop(0))

    private fun input(): SemanticsNodeInteraction = compose.onNodeWithTag("composer-input")

    private fun SemanticsNodeInteraction.pxBounds(): Rect = with(compose.density) { getBoundsInRoot().toRect() }

    /** The field node's height (dp): the field is measured at the TARGET. */
    private fun fieldHeight(): Float = pxToDp(input().pxBounds().height)

    /** The clip box's height (dp): the ANIMATED value. */
    private fun slotHeight(): Float = pxToDp(compose.onNodeWithTag("composer-text-slot").pxBounds().height)

    private fun nodePixels(): PixelMap = input().captureToImage().toPixelMap()

    private val markerTolerance = 20f / 255f

    private fun isMarker(c: Color, marker: Color): Boolean =
        abs(c.red - marker.red) <= markerTolerance &&
            abs(c.green - marker.green) <= markerTolerance &&
            abs(c.blue - marker.blue) <= markerTolerance

    private fun countMarker(px: PixelMap, marker: Color): Int {
        var n = 0
        for (y in 0 until px.height) {
            for (x in 0 until px.width) {
                if (isMarker(px[x, y], marker)) n++
            }
        }
        return n
    }

    // ------------------------------------------- rendered heights (mdpi)

    @Test
    fun theCardHeightsFollowTheMeasuredLineHeight() {
        for ((text, expectedLines) in listOf("" to -1, "1\n2\n3\n4" to 4, ComposerTwelveLines to 12)) {
            mount(text = text, isCard = true, width = 338.dp)
            val l = lineDp()
            // Geist's natural line box: 1.3 × 16.5 = 21.45 (a stray
            // lineHeight, or Roboto, would read ≈ 19.3).
            assertEquals("L ≈ 1.3 × 16.5 (text=$text)", 21.45f, l, 1f)
            // The content height: the probe's own size for the empty text
            // (whatever an empty layout measures), n × L otherwise — the
            // exact math the component runs on its own layout.
            val content = if (expectedLines < 0) pxToDp(probe.size.height.toFloat()) else l * expectedLines
            val expected = ComposerTextMetrics.targetHeight(isCard = true, compactHeight = false, lineHeight = l, contentHeight = content)
            assertEquals("the card height (text=$text)", expected, fieldHeight(), 0.5f)
        }
        // 12 lines: capped (20 + 8L = 196 at mdpi) and scrolling inside.
        assertTrue(
            ComposerTextMetrics.scrolls(isCard = true, compactHeight = false, lineHeight = lineDp(), contentHeight = pxToDp(probe.size.height.toFloat())),
        )
    }

    @Test
    fun theCapsuleHeightsFollowTheMeasuredLineHeightAndPreviewTwoLines() {
        mount(text = "", isCard = false, width = 268.dp)
        assertEquals(50f, fieldHeight(), 0.5f)
        compose.onNodeWithTag("composer-input-preview", useUnmergedTree = true).assertDoesNotExist()

        mount(text = "a\nb\nc\nd", isCard = false, width = 268.dp)
        val l = lineDp()
        // The #593 preview: 28 + 2L, never a third sliced line.
        assertEquals(28f + 2 * l, fieldHeight(), 0.5f)
        // The preview node exists, is at most two lines tall (the bottom
        // of the second line), and the editable text is still the full
        // draft — the field is the preview's placeholder, not its twin.
        val preview = compose.onNodeWithTag("composer-input-preview", useUnmergedTree = true).pxBounds()
        assertEquals("the preview is exactly two lines", probe.getLineBottom(1), preview.height, 0.5f)
        assertEquals("a\nb\nc\nd", controller.value.text)
        assertEquals("a\nb\nc\nd", input().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    // ------------------------------------- the iOS UI test's port (#593)

    @Test
    fun longDraftRestsAsWholeLinePreview() {
        // The ticket's harness: isCard = focused, slot width 268 ↔ 338.
        compose.mainClock.autoAdvance = false
        mountFocusFollowingDraftHarness()

        // Focus, then type the whole iOS UI-test draft.
        input().performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        input().assertIsFocused()
        input().performTextInput(ComposerUIDraft)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()

        // Blur (the iOS test waits for the keyboard to go): the draft
        // rests as a preview of whole lines — two lines plus the
        // capsule's insets, never a third sliced through the bottom.
        compose.onNodeWithTag("blur").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        val resting = fieldHeight()
        assertTrue("rests under 90 dp (2 whole lines + insets): $resting", resting < 90f)
        assertEquals(ComposerUIDraft, controller.value.text)
        assertEquals(ComposerUIDraft, input().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("composer-input-preview", useUnmergedTree = true).assertExists()

        // Focus again: the full draft returns (4 lines at 338 dp).
        input().performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        val full = fieldHeight()
        assertTrue("focusing restores the full draft (> 90 dp): $full", full > 90f)
        compose.onNodeWithTag("composer-input-preview", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(ComposerUIDraft, input().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    /** The port harness: `isCard = focused`, the width follows the mode. */
    private fun mountFocusFollowingDraftHarness() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                val scope = rememberCoroutineScope()
                val ctrl = remember { ComposerController(scope, NoFeedback, "") }
                controller = ctrl
                val focusManager = LocalFocusManager.current
                var focused by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    Column {
                        Box(Modifier.requiredWidth(if (focused) 338.dp else 268.dp)) {
                            ComposerTextField(
                                controller = ctrl,
                                isCard = focused,
                                focusRequester = remember { FocusRequester() },
                                onFocusChange = { focused = it },
                            )
                        }
                        BlurButton { focusManager.clearFocus() }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    // ----------------------------------------------------- the placeholder

    @Test
    fun thePlaceholderShowsOnlyWhileTheTextIsEmptyAndDescribesTheField() {
        mount(text = "", isCard = false, width = 268.dp)
        // Tertiary placeholder ink is present in the capsule...
        assertTrue("placeholder ink", countMarker(nodePixels(), Palette.Light.tertiary) >= 3)
        // ...and the field describes itself with it while empty.
        val empty = input().fetchSemanticsNode().config
        assertTrue(empty.contains(SemanticsProperties.ContentDescription))
        assertEquals(listOf("Message"), empty[SemanticsProperties.ContentDescription])

        // Typing a single space hides it: the RAW text counts, not the
        // trimmed one (whitespace is content; IME composition would be too).
        input().performClick()
        compose.waitForIdle()
        input().performTextInput(" ")
        compose.waitForIdle()
        assertEquals(" ", controller.value.text)
        assertEquals("no placeholder ink", 0, countMarker(nodePixels(), Palette.Light.tertiary))
        val spaced = input().fetchSemanticsNode().config
        assertFalse("no contentDescription once there is text", spaced.contains(SemanticsProperties.ContentDescription))
    }

    // ---------------------------------------------------- the 180 ms tween

    @Test
    fun theHeightTweenRunsOver180msWithEaseInOutAndNotifiesOnce() {
        compose.mainClock.autoAdvance = false
        mount(text = "1", isCard = true, width = 338.dp)
        val l = lineDp()
        val from = 46f // card, 1 line: the minimum
        val to = 20f + 2 * l // 2 lines: 64 at mdpi
        assertEquals(from, slotHeight(), 0.5f)

        editFrozen { controller.text = "1\n2" } // up to the tween's first frame
        compose.mainClock.advanceTimeBy(90)
        compose.waitForIdle()
        val mid = slotHeight()
        assertTrue("strictly between at +90 ms: $mid", mid > from && mid < to)
        assertEquals("the easeInOut midpoint, one frame of slack", (from + to) / 2f, mid, 3f)
        assertEquals("onHeightChange fired once", 1, heightCalls)

        compose.mainClock.advanceTimeBy(110) // ≥ 180 ms + the launch frame
        compose.waitForIdle()
        assertEquals(to, slotHeight(), 0.5f)
        assertEquals("still exactly one notification", 1, heightCalls)
    }

    // ---------------------------------------------------- the morph spring

    @Test
    fun flippingTheCardAnimatesTheHeightOnTheMorphSpring() {
        compose.mainClock.autoAdvance = false
        mount(text = "1\n2\n3\n4", isCard = false, width = 268.dp)
        val l = lineDp()
        val capsule = 28f + 2 * l // the resting preview: 72 at mdpi
        val cardH = 20f + 4 * l // the focused card: 108 at mdpi
        assertEquals(capsule, slotHeight(), 0.5f)

        writeFrozen { card = true; slotWidth = 338.dp }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        val mid = slotHeight()
        assertTrue("not at the target at +100 ms: $mid", mid < cardH - 0.5f)
        assertTrue("moving toward it: $mid", mid > capsule)

        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        assertEquals("settled within 1 s", cardH, slotHeight(), 0.5f)
    }

    @Test
    fun reduceMotionSnapsTheMorphButThe180msTweenStillRuns() {
        ReduceMotion.debugOverride = true
        compose.mainClock.autoAdvance = false
        mount(text = "1\n2\n3\n4", isCard = false, width = 268.dp)
        val l = lineDp()
        assertEquals(28f + 2 * l, slotHeight(), 0.5f)

        // The morph-driven height change snaps in the same frame.
        writeFrozen { card = true; slotWidth = 338.dp }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals("the morph snaps under Reduce Motion", 20f + 4 * l, slotHeight(), 0.5f)

        // ...but the 180 ms text-height tween keeps running (iOS never
        // gates textChanged; only the system animator scale would).
        val from = 20f + 4 * l
        val to = 20f + 5 * l
        editFrozen { controller.text = "1\n2\n3\n4\n5" }
        compose.mainClock.advanceTimeBy(90)
        compose.waitForIdle()
        val mid = slotHeight()
        assertTrue("the tween still runs under Reduce Motion: $mid", mid > from && mid < to)
        compose.mainClock.advanceTimeBy(110)
        compose.waitForIdle()
        assertEquals(to, slotHeight(), 0.5f)
    }

    @Test
    fun flippingAlwaysCardSnapsTheHeightInTheSameFrame() {
        compose.mainClock.autoAdvance = false
        mount(text = "1\n2\n3\n4", isCard = false, width = 268.dp)
        val l = lineDp()
        assertEquals(28f + 2 * l, slotHeight(), 0.5f)

        // chipsAlwaysVisible's didSet passes animated: false — the height
        // snaps with the mode flip it caused.
        writeFrozen { controller.alwaysCard = true; card = true; slotWidth = 338.dp }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(20f + 4 * l, slotHeight(), 0.5f)
    }

    // -------------------------------------------------------- the insets

    @Test
    fun theFirstLineSits14FromTheTopAndTheBottomBandsMatchTheMode() {
        // The preview shares the field's top: 14 dp below the slot top.
        mount(text = "Ship it", isCard = false, width = 268.dp)
        val l = lineDp()
        val slotTop = input().pxBounds().top
        val previewTop = compose.onNodeWithTag("composer-input-preview", useUnmergedTree = true).pxBounds().top
        assertEquals("the first line sits 14 dp below the top", pxToDp(slotTop) + 14f, pxToDp(previewTop), 0.5f)
        // The first baseline: 14 + the font's ascent (hhea 1.005 × 16.5 ≈
        // 16.58 dp; ±1 for pixel rounding).
        assertEquals(16.58f, pxToDp(probe.getLineBaseline(0).toFloat()), 1f)
        // The capsule leaves 14 below the line: one line = 14 + L + 14 = 50.
        assertEquals(14f + l + 14f, fieldHeight(), 0.5f)

        // The card leaves 6 below the last line: 14 + 4L + 6.
        mount(text = "1\n2\n3\n4", isCard = true, width = 338.dp)
        assertEquals(14f + 4 * l + 6f, fieldHeight(), 0.5f)
    }

    // -------------------------------------------------------- Ctrl+Enter

    @Test
    fun ctrlEnterSendsOnceAndInsertsNoNewline() {
        var sends = 0
        mount(text = "hi", isCard = true, width = 338.dp, wire = {
            onSend = { _, _, _ -> sends++; SendResult.Kept }
        })
        input().performClick()
        compose.waitForIdle()
        input().assertIsFocused()
        input().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
        compose.waitForIdle()
        assertEquals("keyboardSend reached the handler once", 1, sends)
        assertEquals("no newline was inserted", "hi", controller.value.text)
    }

    @Test
    fun ctrlNumPadEnterSendsToo() {
        var sends = 0
        mount(text = "hi", isCard = true, width = 338.dp, wire = {
            onSend = { _, _, _ -> sends++; SendResult.Kept }
        })
        input().performClick()
        compose.waitForIdle()
        input().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.NumPadEnter) } }
        compose.waitForIdle()
        assertEquals(1, sends)
        assertEquals("hi", controller.value.text)
    }

    @Test
    fun anEmptyRunningComposerSendsAndStopsNothingOnCtrlEnter() {
        var sends = 0
        var stops = 0
        mount(text = "", isCard = true, width = 338.dp, wire = {
            running = true
            onSend = { _, _, _ -> sends++; SendResult.Kept }
            onStop = { stops++ }
        })
        input().performClick()
        compose.waitForIdle()
        input().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
        compose.waitForIdle()
        // 54's guard: no content means keyboardSend does nothing — it can
        // never trigger Stop either.
        assertEquals(0, sends)
        assertEquals(0, stops)
        assertEquals("", controller.value.text)
    }

    @Test
    fun plainEnterInsertsANewlineAndDoesNotSend() {
        var sends = 0
        mount(text = "hi", isCard = true, width = 338.dp, wire = {
            onSend = { _, _, _ -> sends++; SendResult.Kept }
        })
        input().performClick()
        compose.waitForIdle()
        input().performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        assertEquals(0, sends)
        assertEquals("the field's own newline", "hi\n", controller.value.text)
    }

    @Test
    fun ctrlShiftEnterIsNotConsumed() {
        var sends = 0
        mount(text = "hi", isCard = true, width = 338.dp, wire = {
            onSend = { _, _, _ -> sends++; SendResult.Kept }
        })
        input().performClick()
        compose.waitForIdle()
        // The ⌘↩ modifier match is exact: Ctrl+Shift+Enter falls through
        // to a plain newline.
        input().performKeyInput { withKeysDown(listOf(Key.CtrlLeft, Key.ShiftLeft)) { pressKey(Key.Enter) } }
        compose.waitForIdle()
        assertEquals(0, sends)
        assertEquals("hi\n", controller.value.text)
    }

    // ------------------------------------------------ one field, both modes

    @Test
    fun oneFieldSurvivesTheModeFlipAndKeepsFocus() {
        mount(text = "draft", isCard = false, width = 268.dp)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        input().performClick()
        compose.waitForIdle()
        input().assertIsFocused()

        compose.runOnUiThread { card = true; slotWidth = 338.dp }
        compose.waitForIdle()
        // Still exactly one editable text (never two fields, never a
        // keyed swap), and the focus survived the flip.
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        input().assertIsFocused()
        assertEquals("draft", controller.value.text)
    }

    // ------------------------------------------------------ compact height

    @Test
    @Config(sdk = [36], qualifiers = "w800dp-h400dp-mdpi")
    fun compactHeightCapsTheCardAtThreeLines() {
        mount(text = "1\n2\n3\n4", isCard = true, width = 338.dp)
        val l = lineDp()
        val expected = ComposerTextMetrics.targetHeight(isCard = true, compactHeight = true, lineHeight = l, contentHeight = l * 4)
        // 20 + 3L = 86 at mdpi (iOS 84.35).
        assertEquals(86f, expected, 0.5f)
        assertEquals(expected, fieldHeight(), 0.5f)
        // The content passed the cap — the field scrolls inside it.
        val content = pxToDp(probe.size.height.toFloat())
        assertTrue(ComposerTextMetrics.scrolls(isCard = true, compactHeight = true, lineHeight = l, contentHeight = content))
        assertTrue("the content is taller than the inner region", content > expected - 20f)
    }

    // ---------------------------------------------------- ink, caret, font

    @Test
    fun theInkPlaceholderAndCaretUseThePaletteAndTheFontIsGeist() {
        // The placeholder: tertiary.
        mount(text = "", isCard = false, width = 268.dp)
        assertTrue(countMarker(nodePixels(), Palette.Light.tertiary) >= 3)

        // The text ink: Palette.text.
        mount(text = "Ship it", isCard = false, width = 268.dp)
        assertTrue(countMarker(nodePixels(), Palette.Light.text) >= 3)

        // The caret: Palette.accent (tintColor), on the frozen clock's
        // first frame after focus. Mounted as the card — what a focused
        // composer is in the app: a resting capsule with text shows the
        // #593 preview over the field's text layer at alpha 0, and the
        // caret is drawn inside that layer, so a capsule never shows it.
        mount(text = "Ship it", isCard = true, width = 338.dp)
        compose.mainClock.autoAdvance = false
        input().performClick()
        compose.waitForIdle()
        input().assertIsFocused()
        // The caret is a composed modifier gated on the field's hasFocus
        // state: it needs one recomposition (a frame — waitForIdle runs
        // none on the frozen clock) to launch the blink, whose first step
        // snaps the caret visible (a draw-phase alpha write, redrawn on the
        // second frame). The 500 ms blink-off never comes (32 ms elapse).
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertTrue("accent caret pixels", countMarker(nodePixels(), Palette.Light.accent) >= 3)

        // The font: Geist Regular — the probe renders with the family the
        // composition resolved, and its line box is 1.3 × the size
        // (Roboto's is ≈ 1.17, about 19.3 dp).
        assertEquals(probeFamily, probe.layoutInput.style.fontFamily)
        assertEquals(21.45f, lineDp(), 1f)
    }

    // ------------------------------------------------------- 95's hooks

    @Test
    fun textHiddenHidesTheInkAndTheFieldStays() {
        mount(text = "Ship it", isCard = false, width = 268.dp)
        assertTrue(countMarker(nodePixels(), Palette.Light.text) >= 3)
        compose.runOnUiThread { hidden = true }
        compose.waitForIdle()
        // The handoff capture: the text layer is alpha 0, and the field
        // (its text, its focus target) all stay.
        assertEquals("no text ink", 0, countMarker(nodePixels(), Palette.Light.text))
        input().assertExists()
        assertEquals("Ship it", controller.value.text)
        compose.onNodeWithTag("composer-input-preview", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun onTextRectReportsTheSlotInsetByTheInsets() {
        var rect: Rect? = null
        mount(text = "1\n2\n3\n4", isCard = true, width = 338.dp, onTextRect = { rect = it })
        compose.waitForIdle()
        val r = rect ?: error("onTextRect never fired")
        val slot = input().pxBounds()
        val l = lineDp()
        // 14 top, 6 bottom (the card's insets), height max(20, h − 20).
        assertEquals(pxToDp(slot.top) + 14f, pxToDp(r.top), 0.5f)
        assertEquals(pxToDp(slot.left), pxToDp(r.left), 0.5f)
        assertEquals(pxToDp(slot.right), pxToDp(r.right), 0.5f)
        assertEquals("the text region is the slot minus the insets", 14f + 4 * l + 6f - 20f, pxToDp(r.height), 0.5f)
        assertEquals(pxToDp(slot.bottom) - 6f, pxToDp(r.bottom), 0.5f)
    }

    // ----------------------------------------------------- the focus hook

    @Test
    fun onFocusChangeFiresOnlyOnRealChanges() {
        mount(text = "", isCard = false, width = 268.dp)
        // onFocusChanged also fires once with false at attach: nothing.
        assertEquals(emptyList<Boolean>(), focusEvents)
        input().performClick()
        compose.waitForIdle()
        input().assertIsFocused()
        assertEquals(listOf(true), focusEvents)
        compose.onNodeWithTag("blur").performClick()
        compose.waitForIdle()
        assertEquals(listOf(true, false), focusEvents)
    }
}
