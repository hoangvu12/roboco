package sh.roboco.android.shell

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
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
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.sheet.SheetHeaderRow
import sh.roboco.android.design.sheet.SheetLeading
import sh.roboco.android.design.uiStyle

/**
 * The sheet host's behaviour (android-ui-parity #31) — Robolectric +
 * compose-ui-test at mdpi (1 dp = 1 px, the GlassSheetTest convention) in
 * a 411 × 891 window. The status-bar inset under Robolectric is whatever
 * the window reports (0); every assertion reads it live and checks
 * against `statusBarTop + 10 dp`, never a literal.
 *
 * Covers the ticket's acceptance list: the settled geometry (top at the
 * status bar + 10, full width, window bottom), the present spring
 * tracking progress at three points and settling within 650 ms, drag and
 * fling dismissal (25 % / 1200 dp/s, the upward-fling cancel), the
 * nested-scroll coupling (pull-down at the list top moves the sheet, a
 * list fling that reaches the top does not), predictive back commit and
 * cancel, a content-level back handler winning, the ✕, the
 * one-at-a-time / stacking / same-key rules, the stacked dismiss-all, the
 * instant dismiss, `hideForHandoff` (composed, inert, pass-through,
 * `complete()` once), and the tablet width.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-mdpi")
class SheetHostTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val state = SheetHostState()
    private val dismissed = mutableListOf<String>()
    private var behindClicks = 0
    private var contentBacks = 0
    private var sheetScope: PageSheetScope? = null
    private var statusBarTop = 0f

    @After
    fun tearDown() {
        ReduceMotion.resetForTest()
    }

    // ------------------------------------------------------------ fixtures

    /** The presenting content: any tap the scrim fails to consume lands here. */
    @Composable
    private fun Presenting() {
        Box(
            Modifier
                .fillMaxSize()
                .testTag("behind")
                .clickable { behindClicks++ },
        )
    }

    private fun setContent(presenting: @Composable () -> Unit = { Presenting() }) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                statusBarTop = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
                SheetHost(state) { presenting() }
            }
        }
        compose.waitForIdle()
    }

    private fun present(
        key: String = "test",
        content: @Composable PageSheetScope.() -> Unit = { PlainSheetContent() },
    ) {
        compose.runOnUiThread {
            state.present(key, SheetStyle.Page(grabber = true), onDismissed = { dismissed += key }, content = content)
        }
        compose.waitForIdle()
    }

    /**
     * One frame on the frozen clock. With `autoAdvance = false`,
     * `waitForIdle` sends the snapshot apply notifications but never pumps
     * a frame (ComposeIdlingResource only advances the clock when
     * auto-advancing), so a state write made outside composition is not
     * recomposed until the clock moves.
     */
    private fun frame() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /** Runs the clock past every spring (0.5 s) and settles the frame. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(1100)
        compose.waitForIdle()
    }

    private fun sheetTop(): Float = statusBarTop + 10f

    private fun height(): Float = 891f - sheetTop()

    private fun entry(): SheetEntry = state.entries.single()

    /** A slow vertical drag on the sheet's header band, [distance] px down. */
    private fun dragSheet(distance: Float, durationMillis: Long = 500) {
        compose.onNodeWithTag("page-sheet").performTouchInput {
            swipe(Offset(centerX, 40f), Offset(centerX, 40f + distance), durationMillis)
        }
    }

    @Composable
    private fun PageSheetScope.PlainSheetContent() {
        sheetScope = this
        val typeScale = LocalTypeScale.current
        val palette = RobocoTheme.palette
        Column(Modifier.fillMaxSize()) {
            BasicText("sheet", style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.text))
        }
    }

    /** A scrolling sheet (the nested-scroll try): a 200-row list. */
    @Composable
    private fun PageSheetScope.ListSheetContent() {
        sheetScope = this
        val typeScale = LocalTypeScale.current
        val palette = RobocoTheme.palette
        LazyColumn(Modifier.fillMaxSize().testTag("sheet-list")) {
            items(200) { line ->
                BasicText(
                    "line ${line + 1}",
                    Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
                    style = uiStyle(Face.Mono, typeScale.bodyScaled(13f), palette.text),
                )
            }
        }
    }

    /** The stacked New Project shape: no grabber, its own ✕. */
    @Composable
    private fun PageSheetScope.NewProjectContent() {
        sheetScope = this
        Column(Modifier.fillMaxSize()) {
            SheetHeaderRow(title = "New Project", leading = SheetLeading.Close, onLeading = {})
            Box(Modifier.fillMaxSize())
        }
    }

    @Composable
    private fun PageSheetScope.BackHandlingContent() {
        sheetScope = this
        BackHandler { contentBacks++ }
        PlainSheetContent()
    }

    @Composable
    private fun PageSheetScope.FieldSheetContent() {
        sheetScope = this
        var text by remember { mutableStateOf("") }
        val typeScale = LocalTypeScale.current
        val palette = RobocoTheme.palette
        Column(Modifier.fillMaxSize()) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .testTag("sheet-field"),
                textStyle = uiStyle(Face.Sans, typeScale.fixed(15f), palette.text),
            )
        }
    }

    // ------------------------------------------------------------ geometry

    @Test
    fun aSettledSheetSpansTheWidthAndReachesTheWindowBottom() {
        setContent()
        present("new-session") { NewSessionSheetPlaceholder(this) }
        settle()
        assertEquals(0f, entry().offset.value, 1f)
        val bounds = compose.onNodeWithTag("page-sheet").getBoundsInRoot()
        assertEquals(sheetTop(), bounds.top.value, 1f)
        assertEquals(411f, bounds.width.value, 1f)
        assertEquals(891f, bounds.bottom.value, 1f)
        compose.onNodeWithTag("sheet-scrim").assertExists()
        compose.onNodeWithTag("page-sheet:new-session").assertExists()
        // While the sheet is up, the presenting content has no semantics.
        compose.onNodeWithTag("behind").assertDoesNotExist()
    }

    @Test
    fun theSheetExposesADismissCustomAction() {
        setContent()
        present()
        settle()
        val actions = compose.onNodeWithTag("page-sheet").fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.CustomActions)
        assertEquals(listOf("Dismiss"), actions?.map { it.label })
    }

    @Test
    fun thePlaceholderCloseButtonDismissesAnimated() {
        setContent()
        present("new-session") { NewSessionSheetPlaceholder(this) }
        settle()
        compose.onNodeWithText("New Session").assertExists()
        compose.onNodeWithTag("sheet-close").performClick()
        settle()
        assertTrue(state.entries.isEmpty())
        assertEquals(listOf("new-session"), dismissed)
    }

    // ------------------------------------------------------- presentation

    @Test
    fun presentTracksTheRecessionProgressAndSettlesWithin650Ms() {
        setContent()
        present()
        compose.waitForIdle()
        val e = entry()
        val samples = mutableListOf<Float>()
        repeat(3) {
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
            samples += e.progress
        }
        assertTrue("the sheet must be mid-flight at 100 ms, was ${samples[0]}", samples[0] > 0f)
        assertTrue(samples[1] > samples[0])
        assertTrue(samples[2] > samples[1])
        assertTrue(samples[2] < 1f)
        // The recession follows p: scale = 1 − 0.08·p at every sample.
        samples.forEach { p ->
            assertEquals(1f - 0.08f * p, PageSheetMath.recededScale(p), 1e-6f)
        }
        // 650 ms of test clock: within 1 px of rest.
        compose.mainClock.advanceTimeBy(350)
        compose.waitForIdle()
        assertEquals(0f, e.offset.value, 1f)
    }

    @Test
    fun presentSettledForPreviewStartsAtRestWithoutAnAnimation() {
        setContent()
        compose.runOnUiThread {
            assertTrue(state.presentSettledForPreview("preview") { PlainSheetContent() })
        }
        compose.waitForIdle()
        frame() // compose + lay out the inserted entry (writes heightPx)
        assertEquals(0f, entry().offset.value, 0.01f)
        assertEquals(1f, entry().progress, 0.001f)
        assertTrue(state.isPresenting)
        assertTrue(state.isPresented)
        assertEquals(sheetTop(), compose.onNodeWithTag("page-sheet").getBoundsInRoot().top.value, 1f)
    }

    // ----------------------------------------------------- drag dismissal

    @Test
    fun aDragPastTheQuarterThresholdDismissesOnce() {
        setContent()
        present()
        settle()
        dragSheet(0.30f * height())
        settle()
        assertTrue(state.entries.isEmpty())
        assertEquals(listOf("test"), dismissed)
    }

    @Test
    fun aShortDragSpringsBackToRest() {
        setContent()
        present()
        settle()
        dragSheet(0.20f * height())
        settle()
        assertEquals(1, state.entries.size)
        assertEquals(0f, entry().offset.value, 1f)
        assertTrue(dismissed.isEmpty())
    }

    @Test
    fun aReleaseBelowTheThresholdWithADownwardFlingDismisses() {
        setContent()
        present()
        settle()
        // 200 px is below the 25 % threshold (220 px); the ~2000 px/s
        // release is above the 1200 dp/s fling line at mdpi.
        dragSheet(200f, durationMillis = 100)
        settle()
        assertTrue(state.entries.isEmpty())
        assertEquals(listOf("test"), dismissed)
    }

    @Test
    fun anUpwardFlingFromAPastThresholdDragReturnsToRest() {
        setContent()
        present()
        settle()
        // A slow drag to 0.44 H, then a fast upward flick to 0.30 H: the
        // release velocity points up past 1200 px/s, which cancels the
        // dismiss even though 0.30 H ≥ 25 %.
        compose.onNodeWithTag("page-sheet").performTouchInput {
            val slow = 0.44f * height()
            down(Offset(centerX, 40f))
            repeat(5) { step ->
                advanceEventTime(80)
                moveTo(Offset(centerX, 40f + slow / 5f * (step + 1)))
            }
            advanceEventTime(16)
            moveTo(Offset(centerX, 40f + 0.37f * height()))
            advanceEventTime(16)
            moveTo(Offset(centerX, 40f + 0.30f * height()))
            up()
        }
        settle()
        assertEquals(1, state.entries.size)
        assertEquals(0f, entry().offset.value, 1f)
        assertTrue(dismissed.isEmpty())
    }

    // ---------------------------------------------------- scroll coupling

    @Test
    fun pullingDownAtTheListTopMovesTheSheet() {
        setContent()
        present(content = { ListSheetContent() })
        settle()
        assertEquals(0f, entry().offset.value, 1f)
        compose.onNodeWithTag("sheet-list").performTouchInput {
            swipe(Offset(centerX, 200f), Offset(centerX, 264f), durationMillis = 500)
        }
        compose.waitForIdle()
        // Mid-release (the spring-back has not run: no clock time passed).
        val during = entry().offset.value
        assertTrue("the sheet must follow a pull-down at the list top, was $during", during > 0f)
        settle()
        assertEquals(0f, entry().offset.value, 1f)
        assertEquals(1, state.entries.size)
    }

    @Test
    fun aListFlingThatReachesTheTopLeavesTheSheetExactlyAtRest() {
        setContent()
        present(content = { ListSheetContent() })
        settle()
        // Scroll into the list: at offset 0 the list takes the deltas.
        repeat(2) {
            compose.onNodeWithTag("sheet-list").performTouchInput { swipeUp() }
            settle()
        }
        // A fast downward fling: the list consumes it while it has room,
        // then the fling reaches the list top; its leftover velocity must
        // not move or dismiss the sheet.
        compose.onNodeWithTag("sheet-list").performTouchInput {
            swipe(Offset(centerX, 200f), Offset(centerX, 500f), durationMillis = 100)
        }
        compose.mainClock.advanceTimeBy(3000) // the list's fling runs out
        compose.waitForIdle()
        assertEquals(0f, entry().offset.value, 0.01f)
        assertEquals(1, state.entries.size)
        assertTrue(dismissed.isEmpty())
    }

    // ------------------------------------------------------------- back

    @Test
    fun predictiveBackCommitDismisses() {
        setContent()
        present()
        settle()
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 100f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        assertEquals(0.125f * height(), entry().offset.value, 1f)
        compose.runOnUiThread {
            dispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 100f, 1f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        assertEquals(0.25f * height(), entry().offset.value, 1f)
        compose.runOnUiThread { dispatcher.onBackPressed() }
        settle()
        assertTrue(state.entries.isEmpty())
        assertEquals(listOf("test"), dismissed)
    }

    @Test
    fun predictiveBackCancelSpringsBackToRest() {
        setContent()
        present()
        settle()
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 100f, 0.6f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        settle()
        assertEquals(0f, entry().offset.value, 1f)
        assertEquals(1, state.entries.size)
        assertTrue(dismissed.isEmpty())
    }

    @Test
    fun aContentBackHandlerConsumesBackBeforeTheSheet() {
        setContent()
        present(content = { BackHandlingContent() })
        settle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, contentBacks)
        assertEquals(1, state.entries.size)
        assertTrue(dismissed.isEmpty())
    }

    // ---------------------------------------------------- stacking rules

    @Test
    fun scopePresentStacksANoGrabberSheetAndBackDismissesOnlyTheTop() {
        setContent()
        present("new-session") { sheetScope = this; NewSessionSheetPlaceholder(this) }
        settle()
        compose.runOnUiThread {
            assertTrue(
                sheetScope!!.present(
                    "new-project",
                    SheetStyle.Page(grabber = false),
                    onDismissed = { dismissed += "new-project" },
                ) { NewProjectContent() },
            )
        }
        compose.waitForIdle()
        settle()
        assertEquals(2, state.entries.size)
        assertEquals(SheetStyle.Page(grabber = false), state.entries[1].style)
        // A root present while a sheet is up is the UIKit no-op.
        compose.runOnUiThread {
            assertFalse(state.present("third") { PlainSheetContent() })
        }
        assertEquals(2, state.entries.size)
        // Back dismisses only the top entry.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
        assertEquals(1, state.entries.size)
        assertEquals("new-session", state.entries.single().key)
        assertEquals(listOf("new-project"), dismissed)
    }

    @Test
    fun stateDismissAnimatedTearsDownTheWholeStackAndFiresEachOnce() {
        setContent()
        present("new-session") { sheetScope = this; NewSessionSheetPlaceholder(this) }
        settle()
        compose.runOnUiThread {
            sheetScope!!.present(
                "new-project",
                SheetStyle.Page(grabber = false),
                onDismissed = { dismissed += "new-project" },
            ) { NewProjectContent() }
        }
        settle()
        compose.runOnUiThread { state.dismiss() } // animated by default
        settle()
        assertTrue(state.entries.isEmpty())
        assertEquals(2, dismissed.size)
        assertEquals(setOf("new-session", "new-project"), dismissed.toSet())
    }

    @Test
    fun scopePresentFromANonTopSheetOrWithADuplicateKeyIsIgnored() {
        setContent()
        present("a") { PlainSheetContent() }
        settle()
        val firstScope = sheetScope!!
        var topScope: PageSheetScope? = null
        compose.runOnUiThread {
            assertTrue(firstScope.present("b") { topScope = this; PlainSheetContent() })
        }
        compose.waitForIdle()
        settle()
        assertEquals(2, state.entries.size)
        // The covered sheet is not on top.
        compose.runOnUiThread {
            assertFalse(firstScope.present("c") { PlainSheetContent() })
        }
        // The top sheet may not re-use an existing key.
        compose.runOnUiThread {
            assertFalse(topScope!!.present("a") { PlainSheetContent() })
        }
        assertEquals(2, state.entries.size)
    }

    @Test
    fun dismissInstantRemovesEverythingAndRestoresThePresentingSemantics() {
        setContent()
        present()
        settle()
        compose.onNodeWithTag("behind").assertDoesNotExist()
        compose.runOnUiThread { state.dismiss(animated = false) }
        compose.waitForIdle()
        frame() // recompose: the presenting content's semantics come back
        assertTrue(state.entries.isEmpty())
        assertFalse(state.isPresenting)
        compose.onNodeWithTag("behind").assertExists()
        compose.onNodeWithTag("page-sheet").assertDoesNotExist()
        assertEquals(listOf("test"), dismissed)
    }

    // ---------------------------------------------------- hidden handoff

    @Test
    fun hideForHandoffKeepsTheEntriesComposedButInertUntilComplete() {
        setContent()
        present("new-session") { NewSessionSheetPlaceholder(this) }
        settle()
        var handoff: SheetHandoff? = null
        compose.runOnUiThread { handoff = state.hideForHandoff() }
        compose.waitForIdle()
        frame() // recompose/relayout for the hidden flag
        val e = entry()
        assertTrue(e.hiddenForHandoff)
        assertEquals(0f, e.progress, 0.001f)
        // Still composed: the tags exist.
        compose.onNodeWithTag("page-sheet").assertExists()
        compose.onNodeWithTag("sheet-close").assertExists()
        // Touches pass straight through to the content underneath — a
        // real injected tap, so it goes through hit testing (the parked
        // frame and the scrim are inert).
        compose.onNodeWithTag("behind").performTouchInput { click(center) }
        assertEquals(1, behindClicks)
        // Back does not dismiss the hidden sheet.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, state.entries.size)
        // complete() removes it and fires onDismissed once; twice is a no-op.
        compose.runOnUiThread { handoff!!.complete() }
        compose.waitForIdle()
        assertTrue(state.entries.isEmpty())
        assertEquals(listOf("new-session"), dismissed)
        compose.runOnUiThread { handoff!!.complete() }
        compose.waitForIdle()
        assertEquals(listOf("new-session"), dismissed)
    }

    @Test
    fun dismissInstantOnHiddenEntriesCompletesTheHandoff() {
        setContent()
        present()
        settle()
        var handoff: SheetHandoff? = null
        compose.runOnUiThread { handoff = state.hideForHandoff() }
        compose.waitForIdle()
        compose.runOnUiThread { state.dismiss(animated = false) }
        compose.waitForIdle()
        assertTrue(state.entries.isEmpty())
        assertEquals(listOf("test"), dismissed)
        // The later complete() is a no-op.
        compose.runOnUiThread { handoff!!.complete() }
        compose.waitForIdle()
        assertEquals(listOf("test"), dismissed)
    }

    @Test
    fun presentingWhileAHiddenSheetExistsIsIgnored() {
        setContent()
        present() { PlainSheetContent() }
        settle()
        compose.runOnUiThread { state.hideForHandoff() }
        compose.waitForIdle()
        compose.runOnUiThread {
            assertFalse(state.present("other") { PlainSheetContent() })
        }
        compose.runOnUiThread {
            assertFalse(sheetScope!!.present("other") { PlainSheetContent() })
        }
        assertEquals(1, state.entries.size)
    }

    // -------------------------------------------------------------- IME

    @Test
    fun focusingAFieldInsideTheSheetKeepsTheFrameInPlace() {
        setContent()
        present(content = { FieldSheetContent() })
        settle()
        val before = compose.onNodeWithTag("page-sheet").getBoundsInRoot()
        compose.onNodeWithTag("sheet-field").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("sheet-field").assertIsFocused()
        // The sheet frame itself never moves for the keyboard (the content
        // slot's imePadding lifts the field). Robolectric reports no IME
        // insets, so the real lift is verified on CI/manual with a device.
        assertEquals(before, compose.onNodeWithTag("page-sheet").getBoundsInRoot())
    }

    // ------------------------------------------------------------- tablet

    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun aTabletSheetIs704DpWideAndCentred() {
        setContent()
        present()
        settle()
        val bounds = compose.onNodeWithTag("page-sheet").getBoundsInRoot()
        assertEquals(704f, bounds.width.value, 1f)
        assertEquals((1280f - 704f) / 2f, bounds.left.value, 1f)
        assertEquals(sheetTop(), bounds.top.value, 1f)
        assertEquals(800f, bounds.bottom.value, 1f)
        // Regular-width sheets dim without the card stack: the scrim
        // stays, the recession does not (the golden pins the visuals).
        compose.onNodeWithTag("sheet-scrim").assertExists()
    }
}
