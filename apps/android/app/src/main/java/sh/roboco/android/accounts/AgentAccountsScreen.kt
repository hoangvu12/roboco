package sh.roboco.android.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import android.content.ClipData
import android.content.ClipboardManager
import sh.roboco.android.RobocoApplication
import sh.roboco.android.composer.openInCustomTab
import sh.roboco.android.core.Face
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.GroupedAccessory
import sh.roboco.android.design.GroupedFooter
import sh.roboco.android.design.GroupedHeader
import sh.roboco.android.design.GroupedPosition
import sh.roboco.android.design.GroupedRow
import sh.roboco.android.design.RobocoSwitch
import sh.roboco.android.design.GroupedRowContainer
import sh.roboco.android.design.HarnessMark
import sh.roboco.android.design.Icon
import sh.roboco.android.design.LiftHighlight
import sh.roboco.android.design.LocalActionSheet
import sh.roboco.android.design.LocalAlerts
import sh.roboco.android.design.LocalBottomChrome
import sh.roboco.android.design.LocalToaster
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.PullToRefreshState
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.contextMenu
import sh.roboco.android.design.rememberContextMenuLiftState
import sh.roboco.android.design.rememberPullToRefreshState
import sh.roboco.android.design.uiStyle
import sh.roboco.android.design.glass.ActivitySpinner
import sh.roboco.android.design.glass.GlassCircleButton
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.design.glass.rememberGlassMenuState
import sh.roboco.android.shell.LargeTitleScaffold
import uniffi.roboco_core.AgentAccount
import uniffi.roboco_core.AgentAccountsSnapshot
import uniffi.roboco_core.AgentUsageWindow
import uniffi.roboco_core.DeviceView
import uniffi.roboco_core.WorkspaceSnapshot

/**
 * The Accounts & Usage page (android-next#05) — the port of PR #578's
 * `ProviderAccountsViewController`: every agent CLI login saved on one
 * computer, grouped per harness, each window's meter, percent and
 * written-out reset time spelled out. Tapping a login makes it the one in
 * use (optimistic, toast confirms, single-choice per group); remove is in
 * the row's context menu, confirmed through the action sheet; pull to
 * refresh re-probes; a computer picker appears when more than one engine
 * is paired. Wave-10 34 adds "Add account" (upstream #966's iPhone
 * flow): one row per login option under each agent the computer offers;
 * the computer runs the sign-in and the phone finishes it in a Custom
 * Tab ([AgentAccountsModel.onAddRow]).
 *
 * The behaviour lives in [AgentAccountsModel]; this file only draws. The
 * page is pushed as [AgentAccountsRoute] (the Settings section, the usage
 * chip's "Accounts & Usage…" row, the session ⋯ menu's "Account & Usage").
 */
