package sh.roboco.android.shell

import android.graphics.drawable.ColorDrawable
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import sh.roboco.android.design.Palette

/**
 * The two launch-window backgrounds, one per effective appearance. Built
 * once per process so a recomposition never allocates a new
 * [ColorDrawable]; [android.view.Window.setBackgroundDrawable] only swaps
 * the DecorView's reference, so sharing them is safe.
 */
private val LaunchBackgroundLightDrawable = ColorDrawable(Palette.LaunchBackgroundLight.toArgb())
private val LaunchBackgroundDarkDrawable = ColorDrawable(Palette.LaunchBackgroundDark.toArgb())

/**
 * Keeps the window (DecorView) background on the **launch** colour of the
 * effective appearance (ticket 96): light `#EDEDED`, dark `#000000` — the
 * same colours `Theme.Roboco`'s `windowBackground`/splash resolve per
 * configuration, so what shows through during a whole-tree redraw equals
 * what a cold start in that mode would show.
 *
 * The root's opaque `Palette.background` normally hides it, but
 * `configChanges="uiMode"` (ticket 35) means the theme's
 * `windowBackground` is never re-resolved after an in-place switch: a
 * light-mode launch followed by a switch to Dark would otherwise keep
 * flashing `#EDEDED` during IME resizes and full redraws. `pr621`'s
 * `ZeronRoot.kt:152-155` makes the same point for its own background.
 *
 * No-op outside an activity (previews, non-window hosts).
 */
@Composable
fun AppearanceWindowEffect(isDark: Boolean) {
    val activity = LocalActivity.current ?: return
    val drawable = if (isDark) LaunchBackgroundDarkDrawable else LaunchBackgroundLightDrawable
    SideEffect { activity.window.setBackgroundDrawable(drawable) }
}
