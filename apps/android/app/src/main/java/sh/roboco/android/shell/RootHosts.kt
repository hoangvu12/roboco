package sh.roboco.android.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import dev.chrisbanes.haze.rememberHazeState
import sh.roboco.android.design.ActionSheetHost
import sh.roboco.android.design.ActionSheetHostState
import sh.roboco.android.design.AlertHost
import sh.roboco.android.design.AlertHostState
import sh.roboco.android.design.ContextMenuHost
import sh.roboco.android.design.LocalContextMenuHost
import sh.roboco.android.design.ToasterState
import sh.roboco.android.design.ToastHost
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.image.ImageViewerHost
import sh.roboco.android.image.rememberImageViewerState
import sh.roboco.android.newsession.HandoffOverlayHost

/**
 * The foundation overlay host stack (android-ui-parity #35), mounted once
 * around the root. Mount order is fixed by the foundation tickets —
 * outermost first:
 *
 * `ContextMenuHost(16) { AlertHost(18) { ToastHost(14) { ActionSheetHost(19)
 * { GlassMenuHost(15) { ImageViewerHost(125) { SheetHost(31) { content } } } } } } }`
 *
 * - an alert raised over a page sheet draws above it;
 * - a toast draws above sheets, action sheets and menus;
 * - a toast shown just before a root swap survives the swap (the toaster
 *   state is hoisted by the caller, app-scoped);
 * - `SheetHost` sits **outside** the root swap: the pairing root's Pair
 *   page (34/72) is presented through the same host, a sheet survives a
 *   width collapse (Phone ↔ Split), and 31's page-sheet recession scales
 *   the whole root, as iOS's window root view recedes. Ticket 95 wraps
 *   the `SheetHost` line in its `HandoffOverlayHost`; ticket 72 wraps
 *   the root content in `ProvidePairPage` inside it.
 * - ticket 125's `ImageViewerHost` wraps `SheetHost` (inside
 *   `GlassMenuHost`, outside the sheet): the full-screen image viewer
 *   covers the session screen, the tab chrome and any sheet, while
 *   alerts, action sheets and toasts stay above it.
 *
 * Every host that owns a root presentation registers its instant-dismiss
 * call with [presentations], so one `dismissAllInstantly()` — same frame,
 * before either cross-fade layer changes — tears down the whole presented
 * chain (`SceneDelegate.swift:92-94`). Alerts fade out (18's `dismissAll`
 * is animated; accepted — practically unreachable during a swap) and
 * glass menus close when their trigger leaves composition at the end of
 * the fade (15 has no host-level close; accepted). Toasts are not
 * registered and survive.
 *
 * @param presentations the root-swap registry; the same instance the
 *   caller passes to [AppRoot].
 * @param toaster the app-scoped toast state (42's `appToaster`); `null`
 *   (tests, previews) mounts a local one.
 * @param alerts the app-scoped alert state (android-next#04, the
 *   toaster precedent): the update center shows its prompts through it
 *   from outside any composition. `null` (tests, previews) mounts a
 *   local one.
 */
@Composable
fun RootHosts(
    presentations: RootPresentations,
    toaster: ToasterState? = null,
    alerts: AlertHostState? = null,
    content: @Composable () -> Unit,
) {
    val alertState = alerts ?: remember { AlertHostState() } // 18
    val actionSheets = remember { ActionSheetHostState() } // 19
    val sheets = rememberSheetHostState() // 31
    val menuHaze = rememberHazeState() // 07 / 15
    CompositionLocalProvider(LocalRootPresentations provides presentations) {
        ContextMenuHost { // 16: outermost inside RobocoTheme
            val lift = LocalContextMenuHost.current
            AlertHost(alertState) { // 18: outside ToastHost
                ToastHost(state = toaster) { // 14: outside ActionSheetHost
                    ActionSheetHost(actionSheets) { // 19
                        GlassMenuHost(menuHaze) { // 15
                            // 125: inside the menu host, outside the sheet —
                            // covers the session screen, tab chrome and
                            // sheets; alerts, action sheets and toasts stay
                            // above it (they are outside GlassMenuHost here).
                            val viewer = rememberImageViewerState()
                            ImageViewerHost(viewer) {
                                HandoffOverlayHost { // 95: above the app root and the (hidden) sheet,
                                    // below menus, alerts and toasts — the handoff overlays
                                    SheetHost(sheets) { // 31: recession scales the roots
                                        content()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            DisposableEffect(presentations) {
                val handles = listOf(
                    // 16: the lift was given `dismiss(animated = false)`
                    // for exactly this call.
                    presentations.register { lift?.dismiss(animated = false) },
                    // 18: `dismissAll()` fades; accepted (see KDoc above).
                    presentations.register { alertState.dismissAll() },
                    // 19: `clear()` is internal, same module.
                    presentations.register { actionSheets.clear() },
                    // 31: also covers 104's in-window Settings card entry.
                    presentations.register { sheets.dismiss(animated = false) },
                )
                onDispose { handles.forEach { handle -> handle() } }
            }
        }
    }
}
