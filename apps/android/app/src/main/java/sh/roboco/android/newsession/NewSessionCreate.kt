package sh.roboco.android.newsession

import android.util.Log
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.roboco.android.composer.StagedImage
import sh.roboco.android.design.AlertButton
import sh.roboco.android.design.AlertButtonRole
import sh.roboco.android.design.RobocoAlert
import uniffi.roboco_core.BusyPolicy
import uniffi.roboco_core.ChatConfig
import uniffi.roboco_core.CoreClient
import uniffi.roboco_core.NewSession
import uniffi.roboco_core.OutgoingAttachment
import uniffi.roboco_core.SandboxLevel
import uniffi.roboco_core.SendRequest
import uniffi.roboco_core.SessionTarget
import uniffi.roboco_core.WorktreeSpec

/**
 * The core calls behind a new session's first send (android-ui-parity
 * #94) — the port of `AppModel.createSession`'s three steps
 * (`ios:App/AppModel.swift:618-643`). [CoreClient] and
 * [uniffi.roboco_core.SessionHandle] are JNA-backed objects the JVM
 * cannot construct, so everything the send handler drives goes through
 * this seam and tests fake it.
 *
 * Both members are FFI calls over the engine's WebSocket:
 * [createSession] is a `suspend` engine round trip, [sendFirst] opens
 * the warm handle (never blocking on main — the open reads the per-chat
 * cache seed from disk, android-app#08) and sends the first message.
 * Never call either on the main thread; [NewSessionCreator] owns the
 * `withContext(Dispatchers.IO)` hop.
 */
interface NewSessionCore {

    /** `CoreClient.createSession(newSession)` — returns the **scoped** chat id. */
    suspend fun createSession(newSession: NewSession): String

    /** `CoreClient.openSession(chatId).send(request)` — open, then the first message. */
    suspend fun sendFirst(chatId: String, request: SendRequest)
}

/**
 * The live [NewSessionCore] over the app's client holder
 * (`RobocoApplication.clientOrNull()`, ticket 71): `null` before the
 * startup load finished or after a failed start — exactly iOS's
 * `guard let client` (`AppModel.swift:621`) — becomes an error here, so
 * the creator's catch turns it into the failure alert.
 */
class CoreNewSessionCore(private val client: () -> CoreClient?) : NewSessionCore {

    override suspend fun createSession(newSession: NewSession): String =
        (client() ?: error("no core client")).createSession(newSession)

    override suspend fun sendFirst(chatId: String, request: SendRequest) {
        val c = client() ?: error("no core client")
        // Do NOT close the handle: the core's warm cache keeps it (with
        // the pending echo of the first message) for the router's open
        // (ticket 76); a `closeSession` here would drop the pending row.
        c.openSession(chatId).send(request)
    }
}

/**
 * The request mapping (94's Request mapping table) — the exact port of
 * `AppModel.createSession` (`AppModel.swift:623-638`) plus
 * `StagedImage.outgoing` (`:646-648`). Pure; every value is taken from
 * the draft snapshot the send handler captured before the IO call.
 */
internal object NewSessionRequests {

    /**
     * The target's precedence (`:622-628`): a project wins over a host;
     * neither → `null`, and the caller fails **without** a core call (the
     * alert explains). Ids are the scoped ids the options carry.
     */
    fun target(d: NewSessionDraft, side: SideChatParent? = null): SessionTarget? {
        // android-next #06: ignored by the core for a side chat (the
        // parent's host fills it), but the record needs one (iOS `sideOf`).
        if (side != null) return SessionTarget.Projectless(deviceId = side.deviceId)
        val projectId = d.projectId
        if (projectId != null) return SessionTarget.Project(spaceId = projectId)
        val hostId = d.hostId
        if (hostId != null) return SessionTarget.Projectless(deviceId = hostId)
        return null
    }

    /**
     * `NewSession(target:config:branch:cwd:title:)` (`:629-630`):
     * `reasoning = effort` (nil ⇒ the engine chooses), `sandbox` is
     * always workspace-write on this canvas, `branch` only without a
     * worktree (with one, the branch becomes the worktree's base). A side
     * chat (android-next #06) names its parent and no branch: it runs in
     * the parent's checkout (the core keeps the parent's sandbox too).
     */
    fun newSession(d: NewSessionDraft, target: SessionTarget, side: SideChatParent? = null): NewSession = NewSession(
        target = target,
        config = ChatConfig(
            harness = d.harness,
            model = d.model,
            reasoning = d.effort,
            modelOptions = emptyMap(),
            sandbox = SandboxLevel.WORKSPACE_WRITE,
        ),
        branch = if (d.worktree || side != null) null else d.branch,
        cwd = null,
        title = null,
        parentChatId = side?.chatId,
    )

