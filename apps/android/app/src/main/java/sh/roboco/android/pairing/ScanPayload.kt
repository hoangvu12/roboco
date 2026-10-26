package sh.roboco.android.pairing

import java.util.Locale

/**
 * What a decoded QR payload is (android-ui-parity #138). `Pairing` holds
 * the token or the room secret — never log it, never `toString()` a verdict.
 */
sealed interface ScanVerdict {

    /** Blank (after trim): nothing happens, not even a caption flash. */
    data object Ignore : ScanVerdict

    /** Any payload that is not a pairing link: the 2 s foreign caption. */
    data object Foreign : ScanVerdict

    /**
     * A pairing URL, a Tailcat invite (redeemed through the in-process
     * Tailcat tunnel, android-next#02), a room invite (ADR 0011, ticket 04
     * — the Pair page joins it: scan once, reach every member) or an
     * unwrapped `roboco://pair#url=…` — accept, close, auto-submit.
     */
    data class Pairing(val url: String) : ScanVerdict
}

/**
 * Classifies a decoded QR payload (the ticket's `classifyScan` table, rows
 * 1–5 with 2b, in order):
 *
 * 1. `raw.trim()` empty → [ScanVerdict.Ignore];
 * 2. starts with `roboco:` (ASCII case-insensitive) → `decodePairLink`; a
 *    null unwrap is [ScanVerdict.Foreign], otherwise continue at row 3 with
 *    the unwrapped string trimmed (one level only — a nested
 *    `roboco://` is not unwrapped again);
 * 2b. any `isWhitespace()`/`isISOControl()` char after the trim/unwrap →
 *    [ScanVerdict.Foreign] (a hidden newline must not fill the field);
 * 3. starts with `roboco-tailcat:` (case-sensitive, Rust's
 *    `INVITE_PREFIX`, `crates/proto/src/tailcat.rs`) →
 *    [ScanVerdict.Pairing] — the Pair page pairs it over Tailcat
 *    (android-next#02);
 * 3b. starts with `roboco-room:` (case-sensitive, Rust's
 *    `INVITE_PREFIX`, `crates/proto/src/room.rs`) →
 *    [ScanVerdict.Pairing] — the Pair page joins the room (ticket 04);
 * 4. [isPairingUrl] (ticket 72's `PairingBackend.parse` != null) →
 *    [ScanVerdict.Pairing];
 * 5. otherwise → [ScanVerdict.Foreign].
 *
 * [isPairingUrl] runs the Rust parser: call this off the main thread (the
 * activity runs it on `qr-analysis`).
 */
fun classifyScan(raw: String, isPairingUrl: (String) -> Boolean): ScanVerdict {
    var s = raw.trim()
    if (s.isEmpty()) return ScanVerdict.Ignore
    if (asciiStartsWithIgnoreCase(s, "roboco:")) {
        val unwrapped = decodePairLink(s) ?: return ScanVerdict.Foreign
        s = unwrapped.trim()
    }
    if (s.any { it.isWhitespace() || it.isISOControl() }) return ScanVerdict.Foreign
    if (s.startsWith("roboco-tailcat:")) return ScanVerdict.Pairing(s)
    if (s.startsWith("roboco-room:")) return ScanVerdict.Pairing(s)
    if (isPairingUrl(s)) return ScanVerdict.Pairing(s)
    return ScanVerdict.Foreign
}

/**
 * `roboco://pair[/]#url=<pct-encoded pairing URL>` → the decoded URL, or
 * `null`. The prefix gate (before the first `#`, compared ASCII
 * case-insensitively against `roboco://pair` / `roboco://pair/`) is this
 * ticket's addition — for deep links 36's router does the equivalent
 * "host is `pair`" check; the decoding itself is [PairingLinks.urlFromDeepLink].
 */
fun decodePairLink(link: String): String? {
    val i = link.indexOf('#')
    if (i < 0) return null
    val head = link.substring(0, i).lowercase(Locale.ROOT)
    if (head != "roboco://pair" && head != "roboco://pair/") return null
    return PairingLinks.urlFromDeepLink(link)
}

/**
 * The foreign caption's span — and [RejectDebouncer]'s window: one flash
 * per sighting, reset after this long (android-app#18's 2 s).
 */
internal const val FOREIGN_CAPTION_MS = 2000L

/**
 * One flash per continuous sighting of the same foreign payload (the
 * ticket's debounce reading, window 2000 ms — the same span as the foreign
 * caption): `shouldShow(payload, now)` is true when the payload differs
 * from the last one, or when it has been out of view for ≥ [windowMs];
 * every call stores the payload and the time. A valid pairing payload
 * never goes through this. One thread only (the analysis thread) — no
 * synchronization.
 */
class RejectDebouncer(private val windowMs: Long = FOREIGN_CAPTION_MS) {

    private var last: String? = null
    private var lastSeen = 0L

    fun shouldShow(payload: String, nowMs: Long): Boolean {
        val show = payload != last || nowMs - lastSeen >= windowMs
        last = payload
        lastSeen = nowMs
        return show
    }
}

/**
 * `s.startsWith(prefix)` with ASCII-only case folding (`roboco:` in any
 * case); `String.lowercase()` is Unicode-aware and folds chars like the
 * Kelvin sign into ASCII, which the grammar does not.
 */
private fun asciiStartsWithIgnoreCase(s: String, prefix: String): Boolean {
    if (s.length < prefix.length) return false
    for (i in prefix.indices) {
        val c = s[i]
        val p = prefix[i]
        val cl = if (c in 'A'..'Z') c + 32 else c
        val pl = if (p in 'A'..'Z') p + 32 else p
        if (cl != pl) return false
    }
    return true
}
