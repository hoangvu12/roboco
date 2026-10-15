package sh.roboco.android.accounts

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.roboco.android.RobocoApplication
import sh.roboco.android.design.GroupedAccessory
import sh.roboco.android.design.GroupedPosition
import sh.roboco.android.design.GroupedRow
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.groupedSection
import sh.roboco.android.design.settingsTag
import sh.roboco.android.shell.LocalNavEntry
import sh.roboco.android.shell.LocalNavStack
import uniffi.roboco_core.AgentAccountsSnapshot
import uniffi.roboco_core.AgentUsageWindow
import uniffi.roboco_core.DeviceView
import uniffi.roboco_core.WorkspaceSnapshot

/**
 * The Settings "Agent Accounts" section (android-next#05) — the port of
 * PR #578's Provider Accounts summary, placed where #578 put it: ahead of
 * the computers (Roboco's Devices) section. One row per harness showing
 * the login in use and its fullest window as a small meter; the row opens
 * the Accounts & Usage page on the section's computer.
 *
 * Every visual is ticket 70's ([GroupedRow]); the mini meter is the
 * page's [MiniUsageView]. An engine too old for the accounts RPCs hides
 * the whole section for that engine (no header, no rows, no error spam);
 * an offline engine keeps the section with its clear offline state row.
 */
object AgentAccountsSectionCopy {
    /** The section header (iOS's "Provider Accounts", Roboco's wording). */
    const val Header = AccountsCopy.SectionHeader

    /** No paired engine. */
    const val NoComputerTitle = "No computer yet"

    /** No paired engine's message. */
    const val NoComputerMessage =
        "Open Roboco on a computer to run sessions and see its accounts here."

    /** The pending row. */
    const val Loading = "Loading accounts\u2026"

    /** The offline/failed row's title. */
    const val Unavailable = "Accounts unavailable"

    /** The empty state's title. */
    const val Empty = "No agent accounts"

    /** The empty state's message. */
    fun emptyMessage(deviceName: String) = "Sign in to an agent from Roboco on $deviceName."
}

/** The row ids — the tags are [settingsTag] of these. */
object AgentAccountsRowIds {
    const val None = "agent-accounts:none"
    const val Loading = "agent-accounts:loading"
    const val Unavailable = "agent-accounts:error"
    const val Empty = "agent-accounts:empty"

    /** `agent-account:<harness>` — one row per harness. */
    fun harness(harness: String) = "agent-account:$harness"
}

/**
 * One row of the section, pure data: the [GroupedRow] inputs plus the
 * click's subject. Built only by [agentAccountsRows]; the gallery and the
 * tests build them from a fixed snapshot instead.
 */
@Immutable
sealed interface AgentAccountsRowSpec {
    val id: String

    /** A harness with a saved login: the login in use, its fullest window. */
    data class Harness(
        val harness: String,
        val title: String,
        val subtitle: String,
        val topWindow: AgentUsageWindow?,
    ) : AgentAccountsRowSpec {
        override val id: String get() = AgentAccountsRowIds.harness(harness)
    }

    /** The no-computer row (disabled). */
    data object NoComputer : AgentAccountsRowSpec {
        override val id: String get() = AgentAccountsRowIds.None
    }

    /** The pending row (disabled). */
    data object Loading : AgentAccountsRowSpec {
        override val id: String get() = AgentAccountsRowIds.Loading
    }

    /** The offline/failed row (disabled). */
    data class Unavailable(val message: String) : AgentAccountsRowSpec {
        override val id: String get() = AgentAccountsRowIds.Unavailable
    }

    /** The empty state (disabled). */
    data class Empty(val message: String) : AgentAccountsRowSpec {
        override val id: String get() = AgentAccountsRowIds.Empty
    }
}

/**
 * The section's rows, PR #578's `providerRows`: one row per harness with
 * accounts, in desktop order — the login in use as the subtitle (plus
 * ` · N accounts` when there are more, ` · Usage unavailable` when the
 * active login shows no window and is not an API key), the fullest window
 * as the trailing mini meter (a disclosure when there is none). `null`
 * hides the section entirely — the unsupported-engine rule. No device /
 * loading / failed / empty take one disabled row each. Pure.
 */
