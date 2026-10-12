package sh.roboco.android.design.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsPropertyKey
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.Motion

/**
 * Test-only: the surface's materialization progress `p` (0 = dematerialized,
 * 1 = materialized), exposed for UI tests. Set on an invisible child of
 * [GlassSurface]; production code never reads it. UI tests read it through
 * the semantics tree, including mid-animation.
 */
val GlassProgressKey = SemanticsPropertyKey<Float>("GlassProgress")

/**
 * Test-only: the applied graphics-layer scale of an `interactive`
 * [GlassSurface] (1 = rest, [Glass.PressScale] = fully pressed). Production
 * code never reads it.
 */
val GlassPressScaleKey = SemanticsPropertyKey<Float>("GlassPressScale")

/**
 * The shared materialize/dematerialize animation, the port of iOS
 * `Glass.setVisible` (`ios:Design/Glass.swift:28-36`): blur, fill and content
 * alpha all animate together over 350 ms in / 250 ms out with UIKit's default
 * easeInOut curve.
 *
 * - Interruptible: [Animatable.animateTo] continues from the current value
 *   with the *full* duration of the new direction — `.beginFromCurrentState`.
 *   It never snaps the start value first.
 * - [animated] `false` or Reduce Motion ([LocalReduceMotion]) snaps
 *   (`Glass.swift:34`).
 * - [startHidden] starts at 0 so the first appearance *grows in*: the toast
 *   (14) and panels that appear after the screen's first render (82, 90 —
 *   `SessionViewController.swift:126-130`).
 *
 * UIKit sets the content alpha to its target in the same animation block, so
 * hit testing follows the *model* value, not the in-flight presentation:
 * callers decide interactivity from `visible`, not from `p`.
 */
@Composable
fun rememberGlassProgress(
    visible: Boolean,
    animated: Boolean = true,
    startHidden: Boolean = false,
): State<Float> {
    val reduce = LocalReduceMotion.current
    val anim = remember { Animatable(if (visible && !startHidden) 1f else 0f) }
    LaunchedEffect(visible, animated, reduce) {
        val target = if (visible) 1f else 0f
        if (!animated || reduce) {
            anim.snapTo(target)
        } else {
            anim.animateTo(target, if (visible) Motion.glassIn() else Motion.glassOut())
        }
    }
    return anim.asState()
}
