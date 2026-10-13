package sh.roboco.android.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import sh.roboco.android.shell.LocalNavEntry
import sh.roboco.android.shell.NavRoute
import sh.roboco.android.shell.SessionRoute
import sh.roboco.android.transcript.TranscriptState
import uniffi.roboco_core.TextSystem

/**
 * One opened session (android-ui-parity #76) — the port of
 * `SessionViewController` after a successful `init?`
 * (`ios:Session/SessionViewController.swift:23-30`): the controller, the
 * core seam and the per-screen state a push needs, all built by
 * [CoreSessionRouteFactory] off main and pushed by tickets 32/41/106.
 *
 * **Identity equality on purpose** (not a data class): iOS allows "a
 * second copy of this chat" (`SessionViewController.swift:176-178`) and
 * ticket 24 requires identity equality — two opens of one chat are two
 * routes with two controllers.
 */
class SessionScreenRoute internal constructor(
    /** 51's controller: chrome, attach, markSeen, this route's filler instances. */
    val controller: SessionController,
    /** For sibling adapters (78, 82, 83, 66, 86, 89): cast to [CoreSessionCore] for the client and the handle. */
    val core: SessionCore,
    /** Ticket 75's per-screen state; `openedAtNanos` was stamped at the start of the factory's open. */
    val state: SessionScreenState,
    /**
     * Ticket 60's `TextEngine.shared`, read off main by the factory and
     * captured by the state's transcript factory. Never closed here —
     * the shared engine lives for the process.
     */
    private val text: TextSystem,
) : SessionRoute, NavRoute {

    /** The session's scoped chat id (`SessionHandle.chatId()`, through the controller). */
    override val chatId: String
        get() = controller.chatId

    /**
     * iOS `hidesBottomBarWhenPushed = true` (`SessionViewController.swift:29`)
     * — the tab pill and the accessory hide while a session is on top.
     * The only screen that sets it.
     */
    override val hidesBottomChrome: Boolean
        get() = true

    /**
     * TalkBack pane title (ticket 24): the chrome's title when the layer
     * composes, `null` when it is empty. The first refresh already ran
     * synchronously in `SessionController.create`, so the title is there
     * on the first frame of the push (no empty-title flash).
     */
    override val paneTitle: String?
        get() = controller.chrome.value.title.ifEmpty { null }

    /**
     * Ticket 61's transcript, held lazily by 75's [SessionScreenState]
     * (the factory stored `transcriptFactory = { TranscriptState(text) }`).
     * MAIN THREAD ONLY: created on the first access — [Content] here, or
     * ticket 77's `prepareArrival()` — never from the factory.
     */
    val transcript: TranscriptState
        get() = state.transcript

    /**
     * Teardown of a screen that **was** on screen (a committed pop, a
     * `popToRoot`, a `setRoutes` removal — through the NavEntry close
     * safety net in [Content]): `controller.close()`
     * (`setViewAttached(false)` + `markSeen`, tickets 51/85), then the
     * transcript, only if it was already created. Main thread;
     * idempotent (51's and 61's `close()` are). Ticket 85 replaces the
     * safety net with its `lifecycle.release()`; keep exactly one.
     */
    fun release() {
        controller.close()
        if (state.transcriptCreated) state.transcript.close()
    }

    @Composable
    override fun Content() {
        // The NavEntry close safety net: any removal path (a committed
        // pop, popToRoot, setRoutes — see NavEntry.close) closes the
        // controller and the transcript exactly once, and it survives a
        // host disposal (tab switches, configuration changes) because
        // the entry's retained map does.
        val entry = LocalNavEntry.current
        remember(entry) {
            entry.retain(CloseKey) {
                object : AutoCloseable {
                    override fun close() {
                        release()
                    }
                }
            }
        }
        // Ticket 75's screen (the scaffold, the first-composition wiring,
        // the layer stack). 80 adds `onMore`; 66 the attachment reader.
        // 78: the composer's command set, once per route — over this
        // route's core (a CoreSessionCore holds the client and the
        // handle), reading the controller's host device at call time. A
        // test core that is not a CoreSessionCore yields null and the
        // composer slot stays empty.
        val commands: SessionCommands? = remember(entry) {
            (core as? CoreSessionCore)?.let { c ->
                entry.retain(CommandsKey) {
                    CoreSessionCommands(c.client, c.handle, c.chatId) { controller.hostDeviceId }
                }
            }
        }
        SessionScreen(controller, state, commands = commands)
    }

    /** Ticket 77 fills these; 32's default `HandoffAnimator.Instant` calls `finishArrival()`, so empty bodies are safe until then. */
    override fun prepareArrival() { /* ticket 77 */ }

    /** See [prepareArrival]. */
    override fun finishArrival() { /* ticket 77 */ }

    /**
     * A route that was **never** pushed (superseded, or the caller was
     * cancelled — ticket 32's `openSessionRoute` and 41's
     * `SessionListOpenGate`): `controller.dispose()`, which makes no
     * controller FFI call, then the transcript only if it was created.
     * Main thread; idempotent. Never `controller.close()` — no
     * `setViewAttached`/`markSeen` for a screen nobody saw.
     */
    override fun discard() {
        controller.dispose()
        if (state.transcriptCreated) state.transcript.close()
    }

    private object CloseKey

    /** 78's command-set retention key. */
    private object CommandsKey
}
