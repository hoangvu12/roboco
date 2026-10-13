package sh.roboco.android.image

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.rememberSmoothRoundedShape
import sh.roboco.android.transcript.widgets.imageTileRadius

/**
 * Ticket 125's viewer goldens (recorded by `:app:recordRoborazziDebug`,
 * verified by `:app:verifyRoborazziDebug`), at `w393dp-h852dp-xxhdpi`
 * (d = 3): the landscape fixture at rest in light and dark, zoomed to
 * 2.5× by a double tap at (100, 300) dp, a 200 dp dismiss drag held
 * (scale ≈ 0.93, background ≈ 0.53), and the zoom open at `t ≈ 0.5`
 * (clock-driven), with the tile's rounded crop morphing toward the fit
 * rect. Glyph ink is `Palette.text` (`#27272C` light, `#E8E8EA` dark).
 *
 * Rasterisation is host-dependent: record on the Ubuntu CI runner, like
 * every golden here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ImageViewerScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun imageViewerShownLight() {
        shown(Appearance.Light, "image/image_viewer_shown_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun imageViewerShownDark() {
        shown(Appearance.Dark, "image/image_viewer_shown_dark.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun imageViewerZoomedLight() {
        compose.setContent {
            RobocoTheme(Appearance.Light) { ViewerGoldenHost(landscapeGolden) }
        }
        compose.waitForIdle()
        // Double tap at (100, 300) dp → 2.5× centred there, settled.
        compose.runOnUiThread { goldenState!!.onDoubleTap(Offset(300f, 900f)) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("image/image_viewer_zoomed_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun imageViewerDragLight() {
        compose.setContent {
            RobocoTheme(Appearance.Light) { ViewerGoldenHost(landscapeGolden) }
        }
        compose.waitForIdle()
        // A 200 dp dismiss drag, held (nothing animates while held).
        compose.runOnUiThread {
            goldenState!!.beginDismissDrag()
            runBlocking { goldenState!!.drag(Offset(0f, 600f)) }
        }
        compose.onRoot().captureRoboImage("image/image_viewer_drag_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun imageViewerOpenMidwayLight() {
        compose.setContent {
            RobocoTheme(Appearance.Light) { ViewerGoldenHost(landscapeGolden, withTile = true, shown = false) }
        }
        compose.waitForIdle()
        // A real zoom open out of the tile, frozen mid-flight at t ≈ 0.5.
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread {
            val state = goldenState!!
            state.show(
                ImageViewerRequest(
                    reference = "shot",
                    preview = landscapeGolden,
                    sourceBounds = goldenTileBounds,
                    sourceCornerRadius = imageTileRadius(260f, 146f),
                ),
            )
        }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame() // frame 0: composed, layout
        // Advance frame-by-frame to the deterministic frame at t ≈ 0.5.
        var guard = 0
        while (goldenState!!.presence.value < 0.5f && guard < 40) {
            compose.mainClock.advanceTimeByFrame()
            guard++
        }
        compose.onRoot().captureRoboImage("image/image_viewer_open_midway_light.png")
    }

    // ------------------------------------------------------------- plumbing

    private var goldenState: ImageViewerState? = null
    private var goldenTileBounds = Rect.Zero

    private val landscapeGolden: ImageBitmap by lazy {
        imageViewerFixture(1600, 900, 0xFF5B43E8.toInt(), 0xFFF0A04B.toInt())
    }

    private fun shown(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) { ViewerGoldenHost(landscapeGolden) }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    /**
     * The golden host: the viewer in `Shown` over a striped backdrop,
     * with (for the open golden) the 260 × 146 dp source tile at
     * (18, 300) dp — the same geometry ticket 66's gallery uses.
     */
    @Composable
    private fun ViewerGoldenHost(
        bitmap: ImageBitmap,
        withTile: Boolean = false,
        shown: Boolean = true,
    ) {
        val state = remember { ImageViewerState() }
        if (shown) LaunchedEffect(state) { state.showForPreview(bitmap) }
        goldenState = state
        val palette = RobocoTheme.palette
        ImageViewerHost(state) {
            Box(Modifier.fillMaxSize().background(palette.background)) {
                Box(
                    Modifier
                        .padding(start = 24.dp, top = 90.dp)
                        .size(160.dp, 60.dp)
                        .background(palette.projectTone(1)),
                )
                Box(
                    Modifier
                        .padding(start = 210.dp, top = 180.dp)
                        .size(120.dp, 120.dp)
                        .background(palette.projectTone(5)),
                )
                if (withTile) {
                    Box(
                        Modifier
                            .absoluteOffset(18.dp, 300.dp)
                            .size(260.dp, 146.dp)
                            .onPlaced { coords ->
                                goldenTileBounds = Rect(coords.positionInWindow(), coords.size.toSize())
                            }
                            .clip(rememberSmoothRoundedShape(imageTileRadius(260f, 146f)))
                            .background(palette.projectTone(3))
                            .imageViewerSource("shot"),
                    )
                }
            }
        }
    }
}
