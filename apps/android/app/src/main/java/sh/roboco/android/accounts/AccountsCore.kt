package sh.roboco.android.accounts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.roboco_core.AgentAccountsSnapshot
import uniffi.roboco_core.CoreClient

/**
 * The FFI seam of the agent-account surfaces (android-next#05) — the four
 * `CoreClient` calls behind the usage chip, the Settings section and the
 * Accounts & Usage page. Every member is FFI: call only from an IO lane,
 * never on main. A seam so the plain-JVM tests fake it (the generated
 * records construct without JNA, the calls do not); failures cross as
 * the generated `CoreException` (see [describeAccountsError]).
 */
interface AccountsCore {

    /**
     * `CoreClient.listAgentAccounts(deviceId, forceUsage)` — the host's
     * last probe when `forceUsage` is false; a forced list re-probes
     * (throttled host-side per account; 45 s deadline in the core).
     */
    suspend fun list(deviceId: String, forceUsage: Boolean): AgentAccountsSnapshot

    /**
     * `CoreClient.activateAgentAccount(deviceId, harness, accountId)` —
     * make the login the one in use; replies with the updated accounts.
     */
    suspend fun activate(deviceId: String, harness: String, accountId: String): AgentAccountsSnapshot

    /**
     * `CoreClient.forgetAgentAccount(deviceId, harness, accountId)` —
     * remove a saved login; replies with the updated accounts.
     */
    suspend fun forget(deviceId: String, harness: String, accountId: String): AgentAccountsSnapshot

    /**
     * `CoreClient.cachedAgentAccounts(deviceId)` — the newest snapshot
     * the core holds (every successful list/activate/forget refreshes
     * it), so a screen paints at once and refreshes behind it. `null`
     * before the first success.
     */
    fun cached(deviceId: String): AgentAccountsSnapshot?
}

/**
 * The live [AccountsCore] over the process's single [CoreClient].
 * `deviceId` is the id shape the platform holds (the session's host
 * device id, a workspace `DeviceView` id); the core resolves every shape
 * to the same engine — and the same cache entry.
 */
class CoreAccountsCore(
    private val client: () -> CoreClient?,
) : AccountsCore {
    override suspend fun list(deviceId: String, forceUsage: Boolean): AgentAccountsSnapshot =
        withContext(Dispatchers.IO) { live().listAgentAccounts(deviceId, forceUsage) }

    override suspend fun activate(
        deviceId: String,
        harness: String,
        accountId: String,
    ): AgentAccountsSnapshot =
        withContext(Dispatchers.IO) { live().activateAgentAccount(deviceId, harness, accountId) }

    override suspend fun forget(
        deviceId: String,
        harness: String,
        accountId: String,
    ): AgentAccountsSnapshot =
        withContext(Dispatchers.IO) { live().forgetAgentAccount(deviceId, harness, accountId) }

    override fun cached(deviceId: String): AgentAccountsSnapshot? =
        client()?.cachedAgentAccounts(deviceId)

    /** The live client, or a `Closed` error when the core is not ready. */
    private fun live(): CoreClient = client() ?: throw uniffi.roboco_core.CoreException.Closed()
}
