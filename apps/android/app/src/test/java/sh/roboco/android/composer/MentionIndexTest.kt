package sh.roboco.android.composer

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.roboco_core.FileMatch

/**
 * [MentionIndex] on the plain JVM (the merge check's tier): every row of
 * the ticket's token-allocation and activeQuery tables, plus the encode
 * contract — with a fake `link` so nothing loads the native core. The
 * generated `FileMatch` record constructs without touching `UniffiLib` or
 * `android.os.Build` (the Robolectric rule is for tests that *call into*
 * the core — see [MentionLinkFfiTest] for that tier).
 */
class MentionIndexTest {

    /** `FileMatch("…")` without the `isDir` argument (the record has no default). */
    private fun f(p: String, d: Boolean = false) = FileMatch(p, d)

    /** The ticket's fake link: `<path>` (or `<path/>` for a directory). */
    private val fakeLink: (String, Boolean) -> String = { p, d -> "<$p${if (d) "/" else ""}>" }

    private fun index() = MentionIndex(fakeLink)

    // ── token allocation table (a fresh index per row) ─────────────────────

    @Test
    fun tokenIsAtPlusBasename() {
        val i = index()
        assertEquals("@mod.rs", i.token(f("crates/ui/src/mod.rs")))
        assertEquals(mapOf("@mod.rs" to f("crates/ui/src/mod.rs")), i.tokens)
    }

    @Test
    fun tokenDisambiguatesBasenameClashWithParentFolder() {
        val i = index()
        assertEquals("@lib.rs", i.token(f("src/lib.rs")))
        assertEquals("@src/lib.rs", i.token(f("crates/core/src/lib.rs")))
        assertEquals(
            mapOf("@lib.rs" to f("src/lib.rs"), "@src/lib.rs" to f("crates/core/src/lib.rs")),
            i.tokens,
        )
    }

    @Test
    fun tokenThirdClashGetsItsOwnFolderToken() {
        val i = index()
        assertEquals("@lib.rs", i.token(f("a/lib.rs")))
        assertEquals("@b/lib.rs", i.token(f("b/lib.rs")))
        assertEquals("@c/lib.rs", i.token(f("c/lib.rs")))
        assertEquals(3, i.tokens.size)
        assertEquals(f("a/lib.rs"), i.tokens["@lib.rs"])
    }

    @Test
    fun tokenDisambiguatedClashIsOverwrittenByAThirdFile() {
        val i = index()
        assertEquals("@lib.rs", i.token(f("x/a/lib.rs")))
        assertEquals("@b/lib.rs", i.token(f("y/b/lib.rs")))
        assertEquals("@b/lib.rs", i.token(f("z/b/lib.rs")))
        assertEquals(f("x/a/lib.rs"), i.tokens["@lib.rs"])
        assertEquals(f("z/b/lib.rs"), i.tokens["@b/lib.rs"])
        assertEquals(2, i.tokens.size)
    }

    @Test
    fun tokenSamePathTwiceKeepsOneEntry() {
        val i = index()
        assertEquals("@lib.rs", i.token(f("src/lib.rs")))
        assertEquals("@lib.rs", i.token(f("src/lib.rs")))
        assertEquals(mapOf("@lib.rs" to f("src/lib.rs")), i.tokens)
    }

    @Test
    fun tokenSinglePieceClashRepointsWithoutDisambiguation() {
        val i = index()
        assertEquals("@README.md", i.token(f("x/README.md")))
        assertEquals("@README.md", i.token(f("README.md")))
        assertEquals(mapOf("@README.md" to f("README.md")), i.tokens)
    }

    @Test
    fun tokenKeepsIsDir() {
        val i = index()
        assertEquals("@components", i.token(f("src/components/", true)))
        assertEquals(mapOf("@components" to f("src/components/", true)), i.tokens)
    }

    @Test
    fun tokenDropsEmptyPathPieces() {
        val i = index()
        assertEquals("@b.rs", i.token(f("a//b.rs")))
        assertEquals("@c/b.rs", i.token(f("c/b.rs")))
    }

    @Test
    fun tokenOfDegeneratePathsUsesTheRawPath() {
        assertEquals("@/", index().token(f("/", true)))
        assertEquals("@", index().token(f("", false)))
    }

    @Test
    fun tokenIsDirOnlyDifferenceRepointsTheSamePath() {
        val i = index()
        assertEquals("@lib.rs", i.token(f("src/lib.rs", false)))
        assertEquals("@lib.rs", i.token(f("src/lib.rs", true)))
        assertEquals(mapOf("@lib.rs" to f("src/lib.rs", true)), i.tokens)
    }

    @Test
    fun tokensPreserveInsertionOrder() {
        val i = index()
        i.token(f("src/lib.rs"))
        i.token(f("crates/core/src/lib.rs"))
        assertEquals(listOf("@lib.rs", "@src/lib.rs"), i.tokens.keys.toList())
    }

    // ── activeQuery table (`|` is the cursor; ranges [start, end)) ────────

    @Test
    fun activeQueryJustAtOrTyped() {
        assertEquals(MentionQuery(0, 1, ""), MentionIndex.activeQuery("@", 1))
        assertEquals(MentionQuery(0, 4, "lay"), MentionIndex.activeQuery("@lay", 4))
        assertEquals(MentionQuery(8, 12, "lay"), MentionIndex.activeQuery("Look at @lay", 12))
        assertEquals(MentionQuery(2, 4, "x"), MentionIndex.activeQuery("a\n@x", 4))
    }

