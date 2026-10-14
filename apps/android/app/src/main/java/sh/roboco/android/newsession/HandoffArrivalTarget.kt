package sh.roboco.android.newsession

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.ui.geometry.Rect
import sh.roboco.android.session.SessionScreenRoute
import sh.roboco.android.shell.SessionRoute

/**
 * What the handoff animator needs from the arriving session
 * (android-ui-parity #95) — exactly ticket 77's arrival hooks, in root
 * px:
 *
 * - [arrivalComposerBounds] / [arrivalBubbleBounds] — the glide and
 *   flight targets, `null` until laid out (the composer is re-read
 *   **every glide frame**: it rides the IME down);
 * - [revealArrivalComposer] / [revealArrivalTranscript] — the two
 *   fades, animated with the caller's specs (95's);
 * - [finishArrival] — everything visible again.
 *
 * The method names and types match `SessionArrival` one for one, so the
 * adapter below is a plain forwarder.
 */
interface HandoffArrivalTarget {

    /** The arriving composer's root bounds, root px, unclipped; may be null. */
    fun arrivalComposerBounds(): Rect?

    /** The first user bubble's root bounds, root px; may be null. */
    fun arrivalBubbleBounds(): Rect?

    /** `revealArrivalComposer` over the bottom stack's alpha (ticket 77). */
    suspend fun revealArrivalComposer(spec: AnimationSpec<Float>)

    /** `revealArrivalTranscript` over the transcript's alpha (ticket 77). */
    suspend fun revealArrivalTranscript(spec: AnimationSpec<Float>)

    /** `finishArrival`: show everything (ticket 77). */
    fun finishArrival()
}

/**
 * The only place that knows where ticket 77 keeps the arrival hooks:
 * `(route as? SessionScreenRoute)?.arrival`. Null for any other route
 * type — the animator then takes ticket 32's Instant path (reveal at
 * once, no motion).
 */
fun arrivalTargetOf(route: SessionRoute): HandoffArrivalTarget? {
    val arrival = (route as? SessionScreenRoute)?.arrival ?: return null
    return object : HandoffArrivalTarget {
        override fun arrivalComposerBounds(): Rect? = arrival.arrivalComposerBounds()
        override fun arrivalBubbleBounds(): Rect? = arrival.arrivalBubbleBounds()
        override suspend fun revealArrivalComposer(spec: AnimationSpec<Float>) =
            arrival.revealArrivalComposer(spec)
        override suspend fun revealArrivalTranscript(spec: AnimationSpec<Float>) =
            arrival.revealArrivalTranscript(spec)
        override fun finishArrival() = arrival.finishArrival()
    }
}
