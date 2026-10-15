package sh.roboco.android.design

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * The debug gallery's launch hook (android-ui-parity #22): the app's
 * `MainActivity` calls [maybeStart] from `onCreate`/`onNewIntent`, and it
 * starts the design gallery when the launch intent carries
 * `route=gallery` in a debuggable build.
 *
 * The activity is addressed by class **name** string, never by
 * `DesignGalleryActivity::class`: that class exists only in `src/debug`, so
 * it does not exist in main or release, and referencing it here would break
 * the release build. In release, the route is ignored (logged once), which
 * is android-app#19's rule for launch extras.
 */
object GalleryLaunch {

    private const val TAG = "RobocoGallery"

    const val ROUTE = "gallery"

    const val EXTRA_ROUTE = "route"

    const val CLASS_NAME = "sh.roboco.android.design.DesignGalleryActivity"

    /**
     * Starts the gallery when [source] carries `route=gallery` in a
     * debuggable build, forwarding all extras (minus the route itself).
     * Returns `true` when the gallery was started.
     */
    fun maybeStart(activity: Activity, source: Intent): Boolean {
        if (source.getStringExtra(EXTRA_ROUTE) != ROUTE) return false
        if (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            Log.w(TAG, "route=gallery ignored: not debuggable")
            return false
        }
        val intent = Intent().setClassName(activity.packageName, CLASS_NAME)
        source.extras?.let(intent::putExtras)
        intent.removeExtra(EXTRA_ROUTE)
        return try {
            activity.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "route=gallery ignored: $CLASS_NAME not found", e)
            false
        }
    }
}
