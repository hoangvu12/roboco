package sh.roboco.android.parity

import java.io.File
import java.security.MessageDigest

/**
 * The parity gate's shared file plumbing (android-app#30): where the
 * repo, the goldens and the manifest live, resolved the same way for
 * G2–G4. The `roboco.repoRoot` system property is set for every `Test`
 * task by `apps/android/app/build.gradle.kts`.
 */
internal object ParityPaths {

    val repoRoot: File
        get() = File(checkNotNull(System.getProperty("roboco.repoRoot")) {
            "roboco.repoRoot must be set (the Gradle Test tasks set it)"
        })

    /** `apps/android/parity/`. */
    val parityDir: File get() = repoRoot.resolve("apps/android/parity")

    /** `apps/android/parity/manifest.json`. */
    val manifest: File get() = parityDir.resolve("manifest.json")

    /** `apps/android/parity/ios/`. */
    val iosDir: File get() = parityDir.resolve("ios")

    /** `apps/android/parity/CHECKLIST.md`. */
    val checklist: File get() = parityDir.resolve("CHECKLIST.md")

    /** `apps/android/app/src/test/screenshots/` — the committed goldens. */
    val goldensDir: File
        get() = repoRoot.resolve("apps/android/app/src/test/screenshots")

    /** Every committed golden, recursive, path relative to [goldensDir] (invariant separators). */
    fun goldens(): List<String> =
        goldensDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            .map { it.relativeTo(goldensDir).invariantSeparatorsPath }
            .sorted()
            .toList()

    /** A golden file by its manifest path (relative to the goldens root). */
    fun golden(path: String): File = goldensDir.resolve(path)

    /** An iOS reference by its manifest path (relative to `parity/ios/`). */
    fun iosRef(path: String): File = iosDir.resolve(path)

    /** SHA-256 of a file's bytes, lowercase hex. */
    fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
}

/**
 * The manifest's entry — the G2 shape of `apps/android/parity/manifest.json`.
 * `reviewedSha256` null means the golden is recorded but **not yet signed
 * off** (or not recorded at all): both fail [ParityManifestTest].
 */
internal data class ParityEntry(
    val golden: String,
    val iosRef: String?,
    val adaptation: String?,
    val reviewedSha256: String?,
    val reviewer: String?,
    val date: String?,
    val checklistItems: List<String>,
    val notes: String?,
    val ssimFloor: Double?,
)

/**
 * A minimal JSON parser for the parity manifest — plain JVM (no org.json:
 * the unit-test classpath's android.jar stubs would throw "not mocked"),
 * no new dependencies. The manifest is an authored, well-formed file;
 * anything unexpected fails loudly, which is right for a gate's input.
 */
internal object ParityJson {

    fun parseManifest(text: String): List<ParityEntry> {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.expectEnd()
        val array = value as? List<*> ?: error("manifest root must be an array")
        return array.map { item ->
            val obj = item as? Map<*, *> ?: error("manifest entries must be objects")
            fun string(key: String): String? = obj[key] as? String
            ParityEntry(
                golden = string("golden") ?: error("manifest entry without a golden"),
                iosRef = string("iosRef"),
                adaptation = string("adaptation"),
                reviewedSha256 = string("reviewedSha256"),
                reviewer = string("reviewer"),
                date = string("date"),
                checklistItems = (obj["checklistItems"] as? List<*>)
                    ?.mapNotNull { it as? String }
                    .orEmpty(),
                notes = string("notes"),
                ssimFloor = when (val v = obj["ssimFloor"]) {
                    null, is String -> null
                    is Double -> v
                    is Int -> v.toDouble()
                    is Long -> v.toDouble()
                    else -> error("ssimFloor must be a number or null")
                },
            )
        }
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun parseValue(): Any? {
            skipWhitespace()
            return when (peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> parseNumber()
            }
        }

        fun expectEnd() {
            skipWhitespace()
            check(pos == text.length) { "trailing characters at $pos in the manifest" }
        }

        private fun literal(word: String, value: Any?): Any? {
            check(text.regionMatches(pos, word, 0, word.length)) { "bad literal at $pos" }
            pos += word.length
            return value
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                map[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> error("expected ',' or '}' at $pos in the manifest")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> error("expected ',' or ']' at $pos in the manifest")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                when (val c = next()) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = next()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            val hex = text.substring(pos, pos + 4)
                            pos += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> error("bad escape \\$e at $pos in the manifest")
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = pos
            while (pos < text.length && "+-0123456789.eE".indexOf(text[pos]) >= 0) pos++
            val raw = text.substring(start, pos)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: error("bad number at $start in the manifest")
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) pos++
        }

        private fun peek(): Char {
            check(pos < text.length) { "unexpected end of the manifest" }
            return text[pos]
        }

        private fun next(): Char {
            val c = peek()
            pos++
            return c
        }

        private fun expect(c: Char) {
            check(peek() == c) { "expected '$c' at $pos in the manifest" }
            pos++
        }
    }
}
