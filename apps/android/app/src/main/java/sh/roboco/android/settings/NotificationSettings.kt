package sh.roboco.android.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import sh.roboco.android.design.AlertButton
import sh.roboco.android.design.AlertButtonRole
import sh.roboco.android.design.Alerts
import sh.roboco.android.design.GroupedAccessory
import sh.roboco.android.design.GroupedRow
import sh.roboco.android.design.LocalAlerts
import sh.roboco.android.design.RobocoAlert
import sh.roboco.android.design.RobocoIcon
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoSwitch
import sh.roboco.android.design.groupedSection
import sh.roboco.android.design.settingsTag
import sh.roboco.android.design.toggleTag
import sh.roboco.android.notify.NotificationKind
import sh.roboco.android.notify.NotificationPermission
import sh.roboco.android.notify.NotificationPrefs
import sh.roboco.android.notify.NotificationStatus
import sh.roboco.android.notify.rememberNotificationPermissionRequester

/**
 * The "Notifications" section of the Settings screen (android-ui-parity
 * #99): the port of `MoreViewController.swift:195-196` (the section), its
 * rows (`:126-144`) and the toggle handler (`:146-178`) over the shared
 * preference store (android-app#28's [NotificationPrefs]) and the
 * permission reader (this ticket's
 * [NotificationPermission][sh.roboco.android.notify.NotificationPermission]).
 *
 * One master row ("Notifications", a switch) plus, while notifications are
 * effectively on, three kind rows ("Run finished", "Waiting on your
 * input", "Run failed"), each with its own switch:
 *
 *  - the master switch shows `enabled && Granted` (never `enabled` alone),
 *    so a revoked permission reads as off;
 *  - the kind rows appear and vanish **instantly** (iOS
 *    `animatingDifferences: false`, `MoreViewController.swift:219`) — no
 *    `animateItem`, no tween;
 *  - master off persists `enabled = false` and drops the kinds in the same
 *    frame; master on persists `enabled = true` **before** asking the
 *    system, so a later denial leaves the pref true (iOS's order);
 *  - master on while `Denied` never touches the pref: ticket 18's
 *    "Notifications are off" alert (Not Now / Open Settings) — only system
 *    settings can turn notifications back on;
 *  - master on while `NotDetermined` (API 33+) launches the
 *    `POST_NOTIFICATIONS` request exactly once; `pendingOn` holds the
 *    switch on behind the system dialog, and a refusal slides it back;
 *  - kind switches write their pref only — no request, no alert, no
 *    reload (`MoreViewController.swift:177-178`).
 *
 * Every visual — geometry, fonts, colours, separators, the row layout —
 * comes from ticket 70's [GroupedRow] and ticket 98's [RobocoSwitch];
 * this file sets no dp, font or colour of its own. The alert is ticket
 * 18's through [LocalAlerts]. Status refresh is the host's job: ticket
 * 103 calls [NotificationSectionState.refreshStatus] on `ON_RESUME`
 * (iOS `willEnterForegroundNotification` + `viewWillAppear`,
 * `MoreViewController.swift:99-111`); this file registers no lifecycle
 * observer of its own.
 */

/** The section's copy, verbatim from the ticket's table (iOS strings adapted per the rename rule). */
object NotificationCopy {

    /** `MoreViewController.swift:195` (the section header). */
    const val Header = "Notifications"

    /** `MoreViewController.swift:131` (the master row's title). */
    const val MasterTitle = "Notifications"

    /** `MoreViewController.swift:132` (the fresh-install subtitle). */
    const val MasterSubtitle = "When a session finishes, needs you or fails"

    /** `MoreViewController.swift:132` adapted: "Turned off in iOS Settings". */
    const val MasterSubtitleDenied = "Turned off in Android settings"

    /** `MoreViewController.swift:150`, adapted (lower-case "settings": Android's app is not called "Settings" in prose). */
    const val AlertTitle = "Notifications are off"

    /** `MoreViewController.swift:150`, adapted (Zeron → Roboco). */
    const val AlertMessage = "Allow notifications for Roboco in Android settings."

    /** `MoreViewController.swift:152` (the cancel action). */
    const val AlertNotNow = "Not Now"

    /** `MoreViewController.swift:153` (the default action). */
    const val AlertOpenSettings = "Open Settings"

