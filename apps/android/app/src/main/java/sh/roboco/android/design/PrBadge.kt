package sh.roboco.android.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max
import sh.roboco.android.core.Face
import uniffi.roboco_core.PullRequestState

/**
 * A pull request's badge state — `SessionRowVM.PR` on iOS
 * (`ios:Threads/SessionCell.swift:13-15`): open, draft, merged, closed.
 *
 * [Draft] is unreachable today: the core's `PullRequestState`
 * (`mob:client_ffi/types.rs:460-464`) has only Open/Merged/Closed and
 * [from] maps them 1:1 (`ios:App/AppModel.swift:460-466`). It is kept for
 * parity with the iOS enum, which the badge supports.
 */
enum class PrState {
    Open, Merged, Closed, Draft;

    /** The a11y word, verbatim and lowercase: "open" / "merged" / "closed" / "draft". */
    val word: String
        get() = when (this) {
            Open -> "open"
            Merged -> "merged"
            Closed -> "closed"
            Draft -> "draft"
        }

    companion object {
        /** The core's [PullRequestState] — `AppModel.swift:460-466`'s 1:1 mapping. */
        fun from(state: PullRequestState): PrState = when (state) {
            PullRequestState.OPEN -> Open
            PullRequestState.MERGED -> Merged
            PullRequestState.CLOSED -> Closed
        }
    }
}

/**
 * iOS `PRBadgeView.tone(_:)` (`StatusGlyph.swift:179-186`):
 * open = success, merged = accent, closed = danger, draft = secondary.
 */
@Composable
@ReadOnlyComposable
fun prTone(state: PrState): Color = when (state) {
    PrState.Open -> LocalPalette.current.success
    PrState.Merged -> LocalPalette.current.accent
    PrState.Closed -> LocalPalette.current.danger
    PrState.Draft -> LocalPalette.current.secondary
}

/**
 * iOS `withAlphaComponent`: the alpha **replaces** the tone's (every PR
 * tone is opaque). Quantised the way ticket 01's [rgba] quantises —
 * `round(a · 255)` — instead of `Color.copy(alpha = …)`, per the palette's
 * no-float-alpha rule: 0.08 → 20, 0.85 → 217.
 */
internal fun prToneAlpha(tone: Color, alpha: Double): Color =
    rgba(tone.toArgb() and 0xFFFFFF, alpha)

/**
 * The badge's geometry, all in dp (`PRBadgeView.intrinsicContentSize` and
 * `layoutSubviews`, `StatusGlyph.swift:203-224`). Pure: the unit-test
 * fixture. [labelWidth] is the already-ceiled width of the number string
 * in GeistMono Medium at [size].
 */
data class PrBadgeMetrics(
    /** `r(s·0.45)` — also the corner radius. */
    val pad: Float,
    /** The corner radius: `pad`, continuous corners (ticket 01's smooth shape). */
    val radius: Float,
    /** `r(s·1.65)`. */
    val height: Float,
    /** `2·pad + s + 3 + labelWidth`. */
    val width: Float,
    /** The icon's x: `pad`. */
    val iconX: Float,
    /** The icon's y: `(height − s) / 2` (iOS `contentMode = .center`). */
    val iconY: Float,
    /** The label's x: `pad + s + 3`. */
    val labelX: Float,
)

/**
 * The badge metrics for one size. Swift's `.rounded()` rounds half away
 * from zero, so this goes through ticket 03's [roundHalfAway] — **not**
 * `kotlin.math.round` (half to even: `round(16.5f) == 16f`). In Float32
 * `10f * 0.45f == 4.5f` and `10f * 1.65f == 16.5f` exactly, so s = 10
 * gives pad 5 / height 17 as on iOS.
 */
fun prBadgeMetrics(size: Float, labelWidth: Float): PrBadgeMetrics {
    val pad = roundHalfAway(size * 0.45f)
    val height = roundHalfAway(size * 1.65f)
    return PrBadgeMetrics(
        pad = pad,
        radius = pad,
        height = height,
        width = 2f * pad + size + 3f + labelWidth,
        iconX = pad,
        iconY = (height - size) / 2f,
        labelX = pad + size + 3f,
    )
}

/** `"Pull request $number, $word"` — `PRBadgeView.configure`'s accessibility label, verbatim. */
fun prBadgeA11yLabel(number: ULong, state: PrState): String = "Pull request $number, ${state.word}"

