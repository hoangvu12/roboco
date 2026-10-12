package sh.roboco.android.shell

import androidx.compose.runtime.Immutable

/**
 * The transition geometry of the navigation stack (ticket 24), as pure
 * functions so the math is testable without a host.
 *
 * For any transition, **above** is the entry higher in the stack (push: `to`;
 * pop: `from`) and **below** is the other one; `s` is how far *above* is on
 * screen: `s = p` on push, `s = 1 − p` on pop, with `p` the transition
 * progress (0 = `from` fully shown, 1 = `to` fully shown).
 *
 * Values approximate `UINavigationController`'s system push/pop
 * (`android-app#19` §Navigation stacks): the incoming screen slides in from
 * the trailing edge, the outgoing parallaxes to −30 % under a scrim that
 * peaks at 0.1. Under reduced motion everything cross-fades instead.
 */
@Immutable
data class NavLayerTransforms(
    /** The above layer's horizontal translation, px. Positive = toward the trailing edge. */
    val aboveTx: Float,
    /** The below layer's horizontal translation, px. Negative = toward the leading edge. */
    val belowTx: Float,
    /** The above layer's alpha (below is always opaque). */
    val aboveAlpha: Float,
    /** The black scrim's alpha, drawn between below and above. */
    val scrimAlpha: Float,
)

/**
 * The layer transforms of a transition, from the geometry table:
 *
 * | Property | Full motion | Reduced motion |
 * |---|---|---|
 * | above `translationX` | `dir · W · (1 − s)` | `0` |
 * | below `translationX` | `−dir · 0.3 · W · s` | `0` |
 * | scrim alpha | `0.1 · s` | `0` (not placed) |
 * | above alpha | `1` | `s` |
 *
 * `dir` is `+1` for LTR, `−1` for RTL, and [progress] is clamped to [0, 1].
 */
fun navLayerTransforms(
    kind: NavKind,
    progress: Float,
    widthPx: Float,
    rtl: Boolean,
    reduceMotion: Boolean,
): NavLayerTransforms {
    val p = progress.coerceIn(0f, 1f)
    val dir = if (rtl) -1f else 1f
    val s = if (kind == NavKind.Push) p else 1f - p
    return if (reduceMotion) {
        NavLayerTransforms(aboveTx = 0f, belowTx = 0f, aboveAlpha = s, scrimAlpha = 0f)
    } else {
        NavLayerTransforms(
            aboveTx = dir * widthPx * (1f - s),
            belowTx = -dir * NavMotion.ParallaxFraction * widthPx * s,
            aboveAlpha = 1f,
            scrimAlpha = NavMotion.ScrimMaxAlpha * s,
        )
    }
}

/**
 * Where the host's bottom-chrome slot sits during a transition
 * (`show(e) = !e.route.hidesBottomChrome`, iOS `hidesBottomBarWhenPushed`):
 *
 * - [Fixed] — both below and above show it: it stays put above everything;
 * - [RidesBelow] — only below shows it (push to / pop from a session): it
 *   travels with the outgoing or revealed screen, under the scrim and covered
 *   by the incoming screen, as the UIKit tab bar does;
 * - [RidesAbove] — only above shows it: it travels with the incoming screen;
 * - [Hidden] — neither shows it: not placed.
 */
enum class ChromeLayer { Hidden, Fixed, RidesBelow, RidesAbove }

/** The chrome placement for the show flags of the below and above layers. */
fun chromePlacement(showBelow: Boolean, showAbove: Boolean): ChromeLayer = when {
    showBelow && showAbove -> ChromeLayer.Fixed
    showBelow -> ChromeLayer.RidesBelow
    showAbove -> ChromeLayer.RidesAbove
    else -> ChromeLayer.Hidden
}
