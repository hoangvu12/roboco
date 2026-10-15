package sh.roboco.android.newsession

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [toNewSessionOptions] (android-ui-parity #92) — the Options table:
 * projects in snapshot order with every field mapped (scoped ids, the
 * engine path verbatim, `deviceName ?: deviceName(deviceId)` with the
 * iOS "Unknown device" fallback, `colorIndex` u32 → Int), hosts = the
 * snapshot's execution devices in snapshot order (offline included, the
 * phone never — `isExecutionHost` is false for it).
 *
 * Plain JUnit, like ticket 40's workspace tests: the generated UniFFI
 * records are data classes whose construction touches no JNA (the
 * fixtures live in `RecordFixtures.kt`, shared with the store tests).
 */
class NewSessionOptionsTest {

    @Test
    fun projectsMapInSnapshotOrderWithEveryField() {
        val o = recordSnapshot(
            projects = listOf(
                recordProject("a", deviceId = "dev1", deviceName = "Studio", groupKey = "repo:r", groupName = "r", colorIndex = 3u),
                recordProject("b", deviceId = "dev2", deviceName = null, online = false, git = false),
            ),
            devices = listOf(recordDevice("dev1", name = "Studio"), recordDevice("dev2", name = "MBP")),
        ).toNewSessionOptions()
        assertEquals(listOf("a", "b"), o.projects.map { it.id })
        val a = o.projects[0]
        assertEquals("Project a", a.name)
        assertEquals("C:\\repo\\a", a.path)
        assertEquals("dev1", a.device)
        assertEquals("Studio", a.deviceName) // the engine-provided display name
        assertEquals(true, a.online)
        assertEquals(true, a.git)
        assertEquals("repo:r", a.groupKey)
        assertEquals("r", a.groupName)
        assertEquals(3, a.colorIndex) // u32 → Int
        val b = o.projects[1]
        assertEquals("MBP", b.deviceName) // the snapshot's device name
        assertEquals(false, b.online)
        assertEquals(false, b.git)
        assertEquals("repo:b", b.groupKey) // a projectless key is the scoped space id
    }

    @Test
    fun missingDeviceNameFallsBackToTheSnapshotDeviceOrUnknown() {
        // Known id, no engine-provided name → the snapshot's device name.
        val known = recordSnapshot(
            projects = listOf(recordProject("a", deviceId = "devX", deviceName = null)),
            devices = listOf(recordDevice("devX", name = "Engine machine")),
        ).toNewSessionOptions()
        assertEquals("Engine machine", known.projects[0].deviceName)
        // Unknown device id → the iOS literal fallback.
        val unknown = recordSnapshot(
            projects = listOf(recordProject("a", deviceId = "gone", deviceName = null)),
            devices = emptyList(),
        ).toNewSessionOptions()
        assertEquals("Unknown device", unknown.projects[0].deviceName)
    }

    @Test
    fun hostsAreTheExecutionDevicesInSnapshotOrder() {
        val o = recordSnapshot(
            projects = emptyList(),
            devices = listOf(
                recordDevice("dev1", name = "mac studio", executionHost = true, online = true),
                recordDevice("dev2", engineId = "E2", name = "hetzner-01", executionHost = true, online = false),
                recordDevice("phone", engineId = "E3", name = "This phone", executionHost = false, online = true),
            ),
        ).toNewSessionOptions()
        assertEquals(listOf("dev1", "dev2"), o.hosts.map { it.id })
        assertEquals(listOf("mac studio", "hetzner-01"), o.hosts.map { it.name })
        assertEquals(listOf(true, false), o.hosts.map { it.online }) // offline engines included
        assertTrue(o.projects.isEmpty())
    }
}
