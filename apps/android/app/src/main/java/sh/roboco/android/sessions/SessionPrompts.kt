package sh.roboco.android.sessions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import sh.roboco.android.design.ActionSheetPresenter
import sh.roboco.android.design.Alerts
import sh.roboco.android.design.LocalActionSheet
import sh.roboco.android.design.LocalAlerts
import sh.roboco.android.design.LocalToaster
import sh.roboco.android.design.Toaster
import uniffi.roboco_core.CoreClient
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.SessionRow

/**
 * The launcher layer over the five prompt builders (android-ui-parity
 * #43): [SessionPrompts] turns them into imperative calls the row
 * gestures (44), the folder page (45), the front page menus (46) and the
 * tablet sidebar (105/107) can make from click handlers. Launchers take
 * the **live** record or name the caller already holds — nothing is looked
 * up except the engine list.
 *
 * All writes go through ticket 42's [RowActions]; this class never calls a
 * [CoreClient] write and never catches a [uniffi.roboco_core.CoreException]
 * — 42 owns the dispatch, the IO lane and every error toast. Follow-ups
 * key off 42's [ActionOutcome]: the folder title only on [ActionOutcome.Ok],
 * the pop after delete on [ActionOutcome.Ok] or [ActionOutcome.Gone].
 * 42 calls `onResult` on main a few ms after the write, so `onRenamed` /
 * `onDeleted` arrive asynchronously — callers must not assume they ran
 * before the launcher returns.
 */

/**
 * The only thing the prompts read that 42 doesn't provide: the engine
 * list (android-app#06). `engines()` is an in-memory read (every paired
 * engine in pairing order), fine on the main thread.
 */
interface SessionPromptBackend {

    /** Every engine, pairing order, as a chooser row's data. */
    fun engines(): List<EngineChoice>
}

/**
 * The [SessionPromptBackend] over the live [CoreClient] —
 * `engines()` maps the [uniffi.roboco_core.EngineView] records to
 * [EngineChoice]s, resolved at call time like 42's `writes` seam so a
 * re-created client (re-pair, forget) is picked up.
 */
class CoreSessionPromptBackend(private val client: () -> CoreClient?) : SessionPromptBackend {

    override fun engines(): List<EngineChoice> =
        client()?.engines().orEmpty().map { view ->
            EngineChoice(
                engineId = view.engineId,
                name = view.name,
                connected = view.state is EngineState.Connected,
            )
        }
}

/**
 * The ambient backend. Default: no engines, so ⋯ "New Section…" toasts
 * "Engine not connected" — what an unmounted host amounts to. The app root
 * (ticket 35) or the sessions tab (46, if it lands first) provides
 * [CoreSessionPromptBackend] where it provides [LocalRowActions].
 */
val LocalSessionPromptBackend = staticCompositionLocalOf<SessionPromptBackend> {
    object : SessionPromptBackend {
        override fun engines(): List<EngineChoice> = emptyList()
    }
}

/**
 * The five prompt launchers, as an interface so 44/45/46 can pass a
 * recording fake. Main-thread API: called from click handlers and menu
 * `onSelect`s, never during composition.
 */
interface SessionPrompts {

    /**
     * "Rename Session" for a row (normal or Archived): pre-fills
     * [SessionRow.title]; a non-empty trim calls
     * [RowActions.rename] (42 toasts its errors).
     */
    fun renameSession(row: SessionRow)

    /**
     * "New Section…". The ⋯ menu calls this with `engineId = null`
     * (connected-count routing: toast / alert / "Create on" chooser); the
     * row menu passes the chat's engine id, which skips the chooser and
     * toasts up front when that engine is not connected. Creates the
     * section only — it never moves the chat into it (iOS `:292-294`).
     */
    fun newSection(engineId: String? = null)

    /**
     * "Rename Section": pre-fills `currentName` (the raw section name);
     * on [ActionOutcome.Ok] the folder page's title update runs via
     * [onRenamed] with the trimmed name.
     */
    fun renameSection(sectionId: String, currentName: String, onRenamed: ((String) -> Unit)? = null)

    /**
     * "Move to Section" from the row's trailing Move swipe: the sheet
     * lists 42's [RowActions.sectionsFor] of the chat's engine plus
     * "No Section"; a pick calls [RowActions.move].
     */
    fun moveToSection(row: SessionRow)

    /**
     * The folder page's delete confirm: on [ActionOutcome.Ok] or
     * [ActionOutcome.Gone] — the section is gone either way —
     * [onDeleted] runs (45 pops). A refused delete leaves the user on a
     * section page that still exists.
     */
    fun confirmDeleteSection(sectionId: String, name: String, onDeleted: () -> Unit = {})

