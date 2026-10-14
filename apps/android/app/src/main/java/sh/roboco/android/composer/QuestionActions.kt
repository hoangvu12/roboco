package sh.roboco.android.composer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.roboco_core.SessionHandle
import uniffi.roboco_core.UserInputAnswer

/**
 * The fakeable FFI seam for the question panel's submit
 * (android-ui-parity #91) — [SessionCore][sh.roboco.android.session.SessionCore]
 * and [QueuePort][sh.roboco.android.composer.QueuePort] are the same idea.
 * Suspend and FFI-backed: **never call it on main** (the pr621 trap,
 * `ui/SessionScreen.kt:439` calls `respondInput` synchronously in a click
 * handler).
 */
fun interface QuestionPort {
    /** One engine round trip for a whole question set (`QueueCommand {kind:"respondInput", …}`). */
    suspend fun respondInput(requestId: String, answers: List<QuestionAnswer>)
}

/** The live [QuestionPort] over one open session's handle. */
class CoreQuestionPort(private val handle: SessionHandle) : QuestionPort {
    override suspend fun respondInput(requestId: String, answers: List<QuestionAnswer>): Unit =
        handle.respondInput(
            requestId,
            answers.map { UserInputAnswer(questionId = it.questionId, labels = it.labels) },
        )
}

/**
 * The port of `CoreSessionSource.answer` (`ios:Session/Session
 * /CoreSessionSource.swift:133-135`), with the failure reporting iOS's
 * `try?` cannot have: Roboco's `respondInput` is an engine RPC, so a
 * failure is logged, [onFailure][respond] re-arms the panel's
 * double-submit guard on main, and nothing is shown — no toast
 * (decision COMPOSER-D2).
 *
 * [appScope] must be application-lifetime (ticket 71's, the same one 82's
 * `QueueActions` runs on), never the composition's: an answer submitted
 * just before a committed Back still reaches the engine (iOS `Task {}`
 * also outlives the view) and the call is never cancelled on pop.
 */
class QuestionActions(
    private val port: QuestionPort,
    private val appScope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    /** Production logs `Log.w("RobocoQuestions", …)`; plain-JVM tests swap this (the stub `android.util.Log` throws). */
    private val logW: (String, Throwable) -> Unit = { message, error ->
        android.util.Log.w("RobocoQuestions", message, error)
    },
) {
    /**
     * Sends one answer set: launched on [io] (off main, FFI), a
     * `CancellationException` is rethrown (never a failure), any other
     * `Throwable` is logged and [onFailure] runs on [main] after it —
     * the only recovery the user gets is a working Submit again.
     */
    fun respond(requestId: String, answers: List<QuestionAnswer>, onFailure: () -> Unit) {
        appScope.launch(io) {
            try {
                port.respondInput(requestId, answers)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logW("respondInput failed for $requestId", t)
                withContext(main) { onFailure() }
            }
        }
    }
}
