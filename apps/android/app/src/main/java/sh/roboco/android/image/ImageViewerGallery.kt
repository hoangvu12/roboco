package sh.roboco.android.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.roboco.android.core.Face
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Palette
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.rememberSmoothRoundedShape
import sh.roboco.android.design.uiStyle
import sh.roboco.android.transcript.widgets.imageTileRadius

/**
 * The "Image viewer" section of the debug design gallery (ticket 125,
 * step 12): three tiles drawn like ticket 66's — a 260 × 146 landscape
 * row tile (radius 14), a 76 × 76 user-message thumbnail (radius 12)
 * and a 120 × 213 portrait (radius 12, the shorter side is not
 * > 120 dp) — each carrying `imageViewerSource` and opening the viewer
 * with a downsampled fixture and a `loadFull` that returns the
 * full-resolution fixture after 600 ms (the sharpening swap).
 *
 * Live controls (foundation only, no Material):
 *  - **Off-screen source** removes the tapped tile from composition
 *    300 ms after opening, so the close exercises the fade-out path
 *    (the zoom target re-read finds nothing attached);
 *  - the gallery's own "Force reduce motion" toggle (ticket 22) makes
 *    the open/close the 200 ms cross-fade — nothing is wired here.
 *
 * The fixture bitmaps are generated in code (no assets): the ticket's
 * 1600 × 900, 900 × 1600 and 1200 × 1200 pictures, each a two-stop
 * diagonal gradient in `palette.projectTone(0..2)` (paired with the
 * tone four steps around the 8-tone wheel) with a 100 px white grid
 * and a centred black circle, so zoom and crop are easy to judge.
 *
 * Registered in `Gallery.sections` as "Image viewer"
 * (slug `image_viewer`), `GalleryLayout.Fill`: the section owns its
 * own `ImageViewerHost` filling the body.
 */
