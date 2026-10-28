package sh.roboco.android.settings

import android.util.Log
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.StateFlow
import sh.roboco.android.RobocoApplication
import sh.roboco.android.design.GroupedAccessory
import sh.roboco.android.design.GroupedPosition
import sh.roboco.android.design.GroupedRow
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.groupedSection
import sh.roboco.android.design.settingsTag
import sh.roboco.android.pairing.LocalPairPage
import sh.roboco.android.shell.LocalEnclosingSheet
import sh.roboco.android.shell.LocalNavEntry
import sh.roboco.android.shell.LocalNavStack
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.EngineView
import uniffi.roboco_core.ParkReason

/**
 * The Settings "Devices" section (android-ui-parity #102): the port of
 * `MoreViewController.swift:189-190` — one row per **paired engine** (iOS
 * lists cloud execution hosts; Roboco lists `CoreClient.engines()`), then
 * a "Pair an engine" row (Roboco ADD: iOS adds devices through the cloud
 * account, Roboco pairs per engine).
 *
 * Every visual — geometry, fonts, colours, the 8 dp dot, the chevron,
 * separators, the pressed highlight — comes from ticket 70's
 * [GroupedRow]; this file sets no dp, font or colour of its own. It adds
 * the row data, the connection-label mapping (the web's
 * `settings-engine.ts:71-87` vocabulary plus the typed `ParkReason`s)
 * and the tap routing (push the Engine detail page, open the Pair page).
 *
 * Rows follow the live engine registry flow (ticket 71's
 * [RobocoApplication.coreStartup] `engines`) with **no extra delay**: a
 * pair, forget, rename or connection change shows up on the next frame,
 * with no debounce and no grace — iOS reloads
 * `apply(s, animatingDifferences: false)` at once
 * (`MoreViewController.swift:219`), and Settings is where the user goes
 * to see the truth (the Sessions connection line's 4 s grace is ticket
 * 50's, on purpose).
 */

/** The section's copy, verbatim from the ticket's table. The ellipses are U+2026. */
object DevicesCopy {
    /** `MoreViewController.swift:189`. */
    const val Header = "Devices"

    /** `settings-engine.ts:73` (the registry entry is still pending). */
    const val Starting = "Starting\u2026"

    /** `settings-engine.ts:77`. */
    const val Connected = "Connected"

    /** `settings-engine.ts:79`. */
    const val Reconnecting = "Reconnecting\u2026"

    /** `MoreViewController.swift:190` (the phone has no network). */
    const val Offline = "Offline"

    /** `settings-engine.ts:83` (`Parked(IDENTITY_CHANGED)`). */
    const val EngineChanged = "Engine changed"

    /** `settings-engine.ts:83` (`Parked(REVOKED)`). */
    const val SessionRevoked = "Session revoked"

    /** `Parked(INCOMPATIBLE)` with no `lastError`; shared with ticket 50. */
    const val UpdateRequired = "Update required"

    /** `app-shell.tsx:1133` — the web Welcome button's copy. */
    const val PairAnEngine = "Pair an engine"
}

/**
 * The row ids: the iOS `device:` prefix is kept on engine rows
 * (`Row(id: "device:\($0.id)")`, `MoreViewController.swift:189`) so the
 * tag is `settings-device:<engineId>` — ticket 103's `settingsSectionFor`
 * matches the prefix. The tags are [settingsTag] of these.
 */
object DevicesRowIds {
    const val Pair = "pair"

    /** `device:<engineId>` — the iOS id prefix over Roboco's engine id. */
    fun engine(engineId: String) = "device:$engineId"
}

/**
 * One row of the section, pure data: the [GroupedRow] inputs plus the
 * click's subject. Built only by [devicesRows]; the gallery builds them
 * from a fixed fixture instead of `EngineView`s.
 */
@Immutable
sealed interface DevicesRowSpec {
    val id: String

    /** One paired engine: name, connection label and the dot's state. */
    data class Engine(
        val engineId: String,
        val title: String,
        val label: String,
        val connected: Boolean,
    ) : DevicesRowSpec {
        override val id: String get() = DevicesRowIds.engine(engineId)
    }

    /** The last row: "Pair an engine". */
    data object Pair : DevicesRowSpec {
        override val id: String get() = DevicesRowIds.Pair
    }
}

