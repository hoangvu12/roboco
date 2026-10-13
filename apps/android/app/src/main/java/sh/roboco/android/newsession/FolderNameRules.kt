package sh.roboco.android.newsession

/**
 * The New Folder name check (android-ui-parity #111) — the client half of
 * upstream's server rules (`pr685:crates/engine/src/repos.rs:1363-1400`),
 * ported to Kotlin because Roboco has no `CreateFolder` RPC:
 * `PrepareSpacePath` would otherwise accept `../x` as a path (the ticket's
 * ADAPT). The messages are the Roboco engine's, verbatim.
 *
 * Pure and plain-JVM: no Compose state, no uniffi types.
 */
sealed interface FolderNameCheck {

    /** Whitespace only — iOS's empty guard on the alert's Create button. */
    data object Empty : FolderNameCheck

    /**
     * `.`, `..` or a name with a separator (`/`, `\`) or a NUL. The
     * trimmed name is carried for the re-prompt (iOS keeps the field).
     */
    data class Invalid(val trimmed: String) : FolderNameCheck

    /** A usable single path component: the trimmed name. */
    data class Valid(val name: String) : FolderNameCheck
}

/**
 * [FolderNameRules.check] and its message (the engine's
 * `Enter a folder name without path separators.`, ASCII apostrophe).
 */
object FolderNameRules {

    /** `pr685:repos.rs:1382`, prefixed the way iOS shows it (U+0027). */
    const val INVALID = "Couldn't create the folder. Enter a folder name without path separators."

    /**
     * `t = raw.trim()`; empty → [FolderNameCheck.Empty]; exactly `.` or
     * `..`, or any `/`, `\` or NUL → [FolderNameCheck.Invalid]; else
     * [FolderNameCheck.Valid] with `t`. Only the exact `.` and `..` are
     * rejected: `...` and dot-folders are legal names.
     */
    fun check(raw: String): FolderNameCheck {
        val t = raw.trim()
        if (t.isEmpty()) return FolderNameCheck.Empty
        if (t == "." || t == ".." || t.any { it == '/' || it == '\\' || it == '\u0000' }) {
            return FolderNameCheck.Invalid(t)
        }
        return FolderNameCheck.Valid(t)
    }
}
