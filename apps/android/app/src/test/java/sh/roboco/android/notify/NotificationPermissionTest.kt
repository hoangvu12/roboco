package sh.roboco.android.notify

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The permission status machine's pure tables (android-ui-parity #99):
 * [NotificationPermission.resolveStatus] (first-match resolution over
 * sdk 31–34 × appEnabled × permissionGranted × blocked) and
 * [NotificationPermission.blockedAfterResult] (the five-row situation
 * table of first/second asks). Plain JUnit — both functions are pure.
 */
class NotificationPermissionTest {

    // ------------------------------------------------------ resolveStatus

    /** Rule 1: `appEnabled && permissionGranted` → Granted, for every sdk and blocked flag. */
    @Test
    fun anEnabledAppWithThePermissionIsGrantedAlways() {
        for (sdk in intArrayOf(31, 32, 33, 34)) {
            for (blocked in booleanArrayOf(false, true)) {
                assertEquals(
                    "sdk=$sdk blocked=$blocked",
                    NotificationStatus.Granted,
                    NotificationPermission.resolveStatus(sdk, appEnabled = true, permissionGranted = true, blocked = blocked),
                )
            }
        }
    }

    /**
     * Rule 2: below API 33 there is no runtime permission, so anything
     * that is not Granted reads Denied — only system settings can turn
     * notifications back on there, like iOS `.denied`.
     */
    @Test
    fun below33EverythingElseIsDenied() {
        for (sdk in intArrayOf(31, 32)) {
            for (appEnabled in booleanArrayOf(false, true)) {
                for (granted in booleanArrayOf(false, true)) {
                    if (appEnabled && granted) continue // rule 1
                    for (blocked in booleanArrayOf(false, true)) {
                        assertEquals(
                            "sdk=$sdk appEnabled=$appEnabled granted=$granted blocked=$blocked",
                            NotificationStatus.Denied,
                            NotificationPermission.resolveStatus(sdk, appEnabled, granted, blocked),
                        )
                    }
                }
            }
        }
    }

    /** Rule 3 (defensive): the permission is held but the app is blocked in system settings. */
    @Test
    fun permissionHeldWhileTheAppIsBlockedIsDenied() {
        for (sdk in intArrayOf(33, 34)) {
            for (blocked in booleanArrayOf(false, true)) {
                assertEquals(
                    "sdk=$sdk blocked=$blocked",
                    NotificationStatus.Denied,
                    NotificationPermission.resolveStatus(sdk, appEnabled = false, permissionGranted = true, blocked = blocked),
                )
            }
        }
    }

    /** Rule 4: the recorded final denial — the system will no longer show the dialog. */
    @Test
    fun aRecordedFinalDenialIsDeniedOn33Plus() {
        for (sdk in intArrayOf(33, 34)) {
            assertEquals(
                "sdk=$sdk",
                NotificationStatus.Denied,
                NotificationPermission.resolveStatus(sdk, appEnabled = true, permissionGranted = false, blocked = true),
            )
            assertEquals(
                "sdk=$sdk (app also blocked)",
                NotificationStatus.Denied,
                NotificationPermission.resolveStatus(sdk, appEnabled = false, permissionGranted = false, blocked = true),
            )
        }
    }

    /** Rule 5: API 33+ with nothing decided — a request can still show the system dialog. */
    @Test
    fun anUndecidedRequestIsNotDeterminedOn33Plus() {
        for (sdk in intArrayOf(33, 34)) {
            for (appEnabled in booleanArrayOf(false, true)) {
                assertEquals(
                    "sdk=$sdk appEnabled=$appEnabled",
                    NotificationStatus.NotDetermined,
                    NotificationPermission.resolveStatus(sdk, appEnabled, permissionGranted = false, blocked = false),
                )
            }
        }
    }

    /** The whole lattice at once: 4 sdks × 8 boolean combos, expected hand-copied from the ticket's table. */
    @Test
    fun theResolutionTableMatchesForEveryCombination() {
        // (sdkClass, appEnabled, permissionGranted, blocked) → expected.
        // sdkClass "low" = 31/32 (no runtime permission), "high" = 33/34.
        // The branches mirror the ticket's table order (rule 1 granted,
        // rule 2 the 31–32 system-settings-only state, rule 3 the
        // permission-held-but-app-blocked case, rule 4 the recorded final
        // denial, rule 5 NotDetermined); the per-rule tests above pin each
        // rule with literals, so this loop only checks the lattice holds
        // together.
        fun expected(sdk: Int, appEnabled: Boolean, permissionGranted: Boolean, blocked: Boolean): NotificationStatus = when {
            appEnabled && permissionGranted -> NotificationStatus.Granted
            sdk < 33 -> NotificationStatus.Denied
            permissionGranted -> NotificationStatus.Denied
            blocked -> NotificationStatus.Denied
            else -> NotificationStatus.NotDetermined
        }
        for (sdk in intArrayOf(31, 32, 33, 34)) {
            for (appEnabled in booleanArrayOf(false, true)) {
                for (permissionGranted in booleanArrayOf(false, true)) {
                    for (blocked in booleanArrayOf(false, true)) {
                        assertEquals(
                            "sdk=$sdk appEnabled=$appEnabled permissionGranted=$permissionGranted blocked=$blocked",
                            expected(sdk, appEnabled, permissionGranted, blocked),
                            NotificationPermission.resolveStatus(sdk, appEnabled, permissionGranted, blocked),
                        )
                    }
                }
            }
        }
    }

    // --------------------------------------------------- blockedAfterResult

    /** The ticket's five-row situation table, plus the granted rows. */
    @Test
    fun blockedAfterResultMatchesTheSituationTable() {
        // A local fun, not a function reference: named arguments are not
        // allowed on a function-type value, and the table reads better with them.
        fun f(granted: Boolean, requestedBefore: Boolean, rationaleNow: Boolean) =
            NotificationPermission.blockedAfterResult(granted, requestedBefore, rationaleNow)
        // First ask, "Allow": granted — never blocked, whatever the flags.
        assertEquals("first ask allowed", false, f(true, requestedBefore = false, rationaleNow = false))
        assertEquals("first ask allowed (no rationale)", false, f(true, false, false))
        assertEquals("granted again after a denial", false, f(true, true, false))
        // First ask, "Don't allow" (Android 11+ never makes the first
        // denial final): rationale still shows.
        assertEquals("first ask denied", false, f(false, requestedBefore = false, rationaleNow = true))
        // First ask dismissed (back / outside tap): no rationale yet either,
        // and never requested before → not final.
        assertEquals("first ask dismissed", false, f(false, requestedBefore = false, rationaleNow = false))
        // Second ask, "Don't allow": requested before, no rationale now —
        // final, the system will not show the dialog again.
        assertEquals("second ask denied", true, f(false, requestedBefore = true, rationaleNow = false))
        // A denial with the rationale still showing is never final.
        assertEquals("denied but rationale shows", false, f(false, requestedBefore = true, rationaleNow = true))
    }
}
