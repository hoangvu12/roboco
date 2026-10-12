package sh.roboco.android.design.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState

/**
 * The screen's [HazeState], or `null` outside [ProvideHazeState]. A
 * `GlassSurface` blurs only when a state exists **and** the composition tier
 * is `Blur` ([LocalGlassTier]); otherwise it draws the fallback fill.
 */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * The per-screen Haze convention (ticket 07): **one** [HazeState] per screen,
 * the scrolling content is the *only* `hazeSource`, and chrome samples it
 * through [GlassSurface]. Screen tickets (09, 25, 46, 52, 74, 75) follow
 * this pattern:
 *
 * ```kotlin
 * ProvideHazeState { // one HazeState per screen
 *     Box(Modifier.fillMaxSize()) {
 *         // 1. scrolling content (list / transcript / wallpaper): the ONLY sources
 *         Box(Modifier.fillMaxSize().hazeSource(LocalHazeState.current!!)) { list() }
 *         // 2. chrome: never inside a hazeSource; samples the source via GlassSurface
 *         GlassSurface(Modifier.align(Alignment.BottomCenter)) { … }
 *     }
 * }
 * ```
 *
 * Rules:
 *  - Never put a [GlassSurface] or other chrome inside a `hazeSource`
 *    subtree of the same state: the glass would sample itself and feed back.
 *  - Glass never samples glass. Overlapping chrome surfaces each blur only
 *    the content source.
 *
 * Popups and dialogs are separate windows; whether `HazeInput.Sources` from
 * the screen's state renders inside one is unverified. Tickets 15/17 must
 * check, and provide `LocalHazeState provides null` (fallback fill) inside
 * popups if it does not.
 */
@Composable
fun ProvideHazeState(state: HazeState = rememberHazeState(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHazeState provides state) { content() }
}
