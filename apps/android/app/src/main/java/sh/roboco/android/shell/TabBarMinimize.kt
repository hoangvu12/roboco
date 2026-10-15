package sh.roboco.android.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.Motion
import kotlin.math.sign

/**
 * The pure 24 dp scroll accumulator (android-ui-parity #28) — the port of
 * iOS 26's system `tabBarMinimizeBehavior = .onScrollDown` gesture rule as
 * a plain class, so the threshold semantics are pinned by
 * `MinimizeTrackerTest` without Compose.
 *
 * Compose nested-scroll deltas are pointer deltas: dragging the finger
 * **up** gives a negative `y` and scrolls the list toward its end. So
 * "content scrolled down" travel is `-consumedY`:
 *
 *  - "more than 24 dp down" is `travel > thresholdPx` (strict);
 *  - "24 dp up" is `travel <= -thresholdPx` (the scope's ≥);
 *  - a direction **reversal** resets the accumulator to 0 before adding the
 *    new delta, so 20 down + 5 up + 20 down never triggers;
 *  - leftover upward scroll (`leftoverY > 0.5f`) means the list is at its
 *    top and the pull could not be consumed: reset the accumulator, and
 *    expand if currently minimized;
 *  - a minimize or expand resets the accumulator (the callers do this via
 *    [TabBarMinimizeState], which resets after every target change).
 *
 * [thresholdPx] is a `var` because density is only known in composition
 * ([rememberTabBarMinimizeState] sets it in a `SideEffect`).
 */
class MinimizeTracker(var thresholdPx: Float) {

    /** > 0: content moved toward the end (scrolled down). */
    private var travel = 0f

    /**
     * One nested-scroll event. Returns the new target (`true` = minimize,
     * `false` = expand), or null for no change.
     */
    fun onScroll(consumedY: Float, leftoverY: Float, minimized: Boolean): Boolean? {
        if (leftoverY > 0.5f) {
            // The pull past the top the list could not consume.
            travel = 0f
            return if (minimized) false else null
        }
        if (consumedY == 0f) return null
        val down = -consumedY
        if (travel != 0f && sign(down) != sign(travel)) travel = 0f
        travel += down
        return when {
            !minimized && travel > thresholdPx -> {
                travel = 0f
                true
            }

            minimized && travel <= -thresholdPx -> {
                travel = 0f
                false
            }

            else -> null
        }
    }

    /** Zeroes the accumulator (after a minimize/expand, or by hand in tests). */
    fun reset() {
        travel = 0f
    }
}

/**
 * The minimize state (android-ui-parity #28): the target
 * ([minimized], which flips at the **start** of a morph, like the iOS
 * `UITraitTabAccessoryEnvironment` trait) and the morph position
 * ([progress], 0 expanded ↔ 1 minimized, unclamped through the spring's
 * 0.63 % overshoot).
 *
 * Everything runs on the main thread: the tracker from the nested-scroll
 * connection, [minimize]/[expand] from UI code, the animation in [scope].
 *
 * - `minimize()`/`expand(animate = false)` (ticket 30's
 *   `prepareAccessoryForReveal`) are the programmatic entry points; a call
 *   that is already at its target **and** at rest is a no-op.
 * - Interruptions follow UIKit's render-server rule: `animateTo` always
 *   continues from the current value *and velocity* — never a `snapTo`
 *   back to the start first.
 * - Under Reduce Motion the behaviour (the target flip) stays and only the
 *   motion goes: `progress.snapTo(target)`.
 */
@Stable
class TabBarMinimizeState internal constructor(private val scope: CoroutineScope) {

    /** The target: true = the chrome should sit minimized. */
    var minimized: Boolean by mutableStateOf(false)
        private set

    /**
     * The morph position: 0 = expanded, 1 = minimized. Read it in
     * measure/placement/draw/`graphicsLayer` lambdas only — reading it in
     * a composable body would recompose the shell every frame.
     */
    val progress: Animatable<Float, AnimationVector1D> = Animatable(0f)

    /** The scroll accumulator; the threshold is set once density is known. */
    internal val tracker = MinimizeTracker(thresholdPx = 0f)

