package sh.roboco.android.design.glass

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import java.io.File

/**
 * The glass buttons' behaviour and semantics (ticket 08) — Robolectric +
 * compose-ui-test at mdpi (1 dp = 1 px, the IconSizingTest convention), so
 * the dp assertions read off the measured pixels:
 *
 * - clicks fire once; disabled and busy never click and report
 *   `disabled()`;
 * - the circle measures 44/40 dp, the capsule is exactly 44 dp, the
 *   compact prominent is at least 44 dp;
 * - the prominent heights match the iOS insets at font scale 1 and 2
 *   (`fixed(17)` is a UIKit point: the height ignores font scale);
 * - the pressed glass circle (07's `GlassSurface` scale) and the pressed
 *   prominent (`Modifier.glassPress` scale) both settle at exactly one
 *   `Glass.PressScale` — never `Glass.PressScale²` — 400 ms into the press
 *   and back at 1.0 by 600 ms after release, with unchanged layout bounds;
 * - Reduce Motion keeps the pressed scale at 1.0 (the highlight snaps);
 * - no Material import in the three source files.
 *
 * Robolectric draws software, so every glass surface here is the Fallback
 * tier (the GlassVisibilityTest convention): these tests exercise
 * behaviour, not the drawn material (the Roborazzi goldens cover that).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-mdpi")
class GlassButtonsTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() {
        ReduceMotion.resetForTest()
    }

    // ------------------------------------------------------------ helpers

    private fun hasKey(key: SemanticsPropertyKey<Float>): SemanticsMatcher =
        SemanticsMatcher("has ${key.name}") { it.config.contains(key) }

    /** Every published value of [key] in the semantics tree. */
    private fun pressScales(key: SemanticsPropertyKey<Float>): List<Float> =
        compose.onAllNodes(hasKey(key)).fetchSemanticsNodes().map { it.config[key] }

    /** The node's height in px == dp at mdpi. */
    private fun heightPx(tag: String): Int =
        compose.onNodeWithTag(tag).fetchSemanticsNode().size.height

    // ------------------------------------------------------------- clicks

    @Test
    fun circleClickFiresOnClickOnce() {
        var clicks = 0
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize()) {
                    GlassCircleButton(
                        RobocoIcons.Ellipsis, "More", { clicks++ },
                        modifier = Modifier.testTag("circle"),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("More").performClick()
        compose.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test
    fun capsuleAndProminentClickFiresOnClickOnce() {
        var capsuleClicks = 0
        var prominentClicks = 0
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Column {
                    GlassTextCapsule("Edit", { capsuleClicks++ }, modifier = Modifier.testTag("capsule"))
                    ProminentGlassButton("Next", { prominentClicks++ }, modifier = Modifier.testTag("prominent"))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()
        assertEquals(1, capsuleClicks)
        assertEquals(1, prominentClicks)
    }

    @Test
    fun semanticsAreButtonsWithTheirLabels() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Column {
                    GlassCircleButton(RobocoIcons.Ellipsis, "More", {})
                    GlassTextCapsule("Edit", {})
                    ProminentGlassButton("Next", {})
                }
            }
        }
        compose.waitForIdle()
        // The clickable merges the glyph/text label; its own Role.Button
        // wins over the icon's Role.Image in the merged config.
        val circle = compose.onNodeWithContentDescription("More").fetchSemanticsNode()
        assertEquals(Role.Button, circle.config[SemanticsProperties.Role])
        val capsule = compose.onNodeWithText("Edit").fetchSemanticsNode()
        assertEquals(Role.Button, capsule.config[SemanticsProperties.Role])
        // SemanticsProperties.Text holds AnnotatedStrings: compare the text.
        assertEquals("Edit", capsule.config[SemanticsProperties.Text].first().text)
        val prominent = compose.onNodeWithText("Next").fetchSemanticsNode()
        assertEquals(Role.Button, prominent.config[SemanticsProperties.Role])
    }

    @Test
    fun disabledButtonsDoNotClickAndReportDisabled() {
        var circleClicks = 0
        var capsuleClicks = 0
        var prominentClicks = 0
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Column {
                    GlassCircleButton(
                        RobocoIcons.Ellipsis, "More", { circleClicks++ },
                        modifier = Modifier.testTag("c"), enabled = false,
                    )
                    GlassTextCapsule("Edit", { capsuleClicks++ }, modifier = Modifier.testTag("t"), enabled = false)
                    ProminentGlassButton("Next", { prominentClicks++ }, modifier = Modifier.testTag("p"), enabled = false)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("c").assertIsNotEnabled()
        compose.onNodeWithTag("t").assertIsNotEnabled()
        compose.onNodeWithTag("p").assertIsNotEnabled()
        // Raw taps (performClick would assert on the missing click action).
        compose.onNodeWithTag("c").performTouchInput { click(center) }
        compose.onNodeWithTag("t").performTouchInput { click(center) }
        compose.onNodeWithTag("p").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(0, circleClicks)
        assertEquals(0, capsuleClicks)
        assertEquals(0, prominentClicks)
    }

    @Test
    fun busyProminentIgnoresClicksAndShowsTheSpinner() {
        // The frozen clock keeps the spinner's frame loop from spinning —
        // waitForIdle would otherwise chase its frames forever.
        compose.mainClock.autoAdvance = false
        var clicks = 0
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize()) {
                    ProminentGlassButton(
                        "Use “roboco”", { clicks++ },
                        modifier = Modifier.testTag("p"), busy = true,
                    )
                }
            }
        }
        compose.waitForIdle()
        // The spinner's tag merges into the clickable button node, so it is
        // only addressable in the unmerged tree.
        compose.onNodeWithTag(BusySpinnerTag, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Use “roboco”").assertIsNotEnabled()
        assertEquals(
            "In progress",
            compose.onNodeWithText("Use “roboco”").fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription],
        )
        compose.onNodeWithTag("p").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(0, clicks)
    }

    // ----------------------------------------------------------- geometry

    @Test
    fun circleMeasuresItsSize() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Column {
                    GlassCircleButton(
                        RobocoIcons.Ellipsis, "More", {},
                        modifier = Modifier.testTag("d44"),
                    )
                    GlassCircleButton(
                        RobocoIcons.ArrowDown, "Jump to latest", {},
                        size = 40.dp, iconPt = 15f,
                        modifier = Modifier.testTag("d40"),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("d44").assertWidthIsEqualTo(44.dp).assertHeightIsEqualTo(44.dp)
        compose.onNodeWithTag("d40").assertWidthIsEqualTo(40.dp).assertHeightIsEqualTo(40.dp)
    }

    @Test
    fun textCapsuleIsExactly44DpTall() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                GlassTextCapsule("Edit", {}, modifier = Modifier.testTag("t"))
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("t").assertHeightIsEqualTo(44.dp)
    }

    @Test
    fun prominentCompactIsAtLeast44DpTall() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                ProminentGlassButton(
                    "Next", {},
                    contentPadding = ProminentGlassButtonDefaults.CompactPadding,
                    modifier = Modifier.testTag("p"),
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("p").assertHeightIsAtLeast(44.dp)
    }

    // Geist 17's line box is 1.3 × 17 ≈ 22.1 dp (ticket 03): default
    // 14+22.1+14 ≈ 50, pairing 15+22.1+15 ≈ 52, compact 11+22.1+11 ≈ 44.
    // `fixed(17)` is a UIKit point, so the same holds at font scale 2.
    @Composable
    private fun ProminentHeightsFixture() {
        RobocoTheme(Appearance.Light) {
            Column {
                ProminentGlassButton(
                    "Next", {},
                    contentPadding = ProminentGlassButtonDefaults.CompactPadding,
                    modifier = Modifier.testTag("compact"),
                )
                ProminentGlassButton(
                    "Pair an engine", {},
                    contentPadding = ProminentGlassButtonDefaults.PairingPadding,
                    modifier = Modifier.testTag("pairing"),
                )
                ProminentGlassButton("Use “roboco”", {}, modifier = Modifier.testTag("default"))
            }
        }
    }

    private fun assertProminentHeights() {
        assertTrue("compact was ${heightPx("compact")} dp", heightPx("compact") in 43..45)
        assertTrue("pairing was ${heightPx("pairing")} dp", heightPx("pairing") in 51..53)
        assertTrue("default was ${heightPx("default")} dp", heightPx("default") in 49..51)
    }

    @Test
    fun prominentHeightsMatchTheIosInsets() {
        compose.setContent { ProminentHeightsFixture() }
        compose.waitForIdle()
        assertProminentHeights()
    }

    @Test
    fun prominentHeightsIgnoreFontScale() {
        RuntimeEnvironment.setFontScale(2f)
        compose.setContent { ProminentHeightsFixture() }
        compose.waitForIdle()
        assertProminentHeights()
    }

    // --------------------------------------------------------- the press

    @Test
    fun pressedGlassCircleScalesOnceToPressScaleAndBack() {
        compose.mainClock.autoAdvance = false
        val source = MutableInteractionSource()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize()) {
                    GlassCircleButton(
                        RobocoIcons.Ellipsis, "More", {},
                        modifier = Modifier.testTag("circle"),
                        interactionSource = source,
                    )
                }
            }
        }
        compose.waitForIdle()
        val sizeBefore = compose.onNodeWithTag("circle").fetchSemanticsNode().size

        val press = PressInteraction.Press(Offset.Zero)
        source.tryEmit(press)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400) // the press-down spring settles in ≈0.4 s

        // Exactly one press-scale publisher — 07's GlassSurface — at
        // Glass.PressScale: a stacked glassPress would publish a second
        // GlassPressModifierScaleKey node and compound the scale (1.06²).
        val scales = pressScales(GlassPressScaleKey)
        assertEquals("one press-scale publisher on the glass circle", 1, scales.size)
        assertEquals(Glass.PressScale, scales.single(), 0.005f)
        assertTrue(
            "glassPress must not be stacked on the interactive GlassSurface",
            pressScales(GlassPressModifierScaleKey).isEmpty(),
        )
        // graphicsLayer scaling: the measured bounds never change.
        assertEquals(sizeBefore, compose.onNodeWithTag("circle").fetchSemanticsNode().size)

        source.tryEmit(PressInteraction.Release(press))
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600) // release + the small overshoot
        assertEquals(1f, pressScales(GlassPressScaleKey).single(), 0.005f)
        assertEquals(sizeBefore, compose.onNodeWithTag("circle").fetchSemanticsNode().size)
    }

    @Test
    fun pressedProminentScalesOnceToPressScaleAndBack() {
        compose.mainClock.autoAdvance = false
        val source = MutableInteractionSource()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize()) {
                    ProminentGlassButton(
                        "Next", {},
                        modifier = Modifier.testTag("p"),
                        interactionSource = source,
                    )
                }
            }
        }
        compose.waitForIdle()
        val sizeBefore = compose.onNodeWithTag("p").fetchSemanticsNode().size

        val press = PressInteraction.Press(Offset.Zero)
        source.tryEmit(press)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        val scales = pressScales(GlassPressModifierScaleKey)
        assertEquals("one glassPress scale publisher", 1, scales.size)
        assertEquals(Glass.PressScale, scales.single(), 0.005f)
        assertTrue(
            "the prominent button must not also sit on an interactive GlassSurface",
            pressScales(GlassPressScaleKey).isEmpty(),
        )
        assertEquals(sizeBefore, compose.onNodeWithTag("p").fetchSemanticsNode().size)

        source.tryEmit(PressInteraction.Release(press))
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        assertEquals(1f, pressScales(GlassPressModifierScaleKey).single(), 0.005f)
        assertEquals(sizeBefore, compose.onNodeWithTag("p").fetchSemanticsNode().size)
    }

    @Test
    fun reducedMotionKeepsTheGlassCirclePressedScaleAtOne() {
        ReduceMotion.debugOverride = true
        compose.mainClock.autoAdvance = false
        val source = MutableInteractionSource()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize()) {
                    GlassCircleButton(
                        RobocoIcons.Ellipsis, "More", {},
                        interactionSource = source,
                    )
                }
            }
        }
        compose.waitForIdle()
        source.tryEmit(PressInteraction.Press(Offset.Zero))
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        assertEquals(1f, pressScales(GlassPressScaleKey).single(), 0.005f)
    }

    @Test
    fun reducedMotionKeepsTheProminentPressedScaleAtOne() {
        ReduceMotion.debugOverride = true
        compose.mainClock.autoAdvance = false
        val source = MutableInteractionSource()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize()) {
                    ProminentGlassButton("Next", {}, interactionSource = source)
                }
            }
        }
        compose.waitForIdle()
        source.tryEmit(PressInteraction.Press(Offset.Zero))
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        assertEquals(1f, pressScales(GlassPressModifierScaleKey).single(), 0.005f)
    }

    // --------------------------------------------------- no Material rule

    @Test
    fun glassButtonSourcesImportNoMaterial() {
        // The ticket's grep check; NoMaterialImportsTest covers src/main
        // as a whole, this pins the three files the ticket names.
        for (name in listOf("GlassButtons.kt", "GlassPress.kt", "ActivitySpinner.kt")) {
            val file = File("src/main/java/sh/roboco/android/design/glass/$name")
            assertTrue("$name is missing under ${File(".").absolutePath}", file.isFile)
            val offenders = file.useLines { lines ->
                lines.filter { it.trimStart().startsWith("import androidx.compose.material") }.toList()
            }
            assertTrue("$name imports Material:\n${offenders.joinToString("\n")}", offenders.isEmpty())
        }
    }
}
