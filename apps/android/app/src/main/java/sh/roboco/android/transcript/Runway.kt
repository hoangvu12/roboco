package sh.roboco.android.transcript

import kotlin.math.abs
import kotlin.math.max
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.RowKind

/**
 * The rows the own-turn runway needs (69), so tests can fake a
 * [LayoutFrame]: the row count, the key of the User row at an index, the
 * top `y` (dp, leading gap included) of the row with a key, and the
 * frame's natural height. Everything else the runway computes from these
 * and the viewport geometry (68).
 */
internal interface RunwayRows {
    val rowCount: Int

    /** Row key if the row at [index] is a User row, else null. */
    fun userKeyAt(index: Int): ULong?

    /** Top y (dp, gap included) of the row with [key], or null if it is not in the frame. */
    fun yOf(key: ULong): Float?

    /** `frame.totalHeight()`: rows + the core's 16 dp bottom breathing room. */
    val naturalHeight: Float
}

/**
 * The only UniFFI reference in the runway (69): adapts an applied
 * [LayoutFrame] to [RunwayRows]. Pure forwarding — `indexOf` returns null
 * for a key the frame no longer has, which is the "prompt row
 * disappeared" hold-still case.
 */
internal class LayoutFrameRows(private val frame: LayoutFrame) : RunwayRows {
    override val rowCount: Int get() = frame.rowCount().toInt()
    override fun userKeyAt(index: Int): ULong? =
        frame.placement(index.toUInt())?.takeIf { it.kind == RowKind.USER }?.key
    override fun yOf(key: ULong): Float? = frame.indexOf(key)?.let { frame.placement(it) }?.y
    override val naturalHeight: Float get() = frame.totalHeight()
}

/**
 * The own-turn runway's pure state machine (69, iOS "Runway",
 * `TranscriptListView.swift:44-142`): when the user sends a message
 * immediately, the prompt bubble glides to the top of the visible area
 * and the reply streams into reserved space below it, so the view does
 * not move on every token; once the reply fills that space the runway
 * retires and the ordinary follow-the-tail spring (68) takes over.
 *
 * No viewport, no Compose state — [contentHeight] is the whole rule and
 * is called exactly once per applied frame and once per geometry
 * change (never from a `derivedStateOf` getter: it mutates the key
 * resolution, the queued expectation and the [runwayHeight] memory).
 * The caller ([RunwayDriver]) feeds it 68's `topInset`,
 * `viewportHeight` and `bottomInset`, and turns [Height.promoted] into
 * `viewport.setFollowing(true)`.
 *
 * Height rule (iOS `contentHeight(for:)`, TLV:95-142), per call:
 * 1. a queued prompt that just landed is promoted to own the runway —
 *    before the own-turn guard, so it fires even with no own turn;
 * 2. no own turn → natural height, no reservation;
 * 3. an own turn with an unresolved key resolves it: the newest User row
 *    among the last [PROMPT_SCAN] rows that was not there at send time;
 * 4. unresolved or vanished key → hold still: `max(natural,
 *    runwayHeight)` — the previous turn's reservation keeps the view
 *    from collapsing between send and echo;
 * 5. `hold = y − topInset`, `minimum = hold + viewportHeight −
 *    bottomInset`: holding puts the prompt row (its 30 dp turn gap
 *    included) just under the header, and the reservation makes the hold
 *    the max offset — while the runway holds, "the bottom" *is* the held
 *    position;
 * 6. `natural ≥ minimum − 0.5` → retire: natural height, but the anchor
 *    survives (a viewport that grows past the reply re-reserves);
 * 7. else reserve `minimum`.
 */
internal class Runway {

    /** The turn that owns the runway; [key] stays null until the prompt row is laid out. */
    private class OwnTurn(var key: ULong?, val before: Set<ULong>)

    private var ownTurn: OwnTurn? = null

    /** `before` set of a queued send waiting for its bubble (first one wins). */
    private var queuedTurn: Set<ULong>? = null

    /** Content height (dp) the runway holds; null means "not holding" (settle step, not glide). */
    var runwayHeight: Float? = null
        private set

