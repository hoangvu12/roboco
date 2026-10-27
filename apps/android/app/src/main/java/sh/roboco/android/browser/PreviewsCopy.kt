package sh.roboco.android.browser

/**
 * The preview surfaces' copy (agent-previews ticket 05) — the desktop
 * `preview_body` vocabulary (`ui/src/browser/view.rs`) verbatim where the
 * phone shows the same thing: the remote subtitle, the row label shape
 * (`device · localhost:port`), the loading and reconnect states, and the
 * honest remote empty state. The ellipses are U+2026.
 */
object PreviewsCopy {

    /** The panel page's nav title and TalkBack pane title. */
    const val PageTitle = "Previews"

    /** The panel's title with the chat's title as context (`Previews · Wrangler deploy hygiene`). */
    fun pageTitle(chatTitle: String): String =
        if (chatTitle.isBlank()) PageTitle else "$PageTitle \u00b7 $chatTitle"

    /** The session ⋯ menu's row that pushes the panel. */
    const val SessionMenuRow = "Previews"

    /** The section header above the rows (the desktop's remote subtitle). */
    const val RunningOnYourDevice = "Running on your device"

    /** The loading state (`Looking for dev servers…`). */
    const val Loading = "Looking for dev servers\u2026"

    /**
     * The reconnect state — the desktop's wording when the engine session
     * is offline, revoked or re-dialing and the relayed previews went with
     * it (matching how a chat degrades).
     */
    const val Reconnecting = "Connecting to preview discovery\u2026"

    /** The honest remote empty state (the desktop's exact sentence). */
    const val EmptyRemote =
        "Start a dev server in this project on your other device. " +
            "Its preview will appear here when that device is online."

    /** The engine-too-old note (the `unsupported` phase). */
    const val Unsupported =
        "This Roboco is too old to show previews. Update it on the device running this session."

    /** The browser entry's main-frame failure note. */
    const val LoadFailed = "This preview could not be loaded."

    /** The row's Open affordance (the desktop's `Open` pill). */
    const val Open = "Open"

    /** The browser entry's back affordance. */
    const val Back = "Back"

    /** The browser entry's reload affordance. */
    const val Reload = "Reload"

    /** The browser entry's loading line. */
    const val BrowserLoading = "Loading\u2026"

    /** The row's subtitle: `device · localhost:port` (the desktop's label). */
    fun rowSubtitle(deviceName: String, port: UShort): String =
        "$deviceName \u00b7 localhost:$port"
}
