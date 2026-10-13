package sh.roboco.android.newsession

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.roboco.android.design.AlertButton
import sh.roboco.android.design.AlertButtonRole
import sh.roboco.android.design.AlertField
import sh.roboco.android.design.Alerts
import sh.roboco.android.design.LocalAlerts
import sh.roboco.android.design.RobocoAlert
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.FolderListing

/**
 * The bottom bar's copy (android-ui-parity #111), verbatim from
 * `pr685:apps/ios/Zeron/Threads/NewProjectViewController.swift` (ASCII
 * apostrophes, U+2026, U+201C/U+201D — see the ticket's copy table).
 */
internal object FolderWriteCopy {

    /** `status.text = "Creating folder…"`. */
    const val CreatingFolder = "Creating folder…"

    /** `Roboco is disconnected.` — iOS's `Zeron is disconnected.` under the rename rule. */
    const val Disconnected = "Roboco is disconnected."

    /** The engine's file-vs-folder message, matched exactly (space_paths.rs:36). */
    const val IsAFile = "the path is a file, not a folder"

    const val AlertTitle = "New Folder"

    /** `Create a folder in \(path) on \(device.name).` */
    fun alertMessage(path: String, engineName: String) = "Create a folder in $path on $engineName."

    const val FieldPlaceholder = "Folder name"
    const val Cancel = "Cancel"
    const val Create = "Create"

    /** `Couldn't create the project on \(device.name).` — the single status line for every create failure. */
    fun createFailed(engineName: String) = "Couldn't create the project on $engineName."

    /** `ErrorKind::AlreadyExists` → the re-prompt message. */
    const val AlreadyExists = "Couldn't create the folder. A file or folder with that name already exists."

    /** The generic prefix every other engine error follows. */
    const val FailedPrefix = "Couldn't create the folder. "
}

/**
 * The New Project write actions (android-ui-parity #111): the Use tap
 * (create project) and the New Folder… prompt (client validation, the
 * `PrepareSpacePath` probe + create, browse into the result) over 110's
 * [FolderBrowserController], plus the shared `submitting` guard every
 * control on the sheet reads. The port of
 * `pr685:apps/ios/Zeron/Threads/NewProjectViewController.swift:162-253`.
 *
 * All mutators run on the main thread; each write runs as a coroutine in
 * [scope] (the screen's `rememberCoroutineScope`), so a reply that lands
 * after the screen is disposed dies with the scope. The core is reached
 * only through [ProjectWriteApi], host-path arithmetic only through
 * 110's [HostPathOps], alerts only through [Alerts] (ticket 18).
 *
 * Ticket 112 reuses the two seams this class exposes:
 * [setSubmitting] and [createProjectAt] (which never touch `submitting`
 * themselves — 112 brackets its own call with the flag).
 */
