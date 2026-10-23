package sh.roboco.android.notify

/**
 * One working run as the ongoing notification renders it (a Kotlin-side
 * projection of the FFI `RunRef` — the pure builder stays free of uniffi
 * types so its tests are plain JVM).
 */
data class WorkingRun(
    /** The engine-scoped chat id (the deep link's id). */
    val chatId: String,
    val title: String,
    val engineName: String,
    /** `Session.started_at` in epoch ms, for the chronometer. */
    val startedAtMs: Long?,
    val fromThisDevice: Boolean,
)

/** The inputs the ongoing content derives from. */
data class OngoingInput(
    val working: List<WorkingRun>,
    /** Top-level chats in AwaitingInput (never side chats). */
    val awaiting: Int,
    /** Paired engines; ≥ 2 shows the engine name as subText. */
    val engineCount: Int,
    /** The user dismissed the Live Update: no promotion until the working set changes. */
    val liveUpdateDismissed: Boolean,
)

/**
 * The foreground service's ongoing notification content (the ticket's
 * table). Pure — `RunWatchService` turns it into the posted notification.
 *
 * | Working runs | Title | Text | Chip | Tap |
 * |---|---|---|---|---|
 * | 1 | chat title / "New session" | "Working" (iOS row status word) | "Working" | that chat |
 * | n ≥ 2 | iOS accessory summary: "n working" (+ " · k need(s) you") | titles joined ", " | "n runs" | app root |
 */
data class OngoingContent(
    val title: String,
    val text: String,
    /** `setShortCriticalText` (≤ 7 chars shown in full). */
    val chip: String,
    /** `setWhen(min(startedAtMs))`; `null` skips the chronometer. */
    val whenMs: Long?,
    /** The engine name when n = 1 and ≥ 2 engines are paired; else null. */
    val subText: String?,
    /** The chat to open on tap (null = the app root, the Sessions tab). */
    val tapChatId: String?,
    /** `setRequestPromotedOngoing` — see [ongoingContent]. */
    val promoteLiveUpdate: Boolean,
    /**
     * True while runs are Working (the indeterminate progress bar and
     * `CATEGORY_PROGRESS`); false for the idle "Staying connected" content.
     */
    val working: Boolean = true,
)

/** The idle content's copy (ADR 0010; new copy, no iOS/web source). */
object OngoingCopy {
    const val IdleTitle = "Staying connected"

    /** "Keeping 1 engine connected" / "Keeping 2 engines connected". */
    fun idleText(engineCount: Int): String =
        if (engineCount == 1) "Keeping 1 engine connected" else "Keeping $engineCount engines connected"
}

/**
 * Derive the content. `apiPromotionPossible` folds the API check (36+) and
 * `canPostPromotedNotifications()` — the caller reads the platform, this
 * stays pure. Promotion needs ≥ 1 `fromThisDevice` run (desktop-started
 * runs are ambient, and the Live Update policy demands "user-initiated")
 * and no standing dismissal.
 */
fun ongoingContent(input: OngoingInput, nowMs: Long, apiPromotionPossible: Boolean): OngoingContent {
    val working = input.working
    if (working.isEmpty()) {
        // The service is up with nothing Working: the linger after the
        // last run, or Stay connected (ADR 0010). A quiet status line, no
        // chronometer, no progress, never promoted; the tap opens the app.
        val text = buildString {
            append(OngoingCopy.idleText(input.engineCount))
            if (input.awaiting > 0) {
                append(" · ")
                append(input.awaiting)
                append(if (input.awaiting == 1) " needs you" else " need you")
            }
        }
        return OngoingContent(
            title = OngoingCopy.IdleTitle,
            text = text,
            chip = "",
            whenMs = null,
            subText = null,
            tapChatId = null,
            promoteLiveUpdate = false,
            working = false,
        )
    }
    if (working.size == 1) {
        val run = working[0]
        return OngoingContent(
            title = run.title.ifBlank { "New session" },
            text = "Working",
            chip = "Working",
            whenMs = run.startedAtMs ?: nowMs,
            subText = if (input.engineCount >= 2) run.engineName else null,
            tapChatId = run.chatId,
            promoteLiveUpdate = apiPromotionPossible && !input.liveUpdateDismissed && run.fromThisDevice,
        )
    }
    val summary = buildString {
        append("${working.size} working")
        if (input.awaiting > 0) {
            append(" · ")
            append(input.awaiting)
            append(if (input.awaiting == 1) " needs you" else " need you")
        }
    }
    return OngoingContent(
        title = summary,
        text = working.joinToString(", ") { it.title.ifBlank { "New session" } },
        chip = "${working.size} runs",
        whenMs = working.minOfOrNull { it.startedAtMs ?: nowMs },
        subText = null,
        tapChatId = null,
        promoteLiveUpdate = apiPromotionPossible && !input.liveUpdateDismissed &&
            working.any { it.fromThisDevice },
    )
}
