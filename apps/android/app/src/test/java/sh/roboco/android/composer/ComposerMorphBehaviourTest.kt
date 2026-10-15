package sh.roboco.android.composer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
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
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme

/**
 * The capsule ⇄ card morph's behaviour (android-ui-parity #56): focus and
 * blur sequences, the spring, the snaps, the interruption and tap-to-focus —
 * Robolectric + compose-ui-test at **mdpi** (`w360dp-h240dp-mdpi`), where
 * 1 dp = 1 px, with `mainClock.autoAdvance = false` and `advanceTimeBy`
 * stepping.
 *
 * The harness mounts the ticket's minimal fixture instead of ticket 55's
 * full surface, so nothing here depends on geometry: a 360 × 120 dp `Box`
 * tagged `composer-glass` with [Modifier.composerMorphHost], holding one
 * `BasicTextField(TextFieldState, scrollState = textScroll,
 * lineLimits = MultiLine(maxHeightInLines = 3))` with [Modifier.composerInput],
 * an enabled `clickable` 34 dp control with [Modifier.composerControl], a
 * disabled one, and — z-below the glass, as a sibling — a marked box
 * **outside** the glass but overlapping it.
 *
 * The spring checkpoints (0.62 at 100 ms, 0.96 at 200 ms) are measured
 * after the *first animated frame*: the driver's `launch` costs one frame
 * of alignment, so each test advances one frame before clocking the
 * 100 ms. Robolectric shows no IME, so the IME-blur rule never fires here
 * (its table is `ComposerMorphRuleTest`'s).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h240dp-mdpi")
class ComposerMorphBehaviourTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var morph: ComposerMorphState
    private lateinit var field: TextFieldState
    private var imageCount by mutableStateOf(0)
    private var always by mutableStateOf(false)
    private val focusEvents = mutableListOf<Boolean>()
    private var hideSuggestionsCalls = 0
    private var clicks = 0
    private var disabledClicks = 0

    @Before
    fun freezeClock() {
        compose.mainClock.autoAdvance = false
    }

    @After
    fun resetMotion() {
        ReduceMotion.resetForTest()
    }

    // -------------------------------------------------------------- harness

    /**
     * The ticket's fixture. Tap targets (px = dp at mdpi), glass-local:
     * field 0…56 · enabled control 10…44 × 70…104 · disabled control
     * 100…134 × 70…104 · plain glass (180, 100) · the overlapping outside
     * control covers 240…274 × 60…94 of the glass.
     */
    @Composable
    private fun Fixture(
        alwaysCard: Boolean,
        hasImages: () -> Boolean,
        fieldState: TextFieldState,
        onFocusChange: (Boolean) -> Unit,
        hideSuggestions: () -> Unit,
        attach: (ComposerMorphState) -> Unit,
    ) {
        val state = rememberComposerMorphState(
            alwaysCard = alwaysCard,
            hasImages = hasImages,
            onFocusChange = onFocusChange,
            hideSuggestions = hideSuggestions,
        )
        attach(state)
        Box(Modifier.fillMaxSize()) {
            // z-below the glass: a marked control outside the glass whose
            // bounds overlap it (55 may place `+` as a sibling above the
            // glass, as in upstream PR #714).
            Box(
                Modifier
                    .absoluteOffset(x = 240.dp, y = 60.dp)
                    .size(34.dp)
                    .testTag("outside-control")
                    .composerControl(state),
            )
            Box(
                Modifier
                    .size(360.dp, 120.dp)
                    .testTag("composer-glass")
                    .composerMorphHost(state),
            ) {
                BasicTextField(
                    state = fieldState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("composer-field")
                        .composerInput(state),
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 3),
                    scrollState = state.textScroll,
                )
                Box(
                    Modifier
                        .offset(x = 10.dp, y = 70.dp)
                        .size(34.dp)
                        .testTag("composer-control")
                        .composerControl(state)
                        .clickable { clicks++ },
                )
                Box(
                    Modifier
                        .offset(x = 100.dp, y = 70.dp)
                        .size(34.dp)
                        .testTag("composer-control-disabled")
                        .composerControl(state)
                        .clickable(enabled = false) { disabledClicks++ },
                )
            }
        }
    }

    private fun mount(
        text: String = "Half-written thought",
        initialAlways: Boolean = false,
        initialImages: Int = 0,
    ) {
        imageCount = initialImages
        always = initialAlways
        field = TextFieldState(text)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Fixture(
                    alwaysCard = always,
                    hasImages = { imageCount > 0 },
                    fieldState = field,
                    onFocusChange = { focusEvents += it },
                    hideSuggestions = { hideSuggestionsCalls++ },
                ) { morph = it }
            }
        }
        compose.waitForIdle()
    }

    /**
     * A test-side snapshot write with the clock frozen. Robolectric runs the
     * test body on the main thread with the Looper paused, so Compose's
     * GlobalSnapshotManager never posts the apply notifications, and
     * `MainTestClock.advanceTimeBy*` only advances the coroutine scheduler —
     * without [Snapshot.sendApplyNotifications] neither the Recomposer nor
     * a `snapshotFlow` ever sees the write.
     */
    private fun writeFrozen(write: () -> Unit) {
        compose.runOnIdle {
            write()
            Snapshot.sendApplyNotifications()
        }
    }

    /** Steps the clock one frame (the animation's launch alignment), then [ms]. */
    private fun advancePastLaunch(ms: Long) {
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    // ------------------------------------------------ focus and the spring

    @Test
    fun focusingTheFieldMorphsToTheCardWithTheSpring() {
        mount()
        compose.runOnUiThread { morph.focus() }
        // isCard flips in the same snapshot as the focus event, before any
        // frame has run the spring (iOS writes isCard before the change
        // block, ComposerBar.swift:316).
        assertTrue("isCard flips in the same frame as the focus event", morph.isCard)
        assertEquals("the spring has not moved yet", 0f, morph.progress, 1e-4f)
        advancePastLaunch(100)
        val p100 = morph.progress
        assertTrue("progress at 100 ms: $p100", p100 in 0.54f..0.70f)
        compose.mainClock.advanceTimeBy(100)
        val p200 = morph.progress
        assertTrue("progress at 200 ms: $p200", p200 in 0.91f..1.01f)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals("settled at exactly 1 by 700 ms", 1f, morph.progress, 1e-4f)
        assertEquals("onFocusChange received [true]", listOf(true), focusEvents)
    }

    @Test
    fun blurWithADraftRestsAsTheCapsuleAndScrollsTheTextBackToTop() {
        val long = listOf(
            "Half-written thought",
            "that runs to a second line",
            "and a third",
            "and a fourth",
            "and a fifth",
            "and a sixth",
        ).joinToString("\n")
        mount(text = long)
        compose.runOnUiThread { morph.focus() }
        advancePastLaunch(700)
        assertTrue(morph.isFocused)
        // Scroll the multi-line text down, as a long draft would be.
        compose.runOnUiThread { runBlocking { morph.textScroll.scrollTo(40) } }
        assertTrue("the fixture field is scrolled", morph.textScroll.value > 0)

        compose.runOnUiThread { morph.blur() }
        compose.waitForIdle()
        assertTrue("hideSuggestions runs first", hideSuggestionsCalls == 1)
        assertFalse("isCard flips at once on blur", morph.isCard)
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertEquals("progress reaches 0 by 700 ms", 0f, morph.progress, 1e-4f)
        assertEquals("the text is untouched", long, field.text.toString())
        assertEquals("the text scrolled back to the top, instantly", 0, morph.textScroll.value)
        assertEquals("onFocusChange received [true, false] in order", listOf(true, false), focusEvents)
    }

    @Test
    fun blurringWithStagedImagesKeepsTheCard() {
        mount(initialImages = 1)
        compose.runOnUiThread { morph.focus() }
        advancePastLaunch(700)
        compose.runOnUiThread { morph.blur() }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertFalse(morph.isFocused)
        assertTrue("images keep the card after the blur", morph.isCard)
        assertEquals(1f, morph.progress, 1e-4f)
    }

    @Test
    fun addingAnImageWhileUnfocusedAnimatesToTheCardAndClearingAnimatesBack() {
        mount(text = "")
        assertEquals(0f, morph.progress, 1e-4f)
        // With the clock frozen, a write outside composition reaches the
        // driver's snapshotFlow only through the apply notifications (the
        // frame clock never sends them by itself — see [writeFrozen]).
        writeFrozen { imageCount = 1 }
        advancePastLaunch(100)
        val p100 = morph.progress
        assertTrue("the image arrival animates (100 ms: $p100)", p100 in 0.3f..0.9f)
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertEquals(1f, morph.progress, 1e-4f)
        writeFrozen { imageCount = 0 }
        compose.mainClock.advanceTimeBy(100)
        val back100 = morph.progress
        assertTrue("the clear animates back too (100 ms: $back100)", back100 in 0.1f..0.7f)
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertEquals(0f, morph.progress, 1e-4f)
    }

    // ------------------------------------------------------------ the snaps

    @Test
    fun alwaysCardOnFirstCompositionStartsAtTheCardWithNoEntranceAnimation() {
        mount(initialAlways = true)
        assertEquals("progress starts at the target", 1f, morph.progress, 1e-4f)
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertEquals("no entrance animation ran", 1f, morph.progress, 1e-4f)
    }

    @Test
    fun togglingAlwaysCardWhileSettledSnapsWithinOneFrame() {
        mount()
        assertEquals(0f, morph.progress, 1e-4f)
        writeFrozen { always = true }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertEquals("alwaysCard snaps to the card", 1f, morph.progress, 1e-4f)
        writeFrozen { always = false }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertEquals("alwaysCard snaps back", 0f, morph.progress, 1e-4f)
    }

    @Test
    fun focusAndBlurUnderAlwaysCardNeverChangeProgress() {
        mount(initialAlways = true)
        compose.runOnUiThread { morph.focus() }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertEquals(1f, morph.progress, 1e-4f)
        compose.runOnUiThread { morph.blur() }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertEquals("blur under alwaysCard never collapses the card", 1f, morph.progress, 1e-4f)
        assertEquals(listOf(true, false), focusEvents)
    }

    @Test
    fun reduceMotionSnapsBothWays() {
        ReduceMotion.debugOverride = true
        mount()
        compose.runOnUiThread { morph.focus() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertEquals("focus jumps to 1f within one frame", 1f, morph.progress, 1e-4f)
        compose.runOnUiThread { morph.blur() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertEquals("blur jumps to 0f within one frame", 0f, morph.progress, 1e-4f)
    }

    // ------------------------------------------------------ interruption

    @Test
    fun aBlurMidMorphReversesFromTheCurrentValueAndVelocity() {
        mount()
        compose.runOnUiThread { morph.focus() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(100)
        val mid = morph.progress
        assertTrue("mid-flight: $mid", mid in 0.5f..0.7f)
        compose.runOnUiThread { morph.blur() }
        val samples = mutableListOf(mid)
        repeat(50) {
            compose.mainClock.advanceTimeByFrame()
            samples += morph.progress
        }
        val maxJump = samples.zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue("no frame-to-frame jump exceeds 0.15 (max $maxJump)", maxJump <= 0.15f)
        assertTrue(
            "velocity carried: progress keeps rising after the blur (${samples.take(4)})",
            samples.take(4).max() > mid,
        )
        assertEquals("returned to 0 by 800 ms", 0f, samples.last(), 1e-3f)
    }

    @Test
    fun anAlwaysCardOnlyChangeLeavesARunningSpringAlone() {
        mount()
        compose.runOnUiThread { morph.focus() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(100)
        val before = morph.progress
        compose.runOnIdle { always = true }
        compose.mainClock.advanceTimeByFrame()
        val after = morph.progress
        assertTrue("the spring was neither snapped nor restarted ($before → $after)", after in 0.55f..0.85f)
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals(1f, morph.progress, 1e-4f)
    }

    // ------------------------------------------------------- tap-to-focus

    @Test
    fun aTapOnAPlainGlassAreaFocusesTheField() {
        mount()
        compose.onNodeWithTag("composer-glass").performTouchInput { click(Offset(180f, 100f)) }
        compose.waitForIdle()
        assertTrue("the tap focused the field", morph.isFocused)
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertEquals(1f, morph.progress, 1e-4f)
    }

    @Test
    fun tapsOnControlsDoNotFocusAndTheEnabledClickStillFires() {
        mount()
        // The enabled control: its own click fires, the glass never morphs.
        compose.onNodeWithTag("composer-glass").performTouchInput { click(Offset(27f, 87f)) }
        compose.waitForIdle()
        assertEquals("the enabled control's click fired once", 1, clicks)
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertFalse(morph.isFocused)
        assertEquals(0f, morph.progress, 1e-4f)

        // A disabled clickable with the marker blocks focus too (a disabled
        // UIButton blocks it; a disabled Compose clickable does not).
        compose.onNodeWithTag("composer-glass").performTouchInput { click(Offset(117f, 87f)) }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertFalse(morph.isFocused)
        assertEquals("the disabled control's click never fires", 0, disabledClicks)
        assertEquals(0f, morph.progress, 1e-4f)

        // A marked box outside the glass but overlapping it blocks focus.
        compose.onNodeWithTag("composer-glass").performTouchInput { click(Offset(250f, 75f)) }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertFalse(morph.isFocused)
        assertEquals(0f, morph.progress, 1e-4f)

        // A 40 dp drag across the glass (a chip-strip scroll) does not focus.
        compose.onNodeWithTag("composer-glass").performTouchInput {
            down(Offset(160f, 100f))
            moveBy(Offset(40f, 0f))
            up()
        }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertFalse("a drag beyond touch slop never focuses", morph.isFocused)
        assertEquals(0f, morph.progress, 1e-4f)
    }

    @Test
    fun theGlassHasNoClickActionInSemantics() {
        mount()
        // The glass is semantics-free (no clickable, no role): TalkBack
        // users focus the text field directly.
        compose.onNodeWithTag("composer-glass").assert(hasNoClickAction())
    }
}
