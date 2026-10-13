package sh.roboco.android.transcript

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The known-keys marking (ticket 65, plain JVM, on the pure core — the
 * FFI-facing [KnownRows.onFrameApplied] overload only wraps it with the
 * frame walk): the first non-empty frame marks every key known, a
 * brand-new key claims `true` exactly once, a first frame after an empty
 * one marks its keys too, and a non-empty → non-empty transition marks
 * nothing (iOS `knownKeys`, `TranscriptListView.swift:300-321`).
 */
class KnownRowsTest {

    @Test
    fun theFirstNonEmptyFrameMarksAllKeysKnown() {
        val k = KnownRows()
        k.onFrameApplied(0u) { sequenceOf(1uL, 2uL, 3uL) }
        assertFalse(k.claim(1uL))
        assertFalse(k.claim(2uL))
        assertFalse(k.claim(3uL))
    }

    @Test
    fun aNewKeyClaimsTrueOnceThenFalse() {
        val k = KnownRows()
        k.onFrameApplied(0u) { emptySequence() }
        assertTrue(k.claim(7uL))
        assertFalse(k.claim(7uL))
    }

    @Test
    fun aFrameAfterAnEmptyOneMarksItsNewKeysKnown() {
        val k = KnownRows()
        // A non-empty first frame, then the transcript is cleared (an empty
        // frame), then content arrives again: its keys are all known, so a
        // re-attached transcript does not re-fade.
        k.onFrameApplied(0u) { sequenceOf(1uL) }
        k.onFrameApplied(4u) { emptySequence() } // the empty frame
        k.onFrameApplied(0u) { sequenceOf(2uL, 3uL) }
        assertFalse(k.claim(2uL))
        assertFalse(k.claim(3uL))
    }

    @Test
    fun aNonEmptyToNonEmptyTransitionMarksNothing() {
        val k = KnownRows()
        k.onFrameApplied(0u) { sequenceOf(1uL, 2uL) }
        k.onFrameApplied(2u) { sequenceOf(3uL, 4uL) }
        assertTrue(k.claim(3uL))
        assertTrue(k.claim(4uL))
    }
}