@Stable
class FolderBrowserActions(
    private val c: FolderBrowserController,
    private val paths: HostPathOps,
    private val api: ProjectWriteApi,
    private val alerts: Alerts,
    private val scope: CoroutineScope,
    private val onCreated: (scopedSpaceId: String) -> Unit,
    private val dismissSheet: () -> Unit,
) {

    /**
     * True while the Use action's create is in flight: ticket 08's busy
     * look (spinner, clicks ignored) while the enabled look stays on —
     * iOS disables the button too, but the disabled fill would hide the
     * spinner (see the ticket's ADAPT "busy Use").
     */
    var creatingProject by mutableStateOf(false)
        private set

    /** 110's flag, mirrored for callers that hold only the actions. */
    val submitting: Boolean get() = c.submitting

    /**
     * `Use “<name>”` — the listing's last path component between U+201C
     * and U+201D, or `""` before the first listing (iOS's titleless
     * capsule). 110 replaces `listing` only when a load is applied, so a
     * failed or running load keeps the last title, as iOS does.
     */
    val useTitle: String by derivedStateOf {
        val listing = c.listing ?: return@derivedStateOf ""
        "Use \u201C${paths.name(listing.path)}\u201D"
    }

    /**
     * The shared act guard — iOS's `useButton.isEnabled` rule plus the
     * write guard: a usable listing from the selected engine and nothing
     * in flight. Gates Use (except while busy), New Folder, and the
     * re-prompt.
     */
    val canAct: Boolean get() = c.listingUsable && !c.submitting

    /**
     * The shared submitting guard (112 calls this too): flips 110's
     * [FolderBrowserController.submitting]; ✕, the engine picker, the
     * rows (110's tap guard) and the sheet's dismissability all derive
     * from it.
     */
    fun setSubmitting(busy: Boolean) {
        c.submitting = busy
    }

    /**
     * The Use tap (`create()`, `pr685:…:239-253`): guard, capture the
     * listed path and the git hint, then [createProjectAt] inside the
     * busy look. Whatever the result, the flags reset afterwards — the
     * success path has already dismissed the sheet, and a programmatic
     * dismiss ignores the block.
     */
    fun createProject() {
        if (!canAct) return
        val listing = c.listing ?: return
        val path = listing.path
        val git = gitDetected(listing)
        creatingProject = true
        setSubmitting(true)
        scope.launch {
            try {
                createProjectAt(path, git)
            } finally {
                creatingProject = false
                setSubmitting(false)
            }
        }
    }

    /**
     * The create body without the entry guard and without touching
     * `submitting` — 112's "Create and add" calls it between its own
     * `setSubmitting(true/false)`. Returns whether the project was
     * created; a failure logs (never shows) the exception and sets the
     * single status line.
     */
    suspend fun createProjectAt(path: String, gitDetected: Boolean): Boolean {
        val engine = c.engine ?: return false
        val id = try {
            api.createProject(engine.id, path, gitDetected)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "create project failed", e)
            null
        }
        if (id == null) {
            c.status = FolderWriteCopy.createFailed(engine.name)
            return false
        }
        // iOS :248-251: the draft updates first (113's closure, still
        // under this sheet), then the sheet dismisses.
        onCreated(id)
        dismissSheet()
        return true
    }

    /**
     * `gitDetected`: the listing's own `.git` entry (iOS's only check)
     * OR-ed with 110's [FolderBrowserController.listingIsRepo] — the
     * `isRepo` of the row that reached this folder, because Roboco
     * listings hide dotfiles. The engine re-verifies either way.
     */
    private fun gitDetected(listing: FolderListing): Boolean =
        listing.entries.any { it.name == ".git" } || c.listingIsRepo == true

    /**
     * The New Folder… prompt (`promptForFolder`, `pr685:…:177-216`): a
     * ticket-18 alert with the captured engine and folder path baked
     * into the Create handler (iOS captures them when the prompt opens;
     * 18's queue replaces the dismiss-then-present dance). The
     * re-prompt carries the trimmed name and the error as the message.
     */
    fun promptForFolder(name: String = "", error: String? = null) {
        if (!canAct) return
        val engine = c.engine ?: return
        val listing = c.listing ?: return
        val path = listing.path
        alerts.show(
            RobocoAlert(
                title = FolderWriteCopy.AlertTitle,
                message = error ?: FolderWriteCopy.alertMessage(path, engine.name),
                field = AlertField(
                    text = name,
                    placeholder = FolderWriteCopy.FieldPlaceholder,
                    capitalization = KeyboardCapitalization.None,
                    autocorrect = false,
                ),
                buttons = listOf(
                    AlertButton(FolderWriteCopy.Cancel, AlertButtonRole.Cancel),
                    AlertButton(FolderWriteCopy.Create, AlertButtonRole.Default, requiresText = true) { raw ->
                        submitFolder(raw, engine, path)
                    },
                ),
            )
        )
    }

    /**
     * The alert's Create action: client validation, the submit re-check,
     * then the probe → create → browse flow in [scope]. `setSubmitting`
     * is reset **before** any re-prompt — the prompt's guard needs a
     * quiet controller, exactly as iOS does.
     */
    internal fun submitFolder(raw: String, engine: HostOption, path: String) {
        val check = FolderNameRules.check(raw)
        val name = when (check) {
            FolderNameCheck.Empty -> return // unreachable through the alert (Create is disabled)
            is FolderNameCheck.Invalid -> {
                promptForFolder(check.trimmed, FolderNameRules.INVALID)
                return
            }
            is FolderNameCheck.Valid -> check.name
        }
        // The world may have moved while the alert was up: same engine,
        // same folder, nothing in flight — else the tap dies silently.
        if (!canAct || c.engine?.id != engine.id || c.listing?.path != path) return
        setSubmitting(true)
        c.status = FolderWriteCopy.CreatingFolder
        // Always a child of the listed folder, never the raw typed name:
        // the engine would treat `~x` or `C:x` differently.
        val target = paths.join(path, name)
        scope.launch {
            try {
                val probe = api.prepareSpacePath(engine.id, target, createIfMissing = false)
                if (probe.exists) {
                    setSubmitting(false)
                    c.status = path
                    promptForFolder(name, FolderWriteCopy.AlreadyExists)
                    return@launch
                }
                val made = api.prepareSpacePath(engine.id, target, createIfMissing = true)
                setSubmitting(false)
                // Browse into it: 110's load shows Loading…, then the new
                // folder with the row's own git hint for a later Use.
                c.load(made.path, isRepoHint = made.gitDetected)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setSubmitting(false)
                c.status = path
                promptForFolder(name, folderError(e))
            }
        }
    }

    /**
     * The re-prompt message for a failed create: the engine's
     * file-vs-folder error maps to the `AlreadyExists` copy (a `create_dir`
     * would have failed the same way); everything else follows the
     * generic prefix. `folderErrorMessage` never uses `CoreException`'s
     * own `message` (UniFFI formats it `reason=…`).
     */
    internal fun folderError(e: Throwable): String {
        val message = folderErrorMessage(e)
        return if (message == FolderWriteCopy.IsAFile) {
            FolderWriteCopy.AlreadyExists
        } else {
            FolderWriteCopy.FailedPrefix + message
        }
    }
}