fun agentAccountsRows(
    deviceId: String?,
    deviceName: String,
    snapshot: AgentAccountsSnapshot?,
    loadMessage: String?,
    unsupported: Boolean,
    harnessLabel: (harness: String) -> String = ::harnessLabelOf,
): List<AgentAccountsRowSpec>? {
    if (unsupported) return null
    if (deviceId == null) return listOf(AgentAccountsRowSpec.NoComputer)
    if (snapshot == null) {
        return if (loadMessage != null) {
            listOf(AgentAccountsRowSpec.Unavailable(loadMessage))
        } else {
            listOf(AgentAccountsRowSpec.Loading)
        }
    }
    val groups = providerGroups(snapshot).filter { it.accounts.isNotEmpty() }
    if (groups.isEmpty()) {
        return listOf(AgentAccountsRowSpec.Empty(AgentAccountsSectionCopy.emptyMessage(deviceName)))
    }
    return groups.map { group ->
        val active = group.accounts.firstOrNull { it.active }
        val top = active?.let(::topWindowOf)
        val subtitle = buildString {
            append(active?.let(::accountTitle) ?: "Not signed in")
            if (group.accounts.size > 1) {
                append(" \u00B7 ${group.accounts.size} accounts")
            }
            if (active != null && top == null && !active.apiKey) {
                append(" \u00B7 Usage unavailable")
            }
        }
        AgentAccountsRowSpec.Harness(
            harness = group.harness,
            title = harnessLabel(group.harness),
            subtitle = subtitle,
            topWindow = top,
        )
    }
}

/**
 * The section's behaviour: the rows as a pure function of the tracked
 * device and the loaded snapshot, the paint-at-once load
 * (cache → plain → forced) and the row click's subject.
 */
