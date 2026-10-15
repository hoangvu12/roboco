package sh.roboco.android.newsession

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import sh.roboco.android.composer.SendResult
import sh.roboco.android.composer.StagedImage
import sh.roboco.android.design.Alerts
import sh.roboco.android.shell.DraftHandoff

/**
 * The page-state seam the send handler drives (android-ui-parity #94)
 * — ticket 92's [NewSessionModel] behind the three members `create`
 * touches, so tests fake the page instead of the model. Main thread
 * only, like the model.
 */
interface NewSessionPage {

    /** The page's copy of the sticky draft (snapshot it once per send). */
    val draft: NewSessionDraft

    /** The projects as of now — the worktree lookup needs the picked one. */
    val projects: List<ProjectOption>

    /** 92: `store.lastDraft = draft`, unconditional — the options are sticky even when the create fails. */
    fun persistBeforeCreate()

    /**
     * 92: `created = true` (later `rememberDraft` writes nothing) and the
     * saved text and images are cleared — the options stay. iOS
     * `NewSessionViewController.swift:415-418`.
     */
    fun markCreated()

    /** android-next #06: the chat a side-chat draft hangs off; `null` for a new session. */
    val side: SideChatParent? get() = null
}

/** The live [NewSessionPage] over ticket 92's model. */
class ModelNewSessionPage(private val m: NewSessionModel) : NewSessionPage {
    override val draft: NewSessionDraft get() = m.draft
    override val projects: List<ProjectOption> get() = m.currentOptions?.projects ?: emptyList()
    override fun persistBeforeCreate() = m.persistBeforeCreate()
    override fun markCreated() = m.markCreated()
    override val side: SideChatParent? get() = m.side
}

/**
 * The canvas composer's `onSend` (android-ui-parity #94) — the port of
 * `NewSessionViewController.create` (`:406-423`) with `composer.onSend`
 * wiring (`:148-150`): persist the draft, create the chat and send the
 * first message on IO, then either the failure alert and `Kept`, or
 * (created) mark the page, ask for notification permission once,
 * capture the draft handoff, and call `onCreated`.
 *
 * The whole sequence runs inside one [NonCancellable] block (the
 * Roboco ADAPT — iOS is synchronous and cannot be interrupted): a
 * sheet dismissed mid-call still finishes the send and opens the chat;
 * the capture then returns null (the page is detached) and
 * `onCreated(chatId, null)` opens it plainly. Nothing observable
 * depends on the ask/clear order swap (the ask is async and reads
 * neither; 92's `markCreated` does flag + clear in one call).
 *
 * The delivery mode is ignored, as on iOS: a new chat is idle, so the
 * composer is always in the Send state here. Returns
 * [SendResult.Sent] only after the core said yes — never early: the
 * composer's clear (54) waits for it, and a premature `Sent` would
 * lose the text on failure.
 */
class NewSessionSendHandler(
    private val page: NewSessionPage,
    private val creator: NewSessionCreator,
    /** Ticket 18: shows [StartFailedAlert]. `LocalAlerts.current` in production. */
    private val alerts: Alerts,
    /** android-app#28's `NotificationPermission.askAfterFirstSession(activity)`, bound to the activity. */
    private val askAfterFirstSession: () -> Unit,
    /** 94's `HandoffCaptureState::capture` — may be null (detached); must not throw (it catches itself). */
    private val capture: suspend (text: String) -> DraftHandoff?,
    /** Ticket 32's `PhoneShell.presentNewSession` callback (106 on tablet). */
    private val onCreated: (chatId: String, handoff: DraftHandoff?) -> Unit,
) {

    /** The send sequence, 94's table steps 1–7, on the caller's (main) dispatcher. */
    suspend fun send(text: String, images: List<StagedImage>): SendResult = withContext(NonCancellable) {
        // 1. Snapshot the draft and the projects once — later chip
        //    changes during the IO call do not affect this send — and
        //    persist the options (sticky even on failure, iOS `:409`).
        val d = page.draft
        val projects = page.projects
        val side = page.side
        page.persistBeforeCreate()

        // 2. create → open → send on IO; null = failure. A side chat
        //    (android-next #06) names its parent.
        val chatId = creator.createAndSend(d, text, images, projects, side)

        // 3a. Failure: the alert, and the composer keeps everything.
        if (chatId == null) {
            alerts.show(if (side != null) SideChatStartFailedAlert else StartFailedAlert)
            return@withContext SendResult.Kept
        }

        // 3b. Created: the flag and the clear (options stay, iOS `:415-418`).
        page.markCreated()

        // 4. The one-time notification ask, fire-and-forget: it must
        //    never throw into the sequence (the system dialog may open
        //    over the handoff, as on iOS — do not delay it). A side chat
        //    is never a first session (iOS's side path asks nothing).
        if (side == null) runCatching { askAfterFirstSession() }

        // 5. Capture before the composer clears (54 clears only after
        //    this returns Sent; null when the page left composition).
        val handoff = capture(text)

        // 6. The router swaps in the chat and hides the sheet.
        onCreated(chatId, handoff)

        // 7. Only now does 54 clear the composer.
        SendResult.Sent
    }
}
