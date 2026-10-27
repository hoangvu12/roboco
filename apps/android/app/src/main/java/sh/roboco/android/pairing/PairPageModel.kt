package sh.roboco.android.pairing

import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.roboco.android.R
import uniffi.roboco_core.PairedEngine
import uniffi.roboco_core.PairException
import uniffi.roboco_core.PairingTarget
import uniffi.roboco_core.RoomException
import uniffi.roboco_core.RoomInviteTarget
import uniffi.roboco_core.RoomJoinResult
import uniffi.roboco_core.RoomRecord

/**
 * The Pair page's redeem state (android-ui-parity #72), the port of the
 * web client's `PairPhase` (`web/packages/app/src/routes/pair-page.tsx:7`):
 * `Idle`, `Redeeming`, or `Error(message)`. Editing the field never
 * clears an [Error] — only the next submit does (web parity).
 */
sealed interface PairPhase {
    data object Idle : PairPhase
    data object Redeeming : PairPhase
    data class Error(val message: PairMessage) : PairPhase
}

/**
 * A user-facing error line. [Res] is a string resource, with the
 * failure's own [Res.detail] when it has one;
 * [Verbatim] is one of Rust's four parse messages, shown as is
 * (`crates/ui/src/engine_registry.rs:801-830`).
 */
sealed interface PairMessage {
    /**
     * [detail] is the failure's own reason, shown under a "Show details"
     * disclosure beneath the fixed sentence. Only secret-free text goes
     * here: never the invite, the address or the token.
     */
    data class Res(@StringRes val id: Int, val detail: String? = null) : PairMessage
    data class Verbatim(val text: String) : PairMessage
}

/**
 * The Pair page's FFI seam (android-ui-parity #72): `CoreClient` is a
 * JNA object the tests cannot build, so the page talks to this narrow
 * interface instead. Production is [CoreClientPairingBackend]
 * (`pairEngine`, `joinRoom`, `forgetEngine` and the top-level
 * `parsePairingUrl` / `parseRoomInvite`).
 */
interface PairingBackend {

    /**
     * `parsePairingUrl(input)`; `null` when it throws. Pure and cheap,
     * but still called off the main thread (`Dispatchers.Default`).
     */
    fun parse(input: String): PairingTarget?

    /**
     * `parseRoomInvite(input)` (ADR 0011, ticket 04); `null` when it
     * throws — the same "does not parse" rule as [parse]. Carries only
     * the room name and the member labels: never the secret, never an
     * address.
     */
    fun parseRoom(input: String): RoomInviteTarget?

    /** `CoreClient.pairEngine`; throws [PairException]. */
    suspend fun pair(url: String, replaceEngineId: String?): PairedEngine

    /** `CoreClient.joinRoom`; throws [RoomException]. A partial join is a
     * success — the missing members heal on app start / network change. */
    suspend fun joinRoom(invite: String): RoomJoinResult

    /** `CoreClient.forgetEngine`; called only from the store-failure rollback. */
    suspend fun forget(engineId: String)

    /**
     * Fleet follow (fleet-follow ticket 04): `CoreClient.fetchFleet` on
     * the engine a pairing just returned and, when it offers a fleet,
     * [onJoining] (the progress toast) then `CoreClient.joinFleet`.
     * `null` — silently — when there is no fleet: the engine lacks
     * `rooms-v2`, has none, or never connected. Throws [RoomException]
     * only from the join itself. The offer is opaque: the fleet secret
     * never crosses into Kotlin before the result's `room:` record.
     * Defaults to "no fleet" so test backends opt in.
     */
    suspend fun followFleet(engineId: String, onJoining: () -> Unit): RoomJoinResult? = null
}

/**
 * The fleet follow's only UI (fleet-follow ticket 04): two toasts, the
 * progress line while the join runs and the count of engines it added.
 * Never an error — a failed or absent fleet shows nothing. Production
 * is [ToasterFleetNotices].
 */
interface FleetNotices {

    /** The fleet was offered and its silent join started. */
    fun joining()

    /** The join added [count] engines (> 0) that were not paired before. */
    fun joined(count: Int)

    companion object {
        /** No UI (tests, galleries). */
        val None: FleetNotices = object : FleetNotices {
            override fun joining() {}
            override fun joined(count: Int) {}
        }
    }
}

/**
 * What the Pair page needs from ticket 71 (the [PairedEngineStore] plus
 * the startup pipeline's live engine list). Production is
 * [StorePairedEngineSink].
 */
interface PairedEngineSink {

