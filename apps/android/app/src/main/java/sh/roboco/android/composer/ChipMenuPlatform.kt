package sh.roboco.android.composer

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import sh.roboco.android.design.Palette
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.notify.findActivity

/**
 * The production [ChipMenuPlatform] (android-ui-parity #89): a Custom Tab
 * for "Open Pull Request" (the recipe ticket 126 specifies for every web
 * link, so a PR link and a transcript link look identical) and the plain
 * clipboard for "Copy Link" (iOS `UIPasteboard.general.url`; Android 13+
 * shows its own confirmation chip, the app shows nothing).
 *
 * [dark] is the **app's** resolved appearance at tap time
 * ([RobocoTheme.isDark]), not the system's — the tab's toolbar matches
 * the app the user is looking at.
 */
class AndroidChipMenuPlatform(
    private val context: Context,
    private val dark: Boolean,
) : ChipMenuPlatform {
    override fun openUrl(url: String) = openInCustomTab(context, url, dark)

    override fun copyLink(url: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
            ClipData.newPlainText("Link", url),
        )
    }
}

/**
 * The composition-side platform: `LocalContext` is the Activity the chip
 * lives in, so `openUrl` needs no `FLAG_ACTIVITY_NEW_TASK` on the normal
 * path. Re-created only when the context or the appearance changes.
 */
@Composable
fun rememberChipMenuPlatform(): ChipMenuPlatform {
    val context = LocalContext.current
    val dark = RobocoTheme.isDark
    return remember(context, dark) { AndroidChipMenuPlatform(context, dark) }
}

/**
 * Opens [url] in a Custom Tab with ticket 126's settings (ticket 89 must
 * open a PR link exactly the way 126 opens a transcript link): the title
 * shown, the URL bar hiding on scroll, per-scheme toolbar colours and the
 * current appearance's `Palette.background` (light `#F3F3F5`, dark
 * `#060606` — `ios:Design/Palette.swift:13`). A Custom Tabs browser is
 * pinned via `CustomTabsClient.getPackageName` (needs the manifest's
 * `<queries>` entry on API 30+); without one, or when the launch fails,
 * the plain `ACTION_VIEW` intent runs, and nothing throws when even that
 * has no handler (the app shows no error — iOS `UIApplication.shared.open`
 * fails silently too).
 */
fun openInCustomTab(context: Context, url: String, dark: Boolean) {
    val uri = Uri.parse(url)
    fun params(bg: Color) = CustomTabColorSchemeParams.Builder().setToolbarColor(bg.toArgb()).build()
    val current = params(Palette.of(dark).background)
    val tabs = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .setUrlBarHidingEnabled(true)
        .setColorScheme(if (dark) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
        .setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_LIGHT, params(Palette.Light.background))
        .setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_DARK, params(Palette.Dark.background))
        .setDefaultColorSchemeParams(current)
        .build()
    val newTask = context.findActivity() == null // unwrap ContextWrappers
    val pkg = CustomTabsClient.getPackageName(context, null)
    if (pkg != null) {
        tabs.intent.setPackage(pkg)
        if (newTask) tabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            tabs.launchUrl(context, uri)
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply { if (newTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    } catch (_: ActivityNotFoundException) {
    } catch (_: SecurityException) {
    }
}
