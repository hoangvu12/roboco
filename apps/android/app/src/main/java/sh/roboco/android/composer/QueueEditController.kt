package sh.roboco.android.composer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.roboco.android.session.QueuedEditGate

/**
 * The queued-message edit lease state machine (android-ui-parity #83) —
 * the port of `SessionViewController`'s view-side half
 * (`ios:Session/SessionViewController.swift:381-452`) plus the Android
 * **recovery** additions (a lost renewal or a failed commit merges the
 * edited text back into the composer and shows the failure copy in 73's
 * pill; iOS silently stops renewing and ignores finish results).
 *
 * A non-visual state machine: the engine (through [QueueEditPort]) holds
 * the row with a host lease while it is edited, and the composer (through
 * [QueueEditHost]) shows the row's visible text in place of the user's
 * stashed draft. Snapshot state backs [editingId] (so [isEditing] is
 * observable — 73's `shownBanner` overlay and 74's non-structural pill
 * toggle read it); everything else is main-thread-only plain fields.
 *
 * **Threading.** Everything runs on main. [scope] is the session's
 * `Dispatchers.Main.immediate` scope (the nav entry's), so
 * [beginEdit]'s synchronous part — the switch's stash restore — has
 * happened when it returns, exactly iOS's ordering guarantee. [releaseScope]
 * outlives the screen (the app's `SupervisorJob` + main): a finish a
 * screen started still reaches the engine after its nav entry closes.
 *
 * **Lease discipline** (the facts the port keeps):
 * - the stash is taken **once**, at the first edit; it never holds a
 *   queued row's text;
 * - switching rows restores the draft **before** any suspension, then
 *   finishes the old lease with cancel **and joins it** before the new
 *   begin, so nothing of A's edit can be sent as a new message meanwhile;
 * - the unchanged rule: an edit equal to the row's visible text cancels
 *   (a commit would drop what the composer never showed);
 * - the renewal ticks [renewEveryMs] after acquisition; a `false` (lost,
 *   missing or transport — the FFI collapses all three) triggers
 *   recover-lost with **no finish call**: the row stays gated
 *   (`ReviewRequired` once the engine expires it) so the user decides
 *   via Edit or Remove;
 * - a finish for an acquired-but-missing row releases the lease — no
 *   iOS-style leak.
 */