    /**
     * `SendRequest` (`:632-637`): the encoded body verbatim, staged
     * images as JPEG attachments, the worktree spec only when the draft
     * asks for one **and** the picked project exists (`project.map` — a
     * worktree without a project is nil), base `branch ?: "HEAD"`,
     * delivery always `.queue` (a new chat is idle).
     */
    fun sendRequest(
        d: NewSessionDraft,
        text: String,
        images: List<StagedImage>,
        projects: List<ProjectOption>,
        side: SideChatParent? = null,
    ): SendRequest = SendRequest(
        text = text,
        attachments = images.map { image ->
            OutgoingAttachment(
                name = image.name,
                // iOS `AppModel.swift:647`: staged images are JPEG.
                mimeType = "image/jpeg",
                data = image.bytes,
            )
        },
        // A side chat never makes a worktree (iOS `draft.worktree && parentId == nil`).
        worktree = if (d.worktree && side == null) {
            projects.firstOrNull { it.id == d.projectId }?.let { project ->
                WorktreeSpec(
                    repoPath = project.path,
                    base = d.branch ?: "HEAD",
                    spaceId = project.id,
                )
            }
        } else {
            null
        },
        busy = BusyPolicy.QUEUE,
    )
}

/**
 * Create the chat, open it, send the first message
 * (`NewSessionViewController.swift:406-423` → `AppModel.swift:618-643`),
 * all inside one `withContext(io)` hop: **never** on the main thread
 * (the #609 trap — its port ran the FFI on main).
 *
 * Returns the scoped chat id, or `null` on any failure: no target
 * (no core call), no client, or `createSession` / `openSession` /
 * `send` threw (an offline engine refuses the write — `HostUnavailable`
 * — never queues). A failure after the create can leave an empty chat
 * in the list; that partial failure is iOS parity (see the ticket's
 * Comments).
 */
class NewSessionCreator(
    private val core: NewSessionCore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Production logs `Log.w("NewSession", …)`; tests swap this (the stub `android.util.Log` throws). */
    private val logW: (String, Throwable?) -> Unit = { message, error ->
        Log.w("NewSession", message, error)
    },
) {

    /** The scoped chat id, or null on any failure (see the class docs). */
    suspend fun createAndSend(
        d: NewSessionDraft,
        text: String,
        images: List<StagedImage>,
        projects: List<ProjectOption>,
        side: SideChatParent? = null,
    ): String? {
        // No target: fail without a core call and without a log — the
        // alert explains it, and there is no error to log.
        val target = NewSessionRequests.target(d, side) ?: return null
        return withContext(io) {
            try {
                val chatId = core.createSession(NewSessionRequests.newSession(d, target, side))
                core.sendFirst(chatId, NewSessionRequests.sendRequest(d, text, images, projects, side))
                chatId
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logW("create session failed", t)
                null
            }
        }
    }
}

/**
 * The failure alert (`NewSessionViewController.swift:410-413`, copy
 * verbatim): no field, one default `OK` with no handler. Shown for a
 * missing target, a missing client, or any core failure (offline
 * engine, `HostUnavailable`, RPC error) — the composer stays `Kept`.
 */
val StartFailedAlert = RobocoAlert(
    title = "Couldn't start the session",
    message = "Choose a project or a host that can run it.",
    buttons = listOf(AlertButton("OK", AlertButtonRole.Default)),
)

/**
 * A side chat's failure alert (android-next #06, upstream #899's
 * `create(sideOf:)` copy verbatim): the parent is gone or the create
 * failed — the composer stays `Kept`.
 */
val SideChatStartFailedAlert = RobocoAlert(
    title = "Couldn't start the side chat",
    message = "Its session is no longer available.",
    buttons = listOf(AlertButton("OK", AlertButtonRole.Default)),
)

/**
 * The canvas composer's offline gate (94's Roboco ADD, the parent
 * spec's "Send is disabled while the target engine is offline"): the
 * picked project's or host's engine must be online. An id **not** in
 * the options counts as online (`true`) — a stale id reaches the send
 * and the alert explains it, instead of a silently disabled button.
 * No target → `true` (same reason). No grace, no debounce (79).
 */
fun newSessionCanWrite(draft: NewSessionDraft, projects: List<ProjectOption>, hosts: List<HostOption>): Boolean = when {
    draft.projectId != null -> projects.firstOrNull { it.id == draft.projectId }?.online ?: true
    draft.hostId != null -> hosts.firstOrNull { it.id == draft.hostId }?.online ?: true
    else -> true
}
