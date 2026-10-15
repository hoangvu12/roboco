package sh.roboco.android.sessions

import android.util.Log
import androidx.compose.runtime.Immutable
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import uniffi.roboco_core.CoreClient
import uniffi.roboco_core.SearchHit
import uniffi.roboco_core.SessionRow
import uniffi.roboco_core.WorkspaceSnapshot

/** The core's result cap (`AppModel.swift:491`, `limit: 60`). */
const val SEARCH_LIMIT: UInt = 60u

/**
 * iOS `trimmingCharacters(in: .whitespaces)` (`AppModel.swift:489`). The
 * field is single-line (ticket 29), so Kotlin `trim()` — `isWhitespace`,
 * newlines included — agrees with it on every input the field can produce;
 * the core's `split_whitespace` then re-splits the trimmed string. Trim
 * **exactly once, here**: the field publishes raw text, and
 * whitespace-only must read as the Recent state (a query of `"   "` sent
 * to the core would return `[]` and wrongly show "No Results").
 */
fun String.searchTrim(): String = trim()

/**
 * What [SearchModel] needs from a workspace snapshot — revision plus the
 * front page's `recent` rows (`AppModel.swift:387`: `front.recent`, the
 * flat list only — no pinned, no section rows, no project groups). The
 * slim shape keeps the model's tests free of the large FFI record.
 */
@Immutable
data class SearchSource(val revision: ULong, val recent: List<SessionRow>)

/**
 * Blocking-or-suspending core search: `query` (already trimmed) and
 * `limit` in, the core's [SearchHit]s out — **core order**, never
 * re-sorted or de-duplicated in Kotlin. Production wraps
 * [CoreClient.search] on [Dispatchers.Default] via [coreSessionSearch];
 * tests pass a recording fake.
 */
typealias SessionSearch = suspend (query: String, limit: UInt) -> List<SearchHit>

/**
 * The Search tab's derived state (android-ui-parity #49) — what
 * [SearchScreenContent] draws:
 *
 * - [Mode.Loading] — no workspace snapshot yet (whatever the query);
 * - [Mode.Recent] — snapshot present, trimmed query empty: the flat
 *   `front.recent` rows in snapshot order, never the pinned or section
 *   rows (iOS `AppModel.search`'s `guard … else { return
 *   frontPage.sessions }`);
 * - [Mode.Hits] — the core's `search(q, 60)` rows, core order, with the
 *   ids of archived hits in [archivedIds] (per-row archived actions).
 *
 * [rows] stays **FFI [SessionRow] records**: ticket 41's list builder maps
 * them with 37's `SessionRowVM.from` and hands the record to 44 as
 * `ctx.row` — this model never maps rows. [query] is the trimmed query
 * these rows answer (`""` for Loading / Recent).
 */
@Immutable
data class SearchResults(
    val mode: Mode,
    val query: String,
    val rows: List<SessionRow>,
    /** Ids of hits whose FFI `SessionRow.archived` is true (hits only). */
    val archivedIds: Set<String>,
) {
    enum class Mode { Loading, Recent, Hits }

    /** The no-results view: a search that finished with zero hits. */
    val showsNoResults: Boolean get() = mode == Mode.Hits && rows.isEmpty()

    companion object {
        /** The state before the first snapshot (and the `stateIn` seed). */
        val Loading = SearchResults(Mode.Loading, "", emptyList(), emptySet())
    }
}

/**
 * The Search tab's model (android-ui-parity #49) — the off-main port of
 * iOS's `AppModel.search(_:)` + `SearchViewController.updateSearchResults`
 * (`AppModel.swift:488-492`, `SessionListController.swift:532-538`):
 *
 * ```
 * combine(trimmedQuery, source) ──(query, revision)──▶ mapLatest { derive } ──▶ StateFlow
 * ```
 *
 * - **Trim once** ([searchTrim]) and drop re-emissions whose trimmed value
 *   did not change — `"tool "` after `"tool"` never re-runs the search;
 * - **re-run on revision** (the superset of iOS's `AppModel` notification,
 *   which only fires when the front page / archive / hosts changed); the
 *   same revision never re-runs, whatever the `recent` list's identity;
 * - **latest query wins** ([mapLatest]): a newer query cancels and
 *   discards an older, still-running search — a slow `"a"` can never
 *   overwrite a fast `"ab"` (the anti-reference `#609` calls the FFI
 *   during composition, once per keystroke; this model never runs it
 *   there);
 * - **[derive] runs on [work]** — [Dispatchers.Default] in production,
 *   because the FFI `search` call blocks; tests pass the test scheduler's
 *   dispatcher so virtual time covers the delays;
 * - a failing search reports through [onError] (production:
 * `android.util.Log`) and yields an empty `Hits` — a blank screen after
 * an engine hiccup, not a crash; a [CancellationException] is rethrown,
 * never swallowed into results.
 */
