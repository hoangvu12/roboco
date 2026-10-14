package sh.roboco.android.newsession

import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme

/**
 * [HandoffCaptureState.capture] (android-ui-parity #94) — the handoff
 * capture over a fixture page, at **mdpi** (`w400dp-h800dp-mdpi`, px =
 * dp): a 400 × 800 page whose composer is a red 300 × 120 box at
 * (50, 600) with black text in its 60 dp text slot, the page root
 * carrying [Modifier.handoffScene] and the composer root
 * [Modifier.handoffComposer] exactly as 93 wires them.
 *
 * Pinned: the frames (composer and scene, ±1 px), the composer bitmap's
 * size, its red background and its hidden text, the scene bitmap's
 * composer-hidden background at the composer's centre, the unclipped
 * scene (an opaque (2, 2) pixel) with [CapturedDraftHandoff.sceneShape]
 * the same instance, the text frame's insets, the freeze frame (the
 * on-screen pixels still show the composer and its text throughout the
 * capture, read from a wrapper layer that records what the screen
 * draws), and the detached guard (null + both flags false).
 *
 * `awaitDrawn` is the production two-frame wait plus the screen read;
 * `withFrameNanos` advances under the auto-advancing Robolectric clock
 * (93's settle test drives the same mechanism). If
 * [GraphicsLayer.toImageBitmap] turns out unsupported by the pinned
 * Robolectric version, move this class to `androidTest` and run it with
 * `connectedDebugAndroidTest` (the ticket's fallback).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h800dp-mdpi")
class HandoffCaptureTest {

    @get:Rule
    val compose = createComposeRule()

    // ── the fixture state (one mount per test) ───────────────────────────

    private var state: HandoffCaptureState? = null
    private var scope: CoroutineScope? = null
    private var screenLayer: GraphicsLayer? = null
    private var hostView: View? = null
    private var result: CapturedDraftHandoff? = null
    private var captureDone = false

    /** The on-screen pixels each `awaitDrawn` saw (the freeze-frame proof). */
    private val screens = mutableListOf<PixelMap>()

    /** Detaches the composer (the "page left composition" case). */
    private val composerShown = mutableStateOf(true)

    @Before
    fun resetFixture() {
        state = null
        scope = null
        screenLayer = null
        hostView = null
        result = null
        captureDone = false
        screens.clear()
        composerShown.value = true
    }

    // ── the mount ───────────────────────────────────────────────────────

    /**
     * The fixture page: a wrapper that records what the screen shows (its
     * own [GraphicsLayer], read inside `awaitDrawn` — the same pixels the
     * screen would show, freeze included), and inside it the 400 × 800
     * scene root with the composer and its text slot.
     */
    private fun mount(sceneShape: Shape = SheetShape) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                val density = LocalDensity.current
                val view = LocalView.current
                hostView = view
                val scene = rememberGraphicsLayer()
                val composerLayer = rememberGraphicsLayer()
                val screen = rememberGraphicsLayer()
                val s = remember {
                    HandoffCaptureState(
                        sceneLayer = scene,
                        composerLayer = composerLayer,
                        awaitDrawn = {
                            // The production two-frame wait (the second
                            // resumes after the first frame's draw pass
                            // re-recorded the layers), then the screen read.
                            withFrameNanos {}
                            withFrameNanos {}
                            // Robolectric frames measure and lay out but
                            // never draw the window, so the layers would
                            // keep their old recordings: draw once here,
                            // as a device frame would have.
                            drawOnce(view)
                            val shot = screenLayer?.toImageBitmap()?.toPixelMap()
                            if (shot != null) screens += shot
                        },
                    )
                }
                s.sceneShape = sceneShape
                state = s
                screenLayer = screen
                scope = rememberCoroutineScope()

                Box(Modifier.fillMaxSize().testTag("fixture-root")) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .drawWithContent {
                                // DrawScope's record, as production: the read
                                // below replays it outside the draw pass.
                                screen.record(size.toIntSize()) {
                                    this@drawWithContent.drawContent()
                                }
                                drawLayer(screen)
                            },
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .handoffScene(s)
                                .background(PageBackground),
                        ) {
                            if (composerShown.value) {
                                Box(
                                    Modifier
                                        .offset(x = 50.dp, y = 600.dp)
                                        .size(300.dp, 120.dp)
                                        .handoffComposer(s)
                                        .background(Color.Red),
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(60.dp)
                                            .graphicsLayer { this.alpha = if (s.textHidden) 0f else 1f }
                                            .onGloballyPositioned { coords ->
                                                val b = coords.boundsInRoot()
                                                val topPx = with(density) { 14.dp.toPx() }
                                                val bottomPx = with(density) { 6.dp.toPx() }
                                                val minPx = with(density) { 20.dp.toPx() }
                                                s.onTextRect(
                                                    Rect(
                                                        left = b.left,
                                                        top = b.top + topPx,
                                                        right = b.right,
                                                        bottom = b.top + topPx +
                                                            max(minPx, b.height - topPx - bottomPx),
                                                    )
                                                )
                                            },
                                    ) {
                                        BasicText(
                                            FixtureText,
                                            Modifier.padding(top = 14.dp, bottom = 6.dp),
                                            style = TextStyle(color = Color.Black),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Runs one capture on the composition's scope and waits it out. */
    private fun runCapture() {
        val s = checkNotNull(state) { "mount first" }
        val sc = checkNotNull(scope) { "mount first" }
        val view = checkNotNull(hostView) { "mount first" }
        compose.runOnIdle {
            // The page as the user sees it before the send: Robolectric
            // never drew it (layout only), so the layers are still empty
            // and capture's zero-size guard would return null.
            drawOnce(view)
            sc.launch {
                // A bounded capture: an unsupported host fails the test
                // with a null result instead of hanging it.
                result = runCatching { withTimeout(5_000) { s.capture(FixtureText) } }.getOrNull()
                captureDone = true
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) { captureDone }
    }

    /**
     * One draw pass of the whole window into a throwaway software bitmap —
     * the device's draw pass, which Robolectric never runs on its own (its
     * traversals stop after layout). The `drawWithContent` blocks record
     * the scene, composer and screen layers exactly as a frame would.
     */
    private fun drawOnce(view: View) {
        val root = view.rootView
        if (root.width == 0 || root.height == 0) return
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bitmap))
        bitmap.recycle()
    }

    // ── the capture's geometry and bitmaps ─────────────────────────────

    @Test
    fun captureReturnsTheFramesAndBothBitmaps() {
        mount()
        runCapture()
        val r = checkNotNull(result) { "the capture returned null" }

        // The composer's root position and size in px (±1).
        assertEquals(50f, r.composerFrame.left, 1f)
        assertEquals(600f, r.composerFrame.top, 1f)
        assertEquals(300f, r.composerFrame.width, 1f)
        assertEquals(120f, r.composerFrame.height, 1f)

        // The page root's frame: the full 400 × 800 page.
        assertEquals(0f, r.sceneFrame.left, 1f)
        assertEquals(0f, r.sceneFrame.top, 1f)
        assertEquals(400f, r.sceneFrame.width, 1f)
        assertEquals(800f, r.sceneFrame.height, 1f)

        // The sent body rides along.
        assertEquals(FixtureText, r.text)

        // The composer bitmap is the composer's own size.
        assertEquals(300, r.composer.width)
        assertEquals(120, r.composer.height)
        // The scene bitmap is the page's own size, unclipped.
        assertEquals(400, r.scene.width)
        assertEquals(800, r.scene.height)
    }

    @Test
    fun theComposerBitmapHidesTheTextAndKeepsItsBackground() {
        mount()
        runCapture()
        val r = checkNotNull(result) { "the capture returned null" }

        val composerMap = r.composer.toPixelMap()
        // A background pixel below the text slot: pure red.
        val background = channels(composerMap[10, 100])
        assertTrue(
            "expected the composer's red background, was ${background.contentToString()}",
            background[0] > 250f && background[1] < 5f && background[2] < 5f,
        )
        // No black text pixels in the text area (the composer's local
        // coords: the slot inset by the 14/6 dp insets).
        assertFalse(
            "the composer bitmap must not contain the hidden text",
            hasBlackPixels(composerMap, left = 0, top = 14, right = 299, bottom = 53),
        )
    }

    @Test
    fun theSceneBitmapHidesTheComposer() {
        mount()
        runCapture()
        val r = checkNotNull(result) { "the capture returned null" }

        // At the composer's centre the scene shows the page background,
        // not the (hidden) composer.
        val sceneMap = r.scene.toPixelMap()
        val centre = channels(sceneMap[200, 660])
        val page = channels(PageBackground)
        assertTrue(
            "expected the page background at the composer's centre, was ${centre.contentToString()}",
            close(centre, page),
        )
    }

    @Test
    fun theSceneIsUnclippedAndCarriesTheSheetShape() {
        val shape: Shape = SheetShape
        mount(shape)
        runCapture()
        val r = checkNotNull(result) { "the capture returned null" }

        assertSame(shape, r.sceneShape)
        // Unclipped: the corner pixel inside the 38 dp rounded corner is
        // opaque background (95 applies the clip; here nothing does).
        val corner = r.scene.toPixelMap()[2, 2]
        assertEquals(1f, corner.alpha, 1e-3f)
        assertTrue(close(channels(corner), channels(PageBackground)))
    }

    @Test
    fun theTextFrameIsTheSlotInsetByFourteenAndSix() {
        mount()
        runCapture()
        val r = checkNotNull(result) { "the capture returned null" }

        // composerTop (600) + slotTop (0) + the 14 dp top inset.
        assertEquals(614f, r.textFrame.top, 1f)
        assertEquals(50f, r.textFrame.left, 1f)
        assertEquals(350f, r.textFrame.right, 1f)
        // max(20 dp, slotHeight 60 − 14 − 6) = 40 dp.
        assertTrue("expected a text frame at least 20 dp tall, was ${r.textFrame.height}", r.textFrame.height >= 20f)
        assertEquals(40f, r.textFrame.height, 1f)
    }

    @Test
    fun theScreenKeepsShowingTheComposerAndItsTextThroughout() {
        mount()
        runCapture()
        checkNotNull(result) { "the capture returned null" }

        // Two awaitDrawn calls: one after the text hid, one after the
        // composer hid. The freeze keeps both on screen.
        assertTrue("expected two on-screen reads, was ${screens.size}", screens.size >= 2)
        for ((index, screen) in screens.withIndex()) {
            // The composer is still red on screen…
            val centre = channels(screen[200, 660])
            assertTrue(
                "screen $index: expected the composer's red at its centre, was ${centre.contentToString()}",
                centre[0] > 250f && centre[1] < 5f && centre[2] < 5f,
            )
            // …and its text is still there (root coords: the slot area).
            assertTrue(
                "screen $index: expected the composer's text on screen",
                hasBlackPixels(screen, left = 50, top = 614, right = 349, bottom = 653),
            )
        }
    }

    @Test
    fun aDetachedComposerReturnsNullAndTouchesNoFlags() {
        mount()
        compose.runOnIdle { composerShown.value = false }
        compose.waitForIdle()
        runCapture()

        val s = checkNotNull(state)
        assertNull(result)
        assertFalse(s.textHidden)
        assertFalse(s.composerHidden)
    }

    // ── pixel helpers ──────────────────────────────────────────────────

    private fun channels(pixel: Color): FloatArray =
        floatArrayOf(pixel.red * 255f, pixel.green * 255f, pixel.blue * 255f)

    private fun close(a: FloatArray, b: FloatArray): Boolean =
        a.indices.all { i -> kotlin.math.abs(a[i] - b[i]) <= 2f }

    /** True when any pixel in the rect is near-black (text glyphs). */
    private fun hasBlackPixels(map: PixelMap, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        for (y in top..bottom) {
            for (x in left..right) {
                val c = map[x, y]
                if (c.red < 0.25f && c.green < 0.25f && c.blue < 0.25f) return true
            }
        }
        return false
    }

    // ── the fixture's constants ────────────────────────────────────────

    /** The light theme's `Palette.background` (the fixture's page fill). */
    private val PageBackground = Color(0xFFF3F3F5)

    /** 31's shape, the fixture's stand-in. */
    private val SheetShape = RoundedCornerShape(
        topStart = 38.dp,
        topEnd = 38.dp,
    )

    private val FixtureText = "Add a dark mode toggle to settings"
}
