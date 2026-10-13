package sh.roboco.android.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The instant-dismissal registry (android-ui-parity #35): everything a root
 * swap has to tear down in the same frame it starts —
 * `old.dismiss(animated: false)` on the whole presented chain
 * (`SceneDelegate.swift:92-94`). All entries run synchronously inside one
 * [dismissAllInstantly] call, so their relative order is not observable.
 *
 * [RootHosts] registers the foundation hosts here (context-menu lift,
 * alerts, action sheets, page sheets); presenters that live in their own
 * window register themselves through [RegisterRootPresentation].
 * Toasts are **not** registered: on iOS the toast is a window subview and
 * survives the swap (`ios:Design/Toast.swift`).
 */
@Stable
class RootPresentations {

    // Main thread only; dismissAllInstantly runs on the main thread too.
    private val entries = ArrayList<() -> Unit>()

    /** Registers [dismissInstantly]; the returned handle unregisters it. */
    fun register(dismissInstantly: () -> Unit): () -> Unit {
        entries += dismissInstantly
        return { entries -= dismissInstantly }
    }

    /**
     * Latest registered first. All run in one frame on the calling thread,
     * so the order is not visible to the user.
     */
    fun dismissAllInstantly() {
        entries.toList().asReversed().forEach { it() }
    }
}

/** The root-swap dismissal registry of the composition. */
val LocalRootPresentations = staticCompositionLocalOf { RootPresentations() }

/**
 * Registers a presenter with the root-swap registry
 * (android-ui-parity #35). For presenters that live in their **own**
 * window (a `Dialog`/`Popup`) — none is planned in this set, but the
 * gallery's fake sheet and ticket 125's in-window image viewer
 * (`state::dismissInstantly`, `active = state.isShowing`) use the same
 * API. Ticket 104's tablet Settings card is an in-window `SheetHost`
 * entry and is covered by the host's own registration.
 *
 * While [active] is false nothing is registered; the newest
 * [dismissInstantly] lambda is used (rememberUpdatedState), so the entry
 * may capture a stale scope safely.
 */
@Composable
fun RegisterRootPresentation(active: Boolean, dismissInstantly: () -> Unit) {
    val registry = LocalRootPresentations.current
    val current by rememberUpdatedState(dismissInstantly)
    DisposableEffect(registry, active) {
        if (!active) {
            onDispose { }
        } else {
            val unregister = registry.register { current() }
            onDispose { unregister() }
        }
    }
}
