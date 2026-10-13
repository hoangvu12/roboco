package sh.roboco.android.composer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uniffi.roboco_core.SessionHandle

/**
 * The fakeable FFI seam for the queue commands (android-ui-parity #82) —
 * [SessionCore][sh.roboco.android.session.SessionCore] is the same idea
 * for ticket 51. All methods suspend and block in FFI: **never call them
 * on main** (the #609 trap). Names follow the generated UniFFI output of
 * `crates/mobile/src/client_ffi/session.rs`.
 */
interface QueuePort {
    /**
     * A row's primary action (PR #592): text rows steer into the live
     * turn (never interrupt); attachment rows send now (interrupting).
     * The routing lives in the core — Kotlin never picks between steer
     * and send.
     */
    suspend fun deliverQueuedNow(id: String): Boolean

    /** Removes a row (applied locally after the host acks). */
    suspend fun removeQueued(id: String): Boolean

    /** Nudges a row one slot up (`-1`) or down (`+1`). */
    suspend fun moveQueuedBy(id: String, delta: Int): Boolean
}

/** The live [QueuePort] over one open session's handle. */
class CoreQueuePort(private val handle: SessionHandle) : QueuePort {
    override suspend fun deliverQueuedNow(id: String): Boolean = handle.deliverQueuedNow(id)

    override suspend fun removeQueued(id: String): Boolean = handle.removeQueued(id)

    override suspend fun moveQueuedBy(id: String, delta: Int): Boolean = handle.moveQueuedBy(id, delta)
}

/**
 * The port of `CoreSessionSource.queueAction`
 * (`ios:Session/CoreSessionSource.swift:137-147`): fire-and-forget.
 *
 * - The returned Booleans are ignored (iOS `_ =`): the UI changes only
 *   through the next `ComposerState` (ticket 51's refresh), never through
 *   a return value here.
 * - Results and errors are discarded — `try?` on iOS, a log line here.
 *   A failed RPC is silent in the UI (decision COMPOSER-D2). Gated /
 *   action-pending send-now refusals never even reach this layer's error
 *   path: the core returns `Ok(false)` before touching `queue_error`
 *   (`crates/client/src/session/mod.rs:1304-1306`), so nothing is shown.
 * - [scope] must outlive the screen — an application-lifetime scope, not
 *   a `rememberCoroutineScope()` (iOS's `Task {}` also outlives the
 *   view). A Remove tapped just before Back still reaches the engine.
 *   Never cancelled on pop.
 * - [io] serialises the calls **in tap order** (a limited-parallelism
 *   dispatcher). [QueueAction.Edit] is not handled here at all: ticket
 *   83's `QueueEditController.beginEdit(rowId)` owns it, so it is
 *   ignored with a log line until that lands.
 */
class QueueActions(
    private val port: QueuePort,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val logW: (String, Throwable) -> Unit = { message, error ->
        android.util.Log.w("RobocoQueue", message, error)
    },
) {
    /**
     * [SendNow][QueueAction.SendNow] / [MoveUp][QueueAction.MoveUp] /
     * [MoveDown][QueueAction.MoveDown] / [Remove][QueueAction.Remove] are
     * launched on the serial lane. [Edit][QueueAction.Edit] never reaches
     * FFI.
     */
    fun perform(id: String, action: QueueAction) {
        if (action == QueueAction.Edit) {
            logW("Edit is routed to ticket 83, not the queue port", IllegalStateException(id))
            return
        }
        scope.launch(io) {
            try {
                when (action) {
                    QueueAction.SendNow -> port.deliverQueuedNow(id)
                    QueueAction.Remove -> port.removeQueued(id)
                    QueueAction.MoveUp -> port.moveQueuedBy(id, -1)
                    QueueAction.MoveDown -> port.moveQueuedBy(id, 1)
                    QueueAction.Edit -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                // iOS `try?`: the row stays or leaves according to the
                // engine's echo; nothing is shown.
                logW("queue $action failed for $id", t)
            }
        }
    }
}
