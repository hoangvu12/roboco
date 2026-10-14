package sh.roboco.android.shell

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale

/**
 * One inbound link (android-ui-parity #36), parsed from a `roboco://` URI
 * or the `route` launch extra (iOS's `-route`). The four navigation kinds
 * map one-to-one onto [AppRouter] calls; [Pair] goes to the pairing
 * handler ticket 139 registers, never to the router.
 *
 * Pure Kotlin — no `android.*` — so the grammar is plain-JVM-testable.
 */
sealed interface DeepLink {

    /** `roboco://chat/<id>` / `route chat:<id>` — `router.openSession`. */
    data class OpenChat(val chatId: String) : DeepLink

    /**
     * `roboco://new[?prompt=…]` / `route new` — `router.presentNewSession`.
     * The prompt only **pre-fills** the composer (iOS
     * `NewSessionViewController.swift:78`: `composer.text = prompt ?? …`);
     * it is never sent automatically.
     */
    data class NewSession(val prompt: String?) : DeepLink

    /**
     * `roboco://settings` / `route more` — `router.showSettings`. `more`
     * is the Settings tab's iOS identifier (`MainTabController.swift:27`);
     * pr609's `settings` alias is dropped.
     */
    data object Settings : DeepLink

    /** `roboco://search` / `route search` — `router.showSearch`. */
    data object Search : DeepLink

    /**
     * The whole trimmed `roboco://pair…` string; ticket 139 decodes and
     * validates the `#url=` fragment. **Never log it** — the fragment
     * carries the pairing token — so the data class's generated
     * `toString()` is overridden (139): any generic log line that
     * interpolates a `DeepLink` would otherwise print the token.
     */
    data class Pair(val link: String) : DeepLink {
        override fun toString(): String = "Pair(<redacted>)"
    }
}

/**
 * The `roboco://` deep-link grammar and the `route` extra grammar
 * (android-ui-parity #36), hand-written over the raw
 * `Intent.getDataString()` — never `android.net.Uri` (Robolectric-bound,
 * and its `getQueryParameter` `+` handling has varied by API level).
 *
 * Rules: scheme and host compare case-insensitively while the chat id and
 * the prompt keep their case; the query (`?…`) and the fragment (`#…`)
 * are split off before the path is checked; "path empty" means the path
 * is `""` or `"/"`. Unknown links return null — ignored by the caller and
 * logged there (this file has no `android.*` dependency to log with).
 */
object DeepLinks {

    const val SCHEME = "roboco"

    /**
     * `Intent.dataString` → link, or null when it isn't one we route:
     * `roboco://voice`, `roboco://more`, the desktop's
     * `roboco://open/chat/<id>?workspace=…`, other schemes, a missing
     * `://`, and every malformed chat path. A null result is ignored,
     * never queued.
     */
    fun parse(uri: String?): DeepLink? {
        val s = uri?.trim() ?: return null
        val separator = s.indexOf("://")
        if (separator <= 0) return null
        val scheme = s.substring(0, separator)
        if (!scheme.equals(SCHEME, ignoreCase = true)) return null
        val rest = s.substring(separator + 3)
        // The fragment is split off first (pair links carry it), then the
        // query — the path is checked against whatever is left.
        val hashAt = rest.indexOf('#')
        val beforeFragment = if (hashAt >= 0) rest.substring(0, hashAt) else rest
        val queryAt = beforeFragment.indexOf('?')
        val query = if (queryAt >= 0) beforeFragment.substring(queryAt + 1) else null
        val hostAndPath = if (queryAt >= 0) beforeFragment.substring(0, queryAt) else beforeFragment
        val slash = hostAndPath.indexOf('/')
        val host = (if (slash >= 0) hostAndPath.substring(0, slash) else hostAndPath)
            .lowercase(Locale.ROOT)
        val path = if (slash >= 0) hostAndPath.substring(slash) else ""
        val pathEmpty = path == "" || path == "/"
        return when (host) {
            "chat" -> {
                // `/<seg>` or `/<seg>/` with a non-empty seg that decodes
                // without a slash (`a%2Fb` is rejected, like a literal one).
                val segment = path.removePrefix("/").removeSuffix("/")
                if (segment.isEmpty() || '/' in segment) return null
                val id = percentDecode(segment, plusAsSpace = false) ?: return null
                if (id.isEmpty() || '/' in id) return null
                DeepLink.OpenChat(id)
            }

            "new" -> {
                if (!pathEmpty) return null
                // The **first** `prompt=` parameter, decoded form-style
                // (`+` → space, `%XX` → UTF-8 bytes). Blank → null, a
                // malformed escape → null (the sheet still opens); a
                // non-blank prompt is kept verbatim — not trimmed,
                // newlines kept.
                val raw = query
                    ?.split('&')
                    ?.firstOrNull { it.startsWith("prompt=") }
                    ?.removePrefix("prompt=")
                val prompt = raw
                    ?.let { percentDecode(it, plusAsSpace = true) }
                    ?.takeIf { it.isNotBlank() }
                DeepLink.NewSession(prompt)
            }

            "settings" -> if (pathEmpty) DeepLink.Settings else null

            "search" -> if (pathEmpty) DeepLink.Search else null

            "pair" -> DeepLink.Pair(s)

            else -> null
        }
    }