    /** `MoreViewController.swift:131` (the master row's id). */
    const val MasterId = "notify:enabled"

    /** `MoreViewController.swift:137`: `notify:<kind raw>`. */
    fun kindId(kind: NotificationKind) = "notify:${kind.raw}"
}

/**
 * One row of the section, pure data: the [GroupedRow] inputs plus the
 * switch's value and the click's subject ([kind] is null on the master
 * row). Built only by [notificationRows]; the gallery and screenshot
 * fixtures build them from fixed snapshots instead of a live store.
 */
@Immutable
data class NotificationRowModel(
    val id: String,
    val title: String,
    val subtitle: String?,
    val icon: RobocoIcon,
    val checked: Boolean,
    val kind: NotificationKind?,
)

/**
 * The section's rows for one state — `MoreViewController.swift:126-144`
 * verbatim: the master row always, the three kind rows only while
 * `prefs.enabled && status == Granted`, in `Kind.allCases` order (done,
 * input, failed). The denied subtitle depends only on [status] (never on
 * `enabled`); the master's [checked][NotificationRowModel.checked] is
 * `notificationsOn || pendingOn`, so the switch stays drawn on behind the
 * denied alert and while the system dialog is up.
 */
fun notificationRows(
    prefs: NotificationPrefs.Snapshot,
    status: NotificationStatus,
    pendingOn: Boolean,
): List<NotificationRowModel> {
    val on = prefs.enabled && status == NotificationStatus.Granted
    val master = NotificationRowModel(
        id = NotificationCopy.MasterId,
        title = NotificationCopy.MasterTitle,
        subtitle = if (status == NotificationStatus.Denied) {
            NotificationCopy.MasterSubtitleDenied
        } else {
            NotificationCopy.MasterSubtitle
        },
        icon = RobocoIcons.Bell,
        checked = on || pendingOn,
        kind = null,
    )
    if (!on) return listOf(master)
    return listOf(master) + NotificationKind.entries.map { kind ->
        NotificationRowModel(
            id = NotificationCopy.kindId(kind),
            title = kind.title,
            subtitle = null,
            icon = kindIcon(kind),
            checked = prefs.isOn(kind),
            kind = kind,
        )
    }
}

/** `MoreViewController.swift:135-139`: the kind rows' SF symbols, through ticket 06. */
private fun kindIcon(kind: NotificationKind): RobocoIcon = when (kind) {
    NotificationKind.Done -> RobocoIcons.CheckmarkCircle
    NotificationKind.Input -> RobocoIcons.QuestionmarkBubble
    NotificationKind.Failed -> RobocoIcons.ExclamationmarkTriangle
}

// ---------------------------------------------------------------------------
// The state holder
// ---------------------------------------------------------------------------

/**
 * The section's behaviour (ticket 103's contract: the screen-level state
 * holder). All members are main-thread only — switch callbacks, alert
 * handlers, the activity-result callback and lifecycle callbacks all
 * arrive there.
 *
 * State: `status` (cached, Compose state — re-read through
 * [refreshStatus]), `pendingOn` (the switch drawn on behind the alert /
 * system dialog), `prefsSnapshot` (mirroring [NotificationPrefs.state],
 * so external writers like android-app#28 move the switches on the next
 * frame) and `requestInFlight` (a plain field: the double-tap guard,
 * invisible to composition). [rows] is a snapshot read, so the list
 * re-derives on the next frame with no animation.
 *
 * `requestPermission` is a `var` wired by [rememberNotificationSectionState]
 * (the launcher lives in composition, the state must not); tests inject a
 * counting lambda.
 */