    /** `true` while the runway holds reserved space (68's glide step is active). */
    val holding: Boolean get() = runwayHeight != null

    /** `true` from [begin] until the next [begin] or a queued promotion replaces it. */
    val hasOwnTurn: Boolean get() = ownTurn != null

    /**
     * Result of a recompute; [promoted] is true only when a queued prompt
     * took the runway during this call — the caller sets following on
     * (iOS sets it inside `contentHeight(for:)`, TLV:104).
     */
    data class Height(val height: Float, val promoted: Boolean)

    /**
     * iOS `beginOwnTurn` (TLV:77-81): an immediate send (new turn, steer,
     * stop-and-send) starts a fresh turn keyed on [recent] — the user
     * rows present at send time, so the prompt echo resolves as "the
     * first new User row". Clears a pending queued expectation. Does
     * **not** reset [runwayHeight]: the hold-still rule relies on the
     * previous reservation while the new echo is pending.
     */
    fun begin(recent: Set<ULong>) {
        queuedTurn = null
        ownTurn = OwnTurn(null, recent)
    }

    /**
     * iOS `expectQueuedTurn` (TLV:83-85): a message queued behind a
     * running turn records the user rows present at queue time — the
     * first expectation only, a later one is ignored. Nothing else: the
     * queued bubble takes the runway only when it lands (the promotion
     * in rule 1), not at send time.
     */
    fun expectQueued(recent: Set<ULong>) {
        if (queuedTurn == null) queuedTurn = recent
    }

    /** Rules 1–7; see the class doc. */
    fun contentHeight(
        rows: RunwayRows,
        topInset: Float,
        viewportHeight: Float,
        bottomInset: Float,
    ): Height {
        val natural = rows.naturalHeight
        // Rule 1 — queued promotion, before the own-turn guard.
        var promoted = false
        val queued = queuedTurn
        if (queued != null) {
            val key = newUserRow(rows, queued)
            if (key != null) {
                queuedTurn = null
                ownTurn = OwnTurn(key, queued)
                promoted = true
            }
        }
        val turn = ownTurn
        if (turn == null) {
            // Rule 2 — nobody owns the runway.
            runwayHeight = null
            return Height(natural, promoted)
        }
        if (turn.key == null) {
            // Rule 3 — resolve the prompt row.
            turn.key = newUserRow(rows, turn.before)
        }
        val key = turn.key
        val y = if (key != null) rows.yOf(key) else null
        if (key == null || y == null) {
            // Rule 4 — echo not laid out yet (or gone again): hold still,
            // the previous reservation keeps the spring in glide mode.
            return Height(max(natural, runwayHeight ?: 0f), promoted)
        }
        // Rule 5 — the hold puts the prompt row at the top of the visible
        // area; the reservation makes that the max offset.
        val hold = y - topInset
        val minimum = hold + viewportHeight - bottomInset
        if (natural >= minimum - EPSILON) {
            // Rule 6 — filled: the ordinary tail follow takes over. The
            // anchor stays for the turn, so a viewport that grows past
            // the reply re-reserves instead of the prompt dropping back.
            runwayHeight = null
            return Height(natural, promoted)
        }
        // Rule 7 — reserve the space.
        runwayHeight = minimum
        return Height(minimum, promoted)
    }

