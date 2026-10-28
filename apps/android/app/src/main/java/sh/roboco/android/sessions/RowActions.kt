package sh.roboco.android.sessions

import androidx.compose.runtime.staticCompositionLocalOf
import uniffi.roboco_core.SectionView

/**
 * How one session/section write ended (android-ui-parity #42). The UI
 * changes nothing on failure — rows move only when the core's snapshot
 * changes — so this is *information*, not a command:
 *
 *  - [Ok] — the FFI call returned; android-app#07's overlay will move the row.
 *  - [Skipped] — a blank trimmed name/title; nothing was called.
 *  - [NoClient] — no live client (not paired / torn down / `Closed`).
 *  - [EngineOffline] — the owning engine is not connected; the row stays.
 *  - [PinsNotSynced] — the engine's sidebar was not ready yet (pin and
 *    section writes only); the row stays.
 *  - [Gone] — the id is unknown; the row is already gone.
 *  - [Failed] — anything else (an invalid argument, an engine error, …);
 *    an error toast was shown.
 */
enum class ActionOutcome { Ok, Skipped, NoClient, EngineOffline, PinsNotSynced, Gone, Failed }

/**
 * Every session/section write the UI makes (android-ui-parity #42) — the
 * port of `AppModel`'s `// MARK: Writes` surface
 * (`ios:App/AppModel.swift:495-519`), one method per iOS method, same FFI
 * calls, same log labels.
 *
 * Main-thread API; the FFI work runs off main on one serial IO lane
 * ([CoreRowActions]), so archive-then-instant-Undo and a run of `movePin`
 * calls reach the core in the user's order. [onResult] is called once, on
 * the main thread, after the call has returned — callers use it where iOS
 * acts after the write (ticket 45 updates the folder title after a rename
 * and pops after a delete; ticket 80 decides whether to pop after an
 * archive; tickets 44/47 revert the gesture on refusal).
 *
 * Implementations live for the app's lifetime (iOS's `AppModel`): an Undo
 * raised by the session ⋯ menu must still work after that screen popped.
 */
interface RowActions {

    /** Pin/unpin a chat (`AppModel.setPinned`). */
    fun setPinned(chatId: String, pinned: Boolean, onResult: (ActionOutcome) -> Unit = {})

    /**
     * Archive a chat. On [ActionOutcome.Ok] shows the "Archived · Undo"
     * toast; Undo calls [unarchive] through the same queue.
     */
    fun archive(chatId: String, onResult: (ActionOutcome) -> Unit = {})

    /** Unarchive a chat — no toast of its own (iOS has none). */
    fun unarchive(chatId: String, onResult: (ActionOutcome) -> Unit = {})

    /**
     * Delete a chat for good (`AppModel.delete`, upstream #967) — the
     * caller has already confirmed ("Delete session?"); no toast and no
     * Undo of its own.
     */
    fun delete(chatId: String, onResult: (ActionOutcome) -> Unit = {})

    /** Move a chat into a section; `null` = "No Section" (back to Recent). */
    fun move(chatId: String, toSection: String?, onResult: (ActionOutcome) -> Unit = {})

    /** Reorder a pin between neighbours (`null` at either end). */
    fun movePin(
        chatId: String,
        after: String?,
        before: String?,
        onResult: (ActionOutcome) -> Unit = {},
    )

    /**
     * Pin between neighbours (`null` at either end) — a drop into Pinned
     * (wave-10 41, the core's `pinSessionAt`). The default reports
     * [ActionOutcome.Skipped] (previews and fakes).
     */
    fun pinAt(chatId: String, after: String?, before: String?, onResult: (ActionOutcome) -> Unit = {}) {
        onResult(ActionOutcome.Skipped)
    }

    /**
     * Move a chat into a section between its members (`null` anchors at
     * either end; `sectionId == null` = back to Recent) — a drop there
     * (wave-10 41, the core's `placeSession`). Default: [ActionOutcome.Skipped].
     */
    fun place(
        chatId: String,
        sectionId: String?,
        after: String?,
        before: String?,
        onResult: (ActionOutcome) -> Unit = {},
    ) {
        onResult(ActionOutcome.Skipped)
    }

    /** Rename a chat; a blank trimmed title is skipped. */
    fun rename(chatId: String, title: String, onResult: (ActionOutcome) -> Unit = {})

    /**
     * Create a section on one engine (`null` = the first `Connected`
     * engine); a blank trimmed name is skipped. [onCreated] receives the
     * scoped section id, on the main thread, only on [ActionOutcome.Ok].
     */
    fun createSection(
        name: String,
        engineId: String?,
        onResult: (ActionOutcome) -> Unit = {},
        onCreated: (sectionId: String) -> Unit = {},
    )

    /** Rename a section; a blank trimmed name is skipped. */
    fun renameSection(sectionId: String, name: String, onResult: (ActionOutcome) -> Unit = {})

    /** Delete a section (its sessions return to Recent). */
    fun deleteSection(sectionId: String, onResult: (ActionOutcome) -> Unit = {})

    /**
     * The user sections of one engine, in snapshot order, empty ones
     * included (iOS `folders`, `AppModel.swift:382-386`); Pinned never —
     * it is not a `SectionView`. Section pickers use this because an
     * assign across engines is refused. Runs on the caller's thread and
     * never touches the FFI — the list comes from the snapshot the app
     * already holds.
     */
    fun sectionsFor(engineId: String): List<SectionView>

    /**
     * Log + toast a write error a caller caught itself (ticket 43's
     * `rowActions.report(e)` contract for its own section writes). Uses
     * the failure table with Kind = Sidebar. `CancellationException` is
     * ignored (no toast). Main thread; never calls the FFI.
     */
    fun report(error: Throwable)

    companion object {
        /**
         * Previews and tests of neighbouring tickets: every write is a
         * no-op ([onResult] is never called), `sectionsFor` lists
         * nothing, `report` does nothing.
         */
        val Noop: RowActions = object : RowActions {
            override fun setPinned(chatId: String, pinned: Boolean, onResult: (ActionOutcome) -> Unit) {}
            override fun archive(chatId: String, onResult: (ActionOutcome) -> Unit) {}
            override fun unarchive(chatId: String, onResult: (ActionOutcome) -> Unit) {}
            override fun delete(chatId: String, onResult: (ActionOutcome) -> Unit) {}
            override fun move(chatId: String, toSection: String?, onResult: (ActionOutcome) -> Unit) {}
            override fun movePin(chatId: String, after: String?, before: String?, onResult: (ActionOutcome) -> Unit) {}
            override fun rename(chatId: String, title: String, onResult: (ActionOutcome) -> Unit) {}
            override fun createSection(name: String, engineId: String?, onResult: (ActionOutcome) -> Unit, onCreated: (sectionId: String) -> Unit) {}
            override fun renameSection(sectionId: String, name: String, onResult: (ActionOutcome) -> Unit) {}
            override fun deleteSection(sectionId: String, onResult: (ActionOutcome) -> Unit) {}
            override fun sectionsFor(engineId: String): List<SectionView> = emptyList()
            override fun report(error: Throwable) {}
        }
    }
}

/**
 * The ambient [RowActions]. [RowActions.Noop] until the app root (ticket
 * 35) provides the app-scoped instance inside its `ToastHost`.
 */
val LocalRowActions = staticCompositionLocalOf<RowActions> { RowActions.Noop }