    /**
     * "Delete session?" (upstream #967's `confirmDelete`, the desktop's
     * dialog): Cancel or a destructive Delete, which calls
     * [RowActions.delete]. [onDeleted] runs on [ActionOutcome.Ok] or
     * [ActionOutcome.Gone] — the ⋯ menu leaves the session then; the list
     * needs nothing (the overlay drops the row).
     */
    fun confirmDeleteSession(chatId: String, title: String, onDeleted: () -> Unit = {})

    companion object {
        /** Previews and tests: every launcher is a no-op. */
        val Noop: SessionPrompts = object : SessionPrompts {
            override fun renameSession(row: SessionRow) {}
            override fun newSection(engineId: String?) {}
            override fun renameSection(sectionId: String, currentName: String, onRenamed: ((String) -> Unit)?) {}
            override fun moveToSection(row: SessionRow) {}
            override fun confirmDeleteSection(sectionId: String, name: String, onDeleted: () -> Unit) {}
            override fun confirmDeleteSession(chatId: String, title: String, onDeleted: () -> Unit) {}
        }
    }
}

/**
 * The default launchers. Everything runs on main: the hosts' `show` calls
 * are main-thread, 42's writes leave from main, and the toast is a
 * same-frame `Toaster.show`.
 */
internal class DefaultSessionPrompts(
    private val backend: SessionPromptBackend,
    private val rowActions: RowActions,
    private val alerts: Alerts,
    private val sheets: ActionSheetPresenter,
    private val toaster: Toaster,
) : SessionPrompts {

    override fun renameSession(row: SessionRow) {
        alerts.show(renameSessionAlert(row.title) { title ->
            rowActions.rename(row.id, title)
        })
    }

    override fun newSection(engineId: String?) {
        when (val target = newSectionTarget(engineId, backend.engines())) {
            NewSectionTarget.NoneConnected -> toaster.show(SessionPromptCopy.EngineNotConnected)
            is NewSectionTarget.Engine ->
                alerts.show(newSectionAlert { name -> rowActions.createSection(name, target.engineId) })
            is NewSectionTarget.Choose ->
                sheets.show(
                    createOnSheet(target.engines) { picked ->
                        // Ticket 19 runs onSelect after the sheet has cleared,
                        // so the alert presents from a clean host — no delay.
                        alerts.show(newSectionAlert { name -> rowActions.createSection(name, picked) })
                    },
                )
        }
    }

    override fun renameSection(sectionId: String, currentName: String, onRenamed: ((String) -> Unit)?) {
        alerts.show(
            renameSectionAlert(currentName) { name ->
                rowActions.renameSection(sectionId, name) { outcome ->
                    if (outcome == ActionOutcome.Ok) onRenamed?.invoke(name)
                }
            },
        )
    }

    override fun moveToSection(row: SessionRow) {
        val choices = rowActions.sectionsFor(row.engineId).map { section ->
            SectionChoice(id = section.id, name = section.name)
        }
        sheets.show(
            moveToSectionSheet(choices) { sectionId ->
                rowActions.move(row.id, sectionId)
            },
        )
    }

    override fun confirmDeleteSection(sectionId: String, name: String, onDeleted: () -> Unit) {
        sheets.show(
            deleteSectionSheet(name) {
                rowActions.deleteSection(sectionId) { outcome ->
                    if (outcome == ActionOutcome.Ok || outcome == ActionOutcome.Gone) onDeleted()
                }
            },
        )
    }

    override fun confirmDeleteSession(chatId: String, title: String, onDeleted: () -> Unit) {
        alerts.show(
            deleteSessionAlert(title) {
                rowActions.delete(chatId) { outcome ->
                    if (outcome == ActionOutcome.Ok || outcome == ActionOutcome.Gone) onDeleted()
                }
            },
        )
    }
}

/**
 * The launchers over the ambient hosts and fakes — call once per screen,
 * inside ticket 35's host stack (`AlertHost { ToastHost {
 * ActionSheetHost { … } } }`) so alerts sit above sheets and toasts above
 * both. The launchers are plain functions; call them from click handlers
 * or menu `onSelect`s, never during composition.
 */
@Composable
fun rememberSessionPrompts(
    backend: SessionPromptBackend = LocalSessionPromptBackend.current,
    rowActions: RowActions = LocalRowActions.current,
): SessionPrompts {
    val alerts = LocalAlerts.current
    val sheets = LocalActionSheet.current
    val toaster = LocalToaster.current
    return remember(backend, rowActions, alerts, sheets, toaster) {
        DefaultSessionPrompts(backend, rowActions, alerts, sheets, toaster)
    }
}