    /** Reduce Motion, as seen when a morph starts. */
    internal var reduceMotion: Boolean = false

    /** Minimizes the chrome (animated unless [animate] is false). */
    fun minimize(animate: Boolean = true) = set(true, animate)

    /**
     * Restores the expanded chrome. Ticket 30's reveal path passes
     * `animate = false` so the pop-from-Session reveal lays the chrome out
     * at its expanded final positions in one frame.
     */
    fun expand(animate: Boolean = true) = set(false, animate)

    private fun set(target: Boolean, animate: Boolean) {
        // A call that is already at its target and at rest does nothing:
        // not even a redundant relaunch of a spring that would be a no-op.
        val atRest = !progress.isRunning && progress.value == if (target) 1f else 0f
        if (minimized == target && atRest) return
        minimized = target
        tracker.reset()
        scope.launch {
            if (animate && !reduceMotion) {
                progress.animateTo(if (target) 1f else 0f, Motion.TabBarMinimize.spec())
            } else {
                progress.snapTo(if (target) 1f else 0f)
            }
        }
    }

    /** One nested-scroll event from [MinimizeOnScrollConnection]. */
    internal fun onScroll(consumedY: Float, leftoverY: Float) {
        val target = tracker.onScroll(consumedY, leftoverY, minimized) ?: return
        set(target, animate = true)
    }
}

/**
 * [TabBarMinimizeState] in composition. Plain `remember`, not
 * `rememberSaveable`: after a configuration change the bar comes back
 * expanded, which iOS does too after relaunch.
 */
@Composable
fun rememberTabBarMinimizeState(): TabBarMinimizeState {
    val scope = rememberCoroutineScope()
    val state = remember { TabBarMinimizeState(scope) }
    val density = LocalDensity.current
    val reduce = LocalReduceMotion.current
    SideEffect {
        with(density) { state.tracker.thresholdPx = TabChromeMetrics.ThresholdDp.dp.toPx() }
        state.reduceMotion = reduce
    }
    return state
}

/**
 * The shell's [TabBarMinimizeState], for neighbouring tickets: ticket 30
 * reads it for the accessory reveal (`expand(animate = false)`) and the
 * chrome-top publication. Null outside [MainTabShell].
 */
val LocalTabBarMinimize = staticCompositionLocalOf<TabBarMinimizeState?> { null }

/**
 * The scroll detector (android-ui-parity #28): a [NestedScrollConnection]
 * that watches the tab content's scroll and drives
 * [TabBarMinimizeState], **never consuming** — it always returns
 * `Offset.Zero` / `Velocity.Zero`, so lists, pull-to-refresh (48) and
 * overscroll behave exactly as without it.
 *
 * `onPostScroll` handles both drag and fling `source`s (iOS minimizes
 * during deceleration too) and accumulates `consumed.y` — mid-list,
 * `available.y` is always 0, so it is used only as the "at the top"
 * signal. `onPostFling` expands when a fling ends with leftover upward
 * velocity (the list bounced at its top).
 *
 * [enabled] is read per event, never captured as a value, so the shell can
 * gate on the *current* top route (a pushed Session's transcript must not
 * drive the bar).
 */
class MinimizeOnScrollConnection(
    private val state: TabBarMinimizeState,
    private val enabled: () -> Boolean,
) : NestedScrollConnection {

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (enabled()) state.onScroll(consumed.y, available.y)
        return Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (enabled() && available.y > 0f && state.minimized) state.expand()
        return Velocity.Zero
    }
}

/**
 * Installs [MinimizeOnScrollConnection] around the modified subtree —
 * wrap the tab's whole `NavStack` host, so the root list and pushed folder
 * pages both drive it. A plain composable modifier factory (no
 * `composed {}`): the connection is remembered once per [state] and
 * [enabled] is kept current through [rememberUpdatedState].
 */
@Composable
fun Modifier.minimizeOnScroll(state: TabBarMinimizeState, enabled: () -> Boolean): Modifier {
    val enabledState = rememberUpdatedState(enabled)
    return this.nestedScroll(
        remember(state) { MinimizeOnScrollConnection(state) { enabledState.value() } },
    )
}
