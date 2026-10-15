package sh.roboco.android.transcript.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.min
import sh.roboco.android.core.Fonts
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.TranscriptPalette
import sh.roboco.android.design.color
import sh.roboco.android.transcript.RowModel
import sh.roboco.android.transcript.StyleFonts
import uniffi.roboco_core.ColorRole
import uniffi.roboco_core.Decoration
import uniffi.roboco_core.FaceRole
import uniffi.roboco_core.RowDisplay
import uniffi.roboco_core.StyleDesc
import uniffi.roboco_core.TextRun
import uniffi.roboco_core.Widget
import uniffi.roboco_core.WidgetKind

/**
 * The shimmer specimen (ticket 122): a hand-built tool-group **header**
 * [RowDisplay] — one summary title run in `TEXT_SECONDARY` plus the
 * `Shimmer` widget Rust would emit over it — drawn through the real
 * layer-0 path (62/63), with the [ShimmerLayer] frozen at a caller-chosen
 * phase. Previews, the debug gallery and the Roborazzi goldens all use it.
 *
 * The geometry is the Rust one at text scale 1 on a 402 pt phone
 * (`rows.rs:706-708`: row x 18, cw 366): title slot at x 49.5 (row x 18 +
 * 31.5), 20.25 line height, `tw = min(measured, 330)` (cw − 36). The
 * specimen drops the row's leading gap, so the widget sits at y 4.5 — the
 * shimmer only reads `w.y`, which changes nothing in the maths. The
 * baseline uses Android's `FontMetrics` instead of Rust's measurer, close
 * enough because the base pass and the highlight share the same run.
 */
internal object ShimmerSpecimen {

    /** The tool-group summary title (`tools.rs` header, one line). */
    const val TEXT = "Thought process · ran 3 commands · edited 2 files"

    /** Title slot x in row coordinates: row x 18 + 31.5 (`tools.rs:879`). */
    const val TITLE_X = 49.5f

    /** Widget y at text scale 1 with the leading gap dropped (`tools.rs:882`). */
    const val TITLE_Y = 4.5f

    /** The summary's line height: 18 × 1.125 (`tools.rs:883`). */
    const val LINE_HEIGHT = 20.25f

    /** The specimen's row width (a 402 pt phone, `rows.rs:706`). */
    const val ROW_WIDTH = 402f

    /** The header height: 26 × 1.125 (`tools.rs:878`). */
    const val ROW_HEIGHT = 29.25f

    /** The title's slot width: cw 366 − 31.5 − 4.5 (`tools.rs:880`). */
    const val TITLE_MAX_W = 330f

    /** Geist Regular 13.5 pt (12 × 1.125), ligatures on (`tools.rs:770-780`). */
    val styles: List<StyleDesc> = listOf(StyleDesc(0u, FaceRole.SANS, 13.5f, true))

    /**
     * The fixture row at density `d`: the title run (baseline per the
     * Rust half-leading rule `style.rs:245-247`) and the `Shimmer` widget
     * Rust emits while the group is the streaming tail.
     */
    fun model(fonts: StyleFonts, d: Float, version: ULong = 1uL): RowModel {
        fonts.update(styles)
        fonts.useDensity(d)
        val paint = fonts[0u]!!.paint
        val tw = paint.measureText(TEXT) / d
        val fm = paint.fontMetrics
        val ascent = -fm.ascent / d
        val descent = fm.descent / d
        val baseline = TITLE_Y + (LINE_HEIGHT - (ascent + descent)) / 2f + ascent
        val run = TextRun(
            start = 0u,
            len = TEXT.length.toUInt(),
            x = TITLE_X,
            baseline = baseline,
            width = tw,
            style = 0u,
            color = ColorRole.TEXT_SECONDARY,
            decoration = Decoration.NONE,
            scroller = null,
            revealMs = null,
        )
        val widget = Widget(
            id = 1u,
            kind = WidgetKind.Shimmer,
            x = TITLE_X,
            y = TITLE_Y,
            w = min(tw, TITLE_MAX_W),
            h = LINE_HEIGHT,
            payload = null,
            scroller = null,
        )
        return RowModel(
            RowDisplay(
                key = 1uL,
                version = version,
                width = ROW_WIDTH,
                height = ROW_HEIGHT,
                text = TEXT,
                runs = listOf(run),
                boxes = emptyList(),
                links = emptyList(),
                scrollers = emptyList(),
                widgets = listOf(widget),
                fades = emptyList(),
                copyText = "",
            ),
        )
    }
}

/**
 * The specimen: the 402 × 29.25 dp header row on the transcript background
 * (ticket 01's `#F3F3F5` / `#060606`), the base title painted by the real
 * layer-0 [RowModel.draw] in `TEXT_SECONDARY`, and — when [phase] is not
 * null — the frozen [ShimmerLayer] over the widget rect. `phase = null` is
 * the reduced-motion look: the base title alone (the golden the t = 0
 * golden must match pixel for pixel).
 */
@Composable
internal fun ShimmerSpecimen(dark: Boolean, phase: Float?) {
    val context = LocalContext.current
    val d = LocalDensity.current.density
    // StyleFonts' default constructor resolves typefaces through the shared
    // chain, so make sure it is built (memoised once startup's prewarm ran).
    val fonts = remember(context) {
        Fonts.load(context)
        StyleFonts()
    }
    val model = remember(fonts, d) { ShimmerSpecimen.model(fonts, d) }
    val palette = TranscriptPalette(dark)
    val widget = model.display.widgets[0]
    Box(Modifier.size(402.dp, 29.25.dp).background(RobocoTheme.palette.background)) {
        Canvas(Modifier.matchParentSize()) {
            drawIntoCanvas { c -> model.draw(0, c.nativeCanvas, d, palette, fonts) }
        }
        if (phase != null) {
            Box(
                Modifier
                    .absoluteOffset(ShimmerSpecimen.TITLE_X.dp, ShimmerSpecimen.TITLE_Y.dp)
                    .size(widget.w.dp, ShimmerSpecimen.LINE_HEIGHT.dp),
            ) {
                ShimmerLayer(
                    model = model,
                    x = widget.x,
                    y = widget.y,
                    w = widget.w,
                    h = widget.h,
                    fonts = fonts,
                    color = palette.color(ColorRole.TEXT),
                    phase = { phase },
                )
            }
        }
    }
}
