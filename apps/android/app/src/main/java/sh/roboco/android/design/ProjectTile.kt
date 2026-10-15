package sh.roboco.android.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.icu.text.BreakIterator
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import kotlin.math.ceil
import sh.roboco.android.R
import sh.roboco.android.core.Face
import sh.roboco.android.core.GeistMetrics
import sh.roboco.android.core.assetPath

/**
 * The GeistMono-Medium [Typeface] for the tile's native `Paint` — the
 * approach ticket 06's `NumberCircle` uses: resolved once, process-wide.
 * A native `Paint` cannot use the Compose font families.
 */
private object MonoMediumTypeface {
    @Volatile
    private var cached: Typeface? = null

    fun get(context: Context): Typeface {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: Typeface
                .createFromAsset(context.applicationContext.assets, Face.MonoMedium.assetPath)
                .also { cached = it }
        }
    }
}

/**
 * The project monogram — the desktop's `project_icon.rs` tile, ported from
 * `ios:Design/ProjectTile.swift:1-33`: a rounded square in the project's
 * tone (fill @ 0.08, letter @ 0.85, GeistMono Medium). The tile scales
 * the desktop's 13 px tile by `k = side / 13` (radius `3k`, letter `9k`).
 *
 * `letter` / `metrics` are pure; [draw] is the single draw path both the
 * [ProjectTile] composable and the cached [bitmap] go through, so the two
 * forms render pixel-identically.
 */
object ProjectTiles {

    /** `withAlphaComponent(0.08)` → round(0.08 · 255) = 20. */
    internal const val FILL_ALPHA = 20

    /** `withAlphaComponent(0.85)` → round(0.85 · 255) = 217. */
    internal const val INK_ALPHA = 217

    /**
     * The tile's letter: `ProjectTile.letter(_:)` — trim Swift
     * `.whitespaces` (Unicode Zs + TAB — **not** newlines), then the first
     * grapheme cluster, uppercased locale-independently; empty → "?".
     */
    fun letter(name: String): String {
        val trimmed = name.trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }
        if (trimmed.isEmpty()) return "?"
        val breaks = BreakIterator.getCharacterInstance()
        breaks.setText(trimmed)
        breaks.first()
        val end = breaks.next()
        val cluster = if (end == BreakIterator.DONE) trimmed else trimmed.substring(0, end)
        return cluster.uppercase()
    }

    /**
     * The tile's geometry at one `side` — all values in the same unit as
     * the side passed to [metrics] (dp or px).
     */
    data class Metrics(
        /** `3k`. */
        val radius: Float,
        /** `9k` — GeistMono Medium. */
        val fontSize: Float,
        /** `side/2 + capHeight/2` — the cap height is centred in the tile. */
        val baseline: Float,
    )

    /**
     * The tile's metrics for `k = side / 13`. The baseline centres the
     * **cap height** (`side/2 + capHeight/2`): iOS's `draw(at:)` puts the
     * baseline at `side/2 − (ascender − capHeight/2) + ascender`, in which
     * the ascender cancels. Cap height comes from ticket 03's
     * [GeistMetrics.CAP_HEIGHT] (0.710, OS/2 sCapHeight), pinned to the
     * font bytes by `ProjectTileTest`.
     */
    fun metrics(side: Float): Metrics {
        val k = side / 13f
        val fontSize = 9f * k
        return Metrics(
            radius = 3f * k,
            fontSize = fontSize,
            baseline = side / 2f + GeistMetrics.CAP_HEIGHT * fontSize / 2f,
        )
    }

    /** The smooth rounded-square path (radius `3k`) for one side, in px. */
    internal fun cornerPath(sidePx: Float): android.graphics.Path =
        smoothRoundedPath(sidePx, sidePx, 3f * sidePx / 13f).asAndroidPath()

    /**
     * Draws the fill and the letter into [canvas] at (0, 0)–(sidePx,
     * sidePx): the smooth rounded square (radius `3k`) filled with [tone]
     * @ 0.08, then the letter centred by its **advance box** (`x = (sidePx
     * − measureText)/2`) on the [metrics] baseline in [tone] @ 0.85.
     *
     * [smoothCornerPath] is built by the caller (per side) — the same
     * continuous-corner shape the badge uses; iOS's
     * `UIBezierPath(roundedRect:cornerRadius:)` is also a
     * continuous-curvature rect.
     *
     * Centring is the advance box, **not** `getTextBounds` ink bounds
     * (#621's `Chrome.kt:145-176` approach, which is off for `J`, `Q`,
     * `?`, digits with overshoot, CJK and emoji).
     */
    fun draw(
        canvas: android.graphics.Canvas,
        sidePx: Float,
        letter: String,
        tone: Int,
        typeface: Typeface,
        smoothCornerPath: android.graphics.Path,
    ) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(tone, FILL_ALPHA)
            style = Paint.Style.FILL
        }
        canvas.drawPath(smoothCornerPath, fill)

        val m = metrics(sidePx)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.typeface = typeface
            textSize = m.fontSize
            color = ColorUtils.setAlphaComponent(tone, INK_ALPHA)
            textAlign = Paint.Align.LEFT
        }
        val x = (sidePx - ink.measureText(letter)) / 2f
        canvas.drawText(letter, x, m.baseline, ink)
    }

    /** The LRU key: the rendering is a function of these alone. */
    private data class TileKey(val letter: String, val toneIndex: Int, val sidePx: Int, val dark: Boolean)

    /**
     * `ImageAsset`'s light/dark pair, as a cache: one tiny bitmap per
     * (letter, tone, side, appearance). Lazy so plain-JVM callers of
     * [letter]/[metrics] never touch `android.util`.
     */
    private val bitmapCache by lazy { LruCache<TileKey, ImageBitmap>(64) }

    /**
     * The tile as an [ImageBitmap] — iOS's `ProjectTile.image(...)`
     * (`ProjectTile.swift:8-33`), light/dark aware through [dark] (the
     * `UIImageAsset` pair). Cheap (one small bitmap) and LRU-cached; runs
     * during composition on the main thread, like the iOS call.
     *
     * The side quantises to `ceil(side · density)` px — the pixel-exact
     * size chips and text runs embed.
     */
    fun bitmap(
        name: String,
        colorIndex: Int,
        side: Dp,
        density: Density,
        dark: Boolean,
        typeface: Typeface,
    ): ImageBitmap {
        val sidePx = ceil(side.value * density.density).toInt()
        val toneIndex = Math.floorMod(colorIndex, Palette.PROJECT_TONE_COUNT)
        val monogram = letter(name)
        val key = TileKey(monogram, toneIndex, sidePx, dark)
        bitmapCache.get(key)?.let { return it }
        val tone = Palette.of(dark).projectTone(colorIndex).toArgb()
        val pixels = Bitmap.createBitmap(sidePx, sidePx, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(pixels)
        draw(canvas, sidePx.toFloat(), monogram, tone, typeface, cornerPath(sidePx.toFloat()))
        val image = pixels.asImageBitmap()
        bitmapCache.put(key, image)
        return image
    }
}

