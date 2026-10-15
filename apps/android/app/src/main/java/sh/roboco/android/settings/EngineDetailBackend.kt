package sh.roboco.android.settings

import sh.roboco.android.pairing.PairedEngineStore
import uniffi.roboco_core.CoreClient
import uniffi.roboco_core.CoreException

/**
 * The Engine detail page's narrow FFI + store seam (android-ui-parity
 * #140): exactly the four operations the page needs, so tests fake them
 * in one place and the screen never touches [CoreClient] or the store
 * directly (android-app#06 exposes no generic RPC call, and `EngineView`
 * carries no `sessionId`, hence [revokeOwnSession]).
 *
 * All four are **main-safe suspend calls** — the FFI hops happen inside
 * the state's `withContext(Dispatchers.IO)`, never on the main thread
 * and never in composition.
 */
interface EngineDetailBackend {

    /** `CoreClient.renameEngine` — the engine's own device row rename. */
    suspend fun rename(engineId: String, name: String)

    /** `CoreClient.revokeOwnSession` (this ticket's FFI): best-effort self-revoke. */
    suspend fun revokeOwnSession(engineId: String)

    /** `CoreClient.forgetEngine` — drops the client-side engine (links, cache). */
    suspend fun forget(engineId: String)

    /** Ticket 71's `PairedEngineStore.remove` — drops the stored record. */
    suspend fun removeStored(engineId: String)
}

/**
 * The production backend: [client] is `RobocoApplication::clientOrNull`
 * (null before the core is `Ready` — the page is unreachable then, but a
 * rename or forget racing a core restart must fail closed, not crash),
 * and [store] is `RobocoApplication.engineStore` (71). Each method
 * resolves the client itself, so no client reference is captured across
 * calls.
 */
class CoreEngineDetailBackend(
    private val client: () -> CoreClient?,
    private val store: PairedEngineStore,
) : EngineDetailBackend {

    override suspend fun rename(engineId: String, name: String) {
        live().renameEngine(engineId, name)
    }

    override suspend fun revokeOwnSession(engineId: String) {
        live().revokeOwnSession(engineId)
    }

    override suspend fun forget(engineId: String) {
        live().forgetEngine(engineId)
    }

    override suspend fun removeStored(engineId: String) {
        store.remove(engineId)
    }

    /** The live client, or a `Closed` error when the core is not ready. */
    private fun live(): CoreClient = client() ?: throw CoreException.Closed()
}
