package sh.roboco.android.shell

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Re-applies the edge-to-edge window style whenever the effective light /
 * dark mode changes (android-ui-parity #35; ticket 96 flips the appearance
 * at runtime). No-op outside a `ComponentActivity` (previews).
 */
@Composable
fun SystemBarsEffect(isDark: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    DisposableEffect(activity, isDark) {
        activity.applyRobocoEdgeToEdge(isDark)
        onDispose { }
    }
}

/**
 * Edge-to-edge with **transparent** system bars and icons that follow the
 * effective light/dark mode: dark icons in light mode, light in dark
 * (`SceneDelegate`'s window setup; Roboco draws under both bars). Called
 * from `MainActivity.onCreate` (before `super`, per the androidx template)
 * and re-applied by [SystemBarsEffect].
 *
 * Always the explicit `SystemBarStyle.light`/`dark` forms — never `auto`:
 * `auto` re-enables the nav-bar contrast scrim on 3-button navigation, so
 * both contrast flags are reset after every call.
 */
fun ComponentActivity.applyRobocoEdgeToEdge(isDark: Boolean) {
    val transparent = android.graphics.Color.TRANSPARENT
    val style = if (isDark) {
        SystemBarStyle.dark(transparent)
    } else {
        SystemBarStyle.light(transparent, transparent)
    }
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    window.isNavigationBarContrastEnforced = false
    window.isStatusBarContrastEnforced = false
}
