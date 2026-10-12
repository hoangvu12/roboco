package sh.roboco.android.core

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sh.roboco.android.BuildConfig
import sh.roboco.android.pairing.DeviceLabel
import sh.roboco.android.pairing.PairedEngineStore
import uniffi.roboco_core.ClientEvent
import uniffi.roboco_core.ClientListener
import uniffi.roboco_core.CoreClient
import uniffi.roboco_core.CoreConfig
import uniffi.roboco_core.EngineView
import uniffi.roboco_core.PairedEngine

/** The startup pipeline's state; the splash (ticket 35) holds on `Loading`. */
sealed interface CoreStartState {
    data object Loading : CoreStartState
    data object Ready : CoreStartState
    data class Failed(val reason: String) : CoreStartState
}

/**
 * One consumer of the core's event stream. Called on a Rust runtime
 * thread — hop threads yourself and return fast (the #621 self-repost
 * trap is why the method is *not* named `onEvent`).
 */
fun interface ClientEventSink {
    fun onClientEvent(event: ClientEvent)
}

/**
 * The app's startup (ticket 71, the port of `AppModel.init`/`start`):
 * load the stored engines from [PairedEngineStore], build the single
 * [CoreClient], publish its engine views, then flip [state] to `Ready`.
 *
 * Also owns the app's single [ClientListener]: `EngineIdentityPinned`
 * events flow to `store.pin` through a channel, `EnginesChanged`
 * refreshes [engines] through a coalescing flow, and every event fans
 * out to the [ClientEventSink]s other tickets register (40/42/51) —
 * register them before [start] to see every event. `onEvent` itself only
 * does non-blocking `trySend`/`tryEmit` plus the fan-out, so it is safe
 * to call from the Rust callback thread while the client is still
 * constructing.
 */
class CoreStartup(
    private val context: Context,
    private val store: PairedEngineStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val newClient: (CoreConfig, List<PairedEngine>, ClientListener) -> CoreClient =
        { config, engines, listener -> CoreClient(config, engines, listener) },
    private val onClientReady: (CoreClient) -> Unit = {},
) {

    private val _state = MutableStateFlow<CoreStartState>(CoreStartState.Loading)
    val state: StateFlow<CoreStartState> = _state.asStateFlow()

    /** Null until `Ready`; then `engines()`, re-read on every `EnginesChanged`. */
    private val _engines = MutableStateFlow<List<EngineView>?>(null)
    val engines: StateFlow<List<EngineView>?> = _engines.asStateFlow()

    private val clientRef = AtomicReference<CoreClient?>(null)
    private val started = AtomicBoolean(false)
    private val sinks = CopyOnWriteArrayList<ClientEventSink>()

    private val pins = Channel<ClientEvent.EngineIdentityPinned>(Channel.UNLIMITED)
    private val enginesDirty = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** The listener handed to `CoreClient`; tests deliver events through it. */
    @VisibleForTesting
    internal val listener: ClientListener = object : ClientListener {
        override fun onEvent(event: ClientEvent) = routeClientEvent(event)
    }

    init {
        scope.launch(io) {
            for (pinned in pins) {
                store.pin(pinned.engineId, pinned.deviceId, pinned.name)
            }
        }
        scope.launch(compute) {
            enginesDirty.collect { clientRef.get()?.let { _engines.value = it.engines() } }
        }
    }

    /** Idempotent: the second call does nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(io) {
            try {
                // 1. Load the stored pairings before anything decides what
                //    to render — the pairing root must never flash first.
                val engines = store.load()
                // 2. The redeem label is read once per process.
                val dataDir = File(context.noBackupFilesDir, "core").apply { mkdirs() }
                val config = CoreConfig(
                    dataDir = dataDir.path,
                    deviceName = DeviceLabel.read(context),
                    platform = "android",
                    appVersion = BuildConfig.VERSION_NAME,
                )
                // 3. Never log `engines`; the constructor never dials.
                val client = try {
                    newClient(config, engines, listener)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    Log.e(TAG, "core start failed", error)
                    _state.value = CoreStartState.Failed(error.message ?: error.javaClass.simpleName)
                    return@launch
                }
                // 4. Order matters: engines published before Ready, so
                //    anyone who sees Ready sees a non-null list.
                clientRef.set(client)
                _engines.value = client.engines()
                _state.value = CoreStartState.Ready
                // 5.
                onClientReady(client)
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }

    /** The live client, or null before `Ready`/after a failed start. */
    fun clientOrNull(): CoreClient? = clientRef.get()

    /** Registers an event sink; do this before [start] to see every event. */
    fun addEventSink(sink: ClientEventSink) {
        sinks.add(sink)
    }

    private fun routeClientEvent(event: ClientEvent) {
        when (event) {
            is ClientEvent.EngineIdentityPinned -> pins.trySend(event)
            ClientEvent.EnginesChanged -> enginesDirty.tryEmit(Unit)
            else -> {}
        }
        for (sink in sinks) {
            try {
                sink.onClientEvent(event)
            } catch (error: Throwable) {
                Log.w(TAG, "client event sink failed", error)
            }
        }
    }

    private companion object {
        const val TAG = "Roboco"
    }
}