    @Test
    fun activeQueryNullCases() {
        assertNull(MentionIndex.activeQuery("a@b", 3)) // email-like: 'a' before '@'
        assertNull(MentionIndex.activeQuery("@@x", 3)) // '@' before '@'
        assertNull(MentionIndex.activeQuery("x @foo bar", 10)) // space between
        assertNull(MentionIndex.activeQuery("@foo ", 5)) // space before the cursor
        assertNull(MentionIndex.activeQuery("@foo", 0)) // cursor 0: loop never runs
        assertNull(MentionIndex.activeQuery("hello", 5)) // no '@' at all
        assertNull(MentionIndex.activeQuery("@x", 3)) // cursor past the end
        assertNull(MentionIndex.activeQuery("@x", -1)) // negative cursor
    }

    @Test
    fun activeQueryOnlyCountsUpToTheCursor() {
        // `@f|oo`: the query is [1, 2), the "oo" after the cursor is ignored.
        assertEquals(MentionQuery(0, 2, "f"), MentionIndex.activeQuery("@foo", 2))
    }

    @Test
    fun activeQueryTreatsTabsAsQueryCharacters() {
        assertNull(MentionIndex.activeQuery("\t@x", 3)) // tab before '@' is no boundary
        assertEquals(MentionQuery(0, 4, "a\tb"), MentionIndex.activeQuery("@a\tb", 4))
    }

    @Test
    fun activeQueryTreatsCrAndNbspAsNonBoundaries() {
        assertNull(MentionIndex.activeQuery("\r@x", 3))
        // U+00A0 NBSP: not one of the two boundary characters.
        assertNull(MentionIndex.activeQuery("\u00A0@x", 3))
    }

    @Test
    fun activeQueryCountsSurrogatePairsAsTwoUnits() {
        assertEquals(MentionQuery(0, 3, "😀"), MentionIndex.activeQuery("@😀", 3))
    }

    @Test
    fun activeQueryOnAFieldValueUsesTheSelectionMinimum() {
        // iOS passes selectedRange.location — the selection start, the
        // length ignored. TextRange(7, 5): min = 5, so the query region is
        // [5, 5) — empty, per the row's own definition (query =
        // substring(start + 1, cursor)); the row's "a" is unreachable with
        // end == 5 over "say @ab".
        assertEquals(
            MentionQuery(4, 5, ""),
            MentionIndex.activeQuery(TextFieldValue("say @ab", selection = TextRange(7, 5))),
        )
        // The same selection read the other way round (cursor 6) is the
        // non-empty variant the row's "a" describes.
        assertEquals(
            MentionQuery(4, 6, "a"),
            MentionIndex.activeQuery(TextFieldValue("say @ab", selection = TextRange(7, 6))),
        )
    }

    // ── encode ────────────────────────────────────────────────────────────

    @Test
    fun encodeWithoutTokensReturnsTheTextUnchanged() {
        assertEquals("nothing to do", index().encode("nothing to do"))
    }

    @Test
    fun encodeReplacesEveryOccurrenceOfAToken() {
        val i = index()
        i.token(f("src/lib.rs"))
        assertEquals("fix <src/lib.rs> and <src/lib.rs>", i.encode("fix @lib.rs and @lib.rs"))
    }

    @Test
    fun encodeReplacesTheLongestTokenFirst() {
        val shortFirst = index()
        shortFirst.token(f("lib", true))
        shortFirst.token(f("src/lib.rs"))
        assertEquals("<src/lib.rs> and <lib/>", shortFirst.encode("@lib.rs and @lib"))
        val longFirst = index()
        longFirst.token(f("src/lib.rs"))
        longFirst.token(f("lib", true))
        assertEquals("<src/lib.rs> and <lib/>", longFirst.encode("@lib.rs and @lib"))
    }

    @Test
    fun encodeResolvesClashingTokensIndependently() {
        val i = index()
        i.token(f("src/lib.rs"))
        i.token(f("crates/core/src/lib.rs"))
        assertEquals("<crates/core/src/lib.rs> vs <src/lib.rs>", i.encode("@src/lib.rs vs @lib.rs"))
    }

    @Test
    fun encodeSkipsTokensAbsentFromTheTextWithoutCallingLink() {
        var calls = 0
        val i = MentionIndex { p, d ->
            calls++
            fakeLink(p, d)
        }
        i.token(f("src/lib.rs"))
        assertEquals("nothing here", i.encode("nothing here"))
        assertEquals(0, calls)
    }

    @Test
    fun encodeDoesNotTrimAndDoesNotMutateTokens() {
        val i = index()
        i.token(f("src/lib.rs"))
        val before = i.tokens
        assertEquals("  <src/lib.rs> \n", i.encode("  @lib.rs \n"))
        assertEquals(before, i.tokens)
    }

    @Test
    fun resetClearsTokensAndEncodePassesThrough() {
        val i = index()
        i.token(f("src/lib.rs"))
        i.reset()
        assertTrue(i.tokens.isEmpty())
        assertEquals("@lib.rs stays", i.encode("@lib.rs stays"))
    }

    @Test
    fun fakeLinkFormatsDirectoriesWithASlash() {
        assertEquals("<docs/>", fakeLink("docs", true))
        assertEquals("<docs>", fakeLink("docs", false))
    }

    @Test
    fun reassignsTheTokenMapInsteadOfMutatingIt() {
        val i = index()
        val before = i.tokens
        i.token(f("src/lib.rs"))
        // The old map is never mutated — snapshot readers keep their view.
        assertTrue(before.isEmpty())
        assertEquals(1, i.tokens.size)
        assertEquals(f("src/lib.rs"), i.tokens["@lib.rs"])
    }
}
