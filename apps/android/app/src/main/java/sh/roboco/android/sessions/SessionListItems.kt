package sh.roboco.android.sessions

import android.util.Log
import androidx.compose.runtime.Immutable
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.EngineView
import uniffi.roboco_core.SessionRow

/**
 * The warning sink for the duplicate-section-id skip in
 * [buildSessionListItems]: production logs `Log.w("RobocoSessionList", …)`.
 * Plain-JVM tests swap this — the stub `android.util.Log` throws "not
 * mocked" outside Robolectric (the same seam as `foldLogW`).
 */
internal var sessionListLogW: (String) -> Unit = { Log.w(TAG, it) }

private const val TAG = "RobocoSessionList"

/**
 * iOS `reload()`'s live derivation (`SessionListController.swift:134`):
 * a spinner when **any** row of the section is working, else the input dot
 * when any is awaiting, else nothing. Computed over the section's full row
 * list — before folding and before the first-occurrence de-dupe — exactly
 * as iOS reads `s.sessions` there.
 */
fun sectionLive(vms: List<SessionRowVM>): SectionLive? = when {
    vms.any { it.status == SessionRowVM.Status.Working } -> SectionLive.Spinner
    vms.any { it.status == SessionRowVM.Status.Awaiting } -> SectionLive.Input
    else -> null
}

/**
 * One keyed row of every session list — the port of
 * `SessionListController.Item` (`ios:Threads/SessionListController.swift:6-33`).
 * Identity is **the kind plus the id**: a session keeps its identity when
 * it moves between sections, so the move animates (upstream PR #609 keyed
 * rows by `"$section/$id"`, which loses this — do not copy it).
 *
 * [key] and [contentType] feed `LazyColumn`'s `item(key, contentType)`
 * directly; a duplicate key would crash the list, which is why
 * [buildSessionListItems] de-duplicates (sessions first-wins, folders and
 * section ids once each).
 */
@Immutable
sealed interface SessionListItem {

    /** The LazyColumn item key. */
    val key: String

    /** The LazyColumn content type. */
    val contentType: String

    /**
     * A collapsible section header — the [SectionHeaderState] built here
     * (count/live/collapsed over the section's rows), drawn by ticket 39's
     * [SectionHeaderRow].
     */
    @Immutable
    data class Header(val state: SectionHeaderState) : SessionListItem {
        override val key: String = "h:${state.id}"
        override val contentType: String = "sectionHeader"
    }

    /**
     * A non-collapsible header — `UIListContentConfiguration.plainHeader()`
     * (`SessionListController.swift:71-81`): the title at a fixed 13, no
     * chevron, not selectable. Folder pages only (iOS: every current
     * header is collapsible; the kind is kept so the API mirrors the base
     * class).
     */
    @Immutable
    data class PlainHeader(val sectionId: String, val title: String) : SessionListItem {
        override val key: String = "h:$sectionId"
        override val contentType: String = "plainHeader"
    }

    /** A folder row (the API's `folders:` slot; no current caller emits one). */
    @Immutable
    data class Folder(val sectionId: String, val vm: FolderRowVM) : SessionListItem {
        override val key: String = "f:${vm.id}"
        override val contentType: String = "folder"
    }

    /**
     * A session row: [row] keeps the FFI record (44/47 need `engineId`
     * and friends) and [vm] is 37's `SessionRowVM.from(row)`.
     */
    @Immutable
    data class Session(val sectionId: String, val row: SessionRow, val vm: SessionRowVM) : SessionListItem {
        override val key: String = "s:${vm.id}"
        override val contentType: String = "session"
    }
}

