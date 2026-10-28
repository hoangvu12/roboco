package sh.roboco.android.transcript

import android.content.ComponentCallbacks2
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
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import sh.roboco.android.core.TextEngine
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.transcript.widgets.ToolRailMemory
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.LayoutListener
import uniffi.roboco_core.RowPlacement
import uniffi.roboco_core.TextSystem
import uniffi.roboco_core.TranscriptView
import kotlin.coroutines.ContinuationInterceptor
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
 * The transcript seam the session composer binding needs
 * (android-ui-parity #78): the upload-progress pin (67's ring, fed from
 * the session chrome) and the two runway calls (69, made just before a
 * send). [TranscriptState] is the only production implementation; 78's
 * tests and the gallery provide fakes, which is why the binding types
 * its parameter as this interface and not the (final) class.
 */
interface RunwayHooks {
    /** Attachment upload progress (0..1), null when nothing is in flight. */
    var uploadProgress: Double?

    /** An immediate send (new turn, steer, stop-and-send) — see [TranscriptState.beginOwnTurn]. */
    fun beginOwnTurn()

    /** A message queued behind a running turn — see [TranscriptState.expectQueuedTurn]. */
    fun expectQueuedTurn()
}

/**
 * The data half of the transcript host (ticket 61): it owns one
 * [TranscriptView], relays layout-thread frame notifications to the main
 * thread through [FrameRelay] (one coalesced apply per vsync), forwards the
 * viewport only when it changes, and keeps the applied frame, the
 * [uploadProgress] and the [fonts] style table as Compose-observable
 * state. It draws nothing; the scroll model (68, [viewport]) and the
 * own-turn runway (69) are driven from [apply], as is 130's anchor —
 * capture before the frame swap, restore after the runway height — so
 * the reading position survives content changes while not following.
 * Row motion and painting (62–67, 117, 132) belong to their tickets,
 * which read [frame] and register [FrameListener]s — 132's [rowMotion] is
 * the one exception: this class owns it so [apply] can feed it.
 *
 * Construct from composition or any thread (round 51): the production
 * constructor resolves the MAIN looper's Choreographer before creating the
 * Rust engine (which spawns the `roboco-layout` worker), so frames always
 * apply on main; everything after construction is main-thread only.
 * Owners pass `TextEngine.await()` from a main-dispatcher
 * coroutine; the default argument is a convenience for tests and the lab.
 * [close] is mandatory for every owner — the Rust side holds the relay,
 * the relay holds this state and every frame until the worker exits.
 */
