package sh.roboco.android.settings

import androidx.annotation.MainThread
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import sh.roboco.android.R
import sh.roboco.android.design.GroupedAccessory
import sh.roboco.android.design.GroupedPosition
import sh.roboco.android.design.GroupedRow
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoSwitch
import sh.roboco.android.design.groupedSection
import sh.roboco.android.design.settingsTag
import sh.roboco.android.design.toggleTag
import sh.roboco.android.sessions.FolderRoute
import sh.roboco.android.shell.NavStack

/**
 * The "Sessions" section of the Settings screen (android-ui-parity #100):
 * the port of `MoreViewController.swift:212-216` and its two handlers
 * (`:146-150`, `:246-247`). Two rows:
 *
 *  - `groupByProject` — "Group by Project", subtitle "Sessions outside
 *    your sections, under their project", `square.stack.3d.up` icon and a
 *    ticket 98 [RobocoSwitch]. The switch is bound to the phone-local
 *    `groupByProject` preference (ticket 40's [sh.roboco.android.core.Prefs]):
 *    iOS's `Row(id: "groupByProject", …, accessory: .toggle(app.groupByProject))`
 *    with `.valueChanged` → `toggled(_: _:)`, which writes the preference
 *    and nothing else — no haptic of its own (the switch's
 *    `Haptic.ToggleOn`/`ToggleOff` fire inside [RobocoSwitch] **before**
 *    `onCheckedChange`), no alert, no reload. The Sessions ⋯ menu
 *    "Group by Project" (ticket 46) flips the **same** preference, so
 *    `onGroupByProjectChange` must be the one true writer the screen hands
 *    it (`prefs::setGroupByProject`), and each surface reads the
 *    StateFlow, never a value captured once. Tapping the row **outside**
 *    the switch does nothing (iOS `default: break`; ticket 70 gives
 *    `onClick == null` rows no highlight).
 *  - `archived` — "Archived Sessions", `archivebox` icon, the default
 *    disclosure chevron. A tap pushes ticket 45's [FolderRoute.archived]
 *    on the stack that hosts Settings — the Settings tab's stack on
 *    phones, the Settings form sheet's stack on tablets (104) — via
 *    [openArchived]. Never the Sessions tab (the pr621
 *    `ZeronRoot.kt:1271` `model.tab = Tab.Sessions` trap).
 *
 * Every visual — geometry, fonts, colours, chevron, separator, pressed
 * highlight, the switch itself — belongs to tickets 70 and 98; this file
 * only feeds the rows data and wires the copy. Section placement on the
 * Settings screen belongs to ticket 103.
 */

/**
 * The row ids, `MoreViewController.swift:212,215`. The row tags are
 * [settingsTag] of these: `settings-groupByProject`, `settings-archived`.
 */
enum class SessionsSettingsRow(val id: String) {
    GroupByProject("groupByProject"),
    Archived("archived"),
}

/**
 * The section's copy, resolved from `res/values/strings.xml`. A data
 * class because [LazyListScope] builders are not composables: the screen
 * resolves the strings once (via [sessionsSettingsStrings]) and passes
 * the value into [sessionsSettingsSection].
 */
@Immutable
data class SessionsSettingsStrings(
    val header: String,
    val groupByProject: String,
    val groupByProjectSubtitle: String,
    val archivedSessions: String,
)

/**
 * Resolves [SessionsSettingsStrings] — the ticket's copy table, verbatim:
 * `Sessions`, `Group by Project`, `Sessions outside your sections, under
 * their project`, `Archived Sessions` (`MoreViewController.swift:212-215`).
 * Call at screen level (outside the `LazyColumn`); the result is remembered
 * on the four values so a configuration change re-resolves them.
 */
@Composable
fun sessionsSettingsStrings(): SessionsSettingsStrings {
    val header = stringResource(R.string.settings_sessions_header)
    val groupByProject = stringResource(R.string.group_by_project)
    val groupByProjectSubtitle = stringResource(R.string.settings_group_by_project_subtitle)
    val archivedSessions = stringResource(R.string.settings_archived_sessions)
    return remember(header, groupByProject, groupByProjectSubtitle, archivedSessions) {
        SessionsSettingsStrings(
            header = header,
            groupByProject = groupByProject,
            groupByProjectSubtitle = groupByProjectSubtitle,
            archivedSessions = archivedSessions,
        )
    }
}

// ---------------------------------------------------------------------------
// The emitter
// ---------------------------------------------------------------------------

