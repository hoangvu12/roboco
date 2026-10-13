package sh.roboco.android.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Where a screen stands in its stack's push/pop transition
 * (android-ui-parity #30), for screens that cross-fade their own bottom
 * chrome against the tab chrome — ticket 85's session composer stack reads
 * [presence] inside its `graphicsLayer` (the iOS "fades opposite to the
 * tab bar", so the two glasses never overlap).
 *
 * All getters are snapshot-backed: read them in draw/layout lambdas or
 * `snapshotFlow`, and the per-frame progress never recomposes the screen.
 * Outside any stack (previews, the gallery) [LocalTransitionProgress]
 * provides [Settled].
 */
@Stable
interface TransitionProgress {
    /**
     * 1 = this screen is the settled top; 0 = fully covered or gone.
     * Idle: `1` for `stack.top`, else `0`. During a transition:
     * `t.to → p`, `t.from → 1 − p`, any other entry `0`, clamped to 0..1.
     */
    val presence: Float

    /** 24's [NavStack.bottomChromeVisibility] of this screen's stack. */
    val chromeVisibility: Float

    /** True while a predictive back gesture drives the transition. */
    val isInteractive: Boolean

    companion object {
        /** Outside any stack: fully present, no chrome visibility, no gesture. */
        val Settled: TransitionProgress = object : TransitionProgress {
            override val presence get() = 1f
            override val chromeVisibility get() = 0f
            override val isInteractive get() = false
        }
    }
}

/** The ambient [TransitionProgress]; [TransitionProgress.Settled] outside a stack. */
val LocalTransitionProgress = staticCompositionLocalOf { TransitionProgress.Settled }

/**
 * [TransitionProgress] of one [entry] in [stack]: the getters compute the
 * Spec table from `stack.transition` / `stack.top` /
 * `stack.bottomChromeVisibility`, live. Remembered per (stack, entry), so
 * a recomposed screen keeps its holder.
 */
@Stable
internal class EntryTransitionProgress(
    private val stack: NavStack,
    private val entry: NavEntry,
) : TransitionProgress {
    override val presence: Float
        get() {
            val t = stack.transition
            return when {
                t == null -> if (stack.top === entry) 1f else 0f
                entry === t.to -> t.progress.coerceIn(0f, 1f)
                entry === t.from -> (1f - t.progress).coerceIn(0f, 1f)
                else -> 0f
            }
        }

    override val chromeVisibility: Float
        get() = stack.bottomChromeVisibility

    override val isInteractive: Boolean
        get() = stack.transition?.isInteractive == true
}

/**
 * Provides [LocalTransitionProgress] for one entry's screen. The phone
 * shell wraps every `NavStackHost` entry with it; the tablet hosts
 * (105/106) call the same function, so a session screen works in both
 * shells.
 */
@Composable
fun ProvideTransitionProgress(stack: NavStack, entry: NavEntry, content: @Composable () -> Unit) {
    val progress = remember(stack, entry) { EntryTransitionProgress(stack, entry) }
    CompositionLocalProvider(LocalTransitionProgress provides progress) { content() }
}
