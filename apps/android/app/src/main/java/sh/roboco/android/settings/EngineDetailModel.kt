package sh.roboco.android.settings

import androidx.compose.runtime.Immutable
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.EngineView
import uniffi.roboco_core.ParkReason
import sh.roboco.android.pairing.tailcatAddressLabel

/**
 * The Engine detail page's pure model (android-ui-parity #140): the copy
 * table, the timings, the footer/row model of
 * [EngineDetailScreen][sh.roboco.android.settings.EngineDetailScreen] and
 * the pure functions behind its value cells — the port of the web's
 * `devices.ts` helpers (`formatLastSeen`, `platformLabel`, `shortId`) plus
 * the state→label mapping shared with ticket 102's Devices rows.
 *
 * No Compose here beyond the `@Immutable` markers: everything is plain
 * data over [EngineView], so the model test runs the tables directly.
 */
// ---------------------------------------------------------------------------
// Copy (verbatim from the ticket's table; the ellipses are U+2026)
// ---------------------------------------------------------------------------

/**
 * Every string the page shows. Sources: the iOS Settings cell idiom for
 * the row titles, the web's `settings-devices.tsx` for the Rename dialog
 * and the id chip, the desktop `devices.rs` for the forget failure and
 * the copied hold, `settings-engine.ts` for the status labels.
 */
object EngineDetailCopy {
    /** The status card's title-only rows, in display order. */
    const val Status = "Status"
    const val Address = "Address"
    const val Version = "Version"
    const val Platform = "Platform"
    const val LastConnected = "Last connected"
    const val DeviceId = "Device ID"
    /** ADR 0011 (ticket 04): the room an engine was joined through. */
    const val Room = "Room"

    /**
     * The `rooms-v1` capability string (the engine's wire constant,
     * `crates/proto/src/workspace.rs`): the Room row shows only for
     * engines that advertise it — capability hiding, not an error toast.
     */
    const val ROOMS_V1 = "rooms-v1"

    /** The web's id-chip copy (`settings-devices.tsx:196`). */
    const val Copied = "Copied"

    /** `MoreViewController.swift:205`'s "Choose Wallpaper…" idiom for rows that open something. */
    const val Rename = "Rename\u2026"
    const val PairAgain = "Pair again"

    /** The destructive row title, and the confirm sheet's destructive action. */
    const val Forget = "Forget"

    /** The confirm sheet's title (decision SHELL-D1, the "Sign out?" idiom). */
    fun forgetTitle(name: String) = "Forget $name?"

    /** `settings-devices.tsx:511`: the Rename dialog. */
    const val RenameTitle = "Rename device"
    const val RenamePlaceholder = "Device name"
    const val Cancel = "Cancel"
    const val RenameButton = "Rename"

    /** `notice-chip.ts:32` and ticket 42's toast for `HostUnavailable`. */
    const val NotConnected = "Engine not connected"
    const val RenameFailedPrefix = "Rename failed: "

    /** Desktop `devices.rs:172`. */
    const val ForgetFailed = "Could not forget engine"

    /** The Device ID row's accessibility action label. */
    const val CopyIdAction = "Copy device ID"
}

/**
 * The page's timings, all from the sources: the web's 1 500 ms copied
 * reset (`settings-devices.tsx:205`, desktop `devices.rs:251`), the
 * 3 s revoke cap (android-app#18) and the 15 s relative-time re-render
 * (`useNow(15_000)`, `settings-devices.tsx:76`).
 */
object EngineDetailTimings {
    const val CopiedHoldMs = 1_500L
    const val RevokeTimeoutMs = 3_000L
    const val NowTickMs = 15_000L
}

// ---------------------------------------------------------------------------
// The view model
// ---------------------------------------------------------------------------

/** Which of the two footer styles the status card shows. */
enum class FooterTone {
    /** The connected "newer engine" notice — `secondary`. */
    Notice,

    /** A not-connected `lastError` — `tertiary`. */
    Error,
}

/**
 * The status card's optional footer (`VoiceViewController.swift:95-99`'s
 * `groupedFooter` metrics): the engine's newer-version notice, or its
 * last error when it is not connected (never both — first match wins).
 */
@Immutable
data class EngineFooter(val text: String, val tone: FooterTone)

/**
 * The page as pure data: everything [EngineDetailCards] draws, derived
 * from one [EngineView] snapshot and the clock. `null` fields mean the
 * row is hidden (the Status card shows each row only when its rule
 * holds); the screen re-derives this on every engines-flow emission and
 * every 15 s tick, with no debounce and no grace.
 */
