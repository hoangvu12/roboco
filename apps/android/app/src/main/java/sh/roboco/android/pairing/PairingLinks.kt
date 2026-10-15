package sh.roboco.android.pairing

import java.util.Locale
import sh.roboco.android.shell.percentDecode

/**
 * The inbound pairing-link grammar (android-ui-parity #139): the
 * `roboco://pair#url=` deep link (ticket 36 hands the whole string here
 * via `DeepLink.Pair`) and the `text/plain` share candidates.
 *
 * Pure Kotlin, **no `android.*`** — the grammar is plain-JVM-testable
 * (one Robolectric test pins the `encodedFragment` equivalence with
 * `android.net.Uri`). Ticket 138's QR decoding calls
 * [urlFromDeepLink] on its unwrapped `roboco://pair#url=` payload too;
 * whichever of 138/139 landed first created this object, the other
 * extended it.
 *
 * Everything here pre-fills ticket 72's Pair page and waits for one
 * tap — nothing in this file redeems, and nothing calls the FFI: the
 * only validation is the local shape (decode-once, trim, clean), with
 * the real `parse_pairing_url` run by the caller off the main thread.
 *
 * Never log a link, URL or candidate: they carry the pairing token.
 */
object PairingLinks {

    /** `roboco://pair#url=` — what [deepLinkFor] writes. */
    const val DEEP_LINK_PREFIX = "roboco://pair#url="

    /**
     * The share-candidate cap (a Roboco guard, not a source value): it
     * bounds the forwarded extra and the FFI parse calls for a
     * pathological share. Real shares carry one link.
     */
    const val MAX_SHARE_CANDIDATES = 8

    /**
     * Everything after the **first** `#`, or null when there is none —
     * exactly what `android.net.Uri.parse(link).encodedFragment`
     * returns (`StringUri` takes `substring(indexOf('#') + 1)`), so a
     * `#` inside the carried URL does not cut the fragment short.
     *
     * Hand-written (not `Uri`) so [PairingLinks] stays plain-JVM; the
     * Robolectric [PairingLinksUriTest] pins the equivalence.
     */
    fun encodedFragment(link: String): String? {
        val i = link.indexOf('#')
        return if (i < 0) null else link.substring(i + 1)
    }

    /**
     * `roboco://pair#url=<percent-encoded pairing URL>` → the pairing
     * URL, or null. Decoded **exactly once** (the web `/pair` landing
     * decodes twice — `URLSearchParams` + `decodeURIComponent` — and a
     * second pass here would corrupt a literal `%25…`), `+` kept
     * literal, trimmed, and rejected when anything inside is
     * whitespace or an ISO control (WHATWG would silently drop inner
     * tabs; a one-line field must not carry a hidden newline).
     *
     * Key: exact, case-sensitive `url=`; the **first** such `&`-part
     * wins. The scheme/host of the link itself are not checked —
     * ticket 36's `DeepLinks.parse` already did.
     */
    fun urlFromDeepLink(link: String): String? {
        val frag = encodedFragment(link) ?: return null
        val raw = frag.split('&').firstOrNull { it.startsWith("url=") }?.removePrefix("url=")
            ?: return null
        val url = percentDecode(raw, plusAsSpace = false)?.trim() ?: return null
        return url.takeIf { it.isNotEmpty() && it.none { c -> c.isWhitespace() || c.isISOControl() } }
    }

    /**
     * The `ACTION_SEND` share candidates: maximal runs of non-whitespace
     * chars (`Char.isWhitespace` — covers U+00A0 and `\r\n`, which Java
     * regex `\s` misses), trailing `.,;)` trimmed (all of them — none of
     * those can end a valid link), kept only when non-empty, containing
     * the substring `#token=` (an accepted URL's raw text always does,
     * and WHATWG strips tab/LF/CR that could hide it) and free of ISO
     * controls. The first [MAX_SHARE_CANDIDATES] survivors, in text
     * order.
     *
     * Leading `(<"` and trailing `>]"` are **not** trimmed: such tokens
     * fail the parser and the share falls back to opening the app
     * normally (spec scope).
     */
    fun shareCandidates(text: String): List<String> {
        val out = ArrayList<String>(MAX_SHARE_CANDIDATES)
        val run = StringBuilder()
        var inRun = false
        for (c in text) {
            if (c.isWhitespace()) {
                if (inRun) {
                    if (shareTokenKept(run.toString(), out) &&
                        out.size >= MAX_SHARE_CANDIDATES
                    ) {
                        return out
                    }
                    run.setLength(0)
                    inRun = false
                }
            } else {
                run.append(c)
                inRun = true
            }
        }
        if (inRun) shareTokenKept(run.toString(), out)
        return out
    }

    /**
     * The first candidate [accepts] takes, else null. When `accepts` is
     * the FFI parser, call this **off the main thread**; the pure
     * `shareCandidates` (all the trampoline ever runs) is safe anywhere.
     */
    fun urlFromSharedText(text: String, accepts: (String) -> Boolean): String? =
        shareCandidates(text).firstOrNull(accepts)

    /**
     * `roboco://pair#url=` + strict percent-encoding of [url]: each
     * UTF-8 byte outside the unreserved set `A–Z a–z 0–9 - . _ ~`
     * becomes `%XX` with uppercase hex, so
     * `urlFromDeepLink(deepLinkFor(u)) == u` for every clean `u`.
     * Not `URLEncoder`: it writes `+` for a space and leaves `*`
     * unescaped.
     */
    fun deepLinkFor(url: String): String {
        val out = StringBuilder(DEEP_LINK_PREFIX.length + url.length * 2)
        out.append(DEEP_LINK_PREFIX)
        for (b in url.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            if (unreservedByte(v)) {
                out.append(v.toChar())
            } else {
                out.append('%').append(String.format(Locale.ROOT, "%02X", v))
            }
        }
        return out.toString()
    }
}

/** One trimmed share token, kept when it can still hold a pairing link. */
private fun shareTokenKept(raw: String, out: MutableList<String>): Boolean {
    val token = raw.trimEnd('.', ',', ';', ')')
    if (token.isEmpty() || "#token=" !in token || token.any { it.isISOControl() }) return false
    out += token
    return true
}

/** The RFC 3986 unreserved set, byte-wise: `A–Z a–z 0–9 - . _ ~`. */
private fun unreservedByte(v: Int): Boolean =
    v in 'A'.code..'Z'.code ||
        v in 'a'.code..'z'.code ||
        v in '0'.code..'9'.code ||
        v == '-'.code ||
        v == '.'.code ||
        v == '_'.code ||
        v == '~'.code
