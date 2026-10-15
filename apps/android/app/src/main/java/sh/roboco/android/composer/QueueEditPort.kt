package sh.roboco.android.composer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.roboco_core.QueueEditAction
import uniffi.roboco_core.QueueEditFinish
import uniffi.roboco_core.QueueEditLease
import uniffi.roboco_core.QueueEditStart
import uniffi.roboco_core.SessionHandle

/**
 * Opaque lease handle (android-ui-parity #83): [core] is the uniffi
 * [QueueEditLease] in production, null in fakes. Equality is [leaseId]
 * alone, so the controller's lease-identity checks (`lease != held`)
 * compare lease identity, never the wrapped record.
 */
class EditLease(val rowId: String, val leaseId: String, internal val core: Any? = null) {
    override fun equals(other: Any?): Boolean = other is EditLease && other.leaseId == leaseId

    override fun hashCode(): Int = leaseId.hashCode()

    override fun toString(): String = "EditLease($rowId, $leaseId)"
}

/**
 * The row as the composer snapshot has it: the raw text (what the engine
 * will send, trailer / Appshot context included) and the visible text
 * (what the composer edits).
 */
data class EditRow(val raw: String, val visible: String)

/** `handle.beginQueuedEdit`: an acquired lease, or the reason it refused. */
sealed interface EditStart {
    data class Acquired(val lease: EditLease) : EditStart

    /** Another editor holds the row. */
    data object Locked : EditStart

    /** The row left the queue. */
    data object Missing : EditStart

    /** Host unreachable or the capability is missing. */
    data object Unavailable : EditStart
}

/** `handle.finishQueuedEdit`: how the release went. */
enum class EditFinish { Finished, Conflict, Missing, Lost, Unavailable }

/**
 * The core seam of the queued-message edit lease (android-ui-parity
 * #83) — the port of `CoreSessionSource`'s three lease calls
 * (`ios:Session/CoreSessionSource.swift:149-195`), fakeable so the
 * state machine is fully unit-testable on the JVM. Names follow the
 * generated UniFFI output of `crates/mobile/src/client_ffi/session.rs`
 * (`beginQueuedEdit(id, instanceId)`, `renewQueuedEdit(lease)`,
 * `finishQueuedEdit(lease, action, text, attachments)`, `composer()`).
 *
 * All functions are main-safe: the adapter moves the FFI work to IO, and
 * a fake never blocks. Every method may throw; the controller and the
 * adapter both fold non-cancellation throwables into the Unavailable /
 * false outcomes exactly as the FFI itself does
 * (`session.rs:608-655`: `Err(_) → Unavailable`, `.unwrap_or(false)`).
 */
interface QueueEditPort {

    /** Requests the row's edit lease. A fresh `instanceId` per call (the editor instance id). */
    suspend fun begin(rowId: String, instanceId: String): EditStart

    /**
     * Renews [lease]: `true` only when the engine renewed. `lost`,
     * `missing` **and transport errors** are `false` (the FFI collapses
     * them, `session.rs:624-628`).
     */
    suspend fun renew(lease: EditLease): Boolean

    /**
     * Releases [lease]: `commit = true` sends COMMIT with [text], `false`
     * sends CANCEL with `text = null`. **`attachments` is always null on
     * the wire** — a null attachments list keeps the row's attachments,
     * an empty list would clear them (Roboco keeps attachments separate
     * from the text, `docs/reference/message-queue.md:66-70`).
     */
    suspend fun finish(lease: EditLease, commit: Boolean, text: String?): EditFinish

    /** The row as the composer snapshot has it (raw + visible text), or null when it is gone. */
    suspend fun row(rowId: String): EditRow?
}

/**
 * The live [QueueEditPort] over one open session's handle. The generated
 * methods are already `suspend`; the `withContext(io)` keeps the blocking
 * FFI conversions off main anyway. A thrown exception (other than
 * `CancellationException`) folds into the same outcomes the Rust side
 * produces: `begin → Unavailable`, `renew → false`, `finish → Unavailable`.
 */
class CoreQueueEditPort(
    private val handle: SessionHandle,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : QueueEditPort {

    override suspend fun begin(rowId: String, instanceId: String): EditStart = withContext(io) {
        try {
            when (val start = handle.beginQueuedEdit(id = rowId, instanceId = instanceId)) {
                is QueueEditStart.Acquired -> EditStart.Acquired(
                    EditLease(rowId = start.lease.rowId, leaseId = start.lease.leaseId, core = start.lease),
                )
                QueueEditStart.Locked -> EditStart.Locked
                QueueEditStart.Missing -> EditStart.Missing
                QueueEditStart.Unavailable -> EditStart.Unavailable
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            EditStart.Unavailable
        }
    }

    override suspend fun renew(lease: EditLease): Boolean = withContext(io) {
        val core = lease.core as? QueueEditLease ?: return@withContext false
        try {
            handle.renewQueuedEdit(lease = core)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun finish(lease: EditLease, commit: Boolean, text: String?): EditFinish = withContext(io) {
        val core = lease.core as? QueueEditLease ?: return@withContext EditFinish.Unavailable
        try {
            when (
                handle.finishQueuedEdit(
                    lease = core,
                    action = if (commit) QueueEditAction.COMMIT else QueueEditAction.CANCEL,
                    text = text,
                    attachments = null,
                )
            ) {
                QueueEditFinish.FINISHED -> EditFinish.Finished
                QueueEditFinish.CONFLICT -> EditFinish.Conflict
                QueueEditFinish.MISSING -> EditFinish.Missing
                QueueEditFinish.LOST -> EditFinish.Lost
                QueueEditFinish.UNAVAILABLE -> EditFinish.Unavailable
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            EditFinish.Unavailable
        }
    }

    override suspend fun row(rowId: String): EditRow? = withContext(io) {
        handle.composer().queue.firstOrNull { it.id == rowId }
            ?.let { item -> EditRow(raw = item.text, visible = item.visibleText) }
    }
}

/**
 * Always acquires; finish / renew succeed unless toggled. For the debug
 * gallery and the Transcript Lab — no engine behind it. `row` answers
 * from [rows], so a begin for a row outside it still acquires and the
 * controller's "acquired but the row is gone" path (release + alert)
 * is demonstrable live.
 */
class FixtureQueueEditPort(var rows: Map<String, EditRow>) : QueueEditPort {

    /** `false` → the next renewal tick loses the lease (recover-lost). */
    var renewOk = true

    /** The outcome every finish returns (`Finished` until toggled). */
    var finishOutcome = EditFinish.Finished

    private var nextLease = 0

    override suspend fun begin(rowId: String, instanceId: String): EditStart =
        EditStart.Acquired(EditLease(rowId = rowId, leaseId = "fixture-${nextLease++}"))

    override suspend fun renew(lease: EditLease): Boolean = renewOk

    override suspend fun finish(lease: EditLease, commit: Boolean, text: String?): EditFinish = finishOutcome

    override suspend fun row(rowId: String): EditRow? = rows[rowId]
}
