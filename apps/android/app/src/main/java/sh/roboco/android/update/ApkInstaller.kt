package sh.roboco.android.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The PackageInstaller wrapper (android-next#04): one full-install
 * session per update, written from the verified cache file and committed
 * with a **mutable** status PendingIntent (the system fills
 * `EXTRA_STATUS`/`EXTRA_STATUS_MESSAGE`/`EXTRA_INTENT` into it —
 * `commit()` throws `IllegalArgumentException` for an immutable one
 * from targetSdk 35, and this app targets 36). The status lands in
 * [ApkInstallReceiver], which forwards it to [UpdateCenter.onInstallStatus].
 *
 * No FileProvider: the session API streams the file ourselves.
 */
interface ApkInstaller {

    /** Whether this app may request package installs (`REQUEST_INSTALL_PACKAGES`). */
    fun canInstall(): Boolean

    /** Opens the system's "Install unknown apps" screen for our package. */
    fun openUnknownSourcesSettings()

    /**
     * Writes [apk] into a fresh session and commits it; the outcome
     * arrives as a status broadcast (the receiver hands it to the
     * center). Returns the session id.
     */
    suspend fun install(apk: File): Int
}

/** The production installer. */
class PackageInstallerApkInstaller(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ApkInstaller {

    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun openUnknownSourcesSettings() {
        // The documented data URI pins the screen to our package
        // (Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES). The app context
        // needs NEW_TASK.
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    override suspend fun install(apk: File): Int = withContext(io) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            session.openWrite(SESSION_NAME, 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            session.commit(statusIntent(sessionId).intentSender)
        } finally {
            session.close()
        }
        sessionId
    }

    /**
     * The status receiver: an explicit broadcast to our own manifest
     * receiver (exported=false, no intent filter — only this PendingIntent
     * can reach it), **mutable** so the installer's status extras land.
     */
    private fun statusIntent(sessionId: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        sessionId,
        Intent(context, ApkInstallReceiver::class.java).setAction(ApkInstallReceiver.ACTION_STATUS),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        /** The staged file's name inside the session. */
        const val SESSION_NAME = "roboco-update.apk"
    }
}

/**
 * The status-broadcast decision (pure over the intent the system filled
 * in): `EXTRA_STATUS` is `-1` (`STATUS_PENDING_USER_ACTION`) → launch
 * the intent in `Intent.EXTRA_INTENT`; `0` (`STATUS_SUCCESS`); anything
 * positive is a failure whose `EXTRA_STATUS_MESSAGE` carries the
 * sentence. A broadcast for a session we did not start is ignored
 * **while a session is active**; with none active, every broadcast is
 * processed (the receiver is only reachable through our own
 * PendingIntent, so anything that arrives is ours or a stale replay of
 * a finished session).
 */
fun installStatusOf(intent: Intent, activeSessionId: Int?): ApkInstallStatus {
    val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
    if (activeSessionId != null && sessionId != activeSessionId) return ApkInstallStatus.Ignored
    return when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE)) {
        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
            val confirm = intent.parcelableExtra(Intent.EXTRA_INTENT)
                ?: return ApkInstallStatus.Failed(null)
            ApkInstallStatus.Confirm(confirm)
        }
        PackageInstaller.STATUS_SUCCESS -> ApkInstallStatus.Success
        PackageInstaller.STATUS_FAILURE,
        PackageInstaller.STATUS_FAILURE_ABORTED,
        PackageInstaller.STATUS_FAILURE_BLOCKED,
        PackageInstaller.STATUS_FAILURE_CONFLICT,
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
        PackageInstaller.STATUS_FAILURE_INVALID,
        PackageInstaller.STATUS_FAILURE_STORAGE,
        PackageInstaller.STATUS_FAILURE_TIMEOUT,
        -> ApkInstallStatus.Failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        else -> ApkInstallStatus.Ignored
    }
}

/** One install status broadcast, decoded. */
sealed interface ApkInstallStatus {
    /** The system needs the user: launch the confirm intent. */
    data class Confirm(val confirmIntent: Intent) : ApkInstallStatus

    /** Installed. */
    data object Success : ApkInstallStatus

    /** Failed; [message] is the system's status message when present. */
    data class Failed(val message: String?) : ApkInstallStatus

    /** Not ours (a stale or unrelated broadcast): ignore. */
    data object Ignored : ApkInstallStatus
}

/** The typed `getParcelableExtra` (the untyped one is deprecated on 33+). */
private fun Intent.parcelableExtra(name: String): Intent? =
    if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(name, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }
