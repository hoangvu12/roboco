package sh.roboco.android.pairing

import android.content.res.Resources
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import sh.roboco.android.R
import sh.roboco.android.core.CoreStartup
import sh.roboco.android.design.LocalToaster
import sh.roboco.android.design.Toaster
import sh.roboco.android.shell.LocalSheetHost
import sh.roboco.android.shell.PageSheetScope
import sh.roboco.android.shell.SheetHostState
import sh.roboco.android.shell.SheetStyle
import uniffi.roboco_core.CoreClient
import uniffi.roboco_core.PairedEngine
import uniffi.roboco_core.PairingTarget
import uniffi.roboco_core.RoomInviteTarget
import uniffi.roboco_core.RoomJoinResult
import uniffi.roboco_core.RoomRecord

/**
 * The QR scanner seam (ticket 138's screen, `fun interface`): opens the
 * scanner and hands back the decoded plain `http(s)://…/pair#token=…`
 * URL — 138 already unwrapped `roboco://pair#url=`. `null` until 138
 * lands (the Pair page hides the Scan row then).
 */
fun interface QrScanRequest {

    /** Opens the scanner; [onResult] is invoked once with the decoded URL. */
    fun scan(onResult: (String) -> Unit)
}

/**
 * The single entry point for the Pair page (android-ui-parity #72): the
 * pairing root's capsule (34, wired by 35), Settings → Devices (102),
 * the EngineParked pill (73), Engine detail's "Pair again" (140) and
 * the deep links/shares (139) all call [openPairPage]. It owns the
 * one current [PairPageModel] — a redeem that outlives its sheet keeps
 * running in the launcher's [scope] and is shown again on reopen — and
 * the presentation through ticket 31's sheet host.
 *
 * [io] is handed to every model it creates ([PairPageModel]'s FFI lane,
 * `Dispatchers.IO` in production); tests pass their scheduler's
 * dispatcher so a launcher-built redeem is driven by `advanceUntilIdle`.
 */
@Stable
class PairPageLauncher internal constructor(
    private val sheetHost: SheetHostState,
    private val backend: PairingBackend,
    private val store: PairedEngineSink,
    private val scope: CoroutineScope,
    internal val qrScan: QrScanRequest?,
    internal val clipboard: PairClipboard,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val fleet: FleetNotices = FleetNotices.None,
) {

    /**
     * The page's model, `null` while nothing is open. Kept after a
     * dismissal while the phase is `Redeeming`, so a reopen shows that
     * same redeem ("Pairing…"); ticket 139 makes this state-backed
     * (snapshot-observable) so the pairing root's status line can
     * derive from it.
     */
    internal var current: PairPageModel? by mutableStateOf(null)
        private set

    /**
     * Ticket 139: whether the page's sheet is currently presented —
     * written by [openPairPage] and the `onDismissed` callback (31
     * fires it for animated, drag, back and instant dismissals alike).
     * Observable, so the root's status line and the inbound links both
     * read it.
     */
    var isShowing: Boolean by mutableStateOf(false)
        private set

    /**
     * Ticket 139: a redeem is running, **whether the page shows or not**
     * — `current` survives a dismissal while `Redeeming` (72), so a
     * link that arrives then is ignored instead of replacing the URL
     * being redeemed.
     */
    val isRedeeming: Boolean get() = current?.phase is PairPhase.Redeeming

    /**
     * Opens (or refills) the Pair page.
     *
     * Already presented and redeeming: the call is ignored (Log.i) —
     * the running redeem must not be torn down. Already presented and
     * idle: [refill] with [prefill]/[replaceEngineId] and `Idle`. Not
     * presented: reuse [current] only while it is still redeeming (a
     * redeem that outlived its sheet; [prefill]/[replaceEngineId] are
     * then ignored), otherwise create a fresh model; then present the
     * `Page(grabber = true)` sheet — over [over] when the caller is
     * itself inside a sheet (the tablet Settings dialog, 104's
     * `LocalEnclosingSheet`), else from the root host. A `false` return
     * (another sheet is up) is the UIKit "already presenting" no-op.
     */
    fun openPairPage(prefill: String? = null, replaceEngineId: String? = null, over: PageSheetScope? = null) {
        if (sheetHost.entries.any { it.key == PAIR_KEY }) {
            val model = current
            if (model == null) {
                Log.w(TAG, "openPairPage: the sheet is up without a model; ignored")
                return
            }
            if (model.phase is PairPhase.Redeeming) {
                Log.i(TAG, "ignored: redeem in progress")
                return
            }
            model.refill(prefill, replaceEngineId)
            return
        }
        val model = current
        val fresh = if (model != null && model.phase is PairPhase.Redeeming) {
            // A redeem that outlived its sheet: show it, not a new page.
            model
        } else {
            PairPageModel(
                prefill = prefill,
                initialReplaceEngineId = replaceEngineId,
                backend = backend,
                store = store,
                scope = scope,
                io = io,
                fleet = fleet,
            ).also { current = it }
        }
        // 31 composes sheet entries outside the root content, so
        // LocalPairPage is NOT visible inside the sheet: capture the
        // model, the scan seam and the clipboard here (104's
        // "Captured locals" describes the same effect).
        val content: @Composable PageSheetScope.() -> Unit = {
            PairPage(fresh, this, qrScan, clipboard)
        }
        val onDismissed = {
            // 139: the observable flag flips first, so the root's status
            // line recomposes onto its own Redeeming/Error line as the
            // page goes (31 calls this for every dismissal kind).
            isShowing = false
            // Keep a model that is still redeeming: the background redeem
            // must survive the sheet, and a reopen shows it.
            val m = current
            if (m == null || m.phase !is PairPhase.Redeeming) current = null
        }
        val presented = if (over != null) {
            // PageSheetScope.present defaults to grabber = false.
            over.present(PAIR_KEY, SheetStyle.Page(grabber = true), onDismissed = onDismissed, content = content)
        } else {
            sheetHost.present(PAIR_KEY, SheetStyle.Page(grabber = true), onDismissed = onDismissed, content = content)
        }
        if (presented) {
            // 139: the flag is set only on a successful present — the
            // "already presenting" no-op leaves it untouched.
            isShowing = true
        } else {
            Log.w(TAG, "openPairPage: another sheet is up; ignored")
        }
    }

    private companion object {
        const val TAG = "PairPage"
        const val PAIR_KEY = "pair"
    }
}

