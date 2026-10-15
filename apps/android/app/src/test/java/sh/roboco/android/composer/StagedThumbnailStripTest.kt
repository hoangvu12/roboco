package sh.roboco.android.composer

import android.graphics.Bitmap
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
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
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.NoFeedback
import sh.roboco.android.design.Palette
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme

/**
 * The staged-thumbnail strip's acceptance criteria (android-ui-parity #88):
 * Robolectric + compose-ui-test at **mdpi** (`w390dp-h400dp-mdpi`, 1 dp =
 * 1 px) with the strip mounted at its composer width 342. Geometry,
 * semantics, the two ×-animations (the 250 ms slide and collapse, stepped
 * with `mainClock.advanceTimeByFrame()`), the same-frame snaps, reduce
 * motion, the 56 tap split (via 56's `composerMorphHost` fixture), pixels
 * (the hairline border, the `black @ 0.55` fill over red, the white
 * glyph, the centred crop) and RTL.
 *
 * The runner is the repo's Robolectric convention (`RobolectricTestRunner`
 * + compose rule), like every other behaviour test in the app; the
 * ticket's `AndroidJUnit4` note maps onto it (the ext-junit runner is an
 * androidTest-only dependency here).
 *
 * Animation clocking follows the ticket: `autoAdvance = false`, state
 * writes flushed with `Snapshot.sendApplyNotifications()` (Robolectric's
 * paused looper never posts them by itself), one frame per step. `t0` is
 * the animation's start frame — the last frame the animated value is
 * still at its pre-animation position — because the `LaunchedEffect`
 * starts one frame after the click.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h400dp-mdpi")
class StagedThumbnailStripTest {

    @get:Rule
    val compose = createComposeRule()

    /** The live list the state-driven tests write through. */
    private var images by mutableStateOf<List<StagedImage>>(emptyList())

    /** The focus fixture's field and morph (assigned during composition). */
    private val field = TextFieldState("Half-written thought")
    private var morph: ComposerMorphState? = null

    @Before
    fun freezeClock() {
        compose.mainClock.autoAdvance = false
    }

    @After
    fun resetMotion() {
        ReduceMotion.resetForTest()
    }

    // ------------------------------------------------------------- helpers

    /** A solid red 180 x 135 thumbnail (the pixel-probe fixture). */
    private fun solidImage(id: String): StagedImage {
        val bitmap = Bitmap.createBitmap(180, 135, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFFFF0000.toInt())
        return StagedImage(id, "$id.jpg", ByteArray(0), bitmap.asImageBitmap())
    }

    private fun threeRed(): List<StagedImage> =
        listOf(solidImage("a"), solidImage("b"), solidImage("c"))

    private fun eightRed(): List<StagedImage> =
        List(8) { solidImage("img-$it") }

    /** The strip page: the 342 dp strip at (0, 0) over the palette background. */
    @Composable
    private fun StripHost(
        images: List<StagedImage>,
        onRemove: (String) -> Unit,
        visible: Boolean = true,
        rtl: Boolean = false,
        appearance: Appearance = Appearance.Light,
    ) {
        RobocoTheme(appearance) {
            val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                // Pinned top-LEFT in both directions: a TopStart Box would
                // mirror the 342 dp strip to x = 48 in the 390 dp RTL window,
                // and the RTL test's coordinates are the strip's own (0…342).
                Box(
                    Modifier.fillMaxSize().background(RobocoTheme.palette.background),
                    contentAlignment = AbsoluteAlignment.TopLeft,
                ) {
                    StagedThumbnailStrip(
                        images = images,
                        onRemove = onRemove,
                        visible = visible,
                        modifier = Modifier.width(342.dp),
                    )
                }
            }
        }
    }

    private fun mountStrip(
        images: List<StagedImage>,
        onRemove: (String) -> Unit = {},
        visible: Boolean = true,
        rtl: Boolean = false,
        appearance: Appearance = Appearance.Light,
    ) {
        compose.setContent { StripHost(images, onRemove, visible, rtl, appearance) }
        compose.waitForIdle()
    }

    /** Mounts the class-level [images] list, removing on × like the controller. */
    private fun mountState() {
        compose.setContent {
            StripHost(
                images = images,
                onRemove = { id -> images = images.filterNot { it.id == id } },
            )
        }
        compose.waitForIdle()
    }

    /**
     * A test-side snapshot write with the clock frozen: Robolectric runs the
     * test body on the main thread with the looper paused, so Compose's
     * GlobalSnapshotManager never posts the apply notifications — without
     * [Snapshot.sendApplyNotifications] the Recomposer never sees the write.
     */
    private fun writeFrozen(write: () -> Unit) {
        compose.runOnIdle {
            write()
            Snapshot.sendApplyNotifications()
        }
    }

    /** One frame: recomposition, measure, layout and draw, then idle. */
    private fun stepFrame() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun stripHeight(): Float =
        compose.onNodeWithTag("composer-thumbs").getBoundsInRoot().height.value

    private fun surfaceHeight(): Float =
        compose.onNodeWithTag("surface").getBoundsInRoot().height.value

    private fun tileLeft(i: Int): Float =
        compose.onAllNodesWithTag("composer-thumb")[i].getBoundsInRoot().left.value

    private fun tileRight(i: Int): Float =
        compose.onAllNodesWithTag("composer-thumb")[i].getBoundsInRoot().right.value

    private fun tilePixels(): PixelMap =
        compose.onAllNodesWithTag("composer-thumb")[0].captureToImage().toPixelMap()

    private fun stripPixels(): PixelMap =
        compose.onNodeWithTag("composer-thumbs").captureToImage().toPixelMap()

    private fun hasButtonRole(): SemanticsMatcher =
        SemanticsMatcher("has Button role") { it.config[SemanticsProperties.Role] == Role.Button }

    private fun hasNoContentDescription(): SemanticsMatcher =
        SemanticsMatcher("has no content description") {
            it.config.getOrNull(SemanticsProperties.ContentDescription) == null
        }

    /** Taps the × of tile [i] and flushes the state write. */
    private fun tapRemove(i: Int) {
        compose.onAllNodesWithTag("composer-thumb-remove")[i].performClick()
        // runOnUiThread, not runOnIdle: the click just scheduled a
        // recomposition, which only runs on the next frame (the clock is
        // frozen), so an idle wait here could stall.
        compose.runOnUiThread { Snapshot.sendApplyNotifications() }
    }

    /** ±[tolerance] 8-bit levels per channel. */
    private fun assertChannelNear(what: String, actual: Color, expected: Color, tolerance: Int = 3) {
        val a = actual.toArgb()
        val e = expected.toArgb()
        fun ch(argb: Int, shift: Int) = (argb shr shift) and 0xFF
        for (shift in intArrayOf(16, 8, 0)) {
            assertTrue(
                "$what: channel at shift $shift is ${ch(a, shift)}, expected ${ch(e, shift)} ± $tolerance",
                abs(ch(a, shift) - ch(e, shift)) <= tolerance,
            )
        }
    }

    /** The last frame (index) whose value is still within [band] of [value]. */
    private fun lastFrameNear(trace: List<Float>, value: Float, band: Float = 0.5f): Int {
        val i = trace.indexOfLast { abs(it - value) <= band }
        assertTrue("no frame near $value in $trace", i >= 0)
        return i
    }

    // ------------------------------------------------------- the geometry

    @Test
    fun threeImagesMatchTheWorkedGeometry() {
        mountStrip(threeRed())
        compose.onNodeWithTag("composer-thumbs")
            .assertWidthIsEqualTo(342.dp)
            .assertHeightIsEqualTo(68.dp)
        val tiles = compose.onAllNodesWithTag("composer-thumb")
        tiles.assertCountEquals(3)
        val positions = listOf(0.dp to 12.dp, 64.dp to 12.dp, 128.dp to 12.dp)
        for (i in positions.indices) {
            tiles[i].assertPositionInRootIsEqualTo(positions[i].first, positions[i].second)
            tiles[i].assertWidthIsEqualTo(56.dp)
            tiles[i].assertHeightIsEqualTo(56.dp)
        }
        val removes = compose.onAllNodesWithTag("composer-thumb-remove")
        removes.assertCountEquals(3)
        removes[0].assertPositionInRootIsEqualTo(34.dp, 16.dp)
        removes[0].assertWidthIsEqualTo(18.dp)
        removes[0].assertHeightIsEqualTo(18.dp)
    }

    @Test
    fun anEmptyStripIsZeroHighAndComposesNothing() {
        mountStrip(emptyList())
        compose.onNodeWithTag("composer-thumbs")
            .assertWidthIsEqualTo(342.dp)
            .assertHeightIsEqualTo(0.dp)
        compose.onAllNodesWithTag("composer-thumb").assertCountEquals(0)
        compose.onAllNodesWithTag("composer-thumb-remove").assertCountEquals(0)
    }

    @Test
    fun aHiddenStripIsZeroHighAndComposesNoControls() {
        mountStrip(threeRed(), visible = false)
        compose.onNodeWithTag("composer-thumbs").assertHeightIsEqualTo(0.dp)
        // Nothing is composed while hidden: no × reaches the semantics tree.
        compose.onAllNodesWithTag("composer-thumb-remove").assertCountEquals(0)
        compose.onAllNodesWithTag("composer-thumb").assertCountEquals(0)
    }

    // ------------------------------------------- inside 55's surface (p = 1)

    private fun mountSurface(withStrip: Boolean) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(Palette.Light.background)) {
                    ComposerSurface(
                        progress = { 1f },
                        chipsInteractive = true,
                        modifier = Modifier.testTag("surface").fillMaxWidth(),
                        thumbnails = {
                            if (withStrip) {
                                StagedThumbnailStrip(
                                    images = images,
                                    onRemove = { id -> images = images.filterNot { it.id == id } },
                                )
                            }
                        },
                        text = { Box(Modifier.fillMaxWidth().height(46.dp)) },
                        attach = {},
                        chips = {},
                        action = { Box(Modifier.size(34.dp)) },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    @Config(sdk = [36], qualifiers = "w366dp-h400dp-mdpi")
    fun theCardWithThreeImagesIs164HighAndTheStripSitsAt12Comma12() {
        images = threeRed()
        mountSurface(withStrip = true)
        compose.onNodeWithTag("surface")
            .assertWidthIsEqualTo(366.dp)
            .assertHeightIsEqualTo(164.dp)
        compose.onNodeWithTag("composer-thumbs")
            .assertPositionInRootIsEqualTo(12.dp, 0.dp)
            .assertWidthIsEqualTo(342.dp)
        compose.onAllNodesWithTag("composer-thumb")[0]
            .assertPositionInRootIsEqualTo(12.dp, 12.dp)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w366dp-h400dp-mdpi")
    fun theCardWithoutImagesIs96High() {
        images = emptyList()
        mountSurface(withStrip = true)
        compose.onNodeWithTag("surface").assertHeightIsEqualTo(96.dp)
        compose.onNodeWithTag("composer-thumbs").assertHeightIsEqualTo(0.dp)
    }

    // ------------------------------------------------------- the semantics

    @Test
    fun removeButtonsCarrySemanticsAndTilesDoNot() {
        mountStrip(threeRed())
        val removes = compose.onAllNodesWithContentDescription("Remove image")
        removes.assertCountEquals(3)
        for (i in 0 until 3) {
            removes[i].assert(hasButtonRole())
            removes[i].assert(hasClickAction())
        }
        val tiles = compose.onAllNodesWithTag("composer-thumb")
        tiles.assertCountEquals(3)
        for (i in 0 until 3) {
            // The tile is a plain image: no label, not focusable, no click.
            tiles[i].assert(hasNoContentDescription())
            tiles[i].assert(hasNoClickAction())
        }
    }

    @Test
    fun clickingRemoveOnTheMiddleTileCallsOnRemoveExactlyOnce() {
        val removals = mutableListOf<String>()
        mountStrip(threeRed(), onRemove = { removals += it })
        tapRemove(1)
        compose.waitForIdle()
        assertEquals(listOf("b"), removals)
    }

    @Test
    fun withARealControllerTheListBecomesACommaC() {
        val controller = ComposerController(CoroutineScope(UnconfinedTestDispatcher()), NoFeedback)
        controller.images = threeRed()
        compose.setContent {
            StripHost(
                images = controller.images,
                onRemove = { controller.removeImage(it) },
            )
        }
        compose.waitForIdle()
        tapRemove(1)
        compose.waitForIdle()
        assertEquals(listOf("a", "c"), controller.images.map { it.id })
    }

    // --------------------------------------------------------- the slide

    @Test
    fun removingAMiddleTileSlidesTheSurvivorsOver250ms() {
        images = threeRed()
        mountState()
        tapRemove(1)

        val aTrace = mutableListOf<Float>()
        val cTrace = mutableListOf<Float>()
        val heights = mutableListOf<Float>()
        repeat(24) {
            stepFrame()
            aTrace += tileLeft(0)
            cTrace += tileLeft(1)
            heights += stripHeight()
        }

        // The removed tile is gone; c starts at its old slot 128.
        compose.onAllNodesWithTag("composer-thumb").assertCountEquals(2)
        assertTrue("tile c starts at 128: ${cTrace.first()}", cTrace.first() >= 127.5f)
        assertTrue("tile a never moves: $aTrace", aTrace.all { it == 0f })
        // t0 = the animation's start frame (the last one still at 128).
        val t0 = lastFrameNear(cTrace, 128f)
        assertTrue("the slide fits the trace", t0 + 16 < cTrace.size)
        // t0 + 128 ms (8 frames, eased 0.52): 128 - 0.52 * 64 ≈ 95.
        val mid = cTrace[t0 + 8]
        assertTrue("tile c at t0+128ms: $mid (expected 95 ± 3)", mid in 92f..98f)
        // From t0 + 256 ms (16 frames) on it sits at its new slot 64.
        for (f in (t0 + 16) until cTrace.size) {
            assertEquals("tile c settled at 64 (frame $f)", 64f, cTrace[f], 0.5f)
        }
        // The strip keeps its height: only a last-image removal collapses it.
        assertTrue("the strip stays 68: $heights", heights.all { it == 68f })
    }

    @Test
    fun aSecondRemoveDuringTheSlideContinuesFromTheCurrentOffset() {
        images = threeRed()
        mountState()
        tapRemove(1) // [a, b, c] -> [a, c]: c slides 128 -> 64

        // Let the slide run a few frames, then remove a mid-flight: c's
        // index drops again (1 -> 0) and the interrupted animation must
        // continue from its current offset and velocity, not restart.
        repeat(5) { stepFrame() }
        val before = tileLeft(1)
        assertTrue("mid-slide when interrupted: $before", before in 90f..127f)
        tapRemove(0) // [a, c] -> [c]: c's target becomes 0

        val cTrace = mutableListOf<Float>()
        repeat(24) {
            stepFrame()
            cTrace += tileLeft(0)
        }
        // Continuity: no frame-to-frame jump beyond the ease's steepest
        // rate (~0.44 px/ms -> ~7.4 px/frame), so the animation continued
        // from the interrupted value instead of snapping.
        val maxJump = cTrace.zipWithNext { a, b -> abs(b - a) }.max()
        // 16 px (decision, round 31): the ease's steepest frame is 13.6 px
        // here (the second remove lands ~48 ms into the first slide, ~124 px
        // left over 250 ms); a snap would jump ~120 px.
        assertTrue("continues from the current offset (max jump $maxJump)", maxJump <= 16f)
        // The first post-interruption frame continues the slide (decision,
        // round 35): the ease (0.42, 0, 0.58, 1) runs 128 → 64 over 250 ms —
        // 123.2 at 48 ms (= `before`), 119.3 at 64, 114.3 at 80. The tile
        // moves toward SMALLER x, and tapRemove's click itself advances one
        // frame (64 ms), so the first step lands at 80 ms (CI: 114.0): the
        // window is (before − 12)..before. The old (before − 2)..(before + 10)
        // had the wrong sign and ignored the click's frame.
        assertTrue("the first post-interruption frame continues from $before: ${cTrace.first()}",
            cTrace.first() in (before - 12f)..before)
        assertEquals("settled at its final slot 0", 0f, cTrace.last(), 0.5f)
    }

    // ------------------------------------------------------ the collapse

    @Test
    fun removingTheLastImageCollapsesTheStripOver250ms() {
        images = listOf(solidImage("a"))
        mountState()
        tapRemove(0)

        val heights = mutableListOf<Float>()
        repeat(24) {
            stepFrame()
            heights += stripHeight()
        }
        assertTrue("68 up to and including t0: ${heights.first()}", heights.first() >= 67.5f)
        val t0 = lastFrameNear(heights, 68f)
        assertTrue("the collapse fits the trace", t0 + 16 < heights.size)
        val mid = heights[t0 + 8]
        assertTrue("strip height at t0+128ms: $mid (expected 33 ± 3)", mid in 30f..36f)
        for (f in (t0 + 16) until heights.size) {
            assertEquals("collapsed to 0 (frame $f)", 0f, heights[f], 0.5f)
        }
        compose.onAllNodesWithTag("composer-thumb-remove").assertCountEquals(0)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w366dp-h400dp-mdpi")
    fun theComposerHeightFollowsTheCollapseEveryFrame() {
        images = listOf(solidImage("a"))
        mountSurface(withStrip = true)
        compose.onNodeWithTag("surface").assertHeightIsEqualTo(164.dp)
        tapRemove(0)

        val heights = mutableListOf<Float>()
        repeat(24) {
            stepFrame()
            heights += surfaceHeight()
        }
        assertTrue("164 up to and including t0: ${heights.first()}", heights.first() >= 163.5f)
        val t0 = lastFrameNear(heights, 164f)
        assertTrue("the collapse fits the trace", t0 + 16 < heights.size)
        // 33 of strip + 46 text + 50 toolbar = 129 (± 3, like the strip).
        val mid = heights[t0 + 8]
        assertTrue("composer height at t0+128ms: $mid (expected 129 ± 3)", mid in 126f..132f)
        for (f in (t0 + 16) until heights.size) {
            assertEquals("96 once collapsed (frame $f)", 96f, heights[f], 0.5f)
        }
    }

    // ---------------------------------------------------------- the snaps

    @Test
    fun sendClearsTheStripInTheSameFrameWithNoEmptyBand() {
        images = threeRed()
        mountState()
        assertEquals(68f, stripHeight(), 0.5f)
        writeFrozen { images = emptyList() }
        stepFrame()
        assertEquals("height 0 in the first frame after the change", 0f, stripHeight(), 0.5f)
        compose.onAllNodesWithTag("composer-thumb").assertCountEquals(0)
    }

    @Test
    fun addingImagesToAnEmptyStripSnapsTo68AtTheirSlots() {
        images = emptyList()
        mountState()
        assertEquals(0f, stripHeight(), 0.5f)
        writeFrozen { images = listOf(solidImage("a"), solidImage("b")) }
        stepFrame()
        assertEquals("height 68 in the first frame after the change", 68f, stripHeight(), 0.5f)
        assertEquals(0f, tileLeft(0), 0.5f)
        assertEquals(64f, tileLeft(1), 0.5f)
        compose.onAllNodesWithTag("composer-thumb").assertCountEquals(2)
    }

    @Test
    fun newImagesDuringACollapseSnapTheHeightBackTo68() {
        images = listOf(solidImage("a"))
        mountState()
        tapRemove(0)
        // Frames 1-2: the recomposition and the effect's start frame (68);
        // by frame 4 the collapse is visibly running but far from done.
        repeat(4) { stepFrame() }
        assertTrue("mid-collapse: ${stripHeight()}", stripHeight() in 1f..67f)
        writeFrozen { images = listOf(solidImage("a"), solidImage("b")) }
        stepFrame()
        assertEquals("the collapse is interrupted with a snap back to 68", 68f, stripHeight(), 0.5f)
        compose.onAllNodesWithTag("composer-thumb").assertCountEquals(2)
    }

    // ----------------------------------------------------- reduce motion

    @Test
    fun reduceMotionKeepsThe250msRemovalAnimations() {
        ReduceMotion.debugOverride = true
        images = threeRed()
        mountState()
        tapRemove(1)
        val cTrace = mutableListOf<Float>()
        repeat(24) {
            stepFrame()
            cTrace += tileLeft(1)
        }
        val t0 = lastFrameNear(cTrace, 128f)
        assertTrue("still mid-flight at t0+128ms: ${cTrace[t0 + 8]}", cTrace[t0 + 8] in 92f..98f)
        assertEquals("settled by t0+256ms", 64f, cTrace[t0 + 16], 0.5f)

        // The same 250 ms under reduce motion for the collapse: settle the
        // slide, then remove down to the last image.
        repeat(4) { stepFrame() }
        tapRemove(1) // [a, c] -> [a]
        repeat(4) { stepFrame() }
        tapRemove(0) // [a] -> []: the collapse
        val heights = mutableListOf<Float>()
        repeat(24) {
            stepFrame()
            heights += stripHeight()
        }
        val h0 = lastFrameNear(heights, 68f)
        assertTrue("the collapse still animates at t0+128ms: ${heights[h0 + 8]}", heights[h0 + 8] in 30f..36f)
        assertEquals("collapsed by t0+256ms", 0f, heights[h0 + 16], 0.5f)
    }

    // ---------------------------------------------------- the focus split

    /** 56's fixture: a glass box with tap-to-focus, one real field, the strip. */
    private fun mountFocusFixture() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                val state = rememberComposerMorphState(
                    alwaysCard = false,
                    hasImages = { images.isNotEmpty() },
                )
                morph = state
                Box(Modifier.fillMaxSize().background(Palette.Light.background)) {
                    Box(
                        Modifier
                            .size(360.dp, 120.dp)
                            .testTag("composer-glass")
                            .composerMorphHost(state),
                    ) {
                        BasicTextField(
                            state = field,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("composer-field")
                                .composerInput(state),
                            textStyle = TextStyle(color = Palette.Light.text),
                        )
                        StagedThumbnailStrip(
                            images = images,
                            onRemove = { id -> images = images.filterNot { it.id == id } },
                            controlModifier = Modifier.composerControl(state),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun removeNeverFocusesTheFieldAPhotoTapDoes() {
        images = threeRed()
        mountFocusFixture()
        val state = requireNotNull(morph)
        // Staged images make it a card from the first frame (progress 1);
        // the x tap must leave that as it was.
        val progressBefore = state.progress

        // x: its own click removes; the control marker keeps the glass's
        // tap-to-focus away; nothing focuses.
        compose.onNodeWithTag("composer-glass").performTouchInput { click(Offset(43f, 25f)) }
        compose.waitForIdle()
        assertFalse("x never focuses the field", state.isFocused)
        assertEquals("the morph progress is untouched", progressBefore, state.progress, 1e-3f)
        assertEquals(listOf("b", "c"), images.map { it.id })

        // A tap on the photo itself (tile 0's centre, away from x).
        compose.onNodeWithTag("composer-glass").performTouchInput { click(Offset(28f, 40f)) }
        compose.waitForIdle()
        assertTrue("a photo tap focuses the field", state.isFocused)
    }

    // ---------------------------------------------------------- the pixels

    @Test
    fun theTileEdgeIsTheHairlineAndTheImageIsRed() {
        mountStrip(listOf(solidImage("a")))
        val px = tilePixels()
        assertChannelNear("the left edge at mid-height (light hairline)", px[0, 28], Palette.Light.hairline)
        assertChannelNear("3 px inside the edge is the red image", px[3, 28], Color(0xFFFF0000))
    }

    @Test
    fun darkModeUsesTheDarkHairline() {
        mountStrip(listOf(solidImage("a")), appearance = Appearance.Dark)
        val px = tilePixels()
        assertChannelNear("the left edge at mid-height (dark hairline)", px[0, 28], Palette.Dark.hairline)
        assertChannelNear("3 px inside the edge is the red image", px[3, 28], Color(0xFFFF0000))
    }

    // xxhdpi (decision, round 31): at mdpi the cross's ~1.4 px stroke
    // straddles a pixel boundary and never reaches full white (216 on CI);
    // at 3 px/dp the stroke (~4.2 px) covers whole pixels, so the ≥ 240
    // white check stands. Coordinates below are the mdpi ones × 3.
    @Test
    @Config(sdk = [36], qualifiers = "w390dp-h400dp-xxhdpi")
    fun theRemoveButtonIsBlackOverRedWithAWhiteGlyph() {
        mountStrip(listOf(solidImage("a")))
        val px = tilePixels()
        val d = 3
        // 2 dp inside the circle's left edge, at its mid-height (13 dp):
        // black @ 0.55 over red = (115, 0, 0).
        assertChannelNear("the x fill over red", px[36 * d, 13 * d], Color(0xFF730000))
        // The brightest pixel within 2 dp of the x centre (43, 13) dp is the
        // white cross: scan that box in px.
        var brightest = 0
        for (dy in -2 * d..2 * d) {
            for (dx in -2 * d..2 * d) {
                val argb = px[43 * d + dx, 13 * d + dy].toArgb()
                val min = minOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
                if (min > brightest) brightest = min
            }
        }
        assertTrue("the x glyph has a white pixel (brightest min-channel $brightest)", brightest >= 240)
    }

    @Test
    fun theCentredCropHidesTheFixtureStripe() {
        mountStrip(listOf(fixtureStagedImage(0)))
        val px = tilePixels()
        var magenta = 0
        for (y in 0 until px.height) {
            for (x in 0 until px.width) {
                val argb = px[x, y].toArgb()
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                if (r >= 240 && g <= 30 && b >= 240) magenta++
            }
        }
        assertEquals("no magenta pixel survives the centred crop", 0, magenta)
    }

    @Test
    fun aThemeSwitchRecoloursTheBorderInTheNextFrame() {
        var appearance by mutableStateOf(Appearance.Light)
        compose.setContent {
            StripHost(listOf(solidImage("a")), onRemove = {}, appearance = appearance)
        }
        compose.waitForIdle()
        assertChannelNear("light border before the switch", tilePixels()[0, 28], Palette.Light.hairline)
        writeFrozen { appearance = Appearance.Dark }
        stepFrame()
        assertChannelNear("dark border in the next frame", tilePixels()[0, 28], Palette.Dark.hairline)
    }

    // -------------------------------------------------------------- RTL

    @Test
    fun rtlMirrorsTheStripAndSlidesTowardTheRight() {
        images = threeRed()
        compose.setContent {
            StripHost(
                images = images,
                onRemove = { id -> images = images.filterNot { it.id == id } },
                rtl = true,
            )
        }
        compose.waitForIdle()

        // Tile 0 hugs the strip's right edge; its x sits at the tile's
        // top-LEFT corner (TopEnd mirrors).
        assertEquals(342f, tileRight(0), 0.5f)
        assertEquals(286f, tileLeft(0), 0.5f)
        compose.onAllNodesWithTag("composer-thumb-remove")[0]
            .assertPositionInRootIsEqualTo(290.dp, 16.dp)

        // Remove tile 0: b slides from 222 toward 286 (to the right).
        tapRemove(0)
        val bTrace = mutableListOf<Float>()
        repeat(24) {
            stepFrame()
            bTrace += tileLeft(0)
        }
        assertTrue("tile b starts at 222: ${bTrace.first()}", bTrace.first() in 221.5f..222.5f)
        val t0 = lastFrameNear(bTrace, 222f)
        assertTrue("tile b at t0+128ms: ${bTrace[t0 + 8]}", bTrace[t0 + 8] in 250f..258f)
        assertEquals("settled at 286", 286f, bTrace[t0 + 16], 0.5f)
    }

    // ----------------------------------------------- the 8-image fades

    @Test
    fun eightImagesFadeTheTrailingEdgeAtRestAndSwapAfterScrolling() {
        val holder = arrayOfNulls<ScrollState>(1)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    val state = rememberScrollState()
                    SideEffect { holder[0] = state }
                    StagedThumbnailStrip(
                        images = eightRed(),
                        onRemove = {},
                        scrollState = state,
                        modifier = Modifier.width(342.dp),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        fun isRed(c: Color): Boolean {
            val argb = c.toArgb()
            return ((argb shr 16) and 0xFF) >= 230 && ((argb shr 8) and 0xFF) <= 30 && (argb and 0xFF) <= 30
        }

        // At rest (content 504, viewport 342, overflow 162): no leading
        // fade, a full trailing one — sample 6 px in from each edge.
        val rest = stripPixels()
        assertTrue("no leading fade at rest", isRed(rest[6, 40]))
        assertFalse("the trailing fade at rest", isRed(rest[330, 40]))

        // Scrolled to the end: the write needs the apply notifications
        // (the paused looper) and a frame to re-place and re-mask.
        val state = requireNotNull(holder[0])
        compose.runOnUiThread {
            runBlocking { state.scrollTo(state.maxValue) }
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val end = stripPixels()
        assertFalse("the leading fade after scrolling", isRed(end[6, 40]))
        assertTrue("no trailing fade at the end", isRed(end[330, 40]))
    }
}
