package sh.roboco.android.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification delivery gates (android-app#28's policy table): the
 * foreground / master / kind / permission matrix. Plain JUnit — the policy
 * is pure.
 */
class NotificationPolicyTest {

    private val allOn = NotificationPrefs.Snapshot(
        enabled = true,
        done = true,
        input = true,
        failed = true,
    )

    private val allOff = allOn.copy(enabled = false)

    // -------------------------------------------------- the matrix

    @Test
    fun backgroundWithEverythingOnPostsEveryKind() {
        for (kind in NotificationKind.entries) {
            assertTrue(
                "$kind must post in the background",
                NotificationPolicy.sessionEventAllowed(
                    kind,
                    allOn,
                    NotificationStatus.Granted,
                    appInForeground = false,
                ),
            )
        }
    }

    @Test
    fun theForegroundGateIsAbsoluteNoBannerAndNoChime() {
        // iOS `willPresent` → `[]`: nothing shows while the app is front.
        // Covers "the chat currently open" — and everything else.
        for (kind in NotificationKind.entries) {
            assertFalse(
                "$kind must never post while the app is foreground",
                NotificationPolicy.sessionEventAllowed(
                    kind,
                    allOn,
                    NotificationStatus.Granted,
                    appInForeground = true,
                ),
            )
        }
        // LinkLost too.
        assertFalse(
            NotificationPolicy.linkLostAllowed(allOn, NotificationStatus.Granted, true),
        )
    }

    @Test
    fun theMasterGatePairsThePrefWithThePermission() {
        // Off in Settings.
        for (kind in NotificationKind.entries) {
            assertFalse(
                "the master pref off stops $kind",
                NotificationPolicy.sessionEventAllowed(
                    kind,
                    allOff,
                    NotificationStatus.Granted,
                    appInForeground = false,
                ),
            )
        }
        // The permission denied / revoked.
        for (kind in NotificationKind.entries) {
            assertFalse(
                "a denied permission stops $kind",
                NotificationPolicy.sessionEventAllowed(
                    kind,
                    allOn,
                    NotificationStatus.Denied,
                    appInForeground = false,
                ),
            )
        }
        // The undetermined first run (before the ask): no posting either.
        assertFalse(
            NotificationPolicy.sessionEventAllowed(
                NotificationKind.Done,
                allOn,
                NotificationStatus.NotDetermined,
                appInForeground = false,
            ),
        )
        // The FGS start gate rides the same master.
        assertFalse(NotificationPolicy.masterOn(allOff, NotificationStatus.Granted))
        assertFalse(NotificationPolicy.masterOn(allOn, NotificationStatus.Denied))
        assertTrue(NotificationPolicy.masterOn(allOn, NotificationStatus.Granted))
    }

    @Test
    fun eachKindSwitchStopsOnlyItsOwnKind() {
        for (off in NotificationKind.entries) {
            val snapshot = allOn.copy(
                done = off != NotificationKind.Done,
                input = off != NotificationKind.Input,
                failed = off != NotificationKind.Failed,
            )
            for (kind in NotificationKind.entries) {
                val expected = kind != off
                val actual = NotificationPolicy.sessionEventAllowed(
                    kind,
                    snapshot,
                    NotificationStatus.Granted,
                    appInForeground = false,
                )
                assertTrue("$kind with $off off should post=$expected", expected == actual)
            }
            // LinkLost rides the failed pref.
            val linkLostAllowed = NotificationPolicy.linkLostAllowed(
                snapshot,
                NotificationStatus.Granted,
                appInForeground = false,
            )
            assertTrue("LinkLost with $off off should post=${off != NotificationKind.Failed}",
                (off != NotificationKind.Failed) == linkLostAllowed)
        }
    }
}
