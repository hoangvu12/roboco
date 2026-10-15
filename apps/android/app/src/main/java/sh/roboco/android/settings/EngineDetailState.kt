package sh.roboco.android.settings

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import sh.roboco.android.design.ActionSheetModel
import sh.roboco.android.design.ActionSheetAction
import sh.roboco.android.design.ActionSheetPresenter
import sh.roboco.android.design.ActionSheetStyle
import sh.roboco.android.design.AlertButton
import sh.roboco.android.design.AlertButtonRole
import sh.roboco.android.design.AlertField
import sh.roboco.android.design.Alerts
import sh.roboco.android.design.RobocoAlert
import sh.roboco.android.design.Toaster
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.EngineView
import uniffi.roboco_core.WorkspaceSnapshot

/** The forget flow's phases: `Idle`, or the sequence is running (taps ignored). */
enum class ForgetPhase { Idle, Forgetting }

/**
 * The drafts cleanup at the end of a successful forget: ticket 84's
 * `Drafts.forgetEngine` (every `chat:<engineId>:*` draft, plus the
 * later-saves no-op) and ticket 92's `NewSessionDraftStore.forgetEngine`
 * (strips the engine's ids, or resets the whole draft when it was the
 * last engine). Runs on the main thread after `removeStored`; never
 * blocks (both return jobs / write on the app scope).
 */
fun interface ForgetDrafts {
    fun forget(engineId: String, context: ForgetDraftsContext)
}

/** android.util.Log seam: the plain-JVM state test swaps this out. */
internal var engineDetailLogW: (String) -> Unit = { message ->
    android.util.Log.w("EngineDetail", message)
}

/**
 * The Engine detail page's behaviour (android-ui-parity #140): the
 * Device ID copy timer, the Rename alert + submit, and the Forget
 * confirm sheet + sequence. Pure drawing lives in
 * [EngineDetailCards][EngineDetailCards]; this class owns nothing
 * composable but the two snapshot states ([copied], [forgetPhase]).
 *
 * Scopes (the ticket's pitfall): [uiScope] is the page's
 * `rememberCoroutineScope()` and carries **only** the 1.5 s Copied timer
 * — everything FFI runs on [workScope], the application scope
 * (`RobocoApplication.appScope` + `Dispatchers.Main.immediate`), so a
 * pop or the last-engine root swap never cancels a rename or forget
 * half-way. The FFI hops go through [io] (`Dispatchers.IO`).
 *
 * The confirm sheet belongs to 19's root host, so the engine id and view
 * are captured at tap time and the sequence still runs after the page
 * itself has popped; `requestPop` is idempotent (the screen's guard).
 */
