package sh.roboco.android.design

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric.buildActivity
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `GalleryLaunch.maybeStart` (android-ui-parity #22): `route=gallery` starts
 * the debug-only `DesignGalleryActivity` by class *name* (the class does not
 * exist in main or release), forwards every extra except `route`, and only
 * in debuggable builds. Other routes start nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GalleryLaunchTest {

    /** A bare host activity; the flag is forced either way for the test. */
    private fun host(debuggable: Boolean): Activity {
        val activity = buildActivity(Activity::class.java).setup().get()
        val flags = activity.applicationInfo.flags
        activity.applicationInfo.flags =
            if (debuggable) flags or ApplicationInfo.FLAG_DEBUGGABLE
            else flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        return activity
    }

    @Test
    fun routeGalleryStartsTheGalleryActivityAndForwardsExtras() {
        val activity = host(debuggable = true)
        val source = Intent()
            .putExtra(GalleryLaunch.EXTRA_ROUTE, GalleryLaunch.ROUTE)
            .putExtra("section", "type")
            .putExtra("appearance", "dark")
            .putExtra("reduce", true)

        assertTrue(GalleryLaunch.maybeStart(activity, source))

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(GalleryLaunch.CLASS_NAME, started?.component?.className)
        assertEquals(activity.packageName, started?.component?.packageName)
        assertEquals("type", started?.getStringExtra("section"))
        assertEquals("dark", started?.getStringExtra("appearance"))
        assertEquals(true, started?.getBooleanExtra("reduce", false))
        assertFalse(started!!.hasExtra(GalleryLaunch.EXTRA_ROUTE))
    }

    @Test
    fun otherRoutesStartNothing() {
        val activity = host(debuggable = true)
        val source = Intent().putExtra(GalleryLaunch.EXTRA_ROUTE, "search")

        assertFalse(GalleryLaunch.maybeStart(activity, source))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun nonDebuggableBuildsIgnoreTheGalleryRoute() {
        val activity = host(debuggable = false)
        val source = Intent().putExtra(GalleryLaunch.EXTRA_ROUTE, GalleryLaunch.ROUTE)

        assertFalse(GalleryLaunch.maybeStart(activity, source))
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
