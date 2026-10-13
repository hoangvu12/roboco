package sh.roboco.android.sheets

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.sheet.GlassSheetState
import sh.roboco.android.design.sheet.SheetDetent
import sh.roboco.android.design.sheet.rememberGlassSheetState

/**
 * [SelectTextSheet] and [TextSheetOverlay]'s behaviour (ticket 127) —
 * Robolectric + compose-ui-test at mdpi (1 dp = 1 px, the GlassSheetTest
 * convention) in a 360 × 640 dp window, `mainClock.autoAdvance = false`
 * where timing matters (the present spring is 0.5 s; one more frame after
 * it lands the focus, like `DispatchQueue.main.async { selectAll(nil) }`).
 *
 * The ticket's list, plus the geometry and the scroll coupling:
 * - non-mono: after 1000 ms the field is focused, has the whole text
 *   selected (`TextRange(0, length)`), the header shows "Select Text",
 *   there is no grabber, and the Done tap dismisses exactly once;
 * - mono: opens at Medium with the grabber, nothing focused or selected;
 *   swiping the text up at Medium expands the sheet to Large first;
 * - the insets: 19 dp sides, 16 dp below the 52 dp header, and at the end
 *   of the scroll the last line 32 dp + the nav-bar inset above the sheet
 *   bottom;
 * - the font sizes: 16.5 / 14 dp at `Density(2.625f, 1f)`, 21.45 / 14 dp
 *   under a linear 1.3 font scale (through [SelectTextFontDpKey], the
 *   ticket's "style font size converted back to dp, ±0.01");
 * - the overlay: opening a sheet clears the composer's focus, and a
 *   request while one is showing is ignored.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-mdpi")
class SelectTextSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var sheetState: GlassSheetState? = null
    private var dismissals = 0

    /** The nav-bar inset GlassSheet passes as the content's bottom inset (dp). */
    private var navBottomDp = 0f

    // ------------------------------------------------------------- harness

    /**
     * Composes the sheet directly (own state, so the tests can read the
     * detents and the offset). [density] overrides `LocalDensity` — the
     * theme builds its [sh.roboco.android.design.TypeScale] from it, so a
     * linear font scale shows up exactly.
     */
    private fun composeSheet(
        text: String = Message,
        title: String = SelectTextDefaults.Title,
        mono: Boolean = false,
        density: Density? = null,
        onDismiss: () -> Unit = { dismissals++ },
    ) {
        dismissals = 0
        sheetState = null
        navBottomDp = 0f
        val next = SheetConfig(sheetConfig?.let { it.generation + 1 } ?: 0, text, title, mono, density, onDismiss)
        if (sheetConfig != null) {
            // setContent may run only once per test with an Android compose
            // rule: later calls swap the fixture through state; key() below
            // gives each one a fresh sheet (the size is remembered once).
            compose.runOnIdle { sheetConfig = next }
            return
        }
        sheetConfig = next
        compose.setContent {
            val config = sheetConfig ?: return@setContent
            key(config.generation) { SheetHost(config) }
        }
    }

    /** One [composeSheet] call's fixture. */
    private class SheetConfig(
        val generation: Int,
        val text: String,
        val title: String,
        val mono: Boolean,
        val density: Density?,
        val onDismiss: () -> Unit,
    )

    private var sheetConfig by mutableStateOf<SheetConfig?>(null)

    @Composable
    private fun SheetHost(config: SheetConfig) {
        val text = config.text
        val title = config.title
        val mono = config.mono
        val density = config.density
        val onDismiss = config.onDismiss
        run {
            val content: @Composable () -> Unit = {
                RobocoTheme(Appearance.Light) {
                    Box(Modifier.fillMaxSize()) {
                        val d = LocalDensity.current
                        navBottomDp = with(d) {
                            WindowInsets.navigationBars.exclude(WindowInsets.ime)
                                .getBottom(d).toDp().value
                        }
                        val state = rememberGlassSheetState(
                            if (mono) SelectTextDefaults.MonoDetents else SelectTextDefaults.SansDetents,
                        )
                        sheetState = state
                        SelectTextSheet(
                            text = text,
                            onDismiss = onDismiss,
                            title = title,
                            mono = mono,
                            state = state,
                        )
                    }
                }
            }
            if (density != null) {
                CompositionLocalProvider(LocalDensity provides density) { content() }
            } else {
                content()
            }
        }
    }

    /** Presents the sheet and settles the 0.5 s spring on the frozen clock. */
    private fun presentAndSettle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1100)
        compose.waitForIdle()
    }

    /**
     * The activity window's compose root. With a selection showing, the
     * text field's handles add popup roots, so `onRoot()` is ambiguous; the
     * window root is the one spanning the whole 360 dp width.
     */
    private fun windowRoot(): SemanticsNodeInteraction {
        val roots = compose.onAllNodes(isRoot())
        val index = roots.fetchSemanticsNodes().indexOfFirst { it.size.width >= 360 }
        check(index >= 0) { "no window-sized compose root" }
        return roots[index]
    }

    private fun fieldText(): String = compose.onNodeWithTag("select-text-field")
        .fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun fieldSelection(): TextRange = compose.onNodeWithTag("select-text-field")
        .fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]

    private fun fontDp(): Float = compose.onNodeWithTag("select-text-field")
        .fetchSemanticsNode().config[SelectTextFontDpKey]

    // ------------------------------------------------- the Select Text sheet

    @Test
    fun nonMonoSelectsAllFocusedWithTheTitleAndNoGrabber() {
        compose.mainClock.autoAdvance = false
        composeSheet(mono = false, text = Message)
        presentAndSettle()

        compose.onNodeWithTag("select-text-field").assertIsFocused()
        assertEquals(Message, fieldText())
        assertEquals(TextRange(0, Message.length), fieldSelection())
        compose.onNodeWithText(SelectTextDefaults.Title).assertExists()
        compose.onNodeWithText("Done").assertDoesNotExist() // Done is a glyph, not a word
        compose.onNodeWithContentDescription("Done").assertExists()
        // The plain page sheet: no grabber.
        compose.onNodeWithContentDescription("Sheet Grabber").assertDoesNotExist()
    }

    @Test
    fun theDoneTapDismissesExactlyOnce() {
        compose.mainClock.autoAdvance = false
        composeSheet(mono = false, text = Message)
        presentAndSettle()
        assertEquals(0, dismissals)

        compose.onNodeWithTag("sheet-done").performClick()
        compose.mainClock.advanceTimeBy(1100)
        compose.waitForIdle()
        assertEquals(1, dismissals)
        assertEquals(SheetDetent.Hidden, sheetState!!.current)
    }

    // -------------------------------------------------- the tool detail sheet

    @Test
    fun monoOpensAtMediumUnselectedWithTheGrabber() {
        compose.mainClock.autoAdvance = false
        composeSheet(mono = true, title = "Bash", text = ToolPayload)
        presentAndSettle()

        assertEquals(SheetDetent.Medium, sheetState!!.current)
        compose.onNodeWithTag("select-text-field").assertIsNotFocused()
        assertEquals(ToolPayload, fieldText())
        assertEquals(TextRange.Zero, fieldSelection())
        compose.onNodeWithContentDescription("Sheet Grabber").assertExists()
        compose.onNodeWithText("Bash").assertExists()
    }

    @Test
    fun swipingTheTextAtMediumExpandsTheSheetToLargeFirst() {
        compose.mainClock.autoAdvance = false
        composeSheet(mono = true, title = "Bash", text = ToolPayload)
        presentAndSettle()
        assertEquals(SheetDetent.Medium, sheetState!!.current)

        // An upward swipe over the sheet's content: 17's nested scroll moves
        // the sheet first (the content only scrolls once it is at Large).
        // The gesture is driven in window coordinates (onRoot) because the
        // field's own node is taller than the window and would start the
        // swipe outside it.
        //
        // The distance must cross 17's settle rule: a slow release crosses
        // from Medium (320) to Large (10) only past 50 % of that 310 px span.
        // The old 130 px swipe moved the sheet 114 px (130 − the 16 px touch
        // slop the scroll consumes) — 37 % — so it correctly sprang back to
        // Medium (CI r23). 240 px moves it 224 px (72 %), still a slow drag
        // (600 dp/s, under the 1200 dp/s fling line), starting on the text.
        compose.onRoot().performTouchInput {
            swipe(Offset(180f, 600f), Offset(180f, 360f), durationMillis = 400)
        }
        compose.mainClock.advanceTimeBy(1100)
        compose.waitForIdle()
        assertEquals(SheetDetent.Large, sheetState!!.current)
    }

    // ----------------------------------------------------------- the insets

    @Test
    fun textInsetsMatchTheUikitGeometry() {
        compose.mainClock.autoAdvance = false
        composeSheet(mono = false, text = LongScrollingText)
        presentAndSettle()

        val bounds = compose.onNodeWithTag("select-text-field").getBoundsInRoot()
        // textContainerInset.left (14) + NSTextContainer.lineFragmentPadding (5).
        assertEquals(19f, bounds.left.value, 0.5f)
        // The text wraps 19 dp before the right edge of the 360 dp sheet.
        assertEquals(322f, bounds.right.value - bounds.left.value, 0.5f)
        // The first line's top is 16 dp below the header's bottom
        // (sheet top + 52 + 16); the sheet top is the state's offset at Large.
        assertEquals(sheetState!!.offset + 52f + 16f, bounds.top.value, 0.5f)

        // Scroll to the end: the last line sits 32 dp + bottomInset above the
        // sheet bottom, which at Large is the window bottom (640).
        //
        // The field's bottom is read *unclipped* (position + size):
        // getBoundsInRoot is clipped to the scroll viewport, so it reads 640
        // at every offset short of the end and cannot tell "still
        // scrolling" from "at the end". And the swipes repeat until that
        // bottom stops moving rather than a fixed 8 times: 120 wrapped lines
        // are ~5000 px of text, and a 380 px / 400 ms swipe (+ its ~180 px
        // fling at mdpi) moves ~540 px, so 8 swipes stopped short of the end
        // (CI r33: 640, not 608).
        compose.mainClock.autoAdvance = true
        fun fieldBottom(): Float {
            val node = compose.onNodeWithTag("select-text-field").fetchSemanticsNode()
            return node.positionInRoot.y + node.size.height
        }
        var last = fieldBottom()
        for (attempt in 0 until 40) {
            // Not onRoot(): the sheet opened with the whole text selected, so
            // the field shows its selection handles, and each handle is a
            // Popup — a second compose root (CI r30: a 40 × 40 px root beside
            // the 360 × 640 window). The swipe goes to the window's root.
            windowRoot().performTouchInput {
                swipe(Offset(180f, 500f), Offset(180f, 120f), durationMillis = 400)
            }
            compose.waitForIdle()
            val bottom = fieldBottom()
            if (bottom == last) break // the scroll is at its end
            last = bottom
        }
        assertEquals(640f - SelectTextDefaults.PaddingBottom.value - navBottomDp, last, 1f)
        // And the clipped bounds agree: the field's last line is on screen.
        assertEquals(
            last,
            compose.onNodeWithTag("select-text-field").getBoundsInRoot().bottom.value,
            1f,
        )
    }

    // ------------------------------------------------------- the font sizes

    @Test
    fun fontSizesMatchBodyScaledAndFixed() {
        // Density(2.625f, fontScale 1): bodyFactor 1 → 16.5 dp; fixed 14.
        composeSheet(mono = false, density = Density(2.625f, 1f))
        compose.waitForIdle()
        assertEquals(16.5f, fontDp(), 0.01f)
        composeSheet(mono = true, text = ToolPayload, density = Density(2.625f, 1f))
        compose.waitForIdle()
        assertEquals(14f, fontDp(), 0.01f)

        // A linear 1.3 font scale: bodyScaled(16.5) = 21.45 dp (unrounded);
        // fixed(14) stays 14.
        composeSheet(mono = false, density = LinearDensity(1f, 1.3f))
        compose.waitForIdle()
        assertEquals(21.45f, fontDp(), 0.01f)
        composeSheet(mono = true, text = ToolPayload, density = LinearDensity(1f, 1.3f))
        compose.waitForIdle()
        assertEquals(14f, fontDp(), 0.01f)
    }

    // ------------------------------------------------------------- the overlay

    @Test
    fun openingASheetClearsTheComposerFocus() {
        compose.mainClock.autoAdvance = false
        var controllerRef: TextSheetController? = null
        var composerFocused = false
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                val controller = rememberTextSheetController()
                controllerRef = controller
                Box(Modifier.fillMaxSize()) {
                    // The dummy "composer": focused first, then the sheet
                    // must take the focus away (and hide the IME).
                    var value by remember { mutableStateOf("typing") }
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("composer")
                            .onFocusChanged { composerFocused = it.isFocused },
                    )
                    TextSheetOverlay(controller)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("composer").performTouchInput { click(center) }
        compose.waitForIdle()
        assertTrue("the composer must be focused first", composerFocused)

        controllerRef!!.showDetail("Bash", "payload text")
        Snapshot.sendApplyNotifications() // a test-thread write: advanceTimeBy never sends them
        compose.mainClock.advanceTimeBy(1100)
        compose.waitForIdle()
        assertFalse("opening the sheet must clear the composer's focus", composerFocused)
        assertEquals("payload text", fieldText())
        // The overlay presents the mono request to its smallest detent: the
        // sheet surface's top sits at half the window (320 dp at Medium).
        val sheet = compose.onNodeWithTag("glass-sheet").getBoundsInRoot()
        assertEquals(320f, sheet.top.value, 1f)
    }

    @Test
    fun theOverlayIgnoresNewRequestsWhileASheetIsShowing() {
        compose.mainClock.autoAdvance = false
        var controllerRef: TextSheetController? = null
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                val controller = rememberTextSheetController()
                controllerRef = controller
                Box(Modifier.fillMaxSize()) { TextSheetOverlay(controller) }
            }
        }
        compose.waitForIdle()
        val controller = controllerRef!!

        assertTrue(controller.showDetail("Bash", "first payload"))
        Snapshot.sendApplyNotifications() // a test-thread write: advanceTimeBy never sends them
        compose.mainClock.advanceTimeBy(1100)
        compose.waitForIdle()
        assertEquals("first payload", fieldText())

        // One at a time: the new request is ignored while the sheet is up.
        assertFalse(controller.selectText("second"))
        assertEquals("first payload", fieldText())

        // After the dismissal the slot is free again.
        compose.onNodeWithTag("sheet-done").performClick()
        compose.mainClock.advanceTimeBy(1100)
        compose.waitForIdle()
        assertNull(controller.current)
        assertTrue(controller.selectText("second"))
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1100)
        compose.waitForIdle()
        assertEquals("second", fieldText())
        compose.onNodeWithText(SelectTextDefaults.Title).assertExists()
    }

    // ------------------------------------------------------------- fixtures

    private companion object {
        val Message =
            "Roboco connects directly to the engines you pair with it.\n\n" +
                "The transcript is paint-only, so the text in the list itself cannot be " +
                "selected. Select Text shows the whole message here."

        /** 40 mono lines — taller than the medium content slot, so it scrolls. */
        val ToolPayload: String = buildString {
            for (i in 1..39) appendLine("line $i: $ roboco engine start --port ${8674 + i}")
            append("k".repeat(160))
        }

        /** Long enough that the scroll end is far from the first frame. */
        val LongScrollingText: String = buildString {
            for (i in 1..120) appendLine("prose line $i of the long message used for the scroll-end inset")
        }
    }
}

/**
 * A `Density` whose sp↔dp conversion is linear (the debug gallery's
 * `LinearFontScaleDensity`, kept local so the unit test does not reach into
 * the debug source set): Android 14's non-linear `FontScaleConverter` maps
 * font scale 1.3 to a body factor of ~1.229, so the exact 21.45 dp check
 * needs the linear override.
 */
private class LinearDensity(
    override val density: Float,
    override val fontScale: Float,
) : Density {
    override fun Dp.toSp(): TextUnit = (value / fontScale).sp

    override fun TextUnit.toDp(): Dp {
        check(type == TextUnitType.Sp) { "Only Sp can convert to Dp" }
        return (value * fontScale).dp
    }
}
