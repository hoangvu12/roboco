package sh.roboco.android.design

import android.content.Context
import android.content.res.Resources
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import sh.roboco.android.BuildConfig
import sh.roboco.android.core.Face
import sh.roboco.android.core.assetPath

/**
 * One SF Symbol point → the layout box it reserves: `p × 1.2` dp. Fixed —
 * it does **not** follow the system font scale, exactly like iOS
 * `SymbolConfiguration(pointSize:)`; callers that scale with Dynamic Type
 * pass an already scaled `p` (ticket 03's `TypeScale`).
 *
 * Solar glyphs fill about 20/24 of their viewBox, so a `1.2p` box yields
 * about `1.0p` of ink — matching SF Symbols at point size `p` (measured:
 * the Settings symbols draw ≈ 21.7 pt of ink in a 24 dp box).
 */
internal const val BOX_PER_PT = 1.2f

/** `sizePt × 1.2` dp, the [Icon] layout box. */
internal fun iconBoxDp(sizePt: Float): Dp = (sizePt * BOX_PER_PT).dp

// ---------------------------------------------------------------------------
// Icon + IconInBox
// ---------------------------------------------------------------------------

/**
 * Draws [icon] at an SF Symbol point size, iOS-verbatim: callers pass the
 * same `pointSize` and `weight` the Swift call site passes
 * (`UIImage.SymbolConfiguration(pointSize:weight:)`). The composable
 * reserves a `sizePt × 1.2` dp box, centres the glyph's drawing rect (a
 * `box × opticalScale` square, allowed to overhang the box, never clipped)
 * and scales every stroked path by [weight]'s stroke scale.
 *
 * A [RobocoIcon.Number] delegates to [NumberCircle].
 *
 * This is Compose-foundation only — no Material `Icon`, no
 * `LocalContentColor`, no intrinsic-size letterboxing.
 *
 * @param tint the glyph colour, `Palette.<role>` at the call site
 * @param sizePt the iOS point size, verbatim (never fudged to compensate
 *   for glyph proportions — [RobocoIcon.Vector.opticalScale] does that)
 * @param weight the SF weight, as a stroke scale
 * @param contentDescription `null` for a decorative glyph (no semantics
 *   node); buttons that wrap an icon set the label on the button instead
 */
@Composable
fun Icon(
    icon: RobocoIcon,
    tint: Color,
    sizePt: Float,
    weight: SymbolWeight = SymbolWeight.Regular,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    when (icon) {
        is RobocoIcon.Number -> NumberCircle(icon.n, tint, sizePt, weight, modifier)
        is RobocoIcon.Vector ->
            IconInBox(icon, tint, iconBoxDp(sizePt), weight, modifier, contentDescription)
    }
}

/**
 * [Icon] with an explicit box, for the cases SF sizing does not cover: tab
 * glyphs (24 dp), swipe actions (22 dp per android-app#20) and the
 * transcript icon widget's core rects. The glyph's drawing rect is
 * `box × opticalScale`, centred — it may overhang without affecting
 * layout. A [RobocoIcon.Number] fills the box via [NumberCircle].
 */
@Composable
fun IconInBox(
    icon: RobocoIcon,
    tint: Color,
    box: Dp,
    weight: SymbolWeight = SymbolWeight.Regular,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    when (icon) {
        is RobocoIcon.Number -> NumberCircle(icon.n, tint, box.value / BOX_PER_PT, weight, modifier)
        is RobocoIcon.Vector ->
            VectorIconInBox(icon, tint, box, weight, modifier, contentDescription)
    }
}

@Composable
private fun VectorIconInBox(
    icon: RobocoIcon.Vector,
    tint: Color,
    box: Dp,
    weight: SymbolWeight,
    modifier: Modifier,
    contentDescription: String?,
) {
    // The cached vector keeps (res, weight) stable across recompositions;
    // the painter adapts its intrinsic size to the box we draw below.
    val vector = weightedIconVector(LocalContext.current.resources, icon, weight)
    if (BuildConfig.DEBUG) {
        check(vector.viewportWidth == vector.viewportHeight) {
            "zi drawable ${icon.res} has viewport ${vector.viewportWidth}×" +
                "${vector.viewportHeight}; every icon viewBox must be square"
        }
    }
    val painter = rememberVectorPainter(vector)
    val tintFilter = remember(tint) { ColorFilter.tint(tint) }
    // Mirroring lives here and nowhere else (see RobocoIcon.Vector).
    val mirror = icon.autoMirror && LocalLayoutDirection.current == LayoutDirection.Rtl
    val description = contentDescription
    val semantics = if (description != null) {
        Modifier.semantics {
            this.contentDescription = description
            role = Role.Image
        }
    } else {
        Modifier
    }
    Spacer(
        modifier
            .size(box)
            .then(semantics)
            // drawBehind, not Image(...): the glyph may overhang the box
            // when opticalScale > 1 without changing layout, and never
            // letterboxes.
            .drawBehind {
                val s = box.toPx() * icon.opticalScale
                translate((size.width - s) / 2f, (size.height - s) / 2f) {
                    if (mirror) {
                        scale(-1f, 1f, pivot = Offset(s / 2f, s / 2f)) {
                            with(painter) { draw(Size(s, s), colorFilter = tintFilter) }
                        }
                    } else {
                        with(painter) { draw(Size(s, s), colorFilter = tintFilter) }
                    }
                }
            },
    )
}

