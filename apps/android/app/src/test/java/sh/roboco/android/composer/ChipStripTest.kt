package sh.roboco.android.composer

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.EdgeFade
import sh.roboco.android.design.Palette
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.session.ContextReading
import sh.roboco.android.session.contextChip
import sh.roboco.android.session.contextUsageCard
import java.util.Locale
import kotlin.math.abs

/**
 * The chip strip's behaviour (android-ui-parity #59): geometry, inner
 * geometry, tap routing, `canWrite`, the edge fades and the a11y nodes —
 * Robolectric + compose-ui-test at **mdpi** (`w400dp-h900dp-mdpi`, font
 * scale 1.0), where 1 dp = 1 px and the ticket's dp tables apply directly.
 *
 * Every harness mounts a [GlassMenuHost] (a ChipStrip without one throws —
 * its own test) and paints a plain backdrop so the chip paints are
 * readable pixel-wise. `Glass.forceFallback` is on for the whole class (the
 * golden convention): the menus draw the fallback fill, nothing here
 * depends on blur.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h900dp-mdpi")
class ChipStripTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeClock() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
    }

    @After
    fun restoreGlass() {
        Glass.forceFallback = false
    }

    // ------------------------------------------------------------- harness

    /** The strip under test on a flat backdrop inside a [GlassMenuHost]. */
    @Composable
    private fun Harness(
        chips: List<ComposerChip>,
        chipMenus: Map<String, ChipMenuProvider> = emptyMap(),
        canWrite: Boolean = true,
        onChipTap: (String) -> Unit = {},
        modifier: Modifier = Modifier,
        chipModifier: Modifier = Modifier,
        state: ScrollState = rememberScrollState(),
        appearance: Appearance = Appearance.Light,
        backdrop: Color = Color.White,
    ) {
        RobocoTheme(appearance) {
            GlassMenuHost(hazeState = rememberHazeState()) {
                Box(Modifier.fillMaxSize().background(backdrop)) {
                    ChipStrip(
                        chips = chips,
                        chipMenus = chipMenus,
                        canWrite = canWrite,
                        onChipTap = onChipTap,
                        modifier = modifier,
                        chipModifier = chipModifier,
                        state = state,
                    )
                }
            }
        }
    }

    /** The focus-recogniser harness of ticket 56: a parent counting taps that reach it. */
    @Composable
    private fun FocusHarness(
        chips: List<ComposerChip>,
        canWrite: Boolean,
        onTap: (String) -> Unit,
    ) {
        RobocoTheme(Appearance.Light) {
            GlassMenuHost(hazeState = rememberHazeState()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.White)
                        .pointerInput(Unit) { detectTapGestures { focusCount++ } },
                ) {
                    ChipStrip(
                        chips = chips,
                        chipMenus = emptyMap(),
                        canWrite = canWrite,
                        onChipTap = onTap,
                        // Wider than its single short chip, so the empty
                        // part of the strip is tappable too.
                        modifier = Modifier.width(300.dp),
                    )
                }
            }
        }
    }

    private var focusCount = 0

    private fun node(tag: String): SemanticsNode =
        compose.onNodeWithTag(tag).fetchSemanticsNode()

    private fun unmergedNode(tag: String): SemanticsNode =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()

    /** The `chip-icon` node whose bounds sit inside [chip] (unmerged tree). */
    private fun iconInside(chip: SemanticsNode): SemanticsNode {
        val icons = compose.onAllNodesWithTag("chip-icon", useUnmergedTree = true).fetchSemanticsNodes()
        val inside = icons.filter { icon ->
            icon.positionInRoot.x >= chip.positionInRoot.x &&
                icon.positionInRoot.y >= chip.positionInRoot.y &&
                icon.positionInRoot.x + icon.size.width <= chip.positionInRoot.x + chip.size.width + 1f &&
                icon.positionInRoot.y + icon.size.height <= chip.positionInRoot.y + chip.size.height + 1f
        }
        val tag = chip.config[SemanticsProperties.TestTag] ?: "?"
        assertTrue("no chip-icon inside the $tag chip", inside.isNotEmpty())
        return inside.single()
    }

    private fun pixels(tag: String): PixelMap =
        compose.onNodeWithTag(tag).captureToImage().toPixelMap()

    // --------------------------------------------------------- geometry

    @Test
    fun theStripIs34DpTallChips31High5Centred6ApartAndFlush() {
        compose.setContent { Harness(chips = chipFixtureSession()) }
        compose.waitForIdle()

        val strip = node("composer-chip-strip")
        assertEquals("the strip is 34 dp tall", 34f, strip.size.height.toFloat(), 0.5f)

        val chips = listOf("model", "effort", "branch")
            .map { id -> node("composer-chip-$id") }
            .sortedBy { it.positionInRoot.x }
        for (chip in chips) {
            val height = chip.size.height.toFloat()
            assertEquals("a sans chip is 31.55 dp tall (was $height)", 31.55f, height, 0.5f)
            val centred = chip.positionInRoot.y - strip.positionInRoot.y
            assertEquals("chip centred in the 34 dp row", (34f - height) / 2f, centred, 0.5f)
        }
        assertEquals(
            "chip gap is 6 dp",
            6f,
            chips[1].positionInRoot.x - chips[0].positionInRoot.x - chips[0].size.width,
            0.5f,
        )
        assertEquals(
            "chip gap is 6 dp",
            6f,
            chips[2].positionInRoot.x - chips[1].positionInRoot.x - chips[1].size.width,
            0.5f,
        )
        assertEquals(
            "the first chip is flush with the strip's start",
            0f,
            chips[0].positionInRoot.x - strip.positionInRoot.x,
            0.5f,
        )
    }

    @Test
    fun thePrChipIs3025DpTall() {
        compose.setContent { Harness(chips = chipFixturePrStrips()[0]) }
        compose.waitForIdle()
        // Tolerance 1 (not 0.5): at mdpi 1 dp = 1 px and Compose reports a
        // text layout's height as ceil(px), so the 16.25 dp mono line lays
        // out as 17 px and the chip as 31 px — the closest whole pixel
        // above 30.25. (The sans chip's 17.55 line rounds to 18 → 32, which
        // happens to land within 0.5 of 31.55.)
        assertEquals(
            "a pr chip is 30.25 dp tall (GeistMono 12.5 line + padding)",
            30.25f,
            node("composer-chip-pr").size.height.toFloat(),
            1f,
        )
    }

    @Test
    fun theBranchChipInnerGeometryMatchesTheSpec() {
        compose.setContent { Harness(chips = chipFixtureSession()) }
        compose.waitForIdle()

        val chip = unmergedNode("composer-chip-branch")
        val icon = iconInside(chip)
        assertEquals("icon left edge 11 dp from the chip's left", 11f, icon.positionInRoot.x - chip.positionInRoot.x, 0.5f)
        assertEquals("the branch icon is 13 dp wide", 13f, icon.size.width.toFloat(), 0.5f)
        assertEquals("the branch icon is 13 dp tall", 13f, icon.size.height.toFloat(), 0.5f)

        val title = compose.onNodeWithText("ios-rewrite", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("title starts 6 dp after the icon", 6f, title.positionInRoot.x - (icon.positionInRoot.x + icon.size.width), 0.5f)
        assertEquals(
            "title ends 11 dp before the chip's right edge",
            11f,
            (chip.positionInRoot.x + chip.size.width) - (title.positionInRoot.x + title.size.width),
            0.5f,
        )
    }

    @Test
    fun everyLeadingKindKeepsItsBoxSide() {
        compose.setContent { Harness(chips = chipFixtureSession()) }
        compose.waitForIdle()

        // Symbol: an 11.5 pt SF Symbol in ticket 06's 1.2 × pt box.
        val symbol = iconInside(unmergedNode("composer-chip-effort"))
        assertEquals("the effort symbol box is 13.8 dp", 13.8f, symbol.size.width.toFloat(), 0.5f)
        assertEquals("the effort symbol box is 13.8 dp", 13.8f, symbol.size.height.toFloat(), 0.5f)

        // Brand: the harness mark is 13 dp.
        val brand = iconInside(unmergedNode("composer-chip-model"))
        assertEquals("the brand mark box is 13 dp", 13f, brand.size.width.toFloat(), 0.5f)
        assertEquals("the brand mark box is 13 dp", 13f, brand.size.height.toFloat(), 0.5f)
    }

    @Test
    fun theProjectTileIs14Dp() {
        compose.setContent { Harness(chips = chipFixtureNewSession()) }
        compose.waitForIdle()
        val tile = iconInside(unmergedNode("composer-chip-project"))
        assertEquals("the project tile box is 14 dp", 14f, tile.size.width.toFloat(), 0.5f)
        assertEquals("the project tile box is 14 dp", 14f, tile.size.height.toFloat(), 0.5f)
    }

    @Test
    fun thePrChipUsesA12DpIconAndA5DpGap() {
        compose.setContent { Harness(chips = chipFixturePrStrips()[0]) }
        compose.waitForIdle()
        val chip = unmergedNode("composer-chip-pr")
        val icon = iconInside(chip)
        assertEquals("the PR icon box is 12 dp", 12f, icon.size.width.toFloat(), 0.5f)
        val title = compose.onNodeWithText("1234", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(
            "the PR title starts 5 dp after the icon",
            5f,
            title.positionInRoot.x - (icon.positionInRoot.x + icon.size.width),
            0.5f,
        )
    }

    @Test
    fun aChipWithOnlyAnUnknownSymbolHasNoIconAndNoGap() {
        compose.setContent {
            Harness(chips = listOf(ComposerChip(id = "model", title = "Opus 4.5", symbol = "no.such.symbol")))
        }
        compose.waitForIdle()
        val chip = node("composer-chip-model")
        val title = compose.onNodeWithText("Opus 4.5", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("no icon and no gap: the title starts at the 11 dp padding", 11f, title.positionInRoot.x - chip.positionInRoot.x, 0.5f)
        assertTrue(
            "an unresolvable SF name leaves no icon node",
            compose.onAllNodesWithTag("chip-icon", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun aVeryLongTitleLaysOutAtFullWidthAndTheStripScrolls() {
        val long = "feature/a-very-long-branch-name-that-does-not-fit"
        val state = ScrollState(0)
        compose.setContent {
            Harness(
                chips = listOf(ComposerChip(id = "branch", title = long, icon = ChipIcon.Branch)),
                state = state,
                modifier = Modifier.width(200.dp),
            )
        }
        compose.waitForIdle()

        val chip = node("composer-chip-branch")
        val title = compose.onNodeWithText(long, useUnmergedTree = true).fetchSemanticsNode()
        val textWidth = title.size.width.toFloat()
        assertTrue("the title is laid out at full width (was $textWidth px)", textWidth > 300f)
        assertEquals(
            "chip width = 11 + 13 (icon) + 6 (gap) + title + 11",
            41f + textWidth,
            chip.size.width.toFloat(),
            1f,
        )
        assertTrue("the strip scrolls (maxValue ${state.maxValue})", state.maxValue > 0)
    }

    /**
     * android-next #01: the always-present context chip is last in the
     * strip's order, so at a 360 dp strip the model and effort chips stay
     * fully in view and only the tail scrolls (the strip's existing rule).
     */
    @Test
    fun anAlwaysPresentContextChipNeverPushesModelOrEffortOffAt360Dp() {
        val chips = chipFixtureSession() +
            ComposerChip(id = "pr", title = "1234", icon = ChipIcon.PullRequest, tint = ChipTint.PrOpen) +
            contextChip(ContextReading.Measured(tokens = 1_048_000uL, window = 1_048_576uL), Locale.US)
        compose.setContent { Harness(chips = chips, modifier = Modifier.width(360.dp)) }
        compose.waitForIdle()

        val strip = node("composer-chip-strip")
        val stripEnd = strip.positionInRoot.x + strip.size.width
        assertEquals("the strip is 360 dp", 360f, strip.size.width.toFloat(), 0.5f)
        for (id in listOf("model", "effort")) {
            val chip = node("composer-chip-$id")
            assertTrue(
                "the $id chip ends inside the strip (${chip.positionInRoot.x + chip.size.width} <= $stripEnd)",
                chip.positionInRoot.x + chip.size.width <= stripEnd,
            )
        }
    }

    @Test
    fun atFontScale2TheChipKeepsItsFixedHeight() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Harness(chips = chipFixtureSession())
            }
        }
        compose.waitForIdle()
        assertEquals(
            "fixed sizes never follow the font scale",
            31.55f,
            node("composer-chip-branch").size.height.toFloat(),
            0.5f,
        )
    }

    // -------------------------------------------------------- tap routing

    @Test
    fun aProviderThatReturnsAMenuOpensItAnchoredToTheChipAndSkipsOnChipTap() {
        var provided = 0
        var taps = 0
        compose.setContent {
            Harness(
                chips = chipFixtureSession(),
                chipMenus = mapOf("model" to { provided++; chipModelMenu() }),
                onChipTap = { taps++ },
            )
        }
        compose.waitForIdle()
        assertEquals("the provider never runs during composition", 0, provided)

        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("composer-chip-model").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertEquals("the provider ran exactly once", 1, provided)
        compose.onNodeWithTag("glass-menu").assertExists()
        assertEquals("onChipTap is not called when a menu opens", 0, taps)

        // The menu is anchored to the chip: ticket 15's cover placement
        // starts the panel at the anchor's top/left edges (clamped by the
        // 12 dp margin), so the panel grows out of the chip.
        val chip = node("composer-chip-model")
        val panel = node("glass-menu")
        assertTrue(
            "the panel starts at the chip's top (was ${panel.positionInRoot.y} vs ${chip.positionInRoot.y})",
            panel.positionInRoot.y >= chip.positionInRoot.y - 2f &&
                panel.positionInRoot.y <= chip.positionInRoot.y + 16f,
        )
        assertTrue(
            "the panel starts at the chip's left (was ${panel.positionInRoot.x} vs ${chip.positionInRoot.x})",
            panel.positionInRoot.x >= chip.positionInRoot.x - 2f &&
                panel.positionInRoot.x <= chip.positionInRoot.x + 16f,
        )
    }

    @Test
    fun aProviderThatReturnsNullFallsBackToOnChipTap() {
        var provided = 0
        val taps = mutableListOf<String>()
        compose.setContent {
            Harness(
                chips = chipFixtureSession(),
                chipMenus = mapOf("model" to { provided++; null }),
                onChipTap = { taps.add(it) },
            )
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("composer-chip-model").performClick()
        compose.waitForIdle()
        assertEquals(1, provided)
        assertEquals("onChipTap fires for the chip id", listOf("model"), taps)
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
    }

    @Test
    fun aChipWithoutAProviderCallsOnChipTap() {
        val taps = mutableListOf<String>()
        compose.setContent {
            Harness(chips = chipFixtureSession(), onChipTap = { taps.add(it) })
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("composer-chip-effort").performClick()
        compose.waitForIdle()
        assertEquals(listOf("effort"), taps)
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
    }

    @Test
    fun theProviderRunsOncePerTapAndNeverDuringRecomposition() {
        var provided = 0
        var chips by mutableStateOf(chipFixtureSession())
        compose.setContent {
            Harness(
                chips = chips,
                chipMenus = mapOf("model" to { provided++; chipModelMenu() }),
                onChipTap = {},
            )
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = true

        repeat(3) { round ->
            compose.onNodeWithTag("composer-chip-model").performClick()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            assertEquals("round ${round + 1}: one invocation per tap", round + 1, provided)
            compose.onNodeWithTag("glass-menu").assertExists()
            Espresso.pressBack()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            compose.onNodeWithTag("glass-menu").assertDoesNotExist()
        }

        // Recomposition alone (a new chips list, like the session's every
        // render) never evaluates the provider.
        chips = chips + ComposerChip(id = "context", title = "62% context", symbol = "circle.lefthalf.filled")
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        assertEquals(3, provided)
    }

    /**
     * android-next #01: the context chip's tap opens the read-only details
     * card with the grouped numbers; a tap on a card row keeps it open (the
     * panel swallows it), a tap outside closes it. The chip reads its
     * numbers to TalkBack.
     */
    @Test
    fun theContextChipOpensTheDetailsCardAndATapOutsideClosesIt() {
        val reading = ContextReading.Measured(tokens = 5_417uL, window = 1_048_576uL)
        val taps = mutableListOf<String>()
        compose.setContent {
            Harness(
                // Alone, so the chip is fully inside the strip's viewport.
                chips = listOf(contextChip(reading, Locale.US)),
                chipMenus = mapOf("context" to { contextUsageCard(reading, Locale.US) }),
                onChipTap = { taps.add(it) },
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("composer-chip-context")
            .assertTextContains("1% context")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Context 1 percent used, 5,417 of 1,048,576 tokens"),
                ),
            )

        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("composer-chip-context").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithTag("glass-menu").assertExists()
        compose.onNodeWithText("Context window").assertExists()
        compose.onNodeWithText("5,417 / 1,048,576 tokens").assertExists()
        compose.onNodeWithText("1% used").assertExists()
        compose.onNodeWithText("1,043,159 tokens remaining").assertExists()
        assertEquals("the card is the chip's menu, not onChipTap", emptyList<String>(), taps)

        // A read-only row is no tap target: the card stays open.
        compose.onNodeWithTag("context-card-remaining").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithTag("glass-menu").assertExists()

        // A tap outside (the strip sits at the top; the corner is bare scrim).
        compose.onNodeWithTag("glass-menu-scrim").performTouchInput {
            click(Offset(width - 10f, height - 10f))
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
    }

    // ----------------------------------------------------------- canWrite

    @Test
    fun aDisabledChipIsNotEnabledAndNeitherProviderNorOnChipTapFire() {
        var provided = 0
        val taps = mutableListOf<String>()
        compose.setContent {
            Harness(
                chips = chipFixtureSession(),
                chipMenus = mapOf("model" to { provided++; chipModelMenu() }),
                canWrite = false,
                onChipTap = { taps.add(it) },
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("composer-chip-model").assertIsNotEnabled()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("composer-chip-model").performClick()
        compose.waitForIdle()
        assertEquals("the menu provider is not invoked", 0, provided)
        assertEquals("onChipTap is not invoked", 0, taps.size)
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
    }

    @Test
    fun disabledChipTapsAreConsumedAndNeverFocusTheComposer() {
        val taps = mutableListOf<String>()
        focusCount = 0
        compose.setContent {
            FocusHarness(
                chips = listOf(ComposerChip(id = "branch", title = "main", icon = ChipIcon.Branch)),
                canWrite = false,
                onTap = { taps.add(it) },
            )
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = true

        compose.onNodeWithTag("composer-chip-branch").performClick()
        compose.waitForIdle()
        assertEquals("the disabled chip's tap is consumed (no focus)", 0, focusCount)
        assertEquals(0, taps.size)

        // The empty part of the strip (the strip is 300 dp, the chip ~70):
        // the tap reaches the parent's focus recogniser.
        compose.onNodeWithTag("composer-chip-strip").performTouchInput { click(Offset(280f, 17f)) }
        compose.waitForIdle()
        assertEquals("tapping empty strip space focuses the composer", 1, focusCount)
        assertEquals(0, taps.size)
    }

    @Test
    fun anEnabledChipTapAlsoNeverFocusesTheComposer() {
        val taps = mutableListOf<String>()
        focusCount = 0
        compose.setContent {
            FocusHarness(
                chips = listOf(ComposerChip(id = "branch", title = "main", icon = ChipIcon.Branch)),
                canWrite = true,
                onTap = { taps.add(it) },
            )
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("composer-chip-branch").performClick()
        compose.waitForIdle()
        assertEquals(listOf("branch"), taps)
        assertEquals("chip taps never focus the composer", 0, focusCount)
    }

    @Test
    fun anEmptyStripIsA34DpBoxWithNoFadeThatDoesNotConsumeTaps() {
        focusCount = 0
        val state = ScrollState(0)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                GlassMenuHost(hazeState = rememberHazeState()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.White)
                            .pointerInput(Unit) { detectTapGestures { focusCount++ } },
                    ) {
                        ChipStrip(
                            chips = emptyList(),
                            chipMenus = emptyMap(),
                            canWrite = true,
                            onChipTap = {},
                            modifier = Modifier.width(300.dp),
                            state = state,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = true

        assertEquals("the empty strip keeps its 34 dp height", 34f, node("composer-chip-strip").size.height.toFloat(), 0.5f)
        assertEquals("no content, no overflow, no fade", 0, state.maxValue)

        // Taps on the empty strip are not consumed by it: they reach the
        // parent's focus recogniser, exactly like the space between chips.
        compose.onNodeWithTag("composer-chip-strip").performTouchInput { click(Offset(150f, 17f)) }
        compose.waitForIdle()
        assertEquals("an empty strip's tap focuses the composer", 1, focusCount)
    }

    // ------------------------------------------------------- chipModifier

    @Test
    fun chipModifierReachesEveryChipAndNotTheStrip() {
        compose.setContent {
            Harness(chips = chipFixtureSession(), chipModifier = Modifier.testTag("probe"))
        }
        compose.waitForIdle()

        val probes = compose.onAllNodesWithTag("probe", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("one probe node per chip", 3, probes.size)
        val strip = node("composer-chip-strip")
        for (probe in probes) {
            val height = probe.size.height.toFloat()
            assertEquals("the probe covers a chip, not the 34 dp strip", 31.55f, height, 0.5f)
            assertEquals(
                "the chip is centred in the strip",
                (34f - height) / 2f,
                probe.positionInRoot.y - strip.positionInRoot.y,
                0.5f,
            )
        }
        val xs = probes.map { it.positionInRoot.x }
        assertTrue("the three chips sit at distinct positions ($xs)", xs.distinct().size == 3)
    }

    // ------------------------------------------------------------ no host

    @Test
    fun withoutAGlassMenuHostTheStripThrows() {
        try {
            compose.setContent {
                RobocoTheme(Appearance.Light) {
                    Box(Modifier.fillMaxSize()) {
                        ChipStrip(chipFixtureSession(), emptyMap(), canWrite = true, onChipTap = {})
                    }
                }
            }
            compose.waitForIdle()
            fail("a ChipStrip without a GlassMenuHost must throw")
        } catch (expected: IllegalStateException) {
            var cause: Throwable? = expected
            var found = false
            while (cause != null) {
                if (cause.message?.contains("GlassMenuHost") == true) found = true
                cause = cause.cause
            }
            assertTrue("expected the GlassMenuHost error, got: ${expected.message}", found)
        }
    }

    // ---------------------------------------------------------------- a11y

    @Test
    fun theChipMergesItsTitleAndNamesItsClickAction() {
        compose.setContent {
            Harness(
                chips = chipFixtureSession(),
                chipMenus = mapOf("model" to { chipModelMenu() }),
            )
        }
        compose.waitForIdle()

        compose.onNodeWithTag("composer-chip-effort").assertTextContains("High")
        val withMenu = node("composer-chip-model").config[SemanticsActions.OnClick]
        assertNotNull("the chip is a button", withMenu)
        assertEquals("a chip with a menu offers 'Show menu'", "Show menu", withMenu?.label)
        val withoutMenu = node("composer-chip-effort").config[SemanticsActions.OnClick]
        assertNull("a chip without a menu has no click label", withoutMenu?.label)

        // The strip keeps horizontalScroll's scroll semantics.
        compose.onNodeWithTag("composer-chip-strip")
            .assert(SemanticsMatcher("has a scroll action") { it.config.contains(SemanticsActions.ScrollBy) })
    }

    // ----------------------------------------------------------- ink pixels

    /**
     * The disabled ink is `tertiary` (#97979F light / #6B6B72 dark); an
     * enabled chip's ink is `text` (#27272C / #E8E8EA). The effort chip
     * (gauge symbol + "High") holds full-coverage ink pixels, so the
     * extreme pixel of the chip pins the ink in both appearances: the
     * darkest in light (fill over white ≈ 239), the brightest in dark
     * (fill over the dark backdrop ≈ 17).
     */
    @Test
    fun disabledChipsPaintTheirInkTertiary() {
        // One setContent per test (a second one throws "has already set
        // content"): the four cases are state flips of a single harness.
        var canWrite by mutableStateOf(true)
        var appearance by mutableStateOf(Appearance.Light)
        var backdrop by mutableStateOf(Color.White)
        compose.mainClock.autoAdvance = true
        compose.setContent {
            Harness(chips = chipFixtureSession(), canWrite = canWrite, appearance = appearance, backdrop = backdrop)
        }
        compose.waitForIdle()
        assertExtremePixel("composer-chip-effort", darkest = true, around = listOf(39f, 39f, 44f))

        canWrite = false
        compose.waitForIdle()
        assertExtremePixel("composer-chip-effort", darkest = true, around = listOf(151f, 151f, 159f))

        canWrite = true
        appearance = Appearance.Dark
        backdrop = Palette.Dark.background
        compose.waitForIdle()
        assertExtremePixel("composer-chip-effort", darkest = false, around = listOf(232f, 232f, 234f))

        canWrite = false
        compose.waitForIdle()
        assertExtremePixel("composer-chip-effort", darkest = false, around = listOf(107f, 107f, 114f))
    }

    /**
     * The claude mark keeps `#D97757` enabled and disabled; the project
     * tile keeps its violet tone in both; the codex mark takes the ink
     * (`text`, or `tertiary` when disabled).
     */
    @Test
    fun originalColourIconsKeepTheirColoursInBothStates() {
        // One setContent per test (a second one throws "has already set
        // content"): the fixtures and states are flips of a single harness.
        var chips by mutableStateOf(chipFixtureSession())
        var canWrite by mutableStateOf(true)
        compose.mainClock.autoAdvance = true
        compose.setContent { Harness(chips = chips, canWrite = canWrite) }
        compose.waitForIdle()
        for (enabled in listOf(true, false)) {
            canWrite = enabled
            // The Claude mark (fixture A, "model"): a #D97757 pixel survives.
            chips = chipFixtureSession()
            compose.waitForIdle()
            assertHasPixelNear("composer-chip-model", listOf(217f, 119f, 87f), 12f)

            // The codex mark (fixture B strip 1, "model"): takes the ink.
            chips = chipFixturePrStrips()[0]
            compose.waitForIdle()
            if (enabled) {
                assertExtremePixel("composer-chip-model", darkest = true, around = listOf(39f, 39f, 44f))
            } else {
                assertExtremePixel("composer-chip-model", darkest = true, around = listOf(151f, 151f, 159f))
            }

            // The project tile (fixture D, "project"): a saturated violet
            // pixel (b > r > g) survives the disabled state — a tinted
            // template icon would have gone grey.
            chips = chipFixtureNewSession()
            compose.waitForIdle()
            assertHasVioletPixel("composer-chip-project")
        }
    }

    // --------------------------------------------------------- edge fades

    @Test
    fun aFittingStripHasNoMask() {
        val state = ScrollState(0)
        compose.setContent { Harness(chips = chipFixtureSession(), state = state) }
        compose.waitForIdle()
        assertEquals("fixture A fits: no overflow, no mask", 0, state.maxValue)
    }

    @Test
    fun theOverflowStripFadesItsEndAtRestAndItsStartAtTheEnd() {
        val state = ScrollState(0)
        compose.setContent {
            Harness(chips = chipFixtureNewSession(), state = state, modifier = Modifier.width(240.dp))
        }
        compose.waitForIdle()
        assertTrue("fixture D overflows a 240 dp strip (maxValue ${state.maxValue})", state.maxValue > 0)

        val rest = pixels("composer-chip-strip")
        // Row centre y = 17: the first chip's leading padding (fill over
        // white ≈ 239) is solid at the very start edge.
        assertFillPixel(rest, 0, 17, "the start edge is solid at rest")
        // The last 24 dp ramp: the far end is pure backdrop.
        assertFadedPixel(rest, 239, 17, "the end fade at rest")

        compose.runOnIdle { runBlocking { state.scrollTo(state.maxValue) } }
        compose.waitForIdle()
        val end = pixels("composer-chip-strip")
        assertFadedPixel(end, 0, 17, "the start fade at the end")
        assertFillPixel(end, 239, 17, "the end edge is solid at the end")
    }

    @Test
    fun theTwelveDpPartialFadeIsHalfRamped() {
        val state = ScrollState(12)
        compose.setContent {
            Harness(chips = chipFixtureNewSession(), state = state, modifier = Modifier.width(240.dp))
        }
        compose.waitForIdle()
        assertTrue(state.maxValue > 0)
        assertEquals(12, state.value)
        // The mask the strip draws for this state (ticket 10's pure math):
        // a 12 px start ramp and the full 24 px end fade.
        val (start, end) = EdgeFade.scrollFades(state.value.toFloat(), state.maxValue.toFloat(), 24f)
        assertEquals(12f, start, 0.5f)
        assertEquals(24f, end, 0.5f)
    }

    // --------------------------------------------------------- pixel helpers

    private fun channels(pixel: androidx.compose.ui.graphics.Color): FloatArray =
        floatArrayOf(pixel.red * 255f, pixel.green * 255f, pixel.blue * 255f)

    private fun assertFillPixel(image: PixelMap, x: Int, y: Int, where: String) {
        // controlFill (#27272C @ 0x13) over white ≈ (239, 239, 239).
        val p = channels(image[x, y])
        for (channel in p) {
            assertTrue("$where: expected the chip fill over white, was ${p.contentToString()}", channel in 232f..246f)
        }
    }

    private fun assertFadedPixel(image: PixelMap, x: Int, y: Int, where: String) {
        val p = channels(image[x, y])
        for (channel in p) {
            assertTrue(
                "$where: expected a fully faded (white) pixel, was ${p.contentToString()}",
                channel >= 250f,
            )
        }
    }

    /** The chip's extreme (darkest/brightest) pixel matches [around] per channel. */
    private fun assertExtremePixel(tag: String, darkest: Boolean, around: List<Float>) {
        val map = pixels(tag)
        assertTrue("no pixels captured under $tag", map.width > 0 && map.height > 0)
        var extremeSum = if (darkest) Float.MAX_VALUE else Float.MIN_VALUE
        var extreme = floatArrayOf(0f, 0f, 0f)
        for (y in 0 until map.height) {
            for (x in 0 until map.width) {
                val p = channels(map[x, y])
                val sum = p[0] + p[1] + p[2]
                if (darkest && sum < extremeSum || !darkest && sum > extremeSum) {
                    extremeSum = sum
                    extreme = p
                }
            }
        }
        for (i in 0..2) {
            assertTrue(
                "channel $i of the ${if (darkest) "darkest" else "brightest"} pixel: expected ≈${around[i]}, was ${extreme[i]}",
                abs(extreme[i] - around[i]) < 16f,
            )
        }
    }

    private fun assertHasPixelNear(tag: String, expected: List<Float>, tolerance: Float) {
        val map = pixels(tag)
        var best = Float.MAX_VALUE
        var bestPixel = floatArrayOf(0f, 0f, 0f)
        for (y in 0 until map.height) {
            for (x in 0 until map.width) {
                val p = channels(map[x, y])
                val distance = abs(p[0] - expected[0]) + abs(p[1] - expected[1]) + abs(p[2] - expected[2])
                if (distance < best) {
                    best = distance
                    bestPixel = p
                }
            }
        }
        assertTrue(
            "no pixel within $tolerance per channel of $expected (best ${bestPixel.contentToString()})",
            best <= 3f * tolerance,
        )
    }

    /** The project tile's own tone: a saturated pixel with blue > red > green. */
    private fun assertHasVioletPixel(tag: String) {
        val map = pixels(tag)
        var found = false
        for (y in 0 until map.height) {
            for (x in 0 until map.width) {
                val p = channels(map[x, y])
                if (p[2] - p[1] > 40f && p[2] - p[0] > 30f && p[0] - p[1] > 10f) {
                    found = true
                }
            }
        }
        assertTrue("no saturated violet (project-tone) pixel in the $tag chip", found)
    }
}