/**
 * The pure port of `SessionListController.reload()`
 * (`ios:Threads/SessionListController.swift:118-163`): turns ticket 40's
 * [ListSection]s (all rows, in core order) into the flat, keyed item list.
 *
 *  - Sections are emitted in the given order; a section with `header ==
 *    null` emits no header item (Recent with nothing above it, Search,
 *    folder pages).
 *  - **Header state** (`collapsible` only): `count = rows.size` and the
 *    live glyph are computed over **all** of the section's rows, before
 *    folding and before de-duping — pre-folding here would read a
 *    collapsed header's count as 0. `liveOverrides` replaces the
 *    row-derived value for that header only (ticket 50: `Offline` on
 *    offline `home:*` groups).
 *  - **Folding** (`collapsible` only): a collapsed section emits its
 *    header and nothing else; its rows are skipped and are **not**
 *    recorded as emitted (`:140-143`), so the same id can still surface
 *    from a later section.
 *  - **De-dupe**: a session id already emitted by an earlier section is
 *    skipped (first occurrence wins, so the row stays under its first
 *    section and moves animate). iOS does not de-dupe folders, but a
 *    duplicate folder id would crash the diffable source just the same,
 *    so folders are de-duped too.
 *  - A **duplicate section id** is skipped with a log line (through the
 *    [sessionListLogW] seam) — a duplicate LazyColumn key would crash.
 *
 * Pure and cheap (O(rows)); runs in composition. Never call the FFI from
 * here: 40 hands in ready [ListSection]s and the default [toVm] is
 * [SessionRowVM.from] (pure Kotlin except `HomeColorIndex`, which resolves
 * once and lazily). Plain-JVM tests pass their own [toVm] with
 * `homeColorIndex = 6`.
 */
fun buildSessionListItems(
    sections: List<ListSection>,
    collapsible: Boolean,
    collapsedIds: Set<String> = emptySet(),
    liveOverrides: Map<String, SectionLive> = emptyMap(),
    toVm: (SessionRow) -> SessionRowVM = { SessionRowVM.from(it) },
): List<SessionListItem> {
    val out = ArrayList<SessionListItem>(sections.sumOf { it.rows.size + it.folders.size + 1 })
    val sessionsSeen = HashSet<String>()
    val headersSeen = HashSet<String>()
    val foldersSeen = HashSet<String>()
    for (s in sections) {
        val vms = s.rows.map(toVm)
        val title = s.header
        if (title != null) {
            if (!headersSeen.add(s.id)) {
                sessionListLogW("duplicate section ${s.id}")
                continue
            }
            if (collapsible) {
                val h = SectionHeaderState(
                    id = s.id,
                    title = title,
                    count = s.rows.size,
                    collapsed = s.id in collapsedIds,
                    live = liveOverrides[s.id] ?: sectionLive(vms),
                )
                out += SessionListItem.Header(h)
                // Header only; the rows are NOT added to sessionsSeen
                // (iOS :140-143), so a folded first occurrence does not
                // mask the same id in a later, open section.
                if (h.collapsed) continue
            } else {
                out += SessionListItem.PlainHeader(s.id, title)
            }
        }
        for (f in s.folders) {
            if (foldersSeen.add(f.id)) out += SessionListItem.Folder(s.id, f)
        }
        s.rows.forEachIndexed { i, r ->
            if (sessionsSeen.add(r.id)) out += SessionListItem.Session(s.id, r, vms[i])
        }
    }
    return out
}

/**
 * Which empty-state copy a list with no items shows (Roboco addition — iOS
 * shows a blank list). `Sessions` is the front page, `Archived` the
 * archived folder page; `None` (Pinned, user sections, Search) is blank.
 */
enum class SessionListEmpty { None, Sessions, Archived }

/** The list's derived status — see `SessionListStatusOverlay`'s table. */
enum class SessionListStatus { Content, Loading, EmptySessions, EmptyArchived, Blank }

/**
 * First match wins: items → [SessionListStatus.Content]; no items and
 * loading → [SessionListStatus.Loading] for every empty kind (the
 * spinner outranks the copy); then the [SessionListEmpty] kind;
 * `None` → [SessionListStatus.Blank].
 */
fun sessionListStatus(items: List<SessionListItem>, loading: Boolean, empty: SessionListEmpty): SessionListStatus =
    when {
        items.isNotEmpty() -> SessionListStatus.Content
        loading -> SessionListStatus.Loading
        empty == SessionListEmpty.Sessions -> SessionListStatus.EmptySessions
        empty == SessionListEmpty.Archived -> SessionListStatus.EmptyArchived
        else -> SessionListStatus.Blank
    }

/**
 * "An engine has delivered no live feed frame yet in this process and is
 * still trying" (android-app#06 `EngineView.from_cache` / `state`):
 * from-cache **and** Connecting or Reconnecting. `Connected`, `Offline`
 * and `Parked` engines are not loading — an unreachable engine with no
 * cache keeps the spinner up while it retries, by design (ticket 50
 * explains why).
 */
fun sessionsLoading(engines: List<EngineView>): Boolean =
    engines.any {
        it.fromCache && (it.state is EngineState.Connecting || it.state is EngineState.Reconnecting)
    }
