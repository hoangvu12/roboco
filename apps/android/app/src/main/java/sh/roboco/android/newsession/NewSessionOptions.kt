package sh.roboco.android.newsession

import uniffi.roboco_core.WorkspaceSnapshot

/**
 * The New Session / New Project option records (android-ui-parity #92,
 * `NewSessionOptions.kt`): the port of `ios:Session/NewSessionViewController.swift:3-44`
 * onto the merged `WorkspaceSnapshot` (android-app#07). #110 first
 * created the file as a stand-in because #92 had not landed when the
 * folder browser was built — the declarations follow #92's spec
 * verbatim, so the merge was a no-op and #92 keeps them here.
 *
 * [HostOption] is what #110's folder browser picks its engine from; #92's
 * draft model and #113's menus consume all three records. The phone is
 * never an execution host (`isExecutionHost == false`), so it never
 * appears in [NewSessionOptions.hosts] — one entry per paired engine.
 */

/** `ProjectOption`: one checkout of a project, from `WorkspaceSnapshot.projects`. */
data class ProjectOption(
    val id: String,
    val name: String,
    val path: String,
    val device: String,
    /** Display name of [device] (never show the id). */
    val deviceName: String,
    val online: Boolean,
    val git: Boolean,
    /** Shared by every checkout of one repository, on any device. */
    val groupKey: String,
    val groupName: String,
    val colorIndex: Int,
)

/** `HostOption`: one execution device — one paired engine (the phone can never be one). */
data class HostOption(val id: String, val name: String, val online: Boolean)

/** `ModelChoice`: one harness × model row of the model pickers. */
data class ModelChoice(
    val harness: String,
    val harnessLabel: String,
    val id: String,
    val label: String,
    val efforts: List<String>,
)

/** The options the New Session / New Project pickers offer. */
data class NewSessionOptions(
    val projects: List<ProjectOption>,
    val hosts: List<HostOption>,
)

/**
 * Derives the options from the merged snapshot (ticket 92's Options
 * table): projects in snapshot order, hosts = execution devices in
 * snapshot order (android-app#07 sorts execution hosts first, then
 * lowercase name, then id — so the order equals `executionDevices()`);
 * offline engines are included.
 */
fun WorkspaceSnapshot.toNewSessionOptions(): NewSessionOptions {
    fun deviceNameOf(id: String): String =
        devices.firstOrNull { it.id == id }?.name ?: "Unknown device"
    return NewSessionOptions(
        projects = projects.map { p ->
            ProjectOption(
                id = p.id,
                name = p.name,
                path = p.path,
                device = p.deviceId,
                deviceName = p.deviceName ?: deviceNameOf(p.deviceId),
                online = p.deviceOnline,
                git = p.gitDetected,
                groupKey = p.groupKey,
                groupName = p.groupName,
                colorIndex = p.colorIndex.toInt(),
            )
        },
        hosts = devices.filter { it.isExecutionHost }.map { d -> HostOption(d.id, d.name, d.online) },
    )
}
