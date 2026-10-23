package sh.roboco.android.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.flow.StateFlow
import sh.roboco.android.RobocoApplication
import sh.roboco.android.accounts.agentAccountsSection
import sh.roboco.android.design.LocalBottomChrome
import sh.roboco.android.shell.LargeTitleScaffold
import sh.roboco.android.shell.LocalNavStack
import sh.roboco.android.shell.RegisterScrollToTop
import sh.roboco.android.shell.TitleMode

/**
 * The Settings screen (android-ui-parity #103): the Compose port of
 * `MoreViewController` — a thin assembly. [LargeTitleScaffold]'s large
 * "Settings" title (25) over one `LazyColumn` that stacks the eight
 * section emitters, in this order: **Agent Accounts (android-next#05),
 * Devices (102), Notifications (99), Background (ADR 0010's Stay
 * connected), Appearance (96), Wallpaper (97), Sessions (100), About
 * (101)**. The design gallery and its goldens still show the seven iOS
 * sections; Background is Android-only and joins them when the goldens
 * are next recorded on CI.
 * Every row's look is ticket 70's; every row's behaviour is its section
 * ticket's; this screen owns only:
 *
 *  - the section order (the order of the six emitter calls below **is**
 *    the spec — [SettingsSection] documents it, it is not derived from
 *    it);
 *  - the screen-level hoisting of the section states, so the permission
 *    and photo-picker launchers and the dialog hosts survive scrolling;
 *  - the **one** `ON_RESUME` re-read of the notification status
 *    (99 §Pitfalls installs none of its own — `willEnterForeground` /
 *    `viewWillAppear`, `MoreViewController.swift:99-111`);
 *  - the wiring of 100's rows to 40's `groupByProject` preference and
 *    `openArchived`;
 *  - the bottom scroll inset under the chrome group (30's
 *    `contentPadding` + a trailing margin, never a constant).
 *
 * The reload story is iOS's `reload()` turned inside out: every source is
 * a `StateFlow` or snapshot state, so recomposition **is** the reload, and
 * rows appear and disappear instantly (`animatingDifferences: false` —
 * never an item placement animation anywhere in Settings). Only the
 * notification status cannot be observed, hence the lifecycle effect.
 *
 * iOS's **Account**, **Voice** and **Sign Out** sections are dropped
 * (`MoreViewController.swift:185-188, 191-194, 217-218`): Roboco has no
 * account and no voice (ADR 0004), and forgetting an engine (140) is the
 * nearest action to signing out.
 */

/** `MoreViewController.swift:31`: the title, verbatim (drawn by ticket 25). */
const val SettingsTitle = "Settings"

/**
 * The screen's tunables. [BottomExtra] is the trailing space **on top of**
 * ticket 30's `contentPadding` — the stand-in for the trailing space
 * UIKit's inset-grouped layout puts after the last section (the scope's
 * "bottom chrome inset + 16 dp"; 30's padding already leaves 8 dp, so the
 * total clearance above the accessory is 24 dp).
 */
object SettingsScreenTokens {

    /** Trailing space after the last card, on top of the bottom chrome padding. */
    val BottomExtra = 16.dp
}

/**
 * The sections, in display order = declaration order (iOS
 * `MoreViewController.swift:183-220` minus Account/Voice/Sign-Out, plus
 * Roboco's About in the Sign-Out slot; android-next#05 adds Agent
 * Accounts where PR #578 put Provider Accounts — ahead of the
 * computers). The [key]s are the `groupedSection` keys — they must stay
 * unique across the screen (70 prefixes them `grouped:<key>:…`).
 */
enum class SettingsSection(val key: String) {
    AgentAccounts("agent-accounts"),
    Devices("devices"),
    Notifications("notifications"),

    /** ADR 0010's "Stay connected in background" (Android-only, no iOS slot). */
    Background("background"),
    Appearance("appearance"),
    Wallpaper("wallpaper"),
    Sessions("sessions"),
    About("about"),
}