/**
 * Emits the "Sessions" section — the "Sessions" header (`heading()`) plus
 * the two rows, one lazy item per row — into ticket 103's
 * `LargeTitleScaffold` content (or any `LazyColumn`).
 *
 * [groupByProject] is a **getter** read inside the item lambda, never at
 * the top of this builder: the LazyColumn content is built once and only
 * the `groupByProject` row recomposes when the value flips — the lazy key
 * `grouped:sessions:row:groupByProject` stays stable (iOS replaces the
 * cell because `Row` hashes its accessory; Android keeps the row so
 * ticket 98's thumb animation always completes, including for flips that
 * arrive from the Sessions ⋯ menu while Settings is composed).
 *
 * No `Modifier.animateItem()`: iOS reloads with `animatingDifferences:
 * false` (`MoreViewController.swift:219`), so data changes land instantly.
 *
 * @param onGroupByProjectChange the switch's writer — ticket 40's
 *   `Prefs::setGroupByProject` (no-op on an equal value, StateFlow set
 *   immediately, persisted on IO)
 * @param onOpenArchived the `archived` row's tap — the screen passes
 *   `{ openArchived(LocalNavStack.current) }`
 */
fun LazyListScope.sessionsSettingsSection(
    strings: SessionsSettingsStrings,
    groupByProject: () -> Boolean,
    onGroupByProjectChange: (Boolean) -> Unit,
    onOpenArchived: () -> Unit,
) {
    groupedSection(
        key = "sessions",
        header = strings.header,
        items = SessionsSettingsRow.entries,
        itemKey = { it.id },
    ) { row, position ->
        SessionsSettingsRowContent(
            row = row,
            position = position,
            strings = strings,
            groupByProject = groupByProject(),
            onGroupByProjectChange = onGroupByProjectChange,
            onOpenArchived = onOpenArchived,
        )
    }
}

/**
 * One row of the section: ticket 70's [GroupedRow] fed from the ticket's
 * table. Also used directly (non-lazy) by the design gallery and the
 * pressed-highlight golden, where [previewPressed] holds the `archived`
 * row's highlight at 1 — never set it from behaviour.
 *
 * The switch is built inside the `GroupedAccessory.Custom` lambda (it
 * captures [groupByProject], so it cannot be hoisted to a file-level val),
 * and its tag goes through [RobocoSwitch]'s own `testTag` parameter only:
 * `toggle-groupByProject` appears exactly once.
 */
@Composable
fun SessionsSettingsRowContent(
    row: SessionsSettingsRow,
    position: GroupedPosition,
    strings: SessionsSettingsStrings,
    groupByProject: Boolean,
    onGroupByProjectChange: (Boolean) -> Unit,
    onOpenArchived: () -> Unit,
    previewPressed: Boolean = false,
) {
    when (row) {
        SessionsSettingsRow.GroupByProject -> GroupedRow(
            title = strings.groupByProject,
            subtitle = strings.groupByProjectSubtitle,
            icon = RobocoIcons.SquareStack3dUp,
            position = position,
            accessories = listOf(
                GroupedAccessory.Custom {
                    RobocoSwitch(
                        checked = groupByProject,
                        onCheckedChange = onGroupByProjectChange,
                        testTag = toggleTag(row.id),
                    )
                },
            ),
            testTag = settingsTag(row.id),
            // iOS `default: break`: the row body is not tappable, so a row
            // tap neither flips the switch nor shows the press highlight.
            onClick = null,
        )

        SessionsSettingsRow.Archived -> GroupedRow(
            title = strings.archivedSessions,
            icon = RobocoIcons.Archivebox,
            position = position,
            accessories = listOf(GroupedAccessory.Disclosure),
            testTag = settingsTag(row.id),
            onClick = onOpenArchived,
            previewPressed = previewPressed,
        )
    }
}

// ---------------------------------------------------------------------------
// The push
// ---------------------------------------------------------------------------

/**
 * `MoreViewController.swift:246-247`: pushes the Archived folder screen
 * (ticket 45's [FolderRoute.archived], id `archived`, title "Archived") on
 * [nav] — the stack that hosts Settings, never after a tab switch — with
 * `animated = true`. The guard swallows a second tap that lands after the
 * push has committed and Archived is already on top; ticket 24 blocks
 * touches during the transition itself. The pushed screen keeps the bottom
 * chrome (`FolderRoute.hidesBottomChrome` is false, like iOS).
 */
@MainThread
fun openArchived(nav: NavStack) {
    val route = FolderRoute.archived()
    if (nav.top.route == route) return
    nav.push(route, animated = true)
}
