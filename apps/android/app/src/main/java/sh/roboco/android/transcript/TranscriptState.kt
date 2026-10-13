package sh.roboco.android.transcript

import android.os.Looper
import android.os.Trace
import android.util.Log
import android.view.Choreographer
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import sh.roboco.android.core.TextEngine
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.LayoutListener
import uniffi.roboco_core.RowPlacement
import uniffi.roboco_core.TextSystem
import uniffi.roboco_core.TranscriptView
import kotlin.math.max

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
 * state. It draws nothing; the scroll model (68, [viewport]), anchoring
 * (130), row motion and painting (62–67, 117, 132) belong to their
 * tickets, which read [frame] and register [FrameListener]s.
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

    // ------------------------------------------------------------------
    // Ticket 68: the scroll model. The viewport is the single source of
    // truth for the offset, insets, content height and follow; the
    // properties below forward 65/75's call sites onto it.
    // ------------------------------------------------------------------

    /**
     * The transcript's scroll model (68): offset, insets, follow and the
     * tail spring. Everything runs on the main thread — written from
     * [apply], the scroll modifier and the inset writers (74/75/133).
     */
    val viewport = TranscriptViewport()

    /**
     * The content y (dp) at the host's top edge — the shared scroll
     * coordinate contract (see the ticket). A row's top on screen is
     * `round(p.y·d) − round(offset·d)`; 68 owns follow and insets, 134
     * the physics. Written on main by the viewport.
     */
    val offset: Float get() = viewport.offset

    /** The host's viewport height (dp), written only by the scroll modifier's `onSizeChanged` (68). */
    val viewportHeight: Float get() = viewport.viewportHeight

    /** The legal scroll range: `max(−topInset, contentHeight + bottomInset − viewportHeight)` (68). */
    val maxOffset: Float get() = viewport.maxOffset

    /**
     * Status bar + header height (dp) — 52's `contentTopInset`, written
     * by 75. A change of more than 0.5 snaps the offset to `maxOffset`
     * while following (68 R12).
     */
    var topInset: Float
        get() = viewport.topInset
        set(v) { viewport.setTopInset(v) }

    /**
     * The full covered height at the bottom (dp) — 74's `covered`, written
     * by 133/135. A change of more than 0.5 snaps while following (68 R11).
     */
    var bottomInset: Float
        get() = viewport.bottomInset
        set(v) { viewport.setBottomInset(v) }

    /** The host's drag interactions; passed to the scrollable (68) — never used for follow logic. */
    val scrollInteractions = MutableInteractionSource()

    /** Row hosts composed so far — the no-per-pixel-recomposition test's counter. */
    @VisibleForTesting
    internal var hostCompositions = 0

    /** The currently realized rows, keyed by row key (main thread only). */
    internal val realized = LinkedHashMap<ULong, RealizedRow>()

    /** The host's latest coordinates, for window/root rect conversions. */
    internal var hostCoordinates: LayoutCoordinates? = null

    /** The key of the (future) model cache: identity is (row, version, width). */
    data class ModelKey(val key: ULong, val version: ULong, val width: Float)

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

    /** The ever-growing set of row keys ever laid out (new-row fade-in, ticket 65). */
    val knownRows = KnownRows()

    init {
        // After `listeners` so this is the first listener registered: the
        // marking runs inside the same main-thread relay callback that sets
        // `frame`, so the set is complete before any recomposition reads
        // the new frame (61's apply is not reordered).
        addFrameListener { old, new -> knownRows.onFrameApplied(old, new) }
    }

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
     * its remover. Ticket 65's `knownRows` marking owns the first slot
     * (registered in this class's `init`, right after `listeners`, so the
     * set is complete before any recomposition reads the new frame); later
     * tickets (121) register theirs after construction.
     */
    fun addFrameListener(l: FrameListener): () -> Unit {
        listeners += l
        return { listeners -= l }
    }

    /**
     * The model for one placement (ticket 65): the cache seam first
     * (always `null` until 117), else a **synchronous, counted** build —
     * `display(p.index)` on the same frame the placement came from (indices
     * are per frame), wrapped in the `build-model-sync` trace section, with
     * `RowModel` construction and `storeModel`. A `null` display (a row the
     * frame no longer has — a race between the frame swap and the host's
     * remember) logs once and yields `null`: the row host paints nothing
     * until the next frame re-composes it.
     *
     * Main thread only: `display()` and `RowModel` construction are the
     * counted path (117 moves them to its prefetch thread).
     */
    fun modelFor(frame: LayoutFrame, p: RowPlacement): RowModel? {
        val k = ModelKey(p.key, p.version, frame.width())
        cachedModel(k)?.let { return it }
        val t0 = System.nanoTime()
        Trace.beginSection("build-model-sync") // iOS signpost name
        try {
            TranscriptPerf.syncBuilds++
            val display = frame.display(p.index)
            if (display == null) {
                if (!nullDisplayLogged) {
                    nullDisplayLogged = true
                    Log.w(TAG, "display(${p.index}) returned null")
                }
                return null
            }
            val m = RowModel(display)
            storeModel(k, m)
            TranscriptPerf.maxSyncBuildMs = max(TranscriptPerf.maxSyncBuildMs, (System.nanoTime() - t0) / 1e6)
            return m
        } finally {
            Trace.endSection()
        }
    }

    /** Ticket 117's cache lookup seam: no cache in this ticket, always `null`. */
    internal fun cachedModel(k: ModelKey): RowModel? = null

    /** Ticket 117's cache store seam: a no-op in this ticket. */
    internal fun storeModel(k: ModelKey, m: RowModel) {}

    /** Set by [modelFor] when a display build came back empty. */
    private var nullDisplayLogged = false

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
     * 5. compute `first` (68: the previous frame was missing or had zero
     *    rows — always **before** the swap, so the local `old` is the
     *    previous frame; ticket 130 anchors here) and swap [frame];
     * 6. hand the new geometry to [viewport] (68: first frame lands at
     *    the tail, later frames spring while following);
     * 7. notify the listeners over a copy, so one can remove itself.
     * (Step 2 — the pull — lives in [applyLatest].) Ticket 130 will insert
     * its anchor work between steps 5 and 7.
     */
    @VisibleForTesting
    internal fun apply(new: LayoutFrame) {
        if (isClosed) return
        val old = frame
        if (old != null && new.revision() == old.revision()) return
        if (new.styleCount().toInt() != fonts.count) fonts.update(new.styles())
        // 68: `first` is computed before the swap — `frame` is a delegated
        // snapshot property, so take a local (never `frame!!`); the
        // previous frame was missing or had zero rows. Ticket 130
        // captures its anchor at this point.
        val first = old == null || old.rowCount() == 0u
        frame = new
        // 68: the scroll model reacts to the new geometry — the first
        // frame with rows lands at the tail (R2), later frames spring
        // while following (R3). Ticket 130 consumes `needsAnchor` (R4);
        // nothing anchors in this ticket.
        val needsAnchor = viewport.onFrameApplied(contentHeightFor(new), first)
        listeners.toList().forEach { it.onFrame(old, new) }
    }

    /**
     * The frame's total height (dp) — the Rust core's `totalHeight()`
     * already ends 16 dp under the last row (68). Ticket 69 replaces the
     * body with the runway logic.
     */
    private fun contentHeightFor(f: LayoutFrame): Float = f.totalHeight()

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

        private const val TAG = "RobocoTranscript"

        /** Captured on main, before the engine exists. */
        private fun mainChoreographer(): Choreographer {
            check(Looper.getMainLooper().isCurrentThread) { "TranscriptState must be created on the main thread" }
            return Choreographer.getInstance()
        }
    }
}
