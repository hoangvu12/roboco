package sh.roboco.android.shell

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.roboco.android.design.BottomChromeInsets

/**
 * The list bottom padding under the whole chrome group
 * (android-ui-parity #30): `barBottom + 62 (pill) + 8 (gap) + 48
 * (accessory) + 8` — the constants are 28's [TabChromeMetrics], never
 * retyped. The trailing `+ 8` is the Roboco edge-to-edge choice
 * (android-app#19): the last row clears the accessory by 8 dp, as the
 * iOS reference shows rows scrolling under the chrome (`3-on.png`).
 *
 * Computed from the **expanded** geometry, so it does not follow the
 * minimize morph: lists must not jump as the bar shrinks, and the
 * minimized chrome is shorter, so rows are never hidden by it. It does
 * not include the IME or 29's IME lift — screens with a focused field add
 * their own (ticket 49 owns that formula).
 */
fun bottomChromeContentPadding(barBottom: Dp): Dp =
    barBottom + TabChromeMetrics.PillHeight.dp +
        TabChromeMetrics.Gap.dp +
        TabChromeMetrics.AccessoryHeight.dp + 8.dp

/**
 * The shell's [BottomChromeInsets] from the live system insets:
 * `barBottom = tabBarBottomGap(navBottom, tappableBottom)` (26 — 21 dp on
 * gesture nav, `tappable + 12` above a 3-/2-button bar) and
 * [bottomChromeContentPadding] of it. The same [BottomChromeInsets.barBottom]
 * is passed as `TabChromeLayout(bottomInset = …)` so the bar and the
 * padding agree.
 */
@Composable
fun rememberBottomChromeInsets(): BottomChromeInsets {
    val density = LocalDensity.current
    val nav = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val tap = with(density) { WindowInsets.tappableElement.getBottom(density).toDp() }
    return remember(nav, tap) {
        val bar = tabBarBottomGap(nav, tap)
        BottomChromeInsets(bar, bottomChromeContentPadding(bar))
    }
}
