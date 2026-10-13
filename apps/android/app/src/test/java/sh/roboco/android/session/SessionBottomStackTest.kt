package sh.roboco.android.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import kotlin.math.abs
import kotlin.math.max
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Palette
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass

/**
 * [SessionBottomStack]'s layout, reflow and touch routing
 * (android-ui-parity #74): Robolectric + `createComposeRule` at
 * `w412dp-h915dp-xxhdpi` (1 dp = 3 px). Robolectric reports no insets, so
 * the ticket's 24 dp nav bottom is faked through the debug
 * `imeOverride` (the stack bottom reads `max(ime, nav)`, so a 24 dp
 * override is the same 24 dp bottom inset) and the safe sides are 0, as
 * the A–D worked cases assume. The pure geometry lives in
 * [BottomStackMetricsTest]; the goldens in
 * [SessionBottomStackScreenshotTest].
 *
 * The stand-in slots are plain clickable boxes at the worked heights
 * (pill 30, one-row queue 48 = 40 + 4 + 4, questions 220, composer 50),
 * and the transcript stand-in records every [SessionBottomInsets] it
 * receives — the same-frame contract of the IME ride and the per-frame
 * `covered == H − stackTop + 8` invariant are asserted against it.
 *
 * Every test follows the freeze discipline: `mainClock.autoAdvance =
 * false`, `Snapshot.sendApplyNotifications()` + one frame after each
 * state write, and the glass fallback tier (Robolectric has no haze
 * source anyway, `Glass.forceFallback` pins it).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class SessionBottomStackTest {

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

    // ------------------------------------------------------------ helpers

    /** State write + notification + one frame, the frozen-clock recipe. */
    private fun applyState() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
    }

    /** Composes the content and settles the first two frames (the glass flag flip included). */
    private fun host(content: @Composable () -> Unit) {
        compose.setContent {
            RobocoTheme(Appearance.Light) { content() }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
    }

    /** The stack top, read back from the fade's placement (fadeTop + 44). */
    private fun stackTopFromFade(): Float =
        compose.onNodeWithTag("bottom-fade").getBoundsInRoot().top.value + 44f

    // -------------------------------------------------------------- fixture

    /** What the stand-ins record: received insets, taps, slot scopes. */
    private class Recorder {
        val received = mutableListOf<SessionBottomInsets>()
        var transcriptTaps = 0
        var composerTaps = 0
        var pillTaps = 0
        var queueTaps = 0
        val pillScopes = mutableListOf<Triple<Boolean, Boolean, Boolean>>()
        val queueScopes = mutableListOf<Triple<Boolean, Boolean, Boolean>>()
        val questionScopes = mutableListOf<Triple<Boolean, Boolean, Boolean>>()
        val composerScopes = mutableListOf<Triple<Boolean, Boolean, Boolean>>()
    }

    /**
     * The container with plain stand-in slots: fixed-height clickable
     * boxes (found by their inner tags), a striped, tappable transcript
     * that records every insets value it receives, and a 40 dp jump box
     * carrying ticket 131's tag.
     */
    @Composable
    private fun TestStack(
        recorder: Recorder,
        model: BottomStackModel,
        ime: Dp? = 24.dp,
        alpha: Float = 1f,
        composerHeight: Dp = 50.dp,
        pillHeight: Dp = 30.dp,
        questionsHeight: Dp = 220.dp,
    ) {
        val state = rememberBottomStackState()
        // Written in composition, read in measure: a change invalidates
        // the measure and lands in the same frame's layout.
        state.imeOverride = ime
        val palette = RobocoTheme.palette
        SessionBottomStack(
            state = state,
            model = model,
            transcript = { insets ->
                recorder.received += insets
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(palette.background)
                        .pointerInput(Unit) { detectTapGestures { recorder.transcriptTaps++ } }
                        .testTag("transcript-stand-in"),
                ) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(40.dp))
                        Box(Modifier.fillMaxWidth().height(10.dp).background(palette.accent))
                        Box(Modifier.fillMaxWidth().height(10.dp).background(palette.text))
                    }
                }
            },
            pill = {
                recorder.pillScopes += Triple(shown, glassVisible, animateGlass)
                SlotStandIn(pillHeight, "pill-stand-in") { recorder.pillTaps++ }
            },
            queue = {
                recorder.queueScopes += Triple(shown, glassVisible, animateGlass)
                SlotStandIn((8 + 40 * model.queueCount).dp, "queue-stand-in") { recorder.queueTaps++ }
            },
            questions = {
                recorder.questionScopes += Triple(shown, glassVisible, animateGlass)
                SlotStandIn(questionsHeight, "questions-stand-in") { }
            },
            composer = {
                recorder.composerScopes += Triple(shown, glassVisible, animateGlass)
                SlotStandIn(composerHeight, "composer-stand-in") { recorder.composerTaps++ }
            },
            jump = {
                Box(
                    Modifier
                        .size(40.dp)
                        .background(palette.secondary)
                        .testTag("jump-to-latest"),
                )
            },
            modifier = Modifier.fillMaxSize(),
            stackAlpha = { alpha },
        )
    }

    /** One plain slot stand-in: a fixed-height clickable box. */
    @Composable
    private fun SlotStandIn(height: Dp, tag: String, onTap: () -> Unit) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .testTag(tag)
                .clickable(onClickLabel = tag, onClick = onTap),
        )
    }

    // ---------------------------------------------------------- geometry

    /** Case A: composer only — bounds (12, 833)–(400, 883). */
    @Test
    fun caseAComposerBounds() {
        val recorder = Recorder()
        var model by mutableStateOf(BottomStackModel())
        host { TestStack(recorder, model) }
        val composer = compose.onNodeWithTag("bottom-stack-composer").getBoundsInRoot()
        assertEquals(12f, composer.left.value, 0.5f)
        assertEquals(833f, composer.top.value, 0.5f)
        assertEquals(400f, composer.right.value, 0.5f)
        assertEquals(883f, composer.bottom.value, 0.5f)
    }

    /** Case B: pill + one-row queue + composer — tops 739 / 777 / 833. */
    @Test
    fun caseBSlotTops() {
        val recorder = Recorder()
        var model by mutableStateOf(BottomStackModel(bannerShown = true, queueCount = 1))
        host { TestStack(recorder, model) }
        assertEquals(739f, compose.onNodeWithTag("bottom-stack-pill").getBoundsInRoot().top.value, 0.5f)
        assertEquals(777f, compose.onNodeWithTag("bottom-stack-queue").getBoundsInRoot().top.value, 0.5f)
        assertEquals(833f, compose.onNodeWithTag("bottom-stack-composer").getBoundsInRoot().top.value, 0.5f)
    }

    /** Case A: the jump slot's bounds 356,781–396,821 (40 × 40 at the stack's top-right). */
    @Test
    fun caseAJumpBounds() {
        val recorder = Recorder()
        var model by mutableStateOf(BottomStackModel())
        host { TestStack(recorder, model) }
        val jump = compose.onNodeWithTag("jump-to-latest").getBoundsInRoot()
        assertEquals(356f, jump.left.value, 0.5f)
        assertEquals(781f, jump.top.value, 0.5f)
        assertEquals(396f, jump.right.value, 0.5f)
        assertEquals(821f, jump.bottom.value, 0.5f)
    }

    /** Case A: the fade spans x 0…412, y 789…915 (stackTop − 44 … bottom). */
    @Test
    fun caseAFadeSpansTheWindow() {
        val recorder = Recorder()
        var model by mutableStateOf(BottomStackModel())
        host { TestStack(recorder, model) }
        val fade = compose.onNodeWithTag("bottom-fade").getBoundsInRoot()
        assertEquals(0f, fade.left.value, 0.5f)
        assertEquals(412f, fade.right.value, 0.5f)
        assertEquals(789f, fade.top.value, 0.5f)
        assertEquals(915f, fade.bottom.value, 0.5f)
    }

    /** Case D: a 1280 × 800 window caps the stack at 768 and centres it (x 256). */
    @Test
    @Config(sdk = [36], qualifiers = "w1280dp-h800dp-xxhdpi")
    fun wideWindowCapsAndCentresTheStack() {
        val recorder = Recorder()
        var model by mutableStateOf(BottomStackModel())
        host { TestStack(recorder, model, ime = 48.dp) }
        val composer = compose.onNodeWithTag("bottom-stack-composer").getBoundsInRoot()
        assertEquals(256f, composer.left.value, 0.5f)
        assertEquals(1024f, composer.right.value, 0.5f)
        assertEquals(744f, composer.bottom.value, 0.5f)
    }

    // ----------------------------------------------------------- keyboard

    /**
     * The IME ride: `imeOverride = 300.dp` moves the composer bottom to
     * 607 and `covered` to 366 **in the same frame** — the transcript
     * receives the value in the frame the stack moved.
     */
    @Test
    fun imeRideMovesComposerAndInsetsInTheSameFrame() {
        val recorder = Recorder()
        var model by mutableStateOf(BottomStackModel())
        var ime by mutableStateOf<Dp?>(24.dp)
        host { TestStack(recorder, model, ime = ime) }
        assertEquals(883f, compose.onNodeWithTag("bottom-stack-composer").getBoundsInRoot().bottom.value, 0.5f)

        ime = 300.dp
        applyState()
        val composer = compose.onNodeWithTag("bottom-stack-composer").getBoundsInRoot()
        assertEquals("the composer rides the keyboard (b = 915 − 300 − 8)", 607f, composer.bottom.value, 0.5f)
        assertEquals(
            "the transcript received covered = 366 in the same frame",
            366f,
            recorder.received.last().covered.value,
            0.5f,
        )
        assertEquals("and stackTop = 557 with it", 557f, recorder.received.last().stackTop.value, 0.5f)
    }

    // ------------------------------------------------------------- reflow

    /**
     * A structural change (queue 0 → 1) springs the stack: frame 0
     * unchanged at 833, strictly between 833 and 777 at 100 ms, settled
     * within 0.5 dp at 600 ms, never overshooting 777 by more than 1 dp —
     * and the transcript's received `covered` always satisfies
     * `covered == 915 − stackTop + 8`.
     */
    @Test
    fun structuralQueueInsertSpringsTheStack() {
        val recorder = Recorder()
        var model by mutableStateOf(BottomStackModel())
        host { TestStack(recorder, model) }
        assertEquals(833f, stackTopFromFade(), 0.5f)

        model = BottomStackModel(queueCount = 1)
        applyState()
        assertEquals("frame 0 of the reflow: still 833", 833f, stackTopFromFade(), 0.5f)

        compose.mainClock.advanceTimeBy(100)
        val at100 = stackTopFromFade()
        assertTrue("at 100 ms strictly between 777 and 833: $at100", at100 in 777.5f..832.5f)

        for (frame in 0 until 30) {
            compose.mainClock.advanceTimeByFrame()
            val top = stackTopFromFade()
            assertTrue("never more than 1 dp past 777 (frame $frame): $top", top > 776f)
            val covered = recorder.received.last().covered.value
            assertEquals("covered == H − stackTop + 8 at top $top", 915f - top + 8f, covered, 0.6f)
        }

        compose.mainClock.advanceTimeBy(600)
        compose.mainClock.advanceTimeByFrame()
        assertEquals("settled within 0.5 dp of 777", 777f, stackTopFromFade(), 0.5f)
        // The jump anchor and the fade top moved with it: stackTop 777 →
        // jump top 725, fade top 733.
        val jump = compose.onNodeWithTag("jump-to-latest").getBoundsInRoot()
        assertEquals(725f, jump.top.value, 1.5f)
        assertEquals(733f, compose.onNodeWithTag("bottom-fade").getBoundsInRoot().top.value, 1.5f)
    }

    // --------------------------------------------------- non-structural

    /** Composer height 50 → 120 snaps the stack top 833 → 763 in the same frame. */
    @Test
    fun composerGrowthSnapsInTheSameFrame() {
        val recorder = Recorder()
        var model by mutableStateOf(BottomStackModel())
        var composerHeight by mutableStateOf(50.dp)
        host { TestStack(recorder, model, composerHeight = composerHeight) }
        assertEquals(833f, stackTopFromFade(), 0.5f)

        composerHeight = 120.dp
        applyState()
        assertEquals("50 → 120 snaps, no spring", 763f, stackTopFromFade(), 0.5f)
    }

    /**
     * Editing toggles are never structural: the pill row appears instantly
     * (top 795, stack top 795) and hides instantly again (833), both in
     * the same frame as the change — via `from(chrome, editing)`.
     */
    @Test
    fun editingTogglesShowAndHideThePillInstantly() {
        val recorder = Recorder()
        val chrome = SessionChrome()
        var model by mutableStateOf(BottomStackModel.from(chrome, editing = false))
        host { TestStack(recorder, model) }
        assertEquals(833f, stackTopFromFade(), 0.5f)

        model = BottomStackModel.from(chrome, editing = true)
        applyState()
        assertEquals("the pill row appears instantly at 795", 795f, compose.onNodeWithTag("bottom-stack-pill").getBoundsInRoot().top.value, 0.5f)

        model = BottomStackModel.from(chrome, editing = false)
        applyState()
        assertEquals("and hides instantly again", 833f, stackTopFromFade(), 0.5f)
    }

    // ------------------------------------------------- glass + first render

    /**
     * First render: `queueCount = 2` composes directly, so the queue lays
     * out at full extent on frame 0 (88 dp at 737) with
     * `animateGlass = false` in its scope; a later 0 → 1 change gives the
     * slot `glassVisible = true, animateGlass = true`.
     */
    @Test
    fun firstRenderLaysTheQueueOutAtFullExtentWithUnanimatedGlass() {
        val recorder = Recorder()
        var model by mutableStateOf(BottomStackModel(queueCount = 2))
        host { TestStack(recorder, model) }
        val queue = compose.onNodeWithTag("bottom-stack-queue").getBoundsInRoot()
        assertEquals(88f, queue.height.value, 0.5f)
        assertEquals(737f, queue.top.value, 0.5f)
        assertEquals("the first scope has animateGlass = false", Triple(true, true, false), recorder.queueScopes.first())

        model = BottomStackModel()
        applyState()
        model = BottomStackModel(queueCount = 1)
        applyState()
        assertEquals("a later appearance animates its glass in", Triple(true, true, true), recorder.queueScopes.last())
    }

    /**
     * Questions shown with `queueCount = 2`: the queue slot is
     * `shown = false` but `glassVisible = true` (it only collapses), the
     * composer is `shown = false`; both lose their semantics immediately
     * on the change and, after the spring, are not placed and not in the
     * semantics tree.
     */
    @Test
    fun questionsHideTheQueueAndTheComposerImmediatelyAndFully() {
        val recorder = Recorder()
        var model by mutableStateOf(BottomStackModel(queueCount = 2))
        host { TestStack(recorder, model) }

        model = BottomStackModel(questionsShown = true, queueCount = 2)
        applyState()
        assertEquals("the queue keeps its glass while it collapses", Triple(false, true, true), recorder.queueScopes.last())
        assertFalse("the composer is hidden", recorder.composerScopes.last().first)

        // Semantics cleared immediately (the inner stand-ins vanish at once).
        compose.onNodeWithTag("queue-stand-in").assertDoesNotExist()
        compose.onNodeWithTag("composer-stand-in").assertDoesNotExist()

        // After the spring: neither is placed. Both slots stay composed
        // (the container always composes all four), and an unplaced node
        // keeps its outer test tag in the semantics tree — so "not
        // displayed", not "does not exist".
        compose.mainClock.advanceTimeBy(700)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("bottom-stack-queue").assertIsNotDisplayed()
        compose.onNodeWithTag("bottom-stack-composer").assertIsNotDisplayed()
        // The questions slot is the one showing.
        compose.onNodeWithTag("bottom-stack-questions").assertExists()
    }

    // -------------------------------------------------------- tap routing

    /**
     * A click in the 8 dp gap between the queue and the composer never
     * reaches the transcript stand-in; a click 20 dp above the stack top
     * (inside the fade) does.
     */
    @Test
    fun theStackRectSwallowsTapsInItsGapsAndTheFadePassesThrough() {
        val recorder = Recorder()
        var model by mutableStateOf(BottomStackModel(bannerShown = true, queueCount = 1))
        host { TestStack(recorder, model) }

        // The gap between the queue (777..825) and the composer (833..883).
        compose.onRoot().performTouchInput { click(Offset(200.dp.toPx(), 829.dp.toPx())) }
        compose.waitForIdle()
        assertEquals("the gap tap never reaches the transcript", 0, recorder.transcriptTaps)

        // 20 dp above the stack top (739) — inside the fade.
        compose.onRoot().performTouchInput { click(Offset(200.dp.toPx(), 719.dp.toPx())) }
        compose.waitForIdle()
        assertEquals("the fade passes the tap through", 1, recorder.transcriptTaps)
    }

    /**
     * `stackAlpha = 0` (arrival and pop): a click on the composer
     * stand-in does not fire, the slot is gone from semantics, and the
     * transcript stand-in stays fully drawn — the alpha applies to the
     * slots' layers only.
     */
    @Test
    fun zeroStackAlphaBlocksSlotInputButNeverFadesTheTranscript() {
        val recorder = Recorder()
        var model by mutableStateOf(BottomStackModel())
        host { TestStack(recorder, model, alpha = 0f) }

        // The composer's centre (833 + 25).
        compose.onRoot().performTouchInput { click(Offset(200.dp.toPx(), 858.dp.toPx())) }
        compose.waitForIdle()
        assertEquals("the composer is not tappable at alpha 0", 0, recorder.composerTaps)
        compose.onNodeWithTag("composer-stand-in").assertDoesNotExist()
        compose.onNodeWithTag("bottom-stack-composer").assertExists()

        // The transcript stays fully drawn: the accent stripe at y 40..50
        // is exactly palette.accent, not faded.
        val px = compose.onNodeWithTag("transcript-stand-in").captureToImage().toPixelMap()
        val d = compose.density.density
        val stripe = px[(200f * d).toInt(), (45f * d).toInt()]
        val accent = Palette.of(false).accent
        assertTrue(
            "the stripe is drawn at full alpha: $stripe vs $accent",
            colorDistance(stripe, accent) < 0.1f,
        )
    }

    /** The max channel distance between two colours (pixel probes). */
    private fun colorDistance(a: Color, b: Color): Float =
        max(abs(a.red - b.red), max(abs(a.green - b.green), abs(a.blue - b.blue)))
}
