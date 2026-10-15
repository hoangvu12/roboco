package sh.roboco.android.transcript.widgets

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import sh.roboco.android.core.Face
import sh.roboco.android.debug.labGradientBitmap
import sh.roboco.android.debug.serveLabFixture
import sh.roboco.android.design.Appearance
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
 * The "Image tile states" section of the debug design gallery (ticket
 * 137): every tile state at both tile sizes, over the circular-arc
 * `ChipBackground` placeholder Rust paints under each —
 *
 *  - **Row 1** (a 393 dp stage): three 260 dp tiles stacked at x = 18,
 *    10 dp apart — Loading (the reader never returns), Loaded (the
 *    960 × 540 art, aspect-**fit**: letterboxed top and bottom on
 *    `Palette.elevated`), Failed (the full three-line status);
 *  - **Row 2**: three 76 dp thumbnails right-aligned at x =
 *    299/217/135 — Loading, Loaded (the 540 × 960 portrait, pillarboxed),
 *    Failed — **compact** mode: the 20 dp spinner/glyph only, the label
 *    would not fit;
 *  - **Row 3** (interactive, horizontally scrollable, 6 dp gaps): three
 *    260 dp tiles with a live `reads: N` caption — "Retry succeeds" (the
 *    reader fails on the 1st call, succeeds on the 2nd; tap the Failed
 *    tile), "Slow" (a 3 s read — double-tapping during Loading keeps
 *    `reads: 1`, the loader's de-duplication) and a remote
 *    `https://example.com/a.png` tile (unfetchable: Failed in its first
 *    frame, `reads: 0` forever, taps do nothing).
 *
 * The loader is gallery-local (never the app singleton) behind
 * [TranscriptImageBinding] on the fake engine "gallery", one reader per
 * interactive fixture so the `reads` counters stay independent. Under
 * Roborazzi capture the section renders the deterministic
 * [ImageTileStatesFixture] instead (rows 1–2, static actions, no
 * reader). No Material anywhere.
 *
 * Registered in `Gallery.sections` as "Image tile states".
 */
@Composable
fun ImageTileStatesGallerySection() {
    if (LocalGalleryCapture.current) {
        StatesScaledStage { ImageTileStatesFixture() }
        return
    }
    val loader = remember { TranscriptImageLoader() }
    val reader = remember { StatesReader() }
    val binding = remember(loader, reader) { TranscriptImageBinding(loader, { "gallery" }, reader) }
    val actions = remember(binding) {
        TranscriptActions(
            cachedImage = binding::cached,
            loadImage = binding::load,
        )
    }
    // Remembered so a recomposition never resets the readers' counts.
    val retryReader = remember { RetrySucceedsReader() }
    val slowReader = remember { SlowReader() }
    val neverReader = remember { NeverReader() }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        StatesScaledStage { ImageTileStatesStage(statesChipColor(), actions) }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            InteractiveTile(loader, "/gallery/interactive/retry.png", "Retry succeeds", retryReader)
            InteractiveTile(loader, "/gallery/interactive/slow.png", "Slow", slowReader)
            InteractiveTile(loader, "https://example.com/a.png", "Remote", neverReader)
        }
    }
}

/** The deterministic rows 1–2 fixture (goldens, previews and capture). */
@Composable
fun ImageTileStatesFixture() {
    val actions = remember { statesFixtureActions() }
    ImageTileStatesStage(statesChipColor(), actions)
}

/** The fixture's stage: 393 × 896 dp (row 1: 0..800, row 2: 820..896). */
private val StatesStageWidth = 393.dp
private val StatesStageHeight = 896.dp

/** The rows 1–2 references; each name routes its fixture's actions. */
private const val LOADING_LANDSCAPE = "/gallery/states/loading-landscape.png"
private const val LOADED_LANDSCAPE = "/gallery/states/loaded-landscape.png"
private const val FAILED_LANDSCAPE = "/gallery/states/failed-landscape.png"
private const val LOADING_THUMB = "/gallery/states/loading-thumb.png"
private const val LOADED_THUMB = "/gallery/states/loaded-thumb.png"
private const val FAILED_THUMB = "/gallery/states/failed-thumb.png"

/** The stage itself: every tile over its own placeholder box. */
@Composable
private fun ImageTileStatesStage(chip: Color, actions: TranscriptActions) {
    Box(Modifier.requiredSize(StatesStageWidth, StatesStageHeight)) {
        // Row 1: three 260 dp tiles stacked at x = 18, 10 dp apart.
        StatesTileSlot(chip, 18, 0, 260, LOADING_LANDSCAPE, "Loading", actions)
        StatesTileSlot(chip, 18, 270, 260, LOADED_LANDSCAPE, "Landscape", actions)
        StatesTileSlot(chip, 18, 540, 260, FAILED_LANDSCAPE, "Failed", actions)
        // Row 2: three 76 dp thumbnails right-aligned (6 dp gaps), the
        // gap to row 1 is 20 dp (800 + 20 = 820).
        StatesTileSlot(chip, 299, 820, 76, LOADING_THUMB, "Loading", actions)
        StatesTileSlot(chip, 217, 820, 76, LOADED_THUMB, "Portrait", actions)
        StatesTileSlot(chip, 135, 820, 76, FAILED_THUMB, "Failed", actions)
    }
}

