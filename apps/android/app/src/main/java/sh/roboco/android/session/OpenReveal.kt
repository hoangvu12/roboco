package sh.roboco.android.session

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

/**
 * The open choreography of one Session screen (android-ui-parity #75) —
 * the port of the hold, the reveal and the give-up of
 * `ios:Session/SessionViewController.swift:229-293`. Main thread only
 * (every field is snapshot state written from composition or the frame
 * listener).
 *
 * A pushed session first shows a spinner and hides the transcript: the
 * soft edge effect under the title cannot pick up content that appears
 * mid-push (it would read crisp under the title, then snap on). The
 * transcript fades in (180 ms ease-out) once **both** the push has landed
 * and the first frame with rows has been applied; if the hold is still on
 * 8 s after landing, the transcript is revealed anyway (an empty or
 * unreachable session must not spin forever).
 *
 * [reportedOpen] carries iOS's double duty: it is the reveal condition
 * ("the first rows have been applied") **and** the OPEN probe's once-flag,
 * so after a give-up no `OPEN` line is ever logged for that screen — the
 * probe measures successful opens (`SessionViewController.swift:288` sets
 * the same flag in the give-up path).
 *
 * The state machine (every transition is in the ticket's table):
 *
 * | Event | Effect |
 * |---|---|
 * | [begin] (first composition, once per screen state) | hold → `Holding`, progress 0; otherwise `Idle`, progress 1 |
 * | [onLanded] (the push transition completed) | `landed = true`, try reveal |
 * | [onFirstRows] (a frame with rows was applied) | first call: `reportedOpen = true`, try reveal, `true`; later calls `false` |
 * | [giveUp] (8 s after landing, still holding) | `reportedOpen = true`, try reveal |
 * | [finishReveal] (the tween ended, or reduce motion) | progress 1, `Shown` |
 */
@Stable
class OpenReveal {

    /** The four states of the screen's open phase. */
    enum class Phase { Idle, Holding, Revealing, Shown }

    /** `Idle` until [begin]; never returns to it. */
    var phase by mutableStateOf(Phase.Idle)
        private set

    /** True once the push transition to this screen completed (`viewDidAppear`). */
    var landed by mutableStateOf(false)
        private set

    /**
     * iOS's one flag for "the first rows have been applied" and "the OPEN
     * probe has been emitted" (`SessionViewController.swift:21`). Plain on
     * purpose: it changes only with [phase], which is snapshot state.
     */
    var reportedOpen = false
        private set

    /**
     * 0 = transcript hidden / loader shown, 1 = transcript shown. Written
     * by the reveal animation in `SessionScaffold` (hence `internal set`);
     * read only in draw-phase lambdas so the fade never recomposes a layer.
     */
    var progress by mutableFloatStateOf(1f)
        internal set

    /** The transcript's alpha: [progress]. */
    val transcriptAlpha: Float get() = progress

    /** The loader's alpha: `1 − progress` (the same curve, inverted). */
    val loaderAlpha: Float get() = 1f - progress

    /** True while the loader occupies the transcript area (spinner + fade-out). */
    val loaderComposed: Boolean get() = phase == Phase.Holding || phase == Phase.Revealing

    /**
     * True while the header's edge effect must snap (settle) instead of
     * fading its engagement over 250 ms: the reveal's first frame must
     * already show the blurred edge, the artefact upstream #584 fixed.
     * `SessionScaffold` composes the header under
     * `LocalReduceMotion provides true` while this is set.
     */
    val settlingEdge: Boolean get() = phase == Phase.Holding || phase == Phase.Revealing

    /** The once-guard of [begin] (`hasAppeared`-style: one decision per screen). */
    private var begun = false

    /**
     * The first-composition decision (once per screen state; later calls
     * are ignored, so a host that re-enters composition changes nothing).
     * `begin(true)`: hold — `phase = Holding`, `progress = 0`.
     * `begin(false)`: no hold — `Idle`, `progress` stays 1.
     */
    fun begin(hold: Boolean) {
        if (begun) return
        begun = true
        if (hold) {
            phase = Phase.Holding
            progress = 0f
        }
    }

    /** The push landed (`viewDidAppear`, ticket 24's `NavEntryEffect`). */
    fun onLanded() {
        landed = true
        tryReveal()
    }

    /**
     * A frame was applied; the caller checks `rowCount > 0` itself. Returns
     * `true` when this is the **first** rows frame (the caller then emits
     * the OPEN probe), `false` afterwards or after a give-up.
     */
    fun onFirstRows(): Boolean {
        if (reportedOpen) return false
        reportedOpen = true
        tryReveal()
        return true
    }

    /**
     * The 8 s give-up: a no-op unless the hold is still on. Fakes
     * `reportedOpen` (iOS `:288`) and tries the reveal — which also
     * suppresses the probe for this screen, by design.
     */
    fun giveUp() {
        if (phase != Phase.Holding) return
        reportedOpen = true
        tryReveal()
    }

    /** The end of the reveal: both alphas land at once, no animation. */
    fun finishReveal() {
        progress = 1f
        phase = Phase.Shown
    }

    /** Both conditions met (push landed, rows in): start the reveal. */
    private fun tryReveal() {
        if (phase == Phase.Holding && landed && reportedOpen) {
            phase = Phase.Revealing
        }
    }

    companion object {
        /**
         * The hold decision, made at **first composition** (the port of
         * `viewIsAppearing`'s guard, `SessionViewController.swift:243`):
         * an animated push to this screen, that has not appeared yet,
         * whose arrival was not prepared (the new-session handoff sets
         * `list.alpha = 0` and pushes unanimated — it fails two guards).
         */
        fun shouldHold(
            animatedPushToThis: Boolean,
            hasAppeared: Boolean,
            arrivalPrepared: Boolean,
        ): Boolean = animatedPushToThis && !hasAppeared && !arrivalPrepared
    }
}

/**
 * The loader box's top-left corner in dp (pure, ticket 75's geometry):
 * a 12 dp glyph at `scale` — 21 dp at the ×1.75 the loader uses — centred
 * on the **window's** x (`view.centerXAnchor`) and on the **safe area's**
 * y centre minus 40 (`SessionViewController.swift:252-253`). The safe area
 * under a pushed session's nav bar is `statusBarTop + 44` at the top and
 * the home indicator ([navBarBottom]) at the bottom: the tab bar is hidden
 * (`hidesBottomBarWhenPushed`) and the keyboard is not part of it.
 *
 * Worked values (the ticket's table, pinned by `OpenRevealTest`):
 * iPhone 16 Pro 402×874 (62, 34) → (190.5, 422.5); the 412×900 fixture
 * (24, 0) → (195.5, 433.5); Pixel gesture nav 412×915 (24, 24) →
 * (195.5, 429.0); Pixel 3-button 412×915 (24, 48) → (195.5, 417.0).
 */
fun loaderTopLeft(
    width: Float,
    height: Float,
    statusBarTop: Float,
    navBarBottom: Float,
    bar: Float = 44f,
    lift: Float = 40f,
    box: Float = 21f,
): Offset {
    val safeTop = statusBarTop + bar
    val safeBottom = height - navBarBottom
    return Offset(width / 2f - box / 2f, (safeTop + safeBottom) / 2f - lift - box / 2f)
}
