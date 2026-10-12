package sh.roboco.android.design.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.Motion
import sh.roboco.android.design.RobocoTheme

/**
 * The scroll-edge effect's tunables (ticket 09): the band geometry, the
 * gradient's top alpha, the progressive blur radius, and the composable
 * conveniences over them.
 */
object ScrollEdgeEffectDefaults {

    /** The UIKit navigation-bar row height. */
    val BarHeight: Dp = 44.dp

    /** The soft extension of the effect band below the bar. */
    val Extension: Dp = 16.dp

    /**
     * The progressive blur radius. iOS's radius is system-internal, so this
     * is Haze 2.0.1's own default, tuned once in the gallery against
     * `1-off.png` (the ticket's measured contrast targets), then frozen.
     */
    val BlurRadius: Dp = 20.dp

    /** The background gradient's alpha at the very top of the band. */
    const val TopScrimAlpha: Float = 0.85f

    /** The engaged fade duration = the glass dematerialize time. */
    const val EngageMillis: Int = Motion.GlassOutMs

    /**
     * `statusBar + [BarHeight] + [Extension]` — pure. `statusBarTop` has no
     * default so hosts must read the inset themselves (or use the
     * parameterless composable [height] below).
     */
    fun height(statusBarTop: Dp, barHeight: Dp = BarHeight): Dp =
        statusBarTop + barHeight + Extension

    /**
     * The same height, reading `WindowInsets.statusBars` (the bar row hangs
     * directly under the status bar, as on iOS).
     *
     * Deliberately parameterless: a `@Composable fun height(barHeight: Dp =
     * BarHeight)` overload would make `height(24.dp)` resolve to it (Kotlin
     * prefers the candidate with fewer unfilled defaults) and silently
     * compute statusBar + 24 + 16. Hosts with a custom bar height pass the
     * pure [height] an explicit `barHeight` — or their own value.
     */
    @Composable
    fun height(): Dp {
        val density = LocalDensity.current
        val top = WindowInsets.statusBars.getTop(density)
        return height(with(density) { top.toDp() })
    }

    /**
     * = 07's `Glass.blurAvailable`: the composition's blur tier (API 33+,
     * hardware accelerated, no kill switch). The effect branches on this
     * instead of re-deriving the tier.
     */
    @Composable
    fun blurSupported(): Boolean = Glass.blurAvailable
}

/** Pure: the two-stop scrim brush (alpha already multiplied by [engagedAlpha]). */
internal fun scrollEdgeScrim(color: Color, engagedAlpha: Float): Brush =
    Brush.verticalGradient(
        0f to color.copy(alpha = ScrollEdgeEffectDefaults.TopScrimAlpha * engagedAlpha),
        1f to color.copy(alpha = 0f),
    )

/** Pure: which layers are composed for a given state. */
internal enum class ScrollEdgeLayers { None, ScrimOnly, BlurAndScrim }

/**
 * Pure: `None` once the engaged fade reaches 0 (nothing draws, no Haze node
 * is composed); `BlurAndScrim` only when blur is wanted *and* a screen
 * [HazeState] exists; `ScrimOnly` is the gradient fallback in every other
 * case (API 31–32, the kill switch, no state).
 */
internal fun scrollEdgeLayers(engagedAlpha: Float, blur: Boolean, hasHazeState: Boolean): ScrollEdgeLayers = when {
    engagedAlpha <= 0f -> ScrollEdgeLayers.None
    blur && hasHazeState -> ScrollEdgeLayers.BlurAndScrim
    else -> ScrollEdgeLayers.ScrimOnly
}

