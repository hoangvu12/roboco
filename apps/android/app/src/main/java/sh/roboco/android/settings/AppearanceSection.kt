package sh.roboco.android.settings

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.flow.StateFlow
import sh.roboco.android.RobocoApplication
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GroupedAccessory
import sh.roboco.android.design.GroupedHeader
import sh.roboco.android.design.GroupedPosition
import sh.roboco.android.design.GroupedRow
import sh.roboco.android.design.RobocoIcon
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.groupedSection
import sh.roboco.android.design.settingsTag

/**
 * The "Appearance" section of the Settings screen (android-ui-parity #96):
 * the port of `MoreViewController.swift:195-204` and its handler
 * (`:222-231`). Three title-only rows — System / Light / Dark, each with
 * its SF symbol — and a checkmark on the row whose **stored** value is
 * current (with System stored on a dark phone, "System" is checked, not
 * "Dark"). A tap writes the phone-local `appearance` preference (ticket
 * 40's [sh.roboco.android.core.Prefs]); the checkmark moves on the next
 * frame, with no animation (`dataSource.apply(…, animatingDifferences:
 * false)`), and the whole app re-themes in the same frame through the one
 * root `RobocoTheme(appearance)` (the port of
 * `window.overrideUserInterfaceStyle`, `SceneDelegate.swift:12-16`).
 *
 * Every visual — the header, the card, the row, the checkmark, the
 * pressed highlight — is ticket 70's grouped list; this file only feeds
 * the rows data and wires the copy. Section placement on the Settings
 * screen (between "Notifications" and "Wallpaper") is ticket 103's.
 * There are no haptics: `MoreViewController` makes no generator call.
 */

/**
 * One row, pure data: the [GroupedRow] inputs. The ids are the iOS
 * `Row(id:)` strings (`MoreViewController.swift:198-200`), so the test
 * tags are `settings-appearance:0/1/2` (`settingsTag`).
 */
@Immutable
data class AppearanceRow(
    val appearance: Appearance,
    val id: String,
    val title: String,
    val icon: RobocoIcon,
)

/** The section's rows and copy, verbatim from the iOS reference. */
object AppearanceRows {

    /** `s.appendSections(["Appearance"])` (`MoreViewController.swift:196`). */
    const val HEADER = "Appearance"

    val all: List<AppearanceRow> = listOf(
        AppearanceRow(Appearance.System, "appearance:0", "System", RobocoIcons.CircleLeftHalfFilled),
        AppearanceRow(Appearance.Light, "appearance:1", "Light", RobocoIcons.SunMax),
        AppearanceRow(Appearance.Dark, "appearance:2", "Dark", RobocoIcons.Moon),
    )
}

/**
 * The section's screen-level state (ticket 103's expected API): the
 * current preference as a snapshot read and the one true writer. Call
 * [rememberAppearanceSectionState] at **screen** level, never inside a
 * lazy item.
 */
@Stable
class AppearanceSectionState internal constructor(
    private val currentState: State<Appearance>,
    val onPick: (Appearance) -> Unit,
) {
    /** The stored preference — a snapshot read, so only the rows that read it recompose. */
    val current: Appearance get() = currentState.value
}

/**
 * Remembers the section's state over the appearance preference. The
 * defaults bind the app object (`app.appearance` / `app::setAppearance`),
 * the same lookup tickets 101/102/140 use; previews, tests and the
 * gallery pass their own flow/writer.
 */
@Composable
fun rememberAppearanceSectionState(
    appearance: StateFlow<Appearance> =
        (LocalContext.current.applicationContext as RobocoApplication).appearance,
    onPick: (Appearance) -> Unit =
        (LocalContext.current.applicationContext as RobocoApplication)::setAppearance,
): AppearanceSectionState {
    val current = appearance.collectAsState()
    // rememberUpdatedState: a later recomposition with a new onPick keeps
    // the remembered state working without rebuilding the section.
    val pick by rememberUpdatedState(onPick)
    return remember(current) { AppearanceSectionState(current, onPick = { pick(it) }) }
}

// ---------------------------------------------------------------------------
// The emitters
// ---------------------------------------------------------------------------

/**
 * Emits the section into ticket 103's Settings `LazyColumn` (or any
 * `LazyColumn`): the "Appearance" header (`heading()`) plus the three
 * rows, one lazy item per row, keyed `grouped:appearance:row:<id>`.
 */
fun LazyListScope.appearanceSection(state: AppearanceSectionState) {
    appearanceSection(current = { state.current }, onPick = state.onPick)
}

/**
 * The lower-level emitter (tests, any custom hosting): [current] is a
 * **getter** read inside the item composable, never while building the
 * lazy list — a preference change recomposes the three rows instead of
 * rebuilding the item list, and the lazy keys stay stable.
 *
 * No `Modifier.animateItem()`: the checkmark moves without animation, like
 * `dataSource.apply(…, animatingDifferences: false)`.
 */
fun LazyListScope.appearanceSection(current: () -> Appearance, onPick: (Appearance) -> Unit) {
    groupedSection(
        key = "appearance",
        header = AppearanceRows.HEADER,
        items = AppearanceRows.all,
        itemKey = { it.id },
    ) { row, position ->
        AppearanceRowItem(row = row, position = position, current = current(), onPick = onPick)
    }
}

/**
 * One row: ticket 70's [GroupedRow], title only, the leading symbol in
 * the default `Palette.secondary`, the checkmark accessory on the current
 * row (`Check(false)` renders nothing and reserves no space) and a plain
 * click — no subtitle, no disclosure, no destructive.
 */
@Composable
private fun AppearanceRowItem(
    row: AppearanceRow,
    position: GroupedPosition,
    current: Appearance,
    onPick: (Appearance) -> Unit,
) {
    GroupedRow(
        title = row.title,
        position = position,
        icon = row.icon,
        accessories = listOf(GroupedAccessory.Check(row.appearance == current)),
        testTag = settingsTag(row.id),
        // The stored value, not RobocoTheme.isDark, drives the checkmark.
        // Tapping the already-checked row runs the same handler (iOS
        // rewrites the same value); Prefs.setAppearance no-ops on it.
        onClick = { onPick(row.appearance) },
    )
}

/**
 * The non-lazy variant for previews, the design gallery and tests: the
 * header plus the three rows, drawn directly.
 */
@Composable
fun AppearanceRowsColumn(current: Appearance, onPick: (Appearance) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        GroupedHeader(AppearanceRows.HEADER)
        AppearanceRows.all.forEachIndexed { index, row ->
            AppearanceRowItem(
                row = row,
                position = GroupedPosition.of(index, AppearanceRows.all.size),
                current = current,
                onPick = onPick,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "Appearance – light")
@Composable
private fun AppearanceSectionLightPreview() {
    RobocoTheme(Appearance.Light) {
        Box(Modifier.background(RobocoTheme.palette.background)) {
            AppearanceRowsColumn(Appearance.System, {})
        }
    }
}

@Preview(name = "Appearance – dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AppearanceSectionDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        Box(Modifier.background(RobocoTheme.palette.background)) {
            AppearanceRowsColumn(Appearance.Dark, {})
        }
    }
}
