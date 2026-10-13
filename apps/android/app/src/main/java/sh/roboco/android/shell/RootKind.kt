package sh.roboco.android.shell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The three window roots (android-ui-parity #35): which one is on screen
 * is a pure function of the app state and the window width —
 * [chooseRoot]. The swap between the two *groups* is the 350 ms
 * cross-fade; Phone ↔ Split is an in-place switch inside the shell group
 * (ticket 108's collapse, `SplitRootController.swift:15-16,49`).
 */
enum class RootKind { Pairing, Phone, Split }

/**
 * What the app root needs from the app model (ticket 35, derived in
 * `RobocoApplication` from #13's [sh.roboco.android.core.CoreStatus] and
 * 71's `CoreStartup`):
 *
 * - [Loading] — nothing is drawn, the platform splash is held
 *   (`FirstFrameHold`); Android loads engines asynchronously, and a
 *   paired user must not see the pairing root flash before the list is
 *   known (iOS reads the Keychain synchronously in `AppModel.init`);
 * - [CoreFailed] — the native core could not be loaded; the diagnostic
 *   screen replaces the app (a pairing root without a core cannot work);
 * - [Ready] — the engine list is known; [Ready.hasEngines] is
 *   `engines().isNotEmpty()`, parked (revoked) engines included.
 */
sealed interface RootInput {
    data object Loading : RootInput
    data class CoreFailed(val reason: String) : RootInput
    data class Ready(val hasEngines: Boolean) : RootInput
}

/**
 * The single owner of the 840 dp split breakpoint (the spec's
 * `screenWidthDp >= 840` threshold, measured on the real root width —
 * `pr621:ZeronRoot.kt:245-246`). Tickets 33, 104, 105 and 108 reference
 * this constant. Inclusive: exactly 840 dp is the split; there is no
 * hysteresis.
 */
val SplitBreakpoint: Dp = 840.dp

/**
 * The root on screen. `!hasEngines` wins over the width: a tablet with no
 * engine pairs, it never splits. The width is re-evaluated live (fold,
 * multi-window resize, rotation) — a width change never goes through the
 * cross-fade.
 */
fun chooseRoot(hasEngines: Boolean, width: Dp): RootKind = when {
    !hasEngines -> RootKind.Pairing
    width >= SplitBreakpoint -> RootKind.Split
    else -> RootKind.Phone
}

/**
 * Swaps animate only between these two groups (Pairing ↔ Shell); Phone ↔
 * Split happens inside the shell group, in place, with no fade
 * (`SceneDelegate.swift:24-33` runs `showRoot` only for auth changes; the
 * iPad split collapses to the tab shell inside `UISplitViewController`).
 */
internal enum class RootGroup { Pairing, Shell }

/** The fade group of [RootKind]: Pairing alone, Phone and Split together. */
internal val RootKind.group: RootGroup
    get() = if (this == RootKind.Pairing) RootGroup.Pairing else RootGroup.Shell
