package sh.roboco.android.shell

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Android's stand-in for the iOS status-bar tap (android-ui-parity #26):
 * `UIScrollView.scrollsToTop` scrolls the key window's first scroll view to
 * the top; inner scrollers opt out (`ios:Transcript/RowView.swift:166`). A
 * tab root registers its handler and the tab bar calls it on a re-select at
 * the root; anything deeper in the tree never registers (same opt-out
 * rule), so the outer list moves.
 */
fun interface ScrollToTopHandler {
    /** Scrolls the registering root to its top; interruptible and cancellable. */
    suspend fun scrollToTop()
}

/**
 * The registry of the tab roots' scroll-to-top handlers, owned by
 * [MainTabState] so it survives tab switches and outlives every root
 * composition. Snapshot state, so [MainTabState.onTabTap] re-reads the map
 * when a root registers or unregisters.
 */
class TabScrollToTop {
    internal val handlers: SnapshotStateMap<TabId, ScrollToTopHandler> = mutableStateMapOf()
}

/** The tab whose root is currently composing; `null` outside [MainTabShell] (the tablet sidebar, the gallery). */
val LocalTabId = staticCompositionLocalOf<TabId?> { null }

/** The shell's handler registry; `null` outside [MainTabShell]. */
val LocalTabScrollToTop = staticCompositionLocalOf<TabScrollToTop?> { null }

/**
 * Registers [handler] as this tab root's scroll-to-top handler for as long
 * as the caller is composed. A no-op outside a tab (either local null).
 *
 * The registered wrapper always delegates to the **latest** [handler]
 * (`rememberUpdatedState`), so a lambda capturing changing state stays
 * fresh without re-registering.
 */
@Composable
fun RegisterScrollToTop(handler: ScrollToTopHandler) {
    val tab = LocalTabId.current ?: return
    val registry = LocalTabScrollToTop.current ?: return
    val latest by rememberUpdatedState(handler)
    DisposableEffect(tab, registry) {
        val wrapper = ScrollToTopHandler { latest.scrollToTop() }
        registry.handlers[tab] = wrapper
        onDispose {
            if (registry.handlers[tab] === wrapper) registry.handlers.remove(tab)
        }
    }
}

/**
 * The default handler: the root's list animated to item 0. Still animated
 * under Reduce Motion — iOS does not gate `scrollsToTop` on it.
 */
@Composable
fun RegisterScrollToTop(listState: LazyListState) = RegisterScrollToTop { listState.animateScrollToItem(0) }
