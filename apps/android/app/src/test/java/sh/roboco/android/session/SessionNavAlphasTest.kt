package sh.roboco.android.session

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.roboco.android.shell.NavKind

/**
 * The pure functions of the push/pop choreography (android-ui-parity
 * #85): every row of the ticket's Phase and Worked-values tables, at a
 * tolerance of 1e-6 — including `p` clamping (1.2 → 0, −0.1 → 1 for
 * `Leaving`), `!animated` → 1/1, reduce motion → 1/1, `Bystander` →
 * 1/1 and `IdleHidden` → 0/0.
 *
 * Plain JUnit 4: no Robolectric, no Compose — the functions are pure.
 */
class SessionNavAlphasTest {

    // ── sessionNavPhase: the Phase table, checked in order ──────────────

    @Test
    fun aPushToThisEntryIsEntering() {
        assertEquals(
            SessionNavPhase.Entering,
            sessionNavPhase(hasTransition = true, kind = NavKind.Push, isTo = true, isFrom = false, isTop = false),
        )
    }

    @Test
    fun aPopFromThisEntryIsLeaving() {
        assertEquals(
            SessionNavPhase.Leaving,
            sessionNavPhase(hasTransition = true, kind = NavKind.Pop, isTo = false, isFrom = true, isTop = true),
        )
    }

    @Test
    fun thisEntryAsTheFromOfAPushIsABystander() {
        // The push order rule: `Push && isTo` must match before anything
        // else; this entry is the buried `from`, not the destination.
        assertEquals(
            SessionNavPhase.Bystander,
            sessionNavPhase(hasTransition = true, kind = NavKind.Push, isTo = false, isFrom = true, isTop = false),
        )
    }

    @Test
    fun thisEntryAsTheToOfAPopIsABystander() {
        assertEquals(
            SessionNavPhase.Bystander,
            sessionNavPhase(hasTransition = true, kind = NavKind.Pop, isTo = true, isFrom = false, isTop = false),
        )
    }

    @Test
    fun anUnrelatedTransitionIsABystander() {
        assertEquals(
            SessionNavPhase.Bystander,
            sessionNavPhase(hasTransition = true, kind = NavKind.Push, isTo = false, isFrom = false, isTop = true),
        )
    }

    @Test
    fun theTopWithNoTransitionIsIdleTop() {
        assertEquals(
            SessionNavPhase.IdleTop,
            sessionNavPhase(hasTransition = false, kind = null, isTo = false, isFrom = false, isTop = true),
        )
    }

    @Test
    fun aBuriedOrGoneEntryWithNoTransitionIsIdleHidden() {
        assertEquals(
            SessionNavPhase.IdleHidden,
            sessionNavPhase(hasTransition = false, kind = null, isTo = false, isFrom = false, isTop = false),
        )
    }

    // ── sessionNavAlphas: the Worked-values table ───────────────────────

    private fun alphas(
        phase: SessionNavPhase,
        animated: Boolean = true,
        progress: Float = 0f,
        reduceMotion: Boolean = false,
    ) = sessionNavAlphas(phase, animated, progress, reduceMotion)

    private fun assertAlphas(expected: SessionNavAlphas, actual: SessionNavAlphas) {
        assertEquals("stack α", expected.stack, actual.stack, 1e-6f)
        assertEquals("jump α", expected.jump, actual.jump, 1e-6f)
    }

    @Test
    fun enteringAtZeroHidesTheStackAndShowsTheJump() {
        assertAlphas(SessionNavAlphas(stack = 0f, jump = 1f), alphas(SessionNavPhase.Entering, progress = 0f))
    }

    @Test
    fun enteringAt030TracksTheProgress() {
        assertAlphas(SessionNavAlphas(stack = 0.3f, jump = 1f), alphas(SessionNavPhase.Entering, progress = 0.3f))
    }