/**
 * The desktop's pull-request badge: the PR icon and the bare number in
 * GeistMono Medium, tinted by state — tone @ 0.08 fill, tone @ 0.85 ink
 * (`PRBadgeView`, `ios:Design/StatusGlyph.swift:169-224`).
 *
 * - Static: no pressed state (`isUserInteractionEnabled = false` on iOS),
 *   no animation, no haptics. Appearance changes recolour it on the next
 *   composition.
 * - [size] is already the type-scaled value (iOS's caller passes
 *   `TypeScale.size(11)`); the label uses ticket 03's `fixed(size)` so the
 *   font scale is applied exactly once.
 * - One a11y node: `clearAndSetSemantics` with
 *   [prBadgeA11yLabel] — the number is not a separate text node.
 */
@Composable
fun PrBadge(number: ULong, state: PrState, modifier: Modifier = Modifier, size: Float = 11f) {
    val tone = prTone(state)
    val ink = prToneAlpha(tone, 0.85)
    val fill = prToneAlpha(tone, 0.08)
    val text = number.toString()
    // s is a UIKit point, not an sp: fixed(size) renders exactly size dp
    // whatever the font scale, and already sets GeistMono Medium with
    // includeFontPadding = false and no lineHeight/letterSpacing.
    //
    // TextMotion.Animated (linear metrics + subpixel positioning): the
    // default TextMotion.Static clears SUBPIXEL_TEXT_FLAG and hints, which
    // rounds every glyph advance to whole px — at s = 14, xxhdpi, "102"
    // measures 3 × 25 px = 25 dp instead of CoreText's 3 × 8.4 = 25.2 dp, so
    // the ceiled label width (and the badge) came out 1 dp short of the
    // ticket's 55 dp. Measuring and drawing with the same style keeps the
    // glyphs inside the measured box.
    val style = uiStyle(Face.MonoMedium, LocalTypeScale.current.fixed(size), ink)
        .copy(textMotion = TextMotion.Animated)
    val density = LocalDensity.current.density
    val layout = rememberTextMeasurer().measure(text, style, maxLines = 1, softWrap = false)
    // iOS ceils UILabel.sizeThatFits; Compose measures in px, so convert
    // back to dp and ceil with a 1e-3 tolerance so a float like 30.0000004
    // dp (s = 10, five digits) does not round up to 31.
    val labelWidth = ceil((layout.getLineRight(0) - layout.getLineLeft(0)) / density - 1e-3f)
    val m = prBadgeMetrics(size, labelWidth)
    Box(
        modifier
            .size(m.width.dp, m.height.dp)
            .background(fill, SmoothRoundedShape(m.radius.dp))
            .clearAndSetSemantics { contentDescription = prBadgeA11yLabel(number, state) },
    ) {
        // The icon spans the badge height at x = pad; the s × s glyph is
        // centred vertically, which offset((H − s)/2) reproduces exactly.
        PrIcon(size.dp, ink, Modifier.offset(x = m.iconX.dp, y = m.iconY.dp))
        // CenterStart centres the 1.3·s line box vertically, putting the
        // baseline at (H − 1.3s)/2 + 1.005s — the UILabel layout.
        BasicText(
            text = text,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = m.labelX.dp),
            style = style,
            maxLines = 1,
            softWrap = false,
        )
    }
}

// ---------------------------------------------------------------------------
// PrIcon — the pull-request glyph (24 grid), drawn in code as a template
// ---------------------------------------------------------------------------

/** iOS PRIcon's per-side path cache: one [Path] per side, shared by every draw. */
private val prIconPaths = HashMap<Float, Path>()

/**
 * `pull-request.svg` from the desktop (`crates/ui/assets/icons/`) on its
 * 24 grid, as **one** [Path] with all subpaths — `PRIcon.image(side:)`
 * (`StatusGlyph.swift:226-257`) strokes exactly one path so translucent
 * ink (tone @ 0.85) never double-composites where the trunk's round caps
 * overlap the ring strokes. #621's separate `drawCircle` calls show those
 * darker dots; do not go back to them.
 *
 * Subpaths: the three ring circles (r 2.25 at (6, 5), (6, 19), (18, 19)),
 * the trunk, the branch (run-in, quarter arc to (18, 7.25), drop) and the
 * open arrowhead pointing right. All coordinates × `sidePx / 24`.
 */
