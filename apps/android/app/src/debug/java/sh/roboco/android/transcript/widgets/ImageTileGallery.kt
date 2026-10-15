package sh.roboco.android.transcript.widgets

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.LocalGalleryCapture
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.TranscriptPalette
import sh.roboco.android.design.color
import sh.roboco.android.design.uiStyle
import sh.roboco.android.transcript.AttachmentReader
import sh.roboco.android.transcript.TranscriptActions
import sh.roboco.android.transcript.TranscriptImageBinding
import sh.roboco.android.transcript.TranscriptImageLoader
import uniffi.roboco_core.ColorRole

/**
 * The "Image tile" section of the debug design gallery (ticket 66): the
 * Rust geometry of both tile kinds — three 76 dp user-message thumbnails
 * right-aligned at x = 299/217/135 and a 260 dp image row at x = 18, y =
 * 100 — each over its own circular-arc `ChipBackground` placeholder, as
 * ticket 64's box painter would leave under it.
 *
 * Live controls (foundation only, no Material):
 *  - **Reload** empties the loader's LRU and re-keys the tiles, so the
 *    next 600 ms read replays the load (137: the swap is instant, no
 *    cross-fade any more);
 *  - **Fail one** makes the first thumbnail's read throw — it stays a
 *    placeholder, the others still load;
 *  - **Swap references** flips the 260 tile between two references while
 *    the target's 3 s read is in flight: the tile goes empty (the old
 *    picture never shows), the cached one returns instantly, and a swap
 *    back mid-flight shows the claim — the late result never appears but
 *    does land in the cache;
 *  - the `openImage: <reference> @ <bounds>` line records the last tap.
 *
 * The loader is a gallery-local [TranscriptImageLoader] (never the app
 * singleton) behind [TranscriptImageBinding] on the fake engine "gallery",
 * so the real decoder runs on device. Under Roborazzi capture the section
 * renders the deterministic [ImageTileFixture] instead (the sync-hit
 * path, picture at alpha 1 in the first frame, no reader at all).
 *
 * Registered in `Gallery.sections` as "Image tile" (slug `image_tile`).
 */
@Composable
fun ImageTileGallerySection() {
    if (LocalGalleryCapture.current) {
        ScaledStage { ImageTileFixture(loaded = true) }
        return
    }
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current

    var reloadKey by remember { mutableIntStateOf(0) }
    var failFirst by remember { mutableStateOf(false) }
    var swapped by remember { mutableStateOf(false) }
    var lastOpen by remember { mutableStateOf("—") }

    // Gallery-local: the real decoder, a private LRU, no app singleton.
    val loader = remember { TranscriptImageLoader() }
    val reader = remember(failFirst) {
        FakeImageSource(
            delayMs = 600,
            fail = if (failFirst) setOf(ATT1) else emptySet(),
            slow = setOf(ROW_B),
        )
    }
    val binding = remember(loader, reader) { TranscriptImageBinding(loader, { "gallery" }, reader) }
    val rowReference = if (swapped) ROW_B else ROW_A
    val actions = remember(binding) {
        TranscriptActions(
            cachedImage = binding::cached,
            loadImage = binding::load,
            openImage = { reference, source, _, _ ->
                lastOpen = "$reference @ " +
                    "${source.left.toInt()},${source.top.toInt()} " +
                    "${source.width.toInt()}×${source.height.toInt()}"
            },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScaledStage { ImageTileStage(chipColor(), actions, rowReference, stageKey = reloadKey) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TileControl("Reload") {
                loader.evictAll()
                reloadKey++
            }
            TileControl("Fail one", active = failFirst) {
                failFirst = !failFirst
                loader.evictAll()
                reloadKey++
            }
            TileControl("Swap references", active = swapped) { swapped = !swapped }
        }
        BasicText(
            "openImage: $lastOpen",
            style = uiStyle(Face.Mono, typeScale.fixed(11f), palette.secondary),
        )
    }
}

/** The fixture's stage: 393 × 376 dp (thumbs 0..76, the row tile 100..360). */
private val StageWidth = 393.dp
private val StageHeight = 376.dp

/** The thumbnail references (rightmost first, as Rust draws them). */
private const val ATT1 = "/gallery/att-1.png"
private const val ATT2 = "/gallery/att-2.png"
private const val ATT3 = "/gallery/att-3.png"

/** The 260 dp row tile's two references (the swap pair). */
private const val ROW_A = "/gallery/generated.png"
private const val ROW_B = "/gallery/generated-b.png"

/**
 * The deterministic fixture (goldens and previews): the Rust geometry with
 * a [TranscriptActions] that has **no decoder** — `loaded = true` answers
 * every `cachedImage` with the fixture bitmap (the sync-hit path: alpha 1
 * in the first frame), `loaded = false` answers nothing and never loads
 * (`awaitCancellation`), so the placeholders show forever.
 */
@Composable
fun ImageTileFixture(loaded: Boolean) {
    val actions = remember(loaded) { fixtureActions(loaded) }
    ImageTileStage(chipColor(), actions, ROW_A, stageKey = 0)
}

/**
 * The overlay-clip fixture (golden `image_tile_overlay_clip_light`): the
 * 260 dp tile alone with a full-size red box as `overlay` over its
 * placeholder — the red must come out with the tile's rounded corners.
 */
@Composable
fun ImageTileOverlayFixture() {
    TileSlot(
        chip = chipColor(),
        x = 18,
        y = 100,
        side = 260,
        reference = "/gallery/overlay.png",
        actions = TranscriptActions(),
        overlay = { Box(Modifier.fillMaxSize().background(Color.Red)) },
    )
}

/** The stage itself: every tile over its own placeholder box. */
@Composable
private fun ImageTileStage(
    chip: Color,
    actions: TranscriptActions,
    rowReference: String,
    stageKey: Any,
) {
    Box(Modifier.requiredSize(StageWidth, StageHeight)) {
        // Reload re-keys: fresh tile states claim again after an evict.
        key(stageKey) {
            TileSlot(chip, 299, 0, 76, ATT1, actions)
            TileSlot(chip, 217, 0, 76, ATT2, actions)
            TileSlot(chip, 135, 0, 76, ATT3, actions)
            TileSlot(chip, 18, 100, 260, rowReference, actions)
        }
    }
}

/**
 * One tile and the placeholder Rust paints under it: a circular-arc
 * rounded rect of r = 12/14 in `ChipBackground` (ticket 64's box, here
 * drawn by the fixture because the gallery has no row model), with the
 * [ImageTile] filling it exactly.
 */
@Composable
private fun TileSlot(
    chip: Color,
    x: Int,
    y: Int,
    side: Int,
    reference: String,
    actions: TranscriptActions,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        Modifier
            .absoluteOffset(x.dp, y.dp)
            .size(side.dp, side.dp)
            .background(chip, RoundedCornerShape(imageTileRadius(side.toFloat(), side.toFloat()))),
    ) {
        ImageTile(reference, ImageTileCopy.FallbackLabel, side.toFloat(), side.toFloat(), actions, overlay = overlay)
    }
}