/**
 * The project monogram composable, drawn on a [Canvas] through
 * [ProjectTiles.draw] — `ProjectTile.image`'s direct-draw form. The tone
 * comes from the palette at composition time (light/dark aware); the tile
 * is decorative (no semantics). Do **not** clip it: the letter is drawn
 * unclipped inside its side, as on iOS.
 */
@Composable
fun ProjectTile(name: String, colorIndex: Int, modifier: Modifier = Modifier, side: Dp = 14.dp) {
    val tone = LocalPalette.current.projectTone(colorIndex)
    val context = LocalContext.current
    val typeface = remember { MonoMediumTypeface.get(context) }
    val letter = remember(name) { ProjectTiles.letter(name) }
    val sidePx = with(LocalDensity.current) { side.toPx() }
    val path = remember(sidePx) { ProjectTiles.cornerPath(sidePx) }
    Canvas(modifier.size(side).clearAndSetSemantics { }) {
        drawIntoCanvas { canvas ->
            ProjectTiles.draw(
                canvas = canvas.nativeCanvas,
                sidePx = sidePx,
                letter = letter,
                tone = tone.toArgb(),
                typeface = typeface,
                smoothCornerPath = path,
            )
        }
    }
}

/**
 * The tile as a [BitmapPainter] — iOS's `.alwaysOriginal` image form the
 * composer's "project" chip embeds (`NewSessionViewController.swift:251`,
 * default side 14). **Not tintable**: callers must not apply a tint
 * `ColorFilter` — the tile carries its own tone. Light/dark aware: [dark]
 * is read from the palette, so a theme flip picks up the other cached
 * rendering on the next composition.
 */
@Composable
fun rememberProjectTilePainter(name: String, colorIndex: Int, side: Dp = 14.dp): Painter {
    val density = LocalDensity.current
    val dark = LocalPalette.current.isDark
    val context = LocalContext.current
    val typeface = remember { MonoMediumTypeface.get(context) }
    val bitmap = ProjectTiles.bitmap(name, colorIndex, side, density, dark, typeface)
    return remember(bitmap) { BitmapPainter(bitmap) }
}

/**
 * The desktop's `git-branch` template glyph — `BranchIcon`
 * (`ios:Design/ProjectTile.swift:35-46`), on the #13 pipeline's
 * `zi_git_branch` VectorDrawable (geometrically identical to
 * `crates/ui/assets/icons/git-branch.svg`; the stroke scales with the
 * vector — 0.8125 pt at side 13, no ×1.15 boost).
 *
 * Not routed through ticket 06's [Icon]: that reserves a
 * `sizePt × 1.2` box and scales the stroke by weight; this needs the
 * exact [side] box with the vector's own stroke.
 *
 * The row meta line draws it at `round(12·factor)` tinted `Palette.subline`
 * (ticket 38); chips draw it at 13 tinted `Palette.text`.
 *
 * @param tint required: a template glyph draws in one colour, the tint's
 *   alpha included (`ColorFilter.tint` is SrcIn)
 */
@Composable
fun BranchIcon(tint: Color, modifier: Modifier = Modifier, side: Dp = 13.dp) {
    Image(
        painter = rememberBranchIconPainter(),
        contentDescription = null,
        modifier = modifier.size(side),
        colorFilter = ColorFilter.tint(tint),
    )
}

/** The git-branch VectorDrawable as a template painter (tinted at use). */
@Composable
fun rememberBranchIconPainter(): Painter = painterResource(R.drawable.zi_git_branch)
