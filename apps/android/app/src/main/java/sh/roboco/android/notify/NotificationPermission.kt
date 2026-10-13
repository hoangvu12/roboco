package sh.roboco.android.notify

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity

/**
 * The permission status machine (android-ui-parity#99's shape, created by
 * android-app#28): the Android 13+ `POST_NOTIFICATIONS` runtime permission
 * as the iOS `UNAuthorizationStatus` tri-state. API 31–32 have no runtime
 * permission, so only system settings can change the answer there.
 */
enum class NotificationStatus { NotDetermined, Granted, Denied }

/**
 * Reads (and, for the first-session ask, requests) the notification
 * permission. `status(context)` is activity-free and cheap enough for any
 * thread — android-app#28's delivery gates and service controller call it
 * off the main thread.
 */
object NotificationPermission {

    const val PERMISSION = Manifest.permission.POST_NOTIFICATIONS

    /** The first-session ask's request code (the activity callback forwards it). */
    const val REQUEST_CODE = 4280

    /** `True` when the runtime permission is granted (trivially on API 31–32). */
    private fun permissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * The current status. Resolution first-match-wins (#99's table):
     * granted (also clears a stale `permissionBlocked`), the 31–32
     * system-settings-only state, permission-held-but-app-blocked, the
     * recorded final denial, else NotDetermined (a request can still show
     * the system dialog).
     */
    fun status(
        context: Context,
        prefs: NotificationPrefs = NotificationPrefs.get(context),
    ): NotificationStatus {
        val appEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val granted = permissionGranted(context)
        val resolved = resolveStatus(Build.VERSION.SDK_INT, appEnabled, granted, prefs.permissionBlocked)
        if (resolved == NotificationStatus.Granted && prefs.permissionBlocked) {
            prefs.permissionBlocked = false
        }
        return resolved
    }

    /** #99's resolution table. Pure. */
    @VisibleForTesting
    internal fun resolveStatus(
        sdk: Int,
        appEnabled: Boolean,
        permissionGranted: Boolean,
        blocked: Boolean,
    ): NotificationStatus = when {
        appEnabled && permissionGranted -> NotificationStatus.Granted
        sdk < 33 -> NotificationStatus.Denied
        permissionGranted -> NotificationStatus.Denied
        blocked -> NotificationStatus.Denied
        else -> NotificationStatus.NotDetermined
    }

    /**
     * Whether a just-returned request result means the system will never
     * show the dialog again (a second denial). `shouldShowRationale` needs
     * an Activity, so the decision is made once per request, in the result
     * callback.
     */
    @VisibleForTesting
    internal fun blockedAfterResult(
        granted: Boolean,
        requestedBefore: Boolean,
        rationaleNow: Boolean,
    ): Boolean = !granted && requestedBefore && !rationaleNow

    /**
     * Call from every `POST_NOTIFICATIONS` result callback (this ticket's
     * first-session ask and #99's Settings toggle path): records whether
     * the denial was final, then marks the request as made.
     */
    fun recordRequestResult(prefs: NotificationPrefs, granted: Boolean, rationaleNow: Boolean) {
        val requestedBefore = prefs.permissionRequested
        prefs.permissionBlocked = blockedAfterResult(granted, requestedBefore, rationaleNow)
        prefs.permissionRequested = true
    }

    /**
     * The first-session ask (`ios:App/PushNotifications.swift:76-97`,
     * `NewSessionViewController.swift:416`): the moment it makes sense —
     * "tell me when it's done". If the master is on and this phone never
     * asked: mark it asked (once, ever) and, on API 33+ while
     * NotDetermined, launch the system request through the activity. API
     * 31–32: no runtime permission, nothing to ask. Fire-and-forget — the
     * result lands in the activity's `onRequestPermissionsResult`
     * (MainActivity forwards it to [recordRequestResult]).
     */
    fun askAfterFirstSession(
        activity: ComponentActivity,
        prefs: NotificationPrefs = NotificationPrefs.get(activity),
    ) {
        if (!prefs.enabled || prefs.asked) return
        prefs.asked = true
        if (Build.VERSION.SDK_INT < 33) return
        if (status(activity, prefs) != NotificationStatus.NotDetermined) return
        activity.requestPermissions(arrayOf(PERMISSION), REQUEST_CODE)
    }

    /**
     * iOS `UIApplication.openSettingsURLString`'s nearest page: the app's
     * own notification settings, falling back to its details page
     * (#99's copy of the pr699 logic).
     */
    fun openAppNotificationSettings(context: Context) {
        val pkg = context.packageName
        val primary = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
        val fallback = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", pkg, null),
        )
        for (intent in listOf(primary, fallback)) {
            if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
    }
}

/** Unwraps the `ContextWrapper` chain to an [Activity], or null. */
internal fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