    /**
     * Engine ids the client knows **right now**; read BEFORE `pair()`.
     * Production: `coreStartup.engines.value.orEmpty().map { it.engineId
     * }.toSet()` — after `pair` returns, the client's list already
     * contains the new engine.
     */
    fun knownEngineIds(): Set<String>

    /** 71's `upsert`; throws [CredentialStoreException], the store unchanged. */
    suspend fun upsert(engine: PairedEngine)

    /**
     * The `room:` blob (ticket 04): the joined room's secret plus book,
     * encrypted. Called BEFORE the engine upserts of the same join, so the
     * metadata (which references the room) never exists without its blob
     * — a crash between the two leaves a swept orphan, not a dead room.
     */
    suspend fun upsertRoom(room: RoomRecord)

    /** 71's `remove`; the rollback after `forgetEngine` (new engines only). */
    suspend fun remove(engineId: String)
}

/**
 * Maps a redeem failure to the error line (the web's
 * `describeRedeemError`, `web/packages/app/src/lib/pairing-errors.ts:10-28`,
 * plus the three Tailcat failures of android-next#02): the fixed
 * sentences, the parse reason verbatim, and `pair_error_failed` for
 * everything else — `Failed`, `Storage` and any non-[PairException].
 * `TailcatUnreachable` carries its reason as the detail: the adapter's
 * C-API redacts it (never the address), and the fixed sentence alone
 * cannot tell an offline engine from a corrupt invite ("invalid Tailcat
 * address"). The store-failure copy is **not** produced here; `submit`
 * emits it directly.
 */
fun describePairError(e: Throwable): PairMessage = when (e) {
    is PairException.Refused -> PairMessage.Res(R.string.pair_error_refused)
    is PairException.Unreachable -> PairMessage.Res(R.string.pair_error_unreachable)
    is PairException.InvalidUrl -> PairMessage.Verbatim(e.reason)
    is PairException.TailcatExpired -> PairMessage.Res(R.string.pair_error_tailcat_expired)
    is PairException.TailcatUnreachable -> PairMessage.Res(
        R.string.pair_error_tailcat_unreachable,
        detail = e.reason.trim().ifEmpty { null },
    )
    is PairException.TailcatUnavailable -> PairMessage.Res(R.string.pair_error_tailcat_unavailable)
    else -> PairMessage.Res(R.string.pair_error_failed)
}

/**
 * A room join failure (ADR 0011, ticket 04) to the error line: the same
 * fixed-sentence vocabulary, with the shape-only parse reason verbatim.
 * A member engine without `rooms-v1` never gets here — the join skips it
 * silently (hide, not toast), so there is no "unsupported" sentence.
 */
fun describeRoomError(e: Throwable): PairMessage = when (e) {
    is RoomException.Refused -> PairMessage.Res(R.string.room_error_refused)
    is RoomException.Throttled -> PairMessage.Res(R.string.room_error_throttled)
    is RoomException.Unreachable -> PairMessage.Res(R.string.pair_error_unreachable)
    is RoomException.InvalidInvite -> PairMessage.Verbatim(e.reason)
    else -> PairMessage.Res(R.string.pair_error_failed)
}

/**
 * The Pair page's state machine (android-ui-parity #72): the field, the
 * live parse target, the redeem phase and the persist-first success
 * path. Purely behavioural — the UI is [PairPageContent].
 *
 * `submit` runs in the **launcher's app-scoped [scope]**, never the
 * composition: closing the sheet mid-redeem must not cancel a
 * credential Rust has already minted ([onPaired] is null then, so the
 * success path persists and only logs). All FFI hops go through
 * [withContext] ([io]/[Dispatchers.Default]); `phase` is written on the
 * scope's thread (Main.immediate in production).
 *
 * Never `toString()` a [PairedEngine] or log the URL: both carry the
 * credential.
 */