fun prIconPath(sidePx: Float): Path {
    prIconPaths[sidePx]?.let { return it }
    val k = sidePx / 24f
    val path = Path()
    for ((cx, cy) in arrayOf(6f to 5f, 6f to 19f, 18f to 19f)) {
        path.addOval(Rect(Offset(cx * k, cy * k), 2.25f * k))
    }
    // Trunk: the left rings' connector.
    path.moveTo(6f * k, 7.25f * k)
    path.lineTo(6f * k, 16.75f * k)
    // Branch: run in, quarter arc clockwise from −90° to 0° around
    // (15.75, 7.25) r 2.25 — the SVG's `M15 5h.75A2.25 2.25 0 0 1 18 7.25v9.5`.
    path.moveTo(15f * k, 5f * k)
    path.lineTo(15.75f * k, 5f * k)
    path.arcTo(Rect(13.5f * k, 5f * k, 18f * k, 9.5f * k), -90f, 90f, false)
    path.lineTo(18f * k, 16.75f * k)
    // Open arrowhead pointing right, meeting the branch's start.
    path.moveTo(12.75f * k, 7.75f * k)
    path.lineTo(15.25f * k, 5f * k)
    path.lineTo(12.75f * k, 2.25f * k)
    prIconPaths[sidePx] = path
    return path
}

/**
 * The stroke width in px: `max(1 pt, 1.5 · (sidePx / 24) · 1.15)` — iOS's
 * ×1.15 boost over the source SVG's 1.5, floored at **1 dp**
 * (`max(1, 1.5 · s · 1.15)`, `StatusGlyph.swift:249`).
 *
 * [sidePx] is the side in px; [density] is px per dp, so the floor is
 * `density` px. At sides 11/12 dp the stroke is exactly 1 dp, at 14 dp
 * 1.00625 dp, at 24 dp 1.725 dp. #621's `.coerceAtLeast(1f)` floors at
 * 1 **px** instead — not this.
 */
fun prIconStrokeWidthPx(sidePx: Float, density: Float): Float =
    max(density, 1.5f * (sidePx / 24f) * 1.15f)

/**
 * The pull-request glyph as a template [Painter] — `PRIcon.image(side:)`'s
 * `withRenderingMode(.alwaysTemplate)`. Honours the alpha and the
 * `ColorFilter` it is drawn with (the tint carries its own alpha, e.g.
 * tone @ 0.85), drawing opaque black through the filter like iOS draws
 * opaque black then tints the template image.
 *
 * The path is the shared per-side cache; the stroke is
 * [prIconStrokeWidthPx] with round caps and joins.
 */
class PrIconPainter(private val sidePx: Float, private val density: Float) : Painter() {

    private var tintFilter: ColorFilter? = null
    private var alphaApplied = 1f

    override val intrinsicSize: Size
        get() = Size(sidePx, sidePx)

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        tintFilter = colorFilter
        return true
    }

    override fun applyAlpha(alpha: Float): Boolean {
        alphaApplied = alpha
        return true
    }

    override fun DrawScope.onDraw() {
        val side = size.minDimension
        drawPath(
            prIconPath(side),
            Color.Black,
            alphaApplied,
            Stroke(
                width = prIconStrokeWidthPx(side, density),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
            tintFilter,
        )
    }
}

/**
 * [PrIconPainter] at [side]: the composer's PR chip uses side 12 with
 * `PRBadgeView.tone(state)` as the tint (`CoreSessionSource.swift:57-59`).
 */
@Composable
fun rememberPrIconPainter(side: Dp = 12.dp): Painter {
    val density = LocalDensity.current
    val sidePx = with(density) { side.toPx() }
    return remember(sidePx, density.density) { PrIconPainter(sidePx, density.density) }
}

/**
 * The pull-request glyph at [side] dp, tinted [tint] — one path, one draw
 * call, stroke floored at 1 dp. Decorative: no content description (the
 * surrounding row or chip speaks).
 */
@Composable
fun PrIcon(side: Dp, tint: Color, modifier: Modifier = Modifier) {
    Image(
        painter = rememberPrIconPainter(side),
        contentDescription = null,
        modifier = modifier.size(side),
        colorFilter = ColorFilter.tint(tint),
    )
}
