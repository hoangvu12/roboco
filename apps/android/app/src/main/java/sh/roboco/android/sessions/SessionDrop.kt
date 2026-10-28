package sh.roboco.android.sessions

/**
 * Dragging a session between the front page's sections (wave-10 41,
 * upstream #909's iPhone half, `Threads/SessionListController.swift`
 * drag/drop delegates): where a drop lands and which write it makes.
 * Pinned and the user's sections keep a placed order (shared with the
 * desktop through the engine's sidebar state); Recent is by activity, so a
 * drop there moves membership only. Pure — the gesture layer computes the
 * slot from the list it shows and hands the write to [RowActions].
 */

/** Where a dragged session lands. */
sealed interface SessionDropTarget {
    data object Pinned : SessionDropTarget

    data class Section(val id: String) : SessionDropTarget

    data object Recent : SessionDropTarget
}

/**
 * What a list section means as a destination (iOS `dropTarget`): Pinned,
 * Recent and the user's sections; a project group, search results or
 * anything else is not a place a session is put (`null`).
 */
fun dropTargetOf(sectionId: String, userSections: Set<String>, searching: Boolean): SessionDropTarget? = when {
    searching -> null
    sectionId == FrontPageSections.PINNED -> SessionDropTarget.Pinned
    sectionId == FrontPageSections.RECENT -> SessionDropTarget.Recent
    sectionId in userSections -> SessionDropTarget.Section(sectionId)
    else -> null
}

/** A drop's slot: the destination, the row's index among its rows, its session neighbours. */
data class SessionDropSlot(
    val target: SessionDropTarget,
    val index: Int,
    val after: String?,
    val before: String?,
)

/**
 * Where a drop at [index] of a destination whose session rows are [rows]
 * places [draggedId] (iOS `dropSlot`): the rows without the dragged one,
 * the index clamped into them, and the neighbours either side. Pure.
 */
fun sessionDropSlot(target: SessionDropTarget, rows: List<String>, draggedId: String, index: Int): SessionDropSlot {
    val others = rows.filter { it != draggedId }
    val at = index.coerceIn(0, others.size)
    return SessionDropSlot(
        target = target,
        index = at,
        after = others.getOrNull(at - 1),
        before = others.getOrNull(at),
    )
}

/** The write one drop makes (iOS `performDropWith`). */
sealed interface SessionDropWrite {
    /** A reorder inside Pinned. */
    data class MovePin(val after: String?, val before: String?) : SessionDropWrite

    /** Into Pinned from elsewhere, at the slot. */
    data class PinAt(val after: String?, val before: String?) : SessionDropWrite

    /** Into a section (or within it), at the slot. */
    data class Place(val sectionId: String, val after: String?, val before: String?) : SessionDropWrite

    /** From Pinned back to Recent. */
    data object Unpin : SessionDropWrite

    /** From a section back to Recent. */
    data object LeaveSection : SessionDropWrite

    /** A drag within Recent: nothing to place (it sorts by activity). */
    data object NoMove : SessionDropWrite
}

/** The write for a drop from [source] (where the row sits now) into [slot]. Pure. */
fun sessionDropWrite(source: SessionDropTarget?, slot: SessionDropSlot): SessionDropWrite = when (val target = slot.target) {
    SessionDropTarget.Pinned ->
        if (source == SessionDropTarget.Pinned) {
            SessionDropWrite.MovePin(slot.after, slot.before)
        } else {
            SessionDropWrite.PinAt(slot.after, slot.before)
        }
    is SessionDropTarget.Section -> SessionDropWrite.Place(target.id, slot.after, slot.before)
    SessionDropTarget.Recent -> when (source) {
        SessionDropTarget.Recent, null -> SessionDropWrite.NoMove
        SessionDropTarget.Pinned -> SessionDropWrite.Unpin
        is SessionDropTarget.Section -> SessionDropWrite.LeaveSection
    }
}

/** Runs one drop's write through [actions] (the one serial write lane). */
fun RowActions.applyDrop(chatId: String, write: SessionDropWrite, onResult: (ActionOutcome) -> Unit = {}) {
    when (write) {
        is SessionDropWrite.MovePin -> movePin(chatId, write.after, write.before, onResult)
        is SessionDropWrite.PinAt -> pinAt(chatId, write.after, write.before, onResult)
        is SessionDropWrite.Place -> place(chatId, write.sectionId, write.after, write.before, onResult)
        SessionDropWrite.Unpin -> setPinned(chatId, false, onResult)
        SessionDropWrite.LeaveSection -> move(chatId, null, onResult)
        SessionDropWrite.NoMove -> onResult(ActionOutcome.Skipped)
    }
}
