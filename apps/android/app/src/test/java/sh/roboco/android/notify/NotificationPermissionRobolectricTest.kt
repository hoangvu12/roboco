package sh.roboco.android.notify

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `NotificationPermission.status(context)` under Robolectric at API 31
 * and 34 (android-ui-parity #99's permission acceptance list), with the
 * shadows the ticket names: `shadowOf(NotificationManager)
 * .setNotificationsEnabled(...)` for the app toggle and
 * `shadowOf(application).grantPermissions/denyPermissions(POST_NOTIFICATIONS)`
 * for the runtime permission, plus [NotificationPermission.recordRequestResult]'s
 * bookkeeping over a real [NotificationPrefs]. Each test runs at both
 * SDKs and branches its expectation where the model differs (below 33
 * there is no runtime permission, so the app toggle alone decides).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34])
class NotificationPermissionRobolectricTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    /** Fresh per test (Robolectric rebuilds the app per method). */
    private lateinit var prefs: NotificationPrefs

    @Before
    fun setUp() {
        prefs = NotificationPrefs(context.getSharedPreferences("permission-test", Context.MODE_PRIVATE))
        // The default shadow state is "enabled"; pin it so only the test's
        // own writes matter.
        shadowOf(manager).setNotificationsEnabled(true)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun status() = NotificationPermission.status(context, prefs)

    private val highSdk: Boolean
        get() = Build.VERSION.SDK_INT >= 33

    // ------------------------------------------------------------- status

    @Test
    fun notificationsEnabledReadsGrantedBelow33AndNotDeterminedAbove() {
        shadowOf(manager).setNotificationsEnabled(true)
        val s = status()
        if (highSdk) {
            // No runtime grant recorded: a request can still show the dialog.
            assertEquals(NotificationStatus.NotDetermined, s)
        } else {
            assertEquals(NotificationStatus.Granted, s)
        }
    }

    @Test
    fun notificationsDisabledIsDeniedOnBothSdks() {
        shadowOf(manager).setNotificationsEnabled(false)
        // Below 33: rule 2 (only system settings can turn them back on).
        // On 33+: rule 3 (defensive — permission held but the app is
        // blocked in system settings) once the permission is granted.
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(NotificationStatus.Denied, status())
    }

    @Test
    fun grantedPermissionWithNotificationsEnabledIsGrantedAndClearsStaleBlocked() {
        prefs.permissionBlocked = true
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(NotificationStatus.Granted, status())
        assertFalse("a stale permissionBlocked flag is cleared on Granted", prefs.permissionBlocked)
    }

    @Test
    fun notGrantedAndNotBlockedIsNotDeterminedAbove33() {
        if (highSdk) {
            assertEquals(NotificationStatus.NotDetermined, status())
        } else {
            // Below 33 the permission trivially holds; the app toggle decides.
            assertEquals(NotificationStatus.Granted, status())
        }
    }

    @Test
    fun notGrantedAndBlockedIsDenied() {
        prefs.permissionBlocked = true
        if (highSdk) {
            // Rule 4: the recorded final denial.
            assertEquals(NotificationStatus.Denied, status())
        } else {
            // Below 33 the permission trivially holds, so rule 1 (app
            // toggle on) wins before the blocked flag is ever read — and
            // clears it as stale.
            assertEquals(NotificationStatus.Granted, status())
        }
    }

    // -------------------------------------------------- recordRequestResult

    @Test
    fun recordRequestResultAlwaysMarksTheRequestAsMade() {
        assertFalse("fresh prefs have not requested", prefs.permissionRequested)
        NotificationPermission.recordRequestResult(prefs, granted = true, rationaleNow = false)
        assertTrue(prefs.permissionRequested)
        assertFalse("a grant never sets the blocked flag", prefs.permissionBlocked)

        NotificationPermission.recordRequestResult(prefs, granted = false, rationaleNow = true)
        assertTrue(prefs.permissionRequested)
        assertFalse("a denial with rationale showing is not final", prefs.permissionBlocked)
    }

    @Test
    fun aSecondDenialWithNoRationaleRecordsTheFinalBlock() {
        // First ask dismissed or denied: requestedBefore = false → not final.
        NotificationPermission.recordRequestResult(prefs, granted = false, rationaleNow = false)
        assertFalse("the first denial is never final", prefs.permissionBlocked)
        assertTrue(prefs.permissionRequested)

        // Second denial, no rationale: the dialog will never show again.
        NotificationPermission.recordRequestResult(prefs, granted = false, rationaleNow = false)
        assertTrue("the second denial is final", prefs.permissionBlocked)
        assertTrue(prefs.permissionRequested)

        // Which status() then reports as Denied (rule 4) on 33+. Below 33
        // there is no runtime permission: the enabled app toggle (rule 1)
        // reports Granted regardless of the bookkeeping.
        assertEquals(if (highSdk) NotificationStatus.Denied else NotificationStatus.Granted, status())
    }

    @Test
    fun aGrantAfterABlockClearsIt() {
        NotificationPermission.recordRequestResult(prefs, granted = false, rationaleNow = false)
        NotificationPermission.recordRequestResult(prefs, granted = false, rationaleNow = false)
        assertTrue(prefs.permissionBlocked)

        NotificationPermission.recordRequestResult(prefs, granted = true, rationaleNow = false)
        assertFalse("a granted result clears the block", prefs.permissionBlocked)
        assertTrue(prefs.permissionRequested)
    }
}
