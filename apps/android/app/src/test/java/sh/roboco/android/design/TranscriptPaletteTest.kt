package sh.roboco.android.design

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import uniffi.roboco_core.ColorRole

/**
 * Every ARGB value of the transcript palette table
 * (`ios:Design/Palette.swift:49-96`, via `.scratch/android-ui-parity/issues/02`).
 *
 * The expected values are explicit literals from the ticket's Spec table —
 * never recomputed through [TranscriptPaletteTables.argb] — so a quantisation
 * mistake in the table cannot make the test agree with itself. The literals
 * were also re-derived mechanically from the Swift source (round-half-up
 * alpha) when the ticket was written.
 *
 * Plain JUnit 4, no Robolectric: `ColorRole` is a plain Kotlin enum whose
 * class initialisation touches no JNA and no `android.os.Build`; only the
 * generated file's other classes do.
 */
class TranscriptPaletteTest {

    // ---------------------------------------------------------------------
    // The Rust declaration order, mirrored. `ColorRole.ordinal` is the Rust
    // declaration index (the UniFFI converter writes `ordinal + 1` on the
    // wire). Whoever adds a role to crates/mobile/src/layout/display.rs
    // (upstream: crates/mobile/src/layout/display.rs:10-65) must update this
    // list, the table in TranscriptPalette.kt and this file together: a new
    // role with no `when` branch fails compilation (no `else`), and a
    // renamed or reordered role fails `ordinalsMatchRust`.
    // ---------------------------------------------------------------------
    private val rustOrder = listOf(
        "TEXT", "TEXT_SECONDARY", "TEXT_TERTIARY", "LINK", "ACCENT",
        "DANGER", "SUCCESS", "WARNING",
        "INLINE_CODE_TEXT", "INLINE_CODE_BACKGROUND", "CODE_TEXT",
        "CODE_BACKGROUND", "CODE_BORDER",
        "QUOTE_BAR", "RULE", "TABLE_BORDER", "TABLE_HEADER_BACKGROUND",
        "USER_BUBBLE", "CHIP_BACKGROUND",
        "SYNTAX_KEYWORD", "SYNTAX_STRING", "SYNTAX_COMMENT", "SYNTAX_NUMBER",
        "SYNTAX_FUNCTION", "SYNTAX_TYPE", "SYNTAX_CONSTANT", "SYNTAX_VARIABLE",
        "SYNTAX_PROPERTY", "SYNTAX_OPERATOR", "SYNTAX_PUNCTUATION",
        "SYNTAX_TAG", "SYNTAX_ATTRIBUTE", "SYNTAX_ESCAPE",
        "TEXT_FAINT", "TEXT_SOFT", "TOOL_RAIL", "TOOL_BADGE", "TOOL_WELL",
        "AGENT_CARD", "AGENT_CARD_BORDER", "AGENT_TILE",
        "DIFF_ADD_WASH", "DIFF_DEL_WASH", "DIFF_ADD_BAR", "DIFF_DEL_BAR",
        "DIFF_HUNK", "DANGER_WASH", "DANGER_SOFT",
    )

    @Test
    fun ordinalsMatchRust() {
        assertEquals(48, rustOrder.size)
        assertEquals(48, ColorRole.entries.size)
        assertEquals(rustOrder, ColorRole.entries.map { it.name })
    }

    // ---------------------------------------------------------------------
    // The full table. 48 Light ARGB then 48 Dark ARGB, indexed by
    // ColorRole.ordinal (see the ticket's Spec table).
    // ---------------------------------------------------------------------

    private val expectedLight = intArrayOf(
        0xFF27272C.toInt(), 0xFF62626A.toInt(), 0xFF97979F.toInt(),
        0xFF5B43E8.toInt(), 0xFF5B43E8.toInt(), 0xFFDC2626.toInt(),
        0xFF15803D.toInt(), 0xFFA16207.toInt(), 0xFF3F3F46.toInt(),
        0xFFE9E9ED.toInt(), 0xFF303035.toInt(), 0xFFFAFAFB.toInt(),
        0xFFE4E4E8.toInt(), 0x735B43E8.toInt(), 0xFFE2E2E6.toInt(),
        0xFFE2E2E6.toInt(), 0xFFF3F3F5.toInt(), 0xFFFFFFFF.toInt(),
        0xFFE7E7EB.toInt(), 0xFF5B43E8.toInt(), 0xFF15803D.toInt(),
        0xFF6B7280.toInt(), 0xFFA16207.toInt(), 0xFF2563EB.toInt(),
        0xFF7E22CE.toInt(), 0xFFA16207.toInt(), 0xFF303035.toInt(),
        0xFF0E7490.toInt(), 0xFF52525B.toInt(), 0xFF52525B.toInt(),
        0xFFBE185D.toInt(), 0xFFB91C1C.toInt(), 0xFF0E7490.toInt(),
        0xFF797981.toInt(), 0xD9303035.toInt(), 0x29000000.toInt(),
        0x0F000000.toInt(), 0x29FFFFFF.toInt(), 0x08000000.toInt(),
        0x18000000.toInt(), 0x14000000.toInt(), 0x0E15803D.toInt(),
        0x0EDC2626.toInt(), 0x8C15803D.toInt(), 0x8CDC2626.toInt(),
        0x125B43E8.toInt(), 0x14DC2626.toInt(), 0xFFB91C1C.toInt(),
    )

