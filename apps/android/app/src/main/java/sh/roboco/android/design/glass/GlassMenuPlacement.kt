package sh.roboco.android.design.glass

import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.min

/**
 * The anchored placement of one menu panel (android-ui-parity #15): where
 * the panel sits, how much room it has, and which corner it grows from.
 * Pure pixel maths — [GlassMenuPlacementTest] pins every rule on the plain
 * JVM.
 *
 * All coordinates are **physical** host-space pixels (x grows to the
 * right), exactly as [placeGlassMenu] computes them; the function takes no
 * layout-direction parameter, so the result never depends on RTL — RTL only
 * mirrors the content inside the panel.
 */
data class MenuPlacement(
    /** The panel's top-left offset in host coordinates. */
    val offset: IntOffset,
    /** The height budget in the chosen direction; the content scrolls beyond it. */
    val maxHeight: Int,
    /** True when the panel grows downward from the anchor (it covered it going down). */
    val below: Boolean,
    /** True when the panel's right edge is aligned to the anchor's right edge. */
    val alignEnd: Boolean,
) {
    /**
     * The [TransformOrigin] of the corner the panel grows out of: the open
     * animation scales from the corner nearest the anchor, like the iOS 26
     * pull-down that "grows out of its button".
     */
    val origin: TransformOrigin
        get() = TransformOrigin(
            if (alignEnd) 1f else 0f,
            if (below) 0f else 1f,
        )
}

/**
 * Places a menu panel against [anchor], geometric (see `pr621`'s
 * `AnchoredMenu` for the clamp idea, iOS 26 for the cover placement):
 *
 *  1. **Usable area** = [bounds] inset by [margin] on all four sides.
 *  2. **Horizontal edge**: `alignEnd = anchor.center.x > bounds.center.x`
 *     (grow from the corner nearest the anchor). With `alignEnd` the
 *     panel's right edge aligns to the anchor's right; otherwise the left
 *     edges align. Then clamp into the area.
 *  3. **Vertical direction**: with `cover = true` (iOS 26 pull-down) the
 *     panel covers the anchor — it starts at `anchor.top` going down, or
 *     ends at `anchor.bottom` going up. With `cover = false` it sits
 *     [gap] away from the anchor's edge instead. `below` prefers fitting
 *     below, else the side with more room; [forceBelow] keeps the
 *     direction chosen at open while the panel resizes.
 *  4. **Height and y**: `maxHeight` is the room in the chosen direction,
 *     `h = min(panel.height, maxHeight)`, and `y` clamps into the area.
 *
 * A zero-size [anchor] (a point) works with the same maths.
 */
fun placeGlassMenu(
    anchor: IntRect,
    panel: IntSize,
    bounds: IntRect,
    margin: Int,
    cover: Boolean = true,
    gap: Int = 0,
    forceBelow: Boolean? = null,
): MenuPlacement {
    // 1. The usable area.
    val areaLeft = bounds.left + margin
    val areaTop = bounds.top + margin
    val areaRight = bounds.right - margin
    val areaBottom = bounds.bottom - margin

    // 2. The horizontal edge.
    val alignEnd = anchor.center.x > bounds.center.x
    val rawX = if (alignEnd) anchor.right - panel.width else anchor.left
    val x = rawX.coerceIn(areaLeft, max(areaLeft, areaRight - panel.width))

    // 3. The vertical direction.
    val startBelow = if (cover) anchor.top else anchor.bottom + gap
    val endAbove = if (cover) anchor.bottom else anchor.top - gap
    val roomBelow = areaBottom - startBelow
    val roomAbove = endAbove - areaTop
    val below = forceBelow ?: (panel.height <= roomBelow || roomBelow >= roomAbove)

    // 4. Height and y.
    val maxHeight = max(1, if (below) roomBelow else roomAbove)
    val height = min(panel.height, maxHeight)
    val rawY = if (below) startBelow else endAbove - height
    val y = rawY.coerceIn(areaTop, max(areaTop, areaBottom - height))

    return MenuPlacement(IntOffset(x, y), maxHeight, below, alignEnd)
}
