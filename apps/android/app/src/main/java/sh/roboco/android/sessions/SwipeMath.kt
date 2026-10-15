package sh.roboco.android.sessions

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import sh.roboco.android.design.IosSpring
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * The pure swipe maths (android-ui-parity #44) — everything in [SwipeRow]
 * that has a unit-testable rule: the gesture lock, the release decision
 * table and the pane geometry. Pure Kotlin + Compose value types only
 * ([dp], [Color], [IosSpring]); no snapshot state, no density, so the
 * plain-JVM [SwipeMathTest] pins every branch.
 *
 * The `\*` values below are Android-side choices (UIKit exposes none of
 * its swipe metrics); they come from `android-app#20` §"Swipe mechanics",
 * checked once against an iOS 26 simulator — see the ticket's Comments.
 */
object SwipeTokens {

    /** One action pane's natural width — UIKit's icon-only button. (\*) */
    val ButtonWidth = 74.dp

    /** The pane glyph's reserved box (ticket 06, `IconInBox`). */
    val IconBox = 22.dp

    /** |offset| at or beyond this fraction of the row width is **armed**. (\*) */
    const val FullSwipeFraction = 0.6f

    /** Total travel (hypot of dx, dy) before the lock decides. (\*) */
    val LockSlop = 8.dp

    /** Claim iff |dx| > [LockRatio] · |dy| — clearly horizontal. (\*) */
    const val LockRatio = 1.5f

    /** Release velocity that settles open/closed outright. (\*) per second */
    val FlingVelocity = 300.dp

    /**
     * The settle spring (0.35 s / ζ 0.90) — settle-matched stiffness
     * 480.90, computed by [IosSpring], never pasted. One spring for every
     * open, close, commit and refusal animation.
     */
    val Settle = IosSpring("swipeSettle", durationSec = 0.35, damping = 0.90f)

    /**
     * The Move pane's literal background —
     * `UIColor(hex: 0x5E6AD2)` (`SessionListController.swift:261`),
     * opaque and the same in light and dark.
     */
    val MoveTint = Color(0xFF5E6AD2)
}

// ---------------------------------------------------------------------------
// The gesture lock
// ---------------------------------------------------------------------------

/** What the lock decides once the slop is exceeded. */
enum class LockDecision {
    /** Still inside the slop: keep observing, move nothing. */
    Undecided,

    /** Clearly horizontal and allowed: the row claims the pointer. */
    Horizontal,

    /** Vertical/diagonal or toward a side with no actions: the list scrolls. */
    Reject,
}

/**
 * UIKit's swipe-vs-scroll contention, decided from total travel:
 * `hypot(dx, dy) <= slopPx` → [LockDecision.Undecided]; otherwise
 * horizontal only when `|dx| > 1.5·|dy|` **and** that direction can go.
 *
 * [canGo] receives the **physical** sign of dx (+ = to the right); the
 * caller maps it through the layout direction (`canGo` in `SwipeRow.kt`).
 * It answers "the row is already open, or that side has actions" — an
 * open row may be dragged back the way it cannot newly open.
 */
fun lockDecision(dx: Float, dy: Float, slopPx: Float, canGo: (dxSign: Int) -> Boolean): LockDecision {
    if (hypot(dx, dy) <= slopPx) return LockDecision.Undecided
    if (abs(dx) > SwipeTokens.LockRatio * abs(dy) && canGo(if (dx > 0f) 1 else -1)) {
        return LockDecision.Horizontal
    }
    return LockDecision.Reject
}

// ---------------------------------------------------------------------------
// The release decision
// ---------------------------------------------------------------------------

/** What a release (or a cancelled drag) does. */
sealed interface SwipeRelease {
    /** Spring the offset to [target] (0, +open or −open, logical px). */
    data class Settle(val target: Float) : SwipeRelease

    /** Past the full-swipe fraction: perform the side's first action. */
    data class Commit(val leading: Boolean) : SwipeRelease
}

/**
 * The release table, UIKit's unobservable internals made explicit:
 *
 *  - `|x| ≥ 0.6·widthPx` on a side with actions → [SwipeRelease.Commit];
 *  - a fling (|v| ≥ [flingPx], toward opening) settles open, toward
 *    closing settles at 0;
 *  - otherwise open when the offset has passed **half** the side's open
 *    width (37 dp of 74, 74 dp of 148), else close;
 *  - `x == 0` (a cancelled drag) → close.
 *
 * [x] and [v] are **logical** px / px-per-second (+ = leading revealed);
 * [flingPx] is `SwipeTokens.FlingVelocity` in px.
 */
fun swipeRelease(
    x: Float,
    v: Float,
    widthPx: Float,
    buttonPx: Float,
    leadingCount: Int,
    trailingCount: Int,
    flingPx: Float,
): SwipeRelease {
    val full = SwipeTokens.FullSwipeFraction * widthPx
    if (x >= full && leadingCount > 0) return SwipeRelease.Commit(leading = true)
    if (x <= -full && trailingCount > 0) return SwipeRelease.Commit(leading = false)
    if (x > 0f) {
        // A side with no actions cannot be revealed at all (the drag clamps
        // to 0); the degenerate open = 0 answers Settle(0) either way.
        val open = buttonPx * leadingCount
        return when {
            v >= flingPx -> SwipeRelease.Settle(open)
            v <= -flingPx -> SwipeRelease.Settle(0f)
            else -> SwipeRelease.Settle(if (x >= open / 2f) open else 0f)
        }
    }
    if (x < 0f) {
        // A side with no actions cannot be revealed at all (the drag clamps
        // to 0); the degenerate open = 0 answers Settle(0) — without the
        // guard it would answer Settle(−0f).
        val open = buttonPx * trailingCount
        if (open <= 0f) return SwipeRelease.Settle(0f)
        return when {
            v >= flingPx -> SwipeRelease.Settle(0f)
            v <= -flingPx -> SwipeRelease.Settle(-open)
            else -> SwipeRelease.Settle(if (x <= -open / 2f) -open else 0f)
        }
    }
    return SwipeRelease.Settle(0f)
}

// ---------------------------------------------------------------------------
// The pane geometry
// ---------------------------------------------------------------------------

/**
 * One pane's extent, measured **from the side's screen edge** going
 * inward (px): the pane spans `[outer, inner]`, and the icon is centred
 * in the slot `[slotOuter, inner]` — an
 * `IconInBox`-shaped window `min(paneWidth, 74)` wide glued to the
 * pane's inner (content-side) edge.
 *
 * `index` 0 is the **outermost** action (nearest the screen edge), the
 * first element of `UISwipeActionsConfiguration(actions:)` — leading Pin,
 * trailing Archive, archived Unarchive.
 */
data class PaneSpan(val index: Int, val outer: Float, val inner: Float, val slotOuter: Float)

/**
 * The panes of one side for the current reveal.
 *
 *  - Unarmed, n panes: equal slices, `r / n` each, pane 0 outermost.
 *  - n = 1 (leading, archived trailing): `[0, r]`, never narrower than
 *    the reveal (the single pane is the whole gap).
 *  - n = 2 armed at arm progress [arm] (0 → 1): pane 0 grows
 *    `lerp(r/2, r, a)` from the edge, pane 1 collapses to
 *    `lerp(r/2, 0, a)` between pane 0 and the content — the Archive
 *    button takes the whole gap as the full swipe arms.
 */
fun paneSpans(revealed: Float, count: Int, buttonPx: Float, arm: Float): List<PaneSpan> {
    val r = revealed
    if (count <= 0 || r < 0f) return emptyList()
    if (count == 1) {
        val slot = min(r, buttonPx)
        return listOf(PaneSpan(0, 0f, r, r - slot))
    }
    if (count == 2) {
        val w0 = lerp(r / 2f, r, arm)
        val w1 = r - w0
        val slot0 = min(w0, buttonPx)
        val slot1 = min(w1, buttonPx)
        return listOf(
            PaneSpan(0, 0f, w0, w0 - slot0),
            PaneSpan(1, w0, r, r - slot1),
        )
    }
    // No list has more than two actions per side; keep the equal split
    // (arm is a two-action rule) so the function stays total.
    val slice = r / count
    return (0 until count).map { i ->
        val outer = i * slice
        val inner = (i + 1) * slice
        PaneSpan(i, outer, inner, inner - min(slice, buttonPx))
    }
}

/** `a + (b − a) · t`, clamped to nothing — the caller owns the domain. */
private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
