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
 * A user-facing error line. [Res] is a string resource;
 * [Verbatim] is one of Rust's four parse messages, shown as is
 * (`crates/ui/src/engine_registry.rs:801-830`).
 */
sealed interface PairMessage {
    data class Res(@StringRes val id: Int) : PairMessage
    data class Verbatim(val text: String) : PairMessage
}

/**
 * The Pair page's FFI seam (android-ui-parity #72): `CoreClient` is a
 * JNA object the tests cannot build, so the page talks to this narrow
 * interface instead. Production is [CoreClientPairingBackend]
 * (`pairEngine`, `forgetEngine` and the top-level `parsePairingUrl`).
 */
interface PairingBackend {

    /**
     * `parsePairingUrl(input)`; `null` when it throws. Pure and cheap,
     * but still called off the main thread (`Dispatchers.Default`).
     */
    fun parse(input: String): PairingTarget?

    /** `CoreClient.pairEngine`; throws [PairException]. */
    suspend fun pair(url: String, replaceEngineId: String?): PairedEngine

    /** `CoreClient.forgetEngine`; called only from the store-failure rollback. */
    suspend fun forget(engineId: String)
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

    /** 71's `remove`; the rollback after `forgetEngine` (new engines only). */
    suspend fun remove(engineId: String)
}

/**
 * Maps a redeem failure to the error line (the web's
 * `describeRedeemError`, `web/packages/app/src/lib/pairing-errors.ts:10-28`,
 * plus the three Tailcat failures of android-next#02): the fixed
 * sentences, the parse reason verbatim, and `pair_error_failed` for
 * everything else — `Failed`, `Storage` and any non-[PairException]. The
 * Tailcat reasons are never shown (fixed copy only). The store-failure
 * copy is **not** produced here; `submit` emits it directly.
 */
fun describePairError(e: Throwable): PairMessage = when (e) {
    is PairException.Refused -> PairMessage.Res(R.string.pair_error_refused)
    is PairException.Unreachable -> PairMessage.Res(R.string.pair_error_unreachable)
    is PairException.InvalidUrl -> PairMessage.Verbatim(e.reason)
    is PairException.TailcatExpired -> PairMessage.Res(R.string.pair_error_tailcat_expired)
    is PairException.TailcatUnreachable -> PairMessage.Res(R.string.pair_error_tailcat_unreachable)
    is PairException.TailcatUnavailable -> PairMessage.Res(R.string.pair_error_tailcat_unavailable)
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
     * The redeem (capsule tap, IME Go, or a QR scan). Ignores a double
     * submit and a blank field; snapshots `knownEngineIds` BEFORE `pair`
     * (the client's list already contains the new engine afterwards);
     * persists FIRST, then clears, haptics and closes. On a store write
     * failure for a NEW engine: `forget` then `remove` (71's caller
     * contract) and the store-failure copy — the link is spent, the user
     * needs a fresh one.
     */
    fun submit() {
        if (phase is PairPhase.Redeeming) return
        val url = text.text.toString().trim()
        if (url.isEmpty()) return
        phase = PairPhase.Redeeming
        // Read before `pair`: after it returns, the client's list already
        // contains the new engine (the re-pair/dedupe check below).
        val knownBefore = store.knownEngineIds()
        val replace = replaceEngineId
        scope.launch {
            try {
                val engine = withContext(io) { backend.pair(url, replace) }
                val known = engine.engineId in knownBefore
                try {
                    store.upsert(engine)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (!known) {
                        // 71's caller contract: no unpersisted engine stays
                        // live. Known engines keep their new credential for
                        // this run; the old record parks them after a
                        // restart as the designed recovery path.
                        withContext(NonCancellable + io) {
                            runCatching { backend.forget(engine.engineId) }
                            runCatching { store.remove(engine.engineId) }
                        }
                    }
                    phase = PairPhase.Error(PairMessage.Res(R.string.pair_error_store))
                    return@launch
                }
                // Persist first, then clear, haptic and close. The page is
                // gone (onPaired == null) when the sheet closed mid-redeem:
                // persist, no haptic, no dismiss.
                text.clearText()
                phase = PairPhase.Idle
                onPaired?.invoke()
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
                phase = PairPhase.Error(describePairError(error))
            }
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
