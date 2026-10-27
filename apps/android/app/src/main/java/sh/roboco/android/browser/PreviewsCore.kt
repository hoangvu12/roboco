package sh.roboco.android.browser

import uniffi.roboco_core.CoreClient
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.PreviewPanel
import uniffi.roboco_core.PreviewPanelListener
import uniffi.roboco_core.PreviewPanelState
import uniffi.roboco_core.PreviewProxyStatus

/**
 * The preview FFI seam of the agent-previews phone half (ticket 05) — the
 * three `CoreClient` calls behind the panel and the browser entry:
 * `watchPreviews` (the per-chat `WatchPreviews` subscription whose state
 * callback drives the panel), `previewProxyStatus` (the in-app 7332
 * listener) and `previewProxySetWatching` (its start/stop control).
 *
 * `watch`/`proxyStatus` are suspend — run them on an IO lane, never main.
 * `setProxyWatching` is a cheap in-memory Rust call (it only spawns the
 * listener task). A seam so the plain-JVM tests fake it (the generated
 * records construct without JNA, the calls do not); failures cross as the
 * generated [CoreException] (`watchPreviews` refuses an unpaired engine
 * with `NotFound` — the panel degrades to the honest note).
 *
 * [PanelWatch] abstracts the FFI panel object the same way, so the model's
 * tests drive a fake instead of the UniFFI object.
 */
interface PreviewsCore {

    /**
     * `CoreClient.watchPreviews(chatId, listener)` — starts (or returns)
     * the chat's panel watch. States arrive on [listener] on a Rust
     * runtime thread: hop to a coroutine, never block.
     */
    suspend fun watch(chatId: String, listener: PreviewPanelListener): PanelWatch

    /** `CoreClient.previewProxyStatus()` — the bound port or the inline bind error. */
    suspend fun proxyStatus(): PreviewProxyStatus

    /**
     * `CoreClient.previewProxySetWatching(engineId, watching)` — the
     * listener's start/stop control for one engine (lazy bind, idle
     * shutdown). Cheap: the Rust side only spawns the listener task, so it
     * is safe from any thread. Set semantics on the Rust side: one `false`
     * clears the engine's watcher, so an owner that did not set `true`
     * must not send `false` (the panel's own watch already holds the
     * engine's watcher while it is open).
     */
    fun setProxyWatching(engineId: String, watching: Boolean)
}

/** A live per-chat panel watch over the FFI object. Cheap; idempotent close. */
interface PanelWatch {

    /** The latest state (cheap; never blocks). */
    fun state(): PreviewPanelState

    /** Stop watching: the state callback fires no more. Idempotent. */
    fun stop()
}

/** The live [PreviewsCore] over the process's single [CoreClient]. */
class CorePreviewsCore(
    private val client: () -> CoreClient?,
) : PreviewsCore {

    override suspend fun watch(chatId: String, listener: PreviewPanelListener): PanelWatch =
        CorePanelWatch(live().watchPreviews(chatId, listener))

    override suspend fun proxyStatus(): PreviewProxyStatus = live().previewProxyStatus()

    override fun setProxyWatching(engineId: String, watching: Boolean) {
        live().previewProxySetWatching(engineId, watching)
    }

    /** The live client, or a `Closed` error when the core has not started. */
    private fun live(): CoreClient = client() ?: throw CoreException.Closed()
}

/** [PanelWatch] over the UniFFI [PreviewPanel] object. */
private class CorePanelWatch(
    private val panel: PreviewPanel,
) : PanelWatch {
    override fun state(): PreviewPanelState = panel.state()
    override fun stop() = panel.stop()
}
