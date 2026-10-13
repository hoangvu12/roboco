package sh.roboco.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import sh.roboco.android.RobocoApplication
import sh.roboco.android.core.Face
import sh.roboco.android.core.FontGate
import sh.roboco.android.design.FontGateHost
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.ProvideFeedback
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle
import sh.roboco.android.pairing.PairingRoot
import sh.roboco.android.pairing.PairingRootStatus
import sh.roboco.android.sessions.LocalRowActions

/**
 * The real app content wiring (android-ui-parity #35) — the only place
 * that touches the neighbour tickets' composables:
 *
 * `RobocoTheme(appearance)` (01 — the appearance value is ticket 96's
 * flow, `System` until then) → [SystemBarsEffect] → `ProvideFeedback`
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
    // 96's preference flow; `Appearance.System` until 96 wires the store.
    val appearance by app.appearance.collectAsState()
    RobocoTheme(appearance) { // 01 (ProvideReduceMotion 04, ProvideGlass 07 inside)
        SystemBarsEffect(RobocoTheme.isDark)
        ProvideFeedback { // 05: once, at the root
            // The flow, not the snapshot: a late FontGate.fail (ticket
            // 60's loaders) recomposes this to the fatal screen.
            val fontStatus by FontGate.statusFlow.collectAsState()
            FontGateHost(fontStatus) { // 03: keep this wrapper
                val presentations = remember { RootPresentations() }
                val input by app.rootInput.collectAsState()
                // 34: the hoisted status line; nobody writes it yet
                // (139 later derives it instead).
                val pairingStatus = remember { mutableStateOf<PairingRootStatus>(PairingRootStatus.None) }
                CompositionLocalProvider(LocalRouterHooks provides app.routerHooks) { // 32: one per app root
                    RootHosts(presentations, toaster = app.appToaster) { // 42's app-scoped toaster
                        // 42's row actions, provided inside the root
                        // ToastHost so every screen can write and the
                        // toasts land in the root host.
                        CompositionLocalProvider(LocalRowActions provides app.rowActions) {
                            AppRoot(
                                input = input,
                                state = rootState,
                                presentations = presentations,
                                pairing = {
                                    PairingRoot(
                                        status = pairingStatus.value,
                                        // TODO(72): open the Pair page through
                                        // LocalPairPage (read outside the lambda).
                                        onPair = {},
                                    )
                                },
                                phone = {
                                    // 36: the phone router (32's `onRouter` —
                                    // the `window.rootViewController
                                    // as? AppRouter` port) is attached after
                                    // the shell's first frame, never during
                                    // its composition. 26: the shell state
                                    // stays the default
                                    // `rememberMainTabState()`.
                                    var phoneRouter by remember { mutableStateOf<AppRouter?>(null) }
                                    MainTabShell(onRouter = { router -> phoneRouter = router })
                                    phoneRouter?.let { DeepLinkRouterEffect(hub, it) }
                                },
                                split = {
                                    val tablet = rememberTabletShellState() // 33
                                    // Interim; 108 becomes the single writer
                                    // of isCollapsed (the hoisted shell scope).
                                    SideEffect { tablet.isCollapsed = false }
                                    // 106 adds `DeepLinkRouterEffect(hub, tabletRouter)`
                                    // here once it builds the tablet router.
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
                    }
                }
            }
        }
    }
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
