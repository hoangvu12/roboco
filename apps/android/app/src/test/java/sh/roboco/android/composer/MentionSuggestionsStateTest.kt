package sh.roboco.android.composer

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.roboco.android.design.Cue
import sh.roboco.android.design.Feedback
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.NoFeedback
import uniffi.roboco_core.FileMatch

/**
 * [MentionSuggestionsState] (android-ui-parity #86): every acceptance row of
 * the ticket's Behaviour table — the 120 ms debounce (exactly one search for
 * the last query), the ≤ 5-row cut, the empty/throwing results, the
 * stale-drop and unfocused/unabled drop rules, the hide-on-space, the
 * selection rule, `canWrite`/`mentionSearch` gating, blur, the post-send
 * quiet, and [MentionSuggestionsState.pick] (the worked-examples table, the
 * `Select` haptic, the stale-range guard) — plus the pure helpers
 * [mentionRowName] / [mentionRowDir] / [applyMentionPick].
 *
 * Robolectric (the `FileMatch` records are uniffi types; constructing them
 * never loads the native core) + `runTest` on the `StandardTestDispatcher`:
 * `advanceTimeBy`/`runCurrent` drive the virtual clock the debounce job
 * suspends on. Edits are applied exactly as [MentionSuggestionsBinding]
 * would: write `controller.value`, then call
 * [MentionSuggestionsState.onTextChanged] / [onSelectionChanged].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MentionSuggestionsStateTest {

    /** Haptics recorder (05 ships only `NoFeedback`; the ticket defines the fake here). */
    private class RecordingFeedback : Feedback {
        val haptics = mutableListOf<Haptic>()
        override fun haptic(haptic: Haptic) {
            haptics += haptic
        }

        override fun cue(cue: Cue) = Unit
    }

    private val recording = RecordingFeedback()

    /** Eight distinct matches, for the `take(5)` cut. */
    private val files = (0 until 8).map { FileMatch("src/file$it.rs", false) }

    private val queries = mutableListOf<String>()
    private var searchThrows = false
    private var searchImpl: (String) -> List<FileMatch> = { emptyList() }

    /** When set, the search for this query blocks (in `NonCancellable`) until [gate] opens. */
    private var gatedQuery: String? = null
    private var gate: CompletableDeferred<Unit>? = null

    private val search: (suspend (String) -> List<FileMatch>) = { q ->
        queries += q
        if (q == gatedQuery) withContext(NonCancellable) { gate!!.await() }
        if (searchThrows) throw IllegalStateException("engine offline")
        searchImpl(q)
    }

    /** A focused controller + state pair over this test's scope and fake search. */
    private fun TestScope.fixture(): Pair<ComposerController, MentionSuggestionsState> {
        val controller = ComposerController(scope = backgroundScope, feedback = NoFeedback)
        controller.mentionSearch = search
        val state = MentionSuggestionsState(controller, backgroundScope, recording)
        state.isFocused = { true }
        return controller to state
    }

    /** One user edit, as the binding delivers it: the write, then `textDidChange`. */
    private fun type(controller: ComposerController, state: MentionSuggestionsState, text: String, caret: Int = text.length) {
        controller.value = TextFieldValue(text, TextRange(caret))
        state.onTextChanged()
    }

    // ── pure helpers ────────────────────────────────────────────────────────

    @Test
    fun rowLabelsMatchEveryRowOfTheTable() {
        val rows = listOf(
            Triple("crates/ui/src/layout/mod.rs", "mod.rs", "crates/ui/src/layout"),
            Triple("README.md", "README.md", ""),
            Triple("src", "src", ""),
            Triple("a//b.rs", "b.rs", "a/"),
            Triple("src/components/", "components", "src/components"),
            Triple("/abs/x", "x", "/abs"),
            Triple("", "", ""),
        )
        for ((path, name, dir) in rows) {
            assertEquals("name of \"$path\"", name, mentionRowName(path))
            assertEquals("dir of \"$path\"", dir, mentionRowDir(path))
        }
    }

    @Test
    fun applyMentionPickRejectsARangeThatNoLongerFits() {
        // "Look at @lay" is 12 chars: the query spans [8, 12) (ticket 53's table).
        val q = MentionQuery(8, 12, "lay")
        assertNull("end past the text", applyMentionPick("short", q, "@x"))
        assertNull("start no longer at an @", applyMentionPick("Look at Xlay", q, "@x"))
        assertNull("empty text", applyMentionPick("", MentionQuery(0, 1, ""), "@x"))
        val v = applyMentionPick("Look at @lay", q, "@mod.rs")
        assertEquals("Look at @mod.rs ", v?.text)
        assertEquals(TextRange(16), v?.selection)
    }

    // ── debounce ────────────────────────────────────────────────────────────

    @Test
    fun typingIsDebouncedToOneSearchForTheLastQuery() = runTest {
        val (controller, state) = fixture()
        type(controller, state, "@")
        advanceTimeBy(30)
        type(controller, state, "@l")
        advanceTimeBy(30)
        type(controller, state, "@la")
        advanceTimeBy(30)
        type(controller, state, "@lay")
        advanceTimeBy(119)
        assertEquals("no search before 120 ms", 0, queries.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("lay"), queries)
    }

    // ── results ─────────────────────────────────────────────────────────────

    @Test
    fun aSearchReturningEightMatchesShowsTheFirstFiveRows() = runTest {
        val (controller, state) = fixture()
        searchImpl = { files }
        type(controller, state, "@f")
        advanceTimeBy(120)
        runCurrent()
        assertEquals(files.take(5), state.files)
        assertTrue(controller.suggestionsVisible)
    }

    @Test
    fun anEmptySearchResultHidesTheCardButKeepsTheRows() = runTest {
        val (controller, state) = fixture()
        searchImpl = { files }
        type(controller, state, "@f")
        advanceTimeBy(120)
        runCurrent()
        assertTrue(controller.suggestionsVisible)
        searchImpl = { emptyList() }
        type(controller, state, "@fi")
        advanceTimeBy(120)
        runCurrent()
        assertFalse(controller.suggestionsVisible)
        assertEquals("the rows stay on the card through the fade-out", files.take(5), state.files)
    }

    @Test
    fun aThrowingSearchIsTreatedAsAnEmptyResult() = runTest {
        val (controller, state) = fixture()
        searchImpl = { files }
        type(controller, state, "@f")
        advanceTimeBy(120)
        runCurrent()
        assertTrue(controller.suggestionsVisible)
        searchThrows = true
        type(controller, state, "@fi")
        advanceTimeBy(120)
        runCurrent()
        assertFalse("an exception other than cancellation means []", controller.suggestionsVisible)
    }

    @Test
    fun aResultForAQueryTheUserTypedPastIsDropped() = runTest {
        val (controller, state) = fixture()
        val stale = listOf(FileMatch("stale-la.rs", false))
        val live = listOf(FileMatch("live-lay.rs", false))
        searchImpl = { q -> if (q == "la") stale else live }
        gatedQuery = "la"
        gate = CompletableDeferred()
        type(controller, state, "@la")
        advanceTimeBy(120)
        runCurrent() // the "la" search is blocked on the gate
        assertEquals(listOf("la"), queries)
        type(controller, state, "@lay") // cancels the "la" job, launches "lay"
        gate!!.complete(Unit)
        runCurrent() // the "la" result arrives — and is dropped
        assertEquals(emptyList<FileMatch>(), state.files)
        assertFalse(controller.suggestionsVisible)
        advanceTimeBy(120)
        runCurrent()
        assertEquals("the \"lay\" result is applied", live, state.files)
        assertTrue(controller.suggestionsVisible)
    }

    @Test
    fun typingASpaceAfterAQueryHidesTheCardAtOnceAndCancelsTheSearch() = runTest {
        val (controller, state) = fixture()
        searchImpl = { files }
        type(controller, state, "@lay")
        advanceTimeBy(120)
        runCurrent()
        assertTrue(controller.suggestionsVisible)
        type(controller, state, "@lay ") // the space ends the query
        assertFalse("hidden at once, no 120 ms wait", controller.suggestionsVisible)
        assertNull(state.query)
        advanceTimeBy(300)
        runCurrent()
        assertEquals("the pending job was cancelled", listOf("lay"), queries)
    }

    @Test
    fun aSelectionChangeRecomputesOnlyWithAnActiveQuery() = runTest {
        val (controller, state) = fixture()
        searchImpl = { files }
        type(controller, state, "@lay")
        advanceTimeBy(120)
        runCurrent()
        assertEquals(1, queries.size)
        // Caret moved, text same, query active: recompute (a new search).
        controller.value = TextFieldValue("@lay", TextRange(2))
        state.onSelectionChanged()
        advanceTimeBy(120)
        runCurrent()
        assertEquals(2, queries.size)
        // No active query: moving the caret does nothing.
        controller.value = TextFieldValue("hello", TextRange(3))
        state.onSelectionChanged()
        advanceTimeBy(300)
        runCurrent()
        assertEquals(2, queries.size)
    }

    // ── gating: canWrite / mentionSearch ───────────────────────────────────

    @Test
    fun anOfflineEngineDisablesMentionsEntirely() = runTest {
        val (controller, state) = fixture()
        controller.canWrite = false
        type(controller, state, "@x")
        advanceTimeBy(300)
        runCurrent()
        assertEquals(0, queries.size)
        assertNull(state.query)
        assertFalse(controller.suggestionsVisible)
    }

    @Test
    fun disableHidesAVisibleCardAndForgetsTheQuery() = runTest {
        val (controller, state) = fixture()
        searchImpl = { files }
        type(controller, state, "@lay")
        advanceTimeBy(120)
        runCurrent()
        assertTrue(controller.suggestionsVisible)
        // What the binding's offline observer runs when canWrite flips false.
        controller.canWrite = false
        state.disable()
        assertFalse(controller.suggestionsVisible)
        assertNull(state.query)
    }

    @Test
    fun withoutASearchLambdaNothingIsEverSearchedOrShown() = runTest {
        val (controller, state) = fixture()
        controller.mentionSearch = null
        type(controller, state, "@x")
        advanceTimeBy(300)
        runCurrent()
        assertEquals(0, queries.size)
        assertNull(state.query)
        assertFalse(controller.suggestionsVisible)
    }

    // ── blur, unfocused results, send ──────────────────────────────────────

    @Test
    fun onBlurHidesAndCancelsAnInFlightSearchButKeepsTheQuery() = runTest {
        val (controller, state) = fixture()
        searchImpl = { files }
        gatedQuery = "lay"
        gate = CompletableDeferred()
        type(controller, state, "@lay")
        advanceTimeBy(120)
        runCurrent() // the search is in flight, blocked on the gate
        assertEquals(listOf("lay"), queries)
        state.onBlur()
        assertFalse(controller.suggestionsVisible)
        assertNotNull("iOS keeps mentionQuery on blur", state.query)
        gate!!.complete(Unit) // the result arrives late
        runCurrent()
        advanceTimeBy(10)
        runCurrent()
        assertFalse(controller.suggestionsVisible)
        assertEquals("the late result is dropped", emptyList<FileMatch>(), state.files)
    }

    @Test
    fun aResultArrivingWhileTheFieldIsUnfocusedIsDropped() = runTest {
        val (controller, state) = fixture()
        searchImpl = { files }
        state.isFocused = { false }
        type(controller, state, "@lay")
        advanceTimeBy(120)
        runCurrent()
        assertEquals("the search ran", listOf("lay"), queries)
        assertFalse("the result was dropped", controller.suggestionsVisible)
        assertEquals(emptyList<FileMatch>(), state.files)
    }

    @Test
    fun afterASentSendTheCardStaysHiddenAndNoSearchStarts() = runTest {
        val (controller, state) = fixture()
        searchImpl = { files }
        type(controller, state, "@lay")
        advanceTimeBy(120)
        runCurrent()
        assertTrue(controller.suggestionsVisible)
        controller.onSend = { _, _, _ -> SendResult.Sent }
        controller.send(DeliveryMode.Queue)
        assertTrue("Sent clears the text", controller.value.text.isEmpty())
        assertFalse(controller.suggestionsVisible)
        // The textChanged the clear would trigger (the binding delivers it).
        state.onTextChanged()
        assertNull(state.query)
        advanceTimeBy(300)
        runCurrent()
        assertEquals("no new search over the cleared text", 1, queries.size)
    }

    // ── pick ───────────────────────────────────────────────────────────────

    /** One worked-example row: before + caret, pick (and any token seed), after + caret. */
    private data class PickExample(
        val before: String,
        val caret: Int,
        val file: FileMatch,
        val after: String,
        val caretAfter: Int,
        val seed: List<FileMatch> = emptyList(),
    )

    @Test
    fun pickMatchesEveryRowOfTheWorkedExamplesTable() = runTest {
        val rows = listOf(
            PickExample("Look at @lay", 12, FileMatch("crates/ui/src/layout/mod.rs", false), "Look at @mod.rs ", 16),
            PickExample("@", 1, FileMatch("README.md", false), "@README.md ", 11),
            PickExample("fix @foo bar", 6, FileMatch("src/food.rs", false), "fix @food.rs oo bar", 13),
            PickExample("a\n@co", 5, FileMatch("src/components", true), "a\n@components ", 14),
            PickExample(
                "see @lib", 8, FileMatch("crates/core/src/lib.rs", false), "see @src/lib.rs ", 16,
                seed = listOf(FileMatch("src/lib.rs", false)),
            ),
        )
        for (row in rows) {
            val (controller, state) = fixture()
            row.seed.forEach { controller.mentions.token(it) }
            controller.value = TextFieldValue(row.before, TextRange(row.caret))
            state.onTextChanged() // sets query, exactly as the binding would
            state.pick(row.file)
            assertEquals("text after ${row.before}", row.after, controller.value.text)
            assertEquals("caret after ${row.before}", TextRange(row.caretAfter), controller.value.selection)
        }
    }

    @Test
    fun pickFiresTheSelectHapticOnceAndClearsQueryVisibilityAndKeepsTheToken() = runTest {
        val (controller, state) = fixture()
        controller.value = TextFieldValue("Look at @lay", TextRange(11))
        state.onTextChanged()
        state.pick(FileMatch("crates/ui/src/layout/mod.rs", false))
        assertEquals(listOf(Haptic.Select), recording.haptics)
        assertNull(state.query)
        assertFalse(controller.suggestionsVisible)
        assertEquals(
            mapOf("@mod.rs" to FileMatch("crates/ui/src/layout/mod.rs", false)),
            controller.mentions.tokens,
        )
    }

    @Test
    fun pickWithoutAQueryIsANoOp() = runTest {
        val (controller, state) = fixture()
        state.pick(FileMatch("src/lib.rs", false))
        assertTrue("no haptic", recording.haptics.isEmpty())
        assertEquals("", controller.value.text)
        assertTrue(controller.mentions.tokens.isEmpty())
    }

    @Test
    fun pickWithAStaleRangeHidesWithoutHapticOrToken() = runTest {
        val (controller, state) = fixture()
        searchImpl = { files }
        type(controller, state, "Look at @lay")
        advanceTimeBy(120)
        runCurrent()
        assertTrue(controller.suggestionsVisible)
        // The text was shortened programmatically, without onTextChanged.
        controller.value = TextFieldValue("x", TextRange(1))
        state.pick(FileMatch("src/lib.rs", false))
        assertTrue("no haptic", recording.haptics.isEmpty())
        assertEquals("no token added", emptyMap<String, FileMatch>(), controller.mentions.tokens)
        assertFalse("hidden", controller.suggestionsVisible)
        assertEquals("no text inserted", "x", controller.value.text)
    }
}
