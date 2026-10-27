package sh.roboco.android.browser

import androidx.compose.runtime.Stable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.PreviewPanelListener
import uniffi.roboco_core.PreviewPanelPhase
import uniffi.roboco_core.PreviewPanelState

/**
 * The preview panel's behaviour (agent-previews ticket 05) — the Kotlin
 * half of the core's per-chat `WatchPreviews` state machine: one
 * [PanelWatch] per open panel, whose state callback (a Rust runtime
 * thread) hops to [scope] and publishes into [state]. The Rust panel owns
 * the 7332 listener's watcher while it is open (lazy bind, idle
 * shutdown) — and because a NavStack keeps every entry composed, the
 * panel beneath the browser entry keeps the watcher held while a preview
 * is actually being viewed.
 *
 * `start()` is idempotent; [stop] releases the watch (the entry's close
 * path). An engine this client never paired refuses `watchPreviews` with
 * `NotFound` — the model degrades once to the honest note instead of an
 * error surface (the panel keeps no preview surface on unpaired engines
 * beyond it).
 *
 * Pure drawing lives in [PreviewPanelScreen]; this class owns nothing
 * composable but the state flow.
 */
@Stable
class PreviewPanelModel(
    val chatId: String,
    private val core: () -> PreviewsCore?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {

    private val _state = MutableStateFlow<PreviewPanelState?>(null)

    /** The latest panel state; `null` until the first answer (loading). */
    val state: StateFlow<PreviewPanelState?> = _state.asStateFlow()

    /** The watch in flight (null until `start` lands it, null after `stop`). */
    private var watch: PanelWatch? = null
    private var starting: Job? = null

    // The FFI callback: a Rust runtime thread. Only the hop and the
    // conflated publish happen here — never a blocking call back into
    // Rust (a re-entrant callback under a Rust lock deadlocks).
    private val listener = object : PreviewPanelListener {
        override fun onState(state: PreviewPanelState) {
            scope.launch { _state.value = state }
        }
    }

    /** Start (or keep) the chat's panel watch. Idempotent. */
    fun start() {
        if (watch != null || starting?.isActive == true) return
        starting = scope.launch(io) {
            val live = core() ?: return@launch
            try {
                watch = live.watch(chatId, listener)
            } catch (error: CoreException) {
                // An engine this client never paired (or a chat id that
                // resolves to none): no preview surface exists beyond the
                // honest note — degrade once, never retry-spam.
                _state.value = unpaired(error)
            }
        }
    }

    /** Release the watch: the state callback fires no more. Idempotent. */
    fun stop() {
        starting?.cancel()
        starting = null
        watch?.stop()
        watch = null
    }

    /** The "engine not paired" degradation: the reconnect phase with the note. */
    private fun unpaired(error: CoreException): PreviewPanelState =
        PreviewPanelState(
            phase = PreviewPanelPhase.RECONNECTING,
            engineId = "",
            services = emptyList(),
            error = describePreviewError(error),
        )
}

/** The FFI error's sentence (the generated variants carry `reason`). */
internal fun describePreviewError(error: CoreException): String = when (error) {
    is CoreException.NotFound -> error.reason
    is CoreException.InvalidArgument -> error.reason
    is CoreException.HostUnavailable -> error.reason
    is CoreException.Unsupported -> error.reason
    is CoreException.HostException -> error.reason
    is CoreException.Network -> error.reason
    is CoreException.Auth -> error.reason
    is CoreException.Storage -> error.reason
    is CoreException.NotImplemented -> error.reason
    is CoreException.Internal -> error.reason
    is CoreException.Closed -> "the Roboco core has not started yet"
}
