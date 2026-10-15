package sh.roboco.android.session

import uniffi.roboco_core.CoreClient

/**
 * The core open, behind a seam (android-ui-parity #76): the generated
 * `CoreClient` is a JNA-backed object plain-JVM tests cannot construct,
 * so the route factory goes through this interface and tests fake it.
 * Blocking — the open reads the per-chat cache seed from disk when it
 * creates a new handle (android-app#08) — so it never runs on the main
 * thread; the factory is called on `Dispatchers.IO` (ticket 32's
 * `openSessionRoute`).
 */
fun interface SessionOpener {

    /**
     * Opens [chatId] in the core — `CoreClient.openSession(chatId)`,
     * which returns the warm handle or a freshly seeded one. `null`
     * when there is no client at all (before the startup load
     * finished, or after a core failure — iOS's `guard let client`,
     * `AppModel.swift:524`). Throws when the core refuses the id
     * (`CoreException.NotFound` for an unknown id or one whose engine
     * was forgotten or never paired, `CoreException.Closed` after
     * shutdown) — the caller logs and treats it as a failed open.
     */
    fun open(chatId: String): SessionCore?
}

/**
 * The live [SessionOpener]. [client] is ticket 71's holder —
 * `RobocoApplication.clientOrNull()`, which delegates to
 * `CoreStartup.clientOrNull()`: null before `CoreStartState.Ready` or
 * after a failed start, so an open in that window fails exactly like
 * iOS's `guard let client else { return nil }`. Anything the open
 * throws reaches the factory's catch (`AppModel.swift:528-531`).
 */
class CoreSessionOpener(
    private val client: () -> CoreClient?,
) : SessionOpener {

    override fun open(chatId: String): SessionCore? {
        val core = client() ?: return null
        return CoreSessionCore(core, core.openSession(chatId))
    }
}
