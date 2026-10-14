package sh.roboco.android.sessions

import java.util.Locale
import uniffi.roboco_core.FrontPage
import uniffi.roboco_core.SessionRow

/**
 * The front page's section list (ticket 40) — the port of
 * `SessionListController.buildSections()` (`ios:Threads/SessionListController.swift:354-370`)
 * over the merged `FrontPage` the core already grouped (android-app#07;
 * the grouping itself runs in Rust because it needs the merged
 * `ProjectView.groupKey`/`groupName`).
 *
 * Pure: no Compose types, no state, no FFI — the model
 * (`FrontPageModel`) feeds it a snapshot, the grouping flag and the
 * engine-name map, and hands the result plus `collapsedIds` to ticket
 * 41's list builder.
 *
 * Section order (the Spec table):
 *
 *  1. `pinned` — only when pins exist;
 *  2. each user section, core order, empty sections kept;
 *  3a. `recent` — when grouping is off, **always** (even empty), with
 *      header `"Recent"` only when rows 1–2 produced at least one section;
 *  3b. each group, core order, always with headers — when grouping is on
 *      (Recent is then absent).
 *
 * Rows are every session of the section, FFI records in core order —
 * never folded, never de-duplicated, never counted here (41 does all
 * three, over exactly these rows).
 */
object FrontPageSections {

    const val PINNED = "pinned"
    const val RECENT = "recent"
    const val PINNED_TITLE = "Pinned"
    const val RECENT_TITLE = "Recent"

    /** Space, U+00B7 MIDDLE DOT, space — the same separator iOS uses. */
    const val SUFFIX_SEPARATOR = " · "

    /** The build result: the sections and the per-id metadata. */
    data class Built(val sections: List<ListSection>, val meta: Map<String, FrontSectionMeta>)

    /**
     * Builds the section list. [engineNames] is engineId → `EngineView.name`
     * of **all** paired engines (any state); with ≥2 entries, colliding
     * user-section/group headers get the engine-name suffix.
     */
    fun build(front: FrontPage, groupByProject: Boolean, engineNames: Map<String, String>): Built {
        val raw = ArrayList<RawSection>(front.pinned.size + front.sections.size + 2)
        if (front.pinned.isNotEmpty()) {
            raw += RawSection(
                id = PINNED, title = PINNED_TITLE, engineId = null, rows = front.pinned,
                kind = FrontSectionKind.Pinned, rawName = null,
            )
        }
        for (section in front.sections) {
            raw += RawSection(
                id = section.id, title = section.name, engineId = section.engineId,
                rows = section.sessions, kind = FrontSectionKind.User, rawName = section.name,
            )
        }
        if (groupByProject) {
            for (g in front.groups) {
                // The group's engine: the one engine shared by all its rows.
                // A group spanning engines (one repo cloned twice) has none
                // — it is never a suffix candidate.
                val engineId = g.sessions.map { it.engineId }.distinct().singleOrNull()
                raw += RawSection(
                    id = g.id, title = g.name, engineId = engineId, rows = g.sessions,
                    kind = FrontSectionKind.ProjectGroup, rawName = g.name,
                )
            }
        } else {
            raw += RawSection(
                id = RECENT,
                // A header only when Pinned or at least one user section
                // came first — an empty user section counts.
                title = if (raw.isEmpty()) null else RECENT_TITLE,
                engineId = null, rows = front.recent,
                kind = FrontSectionKind.Recent, rawName = null,
            )
        }
        val suffixed = collisionSuffixes(raw, engineNames)
        return Built(
            sections = raw.map { r -> ListSection(id = r.id, header = suffixed[r.id] ?: r.title, rows = r.rows) },
            meta = raw.associate { r ->
                r.id to FrontSectionMeta(kind = r.kind, rawName = r.rawName, engineId = r.engineId)
            },
        )
    }

