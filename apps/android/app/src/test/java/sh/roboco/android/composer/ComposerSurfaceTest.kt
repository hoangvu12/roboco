package sh.roboco.android.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
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
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Palette
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme

/**
 * [ComposerSurface] and [ComposerAttachButton] — android-ui-parity #55's
 * acceptance criteria (Robolectric + compose-ui-test, the GlassVisibilityTest
 * convention: software rendering, so every glass surface is the Fallback
 * tier; these tests exercise the layout, hit testing and semantics, the
 * Roborazzi goldens cover the drawn material).
 *
 * The fixture is the ticket's worked phone: `W = 366` (a 390 dp phone minus
 * the 12 + 12 session margins) at mdpi (1 dp = 1 px, the IconSizingTest
 * convention), stand-in slots whose sizes pin the ticket's `textH` values
 * (50 in the capsule, 48 mid-morph, 46 in the card, 68 of thumbnails):
 *
 * - the `p = 0` and `p = 1` geometry tables (root height, attach, action,
 *   text, thumbs, chips bounds — everything the unit fixture pins in dp,
 *   here read off the placed nodes);
 * - decision COMPOSER-D1: the text slot is measured at the *target* mode's
 *   width (334 expanding, 264 collapsing) and clipped to the lerped frame
 *   (299 at `p = 0.5`), and a recording stand-in logs that every step of
 *   `p` 0 → 1 measures it at 334;
 * - the z-order: a tap at the attach centre fires `+`, never a chip — even
 *   when the chip stand-in is forced to span the whole toolbar band;
 * - the chips' UIKit model-value rule: they go dead the instant the target
 *   flips (semantics stripped, touches consumed);
 * - focus survival across `p` 1 → 0 → 1 (slots are never removed), and
 *   re-measure-without-recomposition (a `SideEffect` counter stays at 1
 *   across 10 `p` changes);
 * - [ComposerAttachButton]: 34 × 34 circle of `controlFill` over the
 *   background (pixel probes), "Attach" / `Role.Button` /
 *   `composer-attach`, and the disabled look (tertiary ink, dead taps);
 * - RTL mirrors every control.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w366dp-h320dp-mdpi")
class ComposerSurfaceTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() {
        ReduceMotion.resetForTest()
    }

    // ------------------------------------------------------------ helpers

    /** Callback counters, one per fixture. */
    private class Clicks {
        var attach = 0
        var chip = 0
        var action = 0
    }

    /** The text stand-in's saturated fill (never a glass/palette colour). */
    private val textMarker = Color(0xFF00CC66)

    /** The chips stand-in's saturated fill. */
    private val chipMarker = Color(0xFFFF3B30)

    private val markerTolerance = 20f / 255f

    private fun isMarker(c: Color, marker: Color): Boolean =
        abs(c.red - marker.red) <= markerTolerance &&
            abs(c.green - marker.green) <= markerTolerance &&
            abs(c.blue - marker.blue) <= markerTolerance

    private fun rootPixels(): PixelMap = compose.onRoot().captureToImage().toPixelMap()

    private fun countMarker(px: PixelMap, marker: Color): Int {
        var n = 0
        for (y in 0 until px.height) {
            for (x in 0 until px.width) {
                if (isMarker(px[x, y], marker)) n++
            }
        }
        return n
    }

    private fun hasButtonRole(): SemanticsMatcher =
        SemanticsMatcher("has Button role") { it.config[SemanticsProperties.Role] == Role.Button }

    /**
     * The fixture: the composer at a fixed morph position over the page
     * background, at the frame's top-left, with stand-in slots.
     */
    private fun place(
        p: Float,
        chipsInteractive: Boolean,
        textH: Dp,
        clicks: Clicks = Clicks(),
        withThumbs: Boolean = false,
        spanBandChips: Boolean = false,
    ) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(Palette.Light.background)) {
                    ComposerSurface(
                        progress = { p },
                        chipsInteractive = chipsInteractive,
                        modifier = Modifier.testTag("surface").fillMaxWidth(),
                        thumbnails = { if (withThumbs) ThumbsStandIn() },
                        text = { TextStandIn(textH) },
                        placeholder = { PlaceholderStandIn() },
                        attach = { AttachStandIn(clicks) },
                        chips = { ChipsStandIn(clicks, spanBandChips) },
                        action = { ActionStandIn() },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    // --------------------------------------------------------- stand-ins

    /** The text slot: [height] tall, filled with the marker colour. */
    @Composable
    private fun TextStandIn(height: Dp) {
        Box(Modifier.testTag("text").fillMaxWidth().height(height).background(textMarker))
    }

    /** The attach stand-in: a clickable 34 dp box (the geometry reads 34 x 34). */
    @Composable
    private fun AttachStandIn(clicks: Clicks) {
        Box(
            Modifier
                .testTag("attach")
                .size(34.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { clicks.attach++ },
                ),
        )
    }

    /** The placeholder: "Message" in tertiary, one line. */
    @Composable
    private fun PlaceholderStandIn() {
        BasicText(
            "Message",
            modifier = Modifier.testTag("placeholder"),
            style = TextStyle(color = Palette.Light.tertiary),
            maxLines = 1,
        )
    }

    /**
     * The chips stand-in. With [spanBand] it is forced to cover the whole
     * toolbar band (`p = 1`: the band is (0, 46, 366, 96), the chips slot
     * sits at (52, 54)) — the adversarial case of the z-order test.
     */
    @Composable
    private fun ChipsStandIn(clicks: Clicks, spanBand: Boolean = false) {
        val span = if (spanBand) {
            Modifier
                .requiredSize(366.dp, 50.dp)
                .offset(x = (-52).dp, y = (-8).dp)
        } else {
            Modifier.fillMaxSize()
        }
        Box(
            Modifier
                .testTag("chips")
                .then(span)
                .background(chipMarker)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { clicks.chip++ },
                ),
        )
    }

    /** The action stand-in: 34 dp square (the geometry only reads its width). */
    @Composable
    private fun ActionStandIn() {
        Box(
            Modifier
                .testTag("action")
                .size(34.dp)
                .background(Color(0xFF5B43E8)),
        )
    }

    /** The thumbnail stand-in: 68 dp tall. */
    @Composable
    private fun ThumbsStandIn() {
        Box(
            Modifier
                .testTag("thumbs")
                .fillMaxWidth()
                .height(68.dp)
                .background(Color(0xFF62626A)),
        )
    }

    // --------------------------------------------------- the p = 0 table

    @Test
    fun theCapsuleLayoutMatchesTheTicketGeometry() {
        place(p = 0f, chipsInteractive = false, textH = 50.dp)
        compose.onNodeWithTag("surface")
            .assertWidthIsEqualTo(366.dp)
            .assertHeightIsEqualTo(50.dp)
        compose.onNodeWithTag("attach")
            .assertPositionInRootIsEqualTo(8.dp, 8.dp)
            .assertWidthIsEqualTo(34.dp)
            .assertHeightIsEqualTo(34.dp)
        compose.onNodeWithTag("action")
            .assertPositionInRootIsEqualTo(324.dp, 8.dp)
            .assertWidthIsEqualTo(34.dp)
            .assertHeightIsEqualTo(34.dp)
        compose.onNodeWithTag("text")
            .assertPositionInRootIsEqualTo(52.dp, 0.dp)
            .assertWidthIsEqualTo(264.dp)
            .assertHeightIsEqualTo(50.dp)
        compose.onNodeWithTag("placeholder")
            .assertPositionInRootIsEqualTo(52.dp, 14.dp)
        // The chips are gone: absent from the semantics tree (the UIKit
        // model-alpha rule)...
        compose.onNodeWithTag("chips").assertDoesNotExist()
        // ...and nothing of their marker draws (layer alpha 0).
        assertEquals(0, countMarker(rootPixels(), chipMarker))
    }

    // --------------------------------------------------- the p = 1 table

    @Test
    fun theCardLayoutMatchesTheTicketGeometry() {
        place(p = 1f, chipsInteractive = true, textH = 46.dp)
        compose.onNodeWithTag("surface").assertHeightIsEqualTo(96.dp)
        compose.onNodeWithTag("attach")
            .assertPositionInRootIsEqualTo(10.dp, 54.dp)
            .assertWidthIsEqualTo(34.dp)
            .assertHeightIsEqualTo(34.dp)
        compose.onNodeWithTag("action")
            .assertPositionInRootIsEqualTo(322.dp, 54.dp)
            .assertWidthIsEqualTo(34.dp)
            .assertHeightIsEqualTo(34.dp)
        compose.onNodeWithTag("text")
            .assertPositionInRootIsEqualTo(16.dp, 0.dp)
            .assertWidthIsEqualTo(334.dp)
            .assertHeightIsEqualTo(46.dp)
        compose.onNodeWithTag("placeholder")
            .assertPositionInRootIsEqualTo(16.dp, 14.dp)
        compose.onNodeWithTag("chips")
            .assertPositionInRootIsEqualTo(52.dp, 54.dp)
            .assertWidthIsEqualTo(262.dp)
            .assertHeightIsEqualTo(34.dp)
        // Alive now: in the tree and drawing.
        assertTrue(countMarker(rootPixels(), chipMarker) > 0)
    }

    @Test
    fun theCardWithThumbsGrowsTheSurfaceOnly() {
        place(p = 1f, chipsInteractive = true, textH = 46.dp, withThumbs = true)
        compose.onNodeWithTag("surface").assertHeightIsEqualTo(164.dp)
        compose.onNodeWithTag("thumbs")
            .assertPositionInRootIsEqualTo(12.dp, 0.dp)
            .assertWidthIsEqualTo(342.dp)
            .assertHeightIsEqualTo(68.dp)
        compose.onNodeWithTag("text")
            .assertPositionInRootIsEqualTo(16.dp, 68.dp)
        compose.onNodeWithTag("attach").assertPositionInRootIsEqualTo(10.dp, 122.dp)
        compose.onNodeWithTag("action").assertPositionInRootIsEqualTo(322.dp, 122.dp)
        compose.onNodeWithTag("chips").assertPositionInRootIsEqualTo(52.dp, 122.dp)
    }

    // ------------------------------------- COMPOSER-D1: the text's width

    @Test
    fun expandingMeasuresTheCardWidthAndClipsToTheLerpedFrame() {
        place(p = 0.5f, chipsInteractive = true, textH = 48.dp)
        // Measured at the TARGET (card) width 334, placed at the lerped
        // frame's x 34.
        compose.onNodeWithTag("text")
            .assertPositionInRootIsEqualTo(34.dp, 0.dp)
            .assertWidthIsEqualTo(334.dp)
        // The visible (clipped) width is the lerped frame 299: the clip
        // covers global x 34..333 (34 + 299), the placeable runs to 368.
        val px = rootPixels()
        assertTrue("inside the clip at (332, 24)", isMarker(px[332, 24], textMarker))
        assertFalse("outside the clip at (336, 24)", isMarker(px[336, 24], textMarker))
    }

    @Test
    fun collapsingMeasuresTheCapsuleWidthAndIsNotCut() {
        place(p = 0.5f, chipsInteractive = false, textH = 48.dp)
        // Measured at the TARGET (capsule) width 264: narrower than the
        // lerped frame 299, so nothing is cut — the marker's own right
        // edge is at 34 + 264 = 298.
        compose.onNodeWithTag("text")
            .assertPositionInRootIsEqualTo(34.dp, 0.dp)
            .assertWidthIsEqualTo(264.dp)
        val px = rootPixels()
        assertTrue("the stand-in's own edge at (296, 24)", isMarker(px[296, 24], textMarker))
        assertFalse("nothing past it at (300, 24)", isMarker(px[300, 24], textMarker))
    }

    @Test
    fun steppingProgressMeasuresTheTextAtTheTargetOnEveryFrame() {
        var p by mutableFloatStateOf(0f)
        val maxWidths = mutableListOf<Int>()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(Palette.Light.background)) {
                    ComposerSurface(
                        progress = { p },
                        chipsInteractive = true,
                        modifier = Modifier.testTag("surface").fillMaxWidth(),
                        text = { RecordingTextStandIn(46.dp, maxWidths) { p } },
                        attach = {},
                        chips = {},
                        action = { ActionStandIn() },
                    )
                }
            }
        }
        compose.waitForIdle()
        repeat(10) { i ->
            compose.runOnUiThread { p = (i + 1) / 10f }
            compose.waitForIdle()
        }
        // The target is the card from the very first measure (expanding),
        // and it stays 334 through every re-measure.
        assertTrue("the slot was re-measured at each step: ${maxWidths.size} measures", maxWidths.size >= 10)
        assertTrue("every measure at the card width 334: $maxWidths", maxWidths.all { it == 334 })
        // The frame still followed p: at p = 1 the text sits at x 16.
        compose.onNodeWithTag("text").assertPositionInRootIsEqualTo(16.dp, 0.dp)
    }

    /**
     * Logs every `maxWidth` the slot is measured at. Compose skips a
     * child's measure when its constraints are unchanged, so with a steady
     * 334 the parent's re-measure alone would log once; reading [progress]
     * in the layout block invalidates this node on every `p` step, so each
     * frame really re-runs the measure and logs the width it got.
     */
    @Composable
    private fun RecordingTextStandIn(height: Dp, maxWidths: MutableList<Int>, progress: () -> Float) {
        Box(
            Modifier
                .testTag("text")
                .fillMaxWidth()
                .height(height)
                .layout { measurable, constraints ->
                    progress()
                    maxWidths += constraints.maxWidth
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                },
        )
    }

    // ---------------------------------------------------- the z-order

    @Test
    fun aTapOnTheAttachCentreGoesToTheAttachNeverTheChips() {
        val clicks = Clicks()
        // The chip stand-in is forced to span the whole band (366 x 50 at
        // (0, 46)): the iOS regression "the toolbar row must not cover
        // '+'". The attach is placed after (above) the chips, and the band
        // clip bounds the chips' hit area to their slot.
        place(p = 1f, chipsInteractive = true, textH = 46.dp, clicks = clicks, spanBandChips = true)
        compose.onRoot().performTouchInput { click(Offset(27f, 71f)) }
        compose.waitForIdle()
        assertEquals(1, clicks.attach)
        assertEquals(0, clicks.chip)
    }

    // ------------------------------------- the chips' model-value rule

    @Test
    fun theChipsGoDeadTheInstantTheTargetFlips() {
        val clicks = Clicks()
        // p = 1 but the target is the capsule: a collapse just started.
        place(p = 1f, chipsInteractive = false, textH = 46.dp, clicks = clicks)
        compose.onRoot().performTouchInput { click(Offset(183f, 71f)) }
        compose.waitForIdle()
        assertEquals(0, clicks.chip)
        compose.onNodeWithTag("chips").assertDoesNotExist()
    }

    @Test
    fun theChipsAreAliveWhileTheTargetIsTheCard() {
        val clicks = Clicks()
        place(p = 1f, chipsInteractive = true, textH = 46.dp, clicks = clicks)
        compose.onNodeWithTag("chips").assertExists()
        compose.onRoot().performTouchInput { click(Offset(183f, 71f)) }
        compose.waitForIdle()
        assertEquals(1, clicks.chip)
    }

    // ------------------------------------------------- focus survival

    @Test
    fun focusSurvivesTheMorph() {
        var p by mutableFloatStateOf(1f)
        var isCard by mutableStateOf(true)
        val field = TextFieldState("draft")
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(Palette.Light.background)) {
                    ComposerSurface(
                        progress = { p },
                        chipsInteractive = isCard,
                        modifier = Modifier.testTag("surface").fillMaxWidth(),
                        text = {
                            BasicTextField(
                                state = field,
                                modifier = Modifier.testTag("field").fillMaxWidth(),
                                textStyle = TextStyle(color = Palette.Light.text),
                            )
                        },
                        attach = {},
                        chips = {},
                        action = { ActionStandIn() },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("field").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("field").assertIsFocused()
        compose.runOnUiThread { p = 0f; isCard = false }
        compose.waitForIdle()
        // The slot was never removed from composition: the focus survives.
        compose.onNodeWithTag("field").assertIsFocused()
        compose.runOnUiThread { p = 1f; isCard = true }
        compose.waitForIdle()
        compose.onNodeWithTag("field").assertIsFocused()
    }

    // -------------------------------------- re-measure, no recompose

    @Test
    fun progressChangesReMeasureWithoutRecomposingTheTextSlot() {
        var p by mutableFloatStateOf(0f)
        var textCompositions = 0
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(Palette.Light.background)) {
                    ComposerSurface(
                        progress = { p },
                        chipsInteractive = true,
                        modifier = Modifier.testTag("surface").fillMaxWidth(),
                        text = {
                            SideEffect { textCompositions++ }
                            Box(Modifier.testTag("text").height(46.dp))
                        },
                        attach = {},
                        chips = {},
                        action = { ActionStandIn() },
                    )
                }
            }
        }
        compose.waitForIdle()
        assertEquals(1, textCompositions)
        repeat(10) { i ->
            compose.runOnUiThread { p = (i + 1) / 10f }
            compose.waitForIdle()
        }
        // Ten p changes: re-measured (the frame moved to x 16) but the
        // slot composed exactly once.
        assertEquals(1, textCompositions)
        compose.onNodeWithTag("text").assertPositionInRootIsEqualTo(16.dp, 0.dp)
    }

    // ----------------------------------------------------------- RTL

    @Test
    fun rtlMirrorsTheControls() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.fillMaxSize().background(Palette.Light.background)) {
                        ComposerSurface(
                            progress = { 0f },
                            chipsInteractive = false,
                            modifier = Modifier.testTag("surface").fillMaxWidth(),
                            text = { TextStandIn(50.dp) },
                            attach = { AttachStandIn(Clicks()) },
                            chips = {},
                            action = { ActionStandIn() },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        // placeRelative mirrors: the attach's right edge is at W - 8
        // (its top-left lands at 366 - 8 - 34 = 324)...
        compose.onNodeWithTag("attach")
            .assertPositionInRootIsEqualTo(324.dp, 8.dp)
            .assertWidthIsEqualTo(34.dp)
        // ...and the action's left edge is at 8.
        compose.onNodeWithTag("action")
            .assertPositionInRootIsEqualTo(8.dp, 8.dp)
            .assertWidthIsEqualTo(34.dp)
        // The text frame mirrors by the placeable's own width, keeping the
        // leading edge aligned: 366 - 52 - 264 = 50.
        compose.onNodeWithTag("text")
            .assertPositionInRootIsEqualTo(50.dp, 0.dp)
            .assertWidthIsEqualTo(264.dp)
    }

    // ----------------------------------------------- ComposerAttachButton

    @Test
    fun theAttachButtonIsA34dpCircleOfControlFill() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(Palette.Light.background)) {
                    ComposerAttachButton(onClick = {}, modifier = Modifier.testTag("attach"))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("attach")
            .assertWidthIsEqualTo(34.dp)
            .assertHeightIsEqualTo(34.dp)
        val px = compose.onNodeWithTag("attach").captureToImage().toPixelMap()
        // Centre-left (3, 17): controlFill over the fixture background.
        val expected = Palette.Light.controlFill.compositeOver(Palette.Light.background)
        assertChannelNear("the fill at (3, 17)", px[3, 17], expected)
        // A circle: the box's corner (1, 1) is outside the capsule, so it
        // shows the parent's background, not the fill.
        assertChannelNear("outside the circle at (1, 1)", px[1, 1], Palette.Light.background)
    }

    @Test
    fun theAttachButtonCarriesItsSemanticsTagAndClick() {
        var clicks = 0
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(Palette.Light.background)) {
                    ComposerAttachButton(onClick = { clicks++ })
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("composer-attach").assertExists()
        compose.onNodeWithContentDescription("Attach").assert(hasButtonRole())
        compose.onNodeWithContentDescription("Attach").performClick()
        compose.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test
    fun theDisabledAttachButtonDrawsTertiaryInkAndIgnoresTaps() {
        var clicks = 0
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(Palette.Light.background)) {
                    ComposerAttachButton(onClick = { clicks++ }, enabled = false, modifier = Modifier.testTag("attach"))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Attach").assertIsNotEnabled()
        // A tap at the button's centre goes nowhere: the disabled
        // clickable ignores it.
        compose.onRoot().performTouchInput { click(Offset(17f, 17f)) }
        compose.waitForIdle()
        assertEquals(0, clicks)
        // The glyph drops to tertiary: ink pixels of the tertiary colour
        // (the enabled ink is `text`, ~112 levels away per channel), none
        // of the text colour.
        val px = compose.onNodeWithTag("attach").captureToImage().toPixelMap()
        assertTrue(
            "no tertiary ink pixels in the disabled glyph",
            countMarker(px, Palette.Light.tertiary) >= 3,
        )
        assertEquals("no text-coloured ink pixels", 0, countMarker(px, Palette.Light.text))
    }

    @Test
    fun theEnabledAttachButtonInkIsText() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(Palette.Light.background)) {
                    ComposerAttachButton(onClick = {})
                }
            }
        }
        compose.waitForIdle()
        val px = compose.onRoot().captureToImage().toPixelMap()
        assertTrue(
            "no text ink pixels in the enabled glyph",
            countMarker(px, Palette.Light.text) >= 3,
        )
    }

    /** ±2 8-bit levels per channel, as the ticket's pixel AC asks. */
    private fun assertChannelNear(what: String, actual: Color, expected: Color) {
        val a = actual.toArgb()
        val e = expected.toArgb()
        fun ch(argb: Int, shift: Int) = (argb shr shift) and 0xFF
        for (shift in intArrayOf(16, 8, 0)) {
            assertTrue(
                "$what: channel at shift $shift is ${ch(a, shift)}, expected ${ch(e, shift)} ± 2",
                abs(ch(a, shift) - ch(e, shift)) <= 2,
            )
        }
    }
}