@Composable
fun AgentAccountsScreen(
    route: AgentAccountsRoute,
    modifier: Modifier = Modifier,
    core: AccountsCore? = null,
    workspace: StateFlow<WorkspaceSnapshot?>? = null,
    workScope: CoroutineScope? = null,
    io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    nowMs: () -> Long = System::currentTimeMillis,
    /**
     * Wave-10 34's "Add account" seam. Production (no [core] passed) builds
     * the live one; a caller that fakes [core] passes its own or none (no
     * add rows — the read-only page's tests never touch the FFI).
     */
    signIn: AccountSignIn? = null,
) {
    val context = LocalContext.current
    val dark = RobocoTheme.isDark
    val app = context.applicationContext as? RobocoApplication
    val devicesFlow = workspace ?: app?.workspaceFeed?.snapshot
    val snapshot by (devicesFlow ?: remember { MutableStateFlow<WorkspaceSnapshot?>(null) }).collectAsState()
    val devices = remember(snapshot) {
        (snapshot?.devices ?: emptyList()).filter { it.isExecutionHost }
    }
    val toaster = LocalToaster.current
    val alerts = LocalAlerts.current
    val sheets = LocalActionSheet.current
    val effectiveCore = core ?: remember(app) {
        app?.let { CoreAccountsCore(client = { it.clientOrNull() }) }
    }
    val effectiveSignIn = signIn ?: if (core == null) {
        remember(app) { app?.let { CoreAccountSignIn(client = { it.clientOrNull() }) } }
    } else {
        null
    }
    val scope = workScope ?: remember(app) {
        CoroutineScope(
            (app?.appScope ?: CoroutineScope(Dispatchers.Main.immediate)).coroutineContext +
                Dispatchers.Main.immediate,
        )
    }
    // The device-name lambda reads the latest device list without
    // re-creating the model on every workspace change.
    val latestDevices by rememberUpdatedState(devices)
    // The tab opener and the clipboard read the latest context/appearance
    // without re-creating the model (and losing the page) on a theme flip.
    val latestContext by rememberUpdatedState(context)
    val latestDark by rememberUpdatedState(dark)
    val model = remember(effectiveCore, effectiveSignIn, scope, io, toaster, alerts, sheets) {
        if (effectiveCore != null) {
            AgentAccountsModel(
                core = effectiveCore,
                workScope = scope,
                toaster = toaster,
                alerts = alerts,
                sheets = sheets,
                deviceName = { id -> latestDevices.firstOrNull { it.id == id }?.name ?: "your computer" },
                deviceOnline = { id -> latestDevices.firstOrNull { it.id == id }?.online ?: true },
                io = io,
                nowMs = nowMs,
                signIn = effectiveSignIn,
                openUrl = { url -> openInCustomTab(latestContext, url, latestDark) },
                copyText = { text ->
                    // A sign-in code: marked sensitive, so the system's
                    // clipboard preview never shows it (API 33's
                    // `ClipDescription.EXTRA_IS_SENSITIVE`, by its key on 31–32).
                    val clip = ClipData.newPlainText("Code", text).apply {
                        description.extras = android.os.PersistableBundle().apply {
                            putBoolean("android.content.extra.IS_SENSITIVE", true)
                        }
                    }
                    latestContext.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                },
            )
        } else {
            null
        }
    }
    // Leaving the page ends a computer's sign-in rather than polling for
    // ten minutes behind it (iOS `viewWillDisappear`).
    DisposableEffect(model) {
        onDispose { model?.cancelSignIn() }
    }
    // The opener's device, else the primary one once the workspace knows
    // the engines. Re-resolved only while nothing is open yet.
    LaunchedEffect(route.deviceId, devices.isNotEmpty()) {
        if (model != null && model.deviceId == null) {
            model.open(route.deviceId ?: primaryDevice(devices)?.id)
        }
    }

    // wave-10 35: an error card's "Log in" starts the agent's first login
    // once the accounts and the computer's agents are both in (its waiting
    // row can then show, with its Tap to cancel) — once per page. The model
    // refuses an agent with no section, so nothing starts unseen.
    var firstLoginStarted by remember { mutableStateOf(false) }
    val accountsIn = model?.snapshot != null
    val agentsIn = model?.agents != null
    LaunchedEffect(accountsIn, agentsIn) {
        val harness = route.focusHarness
        if (model != null && route.signIn && !firstLoginStarted && harness != null && accountsIn && agentsIn) {
            if (model.startFirstLogin(harness)) firstLoginStarted = true
        }
    }

    val listState = rememberLazyListState()
    val chrome = LocalBottomChrome.current
    // The session opener's agent: scroll that harness's section header to
    // the top once its snapshot lands (the first section already is).
    val focusIndex = model?.let { m ->
        if (m.snapshot == null) {
            null
        } else {
            focusSectionIndex(m.sections(), route.focusHarness) { harness -> extraRowCount(m, harness) }
        }
    }
    LaunchedEffect(focusIndex, route.focusHarness) {
        if (focusIndex != null && focusIndex > 0) {
            listState.scrollToItem(focusIndex)
        }
    }
    LargeTitleScaffold(
        title = AccountsCopy.PageTitle,
        modifier = modifier.testTag("agent-accounts"),
        listState = listState,
        contentPadding = PaddingValues(bottom = chrome.contentPadding + 16.dp),
        trailing = {
            // The computer picker (iOS's nav-bar device menu): only when
            // more than one engine is paired.
            if (devices.size > 1 && model != null) {
                DevicePicker(devices, model)
            }
        },
        pullToRefresh = if (model == null) {
            null
        } else {
            rememberPullToRefreshState(onRefresh = { model.pullToRefresh() })
        },
        content = {
            if (model != null) {
                agentAccountsPage(model)
            }
        },
    )
}

