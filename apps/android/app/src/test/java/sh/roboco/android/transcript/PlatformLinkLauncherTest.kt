package sh.roboco.android.transcript

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [PlatformLinkLauncher] and [customTabsIntent] (Robolectric): the
 * builder extras per the ticket's Custom Tab table — the colour scheme
 * and toolbar colour follow the **app** appearance (dark `#060606`,
 * light `#F3F3F5`), the page title shows and the URL bar hides on
 * scroll — and the launcher's provider probe: `false` with no Custom
 * Tabs service, and the tab actually started (ACTION_VIEW, the URL, the
 * provider's package) with one. A provider needs both registrations
 * because `CustomTabsClient.getPackageName` probes the default
 * `http://` handler and then its Custom Tabs service. `view` swallows
 * the `ActivityNotFoundException` of an unresolvable intent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlatformLinkLauncherTest {

    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun customTabsIntentDarkFollowsTheAppAppearance() {
        val dark = customTabsIntent(true)
        assertEquals(
            CustomTabsIntent.COLOR_SCHEME_DARK,
            dark.intent.getIntExtra(CustomTabsIntent.EXTRA_COLOR_SCHEME, -1),
        )
        assertEquals(
            0xFF060606.toInt(),
            CustomTabsIntent.getColorSchemeParams(dark.intent, CustomTabsIntent.COLOR_SCHEME_DARK).toolbarColor,
        )
    }

    @Test
    fun customTabsIntentLightFollowsTheAppAppearance() {
        val light = customTabsIntent(false)
        assertEquals(
            CustomTabsIntent.COLOR_SCHEME_LIGHT,
            light.intent.getIntExtra(CustomTabsIntent.EXTRA_COLOR_SCHEME, -1),
        )
        assertEquals(
            0xFFF3F3F5.toInt(),
            CustomTabsIntent.getColorSchemeParams(light.intent, CustomTabsIntent.COLOR_SCHEME_LIGHT).toolbarColor,
        )
    }

    @Test
    fun customTabsIntentShowsTheTitleAndHidesTheUrlBarOnScroll() {
        val tabs = customTabsIntent(false)
        assertEquals(
            CustomTabsIntent.SHOW_PAGE_TITLE,
            tabs.intent.getIntExtra(CustomTabsIntent.EXTRA_TITLE_VISIBILITY_STATE, -1),
        )
        assertTrue(tabs.intent.getBooleanExtra(CustomTabsIntent.EXTRA_ENABLE_URLBAR_HIDING, false))
    }

    @Test
    fun customTabWithoutAProviderReturnsFalse() {
        // Robolectric resolves nothing by default: no default browser, so
        // no Custom Tabs provider and no fallback launch.
        assertFalse(PlatformLinkLauncher(app).customTab(URL, dark = false))
    }

    @Test
    fun customTabWithAProviderStartsTheTabWithItsPackage() {
        val pm = shadowOf(app.packageManager)
        // (a) the default http handler, and (b) its Custom Tabs service —
        // getPackageName probes both.
        pm.addResolveInfoForIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse("http://")),
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply { packageName = PROVIDER }
            },
        )
        pm.addResolveInfoForIntent(
            Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION).setPackage(PROVIDER),
            ResolveInfo().apply {
                serviceInfo = ServiceInfo().apply { packageName = PROVIDER }
            },
        )

        assertTrue(PlatformLinkLauncher(app).customTab(URL, dark = true))
        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_VIEW, started!!.action)
        assertEquals(Uri.parse(URL), started.data)
        assertEquals(PROVIDER, started.`package`)
    }

    @Test
    fun viewWithNoHandlerSwallowsTheException() {
        // With checkActivities, startActivity throws for an intent no
        // handler resolves; view() must swallow it, as iOS's open does.
        shadowOf(app).checkActivities(true)
        PlatformLinkLauncher(app).view("weird-scheme:x")
    }

    private companion object {
        const val PROVIDER = "com.example.browser"
        const val URL = "https://example.com/table"
    }
}
