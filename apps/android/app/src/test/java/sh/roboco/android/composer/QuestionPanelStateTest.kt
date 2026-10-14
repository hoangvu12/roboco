package sh.roboco.android.composer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.design.Cue
import sh.roboco.android.design.Feedback
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.Motion
import sh.roboco.android.session.SessionChrome

/**
 * [QuestionPanelState] — android-ui-parity #91's behaviour, pinned on the
 * plain JVM (the state is FFI-free): the configure rules (the reset keyed
 * on the list **and** the request id, the kept items under null, the
 * first-wins prefill for duplicate ids), the answered rule and iOS's
 * whitespace trim, the nextEnabled matrix, the 220 ms auto-advance
 * (bound to its page, restarted by a second pick, cancelled by Back and
 * configure), the F1 auto-submit and the F2 flow (option order), the
 * multi-select deselect, the double-submit guard and its re-arm, the
 * no-request / no-canWrite guards, and [buildAnswers]'s worked
 * examples.
 *
 * The auto-advance timing runs on the [TestScope] scheduler
 * (`StandardTestDispatcher`): `advanceTimeBy` + `runCurrent` step it,
 * and every test drains before `runTest` ends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuestionPanelStateTest {

    /** Records every haptic in order (iOS's two generators). */
    private class RecordingFeedback : Feedback {
        val fired = mutableListOf<Haptic>()
        override fun haptic(haptic: Haptic) {
            fired += haptic
        }

        override fun cue(cue: Cue) {}
    }

    /** Records every submit as `(requestId, answers)`. */
    private class RecordingSubmit {
        val calls = mutableListOf<Pair<String, List<QuestionAnswer>>>()
        val onSubmit: (String, List<QuestionAnswer>) -> Unit = { id, answers -> calls += id to answers }
    }

    private val haptics = RecordingFeedback()
    private val submits = RecordingSubmit()

    private fun TestScope.newState(autoAdvanceMs: Long = Motion.QuestionAutoAdvanceMs): QuestionPanelState =
        QuestionPanelState(this, haptics, submits.onSubmit, autoAdvanceMs)

    private val single = SessionChrome.Questions(
        requestId = "r",
        items = listOf(
            SessionChrome.Question(id = "a", header = "H", text = "Q?", options = listOf("A", "B"), multiSelect = false),
        ),
    )

    private val twoSingles = SessionChrome.Questions(
        requestId = "r2s",
        items = listOf(
            SessionChrome.Question(id = "s1", header = "Question", text = "First?", options = listOf("A", "B"), multiSelect = false),
            SessionChrome.Question(id = "s2", header = "Question", text = "Second?", options = listOf("A", "B"), multiSelect = false),
        ),
    )

    private val multilineOnly = SessionChrome.Question(
        id = "m", header = "Notes", text = "Anything else?", options = emptyList(), multiSelect = false, multiline = true,
    )

    // ------------------------------------------------------------ trimIos

    @Test
    fun trimIosTrimsOnlyZsAndTabNeverNewlines() {
        assertEquals("x", " \t x \t ".trimIos())
        assertEquals("Zs (NBSP) is trimmed", "", "\u00A0".trimIos())
        assertEquals("Zl (line separator) is kept", "\u2028", "\u2028".trimIos())
        assertEquals("LF is kept", "\n", "\n".trimIos())
        assertEquals("inner text is untouched", "a\n b", "a\n b".trimIos())
        assertEquals("", "".trimIos())
    }

    // ------------------------------------------------------------ configure

    @Test
    fun aNewListResetsPagePicksSubmittedAndCustomAndBumpsTheGeneration() = runTest {
        val s = newState()
        s.configure(QuestionPanelFixtures.F1)
        assertEquals(1, s.count)
        assertEquals(0, s.page)
        assertEquals(1, s.generation)
        assertEquals("r1", s.requestId)
        s.pick(0, "Yes, both platforms")
        advanceTimeBy(220)
        runCurrent() // F1 is one single-select question: the pick submits
        assertTrue("F1 auto-submitted", s.submitted)
        assertEquals(1, submits.calls.size)

        s.configure(QuestionPanelFixtures.F2)
        assertEquals("a new list resets the page", 0, s.page)
        assertEquals("a new list clears the picks", emptySet<String>(), s.picked("q1"))
        assertEquals("a new list re-arms the submit guard", false, s.submitted)
        assertEquals("a new list seeds custom from the (absent) prefill", "", s.customText("q-sync"))
        assertEquals("a new list bumps the generation", 2, s.generation)
        assertEquals("r2", s.requestId)
        advanceUntilIdle()
    }

    @Test
    fun configuringAnEqualListUnderTheSameRequestIdFiveTimesChangesNothing() = runTest {
        val s = newState()
        s.configure(QuestionPanelFixtures.F2)
        s.pick(0, "Event-driven fold with coalesced commits")
        val generation = s.generation
        val pick = s.picked("q-sync")
        repeat(5) { s.configure(QuestionPanelFixtures.F2) }
        assertEquals("an ordinary refresh never resets", generation, s.generation)
        assertEquals("the picks survive every refresh", pick, s.picked("q-sync"))
        assertEquals(0, s.page)
        assertEquals("r2", s.requestId)
        advanceUntilIdle()
    }

    @Test
    fun theSameListUnderANewRequestIdResets() = runTest {
        val s = newState()
        s.configure(QuestionPanelFixtures.F2)
        s.pick(0, "Event-driven fold with coalesced commits")
        s.configure(QuestionPanelFixtures.F2.copy(requestId = "r9"))
        assertEquals(2, s.generation)
        assertEquals(0, s.page)
        assertEquals(emptySet<String>(), s.picked("q-sync"))
        assertFalse(s.submitted)
        assertEquals("r9", s.requestId)
        advanceUntilIdle()
        assertEquals("the reset cancelled the pending advance", 0, s.page)
    }

    @Test
    fun configureNullKeepsItemsAndPageAndClearsTheRequestId() = runTest {
        val s = newState()
        s.configure(QuestionPanelFixtures.F2)
        s.pick(0, "Event-driven fold with coalesced commits")
        advanceTimeBy(220)
        runCurrent() // page 1
        s.configure(null)
        assertEquals("items kept while the slot collapses", QuestionPanelFixtures.F2.items, s.items)
        assertEquals("page kept", 1, s.page)
        assertNull(s.requestId)
        advanceUntilIdle()
    }

    @Test
    fun configureNullCancelsThePendingAutoAdvance() = runTest {
        val s = newState()
        s.configure(QuestionPanelFixtures.F1)
        s.pick(0, "Yes, both platforms")
        s.configure(null)
        advanceUntilIdle()
        assertTrue("the stale timer must not submit", submits.calls.isEmpty())
        assertFalse(s.submitted)
        assertEquals(listOf(Haptic.Select), haptics.fired)
    }

    @Test
    fun duplicateIdsDoNotThrowTheFirstPrefillWinsAndBothPagesShareState() = runTest {
        val s = newState()
        s.configure(QuestionPanelFixtures.Dup)
        assertEquals("the first prefill wins", "p1", s.customText("x"))
        s.pick(0, "First")
        assertEquals(setOf("First"), s.picked("x"))
        s.setCustom(0, "typed")
        assertEquals("typed", s.customText("x"))
        assertTrue("page 2 sees the same picks (one shared id)", s.answered(QuestionPanelFixtures.Dup.items[1]))
        s.next() // page 0 → 1
        s.next() // last page → submit
        assertEquals(2, submits.calls.single().second.size)
        assertEquals("both answers carry the shared id", listOf("x", "x"), submits.calls.single().second.map { it.questionId })
        assertEquals(
            listOf(QuestionAnswer("x", listOf("First", "typed")), QuestionAnswer("x", listOf("First", "typed"))),
            submits.calls.single().second,
        )
        advanceUntilIdle()
    }

    // ------------------------------------------------------------ answered / nextEnabled

    @Test
    fun multilineIsAlwaysAnsweredEvenWithEmptyText() = runTest {
        val s = newState()
        // No picks, no custom text: multiline alone answers.
        assertTrue(s.answered(multilineOnly))
        s.configure(SessionChrome.Questions("rm", listOf(multilineOnly)))
        assertTrue(s.nextEnabled)
        s.next() // the only page is the last: submit, labels = [""]
        assertEquals(listOf(QuestionAnswer("m", listOf(""))), submits.calls.single().second)
        advanceUntilIdle()
    }

    @Test
    fun answeredFollowsTheIosWhitespaceRule() = runTest {
        val s = newState()
        val q = single.items[0]
        assertFalse("nothing picked, no text", s.answered(q))
        s.configure(single)
        s.setCustom(0, " \t ")
        assertFalse("iOS-whitespace-only text is blank", s.answered(q))
        s.setCustom(0, "\n")
        assertTrue("a newline is not whitespace: answered", s.answered(q))
        s.setCustom(0, "")
        s.pick(0, "A")
        assertTrue("one pick answers", s.answered(q))
        advanceUntilIdle()
    }

    @Test
    fun nextEnabledGatesOnAnsweredAndOnCanWriteForSubmitOnly() = runTest {
        val s = newState()
        s.configure(QuestionPanelFixtures.F2)
        assertFalse("an unanswered page disables Next", s.nextEnabled)
        s.pick(0, "Event-driven fold with coalesced commits")
        assertTrue("an answered page enables Next", s.nextEnabled)
        s.canWrite = false
        assertTrue("Next (not Submit) stays enabled while offline", s.nextEnabled)
        s.canWrite = true
        advanceTimeBy(220)
        runCurrent() // page 1 (q-gates, multi)
        s.pick(1, "Unit tests")
        s.canWrite = false
        assertFalse("Submit is disabled while the engine is offline", s.nextEnabled)
        s.canWrite = true
        assertTrue("Submit re-arms without losing the picks", s.nextEnabled)
        advanceUntilIdle()
    }

    // ------------------------------------------------------------ the auto-advance

    @Test
    fun theSingleSelectAutoAdvanceFiresAt220MsNot219() = runTest {
        val s = newState()
        s.configure(QuestionPanelFixtures.F2)
        s.pick(0, "Event-driven fold with coalesced commits")
        advanceTimeBy(219)
        runCurrent()
        assertEquals("no advance before 220 ms", 0, s.page)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("the advance at 220 ms", 1, s.page)
        advanceUntilIdle()
    }

    @Test
    fun aSecondPickRestartsTheTimer() = runTest {
        val s = newState()
        s.configure(QuestionPanelFixtures.F2)
        s.pick(0, "Poll the doc host every 120ms")
        advanceTimeBy(100)
        s.pick(0, "Event-driven fold with coalesced commits") // restarts the timer
        advanceTimeBy(119)
        runCurrent()
        assertEquals("t = 219: nothing yet", 0, s.page)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("t = 220: the first timer's slot stays quiet", 0, s.page)
        advanceTimeBy(99)
        runCurrent()
        assertEquals("t = 319: nothing yet", 0, s.page)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("t = 320: fires 220 ms after the second pick", 1, s.page)
        advanceUntilIdle()
    }

    @Test
    fun backWithin220MsCancelsTheStaleAdvance() = runTest {
        val s = newState()
        s.configure(twoSingles)
        s.pick(0, "A")
        advanceTimeBy(220)
        runCurrent() // page 1
        s.pick(1, "A") // schedules the advance for page 1
        advanceTimeBy(100)
        s.back() // page 0, and the timer is cancelled
        assertEquals(0, s.page)
        advanceTimeBy(500)
        runCurrent()
        assertEquals("nothing fires after Back", 0, s.page)
        assertTrue("no submit", submits.calls.isEmpty())
        assertTrue("no Confirm haptic", Haptic.Confirm !in haptics.fired)
    }

    // ------------------------------------------------------------ the flows

    @Test
    fun f1AutoSubmitsOnceAfter220MsWithBothHaptics() = runTest {
        val s = newState()
        s.configure(QuestionPanelFixtures.F1)
        s.pick(0, "Yes, both platforms")
        advanceTimeBy(219)
        runCurrent()
        assertTrue("not submitted before 220 ms", submits.calls.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(
            listOf("r1" to listOf(QuestionAnswer("q1", listOf("Yes, both platforms")))),
            submits.calls,
        )
        assertEquals(listOf(Haptic.Select, Haptic.Confirm), haptics.fired)
        advanceUntilIdle()
    }

    @Test
    fun theF2FlowAnswersInOptionOrderWithOneConfirm() = runTest {
        val s = newState()
        s.configure(QuestionPanelFixtures.F2)
        s.pick(0, "Event-driven fold with coalesced commits")
        advanceTimeBy(220)
        runCurrent() // page 1
        s.pick(1, "Golden screenshots") // tap order: row 2 …
        s.pick(1, "Unit tests") // … then row 0
        s.next()
        assertEquals(
            listOf(
                QuestionAnswer("q-sync", listOf("Event-driven fold with coalesced commits")),
                QuestionAnswer("q-gates", listOf("Unit tests", "Golden screenshots")),
            ),
            submits.calls.single().second,
        )
        assertEquals("r2", submits.calls.single().first)
        assertEquals("Select per tap, one Confirm, none for the page move", listOf(Haptic.Select, Haptic.Select, Haptic.Select, Haptic.Confirm), haptics.fired)
        advanceUntilIdle()
    }

    @Test
    fun multiSelectDeselectRemovesThePickAndStillSelects() = runTest {
        val s = newState()
        s.configure(QuestionPanelFixtures.F2)
        s.pick(0, "Event-driven fold with coalesced commits")
        s.next() // answered → page 1 (cancels the pending timer)
        s.pick(1, "Unit tests")
        assertTrue("Unit tests" in s.picked("q-gates"))
        s.pick(1, "Unit tests") // the deselect
        assertEquals(emptySet<String>(), s.picked("q-gates"))
        assertEquals(listOf(Haptic.Select, Haptic.Select, Haptic.Select), haptics.fired)
        advanceUntilIdle()
    }

    // ------------------------------------------------------------ the submit guards

    @Test
    fun aDoubleSubmitSubmitsOnceAndAFailureReArms() = runTest {
        val s = newState()
        s.configure(QuestionPanelFixtures.F2)
        s.pick(0, "Event-driven fold with coalesced commits")
        s.next()
        s.pick(1, "Unit tests")
        s.next() // submit
        s.next() // the double tap: a no-op
        assertEquals(1, submits.calls.size)
        assertEquals(1, haptics.fired.count { it == Haptic.Confirm })

        s.onSubmitFailed("r2") // the RPC failed for the same request
        assertFalse(s.submitted)
        s.next()
        assertEquals("Submit works again after the failure", 2, submits.calls.size)
        assertEquals(2, haptics.fired.count { it == Haptic.Confirm })
        advanceUntilIdle()
    }

    @Test
    fun onSubmitFailedForAnotherRequestChangesNothing() = runTest {
        val s = newState()
        s.configure(QuestionPanelFixtures.F2)
        s.pick(0, "Event-driven fold with coalesced commits")
        s.next()
        s.pick(1, "Unit tests")
        s.next()
        s.onSubmitFailed("old")
        assertTrue("still submitted", s.submitted)
        s.next()
        assertEquals(1, submits.calls.size)
        advanceUntilIdle()
    }

    @Test
    fun canWriteFalseDisablesSubmitAndTheKeptPicksSubmitWhenItReturns() = runTest {
        val s = newState()
        s.configure(QuestionPanelFixtures.F2)
        s.pick(0, "Event-driven fold with coalesced commits")
        s.next()
        s.pick(1, "Unit tests")
        s.canWrite = false
        assertFalse(s.nextEnabled)
        s.next() // the auto-advance / Done path while offline: nothing
        assertTrue("no submit while offline", submits.calls.isEmpty())
        assertEquals("no Confirm while offline", 0, haptics.fired.count { it == Haptic.Confirm })
        s.canWrite = true
        assertTrue(s.nextEnabled)
        s.next()
        assertEquals(
            listOf(
                QuestionAnswer("q-sync", listOf("Event-driven fold with coalesced commits")),
                QuestionAnswer("q-gates", listOf("Unit tests")),
            ),
            submits.calls.single().second,
        )
        advanceUntilIdle()
    }

    @Test
    fun aMissingRequestIdSubmitsNothingAndPlaysNoHaptic() = runTest {
        val s = newState()
        s.configure(QuestionPanelFixtures.F2)
        s.pick(0, "Event-driven fold with coalesced commits")
        s.next()
        s.pick(1, "Unit tests")
        s.configure(null) // the request resolved elsewhere; items kept
        s.next() // the (now stale) advance on the last page
        assertTrue("no submit without a live request", submits.calls.isEmpty())
        assertEquals("no haptic either", 0, haptics.fired.count { it == Haptic.Confirm })
        assertFalse(s.submitted)
        advanceUntilIdle()
    }

    // ------------------------------------------------------------ page guards

    @Test
    fun actionsCarryingAnotherPageIndexAreIgnored() = runTest {
        val s = newState()
        s.configure(QuestionPanelFixtures.F2)
        s.pick(1, "Unit tests") // page 1's row tapped while page 0 shows
        assertEquals(emptySet<String>(), s.picked("q-gates"))
        assertTrue("ignored before the haptic", haptics.fired.isEmpty())
        s.setCustom(1, "nope")
        assertEquals("", s.customText("q-gates"))
        s.otherDone(1)
        assertEquals(0, s.page)
        assertTrue(submits.calls.isEmpty())
        advanceUntilIdle()
    }

    // ------------------------------------------------------------ buildAnswers

    @Test
    fun buildAnswersFollowsTheWorkedExamples() {
        val a = SessionChrome.Question(id = "a", header = "H", text = "Q?", options = listOf("A", "B"), multiSelect = false)
        val m = SessionChrome.Question(id = "m", header = "H", text = "Q?", options = emptyList(), multiSelect = false, multiline = true)
        val e = SessionChrome.Question(id = "e", header = "H", text = "Q?", options = listOf("x"), multiSelect = false)

        // Pick first, then the trimmed text; option order, not tap order.
        assertEquals(
            listOf(QuestionAnswer("a", listOf("B", "x"))),
            buildAnswers(listOf(a), picks = mapOf("a" to setOf("B")), custom = mapOf("a" to " x ")),
        )
        // Multiline: the raw editor text is appended even when empty.
        assertEquals(listOf(QuestionAnswer("m", listOf("draft"))), buildAnswers(listOf(m), emptyMap(), mapOf("m" to "draft")))
        assertEquals(listOf(QuestionAnswer("m", listOf(""))), buildAnswers(listOf(m), emptyMap(), emptyMap()))
        // A multiline question is always answered.
        // Unvisited pages answer with empty labels; duplicate labels both kept.
        val dup = SessionChrome.Question(id = "d", header = "H", text = "Q?", options = listOf("B", "B", "A"), multiSelect = false)
        assertEquals(
            listOf(QuestionAnswer("d", listOf("B", "B")), QuestionAnswer("e", emptyList())),
            buildAnswers(listOf(dup, e), picks = mapOf("d" to setOf("B")), custom = emptyMap()),
        )
        // Non-multiline custom text only when non-empty after the iOS trim
        // (a newline is not whitespace).
        assertEquals(
            listOf(QuestionAnswer("a", listOf("A", "\n"))),
            buildAnswers(listOf(a), picks = mapOf("a" to setOf("A")), custom = mapOf("a" to "\n")),
        )
        assertEquals(
            listOf(QuestionAnswer("a", listOf("A"))),
            buildAnswers(listOf(a), picks = mapOf("a" to setOf("A")), custom = mapOf("a" to " \t ")),
        )
    }

    @Test
    fun theOtherFieldAnswersAloneAfterTheIosTrim() = runTest {
        val s = newState()
        s.configure(single)
        s.setCustom(0, "  my own  ")
        s.otherDone(0) // advance → submit
        assertEquals(listOf(QuestionAnswer("a", listOf("my own"))), submits.calls.single().second)
        advanceUntilIdle()
    }
}
