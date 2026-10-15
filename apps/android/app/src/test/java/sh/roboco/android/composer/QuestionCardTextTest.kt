package sh.roboco.android.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QuestionCardText] and [QuestionCardTags] — android-ui-parity #90's
 * pure string logic (plain JVM, no Robolectric runner): the header
 * composition, the Next/Submit switch, the `isEmpty` (never `isBlank`)
 * fallback rule, and the tag grammar.
 */
class QuestionCardTextTest {

    // -------------------------------------------------------- the header

    @Test
    fun anEmptyHeaderFallsBackToQuestionOnASinglePage() {
        assertEquals("Question", QuestionCardText.header("", 0, 1))
    }

    @Test
    fun aHeaderIsKeptAsIsOnASinglePage() {
        assertEquals("Scope", QuestionCardText.header("Scope", 0, 1))
    }

    @Test
    fun aSinglePageNeverAppendsTheCounter() {
        // Fixture E: "Scope", 0 of 1 — iOS shows no "· 1 of 1" (:113).
        assertEquals("Scope", QuestionCardText.header("Scope", 0, 1))
        assertEquals("Question", QuestionCardText.header("", 0, 1))
    }

    @Test
    fun severalPagesComposeHeaderDotCounterWithSpaces() {
        // Fixture A: "Question · 1 of 2" — space, U+00B7 ·, space.
        assertEquals("Question · 1 of 2", QuestionCardText.header("", 0, 2))
        assertEquals("Tone · 2 of 3", QuestionCardText.header("Tone", 1, 3))
        assertEquals("Question · 2 of 2", QuestionCardText.header("", 1, 2))
    }

    @Test
    fun aSpaceHeaderIsKeptThenTheSeparatorFollows() {
        // iOS tests isEmpty, not isBlank: a " " header is shown as is
        // (then the separator's own spaces make the double gap).
        assertEquals("  · 1 of 2", QuestionCardText.header(" ", 0, 2))
    }

    @Test
    fun thePageNumberIsOneBased() {
        assertEquals("Q · 5 of 9", QuestionCardText.header("Q", 4, 9))
    }

    // ------------------------------------------------------ the titles

    @Test
    fun nextTitleIsNextExceptOnTheLastPage() {
        assertEquals("Next", QuestionCardText.nextTitle(0, 2))
        assertEquals("Next", QuestionCardText.nextTitle(0, 3))
        assertEquals("Submit", QuestionCardText.nextTitle(1, 2))
        assertEquals("Submit", QuestionCardText.nextTitle(8, 9))
        assertEquals("Submit", QuestionCardText.nextTitle(0, 1))
    }

    // ------------------------------------------------- the copy strings

    @Test
    fun theCopyIsVerbatim() {
        assertEquals("Question", QuestionCardText.FALLBACK_HEADER)
        // U+2026, exactly one character after "Other".
        assertEquals("Other…", QuestionCardText.OTHER_PLACEHOLDER)
        assertEquals(6, QuestionCardText.OTHER_PLACEHOLDER.length)
        assertEquals("Back", QuestionCardText.BACK)
        assertEquals("Next", QuestionCardText.NEXT)
        assertEquals("Submit", QuestionCardText.SUBMIT)
    }

    // ---------------------------------------------------------- the tags

    @Test
    fun optionTagsAreZeroBased() {
        assertEquals("question-option-0", QuestionCardTags.option(0))
        assertEquals("question-option-11", QuestionCardTags.option(11))
        assertEquals("question-option-51", QuestionCardTags.option(51))
    }

    @Test
    fun theStaticTagsMatchTheIosIdentifiers() {
        assertEquals("question-other", QuestionCardTags.OTHER)
        assertEquals("question-editor", QuestionCardTags.EDITOR)
        assertEquals("question-next", QuestionCardTags.NEXT)
        assertEquals("question-card", QuestionCardTags.CARD)
    }

    // ------------------------------------------- the model's deriveds

    private fun model(
        header: String = "",
        page: Int = 0,
        pageCount: Int = 1,
    ) = QuestionPageModel(
        header = header,
        page = page,
        pageCount = pageCount,
        question = "Q",
        options = emptyList(),
        picked = emptySet(),
        multiSelect = false,
        multiline = false,
        customText = "",
        canContinue = true,
    )

    @Test
    fun theModelDerivesHeaderTextShowBackAndNextTitle() {
        val twoPages = model(header = "Tone", page = 1, pageCount = 3)
        assertEquals("Tone · 2 of 3", twoPages.headerText)
        assertTrue(twoPages.showBack)
        assertEquals("Next", twoPages.nextTitle)

        val last = model(header = "", page = 2, pageCount = 3)
        assertEquals("Question · 3 of 3", last.headerText)
        assertTrue(last.showBack)
        assertEquals("Submit", last.nextTitle)

        val first = model(header = "Scope", page = 0, pageCount = 1)
        assertEquals("Scope", first.headerText)
        assertFalse(first.showBack)
        assertEquals("Submit", first.nextTitle)
    }
}