    private val expectedDark = intArrayOf(
        0xFFE8E8EA.toInt(), 0xFFA9A9AE.toInt(), 0xFF6B6B72.toInt(),
        0xFF8B7CF6.toInt(), 0xFF8B7CF6.toInt(), 0xFFF87171.toInt(),
        0xFF34D399.toInt(), 0xFFFACC15.toInt(), 0xFFDCDCE0.toInt(),
        0xFF1A1A1E.toInt(), 0xFFE8E8EA.toInt(), 0xFF0B0B0D.toInt(),
        0xFF1F1F23.toInt(), 0x738B7CF6.toInt(), 0xFF1E1E22.toInt(),
        0xFF232327.toInt(), 0xFF121215.toInt(), 0xFF19191C.toInt(),
        0xFF1C1C20.toInt(), 0xFF8B7CF6.toInt(), 0xFF34D399.toInt(),
        0xFF92929A.toInt(), 0xFFFACC15.toInt(), 0xFF60A5FA.toInt(),
        0xFFC084FC.toInt(), 0xFFFACC15.toInt(), 0xFFE8E8EA.toInt(),
        0xFF22D3EE.toInt(), 0xFFA1A1AA.toInt(), 0xFFA1A1AA.toInt(),
        0xFFF472B6.toInt(), 0xFFF87171.toInt(), 0xFF22D3EE.toInt(),
        0xFF85858A.toInt(), 0xD9E8E8EA.toInt(), 0x1FFFFFFF.toInt(),
        0x0FFFFFFF.toInt(), 0x29000000.toInt(), 0x08FFFFFF.toInt(),
        0x12FFFFFF.toInt(), 0x14FFFFFF.toInt(), 0x0E34D399.toInt(),
        0x0EF87171.toInt(), 0x8C34D399.toInt(), 0x8CF87171.toInt(),
        0x148B7CF6.toInt(), 0x1FF87171.toInt(), 0xFFFCA5A5.toInt(),
    )

    @Test
    fun fullTableExact() {
        assertEquals(48, expectedLight.size)
        assertEquals(48, expectedDark.size)
        assertTable("light", TranscriptPalette(false), expectedLight)
        assertTable("dark", TranscriptPalette(true), expectedDark)
    }