// ---------------------------------------------------------------------------
// NumberCircle (SF "N.circle")
// ---------------------------------------------------------------------------

/** Ring radius per box: Solar `check-circle-linear`'s r=10 on a 24 grid. */
private const val RING_RADIUS_PER_BOX = 10f / 24f

/** Ring stroke per box at Regular: Solar's 1.5 on a 24 grid. */
private const val RING_STROKE_PER_BOX = 1.5f / 24f

/** SF `0.circle`…`50.circle`. */
internal const val NUMBER_CIRCLE_MAX = 50

/**
 * The shown `n`: unchanged inside 0…[NUMBER_CIRCLE_MAX], a `require`-style
 * failure in debug (an SF-unknown index is a programming error), clamped
 * in release so a bad index still draws a valid glyph.
 */
internal fun clampNumberCircle(n: Int): Int =
    if (n in 0..NUMBER_CIRCLE_MAX) {
        n
    } else if (BuildConfig.DEBUG) {
        throw IllegalArgumentException(
            "NumberCircle n=$n is outside SF 0.circle…$NUMBER_CIRCLE_MAX.circle",
        )
    } else {
        n.coerceIn(0, NUMBER_CIRCLE_MAX)
    }

/**
 * The digit size fraction: Geist Medium at `0.50 × box` for one digit,
 * `0.42 × box` for two — SF's `N.circle` keeps the digit at about half the
 * ring diameter, and iOS only ever uses 1…N (`QuestionPanel.swift:120`).
 */
internal fun numberCircleDigitFraction(text: String): Float =
    if (text.length == 1) 0.50f else 0.42f

/** The Geist-Medium [Typeface] for [NumberCircle]'s native `Paint`. */
private object GeistMediumTypeface {
    @Volatile
    private var cached: Typeface? = null

    fun get(context: Context): Typeface {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: Typeface
                .createFromAsset(context.applicationContext.assets, Face.SansMedium.assetPath)
                .also { cached = it }
        }
    }
}

/**
 * SF `N.circle` drawn in code (no drawable): a ring like Solar's
 * `check-circle-linear` (radius `box × 10/24`, stroke `box × 1.5/24 ×
 * weight.strokeScale`) with `n` centred in Geist Medium (ticket 03's
 * face — a native `Paint` cannot use the Compose `FontFamily`).
 *
 * The box is `sizePt × 1.2` dp like [Icon]; the digit size is fixed at a
 * fraction of the box, never scaled by fontScale. `n` outside 0…50 fails
 * `require`-style in debug and clamps to 0…50 in release.
 */
@Composable
fun NumberCircle(
    n: Int,
    tint: Color,
    sizePt: Float = 17f,
    weight: SymbolWeight = SymbolWeight.Regular,
    modifier: Modifier = Modifier,
) {
    val shown = clampNumberCircle(n)
    val text = shown.toString()
    val box = iconBoxDp(sizePt)
    val context = LocalContext.current
    val typeface = remember { GeistMediumTypeface.get(context) }
    // One Paint per composition; textSize/colour are set in the draw block,
    // where the density is known.
    val paint = remember(typeface) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface }
    }
    Canvas(modifier.size(box)) {
        val px = box.toPx()
        drawCircle(
            color = tint,
            radius = px * RING_RADIUS_PER_BOX,
            style = Stroke(px * RING_STROKE_PER_BOX * weight.strokeScale),
        )
        paint.textSize = px * numberCircleDigitFraction(text)
        paint.color = tint.toArgb()
        drawIntoCanvas { canvas ->
            val origin = digitDrawOrigin(paint, text, center.x, center.y)
            canvas.nativeCanvas.drawText(text, origin.x, origin.y, paint)
        }
    }
}

