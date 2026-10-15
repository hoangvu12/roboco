package sh.roboco.android.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import sh.roboco.android.RobocoApplication

/**
 * The install-status receiver (android-next#04): the one entry point the
 * system's PackageInstaller talks to. Manifest-registered with
 * `exported="false"` and **no intent filter** — the only way in is the
 * explicit mutable PendingIntent [PackageInstallerApkInstaller] hands to
 * `Session.commit`, so nothing else can reach it — and it holds no state:
 * every broadcast goes straight to the app's [UpdateCenter].
 */
class ApkInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? RobocoApplication ?: return
        app.updates.onInstallStatus(intent)
    }

    companion object {
        /** The action of the status PendingIntent's explicit intent. */
        const val ACTION_STATUS = "sh.roboco.android.update.INSTALL_STATUS"
    }
}