    /** `contentEquals` with a failure message naming the role and both hexes. */
    private fun assertTable(appearance: String, palette: IntArray, expected: IntArray) {
        assertEquals("$appearance palette size", rustOrder.size, palette.size)
        for (i in expected.indices) {
            if (palette[i] != expected[i]) {
                val role = if (i < rustOrder.size) rustOrder[i] else "#$i"
                fail(
                    "$appearance ${role}: expected 0x" + "%08X".format(expected[i]) +
                        ", actual 0x" + "%08X".format(palette[i]),
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // Spot checks: the acceptance values, straight from the ticket.
    // ---------------------------------------------------------------------

    @Test
    fun spotChecks() {
        val light = TranscriptPalette(false)
        val dark = TranscriptPalette(true)

        // ToolWell is inverted: white @0.16 light, black @0.16 dark.
        assertEquals(0x29FFFFFF.toInt(), light.color(ColorRole.TOOL_WELL))
        assertEquals(0x29000000.toInt(), dark.color(ColorRole.TOOL_WELL))

        assertEquals(0xFFFFFFFF.toInt(), light.color(ColorRole.USER_BUBBLE))
        assertEquals(0xFF19191C.toInt(), dark.color(ColorRole.USER_BUBBLE))

        // QuoteBar: accent hexes @0.45 — no Material/dynamic accent anywhere.
        assertEquals(0x735B43E8.toInt(), light.color(ColorRole.QUOTE_BAR))
        assertEquals(0x738B7CF6.toInt(), dark.color(ColorRole.QUOTE_BAR))

        // ToolRail: alpha differs between appearances (0.162 / 0.12).
        assertEquals(0x29000000.toInt(), light.color(ColorRole.TOOL_RAIL))
        assertEquals(0x1FFFFFFF.toInt(), dark.color(ColorRole.TOOL_RAIL))

        // DiffHunk: alpha differs between appearances (0.07 / 0.08).
        assertEquals(0x125B43E8.toInt(), light.color(ColorRole.DIFF_HUNK))
        assertEquals(0x148B7CF6.toInt(), dark.color(ColorRole.DIFF_HUNK))

        // DangerWash (the error card, upstream Palette.swift:96): red @0.08 / @0.12.
        assertEquals(0x14DC2626.toInt(), light.color(ColorRole.DANGER_WASH))
        assertEquals(0x1FF87171.toInt(), dark.color(ColorRole.DANGER_WASH))
    }

    // ---------------------------------------------------------------------
    // The alpha quantisation rule: (alpha * 255 + 0.5).toInt(), round half
    // up. Truncation would give 24 / 30 / 14: only the 0.12 case is wrong
    // (30, one 8-bit step low; the correct value is 31 — the ticket's
    // Pitfall), but the rule is what keeps every alpha bit-for-bit equal to
    // Compose `Color.toArgb()`.
    // ---------------------------------------------------------------------

    @Test
    fun alphaRounding() {
        assertEquals(24, TranscriptPaletteTables.argb(0x000000, 0.0945f) ushr 24)
        assertEquals(31, TranscriptPaletteTables.argb(0x000000, 0.12f) ushr 24)
        assertEquals(14, TranscriptPaletteTables.argb(0x000000, 0.055f) ushr 24)
    }

    // ---------------------------------------------------------------------
    // Instances: two shared tables, one per appearance, never re-allocated.
    // ---------------------------------------------------------------------

    @Test
    fun sharedInstances() {
        assertSame(TranscriptPalette(true), TranscriptPalette(true))
        assertSame(TranscriptPalette(false), TranscriptPalette(false))
        assertNotSame(TranscriptPalette(false), TranscriptPalette(true))
    }

    // ---------------------------------------------------------------------
    // Roles that alias chrome roles must equal ticket 01's Palette, in both
    // appearances, bit-for-bit (the same round-half-up rule).
    // ---------------------------------------------------------------------

    @Test
    fun aliasesMatchChromePalette() {
        for (dark in listOf(false, true)) {
            val chrome = Palette.of(dark)
            val palette = TranscriptPalette(dark)
            assertEquals("$dark TEXT", chrome.text.toArgb(), palette.color(ColorRole.TEXT))
            assertEquals(
                "$dark TEXT_SECONDARY",
                chrome.secondary.toArgb(),
                palette.color(ColorRole.TEXT_SECONDARY),
            )
            assertEquals(
                "$dark TEXT_TERTIARY",
                chrome.tertiary.toArgb(),
                palette.color(ColorRole.TEXT_TERTIARY),
            )
            assertEquals("$dark LINK", chrome.accent.toArgb(), palette.color(ColorRole.LINK))
            assertEquals("$dark ACCENT", chrome.accent.toArgb(), palette.color(ColorRole.ACCENT))
            assertEquals("$dark DANGER", chrome.danger.toArgb(), palette.color(ColorRole.DANGER))
            assertEquals("$dark SUCCESS", chrome.success.toArgb(), palette.color(ColorRole.SUCCESS))
            assertEquals("$dark WARNING", chrome.warning.toArgb(), palette.color(ColorRole.WARNING))
            assertEquals(
                "$dark CODE_BACKGROUND",
                chrome.codeBackground.toArgb(),
                palette.color(ColorRole.CODE_BACKGROUND),
            )
            assertEquals(
                "$dark CODE_BORDER",
                chrome.codeBorder.toArgb(),
                palette.color(ColorRole.CODE_BORDER),
            )
            assertEquals("$dark RULE", chrome.hairline.toArgb(), palette.color(ColorRole.RULE))
            assertEquals(
                "$dark USER_BUBBLE",
                chrome.userBubble.toArgb(),
                palette.color(ColorRole.USER_BUBBLE),
            )
            assertEquals(
                "$dark CHIP_BACKGROUND",
                chrome.chip.toArgb(),
                palette.color(ColorRole.CHIP_BACKGROUND),
            )
        }
    }
}
