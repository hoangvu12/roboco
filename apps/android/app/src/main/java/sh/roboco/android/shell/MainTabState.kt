package sh.roboco.android.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.roboco.android.design.Feedback
import sh.roboco.android.design.Haptic

/**
 * The phone shell's tab state (android-ui-parity #26): the selected tab,
 * the "return to where you came from" tab, one lazily created and then
 * retained [NavStack] per tab, and the scroll-to-top registry — the
 * `UITabBarController` model of `ios:Shell/MainTabController.swift`, as a
 * plain state holder that lives outside composition so every back stack
 * survives tab switches.
 *
 * Each tab's [stack] is created the first time it is needed — like the
 * UIKit provider closures, which build a tab's controller lazily — and
 * kept for the life of this state, so switching back restores both the
 * stack and the saveable state of its screens (the shell's
 * `SaveableStateHolder` keeps the scroll positions).
 *
 * [NavStack] has no `Saver` (ticket 24 drops process-death restoration, as
 * iOS has none), so after process death the stacks restart at their roots;
 * the configuration changes that matter do not recreate the activity
 * (ticket 35's `configChanges`), so this instance and its stacks survive
 * rotation and fold/unfold.
 */
@Stable
class MainTabState internal constructor(
    initial: TabId,
    initialPrevious: TabId = TabId.Sessions,
) {
    /** The selected tab; initial selection is Sessions (`MainTabController.swift:35`). */
    var selected: TabId by mutableStateOf(initial)
        private set

    /**
     * The last selected tab that is not Search — Search's "return to where
     * you came from" (system back at the Search root, ticket 29's close).
     */
    var previousTab: TabId by mutableStateOf(initialPrevious)
        private set

    /** The tab roots' scroll-to-top handlers (the status-bar-tap port). */
    val scrollToTop: TabScrollToTop = TabScrollToTop()

    /**
     * Ticket 29: invoked from [select] on every real selection change
     * (taps, `onTabTap`, the router, deep links, `backFromRoot` — the one
     * place every route goes through) with the old and new tab. The shell
     * wires `SearchTabState::onTabSelected` here so entering Search
     * activates the field; null by default (tests wire it by hand).
     */
    internal var onSelected: ((old: TabId, new: TabId) -> Unit)? = null

    private val stacks = mutableMapOf<TabId, NavStack>()

    /**
     * The tab's stack, created on first use (`TabRootRoute(tab)` as its
     * root) and kept for the life of this state — the lazy UIKit provider
     * closures. A tab never selected is never composed, because only the
     * selected tab's stack is ever asked for.
     */
    fun stack(tab: TabId): NavStack = stacks.getOrPut(tab) { NavStack(TabRootRoute(tab)) }

    /**
     * Programmatic selection (router 32, deep links 36, launch extras):
     * `showSettings()` → `selectedTab = more` (`MainTabController.swift:150-152`).
     * No haptic — user taps go through [onTabTap].
     */
    fun select(tab: TabId) {
        if (tab == selected) return
        val old = selected
        if (old != TabId.Search) previousTab = old
        selected = tab
        onSelected?.invoke(old, tab)
    }

    /**
     * A user tap on a bar item (the `selectable` click):
     *  - unselected tab → one `Haptic.Select` (the system tab bar's
     *    selection feedback; see the ticket's Comments), then [select];
     *  - selected tab whose stack can pop → `popToRoot(animated = true)`
     *    (the system re-select behaviour), no haptic;
     *  - selected tab at its root → the root's scroll-to-top handler (the
     *    status-bar-tap adaptation), no haptic; nothing when no handler.
     */
    fun onTabTap(tab: TabId, feedback: Feedback, scope: CoroutineScope) {
        if (tab != selected) {
            feedback.haptic(Haptic.Select)
            select(tab)
        } else if (stack(tab).canPop) {
            stack(tab).popToRoot(animated = true)
        } else {
            scrollToTop.handlers[tab]?.let { handler -> scope.launch { handler.scrollToTop() } }
        }
    }

    /**
     * System back at a tab root (Android convention; iOS has no back
     * gesture at a tab root): Settings → Sessions, Search → [previousTab],
     * Sessions → not handled (the activity's default). Returns true when
     * handled. With `canPop`, ticket 24's host handles back instead and
     * this is never called.
     */
    fun backFromRoot(): Boolean = when (selected) {
        TabId.Settings -> {
            select(TabId.Sessions)
            true
        }

        TabId.Search -> {
            select(previousTab)
            true
        }

        TabId.Sessions -> false
    }

    companion object {
        /**
         * Saves `selected` + `previousTab` identifiers (the stable strings —
         * never an ordinal, a reorder would scramble saved state). The
         * stacks are not saved (24 drops process-death restoration); unknown
         * identifiers restore as Sessions.
         */
        fun saver(): Saver<MainTabState, List<String>> = Saver(
            save = { state -> listOf(state.selected.identifier, state.previousTab.identifier) },
            restore = { value ->
                @Suppress("UNCHECKED_CAST")
                val ids = value as List<String>
                MainTabState(
                    initial = TabId.entries.firstOrNull { it.identifier == ids.getOrNull(0) } ?: TabId.Sessions,
                    initialPrevious = TabId.entries.firstOrNull { it.identifier == ids.getOrNull(1) } ?: TabId.Sessions,
                )
            },
        )
    }
}

/**
 * [MainTabState] in composition: the selection survives activity save
 * (configuration changes do not recreate the activity, ticket 35, but the
 * system may still save and restore), with the stacks restarting at their
 * roots after process death.
 */
@Composable
fun rememberMainTabState(initial: TabId = TabId.Sessions): MainTabState =
    rememberSaveable(saver = MainTabState.saver()) { MainTabState(initial) }
