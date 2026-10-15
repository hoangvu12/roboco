package sh.roboco.android.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.graphics.PathParser
import kotlin.math.min

/**
 * Harness brand marks (android-ui-parity #13), the Android port of
 * `ios:Design/BrandMarks.swift`: the exact SVG path data the desktop bundles
 * (`crates/ui/assets/icons/<name>-mark.svg`), parsed once with androidx's
 * [PathParser] after [SvgPathTokens.normalize] splits packed arc flags, and
 * drawn as a tinted glyph — Claude Code and `mock` keep the desktop's Claude
 * orange, every other mark takes the caller's tint.
 */
enum class BrandMark(
    /** `crates/ui/assets/icons/<svgName>-mark.svg` (tests use it). */
    val svgName: String,
    val viewBoxWidth: Float,
    val viewBoxHeight: Float,
    /** The source SVG's fill-rule; the shape must fill even-odd where the asset says so or the mark's holes fill in solid. */
    val evenOdd: Boolean,
    val pathData: String,
) {
    CLAUDE("claude", 256f, 257f, false, CLAUDE_PATH_DATA),
    OPENAI("openai", 256f, 260f, false, OPENAI_PATH_DATA),
    CURSOR("cursor", 466.73f, 532.09f, false, CURSOR_PATH_DATA),
    DEVIN("devin", 263f, 300f, false, DEVIN_PATH_DATA),
    GROK("grok", 16f, 16f, false, GROK_PATH_DATA),
    HERMES("hermes", 24f, 24f, true, HERMES_PATH_DATA),
    PI("pi", 800f, 800f, true, PI_PATH_DATA),
    OPENCODE("opencode", 24f, 30f, true, OPENCODE_PATH_DATA),
    ANTIGRAVITY("antigravity", 24f, 24f, true, ANTIGRAVITY_PATH_DATA);

    /**
     * Parsed once (thread-safe lazy) and never mutated afterwards: shared by
     * every row, so draw code transforms the canvas, never this path.
     */
    val path: Path by lazy {
        PathParser
            .createPathFromPathData(SvgPathTokens.normalize(pathData))
            .asComposePath()
            .apply { fillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero }
    }

    companion object {
        /** Claude Code / mock brand orange; does not change with the theme. */
        val CLAUDE_ORANGE = Color(0xFFD97757)

        fun forHarness(harness: String): BrandMark = when (harness) {
            "codex" -> OPENAI
            "cursor" -> CURSOR
            "devin" -> DEVIN
            "grok" -> GROK
            "hermes" -> HERMES
            "pi" -> PI
            "opencode" -> OPENCODE
            "antigravity" -> ANTIGRAVITY
            else -> CLAUDE   // claude-code + mock share the mark, like the desktop
        }

        /** The mark's own colour, or null when it takes the caller's tint. */
        fun brandTint(harness: String): Color? = when (harness) {
            "claude-code", "mock" -> CLAUDE_ORANGE
            else -> null
        }
    }
}

/** Aspect-fit + centred in the current [DrawScope] size (`ios:Design/BrandMarks.swift:90-98`). */
fun DrawScope.drawBrandMark(mark: BrandMark, color: Color) {
    val s = min(size.width / mark.viewBoxWidth, size.height / mark.viewBoxHeight)
    if (s <= 0f) return
    val dx = (size.width - mark.viewBoxWidth * s) / 2f
    val dy = (size.height - mark.viewBoxHeight * s) / 2f
    // p' = (dx, dy) + s·p — translate then scale about Offset.Zero, never the
    // default centre pivot (marks would drift off-centre).
    withTransform({ translate(dx, dy); scale(s, s, pivot = Offset.Zero) }) {
        drawPath(mark.path, color)
    }
}

/** iOS `BrandMarks.image(for:side:)` rules: null/empty → nothing; Claude ids keep their orange. */
internal fun resolveHarnessMark(harness: String?, tint: Color): Pair<BrandMark, Color>? {
    if (harness.isNullOrEmpty()) return null
    return BrandMark.forHarness(harness) to (BrandMark.brandTint(harness) ?: tint)
}

/**
 * A harness's brand mark in a `side × side` box, aspect-fit and centred. The
 * box always takes `side × side` of layout, even when nothing is drawn.
 * Decorative like the iOS image views: no semantics, no contentDescription —
 * the row or chip that hosts it provides the label. Never mirrored in RTL.
 */
@Composable
fun HarnessMark(harness: String?, side: Dp, tint: Color, modifier: Modifier = Modifier) {
    val resolved = resolveHarnessMark(harness, tint)
    Canvas(modifier.size(side)) {
        resolved?.let { (mark, color) -> drawBrandMark(mark, color) }
    }
}

/** A [BrandMark] as a [Painter]; callers must give it an explicit size (`intrinsicSize` is unspecified). */
class BrandMarkPainter(private val mark: BrandMark, private val color: Color) : Painter() {
    override val intrinsicSize: Size get() = Size.Unspecified   // fills whatever size the host gives
    override fun DrawScope.onDraw() = drawBrandMark(mark, color)
}

/** For GlassMenu rows / chips that take a [Painter]. Null when there is nothing to draw. */
fun harnessMarkPainter(harness: String?, tint: Color): Painter? =
    resolveHarnessMark(harness, tint)?.let { (mark, color) -> BrandMarkPainter(mark, color) }

// ---------------------------------------------------------------------------
// Gallery sample (720 × 240 dp): 9 harnesses × sides 20 / 34 / 64 dp, tinted
// Palette.text on Palette.background. No text labels, so the golden does not
// depend on fonts. Screenshot goldens: BrandMarksScreenshotTest.
// ---------------------------------------------------------------------------

private val HARNESS_IDS = listOf(
    "claude-code", "codex", "cursor", "devin", "grok", "hermes", "pi", "opencode", "antigravity",
)

/** Monospace label style; swapped for Geist by ticket 03. */
private fun labelStyle(color: Color): TextStyle =
    TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color)

@Composable
private fun Label(text: String, color: Color) {
    BasicText(text, style = labelStyle(color))
}

@Composable
fun BrandMarksSample() {
    val palette = RobocoTheme.palette
    Column(Modifier.background(palette.background)) {
        for (side in listOf(20.dp, 34.dp, 64.dp)) {
            Row {
                for (id in HARNESS_IDS) {
                    Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                        HarnessMark(id, side, palette.text)
                    }
                }
            }
        }
    }
}

/**
 * The "Brand marks" section of the design gallery (registered by ticket 22):
 * the sample, the harness ids under their columns, and a null-harness cell
 * that must stay blank. The gallery frame provides the theme.
 */
@Composable
fun BrandMarksGallerySection() {
    val palette = RobocoTheme.palette
    Column(Modifier.background(palette.background)) {
        BrandMarksSample()
        Row {
            for (id in HARNESS_IDS) {
                Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                    Label(id, palette.secondary)
                }
            }
        }
        Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
            HarnessMark(null, 20.dp, palette.text)
        }
        Label("null", palette.secondary)
    }
}

@Preview(widthDp = 720, heightDp = 240)
@Composable
private fun BrandMarksPreviewLight() {
    RobocoTheme(Appearance.Light) {
        BrandMarksSample()
    }
}

@Preview(widthDp = 720, heightDp = 240)
@Composable
private fun BrandMarksPreviewDark() {
    RobocoTheme(Appearance.Dark) {
        BrandMarksSample()
    }
}