@Immutable
data class EngineDetailUi(
    val engineId: String,
    /** `name.trim().ifEmpty { host }` — the large title's text. */
    val title: String,
    /** [engineStatusValue] — the Status row's value. */
    val status: String,
    /** `state is Connected` — the dot's colour and the rename gate. */
    val connected: Boolean,
    /** `baseUrl`, verbatim (the Address row's mono value). */
    val address: String,
    /** `"v" + version.trim()`, or null when the version is blank. */
    val version: String?,
    /** [platformLabel] of the trimmed platform, or null when blank. */
    val platform: String?,
    /** [formatLastConnected], or null (connected, or never connected). */
    val lastConnected: String?,
    /** The **raw** device id, trimmed; null when blank. Copied verbatim. */
    val deviceId: String?,
    val footer: EngineFooter?,
    /** Rename is allowed only while connected. */
    val canRename: Boolean,
    /** "Pair again" shows only for the re-pairable park reasons. */
    val showPairAgain: Boolean,
    /**
     * The room's label when this engine was joined through a room AND the
     * engine advertises `rooms-v1` — the row hides otherwise (an old
     * engine, or a room-less engine: null means the row is absent).
     */
    val room: String?,
)

// ---------------------------------------------------------------------------
// The pure functions
// ---------------------------------------------------------------------------

/**
 * `formatLastSeen(at, now)` (`devices.ts:98-113`), on epoch ms:
 * `s = floorDiv(now − at, 1000)`; `s < 60` → "just now" (a negative `s` —
 * clock skew — lands here too), then `Nm ago` / `Nh ago` / `Nd ago` with
 * integer division.
 */
fun formatLastConnected(atMs: Long, nowMs: Long): String {
    val seconds = Math.floorDiv(nowMs - atMs, 1000L)
    return when {
        seconds < 60L -> "just now"
        seconds < 3_600L -> "${seconds / 60L}m ago"
        seconds < 86_400L -> "${seconds / 3_600L}h ago"
        else -> "${seconds / 86_400L}d ago"
    }
}

/**
 * `platformLabel` (`devices.ts:125-143`): the six known spellings, and
 * **anything else verbatim** — a case-sensitive switch, so `freebsd`
 * stays `freebsd` and `MacOS` stays `MacOS`.
 */
fun platformLabel(platform: String): String = when (platform) {
    "macos", "darwin" -> "macOS"
    "linux" -> "Linux"
    "windows" -> "Windows"
    "web" -> "Web"
    "ios" -> "iOS"
    "android" -> "Android"
    else -> platform
}

/**
 * `shortId` (`devices.ts:146-151`, desktop `devices.rs:348-355`): ids of
 * 12 characters or fewer show as-is; longer ones take 8 + `…` (U+2026) + 4.
 */
fun shortId(id: String): String = if (id.length > 12) "${id.take(8)}\u2026${id.takeLast(4)}" else id

/**
 * The Status row's value. `Parked(INCOMPATIBLE)` reads the short
 * "Update required" — the sentence cannot fit a one-line value, so it
 * moves to the footer; every other state uses ticket 102's
 * [engineConnectionLabel] (the web's `settings-engine.ts:71-87`
 * vocabulary), which never shows `lastError` outside `INCOMPATIBLE`.
 */
fun engineStatusValue(state: EngineState, lastError: String?): String =
    if (state is EngineState.Parked && state.reason == ParkReason.INCOMPATIBLE) {
        DevicesCopy.UpdateRequired
    } else {
        engineConnectionLabel(state, lastError)
    }

/**
 * The status card's footer, first match wins: the last error while not
 * connected (Error, `tertiary` — actionable), else the notice (Notice,
 * `secondary`), else none. Both inputs are trimmed; blank counts as
 * absent. Both at once has no source, so it is not supported.
 */
fun engineFooter(state: EngineState, notice: String?, lastError: String?): EngineFooter? {
    val error = lastError?.trim()
    if (state !is EngineState.Connected && !error.isNullOrEmpty()) {
        return EngineFooter(error, FooterTone.Error)
    }
    val text = notice?.trim()
    if (!text.isNullOrEmpty()) return EngineFooter(text, FooterTone.Notice)
    return null
}

/**
 * "Pair again" shows for `Parked(REVOKED)` and
 * `Parked(IDENTITY_CHANGED)` only: re-pairing cannot fix a version
 * mismatch (`INCOMPATIBLE` leaves only via reconnect/restart, ticket 73's
 * Comments). The inner `when` is exhaustive with no `else`, so a new
 * [ParkReason] is a compile error, not a silently hidden row.
 */
