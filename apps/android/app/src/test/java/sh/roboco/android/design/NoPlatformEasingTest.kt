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
 * Gradle unit tests), ignores `//` line comments for the call checks, and
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
        private val BARE_EASE = Regex("""(?<![\w.])Ease(In|Out|InOut|OutQuart|OutQuint|OutExpo)\b""")
    }

    /** Replaces each `//` line comment with spaces, so offsets and line breaks survive. */
    private fun stripLineComments(text: String): String =
        text.split('\n').joinToString("\n") { line ->
            val comment = line.indexOf("//")
            if (comment < 0) line
            else line.substring(0, comment) + " ".repeat(line.length - comment)
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
