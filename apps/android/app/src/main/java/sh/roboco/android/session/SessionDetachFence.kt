package sh.roboco.android.session

import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Orders a chat's detach (the old controller's IO lane) before the next
 * attach (the new controller's lane) — the Android replacement for iOS's
 * "both on the main thread, so the order is the call order"
 * (android-ui-parity #108, Spec §Detach before attach). Thread-safe.
 *
 * Ticket 51's `SessionController.close()` registers its IO job here under
 * the chat id; the carry-over (ticket 108) joins every pending close for
 * a chat before opening it in the other shell, so the outgoing copy's
 * `setViewAttached(false)` always lands **before** the incoming copy's
 * `setViewAttached(true)` on the core's shared warm `SessionHandle`
 * (a boolean swap there, not a counter — a late false would mark the
 * visible session detached).
 *
 * The join is capped ([timeoutMs], an Android safety value, not an iOS
 * value): after it the caller proceeds anyway and logs. `join()` does not
 * throw for a cancelled or failed job, so a failed FFI call never blocks
 * the carry-over.
 */
class SessionDetachFence(private val timeoutMs: Long = 1000) {

    /** Pending close jobs per chat id. Guarded by [synchronized] on itself. */
    private val pending = HashMap<String, MutableSet<Job>>()

    /**
     * Registers [job] as a pending close of [chatId]. Its completion
     * removes it again (immediately when it is already complete).
     */
    fun track(chatId: String, job: Job) {
        synchronized(pending) {
            pending.getOrPut(chatId) { mutableSetOf() }.add(job)
        }
        job.invokeOnCompletion {
            synchronized(pending) {
                pending[chatId]?.let { jobs ->
                    jobs.remove(job)
                    if (jobs.isEmpty()) pending.remove(chatId)
                }
            }
        }
    }

    /**
     * Joins every pending close job of [chatId]. Returns true when they
     * all finished in time (or none was pending); false = timed out, the
     * caller proceeds anyway.
     */
    suspend fun await(chatId: String): Boolean {
        val jobs = synchronized(pending) { pending[chatId]?.toList() } ?: return true
        return withTimeoutOrNull(timeoutMs) { jobs.forEach { it.join() } } != null
    }

    companion object {
        /** The production fence: [sh.roboco.android.shell.ShellStateScope] awaits it, ticket 51's `close()` tracks it. */
        val Default = SessionDetachFence()
    }
}
