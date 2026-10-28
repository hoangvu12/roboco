package sh.roboco.android.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.EngineView
import uniffi.roboco_core.ParkReason
import uniffi.roboco_core.SendState

/** The lite copy of the FFI `SendState` (android-ui-parity #73): no UniFFI types in the pure derivation. */
enum class SendLite { Sending, Queued, Failed }

/** The lite copy of the FFI `PendingSend`: creation order, `sentAtMs` (the phone's clock) and `state`. */
data class PendingLite(val sentAtMs: Long, val state: SendLite)

/**
 * The owning engine's connection state as the banner derivation needs it
 * (a lite copy of the FFI `EngineView.state` plus the engine id, with one
 * extra state the FFI cannot express: the engine record is gone).
 */
sealed interface EngineLink {
    data object Connected : EngineLink
    data object Connecting : EngineLink
    data object Reconnecting : EngineLink

    /** `EngineState.Offline` — the OS reports no network. */
    data object NoNetwork : EngineLink

    /** `Parked(reason, engineId)`: revoked / identity changed / incompatible. */
    data class Parked(val reason: SessionChrome.ParkedReason, val engineId: String) : EngineLink

    /** `ChromeInput.engine == null` — the engine is forgotten or unknown. */
    data object Missing : EngineLink
}

/** Everything [deriveBanner] reads (a lite copy of a [ChromeInput] plus the filler's sticky failures). */
data class BannerInputs(
    /** Ticket 78's sticky send failure; set until the next successful send. */
    val sendFailure: String? = null,
    /** Ticket 89's sticky setting failure (upstream PR #664); set until the next successful change. */
    val settingFailure: String? = null,
    /** `ComposerState.sendState` (the oldest pending send's headline state). */
    val sendState: SendLite? = null,
    val link: EngineLink = EngineLink.Connected,
    /** `ComposerState.pendingSends`, creation order. */
    val pendingSends: List<PendingLite> = emptyList(),
    /** `ComposerState.live.workingSinceMs` (the engine's clock, not the phone's). */
    val workingSinceMs: Long? = null,
)

/**
 * The send→turn bridge (android-ui-parity #73, the port of the web's
 * `sendingBridge`, `web/packages/app/src/lib/transcript.ts:1279-1292`):
 * the pill shows `Sending to host…` only while a send of **this device** is in
 * flight and the engine has not yet reported the turn it started.
 *
 * `turnStarted <= sendStarted` counts as bridged (the turn at or before
 * the send is the one the send started); a turn that started *after* the
 * send means the engine adopted it and the tail's Working row takes over.
 * The two clocks (phone/engine) are compared as-is, like the web — do not
 * "fix" skew.
 */
fun sendingBridge(sendStarted: Long?, turnStarted: Long?): Boolean =
    sendStarted != null && (turnStarted == null || turnStarted <= sendStarted)

/**
 * Derives the one chrome banner per refresh (the port of
 * `ios:Session/CoreSessionSource.swift:75-87`, adapted per the ticket's
 * precedence table — first match wins):
 *
 * 1. `Failed(sendFailure)` — the last send threw, sticky until the next successful send;
 * 2. `NotDelivered` — `sendState == FAILED` **and** the engine is `Connected` (a degraded
 *    engine never shows a false "Not delivered", the web's rule);
 * 3. `Failed(settingFailure)` — the last `setSessionConfig` threw (upstream PR #664);
 * 4. `EngineParked(reason, engineId)` — `Parked(REVOKED)` / `Parked(IDENTITY_CHANGED)`;
 * 5. `EngineOff` — the engine record is missing, or `Parked(INCOMPATIBLE)` (a version
 *    mismatch is not repairable by re-pairing);
 * 6. `Reconnecting` — the engine is `Connecting`, `Reconnecting` or `Offline`;
 * 7. `Sending` — (the engine is `Connected` here by construction) the send→turn bridge
 *    over the **first** `SENDING` pending send's `sentAtMs`;
 * 8. `None`.
 *
 * Pure. Never returns `Editing` (a display overlay, [shownBanner]) and
 * never `Working` (the transcript tail owns it,
 * `CoreSessionSource.swift:89`). iOS's `.queued` / `.offline` relay-outbox
 * banners are dropped: Roboco refuses writes to a non-connected engine.
 */
