package sh.roboco.android.sessions

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.roboco.android.core.Prefs
import sh.roboco.android.core.WorkspaceFeed
import sh.roboco.android.design.Feedback

/**
 * What the Sessions tab draws (ticket 40) — the `AppModel.frontPage`
 * half of `FrontPage` (`ios:App/AppModel.swift:9-15`): the section list
 * plus the fold resolution. [sections] carries **every** row of every
 * section (not folded, not de-duplicated); ticket 41's
 * `buildSessionListItems` folds via [collapsedIds] and de-duplicates.
 *
 * The rebuild keys on the snapshot **revision** (not iOS's VM equality),
 * the grouping flag, the fold state and the engine names — see the
 * rebuild rule in the ticket.
 */
data class FrontPageUi(
    val revision: ULong,
    val synced: Boolean,
    val sections: List<ListSection>,
    /** Headed ids whose effective fold is true — never a header-less section. */
    val collapsedIds: Set<String>,
    /** Per-id section metadata (kind, unsuffixed name, engine). */
    val meta: Map<String, FrontSectionMeta>,
    /**
     * Section id → live value that replaces 41's row-derived one. Always
     * empty here; ticket 50 fills it (Offline on offline `home:*` groups).
     */
    val liveOverrides: Map<String, SectionLive> = emptyMap(),
)

/**
 * The front page's model (ticket 40) — the `AppModel` front-page half:
 * it combines the workspace feed, the `groupByProject` preference and
 * the fold state into [ui], and routes the two front-page actions
 * (`toggleSection`, `collapseAll`) into [FoldState].
 *
 * `null` until [Prefs] and [FoldState] have loaded from disk **and** a
 * first snapshot exists — no flash of expanded sections, none of Recent
 * before the groups. The section list itself is computed on [compute],
 * never the caller's thread, and nothing here calls the FFI: the feed
 * and the fold state own every call.
 */
class FrontPageModel(
    private val feed: WorkspaceFeed,
    private val prefs: Prefs,
    private val fold: FoldState,
    scope: CoroutineScope,
    compute: CoroutineDispatcher = Dispatchers.Default,
) {

    val ui: StateFlow<FrontPageUi?> = flow {
        prefs.awaitLoaded()
        fold.awaitLoaded()
        emitAll(
            combine(
                feed.snapshot.filterNotNull().distinctUntilChangedBy { it.revision },
                prefs.groupByProject,
                fold.state,
                feed.engines.map { engineViews -> engineViews.associate { it.engineId to it.name } }
                    .distinctUntilChanged(),
            ) { ws, groupByProject, f, engineNames ->
                val built = FrontPageSections.build(ws.front, groupByProject, engineNames)
                val sectionsById = ws.front.sections.associateBy { it.id }
                FrontPageUi(
                    revision = ws.revision,
                    synced = ws.synced,
                    sections = built.sections,
                    // Pure over the combined Fold — never `state.value` — so
                    // the rebuild is race-free.
                    collapsedIds = FrontPageSections.collapsedIds(built.sections) { id ->
                        fold.isCollapsedIn(f, id, sectionsById[id])
                    },
                    meta = built.meta,
                )
            },
        )
    }
        .flowOn(compute)
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        // Reconcile on every new snapshot revision and every engines-list
        // change: stale pendings drop, offline overrides push.
        scope.launch {
            combine(
                feed.snapshot.filterNotNull().distinctUntilChangedBy { it.revision },
                feed.engines,
            ) { ws, engines -> ws to engines }
                .collect { (ws, engines) -> fold.reconcile(ws, engines) }
        }
    }

    /**
     * A header tap (`SessionListController.toggleSection`). A user section
     * (an id in `front.sections`) is engine-synced; everything else is
     * phone-local.
     */
    fun toggleSection(id: String, feedback: Feedback) {
        val ws = feed.snapshot.value ?: return
        fold.toggle(id, ws.front.sections.firstOrNull { it.id == id }, feedback)
    }

    /**
     * The ⋯ menu's Collapse All (`SessionListController.swift:416-421`):
     * fold every still-open id among Pinned (if pins exist), the user
     * sections and — only while grouping is on — the groups. Recent is
     * never touched, and each id goes through the same `toggleSection`
     * path, so persistence, engine writes and haptics behave identically
     * (one `Haptic.Select` per toggled id; ticket 05's gate makes the
     * burst feel like one tick on device).
     */
    fun collapseAll(feedback: Feedback) {
        val ws = feed.snapshot.value ?: return
        for (id in FrontPageSections.collapsibleIds(ws.front, prefs.groupByProject.value)) {
            val section = ws.front.sections.firstOrNull { it.id == id }
            if (!fold.isCollapsed(id, section)) fold.toggle(id, section, feedback)
        }
    }
}