@Stable
class TranscriptState internal constructor(
    text: TextSystem,
    poster: FramePoster,
    /**
     * 117: the model pipeline factory, injected so tests can gate the
     * worker. Declared **before** [makeEngine] so the existing
     * `TranscriptState(text, poster) { _, relay -> engine }` call sites
     * keep binding their trailing lambda to `makeEngine`; pass both by
     * name to inject a pipeline.
     */
    makeModels: (onUpgradeBuilt: (RowPlacement, RowModel) -> Unit) -> ModelPipeline =
        { ModelPipeline(onUpgradeBuilt = it) },
    makeEngine: (TextSystem, LayoutListener) -> TranscriptView,
) : RunwayHooks {
    /**
     * Production constructor. `mainChoreographer()` resolves the main
     * looper's Choreographer while the delegation arguments are evaluated,
     * before the primary constructor creates the Rust engine, so the relay
     * posts every frame apply to main even when composition built this
     * state on another thread (round 51).
     */
    constructor(text: TextSystem = TextEngine.shared) :
        this(text, ChoreographerPoster(mainChoreographer()), makeEngine = ::TranscriptView)

    private val relay = FrameRelay(poster) { applyLatest() }

    /** The engine, for `attach` (51), `setDebugEntries` (135) and toggles (120). Check [isClosed] after teardown. */
    val engine: TranscriptView = makeEngine(text, relay)

    /**
     * The model pipeline (117): the insertion-ordered row-model cache,
     * the async upgrade path, the directional prefetch and their single
     * `sh.roboco.transcript.prefetch` worker. Main thread only, like the
     * rest of this class; completions hop back through the pipeline's
     * main handler and check `closed` first.
     */
    val models = makeModels(::applyUpgrade)

    /** `null` until the first relayed frame, then the newest applied frame. Not cleared by [close]. */
    var frame by mutableStateOf<LayoutFrame?>(null)
        private set

    /**
     * Attachment upload progress (0.0–1.0), `null` when nothing is in
     * flight: drawn as rings on pending thumbnails (ticket 67), fed by the
     * session chrome (ticket 78). Equal writes do not invalidate.
     */
    override var uploadProgress by mutableStateOf<Double?>(null)
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
     * The own-turn runway (69): a sent prompt glides to the top of the
     * visible area and the reply streams into reserved space under it,
     * until it fills the viewport and the ordinary tail follow takes
     * over. The driver owns the pure state machine and the glue to
     * [viewport] — the height recompute per apply and per geometry
     * change, the glide flag for 68's spring step, and the follow flip
     * when a queued prompt is promoted. `currentRows` adapts the applied
     * [frame], so [beginOwnTurn]/[expectQueuedTurn] capture the user
     * keys present at call time — before the core applies the send.
     */
    private val runway = RunwayDriver(viewport) { frame?.let(::LayoutFrameRows) }

    init {
        // 69: install the runway refresh (iOS `refreshRunway`, TLV:147-159)
        // into 68's geometry hook. 68's three inset/viewport setters call
        // it after writing the value and before the following pin — the
        // iOS order (inset change → runway height → offset pin,
        // SVC:373-378). No other call site: on Android the only inputs
        // that change the height are the applied frame and the geometry.
        viewport.onGeometryChanged = runway::refresh
    }

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

    /**
     * Ticket 78 (immediate send: a new turn, a steer, stop-and-send) and
     * 77 (new-session arrival, before any frame): starts an own turn.
     * The next new User row — the optimistic prompt echo — glides to the
     * top of the visible area and owns the reserved space under it until
     * the reply fills it (iOS `beginOwnTurn`, TLV:77-81). Sets following
     * **without** starting the spring. 78 calls this just before the FFI
     * send, so the state may wait on an unresolved key if the send later
     * fails — the next call replaces it.
     */
    override fun beginOwnTurn() = runway.beginOwnTurn()

    /**
     * Ticket 78 (a message queued behind a running turn): records the
     * user keys present at queue time, first expectation only. That
     * bubble takes the runway only when it lands in a frame — the
     * promotion flips `following` on (iOS `expectQueuedTurn`, TLV:83-85,
     * 96-107). Until then, nothing changes.
     */
    override fun expectQueuedTurn() = runway.expectQueuedTurn()

    /** `true` while the runway holds reserved space below the prompt (glide step in 68's spring). */
    val runwayHolding: Boolean get() = runway.holding

    /** The host's drag interactions; passed to the scrollable (68) — never used for follow logic. */
    val scrollInteractions = MutableInteractionSource()

    /** Row hosts composed so far — the no-per-pixel-recomposition test's counter. */
    @VisibleForTesting
    internal var hostCompositions = 0

    /** The currently realized rows, keyed by row key (main thread only). */
    internal val realized = LinkedHashMap<ULong, RealizedRow>()

    /** The host's latest coordinates, for window/root rect conversions. */
    internal var hostCoordinates: LayoutCoordinates? = null

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

    /**
     * The row motion (ticket 132): the S1–S4 fold/reveal selection and the
     * additive per-key segment tracks that slide realized rows from their
     * old placement to their new one (140 ms ease-out folds, 360 ms
     * expo-out reveals). Fed by [apply] before the listeners — with the
     * rows realized for the **old** frame — and read by the host's
     * measure and place passes, the Tools-row clip and [RealizedRow]'s
     * drawn geometry. Main thread only, like the rest of this class.
     */
    val rowMotion = RowMotion()

    init {
        // 121: the tool rail's quiet window — the **first** listener slot
        // (61's reconciler decision reserves it for this ticket, ahead of
        // 65's knownRows below) so the window opens before any listener that
        // could trigger composition work. iOS opens it inside the list view's
        // `apply`, before the rails are built from the same frame
        // (TranscriptListView.swift:308-313): the "attach" is the first
        // frame, or the first frame after an empty one.
        addFrameListener { old, _ ->
            if (old == null || old.rowCount() == 0u) ToolRailMemory.onTranscriptAttached()
        }
    }

    init {
        // 65: the second slot — the marking runs inside the same main-thread
        // relay callback that sets `frame`, so the set is complete before any
        // recomposition reads the new frame (61's apply is not reordered).
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
            // #981: a re-wrap from a real width change (not the first one).
            if (viewportWidthDp > 0f && widthDp != viewportWidthDp) viewport.noteWidthChange()
            viewportWidthDp = widthDp
            this.textScale = textScale
            engine.setViewport(widthDp, textScale)
        }
    }

    /**
     * iOS `pendingFold` (`ios:Transcript/TranscriptListView.swift:161-163`):
     * the row a disclosure or tool toggle just flipped — its next height
     * change tweens with the desktop fold (140 ms ease-out). Written
     * **unconditionally** (under Reduce Motion too, as on iOS) before the
     * engine call by the handlers below; a delegate of [rowMotion]'s
     * `pendingFold`, so the mark has one home and 132's selection is the
     * only thing that clears it. Main thread only.
     */
    var pendingFold: ULong?
        get() = rowMotion.pendingFold
        set(v) { rowMotion.pendingFold = v }

    /**
     * iOS `rowView(_:toggle:)` (`TranscriptListView.swift:563-567`) minus
     * the haptic (the widget plays it, ticket 120): mark [pendingFold] then
     * call `engine.toggle(key)` — the order matters, 132 consumes the mark
     * when the new frame's row height differs. A true flip: a second tap
     * before the next frame undoes the first. A no-op once [isClosed].
     * Main thread only.
     */
    fun toggleRow(key: ULong) {
        if (isClosed) return
        pendingFold = key // iOS order: before the engine call
        engine.toggle(key)
    }

    /**
     * iOS `rowView(_:toggleDetail:open:)` (`TranscriptListView.swift:569-572`):
     * [open] is the detail's state **as painted** (the widget's own field,
     * never inverted locally — two taps before the next frame land once).
     * Marks [pendingFold] with the **row** key, not the detail key, before
     * the engine call. A no-op once [isClosed]. Main thread only.
     */
    fun toggleDetail(row: ULong, detail: ULong, open: Boolean) {
        if (isClosed) return
        pendingFold = row // the row key, not the detail key
        engine.toggleDetail(row, detail, open)
    }

    /**
     * Registers a hook that runs on main after each applied frame; returns
     * its remover. Ticket 121's quiet-window listener owns the first slot and
     * ticket 65's `knownRows` marking the second (both registered in this
     * class's `init` blocks, right after `listeners`, so they run before any
     * recomposition reads the new frame); later tickets register theirs
     * after construction.
     */
    fun addFrameListener(l: FrameListener): () -> Unit {
        listeners += l
        return { listeners -= l }
    }

    /**
     * The model for one placement (ticket 65's counted sync fallback,
     * 117's cache path): the cache seam first, else a **synchronous,
     * counted** build — `buildModel(frame, p, BuildPath.SYNC)` (the only
     * `display()` call site in main sources), wrapped in the
     * `build-model-sync` trace section, with `RowModel` construction and
     * `storeModel`. A `null` display (a row the frame no longer has — a
     * race between the frame swap and the host's recomposition) logs
     * once and yields `null`: the row host paints nothing until the next
     * frame re-composes it.
     *
     * Main thread only; 65's counters (`syncBuilds`, `maxSyncBuildMs`)
     * are written here, one increment per call.
     */
    fun modelFor(frame: LayoutFrame, p: RowPlacement): RowModel? {
        val k = ModelKey(p.key, p.version, frame.width())
        cachedModel(k)?.let { return it }
        val t0 = System.nanoTime()
        Trace.beginSection("build-model-sync") // iOS signpost name
        try {
            TranscriptPerf.syncBuilds++
            val m = buildModel(frame, p, BuildPath.SYNC)
            if (m == null) {
                if (!nullDisplayLogged) {
                    nullDisplayLogged = true
                    Log.w(TAG, "display(${p.index}) returned null")
                }
                return null
            }
            storeModel(k, m)
            TranscriptPerf.maxSyncBuildMs = max(TranscriptPerf.maxSyncBuildMs, (System.nanoTime() - t0) / 1e6)
            return m
        } finally {
            Trace.endSection()
        }
    }

    /** Ticket 117's cache lookup seam: the pipeline's insertion-ordered cache. */
    internal fun cachedModel(k: ModelKey): RowModel? = models.cache[k]

    /** Ticket 117's cache store seam: the pipeline's insertion-ordered cache. */
    internal fun storeModel(k: ModelKey, m: RowModel) = models.cache.store(k, m)

    /**
     * Ticket 117's resolution of one realized row's model (iOS
     * `TranscriptListView.swift:377-385`): KEEP the painted model, take
     * a CACHED one, request an async UPGRADE — the row keeps painting
     * its old model at the new placement until the build lands and
     * passes the version guard — or fall back to [modelFor]'s counted
     * SYNC build. Called from [RowHost] on every recomposition; a few
     * comparisons, never a `remember` keyed on the version (that would
     * rebuild on every streaming token and kill the upgrade path).
     */
    internal fun resolveModel(row: RealizedRow, frame: LayoutFrame, p: RowPlacement): RowModel? {
        val w = frame.width()
        val cur = row.model?.display
        val k = ModelKey(p.key, p.version, w)
        return when (decideModel(cur?.key, cur?.version, cur?.width, p.key, p.version, w, k in models.cache)) {
            ModelAction.KEEP -> row.model
            ModelAction.CACHED -> models.cache[k].also { row.model = it }
            ModelAction.UPGRADE -> {
                models.requestUpgrade(frame, p)
                row.model
            }
            ModelAction.SYNC -> modelFor(frame, p).also { row.model = it }
        }
    }

    /**
     * The upgrade completion's apply guard (iOS
     * `TranscriptListView.swift:458-465`): the model was always stored;
     * it reaches the row only if the row is still realized and the
     * current frame's placement for it still carries the built version —
     * a late older build must never roll a newer one back (118's veil
     * would flicker). The `upgradeTick` bump recomposes this row's host,
     * and only it.
     */
    private fun applyUpgrade(p: RowPlacement, m: RowModel) {
        val row = realized[p.key] ?: return
        val f = frame ?: return
        val i = f.indexOf(p.key) ?: return
        if (f.placement(i)?.version != p.version) return
        row.model = m
        row.upgradeTick++
    }

    /**
     * Ticket 117: a memory trim at or above `RUNNING_LOW` (10) clears
     * the model cache through [models] — a pure cache; realized rows
     * keep painting and prefetch refills. Delivered on the main thread
     * by the host's `ComponentCallbacks2` registration.
     */
    fun onTrimMemory(level: Int) {
        trimDeliveries++
        models.trim(level)
    }

    /** Ticket 117: the `onLowMemory` half of the trim registration (as level `COMPLETE`). */
    fun onLowMemory() {
        trimDeliveries++
        models.trim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    /** Test-only: the trim-registration test counts deliveries through this. */
    @VisibleForTesting
    internal var trimDeliveries = 0


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
     *    previous frame), capture 130's anchor over the old rows (also
     *    before the swap: `following` must be read before the runway
     *    height below can flip it for a queued turn) and swap [frame];
     * 6. hand the runway height (69) to [viewport] (68: the first frame
     *    lands at the tail, later frames spring while following, and R4
     *    returns `needsAnchor` when not following);
     * 7. when `needsAnchor` and an anchor was captured, restore 130's
     *    anchor (a plain offset shift that keeps a fling's momentum) —
     *    before the listeners, so 131's jump button and the lab meters
     *    see the final offset;
     * 8. feed 132's row motion (after the restore, before the listeners —
     *    `realized` still holds the rows composed for the old frame);
     * 9. notify the listeners over a copy, so one can remove itself.
     * (Step 2 — the pull — lives in [applyLatest].)
     */
    @VisibleForTesting
    internal fun apply(new: LayoutFrame) {
        // 117: the whole body sits inside the `apply-frame` trace section
        // and the maxApplyMs timing — iOS's `defer` around `apply(_:)`
        // (`TranscriptListView.swift:293-298`); the lab bench (136) reads
        // the counter.
        Trace.beginSection("apply-frame") // iOS signpost name
        val t0 = System.nanoTime()
        try {
            if (isClosed) return
            val old = frame
            if (old != null && new.revision() == old.revision()) return
            if (new.styleCount().toInt() != fonts.count) fonts.update(new.styles())
            // 68: `first` is computed before the swap — `frame` is a delegated
            // snapshot property, so take a local (never `frame!!`); the
            // previous frame was missing or had zero rows.
            val first = old == null || old.rowCount() == 0u
            // 130 (TLV:301-307): capture the row under the header's bottom
            // edge before the swap — `following` is read before the runway
            // height below (69's queued promotion can flip it to true, and
            // then there is no anchor to apply) and before the old frame is
            // released. No capture while following or without an old frame.
            val anchor = ScrollAnchoring.capture(viewport, old?.anchorRows())
            frame = new
            // 68 + 69: the scroll model reacts to the new geometry — the first
            // frame with rows lands at the tail (R2), later frames spring
            // while following (R3), and the runway height feeds the call (a
            // queued promotion flips `following` inside it). R4 (not first,
            // not following) returns `needsAnchor` for the restore below.
            val needsAnchor = viewport.onFrameApplied(contentHeightFor(new), first)
            // 130 (TLV:324-330): still not following and an anchor was captured
            // — shift the offset so the anchored row stays under the header.
            // A plain `setOffset` write (state + 68's report) is the
            // momentum-preserving equivalent of iOS's `bounds.origin.y` write:
            // it never cancels an in-flight fling, never starts or stops the
            // spring and never touches `following`.
            if (needsAnchor && anchor != null) {
                ScrollAnchoring.restore(viewport, anchor, new.anchorRows())
            }
            // 132 (TLV:314, 331): the row motion is chosen here, after the
            // swap and 130's restore and before the listeners, with the
            // rows `realized` for the **old** frame (composition for `new`
            // has not run yet, so the registry still holds the old band —
            // iOS's `visible`). Only the row views animate: the offset, the
            // content height and `maxOffset` above are already final, and
            // the spring (68) keeps chasing the final `maxOffset` on every
            // tick while rows tween beneath it.
            rowMotion.onFrameApplied(
                old?.lookup(),
                new.lookup(),
                realized.values.map { it.key to it.placement.kind },
                ReduceMotion.isReduced,
            )
            listeners.toList().forEach { it.onFrame(old, new) }
        } finally {
            TranscriptPerf.maxApplyMs = max(TranscriptPerf.maxApplyMs, (System.nanoTime() - t0) / 1e6)
            Trace.endSection()
        }
    }

    /**
     * The content height (dp) for a frame being applied: the runway's
     * reservation while it holds — the prompt pinned at the top of the
     * visible area with a viewport of reserved space under it, so
     * following the tail *is* holding the prompt in place — else the
     * frame's own `totalHeight()` (69, iOS `contentHeight(for:)`,
     * TLV:95-142; the natural height already ends 16 dp under the last
     * row — nothing is added on top). Called as the `onFrameApplied`
     * argument in [apply], so it runs after the frame swap (and after
     * 130's anchor capture) and before 68's first-frame snap / follow /
     * anchor branch; a queued promotion flips `following` inside the
     * same apply, so R3 starts the spring for it.
     */
    private fun contentHeightFor(f: LayoutFrame): Float = runway.heightFor(LayoutFrameRows(f))

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
        // 117: stop the model pipeline before the engine — a completion
        // that still arrives finds the pipeline closed and stops at the
        // inflight/counter steps.
        models.close()
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

        /**
         * The MAIN looper's Choreographer, from whichever thread constructs
         * the state — before the engine exists. The relay only needs that
         * instance: `postFrameCallback` is thread-safe and every callback
         * runs on main, so the apply path stays main-only whoever built us.
         *
         * Construction is not always on main (round 51): composition can
         * run on the thread that triggered a snapshot apply. On CI
         * 88b067d0 (SessionFlowTest.scrollingUpWhileStreamingStaysPut)
         * compose-ui-test's ApplyingContinuationInterceptor sent apply
         * notifications on a Dispatchers.IO worker that had just finished a
         * `withContext`, the Recomposer resumed inline, and
         * TestMonotonicFrameClock recomposed the session route — and this
         * constructor — on that worker. `Choreographer.getInstance()` is
         * per-thread (a looper-less worker has none), so the old check threw
         * instead. Off main, the instance comes from Compose's own main
         * dispatcher, which captured it on main (or hops there once itself
         * if first touched elsewhere).
         */
        @VisibleForTesting
        internal fun mainChoreographer(): Choreographer =
            if (Looper.getMainLooper().isCurrentThread) {
                Choreographer.getInstance()
            } else {
                (AndroidUiDispatcher.Main[ContinuationInterceptor] as AndroidUiDispatcher).choreographer
            }
    }
}
