package sh.roboco.android.composer

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import uniffi.roboco_core.FileMatch
import uniffi.roboco_core.fileMentionLink

/**
 * The `@query` being typed: `[start, end)` covers `@` + query, with [end]
 * the cursor (exclusive) — the port of `MentionIndex.activeQuery`'s
 * `NSRange` (`ios:Composer/Mentions.swift:31-46`). [query] may be empty
 * (just `@`), and may contain any non-space/non-newline character,
 * including tabs.
 */
@Immutable
data class MentionQuery(val start: Int, val end: Int, val query: String)

/**
 * `@file` mentions: the composer shows `@name` tokens; on send they become
 * the canonical `[name](roboco-file:path)` links the engine resolves.
 * Port of `ios:Composer/Mentions.swift:5-48`.
 *
 * - Offsets are UTF-16 code units on both platforms (`NSString` ↔ Kotlin
 *   `String`), so every index carries over 1:1.
 * - [tokens] is snapshot state holding a `LinkedHashMap` copy: a new map is
 *   assigned on every change (the old one is never mutated), which is what
 *   recomposes [rememberMentionTransformation]. Iteration order is
 *   insertion order — the order [encode] tie-breaks equal-length keys in.
 * - The iOS quirks are kept on purpose: disambiguation happens at most
 *   once (a third basename clash overwrites the disambiguated entry),
 *   tokens are never forgotten until [reset] (the iOS doc comment claims
 *   otherwise; the code does not), and matching is plain, case-sensitive,
 *   word-boundary-free substring matching.
 * - Main thread only (snapshot state). [link] is a synchronous,
 *   allocation-light Rust call, fine on main at send time.
 */
@Stable
class MentionIndex(
    /**
     * The canonical-link builder. Injected so plain-JVM tests and previews
     * can construct an index without the native core: the default only
     * calls [fileMentionLink] when [encode] actually replaces a token,
     * never at construction.
     */
    private val link: (path: String, isDir: Boolean) -> String = { p, d -> fileMentionLink(p, d) },
) {

    /** Token → file, insertion order preserved. Read-only: [token]/[reset] reassign it. */
    var tokens: Map<String, FileMatch> by mutableStateOf(emptyMap())
        private set

    /**
     * Token for a file, disambiguated by parent folder on basename clashes.
     * Writes `tokens[token] = file` and returns the token **without** the
     * trailing space (the caller, ticket 86, appends `" "`).
     *
     * - Trims `/` from both ends, splits, drops empty pieces (Swift's
     *   `split` omits them; Kotlin's keeps them).
     * - `@` + the last piece, or `@` + the raw [FileMatch.path] when no
     *   pieces survive (path `""` or `"/"`).
     * - Clash: the token already maps to a *different* path (`isDir` is
     *   ignored) and there is more than one piece → `@` + the last two
     *   pieces. Only once: the disambiguated token is not re-checked, so a
     *   third clashing file overwrites that entry.
     * - The entry is always (over)written: with a single piece the
     *   basename token simply re-points at the new file.
     */
    fun token(file: FileMatch): String {
        val parts = file.path.trim('/').split('/').filter { it.isNotEmpty() }
        var t = "@" + (parts.lastOrNull() ?: file.path)
        val existing = tokens[t]
        if (existing != null && existing.path != file.path && parts.size > 1) {
            t = "@" + parts.takeLast(2).joinToString("/")
        }
        tokens = LinkedHashMap(tokens).apply { put(t, file) }
        return t
    }

    /**
     * Replace live tokens with canonical links, longest key first (stable:
     * ties keep insertion order) and left-to-right, non-overlapping,
     * case-sensitive (`String.replace`). Each entry replaces into the
     * progressively rewritten text, so a shorter key can match inside a
     * previously inserted link's *label* — an accepted iOS parity quirk
     * (`@scope` basenames); see the ticket's Comments.
     *
     * Does not trim (the caller, ticket 54, trims first, as iOS does) and
     * does not mutate [tokens].
     */
    fun encode(text: String): String {
        var out = text
        for ((t, f) in tokens.entries.sortedByDescending { it.key.length }) {
            // The guard keeps absent tokens from calling into the core.
            if (out.contains(t)) out = out.replace(t, link(f.path, f.isDir))
        }
        return out
    }

    /** Forgets every token (`tokens = emptyMap()`); called on `.sent` only (ticket 54). */
    fun reset() {
        tokens = emptyMap()
    }

    companion object {

        /**
         * The `@query` being typed at [cursor], if any — the Swift loop
         * transcribed. Scans back from `cursor - 1`:
         *
         * - only U+0020 and U+000A stop the scan (a tab, `\r` or NBSP may
         *   sit *inside* a query, and before an `@` they fail it);
         * - at the first `@` the preceding character must be a space, a
         *   newline or the start of the text, otherwise the answer is
         *   `null` **immediately** — the scan does not continue to an
         *   earlier `@` (so `a@b|` and `@@x|` are both `null`);
         * - `cursor` out of `[0, length]` → `null`; `cursor == 0` → `null`
         *   (the loop never runs); the query is `[start + 1, cursor)` and
         *   may be empty.
         */
        fun activeQuery(text: String, cursor: Int): MentionQuery? {
            if (cursor < 0 || cursor > text.length) return null
            var i = cursor - 1
            while (i >= 0) {
                val c = text[i]
                if (c == '@') {
                    val before = if (i > 0) text[i - 1] else ' '
                    if (before != ' ' && before != '\n') return null
                    return MentionQuery(i, cursor, text.substring(i + 1, cursor))
                }
                if (c == ' ' || c == '\n') return null
                i--
            }
            return null
        }

        /**
         * iOS passes `selectedRange.location` — the selection *start*, the
         * length ignored — so this uses [TextFieldValue.selection.min].
         * Hiding the card on a non-collapsed selection is ticket 86's UI
         * policy, not an `activeQuery` change.
         */
        fun activeQuery(value: TextFieldValue): MentionQuery? =
            activeQuery(value.text, value.selection.min)
    }
}
