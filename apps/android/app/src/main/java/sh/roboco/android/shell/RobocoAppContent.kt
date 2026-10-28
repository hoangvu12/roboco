package sh.roboco.android.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import sh.roboco.android.RobocoApplication
import sh.roboco.android.core.CoreStartState
import sh.roboco.android.core.FontGate
import sh.roboco.android.design.FontGateHost
import sh.roboco.android.design.ProvideFeedback
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.pairing.CoreClientPairingBackend
import sh.roboco.android.pairing.InboundPairingLinks
import sh.roboco.android.pairing.LocalPairPage
import sh.roboco.android.pairing.PairPageLauncher
import sh.roboco.android.pairing.PairingRoot
import sh.roboco.android.pairing.ProvidePairPage
import sh.roboco.android.pairing.StorePairedEngineSink
import sh.roboco.android.pairing.pairingRootStatus
import sh.roboco.android.pairing.rememberQrScanRequest
import sh.roboco.android.sessions.LocalRowActions
import sh.roboco.android.shell.tablet.Sidebar

/**
 * The real app content wiring (android-ui-parity #35) — the only place
 * that touches the neighbour tickets' composables:
 *
 * `RobocoTheme(appearance)` (01 — the appearance value is ticket 96's
 * `Prefs.appearance` flow) → [SystemBarsEffect] →
 * [AppearanceWindowEffect] (96: the window background follows the launch
 * colour of the effective appearance) → `ProvideFeedback`
 * (05, once, at the root) → `FontGateHost` (03, the flow — a late
 * `FontGate.fail` recomposes to the fatal screen) → the root presentation
 * registry + `RouterHooks` provider (32; 76/95 assign the
 * implementations) → [RootHosts] (the overlay host stack, 14–19/31, with
 * 42's app-scoped toaster and `LocalRowActions`) → [AppRoot].
 *
 * `LocalAppRouter` is **not** provided here: each shell provides its own
 * (32 phone, 106 tablet). Ticket 36's hooks also live here (outside
 * `AppRoot`): the hub's pairing-root tracking over `rootState.kind`, and
 * each shell's router attached/detached after its first frame through
 * [DeepLinkRouterEffect]. Ticket 108's [ShellStateScope] (35's
 * `shellScope` inside `AppRoot`) owns the hoisted shell state both shells
 * read.
 */
