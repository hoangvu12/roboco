package sh.roboco.android.notify

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [NotificationPrefs] (android-app#28's store, extended by
 * android-ui-parity #99): the defaults, the exact key names in the
 * `notifications` file, the synchronous `state` mirror and the
 * persistence across a fresh instance on the same file. Robolectric —
 * the store is real `SharedPreferences`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationPrefsTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** A prefs over a private test file — never the singleton's. */
    private fun newPrefs(file: String = "prefs-test") =
        NotificationPrefs(context.getSharedPreferences(file, Context.MODE_PRIVATE))

    // ------------------------------------------------------------ defaults

    @Test
    fun aFreshInstallReadsEverythingOnExceptTheBookkeeping() {
        val prefs = newPrefs()
        assertTrue("enabled defaults to true (iOS: on unless turned off here)", prefs.enabled)
        for (kind in NotificationKind.entries) {
            assertTrue("$kind defaults to true", prefs.isOn(kind))
        }
        assertFalse("asked defaults to false (the first-session ask has not run)", prefs.asked)
        assertFalse(prefs.permissionRequested)
        assertFalse(prefs.permissionBlocked)
        assertEquals(
            "the state snapshot starts from the same defaults",
            NotificationPrefs.Snapshot(enabled = true, done = true, input = true, failed = true),
            prefs.state.value,
        )
    }

    @Test
    fun theKeysAreExactlyTheTicketsNamesInTheNotificationsFile() {
        val prefs = newPrefs()
        prefs.enabled = false
        prefs.set(NotificationKind.Done, false)
        prefs.set(NotificationKind.Input, false)
        prefs.set(NotificationKind.Failed, false)
        prefs.asked = true
        prefs.permissionRequested = true
        prefs.permissionBlocked = true

        val sp = context.getSharedPreferences("prefs-test", Context.MODE_PRIVATE)
        assertFalse(sp.getBoolean("notifications.enabled", true))
        for (kind in NotificationKind.entries) {
            assertFalse(sp.getBoolean(kind.prefKey, true))
        }
        assertTrue(sp.getBoolean("notifications.asked", false))
        assertTrue(sp.getBoolean("notifications.permissionRequested", false))
        assertTrue(sp.getBoolean("notifications.permissionBlocked", false))
        // No stray keys beyond the ticket's seven.
        assertEquals(
            setOf(
                "notifications.enabled",
                "notifications.done",
                "notifications.input",
                "notifications.failed",
                "notifications.asked",
                "notifications.permissionRequested",
                "notifications.permissionBlocked",
            ),
            sp.all.keys,
        )
    }

    // ------------------------------------------------------- the state flow

    @Test
    fun everySetterUpdatesTheSnapshotSynchronously() {
        val prefs = newPrefs()
        prefs.enabled = false
        assertEquals(NotificationPrefs.Snapshot(false, true, true, true), prefs.state.value)
        prefs.set(NotificationKind.Done, false)
        assertEquals(NotificationPrefs.Snapshot(false, false, true, true), prefs.state.value)
        prefs.set(NotificationKind.Input, false)
        assertEquals(NotificationPrefs.Snapshot(false, false, false, true), prefs.state.value)
        prefs.set(NotificationKind.Failed, false)
        assertEquals(NotificationPrefs.Snapshot(false, false, false, false), prefs.state.value)
        // Turning everything back on, one at a time.
        prefs.enabled = true
        prefs.set(NotificationKind.Done, true)
        prefs.set(NotificationKind.Input, true)
        prefs.set(NotificationKind.Failed, true)
        assertEquals(NotificationPrefs.Snapshot(true, true, true, true), prefs.state.value)
    }

    @Test
    fun theSnapshotIsOnForItsOwnKind() {
        val snapshot = NotificationPrefs.Snapshot(enabled = false, done = true, input = false, failed = true)
        assertTrue(snapshot.isOn(NotificationKind.Done))
        assertFalse(snapshot.isOn(NotificationKind.Input))
        assertTrue(snapshot.isOn(NotificationKind.Failed))
    }

    // ---------------------------------------------------------- persistence

    @Test
    fun writesPersistAcrossANewInstanceOnTheSameFile() {
        newPrefs().apply {
            enabled = false
            set(NotificationKind.Done, false)
            set(NotificationKind.Input, true)
            set(NotificationKind.Failed, false)
            asked = true
            permissionRequested = true
            permissionBlocked = true
        }
        val reopened = newPrefs()
        assertFalse(reopened.enabled)
        assertFalse(reopened.isOn(NotificationKind.Done))
        assertTrue(reopened.isOn(NotificationKind.Input))
        assertFalse(reopened.isOn(NotificationKind.Failed))
        assertTrue(reopened.asked)
        assertTrue(reopened.permissionRequested)
        assertTrue(reopened.permissionBlocked)
        assertEquals(NotificationPrefs.Snapshot(false, false, true, false), reopened.state.value)
    }

    // ------------------------------------------------------------ singleton

    @Test
    fun getReturnsTheProcessWideSingleton() {
        val a = NotificationPrefs.get(context)
        val b = NotificationPrefs.get(context)
        assertSame(a, b)
        // And it is the ticket's file.
        a.enabled = true
        assertTrue(
            context.getSharedPreferences(NotificationPrefs.FILE, Context.MODE_PRIVATE)
                .getBoolean("notifications.enabled", false),
        )
    }
}
