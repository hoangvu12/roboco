package sh.roboco.android.transcript

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import sh.roboco.android.design.LocalIsDark
import sh.roboco.android.design.Palette

/**
 * The platform side of link routing (ticket 126): everything that needs
 * an [android.content.Context]. The production [PlatformLinkLauncher];
 * tests record the calls instead.
 */
interface LinkLauncher {
    /**
     * A Custom Tab for an http(s) URL, tinted for the app appearance
     * [dark]. Returns `false` when no Custom Tabs provider exists or the
     * launch failed, so the caller falls back to [view].
     */
    fun customTab(url: String, dark: Boolean): Boolean

    /**
     * `Intent(ACTION_VIEW)` for the URL; a missing handler
     * (`ActivityNotFoundException`) and a `SecurityException` are
     * swallowed — a silent no-op, as iOS's `UIApplication.open`.
     */
    fun view(url: String)
}

/**
 * The [LinkLauncher] over a [Context]: Custom Tabs when a provider is
 * installed (`CustomTabsClient.getPackageName` — needs the manifest's
 * `<queries>` entry on API 30+), `ACTION_VIEW` for everything else. A
 * non-Activity context gets `FLAG_ACTIVITY_NEW_TASK`.
 */
class PlatformLinkLauncher(private val context: Context) : LinkLauncher {

    override fun customTab(url: String, dark: Boolean): Boolean {
        val provider = CustomTabsClient.getPackageName(context, null) ?: return false
        val tabs = customTabsIntent(dark)
        tabs.intent.setPackage(provider)
        if (activityOf(context) == null) tabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            tabs.launchUrl(context, Uri.parse(url))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    override fun view(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (activityOf(context) == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
        } catch (e: SecurityException) {
        }
    }
}

/** Unwraps [ContextWrapper]s to the [Activity], or `null` (then NEW_TASK is needed). */
private tailrec fun activityOf(context: Context?): Activity? = when (context) {
    is Activity -> context
    is ContextWrapper -> activityOf(context.baseContext)
    else -> null
}

/**
 * The Custom Tab for an http(s) link, per the ticket's table: title
 * shown (SFSafari's site bar), URL bar hiding on scroll (SFSafari's
 * default `barCollapsingEnabled`), and the toolbar coloured
 * `Palette.background` for **both** schemes — light `#F3F3F5`, dark
 * `#060606` — with the scheme itself following the **app** appearance
 * [dark], not the system's, and the default params mirroring the
 * current scheme's. The `preferredControlTintColor` accent is dropped:
 * Custom Tabs has no control-tint API. Pure, so the builder extras
 * unit-test.
 */
fun customTabsIntent(dark: Boolean): CustomTabsIntent {
    val light = CustomTabColorSchemeParams.Builder()
        .setToolbarColor(Palette.Light.background.toArgb())
        .build()
    val darkParams = CustomTabColorSchemeParams.Builder()
        .setToolbarColor(Palette.Dark.background.toArgb())
        .build()
    return CustomTabsIntent.Builder()
        .setShowTitle(true)
        .setUrlBarHidingEnabled(true)
        .setColorScheme(if (dark) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
        .setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_LIGHT, light)
        .setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_DARK, darkParams)
        .setDefaultColorSchemeParams(if (dark) darkParams else light)
        .build()
}

/**
 * What a link tap does (ticket 126): `routeLink` decides, and this class
 * executes — a Custom Tab (or the `ACTION_VIEW` fallback) for web links,
 * the full-screen viewer for image chips, the system for other schemes,
 * and nothing for the TRANSCRIPT-D4 no-ops. No haptics anywhere on the
 * path (the iOS source fires none).
 *
 * [scope] is the session screen's scope (Main): [open] runs on the
 * pointer callback's thread, [loadFull] suspends and does its I/O and
 * decoding off main (66), and [showImage] runs on main. A preview load
 * still in flight when the screen leaves composition is cancelled with
 * that scope, so no viewer appears after a pop (iOS's `weak vc`); a new
 * preview tap cancels the previous load, so the latest tap wins.
 */
class LinkRouter(
    private val scope: CoroutineScope,
    private val isDark: () -> Boolean,
    private val loadFull: suspend (reference: String) -> ImageBitmap?,
    private val showImage: (reference: String, source: Rect, bitmap: ImageBitmap) -> Unit,
    private val launcher: LinkLauncher,
) {
    /** The in-flight preview load; a new tap replaces it. */
    private var pending: Job? = null

    fun open(tap: LinkTap) {
        when (val r = routeLink(tap.url)) {
            is LinkRoute.Web -> if (!launcher.customTab(r.url, isDark())) launcher.view(r.url)
            is LinkRoute.Preview -> showFull(r.reference, tap.rowBounds)
            is LinkRoute.External -> launcher.view(r.url)
            LinkRoute.Ignore -> Unit
        }
    }

    /** Loads the full image, then opens the viewer from the row's bounds; a failed load shows nothing. */
    private fun showFull(reference: String, source: Rect) {
        pending?.cancel()
        pending = scope.launch {
            val bitmap = try {
                loadFull(reference)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (bitmap != null) showImage(reference, source, bitmap)
        }
    }
}

/**
 * One [LinkRouter] per session screen (ticket 126, step 9): the
 * [LinkRouter] is remembered over the context while [loadFull] and
 * [showImage] are kept fresh through `rememberUpdatedState`, the dark
 * flag follows the app appearance, and the scope is the composition's —
 * cancelled on leave, so an in-flight preview load dies with the screen.
 */
@Composable
fun rememberLinkRouter(
    loadFull: suspend (reference: String) -> ImageBitmap?,
    showImage: (reference: String, source: Rect, bitmap: ImageBitmap) -> Unit,
): LinkRouter {
    val context = LocalContext.current
    val dark by rememberUpdatedState(LocalIsDark.current)
    val scope = rememberCoroutineScope()
    val currentLoadFull by rememberUpdatedState(loadFull)
    val currentShowImage by rememberUpdatedState(showImage)
    return remember(context) {
        LinkRouter(
            scope = scope,
            isDark = { dark },
            loadFull = { reference -> currentLoadFull(reference) },
            showImage = { reference, source, bitmap -> currentShowImage(reference, source, bitmap) },
            launcher = PlatformLinkLauncher(context),
        )
    }
}