/**
 * One tile and the placeholder Rust paints under it: a circular-arc
 * rounded rect of r = 12/14 in `ChipBackground` (ticket 64's box, drawn
 * by the fixture because the gallery has no row model), with the
 * [ImageTile] filling it exactly.
 */
@Composable
private fun StatesTileSlot(
    chip: Color,
    x: Int,
    y: Int,
    side: Int,
    reference: String,
    label: String,
    actions: TranscriptActions,
) {
    Box(
        Modifier
            .absoluteOffset(x.dp, y.dp)
            .size(side.dp, side.dp)
            .background(chip, RoundedCornerShape(imageTileRadius(side.toFloat(), side.toFloat()))),
    ) {
        ImageTile(reference, label, side.toFloat(), side.toFloat(), actions)
    }
}

/** `ColorRole.CHIP_BACKGROUND` of the current appearance (#E7E7EB / #1C1C20). */
@Composable
private fun statesChipColor(): Color = Color(TranscriptPalette(RobocoTheme.isDark).color(ColorRole.CHIP_BACKGROUND))

/**
 * The deterministic fixture's actions (no reader): a sync hit for the
 * Loaded references, a never-returning load for the Loading ones and a
 * null load for the Failed ones.
 */
private fun statesFixtureActions(): TranscriptActions {
    val landscape = labGradientBitmap(960, 540).asImageBitmap()
    val portrait = labGradientBitmap(540, 960).asImageBitmap()
    return TranscriptActions(
        cachedImage = { reference, _ ->
            when (reference) {
                LOADED_LANDSCAPE -> landscape
                LOADED_THUMB -> portrait
                else -> null
            }
        },
        loadImage = { reference, _ ->
            when (reference) {
                LOADING_LANDSCAPE, LOADING_THUMB -> awaitCancellation()
                else -> null // the Failed references
            }
        },
    )
}

/**
 * The live rows 1–2 source: the "loading" references never return, the
 * "failed" ones throw, the two loaded ones serve the gradient art as
 * real PNG bytes after 600 ms — so the real decoder runs on device.
 */
private class StatesReader : AttachmentReader {
    override suspend fun read(engineDeviceId: String, reference: String): ByteArray {
        return when {
            reference == LOADING_LANDSCAPE || reference == LOADING_THUMB -> awaitCancellation()
            reference == LOADED_LANDSCAPE -> serveLabFixture(600, 960, 540)
            reference == LOADED_THUMB -> serveLabFixture(600, 540, 960)
            else -> throw IOException("no attachment at $reference")
        }
    }
}

/**
 * Scales the fixed 393 dp stage into the section body (the pairing-root
 * pattern): the tiles keep their exact dp geometry, the whole stage just
 * shrinks on narrow bodies.
 */
@Composable
private fun StatesScaledStage(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val scale = minOf(1f, maxWidth / StatesStageWidth)
        Box(Modifier.fillMaxWidth().height(StatesStageHeight * scale), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .requiredSize(StatesStageWidth, StatesStageHeight)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
            ) { content() }
        }
    }
}

/** A reader whose read count is live gallery state (the captions). */
private abstract class CountingReader : AttachmentReader {
    var reads by mutableIntStateOf(0)
        protected set
}

/** The row-3 "Retry succeeds" fixture: fails on the 1st call, succeeds on the 2nd. */
private class RetrySucceedsReader : CountingReader() {
    private var served = false

    override suspend fun read(engineDeviceId: String, reference: String): ByteArray {
        reads++
        if (!served) {
            served = true
            delay(300)
            throw IOException("no attachment at $reference")
        }
        return serveLabFixture(600, 960, 540)
    }
}

/** The row-3 "Slow" fixture: one 3 s read — taps during Loading join it. */
private class SlowReader : CountingReader() {
    override suspend fun read(engineDeviceId: String, reference: String): ByteArray {
        reads++
        return serveLabFixture(3000, 960, 540)
    }
}

/** The row-3 "Remote" fixture: never called (the reference is unfetchable). */
private class NeverReader : CountingReader() {
    override suspend fun read(engineDeviceId: String, reference: String): ByteArray {
        reads++
        throw IOException("no attachment at $reference")
    }
}

/** One interactive row-3 tile: its own binding (own reader) and a live reads caption. */
@Composable
private fun InteractiveTile(
    loader: TranscriptImageLoader,
    reference: String,
    name: String,
    reader: CountingReader,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val binding = remember(loader, reader) { TranscriptImageBinding(loader, { "gallery" }, reader) }
    val actions = remember(binding) {
        TranscriptActions(
            cachedImage = binding::cached,
            loadImage = binding::load,
        )
    }
    Column {
        StatesTileSlot(statesChipColor(), 0, 0, 260, reference, name, actions)
        BasicText(
            "$name — reads: ${reader.reads}",
            style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// Previews.
// ---------------------------------------------------------------------------

@Preview(name = "ImageTile states light", widthDp = 393, heightDp = 900)
@Composable
private fun ImageTileStatesLightPreview() {
    RobocoTheme(Appearance.Light) {
        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
            ImageTileStatesFixture()
        }
    }
}

@Preview(name = "ImageTile states dark", widthDp = 393, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ImageTileStatesDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
            ImageTileStatesFixture()
        }
    }
}
