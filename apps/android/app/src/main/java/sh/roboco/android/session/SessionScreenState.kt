package sh.roboco.android.session

import androidx.compose.runtime.Stable
import sh.roboco.android.transcript.TranscriptState

/**
 * Per Session screen state (android-ui-parity #75): everything that must
 * exist **before the push** and survive a tab-host disposal, in one object
 * held by the route (ticket 76's `SessionScreenRoute` builds it in
 * `SessionRouteFactory.open`; 77's `prepareArrival()` reaches the same
 * instance before composition; 85 relies on it surviving a tab switch).
 *
 * `SessionScreen` never creates one — a `remember {}` there would recreate
 * it on every tab switch. Plain JUnit tests construct one with a throwing
 * [transcriptFactory] and never read [transcript].
 *
 * @param chatId the session's scoped chat id
 * @param openedAtNanos `System.nanoTime()` at the **start** of
 *   `SessionRouteFactory.open` (76) — the port of iOS's `openedAt`, a
 *   stored property Swift evaluates before `app.sessionSource(chatId)`
 *   opens the session in the core, so `openMs` covers: core open + push +
 *   first layout pass + first apply
 * @param transcriptFactory main thread; production passes
 *   `{ TranscriptState(TextEngine.shared) }` — the engine is created by
 *   60's app-start prewarm, so the first read only wires it up
 */
@Stable
class SessionScreenState(
    val chatId: String,
    val openedAtNanos: Long,
    transcriptFactory: () -> TranscriptState,
) {
    // LazyThreadSafetyMode.NONE: the factory is main-thread-only and the
    // first read happens during SessionScreen's first composition.
    private val transcriptLazy = lazy(LazyThreadSafetyMode.NONE, transcriptFactory)

    /** MAIN THREAD ONLY: the first read creates the [TranscriptState]. */
    val transcript: TranscriptState get() = transcriptLazy.value

    /** True once [transcript] was created (76's discard/release close it only then). */
    val transcriptCreated: Boolean get() = transcriptLazy.isInitialized()

    /** The open choreography (hold, reveal, give-up, the probe's flag). */
    val reveal = OpenReveal()

    /**
     * Ticket 77's arrival hooks (the new-session handoff): the two
     * alpha channels, the `prepared` flag the hold decision reads, and
     * the own turn deferred to [SessionArrival.bind] when the router
     * prepared the arrival before the push. Held here so the route (76)
     * reaches it before composition and the screen (75) binds it at
     * first composition.
     */
    val arrival = SessionArrival()

    /**
     * True once the arrival was prepared (77): a handoff skips 75's
     * open loader, hold and reveal — the explicit port of iOS's
     * `list.alpha > 0` guard (`SessionViewController.swift:245`).
     * Mirrors [SessionArrival.prepared] for `OpenReveal.shouldHold`.
     */
    val arrivalPrepared: Boolean get() = arrival.prepared

    /** attach + markSeen + the frame listener were done (once per screen). */
    internal var started = false

    /**
     * The frame listener's hook (main thread): returns the probe JSON when
     * this is the **first** applied frame with rows, `null` for an empty
     * frame, a second rows frame, or after a give-up. The caller
     * (`SessionScreen`) passes the JSON to [OpenProbe.emit].
     *
     * @param rowCount the frame's `rowCount()` (converted from `UInt`)
     * @param buildMicros the frame's `buildMicros()` (converted from `ULong`)
     *   — worker time of the layout pass that produced the frame
     * @param nowNanos `System.nanoTime()` at the moment the listener runs
     */
    fun onFrameApplied(rowCount: Long, buildMicros: Long, nowNanos: Long = System.nanoTime()): String? {
        if (rowCount <= 0L || !reveal.onFirstRows()) return null
        return OpenProbe.json((nowNanos - openedAtNanos) / 1_000_000.0, rowCount, buildMicros)
    }
}