    /**
     * The `route` extra — iOS's `-route` (`SceneDelegate.swift:64-72`):
     * exact, case-sensitive, not trimmed, ungated. `chat:<id>` takes
     * everything after the first 5 chars verbatim (no percent-decoding,
     * like iOS `dropFirst(5)`); `new` / `more` / `search` are exact
     * matches. Everything else — `chat:` (empty id), `settings`, `New`,
     * `pair`, `""`, non-strings — is null.
     */
    fun parseRoute(route: String?): DeepLink? {
        if (route == null) return null
        if (route.startsWith("chat:")) {
            val id = route.substring(5)
            if (id.isEmpty()) return null
            return DeepLink.OpenChat(id)
        }
        return when (route) {
            "new" -> DeepLink.NewSession(null)
            "more" -> DeepLink.Settings
            "search" -> DeepLink.Search
            else -> null
        }
    }
}

/**
 * `%XX` → bytes → UTF-8; `plusAsSpace` for query values (form decoding,
 * `+` → space). Null on a malformed escape (`%G1`, a trailing `%`);
 * invalid UTF-8 becomes U+FFFD and is accepted. Hand-written, not
 * `java.net.URLDecoder`: that always maps `+` and throws on malformed
 * escapes.
 */
internal fun percentDecode(s: String, plusAsSpace: Boolean): String? {
    val out = ByteArrayOutputStream()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '+' && plusAsSpace -> {
                out.write(0x20)
                i++
            }

            c == '%' -> {
                val high = hexDigitAt(s, i + 1) ?: return null
                val low = hexDigitAt(s, i + 2) ?: return null
                out.write((high shl 4) or low)
                i += 3
            }

            else -> {
                // Encode the whole run of plain chars at once so a
                // surrogate pair never splits into two replacement chars.
                var j = i
                while (j < s.length && s[j] != '%' && !(plusAsSpace && s[j] == '+')) j++
                out.write(s.substring(i, j).toByteArray(Charsets.UTF_8))
                i = j
            }
        }
    }
    return String(out.toByteArray(), Charsets.UTF_8)
}

/** The hex digit of [s] at [at] (0–15), or null when out of range or not hex. */
private fun hexDigitAt(s: String, at: Int): Int? {
    if (at < 0 || at >= s.length) return null
    return when (val c = s[at]) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }
}

/**
 * The engine-scoped session-id scheme (android-ui-parity #36) — the
 * Kotlin mirror of `ScopedId` in
 * `crates/engine-client/src/registry.rs:14-61`, which the phone's whole
 * workspace uses: `engine:v1:` + base64url-no-pad of
 * `["<engineKey>","<rawId>"]` (the `EngineKey` newtype serializes as its
 * bare string). The phone has no `local` engine (engine keys are
 * client-minted UUIDs), so every id the workspace holds is scoped.
 */
object ScopedIds {

    const val PREFIX = "engine:v1:"

    fun isScoped(id: String): Boolean = id.startsWith(PREFIX)

    /**
     * The engine's own chat id inside a scoped id, or null when the
     * prefix is missing, the base64url payload is invalid, the decoded
     * value is not exactly a 2-element JSON string array, or the engine
     * key is empty (Rust's `ScopedId::parse` rejects that too,
     * `engine_registry.rs:57-59`).
     */
    fun rawId(scoped: String): String? {
        if (!isScoped(scoped)) return null
        val bytes = runCatching {
            // The JDK URL decoder accepts unpadded input, matching Rust's
            // URL_SAFE_NO_PAD encode.
            Base64.getUrlDecoder().decode(scoped.substring(PREFIX.length))
        }.getOrNull() ?: return null
        val (engine, rawId) = parseScopedIdJson(String(bytes, Charsets.UTF_8)) ?: return null
        if (engine.isEmpty()) return null
        return rawId
    }
}

/**
 * A deep link's chat id → the id the router opens. A scoped id passes
 * through unchanged (even when not currently known — a just-created
 * chat). A raw engine id resolves through [known] exactly when it
 * matches exactly one scoped id (a raw id on two engines is refused, so
 * it can't misroute; zero matches passes the raw id through so the
 * router's open fails with the "Couldn't open session" toast).
 */
object ChatIds {

    fun resolve(id: String, known: Collection<String>): String {
        if (ScopedIds.isScoped(id)) return id
        val hits = known.filter { ScopedIds.rawId(it) == id }
        return if (hits.size == 1) hits[0] else id
    }
}

/**
 * Parses exactly `["<engine>","<rawId>"]` — the shape
 * `serde_json::to_vec(&(EngineKey, raw_id))` writes
 * (`engine_registry.rs:37-49`). No JSON library: the app ships none and
 * `org.json` is stubbed on the plain JVM. A string is `"` … `"` with the
 * escapes `\" \\ \/ \b \f \n \r \t \uXXXX`; any other shape → null.
 */
private fun parseScopedIdJson(json: String): kotlin.Pair<String, String>? {
    var i = 0

    fun skipWhitespace() {
        while (i < json.length && json[i].isWhitespace()) i++
    }

    fun readString(): String? {
        if (i >= json.length || json[i] != '"') return null
        i++
        val out = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            when (c) {
                '"' -> {
                    i++
                    return out.toString()
                }

                '\\' -> {
                    i++
                    if (i >= json.length) return null
                    when (json[i]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            var code = 0
                            for (offset in 1..4) {
                                val digit = hexDigitAt(json, i + offset) ?: return null
                                code = (code shl 4) or digit
                            }
                            i += 4
                            out.append(code.toChar())
                        }

                        else -> return null
                    }
                    i++
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return null // unterminated string
    }

    skipWhitespace()
    if (i >= json.length || json[i] != '[') return null
    i++
    skipWhitespace()
    val engine = readString() ?: return null
    skipWhitespace()
    if (i >= json.length || json[i] != ',') return null
    i++
    skipWhitespace()
    val rawId = readString() ?: return null
    skipWhitespace()
    if (i >= json.length || json[i] != ']') return null
    i++
    skipWhitespace()
    if (i != json.length) return null
    return engine to rawId
}