/** The nearest [PairPageLauncher]; `null` before the core is ready or outside the app root. */
val LocalPairPage = staticCompositionLocalOf<PairPageLauncher?> { null }

/**
 * Creates the [PairPageLauncher] and provides it as [LocalPairPage].
 * Must sit **inside** [sh.roboco.android.shell.SheetHost] (it presents
 * through the host) and **outside** the root cross-fade, so a root swap
 * mid-redeem does not dispose the launcher (ticket 35 wires it inside
 * `RootHosts`).
 *
 * The [scope] is an app-scoped `Main.immediate` coroutine scope — never
 * `rememberCoroutineScope()`, which dies with a root swap mid-redeem —
 * cancelled only when the whole app root goes away.
 */
@Composable
fun ProvidePairPage(
    backend: PairingBackend,
    store: PairedEngineSink,
    qrScan: QrScanRequest?,
    clipboard: PairClipboard? = null,
    content: @Composable () -> Unit,
) {
    val host = LocalSheetHost.current ?: error("ProvidePairPage must be inside SheetHost")
    val context = LocalContext.current
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val androidClipboard = remember(clipboard, context) {
        clipboard ?: AndroidPairClipboard(context.applicationContext)
    }
    // Fleet follow's two toasts land in the root ToastHost (ProvidePairPage
    // sits inside it; outside one, LocalToaster is the logging no-op).
    val toaster = LocalToaster.current
    val fleet = remember(toaster, context) {
        ToasterFleetNotices(toaster, context.applicationContext.resources)
    }
    val launcher = remember(host, backend, store, qrScan, androidClipboard, fleet) {
        PairPageLauncher(host, backend, store, scope, qrScan, androidClipboard, fleet = fleet)
    }
    DisposableEffect(scope) {
        onDispose { scope.cancel() }
    }
    CompositionLocalProvider(LocalPairPage provides launcher) {
        content()
    }
}

/**
 * The production [PairingBackend] over the live [CoreClient]:
 * `pairEngine`, `joinRoom` (ADR 0011, ticket 04), `forgetEngine` and the
 * top-level `parsePairingUrl` / `parseRoomInvite`
 * (android-app#06; `runCatching` because a parse failure is a normal
 * "does not parse", never an error to surface while typing).
 * [onForgotten] runs after a successful `forgetEngine` (ticket 28: the
 * engine's notifications go with it — production passes
 * `Notifier.cancelEngine`).
 */
class CoreClientPairingBackend(
    private val client: CoreClient,
    private val onForgotten: (engineId: String) -> Unit = {},
) : PairingBackend {

    override fun parse(input: String): PairingTarget? =
        runCatching { uniffi.roboco_core.parsePairingUrl(input) }.getOrNull()

    override fun parseRoom(input: String): RoomInviteTarget? =
        runCatching { uniffi.roboco_core.parseRoomInvite(input) }.getOrNull()

    override suspend fun pair(url: String, replaceEngineId: String?): PairedEngine =
        client.pairEngine(url, replaceEngineId)

    override suspend fun joinRoom(invite: String): RoomJoinResult = client.joinRoom(invite)

    override suspend fun forget(engineId: String) {
        client.forgetEngine(engineId)
        onForgotten(engineId)
    }

    override suspend fun followFleet(engineId: String, onJoining: () -> Unit): RoomJoinResult? {
        // `fetchFleet` never throws (no fleet / old engine / offline = null);
        // the offer is an opaque Rust object, freed once the join returns.
        val offer = client.fetchFleet(engineId) ?: return null
        try {
            onJoining()
            return client.joinFleet(offer)
        } finally {
            offer.destroy()
        }
    }
}

/**
 * The production [FleetNotices] (fleet-follow ticket 04): the app's
 * [Toaster] with the two fleet strings. Copy only — never a fleet name,
 * label or address.
 */
class ToasterFleetNotices(
    private val toaster: Toaster,
    private val resources: Resources,
) : FleetNotices {

    override fun joining() {
        toaster.show(resources.getString(R.string.fleet_joining))
    }

    override fun joined(count: Int) {
        toaster.show(resources.getQuantityString(R.plurals.fleet_joined, count, count))
    }
}

/**
 * The production [PairedEngineSink] over ticket 71: `knownEngineIds`
 * reads the startup pipeline's live engine list (the store exposes no
 * in-memory list; every engine there was loaded from or written to the
 * store), `upsert`/`upsertRoom`/`remove` delegate to the store (the
 * room blob is ticket 04's `room:` class).
 */
class StorePairedEngineSink(
    private val store: PairedEngineStore,
    private val startup: CoreStartup,
) : PairedEngineSink {

    override fun knownEngineIds(): Set<String> =
        startup.engines.value.orEmpty().map { it.engineId }.toSet()

    override suspend fun upsert(engine: PairedEngine) = store.upsert(engine)

    override suspend fun upsertRoom(room: RoomRecord) = store.upsertRoom(room)

    override suspend fun remove(engineId: String) = store.remove(engineId)
}