@Stable
class QueueEditController(
    private val port: QueueEditPort,
    private val host: QueueEditHost,
    private val scope: CoroutineScope,
    private val releaseScope: CoroutineScope,
    private val newInstanceId: () -> String = { java.util.UUID.randomUUID().toString() },
    private val renewEveryMs: Long = 20_000L,
) : QueuedEditGate {

    /** The row being edited, or null (`editingQueueId`). Snapshot state. */
    var editingId: String? by mutableStateOf(null)
        private set

    /** 73's display overlay flag and 74's non-structural pill toggle. */
    val isEditing: Boolean
        get() = editingId != null

    /**
     * 78's `QueuedEditGate`: `Edit queued message` while an edit is open,
     * else null (78's `applyChrome` writes it over the chrome's
     * placeholder, which iOS resets by mistake on every chrome change).
     */
    override val placeholderOverride: String?
        get() = if (editingId != null) QueueEditCopy.Placeholder else null

    /**
     * 78's `QueuedEditGate` / `onSend` step 1: while editing, commit the
     * edit through [commit] and return [SendResult.Replaced] (the
     * composer keeps its contents — the stash was just restored into
     * it); while idle, `null` (a no-op with no port call) so the normal
     * send runs.
     */
    override fun interceptSend(body: String): SendResult? =
        if (editingId != null) {
            commit(body)
            SendResult.Replaced
        } else {
            null
        }

    /** The user's own parked draft text (ticket 84 saves this instead of the composer's text), or null. */
    val stashedDraftOrNull: String?
        get() = stash?.text

    /**
     * Begins (or switches to) an edit of [rowId] — 82's queue row menu
     * `Edit`. While a begin is pending, further taps are ignored
     * (iOS's `editPending` guard). **Switching:** synchronously put the
     * user's own draft back (so nothing of A's edit can be sent as a new
     * message while the leases change hands), then finish A with cancel,
     * await it, and begin B.
     *
     * A refused begin (locked, missing, unreachable, thrown) shows the
     * `Can't edit right now` alert and leaves the composer untouched. A
     * begin that acquired after the screen left or the controller was
     * disposed — or for a row that left the snapshot — releases the
     * lease with cancel and shows nothing else.
     */
    fun beginEdit(rowId: String) {
        if (pending || disposed) return
        pending = true
        val switching = editingId != null
        if (switching) {
            // Row A goes back and the user's own draft returns right away.
            // No suspension before this: the ordering guarantee is iOS's
            // (:400-408), and it is what keeps A's text out of a new send.
            editingId = null
            restoreStash()
            host.placeholder = host.normalPlaceholder
            host.attachEnabled = true
        }
        scope.launch {
            try {
                if (switching) {
                    finishLease(text = null, editedRaw = null).join()
                }
                val start = try {
                    port.begin(rowId, newInstanceId())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    EditStart.Unavailable
                }
                val acquired = start as? EditStart.Acquired
                if (acquired == null) {
                    host.showCantEditAlert()
                    return@launch
                }
                val held = acquired.lease
                val row = try {
                    port.row(rowId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                if (row == null) {
                    // No lease leak: release what we just acquired, then alert.
                    releaseAcquired(held)
                    host.showCantEditAlert()
                    return@launch
                }
                if (disposed || !onScreen) {
                    // Left the session while waiting (iOS :418-422): give
                    // the row straight back, nothing else.
                    releaseAcquired(held)
                    return@launch
                }
                lease = held
                base = row
                startRenewal(held)
                if (stash == null) {
                    // Stash once: on a switch the composer already holds the
                    // user's own draft again, so this is still that draft.
                    stash = Stash(text = host.text, images = host.images)
                }
                editingId = rowId
                host.images = emptyList()
                host.text = row.visible
                host.placeholder = QueueEditCopy.Placeholder
                host.attachEnabled = false
                lastFailure?.let(host::clearFailure)
                lastFailure = null
                host.focusComposer()
            } finally {
                pending = false
            }
        }
    }

    /**
     * Commits the edit with [text] — the exact body 78 passes to
     * `interceptSend` (trimmed, mention-encoded; never re-trimmed or
     * re-encoded). No-op while idle. The composer's raw text at commit
     * time is captured first (recovery must show the un-encoded tokens),
     * then the draft is restored and the lease finishes on the release
     * scope: unchanged → cancel with no text, changed → COMMIT with
     * `replacingVisible(raw, visible, edited)`.
     */
    fun commit(text: String) {
        if (editingId == null) return
        val editedRaw = host.text
        editingId = null
        restoreStash()
        host.placeholder = host.normalPlaceholder
        host.attachEnabled = true
        finishLease(text = text, editedRaw = editedRaw)
    }

    /**
     * Cancels the edit — 73's `onEditingTap` (the pill's tap while
     * editing) and 84's cancel-before-save on leave. No-op while idle
     * (a second call, or a call after the edit ended, makes no port
     * call). The draft is restored first, then the lease finishes with
     * cancel on the release scope.
     */
    fun cancelEdit() {
        if (editingId == null) return
        editingId = null
        restoreStash()
        host.placeholder = host.normalPlaceholder
        host.attachEnabled = true
        finishLease(text = null, editedRaw = null)
    }

    /** The screen appeared again (ticket 24's `NavEntryEffect`): an in-flight begin may now take the lease. */
    fun onScreenShown() {
        onScreen = true
    }

    /**
     * The screen is leaving (ticket 24's `NavEntryEffect`). **Only a
     * flag** for the "acquired after leaving" case above — the
     * cancel-on-leave itself is 85's (its `leave()` calls [cancelEdit]
     * through its adapter before 84's save), never this.
     */
    fun onScreenLeft() {
        onScreen = false
    }

    /**
     * The session's controllers are being disposed (the nav entry's
     * close, after a committed pop): marks the controller disposed, so
     * an in-flight begin releases its lease and a failed commit never
     * touches a composer that no longer exists, then cancels the edit
     * and the renewal. The finish still runs — on [releaseScope], which
     * outlives the (already cancelled) entry scope.
     */
    fun dispose() {
        disposed = true
        cancelEdit()
        renewal?.cancel()
        renewal = null
    }

    // ── private state ────────────────────────────────────────────────────

    /** The user's own parked draft (never a queued row's text). */
    private data class Stash(val text: String, val images: List<StagedImage>)

    private var stash: Stash? by mutableStateOf(null)

    /** An edit lease is being requested (taps on other rows wait). */
    private var pending = false

    /** `view.window != nil` — only the "acquired after leaving" guard. */
    private var onScreen = true

    private var disposed = false

    private var lease: EditLease? = null

    /** The row as it was when the edit began (raw + visible; the trailer / Appshot context rides along). */
    private var base: EditRow? = null

    private var renewal: Job? = null

    /** The recovery message currently held in 73's send-failure slot, or null. */
    private var lastFailure: String? = null

    /** `restoreStash`: nothing when no stash exists (a cancel after a failed begin). */
    private fun restoreStash() {
        val parked = stash ?: return
        host.text = parked.text
        host.images = parked.images
        stash = null
    }

    /**
     * The renewal loop (`CoreSessionSource.swift:157-169`): sleep
     * [renewEveryMs], then renew — the first tick is one interval after
     * acquisition. Ends (a) when the lease was replaced or released
     * (`lease != held`, checked after the sleep and again after the
     * renew, so an edit that ended while the renew was in flight is
     * never reported lost), or (b) through recover-lost.
     */
    private fun startRenewal(held: EditLease) {
        renewal = scope.launch {
            while (true) {
                delay(renewEveryMs)
                if (lease != held) return@launch
                val renewed = try {
                    port.renew(held)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    false
                }
                if (lease != held) return@launch
                if (!renewed) {
                    recoverLost()
                    return@launch
                }
            }
        }
    }

    /**
     * The port of `CoreSessionSource.finishEdit` (`:168-186`): cancel the
     * renewal, take the lease and the base, then release on
     * **[releaseScope]** — the returned [Job] is what the switch path
     * joins before beginning the next row. A null lease is a no-op.
     *
     * `body`: null (cancel) when [text] is null, or when it equals the
     * row's visible text exactly; otherwise COMMIT with
     * `replacingVisible(raw, visible, text)`. A non-`Finished` commit
     * outcome (and a thrown finish, folded into `Unavailable`) triggers
     * recover-commit — unless the controller is already disposed. Cancel
     * outcomes are ignored, as on iOS.
     */
    private fun finishLease(text: String?, editedRaw: String?): Job {
        renewal?.cancel()
        renewal = null
        val held = lease
        val b = base
        lease = null
        base = null
        return releaseScope.launch {
            if (held == null) return@launch
            val body = if (text != null && b != null) {
                if (text == b.visible) null else QueueEditText.replacingVisible(b.raw, b.visible, text)
            } else {
                text
            }
            val outcome = try {
                port.finish(held, commit = body != null, text = body)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                EditFinish.Unavailable
            }
            if (body != null && outcome != EditFinish.Finished && !disposed) {
                recoverCommit(editedRaw, outcome)
            }
        }
    }

    /**
     * Releases a lease the begin acquired but could not use (the row left
     * the snapshot, the screen left, the controller was disposed): CANCEL
     * on the release scope, outcome ignored (an unreachable engine expires
     * the lease on its own and the row turns `Needs review`).
     */
    private fun releaseAcquired(held: EditLease): Job = releaseScope.launch {
        try {
            port.finish(held, commit = false, text = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Nothing more this client can do; see above.
        }
    }

    /**
     * Recover-lost (the renewal came back false while still editing this
     * lease): the edited text merges in front of the draft, the draft's
     * images return, the pill gets `Edit protection expired; …` through
     * 73's sticky slot. **No finish call** — releasing the row with
     * cancel would let the old text deliver at turn end while the user's
     * edit sits in the composer; leaving it gated makes the user decide
     * via Edit or Remove, as the desktop does.
     */
    private fun recoverLost() {
        val edited = host.text
        renewal?.cancel()
        renewal = null
        lease = null
        base = null
        editingId = null
        val parked = stash
        host.images = parked?.images ?: emptyList()
        host.text = QueueEditText.merge(edited, parked?.text ?: "")
        stash = null
        host.placeholder = host.normalPlaceholder
        host.attachEnabled = true
        val message = QueueEditCopy.RenewLost
        host.showFailure(message)
        lastFailure = message
    }

    /**
     * Recover-commit (a commit finished `Conflict` / `Missing` / `Lost` /
     * `Unavailable`): the edited raw text (tokens intact, never the
     * encoded body) merges in front of the current text — into the
     * **stash** when a new edit is already open (its restore will surface
     * the merge), into the composer otherwise. Images are untouched.
     * `host.showFailure` carries the matching copy into the pill.
     */
    private fun recoverCommit(editedRaw: String?, outcome: EditFinish) {
        if (editedRaw != null) {
            val parked = stash
            if ((editingId != null || pending) && parked != null) {
                stash = parked.copy(text = QueueEditText.merge(editedRaw, parked.text))
            } else {
                host.text = QueueEditText.merge(editedRaw, host.text)
            }
        }
        val message = QueueEditCopy.finishMessage(outcome) ?: return
        host.showFailure(message)
        lastFailure = message
    }
}