fun deriveBanner(i: BannerInputs): SessionChrome.Banner {
    // 1 — the sticky send failure wins over everything.
    i.sendFailure?.let { return SessionChrome.Banner.Failed(it) }
    // 2 — only while the engine is connected; a retry would be skipped otherwise.
    if (i.sendState == SendLite.Failed && i.link == EngineLink.Connected) {
        return SessionChrome.Banner.NotDelivered
    }
    // 3 — the sticky setting failure (upstream PR #664's precedence: after NotDelivered).
    i.settingFailure?.let { return SessionChrome.Banner.Failed(it) }
    // 4–6 — the engine link decides.
    when (val l = i.link) {
        // 4 — parked for a repairable reason: the tap re-pairs this engine.
        is EngineLink.Parked -> when (l.reason) {
            SessionChrome.ParkedReason.Revoked,
            SessionChrome.ParkedReason.IdentityChanged,
            -> return SessionChrome.Banner.EngineParked(l.reason, l.engineId)

            // 5 — a version mismatch is not repairable by re-pairing.
            SessionChrome.ParkedReason.Incompatible -> return SessionChrome.Banner.EngineOff
        }
        // 5 — the engine record is missing (forgotten / unknown).
        EngineLink.Missing -> return SessionChrome.Banner.EngineOff
        // 6 — known engine, no live connection: reads stay possible, writes are refused.
        EngineLink.Connecting,
        EngineLink.Reconnecting,
        EngineLink.NoNetwork,
        -> return SessionChrome.Banner.Reconnecting
        // 7 — connected: fall through to the send→turn bridge.
        EngineLink.Connected -> Unit
    }
    // 7 — the first SENDING pending send in creation order.
    val sendStart = i.pendingSends.firstOrNull { it.state == SendLite.Sending }?.sentAtMs
    return if (sendingBridge(sendStart, i.workingSinceMs)) SessionChrome.Banner.Sending else SessionChrome.Banner.None
}

/**
 * The banner the pill **displays** — iOS `render` (`SessionViewController.swift:501`):
 * `self.pill.banner = editing ? .editing : c.banner`. [editing] is ticket
 * 83's queue-edit lease flag (`editingId != null`); the chrome banner
 * itself never holds `Editing`, which is what lets ticket 74 treat
 * editing toggles as non-structural (they snap, like iOS `:432-433`).
 */
fun shownBanner(chromeBanner: SessionChrome.Banner, editing: Boolean): SessionChrome.Banner =
    if (editing) SessionChrome.Banner.Editing else chromeBanner

/** What a tap on the shown banner does (pure; [StatusBanner.onTap] dispatches it). */
sealed interface BannerTap {
    /** Nothing runs (the tap is still consumed at the hit-test level). */
    data object None : BannerTap

    /** Cancel the queued-message edit (ticket 83's `cancelEdit()`, iOS `endEdit(commit: nil)`). */
    data object CancelEdit : BannerTap

    /** `retryDelivery()` on the IO scope, errors logged and swallowed (iOS `:197-199`). */
    data object RetryDelivery : BannerTap

    /** Open ticket 72's Pair page in re-pair mode with `replaceEngineId = engineId`. */
    data class Repair(val engineId: String) : BannerTap
}

/**
 * The tap dispatch table: `Editing` cancels the edit; `NotDelivered`
 * retries — but only when [engineConnected] (the engine state at the last
 * fill; the web skips the retry when the engine is not connected,
 * `chat-page.tsx:1251-1254`); `EngineParked` re-pairs. Every other banner
 * consumes the tap without doing anything, like the iOS `UIControl`.
 */
fun bannerTapAction(banner: SessionChrome.Banner, engineConnected: Boolean): BannerTap =
    when (banner) {
        SessionChrome.Banner.Editing -> BannerTap.CancelEdit
        SessionChrome.Banner.NotDelivered -> if (engineConnected) BannerTap.RetryDelivery else BannerTap.None
        is SessionChrome.Banner.EngineParked -> BannerTap.Repair(banner.engineId)
        is SessionChrome.Banner.Failed,
        SessionChrome.Banner.EngineOff,
        SessionChrome.Banner.Reconnecting,
        SessionChrome.Banner.Sending,
        is SessionChrome.Banner.Working,
        SessionChrome.Banner.None,
        -> BannerTap.None
    }

/**
 * The `<error>` text of a failed send / setting change — iOS prints Swift
 * reflection (`HostUnavailable(message: "…")`); Roboco prints the
 * exception's `reason` (the Rust `#[error]` text).
 *
 * For a `CoreException` an exhaustive `when` over the generated variants
 * returns each `reason` (`HostUnavailable` → `Engine is offline;
 * reconnecting`, so the pill reads `Couldn't send: Engine is offline;
 * reconnecting`). **Never read `CoreException.message`**: depending on the
 * pinned UniFFI version the generated override returns `reason` or
 * `"reason=<value>"`. `Closed` has no payload and maps to the Rust
 * `#[error]` text (`mob:client_ffi/types.rs:37-38`). Any other
 * `Throwable` yields its message, or its class name when blank/null.
 */
