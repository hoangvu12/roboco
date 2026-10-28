package sh.roboco.android.sessions

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.roboco.android.design.Toaster
import uniffi.roboco_core.ClientEvent
import uniffi.roboco_core.CoreClient
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.SectionView

/**
 * The narrow FFI surface [CoreRowActions] needs (`mob:client_ffi/
 * workspace.rs:116-184`, android-app#07) — fakeable, because `CoreClient`
 * is a JNA object. Every method is the one-to-one Kotlin name UniFFI
 * generates from the Rust `snake_case` name; each throws the generated
 * [CoreException] variants fail-fast (offline engine, pins not ready,
 * unknown id, invalid argument) and never blocks on the network.
 */
interface SessionWrites {
    fun pinSession(chatId: String)
    fun unpinSession(chatId: String)
    fun archiveSession(chatId: String)
    fun unarchiveSession(chatId: String)
    fun deleteSession(chatId: String)
    fun assignSection(chatId: String, sectionId: String?)
    fun movePin(chatId: String, after: String?, before: String?)
    fun pinSessionAt(chatId: String, after: String?, before: String?)
    fun placeSession(chatId: String, sectionId: String?, after: String?, before: String?)
    fun renameSession(chatId: String, title: String)
    fun createSection(name: String, engineId: String?): String
    fun renameSection(sectionId: String, name: String)
    fun deleteSection(sectionId: String)
}

/**
 * The [SessionWrites] view of the live [CoreClient] — one-line
 * delegations to the generated methods (`CoreResult<T>` lifts to `T`
 * throwing `CoreException`). Cheap: allocates the delegating object only.
 */
fun CoreClient.asSessionWrites(): SessionWrites = object : SessionWrites {
    override fun pinSession(chatId: String) = this@asSessionWrites.pinSession(chatId)
    override fun unpinSession(chatId: String) = this@asSessionWrites.unpinSession(chatId)
    override fun archiveSession(chatId: String) = this@asSessionWrites.archiveSession(chatId)
    override fun unarchiveSession(chatId: String) = this@asSessionWrites.unarchiveSession(chatId)
    override fun deleteSession(chatId: String) = this@asSessionWrites.deleteSession(chatId)
    override fun assignSection(chatId: String, sectionId: String?) =
        this@asSessionWrites.assignSection(chatId, sectionId)
    override fun movePin(chatId: String, after: String?, before: String?) =
        this@asSessionWrites.movePin(chatId, after, before)
    override fun pinSessionAt(chatId: String, after: String?, before: String?) =
        this@asSessionWrites.pinSessionAt(chatId, after, before)
    override fun placeSession(chatId: String, sectionId: String?, after: String?, before: String?) =
        this@asSessionWrites.placeSession(chatId, sectionId, after, before)
    override fun renameSession(chatId: String, title: String) =
        this@asSessionWrites.renameSession(chatId, title)
    override fun createSection(name: String, engineId: String?): String =
        this@asSessionWrites.createSection(name, engineId)
    override fun renameSection(sectionId: String, name: String) =
        this@asSessionWrites.renameSection(sectionId, name)
    override fun deleteSection(sectionId: String) = this@asSessionWrites.deleteSection(sectionId)
}

/** The toast copy for a failure whose reason carries nothing usable (`web/packages/app/src/lib/chat-actions.ts:139`). */
internal const val GENERIC_FAILURE = "The change could not be applied."

/** The offline-engine toast (`web/packages/app/src/lib/notice-chip.ts:32`, `OFFLINE_NOTICE`). */
internal const val ENGINE_OFFLINE_TOAST = "Engine not connected"

/** The pins-not-synced toast (new copy, android-app#20 §Multi-engine). */
internal const val PINS_NOT_SYNCED_TOAST = "Pins aren't synced yet. Try again in a moment."

/** The archive toast pair (`ios:App/AppModel.swift:508`). */
internal const val ARCHIVED_TOAST = "Archived"
internal const val ARCHIVED_TOAST_ACTION = "Undo"

/**
 * The write's kind, which decides what `Unsupported` means: pin and
 * section writes (everything that ends in `SetSidebarPins` /
 * `SetSidebarSections`) raise `Unsupported("pins are not synced yet")`
 * and get the pins copy; chat writes (`archive`, `unarchive`, `rename`)
 * could only see `Unsupported` from an engine error mapping and get the
 * reason text (`move`/`assignSection` counts as Sidebar — it writes
 * `SetSidebarSections`).
 */
internal enum class OpKind { Chat, Sidebar }