/**
 * The scroll-edge effect (ticket 09): the Android port of iOS 26's system
 * scroll-edge effect. Upstream sets the transcript's to `.soft`
 * (`ios:Session/SessionViewController.swift:76-78`) and every tab navigation
 * stack keeps the default effect by not setting a bar appearance object
 * (`ios:Shell/MainTabController.swift:125-134`), so rows scrolling up under
 * the status bar, the nav-bar title and its glass buttons are **blurred
 * progressively** (strongest at the top) and **faded into the page
 * background**, and the title never sits over crisp text.
 *
 * Android has no system equivalent, so this is a pass-through overlay with
 * two layers, sitting between a screen's scrolling content (below it) and
 * that screen's header chrome (above it):
 *
 *  1. a Haze progressive blur of the content behind it (API 33+ only);
 *  2. a `Palette.background` gradient, 0.85 alpha at the top → 0 at the
 *     bottom — the same fade-into-the-page-colour idea as ticket 10's
 *     `EdgeFadeOverlay`, which the iOS comment describes as "a gradient
 *     overlay (not a mask on the scroll view) so scrolling never renders
 *     offscreen" (`ios:Session/SessionViewController.swift:133-135`).
 *
 * **Geometry:** the host's full width and [height] (default
 * [ScrollEdgeEffectDefaults.height] = status bar + 44 dp bar + 16 dp soft
 * extension; the large-title state passes the bar's current bottom + 16 dp —
 * ticket 25). Placed at `Alignment.TopStart`, y = 0: it covers the status bar.
 *
 * **Hit testing and semantics:** none. A `Box`/`Spacer` without input
 * modifiers is not a hit target, so taps, drags and long-presses reach the
 * scrolling content underneath (a sibling *below* this overlay in the host's
 * `Box`). It adds no accessibility nodes — the effect is decorative.
 *
 * **Engagement:** iOS's effect engages internally ("only after an on-screen
 * scroll", `ios:Transcript/TranscriptListView.swift:281-288`); here the host
 * passes [engaged], derived from its scroll state — `contentUnderTopEdge` on
 * a `LazyListState`/`ScrollState`, or the transcript viewport's "content
 * above the top inset". The fade is alpha 0 ↔ 1 over 250 ms with UIKit's
 * easeInOut (the glass dematerialize timing, `ios:Design/Glass.swift:35`),
 * interruptible from the current value, snapping under Reduce Motion. At
 * alpha 0 nothing is composed at all — no Haze node, no draw.
 *
 * **Fallbacks:** API 31–32 (Haze's pre-draw invalidation path is too costly
 * under a fast list), a `null` [hazeState], and the glass kill switch
 * ([ScrollEdgeEffectDefaults.blurSupported]) all render layer 2 only, with
 * the same geometry and the same engagement behaviour.
 *
 * # Host wiring
 *
 * One [HazeState] per screen (ticket 07's [ProvideHazeState]); the scrolling
 * content is the only `hazeSource`; the effect and the header chrome are
 * siblings drawn after it — **never inside the source box** (a blur that
 * samples its own output feeds back, and the title would blur itself):
 *
 * ```
 * val hazeState = rememberHazeState()
 * ProvideHazeState(state = hazeState) {                // ticket 07
 *     Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
 *         // 1. haze SOURCE: everything that scrolls (and the wallpaper hero
 *         //    on the front page — ticket 21)
 *         Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
 *             LazyColumn(state = listState, contentPadding = PaddingValues(top = headerBottom)) { … }
 *         }
 *         // 2. the effect, between content and chrome
 *         ScrollEdgeEffect(
 *             height = ScrollEdgeEffectDefaults.height(),
 *             engaged = listState.contentUnderTopEdge,
 *         )
 *         // 3. chrome on top: title + glass buttons (tickets 25 / 52 / 08)
 *         HeaderBar(…)
 *     }
 * }
 * ```
 *
 * - The header glass buttons (ticket 08) use the same `hazeState` as their
 *   glass input; Haze supports several effects per state.
 * - On tablet, each column's screen has its own `hazeState` and effect.
 * - The effect lives inside each screen, so it slides with the screen during
 *   push transitions (ticket 24); ticket 75 owns "no crisp text under the
 *   title mid-push" by holding the transcript.
 *
 * Hosts: the Sessions root, folder screens, Search and Settings through
 * ticket 25's `LargeTitleScaffold`; the session header (52) over the
 * transcript; the folder browser (110); the Transcript Lab (135).
 *
 * Only this function's body recomposes during the engaged fade (it has no
 * children of its own), and the Haze style is rebuilt in an isolated private
 * composable — hosts' content never recomposes with it.
 */
