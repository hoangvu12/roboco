package sh.roboco.android.composer

import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import sh.roboco.android.shell.LocalNavEntry
import sh.roboco.android.shell.LocalNavStack
import sh.roboco.android.shell.NavEntry

/**
 * One session's draft saver (android-ui-parity #84), retained on the
 * Session nav entry (ticket 24's `NavEntry.retain`, so a tab-host
 * disposal never drops it — the same lifetime as 78's composer binding
 * and 83's queue-edit controller). Plain, main-thread, unit-testable
 * logic; the composition half is [SessionDraftEffect].
 *
 * Ticket 85 (SessionNavLifecycle) calls [saveNow] from its `leave()`
 * (after cancelling an open queued edit — this class has no cancel API
 * and must never add one) and on configuration-change disposal.
 */
class SessionDraftSaver(
    /** The chat this saver persists: `DraftKey.Chat(core.engineId, core.chatId)`. */
    val key: DraftKey.Chat,
    private val drafts: Drafts,
) {
    /**
     * True while the composer was seeded before [Drafts] finished its
     * first read: a blank [saveNow] in that window is suppressed (leaving
     * a chat before the late load applied would otherwise delete a draft
     * the user never saw); a non-blank save goes through. The fallback
     * load in [SessionDraftEffect] clears it.
     */
    var pendingLoad: Boolean = !drafts.isLoaded
        internal set

    /** The composer's whole text, read at save time; null until [bind] (a no-op save). */
    private var composerText: (() -> String)? = null

    /** 83's stash (`stashedDraftOrNull`), read at save time; `null` = no edit open. */
    private var stashedDraft: () -> String? = { null }

    /**
     * Main thread, on every composition entry: the latest composer wins
     * (78 retains the controller across tab switches, but its composition
     * is recreated — the re-entry rebinds to the same controller's live
     * text). Never unbound: 85's dispose-time `saveNow()` runs after this
     * effect's disposal and must still read the (now disposed) composer's
     * last text — the next composition rebinds to the new one.
     */
    fun bind(composerText: () -> String, stashedDraft: () -> String?) {
        this.composerText = composerText
        this.stashedDraft = stashedDraft
    }

    /**
     * iOS `saveDraft()` (`SessionViewController.swift:389-392`): the
     * stash while a queued edit is open, else the composer text — never
     * a queued row's text. No-op until [bind]. [Drafts.save] applies the
     * blank-removes / verbatim-keeps rule.
     */
    fun saveNow() {
        val read = composerText ?: return
        val text = stashedDraft() ?: read()
        if (pendingLoad && isDraftBlank(text)) return
        drafts.save(key, text)
    }

    /**
     * `didEnterBackgroundNotification` (`:37-40`): save **only** — never
     * cancel an open queued edit (the user glancing at another app must
     * not lose their in-progress edit of the row; `saveNow` picks the
     * stash) — and only for the visible (top) entry of its stack: a
     * hidden copy's composer text could be stale, and it already saved
     * when it disappeared.
     */
    fun onBackground(isTop: Boolean) {
        if (isTop) saveNow()
    }
}

/** The saver's retention key: one saver per Session nav entry. */
private object SessionDraftSaverKey

/**
 * The entry-retained saver: [SessionDraftEffect] and ticket 85's
 * `SessionNavLifecycle` reach the **same** instance through the same
 * retain key (85's wiring: `DraftSaver { entry.sessionDraftSaver(key,
 * drafts).saveNow() }`).
 */
@MainThread
fun NavEntry.sessionDraftSaver(key: DraftKey.Chat, drafts: Drafts): SessionDraftSaver =
    retain(SessionDraftSaverKey) { SessionDraftSaver(key, drafts) }

/**
 * Binds one session's [SessionDraftSaver] to its live composer
 * (android-ui-parity #84) — the Android half of `viewDidLoad`'s load and
 * of the `didEnterBackgroundNotification` observer:
 *
 * - [DisposableEffect] binds on every composition entry and saves on
 *   disposal: the safety net that runs **before** 85's dispose-time
 *   `leave()` (Compose disposes inner effects before outer ones). In the
 *   normal flow it re-saves the same text, a no-op; it never unbinds (85
 *   reads the last text after this effect is gone).
 * - while [SessionDraftSaver.pendingLoad] is set, the fallback load
 *   applies the disk text only into an **empty**, non-editing composer
 *   (a tab switch back must not overwrite the retained composer's text),
 *   then clears the flag;
 * - the activity's [Lifecycle.Event.ON_STOP] saves (the app can be killed
 *   in the background: the draft goes to disk first) — no edit cancel,
 *   and only when this entry is the top of its [NavStack] (`stack.top`
 *   is snapshot state; reading it inside the callback gives the value at
 *   event time).
 *
 * This ticket hooks **no** navigation: 85 owns "cancel the edit, then
 * save" at every disappearance delivered by ticket 24.
 */
@Composable
fun SessionDraftEffect(
    saver: SessionDraftSaver,
    /** Ticket 54's controller, created by 78 and retained on the same nav entry. */
    composer: ComposerController,
    /**
     * Ticket 83's queue-edit controller: `stashedDraftOrNull` /
     * `isEditing` are read at save time. Nullable because the controller
     * cannot be created on every screen host (no port, no banner filler,
     * no nav entry, a test app) — a null edit behaves exactly like an
     * open lease with no stash: the composer's own text is the draft.
     */
    edit: QueueEditController?,
    drafts: Drafts = LocalDrafts.current,
) {
    val stack = LocalNavStack.current
    val entry = LocalNavEntry.current
    DisposableEffect(saver, composer, edit) {
        saver.bind({ composer.text }, { edit?.stashedDraftOrNull })
        onDispose { saver.saveNow() } // the safety net; do NOT unbind
    }
    if (saver.pendingLoad) {
        LaunchedEffect(saver, composer) {
            val text = drafts.load(saver.key)
            if (composer.text.isEmpty() && edit?.isEditing != true) composer.text = text
            saver.pendingLoad = false
        }
    }
    // MainActivity declares configChanges, so rotation and fold do not
    // fire ON_STOP; a rare recreation (locale change) or an extra stop
    // (photo picker, camera, share sheet) only causes a harmless re-save.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { saver.onBackground(isTop = stack.top === entry) }
}
