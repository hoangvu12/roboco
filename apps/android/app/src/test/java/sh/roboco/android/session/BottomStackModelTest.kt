package sh.roboco.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BottomStackModel] and [isStructural] (android-ui-parity #74), plain
 * JUnit: the structural rule of `ios:Session/SessionViewController.swift:511`
 * (questions flip, queue count, banner flip — never editing), the
 * `from(chrome, editing)` mapping, and the visibility rules of `:497-502`.
 */
class BottomStackModelTest {

    private fun chrome(
        banner: SessionChrome.Banner = SessionChrome.Banner.None,
        questions: SessionChrome.Questions? = null,
        queue: List<SessionChrome.QueuedItem> = emptyList(),
    ) = SessionChrome(banner = banner, questions = questions, queue = queue)

    private fun queueOf(count: Int): List<SessionChrome.QueuedItem> =
        (0 until count).map { SessionChrome.QueuedItem(id = "q$it", text = "m$it", gate = null) }

    // -------------------------------------------------------- isStructural

    @Test
    fun structuralChangesAnimate() {
        // Questions absent ↔ present.
        assertTrue(isStructural(BottomStackModel(), BottomStackModel(questionsShown = true)))
        assertTrue(isStructural(BottomStackModel(questionsShown = true), BottomStackModel()))
        // The queue count changed — 1→2, 3→4, 2→0.
        assertTrue(isStructural(BottomStackModel(queueCount = 1), BottomStackModel(queueCount = 2)))
        assertTrue(isStructural(BottomStackModel(queueCount = 3), BottomStackModel(queueCount = 4)))
        assertTrue(isStructural(BottomStackModel(queueCount = 2), BottomStackModel(queueCount = 0)))
        // The banner flipped none ↔ some.
        assertTrue(isStructural(BottomStackModel(), BottomStackModel(bannerShown = true)))
        assertTrue(isStructural(BottomStackModel(bannerShown = true), BottomStackModel()))
        // The Limited banner appeared or vanished (the failover port).
        assertTrue(isStructural(BottomStackModel(), BottomStackModel(limitedShown = true)))
        assertTrue(isStructural(BottomStackModel(limitedShown = true), BottomStackModel()))
    }

    @Test
    fun nonStructuralChangesSnap() {
        // Working → Sending keeps bannerShown true.
        val working = BottomStackModel.from(
            chrome(banner = SessionChrome.Banner.Working(0L, "Working")),
            editing = false,
        )
        val sending = BottomStackModel.from(chrome(banner = SessionChrome.Banner.Sending), editing = false)
        assertTrue(working.bannerShown)
        assertTrue(sending.bannerShown)
        assertFalse(isStructural(working, sending))
        // Editing is never structural (`:432-433`, `:451`).
        assertFalse(isStructural(BottomStackModel(editing = false), BottomStackModel(editing = true)))
        assertFalse(isStructural(BottomStackModel(editing = true), BottomStackModel(editing = false)))
        // Identical models.
        assertFalse(isStructural(BottomStackModel(), BottomStackModel()))
        assertFalse(isStructural(BottomStackModel(queueCount = 2, editing = true), BottomStackModel(queueCount = 2, editing = true)))
    }

    // ------------------------------------------------------------- from()

    @Test
    fun fromMapsTheChromeFields() {
        val none = chrome()
        val fromNoneEditing = BottomStackModel.from(none, editing = true)
        assertFalse(fromNoneEditing.bannerShown)
        assertTrue(fromNoneEditing.editing)
        assertFalse(fromNoneEditing.questionsShown)
        assertEquals(0, fromNoneEditing.queueCount)

        val fromSending = BottomStackModel.from(chrome(banner = SessionChrome.Banner.Sending), editing = false)
        assertTrue(fromSending.bannerShown)
        assertFalse(fromSending.editing)

        val fromNone = BottomStackModel.from(none, editing = false)
        assertFalse(fromNone.bannerShown)
        assertFalse(fromNone.editing)
    }

    /** `from(None, false)` vs `from(None, true)` is NOT structural (editing never is). */
    @Test
    fun editingAloneIsNeverStructuralThroughFrom() {
        val idle = BottomStackModel.from(chrome(), editing = false)
        val editing = BottomStackModel.from(chrome(), editing = true)
        assertFalse(isStructural(idle, editing))
        assertFalse(isStructural(editing, idle))
    }

    @Test
    fun fromCountsTheQueueAndMapsQuestions() {
        val withQueue = BottomStackModel.from(chrome(queue = queueOf(3)), editing = false)
        assertEquals(3, withQueue.queueCount)
        val asking = BottomStackModel.from(
            chrome(questions = SessionChrome.Questions("r1", emptyList())),
            editing = false,
        )
        assertTrue(asking.questionsShown)
        assertFalse(asking.composerShown)
    }

    // ---------------------------------------------------------- visibility

    @Test
    fun questionsHideTheComposerAndTheQueue() {
        val asking = BottomStackModel(questionsShown = true, queueCount = 2)
        assertFalse(asking.queueShown)
        assertFalse(asking.composerShown)
        assertFalse(asking.pillShown)
    }

    @Test
    fun theQueueHidesAtCountZero() {
        assertFalse(BottomStackModel(queueCount = 0).queueShown)
        assertTrue(BottomStackModel(queueCount = 1).queueShown)
        // …unless questions show.
        assertFalse(BottomStackModel(queueCount = 1, questionsShown = true).queueShown)
    }

    @Test
    fun thePillShowsForABannerOrAnEdit() {
        assertFalse(BottomStackModel().pillShown)
        assertTrue(BottomStackModel(bannerShown = true).pillShown)
        assertTrue(BottomStackModel(editing = true).pillShown)
        assertTrue(BottomStackModel(bannerShown = true, editing = true).pillShown)
    }
}
