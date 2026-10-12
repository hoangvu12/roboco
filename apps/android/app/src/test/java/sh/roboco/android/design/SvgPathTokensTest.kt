package sh.roboco.android.design

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The packed-arc-flag normaliser (android-ui-parity #13), pinned against the
 * examples and token counts computed with a Python port of the same rules in
 * the ticket. The counts fail loudly if the tokeniser's number/flag rules
 * drift — PathParser misparses silently otherwise.
 */
class SvgPathTokensTest {

    @Test
    fun secondDotStartsANewNumber() {
        // "1.5.5" is two numbers; packed flags "01" split into 0, 1.
        assertEquals("a .54 .54 0 0 1 .148 .026", SvgPathTokens.normalize("a.54.54 0 01.148.026"))
    }

    @Test
    fun exponentsAndAStrippedSign() {
        assertEquals("M 1.5 .5 L -2e-3 4 z", SvgPathTokens.normalize("M1.5.5L-2e-3,4z"))
    }

    @Test
    fun repeatedImplicitArcFlagsSplit() {
        // The second arc continues implicitly: its args 4/5 are the packed "11".
        assertEquals(
            "a 1 1 0 0 1 2 2 1 1 0 1 1 3 3",
            SvgPathTokens.normalize("a1 1 0 0 1 2 2 1 1 0 11 3 3"),
        )
    }

    @Test
    fun implicitMovetoContinuations() {
        assertEquals(
            "m 50.228 170.321 50.357 -28.257 .843 -2.463",
            SvgPathTokens.normalize("m50.228 170.321 50.357-28.257.843-2.463"),
        )
    }

    @Test
    fun generatedMarkTokenCounts() {
        val expected = mapOf(
            "claude" to 342,
            "openai" to 366,
            "cursor" to 108,
            "devin" to 204,
            "grok" to 123,
            "hermes" to 5032,
            "pi" to 38,
            "opencode" to 24,
            "antigravity" to 43,
        )
        for ((name, count) in expected) {
            val d = when (name) {
                "claude" -> CLAUDE_PATH_DATA
                "openai" -> OPENAI_PATH_DATA
                "cursor" -> CURSOR_PATH_DATA
                "devin" -> DEVIN_PATH_DATA
                "grok" -> GROK_PATH_DATA
                "hermes" -> HERMES_PATH_DATA
                "pi" -> PI_PATH_DATA
                "opencode" -> OPENCODE_PATH_DATA
                else -> ANTIGRAVITY_PATH_DATA
            }
            assertEquals("$name token count", count, SvgPathTokens.normalize(d).split(' ').size)
        }
    }
}