/**
 * The computer picker: the trailing circle button opening the glass
 * menu of paired engines (checked on the selected one).
 */
@Composable
private fun DevicePicker(devices: List<DeviceView>, model: AgentAccountsModel) {
    val menu = rememberGlassMenuState()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    GlassCircleButton(
        icon = RobocoIcons.Desktopcomputer,
        contentDescription = "Computer",
        onClick = {
            val selected = model.deviceId
            menu.open(
                bounds,
                Menu(
                    title = "Computer",
                    children = devices.map { device ->
                        MenuItem(
                            title = device.name,
                            subtitle = if (device.online) "Online" else "Offline",
                            checked = device.id == selected,
                            testTag = "accounts-device-${device.engineId}",
                        ) { model.open(device.id) }
                    },
                ),
            )
        },
        modifier = Modifier.onGloballyPositioned { bounds = it.boundsInWindow() },
    )
}

// ---------------------------------------------------------------------------
// The list (the page body, the gallery and the tests)
// ---------------------------------------------------------------------------

/**
 * The lazy item index of `focusHarness`'s section header (0 when it is
 * the first section or absent), for the session opener's scroll. Pure.
 */
internal fun focusSectionIndex(
    groups: List<ProviderGroup>,
    focusHarness: String?,
    extraRows: (String) -> Int,
): Int? {
    if (focusHarness == null) return null
    var index = 0
    for (group in groups) {
        if (group.harness == focusHarness) return index
        // header + rows + add rows + optional warnings footer
        index += 1 + group.accounts.size + extraRows(group.harness) + if (group.warnings.isNotEmpty()) 1 else 0
    }
    return null
}

/** The add rows plus the waiting row under `harness` (lazy items after its accounts). */
private fun extraRowCount(model: AgentAccountsModel, harness: String): Int =
    model.addRows(harness).size + if (model.signingIn?.harness == harness) 1 else 0

/**
 * Emits the page: one section per harness (header with the brand mark,
 * the login rows, the harness's warnings as the footer), the status row
 * while nothing is on screen, and the about footer (usage freshness +
 * where logins are added). Keys `grouped:accounts:<harness>:*`.
 *
 * [harnessLabel] resolves each section header's display name — production
 * passes the core's static [harnessLabelOf], tests inject their own.
 */
