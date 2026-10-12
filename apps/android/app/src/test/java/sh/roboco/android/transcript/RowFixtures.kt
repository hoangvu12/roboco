package sh.roboco.android.transcript

import uniffi.roboco_core.BoxPrim
import uniffi.roboco_core.BoxStyle
import uniffi.roboco_core.ColorRole
import uniffi.roboco_core.Decoration
import uniffi.roboco_core.Fade
import uniffi.roboco_core.FadeEdge
import uniffi.roboco_core.FaceRole
import uniffi.roboco_core.RowDisplay
import uniffi.roboco_core.Scroller
import uniffi.roboco_core.StyleDesc
import uniffi.roboco_core.TextRun

/**
 * Synthetic [RowDisplay]s and [StyleDesc]s for ticket 62's tests
 * (`.scratch/android-ui-parity/issues/62-row-model-style-fonts.md`,
 * Implementation step 6). Built directly from the generated records, so
 * [RowModelTest] stays plain JVM: constructing them touches no JNA and
 * no android.os (same reasoning as `TranscriptPaletteTest`).
 *
 * The main fixture `row()` is one message with a fenced code block:
 * line 1 plain text, line 2 a mono run inside a scroller (the code
 * block's viewport is the box), line 3 a wide trailing line that the
 * fade claims.
 */
internal object RowFixtures {

    /** 90 UTF-16 units: 19 + 21 + 50. */
    const val T: String =
        "The quick brown fox" + "fn main() -> u8 { 0 }" + "A trailing line wider than its slot fades out here"

    val styles: List<StyleDesc> = listOf(
        StyleDesc(0u, FaceRole.SANS, 16.5f, true),
        StyleDesc(1u, FaceRole.MONO, 13.5f, false),
    )

    /** A text run; `width` is unused by the seed painters, `revealMs` is always None upstream. */
    fun run(
        start: UInt,
        len: UInt,
        x: Float,
        baseline: Float,
        scroller: UInt? = null,
        style: UShort = 0u,
        color: ColorRole = ColorRole.TEXT,
    ): TextRun = TextRun(start, len, x, baseline, 0f, style, color, Decoration.NONE, scroller, null)

    fun fade(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        edge: FadeEdge = FadeEdge.TRAILING,
        scroller: UInt? = null,
    ): Fade = Fade(x, y, w, h, edge, scroller)

    fun box(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radius: Float,
        style: BoxStyle,
        color: ColorRole,
        scroller: UInt? = null,
    ): BoxPrim = BoxPrim(x, y, w, h, radius, style, color, scroller)

    /** A row over the given primitives; links and widgets are empty (tickets 126/67). */
    fun rowOf(
        runs: List<TextRun>,
        boxes: List<BoxPrim> = emptyList(),
        scrollers: List<Scroller> = emptyList(),
        fades: List<Fade> = emptyList(),
    ): RowDisplay =
        RowDisplay(
            key = 1uL,
            version = 1uL,
            width = 320f,
            height = 112f,
            text = T,
            runs = runs,
            boxes = boxes,
            links = emptyList(),
            scrollers = scrollers,
            widgets = emptyList(),
            fades = fades,
            copyText = T,
        )

    /**
     * The full fixture: run 0 "The quick brown fox" (line 1), run 1 the
     * mono code line inside scroller 0 (the code box at 16/40, 288×32),
     * run 2 the wide trailing line that fade 0 (216/74, 88×24) claims
     * (92 > 74, 92 ≤ 98.5, 16 < 304).
     */
    fun row(): RowDisplay = rowOf(
        runs = listOf(
            run(0u, 19u, 16f, 28f, scroller = null, style = 0u, color = ColorRole.TEXT),
            run(19u, 21u, 12f, 22f, scroller = 0u, style = 1u, color = ColorRole.CODE_TEXT),
            run(40u, 50u, 16f, 92f, scroller = null, style = 0u, color = ColorRole.TEXT_SECONDARY),
        ),
        boxes = listOf(
            box(16f, 40f, 288f, 32f, 10f, BoxStyle.FILL, ColorRole.CODE_BACKGROUND),
            box(16f, 40f, 288f, 32f, 10f, BoxStyle.HAIRLINE, ColorRole.CODE_BORDER),
        ),
        scrollers = listOf(Scroller(16f, 40f, 288f, 32f, 400f)),
        fades = listOf(fade(216f, 74f, 88f, 24f, FadeEdge.TRAILING, null)),
    )
}