@Stable
class NotificationSectionState(
    val prefs: NotificationPrefs,
    private val readStatus: () -> NotificationStatus,
    private val alerts: Alerts,
    private val openSettings: () -> Unit,
) {

    /** Set by [rememberNotificationSectionState] once the launcher exists. */
    var requestPermission: () -> Unit = {}

    /** The cached permission status; written only through [refreshStatus]. */
    var status: NotificationStatus by mutableStateOf(readStatus())
        private set

    /** True while the denied alert is up or a permission request is in flight. */
    var pendingOn: Boolean by mutableStateOf(false)
        private set

    /** Mirrors [NotificationPrefs.state]; the factory's collector also writes it. */
    var prefsSnapshot: NotificationPrefs.Snapshot by mutableStateOf(prefs.state.value)
        internal set

    /** The rows for the current state — a snapshot read (`MoreViewController.swift:126-144`). */
    val rows: List<NotificationRowModel>
        get() = notificationRows(prefsSnapshot, status, pendingOn)

    /** The double-tap guard for the request path; plain field, never composed. */
    private var requestInFlight = false

    /**
     * Re-reads the status and writes it only when it changed — iOS's
     * `guard status != self.notificationStatus else { return }`
     * (`MoreViewController.swift:104-111`). Synchronous, main-safe.
     * Ticket 103 calls this on `ON_RESUME`; it is idempotent, so an
     * `ON_RESUME` that lands between the permission dialog closing and
     * the result callback changes nothing.
     */
    fun refreshStatus() {
        val s = readStatus()
        if (s != status) status = s
    }

    /** `toggled(row.id, toggle.isOn)`: a kind row writes its pref; the master runs the machine. */
    fun onToggle(row: NotificationRowModel, on: Boolean) {
        val kind = row.kind
        if (kind != null) {
            prefs.set(kind, on)
            prefsSnapshot = prefs.state.value
        } else {
            onMasterToggled(on)
        }
    }

    /**
     * The master switch, `MoreViewController.swift:146-178`:
     *
     *  - **off**: `enabled = false`, the kind rows vanish in this frame;
     *  - **on, Denied**: `enabled` is **not** changed — only system
     *    settings can turn notifications back on. The alert shows, with
     *    `pendingOn` holding the switch on behind it;
     *  - **on, Granted**: `enabled = true`, the kind rows appear at once,
     *    no request;
     *  - **on, NotDetermined** (API 33+ only): `enabled = true` is
     *    persisted **before** the ask (iOS's order, so a denial leaves it
     *    true), `pendingOn` holds the switch on, and exactly one request
     *    launches (the in-flight guard swallows a double tap).
     *
     * A fresh status is read first: iOS checks its cache after its
     * refresh triggers, and a fresh read can only avoid a stale decision.
     */
    fun onMasterToggled(on: Boolean) {
        if (!on) {
            pendingOn = false
            prefs.enabled = false
            prefsSnapshot = prefs.state.value
            return
        }
        refreshStatus()
        when (status) {
            NotificationStatus.Denied -> {
                pendingOn = true
                alerts.show(deniedAlert())
            }

            NotificationStatus.Granted -> {
                prefs.enabled = true
                prefsSnapshot = prefs.state.value
            }

            NotificationStatus.NotDetermined -> {
                if (requestInFlight) return
                prefs.enabled = true
                prefsSnapshot = prefs.state.value
                pendingOn = true
                requestInFlight = true
                requestPermission()
            }
        }
    }

    /**
     * The request's result (the requester already recorded it through
     * [NotificationPermission.recordRequestResult]): the guard frees, the
     * switch stops holding, the status re-reads. Granted → the switch
     * stays on and the kind rows appear; not granted → the switch slides
     * back off, and the subtitle becomes the denied copy only if the
     * result made the status `Denied`.
     */
    fun onPermissionResult(granted: Boolean) {
        requestInFlight = false
        pendingOn = false
        refreshStatus()
    }

    /**
     * The denied alert, `MoreViewController.swift:150-155` with Roboco's
     * copy: "Not Now" (Cancel, leading) slides the switch back; "Open
     * Settings" (Default) fires [openSettings] first. Both buttons
     * re-read the status — ticket 18 also routes back and scrim taps to
     * the Cancel action.
     */
    private fun deniedAlert() = RobocoAlert(
        title = NotificationCopy.AlertTitle,
        message = NotificationCopy.AlertMessage,
        buttons = listOf(
            AlertButton(
                title = NotificationCopy.AlertNotNow,
                role = AlertButtonRole.Cancel,
                requiresText = false,
            ) {
                pendingOn = false
                refreshStatus()
            },
            AlertButton(
                title = NotificationCopy.AlertOpenSettings,
                role = AlertButtonRole.Default,
                requiresText = false,
            ) {
                openSettings()
                pendingOn = false
                refreshStatus()
            },
        ),
    )
}