@Stable
class AgentAccountsSectionState internal constructor(
    private val core: AccountsCore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val nowMs: () -> Long,
    private val onOpen: (deviceId: String) -> Unit,
    /** The section's computer (the primary device), or `null`. */
    private val device: () -> DeviceView?,
) {
    /** The loaded snapshot (the core's cache paints at once). */
    var snapshot: AgentAccountsSnapshot? by mutableStateOf(null)
        private set

    /** The load's failure copy (an offline engine's clear state). */
    var loadMessage: String? by mutableStateOf(null)
        private set

    /** The unsupported-engine rule: the section hides, never asks again. */
    var unsupported: Boolean by mutableStateOf(false)
        private set

    /** The newest probe's wording, for the section's footer. */
    val updatedText: String?
        get() = snapshot?.let { sh.roboco.android.accounts.updatedText(it, nowMs()) }

    /** The section's device id (the click's subject). */
    internal val deviceId: String? get() = device()?.id

    /** The section's device's display name (never its id). */
    internal val deviceName: String get() = device()?.name ?: "your computer"

    /** The rows for the tracked device; `null` hides the section. */
    val rows: List<AgentAccountsRowSpec>? by derivedStateOf {
        agentAccountsRows(
            deviceId = device()?.id,
            deviceName = device()?.name ?: "your computer",
            snapshot = snapshot,
            loadMessage = loadMessage,
            unsupported = unsupported,
        )
    }

    /** The row click: open the Accounts & Usage page on this device. */
    internal fun openPage() {
        device()?.id?.let(onOpen)
    }

    /**
     * Points the section at its device (the screen re-runs this whenever
     * the primary device changes): the core's cached snapshot paints at
     * once (one io hop — never an FFI call on main), then the plain list,
     * then the forced probe. An unsupported engine never asks again.
     */
    internal fun track(deviceId: String?) {
        if (unsupported) return
        if (deviceId == null) {
            snapshot = null
            loadMessage = null
            tracked = null
            return
        }
        if (tracked == deviceId) return
        tracked = deviceId
        loadMessage = null
        scope.launch {
            try {
                val cached = withContext(io) { core.cached(deviceId) }
                if (tracked == deviceId) {
                    snapshot = cached
                }
                val plain = withContext(io) { core.list(deviceId, false) }
                if (tracked == deviceId) {
                    snapshot = plain
                }
                val forced = withContext(io) { core.list(deviceId, true) }
                if (tracked == deviceId) {
                    snapshot = forced
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (isUnsupportedEngine(t)) {
                    unsupported = true
                    snapshot = null
                } else if (snapshot == null) {
                    loadMessage = describeAccountsError(t, device()?.name ?: "your computer")
                }
            }
        }
    }

    private var tracked: String? = null
}

/**
 * Remembers the Agent Accounts section state. Call at **screen** level
 * (Settings calls this once, before the Devices section), never inside a
 * `LazyColumn` item. The loads run on [scope] (the screen's
 * `rememberCoroutineScope()`); every FFI hop is inside
 * `withContext(io)`.
 *
 * [onOpen] defaults to pushing [AgentAccountsRoute] on the enclosing
 * stack; pass an explicit lambda (tests, the tablet dialog) to bypass the
 * local read. Without a core (outside the app root) the section shows the
 * no-computer row and loads nothing.
 */
@Composable
fun rememberAgentAccountsSectionState(
    workspace: kotlinx.coroutines.flow.StateFlow<WorkspaceSnapshot?>? = null,
    core: AccountsCore? = null,
    scope: CoroutineScope? = null,
    io: CoroutineDispatcher = Dispatchers.IO,
    nowMs: () -> Long = System::currentTimeMillis,
    onOpen: ((deviceId: String) -> Unit)? = null,
): AgentAccountsSectionState {
    val app = LocalContext.current.applicationContext as? RobocoApplication
    val snapshotState = (workspace ?: app?.workspaceFeed?.snapshot)?.collectAsState()
        ?: remember { androidx.compose.runtime.mutableStateOf<WorkspaceSnapshot?>(null) }
    val effectiveCore = core ?: remember(app) {
        app?.let { CoreAccountsCore(client = { it.clientOrNull() }) }
    }
    val effectiveScope = scope ?: rememberCoroutineScope()
    val open = onOpen ?: defaultOpenAccounts()
    val devices = remember(snapshotState) {
        derivedStateOf {
            (snapshotState.value?.devices ?: emptyList()).filter { it.isExecutionHost }
        }
    }
    val state = remember(effectiveCore, effectiveScope, io, nowMs, open, devices) {
        AgentAccountsSectionState(
            core = effectiveCore ?: NoAccountsCore,
            scope = effectiveScope,
            io = io,
            nowMs = nowMs,
            onOpen = open,
            device = { primaryDevice(devices.value) },
        )
    }
    // Track the primary device: paint, load plain, then force.
    val deviceId by remember(state) { derivedStateOf { primaryDevice(devices.value)?.id } }
    LaunchedEffect(deviceId, state) {
        state.track(deviceId)
    }
    return state
}

/** The no-core fallback: loads fail as unavailable, nothing is cached. */
private object NoAccountsCore : AccountsCore {
    override suspend fun list(deviceId: String, forceUsage: Boolean): AgentAccountsSnapshot =
        throw uniffi.roboco_core.CoreException.HostUnavailable("No core")

    override suspend fun activate(
        deviceId: String,
        harness: String,
        accountId: String,
    ): AgentAccountsSnapshot = throw uniffi.roboco_core.CoreException.HostUnavailable("No core")

    override suspend fun forget(
        deviceId: String,
        harness: String,
        accountId: String,
    ): AgentAccountsSnapshot = throw uniffi.roboco_core.CoreException.HostUnavailable("No core")

    override fun cached(deviceId: String): AgentAccountsSnapshot? = null
}

/**
 * The default row handler: push [AgentAccountsRoute] (animated) on the
 * enclosing [sh.roboco.android.shell.NavStack], with the same
 * mid-transition guard as the Devices section's.
 */
@Composable
private fun defaultOpenAccounts(): (String) -> Unit {
    val stack = LocalNavStack.current
    val entry = LocalNavEntry.current
    return remember(stack, entry) {
        { deviceId: String ->
            if (stack.top.id == entry.id) {
                stack.push(AgentAccountsRoute(deviceId = deviceId), animated = true)
            }
        }
    }
}

/**
 * One row of the section: ticket 70's [GroupedRow]. A harness row shows
 * the mini meter of its fullest window (no chevron, PR #578's accessory
 * split) or a plain disclosure; the message rows are not clickable.
 */
@Composable
fun AgentAccountsRow(
    spec: AgentAccountsRowSpec,
    position: GroupedPosition,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (spec) {
        is AgentAccountsRowSpec.Harness -> GroupedRow(
            title = spec.title,
            position = position,
            modifier = modifier,
            subtitle = spec.subtitle,
            accessories = if (spec.topWindow != null) {
                listOf(GroupedAccessory.Custom { MiniUsageView(spec.topWindow) })
            } else {
                listOf(GroupedAccessory.Disclosure)
            },
            testTag = settingsTag(spec.id),
            onClick = onClick,
        )
        AgentAccountsRowSpec.NoComputer -> GroupedRow(
            title = AgentAccountsSectionCopy.NoComputerTitle,
            position = position,
            modifier = modifier,
            subtitle = AgentAccountsSectionCopy.NoComputerMessage,
            icon = RobocoIcons.Desktopcomputer,
            accessories = emptyList(),
            testTag = settingsTag(spec.id),
        )
        AgentAccountsRowSpec.Loading -> GroupedRow(
            title = AgentAccountsSectionCopy.Loading,
            position = position,
            modifier = modifier,
            icon = RobocoIcons.Gauge,
            accessories = emptyList(),
            testTag = settingsTag(spec.id),
        )
        is AgentAccountsRowSpec.Unavailable -> GroupedRow(
            title = AgentAccountsSectionCopy.Unavailable,
            position = position,
            modifier = modifier,
            subtitle = spec.message,
            icon = RobocoIcons.ExclamationmarkTriangle,
            accessories = emptyList(),
            testTag = settingsTag(spec.id),
        )
        is AgentAccountsRowSpec.Empty -> GroupedRow(
            title = AgentAccountsSectionCopy.Empty,
            position = position,
            modifier = modifier,
            subtitle = spec.message,
            accessories = emptyList(),
            testTag = settingsTag(spec.id),
        )
    }
}

/**
 * Emits the section into the Settings scaffold's content (or any
 * `LazyColumn`): the "Agent Accounts" header and the rows, one lazy item
 * per row, keyed `grouped:agent-accounts:row:<id>`. A `null` rows list
 * (the unsupported engine) emits nothing at all — the section hides. The
 * footer carries the plan-usage line plus the probe's freshness.
 */
fun LazyListScope.agentAccountsSection(state: AgentAccountsSectionState) {
    val rows = state.rows ?: return
    groupedSection(
        key = "agent-accounts",
        header = AgentAccountsSectionCopy.Header,
        items = rows,
        itemKey = { it.id },
        footer = sectionFooter(state, rows),
    ) { row, position ->
        AgentAccountsRow(
            row,
            position,
            onClick = { state.openPage() },
        )
    }
}

/** The section's footer: the plan-usage line plus the probe's freshness. */
private fun sectionFooter(
    state: AgentAccountsSectionState,
    rows: List<AgentAccountsRowSpec>,
): String? {
    val snapshot = state.snapshot
    if (snapshot == null || snapshot.accounts.isEmpty()) return null
    if (rows.none { it is AgentAccountsRowSpec.Harness }) return null
    val parts = buildList {
        add(AccountsCopy.planUsageOn(state.deviceName))
        state.updatedText?.let { add("$it.") }
    }
    return parts.joinToString(" ")
}
