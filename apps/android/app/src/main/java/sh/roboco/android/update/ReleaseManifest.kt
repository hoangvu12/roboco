package sh.roboco.android.update

import androidx.compose.runtime.Immutable
import kotlin.math.roundToLong

/**
 * The release feed's `manifest.json` (android-next#04) — the exact shape
 * `.github/workflows/release.yml` writes:
 *
 * ```json
 * {"version": "0.10.0", "date": "2026-10-20T13:37:00Z",
 *  "files": {"roboco-0.10.0-android-arm64-v8a.apk": {"sha256": "…", "size": 24530000}}}
 * ```
 *
 * `date` and `size` are this ticket's additions to the workflow (the
 * About row's "24.5 MB · Released Oct 20" subtitle); the desktop's serde
 * parser ignores unknown fields, and this parser ignores unknown keys at
 * every level, so older manifests (no `date`, no `size`) and newer ones
 * keep parsing. The JSON is read by [MiniJson] — a small hand-rolled
 * reader, because `org.json` is stubbed off Robolectric and the parser
 * must stay plain-JVM testable. Malformed input parses to `null`, never
 * throws.
 */
@Immutable
data class ReleaseManifest(
    /** The release version, as written by the workflow (no `v` prefix). */
    val version: String,
    /** The release's publication time, ISO-8601 UTC; null on old manifests. */
    val date: String?,
    /** Artifact basename → metadata. Empty for a manifest without `files`. */
    val files: Map<String, ApkAsset>,
) {

    /**
     * The metadata of the APK [abi] would install, or null when this
     * release has no asset for it (then the flow opens the releases
     * page instead of downloading).
     */
    fun apkFor(abi: String): ApkAsset? = files[apkAssetName(version, abi)]

    /** Parses `manifest.json`; null on malformed or version-less input. */
    companion object {
        fun parse(json: String): ReleaseManifest? = parseReleaseManifest(json)
    }
}

/** One artifact's row in the manifest's `files` map. */
@Immutable
data class ApkAsset(
    /** The artifact basename: the manifest key and the download URL's last segment. */
    val name: String,
    /** The SHA-256 the downloaded bytes must match; null when absent. */
    val sha256: String?,
    /** The artifact's size in bytes; null on old manifests. */
    val sizeBytes: Long?,
)

/**
 * `roboco-<ver>-android-<abi>.apk` — the release workflow's staging name
 * (`release.yml` "Verify and stage APKs" → "updater manifest"), with the
 * manifest's own version string (the tag without `v`).
 */
fun apkAssetName(version: String, abi: String): String = "roboco-$version-android-$abi.apk"

/**
 * Maps the device's primary ABI (`Build.SUPPORTED_ABIS[0]`) to the ABI of
 * the split APK the release publishes, or null when no split matches
 * (then the flow opens the releases page instead of downloading).
 */
fun assetAbi(deviceAbi: String): String? = when (deviceAbi) {
    "arm64-v8a", "x86_64" -> deviceAbi
    else -> null
}

/** Parses `manifest.json`; null on malformed or version-less input. */
fun parseReleaseManifest(json: String): ReleaseManifest? {
    val root = MiniJson.parse(json) as? MiniJson.Obj ?: return null
    val version = (root["version"] as? MiniJson.Str)?.value?.takeIf { it.isNotBlank() } ?: return null
    val date = (root["date"] as? MiniJson.Str)?.value
    val files = when (val filesValue = root["files"]) {
        null, is MiniJson.Null -> emptyMap<String, ApkAsset>()
        is MiniJson.Obj -> buildMap<String, ApkAsset> {
            for ((name, entry) in filesValue.values) {
                val file = entry as? MiniJson.Obj ?: continue
                put(
                    name,
                    ApkAsset(
                        name = name,
                        sha256 = (file["sha256"] as? MiniJson.Str)?.value,
                        sizeBytes = (file["size"] as? MiniJson.Num)?.asLong(),
                    ),
                )
            }
        }
        else -> return null
    }
    return ReleaseManifest(version = version, date = date, files = files)
}

// ---------------------------------------------------------------------------
// A minimal JSON reader
// ---------------------------------------------------------------------------

