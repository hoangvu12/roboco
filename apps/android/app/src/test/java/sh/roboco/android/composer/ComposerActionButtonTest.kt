package sh.roboco.android.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
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
import sh.roboco.android.design.Cue
import sh.roboco.android.design.Feedback
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.NoFeedback
import sh.roboco.android.design.Palette
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuHost

/**
 * [ComposerActionButton] (android-ui-parity #58) — Robolectric +
 * compose-ui-test, the GlassMenuBehaviourTest / ComposerSurfaceTest
 * conventions: software rendering (`Glass.forceFallback`, so the menu
 * panels draw the fallback fill), a `w411dp-h891dp-mdpi` window (1 dp =
 * 1 px, so pixel probes read the palette directly), the clock frozen and
 * advanced per frame for the animation checks.
 *
 * Covers the ticket's acceptance criteria:
 *  - the 34 × 34 capsule in all five visual states, the exact light/dark
 *    fills and inks (pixel probes at the glyph-free spots of the circle),
 *    the 18 dp / 13.2 dp glyph boxes (`composer-send-glyph`, unmerged);
 *  - the labels, role, tag, `disabled()` and the merged single node;
 *  - the state morph: only a visual-action change springs the fill
 *    ([Motion.ActionButton]) and bounces the glyph (scale 0.85 → 1, read
 *    through the test-only [ActionButtonScaleKey]); enabled-only,
 *    steering and theme changes snap with no bounce; the first
 *    composition never animates; a mid-flight change continues from the
 *    current fill;
 *  - taps: `onPrimary` exactly once (gesture and a11y paths), nothing and
 *    no haptic when disabled or offline, the controller overload firing
 *    exactly one haptic (Stop before onStop, Confirm before onSend, the
 *    queue routing to Steer);
 *  - long presses: a slow tap in the send/stop states, the queue state
 *    opening the delivery menu with exactly one [Haptic.LongPress] and no
 *    tap, the menu rows sending their modes one frame after the close
 *    starts, the two-row menu without steer support;
 *  - the press look (0.75 layer alpha, enabled buttons only);
 *  - focus: tapping the button never steals focus from the composer's
 *    text field;
 *  - TalkBack: the queue state's custom `Show menu` long-click action.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-mdpi")
class ComposerActionButtonTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun forceFallbackTier() {
        Glass.forceFallback = true
    }

    @After
    fun restoreTier() {
        Glass.forceFallback = false
    }

    // ------------------------------------------------------------ helpers

    /** Records haptics into the shared event list, one per line. */
    private class RecordingFeedback(val events: MutableList<String>) : Feedback {
        override fun haptic(haptic: Haptic) {
            events += "haptic:$haptic"
        }

        override fun cue(cue: Cue) {
            events += "cue:$cue"
        }
    }

    /** Test-side handle on an object created inside the composition. */
    private class Ref<T> {
        var value: T? = null
    }

    private val sendDisabled = ActionState(ComposerAction.Send, enabled = false, steering = false, deliveryMenu = false)
    private val sendEnabled = ActionState(ComposerAction.Send, enabled = true, steering = false, deliveryMenu = false)
    private val queue = ActionState(ComposerAction.Queue, enabled = true, steering = false, deliveryMenu = true)
    private val queueSteering = ActionState(ComposerAction.Queue, enabled = true, steering = true, deliveryMenu = true)
    private val stop = ActionState(ComposerAction.Stop, enabled = true, steering = false, deliveryMenu = false)
    private val offlineRunning = ActionState(ComposerAction.Queue, enabled = false, steering = false, deliveryMenu = false)

    /**
     * One button, centred on the palette background inside a
     * [GlassMenuHost]. [stateVar] drives the state from the test (the
     * animation checks); [state] is used when it is null.
     */
    private fun place(
        state: ActionState,
        appearance: Appearance = Appearance.Light,
        feedback: Feedback = NoFeedback,
        canSteer: Boolean = false,
        onPrimary: () -> Unit = {},
        onSend: (DeliveryMode) -> Unit = {},
        stateVar: MutableState<ActionState>? = null,
    ) {
        val body: @Composable () -> Unit = {
            RobocoTheme(appearance) {
                CompositionLocalProvider(LocalFeedback provides feedback) {
                    GlassMenuHost(hazeState = rememberHazeState()) {
                        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                            Box(Modifier.align(Alignment.Center)) {
                                ComposerActionButton(
                                    state = stateVar?.value ?: state,
                                    onPrimary = onPrimary,
                                    onSend = onSend,
                                    canSteer = canSteer,
                                )
                            }
                        }
                    }
                }
            }
        }
        // A compose rule accepts one setContent per test ("has already set
        // content"), so later place() calls swap the body inside the first
        // one; key(generation) disposes the old subtree, so every place()
        // is still a fresh first composition (no carried-over animation).
        val holder = placed
        if (holder == null) {
            val h = mutableStateOf(0 to body)
            placed = h
            compose.setContent {
                val (generation, content) = h.value
                key(generation) { content() }
            }
        } else {
            compose.runOnUiThread { holder.value = (holder.value.first + 1) to body }
        }
        compose.waitForIdle()
    }

    /** The single setContent's body, swapped by every later [place]. */
    private var placed: MutableState<Pair<Int, @Composable () -> Unit>>? = null

    /**
     * Writes [value] with the clock frozen and lets it land. With
     * `autoAdvance = false`, `waitForIdle` only sends the snapshot apply
     * notifications (it pumps no frame — ComposeIdlingResource's
     * shouldPumpTime requires autoAdvance), so the button recomposes on
     * the next frame (frame 1: recomposition + the LaunchedEffect, whose
     * snapTo/animateTo start), and the animations' first frame (their
     * start values, the bounce's 0.85, re-read by the scale semantics)
     * lands on frame 2.
     */
    private fun <T> MutableState<T>.setFrozen(value: T) {
        compose.runOnUiThread { this.value = value }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /** The whole 34 × 34 button image as pixels (1 dp = 1 px at mdpi). */
    private fun pixels(): PixelMap =
        compose.onNodeWithTag("composer-send").captureToImage().toPixelMap()

    /** The test-only bounce scale of the merged `composer-send` node. */
    private fun scaleKey(): Float {
        val nodes = compose.onAllNodes(hasScaleKey).fetchSemanticsNodes()
        assertTrue("no ActionButtonScaleKey node in the tree", nodes.isNotEmpty())
        return nodes.first().config[ActionButtonScaleKey]
    }

    private val hasScaleKey = SemanticsMatcher("has ActionButtonScale") {
        it.config.contains(ActionButtonScaleKey)
    }

    private fun hasButtonRole(): SemanticsMatcher =
        SemanticsMatcher("has Button role") { it.config[SemanticsProperties.Role] == Role.Button }

    private val markerTolerance = 8f / 255f

    private fun isMarker(c: Color, marker: Color): Boolean =
        abs(c.red - marker.red) <= markerTolerance &&
            abs(c.green - marker.green) <= markerTolerance &&
            abs(c.blue - marker.blue) <= markerTolerance

    /** Ink pixels well inside the capsule (the parent background would otherwise match too). */
    private fun countInkInsideCircle(px: PixelMap, ink: Color): Int {
        var n = 0
        for (y in 0 until px.height) {
            for (x in 0 until px.width) {
                val dx = x - 17f
                val dy = y - 17f
                if (dx * dx + dy * dy < 16f * 16f && isMarker(px[x, y], ink)) n++
            }
        }
        return n
    }

    /** ±2 8-bit levels per channel, as the ticket's pixel AC asks. */
    private fun assertChannelNear(what: String, actual: Color, expected: Color) {
        val a = actual.toArgbChannels()
        val e = expected.toArgbChannels()
        for (i in 0..2) {
            assertTrue(
                "$what: channel $i is ${a[i]}, expected ${e[i]} ± 2",
                abs(a[i] - e[i]) <= 2f,
            )
        }
    }

    private fun Color.toArgbChannels(): FloatArray =
        floatArrayOf(red * 255f, green * 255f, blue * 255f)

    private val lightPalette = Palette.Light
    private val darkPalette = Palette.Dark

    private fun disabledFill(p: Palette): Color = p.controlFill.compositeOver(p.background)

    // ------------------------------------------------------- geometry

    @Test
    fun theButtonMeasures34x34InEveryVisualState() {
        for ((_, state) in ActionButtonFixtureStates) {
            place(state)
            val node = compose.onNodeWithTag("composer-send").fetchSemanticsNode()
            assertEquals("width of $state", 34, node.size.width)
            assertEquals("height of $state", 34, node.size.height)
        }
    }

    @Test
    fun theFillIsACircleNotASquare() {
        place(sendEnabled)
        val px = pixels()
        assertChannelNear("fill at (3, 17)", px[3, 17], Palette.Light.accent)
        // The box's corner (1, 1) is outside the capsule: the parent's
        // background, not the fill.
        assertChannelNear("outside the capsule at (1, 1)", px[1, 1], Palette.Light.background)
    }

    @Test
    fun sendDrawsAn18dpGlyphBoxAndStopA132dpOne() {
        place(sendEnabled)
        compose.onNodeWithTag("composer-send-glyph", useUnmergedTree = true)
            .assertWidthIsEqualTo(18.dp)
        place(offlineRunning)
        compose.onNodeWithTag("composer-send-glyph", useUnmergedTree = true)
            .assertWidthIsEqualTo(18.dp)
        place(stop)
        compose.onNodeWithTag("composer-send-glyph", useUnmergedTree = true)
            .assertWidthIsEqualTo(13.2.dp)
    }

    // ------------------------------------------------------- colours

    @Test
    fun theFiveLooksDrawTheirExactPaletteColours() {
        val cases = listOf(
            sendDisabled to (disabledFill(lightPalette) to Palette.Light.tertiary),
            sendEnabled to (Palette.Light.accent to Color.White),
            queue to (Palette.Light.accent to Color.White),
            stop to (Palette.Light.text to Palette.Light.background),
            offlineRunning to (disabledFill(lightPalette) to Palette.Light.tertiary),
        )
        for ((state, colours) in cases) {
            place(state, appearance = Appearance.Light)
            val px = pixels()
            assertChannelNear("light fill of $state", px[3, 17], colours.first)
            assertChannelNear("light fill (right) of $state", px[30, 17], colours.first)
            assertTrue("light ink of $state", countInkInsideCircle(px, colours.second) >= 3)
        }
        val darkCases = listOf(
            sendDisabled to (disabledFill(darkPalette) to Palette.Dark.tertiary),
            sendEnabled to (Palette.Dark.accent to Color.White),
            queue to (Palette.Dark.accent to Color.White),
            stop to (Palette.Dark.text to Palette.Dark.background),
            offlineRunning to (disabledFill(darkPalette) to Palette.Dark.tertiary),
        )
        for ((state, colours) in darkCases) {
            place(state, appearance = Appearance.Dark)
            val px = pixels()
            assertChannelNear("dark fill of $state", px[3, 17], colours.first)
            assertTrue("dark ink of $state", countInkInsideCircle(px, colours.second) >= 3)
        }
    }

    // ------------------------------------------------------- a11y

    @Test
    fun everyStateExposesItsLabelRoleTagAndDisabledState() {
        place(sendEnabled)
        compose.onNodeWithTag("composer-send").assertExists()
        compose.onNodeWithContentDescription("Send message").apply {
            assert(hasButtonRole())
            assertIsEnabled()
        }
        place(queue)
        compose.onNodeWithContentDescription("Queue message").assert(hasButtonRole())
        place(queueSteering)
        compose.onNodeWithContentDescription("Steer").assertExists()
        place(stop)
        compose.onNodeWithContentDescription("Stop response").assert(hasButtonRole())
        place(sendDisabled)
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        // Offline while running: the label matches the drawn arrow, not 54's
        // queue label.
        place(offlineRunning)
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        compose.onNodeWithTag("composer-send").assertExists()
    }

    // ------------------------------------------------------- animation

    @Test
    fun actionChangesSpringTheFillAndBounceTheGlyphEachTime() {
        compose.mainClock.autoAdvance = false
        val state = mutableStateOf(sendEnabled)
        place(sendEnabled, stateVar = state)

        // The first composition never animates.
        assertEquals("frame 0 scale", 1f, scaleKey(), 1e-4f)
        assertChannelNear("frame 0 fill", pixels()[3, 17], Palette.Light.accent)

        // Send → Stop: the spring and the bounce start at once.
        state.setFrozen(stop)
        assertTrue("the bounce started (scale ${scaleKey()})", scaleKey() < 1f)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertTrue("still bouncing after two frames (scale ${scaleKey()})", scaleKey() < 1f)
        val mid = pixels()[3, 17]
        assertTrue(
            "the fill is strictly between accent and text 32 ms in (was $mid)",
            isStrictlyBetween(mid, Palette.Light.accent, Palette.Light.text),
        )
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        assertChannelNear("settled stop fill", pixels()[3, 17], Palette.Light.text)
        assertEquals("settled scale", 1f, scaleKey(), 1e-3f)

        // Stop → Queue: bounces again, springs text → accent.
        state.setFrozen(queue)
        assertTrue("the second bounce started (scale ${scaleKey()})", scaleKey() < 1f)
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        assertChannelNear("settled queue fill", pixels()[3, 17], Palette.Light.accent)
        assertEquals("settled scale again", 1f, scaleKey(), 1e-3f)

        // Queue → Send: bounces again (the colours do not change).
        state.setFrozen(sendEnabled)
        assertTrue("the third bounce started (scale ${scaleKey()})", scaleKey() < 1f)
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        assertEquals("settled scale the third time", 1f, scaleKey(), 1e-3f)
    }

    @Test
    fun sendToQueueBouncesWithoutAnyColourChange() {
        compose.mainClock.autoAdvance = false
        val state = mutableStateOf(sendEnabled)
        place(sendEnabled, stateVar = state)
        state.setFrozen(queue)
        assertTrue("the glyph bounces (scale ${scaleKey()})", scaleKey() < 1f)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        // Both states fill accent: no colour flight, only the bounce.
        assertChannelNear("the fill stayed accent", pixels()[3, 17], Palette.Light.accent)
        assertTrue("the bounce is still in flight (scale ${scaleKey()})", scaleKey() < 1f)
    }

    @Test
    fun aMidFlightChangeContinuesFromTheCurrentFill() {
        compose.mainClock.autoAdvance = false
        val state = mutableStateOf(sendEnabled)
        place(sendEnabled, stateVar = state)
        // Send → Stop, two frames in (about a quarter of the flight)…
        state.setFrozen(stop)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val inFlight = pixels()[3, 17]
        assertTrue("in flight before the interruption", isStrictlyBetween(inFlight, Palette.Light.accent, Palette.Light.text))
        // …then Stop → Queue without settling: the new spring continues
        // from the current fill (`beginFromCurrentState`), never resetting
        // to the old target first.
        state.setFrozen(queue)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertTrue(
            "the fill continued from the current value toward accent (was ${pixels()[3, 17]})",
            isStrictlyBetween(pixels()[3, 17], Palette.Light.accent, Palette.Light.text),
        )
    }

    @Test
    fun enabledOnlyAndSteeringChangesSnapWithNoBounce() {
        compose.mainClock.autoAdvance = false
        val state = mutableStateOf(sendDisabled)
        place(sendDisabled, stateVar = state)
        assertChannelNear("the grey fill", pixels()[3, 17], disabledFill(lightPalette))

        // Typing the first character: grey → accent snaps, no spring, no
        // bounce (the scale stays exactly 1).
        state.setFrozen(sendEnabled)
        assertEquals("no bounce on an enabled-only change", 1f, scaleKey(), 0f)
        assertChannelNear("the accent fill snapped", pixels()[3, 17], Palette.Light.accent)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals("still no bounce", 1f, scaleKey(), 0f)
        assertChannelNear("the accent fill stayed", pixels()[3, 17], Palette.Light.accent)

        // A steering change mid-queue: label only, the look is untouched.
        state.setFrozen(queue)
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        state.setFrozen(queueSteering)
        assertEquals("no bounce on a steering change", 1f, scaleKey(), 0f)
        compose.onNodeWithContentDescription("Steer").assertExists()
    }

    /** Every channel strictly between the two endpoints. */
    private fun isStrictlyBetween(c: Color, a: Color, b: Color): Boolean {
        val v = c.toArgbChannels()
        val lo = a.toArgbChannels().zip(b.toArgbChannels()) { x, y -> minOf(x, y) }
        val hi = a.toArgbChannels().zip(b.toArgbChannels()) { x, y -> maxOf(x, y) }
        return (0..2).all { i -> v[i] in (lo[i] + 1.5f)..(hi[i] - 1.5f) }
    }

    // ------------------------------------------------------- taps

    @Test
    fun aTapRunsOnPrimaryOnceWithNoHapticAndNoSend() {
        for (state in listOf(sendEnabled, queue, stop)) {
            val feedback = RecordingFeedback(mutableListOf())
            var primary = 0
            var sends = 0
            place(state, feedback = feedback, onPrimary = { primary++ }, onSend = { sends++ })
            compose.onNodeWithTag("composer-send").performTouchInput { click(center) }
            compose.waitForIdle()
            assertEquals("onPrimary once for $state", 1, primary)
            assertEquals("onSend never for $state", 0, sends)
            assertTrue("no haptic from the button for $state (${feedback.events})", feedback.events.isEmpty())
        }
    }

    @Test
    fun theAccessibilityClickRunsTheSameHandler() {
        var primary = 0
        place(sendEnabled, onPrimary = { primary++ }, onSend = {})
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitForIdle()
        assertEquals(1, primary)
    }

    @Test
    fun disabledAndOfflineTapsDoNothingAndStaySilent() {
        for (state in listOf(sendDisabled, offlineRunning)) {
            val events = mutableListOf<String>()
            val feedback = RecordingFeedback(events)
            var primary = 0
            var sends = 0
            place(state, feedback = feedback, onPrimary = { primary++ }, onSend = { sends++ })
            // Both the gesture and the a11y path: consumed, nothing runs.
            compose.onNodeWithTag("composer-send").performTouchInput { click(center) }
            compose.onNodeWithTag("composer-send").performClick()
            compose.waitForIdle()
            assertEquals("no callback for $state", 0, primary)
            assertEquals("no send for $state", 0, sends)
            assertTrue("no haptic for $state", events.isEmpty())
        }
    }

    @Test
    fun tapsThroughTheControllerFireExactlyOneHapticEach() {
        val events = mutableListOf<String>()
        val feedback = RecordingFeedback(events)
        val ref = Ref<ComposerController>()
        var mode: DeliveryMode? = null
        var body: String? = null
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(LocalFeedback provides feedback) {
                    GlassMenuHost(hazeState = rememberHazeState()) {
                        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                            Box(Modifier.align(Alignment.Center)) {
                                val controller = rememberComposerController()
                                controller.onSend = { text, _, m ->
                                    body = text
                                    mode = m
                                    events += "onSend"
                                    SendResult.Kept
                                }
                                controller.onStop = { events += "onStop" }
                                SideEffect { ref.value = controller }
                                ComposerActionButton(controller)
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val controller = requireNotNull(ref.value) { "the controller was composed" }

        // Stop: exactly one Stop haptic, before exactly one onStop.
        compose.runOnUiThread { controller.running = true }
        compose.waitForIdle()
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitForIdle()
        assertEquals(listOf("haptic:Stop", "onStop"), events)

        // Send with text: one Confirm, then the queue delivery.
        events.clear()
        compose.runOnUiThread {
            controller.running = false
            controller.text = "hello"
        }
        compose.waitForIdle()
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitForIdle()
        assertEquals(listOf("haptic:Confirm", "onSend"), events)
        assertEquals(DeliveryMode.Queue, mode)
        assertEquals("hello", body)

        // Queue with steer preferred and supported: routed to Steer.
        events.clear()
        mode = null
        compose.runOnUiThread {
            controller.running = true
            controller.canSteer = true
            controller.preferredDelivery = DeliveryMode.Steer
        }
        compose.waitForIdle()
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitForIdle()
        assertEquals(listOf("haptic:Confirm", "onSend"), events)
        assertEquals(DeliveryMode.Steer, mode)
    }

    // ------------------------------------------------------- long press

    @Test
    fun aLongPressInSendOrStopBehavesAsASlowTap() {
        var primary = 0
        place(sendEnabled, onPrimary = { primary++ }, onSend = {})
        compose.onNodeWithTag("composer-send").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals("the send release is a slow tap", 1, primary)

        primary = 0
        place(stop, onPrimary = { primary++ }, onSend = {})
        compose.onNodeWithTag("composer-send").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals("the stop release is a slow tap", 1, primary)
    }

    @Test
    fun aQueueLongPressOpensTheMenuWithOneHapticAndNoTap() {
        val events = mutableListOf<String>()
        val feedback = RecordingFeedback(events)
        var primary = 0
        var sends = 0
        place(queue, feedback = feedback, onPrimary = { primary++ }, onSend = { sends++ }, canSteer = true)
        compose.onNodeWithTag("composer-send").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600) // settle the menu's open spring
        compose.waitForIdle()
        compose.onNodeWithTag("glass-menu").assertExists()
        assertEquals("exactly one LongPress, the button's only haptic", listOf("haptic:LongPress"), events)
        assertEquals("the release after the long press is not a tap", 0, primary)
        assertEquals(0, sends)
    }

    @Test
    fun theDeliveryMenuShowsItsRowsAndPicksSendTheirModes() {
        val modes = mutableListOf<DeliveryMode>()
        place(queue, onPrimary = {}, onSend = { modes += it }, canSteer = true)
        compose.onNodeWithTag("composer-send").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithText("While the agent works").assertExists()
        compose.onNodeWithText("Queue for next turn").assertExists()
        compose.onNodeWithText("Steer now").assertExists()
        compose.onNodeWithText("Deliver into the running turn").assertExists()
        compose.onNodeWithText("Stop and send").assertExists()
        compose.onNodeWithTag("composer-delivery-queue").assertExists()
        compose.onNodeWithTag("composer-delivery-steer").assertExists()
        compose.onNodeWithTag("composer-delivery-interrupt").assertExists()

        // The destructive row's title ink is danger.
        val rowPx = compose.onNodeWithTag("composer-delivery-interrupt").captureToImage().toPixelMap()
        var danger = 0
        for (y in 0 until rowPx.height) {
            for (x in 0 until rowPx.width) {
                if (isMarker(rowPx[x, y], Palette.Light.danger)) danger++
            }
        }
        assertTrue("the Stop and send title is danger ink ($danger px)", danger >= 3)

        // A pick closes the menu and runs the action one frame after the
        // close starts (ticket 15's rule).
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("composer-delivery-queue").performClick()
        compose.waitForIdle()
        assertEquals("the action waits a frame", 0, modes.size)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(listOf(DeliveryMode.Queue), modes)
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
        compose.mainClock.autoAdvance = true

        // The Steer row sends Steer.
        compose.onNodeWithTag("composer-send").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithTag("composer-delivery-steer").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(listOf(DeliveryMode.Queue, DeliveryMode.Steer), modes)
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()

        // The destructive row sends Interrupt.
        compose.onNodeWithTag("composer-send").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithTag("composer-delivery-interrupt").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(listOf(DeliveryMode.Queue, DeliveryMode.Steer, DeliveryMode.Interrupt), modes)
    }

    @Test
    fun withoutSteerSupportTheMenuHasExactlyTwoRows() {
        place(queue, canSteer = false)
        compose.onNodeWithTag("composer-send").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithText("Queue for next turn").assertExists()
        compose.onNodeWithText("Stop and send").assertExists()
        compose.onNodeWithText("Steer now").assertDoesNotExist()
        compose.onNodeWithTag("composer-delivery-steer").assertDoesNotExist()
    }

    @Test
    fun anOfflineRunningEngineHasNoMenu() {
        val events = mutableListOf<String>()
        val feedback = RecordingFeedback(events)
        place(offlineRunning, feedback = feedback)
        compose.onNodeWithTag("composer-send").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
        assertTrue("no LongPress haptic without a menu ($events)", events.isEmpty())
    }

    // ------------------------------------------------------- press look

    @Test
    fun anEnabledPressDimsTheWholeButtonTo075AndReleaseRestores() {
        place(sendEnabled)
        compose.onNodeWithTag("composer-send").performTouchInput { down(center) }
        compose.waitForIdle()
        val pressed = Palette.Light.accent.copy(alpha = ActionButtonTokens.PressedAlpha)
            .compositeOver(Palette.Light.background)
        assertChannelNear("the pressed fill", pixels()[3, 17], pressed)
        compose.onNodeWithTag("composer-send").performTouchInput { up() }
        compose.waitForIdle()
        assertChannelNear("the restored fill", pixels()[3, 17], Palette.Light.accent)
    }

    @Test
    fun aDisabledPressShowsNoPressLook() {
        place(sendDisabled)
        compose.onNodeWithTag("composer-send").performTouchInput { down(center) }
        compose.waitForIdle()
        assertChannelNear("no dimming while disabled", pixels()[3, 17], disabledFill(lightPalette))
        compose.onNodeWithTag("composer-send").performTouchInput { up() }
        compose.waitForIdle()
    }

    // ------------------------------------------------------- focus

    @Test
    fun tappingTheButtonNeverStealsFocusFromTheComposerField() {
        val field = TextFieldState("draft")
        var primary = 0
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                GlassMenuHost(hazeState = rememberHazeState()) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        Column(Modifier.align(Alignment.Center)) {
                            BasicTextField(
                                state = field,
                                modifier = Modifier.testTag("field").width(200.dp),
                                textStyle = TextStyle(color = RobocoTheme.palette.text),
                            )
                            Spacer(Modifier.height(40.dp))
                            ComposerActionButton(sendEnabled, onPrimary = { primary++ }, onSend = {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("field").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("field").assertIsFocused()
        // The tap runs the action and leaves the field focused (the IME
        // stays up; iOS buttons never take first responder).
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("field").assertIsFocused()
        assertEquals(1, primary)
    }

    // ------------------------------------------------------- TalkBack

    @Test
    fun theQueueStateExposesAShowMenuLongClickAction() {
        place(queue, canSteer = true)
        val action = compose.onNodeWithTag("composer-send").fetchSemanticsNode()
            .config[SemanticsActions.OnLongClick]
        assertEquals("Show menu", action?.label)
        action?.action?.invoke()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithTag("glass-menu").assertExists()

        // No such action in the send state.
        place(sendEnabled)
        assertNull(
            // getOrNull: config[key] throws when the key is absent.
            compose.onNodeWithTag("composer-send").fetchSemanticsNode().config.getOrNull(SemanticsActions.OnLongClick),
        )
    }
}

