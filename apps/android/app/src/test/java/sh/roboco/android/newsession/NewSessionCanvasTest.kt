package sh.roboco.android.newsession

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.composer.ComposerBar
import sh.roboco.android.composer.ComposerController
import sh.roboco.android.composer.ComposerMorphState
import sh.roboco.android.composer.DeliveryMode
import sh.roboco.android.composer.Drafts
import sh.roboco.android.composer.FakePrefsStore
import sh.roboco.android.composer.SendResult
import sh.roboco.android.composer.StagedImage
import sh.roboco.android.composer.rememberComposerController
import sh.roboco.android.composer.rememberComposerMorphState
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.NoFeedback
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassMenuHost
import uniffi.roboco_core.FileMatch

/**
 * The New Session canvas's behaviour (android-ui-parity #93) — Robolectric
 * + compose-ui-test at xxhdpi (3 px/dp) on the ticket's `w411dp-h857dp`
 * phone, with fixed [CanvasInsets] and a fake wallpaper whose draw records
 * the cutout rect (the same-frame IME-tracking check).
 *
 * Covered: the geometry of the ticket's worked case A (composer
 * (12, 691, 399, 825), hero centred in 52…671, wallpaper 411 × 617.04),
 * the IME case B (composer bottom 513, wallpaper unchanged), the IME
 * animation tracking (the cutout equals the composer bounds on every
 * frame, and the wallpaper-local composer rect — 21's consumer contract),
 * 17's nav bar + ✕, the embedded row, the fixed type sizes under font
 * scale 1.3, the mark's harness redraw, the tap-to-dismiss rules, the
 * focus-on-appear settle, and the screen's wiring: [bindNewSessionComposer]
 * (mention search, attach null, the configuration) and
 * [NewSessionScreenBody] over a fake model (seeded text, placeholder, the
 * draft save on dispose).
 *
 * The screen entry (`rememberNewSessionModel`) is 92's app-graph wiring
 * and stays untested here: the built-in model catalog is FFI, so the page
 * runs over a fake [ModelCatalogSource] (92's model tests use the same
 * seam).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h857dp-xxhdpi")
class NewSessionCanvasTest {

    @get:Rule
    val compose = createComposeRule()

    // ── the shared fixture state (one mount per test) ───────────────────

    private val harnessState = mutableStateOf("claude-code")
    private val insetsState = mutableStateOf(CanvasInsets(0f, 0f, 0f, 24f, 0f))
    private val padState = mutableStateOf(0.dp)

    private var canvasState: NewSessionCanvasState? = null
    private var canvasMorph: ComposerMorphState? = null
    private var wallpaperCoords: LayoutCoordinates? = null

    /** The cutout rects the fake wallpaper's draw saw, one per frame. */
    private val recordedCutouts = mutableListOf<Rect>()

    private var closeCount = 0
    private var rowClicks = 0

    @Before
    fun resetFixture() {
        canvasState = null
        canvasMorph = null
        wallpaperCoords = null
        recordedCutouts.clear()
        closeCount = 0
        rowClicks = 0
        harnessState.value = "claude-code"
        insetsState.value = CanvasInsets(0f, 0f, 0f, 24f, 0f)
        padState.value = 0.dp
    }

    // ── the mount ───────────────────────────────────────────────────────

    /**
     * The canvas over the stand-in composer (134 dp — the ticket's fixture
     * height), the recording wallpaper and, for the tap tests, the real
     * [ComposerBar] and a clickable fake mention row 8 dp above it.
     *
     * @param insets the fixed insets this mount starts from (the state
     *   stays live for the IME-tracking test)
     * @param hostPad simulates 31's imePadding: the constraints shrink
     *   while the insets say the same IME was consumed (`imeAbove`)
     * @param width the "Narrow" toggle: a 200 dp page gives the 2-line
     *   headline
     */
    private fun mountSheetCanvas(
        insets: CanvasInsets = insetsState.value,
        hostPad: Dp = padState.value,
        realComposer: Boolean = false,
        fakeRow: Boolean = false,
        width: Dp? = null,
    ) {
        insetsState.value = insets
        padState.value = hostPad
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                // The real ComposerBar's attach button needs a GlassMenuHost
                // above it (rememberGlassMenuState throws without one;
                // production mounts it in RootHosts).
                GlassMenuHost { SheetCanvasContent(width, realComposer, fakeRow) }
            }
        }
        compose.waitForIdle()
    }

    /** [mountSheetCanvas]'s content, inside the theme and the menu host. */
    @Composable
    private fun SheetCanvasContent(width: Dp?, realComposer: Boolean, fakeRow: Boolean) {
        val sizeModifier = if (width != null) {
            Modifier.width(width).fillMaxSize()
        } else {
            Modifier.fillMaxSize()
        }
        Box(Modifier.then(sizeModifier).padding(bottom = padState.value)) {
            val controller = rememberComposerController()
            remember(controller) {
                controller.alwaysCard = true
                controller.placeholder = "Describe the task"
                true
            }
            val morph = rememberComposerMorphState(
                alwaysCard = true,
                hasImages = { controller.images.isNotEmpty() },
            )
            canvasMorph = morph
            val state = remember { NewSessionCanvasState() }
            canvasState = state
            NewSessionCanvas(
                harness = harnessState.value,
                embedded = false,
                onClose = { closeCount++ },
                onBackgroundTap = { morph.blur() },
                composer = {
                    if (fakeRow) FakeMentionRow()
                    if (realComposer) {
                        ComposerBar(controller, morph, Modifier.testTag("new-session-composer"))
                    } else {
                        StandInComposer()
                    }
                },
                state = state,
                insets = insetsState.value,
                wallpaper = { m, c -> FakeWallpaper(m, c) },
            )
        }
    }

    /** The ticket's stand-in composer: full width, 134 dp tall. */
    @Composable
    private fun StandInComposer() {
        Box(Modifier.fillMaxWidth().height(134.dp).testTag("new-session-composer"))
    }

    /**
     * A clickable row 8 dp above the composer's top (86's card row
     * stand-in): composed inside the canvas's composer slot, so it is a
     * descendant of the tap detector — a pick must not dismiss.
     */
    @Composable
    private fun FakeMentionRow() {
        Box(
            Modifier
                .offset(y = -64.dp) // a 56 dp row + the 8 dp gap above the composer
                .fillMaxWidth()
                .height(56.dp)
                .background(RowColor)
                .testTag("fake-row")
                .clickable { rowClicks++ },
        )
    }

    /** 21's core stand-in: records every cutout its draw sees. */
    @Composable
    private fun FakeWallpaper(m: Modifier, cutout: () -> Rect?) {
        Box(
            m
                .testTag("wallpaper")
                .onGloballyPositioned { wallpaperCoords = it }
                .drawBehind { cutout()?.let { r -> recordedCutouts += r } },
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private val density: Float get() = compose.density.density

    private fun bounds(tag: String): DpRect = compose.onNodeWithTag(tag).getBoundsInRoot()

    /** dp comparison at ±1 px (the ticket's tolerance). */
    private fun assertDp(what: String, expected: Float, actual: Dp, tolerancePx: Float = 1f) {
        val e = expected * density
        val a = actual.value * density
        assertTrue("$what: $actual vs ${expected}dp (±$tolerancePx px)", abs(e - a) <= tolerancePx)
    }

    private fun assertPx(what: String, expected: Float, actual: Float) {
        assertTrue("$what: $actual px vs $expected px (±1)", abs(expected - actual) <= 1f)
    }

    /** One text node's [TextLayoutResult] (the GetTextLayoutResult action). */
    private fun textLayout(tag: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single()
    }

    /** True when the node's pixels contain the given colour (±10/channel). */
    private fun hasColor(tag: String, color: Color): Boolean {
        val map: PixelMap = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        for (x in 0 until map.width) {
            for (y in 0 until map.height) {
                val p = map[x, y]
                if (
                    abs(p.red - color.red) < 0.04f &&
                    abs(p.green - color.green) < 0.04f &&
                    abs(p.blue - color.blue) < 0.04f
                ) {
                    return true
                }
            }
        }
        return false
    }

    // ── geometry (worked case A) ────────────────────────────────────────

    @Test
    fun theComposerSitsTwelveInEightUpFromTheInsetAndTheHeroIsCentredAboveIt() {
        mountSheetCanvas()
        val composer = bounds("new-session-composer")
        assertDp("composer left", 12f, composer.left)
        assertDp("composer top", 691f, composer.top)
        assertDp("composer right", 399f, composer.right)
        assertDp("composer bottom", 825f, composer.bottom)

        // The hero is centred in the free space 52…(691 − 20).
        val hero = bounds("new-session-hero")
        assertDp("hero top (1 line)", 323.2f, hero.top)
        assertDp("hero bottom", 399.8f, hero.bottom)
        assertDp("hero centred in 52…671", (52f + 671f) / 2f, (hero.top + hero.bottom) / 2f)
        // The headline node sits inside the hero column, clear of the
        // composer: bottom ≤ composerTop − 20 (the iOS UI test's rule).
        val headline = bounds("new-session-headline")
        assertTrue("headline clear of the composer", headline.bottom.value <= composer.top.value - 20f)

        // The wallpaper: 411 × 617.04 at the page origin.
        val wallpaper = bounds("wallpaper")
        assertDp("wallpaper left", 0f, wallpaper.left)
        assertDp("wallpaper top", 0f, wallpaper.top)
        assertDp("wallpaper width", 411f, wallpaper.right)
        assertDp("wallpaper height", 617.04f, wallpaper.bottom)

        // The nav bar: 17's 52 dp row, the ✕ 44 dp at start 16, centred at
        // y = 26, labelled "Close".
        val nav = bounds("new-session-nav")
        assertDp("nav top", 0f, nav.top)
        assertDp("nav bottom", 52f, nav.bottom)
        val close = bounds("sheet-close")
        assertDp("close left", 16f, close.left)
        assertDp("close top", 4f, close.top)
        assertDp("close size", 44f, close.right - close.left)
        assertDp("close centred at y = 26", 26f, (close.top + close.bottom) / 2f)
        assertEquals(
            "Close",
            compose.onNodeWithTag("sheet-close").fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription].single(),
        )
        compose.onNodeWithTag("sheet-close").performClick()
        compose.waitForIdle()
        assertEquals("✕ closes once", 1, closeCount)
    }

    @Test
    fun theComposerRectMatchesTheComposerNodeAndTheWallpaperLocalContract() {
        mountSheetCanvas()
        val composer = bounds("new-session-composer")
        val rect = canvasState!!.composerRect
        assertTrue("the rect is published", rect != null)
        val d = density
        assertPx("rect left", composer.left.value * d, rect!!.left)
        assertPx("rect top", composer.top.value * d, rect.top)
        assertPx("rect right", composer.right.value * d, rect.right)
        assertPx("rect bottom", composer.bottom.value * d, rect.bottom)
        // 21's consumer contract: the composer rect in wallpaper-local px.
        val local = wallpaperCoords!!.localBoundingBoxOf(canvasState!!.composerCoordinates!!, clipBounds = false)
        assertPx("wallpaper-local left", rect.left, local.left)
        assertPx("wallpaper-local top", rect.top, local.top)
        assertPx("wallpaper-local right", rect.right, local.right)
        assertPx("wallpaper-local bottom", rect.bottom, local.bottom)
    }

    // ── the IME (worked case B) ─────────────────────────────────────────

    @Test
    fun withTheImeUpTheComposerRidesTo513AndTheWallpaperKeepsItsHeight() {
        mountSheetCanvas(
            insets = CanvasInsets(0f, 0f, 0f, 0f, 336f),
            hostPad = 336.dp,
        )
        val composer = bounds("new-session-composer")
        assertDp("composer bottom = 521 − 8", 513f, composer.bottom)
        // The headline stays clear of the composer (≤ composerTop − 20, so
        // in particular < composerTop − 8, the iOS UI test's assertion).
        val headline = bounds("new-session-headline")
        assertTrue("headline above composerTop − 8", headline.bottom.value < composer.top.value - 8f)
        val hero = bounds("new-session-hero")
        assertDp("hero re-centred in 52…359", (52f + 359f) / 2f, (hero.top + hero.bottom) / 2f)
        // The wallpaper is sized from the IME-less page height: still
        // 617.04 dp, never re-cropped.
        val wallpaper = bounds("wallpaper")
        assertDp("wallpaper height unchanged", 617.04f, wallpaper.bottom - wallpaper.top)
        assertDp("wallpaper still at the top", 0f, wallpaper.top)
    }

    @Test
    fun theCutoutFollowsTheComposerOnEveryImeAnimationFrame() {
        compose.mainClock.autoAdvance = false
        mountSheetCanvas()
        // Frame 1: the initial layout (ime 0).
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        val d = density
        for (frame in 1..10) {
            // 31's imePadding: the host shrinks by the IME and the canvas
            // is told the same IME was consumed (`bottom = 0`,
            // `imeAbove = ime`) — the composer's page position rides the
            // keyboard top, the wallpaper keeps the IME-less height.
            val ime = 336f * frame / 10f
            compose.runOnUiThread {
                padState.value = ime.dp
                insetsState.value = CanvasInsets(0f, 0f, 0f, 0f, ime)
            }
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()

            val composer = bounds("new-session-composer")
            // Robolectric never draws the window on its own (no valid
            // Surface: traversals measure and lay out, the draw is
            // skipped), so the wallpaper's drawBehind only runs on a
            // capture. Draw this frame's state once, then read what the
            // draw-phase cutout read saw.
            compose.onRoot().captureToImage()
            val cutout = recordedCutouts.lastOrNull()
            assertTrue("frame $frame recorded a cutout", cutout != null)
            assertPx("frame $frame cutout left", composer.left.value * d, cutout!!.left)
            assertPx("frame $frame cutout top", composer.top.value * d, cutout.top)
            assertPx("frame $frame cutout right", composer.right.value * d, cutout.right)
            assertPx("frame $frame cutout bottom", composer.bottom.value * d, cutout.bottom)
            // 21's consumer contract on every frame too.
            val local = wallpaperCoords!!.localBoundingBoxOf(canvasState!!.composerCoordinates!!, clipBounds = false)
            assertPx("frame $frame wallpaper-local left", composer.left.value * d, local.left)
        }
        // The end state is case B, and the wallpaper never re-sized.
        assertDp("composer bottom after the IME", 513f, bounds("new-session-composer").bottom)
        assertDp("wallpaper height after the IME", 617.04f, bounds("wallpaper").bottom - bounds("wallpaper").top)
    }

    // ── embedded ────────────────────────────────────────────────────────

    @Test
    fun theEmbeddedPageHasNoCloseA44dpRowAtTheInsetAndThePaneTitle() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                NewSessionCanvas(
                    harness = "claude-code",
                    embedded = true,
                    onClose = { closeCount++ },
                    onBackgroundTap = {},
                    composer = { StandInComposer() },
                    insets = CanvasInsets(top = 24f, safeLeft = 0f, safeRight = 0f, bottom = 0f, imeAbove = 0f),
                    wallpaper = { m, c -> FakeWallpaper(m, c) },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("sheet-close").assertDoesNotExist()
        val nav = bounds("new-session-nav")
        assertDp("nav row top = the inset", 24f, nav.top)
        assertDp("nav row height", 44f, nav.bottom - nav.top)
        val title = bounds("new-session-title")
        assertDp("title centred", 411f / 2f, (title.left + title.right) / 2f)
        assertEquals(
            "New Session",
            compose.onNodeWithTag("new-session-screen").fetchSemanticsNode()
                .config[SemanticsProperties.PaneTitle],
        )
    }

    // ── typography ──────────────────────────────────────────────────────

    /**
     * `Fonts.ui` is fixed on iOS (no Dynamic Type), so the headline's line
     * box (1.3 × 22 = 28.6 dp ≈ 86 px here) and the title's (1.3 × 17 =
     * 22.1 dp ≈ 66 px) must not move under a 1.3 font scale: the mount
     * swaps [LocalDensity] (same density 3, the scale from state) and reads
     * both layouts through the one content set. Embedded mode, because it
     * owns the tagged title node.
     */
    @Test
    fun theFixedTypeSizesDoNotFollowTheFontScale() {
        val fontScale = mutableStateOf(1f)
        val base = compose.density.density
        compose.setContent {
            // The density override wraps the theme so the TypeScale is
            // built from it too: `fixed(pt)` roundtrips dp through the
            // font-scale converter, so the rendered height must not move.
            CompositionLocalProvider(LocalDensity provides Density(base, fontScale.value)) {
                RobocoTheme(Appearance.Light) {
                    NewSessionCanvas(
                        harness = "claude-code",
                        embedded = true,
                        onClose = {},
                        onBackgroundTap = {},
                        composer = { StandInComposer() },
                        insets = CanvasInsets(top = 24f, safeLeft = 0f, safeRight = 0f, bottom = 0f, imeAbove = 0f),
                        wallpaper = { m, c -> FakeWallpaper(m, c) },
                    )
                }
            }
        }
        compose.waitForIdle()
        val headline1 = textLayout("new-session-headline").size.height
        val title1 = textLayout("new-session-title").size.height
        assertTrue("headline line box 28.6 dp at scale 1: $headline1", abs(headline1 - 86f) <= 2f)
        assertTrue("title line box 22.1 dp at scale 1: $title1", abs(title1 - 66f) <= 2f)

        compose.runOnUiThread { fontScale.value = 1.3f }
        compose.waitForIdle()
        val headline2 = textLayout("new-session-headline").size.height
        val title2 = textLayout("new-session-title").size.height
        assertTrue("headline unchanged at scale 1.3: $headline2", abs(headline1 - headline2) <= 1f)
        assertTrue("title unchanged at scale 1.3: $title2", abs(title1 - title2) <= 1f)
    }

    /** The "Narrow" case: a 200 dp page wraps the headline to 2 lines. */
    @Test
    fun theHeadlineWrapsToTwoLinesInANarrowPage() {
        mountSheetCanvas(width = 200.dp)
        assertEquals(2, textLayout("new-session-headline").lineCount)
        // The hero column is 34 + 14 + 2 × 28.6 dp tall.
        val hero = bounds("new-session-hero")
        // ±2 px (decision, round 31): each 28.6 dp line rounds to whole px
        // (85.8 → 85 at 3 px/dp), so two lines can land 1.6 px short.
        assertDp("two-line hero height", 105.2f, hero.bottom - hero.top, tolerancePx = 2f)
    }

    // ── the mark ────────────────────────────────────────────────────────

    @Test
    fun theMarkRedrawsWithTheNextHarnessAndNoFade() {
        compose.mainClock.autoAdvance = false
        mountSheetCanvas()
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        // claude-code: the brand orange, not the text tint.
        assertTrue("claude-code draws #D97757", hasColor("new-session-mark", ClaudeOrange))

        compose.runOnUiThread { harnessState.value = "codex" }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame() // the next frame, no fade
        compose.waitForIdle()
        assertTrue("codex draws Palette.text (#27272C light)", hasColor("new-session-mark", LightText))
        assertFalse("the orange is gone", hasColor("new-session-mark", ClaudeOrange))
    }

    // ── the card morph ───────────────────────────────────────────────

    /**
     * `chipsAlwaysVisible` (always a card) starts the morph at 1 — no
     * capsule-to-card animation ever runs on this page.
     */
    @Test
    fun theCardMorphStartsSettledAtOneOnTheFirstFrame() {
        compose.mainClock.autoAdvance = false
        mountSheetCanvas()
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertTrue("chipsAlwaysVisible", canvasMorph!!.alwaysCard)
        assertEquals(1f, compose.runOnUiThread { canvasMorph!!.progress }, 1e-3f)
    }

    // ── tap to dismiss ──────────────────────────────────────────────────

    @Test
    fun aTapOutsideTheComposerClearsFocusAndTheCardStaysACard() {
        mountSheetCanvas(realComposer = true)
        compose.onNodeWithTag("composer-input").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("composer-input").assertIsFocused()

        compose.onRoot().performTouchInput { click(Offset(205.dp.toPx(), 150.dp.toPx())) }
        compose.waitForIdle()
        compose.onNodeWithTag("composer-input").assertIsNotFocused()
        val progress = compose.runOnUiThread { canvasMorph!!.progress }
        assertEquals("the card stays a card (alwaysCard)", 1f, progress, 1e-3f)
    }

    @Test
    fun aTapOnTheComposerTextDoesNotClearFocus() {
        mountSheetCanvas(realComposer = true)
        compose.onNodeWithTag("composer-input").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("composer-input").assertIsFocused()
        // A second tap on the field itself: the down is inside the
        // composer rect, so the detector never tracks it.
        compose.onRoot().performTouchInput { click(Offset(205.dp.toPx(), 750.dp.toPx())) }
        compose.waitForIdle()
        compose.onNodeWithTag("composer-input").assertIsFocused()
    }

    @Test
    fun aTapOnAMentionRowPicksAndDoesNotClearFocus() {
        mountSheetCanvas(realComposer = true, fakeRow = true)
        compose.onNodeWithTag("composer-input").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("composer-input").assertIsFocused()
        compose.onNodeWithTag("fake-row").performClick()
        compose.waitForIdle()
        assertEquals("the row's onClick ran", 1, rowClicks)
        compose.onNodeWithTag("composer-input").assertIsFocused()
    }

    @Test
    fun aDragBeyondTouchSlopDoesNotClearFocus() {
        mountSheetCanvas(realComposer = true)
        compose.onNodeWithTag("composer-input").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("composer-input").assertIsFocused()
        compose.onRoot().performTouchInput {
            down(Offset(205.dp.toPx(), 150.dp.toPx()))
            moveBy(Offset(0f, 40.dp.toPx()))
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("composer-input").assertIsFocused()
    }

    @Test
    fun aTapInTheNavBarAreaDoesNotClearFocus() {
        mountSheetCanvas(realComposer = true)
        compose.onNodeWithTag("composer-input").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("composer-input").assertIsFocused()
        compose.onRoot().performTouchInput { click(Offset(205.dp.toPx(), 20.dp.toPx())) }
        compose.waitForIdle()
        compose.onNodeWithTag("composer-input").assertIsFocused()
    }

    // ── focus on appear ─────────────────────────────────────────────────

    /** A host that moves for 300 ms, like 31's sheet present motion. */
    private fun mountMovingHost(focused: () -> Unit) {
        val state = NewSessionCanvasState()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                val offset = remember { Animatable(300f) }
                LaunchedEffect(Unit) { offset.animateTo(0f, tween(durationMillis = 300)) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .offset { IntOffset(0, offset.value.roundToInt()) }
                        .onGloballyPositioned { state.pageCoordinates = it },
                ) {
                    NewSessionFocusOnAppear(
                        focusOnAppear = true,
                        embedded = false,
                        state = state,
                        focus = focused,
                    )
                }
            }
        }
    }

    @Test
    fun theSheetWaitsForThePresentMotionToSettleBeforeFocusing() {
        compose.mainClock.autoAdvance = false
        var focused = 0
        mountMovingHost { focused++ }
        compose.waitForIdle()
        // Still moving (96 ms in): no focus.
        repeat(6) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        assertEquals("not focused while the host moves", 0, focused)
        // The tween ends at 300 ms; two still frames later the wait returns.
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertEquals("focused once after the settle", 1, focused)
    }

    @Test
    fun embeddedFocusesOnTheFirstFrameWhenAsked() {
        compose.mainClock.autoAdvance = false
        var focused = 0
        val state = NewSessionCanvasState()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                NewSessionFocusOnAppear(
                    focusOnAppear = true,
                    embedded = true,
                    state = state,
                    focus = { focused++ },
                )
            }
        }
        compose.waitForIdle()
        assertEquals("not before the first frame", 0, focused)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(1, focused)
    }

    @Test
    fun embeddedNeverFocusesWhenTheCallerSaysSo() {
        compose.mainClock.autoAdvance = false
        var focused = 0
        val state = NewSessionCanvasState()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                NewSessionFocusOnAppear(
                    focusOnAppear = false,
                    embedded = true,
                    state = state,
                    focus = { focused++ },
                )
            }
        }
        compose.waitForIdle()
        repeat(5) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        assertEquals(0, focused)
    }

    // ── the composer configuration (the viewDidLoad block) ──────────────

    /** A controller over the test scope and [NoFeedback]. */
    private fun newController(): ComposerController =
        ComposerController(CoroutineScope(Dispatchers.Unconfined), NoFeedback)

    @Test
    fun theMentionSearchReturnsNothingWithoutAProjectAndNeverCallsTheEngine() = runTest {
        val calls = mutableListOf<String>()
        val fake = NewSessionFileSearch { deviceId, spaceId, query ->
            calls += "$deviceId/$spaceId/$query"
            listOf(FileMatch("a.rs", false))
        }
        val controller = newController()
        bindNewSessionComposer(controller, project = { null }, emptyList(), fake)
        assertEquals(emptyList<FileMatch>(), controller.mentionSearch!!("lay"))
        assertTrue("no project: the engine is never called", calls.isEmpty())
    }

    @Test
    fun theMentionSearchUsesTheProjectReadAtCallTime() = runTest {
        val calls = mutableListOf<String>()
        val fake = NewSessionFileSearch { deviceId, spaceId, query ->
            calls += "$deviceId/$spaceId/$query"
            emptyList()
        }
        var picked = project("s1", "d1")
        val controller = newController()
        bindNewSessionComposer(controller, project = { picked }, emptyList(), fake)
        controller.mentionSearch!!("lay")
        assertEquals(listOf("d1/s1/lay"), calls)
        // A later project pick changes the scope of the next call.
        picked = project("s2", "d2")
        controller.mentionSearch!!("lay")
        assertEquals(listOf("d1/s1/lay", "d2/s2/lay"), calls)
    }

    @Test
    fun aThrowingEngineYieldsNoMatches() = runTest {
        val fake = NewSessionFileSearch { _, _, _ -> throw IllegalStateException("engine down") }
        val controller = newController()
        bindNewSessionComposer(controller, project = { project("s1", "d1") }, emptyList(), fake)
        assertEquals(emptyList<FileMatch>(), controller.mentionSearch!!("lay"))
    }

    @Test
    fun theConfigurationMatchesTheViewDidLoadBlock() = runTest {
        val fake = NewSessionFileSearch { _, _, _ -> emptyList() }
        val controller = newController()
        val image = StagedImage("i1", "i1.jpg", byteArrayOf(1), ImageBitmap(1, 1))
        bindNewSessionComposer(controller, project = { null }, listOf(image), fake)
        assertTrue("chipsAlwaysVisible", controller.alwaysCard)
        assertEquals("Describe the task", controller.placeholder)
        assertEquals(listOf(image), controller.images)
        // 87's ComposerAttachMenuButton computes 8 − images.size itself.
        assertNull(controller.attachMenu)
        // The 94 stub: every send stays Kept.
        assertEquals(SendResult.Kept, controller.onSend!!("", emptyList(), DeliveryMode.Queue))
    }

    // ── the page over a model (NewSessionScreenBody) ────────────────────

    /** Counts the `Drafts` disk writes (one per `save`). */
    private class CountingStore : DataStore<Preferences> {
        private val state = MutableStateFlow(preferencesOf())
        var writes = 0
            private set

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            if (next != state.value) writes++
            state.value = next
            return next
        }
    }

    /** 92's fake catalog: an empty list on both paths (no FFI on the JVM). */
    private class FakeCatalog : ModelCatalogSource {
        override fun builtIn(): List<ModelChoice> = emptyList()
        override suspend fun fetch(deviceId: String): List<ModelChoice> = emptyList()
    }

    private fun project(id: String, device: String) = ProjectOption(
        id = id,
        name = id,
        path = "/repo",
        device = device,
        deviceName = device,
        online = true,
        git = true,
        groupKey = "g",
        groupName = "g",
        colorIndex = 0,
    )

    /** A page over the fakes; [CountingStore] counts the draft saves. */
    private class PageFixture(
        val model: NewSessionModel,
        val store: NewSessionDraftStore,
        val drafts: CountingStore,
    )

    private fun newPage(prompt: String?): PageFixture {
        val counting = CountingStore()
        val drafts = Drafts(counting, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        val store = NewSessionDraftStore(
            FakePrefsStore(),
            drafts,
            CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
            launchHarness = { null },
        )
        val model = NewSessionModel(
            prompt = prompt,
            store = store,
            options = MutableStateFlow(NewSessionOptions(emptyList(), emptyList())),
            catalog = FakeCatalog(),
            pageScope = CoroutineScope(Dispatchers.Unconfined),
            appScope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
            main = Dispatchers.Unconfined,
        )
        return PageFixture(model, store, counting)
    }

    @Test
    fun thePageSeedsTheComposerWithTheModelInitialTextBeforeTheFirstFrame() {
        val page = newPage(prompt = "Fix tests")
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                GlassMenuHost { // the page's ComposerBar opens menus (see mountSheetCanvas)
                    NewSessionScreenBody(
                        ns = page.model,
                        embedded = false,
                        onClose = {},
                        focusOnAppear = false,
                        fileSearch = NewSessionFileSearch { _, _, _ -> emptyList() },
                        labels = GalleryLabels,
                    )
                }
            }
        }
        compose.waitForIdle()
        val config = compose.onNodeWithTag("composer-input").fetchSemanticsNode().config
        assertEquals("Fix tests", config[SemanticsProperties.EditableText].text)
    }

    @Test
    fun anEmptyPageShowsTheDescribeTheTaskPlaceholder() {
        val page = newPage(prompt = null)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                GlassMenuHost { // the page's ComposerBar opens menus (see mountSheetCanvas)
                    NewSessionScreenBody(
                        ns = page.model,
                        embedded = false,
                        onClose = {},
                        focusOnAppear = false,
                        fileSearch = NewSessionFileSearch { _, _, _ -> emptyList() },
                        labels = GalleryLabels,
                    )
                }
            }
        }
        compose.waitForIdle()
        val config = compose.onNodeWithTag("composer-input").fetchSemanticsNode().config
        assertEquals(listOf("Describe the task"), config[SemanticsProperties.ContentDescription])
        assertEquals("", config[SemanticsProperties.EditableText].text)
    }

    @Test
    fun disposingThePageSavesTheDraftOnce() {
        val page = newPage(prompt = null)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                var show by remember { mutableStateOf(true) }
                if (show) {
                    GlassMenuHost { // the page's ComposerBar opens menus (see mountSheetCanvas)
                        NewSessionScreenBody(
                            ns = page.model,
                            embedded = false,
                            onClose = {},
                            focusOnAppear = false,
                            fileSearch = NewSessionFileSearch { _, _, _ -> emptyList() },
                            labels = GalleryLabels,
                        )
                    }
                }
                BasicText(
                    "Toggle",
                    Modifier.testTag("toggle").clickable { show = false },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("composer-input").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("composer-input").performTextInput("Half-written idea")
        compose.waitForIdle()
        compose.onNodeWithTag("toggle").performClick()
        compose.waitForIdle()
        assertEquals("one rememberDraft on dispose", 1, page.drafts.writes)
        assertEquals("Half-written idea", page.store.text)
    }

    // ── companions ──────────────────────────────────────────────────────

    private companion object {
        /** The fake mention row's fill (never a palette colour). */
        val RowColor = Color(0xFF2E6DB4)

        /** `#D97757` — the Claude brand orange (ticket 13). */
        val ClaudeOrange = Color(0xFFD97757)

        /** `Palette.text` light (`#27272C`). */
        val LightText = Color(0xFF27272C)
    }
}
