package sh.roboco.android.newsession

import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.DeviceView
import uniffi.roboco_core.FrontPage
import uniffi.roboco_core.ProjectView
import uniffi.roboco_core.PullRequestGroups
import uniffi.roboco_core.WorkspaceSnapshot

/**
 * UniFFI record fixtures shared by the #92 tests that need a
 * [WorkspaceSnapshot] (`NewSessionOptionsTest` for the options mapping,
 * `NewSessionDraftStoreTest` for the engine-id forget overload) — the
 * same role `sessions/WorkspaceFixtures.kt` plays for ticket 40's tests.
 * Only the fields the tickets read are parameterised; everything else is
 * a neutral default. Plain JVM: the generated records construct without
 * JNA.
 */

/** A [ProjectView] with only the mapping-relevant fields set apart. */
internal fun recordProject(
    id: String,
    engineId: String = "E1",
    deviceId: String = "dev1",
    deviceName: String? = null,
    online: Boolean = true,
    git: Boolean = true,
    groupKey: String = "repo:$id",
    groupName: String = "group $id",
    colorIndex: UInt = 0u,
    name: String = "Project $id",
    path: String = "C:\\repo\\$id",
): ProjectView = ProjectView(
    id = id,
    engineId = engineId,
    name = name,
    path = path,
    colorIndex = colorIndex,
    deviceId = deviceId,
    deviceName = deviceName,
    deviceOnline = online,
    gitDetected = git,
    createdAtMs = 0L,
    groupKey = groupKey,
    groupName = groupName,
    indicator = ChatIndicator.IDLE,
    unseenCount = 0u,
    sessions = emptyList(),
)

/** A [DeviceView] with only the mapping-relevant fields set apart. */
internal fun recordDevice(
    id: String,
    engineId: String = "E1",
    name: String = "Device $id",
    executionHost: Boolean = true,
    online: Boolean = true,
): DeviceView = DeviceView(
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

/** A workspace snapshot carrying only projects and devices. */
internal fun recordSnapshot(
    projects: List<ProjectView> = emptyList(),
    devices: List<DeviceView> = emptyList(),
): WorkspaceSnapshot = WorkspaceSnapshot(
    revision = 1uL,
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