/**
 * The engine-row id prefix 103's table names. Ticket 102 kept iOS's
 * `device:` prefix (`DevicesRowIds.engine`), and 102's own KDoc hands it
 * to this map — so [SettingsDeviceIdPrefix] (taken from 102's constant,
 * never retyped) is the prefix the real rows carry, and the `engine:`
 * spelling 103's reviewer note expected is accepted alongside it: the map
 * holds whichever 102/140 ship, and 140 may still rename.
 */
private const val SettingsEngineIdPrefix = "engine:"

/** 102's engine-row id prefix, derived from [DevicesRowIds.engine] (see above). */
private val SettingsDeviceIdPrefix = DevicesRowIds.engine("")

/**
 * Pure: maps a row id (the part after `settings-`) to its section; null
 * for an unknown id. The prefixes are the section tickets' row ids:
 * `agent-account:`/`agent-accounts:` (android-next#05),
 * `engine:`/`device:` rows and `DevicesRowIds.Pair` (102), `notify:` (99),
 * `appearance:` (96), `wallpaper:` (97), 100's two rows, `about:` (101).
 * `account`, `voice` and `signout` (the dropped iOS sections) map to null.
 */
fun settingsSectionFor(id: String): SettingsSection? = when {
    id.startsWith("agent-account:") || id.startsWith("agent-accounts:") ->
        SettingsSection.AgentAccounts

    id.startsWith(SettingsEngineIdPrefix) ||
        id.startsWith(SettingsDeviceIdPrefix) ||
        id == DevicesRowIds.Pair -> SettingsSection.Devices

    id.startsWith("notify:") -> SettingsSection.Notifications
    id.startsWith("background:") -> SettingsSection.Background
    id.startsWith("appearance:") -> SettingsSection.Appearance
    id.startsWith("wallpaper:") -> SettingsSection.Wallpaper
    id == SessionsSettingsRow.GroupByProject.id ||
        id == SessionsSettingsRow.Archived.id -> SettingsSection.Sessions

    id.startsWith("about:") -> SettingsSection.About
    else -> null
}

/**
 * Pure: the screen's bottom content padding — the chrome group's
 * `contentPadding` (30) plus [SettingsScreenTokens.BottomExtra]. Passed
 * to [LargeTitleScaffold] as `contentPadding(bottom = …)`, never as a
 * `Modifier.padding` on the list: rows must scroll under the glass bars.
 */
fun settingsBottomPadding(chromeContentPadding: Dp): Dp =
    chromeContentPadding + SettingsScreenTokens.BottomExtra

// ---------------------------------------------------------------------------
// The screen
// ---------------------------------------------------------------------------

