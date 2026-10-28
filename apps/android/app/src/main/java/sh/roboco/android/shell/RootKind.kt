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
 * The single owner of the split breakpoint, measured on the real root
 * width. Tickets 33, 104, 105 and 108 reference this constant. Inclusive;
 * no hysteresis.
 *
 * Wave-10 42 (user decision 2026-10-28): a tablet held upright follows the
 * iPad — split when wide enough. The narrowest iPad that splits in
 * portrait is the iPad mini at 744 pt, so the breakpoint is 744 dp (it was
 * Material's 840 dp "expanded" class, which kept every portrait tablet on
 * the phone layout).
 */
val SplitBreakpoint: Dp = 744.dp

/**
 * Wave-10 42 (I1): below this height the window is a phone held sideways
 * (a Pixel 7 in landscape is ~915 × 400 dp), which keeps the phone layout
 * however wide it is — iOS's compact height class.
 */
val CompactHeight: Dp = 480.dp

/**
 * The root on screen. `!hasEngines` wins over the size: a tablet with no
 * engine pairs, it never splits. A compact-height window (a landscape
 * phone) stays on the phone layout; otherwise the width decides. Both are
 * re-evaluated live (fold, multi-window resize, rotation) — a change never
 * goes through the cross-fade.
 */
fun chooseRoot(hasEngines: Boolean, width: Dp, height: Dp = Dp.Infinity): RootKind = when {
    !hasEngines -> RootKind.Pairing
    height < CompactHeight -> RootKind.Phone
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
