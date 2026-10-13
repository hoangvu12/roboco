package sh.roboco.android.transcript

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf

/**
 * The live horizontal offsets of one realized row's scrollers (ticket 116).
 * One registry per [RealizedRow]; [RowScroller] registers its state in a
 * `SideEffect` and unregisters it on dispose, so the map holds exactly the
 * scrollers that are composed. Main thread only.
 *
 * `offsetDp` reads snapshot state (the map and `ScrollState.value`), so
 * reading it inside a snapshot observer — a `snapshotFlow`, a semantics
 * provider or a draw — re-runs when the scroller scrolls: that is how 126
 * hit-tests links where they are painted now and 129 places its link
 * nodes, without recomposing anything.
 */
@Stable
class ScrollerRegistry {

    private class Entry(val state: ScrollState, val density: Float)

    private val entries = mutableStateMapOf<Int, Entry>()

    /**
     * Publishes scroller [index]'s [state] at the current [density] (px per
     * dp). A re-registration with the same state and density writes
     * nothing; a changed state or density replaces the entry. Called after
     * a successful composition, never during one.
     */
    internal fun register(index: Int, state: ScrollState, density: Float) {
        val e = entries[index]
        if (e == null || e.state !== state || e.density != density) entries[index] = Entry(state, density)
    }

    /**
     * Forgets scroller [index] — but only if [state] is still the registered
     * one, so the dispose of an old effect and the `SideEffect` of the new
     * one may run in either order when the scroller's identity moves.
     */
    internal fun unregister(index: Int, state: ScrollState) {
        if (entries[index]?.state === state) entries.remove(index)
    }

    /** Scroller [index]'s current content offset in dp; 0 while it is not composed. */
    fun offsetDp(index: Int): Float = entries[index]?.let { it.state.value / it.density } ?: 0f
}