/**
 * Pure: maps a thrown error to an outcome plus its toast text (`null` =
 * no toast) — the ticket's failure table, exactly. Rethrows
 * `CancellationException` (the caller rethrows, so a cancelled call never
 * toasts).
 *
 * Reads the generated `reason` field, not `message`: the UniFFI template
 * this repo generates renders `message` as `"reason=<value>"`
 * (`crates/mobile/uniffi.toml` renames the `message` fields to `reason`
 * so the variants compile; the property then stringifies them).
 */
internal fun classify(e: Throwable, kind: OpKind): Pair<ActionOutcome, String?> = when (e) {
    is CancellationException -> throw e
    is CoreException.HostUnavailable -> ActionOutcome.EngineOffline to ENGINE_OFFLINE_TOAST
    is CoreException.Unsupported ->
        if (kind == OpKind.Sidebar) ActionOutcome.PinsNotSynced to PINS_NOT_SYNCED_TOAST
        else ActionOutcome.Failed to (e.reason.ifBlank { null } ?: GENERIC_FAILURE)
    is CoreException.NotFound -> ActionOutcome.Gone to null
    is CoreException.Closed -> ActionOutcome.NoClient to null
    is CoreException -> ActionOutcome.Failed to (e.reasonOrNull?.ifBlank { null } ?: GENERIC_FAILURE)
    else -> ActionOutcome.Failed to GENERIC_FAILURE
}

/** The reason field of the remaining variants (every one but `Closed` has it). */
private val CoreException.reasonOrNull: String?
    get() = when (this) {
        is CoreException.NotFound -> reason
        is CoreException.InvalidArgument -> reason
        is CoreException.HostUnavailable -> reason
        is CoreException.Unsupported -> reason
        is CoreException.HostException -> reason
        is CoreException.Network -> reason
        is CoreException.Auth -> reason
        is CoreException.Storage -> reason
        is CoreException.NotImplemented -> reason
        is CoreException.Internal -> reason
        is CoreException.Closed -> null
    }

/**
 * For the owner's event flow: the notice texts of a `ClientEvent` stream —
 * `ClientEvent.Notice{message}` trimmed, blank ones dropped, every other
 * event filtered out. `CoreRowActions.startNoticeToasts` consumes it.
 */
fun Flow<ClientEvent>.notices(): Flow<String> =
    filterIsInstance<ClientEvent.Notice>().map { it.message.trim() }.filter { it.isNotEmpty() }

/**
 * The [RowActions] over the real FFI (android-ui-parity #42). One
 * instance for the whole app, app-lifetime scope — a toast's Undo must
 * outlive the screen that raised it (iOS holds `[weak self]` on the
 * app-lifetime `AppModel`).
 *
 * Threading (the ticket's table):
 *  - public methods are called on main;
 *  - the FFI call runs on [io] — `Dispatchers.IO.limitedParallelism(1)`,
 *    **one queue for every write**, so the user's order survives (an
 *    archive followed by an instant Undo, or a run of `movePin` calls);
 *  - toasts, `onResult` and `onCreated` run on [main];
 *  - notice collection runs on [main] in [scope].
 *
 * Never call the FFI on main: `CoreClient` goes through JNA and
 * android-app#06 warns about re-entrant listener callbacks. `writes` is
 * read on main at call time, so a re-created client (re-pair, forget) is
 * picked up the same way iOS re-reads `client?`.
 *
 * [log] is iOS's `NSLog("<what> failed: <error>")` with the Android tag;
 * tests pass `{}` to keep `android.util.Log` off the plain JVM.
 */