@Composable
fun ImageViewerGallerySection() {
    val state = rememberImageViewerState()
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val scope = rememberCoroutineScope()

    var offscreen by remember { mutableStateOf(false) }
    var hiddenTile by remember { mutableStateOf<String?>(null) }

    // When the viewer closes, the removed tile comes back for the next demo.
    LaunchedEffect(state.phase) {
        if (state.phase == ViewerPhase.Hidden) hiddenTile = null
    }

    val fixtures = remember(palette) { galleryFixtures(palette) }
    // The off-screen demo: when the toggle is on, the tapped tile leaves
    // composition 300 ms after opening, so the close must fade.
    val removeTileAfterOpen: (String) -> Unit = { reference ->
        if (offscreen) {
            scope.launch {
                delay(300)
                hiddenTile = reference
            }
        }
    }

    ImageViewerHost(state) {
        Column(
            Modifier
                .fillMaxSize()
                .background(palette.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (hiddenTile != fixtures[0].reference) {
                ViewerGalleryTile(fixtures[0], state, onOpen = removeTileAfterOpen)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (hiddenTile != fixtures[1].reference) {
                    ViewerGalleryTile(fixtures[1], state, onOpen = removeTileAfterOpen)
                }
                if (hiddenTile != fixtures[2].reference) {
                    ViewerGalleryTile(fixtures[2], state, onOpen = removeTileAfterOpen)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ViewerGalleryControl(
                    label = "Off-screen source",
                    active = offscreen,
                    onClick = { offscreen = !offscreen },
                )
            }
            BasicText(
                "Tap a tile: it zooms out of the tile, sharpens after 0.6 s, " +
                    "pinches 1×–5×, double-taps to 2.5×, pans, drags down to " +
                    "dismiss, backs and shares. The gallery's reduce-motion " +
                    "toggle swaps the zoom for a 200 ms cross-fade.",
                style = uiStyle(Face.Mono, typeScale.fixed(11f), palette.secondary),
            )
        }
    }
}

/** One tile + its full-resolution and preview fixtures (both lazy). */
private class GalleryFixture(
    val reference: String,
    private val width: Int,
    private val height: Int,
    private val toneA: Int,
    private val toneB: Int,
    val tileWidth: Dp,
    val tileHeight: Dp,
) {
    /** The full-resolution fixture (the `loadFull` result). */
    val full: ImageBitmap by lazy { imageViewerFixture(width, height, toneA, toneB) }

    /** The downsampled start frame (a third of each side, same grid). */
    val preview: ImageBitmap by lazy { imageViewerFixture(width / 3, height / 3, toneA, toneB) }
}

/** The three fixtures of the current appearance (projectTone 0..2). */
private fun galleryFixtures(palette: Palette): List<GalleryFixture> = listOf(
    GalleryFixture(
        reference = "gallery://viewer-landscape.png",
        width = 1600,
        height = 900,
        toneA = palette.projectTone(0).toArgb(),
        toneB = palette.projectTone(4).toArgb(),
        tileWidth = 260.dp,
        tileHeight = 146.dp,
    ),
    GalleryFixture(
        reference = "gallery://viewer-square.png",
        width = 1200,
        height = 1200,
        toneA = palette.projectTone(1).toArgb(),
        toneB = palette.projectTone(5).toArgb(),
        tileWidth = 76.dp,
        tileHeight = 76.dp,
    ),
    GalleryFixture(
        reference = "gallery://viewer-portrait.png",
        width = 900,
        height = 1600,
        toneA = palette.projectTone(2).toArgb(),
        toneB = palette.projectTone(6).toArgb(),
        tileWidth = 120.dp,
        tileHeight = 213.dp,
    ),
)

/**
 * One gallery tile, drawn like ticket 66's: an `Image` clipped to the
 * continuous-corner radius of its size, a `controlFill` placeholder
 * under it, a tap that reports the unclipped window rect, and
 * `imageViewerSource` so the viewer can hide and re-target it.
 */
@Composable
private fun ViewerGalleryTile(
    fixture: GalleryFixture,
    state: ImageViewerState,
    onOpen: (String) -> Unit = {},
) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val radius = imageTileRadius(fixture.tileWidth.value, fixture.tileHeight.value)
    Box(
        Modifier
            .size(fixture.tileWidth, fixture.tileHeight)
            .onPlaced { placed -> coords = placed }
            .clip(rememberSmoothRoundedShape(radius))
            .background(RobocoTheme.palette.controlFill)
            .clickable(interactionSource = null, indication = null) {
                val placed = coords ?: return@clickable
                state.show(
                    ImageViewerRequest(
                        reference = fixture.reference,
                        preview = fixture.preview,
                        sourceBounds = Rect(placed.positionInWindow(), placed.size.toSize()),
                        sourceCornerRadius = radius,
                        loadFull = {
                            delay(600)
                            fixture.full
                        },
                    ),
                )
                onOpen(fixture.reference)
            }
            .imageViewerSource(fixture.reference),
    ) {
        Image(
            fixture.preview,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

/** One gallery control: a `BasicText` label in a clickable capsule (ticket 22's style). */
@Composable
private fun ViewerGalleryControl(label: String, active: Boolean, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Box(
        Modifier
            .clip(CapsuleShape)
            .background(if (active) palette.accentSoft else palette.controlFill)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        BasicText(
            label,
            style = uiStyle(
                Face.SansMedium,
                typeScale.fixed(13f),
                if (active) palette.accent else palette.text,
            ),
            maxLines = 1,
        )
    }
}

/**
 * The generated fixture picture (also the previews' backdrop): a
 * two-stop diagonal gradient [toneA] → [toneB] with a 100 px white grid
 * and a centred black circle. Gallery fixture colours, not iOS values.
 */
internal fun imageViewerFixture(width: Int, height: Int, toneA: Int, toneB: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(
        0f,
        0f,
        width.toFloat(),
        height.toFloat(),
        toneA,
        toneB,
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    paint.shader = null
    paint.color = 0xFFFFFFFF.toInt()
    paint.strokeWidth = 2f
    var x = 100
    while (x < width) {
        canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), paint)
        x += 100
    }
    var y = 100
    while (y < height) {
        canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), paint)
        y += 100
    }
    paint.color = 0xFF000000.toInt()
    canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) * 0.18f, paint)
    return bitmap.asImageBitmap()
}
