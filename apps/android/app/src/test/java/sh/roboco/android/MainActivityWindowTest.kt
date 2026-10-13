package sh.roboco.android

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.roboco.android.core.AppLifecycleHooks

/**
 * The window setup (android-ui-parity #35, Robolectric + ActivityScenario):
 * edge-to-edge with both contrast scrims off, the `configChanges`
 * declaration that keeps the activity alive across rotation/fold/resize
 * and appearance changes, and — the #609/#621 regression — recreating the
 * activity never adds a second `ProcessLifecycleOwner` observer, because
 * the hooks are installed once in `RobocoApplication.onCreate`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainActivityWindowTest {

    @Before
    fun resetInstallState() {
        // Robolectric reuses this JVM across classes; each test here
        // launches a fresh application, which installs its own hooks.
        AppLifecycleHooks.resetForTest()
    }

    @Test
    fun edgeToEdgeDisablesBothContrastScrims() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                assertFalse("nav-bar contrast scrim must be off", activity.window.isNavigationBarContrastEnforced)
                assertFalse("status-bar contrast scrim must be off", activity.window.isStatusBarContrastEnforced)
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun recreatingTheActivityAddsNoLifecycleObserver() {
        val registry = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            // After the first launch (which installs the hooks once).
            val afterLaunch = registry.observerCount

            repeat(3) { scenario.recreate() }

            assertEquals(
                "recreate() must not add ProcessLifecycleOwner observers",
                afterLaunch,
                registry.observerCount,
            )
        } finally {
            scenario.close()
        }
    }

    @Test
    fun theManifestDeclaresTheSizeAndUiModeConfigChanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            0,
        )
        val needed = ActivityInfo.CONFIG_SCREEN_SIZE or
            ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or
            ActivityInfo.CONFIG_SCREEN_LAYOUT or
            ActivityInfo.CONFIG_UI_MODE
        assertTrue(
            "configChanges=${info.configChanges} lacks $needed",
            (info.configChanges and needed) == needed,
        )
    }
}
