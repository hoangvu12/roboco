package sh.roboco.android.design.sheet

import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.ui.unit.Density
import kotlin.math.abs

/**
 * The sheet's detent anchors and its settle rule — the pure, unit-testable
 * half of the sheet (android-ui-parity #17).
 *
 * [sheetAnchors] maps the parent's geometry to `DraggableAnchors`
 * exactly as the ticket's Geometry table says, and [settleTarget] is the
 * release rule that replaces UIKit's private velocity projection
 * (25 % / 50 % / 1200 dp/s, from android-app#19). No Compose state: both
 * functions are plain, so `SheetSettleTest` runs on the plain JVM.
 */

/**
 * The anchors of one sheet layout, px from the parent's top:
 *
 * - [SheetDetent.Hidden] at the parent height (off screen);
 * - [SheetDetent.Large] at `statusBar + 10dp` (`MainTabController`'s
 *   `.pageSheet` leaves exactly that much content visible);
 * - [SheetDetent.Medium] at `H × 0.5` (Apple documents `.medium()` as
 *   "approximately half the height of the screen"), only when `Medium` is
 *   in [detents] **and** the medium position clears `Large` by more than
 *   1 px — tiny landscape windows drop the medium detent instead of
 *   producing a degenerate anchor.
 *
 * `Large` is only included when it is in [detents], so a medium-only sheet
 * cannot settle at an anchor its caller never offered.
 */
internal fun sheetAnchors(
    parentHeightPx: Float,
    statusBarPx: Float,
    density: Density,
    detents: List<SheetDetent>,
): DraggableAnchors<SheetDetent> {
    val largePx = statusBarPx + with(density) { GlassSheetDefaults.LargeTopGap.toPx() }
    val positions = mutableMapOf<SheetDetent, Float>(
        SheetDetent.Hidden to parentHeightPx,
    )
    if (SheetDetent.Large in detents) positions[SheetDetent.Large] = largePx
    if (SheetDetent.Medium in detents) {
        val mediumPx = parentHeightPx * GlassSheetDefaults.MediumFraction
        // "only if Medium is in detents and H × 0.5 > sb + 10 + 1"
        if (mediumPx > largePx + MediumDropEpsilon) positions[SheetDetent.Medium] = mediumPx
    }
    return SheetAnchors(positions)
}

/** The medium anchor is dropped when it is not more than this above `Large`. */
private const val MediumDropEpsilon = 1f

/** The fling step search ignores anchors within this distance of the sheet. */
internal const val SettleEpsilon = 0.5f

/**
 * The release rule (a pure function, unit-tested): which detent a released
 * sheet settles at.
 *
 * [velocity] is the release velocity in px/s, positive = downward, and
 * [anchors] is the live anchor set. `FlingVelocity` (1200 dp/s in px,
 * [flingVelocityPx]) and the fractions come from [GlassSheetDefaults]:
 *
 * - a fling (|velocity| ≥ 1200 dp/s) moves **one step**: downward to the
 *   first anchor below (a hard fling from `Large` dismisses a large-only
 *   sheet and stops at `Medium` when the sheet has it), upward to the last
 *   anchor above;
 * - a slow release picks between the two anchors bracketing the offset:
 *   25 % of the way toward `Hidden` (or a faster fling) dismisses, 50 %
 *   between visible detents crosses.
 */
internal fun settleTarget(
    offset: Float,
    velocity: Float,
    anchors: DraggableAnchors<SheetDetent>,
    flingVelocityPx: Float,
): SheetDetent {
    val sorted = anchors.sortedByPosition()
    if (sorted.isEmpty()) return SheetDetent.Hidden

    if (abs(velocity) >= flingVelocityPx) {
        return if (velocity > 0f) {
            // Downward fling: the first anchor strictly below, else the last.
            sorted.firstOrNull { it.second > offset + SettleEpsilon }?.first
                ?: sorted.last().first
        } else {
            // Upward fling: the last anchor strictly above, else the first.
            sorted.lastOrNull { it.second < offset - SettleEpsilon }?.first
                ?: sorted.first().first
        }
    }

    // Slow release: bracket the offset and cross at the threshold.
    val upperIndex = sorted.indexOfLast { it.second <= offset }
    val lowerIndex = sorted.indexOfFirst { it.second > offset }
    val upper = when {
        upperIndex >= 0 -> sorted[upperIndex]
        lowerIndex >= 0 -> sorted[lowerIndex]
        else -> sorted.last()
    }
    val lower = when {
        lowerIndex >= 0 -> sorted[lowerIndex]
        upperIndex >= 0 -> sorted[upperIndex]
        else -> sorted.last()
    }
    val span = lower.second - upper.second
    val fraction = if (span <= 0f) 0f else (offset - upper.second) / span
    val threshold = if (lower.first == SheetDetent.Hidden) {
        GlassSheetDefaults.DismissFraction
    } else {
        GlassSheetDefaults.DetentFraction
    }
    return if (fraction >= threshold) lower.first else upper.first
}

/** The anchors of [this] as `(detent, position)` pairs, position ascending. */
private fun DraggableAnchors<SheetDetent>.sortedByPosition(): List<Pair<SheetDetent, Float>> =
    SheetDetent.entries.mapNotNull { detent ->
        val position = positionOf(detent)
        if (position.isNaN()) null else detent to position
    }.sortedBy { it.second }

/**
 * [DraggableAnchors] over a fixed `SheetDetent` → px map, kept sorted by
 * position so [anchorAt]/[positionAt] are deterministic. Foundation's own
 * `DefaultDraggableAnchors` is internal, so sheets build theirs here —
 * equality over the positions, so `updateAnchors` with unchanged geometry
 * is a no-op.
 */
internal class SheetAnchors(positions: Map<SheetDetent, Float>) : DraggableAnchors<SheetDetent> {

    private val sorted: List<Pair<SheetDetent, Float>> =
        positions.entries.map { (detent, position) -> detent to position }.sortedBy { it.second }

    private val byDetent: Map<SheetDetent, Float> = positions

    override val size: Int get() = sorted.size

    override fun positionOf(value: SheetDetent): Float = byDetent[value] ?: Float.NaN

    override fun hasPositionFor(value: SheetDetent): Boolean = byDetent.containsKey(value)

    override fun closestAnchor(offset: Float): SheetDetent? = closestAnchor(offset, false)

    override fun closestAnchor(offset: Float, preferGreater: Boolean): SheetDetent? {
        if (sorted.isEmpty()) return null
        var best: Pair<SheetDetent, Float>? = null
        for (entry in sorted) {
            val distance = abs(entry.second - offset)
            val bestDistance = best?.let { abs(it.second - offset) } ?: Float.MAX_VALUE
            if (best == null || distance < bestDistance ||
                (distance == bestDistance && preferGreater && entry.second > best!!.second)
            ) {
                best = entry
            }
        }
        return best?.first
    }

    override fun minPosition(): Float = sorted.firstOrNull()?.second ?: Float.NaN

    override fun maxPosition(): Float = sorted.lastOrNull()?.second ?: Float.NaN

    override fun anchorAt(index: Int): SheetDetent = sorted[index].first

    override fun positionAt(index: Int): Float = sorted[index].second

    override fun equals(other: Any?): Boolean = other is SheetAnchors && other.byDetent == byDetent

    override fun hashCode(): Int = byDetent.hashCode()
}