/**
 * Remembers the section's state. Call at the **top of SettingsScreen**
 * (ticket 103), never inside a `LazyColumn` item: the permission
 * launcher's registration must survive scrolling. The wiring:
 *
 *  - the status is read synchronously once, so the first frame is
 *    correct (iOS `viewWillAppear`);
 *  - [NotificationPrefs.state] is collected into
 *    [NotificationSectionState.prefsSnapshot], so a write from outside
 *    the section (android-app#28's first-session ask, a test) moves the
 *    switches on the next frame;
 *  - `state.requestPermission` is (re)bound to the launcher through a
 *    [SideEffect] on every recomposition, so it is never the stale
 *    remembered lambda of a first composition;
 *  - no lifecycle observer is registered here: ticket 103 owns the
 *    `ON_RESUME` → [NotificationSectionState.refreshStatus] hook. A
 *    second observer would only double the reads.
 *
 * [readStatus]/[openSettings] are the test seams (ticket 103's fake-seam
 * contract): pass lambdas to bypass the real permission reader and the
 * real system-settings intent.
 */
@Composable
fun rememberNotificationSectionState(
    readStatus: (() -> NotificationStatus)? = null,
    openSettings: (() -> Unit)? = null,
): NotificationSectionState {
    val context = LocalContext.current
    val alerts = LocalAlerts.current
    val prefs = remember { NotificationPrefs.get(context) }
    val state = remember(prefs, alerts) {
        NotificationSectionState(
            prefs = prefs,
            readStatus = readStatus ?: { NotificationPermission.status(context, prefs) },
            alerts = alerts,
            openSettings = openSettings
                ?: { NotificationPermission.openAppNotificationSettings(context) },
        )
    }
    val requester = rememberNotificationPermissionRequester(prefs) { granted ->
        state.onPermissionResult(granted)
    }
    SideEffect { state.requestPermission = requester::request }
    LaunchedEffect(state) { prefs.state.collect { state.prefsSnapshot = it } }
    return state
}

// ---------------------------------------------------------------------------
// The emitters
// ---------------------------------------------------------------------------

/**
 * Emits the section into ticket 103's `LargeTitleScaffold` content (or
 * any `LazyColumn`): the "Notifications" header and the rows, one lazy
 * item per row. [state.rows] is read inside this builder (a snapshot
 * read), so the items re-derive on the next frame when the status, the
 * prefs or `pendingOn` change — with **no** `animateItem()` and no tween
 * of its own (iOS reloads with `animatingDifferences: false`,
 * `MoreViewController.swift:219`); the only motion is ticket 98's switch
 * slide. [key] keeps the gallery's three fixtures apart.
 */
fun LazyListScope.notificationSection(
    state: NotificationSectionState,
    key: String = "notifications",
) = notificationRowsSection(state.rows, state::onToggle, key)

/**
 * The stateless emitter: fixed [rows] (the gallery and the screenshot
 * fixtures call it without a live store), each row ticket 70's
 * [GroupedRow] with ticket 98's [RobocoSwitch] in its `Custom` accessory
 * slot. The row body is not clickable (`MoreViewController.swift:222-257`
 * hits `default: break` for `notify:` ids): a row tap neither flips the
 * switch nor flashes the highlight.
 *
 * Lazy keys are ticket 70's `grouped:<key>:header` /
 * `grouped:<key>:row:<id>` with the row id as the item key — never the
 * checked value (a value key would recreate the row and cut ticket 98's
 * thumb animation). The tags appear exactly once each: the row's
 * `settings-<id>` through [GroupedRow]'s own `testTag`, the switch's
 * `toggle-<id>` through [RobocoSwitch]'s.
 */
fun LazyListScope.notificationRowsSection(
    rows: List<NotificationRowModel>,
    onToggle: (NotificationRowModel, Boolean) -> Unit,
    key: String = "notifications",
) {
    groupedSection(
        key = key,
        header = NotificationCopy.Header,
        items = rows,
        itemKey = { it.id },
    ) { row, position ->
        GroupedRow(
            title = row.title,
            position = position,
            subtitle = row.subtitle,
            icon = row.icon,
            accessories = listOf(
                GroupedAccessory.Custom {
                    RobocoSwitch(
                        checked = row.checked,
                        onCheckedChange = { onToggle(row, it) },
                        testTag = toggleTag(row.id),
                    )
                },
            ),
            testTag = settingsTag(row.id),
            onClick = null,
        )
    }
}