internal fun LazyListScope.agentAccountsPage(
    model: AgentAccountsModel,
    harnessLabel: (String) -> String = ::harnessLabelOf,
) {
    val snapshot: AgentAccountsSnapshot? = model.snapshot
    val groups = model.sections()
    if (snapshot == null) {
        item(key = "grouped:accounts:status", contentType = "grouped-row") {
            AccountsStatusRow(
                message = when (val load = model.load) {
                    is AccountsLoad.Failed -> load.message
                    is AccountsLoad.Unsupported -> load.message
                    AccountsLoad.Loading -> AccountsCopy.Loading
                    AccountsLoad.Ready -> AccountsCopy.Loading
                },
            )
        }
    } else if (groups.isEmpty()) {
        item(key = "grouped:accounts:empty", contentType = "grouped-row") {
            AccountsStatusRow(message = AccountsCopy.noAccounts(model.nameOfSelected()))
        }
    }
    for (group in groups) {
        item(key = "grouped:accounts:${group.harness}:header", contentType = "grouped-header") {
            HarnessHeader(group.harness, harnessLabel(group.harness))
        }
        // One card per agent: its logins, then the add rows (wave-10 34),
        // then the sign-in under way.
        val addRows = model.addRows(group.harness)
        val waiting = model.signingIn?.takeIf { it.harness == group.harness }
        val cardRows = group.accounts.size + addRows.size + if (waiting != null) 1 else 0
        group.accounts.forEachIndexed { row, account ->
            val position = GroupedPosition.of(row, cardRows)
            item(
                key = "grouped:accounts:${group.harness}:row:${account.id}",
                contentType = "grouped-row",
            ) {
                AgentAccountRow(
                    account = account,
                    switching = model.switching == account.id,
                    position = position,
                    model = model,
                )
            }
        }
        addRows.forEachIndexed { offset, add ->
            val position = GroupedPosition.of(group.accounts.size + offset, cardRows)
            item(key = "grouped:accounts:${group.harness}:${add.id}", contentType = "grouped-row") {
                AddAccountRowView(add, position, enabled = model.addEnabled) { model.onAddRow(add) }
            }
        }
        if (waiting != null) {
            item(key = "grouped:accounts:${group.harness}:waiting", contentType = "grouped-row") {
                SignInWaitingRow(waiting, GroupedPosition.of(cardRows - 1, cardRows)) { model.cancelSignIn() }
            }
        }
        if (group.warnings.isNotEmpty()) {
            item(key = "grouped:accounts:${group.harness}:footer", contentType = "grouped-footer") {
                GroupedFooter(
                    group.warnings.joinToString("\n"),
                    Modifier.testTag("accounts-${group.harness}-warning"),
                    color = RobocoTheme.palette.warning,
                )
            }
        }
    }
    // Wave-10 40: the computer's agents, each with its on/off switch.
    val agentRows = model.agentRows()
    if (agentRows.isNotEmpty()) {
        item(key = "grouped:accounts:agents:header", contentType = "grouped-header") {
            GroupedHeader(AgentsCopy.header(model.nameOfSelected()))
        }
        agentRows.forEachIndexed { ix, agent ->
            item(key = "grouped:accounts:agents:${agent.harness}", contentType = "grouped-row") {
                AgentToggleRowView(
                    agent = agent,
                    position = GroupedPosition.of(ix, agentRows.size),
                    busy = agent.harness in model.toggling,
                    onChange = { on -> model.setAgentEnabled(agent.harness, on) },
                )
            }
        }
        item(key = "grouped:accounts:agents:footer", contentType = "grouped-footer") {
            GroupedFooter(AgentsCopy.Footer)
        }
    }
    item(key = "grouped:accounts:about", contentType = "grouped-footer") {
        val updated = model.updatedText
        Column {
            updated?.let {
                GroupedFooter(
                    if (model.refreshing) AccountsCopy.UpdatingUsage else "$it.",
                    Modifier.testTag("accounts-updated"),
                )
            }
            GroupedFooter(
                if (model.canAddAccounts) {
                    SignInCopy.about(model.nameOfSelected())
                } else {
                    AccountsCopy.signInSection(model.nameOfSelected())
                },
                Modifier.testTag("accounts-about"),
            )
        }
    }
}

/**
 * The header of one harness's section: [GroupedFooter] geometry with the
 * harness's brand mark at 16 dp beside the label (iOS's
 * `BrandMarks.image(for:side: 16)` + `imageToTextPadding = 8`). The label
 * comes from the caller — production resolves it through the core's
 * static `harnessLabel` (a pure lookup), tests inject their own.
 */
@Composable
internal fun HarnessHeader(harness: String, label: String) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = 32.dp,
                end = 32.dp,
                top = 27.dp,
                bottom = 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HarnessMark(harness, 16.dp, palette.secondary, Modifier.testTag("accounts-header-$harness-mark"))
        Spacer(Modifier.width(8.dp))
        BasicText(
            label,
            style = uiStyle(Face.SansSemibold, typeScale.bodyScaled(16f), palette.secondary),
            modifier = Modifier.testTag("accounts-header-$harness"),
        )
    }
}

/**
 * The harness's display name (the core's static label helper — a pure
 * catalog lookup, like the new-session chips' `ChipLabels.harnessLabel`).
 */
internal fun harnessLabelOf(harness: String): String = uniffi.roboco_core.harnessLabel(harness)

/**
 * One agent of the computer (wave-10 40, iOS's Providers computer row):
 * its name over "Installed" / "Not installed" / "Offline", dimmed when not
 * installed, and its switch — a locked or busy switch ignores the flip and
 * springs back.
 */
