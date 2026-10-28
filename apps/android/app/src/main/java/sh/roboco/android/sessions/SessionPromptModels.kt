package sh.roboco.android.sessions

import androidx.compose.ui.text.input.KeyboardCapitalization
import sh.roboco.android.design.ActionSheetAction
import sh.roboco.android.design.ActionSheetModel
import sh.roboco.android.design.ActionSheetStyle
import sh.roboco.android.design.AlertButton
import sh.roboco.android.design.AlertButtonRole
import sh.roboco.android.design.AlertField
import sh.roboco.android.design.RobocoAlert

/**
 * The Sessions area's five small prompts (android-ui-parity #43) as pure
 * model builders — the port of `ios:Threads/SessionListController.swift`'s
 * inline `UIAlertController`s: Rename Session (`:320-329`), New Section
 * (`:309-318`), Rename Section (`:400-409`, `:490-500`), Move to Section
 * (`:298-307`) and the folder-page delete confirm (`:502-513`).
 *
 * This file owns **no visuals** (ticket 18 draws the alerts, ticket 19 the
 * sheets) and **no writes** (ticket 42's [RowActions] owns every mutation;
 * [SessionPrompts] wires the two together). Everything here is a pure
 * function from live caller values to a model, so callers (45's folder
 * page, 46's front page, 44's row gestures, 105/107's sidebar) can also
 * build a model and present it themselves.
 */

/** Every string of the five prompts, verbatim from iOS (plus Roboco's "Create on" and offline toast). */
object SessionPromptCopy {
    /** `:321`. */
    const val RenameSessionTitle = "Rename Session"

    /** `:310`. */
    const val NewSectionTitle = "New Section"

    /** `:311` — the New Section field's placeholder. */
    const val NewSectionPlaceholder = "Name"

    /** `:401`, `:491`. */
    const val RenameSectionTitle = "Rename Section"

    /** `:299`. */
    const val MoveToSectionTitle = "Move to Section"

    /** `:303` — always listed, even when the chat is in no section. */
    const val NoSection = "No Section"

    /** `:503`. */
    const val DeleteSectionMessage = "Its sessions move back to the main list."

    /** `:504`. */
    const val DeleteSectionAction = "Delete Section"

    /** Roboco's engine-chooser message (android-app#20:237); iOS has no equivalent. */
    const val CreateOnMessage = "Create on"

    /** `:304`, `:312`, `:323`, `:403`, `:493`, `:509`. */
    const val Cancel = "Cancel"

    /** `:313`. */
    const val Create = "Create"

    /** `:324`, `:404`, `:494`. */
    const val Rename = "Rename"

    /** `web/…/notice-chip.ts:32` (`OFFLINE_NOTICE`) — this ticket's one pre-check toast. */
    const val EngineNotConnected = "Engine not connected"

    /** `Delete “<name>”?` — U+201C/U+201D curly quotes, checked byte-wise on `:503`. */
    fun deleteSectionTitle(name: String) = "Delete “$name”?"

    /** Upstream #967 `confirmDelete` (`ios:Session/SessionViewController.swift:603-618`), the desktop's dialog. */
    const val DeleteSessionTitle = "Delete session?"

    /** The confirm's destructive button (and the menus' row title). */
    const val Delete = "Delete"

    /** An untitled chat in the confirm's message (`row.hasTitle ? row.title : "New session"`). */
    const val UntitledSession = "New session"

    /** `“<title>” will be permanently deleted. This can’t be undone.` — curly quotes and apostrophe as upstream. */
    fun deleteSessionMessage(title: String) =
        "“${title.ifBlank { UntitledSession }}” will be permanently deleted. This can’t be undone."
}

/**
 * Trimmed text, or `null` when nothing is left — the iOS
 * `!name.isEmpty` guard. iOS trims `.whitespaces` (spaces and tabs);
 * Kotlin's [String.trim] also drops newlines, which the single-line field
 * can only receive by pasting — the safer choice there.
 */
fun trimmedName(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }

/**
 * "Rename Session" (`:320-329`): pre-filled with the row's displayed
 * title, no placeholder, `UITextField` defaults (`.sentences`, autocorrect
 * on). Blank text on Rename closes the alert and does nothing; a
 * non-empty trim submits once.
 */
fun renameSessionAlert(currentTitle: String, onRename: (String) -> Unit): RobocoAlert = RobocoAlert(
    title = SessionPromptCopy.RenameSessionTitle,
    field = AlertField(text = currentTitle),
    buttons = listOf(
        AlertButton(SessionPromptCopy.Cancel, role = AlertButtonRole.Cancel),
        AlertButton(SessionPromptCopy.Rename) { raw -> trimmedName(raw)?.let(onRename) },
    ),
)

/**
 * "New Section" (`:309-318`): an empty "Name" field with
 * `.words` capitalization. Create is never disabled (`requiresText` stays
 * false); a blank trim dismisses the alert without creating.
 */
fun newSectionAlert(onCreate: (String) -> Unit): RobocoAlert = RobocoAlert(
    title = SessionPromptCopy.NewSectionTitle,
    field = AlertField(
        placeholder = SessionPromptCopy.NewSectionPlaceholder,
        capitalization = KeyboardCapitalization.Words,
    ),
    buttons = listOf(
        AlertButton(SessionPromptCopy.Cancel, role = AlertButtonRole.Cancel),
        AlertButton(SessionPromptCopy.Create) { raw -> trimmedName(raw)?.let(onCreate) },
    ),
)

