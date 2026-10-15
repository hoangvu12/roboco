package sh.roboco.android.composer

/**
 * The queue-edit text maths (android-ui-parity #83) — the Kotlin port of
 * `CoreSessionSource.replacingVisible`
 * (`ios:Session/CoreSessionSource.swift:437-452`) and the recovery merge.
 *
 * Pure string functions, so [QueueEditTests] pins them on the plain JVM
 * with no native core. android-app#08 also exports `replacing_visible`
 * from Rust; this is the same port of the same 6 Swift lines — if they
 * ever diverge, the Rust one is canonical (kept separate so the JVM
 * tests run without the native library).
 */
object QueueEditText {

    /**
     * The row's raw text with its visible part replaced, keeping the
     * hidden context that rides after it (the attachment trailer, the
     * Appshot block). Cases:
     *
     * - a blank edit stays blank — the host removes the row;
     * - `raw == visible` → the edit is the whole new text;
     * - the raw body (leading whitespace dropped) starts with the visible
     *   text → the edit plus the invisible tail;
     * - otherwise (an attachment-only row: all of the raw text is
     *   context, the visible text is a placeholder) → the edit, a blank
     *   line, then the whole raw body.
     *
     * Kotlin `startsWith` / `substring` work on UTF-16 units where Swift
     * works on grapheme clusters; they differ only when `visible` ends in
     * the middle of a grapheme cluster of `raw`, which cannot happen for
     * core-produced `visibleText` (ticket Comments).
     */
    fun replacingVisible(raw: String, visible: String, edited: String): String {
        if (edited.isBlank() || raw == visible) return edited
        val body = raw.trimStart()
        if (body.startsWith(visible)) return edited + body.substring(visible.length)
        return if (body.isEmpty()) edited else edited + "\n\n" + body
    }

    /**
     * The recovery merge: the edited text first, then one blank line,
     * then the user's own draft (android-app#24's reading of #277's
     * "keep the edit as a recoverable draft"). A blank side yields the
     * other side unchanged.
     */
    fun merge(edited: String, draft: String): String =
        if (draft.isBlank()) edited else if (edited.isBlank()) draft else edited + "\n\n" + draft
}

/**
 * Every string this ticket owns (android-ui-parity #83), verbatim: the
 * iOS composer/alert strings from `SessionViewController.swift:410-415`
 * and the five failure messages from the Roboco desktop / web copy
 * (`crates/ui/src/queue.rs:1614-1640, 1677-1685`, mirrored in
 * `web/packages/app/src/lib/queue-actions.ts:229-276`).
 */
object QueueEditCopy {

    /** The composer's placeholder while an edit is open (`:415`). */
    const val Placeholder = "Edit queued message"

    /**
     * The alert title (`:411`). The apostrophe is ASCII (U+0027), as in
     * the Swift source.
     */
    const val AlertTitle = "Can't edit right now"

    /** The alert message (`:411`). */
    const val AlertMessage = "Another device is editing this message, or it was just sent."

    /** The alert's single `OK` button (Default; also the back action, ticket 18). */
    const val AlertOk = "OK"

    /** Recover-lost: the renewal came back false. */
    const val RenewLost = "Edit protection expired; review this message before sending"

    /** Recover-commit: finish returned Conflict. */
    const val FinishConflict = "This message changed on another device; your edit was kept locally"

    /** Recover-commit: finish returned Missing. */
    const val FinishMissing = "The queued message was removed; your edit was kept locally"

    /** Recover-commit: finish returned Lost. */
    const val FinishLost = "The edit lease changed; your text is still in the editor"

    /** Recover-commit: finish threw (transport error). */
    const val FinishUnreachable = "Couldn't reach the chat host; your edit is still in the editor"

    /**
     * The recovery copy for a finish outcome, or null for
     * [EditFinish.Finished] (a successful commit shows nothing).
     */
    fun finishMessage(outcome: EditFinish): String? = when (outcome) {
        EditFinish.Finished -> null
        EditFinish.Conflict -> FinishConflict
        EditFinish.Missing -> FinishMissing
        EditFinish.Lost -> FinishLost
        EditFinish.Unavailable -> FinishUnreachable
    }
}