    /**
     * The ids of the **headed** sections in [sections] whose effective fold
     * is true. A header-less section is never a member: a user who folded
     * Recent and then deleted every section must not see an empty page,
     * and 41 ignores the fold without a header anyway — excluding it
     * keeps the hand-off honest.
     */
    fun collapsedIds(sections: List<ListSection>, isCollapsed: (id: String) -> Boolean): Set<String> =
        sections.filter { it.header != null && isCollapsed(it.id) }.mapTo(HashSet()) { it.id }

    /**
     * The offline overrides for ticket 50's wifi-off rule: every `home:*`
     * group whose single shared engine is in [offlineEngines] (the
     * **graced** set, from `EngineHealthUi.offlineEngineIds` — never the
     * raw `engineOnline`, so the header glyph and the connection line
     * always agree and neither blinks on blips). The ids come straight
     * from `build`'s [Built.meta], whose `engineId` is the single engine
     * of the group's rows — a group spanning engines has none and is
     * never overridden. [build] itself is unchanged: the map is applied
     * by 41's builder (`liveOverrides[id] ?: sectionLive(vms)`, which
     * keeps the count and the rows and only swaps the glyph).
     */
    fun offlineOverrides(meta: Map<String, FrontSectionMeta>, offlineEngines: Set<String>): Map<String, SectionLive> =
        meta.filter { (id, m) -> id.startsWith("home:") && m.engineId != null && m.engineId in offlineEngines }
            .mapValues { SectionLive.Offline }

    /**
     * The ids Collapse All may fold, in order (ticket 46): `"pinned"` when
     * pins exist, every user section, and the groups only while grouping
     * is on. **Recent is never in the list** (`SessionListController.swift:419-421`).
     */
    fun collapsibleIds(front: FrontPage, groupByProject: Boolean): List<String> = buildList {
        if (front.pinned.isNotEmpty()) add(PINNED)
        front.sections.forEach { add(it.id) }
        if (groupByProject) front.groups.forEach { add(it.id) }
    }

    // ------------------------------------------------------------------ internals

    /** A section before the collision pass: the raw title plus its metadata. */
    private class RawSection(
        val id: String,
        val title: String?,
        val engineId: String?,
        val rows: List<SessionRow>,
        val kind: FrontSectionKind,
        val rawName: String?,
    )

    /** A suffix candidate: a user section or group with a title and an engine. */
    private class Candidate(val id: String, val title: String, val engineId: String)

    /**
     * The multi-engine name-collision suffix (the Roboco adaptation — new
     * copy, needed only with ≥2 paired engines): user-section and group
     * headers whose titles match after `trim().lowercase(Locale.ROOT)`
     * while sitting on **different** engines become
     * `"<title> · <engine name>"`. Pinned and Recent are never candidates
     * (Pinned is one merged group, so it cannot collide), same-engine
     * duplicates stay unchanged (the suffix would not tell them apart),
     * and an unknown engine name leaves the title unchanged. The map holds
     * the suffixed titles by section id; `meta.rawName` keeps the
     * unsuffixed name either way.
     */
    private fun collisionSuffixes(
        raw: List<RawSection>,
        engineNames: Map<String, String>,
    ): Map<String, String> {
        if (engineNames.size < 2) return emptyMap()
        val candidates = raw.mapNotNull { r ->
            val engineId = r.engineId ?: return@mapNotNull null
            val title = r.title ?: return@mapNotNull null
            if (r.kind != FrontSectionKind.User && r.kind != FrontSectionKind.ProjectGroup) return@mapNotNull null
            Candidate(id = r.id, title = title, engineId = engineId)
        }
        val out = HashMap<String, String>()
        for (group in candidates.groupBy { it.title.trim().lowercase(Locale.ROOT) }.values) {
            if (group.map { it.engineId }.distinct().size < 2) continue
            for (candidate in group) {
                val engineName = engineNames[candidate.engineId] ?: continue
                out[candidate.id] = candidate.title + SUFFIX_SEPARATOR + engineName
            }
        }
        return out
    }
}
