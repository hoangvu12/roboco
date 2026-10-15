package sh.roboco.android.sessions

import androidx.compose.runtime.Immutable
import uniffi.roboco_core.SessionRow

/**
 * One folder row of the side rail — the port of `FolderRowVM`
 * (`ios:Threads/SessionCell.swift:34-40`). The front page never emits
 * folders (`buildSections` passes `[]`); the tablet sidebar (105) and the
 * archive picker are their consumers. Declared once here so no later
 * ticket redeclares it.
 */
@Immutable
data class FolderRowVM(
    val id: String,
    val name: String,
    val count: Int,
    val symbol: String,
)

/**
 * One section of every list that draws the workspace — the port of the
 * `buildSections()` tuple (`ios:Threads/SessionListController.swift:354-370`):
 * the section id, its optional header title, its folders and its rows.
 *
 *  - `header == null` → rows only, never foldable (a Recent with nothing
 *    before it ignores its fold flag and always shows its rows);
 *  - `rows` is **every** session of the section, in core order, as the FFI
 *    records. Ticket 41's `buildSessionListItems` maps them with 37's
 *    `SessionRowVM.from(row)`, keeps them for 43/44/47 (`engineId`),
 *    computes the header's count/live over them **before** folding or
 *    de-duplicating, and then folds and first-occurrence de-duplicates —
 *    pre-folding here would read a collapsed header's count as 0.
 */
@Immutable
data class ListSection(
    val id: String,
    val header: String?,
    val folders: List<FolderRowVM> = emptyList(),
    val rows: List<SessionRow>,
)

/**
 * The live glyph of a section header — `SectionHeaderCell.State.live`
 * (`ios:Threads/SessionCell.swift:235-241`): a spinner while any session
 * works, else the input dot while any awaits. Ticket 50's `Offline` is
 * the wifi-off glyph on an offline `home:*` group — it **replaces** the
 * spinner/input for that header, because an unreachable engine's
 * "working" status is cached, not live.
 */
enum class SectionLive { Spinner, Input, Offline }

/**
 * A header row's state — the port of `SectionHeaderCell.State`
 * (`SessionCell.swift:235-241`). Ticket 40 declares the type; ticket 41's
 * `buildSessionListItems` builds the values (count and live over **all**
 * the section's rows, folded or not), and ticket 39 draws them.
 */
@Immutable
data class SectionHeaderState(
    /** `"pinned"`, `"recent"`, a section id, a project-group id. */
    val id: String,
    val title: String,
    /** Sessions in the section, folded or not. */
    val count: Int,
    val collapsed: Boolean,
    /** null = no glyph. */
    val live: SectionLive?,
)

/** Which front-page kind a section is — ticket 46's header long-press menus read it. */
enum class FrontSectionKind { Pinned, User, Recent, ProjectGroup }

/**
 * What the front page knows about one emitted section: its [kind], its
 * `rawName` (the unsuffixed `SectionView`/`SessionGroup` name, for 46's
 * Rename prefill; null for Pinned/Recent) and its `engineId`
 * (`SectionView.engineId` for `User`; the single engine shared by a
 * group's rows, null when they span engines — ticket 50's offline
 * `home:*` rule).
 */
data class FrontSectionMeta(
    val kind: FrontSectionKind,
    val rawName: String?,
    val engineId: String?,
)