/**
 * "Rename Section" (`:400-409`, `:490-500`): like Rename Session with the
 * section's raw [SectionView][uniffi.roboco_core.SectionView] name — never
 * the header's `" · <engine>"` collision suffix (ticket 40) — pre-filled.
 * The trimmed name is what the write and the folder page's title update
 * both use.
 */
fun renameSectionAlert(currentName: String, onRename: (String) -> Unit): RobocoAlert = RobocoAlert(
    title = SessionPromptCopy.RenameSectionTitle,
    field = AlertField(text = currentName),
    buttons = listOf(
        AlertButton(SessionPromptCopy.Cancel, role = AlertButtonRole.Cancel),
        AlertButton(SessionPromptCopy.Rename) { raw -> trimmedName(raw)?.let(onRename) },
    ),
)

/** One row of the Move to Section sheet: a section of the chat's engine. */
data class SectionChoice(val id: String, val name: String)

/** One engine of the "Create on" chooser (or the new-section pre-check). */
data class EngineChoice(val engineId: String, val name: String, val connected: Boolean)

/**
 * "Move to Section" (`:298-307`): one default row per **user section of the
 * chat's engine** in snapshot order, then "No Section" — always last, even
 * when the chat is in no section. No checkmark on the current section (the
 * pr621 Android port adds one; iOS does not), no "New Section…" row.
 * Roboco scopes the list to one engine because a cross-engine assign is
 * refused (`InvalidArgument`).
 */
fun moveToSectionSheet(sections: List<SectionChoice>, onMove: (sectionId: String?) -> Unit): ActionSheetModel =
    ActionSheetModel(
        title = SessionPromptCopy.MoveToSectionTitle,
        actions = sections.map { section ->
            ActionSheetAction(section.name) { onMove(section.id) }
        } + ActionSheetAction(SessionPromptCopy.NoSection) { onMove(null) },
    )

/**
 * `Delete “<name>”?` (`:502-513`): the message about sessions returning to
 * Recent and one destructive "Delete Section" row. The name is the raw
 * section name; the title wraps it in U+201C/U+201D.
 */
fun deleteSectionSheet(name: String, onDelete: () -> Unit): ActionSheetModel = ActionSheetModel(
    title = SessionPromptCopy.deleteSectionTitle(name),
    message = SessionPromptCopy.DeleteSectionMessage,
    actions = listOf(
        ActionSheetAction(
            SessionPromptCopy.DeleteSectionAction,
            style = ActionSheetStyle.Destructive,
            onSelect = onDelete,
        ),
    ),
)

/**
 * "Delete session?" (upstream #967): an alert, not a sheet — Cancel, then
 * a destructive "Delete". Roboco has no Cloud machines, so the message is
 * always the plain "will be permanently deleted" one.
 */
fun deleteSessionAlert(title: String, onDelete: () -> Unit): RobocoAlert = RobocoAlert(
    title = SessionPromptCopy.DeleteSessionTitle,
    message = SessionPromptCopy.deleteSessionMessage(title),
    buttons = listOf(
        AlertButton(SessionPromptCopy.Cancel, role = AlertButtonRole.Cancel),
        AlertButton(SessionPromptCopy.Delete, role = AlertButtonRole.Destructive) { onDelete() },
    ),
)

/**
 * The "Create on" engine chooser (Roboco's step before the New Section
 * alert when several engines are connected, android-app#20:237): one
 * default row per **connected** engine in pairing order. Duplicate engine
 * names list as-is; no source defines a disambiguation.
 */
fun createOnSheet(engines: List<EngineChoice>, onPick: (engineId: String) -> Unit): ActionSheetModel =
    ActionSheetModel(
        title = SessionPromptCopy.NewSectionTitle,
        message = SessionPromptCopy.CreateOnMessage,
        actions = engines.map { engine ->
            ActionSheetAction(engine.name) { onPick(engine.engineId) }
        },
    )

/**
 * What ⋯/row "New Section…" should do first (android-app#20:235-239):
 * an explicit engine id wins when that engine is connected (an unknown id
 * counts as not connected); `null` decides by the connected count — none
 * → toast, one → straight to the alert, several → the chooser.
 */
sealed interface NewSectionTarget {
    /** No connected engine: the caller toasts "Engine not connected" and shows nothing. */
    data object NoneConnected : NewSectionTarget

    /** Exactly one candidate engine: show the New Section alert for it. */
    data class Engine(val engineId: String) : NewSectionTarget

    /** Two or more connected engines, in pairing order: show the "Create on" sheet. */
    data class Choose(val engines: List<EngineChoice>) : NewSectionTarget
}

/**
 * The decision for "New Section…": `explicitEngineId != null` → that engine
 * when connected, else [NewSectionTarget.NoneConnected]; `null` → by the
 * connected count (0 → [NewSectionTarget.NoneConnected], 1 →
 * [NewSectionTarget.Engine], else [NewSectionTarget.Choose] over the
 * connected engines in list order).
 */
fun newSectionTarget(explicitEngineId: String?, engines: List<EngineChoice>): NewSectionTarget {
    if (explicitEngineId != null) {
        val engine = engines.firstOrNull { it.engineId == explicitEngineId }
        return if (engine?.connected == true) {
            NewSectionTarget.Engine(explicitEngineId)
        } else {
            NewSectionTarget.NoneConnected
        }
    }
    val connected = engines.filter { it.connected }
    return when (connected.size) {
        0 -> NewSectionTarget.NoneConnected
        1 -> NewSectionTarget.Engine(connected.single().engineId)
        else -> NewSectionTarget.Choose(connected)
    }
}
