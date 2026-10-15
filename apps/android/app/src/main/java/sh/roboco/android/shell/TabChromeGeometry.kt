package sh.roboco.android.shell

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.util.lerp

/**
 * The tab chrome's measured constants, in dp as `Float` (android-ui-parity
 * #28) — the pure-geometry mirror of ticket 26's `GlassTabBarDefaults` (Dp)
 * and 27's `NewSessionAccessoryMetrics.Height`. Every value that exists in
 * 26/27 is the same number, and `TabChromeGeometryTest` pins the equality
 * so the two can never drift; the starred (*) values are 28's own, from
 * android-app#19 §"Minimize on scroll" (iOS exposes none of them).
 *
 * This object is `const`-only so the geometry function below stays pure and
 * plain-JVM testable: no Compose runtime, no [androidx.compose.ui.unit.Dp].
 */
object TabChromeMetrics {

    /** = GlassTabBarDefaults.SideMargin (26): the screen → row margin. */
    const val SideMargin = 21f

    /** = GlassTabBarDefaults.MaxRowWidth (26): the row-width cap. */
    const val MaxRowWidth = 398f

    /** = GlassTabBarDefaults.Gap (26): pill↔search and pill↔accessory. (*) inline gaps. */
    const val Gap = 8f

    /** = GlassTabBarDefaults.PillHeight (26): the expanded pill's height. */
    const val PillHeight = 62f

    /** = GlassTabBarDefaults.SearchSize (26): the expanded search circle. */
    const val SearchExpanded = 62f

    /** = NewSessionAccessoryMetrics.Height (27): the accessory capsule. */
    const val AccessoryHeight = 48f

    /** (*) The pill and search circles when minimized. */
    const val Minimized = 48f

    /** = GlassTabBarDefaults.IconBox (26): the tab glyph's layout box. */
    const val IconSize = 24f

    /** = 26's IconTop 12 + IconBox / 2: the glyph centre below the cell top. */
    const val IconCentreY = 24f

    /** = GlassTabBarDefaults.PlatterInset (26): the platter's inset. */
    const val PlatterInset = 4f

    /** (*) The scroll accumulator's trigger distance. */
    const val ThresholdDp = 24f
}

/**
 * The chrome's rects for one morph position (android-ui-parity #28), in dp
 * as [Rect]s in **start-based (LTR) shell coordinates** — origin
 * top-left of the shell, x growing toward the end side — so placement can
 * mirror exactly with `placeRelative`. `accessory` is null when there is no
 * accessory; `chromeTop` is the min of the placed pieces' tops (the value
 * ticket 30 publishes as the toast anchor / list padding bound), which is
 * never `+∞` because the pill is always placed.
 *
 * #29 adds the row box ([rowLeft]/[rowWidth] — 26's centred, capped row)
 * and the IME lift the search chrome applied to the pill and search
 * rects ([imeLift], dp — 0 whenever the search chrome is off screen).
 */
data class TabChromeRects(
    val pill: Rect,
    val search: Rect,
    val accessory: Rect?,
    val chromeTop: Float,
    val rowLeft: Float = 0f,
    val rowWidth: Float = 0f,
    val imeLift: Float = 0f,
)

/**
 * The expanded tab pill's width — the geometry's p = 0 pill rect width:
 * `rowW − Gap − SearchExpanded` (290 at W = 402, 248 at W = 360). Exposed
 * on its own because [GlassTabPill] lays its content out at the *expanded*
 * size while the glass itself shrinks, and the caller computes this from
 * the shell's width + insets (the geometry's own input, so they agree by
 * construction).
 */
fun tabPillExpandedWidth(width: Float, insetStart: Float = 0f, insetEnd: Float = 0f): Float {
    val available = width - insetStart - insetEnd
    val rowW = minOf(available - 2f * TabChromeMetrics.SideMargin, TabChromeMetrics.MaxRowWidth)
    return rowW - TabChromeMetrics.Gap - TabChromeMetrics.SearchExpanded
}