@Stable
class EngineDetailState(
    val engineId: String,
    private val backend: EngineDetailBackend,
    private val uiScope: CoroutineScope,
    private val workScope: CoroutineScope,
    private val toaster: Toaster,
    private val alerts: Alerts,
    private val sheets: ActionSheetPresenter,
    private val requestPop: () -> Unit,
    /** Tickets 84/92's cleanup; the default no-op keeps tests light. */
    private val forgetDrafts: ForgetDrafts = ForgetDrafts { _, _ -> },
    /** Read at Forget-confirm time, before any FFI call. The default
     * (a null snapshot, no engines left) exists for tests that wire a
     * [forgetDrafts] fake without a context — production always passes
     * the real provider (the live workspace snapshot and the engines-left
     * count), so the "last engine → full reset" reading of `enginesLeft =
     * 0` never fires by accident. */
    private val forgetContext: () -> ForgetDraftsContext = { ForgetDraftsContext(null, 0) },
    /** Ticket 28: an engine's notifications go when it is forgotten
     * (production: `Notifier.cancelEngine`). Runs once `forget` succeeded;
     * the default no-op keeps tests light. */
    private val onEngineForgotten: (engineId: String) -> Unit = {},
    private val io: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) {
    private var copiedHold: Job? = null

    /** The Device ID row's "Copied" state: true for 1.5 s after a tap. */
    var copied: Boolean by mutableStateOf(false)
        private set

    /** The forget flow's phase; `Forgetting` ignores taps on the row. */
    var forgetPhase: ForgetPhase by mutableStateOf(ForgetPhase.Idle)
        private set

    /**
     * The Device ID tap: copies the **raw** id (the caller's `copy` wraps
     * the clipboard in `runCatching`), fires the light haptic, flips the
     * value to `Copied` at once and restarts the 1 500 ms hold (the web
     * clears the old timer — `settings-devices.tsx:199-206`).
     */
    fun onCopyId(fullId: String, copy: (String) -> Unit, haptic: () -> Unit) {
        copy(fullId)
        haptic()
        copied = true
        copiedHold?.cancel()
        copiedHold = uiScope.launch {
            delay(EngineDetailTimings.CopiedHoldMs)
            copied = false
        }
    }

    /**
     * The "Rename…" tap: not connected → the toast explains why (the row
     * keeps its disabled look but stays tappable, `VoiceViewController`
     * precedent); connected → ticket 18's alert (title, pre-filled field
     * with the caret at the end, placeholder, Cancel + Rename). The
     * dialog is closed for input before [submitRename] can run (18's
     * resolve-then-call order), so a double tap cannot rename twice.
     */
    fun onRename(view: EngineView) {
        if (view.state !is EngineState.Connected) {
            toaster.show(EngineDetailCopy.NotConnected)
            return
        }
        alerts.show(
            RobocoAlert(
                title = EngineDetailCopy.RenameTitle,
                field = AlertField(
                    text = view.name.trim().ifEmpty { view.host },
                    placeholder = EngineDetailCopy.RenamePlaceholder,
                ),
                buttons = listOf(
                    AlertButton(EngineDetailCopy.Cancel, AlertButtonRole.Cancel),
                    AlertButton(EngineDetailCopy.RenameButton, AlertButtonRole.Default) { text ->
                        submitRename(text)
                    },
                ),
            ),
        )
    }

    /**
     * The alert's Rename button: the web's "dialog closes first" rule —
     * a blank name (trimmed) silently does nothing, otherwise the rename
     * runs on the application scope and failures toast
     * ([renameErrorText]); success stays silent (the engine echoes the
     * new name and the title updates by itself — no optimistic change).
     */
    internal fun submitRename(text: String) {
        val name = text.trim()
        if (name.isEmpty()) return
        workScope.launch {
            try {
                withContext(io) { backend.rename(engineId, name) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                toaster.show(renameErrorText(error))
            }
        }
    }

    /**
     * The Forget tap (decision SHELL-D1): ticket 19's action sheet —
     * "Forget <name>?" with one destructive Forget action; Cancel, the
     * scrim, Back and Escape do nothing (19's defaults). A tap while the
     * sequence is running is ignored.
     */
    fun onForget(view: EngineView) {
        if (forgetPhase != ForgetPhase.Idle) return
        sheets.show(
            ActionSheetModel(
                title = EngineDetailCopy.forgetTitle(view.name.trim().ifEmpty { view.host }),
                actions = listOf(
                    ActionSheetAction(EngineDetailCopy.Forget, ActionSheetStyle.Destructive) {
                        confirmForget(view)
                    },
                ),
            ),
        )
    }

    /**
     * The sheet's Forget: captures the view and the drafts context
     * (before any FFI call), then runs the sequence on the application
     * scope. 19 runs `onSelect` after the sheet has dismissed, so the
     * sheet is already gone when this starts.
     */
    internal fun confirmForget(view: EngineView) {
        if (forgetPhase != ForgetPhase.Idle) return
        forgetPhase = ForgetPhase.Forgetting
        val wasConnected = view.state is EngineState.Connected
        val context = forgetContext()
        workScope.launch {
            forgetSequence(wasConnected = wasConnected, context = context)
        }
    }

    /**
     * The forget sequence: best-effort revoke (connected only, capped at
     * 3 s, every failure logged and ignored), then `forgetEngine` —
     * whose failure toasts "Could not forget engine", resets the phase
     * and stops (no store removal, no pop). After it succeeds: the
     * stored record (a `CredentialStoreException` is logged only — the
     * record comes back on next launch), the drafts cleanup (84/92), one
     * `requestPop`, and the success log. `forgetEngine` emits
     * `EnginesChanged`, so the engine can already be gone from the flow
     * by then — the page keeps drawing its last view while popping.
     */
    private suspend fun forgetSequence(wasConnected: Boolean, context: ForgetDraftsContext) {
        if (wasConnected) {
            val timedOut = withTimeoutOrNull(EngineDetailTimings.RevokeTimeoutMs) {
                try {
                    withContext(io) { backend.revokeOwnSession(engineId) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    engineDetailLogW(
                        "engine revoke skipped engine=$engineId cause=${error.javaClass.simpleName}",
                    )
                }
            } == null
            if (timedOut) {
                engineDetailLogW("engine revoke skipped engine=$engineId cause=timeout")
            }
        }
        try {
            withContext(io) { backend.forget(engineId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            toaster.show(EngineDetailCopy.ForgetFailed)
            forgetPhase = ForgetPhase.Idle
            engineDetailLogW("engine forget failed engine=$engineId cause=${error.javaClass.simpleName}")
            return
        }
        // Ticket 28: all of the engine's notifications go with it (its
        // chats' and its link-lost one), whatever the store does next.
        runCatching { onEngineForgotten(engineId) }
            .onFailure { error ->
                engineDetailLogW("engine notifications cancel failed engine=$engineId cause=${error.javaClass.simpleName}")
            }
        runCatching { withContext(io) { backend.removeStored(engineId) } }
            .onFailure { error ->
                // 71's caller contract: the record comes back on next
                // launch; never fatal, never a toast.
                engineDetailLogW(
                    "engine store remove failed engine=$engineId cause=${error.javaClass.simpleName}",
                )
            }
        forgetDrafts.forget(engineId, context)
        requestPop()
        engineDetailLogW("engine forgotten engine=$engineId")
    }
}

/**
 * The rename failure toast, the ticket's error table: `HostUnavailable`
 * is the "not connected" wording; other core errors print their
 * `reason`, or the class name when blank (`Closed` carries none); plain
 * throwables print their message or class name. The `when` over
 * [CoreException] is exhaustive per-variant, so a new variant is a
 * compile error.
 */
internal fun renameErrorText(error: Throwable): String = when (error) {
    is CoreException.HostUnavailable -> EngineDetailCopy.NotConnected
    is CoreException -> {
        val reason = coreExceptionReason(error)?.trim()
        if (reason.isNullOrEmpty()) {
            EngineDetailCopy.RenameFailedPrefix + error.javaClass.simpleName
        } else {
            EngineDetailCopy.RenameFailedPrefix + reason
        }
    }
    else -> EngineDetailCopy.RenameFailedPrefix + (error.message ?: error.javaClass.simpleName)
}

/** [CoreException]'s reason field, variant by variant (`Closed` has none). */
private fun coreExceptionReason(error: CoreException): String? = when (error) {
    is CoreException.NotFound -> error.reason
    is CoreException.InvalidArgument -> error.reason
    is CoreException.HostUnavailable -> error.reason
    is CoreException.Unsupported -> error.reason
    is CoreException.HostException -> error.reason
    is CoreException.Network -> error.reason
    is CoreException.Auth -> error.reason
    is CoreException.Storage -> error.reason
    is CoreException.NotImplemented -> error.reason
    is CoreException.Closed -> null
    is CoreException.Internal -> error.reason
}

/**
 * The [ForgetDrafts] context (the pre-forget workspace snapshot and the
 * engines left after the forget), as its own type so the state's
 * constructor default stays a plain no-op lambda.
 */
@Immutable
data class ForgetDraftsContext(
    val snapshot: WorkspaceSnapshot?,
    val enginesLeft: Int,
)
