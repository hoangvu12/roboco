package sh.roboco.android.pairing

import android.util.Log
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.roboco_core.CoreClient
import uniffi.roboco_core.PairedEngine

/**
 * The debug `pair` launch extra's redemption (android-app#30): the flow
 * rig's [FixtureRule][sh.roboco.android.flows.FixtureRule] mints fresh
 * single-use pairing URLs from the `android_fixture` control endpoint and
 * hands them to `MainActivity` as a comma-separated extra. This class
 * redeems each through the live [CoreClient] and persists it — the same
 * pair-then-upsert contract as [PairPageModel.submit], minus the page:
 * no sheet is up during startup, so there is no phase state and no
 * dismiss/haptic to run.
 *
 * Called from `MainActivity.onCreate` **after** `LaunchOptions.parse`
 * (which already drops the extra in non-debuggable builds) and before the
 * first frame is released — the activity's hold waits for
 * `RootInput.Ready(hasEngines = true)`, so the redeem lands before the
 * shell is drawn and the pairing root never flashes mid-rig.
 *
 * Errors never throw (a broken rig must not crash the app under test):
 * each failure logs the error **class** only — never the URL, which
 * carries the pairing token — and the launch proceeds; the test then
 * fails at its own waits, which name the rig.
 */
class LaunchPairRedeemer(
    /** Resolves the live client (null before `CoreStartState.Ready`). */
    private val client: () -> CoreClient?,
    /** The persisted pairing records (71). */
    private val store: PairedEngineStore,
    /** The application scope: the redeem outlives the activity. */
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Redeems every URL in order; the first failure does not stop the rest.
     * [onDone] receives the number of engines paired (0 when the client
     * never came up), on [scope]'s dispatcher, once every URL was tried.
     */
    fun redeem(urls: List<String>, onDone: (redeemed: Int) -> Unit = {}) {
        if (urls.isEmpty()) return
        scope.launch {
            var redeemed = 0
            try {
                val core = awaitClient()
                if (core == null) {
                    Log.w(TAG, "pair extra: no client within ${STARTUP_TIMEOUT_MS} ms; nothing redeemed")
                    return@launch
                }
                for (url in urls) {
                    if (redeemOne(core, url)) redeemed++
                }
            } finally {
                onDone(redeemed)
            }
        }
    }

    /** True when the engine was paired (persisted or not — see [persist]). */
    private suspend fun redeemOne(core: CoreClient, url: String): Boolean {
        return try {
            val engine = withContext(io) { core.pairEngine(url, null) }
            persist(engine)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Class name only — the URL carries the token (the redaction
            // rule PairPageModel's background-failure log follows).
            Log.w(TAG, "pair extra: redeem failed (${error::class.simpleName})")
            false
        }
    }

    /**
     * The persist-first success path: a store failure leaves the engine
     * live but unpersisted, which is 71's designed recovery path for a
     * *new* engine only — same trade-off as the page, minus the rollback
     * (a flow test that fails here fails its own next assertion anyway).
     */
    private suspend fun persist(engine: PairedEngine) {
        try {
            store.upsert(engine)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "pair extra: store upsert failed (${error::class.simpleName})")
        }
    }

    /**
     * The startup pipeline is asynchronous (`coreStartup.start()` was
     * queued when the process came up); poll briefly rather than fail —
     * the client is normally there within milliseconds.
     */
    private suspend fun awaitClient(): CoreClient? {
        val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            client()?.let { return it }
            delay(POLL_MS)
        }
        return null
    }

    private companion object {
        const val TAG = "Roboco"
        const val STARTUP_TIMEOUT_MS = 20_000L
        const val POLL_MS = 100L
    }
}
