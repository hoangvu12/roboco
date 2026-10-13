package sh.roboco.android.sessions

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.roboco_core.SearchField
import uniffi.roboco_core.SearchHit

/**
 * The Search model's logic (android-ui-parity #49) — plain JUnit +
 * `kotlinx-coroutines-test`, virtual time over [StandardTestDispatcher]:
 * the trim / empty → Recent rule, the 60-hit limit, core order, archived
 * ids, latest-query-wins, the revision re-run rule, and the error /
 * cancellation paths.
 *
 * The model is built with `work = <the test scheduler's dispatcher>` and a
 * recording `onError`, so no `Dispatchers.Default` and no `android.util.Log`
 * are touched; the rows stay FFI records (no mapping happens here — 41
 * maps them with 37's `from`). Rows come from [row] (the shared
 * `WorkspaceFixtures` builder) and [hit].
 */
class SearchModelTest {

    // ------------------------------------------------------------ the fakes

    /**
     * The recording [SessionSearch]: logs every `(query, limit)` call,
     * delays by [delayOf] (virtual ms) before answering, throws [error]
     * when set and serves [hits] — in the given order, like the core.
     */
    private class FakeSearch {
        val calls = CopyOnWriteArrayList<Pair<String, UInt>>()
        var delayOf: (String) -> Long = { 0L }
        var error: Throwable? = null
        var hits: List<SearchHit> = emptyList()

        val asSearch: SessionSearch = { q, n ->
            calls += q to n
            delay(delayOf(q))
            error?.let { throw it }
            hits
        }
    }

    /** One hit over a neutral FFI row; `archived` sets the row's flag too. */
    private fun hit(id: String, archived: Boolean = false): SearchHit = SearchHit(
        session = row(id).copy(archived = archived, title = "Session $id"),
        score = 100u,
        field = SearchField.TITLE,
    )

    private val source = SearchSource(revision = 1uL, recent = listOf(row("r1"), row("r2"), row("r3")))

    /** The model + its driving state flows + everything its collector saw. */
    private class Harness(
        val model: SearchModel,
        val query: MutableStateFlow<String>,
        val sourceFlow: MutableStateFlow<SearchSource?>,
        val seen: MutableList<SearchResults>,
    )