@Composable
fun RobocoAppContent(app: RobocoApplication, rootState: AppRootState) {
    // 36: the deep-link hub follows the root the cross-fade is heading
    // to (its `kind`), never composition lifetimes — during the 350 ms
    // fade both roots are composed but links are already dropped on the
    // pairing target. `kind == null` (Loading/CoreFailed) reports `false`
    // with no router attached, so cold-start links stay held.
    val hub = app.deepLinks
    LaunchedEffect(rootState) {
        snapshotFlow { rootState.kind }.collect { kind ->
            hub.showingPairingRoot(kind == RootKind.Pairing)
        }
    }
    // 96's preference flow: the DataStore-backed appearance, `System` until
    // the first disk read (the platform's own persisted per-app mode has
    // already covered the splash), then the stored choice.
    val appearance by app.appearance.collectAsState()
    RobocoTheme(appearance) { // 01 (ProvideReduceMotion 04, ProvideGlass 07 inside)
        SystemBarsEffect(RobocoTheme.isDark)
        // 96: the DecorView background keeps the effective launch colour,
        // so an in-place switch never shows the stale window background
        // through a whole-tree redraw.
        AppearanceWindowEffect(RobocoTheme.isDark)
        ProvideFeedback { // 05: once, at the root
            // The flow, not the snapshot: a late FontGate.fail (ticket
            // 60's loaders) recomposes this to the fatal screen.
            val fontStatus by FontGate.statusFlow.collectAsState()
            FontGateHost(fontStatus) { // 03: keep this wrapper
                val presentations = remember { RootPresentations() }
                CompositionLocalProvider(LocalRouterHooks provides app.routerHooks) { // 32: one per app root
                    RootHosts(
                        presentations,
                        toaster = app.appToaster, // 42's app-scoped toaster
                        alerts = app.appAlerts, // android-next#04's app-scoped alert state
                    ) { // 42's app-scoped toaster
                        // 42's row actions, provided inside the root
                        // ToastHost so every screen can write and the
                        // toasts land in the root host.
                        CompositionLocalProvider(LocalRowActions provides app.rowActions) {
                            // 72: the Pair page launcher. `client` re-reads
                            // on `Ready` (the collected startup state);
                            // while it is null (core loading or failed)
                            // `LocalPairPage` stays null and callers no-op
                            // instead of crashing.
                            val coreState by app.coreStartup.state.collectAsState()
                            val client =
                                if (coreState is CoreStartState.Ready) app.clientOrNull() else null
                            // One movable root for both branches: `Ready`
                            // moves it under the launcher instead of
                            // discarding everything remembered below it.
                            val root = remember(app, rootState, presentations) {
                                movableContentOf { pairPage: PairPageLauncher? ->
                                    AppRootWithPairing(
                                        app = app,
                                        rootState = rootState,
                                        presentations = presentations,
                                        pairPage = pairPage,
                                    )
                                }
                            }
                            if (client == null) {
                                root(null)
                            } else {
                                // Stable across recompositions, so
                                // ProvidePairPage keeps its launcher (it must
                                // not be rebuilt mid-redeem).
                                val backend = remember(client) {
                                    // 28: a forgotten engine's notifications go with it.
                                    CoreClientPairingBackend(client, onForgotten = app.notifier::cancelEngine)
                                }
                                val store = remember(app) { StorePairedEngineSink(app.engineStore, app.coreStartup) }
                                // 138: the real scanner — the Pair page's
                                // Scan QR row appears (72) whenever a
                                // camera exists.
                                val qrScan = rememberQrScanRequest()
                                ProvidePairPage(
                                    backend = backend,
                                    store = store,
                                    qrScan = qrScan,
                                ) {
                                    // 139: the inbound pairing-link handler
                                    // (deep links and shares). Registered only
                                    // while the root input is Ready, one frame
                                    // after the root is drawn; while
                                    // Loading/CoreFailed the hub holds links.
                                    val input by app.rootInput.collectAsState()
                                    InboundPairingLinks(ready = input is RootInput.Ready)
                                    root(LocalPairPage.current)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * [AppRoot] with the Pair page hook wired (72's step 8): the root
 * content, one body for both the launcher-present and
 * launcher-absent cases, with [pairPage] read **outside** the pairing
 * lambda. 138 wired the real `QrScanRequest` ([rememberQrScanRequest]);
 * 139 derives the root's status line from [pairPage] (its
 * snapshot-backed `current`/`isShowing`) via `pairingRootStatus(pairPage)`.
 *
 * 108: [ShellStateScope] wraps both shell slots inside the root (35's
 * `shellScope`), so 106's hoisted block — the split state, the sidebar
 * state, the phone-router slot, the settings slot and the tablet router —
 * now lives there (created once, surviving every Phone ↔ Split switch),
 * and the slots below only **read** it through [LocalShellStates].
 * `isCollapsed` has exactly one writer (the scope's `SideEffect`); the
 * scope's `onDispose` releases every hoisted session when the shell layer
 * itself leaves composition.
 */
@Composable
private fun AppRootWithPairing(
    app: RobocoApplication,
    rootState: AppRootState,
    presentations: RootPresentations,
    pairPage: PairPageLauncher?,
) {
    val input by app.rootInput.collectAsState()
    AppRoot(
        input = input,
        state = rootState,
        presentations = presentations,
        pairing = {
            PairingRoot(
                status = pairingRootStatus(pairPage),
                onPair = { pairPage?.openPairPage() },
            )
        },
        // 108: the shell-state scope above the Phone ↔ Split switch (35's
        // `shellScope`). Everything the two shells share — 26's tab state,
        // 33's split state, 105's sidebar, 106's slots and tablet router —
        // is remembered inside it, so the open session's NavEntry and the
        // phone router survive the switch, and the carry-over owns what
        // moves across it.
        shellScope = { shellKind, content -> ShellStateScope(shellKind, content) },
        phone = {
            // 108: the hoisted shell state, read through the scope's local.
            val s = LocalShellStates.current
            s.saveable.SaveableStateProvider("phone") {
                // 36: the phone router (32's `onRouter` — the
                // `window.rootViewController as? AppRouter` port) is attached
                // after the shell's first frame, never during its composition.
                // 26: the shell state is the scope's — the tab stacks survive
                // the 840 dp round trip (one `MainTabState`, like iOS's one
                // `MainTabController`). 106/108: `presence` forwards the tab
                // shell's session appear/close into the carry-over through
                // 106's `tabPresence`, and the slot keeps the router for the
                // tablet router's collapsed delegation.
                var phoneRouter by remember { mutableStateOf<AppRouter?>(null) }
                // 27 step 4: the accessory's live summary off the one
                // workspace feed (the flow coalesces by revision off the
                // main thread). The state is NOT read here: a stable
                // provider hands it to the accessory, so a counts change
                // recomposes the accessory only, never this slot or the
                // shell's measure-time subcomposition.
                val counts = rememberLiveCounts(app.workspaceFeed.snapshot)
                val liveCounts = remember(counts) { { counts.value } }
                MainTabShell(
                    state = s.tabs,
                    liveCounts = liveCounts,
                    presence = s.presence,
                    // Wave-10 37: the iPhone's one-row bottom bar.
                    oneRowBar = true,
                    onRouter = { router -> phoneRouter = router; s.phoneSlot.attach(router) },
                )
                phoneRouter?.let { DeepLinkRouterEffect(app.deepLinks, it) }
                // 106: the slot drops the phone router when this shell leaves
                // (the 840 dp expand cancels its scope, together with this
                // slot's disposal), so a collapsed delegation keeps the latest
                // call pending for the fresh shell instead of calling a dead
                // router.
                DisposableEffect(phoneRouter) {
                    val attached = phoneRouter
                    onDispose { attached?.let { s.phoneSlot.detach(it) } }
                }
            }
        },
        split = {
            // 108: the hoisted shell state, read through the scope's local.
            val s = LocalShellStates.current
            s.saveable.SaveableStateProvider("split") {
                CompositionLocalProvider(LocalAppRouter provides s.tabletRouter) {
                    // 104: the presenter must be created INSIDE this provider —
                    // it captures currentCompositionLocalContext, so the card's
                    // rows reach this router. The split registers it in the
                    // settings slot while composed.
                    val presenter = rememberSettingsDialogPresenter()
                    DisposableEffect(presenter) {
                        s.settingsSlot.presenter = presenter
                        onDispose {
                            if (s.settingsSlot.presenter === presenter) s.settingsSlot.presenter = null
                        }
                    }
                    // 36: the split's router attached after its first frame,
                    // like the phone slot's.
                    DeepLinkRouterEffect(app.deepLinks, s.tabletRouter)
                    SplitRoot(
                        shell = s.tablet,
                        sidebar = { shown ->
                            // 105: the sidebar pane (its rows read
                            // `s.tablet.currentChatId` for the highlight);
                            // 107: the bottom toolbar fills the slot — gear
                            // (Settings), ⋯ (the options menu bound to the
                            // sidebar's own stack) and the accent +.
                            Sidebar(
                                state = s.sidebar,
                                currentChatId = s.tablet.currentChatId,
                                shown = shown,
                                toolbar = { stack -> SidebarToolbar(stack) },
                            )
                        },
                        // 106: the pane renders the router's persistent detail
                        // stack — the slot's DetailRoot argument is ignored on
                        // purpose (the stack is the source of truth; 33 step 5:
                        // no NavStack, no key(detailGeneration) here).
                        detail = { _ -> TabletDetailHost(s.tabletRouter.detailStack) },
                        router = s.tabletRouter,
                    )
                }
            }
        },
    )
}
