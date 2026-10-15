package sh.roboco.android.sidechats

import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.roboco.android.design.Toaster
import uniffi.roboco_core.CoreException

/** The fork's failure copy, verbatim from upstream #899's `AppModel.forkSideChat`. */
object SideChatForkCopy {
    const val Failed = "Couldn't fork this session."
    const val Unsupported = "This engine doesn't support side chats. Update it to fork."

    fun unreachable(reason: String): String = "The host is unreachable ($reason)."
}

/**
 * The toast for a failed fork (iOS: the host's reason for a `HostError` —
 * "nothing to fork yet" when no reply has completed —, "The host is
 * unreachable (…)" offline, else the generic line). Pure.
 */
fun forkErrorText(error: Throwable): String = when (error) {
    is CoreException.HostException -> error.reason.ifBlank { SideChatForkCopy.Failed }
    is CoreException.HostUnavailable -> SideChatForkCopy.unreachable(error.reason)
    is CoreException.Unsupported -> SideChatForkCopy.Unsupported
    else -> SideChatForkCopy.Failed
}

/**
 * Fork into Side Chat (android-next #06, upstream #899's `forkSideChat`):
 * the engine copies the session's history through its last completed
 * reply into a side chat on its host (`ForkSideChat`; from a side chat, a
 * sibling), and [onForked] opens it. One fork at a time, like the
 * desktop's; [forking] is snapshot state for the ⋯ row's disabled flag.
 * A failure toasts [forkErrorText].
 *
 * [fork] is `CoreClient.forkSideChat` (a seam: the generated client is a
 * JNA object tests cannot build); it runs on [scope], which must outlive
 * the screen (the app scope), so a fork tapped just before Back still
 * lands — [onForked] then decides whether to open it.
 */
@Stable
class SideChatFork(
    private val fork: suspend (sourceId: String) -> String,
    private val scope: CoroutineScope,
    private val toaster: Toaster,
    private val logW: (String, Throwable?) -> Unit = { message, error -> Log.w("RobocoSideChats", message, error) },
) {
    var forking: Boolean by mutableStateOf(false)
        private set

    /** Main thread. A second call while one runs is ignored. */
    fun start(sourceId: String, onForked: (forkId: String) -> Unit) {
        if (forking) return
        forking = true
        scope.launch {
            val forkId = try {
                fork(sourceId)
            } catch (cancelled: CancellationException) {
                forking = false
                throw cancelled
            } catch (t: Throwable) {
                logW("fork side chat failed", t)
                forking = false
                toaster.show(forkErrorText(t))
                return@launch
            }
            forking = false
            onForked(forkId)
        }
    }
}
