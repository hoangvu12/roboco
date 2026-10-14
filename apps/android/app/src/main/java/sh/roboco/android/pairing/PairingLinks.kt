package sh.roboco.android.pairing

import sh.roboco.android.shell.percentDecode

/**
 * The pairing link grammar shared by the QR scanner (android-ui-parity
 * #138) and the inbound links (139): the `roboco://pair#url=…` deep-link
 * wrapper. 138 ships only the two members the scanner needs, written
 * exactly as 139's Steps 1–2; **139 must extend this object** (add
 * `shareCandidates`, `urlFromSharedText`, `deepLinkFor`, the
 * `DEEP_LINK_PREFIX`/`MAX_SHARE_CANDIDATES` constants), never add a
 * second one.
 *
 * Pure Kotlin, no `android.*` and no `java.net.URLDecoder` (36: it maps
 * `+` to a space and throws on malformed escapes) — plain-JVM testable.
 */
object PairingLinks {

    /**
     * Everything after the **first** `#`, or `null` when there is no `#`.
     * Exactly what `android.net.Uri.parse(link).encodedFragment` returns
     * (`StringUri` takes `substring(indexOf('#') + 1)`); hand-written here
     * so the parser stays plain-JVM. Never the *decoded* fragment.
     */
    fun encodedFragment(link: String): String? {
        val i = link.indexOf('#')
        return if (i < 0) null else link.substring(i + 1)
    }

    /**
     * `roboco://pair#url=<pct-encoded pairing URL>` → the URL, decoded
     * **once** (139's steps 1–5):
     *
     * 1. the encoded fragment (everything after the first `#`), `null`
     *    without a `#`;
     * 2. split on `&`; the **first** part starting with `url=` (exact,
     *    case-sensitive key) wins, prefix removed; none → `null`;
     * 3. decode once with 36's `percentDecode(plusAsSpace = false)` — `+`
     *    stays `+`, a malformed `%` escape → `null`;
     * 4. `trim()`; empty → `null`;
     * 5. any `isWhitespace()`/`isISOControl()` char → `null` (WHATWG
     *    parsing would silently drop an inner tab/LF, but a single-line
     *    field must not be filled with a hidden newline).
     *
     * No scheme/host/prefix gate here: the router (36) does it for deep
     * links, the QR side ([decodePairLink]) for scans.
     */
    fun urlFromDeepLink(link: String): String? {
        val fragment = encodedFragment(link) ?: return null
        var value: String? = null
        for (part in fragment.split('&')) {
            if (part.startsWith("url=")) {
                value = part.removePrefix("url=")
                break
            }
        }
        val raw = value ?: return null
        val decoded = percentDecode(raw, plusAsSpace = false) ?: return null
        val trimmed = decoded.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return null
        return trimmed
    }
}
