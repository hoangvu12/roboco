package sh.roboco.android.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Motion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle
import uniffi.roboco_core.FileMatch

/**
 * [MentionSuggestionsHost] / the layer / the card (android-ui-parity #86):
 * the ticket's `w400dp-h800dp-xxhdpi` worked phone — a host filling the
 * window, a clickable stub (600 dp, z below the layer) under the card area,
 * and the anchor `Box` at x = 12, 376 × 106 dp, bottom-aligned with a 12 dp
 * margin, holding a focused foundation `BasicTextField` bound to a
 * [ComposerController].
 *
 * Covers: the exact card geometry (left 12 / width 376 / bottom = anchor
 * top − 8 / height 245.3), the ≤ 5 rows and the 50.45 / 35.5 dp row
 * heights, the anchor follow-through, the 220 ms ease-out show and its
 * interrupted hide, the pick through the UI (mirroring
 * `iosui:SessionFlowTests.swift:265-276`), the tap pass-through and
 * swallow rules, blur, and the offline gate.
 *
 * Timing: geometry/behaviour tests run on the auto-advancing clock
 * (`advanceTimeBy` drains the 120 ms debounce and the 220 ms tween); the
 * animation tests freeze it and step frames — the frame clock quantizes to
 * 16 ms, so "at 110 ms" is read after the frame at 96–112 ms of tween time
 * and the window is widened accordingly.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h800dp-xxhdpi")
class MentionSuggestionsLayerTest {

    @get:Rule
    val compose = createComposeRule()

    /** Taps that reached the stub under the card area. */
    private var stubClicks = 0

    /** The anchor's height; grown by the follow-through test. */
    private val anchorHeight = mutableStateOf(106.dp)

    private var controller: ComposerController? = null
    private var state: MentionSuggestionsState? = null

    // -------------------------------------------------------------- harness

    /**
     * The ticket's stand-in harness (step 9): host + stub + anchor + field
     * + a "Blur" control, over `RobocoTheme`. The field is focused by
     * tapping it (the real IME path is 57's; focus is what the binding
     * gates edits on).
     */
    private fun mount(search: (suspend (String) -> List<FileMatch>) = { MentionFixture.files }) {
        stubClicks = 0
        anchorHeight.value = 106.dp
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                val focus = remember { mutableStateOf(false) }
                val focusManager = LocalFocusManager.current
                val c = rememberComposerController()
                remember(c) { c.mentionSearch = search }
                val s = rememberMentionSuggestionsState(c)
                controller = c
                state = s
                MentionSuggestionsHost(Modifier.fillMaxSize()) {
                    // Inside the host: the binding registers the state
                    // through the host's LocalMentionLayerSlot (outside it
                    // the slot is null and the card never composes).
                    MentionSuggestionsBinding(s, isFocused = { focus.value })
                    // z-below the layer: a stub under the card area.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(600.dp)
                            .testTag("stub")
                            .clickable { stubClicks++ },
                    )
                    // The anchor: the composer stand-in at the ticket's geometry.
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 12.dp, bottom = 12.dp)
                            .size(376.dp, anchorHeight.value)
                            .background(AnchorColor)
                            .testTag("anchor")
                            .mentionAnchor(s),
                    ) {
                        BasicTextField(
                            value = c.value,
                            onValueChange = { c.value = it },
                            textStyle = uiStyle(
                                Face.Sans,
                                LocalTypeScale.current.fixed(16.5f),
                                RobocoTheme.palette.text,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("field")
                                .onFocusChanged { focus.value = it.isFocused },
                        )
                    }
                    BasicText(
                        "Blur",
                        style = uiStyle(Face.SansMedium, LocalTypeScale.current.fixed(14f), RobocoTheme.palette.accent),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .testTag("blur")
                            .clickable { focusManager.clearFocus() },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** Mounts, focuses the field, types `Look at @lay`, settles the show. */
    private fun showCard(search: (suspend (String) -> List<FileMatch>) = { MentionFixture.files }) {
        mount(search)
        compose.onNodeWithTag("field").performClick()
        compose.waitForIdle()
        compose.runOnUiThread { controller!!.value = TextFieldValue("Look at @lay", TextRange(11)) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithTag("mention-card").assertExists()
    }

    private fun cardBounds(): DpRect = compose.onNodeWithTag("mention-card").getBoundsInRoot()

    private fun anchorTop(): Dp = compose.onNodeWithTag("anchor").getBoundsInRoot().top

    private fun assertDpEquals(what: String, expected: Dp, actual: Dp, tol: Dp = 0.5.dp) {
        assertTrue("$what: $actual vs $expected (±$tol)", abs(expected.value - actual.value) <= tol.value)
    }

    // ------------------------------------------------------------- geometry

    @Test
    fun theCardSitsAboveTheAnchorAtTheExactGeometry() {
        showCard()
        val card = cardBounds()
        assertDpEquals("card left = anchor x", 12.dp, card.left)
        assertDpEquals("card width = anchor width", 376.dp, card.width)
        assertDpEquals("card bottom = anchor top − 8", anchorTop() - 8.dp, card.bottom)
        // (DpRect.height is the ui.unit extension, which cannot be imported
        // next to foundation.layout.height — the same arithmetic as the rows.)
        assertDpEquals("card height (4 + 4 × 50.45 + 35.5 + 4)", 245.3.dp, card.bottom - card.top, 1.dp)
    }

    @Test
    fun fiveRowsExistAndTheRowHeightsMatch() {
        showCard()
        for (i in 0..4) compose.onNodeWithTag("mention-$i").assertExists()
        compose.onNodeWithTag("mention-5").assertDoesNotExist()
        val row0 = compose.onNodeWithTag("mention-0").getBoundsInRoot()
        val row4 = compose.onNodeWithTag("mention-4").getBoundsInRoot()
        assertDpEquals("row 0 height (name + dir)", 50.45.dp, row0.bottom - row0.top, 1.dp)
        assertDpEquals("row 4 height (no dir)", 35.5.dp, row4.bottom - row4.top, 1.dp)
        // The hit target is the whole row rectangle, insets included: it
        // starts at the card's left edge, not at the text's 14 dp inset.
        assertDpEquals("row 0 spans from the card's left", 12.dp, row0.left)
        // The accessibility contract: Role.Button, merged label = name,
        // then dir; the row without a dir has a one-element label.
        val config0 = compose.onNodeWithTag("mention-0").fetchSemanticsNode().config
        assertEquals(Role.Button, config0[SemanticsProperties.Role])
        assertEquals(
            listOf("mod.rs", "crates/ui/src/layout"),
            config0[SemanticsProperties.Text].map { it.text },
        )
        assertEquals(
            listOf("LAYOUT.md"),
            compose.onNodeWithTag("mention-4").fetchSemanticsNode().config[SemanticsProperties.Text]
                .map { it.text },
        )
    }

    @Test
    fun theCardFollowsTheAnchorWhenItGrows() {
        showCard()
        val before = cardBounds()
        compose.runOnUiThread { anchorHeight.value = 146.dp }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val after = cardBounds()
        assertDpEquals("the card bottom follows the anchor top up by 40", before.bottom - 40.dp, after.bottom)
    }

    // ------------------------------------------------------------- animation

    @Test
    fun theShowRunsOver220msWithEaseOutAndThe8dpSlide() {
        mount()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("field").performClick()
        compose.waitForIdle()
        compose.runOnUiThread { controller!!.value = TextFieldValue("Look at @lay", TextRange(11)) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(120) // the debounce
        compose.waitForIdle() // the result lands, the layer composes
        compose.mainClock.advanceTimeByFrame() // the tween starts
        // ~110 ms into the tween: the frame clock quantizes to 16 ms, so
        // the last frame's playtime is 96–112 ms.
        repeat(7) { compose.mainClock.advanceTimeByFrame() }
        val v = compose.runOnUiThread { state!!.progress.value }
        assertTrue("progress at ~110 ms: $v", v in 0.60f..0.72f)
        // translationY = (1 − v) × 8 dp: the drawn bottom sits below the
        // settled bottom by that amount (semantics bounds track layers).
        val top = anchorTop()
        val bottom = cardBounds().bottom
        assertDpEquals(
            "bottom = anchor top − 8 + (1 − v) × 8",
            top - 8.dp + ((1f - v) * Motion.MentionListOffsetDp).dp,
            bottom,
        )
        // 220 ms: exactly 1, no slide left.
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        val end = compose.runOnUiThread { state!!.progress.value }
        assertTrue("settled at 1 by 220 ms: $end", abs(end - 1f) < 1e-3f)
        assertDpEquals("settled bottom = anchor top − 8", anchorTop() - 8.dp, cardBounds().bottom)
    }

    @Test
    fun aHideStartingMidShowInterruptsFromTheCurrentValueAndRemovesTheLayer() {
        mount()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("field").performClick()
        compose.waitForIdle()
        compose.runOnUiThread { controller!!.value = TextFieldValue("Look at @lay", TextRange(11)) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(120)
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        repeat(7) { compose.mainClock.advanceTimeByFrame() } // mid-show, ~0.65
        val before = compose.runOnUiThread { state!!.progress.value }
        assertTrue("mid-show: $before", before in 0.5f..0.75f)
        // Typing a space kills the query: the hide starts here.
        compose.runOnUiThread { controller!!.value = TextFieldValue("Look at @lay ", TextRange(13)) }
        compose.waitForIdle()
        // Frozen clock: frame 1 runs the edit observer's hide and recomposes
        // the layer (the show still advances one frame), frame 2 is the
        // hide tween's t = 0 (the current value, ≥ before), frames 3–4 are
        // ~32 ms into the hide. Reading after a single frame would still
        // see the show's last step, above `before`.
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
        val early = compose.runOnUiThread { state!!.progress.value }
        assertTrue("animates down from the current value (no snap to 0 or 1): $early", early in 0.1f..before)
        compose.mainClock.advanceTimeBy(400) // 220 ms more
        compose.waitForIdle()
        val end = compose.runOnUiThread { state!!.progress.value }
        assertTrue("reaches 0 after 220 ms more: $end", end < 1e-3f)
        assertTrue("the layer has no nodes once hidden", compose.onAllNodesWithTag("mention-card").fetchSemanticsNodes().isEmpty())
    }

    // ------------------------------------------------------- pick (the UI test)

    @Test
    fun pickingARowInsertsTheTokenAndKeepsTheFieldFocused() {
        mount(search = { listOf(FileMatch("crates/ui/src/layout/mod.rs", false)) })
        compose.onNodeWithTag("field").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("field").performTextInput("Look at @lay")
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithTag("mention-0").assertExists()
        compose.onNodeWithTag("mention-0").performClick()
        compose.waitForIdle()
        val value = compose.runOnUiThread { controller!!.value }
        assertEquals("Look at @mod.rs ", value.text)
        assertEquals(TextRange(16), value.selection)
        compose.onNodeWithTag("field").assertIsFocused()
        // The card fades away.
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag("mention-card").fetchSemanticsNodes().isEmpty())
    }

    // ------------------------------------------------------- hit testing

    @Test
    fun tapsFallThroughWhileHidingAndAreSwallowedWhileShown() {
        mount()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("field").performClick()
        compose.waitForIdle()
        compose.runOnUiThread { controller!!.value = TextFieldValue("Look at @lay", TextRange(11)) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(120)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400) // fully shown
        compose.waitForIdle()
        val card = cardBounds()
        val centreX = card.left + (card.right - card.left) / 2f
        val centreY = card.top + (card.bottom - card.top) / 2f

        // A tap in the card's 4 dp top padding while shown is swallowed:
        // the stub does not fire, and no row does either.
        compose.onRoot().performTouchInput {
            click(Offset(centreX.toPx(), (card.top + 2.dp).toPx()))
        }
        compose.waitForIdle()
        assertEquals("the padding tap is swallowed", 0, stubClicks)
        assertEquals("no row fired", "Look at @lay", compose.runOnUiThread { controller!!.value.text })

        // A tap at the card's centre while shown does not reach the stub
        // either: it lands on a row, which picks (and hides the card).
        compose.onRoot().performTouchInput { click(Offset(centreX.toPx(), centreY.toPx())) }
        compose.waitForIdle()
        assertEquals(0, stubClicks)
        assertNotEquals("the row picked", "Look at @lay", compose.runOnUiThread { controller!!.value.text })

        // During the fade-out: no pointer-input anywhere in the card, so
        // the same tap falls through to the stub.
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(110)
        compose.onRoot().performTouchInput { click(Offset(centreX.toPx(), centreY.toPx())) }
        compose.waitForIdle()
        assertEquals("the stub receives the tap during the fade-out", 1, stubClicks)

        // And after the fade completes.
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onRoot().performTouchInput { click(Offset(centreX.toPx(), centreY.toPx())) }
        compose.waitForIdle()
        assertEquals("the stub receives the tap after the fade", 2, stubClicks)
    }

    // ------------------------------------------------------- blur and offline

    @Test
    fun clearingFocusFadesTheCardOutAndTypingAgainShowsIt() {
        showCard()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("blur").performClick()
        // Frozen clock: waitForIdle delivers the focus write to the blur
        // observer, one frame runs it (onBlur → the hide write, made inside
        // a coroutine), and the second waitForIdle sends that write's apply
        // notification — no animation awaits frames here, so the frame
        // clock alone would never deliver it and the card would never fade.
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle() // the hide starts on the next frames
        compose.mainClock.advanceTimeBy(110) // mid-fade
        val mid = compose.runOnUiThread { state!!.progress.value }
        assertTrue("fading over 220 ms, not gone at once: $mid", mid in 0.1f..0.9f)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertTrue(compose.runOnUiThread { state!!.progress.value } < 1e-3f)
        assertTrue(compose.onAllNodesWithTag("mention-card").fetchSemanticsNodes().isEmpty())
        // Focus again and type one more character: it shows again after
        // the 120 ms debounce (behaviour only: back on the auto clock).
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("field").performClick()
        compose.waitForIdle()
        compose.runOnUiThread { controller!!.value = TextFieldValue("Look at @layx", TextRange(12)) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithTag("mention-card").assertExists()
    }

    @Test
    fun goingOfflineHidesAVisibleCard() {
        showCard()
        compose.runOnUiThread { controller!!.canWrite = false }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag("mention-card").fetchSemanticsNodes().isEmpty())
    }

    private companion object {
        /** The anchor stand-in's fill (never a palette colour). */
        val AnchorColor = Color(0xFF2E6DB4)
    }
}