fun errorText(t: Throwable): String = when (t) {
    is CoreException.NotFound -> t.reason
    is CoreException.InvalidArgument -> t.reason
    is CoreException.HostUnavailable -> t.reason
    is CoreException.Unsupported -> t.reason
    is CoreException.HostException -> t.reason
    is CoreException.Network -> t.reason
    is CoreException.Auth -> t.reason
    is CoreException.Storage -> t.reason
    is CoreException.NotImplemented -> t.reason
    is CoreException.Closed -> "client is shut down"
    is CoreException.Internal -> t.reason
    else -> t.message?.takeIf { it.isNotBlank() } ?: (t::class.simpleName ?: "error")
}

/**
 * The banner `ChromeFiller` (android-ui-parity #73): owns the sticky
 * `sendFailure` / `settingFailure` state and the tap dispatch that the
 * pill drives. Registered once per route (see the wiring contract below).
 *
 * **Threading:** [fill] runs on the controller's IO lane (it touches no
 * FFI, only the lite copy); the `note*` setters and [onTap] run on main.
 * Every shared field is `@Volatile`.
 *
 * **Wiring contract for the sibling tickets** (this ticket implements only
 * this side):
 * - **Registration (76):** `chromeFillers.register(ChromeFillerRegistry.BANNER) { core ->
 *   StatusBanner(retryDelivery = { (core as CoreSessionCore).handle.retryDelivery() }) }`
 *   — never a second banner filler; 78 must not register its fallback
 *   `SendFailureFiller` (78 step 3's "feed 73's filler" option).
 * - **From the screen (75/76):** `controller.filler<StatusBanner>()` (declared by ticket 51).
 * - **75/74 placement:** `StatusPillRow(shownBanner(chrome.banner, editing),
 *   onTap = banner::onTap, tapEnabled = { b -> bannerTapAction(b, true) != BannerTap.None })`
 *   where `editing` is ticket 83's flag; set
 *   `banner.openPairPage = { id -> LocalPairPage.current?.openPairPage(prefill = null, replaceEngineId = id) }`
 *   (ticket 72's `PairPageLauncher`; capture the launcher in composition) once when the
 *   screen is composed.
 * - **78 (send):** after `handle.send(...)` returns → `noteSendSucceeded()`; when it throws
 *   (not `CancellationException`) → `noteSendFailed(t)` — through the [SendReporter]
 *   interface the binding holds.
 * - **89 (chip menus):** `noteSettingSucceeded()` / `noteSettingFailed(t)` around
 *   `setSessionConfig` (89's `onSettingResult`).
 * - **83 (queue edit):** `banner.onEditingTap = { queueEdit.cancelEdit() }` (iOS
 *   `endEdit(commit: nil)`).
 */
