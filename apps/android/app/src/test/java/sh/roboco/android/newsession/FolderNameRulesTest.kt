package sh.roboco.android.newsession

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [FolderNameRules.check] (android-ui-parity #111): the ticket's name
 * table — empty/whitespace, the exact `.` and `..`, separators and NUL
 * (with the trimmed name kept for the re-prompt), and the legal names
 * `...` and `.hidden`. Plain JUnit, no Robolectric: the rules are pure.
 */
class FolderNameRulesTest {

    @Test
    fun blankNamesAreEmpty() {
        assertEquals(FolderNameCheck.Empty, FolderNameRules.check(""))
        assertEquals(FolderNameCheck.Empty, FolderNameRules.check("   "))
        assertEquals(FolderNameCheck.Empty, FolderNameRules.check(" \t\n "))
    }

    @Test
    fun dotNamesAreInvalid() {
        assertEquals(FolderNameCheck.Invalid("."), FolderNameRules.check("."))
        assertEquals(FolderNameCheck.Invalid(".."), FolderNameRules.check(".."))
    }

    @Test
    fun separatorsAndNulAreInvalidWithTheTrimmedNameKept() {
        assertEquals(FolderNameCheck.Invalid("a/b"), FolderNameRules.check("a/b"))
        assertEquals(FolderNameCheck.Invalid("a\\b"), FolderNameRules.check("a\\b"))
        assertEquals(FolderNameCheck.Invalid("a\u0000b"), FolderNameRules.check("a\u0000b"))
        assertEquals(FolderNameCheck.Invalid("../outside"), FolderNameRules.check("../outside"))
        assertEquals(FolderNameCheck.Invalid("../outside"), FolderNameRules.check("  ../outside  "))
    }

    @Test
    fun plainNamesAreValidAndTrimmed() {
        assertEquals(FolderNameCheck.Valid("My new project"), FolderNameRules.check("  My new project "))
        assertEquals(FolderNameCheck.Valid("srv"), FolderNameRules.check("srv"))
        // Only the exact `.` and `..` are rejected.
        assertEquals(FolderNameCheck.Valid("..."), FolderNameRules.check("..."))
        assertEquals(FolderNameCheck.Valid(".hidden"), FolderNameRules.check(".hidden"))
    }

    @Test
    fun theInvalidMessageIsVerbatim() {
        assertEquals(
            "Couldn't create the folder. Enter a folder name without path separators.",
            FolderNameRules.INVALID,
        )
    }
}
