package sh.roboco.android.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.roboco_core.ClientEvent
import uniffi.roboco_core.Connectivity
import uniffi.roboco_core.CoreClient
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.EngineView
import uniffi.roboco_core.WorkspaceSnapshot

/**
 * The workspace FFI seam (ticket 40) — the four calls the feed, the fold
 * state and their tests need (`mob:client_ffi/workspace.rs`, android-app#07).
 * Every method is cheap, never blocks on the network and is safe from any
 * thread; [setSectionCollapsed] fails fast and never throws for a reason
 * that a fold retry could not survive.
 */
interface WorkspaceCore {
    /** Current workspace revision (compare before pulling). */
    fun workspaceRevision(): ULong

    /** The whole derived workspace. */
    fun workspace(): WorkspaceSnapshot

    /** Every paired engine, in pairing order. */
    fun engines(): List<EngineView>

    /** The aggregate connectivity posture (ticket 50 reads it). */
    fun connectivity(): Connectivity

    /** Sets a user section's engine-synced fold (optimistic overlay, background RPC). */
    @Throws(CoreException::class)
    fun setSectionCollapsed(sectionId: String, collapsed: Boolean)
}

/**
 * The [WorkspaceCore] over the live [CoreClient], resolved at call time:
 * `RobocoApplication` builds this over `clientOrNull()` (ticket 71) so the
 * feed and the fold state work from the moment `CoreStartup` flips to
 * `Ready`, without holding a client a failed start never produced. Before
 * `Ready` the calls refuse like an unreachable engine — nothing in the UI
 * can reach them that early (there is no snapshot to draw sections from).
 */
class CoreClientWorkspace(private val client: () -> CoreClient?) : WorkspaceCore {

    private fun live(): CoreClient =
        client() ?: throw CoreException.HostUnavailable("the Roboco core has not started yet")

    override fun workspaceRevision(): ULong = live().workspaceRevision()

    override fun workspace(): WorkspaceSnapshot = live().workspace()

    override fun engines(): List<EngineView> = live().engines()

    override fun connectivity(): Connectivity = live().connectivity()

    override fun setSectionCollapsed(sectionId: String, collapsed: Boolean) =
        live().setSectionCollapsed(sectionId, collapsed)
}

/**
 * The single Kotlin owner of the workspace snapshot (ticket 40 — the
 * settled feed-owner of this set): the `StateFlow<WorkspaceSnapshot?>` that
 * ticket 27 counts from, tickets 42/49/92/110 read, and ticket 40's model
 * builds the Sessions tab on.
 *
 * Re-reads are **revision-gated** (the core coalesces events into bursts
 * that carry only revisions): a `WorkspaceChanged{revision}` event triggers
 * a read only when the revision moved, the read itself publishes only when
 * the fetched revision differs, and bursts conflate to at most one pending
 * read per in-flight one — a burst of N events costs at most two
 * `workspace()` calls. Engine views refresh the same way on
 * `EnginesChanged`/`ConnectivityChanged`.
 *
 * The FFI never runs on the caller's thread: both drains run on one serial
 * lane over [io], and `onEvent` (called from a Rust runtime thread) only
 * does non-blocking `trySend`s.
 */
class WorkspaceFeed(
    private val core: () -> WorkspaceCore?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val serial = io.limitedParallelism(1)
    private val workspaceDirty = Channel<Unit>(Channel.CONFLATED)
    private val enginesDirty = Channel<Unit>(Channel.CONFLATED)

    private val _snapshot = MutableStateFlow<WorkspaceSnapshot?>(null)
    /** The merged workspace; null until the first read. */
    val snapshot: StateFlow<WorkspaceSnapshot?> = _snapshot.asStateFlow()

    private val _engines = MutableStateFlow<List<EngineView>>(emptyList())
    /** Every paired engine; empty until the first read. */
    val engines: StateFlow<List<EngineView>> = _engines.asStateFlow()

    private val _connectivity = MutableStateFlow<Connectivity?>(null)
    /** The aggregate connectivity posture; null until the first read (ticket 50). */
    val connectivity: StateFlow<Connectivity?> = _connectivity.asStateFlow()

    init {
        scope.launch(serial) {
            for (trigger in workspaceDirty) {
                val c = core() ?: continue // no client yet: start() re-triggers when Ready
                // Before Ready (or after a failed start) the live seam refuses
                // with HostUnavailable; the next trigger retries, so the lane
                // must outlive the refusal instead of failing the app scope.
                val ws = try { c.workspace() } catch (e: CoreException) { continue }
                if (ws.revision != _snapshot.value?.revision) _snapshot.value = ws
            }
        }
        scope.launch(serial) {
            for (trigger in enginesDirty) {
                val c = core() ?: continue
                val engineViews = try { c.engines() } catch (e: CoreException) { continue }
                if (engineViews != _engines.value) _engines.value = engineViews
                // Ticket 50: the aggregate connectivity posture, read on
                // the same drain — `EnginesChanged` and
                // `ConnectivityChanged` both trigger it, so the engines
                // and the posture never disagree. Off the main thread,
                // like the engines read above it.
                val conn = try { c.connectivity() } catch (e: CoreException) { continue }
                if (conn != _connectivity.value) _connectivity.value = conn
            }
        }
    }

    /** Triggers a first read of both lists; safe to call before the client exists. */
    fun start() {
        workspaceDirty.trySend(Unit)
        enginesDirty.trySend(Unit)
    }

    /**
     * The app's [ClientEvent] sink — registered before `CoreStartup.start()`
     * so it sees every event; called on a Rust runtime thread, so it only
     * conflates and returns.
     */
    fun onEvent(event: ClientEvent) {
        when (event) {
            is ClientEvent.WorkspaceChanged ->
                if (event.revision != _snapshot.value?.revision) workspaceDirty.trySend(Unit)
            ClientEvent.EnginesChanged, is ClientEvent.ConnectivityChanged ->
                enginesDirty.trySend(Unit)
            else -> {}
        }
    }
}
