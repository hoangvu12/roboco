package sh.roboco.android.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import sh.roboco.android.design.Motion

/**
 * The window-level root swap (android-ui-parity #35): the port of
 * `showRoot(animated: true)`'s `UIView.transition(with: window, duration:
 * 0.35, options: [.transitionCrossDissolve, .allowAnimatedContent])`
 * (`SceneDelegate.swift:99-101`).
 *
 * This is a custom component, not `AnimatedContent`/`Crossfade`: those fade
 * **both** layers, so the background bleeds through at the mid-point
 * (~25 % at t = 0.5 — the `pr609:ZeronRoot.kt:55-57` trap). A
 * `CATransition` fade composites `old × (1 − e(t)) + new × e(t)`, which
 * this reproduces exactly:
 *
 * - the **incoming** root is drawn **below** at alpha 1;
 * - the **outgoing** root is drawn **on top**, alpha 1 → 0 along
 *   `Motion.rootSwap()` (350 ms easeInOut, `(0.42, 0, 0.58, 1)` — the
 *   default curve `UIView` uses when none is given);
 * - no pointer input reaches either layer while the fade runs (UIKit
 *   animations disable interaction unless `.allowUserInteraction` is
 *   passed, and it is not);
 * - the outgoing layer is `hideFromAccessibility()` from the first swap
 *   frame, so TalkBack never focuses the leaving root.
 *
 * **Interrupted swap:** a target change during a running fade snaps the
 * old fade to its end (the outgoing layer leaves) and starts a fresh
 * 350 ms fade from the currently shown root.
 *
 * [beforeSwap] runs in the same frame, before either layer changes: the
 * instant dismissal of every root presentation (`SceneDelegate.swift:92-94`'s
 * `old.dismiss(animated: false)`). `LaunchedEffect` runs after the
 * composition that saw the new target — that composition still shows the
 * old root, so `beforeSwap` really runs before any layer change.
 *
 * `key(k)` keeps each root's composition when it moves from "shown" to
 * "leaving": its `remember`/`rememberSaveable` state survives the fade and
 * is dropped only when it leaves the layer list, so the leaving frame does
 * not reset to its initial state mid-fade (a fresh composition per swap,
 * like iOS building a new controller each `showRoot`).
 */
@Composable
internal fun <K : Any> RootCrossfade(
    target: K,
    beforeSwap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (K) -> Unit,
) {
    var shown by remember { mutableStateOf(target) }
    var leaving by remember { mutableStateOf<K?>(null) }
    val fade = remember { Animatable(1f) }

    LaunchedEffect(target) {
        if (target == shown) return@LaunchedEffect
        if (leaving != null) {
            // Interrupted: finish the old fade at once, then start fresh.
            fade.snapTo(0f)
            leaving = null
        }
        beforeSwap()
        leaving = shown
        shown = target
        fade.snapTo(1f)
        fade.animateTo(0f, Motion.rootSwap())
        leaving = null
    }

    Box(modifier) {
        // Stable order: the shown root first, the leaving root after — its
        // zIndex(1f) puts it on top anyway, and the key() keeps its slot.
        val layers = listOfNotNull(shown, leaving)
        for (k in layers) key(k) {
            val isLeaving = k == leaving
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(if (isLeaving) 1f else 0f)
                    // Read fade.value only inside the layer: the fade then
                    // redraws, never recomposes the roots, each frame.
                    .graphicsLayer { alpha = if (isLeaving) fade.value else 1f }
                    .then(
                        if (isLeaving) {
                            Modifier.semantics { hideFromAccessibility() }
                        } else {
                            Modifier
                        },
                    ),
            ) { content(k) }
        }
        if (leaving != null) {
            // Input blocker above both layers (UIKit disables interaction
            // during the transition). The Initial pass consumes everything,
            // so neither root — nor anything under the window — sees a tap.
            Box(
                Modifier
                    .matchParentSize()
                    .zIndex(2f)
                    .testTag("root-swap-blocker")
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                    .changes
                                    .forEach { it.consume() }
                            }
                        }
                    },
            )
        }
    }
}
