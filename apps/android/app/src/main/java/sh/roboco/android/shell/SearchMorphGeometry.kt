package sh.roboco.android.shell

/**
 * One dp rect in **row-local** coordinates (android-ui-parity #29): `x`
 * measured from the row box's leading edge, `y` from its top, the row box
 * being ticket 26's centred, capped row — width
 * `tabBarRowWidth(available)`, height [SearchMorphGeometry.RowHeight],
 * its bottom edge at ticket 26's `tabBarBottomGap` above the window
 * bottom. Pure Kotlin, no Compose: the geometry below stays plain-JVM
 * testable.
 */
data class BarRect(val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * The search morph's pure geometry (android-ui-parity #29): the bottom bar
 * morphs into the search row — the tab pill collapses into the 48 dp
 * **return circle** at the row's leading edge, the search circle stretches
 * into the 48 dp **field capsule** that fills the rest of the row, both
 * bottom-aligned with the row (the same bottom as ticket 28's minimized
 * circles; the outer edges stay where ticket 26's pill and circle edges
 * are, so nothing slides sideways).
 *
 * Every function is a pure mapping from the morph progress `p` (0 = normal
 * bar, 1 = search mode, unclamped — the spring overshoots ≈ 0.6 %) into
 * row-local rects; alphas and colours clamp `p` to 0…1. Nothing here reads
 * the runtime — `SearchMorphGeometryTest` pins the ticket's worked table.
 */
object SearchMorphGeometry {

    /** = ticket 26's `GlassTabBarDefaults.PillHeight`: the row box's height. */
    const val RowHeight = 62f

    /** The 48 dp search-mode pieces (and ticket 28's minimized circles). */
    const val Circle = 48f

    /** The return circle → field gap (ticket 26's pill → circle gap). */
    const val Gap = 8f

    /** The row's clearance above the IME top while it rides on the IME. */
    const val ImeGap = 8f

    /** The accessory fade span: accessory height 48 + its gap 8. */
    const val AccessoryFadeSpan = 56f

    // ---------------------------------------------- the field's content
    // (dp, field-local, relative to the field capsule's leading edge)

    /** The magnifier's point size at p = 0 (26's circle glyph). */
    const val MagnifierPtRest = 22f

    /** The magnifier's point size at p = 1 (the field's SF body glyph). */
    const val MagnifierPtField = 17f

    /**
     * SF Symbol box = pt × 1.2 (ticket 06's `BOX_PER_PT`, as a pure float
     * so this object carries no Compose types).
     */
    const val BoxPerPt = 1.2f

    /** The magnifier box's leading inset at p = 1. */
    const val MagnifierLeading = 14f

    /** The text's start from the field's leading edge. */
    const val TextStart = 40f

    /** The text's end inset when no clear button is visible. */
    const val TextEndInset = 14f

    /** The clear glyph box's trailing inset. */
    const val ClearTrailingInset = 12f

    /** The clear glyph box's gap before the text's end. */
    const val ClearTextGap = 6f

    /** The clear button's touch target width (44 × 48, centred on the glyph). */
    const val ClearTouchWidth = 44f

    // ------------------------------------------------------------ targets

    /** The return circle at p = 1: row-local (0, 14, 48, 48). */
    fun returnTarget(): BarRect = BarRect(0f, RowHeight - Circle, Circle, Circle)

    /** The field capsule at p = 1: row-local (56, 14, R − 56, 48). */
    fun fieldTarget(rowWidth: Float): BarRect =
        BarRect(Circle + Gap, RowHeight - Circle, rowWidth - (Circle + Gap), Circle)

    /** Component-wise `a + (b − a) · t` on x, y, w and h (t unclamped). */
    fun lerp(a: BarRect, b: BarRect, t: Float): BarRect = BarRect(
        x = a.x + (b.x - a.x) * t,
        y = a.y + (b.y - a.y) * t,
        w = a.w + (b.w - a.w) * t,
        h = a.h + (b.h - a.h) * t,
    )

    /**
     * The return circle at progress [p]: the pill rect at p = 0 for the
     * current minimize position (`pill0`, row-local) lerped to
     * [returnTarget]. Entering Search from a minimized bar animates ticket
     * 28's `m → 0` and `p → 1` together; `pill0` follows `m`, and both
     * ends converge on the same target, so the motion stays continuous.
     */
    fun returnRect(pill0: BarRect, p: Float): BarRect = lerp(pill0, returnTarget(), p)

    /** The field capsule at progress [p]: `search0` lerped to [fieldTarget]. */
    fun fieldRect(search0: BarRect, rowWidth: Float, p: Float): BarRect =
        lerp(search0, fieldTarget(rowWidth), p)

    // --------------------------------------------------------- contents

    /** The magnifier's point size: 22 at rest → 17 in the field. */
    fun magnifierPt(p: Float) = MagnifierPtRest + (MagnifierPtField - MagnifierPtRest) * p.coerceIn(0f, 1f)

    /**
     * The magnifier box's centre x, **field-local**: centred in the circle
     * at p = 0, its leading inset (14 + box / 2) at p = 1.
     */
    fun magnifierCenterX(field: BarRect, p: Float): Float {
        val box = magnifierPt(p) * BoxPerPt
        return field.w / 2f + (MagnifierLeading + box / 2f - field.w / 2f) * p
    }

    /**
     * The accessory's bottom edge, row-local: `lerp(0, 14, p) − 8` — 8 above
     * the row box's top at p = 0, 8 above the field's top (row y 14) at
     * p = 1 (the row shrinks 62 → 48, so the accessory slides down 14).
     */
    fun accessoryBottomY(p: Float) = (RowHeight - Circle) * p - Gap

    /** The text, placeholder and clear fade: 0 until p = 0.6, 1 at p = 1. */
    fun contentAlpha(p: Float) = ((p - 0.6f) / 0.4f).coerceIn(0f, 1f)

    /** The pill contents (platter + items) fade: 1 at p = 0, gone at p = 0.5. */
    fun pillItemsAlpha(p: Float) = 1f - (p / 0.5f).coerceIn(0f, 1f)

    /** The collapsed (previous-tab) glyph fade: 0 until p = 0.5, 1 at p = 1. */
    fun collapsedIconAlpha(p: Float) = ((p - 0.5f) / 0.5f).coerceIn(0f, 1f)

    // ------------------------------------------------------------ the IME

    /**
     * The row's bottom edge above the window bottom: at rest [b]; while
     * the search chrome is on screen (`active` = Search selected or p > 0),
     * `max(b, imeBottom + ImeGap)` — the row rides on the IME, 8 dp ★ above
     * its top.
     */
    fun rowBottom(b: Float, imeBottom: Float, active: Boolean) =
        if (active) maxOf(b, imeBottom + ImeGap) else b

    // -------------------------------------------------------------- RTL

    /** Mirrors a row-local rect: `x' = rowWidth − x − w` (identity in LTR). */
    fun mirror(r: BarRect, rowWidth: Float, rtl: Boolean) =
        if (rtl) r.copy(x = rowWidth - r.x - r.w) else r

    // ----------------------------------------------- field content boxes

    /**
     * The text box's end inset from the field's leading edge: 38.4 (12
     * trailing + 20.4 glyph box + 6 gap) when the clear button is visible,
     * else 14. At R = 360 the text box is 304 − 40 − 14 = 250 dp wide (the
     * placeholder's 224.4 dp fits).
     */
    fun textEnd(clearVisible: Boolean): Float =
        if (clearVisible) ClearTrailingInset + MagnifierPtField * BoxPerPt + ClearTextGap else TextEndInset

    /**
     * The clear button's touch-box start, field-local: the 44 dp-wide box
     * centred on the glyph, whose trailing edge sits 12 from the field's
     * trailing edge (the final rect's — the content never reflows).
     */
    fun clearTouchStart(fieldWidth: Float): Float {
        val glyphCentre = fieldWidth - ClearTrailingInset - MagnifierPtField * BoxPerPt / 2f
        return glyphCentre - ClearTouchWidth / 2f
    }
}