    companion object {
        /** User rows near the tail count as "present at send time" (TLV:71). */
        const val RECENT_USER_SCAN = 24

        /** Newest-first window for resolving the prompt row (TLV:100, 116). */
        const val PROMPT_SCAN = 12

        /** Retire tolerance and refresh threshold, dp (TLV:132, 155). */
        const val EPSILON = 0.5f

        // GLIDE_RETAIN 0.85 / the 8-frame cap live in 68's springTick
        // (TranscriptViewport), not here.

        /**
         * The User-row keys among the last [RECENT_USER_SCAN] rows of
         * [rows] — the set a new prompt resolves against (iOS
         * `recentUserKeys`, TLV:68-75). A null frame (nothing applied
         * yet) gives the empty set.
         */
        fun recentUserKeys(rows: RunwayRows?): Set<ULong> {
            if (rows == null) return emptySet()
            val n = rows.rowCount
            if (n <= 0) return emptySet()
            val keys = HashSet<ULong>()
            for (i in n - 1 downTo maxOf(0, n - RECENT_USER_SCAN)) {
                rows.userKeyAt(i)?.let { keys.add(it) }
            }
            return keys
        }

        /**
         * The newest User row among the last [PROMPT_SCAN] rows whose key
         * is not in [before] (the prompt echo), or null. `n == 0` returns
         * null; a User row at `n − PROMPT_SCAN − 1` or older is not
         * picked up.
         */
        fun newUserRow(rows: RunwayRows, before: Set<ULong>): ULong? {
            val n = rows.rowCount
            if (n <= 0) return null
            for (i in n - 1 downTo maxOf(0, n - PROMPT_SCAN)) {
                val key = rows.userKeyAt(i) ?: continue
                if (key !in before) return key
            }
            return null
        }
    }
}

/**
 * The glue between the pure [Runway] and 68's [TranscriptViewport] (69):
 * the height recompute runs once per applied frame ([heightFor], as the
 * `onFrameApplied` argument, so before 68's first-frame snap / follow /
 * anchor branch) and once per geometry change ([refresh], installed as
 * 68's `onGeometryChanged` hook, called by the three inset/viewport
 * setters after the value is written and before the following pin — the
 * iOS order, SVC:373-378). After every recompute it drives
 * [TranscriptViewport.glide], which 68's `springTick` reads on every
 * tick (retire → settle step, re-reserve → glide step, mid-flight).
 *
 * Plain JVM: no Compose, no UniFFI — [currentRows] supplies the applied
 * frame's rows (TranscriptState passes `frame?.let(::LayoutFrameRows)`),
 * so the driver is unit-testable with fake rows.
 */
internal class RunwayDriver(
    private val viewport: TranscriptViewport,
    private val currentRows: () -> RunwayRows?,
) {
    val runway = Runway()

    /** `true` while the runway holds reserved space. */
    val holding: Boolean get() = runway.holding

    /**
     * iOS `beginOwnTurn` (TLV:77-81): starts a fresh turn on the user
     * keys present **now** (before the core applies the send), and sets
     * following through 68's internal setter — which never starts the
     * spring, exactly like iOS.
     */
    fun beginOwnTurn() {
        runway.begin(Runway.recentUserKeys(currentRows()))
        viewport.setFollowing(true)
    }

    /**
     * iOS `expectQueuedTurn` (TLV:83-85): records the user keys present
     * now, first expectation only. `following` is untouched — the queued
     * bubble flips it when it lands (the promotion inside [heightFor]).
     */
    fun expectQueuedTurn() {
        runway.expectQueued(Runway.recentUserKeys(currentRows()))
    }

    /**
     * 68's `contentHeightFor` body: one runway recompute against the
     * viewport geometry, for the frame being applied. Also sets the
     * glide flag and flips `following` on a queued promotion, so 68's R3
     * starts the spring for it in the same apply.
     */
    fun heightFor(rows: RunwayRows): Float {
        val r = runway.contentHeight(rows, viewport.topInset, viewport.viewportHeight, viewport.bottomInset)
        viewport.glide = runway.holding
        if (r.promoted) viewport.setFollowing(true)
        return r.height
    }

    /**
     * iOS `refreshRunway` (TLV:152-159), installed as 68's
     * `onGeometryChanged`: recompute only with an own turn and a frame;
     * a change of more than [Runway.EPSILON] writes the height and —
     * only while following and not tracking — arms the tail spring. A
     * smaller change does nothing (no write, no spring).
     */
    fun refresh() {
        if (!runway.hasOwnTurn) return
        val rows = currentRows() ?: return
        val h = heightFor(rows)
        if (abs(viewport.contentHeight - h) > Runway.EPSILON) {
            viewport.setContentHeight(h)
            if (viewport.following && !viewport.tracking) viewport.startSpring()
        }
    }
}
