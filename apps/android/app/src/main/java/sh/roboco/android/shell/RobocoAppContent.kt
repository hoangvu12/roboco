package sh.roboco.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import sh.roboco.android.RobocoApplication
import sh.roboco.android.core.CoreStartState
import sh.roboco.android.core.Face
import sh.roboco.android.core.FontGate
import sh.roboco.android.design.FontGateHost
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.ProvideFeedback
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle
import sh.roboco.android.pairing.CoreClientPairingBackend
import sh.roboco.android.pairing.LocalPairPage
import sh.roboco.android.pairing.PairPageLauncher
import sh.roboco.android.pairing.PairingRoot
import sh.roboco.android.pairing.PairingRootStatus
import sh.roboco.android.pairing.ProvidePairPage
import sh.roboco.android.pairing.StorePairedEngineSink
import sh.roboco.android.pairing.rememberQrScanRequest
import sh.roboco.android.sessions.LocalRowActions

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
 * (32 phone, 106 tablet).
 *
 * Ticket 36's hooks also live here (outside `AppRoot`): the hub's
 * pairing-root tracking over `rootState.kind`, and each shell's router
 * attached/detached after its first frame through
 * [DeepLinkRouterEffect].
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
                // 34: the hoisted status line; nobody writes it yet
                // (139 later derives it instead).
                val pairingStatus = remember { mutableStateOf<PairingRootStatus>(PairingRootStatus.None) }
                CompositionLocalProvider(LocalRouterHooks provides app.routerHooks) { // 32: one per app root
                    RootHosts(presentations, toaster = app.appToaster) { // 42's app-scoped toaster
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
                            val root = remember(app, rootState, presentations, pairingStatus) {
                                movableContentOf { pairPage: PairPageLauncher? ->
                                    AppRootWithPairing(
                                        app = app,
                                        rootState = rootState,
                                        presentations = presentations,
                                        pairingStatus = pairingStatus,
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
                                val backend = remember(client) { CoreClientPairingBackend(client) }
                                val store = remember(app) { StorePairedEngineSink(app.engineStore, app.coreStartup) }
                                ProvidePairPage(
                                    backend = backend,
                                    store = store,
                                    qrScan = null, // 138 lands the scanner
                                ) {
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
 * lambda. 138 wired the real [QrScanRequest] (`rememberQrScanRequest`);
 * 139 replaces the hoisted `pairingStatus` with
 * `pairingRootStatus(pairPage)`.
 */
@Composable
private fun AppRootWithPairing(
    app: RobocoApplication,
    rootState: AppRootState,
    presentations: RootPresentations,
    pairingStatus: MutableState<PairingRootStatus>,
    pairPage: PairPageLauncher?,
) {
    val input by app.rootInput.collectAsState()
    AppRoot(
        input = input,
        state = rootState,
        presentations = presentations,
        pairing = {
            PairingRoot(
                status = pairingStatus.value,
                onPair = { pairPage?.openPairPage() },
            )
        },
        phone = {
            // 36: the phone router (32's `onRouter` — the
            // `window.rootViewController as? AppRouter` port) is attached
            // after the shell's first frame, never during its composition.
            // 26: the shell state stays the default `rememberMainTabState()`.
            var phoneRouter by remember { mutableStateOf<AppRouter?>(null) }
            MainTabShell(onRouter = { router -> phoneRouter = router })
            phoneRouter?.let { DeepLinkRouterEffect(app.deepLinks, it) }
        },
        split = {
            val tablet = rememberTabletShellState() // 33
            // Interim; 108 becomes the single writer
            // of isCollapsed (the hoisted shell scope).
            SideEffect { tablet.isCollapsed = false }
            SplitRoot(
                shell = tablet,
                sidebar = { _ ->
                    // TODO(105): the sidebar pane.
                    SplitPanePlaceholder("Sidebar")
                },
                detail = { _ ->
                    // TODO(106): the detail pane's stack.
                    SplitPanePlaceholder("Detail")
                },
            )
        },
    )
}

/**
 * The interim split slot content (105/106 have not landed): a full
 * `palette.background` pane with the slot's name centred, so the split
 * frame, the hairline and the Phone ↔ Split switch are exercisable.
 */
@Composable
private fun SplitPanePlaceholder(label: String) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Box(
        Modifier
            .fillMaxSize()
            .background(palette.background),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = uiStyle(Face.Sans, typeScale.scaled(17f), palette.secondary))
    }
}