/**
 * A tiny strict JSON reader for the updater manifest — objects, arrays,
 * strings (with escapes), numbers, booleans and null, recursing into
 * values it does not know (unknown keys are ignored by the caller). It
 * exists only because `org.json` cannot run in plain-JVM unit tests; the
 * manifest is a sub-kilobyte document, so the reader is sized for it and
 * nothing else. Returns null on the first malformed token.
 */
internal object MiniJson {

    internal sealed interface Value

    internal object Null : Value

    internal data class Bool(val value: Boolean) : Value

    internal data class Num(private val raw: Double) : Value {
        /** The value rounded to a whole number (sizes); null when not finite. */
        fun asLong(): Long? = if (raw.isFinite()) raw.roundToLong() else null
    }

    internal data class Str(val value: String) : Value

    internal data class Arr(val values: List<Value>) : Value

    internal class Obj(val values: Map<String, Value>) : Value {
        operator fun get(key: String): Value? = values[key]
    }

    /** Parses one JSON document; null on malformed input. */
    fun parse(text: String): Value? {
        val p = Parser(text)
        val value = p.parseValue() ?: return null
        p.skipWhitespace()
        if (!p.atEnd()) return null
        return value
    }

    private class Parser(private val text: String) {
        private var i = 0

        fun atEnd(): Boolean = i >= text.length

        fun skipWhitespace() {
            while (i < text.length && text[i].isWhitespace()) i++
        }

        fun parseValue(): Value? {
            skipWhitespace()
            if (atEnd()) return null
            return when (val c = text[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()?.let { Str(it) }
                't' -> literal("true")?.let { Bool(true) }
                'f' -> literal("false")?.let { Bool(false) }
                'n' -> literal("null")?.let { Null }
                else -> if (c == '-' || c.isDigit()) parseNumber() else null
            }
        }

        private fun literal(word: String): Boolean? {
            if (!text.startsWith(word, i)) return null
            i += word.length
            return true
        }

        private fun parseObject(): Obj? {
            i++ // '{'
            val map = LinkedHashMap<String, Value>()
            skipWhitespace()
            if (!atEnd() && text[i] == '}') {
                i++
                return Obj(map)
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || text[i] != '"') return null
                val key = parseString() ?: return null
                skipWhitespace()
                if (atEnd() || text[i] != ':') return null
                i++
                val value = parseValue() ?: return null
                map[key] = value
                skipWhitespace()
                if (atEnd()) return null
                when (text[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return Obj(map)
                    }
                    else -> return null
                }
            }
        }

        private fun parseArray(): Arr? {
            i++ // '['
            val list = ArrayList<Value>()
            skipWhitespace()
            if (!atEnd() && text[i] == ']') {
                i++
                return Arr(list)
            }
            while (true) {
                val value = parseValue() ?: return null
                list.add(value)
                skipWhitespace()
                if (atEnd()) return null
                when (text[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return Arr(list)
                    }
                    else -> return null
                }
            }
        }

        private fun parseString(): String? {
            i++ // '"'
            val sb = StringBuilder()
            while (!atEnd()) {
                when (val c = text[i]) {
                    '"' -> {
                        i++
                        return sb.toString()
                    }
                    '\\' -> {
                        i++
                        if (atEnd()) return null
                        when (val e = text[i]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= text.length) return null
                                val hex = text.substring(i + 1, i + 5)
                                // toInt(16) throws on a malformed escape;
                                // a failed parse is a failed document.
                                val code = runCatching { hex.toInt(16) }.getOrNull()
                                    ?: return null
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> return null
                        }
                        i++
                    }
                    else -> {
                        if (c < ' ') return null
                        sb.append(c)
                        i++
                    }
                }
            }
            return null
        }

        private fun parseNumber(): Num? {
            val start = i
            if (!atEnd() && text[i] == '-') i++
            while (!atEnd() && text[i].isDigit()) i++
            if (!atEnd() && text[i] == '.') {
                i++
                if (atEnd() || !text[i].isDigit()) return null
                while (!atEnd() && text[i].isDigit()) i++
            }
            if (!atEnd() && (text[i] == 'e' || text[i] == 'E')) {
                i++
                if (!atEnd() && (text[i] == '+' || text[i] == '-')) i++
                if (atEnd() || !text[i].isDigit()) return null
                while (!atEnd() && text[i].isDigit()) i++
            }
            if (i == start) return null
            return text.substring(start, i).toDoubleOrNull()?.let { Num(it) }
        }
    }
}