    /**
     * Builds the model over mutable state flows and holds one collector
     * for the whole test, so `WhileSubscribed` keeps the sharing up.
     *
     * The model's sharing scope is a **foreground** scope on the test's
     * dispatcher with its own, unparented [Job] (cancelled when
     * [TestScope.backgroundScope] is, at the test's end). It must not be
     * `backgroundScope` itself: every task dispatched from a background
     * coroutine — the `stateIn` sharing, `mapLatest`'s derivation, the
     * `withContext(work)` hop and the fake's `delay` — inherits the
     * background marker, and `advanceUntilIdle()` returns as soon as no
     * *foreground* task is queued (`TestCoroutineScheduler.advanceUntilIdle`
     * = `advanceUntilIdleOr { events.none(isForeground) }`), so the
     * derivation never ran and the state stayed `Loading`. It must not be a
     * child of the test's own job either — the never-completing sharing
     * coroutine would keep `runTest` from finishing.
     */
    private fun TestScope.harness(
        fake: FakeSearch,
        source: SearchSource?,
        onError: (Throwable) -> Unit = {},
    ): Harness {
        val query = MutableStateFlow("")
        val sourceFlow = MutableStateFlow(source)
        val modelJob = Job()
        backgroundScope.coroutineContext.job.invokeOnCompletion { modelJob.cancel() }
        val modelScope = CoroutineScope(this.coroutineContext + modelJob)
        val model = SearchModel(
            query = query,
            source = sourceFlow,
            search = fake.asSearch,
            scope = modelScope,
            work = StandardTestDispatcher(testScheduler),
            onError = onError,
        )
        val seen = CopyOnWriteArrayList<SearchResults>()
        // Unconfined: the collector subscribes at once, so the sharing
        // starts before the first advance.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.results.collect { seen += it }
        }
        return Harness(model, query, sourceFlow, seen)
    }

    // ------------------------------------------------------ trim / states

    @Test
    fun aNullSourceStaysLoadingForEveryQueryAndNeverCallsTheCore() = runTest {
        val fake = FakeSearch()
        val h = harness(fake, source = null)
        h.query.value = ""
        advanceUntilIdle()
        assertSame(SearchResults.Loading, h.model.results.value)
        h.query.value = "tool"
        advanceUntilIdle()
        assertSame(SearchResults.Loading, h.model.results.value)
        assertTrue("no search call before the first snapshot", fake.calls.isEmpty())
    }

    @Test
    fun aWhitespaceQueryShowsTheRecentRowsInSnapshotOrder() = runTest {
        val fake = FakeSearch()
        val h = harness(fake, source)
        h.query.value = "   "
        advanceUntilIdle()
        val value = h.model.results.value
        assertEquals(SearchResults.Mode.Recent, value.mode)
        assertEquals(source.recent, value.rows)
        assertEquals("", value.query)
        assertTrue(value.archivedIds.isEmpty())
        assertTrue("whitespace is the Recent state, not a search", fake.calls.isEmpty())
    }

    @Test
    fun aPaddedQueryIsTrimmedSentOnceAndKeptInCoreOrder() = runTest {
        val fake = FakeSearch()
        fake.hits = listOf(hit("a"), hit("b", archived = true), hit("c"))
        val h = harness(fake, source)
        h.query.value = "  tool "
        advanceUntilIdle()
        val value = h.model.results.value
        assertEquals(SearchResults.Mode.Hits, value.mode)
        assertEquals("tool", value.query)
        assertEquals(listOf("a", "b", "c"), value.rows.map { it.id })
        assertEquals(setOf("b"), value.archivedIds)
        assertEquals(listOf("tool" to SEARCH_LIMIT), fake.calls)
    }

    @Test
    fun aQueryWithOnlyTrailingSpaceDoesNotRerunTheSearch() = runTest {
        val fake = FakeSearch()
        fake.hits = listOf(hit("a"))
        val h = harness(fake, source)
        h.query.value = "tool"
        advanceUntilIdle()
        assertEquals(1, fake.calls.size)
        h.query.value = "tool "
        advanceUntilIdle()
        assertEquals("the trimmed query is unchanged: one call", 1, fake.calls.size)
        assertEquals("tool", h.model.results.value.query)
    }

    // ------------------------------------------------------- latest wins

    @Test
    fun theLatestQueryWinsAndTheSlowOneNeverPublishes() = runTest {
        val fake = FakeSearch()
        // "a" takes 100 ms, "ab" 10 ms; "ab" is typed 5 ms after "a".
        fake.delayOf = { q -> if (q == "a") 100L else 10L }
        fake.hits = listOf(hit("ab-1"))
        val h = harness(fake, source)
        h.query.value = "a"
        advanceTimeBy(5)
        h.query.value = "ab"
        advanceUntilIdle()
        val value = h.model.results.value
        assertEquals(SearchResults.Mode.Hits, value.mode)
        assertEquals("ab", value.query)
        assertTrue("no emission ever carried the superseded query", h.seen.none { it.query == "a" })
        assertEquals(listOf("a" to SEARCH_LIMIT, "ab" to SEARCH_LIMIT), fake.calls)
    }

    // ------------------------------------------------------- the revision

    @Test
    fun aRevisionBumpRerunsOnceAndTheSameRevisionDoesNot() = runTest {
        val fake = FakeSearch()
        fake.hits = listOf(hit("x"))
        val h = harness(fake, source)
        h.query.value = "tool"
        advanceUntilIdle()
        assertEquals(1, fake.calls.size)

        // A new revision re-runs the current query.
        h.sourceFlow.value = source.copy(revision = 2uL)
        advanceUntilIdle()
        assertEquals(2, fake.calls.size)

        // The same revision with a different recent list is not a re-run:
        // the revision is the re-run key (a superset of iOS's AppModel
        // notifications).
        h.sourceFlow.value = SearchSource(revision = 2uL, recent = listOf(row("r9")))
        advanceUntilIdle()
        assertEquals("same revision: no third call", 2, fake.calls.size)
    }

    // --------------------------------------------------- errors / cancel

    @Test
    fun aThrowingCoreYieldsEmptyHitsAndReportsOnce() = runTest {
        val fake = FakeSearch()
        fake.error = IllegalStateException("engine hiccup")
        val errors = CopyOnWriteArrayList<Throwable>()
        val h = harness(fake, source, onError = { errors += it })
        h.query.value = "tool"
        advanceUntilIdle()
        val value = h.model.results.value
        assertEquals(SearchResults.Mode.Hits, value.mode)
        assertTrue(value.rows.isEmpty())
        assertTrue(value.showsNoResults)
        assertEquals(1, errors.size)
    }

    @Test
    fun aCancellationExceptionIsNotSwallowedIntoResults() = runTest {
        val fake = FakeSearch()
        fake.error = CancellationException("superseded")
        val errors = CopyOnWriteArrayList<Throwable>()
        val h = harness(fake, source, onError = { errors += it })
        h.query.value = "tool"
        advanceUntilIdle()
        // The cancellation propagates (mapLatest discards the derivation);
        // it never becomes an empty Hits and never reaches onError. The
        // previous value — the initial Loading — is what stays.
        assertEquals(SearchResults.Mode.Loading, h.model.results.value.mode)
        assertTrue(errors.isEmpty())
    }

    // ------------------------------------------------------ the padding

    @Test
    fun theBottomPaddingFollowsTheWorkedTable() {
        // Gesture nav, barBottom = 24, contentPadding = 150:
        // IME down (lift 0) → the chrome padding; IME 300 (lift 284) →
        // 24 + 284 + 62 + 8 = 378.
        assertEquals(150f, searchBottomPadding(150f, 24f, 0f), 1e-3f)
        assertEquals(378f, searchBottomPadding(150f, 24f, 284f), 1e-3f)
        // The two regimes meet: the accessory band (150) wins until the
        // search row's band grows past it.
        assertEquals(150f, searchBottomPadding(150f, 24f, 56f), 1e-3f)
        assertEquals(172f, searchBottomPadding(150f, 24f, 78f), 1e-3f)
    }
}