/**
 * The Settings tab's root (and the tablet dialog's content, 104).
 *
 * The section states are **parameters with the real factories as
 * defaults**, so tests and previews inject fakes through the factories'
 * own seams without new seams here — and the launchers (97's photo
 * picker, 99's permission request) and the states' snapshots are created
 * at screen level, never inside a lazy item (a launcher created in an
 * item loses its result when the item scrolls away).
 *
 * `topInset` defaults to [Dp.Unspecified]: "use 25's own default" — the
 * status bar in a tab, and 0 inside 104's card through 25's
 * `LocalLargeTitleHostInsets`. Re-declaring the status-bar default here
 * would override that and put a status-bar gap inside the dialog.
 *
 * [groupByProject]/[onGroupByProjectChange] are 100's wiring: the shared
 * phone-local preference (40), which the Sessions ⋯ menu (46) also flips —
 * one writer, two surfaces reading the same flow.
 *
 * @param trailing the nav row's trailing slot: `{}` on the phone tab,
 *   104's "Done" capsule in the tablet dialog
 * @param topInset see above; [Dp.Unspecified] = 25's default
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    topInset: Dp = Dp.Unspecified,
    trailing: @Composable RowScope.() -> Unit = {},
    accounts: sh.roboco.android.accounts.AgentAccountsSectionState =
        sh.roboco.android.accounts.rememberAgentAccountsSectionState(),
    devices: DevicesSectionState = rememberDevicesSectionState(),
    notifications: NotificationSectionState = rememberNotificationSectionState(),
    appearance: AppearanceSectionState = rememberAppearanceSectionState(),
    wallpaper: WallpaperSectionState = rememberWallpaperSectionState(),
    groupByProject: StateFlow<Boolean> =
        (LocalContext.current.applicationContext as RobocoApplication).prefs.groupByProject,
    onGroupByProjectChange: (Boolean) -> Unit =
        (LocalContext.current.applicationContext as RobocoApplication).prefs::setGroupByProject,
    about: AboutSectionState = rememberAboutSectionState(),
    stayConnected: StateFlow<Boolean> =
        (LocalContext.current.applicationContext as RobocoApplication).prefs.stayConnected,
    onStayConnectedChange: (Boolean) -> Unit =
        (LocalContext.current.applicationContext as RobocoApplication).prefs::setStayConnected,
) {
    val listState = rememberLazyListState()
    // 26: the tab's scroll-to-top handler for a re-select at the root; a
    // no-op outside a tab (the tablet dialog has no LocalTabId).
    RegisterScrollToTop(listState)
    // The ONE ON_RESUME re-read (99's contract): returning from system
    // settings, from the permission dialog, from the background, plus the
    // lifecycle catch-up when the effect enters composition in a resumed
    // activity — so a tab switch back re-reads the status too. An
    // injected hand-built NotificationSectionState gets the same re-reads.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notifications.refreshStatus() }
    val group by groupByProject.collectAsState()
    val onGroup by rememberUpdatedState(onGroupByProjectChange)
    val stay by stayConnected.collectAsState()
    val onStay by rememberUpdatedState(onStayConnectedChange)
    val nav = LocalNavStack.current
    val sessionsStrings = sessionsSettingsStrings()
    SettingsScreenContent(listState, modifier, topInset, trailing) {
        agentAccountsSection(accounts)
        devicesSection(devices)
        notificationSection(notifications)
        backgroundSettingsSection(
            stayConnected = { stay },
            onStayConnectedChange = { onStay(it) },
        )
        appearanceSection(appearance)
        wallpaperSection(wallpaper)
        sessionsSettingsSection(
            sessionsStrings,
            // The value is read inside the row item (100's rule: the lazy
            // key stays stable when the ⋯ menu flips the preference).
            groupByProject = { group },
            onGroupByProjectChange = { onGroup(it) },
            onOpenArchived = { openArchived(nav) },
        )
        aboutSection(about)
    }
}

// ---------------------------------------------------------------------------
// The assembly (no behaviour)
// ---------------------------------------------------------------------------

/**
 * The scaffold and padding only; [sections] emits the grouped sections.
 * Used by [SettingsScreen], the design gallery and the screenshot tests,
 * which build their section states through each ticket's seam instead of
 * the app singletons.
 *
 * - the page fill is 25's root `Palette.background` (never painted twice);
 * - the bottom padding is [settingsBottomPadding] over
 *   [LocalBottomChrome] (never a constant, never `navigationBars` alone —
 *   that misses the pill and the accessory);
 * - the `settings-screen` root tag sits on the scaffold's root modifier;
 * - `topInset.isSpecified` never flips for a given host, so the branch is
 *   a one-time choice: when specified it is forwarded, otherwise 25's
 *   default (and 104's `LocalLargeTitleHostInsets` override of it) applies.
 */
@Composable
internal fun SettingsScreenContent(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    topInset: Dp = Dp.Unspecified,
    trailing: @Composable RowScope.() -> Unit = {},
    sections: LazyListScope.() -> Unit,
) {
    val chrome = LocalBottomChrome.current
    val padding = PaddingValues(bottom = settingsBottomPadding(chrome.contentPadding))
    val tagged = modifier.testTag("settings-screen")
    if (topInset.isSpecified) {
        LargeTitleScaffold(
            title = SettingsTitle,
            modifier = tagged,
            mode = TitleMode.Always,
            listState = listState,
            topInset = topInset,
            contentPadding = padding,
            trailing = trailing,
            content = sections,
        )
    } else {
        // Omit topInset entirely: forwarding even Dp.Unspecified would
        // override 25's default (104's card resolves it to 0).
        LargeTitleScaffold(
            title = SettingsTitle,
            modifier = tagged,
            mode = TitleMode.Always,
            listState = listState,
            contentPadding = padding,
            trailing = trailing,
            content = sections,
        )
    }
}
