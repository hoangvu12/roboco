package sh.roboco.android.composer

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import sh.roboco.android.design.Palette

/**
 * [mentionRanges] and [MentionVisualTransformation.filter] on the plain
 * JVM (the merge check's tier): ranges, merging, composition filtering,
 * the identity of the transformed text and offsets, and the span fields.
 */
class MentionStyleTest {

    /** The light accent, as [mentionSpanStyle] resolves it. */
    private val accent = Palette.Light.accent

    /** A stand-in for [mentionSpanStyle]: Medium at an unspecified size. */
    private val style = SpanStyle(color = accent, fontWeight = FontWeight.Medium)

    // ── mentionRanges ──────────────────────────────────────────────────────

    @Test
    fun rangesCoverEveryNonOverlappingOccurrenceOfEachToken() {
        assertEquals(
            listOf(TextRange(8, 15), TextRange(20, 27)),
            mentionRanges("Look at @mod.rs and @mod.rs", setOf("@mod.rs")),
        )
    }

    @Test
    fun overlappingTokenMatchesMergeIntoOneRange() {
        assertEquals(listOf(TextRange(0, 7)), mentionRanges("@lib.rs", setOf("@lib", "@lib.rs")))
    }

    @Test
    fun touchingMatchesMerge() {
        assertEquals(listOf(TextRange(0, 7)), mentionRanges("@mod.rs", setOf("@mo", "d.rs")))
    }

    @Test
    fun matchingIsCaseSensitive() {
        assertEquals(emptyList<TextRange>(), mentionRanges("@Mod.rs", setOf("@mod.rs")))
    }

    @Test
    fun matchingIsPlainSubstring() {
        // `@lib.rs` inside `@lib.rsx`, as on iOS.
        assertEquals(listOf(TextRange(0, 7)), mentionRanges("@lib.rsx", setOf("@lib.rs")))
    }

    @Test
    fun matchesIntersectingTheCompositionAreDropped() {
        // " abc" occupies [7, 11): [0, 7) survives [8, 11), dies under [3, 5).
        assertEquals(listOf(TextRange(0, 7)), mentionRanges("@mod.rs abc", setOf("@mod.rs"), TextRange(8, 11)))
        assertEquals(emptyList<TextRange>(), mentionRanges("@mod.rs abc", setOf("@mod.rs"), TextRange(3, 5)))
    }

    @Test
    fun collapsedCompositionIsIgnored() {
        assertEquals(listOf(TextRange(0, 7)), mentionRanges("@mod.rs abc", setOf("@mod.rs"), TextRange(4, 4)))
    }

    @Test
    fun noTokensYieldsNoRanges() {
        assertEquals(emptyList<TextRange>(), mentionRanges("@mod.rs", emptySet()))
    }

    @Test
    fun emptyTokensAreDropped() {
        assertEquals(emptyList<TextRange>(), mentionRanges("@mod.rs", setOf("")))
    }

    // ── MentionVisualTransformation.filter ────────────────────────────────

    @Test
    fun filterKeepsTheTextIdenticalAndTheOffsetsIdentity() {
        val input = AnnotatedString("Look at @mod.rs and @lib.rsx")
        val out = MentionVisualTransformation(setOf("@mod.rs", "@lib.rs"), null, style).filter(input)
        assertEquals(input.text, out.text.text)
        for (i in 0..input.text.length) {
            assertEquals(i, out.offsetMapping.originalToTransformed(i))
            assertEquals(i, out.offsetMapping.transformedToOriginal(i))
        }
    }

    @Test
    fun filterWithoutMatchesReturnsTheInputItself() {
        val input = AnnotatedString("no tokens here")
        val out = MentionVisualTransformation(setOf("@mod.rs"), null, style).filter(input)
        assertSame(input, out.text)
    }

    @Test
    fun filterSpansEqualMentionRangesAndCarryTheTokenStyle() {
        val text = "Look at @mod.rs and @mod.rs"
        val tokens = setOf("@mod.rs")
        val out = MentionVisualTransformation(tokens, null, style).filter(AnnotatedString(text))
        val spans = out.text.spanStyles
        assertEquals(mentionRanges(text, tokens), spans.map { TextRange(it.start, it.end) })
        for (span in spans) {
            assertEquals(style, span.item)
            assertEquals(accent, span.item.color)
            assertEquals(FontWeight.Medium, span.item.fontWeight)
            // No fontSize on the span: the token follows the body's size.
            assertEquals(TextUnit.Unspecified, span.item.fontSize)
        }
    }

    @Test
    fun filterSkipsMatchesInsideTheComposition() {
        val text = "@mod.rs abc"
        val without = MentionVisualTransformation(setOf("@mod.rs"), null, style)
            .filter(AnnotatedString(text))
        val with = MentionVisualTransformation(setOf("@mod.rs"), TextRange(3, 5), style)
            .filter(AnnotatedString(text))
        assertEquals(listOf(TextRange(0, 7)), without.text.spanStyles.map { TextRange(it.start, it.end) })
        assertEquals(emptyList<AnnotatedString.Range<SpanStyle>>(), with.text.spanStyles)
    }

    @Test
    fun equalityCoversAllThreeFields() {
        val a = MentionVisualTransformation(setOf("@mod.rs"), null, style)
        assertEquals(a, MentionVisualTransformation(setOf("@mod.rs"), null, style))
        assertEquals(a.hashCode(), MentionVisualTransformation(setOf("@mod.rs"), null, style).hashCode())
        assertNotEquals(a, MentionVisualTransformation(setOf("@lib.rs"), null, style))
        assertNotEquals(a, MentionVisualTransformation(setOf("@mod.rs"), TextRange(0, 1), style))
        assertNotEquals(a, MentionVisualTransformation(setOf("@mod.rs"), null, SpanStyle(color = accent)))
    }
}