class SearchModel(
    query: Flow<String>,
    source: Flow<SearchSource?>,
    private val search: SessionSearch,
    scope: CoroutineScope,
    started: SharingStarted = SharingStarted.WhileSubscribed(5_000),
    private val work: CoroutineDispatcher = Dispatchers.Default,
    private val onError: (Throwable) -> Unit = { Log.w(TAG, "search failed", it) },
) {
    /**
     * The latest [SearchResults]; `Loading` until the first derivation
     * lands. While a newer query is still being searched the **previous**
     * value stays on screen — no blank flash, no spinner.
     *
     * `mapLatest` is the cancellation contract (a newer emission cancels
     * the running derivation); it is `@ExperimentalCoroutinesApi` in the
     * pinned coroutines, opted in here, at its only use.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val results: StateFlow<SearchResults> =
        combine(
            query.map { it.searchTrim() }.distinctUntilChanged(),
            source,
        ) { q, s -> q to s }
            .distinctUntilChanged { a, b ->
                a.first == b.first &&
                    a.second?.revision == b.second?.revision &&
                    (a.second == null) == (b.second == null)
            }
            .mapLatest { (q, s) -> withContext(work) { derive(q, s) } }
            .stateIn(scope, started, SearchResults.Loading)

    /**
     * One derivation (`AppModel.search`): no snapshot → `Loading`; empty
     * trimmed query → `Recent`; otherwise the trimmed query and
     * [SEARCH_LIMIT] to [search], the hits in core order with the
     * archived ids collected. Runs on [work], never the main thread.
     */
    private suspend fun derive(q: String, s: SearchSource?): SearchResults {
        if (s == null) return SearchResults.Loading
        if (q.isEmpty()) return SearchResults(SearchResults.Mode.Recent, "", s.recent, emptySet())
        val hits = try {
            search(q, SEARCH_LIMIT)
        } catch (e: CancellationException) {
            // A superseded search: let mapLatest discard it, never report it.
            throw e
        } catch (e: Throwable) {
            onError(e)
            emptyList()
        }
        // Second guard: `mapLatest` already discards a cancelled derivation
        // at its suspension points, but a blocking FFI call that finished
        // after cancellation must not publish either.
        coroutineContext.ensureActive()
        return SearchResults(
            mode = SearchResults.Mode.Hits,
            query = q,
            rows = hits.map { it.session },
            archivedIds = hits.filter { it.session.archived }.map { it.session.id }.toSet(),
        )
    }
}

private const val TAG = "RobocoSearch"

/**
 * [WorkspaceSnapshot] → [SearchSource]: the revision plus `front.recent`.
 * Called off composition (the stateful [SearchScreen] maps the feed's
 * flow with it).
 */
fun WorkspaceSnapshot.toSearchSource(): SearchSource = SearchSource(revision, front.recent)

/**
 * The production [SessionSearch]: whatever client `client()` holds right
 * now (null before `CoreStartState.Ready` — no hits, no error), with the
 * FFI call itself on [Dispatchers.Default] — `CoreClient.search` blocks
 * and must never run on main. Pass `app::clientOrNull` (ticket 71's
 * seam, the same accessor tickets 42/48 resolve at call time).
 */
fun coreSessionSearch(client: () -> CoreClient?): SessionSearch = { q, n ->
    val c = client()
    if (c == null) {
        emptyList()
    } else {
        withContext(Dispatchers.Default) { c.search(q, n) }
    }
}