fun isRepairable(state: EngineState): Boolean = when (state) {
    is EngineState.Parked -> when (state.reason) {
        ParkReason.REVOKED -> true
        ParkReason.IDENTITY_CHANGED -> true
        ParkReason.INCOMPATIBLE -> false
    }
    is EngineState.Connecting -> false
    is EngineState.Connected -> false
    is EngineState.Reconnecting -> false
    is EngineState.Offline -> false
}

/**
 * The page model of one engine snapshot at [nowMs] ms: every
 * [EngineDetailUi] field per the Status card table — the value rows each
 * shown only when its rule holds, `lastConnected` only while not
 * connected, the raw trimmed device id, and the footer.
 */
fun engineDetailUi(view: EngineView, nowMs: Long): EngineDetailUi {
    val connected = view.state is EngineState.Connected
    val version = view.version?.trim()?.takeIf { it.isNotEmpty() }
    val platform = view.platform?.trim()?.takeIf { it.isNotEmpty() }
    return EngineDetailUi(
        engineId = view.engineId,
        title = view.name.trim().ifEmpty { view.host },
        // android-next#02: say which route is being retried.
        status = if (view.tailcat && view.state is EngineState.Reconnecting) {
            "Reconnecting over Tailcat…"
        } else {
            engineStatusValue(view.state, view.lastError)
        },
        connected = connected,
        // android-next#02: a Tailcat engine shows its fingerprint, never
        // the secret address (its baseUrl is already `tailcat:<8 hex>`).
        address = tailcatAddressLabel(view.baseUrl) ?: view.baseUrl,
        version = version?.let { "v$it" },
        platform = platform?.let { platformLabel(it) },
        lastConnected = if (connected) null else view.lastConnectedMs?.let { formatLastConnected(it, nowMs) },
        deviceId = view.deviceId?.trim()?.takeIf { it.isNotEmpty() },
        footer = engineFooter(view.state, view.notice, view.lastError),
        canRename = connected,
        showPairAgain = isRepairable(view.state),
        // ADR 0011 (ticket 04): the Room row is capability-gated — hidden
        // (never an error) when the engine lacks rooms-v1; while offline
        // the capabilities are unknown, so the row hides until it
        // reconnects (the degrade-gracefully rule).
        room = view.room?.label?.takeIf { it.isNotBlank() }
            ?.takeIf { view.capabilities.contains(EngineDetailCopy.ROOMS_V1) },
    )
}

// ---------------------------------------------------------------------------
// The status card's rows
// ---------------------------------------------------------------------------

/**
 * One row of the status card, in the order the ticket's table fixes:
 * Status, Address, then Version / Platform / Last connected / Device ID
 * each only when its value exists. The [tag]s are the row test tags.
 */
internal sealed interface EngineStatusRow {
    val tag: String

    /** "Status" — always shown, `[Dot, Value]`. */
    data object Status : EngineStatusRow {
        override val tag: String get() = "engine-detail-status"
    }

    /** "Address" — always shown, the mono `FadingText`. */
    data object Address : EngineStatusRow {
        override val tag: String get() = "engine-detail-address"
    }

    /** "Version" — `v…`. */
    data class Version(val value: String) : EngineStatusRow {
        override val tag: String get() = "engine-detail-version"
    }

    /** "Platform" — [platformLabel]. */
    data class Platform(val value: String) : EngineStatusRow {
        override val tag: String get() = "engine-detail-platform"
    }

    /** "Last connected" — [formatLastConnected]. */
    data class LastConnected(val value: String) : EngineStatusRow {
        override val tag: String get() = "engine-detail-last-connected"
    }

    /** "Device ID" — the value that copies. */
    data class DeviceId(val id: String) : EngineStatusRow {
        override val tag: String get() = "engine-detail-device-id"
    }

    /** "Room" — the room's label; only when the engine advertises `rooms-v1`. */
    data class Room(val value: String) : EngineStatusRow {
        override val tag: String get() = "engine-detail-room"
    }
}

/** The status card's shown rows, in order, from the model's non-null fields. */
internal fun statusRows(ui: EngineDetailUi): List<EngineStatusRow> = buildList {
    add(EngineStatusRow.Status)
    add(EngineStatusRow.Address)
    ui.version?.let { add(EngineStatusRow.Version(it)) }
    ui.platform?.let { add(EngineStatusRow.Platform(it)) }
    ui.room?.let { add(EngineStatusRow.Room(it)) }
    ui.lastConnected?.let { add(EngineStatusRow.LastConnected(it)) }
    ui.deviceId?.let { add(EngineStatusRow.DeviceId(it)) }
}
