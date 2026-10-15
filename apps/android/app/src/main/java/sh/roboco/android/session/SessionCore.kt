package sh.roboco.android.session

import uniffi.roboco_core.ChatConfig
import uniffi.roboco_core.CoreClient
import uniffi.roboco_core.EngineView
import uniffi.roboco_core.ComposerState
import uniffi.roboco_core.SessionHandle
import uniffi.roboco_core.SessionRow
import uniffi.roboco_core.TranscriptView

/**
 * The narrow FFI surface [SessionController] needs (android-ui-parity
 * #51) — fakeable, because the generated `CoreClient` /
 * `SessionHandle` are JNA-backed objects that cannot be constructed on
 * the JVM. Every method blocks in FFI and must run on the controller's
 * serial IO lane, never on main.
 *
 * Names follow the UniFFI output of `crates/mobile/src/client_ffi`
 * (`chatId()`, `composer()`, `sessionRow(chatId)`, `engine(engineId)`,
 * `markSeen(chatId)`, `setViewAttached(attached)`,
 * `TranscriptView.attach(client, chatId)`).
 */
interface SessionCore {
    /** The session's scoped chat id (`SessionHandle.chatId()`). */
    val chatId: String

    /**
     * The owning engine's id (`SessionHandle.engineId()`), read once —
     * it never changes. The engine half of the per-chat draft key
     * (ticket 84: `DraftKey.Chat(engineId, chatId)`); exposed on the
     * seam rather than parsing the `engine:v1:…` scoped id in Kotlin,
     * which belongs to Rust.
     */
    val engineId: String

    /** `SessionHandle.composer()` — the composer-facing pull. */
    fun composer(): ComposerState

    /** `CoreClient.sessionRow(chatId)` — the cached workspace row, any chat. */
    fun sessionRow(): SessionRow?

    /**
     * `CoreClient.sessionConfig(chatId)` — the chat's local run config
     * (android-app#07). Ticket 89's chip menus read it on the controller's
     * IO lane so their provider never makes an FFI call on main.
     */
    fun sessionConfig(): ChatConfig?

    /** `CoreClient.engine(handle.engineId())` — the owning engine's view. */
    fun engine(): EngineView?

    /**
     * `TranscriptView.attach(client, chatId)` (android-app#08's layout
     * seam). `false` means the chat has no open session; the controller
     * logs it and carries on (iOS ignores the Bool too,
     * `CoreSessionSource.swift:26-29`).
     */
    fun attachTranscript(view: TranscriptView): Boolean

    /** `SessionHandle.setViewAttached(attached)`. */
    fun setViewAttached(attached: Boolean)

    /** `CoreClient.markSeen(chatId)` — through the client, not the handle (`AppModel.swift:519`). */
    fun markSeen()
}

/**
 * The live [SessionCore] over one open session. Ticket 76 builds this
 * after `CoreClient.openSession(chatId)` succeeded, off main; sibling
 * adapters (78, 82, 83, 84, 86, 89) use [client]/[handle] for their own
 * calls. [engineId] is read once in the constructor — it never changes
 * — so the first `engine()` pull and every later one cost one FFI call
 * less, and 84's draft key reads it without another call.
 */
class CoreSessionCore(
    val client: CoreClient,
    val handle: SessionHandle,
) : SessionCore {
    override val chatId: String = handle.chatId()

    override val engineId: String = handle.engineId()

    override fun composer(): ComposerState = handle.composer()

    override fun sessionRow(): SessionRow? = client.sessionRow(chatId)

    override fun sessionConfig(): ChatConfig? = client.sessionConfig(chatId)

    override fun engine(): EngineView? = client.engine(engineId)

    override fun attachTranscript(view: TranscriptView): Boolean = view.attach(client, chatId)

    override fun setViewAttached(attached: Boolean) = handle.setViewAttached(attached)

    override fun markSeen() = client.markSeen(chatId)
}
