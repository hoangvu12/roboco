package sh.roboco.android.shell

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Face
import sh.roboco.android.design.GlassStage
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.TypeScale
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassPressScaleKey
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.uiStyle

/**
 * Ticket 26's bar geometry and semantics (Robolectric, 402 × 874 dp at
 * xxhdpi with the zero insets Robolectric reports): the pill, circle and
 * platter bounds; the row-width cap at 360 / 800 dp; the
 * [tabBarBottomGap] / [tabBarRowWidth] tables; the press scale through
 * ticket 07's [GlassPressScaleKey] (whole pill vs circle, reduce); the
 * platter snap under Reduce Motion; the label's fixed font size at
 * fontScale 1.6; and the three `Role.Tab` nodes in one selectable group.
 *
 * The fixture: a 140 dp [GlassStage] aligned to the window bottom, so the
 * bar's bottom edge is the window bottom and the 21 dp bottom gap is
 * checked against the real window edge. The bar is the backdrop's sibling
 * (never inside its `hazeSource`). The selected tab is a plain
 * `mutableStateOf` field, so a flip mid-test starts the platter slide.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class GlassTabBarTest {

    @get:Rule
    val compose = createComposeRule()

    /** The bar's selection; set before [stage] or flip it after to animate. */
    private var selected by mutableStateOf(TabId.Sessions)

    @After
    fun tearDown() {
        ReduceMotion.resetForTest()
    }

    // ------------------------------------------------------------- fixtures

    /** The bar on the striped backdrop, the stage at the window bottom. */
    private fun stage(
        rtl: Boolean = false,
        source: MutableInteractionSource? = null,
        reduce: Boolean = false,
        freezeClock: Boolean = false,
    ) {
        if (freezeClock) compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalReduceMotion provides reduce,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        val haze = rememberHazeState()
                        CompositionLocalProvider(LocalHazeState provides haze) {
                            GlassStage(
                                modifier = Modifier.align(Alignment.BottomCenter),
                                height = 140.dp,
                                backdropModifier = Modifier.hazeSource(haze),
                            ) {
                                GlassTabBar(
                                    selected = selected,
                                    onTabTap = {},
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                    pillInteractionSource = source ?: remember { MutableInteractionSource() },
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** The 07 scale applied under [tag]'s GlassSurface, read from its test-only key. */
    private fun pressScaleUnder(tag: String): Float =
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .onChildren()
            .filter(hasPressScale())
            .fetchSemanticsNodes()
            .single()
            .config[GlassPressScaleKey]

    private fun hasPressScale(): SemanticsMatcher =
        SemanticsMatcher("has GlassPressScale") { it.config.contains(GlassPressScaleKey) }

    private fun hasRoleTab(): SemanticsMatcher =
        SemanticsMatcher("has Role.Tab") {
            it.config.contains(SemanticsProperties.Role) && it.config[SemanticsProperties.Role] == Role.Tab
        }

    private fun hasSelectableGroup(): SemanticsMatcher =
        SemanticsMatcher("has selectable group") { it.config.contains(SemanticsProperties.SelectableGroup) }

    // ------------------------------------------------------------- geometry

    @Test
    fun pillCircleAndPlatterBoundsAt402dp() {
        stage()
        // Pill: 290 × 62 at x 21; bottom 853 = window bottom − 21.
        compose.onNodeWithTag("tab-pill")
            .assertPositionInRootIsEqualTo(21.dp, 791.dp)
            .assertWidthIsEqualTo(290.dp)
            .assertHeightIsEqualTo(62.dp)
        // Search circle: 62 × 62 at x 319, the same top and bottom.
        compose.onNodeWithTag("tab-search")
            .assertPositionInRootIsEqualTo(319.dp, 791.dp)
            .assertWidthIsEqualTo(62.dp)
            .assertHeightIsEqualTo(62.dp)
        // Platter: cellWidth × 54 = 141 × 54, inset 4, under Sessions.
        compose.onNodeWithTag("tab-platter")
            .assertPositionInRootIsEqualTo(25.dp, 795.dp)
            .assertWidthIsEqualTo(141.dp)
            .assertHeightIsEqualTo(54.dp)
        // Each cell is the full cellWidth × 62 hit target (weight + fill).
        compose.onNodeWithTag("tab-sessions")
            .assertPositionInRootIsEqualTo(25.dp, 791.dp)
            .assertWidthIsEqualTo(141.dp)
            .assertHeightIsEqualTo(62.dp)
        // The label's layout starts 36 dp below the pill top (12 spacer + 24
        // icon box), so paddingFromBaseline(13) puts its baseline at 49. The
        // text node's bounds sit inside that padding (13 − first baseline
        // below 36), so the baseline itself is what is checked.
        val pill = compose.onNodeWithTag("tab-pill").fetchSemanticsNode()
        val label = compose.onNodeWithText("Sessions", useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        label.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val baseline = label.positionInRoot.y + layouts.single().firstBaseline
        val density = pill.size.height / 62f
        assertEquals(pill.positionInRoot.y + 49f * density, baseline, 0.75f)
    }

    @Test
    fun platterSlidesToTheSettingsCell() {
        selected = TabId.Settings
        stage()
        compose.onNodeWithTag("tab-platter")
            .assertPositionInRootIsEqualTo(166.dp, 795.dp)
            .assertWidthIsEqualTo(141.dp)
            .assertHeightIsEqualTo(54.dp)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h874dp-xxhdpi")
    fun pillIs248dpWideAt360dp() {
        stage()
        compose.onNodeWithTag("tab-pill")
            .assertPositionInRootIsEqualTo(21.dp, 791.dp)
            .assertWidthIsEqualTo(248.dp)
            .assertHeightIsEqualTo(62.dp)
        compose.onNodeWithTag("tab-search").assertPositionInRootIsEqualTo(277.dp, 791.dp)
        compose.onNodeWithTag("tab-platter")
            .assertPositionInRootIsEqualTo(25.dp, 795.dp)
            .assertWidthIsEqualTo(120.dp)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w800dp-h874dp-xxhdpi")
    fun rowIsCappedAt398AndCentredAt800dp() {
        stage()
        compose.onNodeWithTag("tab-pill")
            .assertPositionInRootIsEqualTo(201.dp, 791.dp)
            .assertWidthIsEqualTo(328.dp)
            .assertHeightIsEqualTo(62.dp)
        compose.onNodeWithTag("tab-search").assertPositionInRootIsEqualTo(537.dp, 791.dp)
        compose.onNodeWithTag("tab-platter")
            .assertPositionInRootIsEqualTo(205.dp, 795.dp)
            .assertWidthIsEqualTo(160.dp)
    }

    @Test
    fun orderMirrorsInRtlAndThePlatterTracksItsCell() {
        stage(rtl = true)
        // The search circle moves to the left edge, the pill to the right.
        compose.onNodeWithTag("tab-search").assertPositionInRootIsEqualTo(21.dp, 791.dp)
        compose.onNodeWithTag("tab-pill")
            .assertPositionInRootIsEqualTo(91.dp, 791.dp)
            .assertWidthIsEqualTo(290.dp)
        // Sessions is now the right cell; the platter mirrors with it.
        compose.onNodeWithTag("tab-platter")
            .assertPositionInRootIsEqualTo(236.dp, 795.dp)
            .assertWidthIsEqualTo(141.dp)
    }

    // ------------------------------------------------------- the tables

    @Test
    fun bottomGapTable() {
        assertEquals(21.dp, tabBarBottomGap(0.dp, 0.dp))
        assertEquals(21.dp, tabBarBottomGap(16.dp, 0.dp))
        assertEquals(24.dp, tabBarBottomGap(24.dp, 0.dp))
        assertEquals(60.dp, tabBarBottomGap(48.dp, 48.dp))
    }

    @Test
    fun rowWidthTable() {
        assertEquals(360.dp, tabBarRowWidth(402.dp))
        assertEquals(318.dp, tabBarRowWidth(360.dp))
        assertEquals(398.dp, tabBarRowWidth(440.dp))
        assertEquals(398.dp, tabBarRowWidth(800.dp))
    }

    // ------------------------------------------------------------ semantics

    @Test
    fun threeTabsInOneSelectableGroup() {
        stage()
        assertEquals(3, compose.onAllNodes(hasRoleTab()).fetchSemanticsNodes().size)
        // All three sit inside the row's one selectable group (TalkBack
        // announces "x of 3").
        assertEquals(
            3,
            compose.onAllNodes(hasRoleTab().and(hasAnyAncestor(hasSelectableGroup())))
                .fetchSemanticsNodes().size,
        )
        compose.onNodeWithTag("tab-sessions").assertIsSelected()
        compose.onNodeWithTag("tab-more").assertIsNotSelected()
        compose.onNodeWithTag("tab-search").assertIsNotSelected()
        // The pill cells merge their labels; Search is a label only.
        compose.onNodeWithText("Sessions").assertExists()
        compose.onNodeWithText("Settings").assertExists()
        compose.onNodeWithText("Search").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search").assertExists()
    }

    // ---------------------------------------------------------- the press

    @Test
    fun holdingEitherCellScalesTheWholePill() {
        val source = MutableInteractionSource()
        stage(source = source)
        val press = PressInteraction.Press(Offset.Zero)
        compose.runOnUiThread { source.tryEmit(press) }
        compose.waitForIdle()
        assertEquals(Glass.PressScale, pressScaleUnder("tab-pill"), 0.005f)
        assertEquals(1f, pressScaleUnder("tab-search"), 0.005f)
        compose.runOnUiThread { source.tryEmit(PressInteraction.Release(press)) }
        compose.waitForIdle()
        assertEquals(1f, pressScaleUnder("tab-pill"), 0.005f)
    }

    @Test
    fun holdingTheSearchCircleScalesOnlyTheCircle() {
        stage()
        compose.onNodeWithTag("tab-search").performTouchInput { down(center) }
        compose.waitForIdle()
        assertEquals(Glass.PressScale, pressScaleUnder("tab-search"), 0.005f)
        assertEquals(1f, pressScaleUnder("tab-pill"), 0.005f)
        compose.onNodeWithTag("tab-search").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(1f, pressScaleUnder("tab-search"), 0.005f)
    }

    @Test
    fun pressScaleStaysOneUnderReduceMotion() {
        val source = MutableInteractionSource()
        stage(source = source, reduce = true)
        compose.runOnUiThread { source.tryEmit(PressInteraction.Press(Offset.Zero)) }
        compose.waitForIdle()
        assertEquals(1f, pressScaleUnder("tab-pill"), 0.005f)
    }

    // ------------------------------------------------------ platter motion

    @Test
    fun platterSnapsUnderReduceMotion() {
        stage(reduce = true, freezeClock = true)
        selected = TabId.Settings
        compose.waitForIdle()
        // One frame later the platter is already at its target: snapTo
        // collapsed the whole spring — no slide happened.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithTag("tab-platter").assertPositionInRootIsEqualTo(166.dp, 795.dp)
    }

    @Test
    fun platterSlidesUnderFullMotion() {
        stage(freezeClock = true)
        selected = TabId.Settings
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        // Frame 1 recomposes and launches the effect, frame 2 is the spring's
        // start frame (play time 0), only frame 3 moves it; one more frame of
        // margin. Four frames of a 0.32 s spring cover ≈ 20–40 dp of 141.
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        // A few frames into the spring: the platter has left the Sessions
        // cell but is nowhere near the Settings cell yet.
        val platter = compose.onNodeWithTag("tab-platter").fetchSemanticsNode()
        val pill = compose.onNodeWithTag("tab-pill").fetchSemanticsNode()
        val density = pill.size.height / 62f
        val x = platter.positionInRoot.x / density
        assertTrue("platter should still be sliding (x = $x dp)", x > 25f && x < 150f)
    }

    // ------------------------------------------------------- the label font

    @Test
    fun tabLabelRendersAtTheSameSizeAtFontScale16() {
        compose.setContent {
            RobocoTheme {
                Column {
                    LabelAtDensity("label-1x", Density(3f, fontScale = 1f))
                    LabelAtDensity("label-16x", Density(3f, fontScale = 1.6f))
                }
            }
        }
        compose.waitForIdle()
        // fixed(10) renders at 10 dp whatever the font scale: iOS tab labels
        // do not grow with Dynamic Type.
        val h1 = compose.onNodeWithTag("label-1x").fetchSemanticsNode().size.height
        val h16 = compose.onNodeWithTag("label-16x").fetchSemanticsNode().size.height
        assertEquals(h1.toFloat(), h16.toFloat(), 1f)
    }

    /** The tab label exactly as [GlassTabBar]'s cells build it, at [density]. */
    @Composable
    private fun LabelAtDensity(tag: String, density: Density) {
        CompositionLocalProvider(LocalDensity provides density) {
            val typeScale = remember(density) { TypeScale.from(density) }
            val style: TextStyle = uiStyle(
                Face.SansSemibold,
                typeScale.fixed(GlassTabBarDefaults.LabelPt),
                RobocoTheme.palette.text,
            )
            Box(Modifier.testTag(tag)) {
                BasicText(TabId.Sessions.label, style = style, maxLines = 1, softWrap = false)
            }
        }
    }
}