/** `ColorRole.CHIP_BACKGROUND` of the current appearance (#E7E7EB / #1C1C20). */
@Composable
private fun chipColor(): Color = Color(TranscriptPalette(RobocoTheme.isDark).color(ColorRole.CHIP_BACKGROUND))

/** The deterministic fixture's actions (no decoder, no reader). */
private fun fixtureActions(loaded: Boolean): TranscriptActions {
    val bitmap = fixtureBitmap().asImageBitmap()
    return if (loaded) {
        TranscriptActions(
            cachedImage = { _, _ -> bitmap },
            loadImage = { _, _ -> bitmap },
        )
    } else {
        TranscriptActions(
            cachedImage = { _, _ -> null },
            loadImage = { _, _ -> awaitCancellation() },
        )
    }
}

/**
 * Scales the fixed 393 dp stage into the section body (the pairing-root
 * pattern): the tiles keep their exact dp geometry, the whole stage just
 * shrinks on narrow bodies.
 */
@Composable
private fun ScaledStage(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val scale = minOf(1f, maxWidth / StageWidth)
        Box(Modifier.fillMaxWidth().height(StageHeight * scale), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .requiredSize(StageWidth, StageHeight)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
            ) { content() }
        }
    }
}

/** One gallery control: a `BasicText` label in a clickable capsule (ticket 22's style). */
@Composable
private fun TileControl(label: String, active: Boolean = false, onClick: () -> Unit) {
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
            style = uiStyle(Face.SansMedium, typeScale.fixed(13f), if (active) palette.accent else palette.text),
            maxLines = 1,
        )
    }
}

/**
 * The fake attachment source: a [delayMs] wait, then [IOException] for
 * references in [fail], else the fixture PNG's bytes (encoded once).
 * References in [slow] take [slowDelayMs] instead (the swap demo's
 * in-flight window). The real decoder still runs on the bytes.
 */
class FakeImageSource(
    private val delayMs: Long,
    private val fail: Set<String> = emptySet(),
    private val slow: Set<String> = emptySet(),
    private val slowDelayMs: Long = 3000L,
) : AttachmentReader {
    private val bytes: ByteArray by lazy {
        ByteArrayOutputStream().use { out ->
            fixtureBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    override suspend fun read(engineDeviceId: String, reference: String): ByteArray {
        delay(if (reference in slow) slowDelayMs else delayMs)
        if (reference in fail) throw IOException("no attachment at $reference")
        return bytes
    }
}

/**
 * The deterministic 1200 × 800 fixture picture: a two-stop diagonal
 * gradient #5B43E8 → #F0A04B (top-left → bottom-right) with a white 2 px
 * grid every 100 px. Fixture-only colours, not iOS values. Memoized.
 */
fun fixtureBitmap(): Bitmap {
    fixtureCache?.let { return it }
    val w = 1200
    val h = 800
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(
        0f, 0f, w.toFloat(), h.toFloat(),
        0xFF5B43E8.toInt(), 0xFFF0A04B.toInt(),
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    paint.shader = null
    paint.color = 0xFFFFFFFF.toInt()
    paint.strokeWidth = 2f
    var x = 100
    while (x < w) {
        canvas.drawLine(x.toFloat(), 0f, x.toFloat(), h.toFloat(), paint)
        x += 100
    }
    var y = 100
    while (y < h) {
        canvas.drawLine(0f, y.toFloat(), w.toFloat(), y.toFloat(), paint)
        y += 100
    }
    fixtureCache = bitmap
    return bitmap
}

private var fixtureCache: Bitmap? = null

// ---------------------------------------------------------------------------
// Previews.
// ---------------------------------------------------------------------------

@Preview(name = "ImageTile light", widthDp = 393, heightDp = 380)
@Composable
private fun ImageTileLightPreview() {
    RobocoTheme(Appearance.Light) {
        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
            ImageTileFixture(loaded = true)
        }
    }
}

@Preview(name = "ImageTile dark", widthDp = 393, heightDp = 380, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ImageTileDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
            ImageTileFixture(loaded = true)
        }
    }
}