class StatusBanner(
    /** The chat's `retryDelivery` (runs on [io], never main — upstream #609's trap). */
    private val retryDelivery: suspend () -> Unit,
    io: CoroutineDispatcher = Dispatchers.IO,
    private val logW: (String, Throwable) -> Unit = { message, error ->
        android.util.Log.w("RobocoSession", message, error)
    },
) : ChromeFiller, SendReporter {
    /** Ticket 83: cancels the queued-message edit. Main thread. */
    @Volatile
    var onEditingTap: (() -> Unit)? = null

    /** Ticket 72's Pair page in re-pair mode. Main thread. */
    @Volatile
    var openPairPage: ((replaceEngineId: String) -> Unit)? = null

    /** The last send's failure text, or null. Sticky until the next successful send. */
    @Volatile
    private var sendFailure: String? = null

    /** The last `setSessionConfig`'s failure text, or null. Sticky until the next successful one. */
    @Volatile
    private var settingFailure: String? = null

    /** The engine state of the last [fill]; gates the retry tap. */
    @Volatile
    private var lastConnected = false

    /** The invalidate handle [bind] stores; the controller's coalescing trigger. */
    @Volatile
    private var invalidate: () -> Unit = {}

    /**
     * The retry lane. Never cancelled: a retry may finish (and invalidate)
     * after the screen closed, exactly like iOS's synchronous call
     * (`CoreSessionSource.swift:197-199`).
     */
    private val scope = CoroutineScope(SupervisorJob() + io)

    /** Ticket 78: a send returned normally — clears the sticky send failure. */
    override fun noteSendSucceeded() {
        if (sendFailure != null) {
            sendFailure = null
            invalidate()
        }
    }

    /** Ticket 78: `handle.send` threw — holds `Couldn't send: <errorText>` until the next success. */
    override fun noteSendFailed(error: Throwable) {
        replaceSendFailure("Couldn't send: " + errorText(error))
    }

    /** Ticket 89: `setSessionConfig` returned normally — clears the sticky setting failure. */
    fun noteSettingSucceeded() {
        if (settingFailure != null) {
            settingFailure = null
            invalidate()
        }
    }

    /** Ticket 89: `setSessionConfig` threw — holds `Couldn't change session settings: <errorText>`. */
    fun noteSettingFailed(error: Throwable) {
        replaceSettingFailure("Couldn't change session settings: " + errorText(error))
    }

    /**
     * Ticket 83's recovery copy into the sticky send-failure slot,
     * **verbatim** (no `Couldn't send: ` prefix).
     */
    fun noteFailureMessage(message: String) {
        replaceSendFailure(message)
    }

    /**
     * Clears the send-failure slot only if it still holds exactly
     * [message] — so a later genuine `Couldn't send: …` is never wiped.
     */
    fun clearFailureMessage(message: String) {
        if (sendFailure == message) {
            sendFailure = null
            invalidate()
        }
    }

    /** The pill's tap. Main thread; uses the engine state of the last [fill]. */
    fun onTap(banner: SessionChrome.Banner) {
        when (val action = bannerTapAction(banner, lastConnected)) {
            BannerTap.None -> Unit
            BannerTap.CancelEdit -> onEditingTap?.invoke()
            BannerTap.RetryDelivery -> scope.launch {
                try {
                    retryDelivery()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    // iOS swallows the error (`try?`); the log is all it gets.
                    logW("retryDelivery failed", t)
                }
                invalidate()
            }
            is BannerTap.Repair -> openPairPage?.invoke(action.engineId)
        }
    }

    override fun bind(invalidate: () -> Unit) {
        this.invalidate = invalidate
    }

    /**
     * Derives the banner from the lite inputs and the sticky failures; no
     * FFI (everything it needs is already in [input]). Caches the engine's
     * connected state for the retry-tap guard.
     */
    override fun fill(chrome: SessionChrome, input: ChromeInput): SessionChrome {
        val inputs = bannerInputs(input, sendFailure, settingFailure)
        lastConnected = inputs.link == EngineLink.Connected
        return chrome.copy(banner = deriveBanner(inputs))
    }

    /** Sets the sticky send failure; invalidates only when the text changed. */
    private fun replaceSendFailure(next: String?) {
        if (sendFailure != next) {
            sendFailure = next
            invalidate()
        }
    }

    /** Sets the sticky setting failure; invalidates only when the text changed. */
    private fun replaceSettingFailure(next: String?) {
        if (settingFailure != next) {
            settingFailure = next
            invalidate()
        }
    }
}

/** The FFI `SendState` → [SendLite]. */
private fun SendState.toSendLite(): SendLite = when (this) {
    SendState.SENDING -> SendLite.Sending
    SendState.QUEUED -> SendLite.Queued
    SendState.FAILED -> SendLite.Failed
}

/** The FFI `EngineView?` → [EngineLink] (exhaustive over `EngineState`; `null` → [EngineLink.Missing]). */
private fun EngineView?.toEngineLink(): EngineLink {
    val engine = this ?: return EngineLink.Missing
    return when (val state = engine.state) {
        EngineState.Connected -> EngineLink.Connected
        EngineState.Connecting -> EngineLink.Connecting
        EngineState.Reconnecting -> EngineLink.Reconnecting
        EngineState.Offline -> EngineLink.NoNetwork
        is EngineState.Parked -> EngineLink.Parked(
            reason = when (state.reason) {
                ParkReason.REVOKED -> SessionChrome.ParkedReason.Revoked
                ParkReason.IDENTITY_CHANGED -> SessionChrome.ParkedReason.IdentityChanged
                ParkReason.INCOMPATIBLE -> SessionChrome.ParkedReason.Incompatible
            },
            engineId = engine.engineId,
        )
    }
}

/**
 * Maps a [ChromeInput] (uniffi records) plus the filler's sticky failures
 * into the pure [BannerInputs] (internal so the tests can drive it
 * directly). `pendingSends` keeps its creation order; the `when`s are
 * exhaustive over the FFI enums.
 */
internal fun bannerInputs(input: ChromeInput, sendFailure: String?, settingFailure: String?): BannerInputs {
    val composer = input.composer
    return BannerInputs(
        sendFailure = sendFailure,
        settingFailure = settingFailure,
        sendState = composer.sendState?.toSendLite(),
        link = input.engine.toEngineLink(),
        pendingSends = composer.pendingSends.map { PendingLite(sentAtMs = it.sentAtMs, state = it.state.toSendLite()) },
        workingSinceMs = composer.live.workingSinceMs,
    )
}
