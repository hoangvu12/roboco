package sh.roboco.android.design

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import android.view.ViewConfiguration
import androidx.test.core.app.ApplicationProvider
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The iOS toggle switch (android-ui-parity #98): the layout geometry and
 * row placement, the at-rest pixels of both states in both appearances,
 * tap / drag / scroll / RTL interactions, the haptic moments, the
 * caller-driven change, reduced-motion snapping and the press lens — all
 * on the ticket's `w402dp` phone at xxhdpi (1 dp = 3 px), font scale 1.
 *
 * Pixel probes host the switch on a padded `elevated` box (the white card
 * of the acceptance list) and capture that box, so the points outside the
 * 63 × 28 node bounds — the lens overhang and the "no resting shadow"
 * check below the track — are visible; every probe adds the 8 dp padding.
 *
 * Timing tests freeze the clock (`autoAdvance = false`) and advance it by
 * hand, the `GroupedListTest` / `ScrollEdgeEffectTest` method.
 *
 * The runner is the repo's Robolectric convention
 * (`RobolectricTestRunner` + compose rule); the ticket's `AndroidJUnit4`
 * note maps onto it (the ext-junit runner is an androidTest-only
 * dependency here).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h420dp-xxhdpi")
class RobocoSwitchTest {

    @get:Rule
    val compose = createComposeRule()

    private val feedback = RecordingFeedback()

    /** The accepting caller's state, held by the test and written synchronously. */
    private fun stateOf(value: Boolean): MutableState<Boolean> = mutableStateOf(value)

    // ------------------------------------------------------------ helpers

    /**
     * The switch on an `elevated` card: a tagged host box with 8 dp of
     * padding (the lens probes read points outside the switch's own
     * bounds), inside the page background, with the feedback recorder and
     * the optional reduce-motion / RTL providers.
     */
    private fun hostSwitch(
        appearance: Appearance = Appearance.Light,
        checked: MutableState<Boolean>,
        onCheckedChange: (Boolean) -> Unit,
        reduce: Boolean = false,
        rtl: Boolean = false,
    ) {
        compose.setContent {
            RobocoTheme(appearance) {
                CompositionLocalProvider(
                    LocalFeedback provides feedback,
                    LocalReduceMotion provides reduce,
                    LocalLayoutDirection provides (if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr),
                ) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        Box(
                            Modifier
                                .testTag("host")
                                .background(RobocoTheme.palette.elevated)
                                .padding(8.dp),
                        ) {
                            RobocoSwitch(checked.value, onCheckedChange, testTag = "toggle-x")
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * Applies a state write made outside composition while the clock is
     * frozen: under Robolectric the apply notification is posted to the
     * paused main Looper and the recomposition waits for a frame, neither
     * of which `advanceTimeBy` provides on its own.
     */
    private fun settleFrozen() {
        compose.waitForIdle()
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /** The host box's pixel at switch-local dp coordinates (adds the 8 dp padding, × 3 px/dp). */
    private fun hostPixel(xDp: Float, yDp: Float): Color {
        val px: PixelMap = compose.onNodeWithTag("host").captureToImage().toPixelMap()
        return px[((8f + xDp) * 3f).toInt(), ((8f + yDp) * 3f).toInt()]
    }

    /** Per-channel colour assertion; [tolerance] is in 8-bit steps. */
    private fun assertColour(actual: Color, red: Int, green: Int, blue: Int, tolerance: Int, what: String) {
        val t = tolerance / 255f
        assertTrue(
            "$what: rgb(${(actual.red * 255f).toInt()}, ${(actual.green * 255f).toInt()}, ${(actual.blue * 255f).toInt()}), " +
                "expected ($red, $green, $blue) ± $tolerance",
            abs(actual.red - red / 255f) <= t &&
                abs(actual.green - green / 255f) <= t &&
                abs(actual.blue - blue / 255f) <= t,
        )
    }

    private fun assertDp(actual: Dp, expected: Dp, what: String, tolerance: Float = 1f) {
        assertTrue(
            "$what: $actual dp, expected $expected ± $tolerance dp",
            abs(actual.value - expected.value) <= tolerance,
        )
    }

    /** [DpRect] carries no width/height of its own in this Compose. */
    private fun widthOf(bounds: DpRect): Dp = bounds.right - bounds.left

    private fun heightOf(bounds: DpRect): Dp = bounds.bottom - bounds.top

    private class RecordingFeedback : Feedback {
        val haptics = mutableListOf<Haptic>()

        override fun haptic(haptic: Haptic) {
            haptics += haptic
        }

        override fun cue(cue: Cue) = Unit
    }

    // ------------------------------------------------------------ layout

    @Test
    fun layoutIsExactly63By28Dp() {
        hostSwitch(checked = stateOf(false), onCheckedChange = {})
        val bounds = compose.onNodeWithTag("toggle-x").getUnclippedBoundsInRoot()
        assertDp(widthOf(bounds), 63.dp, "switch width", 0.5f)
        assertDp(heightOf(bounds), 28.dp, "switch height", 0.5f)
    }

    @Test
    fun rowPlacementIsTrailing16AndCentredOnTheRow() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(LocalFeedback provides feedback) {
                    Column(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        GroupedRow(
                            title = "Group by Project",
                            subtitle = "Sessions outside your sections, under their project",
                            icon = RobocoIcons.SquareStack3dUp,
                            position = GroupedPosition.Single,
                            accessories = listOf(
                                GroupedAccessory.Custom {
                                    RobocoSwitch(false, {}, testTag = toggleTag("groupByProject"))
                                },
                            ),
                            testTag = "row",
                            onClick = null,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val row = compose.onNodeWithTag("row").getUnclippedBoundsInRoot()
        val switch = compose
            .onNodeWithTag(toggleTag("groupByProject"), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertDp(widthOf(switch), 63.dp, "switch width in the row", 0.5f)
        assertDp(heightOf(switch), 28.dp, "switch height in the row", 0.5f)
        // Card right edge 386 (402 − 16), the custom accessory's trailing
        // margin 16 → the switch ends at 370.
        assertDp(switch.right, 370.dp, "switch right edge", 1f)
        val rowCentre = (row.top.value + row.bottom.value) / 2f
        val switchCentre = (switch.top.value + switch.bottom.value) / 2f
        assertTrue(
            "switch centre $switchCentre should sit on the row centre $rowCentre (±1 dp)",
            abs(rowCentre - switchCentre) <= 1f,
        )
        // Inside the merged row, the unmerged lookup still finds the
        // switch's own semantics.
        compose.onNodeWithTag(toggleTag("groupByProject"), useUnmergedTree = true).assertIsOff()
    }

    // ------------------------------------------------- pixels at rest

    @Test
    fun onStatePixelsInLight() {
        hostSwitch(appearance = Appearance.Light, checked = stateOf(true), onCheckedChange = {})
        // Track: accent #5B43E8 at the far (OFF) end of the track.
        assertColour(hostPixel(8f, 14f), 91, 67, 232, 2, "ON track at (8, 14)")
        // Thumb: opaque white at the trailing end, centre (42.5, 14).
        assertColour(hostPixel(42.5f, 14f), 255, 255, 255, 2, "ON thumb centre")
        // 2 dp below the track's bottom edge: the card colour — no
        // resting shadow.
        assertColour(hostPixel(20.5f, 30f), 255, 255, 255, 2, "below the ON track")
    }

    @Test
    fun offStatePixelsInLight() {
        hostSwitch(appearance = Appearance.Light, checked = stateOf(false), onCheckedChange = {})
        // Track: the system grey #C6C6CA (0x787880 @ 0.42 on white).
        assertColour(hostPixel(55f, 14f), 198, 198, 202, 3, "OFF track at (55, 14)")
        assertColour(hostPixel(20.5f, 14f), 255, 255, 255, 2, "OFF thumb centre")
        assertColour(hostPixel(20.5f, 30f), 255, 255, 255, 2, "below the OFF track")
    }

    @Test
    fun onAndOffPixelsInDark() {
        // One setContent per test (a second throws "has already set
        // content"): the OFF half flips the same host's state.
        val checked = stateOf(true)
        hostSwitch(appearance = Appearance.Dark, checked = checked, onCheckedChange = {})
        // Track: accent dark #8B7CF6.
        assertColour(hostPixel(8f, 14f), 139, 124, 246, 2, "dark ON track at (8, 14)")
        assertColour(hostPixel(42.5f, 14f), 255, 255, 255, 2, "dark ON thumb centre")
        // 2 dp below the track: elevated #111113, not a shadow.
        assertColour(hostPixel(20.5f, 30f), 17, 17, 19, 2, "below the dark ON track")
        compose.runOnIdle { checked.value = false }
        compose.waitForIdle()
        // OFF track: #3C3C41 (the 0.42 grey over #111113).
        assertColour(hostPixel(55f, 14f), 60, 60, 65, 3, "dark OFF track at (55, 14)")
        assertColour(hostPixel(20.5f, 14f), 255, 255, 255, 2, "dark OFF thumb centre")
        assertColour(hostPixel(20.5f, 30f), 17, 17, 19, 2, "below the dark OFF track")
    }

    // ------------------------------------------------------- tap

    @Test
    fun aTapCommitsOnceWithAHapticAndTheThumbSlides() {
        compose.mainClock.autoAdvance = false
        val on = stateOf(false)
        val values = mutableListOf<Boolean>()
        hostSwitch(checked = on, onCheckedChange = { on.value = it; values += it })

        compose.onNodeWithTag("toggle-x").performClick()
        compose.waitForIdle()
        assertEquals(listOf(true), values)
        assertEquals(listOf(Haptic.ToggleOn), feedback.haptics)

        // 16 ms in: the 0.35 s spring has barely moved the thumb — its
        // centre is still the grey track, not white.
        compose.mainClock.advanceTimeBy(16)
        val early = hostPixel(42.5f, 14f)
        assertTrue(
            "16 ms after the tap the thumb centre should not be white yet, was $early",
            early.red < 253f / 255f,
        )

        // 500 ms later: the slide is settled — thumb white, track accent.
        compose.mainClock.advanceTimeBy(500)
        assertColour(hostPixel(42.5f, 14f), 255, 255, 255, 2, "thumb centre after the slide")
        assertColour(hostPixel(8f, 14f), 91, 67, 232, 2, "ON track after the slide")

        // A second tap turns it back off.
        compose.onNodeWithTag("toggle-x").performClick()
        compose.waitForIdle()
        assertEquals(listOf(true, false), values)
        assertEquals(listOf(Haptic.ToggleOn, Haptic.ToggleOff), feedback.haptics)
    }

    @Test
    fun aRefusingCallerKeepsTheThumbAtOff() {
        compose.mainClock.autoAdvance = false
        val values = mutableListOf<Boolean>()
        // The value is never accepted: checked stays false.
        hostSwitch(checked = stateOf(false), onCheckedChange = { values += it })

        compose.onNodeWithTag("toggle-x").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        assertEquals("the refusal is still reported once", listOf(true), values)
        assertEquals(listOf(Haptic.ToggleOn), feedback.haptics)
        assertColour(hostPixel(20.5f, 14f), 255, 255, 255, 2, "thumb stayed at OFF centre")
        assertColour(hostPixel(55f, 14f), 198, 198, 202, 3, "track stayed grey")

        // A drag all the way to the far end, released there: reported once,
        // and the thumb springs back to the refused side.
        compose.onNodeWithTag("toggle-x").performTouchInput {
            down(Offset(20.dp.toPx(), 14.dp.toPx()))
            repeat(3) { moveBy(Offset(10.dp.toPx(), 0f)) }
            up()
        }
        // The release's `dragging = false` is a state write from a
        // coroutine, outside composition: with the clock frozen it must be
        // applied and recomposed (one frame) before the spring-back effect
        // relaunches; otherwise the 500 ms below elapse with the thumb
        // still parked at the drag's end fraction.
        settleFrozen()
        compose.mainClock.advanceTimeBy(500)
        assertEquals(listOf(true, true), values)
        assertColour(hostPixel(20.5f, 14f), 255, 255, 255, 2, "thumb sprung back after the refused drag")
        assertColour(hostPixel(55f, 14f), 198, 198, 202, 3, "track grey again after the refused drag")
    }

    // ------------------------------------------------------- drag

    @Test
    fun aDragPastHalfTogglesOn() {
        val on = stateOf(false)
        val values = mutableListOf<Boolean>()
        hostSwitch(checked = on, onCheckedChange = { on.value = it; values += it })
        compose.onNodeWithTag("toggle-x").performTouchInput {
            down(Offset(20.dp.toPx(), 14.dp.toPx()))
            repeat(3) { moveBy(Offset(10.dp.toPx(), 0f)) }
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(true), values)
        assertEquals(listOf(Haptic.ToggleOn), feedback.haptics)
        assertColour(hostPixel(42.5f, 14f), 255, 255, 255, 2, "thumb at ON after the drag")
        assertColour(hostPixel(8f, 14f), 91, 67, 232, 2, "accent track after the drag")
    }

    @Test
    fun aDragThatStaysUnderHalfReportsNothing() {
        val on = stateOf(false)
        val values = mutableListOf<Boolean>()
        hostSwitch(checked = on, onCheckedChange = { on.value = it; values += it })
        // Past the touch slop by 4 dp in 2 steps, fraction 4/22 ≈ 0.18 <
        // 0.5 — released on the OFF side. The slop is read from the
        // platform, not assumed: a device's is 8 dp, Robolectric's
        // ShadowViewConfiguration reports 16 dp, and a fixed +12 dp never
        // crossed it there (the gesture became a tap and reported `true`).
        val slopPx = ViewConfiguration.get(ApplicationProvider.getApplicationContext()).scaledTouchSlop.toFloat()
        compose.onNodeWithTag("toggle-x").performTouchInput {
            val step = (slopPx + 4.dp.toPx()) / 2f
            down(Offset(20.dp.toPx(), 14.dp.toPx()))
            repeat(2) { moveBy(Offset(step, 0f)) }
            up()
        }
        compose.waitForIdle()
        assertEquals("an under-half drag must not report", emptyList<Boolean>(), values)
        assertEquals("an under-half drag must not haptic", emptyList<Haptic>(), feedback.haptics)
        assertColour(hostPixel(20.5f, 14f), 255, 255, 255, 2, "thumb back at OFF after the short drag")
    }

    @Test
    fun anRtlDragLeftTurnsTheSwitchOn() {
        val on = stateOf(false)
        val values = mutableListOf<Boolean>()
        hostSwitch(checked = on, onCheckedChange = { on.value = it; values += it }, rtl = true)
        // In RTL the OFF thumb rests at the right end (centre 42.5); a
        // leftward drag drives the fraction to 1.
        compose.onNodeWithTag("toggle-x").performTouchInput {
            down(Offset(42.5.dp.toPx(), 14.dp.toPx()))
            repeat(3) { moveBy(Offset(-10.dp.toPx(), 0f)) }
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(true), values)
        assertEquals(listOf(Haptic.ToggleOn), feedback.haptics)
        // ON in RTL: the thumb's centre is at x 20.5.
        assertColour(hostPixel(20.5f, 14f), 255, 255, 255, 2, "RTL ON thumb centre")
    }

    // ------------------------------------------------------- scroll

    @Test
    fun aVerticalSwipeScrollsTheParentAndCancelsTheSwitch() {
        val on = stateOf(false)
        val values = mutableListOf<Boolean>()
        val state = ScrollState(0)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(LocalFeedback provides feedback) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        // A 200 dp tall scrollable column with 1000 dp of
                        // content; the switch at the top.
                        Column(Modifier.fillMaxWidth().height(200.dp).verticalScroll(state)) {
                            RobocoSwitch(on.value, { on.value = it; values += it }, testTag = "toggle-x")
                            Spacer(Modifier.height(972.dp))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        // Straight up from the switch's centre: the column takes the
        // gesture (the switch's own swipeUp() only spans its 28 dp).
        compose.onNodeWithTag("toggle-x").performTouchInput {
            swipe(center, center - Offset(0f, 150.dp.toPx()), durationMillis = 300)
        }
        compose.waitForIdle()
        assertEquals("a scroll steal must not report", emptyList<Boolean>(), values)
        assertEquals("a scroll steal must not haptic", emptyList<Haptic>(), feedback.haptics)
        assertTrue("the column should have scrolled, state.value = ${state.value}", state.value > 0)
    }

    // --------------------------------------------- caller-driven change

    @Test
    fun aCallerDrivenChangeSlidesSilently() {
        compose.mainClock.autoAdvance = false
        val on = stateOf(false)
        val values = mutableListOf<Boolean>()
        hostSwitch(checked = on, onCheckedChange = { on.value = it; values += it })
        // The caller flips the value with no touch: the thumb animates,
        // but no haptic and no callback — `.valueChanged` is user-only.
        on.value = true
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        assertEquals(emptyList<Boolean>(), values)
        assertEquals(emptyList<Haptic>(), feedback.haptics)
        assertColour(hostPixel(42.5f, 14f), 255, 255, 255, 2, "thumb slid to the caller's ON")
        assertColour(hostPixel(8f, 14f), 91, 67, 232, 2, "accent track after the caller's change")
    }

    // -------------------------------------------------- reduced motion

    @Test
    fun reducedMotionSnapsWithinTwoFrames() {
        compose.mainClock.autoAdvance = false
        val on = stateOf(false)
        val values = mutableListOf<Boolean>()
        hostSwitch(checked = on, onCheckedChange = { on.value = it; values += it }, reduce = true)
        compose.onNodeWithTag("toggle-x").performClick()
        compose.waitForIdle()
        // At most two frames (the recomposition and the effect launch may
        // take one each): the thumb is white AND the track reads accent —
        // with the 0.35 s spring the track would still be mostly grey at
        // 32 ms, which is what distinguishes a snap.
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertColour(hostPixel(42.5f, 14f), 255, 255, 255, 2, "reduced-motion snapped thumb")
        assertColour(hostPixel(8f, 14f), 91, 67, 232, 2, "reduced-motion snapped accent fill")
        assertEquals(listOf(true), values)
    }

    @Test
    fun reducedMotionHasNoLift() {
        compose.mainClock.autoAdvance = false
        hostSwitch(checked = stateOf(false), onCheckedChange = {}, reduce = true)
        // The finger held down for 400 ms on an OFF switch: the thumb
        // centre stays exactly white — no scale, translucency or shadow.
        compose.onNodeWithTag("toggle-x").performTouchInput { down(center) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertColour(hostPixel(20.5f, 14f), 255, 255, 255, 0, "no lift under reduced motion")
        compose.onNodeWithTag("toggle-x").performTouchInput { up() }
        compose.waitForIdle()
    }

    // ------------------------------------------------------- the lens

    @Test
    fun pressingLiftsTheThumbIntoATranslucentLensInLight() {
        compose.mainClock.autoAdvance = false
        hostSwitch(checked = stateOf(false), onCheckedChange = {})
        compose.onNodeWithTag("toggle-x").performTouchInput { down(center) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        // The lens at alpha 0.35: the grey track shows through the white
        // fill, so the thumb centre is between #C6C6CA and white.
        val lens = hostPixel(20.5f, 14f)
        assertTrue(
            "the lifted thumb centre should sit between grey and white, was $lens",
            lens.red in (200f / 255f)..(253f / 255f) &&
                lens.green in (200f / 255f)..(253f / 255f) &&
                lens.blue in (202f / 255f)..(253f / 255f),
        )
        compose.onNodeWithTag("toggle-x").performTouchInput { up() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertColour(hostPixel(20.5f, 14f), 255, 255, 255, 2, "thumb back at rest after the release")
    }

    @Test
    fun theLensOverhangsTheTrackInDark() {
        compose.mainClock.autoAdvance = false
        hostSwitch(appearance = Appearance.Dark, checked = stateOf(false), onCheckedChange = {})
        compose.onNodeWithTag("toggle-x").performTouchInput { down(center) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        // (20.5, −1.5) dp: above the track's top edge, over the lens's
        // overhang (the lens reaches 2.8 dp past the track).
        val over = hostPixel(20.5f, -1.5f)
        assertTrue(
            "the lens overhang should be ≥20 lighter than #111113 per channel, was $over",
            over.red - 17f / 255f >= 20f / 255f &&
                over.green - 17f / 255f >= 20f / 255f &&
                over.blue - 19f / 255f >= 20f / 255f,
        )
        compose.onNodeWithTag("toggle-x").performTouchInput { up() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertColour(hostPixel(20.5f, -1.5f), 17, 17, 19, 2, "overhang back to elevated dark")
    }

    // ------------------------------------------------------- semantics

    @Test
    fun semanticsCarryTheSwitchRoleStateAndClickAction() {
        val on = stateOf(false)
        val values = mutableListOf<Boolean>()
        hostSwitch(checked = on, onCheckedChange = { on.value = it; values += it })
        val node = compose.onNodeWithTag("toggle-x")
        node.assertIsOff()
        assertEquals(Role.Switch, node.fetchSemanticsNode().config[SemanticsProperties.Role])

        // TalkBack's double-tap goes through the semantics OnClick, which
        // toggles through the same commit path (haptic + callback).
        node.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf(true), values)
        assertEquals(listOf(Haptic.ToggleOn), feedback.haptics)
        node.assertIsOn()

        node.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf(true, false), values)
        assertEquals(listOf(Haptic.ToggleOn, Haptic.ToggleOff), feedback.haptics)
        node.assertIsOff()
    }
}