@Composable
fun ScrollEdgeEffect(
    height: Dp,
    modifier: Modifier = Modifier,
    engaged: Boolean = true,
    hazeState: HazeState? = LocalHazeState.current,
    color: Color = RobocoTheme.palette.background,
    blur: Boolean = ScrollEdgeEffectDefaults.blurSupported(),
) {
    val reduce = LocalReduceMotion.current
    val a by animateFloatAsState(
        targetValue = if (engaged) 1f else 0f,
        // UIKit easeInOut over the glass dematerialize duration; the alpha
        // multiplies both layers (the Haze style's alpha and the scrim's).
        // Reduce Motion snaps: the effect itself is static either way.
        animationSpec = if (reduce) snap() else Motion.glassOut(),
        label = "scrollEdge",
    )
    when (scrollEdgeLayers(a, blur, hazeState != null)) {
        ScrollEdgeLayers.None ->
            // The fade-out finished: an empty spacer keeps the layout slot
            // without drawing anything or composing a Haze node.
            Spacer(modifier.fillMaxWidth().height(height))

        ScrollEdgeLayers.ScrimOnly -> Box(modifier.fillMaxWidth().height(height)) {
            ScrimLayer(Modifier.matchParentSize(), color, a)
        }

        ScrollEdgeLayers.BlurAndScrim -> Box(modifier.fillMaxWidth().height(height)) {
            ScrollEdgeBlurLayer(
                Modifier.matchParentSize(),
                height, color, a,
                requireNotNull(hazeState) { "BlurAndScrim requires a HazeState" },
            )
            ScrimLayer(Modifier.matchParentSize(), color, a)
        }
    }
}

/**
 * The background gradient, always layer 2 (top): a two-stop vertical
 * `Brush` of [color], alpha 0.85 × [engagedAlpha] at the top → 0 at the
 * bottom, covering [base]. The brush is built in `drawWithCache`, keyed
 * on [color] and [engagedAlpha], so it is allocated only when they change —
 * not on every draw of every scrolled frame.
 */
@Composable
private fun ScrimLayer(base: Modifier, color: Color, engagedAlpha: Float) {
    Box(
        base.drawWithCache {
            val scrim = scrollEdgeScrim(color, engagedAlpha)
            onDrawBehind { drawRect(scrim) }
        },
    )
}

/**
 * Layer 1 (bottom): the progressive blur of the content behind the band.
 *
 * The style is an immutable Haze program, so the animated [engagedAlpha]
 * rebuilds it every frame of the fade — in this isolated composable only,
 * never the host's content. The progressive gradient runs intensity 1 at
 * y = 0 → 0 at y = [height] with `Motion.EaseIn` (the exact curve of Haze's
 * own default; Compose's constant is not imported — the Motion tokens are
 * the single source of curves), which keeps the blur strong deep into the
 * band and falling off near its bottom, as the `1-off.png` measurements
 * show. Haze 2.0.1's defaults run the *other* way (0 → 1, strongest at the
 * bottom, `endY` = +∞), so all five arguments are passed explicitly.
 *
 * `HazeInput.Sources` (exact captured-source semantics, per Haze's 2.0
 * migration guide), never `HazeInput.Backdrop`: backdrop samples every
 * earlier pixel in the window and would pick up chrome drawn underneath,
 * such as a large title that has not collapsed yet. No fallback colour
 * effect — layer 2 is our fallback, not a Haze scrim.
 */
@Composable
private fun ScrollEdgeBlurLayer(base: Modifier, height: Dp, color: Color, engagedAlpha: Float, hazeState: HazeState) {
    val heightPx = with(LocalDensity.current) { height.toPx() }
    val style = remember(heightPx, color, engagedAlpha) {
        HazeBlurStyle {
            blurEnabled(true)
            blurRadius(ScrollEdgeEffectDefaults.BlurRadius)
            noiseFactor(0f)
            backgroundColor(color)
            colorEffects(emptyList())
            fallbackColorEffect(null)
            alpha(engagedAlpha)
            progressive(
                HazeProgressive.verticalGradient(
                    easing = Motion.EaseIn,
                    startY = 0f,
                    startIntensity = 1f,
                    endY = heightPx,
                    endIntensity = 0f,
                ),
            )
        }
    }
    Box(base.hazeBlur(HazeInput.Sources(hazeState), style))
}

// Host helpers for ScrollEdgeEffect's `engaged` input.

/** A lazy list has content under the top edge once it can scroll backward. */
val LazyListState.contentUnderTopEdge: Boolean get() = canScrollBackward

/** A scrollable column has content under the top edge once it has scrolled. */
val ScrollState.contentUnderTopEdge: Boolean get() = value > 0
