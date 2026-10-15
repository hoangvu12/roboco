package sh.roboco.android.session

import sh.roboco.android.shell.AppRouter
import sh.roboco.android.transcript.TranscriptState

/**
 * Ticket 83's "endEdit(commit = null)" (`ios:Session/
 * CoreSessionSource.swift:172-185`) when a queued message is being
 * edited; a no-op otherwise. Main thread; must restore the stashed
 * user draft into the composer **synchronously** — the draft save that
 * follows reads it.
 */
fun interface QueuedEditCanceller {
    fun cancelIfEditing()
}

/**
 * Ticket 84's `saveDraft()` (`SessionViewController.swift:389-392`): the
 * stash while an edit is open, else the composer text. Main thread.
 */
fun interface DraftSaver {
    fun save()
}

/**
 * The navigation lifecycle of one Session screen (android-ui-parity #85)
 * — the port of `SessionViewController`'s appear/disappear pair
 * (`ios:Session/SessionViewController.swift:173-179, 280, 295-315`):
 *
 * - **reversible work only** while the screen is merely leaving
 *   ([leave]: cancel a queued edit, then save the draft). iOS runs this
 *   in `viewWillDisappear`, which also fires at the *start* of an
 *   interactive pop that may still be cancelled — detaching there is the
 *   upstream bug this ticket exists to avoid (the reply froze);
 * - **irreversible work** only after the pop has committed
 *   ([closeAfterPop] from 24's `onDidDisappear(removed = true)`): tell
 *   the router the session closed, then [release] — detach + mark seen
 *   (51's `controller.close()` on its serial IO lane) and the layout
 *   engine (61's `transcript.close()` on main);
 * - **re-attach** on every re-appearance after the first
 *   ([onWillAppear] with `hasAppeared`, iOS `viewWillAppear` `:178`) and
 *   `sessionDidAppear` on every appearance.
 *
 * A plain class, retained per [sh.roboco.android.shell.NavEntry] (ticket
 * 24's `retain`), so it survives a tab host's disposal and
 * configuration changes. Every method is **main thread only** and
 * idempotent; after [release] every method is a no-op. No Compose.
 *
 * @param chatId the session's scoped chat id
 * @param controller ticket 51's screen state holder: `reattach`, and
 *   `close()` = `setViewAttached(false)` then `markSeen` on its IO lane
 * @param transcript ticket 61's state, owned by 75's
 *   `SessionScreenState`; `close()` frees the frame relay and the Rust
 *   layout engine (main thread)
 * @param edits ticket 83's cancel, through 78's entry-retained binding
 * @param drafts ticket 84's per-chat save, through the entry-retained
 *   `SessionDraftSaver`
 */
class SessionNavLifecycle(
    val chatId: String,
    private val controller: SessionController,
    private val transcript: TranscriptState,
    private val edits: QueuedEditCanceller,
    private val drafts: DraftSaver,
) {
    /** True after [release]/[closeAfterPop]: every method is a no-op then. */
    var isClosed: Boolean = false
        private set

    /**
     * 24's `onWillAppear`, or composition enter. iOS `viewWillAppear`
     * (`:173-179`): `if hasAppeared { source.reattach() }` — the first
     * appearance does **not** re-attach (75 attached in its first
     * composition); every later one does (tab return, the re-run after a
     * cancelled pop). A redundant re-attach is harmless in the core
     * (`setViewAttached(true)` on an attached handle only touches the LRU
     * time).
     */
    fun onWillAppear(hasAppeared: Boolean) {
        if (!isClosed && hasAppeared) controller.reattach()
    }

    /**
     * 24's `onDidAppear`, or composition enter after the first appearance.
     * iOS `viewDidAppear` (`:280`): the router's `sessionDidAppear` runs
     * on **every** appearance (the push landing, a cancelled pop, a tab
     * switch back).
     */
    fun onDidAppear(router: AppRouter) {
        if (!isClosed) router.sessionDidAppear(chatId)
    }

    /**
     * 24's `onWillDisappear` (any — a pop that may still be cancelled, a
     * tab deactivation) or composition dispose outside a configuration
     * change. Reversible work only, in the iOS order: **cancel the edit
     * first** (it restores the stashed user draft into the composer),
     * **then save** (so the saved draft is the user's text, never the
     * queued row's). Idempotent.
     */
    fun leave() {
        if (isClosed) return
        edits.cancelIfEditing() // FIRST: puts the user's own draft back into the composer
        drafts.save()           // THEN: saves it (never the queued text)
    }

    /**
     * Composition dispose **during a configuration change** (rotation,
     * dark-mode switch, font scale): iOS has no disappearance there, so
     * the queued-edit lease must survive; only the draft is persisted.
     */
    fun onConfigurationDispose() {
        if (!isClosed) drafts.save()
    }

    /**
     * 24's `onDidDisappear(removed = true)` **only** — the pop/removal
     * has committed, the session really left its stack. iOS
     * `viewDidDisappear` (`:309-314`) with `isMovingFromParent ||
     * navigationController == nil`: tell the router (the phone router
     * forwards it, the tablet router ignores it while expanded), then
     * the irreversible teardown. Idempotent.
     */
    fun closeAfterPop(router: AppRouter) {
        if (isClosed) return
        router.sessionDidClose(chatId)
        release()
    }

    /**
     * Detach + mark seen (51's `controller.close()`, on its serial IO
     * lane) and the layout engine (61's `transcript.close()`, on main) —
     * the iOS `detach` → `engine.close()` → `markSeen` sequence, whose
     * relative FFI order is not observable (the mark is a client stamp;
     * the layout worker never reads it). Also 76's NavEntry safety net:
     * close-only removal paths (`popToRoot` intermediates, `setRoutes`)
     * run it without `sessionDidClose`. Idempotent.
     */
    fun release() {
        if (isClosed) return
        isClosed = true
        controller.close()   // IO lane: setViewAttached(false), then markSeen
        transcript.close()   // main: relay close, engine shutdown, engine close
    }
}
