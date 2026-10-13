package sh.roboco.android.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Palette
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.gallery.QuestionCardFixtures
import sh.roboco.android.design.glass.Glass

/**
 * [QuestionCard] — android-ui-parity #90's acceptance criteria (Robolectric
 * + compose-ui-test). The window is the ticket's phone slice: **388 dp
 * wide at 420 dpi (density 2.625)**, so the dp arithmetic of the height
 * formula lands inside the ±1 dp tolerances (at mdpi the per-line ceil
 * drift would exceed them). Glass runs its forced fallback tier (the
 * GalleryCapture convention) so the fill probes read exact composites;
 * the Roborazzi goldens cover the drawn material.
 *
 *  - the three height formulas: A 342.85, C 294.45, E 294.05 dp (± 1 dp),
 *    unchanged at font scale 2.0 (`fixed` sizes);
 *  - the gap table: 10 (header→question), 14 (question→options), 6
 *    (between options), 10 (options→Other), 14 (Other→buttons), and the
 *    multiline rule 24 (question→editor around the empty options column)
 *    and 10 (editor→buttons);
 *  - the insets: content 16/16/16/12, option row 42.8 dp with the title
 *    42.4 dp in, no icon and no gap from the 51st option;
 *  - callbacks: onPick once with the label, onBack, onNext gated by
 *    canContinue, onOtherDone once with focus kept, the focus hooks;
 *  - semantics: Role.Button + label + selected, Back removed on page 0,
 *    Next/Submit titles, Disabled;
 *  - the fills: chip@0.55 / accent@0.12 / chip@0.6 composited over the
 *    fallback card (light and dark), the ARGB constants, the icon inks;
 *  - the pressed alpha 0.5 (content dimmed, fill unchanged), the editor's
 *    13/12 inset by its caret, and the editor scrolling inside 140 dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w388dp-h520dp-420dpi")
class QuestionCardTest {

    @get:Rule
    val compose = createComposeRule()

    /** The qualifier's 420 dpi. */
    private val density = 2.625f

    @Before
    fun forceFallbackGlass() {
        Glass.forceFallback = true
    }

    @After
    fun restore() {
        Glass.forceFallback = false
        compose.mainClock.autoAdvance = true
        RuntimeEnvironment.setFontScale(1f)
    }

    // ------------------------------------------------------------ helpers

    /** Callback counters, one per fixture. */
    private class Counts {
        var pick = 0
        var custom = 0
        var otherDone = 0
        var back = 0
        var next = 0
        var pickedLabel: String? = null
        var customText: String? = null
    }

    private fun actionsOf(c: Counts) = QuestionPageActions(
        onPick = { c.pickedLabel = it; c.pick++ },
        onCustomChange = { c.customText = it; c.custom++ },
        onOtherDone = { c.otherDone++ },
        onBack = { c.back++ },
        onNext = { c.next++ },
    )

    /** One [place] call's inputs; [generation] keys a fresh card per call. */
    private class Placement(
        val model: QuestionPageModel,
        val actions: QuestionPageActions,
        val appearance: Appearance,
        val glassVisible: Boolean,
        val animateGlass: Boolean,
        val extra: @Composable () -> Unit,
        val generation: Int,
    )

    private var placement by mutableStateOf<Placement?>(null)

    /**
     * Renders one card over the page background, top-start, plus [extra].
     * The rule allows one setContent per test, so the first call mounts and
     * later calls swap the inputs (keyed: each call is a fresh card).
     */
    private fun place(
        model: QuestionPageModel = QuestionCardFixtures.A,
        actions: QuestionPageActions = QuestionCardFixtures.NoActions,
        appearance: Appearance = Appearance.Light,
        glassVisible: Boolean = true,
        animateGlass: Boolean = true,
        extra: @Composable () -> Unit = {},
    ) {
        val mounted = placement != null
        placement = Placement(
            model, actions, appearance, glassVisible, animateGlass, extra,
            generation = (placement?.generation ?: 0) + 1,
        )
        if (!mounted) {
            compose.setContent {
                val p = placement ?: return@setContent
                val background = if (p.appearance == Appearance.Dark) Palette.Dark.background else Palette.Light.background
                key(p.generation) {
                    RobocoTheme(p.appearance) {
                        Box(Modifier.fillMaxSize().background(background)) {
                            QuestionCard(p.model, p.actions, Modifier, p.glassVisible, p.animateGlass)
                            p.extra()
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): DpRect = compose.onNodeWithTag(tag).getBoundsInRoot()

    private fun textBounds(value: String): DpRect = compose.onNodeWithText(value).getBoundsInRoot()

    private fun unmergedTextBounds(value: String): DpRect =
        compose.onNodeWithText(value, useUnmergedTree = true).getBoundsInRoot()

    private fun DpRect.heightDp(): Float = bottom.value - top.value

    private fun DpRect.widthDp(): Float = right.value - left.value

    /** The vertical gap between [above]'s bottom and [below]'s top, in dp. */
    private fun gap(above: DpRect, below: DpRect): Float = below.top.value - above.bottom.value

    private fun hasButtonRole(): SemanticsMatcher =
        SemanticsMatcher("has Button role") { it.config[SemanticsProperties.Role] == Role.Button }

    // -------------------------------------------------------- the fills

    /** The fallback card interior: `Glass.<mode>.fallback` over the page background. */
    private val cardLight = Glass.Light.fallback.compositeOver(Palette.Light.background)
    private val cardDark = Glass.Dark.fallback.compositeOver(Palette.Dark.background)
    private val unpickedFillLight = Palette.Light.chip.copy(alpha = 0.55f).compositeOver(cardLight)
    private val unpickedFillDark = Palette.Dark.chip.copy(alpha = 0.55f).compositeOver(cardDark)
    private val pickedFillLight = Palette.Light.accent.copy(alpha = 0.12f).compositeOver(cardLight)
    private val otherFillLight = Palette.Light.chip.copy(alpha = 0.6f).compositeOver(cardLight)
    private val dimmedInkLight = Palette.Light.text.copy(alpha = 0.5f).compositeOver(unpickedFillLight)

    private fun pixels(tag: String): PixelMap = compose.onNodeWithTag(tag).captureToImage().toPixelMap()

    /** ± [tolerance] 8-bit levels on every channel. */
    private fun isNear(c: Color, target: Color, tolerance: Int): Boolean {
        fun ch(color: Color, shift: Int) = (color.toArgb() shr shift) and 0xFF
        return abs(ch(c, 16) - ch(target, 16)) <= tolerance &&
            abs(ch(c, 8) - ch(target, 8)) <= tolerance &&
            abs(ch(c, 0) - ch(target, 0)) <= tolerance
    }

    private fun countNear(
        px: PixelMap,
        target: Color,
        tolerance: Int = 12,
        xRange: IntRange? = null,
        yRange: IntRange? = null,
    ): Int {
        var n = 0
        val xs = xRange ?: 0 until px.width
        val ys = yRange ?: 0 until px.height
        for (y in ys) {
            for (x in xs) {
                if (isNear(px[x, y], target, tolerance)) n++
            }
        }
        return n
    }

    /** The ink's bounding box (px), or null when no pixel is near [target]. */
    private fun inkBounds(px: PixelMap, target: Color, tolerance: Int = 12): Rect? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (y in 0 until px.height) {
            for (x in 0 until px.width) {
                if (isNear(px[x, y], target, tolerance)) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        if (minX == Int.MAX_VALUE) return null
        return Rect(minX.toFloat(), minY.toFloat(), (maxX + 1).toFloat(), (maxY + 1).toFloat())
    }

    /** ± [tolerance] levels per channel, like ComposerSurfaceTest's probes. */
    private fun assertChannelNear(what: String, actual: Color, expected: Color, tolerance: Int = 3) {
        fun ch(argb: Int, shift: Int) = (argb shr shift) and 0xFF
        val a = actual.toArgb()
        val e = expected.toArgb()
        for (shift in intArrayOf(16, 8, 0)) {
            assertTrue(
                "$what: channel at shift $shift is ${ch(a, shift)}, expected ${ch(e, shift)} ± $tolerance",
                abs(ch(a, shift) - ch(e, shift)) <= tolerance,
            )
        }
    }

    // ------------------------------------------------------ the heights

    @Test
    fun fixtureAIs342_85dpTallAt388dpWidth() {
        place()
        val card = bounds("question-card")
        assertEquals(0f, card.left.value, 0.5f)
        assertEquals(0f, card.top.value, 0.5f)
        assertEquals("card width = the panel width", 388f, card.widthDp(), 1f)
        // 16 + 16.25 + 10 + 22.1 + 14 + 3 × 42.8 + 2 × 6 + 10 + 44 + 14 + 44.1 + 12.
        assertEquals("fixture A's height formula", 342.85f, card.heightDp(), 1f)
        // The insets: header 16 below the top and 16 in from the start…
        val header = textBounds("Question · 1 of 2")
        assertEquals(16f, header.top.value - card.top.value, 0.5f)
        assertEquals(16f, header.left.value - card.left.value, 0.5f)
        // …full-width children 16 from the end…
        assertEquals(16f, card.right.value - bounds("question-option-0").right.value, 1.5f)
        assertEquals(16f, card.right.value - bounds("question-other").right.value, 1.5f)
        // …and the Next row's bottom 12 above the card bottom.
        val next = bounds("question-next")
        assertEquals(12f, card.bottom.value - next.bottom.value, 0.5f)
        // Next sits at the trailing edge; Back is gone on page 0.
        assertEquals(16f, card.right.value - next.right.value, 1.5f)
        compose.onAllNodesWithText("Back").assertCountEquals(0)
    }

    @Test
    fun fixtureCIs294_45dpTallAndUsesTheEditorGaps() {
        place(QuestionCardFixtures.C)
        val card = bounds("question-card")
        // 16 + 16.25 + 10 + 22.1 + 14 + 0 + 10 + 140 + 10 + 44.1 + 12.
        assertEquals("fixture C's height formula", 294.45f, card.heightDp(), 1f)
        val editor = bounds("question-editor")
        assertEquals(140f, editor.heightDp(), 0.5f)
        assertEquals(356f, editor.widthDp(), 1.5f)
        // The empty options column keeps both its gaps: question→editor is
        // 14 + 10 = 24…
        assertEquals(24f, gap(textBounds("Describe the change you want"), editor), 0.5f)
        // …and the editor (the hidden Other field's custom 14 dropped) is
        // followed by the default 10.
        assertEquals(10f, gap(editor, bounds("question-next")), 0.5f)
        // The Other field is absent when multiline.
        compose.onNodeWithTag("question-other").assertDoesNotExist()
        compose.onNodeWithText("Question").assertExists()
    }

    @Test
    fun fixtureEIs294_05dpTall() {
        place(QuestionCardFixtures.E)
        val card = bounds("question-card")
        // 16 + 16.25 + 10 + 22.1 + 14 + (2 × 42.8 + 6) + 10 + 44 + 14 + 44.1 + 12.
        assertEquals("fixture E's height formula", 294.05f, card.heightDp(), 1f)
        // Single page: the header is the bare "Scope" (no "· 1 of 1").
        val header = textBounds("Scope")
        assertEquals(16f, header.left.value, 0.5f)
        compose.onNodeWithText("Scope · 1 of 1").assertDoesNotExist()
        // canContinue = false: Submit, disabled.
        compose.onNodeWithText("Submit").assertIsNotEnabled()
    }

    @Test
    fun theHeightsIgnoreFontScale() {
        RuntimeEnvironment.setFontScale(2f)
        place()
        assertEquals(342.85f, bounds("question-card").heightDp(), 1f)
        place(QuestionCardFixtures.C)
        assertEquals(294.45f, bounds("question-card").heightDp(), 1f)
    }

    // ------------------------------------------------------- the gaps

    @Test
    fun theColumnGapsMatchTheTicketSpacings() {
        place()
        val header = textBounds("Question · 1 of 2")
        val question = textBounds("Pick a tone")
        val option0 = bounds("question-option-0")
        val option1 = bounds("question-option-1")
        val option2 = bounds("question-option-2")
        val other = bounds("question-other")
        val next = bounds("question-next")
        assertEquals("the Other field is 44 dp tall", 44f, other.heightDp(), 0.5f)
        assertEquals("header → question is 10", 10f, gap(header, question), 0.5f)
        assertEquals("question → first option is 14", 14f, gap(question, option0), 0.5f)
        assertEquals("between options is 6", 6f, gap(option0, option1), 0.5f)
        assertEquals("between options is 6", 6f, gap(option1, option2), 0.5f)
        assertEquals("last option → Other is 10", 10f, gap(option2, other), 0.5f)
        assertEquals("Other → the buttons row is 14", 14f, gap(other, next), 0.5f)
    }

    @Test
    fun optionRowsAreFullWidth42_8dpTallWithTheTitle42_4DpIn() {
        place()
        val row = bounds("question-option-0")
        assertEquals("the row is the column's width (356 = 388 − 32)", 356f, row.widthDp(), 1.5f)
        assertEquals("11 + 20.8 + 11", 42.8f, row.heightDp(), 0.5f)
        // 12 (row padding) + 20.4 (the 17 pt icon box) + 10 (imagePadding).
        assertEquals(
            "the title starts 12 + 20.4 + 10 in",
            42.4f,
            unmergedTextBounds("Formal").left.value - row.left.value,
            0.5f,
        )
    }

    @Test
    @Config(sdk = [36], qualifiers = "w388dp-h1100dp-420dpi")
    fun the51stOptionRowHasNoIconAndNoGap() {
        val manyOptions = QuestionPageModel(
            header = "Question",
            page = 0,
            pageCount = 1,
            question = "Pick one",
            options = (1..51).map { "Option $it" },
            picked = emptySet(),
            multiSelect = false,
            multiline = false,
            customText = "",
            canContinue = false,
        )
        place(manyOptions)
        // 51 rows (~2500 dp) overflow even the 1100 dp window: rows 49/50
        // lie below the root, where getBoundsInRoot clips both the row and
        // its title to the same empty rect (left 0 - 0 = 0). Unclipped
        // bounds measure the real layout positions.
        fun row(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        fun title(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()
        // SF Symbols has "\(n).circle" only for 0…50: the 51st row (index
        // 50) shows no icon, so no gap either — the title is 12 dp in.
        assertEquals(
            "the 51st title starts at the plain padding",
            12f,
            title("Option 51").left.value - row("question-option-50").left.value,
            0.5f,
        )
        // The 50th still has its ring: 42.4 dp in.
        assertEquals(
            "the 50th title keeps the icon and the 10 dp gap",
            42.4f,
            title("Option 50").left.value - row("question-option-49").left.value,
            0.5f,
        )
    }

    // ---------------------------------------------------- the callbacks

    @Test
    fun tappingAnOptionFiresOnPickOnceWithTheLabel() {
        val counts = Counts()
        place(actions = actionsOf(counts))
        compose.onNodeWithTag("question-option-1").performClick()
        compose.waitForIdle()
        assertEquals(1, counts.pick)
        assertEquals("Casual", counts.pickedLabel)
        // Picking is decided by label, not index — not the row's text alone.
        assertEquals(0, counts.next)
    }

    @Test
    fun backOnPage1SitsAtTheRowStartAsTallAsNextAndFires() {
        val counts = Counts()
        place(QuestionCardFixtures.B2, actionsOf(counts))
        val back = compose.onNodeWithText("Back").getBoundsInRoot()
        val next = bounds("question-next")
        assertEquals("Back at the row start (the content's 16)", 16f, back.left.value, 0.5f)
        assertEquals("Back fills the row height (Next ≈ 44.1)", next.heightDp(), back.heightDp(), 0.5f)
        // The title inks in secondary (iOS: baseForegroundColor).
        val backInk = compose.onNodeWithText("Back").captureToImage().toPixelMap()
        assertTrue("Back's title inks in secondary", countNear(backInk, Palette.Light.secondary, 12) >= 2)
        compose.onNodeWithText("Back").performClick()
        compose.waitForIdle()
        assertEquals(1, counts.back)
    }

    @Test
    fun nextReadsNextOrSubmitAndGatesOnCanContinue() {
        val counts = Counts()
        place(QuestionCardFixtures.B1, actionsOf(counts))
        compose.onNodeWithText("Next").assertIsEnabled()
        compose.onNodeWithTag("question-next").performClick()
        compose.waitForIdle()
        assertEquals("an answered page advances", 1, counts.next)

        val disabled = Counts()
        place(QuestionCardFixtures.A, actions = actionsOf(disabled))
        compose.onNodeWithTag("question-next").assertIsNotEnabled()
        compose.onNodeWithText("Submit").assertDoesNotExist()
        // A raw tap at the disabled centre goes nowhere.
        val nb = bounds("question-next")
        val cx = (nb.left.value + nb.right.value) / 2f * density
        val cy = (nb.top.value + nb.bottom.value) / 2f * density
        compose.onRoot().performTouchInput { click(Offset(cx, cy)) }
        compose.waitForIdle()
        assertEquals(0, disabled.next)

        place(QuestionCardFixtures.B2)
        compose.onNodeWithText("Submit").assertExists()
    }

    @Test
    fun imeDoneOnTheOtherFieldFiresOnceAndKeepsFocus() {
        val counts = Counts()
        place(actions = actionsOf(counts))
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("question-other").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(16)
        compose.waitForIdle()
        compose.onNodeWithTag("question-other").assertIsFocused()
        // The caret is the window tint (tintColor, SceneDelegate.swift:15).
        assertTrue(
            "the caret draws in accent",
            countNear(pixels("question-other"), Palette.Light.accent, 12) >= 2,
        )
        compose.onNodeWithTag("question-other").performImeAction()
        compose.waitForIdle()
        assertEquals(1, counts.otherDone)
        // iOS returns true from textFieldShouldReturn without resigning.
        compose.onNodeWithTag("question-other").assertIsFocused()
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun theOtherFieldFocusHooksReportFocusInAndOut() {
        val requester = FocusRequester()
        val events = mutableListOf<Boolean>()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(Palette.Light.background)) {
                    QuestionPageContent(
                        QuestionCardFixtures.A,
                        QuestionCardFixtures.NoActions,
                        otherFocusRequester = requester,
                        onOtherFocusChanged = { events.add(it) },
                    )
                    BasicTextField(
                        value = "",
                        onValueChange = {},
                        modifier = Modifier.testTag("steal").fillMaxWidth().height(30.dp),
                        textStyle = TextStyle(color = Palette.Light.text),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { requester.requestFocus() }
        compose.waitForIdle()
        compose.onNodeWithTag("question-other").assertIsFocused()
        assertEquals(true, events.last())
        // Focus leaving the card (91's refocus-after-Done relies on this).
        compose.onNodeWithTag("steal").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("question-other").assertIsNotFocused()
        assertEquals(false, events.last())
    }

    @Test
    fun pressingAnOptionDimsTheContentToHalfAndKeepsTheFill() {
        place()
        val before = pixels("question-option-0")
        assertTrue("the number glyph draws in text ink", countNear(before, Palette.Light.text, 12) >= 5)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("question-option-0").performTouchInput { down(center) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val during = pixels("question-option-0")
        assertEquals("no full-opacity ink while pressed", 0, countNear(during, Palette.Light.text, 8))
        assertTrue("the ink reads text @0.5 over the fill", countNear(during, dimmedInkLight, 8) >= 5)
        assertChannelNear(
            "the fill is unchanged while pressed",
            during[during.width - 10, during.height / 2],
            unpickedFillLight,
        )
        compose.onNodeWithTag("question-option-0").performTouchInput { cancel() }
        compose.waitForIdle()
    }

    // ------------------------------------------------------ the glass

    @Test
    fun hidingTheGlassStripsThePageFromTheSemanticsTree() {
        val counts = Counts()
        var visible by mutableStateOf(true)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(Palette.Light.background)) {
                    QuestionCard(
                        QuestionCardFixtures.A,
                        actionsOf(counts),
                        Modifier,
                        glassVisible = visible,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("question-option-0").assertExists()
        val option = bounds("question-option-0")
        // The UIKit model-value rule: the moment the target flips, the
        // page is dead — semantics stripped, taps swallowed.
        compose.runOnUiThread { visible = false }
        compose.waitForIdle()
        compose.onNodeWithTag("question-option-0").assertDoesNotExist()
        compose.onNodeWithTag("question-other").assertDoesNotExist()
        compose.onNodeWithTag("question-next").assertDoesNotExist()
        compose.onNodeWithTag("question-card").assertExists()
        val cx = (option.left.value + option.right.value) / 2f * density
        val cy = (option.top.value + option.bottom.value) / 2f * density
        compose.onRoot().performTouchInput { click(Offset(cx, cy)) }
        compose.waitForIdle()
        assertEquals("a tap on the dematerialized card goes nowhere", 0, counts.pick)
    }

    // ------------------------------------------------------ the colors

    @Test
    fun theFillColorsMatchTheTicketArgbTable() {
        // Written through the palette roles; the table is the ARGB the
        // 8-bit quantization must land on.
        assertEquals(0x8CE7E7EB.toInt(), Palette.Light.chip.copy(alpha = 0.55f).toArgb())
        assertEquals(0x8C1C1C20.toInt(), Palette.Dark.chip.copy(alpha = 0.55f).toArgb())
        assertEquals(0x99E7E7EB.toInt(), Palette.Light.chip.copy(alpha = 0.6f).toArgb())
        assertEquals(0x991C1C20.toInt(), Palette.Dark.chip.copy(alpha = 0.6f).toArgb())
        assertEquals(0x1F5B43E8.toInt(), Palette.Light.accent.copy(alpha = 0.12f).toArgb())
        assertEquals(0x1F8B7CF6.toInt(), Palette.Dark.accent.copy(alpha = 0.12f).toArgb())
    }

    @Test
    fun theUnpickedRowFillCompositesOverTheCard() {
        place()
        val row = pixels("question-option-0")
        assertChannelNear("the unpicked fill (light)", row[row.width - 10, row.height / 2], unpickedFillLight)
        assertTrue("the number circle inks in text", countNear(row, Palette.Light.text, 12, xRange = 28 until 92) >= 2)

        place(appearance = Appearance.Dark)
        val dark = pixels("question-option-0")
        assertChannelNear("the unpicked fill (dark)", dark[dark.width - 10, dark.height / 2], unpickedFillDark)
    }

    @Test
    fun thePickedRowAndOtherFieldFillsCompositeOverTheCard() {
        place(QuestionCardFixtures.B1)
        val row = pixels("question-option-1")
        assertChannelNear("the picked fill (light)", row[row.width - 10, row.height / 2], pickedFillLight)
        assertTrue("the check glyph inks in accent", countNear(row, Palette.Light.accent, 12, xRange = 28 until 92) >= 2)

        val other = pixels("question-other")
        assertChannelNear("the Other field fill (light)", other[other.width - 10, other.height / 2], otherFillLight)
    }

    // ------------------------------------------------- the fields' ink

    @Test
    fun theOtherPlaceholderSits12DpInInTertiary() {
        place()
        val px = pixels("question-other")
        val ink = inkBounds(px, Palette.Light.tertiary)
        assertTrue("the placeholder draws in tertiary", ink != null)
        ink!!
        assertEquals("12 dp from the field's start", 12f, ink.left / density, 1.5f)
        // Single line, vertically centred in the 44 dp field.
        assertTrue(
            "the ink is one line tall (${(ink.bottom - ink.top) / density} dp)",
            (ink.bottom - ink.top) / density < 15f,
        )
        assertTrue(
            "the ink is centred on the field's 22 dp midline (was ${((ink.top + ink.bottom) / 2 / density)})",
            abs((ink.top + ink.bottom) / 2f / density - 22f) <= 2.5f,
        )
    }

    @Test
    fun theEditorInsetsItsText13Start12Top() {
        // An empty editor puts the caret at the very start of the text slot:
        // its left edge is the 13 dp inset, its top the 12 dp one.
        place(QuestionCardFixtures.C.copy(customText = ""))
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("question-editor").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(16)
        compose.waitForIdle()
        val px = pixels("question-editor")
        val caret = inkBounds(px, Palette.Light.accent, 12)
        assertTrue("the caret draws in accent", caret != null)
        caret!!
        assertEquals("8 (inset) + 5 (line-fragment padding)", 13f, caret.left / density, 1.5f)
        assertEquals("the textContainerInset's top", 12f, caret.top / density, 1.5f)
    }

    @Test
    fun theEditorStays140AndScrollsWhenTheTextIsTaller() {
        val tall = QuestionCardFixtures.C.copy(
            customText = (1..20).joinToString("\n") { "line $it" },
        )
        place(tall)
        assertEquals(140f, bounds("question-editor").heightDp(), 0.5f)
        // The box never grows: the card keeps fixture C's height, the text
        // scrolls inside the editor.
        assertEquals(294.45f, bounds("question-card").heightDp(), 1f)
    }

    // ----------------------------------------------------- the a11y

    @Test
    fun pickedRowsCarrySelectedSemanticsAndReadAsButtons() {
        place(QuestionCardFixtures.B1)
        compose.onNodeWithTag("question-option-1").assertIsSelected()
        compose.onNodeWithTag("question-option-0").assertIsNotSelected()
        // One merged button per row, labelled by the option title.
        compose.onNodeWithText("Event-driven fold with coalesced commits").assert(hasButtonRole())
        compose.onNodeWithText("Event-driven fold with coalesced commits").assertIsSelected()

        // Multi-select picks show the square check: still selected.
        place(QuestionCardFixtures.B2)
        compose.onNodeWithText("Unit tests").assertIsSelected()
        compose.onNodeWithText("End-to-end (two-device)").assertIsNotSelected()
    }

    @Test
    fun theTagsExistPerMode() {
        place()
        for (i in 0..2) compose.onNodeWithTag("question-option-$i").assertExists()
        compose.onNodeWithTag("question-other").assertExists()
        compose.onNodeWithTag("question-next").assertExists()
        compose.onNodeWithTag("question-editor").assertDoesNotExist()
        // The empty field exposes the placeholder as its description.
        compose.onNodeWithContentDescription("Other…").assertExists()

        place(QuestionCardFixtures.C)
        compose.onNodeWithTag("question-editor").assertExists()
        compose.onNodeWithTag("question-other").assertDoesNotExist()
        compose.onNodeWithContentDescription("Other…").assertDoesNotExist()
    }
}
