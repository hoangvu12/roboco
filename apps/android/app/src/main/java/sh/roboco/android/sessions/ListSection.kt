package sh.roboco.android.sessions

import androidx.compose.runtime.Immutable

/**
 * The sessions front page's live-section marker
 * (android-ui-parity #39, data type owned by #40): what a section header's
 * 12 × 12 status glyph shows — a violet spinner when any session in the
 * group is working, a violet input dot when any awaits input
 * (`SessionListController.swift:136`).
 *
 * Roboco carries its own enum instead of the raw `StatusGlyphKind` so
 * ticket 50 can add `Offline` (wifi-off) without widening the glyph kind;
 * `SectionHeaderRow` maps the value to `StatusGlyphKind` with an
 * exhaustive `when`.
 */
enum class SectionLive { Spinner, Input } // ticket 50 adds Offline

/**
 * One foldable section header's render state
 * (android-ui-parity #39, data type owned by #40) — the port of iOS
 * `SectionHeaderCell.State` (`ios:Threads/SessionCell.swift:234-241`).
 *
 * Built by ticket 41's `buildSessionListItems`: `title` is 40's
 * `ListSection.header`, `count` counts every session in the section
 * (folded or not), `collapsed` is the persisted fold set, and `live` is
 * 41's `sectionLive(vms)`.
 */
@Immutable
data class SectionHeaderState(
    /** "pinned", "recent", a section id, a project-group id. */
    val id: String,
    /** The header title, verbatim; this row just draws the string. */
    val title: String,
    /** Sessions in the section, folded or not. */
    val count: Int,
    /** True when the section is folded; turns the chevron to point right. */
    val collapsed: Boolean,
    /** null = no glyph after the count. */
    val live: SectionLive?,
)
