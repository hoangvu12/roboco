package sh.roboco.android.sessions

import androidx.compose.runtime.Immutable
import sh.roboco.android.design.PrState
import sh.roboco.android.design.prBadgeA11yLabel
import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.SendState
import uniffi.roboco_core.SessionRow

/**
 * One session row's view model — the port of `SessionRowVM`
 * (`ios:Threads/SessionCell.swift:3-32`). Every list (Sessions front page,
 * folders, search, the tablet sidebar) draws the same two-line row from
 * this one record; [SessionRow] is its composable.
 *
 * [Status], [pr] and the rest map 1:1 from the FFI [SessionRow] by
 * [from] (`AppModel.vm`, `ios:App/AppModel.swift:450-474`). The FFI's
 * `engineId`/`engineOnline` additions (android-app#07) are deliberately
 * **not** carried: this stays a 1:1 port of the iOS view model, and the
 * tickets that need the owning engine (42 row actions, 50 offline
 * indication) look the FFI row up by id. A later ticket that must add a
 * field appends it with a default so this ticket's fixtures keep compiling.
 * The composable that draws it is [sh.roboco.android.sessions.SessionRow].
 */
@Immutable
data class SessionRowVM(
    val id: String,
    val title: String,
    val projectName: String,
    /** False for project-less sessions (the tile reads "H" for Home, like desktop). */
    val hasProject: Boolean,
    val colorIndex: Int,
    val harness: String?,
    val branch: String?,
    val pr: PrState?,
    val prNumber: ULong?,
    val status: Status,
    val timeLabel: String,
    val unseen: Boolean,
    val pinned: Boolean,
    val sendFailed: Boolean,
    /**
     * android-next #06 (upstream #899's `runningSubagents`): agents running
     * under this session right now — its open transcript's running
     * subagents plus its working side chats. The "● N" pill.
     */
    val runningSubagents: Int = 0,
) {
    /**
     * `SessionRowVM.Status` (`SessionCell.swift:4-6`). Not the FFI enum:
     * [from] maps [ChatIndicator] into it via [statusOf].
     */
    enum class Status { Idle, Completed, Working, Awaiting, Errored }

    companion object {
        /**
         * The FFI row → view model (`AppModel.vm`, `AppModel.swift:450-474`).
         * Pure and cheap enough for any thread.
         *
         * [homeColorIndex] defaults to the core's `projectColorIndex("home")`
         * (teal 6), resolved once. Plain-JVM tests must pass `6` explicitly:
         * the lazy default calls into the native core.
         */
        fun from(r: SessionRow, homeColorIndex: Int = HomeColorIndex.value): SessionRowVM =
            SessionRowVM(
                id = r.id,
                title = r.title,
                projectName = r.project?.name ?: r.deviceName ?: "No project",
                hasProject = r.project != null,
                // Project-less sessions tone like the desktop's "home" tile.
                colorIndex = r.project?.colorIndex?.toInt() ?: homeColorIndex,
                harness = r.harness,
                branch = r.branch,
                pr = r.pullRequest?.let { PrState.from(it.state) },
                prNumber = r.pullRequest?.number,
                // The staleness-gated display status, never hostIndicator:
                // a local in-flight send reads Working (types.rs:490-493).
                status = statusOf(r.indicator),
                timeLabel = r.timeLabel,
                unseen = r.unseen,
                pinned = r.pinned,
                sendFailed = r.sendState == SendState.FAILED,
                runningSubagents = r.runningSubagents.toInt(),
            )
    }
}

/**
 * `AppModel.status` (`AppModel.swift:440-448`). Exhaustive on purpose: a
 * new FFI case fails to compile instead of silently reading Idle.
 */
fun statusOf(i: ChatIndicator): SessionRowVM.Status = when (i) {
    ChatIndicator.WORKING -> SessionRowVM.Status.Working
    ChatIndicator.AWAITING_INPUT -> SessionRowVM.Status.Awaiting
    ChatIndicator.ERRORED -> SessionRowVM.Status.Errored
    ChatIndicator.COMPLETED -> SessionRowVM.Status.Completed
    ChatIndicator.IDLE -> SessionRowVM.Status.Idle
}

/**
 * `projectColorIndex("home")` — the 32-bit FNV-1a of the UTF-8 bytes of
 * `"home"` (3536372366) mod 8 = 6 (teal) — resolved once. Touches the
 * native core; never read from a plain-JVM test (pass `6` instead).
 */
object HomeColorIndex {
    val value: Int by lazy { uniffi.roboco_core.projectColorIndex("home").toInt() }
}

/**
 * The row's accessibility label — `SessionCell.swift:152`: title, project
 * name, the corner word (never the time label), the running agents
 * ("2 subagents running", android-next #06), and the PR badge's label only
 * when both `pr` and `prNumber` are non-nil.
 */
fun sessionRowA11yLabel(vm: SessionRowVM): String {
    val pr = vm.pr
    val number = vm.prNumber
    return listOfNotNull(
        vm.title,
        vm.projectName,
        corner(vm)?.word,
        runningAgentsLabel(vm.runningSubagents),
        if (pr != null && number != null) prBadgeA11yLabel(number, pr) else null,
    ).joinToString(", ")
}

/** The "● N" pill's spoken label (upstream #899's `SessionCell`); `null` at 0. */
fun runningAgentsLabel(running: Int): String? = when {
    running <= 0 -> null
    running == 1 -> "1 subagent running"
    else -> "$running subagents running"
}