/**
 * The connection label of [state] — `settings-engine.ts:71-87` (the web
 * paired-engine row vocabulary) plus the typed states the web lacks:
 * [EngineState.Offline] (the OS reports no network) and the
 * [ParkReason]s behind the web's `lastError.includes("identity")` string
 * test. Every non-connected state is a plain word; only
 * [ParkReason.INCOMPATIBLE] shows `lastError` (the engine-too-old /
 * app-too-old sentence) — transport errors belong in logs and on the
 * Engine detail page (140), never in the row. The `when` is exhaustive
 * with no `else`, so a new Rust state is a compile error, not a silent
 * label.
 */
fun engineConnectionLabel(state: EngineState, lastError: String?): String = when (state) {
    is EngineState.Connecting -> DevicesCopy.Starting
    is EngineState.Connected -> DevicesCopy.Connected
    is EngineState.Reconnecting -> DevicesCopy.Reconnecting
    is EngineState.Offline -> DevicesCopy.Offline
    is EngineState.Parked -> when (state.reason) {
        ParkReason.IDENTITY_CHANGED -> DevicesCopy.EngineChanged
        ParkReason.REVOKED -> DevicesCopy.SessionRevoked
        ParkReason.INCOMPATIBLE ->
            lastError?.trim().takeUnless { it.isNullOrEmpty() } ?: DevicesCopy.UpdateRequired
    }
}

/**
 * A connected Tailcat engine's path instead of the plain "Connected":
 * "Direct · 35 ms" or "Via relay (Tokyo) · 160 ms" (fleet-redesign 16).
 * The core builds the text; it never carries an address. `null` for LAN
 * engines, before the first measurement, and in every other state.
 */
fun connectedPathLabel(engine: EngineView): String? =
    engine.linkPath?.trim()?.takeIf { it.isNotEmpty() && engine.state is EngineState.Connected }

/**
 * The section's rows, `MoreViewController.swift:189-190` adapted: one
 * [DevicesRowSpec.Engine] per engine **in `engines()` order** (pairing
 * order; never sorted — Rust already dedupes re-pairs by `engineId` and
 * pinned `deviceId`), then [DevicesRowSpec.Pair]. `null` (the core not
 * `Ready` yet) is an empty list: only the `pair` row. The title guard
 * `name.trim().ifEmpty { host }` only stops an empty title line if
 * Rust's non-blank name guarantee ever breaks.
 */
fun devicesRows(engines: List<EngineView>?): List<DevicesRowSpec> =
    engines.orEmpty().map { e ->
        DevicesRowSpec.Engine(
            engineId = e.engineId,
            title = e.name.trim().ifEmpty { e.host },
            label = connectedPathLabel(e) ?: engineConnectionLabel(e.state, e.lastError),
            connected = e.state is EngineState.Connected,
        )
    } + DevicesRowSpec.Pair

/**
 * The section's behaviour: the rows as a pure function of the collected
 * engine list ([rows] is a snapshot read, so the list re-derives on the
 * next frame with no animation), and [onRowClick] dispatches to the
 * engine detail push or the Pair page. Built only by
 * [rememberDevicesSectionState].
 */
@Stable
class DevicesSectionState internal constructor(
    private val current: State<List<EngineView>?>,
    private val onOpenEngine: (engineId: String) -> Unit,
    private val onPair: () -> Unit,
) {
    /** The rows for the current engine list — a snapshot read. */
    val rows: List<DevicesRowSpec> by derivedStateOf { devicesRows(current.value) }

    /** `Engine` → [onOpenEngine]; `Pair` → [onPair]. */
    fun onRowClick(row: DevicesRowSpec) {
        when (row) {
            is DevicesRowSpec.Engine -> onOpenEngine(row.engineId)
            DevicesRowSpec.Pair -> onPair()
        }
    }
}

/**
 * Remembers the Devices section state. Call at **screen** level (ticket
 * 103 calls this once at the top of `SettingsScreen`), never inside a
 * `LazyColumn` item.
 *
 * `collectAsState()` on the [StateFlow] starts from its current value, so
 * the first frame already shows the engines (no empty flash). Never call
 * `CoreClient.engines()` here or in composition: ticket 71's collector
 * already reads it off the main thread.
 *
 * [onOpenEngine]/[onPair] default to the production routing and are only
 * then read from the composition locals: the default engine handler
 * pushes [EngineDetailRoute] on the enclosing stack, the default pair
 * handler opens ticket 72's Pair page. Pass explicit lambdas (tests, the
 * tablet dialog) to bypass both local reads.
 */