@Stable
class PairPageModel(
    prefill: String?,
    initialReplaceEngineId: String?,
    private val backend: PairingBackend,
    private val store: PairedEngineSink,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val fleet: FleetNotices = FleetNotices.None,
) {

    /** The link field, pre-filled from a deep link/share (139) or empty. */
    val text: TextFieldState = TextFieldState(prefill.orEmpty())

    /**
     * Re-pair mode: the engine id Rust keeps when it re-pairs. `null`
     * for a plain pair; set per redeem, replaced by `refill`.
     */
    var replaceEngineId: String? by mutableStateOf(initialReplaceEngineId)
        internal set

    /** The redeem state; only [submit] leaves `Idle`. */
    var phase: PairPhase by mutableStateOf(PairPhase.Idle)
        private set

    /**
     * The live parse of the trimmed field, written by the page's
     * live-parse effect (null when the text is blank or does not parse).
     */
    var target: PairingTarget? by mutableStateOf(null)
        internal set

    /**
     * The live parse as a room invite (ticket 04): set when the trimmed
     * field parses as `roboco-room:` — exactly one of [target] /
     * [roomTarget] is non-null at a time, and a submit dispatches on it.
     * Secret-free: the name and the member labels only.
     */
    var roomTarget: RoomInviteTarget? by mutableStateOf(null)
        internal set

    /**
     * Set by the page while it is shown; called on success (haptic +
     * close). `null` when the page is gone — a background redeem then
     * persists without either.
     */
    var onPaired: (() -> Unit)? = null

    /** True while a submit would do something (not redeeming, text after trim). */
    val canSubmit: Boolean
        get() = phase !is PairPhase.Redeeming && text.text.trim().isNotEmpty()

    /**
     * `backend.parse(input)` — the page's live-parse effect calls this on
     * `Dispatchers.Default`; production is the same `parse_pairing_url`
     * the redeem uses, so "Engine at …" never shows for a link Rust
     * would reject.
     */
    internal fun parseTarget(input: String): PairingTarget? = backend.parse(input)

    /**
     * `backend.parseRoom(input)` — the same live-parse effect's room arm:
     * `null` unless the field is a `roboco-room:` invite.
     */
    internal fun parseRoomTarget(input: String): RoomInviteTarget? = backend.parseRoom(input)

    /**
     * The redeem (capsule tap, IME Go, or a QR scan). Ignores a double
     * submit and a blank field; snapshots `knownEngineIds` BEFORE the
     * FFI call (the client's list already contains the new engines
     * afterwards); persists FIRST, then clears, haptics and closes. A
     * `roboco-room:` field dispatches to the join instead (ticket 04):
     * the room blob first, then every joined member's record — a partial
     * join is a success (the missing members heal on app start / network
     * change). On a store write failure for a NEW engine: `forget` then
     * `remove` (71's caller contract) and the store-failure copy — the
     * link is spent, the user needs a fresh one.
     */
    fun submit() {
        if (phase is PairPhase.Redeeming) return
        val url = text.text.toString().trim()
        if (url.isEmpty()) return
        // The dispatch is the prefix the Rust decoder gates on (the live
        // parse's `roomTarget` is display-only, exactly like `target`): a
        // `roboco-room:` invite never goes to the pairing redeem, however
        // fast the submit lands after typing.
        val room = url.startsWith("roboco-room:")
        phase = PairPhase.Redeeming
        // Read before the call: after it returns, the client's list
        // already contains the new engines (the re-pair/dedupe check).
        val knownBefore = store.knownEngineIds()
        val replace = replaceEngineId
        scope.launch {
            try {
                var paired: PairedEngine? = null
                if (room) {
                    val result = withContext(io) { backend.joinRoom(url) }
                    persistJoin(result, knownBefore)
                } else {
                    val engine = withContext(io) { backend.pair(url, replace) }
                    persist(engine, knownBefore)
                    paired = engine
                }
                if (phase is PairPhase.Error) {
                    // The store-failure copy stands (the rollback above
                    // already ran); nothing is cleared, nothing closes.
                    return@launch
                }
                // Persist first, then clear, haptic and close. The page is
                // gone (onPaired == null) when the sheet closed mid-redeem:
                // persist, no haptic, no dismiss.
                text.clearText()
                phase = PairPhase.Idle
                onPaired?.invoke()
                // The pairing is done and persisted, exactly as before
                // fleets; the fleet follows on its own (never blocking or
                // failing the pairing).
                if (paired != null) followFleet(paired.engineId, knownBefore)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A background failure (the sheet closed mid-redeem) is only
                // *surfaced* through the log — the class, never the URL (it
                // carries the token); 139 shows these on the pairing root.
                // The phase still records it so the kept model cannot stay
                // a zombie `Redeeming` forever: a reopen then builds a
                // fresh page instead of a stuck busy capsule.
                if (onPaired == null) {
                    Log.w(TAG, "background redeem failed: ${error::class.simpleName}")
                }
                phase = PairPhase.Error(
                    if (room) describeRoomError(error) else describePairError(error),
                )
            }
        }
    }

    /** The pairing persist-first path (72): a store failure for a NEW
     * engine rolls it back (forget + remove, 71's caller contract). */
    private suspend fun persist(engine: PairedEngine, knownBefore: Set<String>) {
        if (!storeEngine(engine, knownBefore)) {
            phase = PairPhase.Error(PairMessage.Res(R.string.pair_error_store))
        }
    }

    /** [persist] without the phase: `false` after a store failure (a NEW
     * engine is rolled back — forget + remove — a known one stays). */
    private suspend fun storeEngine(engine: PairedEngine, knownBefore: Set<String>): Boolean {
        try {
            store.upsert(engine)
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (engine.engineId !in knownBefore) {
                withContext(NonCancellable + io) {
                    runCatching { backend.forget(engine.engineId) }
                    runCatching { store.remove(engine.engineId) }
                }
            }
            return false
        }
    }

    /**
     * Fleet follow (fleet-follow ticket 04), in the launcher's app-scoped
     * [scope] after a pairing was persisted: fetch the engine's fleet and
     * join it silently (`followFleet`), then persist like a room join —
     * the `room:` blob first, then every record as an upsert by engine id
     * (the introducing engine and members paired before come back with
     * their existing ids, so they replace their rows in place: never a
     * second row). Rust dedupes before presenting (already-paired members
     * get no new session) and folds a member reached under another
     * address by its pinned identity.
     *
     * Silent by contract: no fleet, an old engine, a failed join or a
     * store failure never touch [phase] and never toast an error — the
     * pairing already succeeded. Only [fleet]'s progress and count toasts
     * show. A store failure rolls back only the engines this join minted
     * (never the paired one, never one paired before).
     */
    private fun followFleet(engineId: String, knownBeforePairing: Set<String>) {
        scope.launch {
            try {
                // Known = what the phone had before this pairing (read
                // before `pair`, so no fleet member minted below is in it)
                // plus the paired engine itself: a rollback must never
                // forget it, and it never counts as "added".
                val knownBefore = knownBeforePairing + engineId
                val result = withContext(io) {
                    backend.followFleet(engineId) { fleet.joining() }
                } ?: return@launch
                if (!storeFleetJoin(result, knownBefore)) {
                    Log.w(TAG, "fleet join not saved")
                    return@launch
                }
                val added = result.engines.map { it.engineId }.toSet().count { it !in knownBefore }
                if (added > 0) fleet.joined(added)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // The class only — never the invite, an address or a record.
                Log.w(TAG, "fleet follow skipped: ${error::class.simpleName}")
            }
        }
    }

    /** The fleet's persist: [persistJoin] without the phase. `false` after
     * a store failure, the new engines rolled back. */
    private suspend fun storeFleetJoin(result: RoomJoinResult, knownBefore: Set<String>): Boolean {
        try {
            store.upsertRoom(result.room)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            withContext(NonCancellable + io) {
                for (engine in result.engines) {
                    if (engine.engineId !in knownBefore) {
                        runCatching { backend.forget(engine.engineId) }
                        runCatching { store.remove(engine.engineId) }
                    }
                }
            }
            return false
        }
        for (engine in result.engines) {
            if (!storeEngine(engine, knownBefore)) return false
        }
        return true
    }

    /**
     * The room join's persist-first path (ticket 04): the `room:` blob
     * first (a crash between blob and engines leaves a swept orphan, not
     * a dead room), then every joined member's record. A store failure
     * rolls the NEW engines back and — if the blob write itself failed —
     * the store is unchanged (the old room, if any, survives). The
     * missing members are fine: they were never engines.
     */
    private suspend fun persistJoin(result: RoomJoinResult, knownBefore: Set<String>) {
        try {
            store.upsertRoom(result.room)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The blob write failed atomically: roll the new engines back
            // (71's caller contract — no unpersisted engine stays live).
            withContext(NonCancellable + io) {
                for (engine in result.engines) {
                    if (engine.engineId !in knownBefore) {
                        runCatching { backend.forget(engine.engineId) }
                        runCatching { store.remove(engine.engineId) }
                    }
                }
            }
            phase = PairPhase.Error(PairMessage.Res(R.string.pair_error_store))
            return
        }
        for (engine in result.engines) {
            persist(engine, knownBefore)
            if (phase is PairPhase.Error) return
        }
    }

    /**
     * A QR scan result (ticket 138 delivers the plain URL): set the field
     * and submit at once (android-app#18: scans auto-submit, links and
     * shares do not).
     */
    fun acceptScanned(url: String) {
        text.setTextAndPlaceCursorAtEnd(url)
        submit()
    }

    /**
     * "Opening while open": replace the field with [prefill] (when
     * non-null), take the new [replaceEngineId] and reset the phase —
     * only called while the sheet is up and NOT redeeming.
     */
    internal fun refill(prefill: String?, replaceEngineId: String?) {
        if (prefill != null) text.setTextAndPlaceCursorAtEnd(prefill)
        this.replaceEngineId = replaceEngineId
        phase = PairPhase.Idle
    }

    private companion object {
        const val TAG = "PairPage"
    }
}
