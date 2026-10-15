package sh.roboco.android.image

import android.graphics.Bitmap
import android.provider.Settings
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.view.WindowCompat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.rememberSmoothRoundedShape
import sh.roboco.android.transcript.widgets.imageTileRadius

/**
 * Ticket 125's viewer behaviour (Robolectric, `createAndroidComposeRule`,
 * `w393dp-h852dp-xxhdpi` so d = 3): the open timeline through the test
 * clock (background alpha 0 → 1 in ≈ 0.45 s, no overshoot, the tile
 * hidden while showing), the close back into the current tile rect and
 * the fade when the source left composition, the failed-load rule, the
 * full-resolution swap, pinch/double-tap/pan/drag numbers, predictive
 * back in every phase, Reduce Motion's cross-fade, the animator-scale-0
 * snap, the a11y surface and the system-bar icons. Written per the test
 * policy; the batch run executes it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class ImageViewerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Taps that fell through to the BackHandler under the viewer. */
    private var underPops = 0

    private lateinit var state: ImageViewerState

    @Before
    fun setUp() {
        underPops = 0
    }

    @After
    fun cleanUp() {
        ReduceMotion.resetForTest()
        Settings.Global.putFloat(
            compose.activity.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }

    // ------------------------------------------------------------- the host

    /** The test host: the viewer over a fake transcript with one tile. */
    private fun host(tileVisible: () -> Boolean = { true }) {
        state = ImageViewerState()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                ImageViewerHost(state) {
                    BackHandler(enabled = true) { underPops++ }
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        if (tileVisible()) FixtureTile(state)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** One 120 × 90 dp tile (radius 12) at a fixed spot, opening on tap. */
    @Composable
    private fun FixtureTile(state: ImageViewerState, reference: String = "a") {
        var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        val radius = imageTileRadius(120f, 90f)
        Box(
            Modifier
                .testTag("fixture-tile")
                .absoluteOffset(120.dp, 200.dp)
                .size(120.dp, 90.dp)
                .onPlaced { placed -> coords = placed }
                .clip(rememberSmoothRoundedShape(radius))
                .background(Color(0xFF5B43E8.toInt()))
                .clickable(interactionSource = null, indication = null) {
                    val placed = coords ?: return@clickable
                    state.show(
                        ImageViewerRequest(
                            reference = reference,
                            preview = fixtureTilePicture(),
                            sourceBounds = Rect(placed.positionInWindow(), placed.size.toSize()),
                            sourceCornerRadius = radius,
                        ),
                    )
                }
                .imageViewerSource(reference),
        )
    }

    private fun fixtureTilePicture(): ImageBitmap = tilePicture

    private val tilePicture: ImageBitmap by lazy {
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF3366CC.toInt())
        bitmap.asImageBitmap()
    }

    private val fullPicture: ImageBitmap by lazy {
        val bitmap = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFFEEDDAA.toInt())
        bitmap.asImageBitmap()
    }

    /** A request for [state], defaulting to the tile's current rect. */
    private fun request(
        source: Rect? = null,
        preview: ImageBitmap? = fixtureTilePicture(),
        loadFull: (suspend () -> ImageBitmap?)? = null,
        label: String? = null,
    ): ImageViewerRequest = ImageViewerRequest(
        reference = "a",
        preview = preview,
        sourceBounds = source,
        sourceCornerRadius = 12.dp,
        label = label,
        loadFull = loadFull,
    )

    /** The tile's rect in root px (the root starts at the window origin). */
    private fun tileBounds(): Rect = with(compose.density) {
        compose.onNodeWithTag("fixture-tile").getBoundsInRoot().toRect()
    }

    /** The viewer's current [key] value (background alpha, zoom). */
    private fun viewerValue(key: SemanticsPropertyKey<Float>): Float {
        val matcher = SemanticsMatcher("has ${key.name}") { it.config.contains(key) }
        val nodes = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("no ${key.name} node in the semantics tree", nodes.isNotEmpty())
        return nodes.first().config[key]
    }

    /** Opens the viewer from the tile's rect on the frozen clock. */
    private fun openFrozen(source: Rect? = null, label: String? = null) {
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread {
            state.show(request(source = source ?: tileBounds(), label = label))
        }
        Snapshot.sendApplyNotifications()
        // Frame 0: the page composes and measures, the open animation starts.
        compose.mainClock.advanceTimeByFrame()
    }

    /** Opens the viewer and settles it on the auto clock. */
    private fun openAndSettle(source: Rect? = null, label: String? = null) {
        compose.runOnUiThread {
            state.show(request(source = source ?: tileBounds(), label = label))
        }
        compose.waitForIdle()
    }

    // --------------------------------------------------------- open/close

    @Test
    fun theOpenFadesTheBackgroundTo1WithinHalfASecondWithoutOvershoot() {
        host()
        openFrozen()

        assertEquals(0f, viewerValue(ImageViewerBackgroundAlphaKey), 0.001f)
        assertEquals(ViewerPhase.Opening, state.phase)
        assertNotNull("the tile must be registered as the source", state.sourceEntry)

        var elapsed = 0
        while (elapsed < 700) {
            compose.mainClock.advanceTimeBy(50)
            elapsed += 50
            assertTrue(
                "the background alpha never overshoots (at ${elapsed} ms: " +
                    viewerValue(ImageViewerBackgroundAlphaKey) + ")",
                viewerValue(ImageViewerBackgroundAlphaKey) <= 1.0001f,
            )
        }
        assertEquals(1f, viewerValue(ImageViewerBackgroundAlphaKey), 0.01f)
        assertEquals(ViewerPhase.Shown, state.phase)
        assertEquals(1f, state.zoom.value, 0.001f)
        // The picture settles at the aspect-fit rect of the 64 × 32 fixture.
        val view = state.viewSize
        assertEquals(64f / 32f, state.fitRect.width / state.fitRect.height, 0.01f)
        assertTrue(state.fitRect.width <= view.width + 0.5f)
        // The tile is hidden (its layer alpha is 0) for the whole flight.
        assertNotNull(state.sourceEntry)
    }

    @Test
    fun closeZoomsBackIntoTheCurrentTileRectAndReleasesTheSource() {
        host()
        // The tile's semantics vanish while the viewer shows
        // (clearAndSetSemantics on the content), so capture them first.
        val tile = tileBounds()
        openFrozen(source = tile)
        compose.mainClock.advanceTimeBy(700)
        assertEquals(ViewerPhase.Shown, state.phase)

        compose.runOnUiThread { state.close() }
        val target = state.closeTarget ?: throw AssertionError("the close target is the tile")
        assertEquals(tile.left, target.left, 1f)
        assertEquals(tile.top, target.top, 1f)
        assertEquals(tile.right, target.right, 1f)
        assertEquals(tile.bottom, target.bottom, 1f)
        assertEquals(ViewerPhase.Closing, state.phase)

        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(700)
        assertEquals(ViewerPhase.Hidden, state.phase)
        assertNull("the tile reappears after the close", state.sourceEntry)
        compose.onNodeWithTag("image-preview").assertDoesNotExist()
    }

    @Test
    fun theCloseButtonRunsTheSameClose() {
        host()
        openFrozen()
        compose.mainClock.advanceTimeBy(700)
        compose.mainClock.autoAdvance = true

        compose.onNodeWithTag(ImageViewerCloseTag).performClick()
        compose.waitForIdle()
        assertEquals(ViewerPhase.Hidden, state.phase)
        assertNull(state.sourceEntry)
    }

    @Test
    fun closeFadesWhenTheSourceLeftComposition() {
        var tileVisible by mutableStateOf(true)
        host(tileVisible = { tileVisible })
        openAndSettle()
        assertEquals(ViewerPhase.Shown, state.phase)

        compose.runOnUiThread { tileVisible = false }
        compose.waitForIdle()
        compose.runOnUiThread { state.close() }
        assertNull("no attached source left", state.closeTarget)
        assertEquals(ViewerTransition.Fade, state.transition)
        compose.waitForIdle()
        assertEquals(ViewerPhase.Hidden, state.phase)
    }

    @Test
    fun aFailedFullLoadNeverOpensTheViewer() {
        host()
        compose.runOnUiThread {
            state.show(request(source = null, preview = null, loadFull = { null }))
        }
        compose.waitForIdle()
        assertEquals(ViewerPhase.Hidden, state.phase)
        assertFalse(state.isShowing)
        compose.onNodeWithTag("image-preview").assertDoesNotExist()
    }

    @Test
    fun theFullResolutionBitmapSwapsInWithoutAnimation() {
        host()
        val full = fullPicture
        compose.runOnUiThread {
            state.show(request(loadFull = { full }))
        }
        compose.waitForIdle()
        assertEquals(ViewerPhase.Shown, state.phase)
        assertEquals(full, state.displayBitmap)
        // F recomputed from the new size: same aspect, different rect.
        assertEquals(256f / 128f, state.fitRect.width / state.fitRect.height, 0.01f)
    }

    @Test
    fun showWhileShowingIsIgnored() {
        host()
        openAndSettle()
        compose.runOnUiThread {
            state.show(request(source = null, preview = fullPicture))
        }
        compose.waitForIdle()
        // The second request never replaced the first's bitmap.
        assertEquals(fixtureTilePicture(), state.displayBitmap)
    }

    // -------------------------------------------------------- zoom and pan

    @Test
    fun doubleTapZoomsTo25AroundTheTapAndBack() {
        host()
        openAndSettle()
        val view = state.viewSize
        val tap = Offset(view.width * 0.25f, view.height * 0.35f)

        compose.onNodeWithTag("image-preview").performTouchInput { doubleClick(tap) }
        compose.waitForIdle()
        assertEquals(2.5f, state.zoom.value, 0.01f)
        assertEquals(view.width / 2f - 2.5f * tap.x, state.tx.value, 2f)
        assertEquals(view.height / 2f - 2.5f * tap.y, state.ty.value, 2f)

        compose.onNodeWithTag("image-preview").performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        assertEquals(1f, state.zoom.value, 0.01f)
        assertEquals(0f, state.tx.value, 1f)
        assertEquals(0f, state.ty.value, 1f)
    }

    @Test
    fun aSingleTapDoesNothing() {
        host()
        openAndSettle()
        compose.onNodeWithTag("image-preview").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(1f, state.zoom.value, 0.001f)
        assertEquals(ViewerPhase.Shown, state.phase)
        assertEquals(0, underPops)
    }

    @Test
    fun aPinchSettlesAtTheZoomLimits() {
        host()
        openAndSettle()
        // 200 px apart → 1000 px apart: a factor of 5, capped.
        compose.onNodeWithTag("image-preview").performTouchInput {
            pinch(
                start0 = Offset(centerX - 100f, centerY),
                end0 = Offset(centerX - 500f, centerY),
                start1 = Offset(centerX + 100f, centerY),
                end1 = Offset(centerX + 500f, centerY),
                durationMillis = 400,
            )
        }
        compose.waitForIdle()
        assertEquals(5f, state.zoom.value, 0.01f)

        // Back in: a factor of 0.2 from 5 → 1, rubber-banded below 1
        // during the gesture and settled back at 1.
        compose.onNodeWithTag("image-preview").performTouchInput {
            pinch(
                start0 = Offset(centerX - 500f, centerY),
                end0 = Offset(centerX - 100f, centerY),
                start1 = Offset(centerX + 500f, centerY),
                end1 = Offset(centerX + 100f, centerY),
                durationMillis = 400,
            )
        }
        compose.waitForIdle()
        assertEquals(1f, state.zoom.value, 0.01f)
    }

    @Test
    fun anOvershootPinchShowsTheRubberBandedZoomThenSettles() {
        host()
        openAndSettle()
        // Drive the pinch math directly (nothing animates at rest):
        // a raw 8× shows 5·sqrt(8/5) ≈ 6.32, and the release settles at 5.
        compose.runOnIdle {
            runBlocking {
                state.pinch(8f, Offset(state.viewSize.width / 2f, state.viewSize.height / 2f), Offset.Zero)
            }
        }
        assertEquals(6.3246f, state.zoom.value, 0.01f)
        compose.runOnUiThread { state.settlePinch(Offset(state.viewSize.width / 2f, state.viewSize.height / 2f)) }
        compose.waitForIdle()
        assertEquals(5f, state.zoom.value, 0.01f)
        assertFalse(state.pinching)
    }

    @Test
    fun aPanPastTheEdgeRubberBandsAndSpringsBackToTheBound() {
        host()
        openAndSettle()
        // To 2.5× first.
        compose.onNodeWithTag("image-preview").performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        assertEquals(2.5f, state.zoom.value, 0.01f)

        // A direct pan past the right bound: the raw offset is 600 px,
        // the drawn offset is the rubber-banded 0.55-banded value.
        compose.runOnIdle {
            runBlocking {
                state.stopSettles()
                // pan() ADDS to the current offset, and the double tap left tx
                // at its own zoom-centred value (−0.75·W): pan to exactly 600.
                state.pan(Offset(600f - state.tx.value, 0f))
            }
        }
        val drawn = state.displayedT()
        assertTrue("the pan is rubber-banded ($drawn)", drawn.x < 600f && drawn.x > 0f)
        assertEquals(600f, state.tx.value, 0.01f)

        compose.runOnUiThread { state.endPan(Offset.Zero) }
        compose.waitForIdle()
        assertEquals(0f, state.tx.value, 1f)
        assertEquals(ViewerPhase.Shown, state.phase)
    }

    @Test
    fun aPanFlingDecaysAndStopsAtTheBounds() {
        host()
        openAndSettle()
        compose.onNodeWithTag("image-preview").performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        val view = state.viewSize
        val lower = view.width - view.width * 2.5f

        compose.onNodeWithTag("image-preview").performTouchInput {
            swipeWithVelocity(
                start = Offset(view.width * 0.75f, view.height * 0.5f),
                end = Offset(view.width * 0.15f, view.height * 0.5f),
                // A speed, not a signed velocity: the direction is start → end
                // (swipeWithVelocity rejects a negative endVelocity).
                endVelocity = 3000f,
                durationMillis = 200,
            )
        }
        compose.waitForIdle()
        val tx = state.tx.value
        assertTrue("the fling stays inside the pan bounds ($tx of [$lower, 0])", tx <= 0f && tx >= lower)
        assertTrue("the fling carried the content ($tx)", tx < -100f)
        assertFalse("the fling has settled", state.tx.isRunning)
    }

    // ---------------------------------------------------- drag to dismiss

    @Test
    fun a150DpDragDownReleasesToClose() {
        host()
        openAndSettle()
        compose.onNodeWithTag("image-preview").performTouchInput {
            swipe(
                start = Offset(centerX, centerY),
                end = Offset(centerX, centerY + 450f), // 150 dp at d = 3
                durationMillis = 600,
            )
        }
        compose.waitForIdle()
        assertEquals(ViewerPhase.Hidden, state.phase)
    }

    @Test
    fun a50DpDragSpringsBackWithTheBackgroundAlphaRestored() {
        host()
        openAndSettle()
        compose.mainClock.autoAdvance = false
        // Drive the drag directly (nothing animates at rest): a 150 px
        // (50 dp) offset with no release velocity must spring back.
        compose.runOnIdle {
            runBlocking {
                state.beginDismissDrag()
                state.drag(Offset(0f, 150f))
            }
        }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame() // apply the drag write
        assertEquals(ViewerPhase.Dragging, state.phase)
        // Progress = 150 / (0.5 · H): the background fades to ≈ 0.88.
        val alpha = viewerValue(ImageViewerBackgroundAlphaKey)
        assertTrue("the background fades with the drag ($alpha)", alpha < 0.95f && alpha > 0.7f)
        compose.runOnUiThread { state.endDrag(Offset.Zero) }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(700)
        assertEquals(ViewerPhase.Shown, state.phase)
        assertEquals(0f, state.dragOffset.value.y, 1f)
        assertEquals(1f, viewerValue(ImageViewerBackgroundAlphaKey), 0.01f)
    }

    @Test
    fun aFastShortFlickClosesOnVelocity() {
        host()
        openAndSettle()
        compose.onNodeWithTag("image-preview").performTouchInput {
            swipeWithVelocity(
                start = Offset(centerX, centerY),
                end = Offset(centerX, centerY + 60f), // 20 dp
                endVelocity = 3000f, // 1000 dp/s > 800
                // 60 px ending at 3000 px/s cannot take 60 ms (the gesture
                // generator rejects it); 40 ms is the length it accepts.
                durationMillis = 40,
            )
        }
        compose.waitForIdle()
        assertEquals(ViewerPhase.Hidden, state.phase)
    }

    @Test
    fun aHorizontalDragAtZoom1DoesNothing() {
        host()
        openAndSettle()
        compose.onNodeWithTag("image-preview").performTouchInput {
            swipe(
                start = Offset(centerX, centerY),
                end = Offset(centerX + 400f, centerY),
                durationMillis = 600,
            )
        }
        compose.waitForIdle()
        assertEquals(ViewerPhase.Shown, state.phase)
        assertEquals(Offset.Zero, state.dragOffset.value)
        assertEquals(1f, viewerValue(ImageViewerBackgroundAlphaKey), 0.01f)
    }

    // ---------------------------------------------------- predictive back

    @Test
    fun predictiveBackShrinksAndFadesThenCancelsBack() {
        host()
        openAndSettle()
        val dispatcher = compose.activity.onBackPressedDispatcher

        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(
                BackEventCompat(100f, 100f, 0.4f, BackEventCompat.EDGE_LEFT),
            )
        }
        compose.waitForIdle()
        assertEquals(ViewerPhase.Back, state.phase)
        assertEquals(0.4f, state.backProgress.value, 0.01f)
        // 1 − q on the background.
        val alpha = viewerValue(ImageViewerBackgroundAlphaKey)
        assertEquals(0.6f, alpha, 0.05f)

        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertEquals(ViewerPhase.Shown, state.phase)
        assertEquals(0f, state.backProgress.value, 0.01f)
        assertEquals(0, underPops)
    }

    @Test
    fun predictiveBackCommitClosesWithoutReachingTheContentBelow() {
        host()
        openAndSettle()
        val dispatcher = compose.activity.onBackPressedDispatcher

        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(
                BackEventCompat(100f, 100f, 1f, BackEventCompat.EDGE_LEFT),
            )
        }
        compose.waitForIdle()
        assertEquals(ViewerPhase.Back, state.phase)
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(ViewerPhase.Hidden, state.phase)
        assertEquals("back is handled by the viewer, not the content below", 0, underPops)
    }

    @Test
    fun aBackPressDuringTheOpenAnimationReversesIt() {
        host()
        openFrozen()
        compose.mainClock.advanceTimeBy(100) // 100 ms into the open
        assertTrue(state.openStarted)
        assertEquals(ViewerPhase.Opening, state.phase)

        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
        }
        compose.mainClock.advanceTimeByFrame() // the handler starts (ignored branch)
        assertEquals(ViewerPhase.Opening, state.phase)
        compose.runOnUiThread { dispatcher.onBackPressed() }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(700)
        assertEquals(ViewerPhase.Hidden, state.phase)
        assertEquals(0, underPops)
        // The session screen underneath is still there.
        compose.onNodeWithTag("fixture-tile").assertExists()
    }

    // ------------------------------------------------------ reduce motion

    @Test
    fun reduceMotionOpensAsA200msCrossFadeFromNowhere() {
        ReduceMotion.debugOverride = true
        host()
        openFrozen()
        assertEquals(ViewerTransition.Fade, state.transition)

        compose.mainClock.advanceTimeBy(100)
        // ≈ Half-way through the 200 ms easeInOut cross-fade (the frame
        // the tween starts on shifts the play time by up to 16 ms).
        val midway = viewerValue(ImageViewerBackgroundAlphaKey)
        assertTrue("the cross-fade is near its midpoint at 100 ms ($midway)", midway in 0.25f..0.6f)
        compose.mainClock.advanceTimeBy(200)
        assertEquals(ViewerPhase.Shown, state.phase)
        assertEquals(1f, viewerValue(ImageViewerBackgroundAlphaKey), 0.01f)
    }

    @Test
    fun anAnimatorScaleOfZeroSnapsTheCrossFadeOpen() {
        // RobocoTheme installs ReduceMotion, which reads the animator scale:
        // 0 counts as reduced, so the open is the cross-fade. On a device the
        // window recomposer's MotionDurationScale (0) then snaps the tween;
        // the compose-test recomposer installs no MotionDurationScale (scale
        // 1), so here the 200 ms fade runs and the test waits it out.
        Settings.Global.putFloat(
            compose.activity.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            0f,
        )
        host()
        openFrozen()
        assertEquals(ViewerTransition.Fade, state.transition)
        compose.mainClock.advanceTimeBy(ViewerFadeMs + 32L)
        assertEquals(ViewerPhase.Shown, state.phase)
        assertEquals(1f, viewerValue(ImageViewerBackgroundAlphaKey), 0.01f)
        compose.runOnUiThread { state.close() }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ViewerFadeMs + 32L)
        assertEquals(ViewerPhase.Hidden, state.phase)
    }

    // ------------------------------------------------------ a11y and bars

    @Test
    fun talkbackSeesTheButtonsThenTheImageAndNothingBelow() {
        host()
        openAndSettle(label = "Calculation result: 8")

        val picture = compose.onAllNodesWithTag("image-preview").fetchSemanticsNodes()
        assertEquals(1, picture.size)
        assertEquals(Role.Image, picture[0].config[SemanticsProperties.Role])
        assertEquals(listOf("Calculation result: 8"), picture[0].config[SemanticsProperties.ContentDescription])

        val close = compose.onAllNodesWithContentDescription(CloseImagePreviewLabel).fetchSemanticsNodes()
        assertEquals(1, close.size)
        assertEquals(Role.Button, close[0].config[SemanticsProperties.Role])

        val share = compose.onAllNodesWithContentDescription(ShareImageLabel).fetchSemanticsNodes()
        assertEquals(1, share.size)
        assertEquals(Role.Button, share[0].config[SemanticsProperties.Role])

        // Nothing under the viewer is focusable while it shows.
        compose.onNodeWithTag("fixture-tile").assertDoesNotExist()
    }

    @Test
    fun thePictureFallsBackToTheImageLabel() {
        host()
        openAndSettle(label = null)
        val picture = compose.onAllNodesWithTag("image-preview").fetchSemanticsNodes()
        assertEquals(listOf(ImageFallbackLabel), picture[0].config[SemanticsProperties.ContentDescription])
    }

    @Test
    fun systemBarIconsGoLightWhileShownAndBackAfterClose() {
        host()
        val window = compose.activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = true // dark icons in light mode
        controller.isAppearanceLightNavigationBars = true

        openAndSettle()
        compose.waitForIdle()
        assertFalse("light icons on the black page", controller.isAppearanceLightStatusBars)
        assertFalse(controller.isAppearanceLightNavigationBars)

        compose.onNodeWithTag(ImageViewerCloseTag).performClick()
        compose.waitForIdle()
        assertTrue("restored after the close", controller.isAppearanceLightStatusBars)
        assertTrue(controller.isAppearanceLightNavigationBars)
    }

    @Test
    fun theCloseAndShareGeometryFollowsTheSafeArea() {
        host()
        openAndSettle()
        val d = compose.density.density
        val close = with(compose.density) {
            compose.onNodeWithTag(ImageViewerCloseTag).getBoundsInRoot().toRect()
        }
        val share = with(compose.density) {
            compose.onNodeWithTag(ImageViewerShareTag).getBoundsInRoot().toRect()
        }
        // 44 × 44 dp discs, 16 dp in from the horizontal edges, at least
        // 8 dp below the window top (the host's insets add on top).
        assertEquals(44f * d, close.width, 1f)
        assertEquals(44f * d, close.height, 1f)
        assertEquals(16f * d, close.left, 1f)
        val view = state.viewSize
        assertEquals(view.width - 16f * d - 44f * d, share.left, 1f)
        assertTrue("the close button sits below the top edge", close.top >= 8f * d - 0.5f)
        assertTrue("share mirrors close at the trailing edge", share.left > close.left)
    }
}
