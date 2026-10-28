package sh.roboco.android.shell

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import sh.roboco.android.RobocoApplication

/**
 * The Search tab's state (android-ui-parity #29) — the port of
 * `SearchViewController`'s `query`
 * (`ios:Threads/SessionListController.swift:516-539`): the query is the raw
 * field text (spaces are trimmed only by ticket 49's search, never here), it
 * survives tab switches and activity recreations — the holder lives outside
 * composition, in [RobocoApplication] — and it is gone after process death
 * (nothing here is written to instance state, unlike `MainTabState`).
 *
 * `previousTab` is **not** stored here: it is [MainTabState.previousTab]
 * (ticket 26), the single owner of the "tab you came from".
 */
@Stable
class SearchTabState {

    /**
     * The field's text. `androidx.compose.foundation.text.input`'s
     * state-based field owns the value, selection and undo stack; edits
     * while the tab is away are invisible but kept.
     */
    val text: TextFieldState = TextFieldState()

    /**
     * Raw field text; snapshot-observable (`TextFieldState.text` is
     * snapshot state). Ticket 49 reads this (or [queryFlow]) and trims it
     * before matching — this ticket publishes the raw text.
     */
    val query: String get() = text.text.toString()

    /**
     * Every text change, raw and untrimmed, distinct until changed —
     * `updateSearchResults` fires on every edit
     * (`SessionListController.swift:534-537`).
     */
    fun queryFlow(): Flow<String> = snapshotFlow { query }.distinctUntilChanged()

    /**
     * Incremented when the tab is **entered**; the field focuses once per
     * new value — `automaticallyActivatesSearch`
     * (`MainTabController.swift:31`): every route into Search (a tap,
     * `showSearch()`, a deep link, a launch extra) activates the field.
     */
    var activationToken: Int by mutableIntStateOf(0)
        private set

    /**
     * Last token the field acted on, so a recreated composition (rotation)
     * does not refocus: [MainTabState.select] bumps [activationToken], the
     * field's `LaunchedEffect` consumes it here. `0` means "never entered".
     */
    internal var consumedToken: Int = 0

    /**
     * The IME lift the chrome last applied to the search row, in dp
     * (`SearchMorphGeometry.rowBottom(B, imeBottom, …) − B`); written from
     * [TabChromeLayout]'s placement block, read only by tests and (later)
     * ticket 49's results-list padding. 0 whenever the search chrome is
     * not on screen.
     */
    private val imeLift = mutableFloatStateOf(0f)

    /** See [imeLift]. */
    fun searchImeLift(): Float = imeLift.floatValue

    /** See [imeLift]. */
    internal fun publishImeLift(liftDp: Float) {
        imeLift.floatValue = liftDp
    }

    /**
     * The selection hook: `MainTabState.onSelected` routes every selection
     * (taps, router, deep links, back) here; only an entry **into** Search
     * counts — leaving, or staying, never does (UIKit's `selectedTab` on
     * the already-selected tab is a no-op, `MainTabController.swift:154-156`).
     */
    fun onTabSelected(old: TabId, new: TabId) {
        if (new == TabId.Search && old != TabId.Search) activationToken++
    }

    /**
     * Wave-10 37: the one-row bar's field has no tab to enter — the
     * router's `showSearch()` (⌘⇧F, a deep link) activates it directly.
     */
    fun activate() {
        activationToken++
    }
}

/**
 * The shell's [SearchTabState]; outside [MainTabShell] a read throws.
 * Ticket 49's Search screen reads it for the query.
 */
val LocalSearchTabState = staticCompositionLocalOf<SearchTabState> { error("No SearchTabState") }

/**
 * [SearchTabState] in composition: the app-scoped instance
 * ([RobocoApplication]'s holder survives rotation, not process death — the
 * query is deliberately not saveable). Non-app contexts (unit tests,
 * previews) get a throwaway instance; tests that drive the field pass
 * their own state into the fixture instead.
 */
@Composable
fun rememberSearchTabState(): SearchTabState {
    val app = LocalContext.current.applicationContext
    return remember(app) {
        (app as? RobocoApplication)?.searchTabState ?: SearchTabState()
    }
}
