package sh.roboco.android.update

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The PackageInstaller status mapping and the unknown-sources settings
 * intent (android-next#04): the decode of the status broadcast
 * (PENDING_USER_ACTION → the confirm intent, SUCCESS, failures with the
 * status message, stale session ids ignored) and the
 * `ACTION_MANAGE_UNKNOWN_APP_SOURCES` intent pinned to our package.
 * Robolectric for the `Intent`s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ApkInstallerStatusTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private fun statusIntent(status: Int, sessionId: Int = 7): Intent =
        Intent().putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
            .putExtra(PackageInstaller.EXTRA_STATUS, status)

    @Test
    fun pendingUserActionCarriesTheConfirmIntent() {
        val confirm = Intent(Intent.ACTION_INSTALL_PACKAGE)
        val intent = statusIntent(PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(Intent.EXTRA_INTENT, confirm)
        val status = installStatusOf(intent, activeSessionId = 7)
        assertTrue(status is ApkInstallStatus.Confirm)
        assertEquals(confirm, (status as ApkInstallStatus.Confirm).confirmIntent)
    }

    @Test
    fun successIsSuccess() {
        assertTrue(
            installStatusOf(
                statusIntent(PackageInstaller.STATUS_SUCCESS),
                activeSessionId = 7,
            ) is ApkInstallStatus.Success,
        )
    }

    @Test
    fun failuresCarryTheStatusMessage() {
        for (code in listOf(
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE,
            PackageInstaller.STATUS_FAILURE_TIMEOUT,
        )) {
            val intent = statusIntent(code)
                .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "reason $code")
            val status = installStatusOf(intent, activeSessionId = 7)
            assertTrue("code $code", status is ApkInstallStatus.Failed)
            assertEquals("reason $code", (status as ApkInstallStatus.Failed).message)
        }
    }

    @Test
    fun aPendingUserActionWithoutTheConfirmIntentFailsGracefully() {
        val status = installStatusOf(
            statusIntent(PackageInstaller.STATUS_PENDING_USER_ACTION),
            activeSessionId = 7,
        )
        assertTrue(status is ApkInstallStatus.Failed)
    }

    @Test
    fun aForeignSessionIdIsIgnored() {
        val intent = statusIntent(PackageInstaller.STATUS_SUCCESS, sessionId = 99)
        assertTrue(installStatusOf(intent, activeSessionId = 7) is ApkInstallStatus.Ignored)
        // No active session: every broadcast is accepted.
        assertTrue(installStatusOf(intent, activeSessionId = null) is ApkInstallStatus.Success)
    }

    @Test
    fun anUnknownStatusIsIgnored() {
        assertTrue(
            installStatusOf(statusIntent(Integer.MIN_VALUE), activeSessionId = 7)
                is ApkInstallStatus.Ignored,
        )
    }

    @Test
    fun theUnknownSourcesSettingsIntentIsPinnedToOurPackage() {
        val installer = PackageInstallerApkInstaller(context)
        installer.openUnknownSourcesSettings()
        val started = shadowOf(context).nextStartedActivity
        assertNotNull(started)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, started!!.action)
        assertEquals(Uri.parse("package:sh.roboco.android"), started.data)
        // Started from the application context: the NEW_TASK flag is set.
        assertTrue(
            "FLAG_ACTIVITY_NEW_TASK",
            (started.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0,
        )
    }
}