/**
 * The chrome geometry for morph progress [progress] — the pure function of
 * ticket 28's Spec table. Plain arithmetic: no Compose runtime, pinned by
 * `TabChromeGeometryTest` against the worked table (W 402 / H 874 / B 22).
 *
 *  - `bottom = height − bottomInset` is the row's bottom edge — ticket 26's
 *    `tabBarBottomGap` in the shell, 21 in the worked table;
 *  - the row is 26's `tabBarRowWidth`, centred in the inset-adjusted width;
 *  - every rect is `lerp(expanded, minimized, progress)` on all four edges.
 *    [progress] is **unclamped**: the spring overshoots past 1 and the
 *    pill keeps shrinking (48 − 0.0063 × 242 ≈ 46.5 at the peak). Alphas
 *    clamp; geometry never does;
 *  - minimized, all three pieces share the bottom edge [bottomInset] above
 *    the shell bottom, with two 8 dp gaps between the 48 dp circles.
 */
fun tabChromeGeometry(
    width: Float,
    height: Float,
    bottomInset: Float,
    hasAccessory: Boolean,
    progress: Float,
    insetStart: Float = 0f,
    insetEnd: Float = 0f,
): TabChromeRects {
    val bottom = height - bottomInset
    val available = width - insetStart - insetEnd
    val rowW = minOf(available - 2f * TabChromeMetrics.SideMargin, TabChromeMetrics.MaxRowWidth)
    val rowL = insetStart + (available - rowW) / 2f
    val rowR = rowL + rowW

    val pillW = tabPillExpandedWidth(width, insetStart, insetEnd)

    // -------------------------------------------------- expanded (p = 0)
    val pill0 = Rect(rowL, bottom - TabChromeMetrics.PillHeight, rowL + pillW, bottom)
    val search0 = Rect(
        rowR - TabChromeMetrics.SearchExpanded,
        bottom - TabChromeMetrics.SearchExpanded,
        rowR,
        bottom,
    )
    val accessory0 = Rect(
        rowL,
        bottom - TabChromeMetrics.PillHeight - TabChromeMetrics.Gap - TabChromeMetrics.AccessoryHeight,
        rowR,
        bottom - TabChromeMetrics.PillHeight - TabChromeMetrics.Gap,
    )

    // -------------------------------------------------- minimized (p = 1)
    val pill1 = Rect(rowL, bottom - TabChromeMetrics.Minimized, rowL + TabChromeMetrics.Minimized, bottom)
    val search1 = Rect(
        rowR - TabChromeMetrics.Minimized,
        bottom - TabChromeMetrics.Minimized,
        rowR,
        bottom,
    )
    val accessory1 = Rect(
        rowL + TabChromeMetrics.Minimized + TabChromeMetrics.Gap,
        bottom - TabChromeMetrics.AccessoryHeight,
        rowR - TabChromeMetrics.Minimized - TabChromeMetrics.Gap,
        bottom,
    )

    val pill = lerpRect(pill0, pill1, progress)
    val search = lerpRect(search0, search1, progress)
    val accessory = if (hasAccessory) lerpRect(accessory0, accessory1, progress) else null
    val chromeTop = minOf(pill.top, search.top, accessory?.top ?: Float.POSITIVE_INFINITY)
    return TabChromeRects(
        pill = pill,
        search = search,
        accessory = accessory,
        chromeTop = chromeTop,
        rowLeft = rowL,
        rowWidth = rowW,
    )
}

/** Component-wise `a + (b − a) · p` on all four edges. */
private fun lerpRect(a: Rect, b: Rect, p: Float): Rect = Rect(
    left = lerp(a.left, b.left, p),
    top = lerp(a.top, b.top, p),
    right = lerp(a.right, b.right, p),
    bottom = lerp(a.bottom, b.bottom, p),
)