@Composable
internal fun AgentToggleRowView(
    agent: AgentToggleRow,
    position: GroupedPosition,
    busy: Boolean,
    onChange: (Boolean) -> Unit,
) {
    GroupedRow(
        title = agent.label,
        subtitle = agent.subtitle,
        position = position,
        modifier = if (agent.dimmed) Modifier.alpha(0.5f) else Modifier,
        accessories = listOf(
            GroupedAccessory.Custom {
                // A locked or busy switch refuses the flip without its haptic.
                RobocoSwitch(
                    checked = agent.on,
                    onCheckedChange = onChange,
                    modifier = if (agent.toggleEnabled && !busy) Modifier else Modifier.alpha(0.5f),
                    testTag = "agent-toggle-${agent.harness}",
                    enabled = agent.toggleEnabled && !busy,
                )
            },
        ),
        testTag = "agent-${agent.harness}",
        onClick = null,
    )
}

/**
 * One "Add account" row (iOS's quiet add cell): a muted plus and the label
 * in the text colour, lined up with the account names; both tertiary while
 * another sign-in or a switch is under way.
 */
@Composable
internal fun AddAccountRowView(
    row: AddAccountRow,
    position: GroupedPosition,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    GroupedRowContainer(
        position = position,
        onClick = if (enabled) onClick else null,
        testTag = row.id,
    ) {
        Spacer(Modifier.width(16.dp))
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(
                RobocoIcons.Plus,
                if (enabled) palette.secondary else palette.tertiary,
                14f,
                SymbolWeight.Semibold,
            )
        }
        Spacer(Modifier.width(12.dp))
        BasicText(
            row.title,
            style = uiStyle(Face.SansMedium, typeScale.fixed(15f), if (enabled) palette.text else palette.tertiary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 13.dp),
        )
        Spacer(Modifier.width(16.dp))
    }
}

/**
 * The sign-in under way (iOS's waiting cell): "Finish signing in in the
 * browser…", the device-code note and "Tap to cancel", and a spinner. A
 * tap ends it here and on the computer.
 */
@Composable
internal fun SignInWaitingRow(state: SignInState, position: GroupedPosition, onCancel: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    GroupedRowContainer(
        position = position,
        onClick = onCancel,
        testTag = "sign-in-waiting",
    ) {
        Spacer(Modifier.width(16.dp))
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 12.dp),
        ) {
            BasicText(
                SignInCopy.Waiting,
                style = uiStyle(Face.SansMedium, typeScale.fixed(16f), palette.text),
            )
            BasicText(
                signInDetail(state),
                style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                modifier = Modifier.testTag("sign-in-waiting-detail"),
            )
        }
        ActivitySpinner(palette.secondary, Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
    }
}

/**
 * The centered status row (iOS's message cell): secondary 15 pt text on
 * the elevated card, not clickable.
 */
