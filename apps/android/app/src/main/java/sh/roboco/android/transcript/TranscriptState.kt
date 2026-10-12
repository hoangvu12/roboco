package sh.roboco.android.transcript

import android.os.Looper
import android.view.Choreographer
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import sh.roboco.android.core.TextEngine
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.LayoutListener
import uniffi.roboco_core.TextSystem
import uniffi.roboco_core.TranscriptView

/**
 * Runs on the main thread after each applied frame, in registration order.
 * [old] is the previously applied frame (`null` for the first), [new] the
 * frame that just became [TranscriptState.frame].
 */
fun interface FrameListener {
    fun onFrame(old: LayoutFrame?, new: LayoutFrame)
}

/**
 * The data half of the transcript host (ticket 61): it owns one
 * [TranscriptView], relays layout-thread frame notifications to the main
 * thread through [FrameRelay] (one coalesced apply per vsync), forwards the
 * viewport only when it changes, and keeps the applied frame, the
 * [uploadProgress] and the [fonts] style table as Compose-observable
 * state. It draws nothing; scrolling, anchoring, row motion and painting
 * belong to later tickets (62–69, 117, 130, 132), which read [frame] and
 * register [FrameListener]s.
 *
 * Construct on the main thread (the production constructor checks it
 * before creating the Rust engine, which spawns the `roboco-layout`
 * worker). Owners pass `TextEngine.await()` from a main-dispatcher
 * coroutine; the default argument is a convenience for tests and the lab.
 * [close] is mandatory for every owner — the Rust side holds the relay,
 * the relay holds this state and every frame until the worker exits.
 */
@Stable
class TranscriptState internal constructor(
    text: TextSystem,
    poster: FramePoster,
    makeEngine: (TextSystem, LayoutListener) -> TranscriptView,
) {
    /**
     * Production constructor: main thread only. The check runs inside
     * `mainChoreographer()`, while the delegation arguments are evaluated —
     * before the primary constructor creates the Rust engine — so a wrong
     * thread never leaks a running layout worker.
     */
    constructor(text: TextSystem = TextEngine.shared) :
        this(text, ChoreographerPoster(mainChoreographer()), ::TranscriptView)

    private val relay = FrameRelay(poster) { applyLatest() }

    /** The engine, for `attach` (51), `setDebugEntries` (135) and toggles (120). Check [isClosed] after teardown. */
    val engine: TranscriptView = makeEngine(text, relay)

    /** `null` until the first relayed frame, then the newest applied frame. Not cleared by [close]. */
    var frame by mutableStateOf<LayoutFrame?>(null)
        private set

    /**
     * Attachment upload progress (0.0–1.0), `null` when nothing is in
     * flight: drawn as rings on pending thumbnails (ticket 67), fed by the
     * session chrome (ticket 78). Equal writes do not invalidate.
     */
    var uploadProgress by mutableStateOf<Double?>(null)

    /** The style table for this transcript, synced in [apply] step 4 (data half; ticket 62 adds the paints). */
    val fonts = StyleFonts()

    /** The last width sent to the engine. `0f` until [setViewport] first fires. */
    var viewportWidthDp = 0f
        private set

    /** The last text scale sent to the engine. `1f` until [setViewport] first fires. */
    var textScale = 1f
        private set

    /** `true` after [close]. */
    var isClosed = false
        private set

    private val listeners = ArrayList<FrameListener>()

    /**
     * Forwards the viewport (width in dp plus the Dynamic-Type text scale)
     * to the engine, only when one of them changed (exact compare — the
     * worker runs a full pass on every viewport message, even an identical
     * one, so Kotlin-side dedup saves it). No-op once closed.
     */
    fun setViewport(widthDp: Float, textScale: Float) {
        if (isClosed) return
        if (widthDp != viewportWidthDp || textScale != this.textScale) {
            viewportWidthDp = widthDp
            this.textScale = textScale
            engine.setViewport(widthDp, textScale)
        }
    }

    /**
     * Registers a hook that runs on main after each applied frame; returns
     * its remover. Ticket 121 owns the first slot (registered in `init` by
     * that ticket); owners register theirs after construction.
     */
    fun addFrameListener(l: FrameListener): () -> Unit {
        listeners += l
        return { listeners -= l }
    }

    /** The relay's apply hop: pull the newest published frame and apply it. */
    private fun applyLatest() {
        if (!isClosed) apply(engine.frame())
    }

    /**
     * The apply sequence, once per relay hop (main thread):
     * 1. if closed, return;
     * 3. skip a duplicate revision (a hop can pull a frame the previous
     *    hop already applied — revisions increase monotonically per view);
     * 4. sync [fonts] when the style count moved;
     * 5. swap [frame];
     * 6. notify the listeners over a copy, so one can remove itself.
     * (Step 2 — the pull — lives in [applyLatest].) Later tickets insert
     * their work between steps 3 and 5.
     */
    @VisibleForTesting
    internal fun apply(new: LayoutFrame) {
        if (isClosed) return
        val old = frame
        if (old != null && new.revision() == old.revision()) return
        if (new.styleCount().toInt() != fonts.count) fonts.update(new.styles())
        frame = new
        listeners.toList().forEach { it.onFrame(old, new) }
    }

    /**
     * Idempotent teardown: cancel a pending relay hop, stop the worker
     * (`shutdown`, the Rust `close`) and free the Rust handle (`close`),
     * then drop the listeners. The last [frame] stays, so a screen still
     * animating out keeps painting. Mandatory on a real pop, never on a
     * transient composition disposal (cancelled predictive back).
     */
    fun close() {
        if (isClosed) return
        isClosed = true
        relay.close()
        engine.shutdown()
        engine.close()
        listeners.clear()
    }

    companion object {
        /**
         * The body text style's Dynamic-Type factor
         * (`UIFontMetrics(forTextStyle: .body).scaledValue(for: 17) / 17`,
         * `ios:Transcript/TranscriptListView.swift:203-212`), **unclamped**.
         * Goes through `17.sp.toDp()` so Compose's copy of the Android 14
         * non-linear font curves applies (linear below fontScale 1.03,
         * interpolated between table entries above).
         */
        fun bodyTextScale(density: Density): Float = with(density) { 17.sp.toDp().value } / 17f

        /** Captured on main, before the engine exists. */
        private fun mainChoreographer(): Choreographer {
            check(Looper.getMainLooper().isCurrentThread) { "TranscriptState must be created on the main thread" }
            return Choreographer.getInstance()
        }
    }
}
