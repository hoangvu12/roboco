package sh.roboco.android.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * `forHarness` / `brandTint` / `resolveHarnessMark`, every row of the
 * ticket's mapping table (`ios:Design/BrandMarks.swift:62-82`): Claude Code
 * and `mock` share the Claude mark and its orange, the other ids map to their
 * own marks and take the caller's tint, and a null/empty harness draws
 * nothing.
 */
class BrandMarkMappingTest {

    private val orange = Color(0xFFD97757)

    private fun assertRow(harness: String, expectedMark: BrandMark, expectedTint: Color?) {
        assertSame("forHarness(\"$harness\")", expectedMark, BrandMark.forHarness(harness))
        val tint = BrandMark.brandTint(harness)
        if (expectedTint == null) {
            assertNull("brandTint(\"$harness\") should be null (caller's tint)", tint)
        } else {
            assertEquals("brandTint(\"$harness\")", expectedTint.toArgb(), tint!!.toArgb())
        }
    }

    @Test
    fun mappingTable() {
        assertRow("claude-code", BrandMark.CLAUDE, orange)
        assertRow("mock", BrandMark.CLAUDE, orange)
        assertRow("codex", BrandMark.OPENAI, null)
        assertRow("cursor", BrandMark.CURSOR, null)
        assertRow("devin", BrandMark.DEVIN, null)
        assertRow("grok", BrandMark.GROK, null)
        assertRow("hermes", BrandMark.HERMES, null)
        assertRow("pi", BrandMark.PI, null)
        assertRow("opencode", BrandMark.OPENCODE, null)
        assertRow("antigravity", BrandMark.ANTIGRAVITY, null)
    }

    @Test
    fun unknownHarnessIdGetsTheClaudeShapeButNoOrange() {
        assertRow("foo", BrandMark.CLAUDE, null)
        // Matched exactly and case-sensitively, as in Swift.
        assertRow("Claude-Code", BrandMark.CLAUDE, null)
        assertRow("CODEX", BrandMark.CLAUDE, null)
    }

    @Test
    fun emptyHarnessHasNoTint() {
        assertNull(BrandMark.brandTint(""))
    }

    @Test
    fun resolveKeepsClaudeOrangeAndPassesTheCallerTintOtherwise() {
        assertNull(resolveHarnessMark(null, Color.Red))
        assertNull(resolveHarnessMark("", Color.Red))

        val claude = resolveHarnessMark("claude-code", Color.Red)
        assertNotNull(claude)
        assertSame(BrandMark.CLAUDE, claude!!.first)
        assertEquals(0xFFD97757.toInt(), claude.second.toArgb())

        val codex = resolveHarnessMark("codex", Color.Red)
        assertNotNull(codex)
        assertSame(BrandMark.OPENAI, codex!!.first)
        assertEquals(Color.Red.toArgb(), codex.second.toArgb())
    }

    @Test
    fun painterIsNullOnlyForNullOrEmptyHarness() {
        assertNull(harnessMarkPainter(null, Color.Red))
        assertNull(harnessMarkPainter("", Color.Red))
        assertNotNull(harnessMarkPainter("codex", Color.Red))
        assertNotNull(harnessMarkPainter("claude-code", Color.Red))
    }
}