@Composable
internal fun AccountsStatusRow(message: String) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    GroupedRowContainer(
        position = GroupedPosition.Single,
        onClick = null,
        testTag = "accounts-status",
    ) {
        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                message,
                style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.secondary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The login row
// ---------------------------------------------------------------------------

/**
 * One login (PR #578's `ProviderAccountCell`): the avatar (ringed when in
 * use), the title, `plan · org · In use`, a meter per usage window (or
 * why there is none), the spinner while switching, the checkmark when
 * active. Tap switches (the model guards the not-switchable ones);
 * long-press opens the context menu (Use This Account / Copy Email /
 * Remove Account…); remove is confirmed through the action sheet.
 */
@Composable
internal fun AgentAccountRow(
    account: AgentAccount,
    switching: Boolean,
    position: GroupedPosition,
    model: AgentAccountsModel,
    modifier: Modifier = Modifier,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val liftState = rememberContextMenuLiftState()
    val clipboard = LocalClipboardManager.current
    GroupedRowContainer(
        position = position,
        modifier = modifier.contextMenu(
            highlight = LiftHighlight.GroupedCard,
            liftState = liftState,
        ) {
            accountContextMenu(account, model) { clipboard.setText(AnnotatedString(it)) }
        },
        onClick = { model.onRowTap(account) },
        testTag = "account-${account.id}",
    ) {
        Spacer(Modifier.width(16.dp))
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(account)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(
                        accountTitle(account),
                        style = uiStyle(Face.SansMedium, typeScale.fixed(16f), palette.text),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("account-${account.id}-title"),
                    )
                    val meta = accountMeta(account)
                    if (meta.isNotEmpty()) {
                        BasicText(
                            meta,
                            style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (switching) {
                    ActivitySpinner(
                        palette.secondary,
                        Modifier
                            .size(20.dp)
                            .testTag("account-switching"),
                    )
                    Spacer(Modifier.width(16.dp))
                } else if (account.active) {
                    Icon(
                        RobocoIcons.Checkmark,
                        palette.accent,
                        17f,
                        SymbolWeight.Semibold,
                        Modifier.testTag("account-active"),
                    )
                    Spacer(Modifier.width(16.dp))
                } else {
                    Spacer(Modifier.width(16.dp))
                }
            }
            if (account.usageWindows.isNotEmpty()) {
                Spacer(Modifier.size(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (window in account.usageWindows) {
                        UsageMeter(window)
                    }
                }
            }
            val note = if (account.usageWindows.isEmpty()) {
                missingUsage(account)
            } else {
                account.usageError
            }
            if (note != null) {
                Spacer(Modifier.size(8.dp))
                BasicText(
                    note,
                    style = uiStyle(
                        Face.Sans,
                        typeScale.fixed(13f),
                        if (account.usageError != null && !account.apiKey) palette.warning else palette.tertiary,
                    ),
                    modifier = Modifier.testTag("account-note-${account.id}"),
                )
            }
        }
    }
}

/**
 * The 40 dp avatar ring: a 34 dp control-fill circle with the initial (an
 * API key shows ⌘), ringed with a 2 dp accent border when the login is in
 * use.
 */
@Composable
private fun Avatar(account: AgentAccount) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(if (account.active) 40.dp else 34.dp)
                .clip(CircleShape)
                .then(
                    if (account.active) {
                        Modifier.border(2.dp, palette.accent, CircleShape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(palette.controlFill),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    if (account.apiKey) "\u2318" else accountTitle(account).take(1).uppercase(),
                    style = uiStyle(Face.SansSemibold, typeScale.fixed(15f), palette.text),
                )
            }
        }
    }
}

/**
 * The row's meta line: `plan · org`, then `In use` (the active row's
 * accent word, drawn by the caller in the secondary ink like the rest) or
 * `Credentials unavailable` — joined with ` · `.
 */
internal fun accountMeta(account: AgentAccount): String = buildString {
    accountDetail(account)?.let { append(it) }
    if (account.active) {
        if (isNotEmpty()) append(" \u00B7 ")
        append(AccountsCopy.InUse)
    } else if (!account.switchable) {
        if (isNotEmpty()) append(" \u00B7 ")
        append(AccountsCopy.CredentialsUnavailable)
    }
}

/**
 * The row's context menu (iOS's `contextMenuConfigurationForItemsAt`):
 * Use This Account (when not active and switchable), Copy Email (when it
 * has one), Remove Account… (destructive, when switchable).
 */
private fun accountContextMenu(
    account: AgentAccount,
    model: AgentAccountsModel,
    copyEmail: (String) -> Unit,
): Menu = Menu(
    title = null,
    children = buildList {
        if (!account.active && account.switchable) {
            add(
                MenuItem(
                    title = AccountsCopy.UseThisAccount,
                    icon = MenuIcon.Symbol(RobocoIcons.CheckmarkCircle),
                    testTag = "account-menu-use",
                ) { model.switchTo(account) },
            )
        }
        account.email?.let { email ->
            add(
                MenuItem(
                    title = AccountsCopy.CopyEmail,
                    icon = MenuIcon.Symbol(RobocoIcons.DocOnDoc),
                    testTag = "account-menu-copy",
                ) { copyEmail(email) },
            )
        }
        if (account.switchable) {
            add(
                MenuItem(
                    title = AccountsCopy.RemoveAccountDots,
                    icon = MenuIcon.Symbol(RobocoIcons.Trash),
                    destructive = true,
                    testTag = "account-menu-remove",
                ) { model.onRemove(account) },
            )
        }
    },
)