/**
 * The digit draw origin that centres the glyph's **ink** bounds on the ring
 * centre — `x = cx − rect.exactCenterX()`, `y = cy − rect.exactCenterY()`
 * with `rect` from `Paint.getTextBounds` (SF centres the digit on its ink,
 * not its advance). [paint.textSize] must already be set.
 */
internal fun digitDrawOrigin(paint: Paint, text: String, cx: Float, cy: Float): Offset {
    val bounds = Rect()
    paint.getTextBounds(text, 0, text.length, bounds)
    return Offset(cx - bounds.exactCenterX(), cy - bounds.exactCenterY())
}

// ---------------------------------------------------------------------------
// Weight-scaled vectors
// ---------------------------------------------------------------------------

/**
 * Process-wide cache of loaded/weight-scaled vectors, keyed by
 * `(res, weight)`. Composition is main-thread, so a plain `HashMap` needs
 * no synchronisation; the rebuild must never run per frame.
 */
private val weightedVectorCache = HashMap<Pair<Int, SymbolWeight>, ImageVector>()

/**
 * [icon]'s [ImageVector] with [weight]-scaled strokes: the vector loaded
 * from the #13 drawable as-is for [SymbolWeight.Regular], a rebuilt copy
 * for every other weight. The result is cached per `(res, weight)` — two
 * calls with the same key return the same instance.
 *
 * The loaded vector never sets `android:autoMirrored` (#13's generator
 * writes no such attribute), asserted in debug: mirroring is [IconInBox]'s
 * job, and `VectorPainter` would otherwise mirror a second time in RTL.
 */
internal fun weightedIconVector(
    resources: Resources,
    icon: RobocoIcon.Vector,
    weight: SymbolWeight,
): ImageVector {
    val key = icon.res to weight
    weightedVectorCache[key]?.let { return it }
    val base = ImageVector.vectorResource(null, resources, icon.res)
    if (BuildConfig.DEBUG) {
        check(!base.autoMirror) {
            "zi drawable ${icon.res} sets android:autoMirrored; " +
                "IconInBox does the mirroring and would double-mirror in RTL"
        }
    }
    val vector = if (weight == SymbolWeight.Regular) base else rescaleVectorStrokes(base, weight)
    weightedVectorCache[key] = vector
    return vector
}

/**
 * Rebuilds [vector] with every **stroked** path's `strokeLineWidth ×
 * weight.strokeScale` (fill-only paths are copied unchanged — SF weights
 * change stroke thickness, not a solid glyph's extent). Same viewports and
 * default size; `autoMirror = false`, per [weightedIconVector].
 */
internal fun rescaleVectorStrokes(vector: ImageVector, weight: SymbolWeight): ImageVector {
    val builder = ImageVector.Builder(
        vector.name,
        vector.defaultWidth,
        vector.defaultHeight,
        vector.viewportWidth,
        vector.viewportHeight,
        vector.tintColor,
        vector.tintBlendMode,
        false,
    )
    appendVectorChildren(builder, vector.root, weight)
    return builder.build()
}

private fun appendVectorChildren(
    builder: ImageVector.Builder,
    group: VectorGroup,
    weight: SymbolWeight,
) {
    for (child in group) {
        when (child) {
            is VectorGroup -> {
                builder.addGroup(
                    child.name,
                    child.rotation,
                    child.pivotX,
                    child.pivotY,
                    child.scaleX,
                    child.scaleY,
                    child.translationX,
                    child.translationY,
                    child.clipPathData,
                )
                appendVectorChildren(builder, child, weight)
                builder.clearGroup()
            }

            is VectorPath -> builder.addPath(
                child.pathData,
                child.pathFillType,
                child.name,
                child.fill,
                child.fillAlpha,
                child.stroke,
                child.strokeAlpha,
                if (child.stroke != null) {
                    child.strokeLineWidth * weight.strokeScale
                } else {
                    child.strokeLineWidth
                },
                child.strokeLineCap,
                child.strokeLineJoin,
                child.strokeLineMiter,
                child.trimPathStart,
                child.trimPathEnd,
                child.trimPathOffset,
            )
        }
    }
}

/** Every [VectorPath] of [this] in paint order, groups flattened. */
internal fun ImageVector.vectorPaths(): List<VectorPath> {
    val paths = mutableListOf<VectorPath>()
    fun walk(group: VectorGroup) {
        for (child in group) {
            when (child) {
                is VectorPath -> paths += child
                is VectorGroup -> walk(child)
            }
        }
    }
    walk(root)
    return paths
}