    @Test
    fun enteringAtOneIsFullyShown() {
        assertAlphas(SessionNavAlphas(stack = 1f, jump = 1f), alphas(SessionNavPhase.Entering, progress = 1f))
    }

    @Test
    fun leavingUnderAGestureCrossFadesBoth() {
        // The gesture row: p = 0.4 → stack = jump = 1 − p = 0.6.
        assertAlphas(SessionNavAlphas(stack = 0.6f, jump = 0.6f), alphas(SessionNavPhase.Leaving, progress = 0.4f))
    }

    @Test
    fun leavingClampsAnOvershootToZero() {
        assertAlphas(SessionNavAlphas(stack = 0f, jump = 0f), alphas(SessionNavPhase.Leaving, progress = 1.2f))
    }

    @Test
    fun leavingClampsANegativeProgressToOne() {
        assertAlphas(SessionNavAlphas(stack = 1f, jump = 1f), alphas(SessionNavPhase.Leaving, progress = -0.1f))
    }

    @Test
    fun anUnanimatedEnteringLeavesBothUntouched() {
        // iOS :196-198 — no transition coordinator: neither alpha moves.
        assertAlphas(SessionNavAlphas(stack = 1f, jump = 1f), alphas(SessionNavPhase.Entering, animated = false, progress = 0f))
    }

    @Test
    fun anUnanimatedLeavingLeavesBothUntouched() {
        assertAlphas(SessionNavAlphas(stack = 1f, jump = 1f), alphas(SessionNavPhase.Leaving, animated = false, progress = 0.4f))
    }

    @Test
    fun reducedMotionKeepsBothMultipliersAtOne() {
        // 24's reduced branch already cross-fades the whole layer; multiplying
        // by presence again would square the fade.
        assertAlphas(SessionNavAlphas(stack = 1f, jump = 1f), alphas(SessionNavPhase.Leaving, progress = 0.5f, reduceMotion = true))
        assertAlphas(SessionNavAlphas(stack = 1f, jump = 1f), alphas(SessionNavPhase.Entering, progress = 0.5f, reduceMotion = true))
    }

    @Test
    fun aBystanderShowsEverything() {
        assertAlphas(SessionNavAlphas(stack = 1f, jump = 1f), alphas(SessionNavPhase.Bystander, progress = 0.5f))
    }

    @Test
    fun idleTopShowsEverything() {
        assertAlphas(SessionNavAlphas(stack = 1f, jump = 1f), alphas(SessionNavPhase.IdleTop, animated = false, progress = 0f))
    }

    @Test
    fun idleHiddenHidesEverything() {
        // A safety value: 24 does not place the entry anyway.
        assertAlphas(SessionNavAlphas(stack = 0f, jump = 0f), alphas(SessionNavPhase.IdleHidden, animated = false, progress = 0f))
    }

    @Test
    fun enteringClampsItsProgressToo() {
        assertAlphas(SessionNavAlphas(stack = 1f, jump = 1f), alphas(SessionNavPhase.Entering, progress = 1.2f))
        assertAlphas(SessionNavAlphas(stack = 0f, jump = 1f), alphas(SessionNavPhase.Entering, progress = -0.1f))
    }

    // ── the constants and the static alpha ──────────────────────────────

    @Test
    fun theShownAndGoneConstantsMatchTheirTableRows() {
        assertEquals(1f, SessionNavAlphas.Shown.stack, 1e-6f)
        assertEquals(1f, SessionNavAlphas.Shown.jump, 1e-6f)
        assertEquals(0f, SessionNavAlphas.Gone.stack, 1e-6f)
        assertEquals(0f, SessionNavAlphas.Gone.jump, 1e-6f)
    }

    @Test
    fun theStaticAlphaIsAlwaysOne() {
        assertEquals(1f, SessionNavAlpha.Static.stackAlpha(), 1e-6f)
        assertEquals(1f, SessionNavAlpha.Static.jumpAlpha(), 1e-6f)
    }
}