class CoreRowActions(
    private val writes: () -> SessionWrites?,
    private val sections: () -> List<SectionView>,
    private val toaster: Toaster,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val log: (String) -> Unit = { Log.w("Roboco", it) },
) : RowActions {

    private var noticeJob: Job? = null

    /**
     * Toasts every notice (a background write that failed after its
     * optimistic overlay was applied — android-app#07 reverts the overlay
     * and emits `ClientEvent.Notice{message}`). Blank messages are
     * skipped; the message is shown verbatim. App-scoped: call once at
     * app start; a later call while the collector is alive is ignored, so
     * a notice is never shown twice.
     */
    fun startNoticeToasts(notices: Flow<String>) {
        if (noticeJob?.isActive == true) return
        noticeJob = scope.launch(main) {
            notices.collect { message ->
                if (message.isNotBlank()) toaster.show(message)
            }
        }
    }

    // ------------------------------------------------------------------ writes

    override fun setPinned(chatId: String, pinned: Boolean, onResult: (ActionOutcome) -> Unit) {
        if (pinned) dispatch("pin", OpKind.Sidebar, onResult) { it.pinSession(chatId) }
        else dispatch("pin", OpKind.Sidebar, onResult) { it.unpinSession(chatId) }
    }

    override fun archive(chatId: String, onResult: (ActionOutcome) -> Unit) {
        dispatch("archive", OpKind.Chat, onResult, onOk = {
            // Gated on Ok: iOS shows this even when the call threw
            // (AppModel.swift:506-508), but Roboco writes fail fast when
            // the engine is offline and the row stays — a stale Undo
            // would promise a restore that never happened.
            toaster.show(ARCHIVED_TOAST, ARCHIVED_TOAST_ACTION) { unarchive(chatId) }
        }) { it.archiveSession(chatId) }
    }

    override fun unarchive(chatId: String, onResult: (ActionOutcome) -> Unit) {
        dispatch("unarchive", OpKind.Chat, onResult) { it.unarchiveSession(chatId) }
    }

    override fun delete(chatId: String, onResult: (ActionOutcome) -> Unit) {
        dispatch("delete", OpKind.Chat, onResult) { it.deleteSession(chatId) }
    }

    override fun move(chatId: String, toSection: String?, onResult: (ActionOutcome) -> Unit) {
        dispatch("assign", OpKind.Sidebar, onResult) { it.assignSection(chatId, toSection) }
    }

    override fun movePin(chatId: String, after: String?, before: String?, onResult: (ActionOutcome) -> Unit) {
        dispatch("move pin", OpKind.Sidebar, onResult) { it.movePin(chatId, after, before) }
    }

    override fun pinAt(chatId: String, after: String?, before: String?, onResult: (ActionOutcome) -> Unit) {
        dispatch("pin", OpKind.Sidebar, onResult) { it.pinSessionAt(chatId, after, before) }
    }

    override fun place(
        chatId: String,
        sectionId: String?,
        after: String?,
        before: String?,
        onResult: (ActionOutcome) -> Unit,
    ) {
        dispatch("assign", OpKind.Sidebar, onResult) { it.placeSession(chatId, sectionId, after, before) }
    }

    override fun rename(chatId: String, title: String, onResult: (ActionOutcome) -> Unit) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            onResult(ActionOutcome.Skipped)
            return
        }
        dispatch("rename", OpKind.Chat, onResult) { it.renameSession(chatId, trimmed) }
    }

    override fun createSection(
        name: String,
        engineId: String?,
        onResult: (ActionOutcome) -> Unit,
        onCreated: (sectionId: String) -> Unit,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            onResult(ActionOutcome.Skipped)
            return
        }
        dispatch("section", OpKind.Sidebar, onResult, onOk = onCreated) {
            it.createSection(trimmed, engineId)
        }
    }

    override fun renameSection(sectionId: String, name: String, onResult: (ActionOutcome) -> Unit) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            onResult(ActionOutcome.Skipped)
            return
        }
        dispatch("rename section", OpKind.Sidebar, onResult) { it.renameSection(sectionId, trimmed) }
    }

    override fun deleteSection(sectionId: String, onResult: (ActionOutcome) -> Unit) {
        dispatch("delete section", OpKind.Sidebar, onResult) { it.deleteSection(sectionId) }
    }

    // ------------------------------------------------------------------ reads and reports

    override fun sectionsFor(engineId: String): List<SectionView> =
        sections().filter { it.engineId == engineId }

    override fun report(error: Throwable) {
        if (error is CancellationException) return
        val (_, toast) = classify(error, OpKind.Sidebar)
        log("write failed: $error")
        toast?.let { toaster.show(it) }
    }

    // ------------------------------------------------------------------ the runner

    /**
     * The one runner every write goes through (named `dispatch`, not
     * `run`, so it never shadows the stdlib `run { }` used in its body):
     * read the client on main, make the call on the serial IO lane, then
     * toast/log/report on main.
     */
    private fun <T> dispatch(
        label: String,
        kind: OpKind,
        onResult: (ActionOutcome) -> Unit,
        onOk: (T) -> Unit = {},
        call: (SessionWrites) -> T,
    ) {
        scope.launch(main) {
            val w = writes() ?: run {
                log("$label skipped: no client")
                onResult(ActionOutcome.NoClient)
                return@launch
            }
            val result = withContext(io) { runCatching { call(w) } }
            result.fold(
                onSuccess = {
                    onOk(it)
                    onResult(ActionOutcome.Ok)
                },
                onFailure = { e ->
                    val (outcome, toast) = classify(e, kind) // rethrows CancellationException
                    log("$label failed: $e")
                    toast?.let { toaster.show(it) }
                    onResult(outcome)
                },
            )
        }
    }
}