// ---------------------------------------------------------------------------
// The usage meters
// ---------------------------------------------------------------------------

/**
 * One usage window (iOS's `UsageMeterView`): the label and the percent on
 * one line (the percent in the level's colour unless normal), a 6 dp
 * capsule track with the fill at the level's colour, and the written-out
 * reset time under it.
 */
@Composable
internal fun UsageMeter(
    window: AgentUsageWindow,
    nowMs: () -> Long = System::currentTimeMillis,
    locale: java.util.Locale = java.util.Locale.getDefault(),
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val fraction = clampedFraction(window.usedFraction)
    val level = usageLevelOf(fraction)
    val color = usageLevelColor(level)
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("usage-meter-${window.label}"),
    ) {
        Row(Modifier.fillMaxWidth()) {
            BasicText(
                window.label,
                style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                modifier = Modifier.weight(1f),
            )
            BasicText(
                usagePercent(fraction),
                style = uiStyle(
                    Face.Mono,
                    typeScale.fixed(13f),
                    if (level == UsageLevel.Normal) palette.text else color,
                ),
            )
        }
        Spacer(Modifier.size(6.dp))
        MeterTrack(fraction, color, 6.dp, 0.015f)
        resetText(window.resetsAtMs, nowMs(), locale, zone)?.let {
            Spacer(Modifier.size(5.dp))
            BasicText(
                it,
                style = uiStyle(Face.Sans, typeScale.fixed(12f), palette.tertiary),
            )
        }
    }
}

/**
 * The compact trailing meter of the Settings section's rows (iOS's
 * `MiniUsageView`): a 46 dp bar (5 dp tall) plus the percent.
 */
@Composable
internal fun MiniUsageView(window: AgentUsageWindow) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val fraction = clampedFraction(window.usedFraction)
    val level = usageLevelOf(fraction)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.testTag("mini-usage"),
    ) {
        MeterTrack(fraction, usageLevelColor(level), height = 5.dp, minWidthFraction = 0.03f, width = 46.dp)
        BasicText(
            usagePercent(fraction),
            style = uiStyle(
                Face.Mono,
                typeScale.fixed(13f),
                if (level == UsageLevel.Normal) palette.secondary else usageLevelColor(level),
            ),
        )
    }
}

/**
 * The meter's capsule track with its fill: `height` tall, `width` wide
 * (the full row when unset), the fill at [color] and at least
 * [minWidthFraction] of the track so a near-empty window still reads.
 */
@Composable
private fun MeterTrack(
    fraction: Float,
    color: Color,
    height: androidx.compose.ui.unit.Dp,
    minWidthFraction: Float,
    width: androidx.compose.ui.unit.Dp? = null,
) {
    val palette = RobocoTheme.palette
    Box(
        Modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .height(height)
            .clip(CapsuleShape)
            .background(palette.controlFill),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceAtLeast(minWidthFraction))
                .height(height)
                .clip(CapsuleShape)
                .background(color),
        )
    }
}

/** The level's paint (`Palette.accent` / `warning` / `danger`). */
@Composable
internal fun usageLevelColor(level: UsageLevel): Color = when (level) {
    UsageLevel.Normal -> RobocoTheme.palette.accent
    UsageLevel.Warning -> RobocoTheme.palette.warning
    UsageLevel.Critical -> RobocoTheme.palette.danger
}

/**
 * The primary device of the Settings section (PR #578's `primaryHostId`):
 * the online computer with the most sessions, else the first one.
 * Pure.
 */
internal fun primaryDevice(devices: List<DeviceView>): DeviceView? {
    val online = devices.filter { it.online }
    val pool = if (online.isNotEmpty()) online else devices
    return pool.maxByOrNull { it.sessionCount } ?: pool.firstOrNull()
}