private const val LOG_TAG = "NewProject"

/**
 * The engine error text: every `CoreException` variant's `reason`
 * (android-app#10's rename of the `message` field; UniFFI appends
 * `Exception` to variant names ending in `Error`, so `HostError` is
 * `HostException`), `Roboco is disconnected.` for a closed client, and
 * the platform message otherwise.
 */
internal fun folderErrorMessage(e: Throwable): String = when (e) {
    is CoreException.Closed -> FolderWriteCopy.Disconnected
    is CoreException.NotFound -> e.reason
    is CoreException.InvalidArgument -> e.reason
    is CoreException.HostUnavailable -> e.reason
    is CoreException.Unsupported -> e.reason
    is CoreException.HostException -> e.reason
    is CoreException.Network -> e.reason
    is CoreException.Auth -> e.reason
    is CoreException.Storage -> e.reason
    is CoreException.NotImplemented -> e.reason
    is CoreException.Internal -> e.reason
    else -> e.message ?: e.toString()
}

/**
 * The screen's [FolderBrowserActions]: the alerts come from
 * [LocalAlerts.current] and the scope from `rememberCoroutineScope()`,
 * so a write left pending when the screen goes away is cancelled with it.
 */
@Composable
fun rememberFolderBrowserActions(
    controller: FolderBrowserController,
    paths: HostPathOps,
    api: ProjectWriteApi,
    onCreated: (String) -> Unit,
    dismissSheet: () -> Unit,
): FolderBrowserActions {
    val alerts = LocalAlerts.current
    val scope = rememberCoroutineScope()
    return remember(controller) {
        FolderBrowserActions(controller, paths, api, alerts, scope, onCreated, dismissSheet)
    }
}
