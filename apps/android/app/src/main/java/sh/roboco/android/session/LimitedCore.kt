package sh.roboco.android.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.roboco_core.CoreClient
import uniffi.roboco_core.ResumeCandidates
import uniffi.roboco_core.ResumeSwitchOutcome

/**
 * The Limited banner's core seam (the failover port's slice of the
 * `SessionCore` idea): the four engine calls behind "Resume at reset",
 * "Cancel", "Resume on another account" and its picker. Fakeable for the
 * JVM tests; the generated `CoreClient` methods are JNA-backed and cannot
 * be constructed on the JVM.
 *
 * Every method crosses FFI and blocks on the engine — run on an IO lane,
 * never main. The async FFI methods execute on the core's own runtime;
 * `withContext(Dispatchers.IO)` matches [AccountsCore][sh.roboco.android.accounts.AccountsCore]'s lane.
 */
interface LimitedCore {
    /** `ListResumeAccountCandidates`: the ranked switch targets for the
     *  chat (best first; the first is the recommendation) plus its
     *  signed-out rows. Served from the engine's cached rows — no network. */
    suspend fun candidates(chatId: String): ResumeCandidates

    /** `ResumeOnAnotherAccount`: switch the chat's harness to `accountId`
     *  (the engine's recommendation when null) and dispatch the
     *  continuation. A failed switch leaves the harness as it was. */
    suspend fun switch(chatId: String, accountId: String?): ResumeSwitchOutcome

    /** `Mutate scheduleResumeAtReset`: arm the durable continuation that
     *  sends the continue prompt at the limit's reset time. */
    suspend fun armResumeAtReset(chatId: String)

    /** `Mutate cancelScheduledResume`: withdraw the armed continuation
     *  (a no-op engine-side when nothing is armed). */
    suspend fun cancelScheduledResume(chatId: String)
}

/** The live [LimitedCore] over the app's [CoreClient]. */
class CoreLimitedCore(
    private val client: CoreClient,
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : LimitedCore {
    override suspend fun candidates(chatId: String): ResumeCandidates =
        withContext(io) { client.listResumeAccountCandidates(chatId = chatId) }

    override suspend fun switch(chatId: String, accountId: String?): ResumeSwitchOutcome =
        withContext(io) { client.resumeOnAnotherAccount(chatId = chatId, accountId = accountId) }

    override suspend fun armResumeAtReset(chatId: String) =
        withContext(io) { client.scheduleResumeAtReset(chatId = chatId) }

    override suspend fun cancelScheduledResume(chatId: String) =
        withContext(io) { client.cancelScheduledResume(chatId = chatId) }
}
