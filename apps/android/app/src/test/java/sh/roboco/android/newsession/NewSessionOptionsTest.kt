package sh.roboco.android.newsession

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.DeviceView
import uniffi.roboco_core.FrontPage
import uniffi.roboco_core.ProjectView
import uniffi.roboco_core.PullRequestGroups
import uniffi.roboco_core.WorkspaceSnapshot

/**
 * [toNewSessionOptions] (android-ui-parity #92) — the Options table:
 * projects in snapshot order with every field mapped (scoped ids, the
 * engine path verbatim, `deviceName ?: deviceName(deviceId)` with the
 * iOS "Unknown device" fallback, `colorIndex` u32 → Int), hosts = the
 * snapshot's execution devices in snapshot order (offline included, the
 * phone never — `isExecutionHost` is false for it).
 *
 * Plain JUnit, like ticket 40's workspace tests: the generated UniFFI
 * records are data classes whose construction touches no JNA.
 */
class NewSessionOptionsTest {

    private fun project(
        id: String,
        engineId: String = "E1",
        deviceId: String = "dev1",
        deviceName: String? = null,
        online: Boolean = true,
        git: Boolean = true,
        groupKey: String = "repo:$id",
        groupName: String = "group $id",
        colorIndex: UInt = 0u,
    ) = ProjectView(
        id = id,
        engineId = engineId,
        name = "Project $id",
        path = "C:\\repo\\$id",
        colorIndex = colorIndex,
        deviceId = deviceId,
        deviceName = deviceName,
        deviceOnline = online,
        gitDetected = git,
        createdAtMs = 5L,
        groupKey = groupKey,
        groupName = groupName,
        indicator = ChatIndicator.IDLE,
        unseenCount = 2u,
        sessions = emptyList(),
    )

    private fun device(
        id: String,
        engineId: String = "E1",
        name: String = "Device $id",
        executionHost: Boolean = true,
        online: Boolean = true,
    ) = DeviceView(
        id = id,
        engineId = engineId,
        name = name,
        platform = "windows",
        online = online,
        lastSeenMs = null,
        version = null,
        capabilities = emptyList(),
        isExecutionHost = executionHost,
        isSelf = false,
        sessionCount = 0u,
    )

    private fun snapshotOf(projects: List<ProjectView>, devices: List<DeviceView>) = WorkspaceSnapshot(
        revision = 7uL,
        computedAtMs = 0L,
        pinsReady = true,
        synced = true,
        front = FrontPage(pinned = emptyList(), sections = emptyList(), recent = emptyList(), groups = emptyList()),
        projects = projects,
        projectless = emptyList(),
        pullRequests = PullRequestGroups(open = emptyList(), merged = emptyList(), closed = emptyList()),
        archived = emptyList(),
        devices = devices,
    )

    @Test
    fun projectsMapInSnapshotOrderWithEveryField() {
        val s = snapshotOf(
            projects = listOf(
                project("a", deviceId = "dev1", deviceName = "Studio", groupKey = "repo:r", groupName = "r", colorIndex = 3u),
                project("b", deviceId = "dev2", deviceName = null, online = false, git = false),
            ),
            devices = listOf(device("dev1", name = "Studio"), device("dev2", name = "MBP")),
        )
        val o = s.toNewSessionOptions()
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
        val known = snapshotOf(
            projects = listOf(project("a", deviceId = "devX", deviceName = null)),
            devices = listOf(device("devX", name = "Engine machine")),
        ).toNewSessionOptions()
        assertEquals("Engine machine", known.projects[0].deviceName)
        // Unknown device id → the iOS literal fallback.
        val unknown = snapshotOf(
            projects = listOf(project("a", deviceId = "gone", deviceName = null)),
            devices = emptyList(),
        ).toNewSessionOptions()
        assertEquals("Unknown device", unknown.projects[0].deviceName)
    }

    @Test
    fun hostsAreTheExecutionDevicesInSnapshotOrder() {
        val o = snapshotOf(
            projects = emptyList(),
            devices = listOf(
                device("dev1", name = "mac studio", executionHost = true, online = true),
                device("dev2", engineId = "E2", name = "hetzner-01", executionHost = true, online = false),
                device("phone", engineId = "E3", name = "This phone", executionHost = false, online = true),
            ),
        ).toNewSessionOptions()
        assertEquals(listOf("dev1", "dev2"), o.hosts.map { it.id })
        assertEquals(listOf("mac studio", "hetzner-01"), o.hosts.map { it.name })
        assertEquals(listOf(true, false), o.hosts.map { it.online }) // offline engines included
        assertTrue(o.projects.isEmpty())
    }
}
