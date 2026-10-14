package sh.roboco.android.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.FrontPage
import uniffi.roboco_core.WorkspaceSnapshot

/**
 * What the "New session" accessory's live summary counts — the port of
 * `AppModel.LiveCounts` (`ios:App/AppModel.swift:35-38`): front-page
 * sessions that are working, and sessions waiting on the user.
 *
 * `LiveCounts()` is the empty state, also used before the first snapshot
 * arrives. Pinned by `LiveCountsTest`.
 */
@Immutable
data class LiveCounts(val working: Int = 0, val awaiting: Int = 0)

/**
 * The mark the accessory's 12 dp glyph slot draws — the port of the iOS
 * `cells.kind` rule (`ios:Shell/MainTabController.swift:327`), kept free of
 * Compose colours so the formatter stays pure: the composable maps it onto
 * ticket 11's `StatusGlyphKind` (spinner, or the input dot).
 */
enum class LiveGlyph { Spinner, InputDot }

/**
 * The counting core — `refreshWorkspace`'s dedup loop
 * (`ios:App/AppModel.swift:371-376`): iterate the rows, first occurrence of
 * an id wins (`seen.insert(row.id).inserted`), and count by the
 * staleness-gated display status (`ios:App/AppModel.swift:440-448`):
 * `ChatIndicator.WORKING` → working, `ChatIndicator.AWAITING_INPUT` →
 * awaiting, and `ERRORED`/`COMPLETED`/`IDLE` count as neither (the Kotlin
 * constants are the UniFFI names android-app#10 generates for
 * `crates/mobile/src/client_ffi/types.rs:438-445`).
 *
 * Testable core: rows in any order (the caller fixes the order), so the
 * same function backs the front-page derivation and the tests.
 */
internal fun countLive(rows: Sequence<Pair<String, ChatIndicator>>): LiveCounts {
    val seen = HashSet<String>()
    var working = 0
    var awaiting = 0
    for ((id, indicator) in rows) {
        if (!seen.add(id)) continue
        when (indicator) {
            ChatIndicator.WORKING -> working++
            ChatIndicator.AWAITING_INPUT -> awaiting++
            ChatIndicator.ERRORED, ChatIndicator.COMPLETED, ChatIndicator.IDLE -> {}
        }
    }
    return LiveCounts(working, awaiting)
}

/**
 * The counts of a merged workspace's front page — `refreshWorkspace`'s
 * row set (`ios:App/AppModel.swift:367-376`): `recent`, then `pinned`, then
 * each section's sessions in order, **deduplicated by id** (first
 * occurrence wins). Collapsed sections still count — `SectionView.sessions`
 * is filled whether `collapsed` is true or not.
 *
 * Never counted: `archived`, `projects[].sessions`, `projectless` and
 * `front.groups` (groups are only a regrouping of `recent` — counting them
 * would double count). The rows' `indicator` (the staleness-gated display
 * status) is read, never `host_indicator`.
 */
fun liveCountsOf(front: FrontPage): LiveCounts = countLive(
    sequence {
        yieldAll(front.recent)
        yieldAll(front.pinned)
        for (section in front.sections) yieldAll(section.sessions)
    }.map { it.id to it.indicator },
)

/**
 * The accessory's counts off the (merged, android-app#07) workspace feed:
 *
 *  - recompute **only** when `WorkspaceSnapshot.revision` changes
 *    (`distinctUntilChangedBy`) — the core publishes bursts, and the
 *    revision coalesces them;
 *  - a null snapshot (before the first snapshot arrives) gives
 *    `LiveCounts()`;
 *  - the output is `distinctUntilChanged()`, so the UI recomposes only
 *    when the counts actually change;
 *  - the derivation runs on `Dispatchers.Default` — a few hundred rows of
 *    iteration are cheap, but never on the main thread.
 *
 * The owner is the shell owner (`RobocoAppContent`'s phone slot), which
 * reads ticket 40's `RobocoApplication.workspaceFeed.snapshot` once and
 * collects this flow through [rememberLiveCounts].
 */
fun liveCountsFlow(workspace: Flow<WorkspaceSnapshot?>): Flow<LiveCounts> =
    workspace
        .distinctUntilChangedBy { it?.revision }
        .map { ws -> ws?.let { liveCountsOf(it.front) } ?: LiveCounts() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

/**
 * The accessory's live counts as Compose state (ticket 27 step 4): one
 * [liveCountsFlow] per [workspace] instance, collected while the shell's
 * lifecycle is at least STARTED, starting from `LiveCounts()`.
 */
@Composable
fun rememberLiveCounts(workspace: Flow<WorkspaceSnapshot?>): State<LiveCounts> {
    val counts = remember(workspace) { liveCountsFlow(workspace) }
    return counts.collectAsStateWithLifecycle(initialValue = LiveCounts())
}

/**
 * The summary parts, in iOS order (`ios:Shell/MainTabController.swift:322-325`):
 * working first, then awaiting. Awaiting is singular only for exactly 1
 * ("1 needs you"); 0 never shows; 2+ reads "N need you". Working never
 * inflects ("1 working", "2 working").
 */
fun LiveCounts.summaryParts(): List<String> {
    val parts = mutableListOf<String>()
    if (working > 0) parts.add("$working working")
    if (awaiting > 0) parts.add(if (awaiting == 1) "$awaiting needs you" else "$awaiting need you")
    return parts
}

/**
 * The live summary line: the parts joined with `" · "` — space, U+00B7
 * MIDDLE DOT, space (`MainTabController.swift:325`). Written as the literal
 * middle dot in source, so no editor can mangle it.
 */
fun LiveCounts.summaryText(): String = summaryParts().joinToString(" · ")

/**
 * The button's accessibility label: "New session", or "New session, " plus
 * the parts joined with `", "` — a comma, not the middle dot
 * (`MainTabController.swift:328`).
 */
fun LiveCounts.accessibilityLabel(): String {
    val parts = summaryParts()
    return if (parts.isEmpty()) "New session" else "New session, " + parts.joinToString(", ")
}

/**
 * The glyph slot's kind: `null` (nothing drawn) when the parts are empty,
 * `Spinner` while anything is working, else the input dot
 * (`MainTabController.swift:327`).
 */
fun LiveCounts.glyph(): LiveGlyph? = when {
    working > 0 -> LiveGlyph.Spinner
    awaiting > 0 -> LiveGlyph.InputDot
    else -> null
}
