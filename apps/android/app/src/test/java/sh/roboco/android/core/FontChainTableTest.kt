package sh.roboco.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.roboco_core.FaceRole

/**
 * `FontChain`'s pure tables (plain JVM, for the merge check): the wide
 * code-point table (upstream PR #621 verbatim), the double-width rule
 * that excludes the two Geist-covered marks, and the ligature settings
 * Rust's own measurer disables (`'liga' 0, 'clig' 0` — not #609's or
 * #621's `'calt'`). The android-dependent chain behaviour is
 * [FontChainTest] under Robolectric.
 */
class FontChainTableTest {

    private val wideTrue = listOf(
        0x1100, // Hangul Jamo lead
        0x115F, // last Hangul Jamo
        0x3042, // hiragana あ
        0x4E00, // CJK ideograph 一
        0xAC00, // Hangul syllable 가
        0xFF01, // fullwidth !
        0x1F600, // emoji in the 1F300..1F64F band
        0x1F92F, // emoji in the 1F900..1F9FF band
        0x20000, // CJK extension B
    )

    private val wideFalse = listOf(
        0x10FF, // just below Hangul Jamo
        0x1160, // Hangul Jamo filler: not wide
        0x303F, // just past 2E80..303E
        0x1F680, // transport emoji: deliberately not wide
        0x1F1EF, // regional indicator: not wide
        0x41, // 'A'
    )

    @Test
    fun isWideMatchesTheTable() {
        for (cp in wideTrue) {
            assertTrue("0x${cp.toString(16)} should be wide", FontChain.isWide(cp))
        }
        for (cp in wideFalse) {
            assertFalse("0x${cp.toString(16)} should not be wide", FontChain.isWide(cp))
        }
    }

    @Test
    fun isDoubleWidthIsIsWideMinusTheTwoGeistCoveredMarks() {
        // Geist Mono covers U+3003 (〃) and U+301C (〜) at a 1-cell advance,
        // so they are never doubled; everything else in the table is.
        assertFalse(FontChain.isDoubleWidth(0x3003))
        assertFalse(FontChain.isDoubleWidth(0x301C))
        for (cp in wideTrue) {
            assertTrue("0x${cp.toString(16)} should be double width", FontChain.isDoubleWidth(cp))
        }
        for (cp in wideFalse) {
            assertFalse("0x${cp.toString(16)} should not be double width", FontChain.isDoubleWidth(cp))
        }
        // Neighbours of the two exceptions stay doubled.
        assertTrue(FontChain.isDoubleWidth(0x3002))
        assertTrue(FontChain.isDoubleWidth(0x301D))
    }

    @Test
    fun hasWideScansCodePointsNotChars() {
        assertFalse(FontChain.hasWide(""))
        assertFalse(FontChain.hasWide("abc"))
        assertTrue(FontChain.hasWide("名称"))
        assertTrue(FontChain.hasWide("a名c"))
        // A surrogate pair (U+20000) is one code point.
        val extension = "\uD840\uDC00"
        assertEquals(1, extension.codePointCount(0, extension.length))
        assertTrue(FontChain.hasWide(extension))
        // The (start, end) window scans only that range.
        val mixed = "名称"
        assertFalse(FontChain.hasWide(mixed, 1, 1))
        assertTrue(FontChain.hasWide(mixed, 0, 1))
    }

    @Test
    fun featureSettingsMatchRustsLigatureSet() {
        assertEquals("'liga' 0, 'clig' 0", FontChain.featureSettings(false))
        assertEquals(null, FontChain.featureSettings(true))
    }

    @Test
    fun monoRolesAreTheFourGeistMonoFaces() {
        for (role in FaceRole.entries) {
            assertEquals("$role mono", role.face.mono, FontChain.isMono(role))
        }
        assertTrue(FontChain.isMono(FaceRole.MONO))
        assertTrue(FontChain.isMono(FaceRole.MONO_MEDIUM))
        assertTrue(FontChain.isMono(FaceRole.MONO_SEMIBOLD))
        assertTrue(FontChain.isMono(FaceRole.MONO_ITALIC))
        assertFalse(FontChain.isMono(FaceRole.SANS))
        assertFalse(FontChain.isMono(FaceRole.SANS_BOLD_ITALIC))
    }
}
