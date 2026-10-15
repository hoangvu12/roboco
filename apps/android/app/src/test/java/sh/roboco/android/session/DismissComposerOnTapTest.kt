package sh.roboco.android.session

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
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
import sh.roboco.android.composer.ComposerMorphState
import sh.roboco.android.composer.composerInput
import sh.roboco.android.composer.rememberComposerMorphState
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.transcript.LocalTranscriptTapGate
import sh.roboco.android.transcript.TranscriptTapGate
import sh.roboco.android.transcript.transcriptControl

/**
 * The transcript's tap-to-dismiss observer (android-ui-parity #81):
 * Robolectric + compose-ui-test at `w412dp-h900dp-xxhdpi` (1 dp = 3 px),
 * the frame frozen (`mainClock.autoAdvance = false`) so the gestures'
 * timestamps are the injected ones and 56's spring only moves when the
 * test advances the clock.
 *
 * The harness mounts the ticket's minimal fixture instead of 65's real
 * host, so nothing here needs the native core:
 *
 * - `t-transcript` — a full-size scrolling `Column` with
 *   [Modifier.dismissComposerOnTap] outermost of its chain, holding a
 *   `clickable` link (`t-link`), a 44 dp marked control (`t-control`,
 *   marker + `clickable`), a marker-only box (`t-control-disabled`) and
 *   plain text (`t-plain`), then enough filler to scroll;
 * - `t-stack` — a sibling **after** the transcript in a `Box`, so it is
 *   above it: an 88 dp strip over the transcript's bottom edge whose
 *   container carries 74's observe-only blocker (`pointerInput`, never
 *   consuming), two 40 dp children separated by an 8 dp gap, the lower
 *   one holding the field (`t-input`).
 *
 * The generic tests dismiss through
 * `onDismiss = { focusManager.clearFocus(); dismissals++ }` (#621's
 * shape, but only the composer's field is ever focused in the fixture);
 * the last two mount the real [ComposerMorphState] (ticket 56) with
 * [composerInput] on the field and dismiss through `blur()`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h900dp-xxhdpi")
class DismissComposerOnTapTest {

    @get:Rule
    val compose = createComposeRule()

    private val gate = TranscriptTapGate()
    private val focusEvents = mutableListOf<Boolean>()
    private var dismissals = 0
    private var linkTaps = 0
    private var controlTaps = 0
    private lateinit var scroll: ScrollState
    private lateinit var field: TextFieldState
    private lateinit var morph: ComposerMorphState

    @Before
    fun freezeClock() {
        compose.mainClock.autoAdvance = false
    }

    // -------------------------------------------------------------- harness

    private fun mountGeneric() {
        field = TextFieldState("Half-written thought")
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(LocalTranscriptTapGate provides gate) {
                    val focusManager = LocalFocusManager.current
                    TapDismissStage(
                        gate = gate,
                        onDismiss = {
                            focusManager.clearFocus()
                            dismissals++
                        },
                        field = field,
                    ) { scroll = it }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun mountMorph() {
        field = TextFieldState("Half-written thought")
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(LocalTranscriptTapGate provides gate) {
                    val m = rememberComposerMorphState(
                        alwaysCard = false,
                        hasImages = { false },
                        onFocusChange = { focusEvents += it },
                    )
                    morph = m
                    TapDismissStage(
                        gate = gate,
                        onDismiss = {
                            m.blur()
                            dismissals++
                        },
                        field = field,
                        morph = m,
                    ) { scroll = it }
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * The fixture: a scrolling transcript with the observer, and the
     * 74-style blocked strip over its bottom edge holding the field. The
     * strip is 40 + 8 + 40 = 88 dp (the ticket's "overlapping its bottom
     * 100 dp" realized exactly): children at 0…40 dp and 48…88 dp, the
     * 8 dp gap between them at 40…48 dp — which is also the strip's
     * centre, so a swipe injected at the centre starts in the gap.
     */
    @Composable
    private fun TapDismissStage(
        gate: TranscriptTapGate,
        onDismiss: () -> Unit,
        field: TextFieldState,
        morph: ComposerMorphState? = null,
        attachScroll: (ScrollState) -> Unit,
    ) {
        Box(Modifier.fillMaxSize()) {
            val state = rememberScrollState()
            attachScroll(state)
            Column(
                Modifier
                    .fillMaxSize()
                    // 81: outermost of the chain, before the scroll — the
                    // Initial-pass observer never consumes.
                    .dismissComposerOnTap(gate, onDismiss)
                    .testTag("t-transcript")
                    .verticalScroll(state),
            ) {
                BasicText(
                    "A painted link",
                    Modifier
                        .testTag("t-link")
                        .clickable { linkTaps++ }
                        .padding(12.dp),
                )
                Box(
                    Modifier
                        .testTag("t-control")
                        .size(44.dp)
                        .transcriptControl()
                        .clickable { controlTaps++ },
                )
                Box(
                    Modifier
                        .testTag("t-control-disabled")
                        .size(44.dp)
                        .transcriptControl(),
                )
                BasicText(
                    "Half-written thought",
                    Modifier
                        .testTag("t-plain")
                        .padding(12.dp),
                )
                repeat(40) { i -> BasicText("filler line $i", Modifier.padding(12.dp)) }
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(88.dp)
                    .testTag("t-stack")
                    // 74's observe-only blocker: hit-testable (so the strip's
                    // rect, gaps included, occludes the transcript behind it)
                    // but never consuming — its children keep their taps.
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
            ) {
                Box(Modifier.fillMaxWidth().height(40.dp))
                BasicTextField(
                    state = field,
                    modifier = Modifier
                        .offset(y = 48.dp)
                        .fillMaxWidth()
                        .height(40.dp)
                        .testTag("t-input")
                        .then(if (morph != null) Modifier.composerInput(morph) else Modifier),
                )
            }
        }
    }

    /** Steps the clock one frame (the animation's launch alignment), then [ms]. */
    private fun advancePastLaunch(ms: Long) {
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    // ------------------------------------------------- the iOS gesture rule

    @Test
    fun aClickOnPlainSpaceUnfocusesTheInputAndKeepsTheDraft() {
        mountGeneric()
        compose.onNodeWithTag("t-input").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("t-input").assertIsFocused()

        compose.onNodeWithTag("t-plain").performTouchInput { click(center) }
        compose.waitForIdle()

        compose.onNodeWithTag("t-input").assertIsNotFocused()
        compose.onNodeWithTag("t-input").assertTextContains("Half-written thought")
        assertEquals("the observer dismissed once", 1, dismissals)
    }

    @Test
    fun aClickOnALinkOpensItAndUnfocusesTheInput() {
        mountGeneric()
        compose.onNodeWithTag("t-input").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("t-input").assertIsFocused()

        // The link's `clickable` consumes the up — in the Main pass, after
        // the observer has already seen it unconsumed on the Initial pass,
        // so both fire (upstream #585).
        compose.onNodeWithTag("t-link").performTouchInput { click(center) }
        compose.waitForIdle()

        assertEquals("the link opened too", 1, linkTaps)
        compose.onNodeWithTag("t-input").assertIsNotFocused()
        assertEquals(1, dismissals)
    }

    @Test
    fun aClickOnAMarkedControlKeepsTheComposerFocused() {
        mountGeneric()
        compose.onNodeWithTag("t-input").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("t-input").assertIsFocused()

        // The marker claims the down: the control's own click fires and
        // the composer stays focused.
        compose.onNodeWithTag("t-control").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals("the control's click fired", 1, controlTaps)
        compose.onNodeWithTag("t-input").assertIsFocused()
        assertEquals("no dismiss on a control", 0, dismissals)

        // A marker with no `clickable` behind it blocks the dismiss the
        // same way (the node's rect is hit-testable by itself).
        compose.onNodeWithTag("t-control-disabled").performTouchInput { click(center) }
        compose.waitForIdle()
        compose.onNodeWithTag("t-input").assertIsFocused()
        assertEquals(0, dismissals)
    }

    @Test
    fun aSwipeAcrossTheTranscriptScrollsItAndNeverDismisses() {
        mountGeneric()
        // Starts at the centre: the default swipeUp starts at the bottom
        // edge, inside the 88 dp stack strip, whose blocker occludes the
        // transcript (aTapInTheStackGap… pins exactly that).
        compose.onNodeWithTag("t-transcript").performTouchInput { swipeUp(startY = centerY, endY = top) }
        compose.waitForIdle()
        assertTrue("the transcript scrolled", scroll.value > 0)
        assertEquals("a drag never dismisses", 0, dismissals)
    }

    @Test
    fun aTapThatMovesHalfTheTouchSlopStillDismisses() {
        mountGeneric()
        compose.onNodeWithTag("t-transcript").performTouchInput {
            down(center)
            moveBy(Offset(0f, viewConfiguration.touchSlop * 0.5f))
            up()
        }
        compose.waitForIdle()
        assertEquals(1, dismissals)
    }

    @Test
    fun aTapThatMovedPastTheSlopNeverDismissesEvenBackAtTheStart() {
        mountGeneric()
        compose.onNodeWithTag("t-transcript").performTouchInput {
            val start = center
            down(start)
            moveBy(Offset(0f, viewConfiguration.touchSlop * 1.5f))
            moveTo(start)
            up()
        }
        compose.waitForIdle()
        assertEquals(0, dismissals)
    }

    @Test
    fun aPressLongerThanTheLongPressTimeoutNeverDismisses() {
        mountGeneric()
        compose.onNodeWithTag("t-transcript").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 50)
            up()
        }
        compose.waitForIdle()
        assertEquals(0, dismissals)
    }

    @Test
    fun aSecondPointerCancelsTheDismiss() {
        mountGeneric()
        compose.onNodeWithTag("t-transcript").performTouchInput {
            down(0, center)
            down(1, Offset(centerX - 30f, centerY - 30f))
            up(0)
            up(1)
        }
        compose.waitForIdle()
        assertEquals(0, dismissals)
    }

    @Test
    fun aCancelledGestureNeverDismisses() {
        mountGeneric()
        compose.onNodeWithTag("t-transcript").performTouchInput {
            down(center)
            cancel()
        }
        compose.waitForIdle()
        assertEquals(0, dismissals)
    }

    @Test
    fun aTapInTheStackGapNeverDismissesAndTheStackBlocksScrolling() {
        mountGeneric()
        // The 8 dp gap between the strip's children (40…48 dp of t-stack):
        // the blocker container occludes the transcript, so the tap never
        // reaches the observer.
        compose.onNodeWithTag("t-stack").performTouchInput {
            click(Offset(centerX, 44.dp.toPx()))
        }
        compose.waitForIdle()
        assertEquals(0, dismissals)

        // A swipe that starts inside the stack drags the blocker, not the
        // transcript: the transcript's scroll value is unchanged.
        val before = scroll.value
        compose.onNodeWithTag("t-stack").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals("the stack blocked the transcript's scroll", before, scroll.value)
    }

    @Test
    fun aTapOnPlainSpaceStillCallsOnDismissWithoutFocus() {
        mountGeneric()
        compose.onNodeWithTag("t-plain").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals("the observer fires even with nothing focused", 1, dismissals)
    }

    // ------------------------------------------- the real morph (ticket 56)

    @Test
    fun withTheRealMorphATapBlursToTheCapsule() {
        mountMorph()
        compose.runOnUiThread { morph.focus() }
        advancePastLaunch(700)
        assertTrue(morph.isFocused)
        assertTrue("the card is up", morph.isCard)
        assertEquals(1f, morph.progress, 1e-4f)

        compose.onNodeWithTag("t-plain").performTouchInput { click(center) }
        compose.waitForIdle()

        assertFalse("isCard flips at once, before the spring", morph.isCard)
        assertEquals(listOf(true, false), focusEvents)
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertEquals("progress reaches 0 within 700 ms", 0f, morph.progress, 1e-4f)
        // The draft is never touched.
        compose.onNodeWithTag("t-input").assertTextContains("Half-written thought")
    }

    @Test
    fun theRealMorphBlurIsANoOpWhenNotFocused() {
        mountMorph()
        compose.onNodeWithTag("t-plain").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals("onDismiss ran", 1, dismissals)
        assertEquals("blur() on an unfocused composer is a no-op", emptyList<Boolean>(), focusEvents)
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertFalse(morph.isCard)
        assertEquals("nothing animated", 0f, morph.progress, 1e-4f)
    }

    // ----------------------------------------------------------- semantics

    @Test
    fun theTranscriptHasNoClickActionInSemantics() {
        mountGeneric()
        // The observer is gesture-level: no click action on the transcript
        // (that would announce the whole transcript as a button).
        compose.onNodeWithTag("t-transcript")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }
}
