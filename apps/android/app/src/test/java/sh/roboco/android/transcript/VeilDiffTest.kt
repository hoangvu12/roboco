package sh.roboco.android.transcript

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [VeilDiff.insertionAt] over the ticket's "Worked diff examples" table
 * (plus the suffix-cap rule). Plain JVM: pure UTF-16 arithmetic over
 * [CharSequence]s, no Android and no Compose.
 */
class VeilDiffTest {

    @Test
    fun anAppendAfterThePrefixInsertsAtThePrefixLength() {
        assertEquals(5, VeilDiff.insertionAt("Hello", "Hello world"))
    }

    @Test
    fun aBulletTailKeepsTheSuffixSettled() {
        // Rust places a list marker after the item text (markdown.rs:561-567),
        // so streamed words land before the row's trailing bullet.
        assertEquals(5, VeilDiff.insertionAt("Hello•", "Hello world•"))
    }

    @Test
    fun aNewListItemInsertsBeforeItsBullet() {
        assertEquals(2, VeilDiff.insertionAt("A•", "A•B•"))
    }

    @Test
    fun anInsertionIntoEmptyTextStartsAtZero() {
        assertEquals(0, VeilDiff.insertionAt("", "Hi"))
    }

    @Test
    fun aPrefixRewriteIsNotAnInsertion() {
        // delta 1, but new.length − p − sfx = 4 ≠ 1: more than an insertion.
        assertEquals(-1, VeilDiff.insertionAt("abc", "xbcd"))
    }

    @Test
    fun aShrinkIsNotAnInsertion() {
        assertEquals(-1, VeilDiff.insertionAt("Hello world", "Hello"))
    }

    @Test
    fun anEqualLengthReplacementIsNotAnInsertion() {
        assertEquals(-1, VeilDiff.insertionAt("Hello", "Hallo"))
    }

    @Test
    fun anEqualTextNewVersionIsNotAnInsertion() {
        // A width-only rebuild arrives with the same text: delta 0.
        assertEquals(-1, VeilDiff.insertionAt("Hello", "Hello"))
    }

    @Test
    fun theSuffixScanIsCappedAtThePrefix() {
        // "aa" → "aaa": the common prefix is the whole old text, so the
        // suffix scan stops at once (max − p = 0) and the chunk is [2, 3).
        assertEquals(2, VeilDiff.insertionAt("aa", "aaa"))
    }

    @Test
    fun aWordReplacePlusAppendIsNotOneInsertion() {
        // delta 6, but the changed first letter makes it two insertions.
        assertEquals(-1, VeilDiff.insertionAt("Hello", "Jello there"))
    }

    @Test
    fun theInsertedTextIsExactlyTheDelta() {
        // When the delta test passes, the insertion is new[p, p + delta):
        // the bullet example, checked from the returned index.
        val p = VeilDiff.insertionAt("Hello•", "Hello world•")
        assertEquals(" world", "Hello world•".substring(p, p + 6))
    }
}
