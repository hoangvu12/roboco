package sh.roboco.android.design

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Motion tokens (ticket 04) are the single source of curves, springs and
 * durations. Nothing under `src/main/java` may bypass them:
 *
 *  - `FastOutSlowInEasing` & co are Compose's curves, not iOS ones;
 *  - `Spring.Stiffness*` / `Spring.DampingRatio*` are not iOS values;
 *  - Compose's own `Ease*` constants (some share our control points, some are
 *    bezier approximations) must not be used — `Motion.Ease*` is the one
 *    source. This also catches a bare `EaseOut` silently resolving to
 *    Compose's constant under `import androidx.compose.animation.core.*`;
 *  - `tween()` without `easing =` silently uses `FastOutSlowInEasing`;
 *  - `spring()` without `stiffness` silently uses 1500.
 *
 * The scan is a heuristic, not a Kotlin parser: it walks every `.kt` under
 * `src/main/java` (the module directory, which is the working directory for
 * Gradle unit tests), ignores `//` and block/KDoc comments plus member calls
 * and declarations (`X.spring()`, `fun spring()`) for the call checks, and
 * takes call arguments by paren counting so multi-line calls work. `Motion.kt`
 * itself is exempt from the name checks (it defines the tokens) but not from
 * the call checks — the token file must pass `easing`/`stiffness` explicitly
 * like everything else.
 */
class NoPlatformEasingTest {

    private val root = File("src/main/java")

    @Test
    fun noPlatformEasingsOrDefaultedSpecsOutsideMotionTokens() {
        assertTrue("src/main/java must exist (unit tests run from the app module)", root.isDirectory)
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("no Kotlin sources found under src/main/java", files.isNotEmpty())

        val problems = mutableListOf<String>()
        for (file in files) {
            val text = file.readText()
            val where = file.relativeTo(root).invariantSeparatorsPath
            val isTokenFile = file.name == "Motion.kt"

            if (!isTokenFile) {
                for (name in PLATFORM_EASINGS) {
                    if (name in text) problems += "$where: $name is a Compose curve, use the Motion tokens"
                }
                if ("Spring.Stiffness" in text || "Spring.DampingRatio" in text) {
                    problems += "$where: Spring.Stiffness/DampingRatio are not iOS values, use IosSpring/Motion springs"
                }
                for (match in EASE_IMPORT.findAll(text)) {
                    val imported = match.value.substringAfterLast('.')
                    if (imported != "Easing") {
                        problems += "$where: imports $imported from animation.core, use Motion.Ease* tokens"
                    }
                }
                for (match in BARE_EASE.findAll(text)) {
                    problems += "$where: bare '${match.value}' resolves to Compose's constant under a " +
                        "wildcard import, write Motion.${match.value}"
                }
            }

            // Call checks apply to every file, Motion.kt included.
            val code = stripLineComments(text)
            forEachCallSpan(code, "tween") { span, offset ->
                if ("easing" !in span && "Motion." !in span) {
                    problems += "$where: tween call at offset $offset passes no easing " +
                        "(the default is FastOutSlowInEasing): ${span.lineSequence().firstOrNull() ?: ""}"
                }
            }
            forEachCallSpan(code, "spring") { span, offset ->
                if ("stiffness" !in span) {
                    problems += "$where: spring call at offset $offset passes no stiffness " +
                        "(the default is 1500): ${span.lineSequence().firstOrNull() ?: ""}"
                }
            }
        }
        assertTrue(
            "platform easings / defaulted specs found:\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    private companion object {
        private val PLATFORM_EASINGS = listOf(
            "FastOutSlowInEasing",
            "LinearOutSlowInEasing",
            "FastOutLinearInEasing",
        )
        private val EASE_IMPORT = Regex("""import\s+androidx\.compose\.animation\.core\.Ease\w*""")
        private val BARE_EASE = Regex("""(?<![\w.])Ease(In|Out|InOut|OutQuart|OutQuint|OutExpo)\b""")    }

    /**
     * Replaces each `/* … */` block comment (KDoc included) and each `//`
     * line comment with spaces, keeping line breaks, so offsets survive.
     * KDoc prose such as "the `Motion.ToastRise` spring (0.4 s / ζ 0.85)"
     * or "not a Compose `spring()`" is not a call.
     */
    private fun stripLineComments(text: String): String {
        // A small lexer, string-aware so "image/*" or "https://…" literals
        // do not open comments; Kotlin block comments nest.
        val out = StringBuilder(text.length)
        var i = 0
        fun blank(c: Char) { out.append(if (c == '\n') '\n' else ' ') }
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
            when {
                c == '/' && next == '/' -> {
                    while (i < text.length && text[i] != '\n') { blank(text[i]); i++ }
                }
                c == '/' && next == '*' -> {
                    var depth = 0
                    while (i < text.length) {
                        if (text.startsWith("/*", i)) {
                            depth++; blank(text[i]); blank(text[i + 1]); i += 2
                        } else if (text.startsWith("*/", i)) {
                            depth--; blank(text[i]); blank(text[i + 1]); i += 2
                            if (depth == 0) break
                        } else {
                            blank(text[i]); i++
                        }
                    }
                }
                text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }
                    out.append(text, i, end); i = end
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != c && text[j] != '\n') {
                        if (text[j] == '\\') j++
                        j++
                    }
                    val end = minOf(j + 1, text.length)
                    out.append(text, i, end); i = end
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /**
     * Runs [action] for every `name(…)` call in [text], passing the argument
     * text from `(` through the matching `)` (paren counting, so multi-line
     * calls work) and the offset of `(`. Explicit type arguments
     * (`tween<Float>(…)`) are skipped. Unclosed calls run to end of text.
     */
    private inline fun forEachCallSpan(text: String, name: String, action: (span: String, offset: Int) -> Unit) {
        val pattern = Regex("""\b${Regex.escape(name)}(?:\s*<[^()]*>)?\s*\(""")
        var searchFrom = 0
        while (searchFrom < text.length) {
            val match = pattern.find(text, searchFrom) ?: break
            val open = match.range.last
            // `PageSheetDefaults.spring()` is a member call (our own token
            // accessor, not Compose's top-level `spring`), and `fun spring()`
            // declares one: neither is a defaulted Compose spec.
            val before = text.substring(0, match.range.first).trimEnd()
            if (before.endsWith('.') || before.endsWith("fun")) {
                searchFrom = open + 1
                continue
            }
            var depth = 0
            var i = open
            while (i < text.length) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                i++
            }
            val end = if (i < text.length) i + 1 else text.length
            action(text.substring(open, end), open)
            searchFrom = open + 1
        }
    }
}