@Composable
fun rememberDevicesSectionState(
    engines: StateFlow<List<EngineView>?> =
        (LocalContext.current.applicationContext as RobocoApplication).coreStartup.engines,
    onOpenEngine: ((engineId: String) -> Unit)? = null,
    onPair: (() -> Unit)? = null,
): DevicesSectionState {
    val current = engines.collectAsState()
    val open = onOpenEngine ?: defaultOpenEngine()
    val pair = onPair ?: defaultPair()
    return remember(current, open, pair) { DevicesSectionState(current, open, pair) }
}

/**
 * The default engine handler: push [EngineDetailRoute] (animated) on the
 * enclosing [sh.roboco.android.shell.NavStack]. The `stack.top.id ==
 * entry.id` guard ignores a second tap while a push is already under way
 * (Settings is no longer on top) — the smallest guard that matches
 * UIKit's refusal to push from a controller that is mid-transition.
 */
@Composable
private fun defaultOpenEngine(): (engineId: String) -> Unit {
    val stack = LocalNavStack.current
    val entry = LocalNavEntry.current
    return remember(stack, entry) {
        { engineId: String ->
            if (stack.top.id == entry.id) stack.push(EngineDetailRoute(engineId), animated = true)
        }
    }
}

/**
 * The default pair handler: ticket 72's [LocalPairPage] launcher, normal
 * mode (no prefill, no re-pair), presented over [LocalEnclosingSheet] when
 * the section sits inside a sheet (104: the tablet Settings dialog, whose
 * card would otherwise make the root host refuse the page). Null on
 * phones: the root host presents it. Without a launcher (before the core
 * is ready, outside the app root) the click is a logged no-op.
 */
@Composable
private fun defaultPair(): () -> Unit {
    val pairPage = LocalPairPage.current
    val enclosingSheet = LocalEnclosingSheet.current
    return remember(pairPage, enclosingSheet) {
        {
            val launcher = pairPage
            if (launcher != null) {
                launcher.openPairPage(prefill = null, replaceEngineId = null, over = enclosingSheet)
            } else {
                Log.w("Devices", "no PairPageLauncher")
            }
        }
    }
}

/**
 * One row of the section: ticket 70's [GroupedRow] with the spec's data.
 * Engine rows carry the `desktopcomputer` icon (ticket 06), the
 * connection label as the subtitle, then `[Dot, Disclosure]` — the dot
 * sits left of the chevron; the `pair` row carries the `plus` icon and
 * only the chevron. `iconTint` keeps its `secondary` default and
 * `destructive` is false. All merged-semantics/`Role.Button` behaviour
 * is ticket 70's; the dot and chevron are decorative (the label carries
 * the state in words).
 */
@Composable
fun DevicesRow(
    spec: DevicesRowSpec,
    position: GroupedPosition,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    previewPressed: Boolean = false,
) {
    when (spec) {
        is DevicesRowSpec.Engine -> GroupedRow(
            title = spec.title,
            position = position,
            modifier = modifier,
            subtitle = spec.label,
            icon = RobocoIcons.Desktopcomputer,
            accessories = listOf(GroupedAccessory.Dot(spec.connected), GroupedAccessory.Disclosure),
            testTag = settingsTag(spec.id),
            onClick = onClick,
            previewPressed = previewPressed,
        )
        DevicesRowSpec.Pair -> GroupedRow(
            title = DevicesCopy.PairAnEngine,
            position = position,
            modifier = modifier,
            icon = RobocoIcons.Plus,
            accessories = listOf(GroupedAccessory.Disclosure),
            testTag = settingsTag(spec.id),
            onClick = onClick,
            previewPressed = previewPressed,
        )
    }
}

/**
 * Emits the section into ticket 103's `LargeTitleScaffold` content (or
 * any `LazyColumn`): the "Devices" header and the rows, one lazy item
 * per row, keyed `grouped:devices:row:device:<engineId>` /
 * `grouped:devices:row:pair`, so a re-pair (same `engineId`) or a state
 * change keeps the item identity. [DevicesSectionState.rows] is read
 * inside this builder, so the items re-derive on the next frame when the
 * flow changes. **No `Modifier.animateItem()`**: iOS reloads with
 * `animatingDifferences: false` (`MoreViewController.swift:219`).
 */
fun LazyListScope.devicesSection(state: DevicesSectionState) {
    groupedSection(
        key = "devices",
        header = DevicesCopy.Header,
        items = state.rows,
        itemKey = { it.id },
    ) { row, position ->
        DevicesRow(row, position, onClick = { state.onRowClick(row) })
    }
}
