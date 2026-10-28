package sh.roboco.android.transcript

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The transcript's scroll model (ticket 68): where the viewport sits over
 * the Rust-owned content, and who owns it. `offset` is the content-space
 * `y` shown at the top edge of the (full-screen) transcript box — a row
 * draws at screen `y = p.y − offset`; `minOffset` is `−topInset` (the
 * resting top, where row 0 sits just below the header, TLV:218) and
 * `maxOffset` is `contentHeight + bottomInset − viewportHeight`
 * (TLV:219-220). Content scrolls **under** the glass header and the
 * bottom stack; the insets only widen the scroll range, they never clip.
 *
 * Follow-the-tail (TLV:36-39, 240-279): on by default, the first frame
 * with rows lands at the bottom (R2), new frames pull the view to the
 * tail with a per-frame critically damped approach (`springTick`), a drag
 * hands control to the user (R5), and a release within 70 dp of the tail
 * with the finger not flinging away from it (R6) or momentum carrying the
 * list back into the tail (R7) hands it back. `scrollToBottom` and
 * `releaseFollow` are the programmatic halves.
 *
 * The tail spring is not a Compose `spring()` and is deliberately not
 * gated by Reduce Motion: it is iOS's `CADisplayLink` tick (TLV:503-538)
 * ported formula-for-formula (`1 − e^(−16·dt)` settle, `1 − 0.85^frames`
 * glide, 1/30 s dt clamp, 0.5 dp snap), re-reading the target every tick
 * so it chases a streaming tail. `IosSpring`/`Motion.*` (FOUNDATION-D1)
 * cover row tweening, not this scroll formula; the constants below are
 * iOS's, unit-test pinned (see `TranscriptViewportTest`).
 *
 * Threading: main thread only — written from the frame relay's apply
 * (61), the scroll modifier's gestures and the spring frame driver (68),
 * and the inset writers (74/75/133). Compose runtime snapshot state only,
 * no UI and no core types, so it is unit-testable on the plain JVM.
 */
@Stable
class TranscriptViewport(private val nanoTime: () -> Long = System::nanoTime) {

    /** The content y (dp) at the top edge of the transcript box. */
    var offset by mutableFloatStateOf(0f)
        private set

    /** The transcript box height (dp); only the scroll modifier's `onSizeChanged` writes it. */
    var viewportHeight by mutableFloatStateOf(0f)
        private set

    /** Status bar + header height (dp), from ticket 52's `contentTopInset` (75 writes it). */
    var topInset by mutableFloatStateOf(0f)
        private set

    /** The full covered height at the bottom (dp): 74's `covered`, written by 133/135. */
    var bottomInset by mutableFloatStateOf(0f)
        private set

    /** The frame's `totalHeight()` (dp); ticket 69 may replace it with the runway height. */
    var contentHeight by mutableFloatStateOf(0f)
        private set

    /** Following the tail: new content keeps the bottom in view (TLV:37). */
    var following by mutableStateOf(true)
        private set

    /** `true` while the tail spring requests display frames (the frame driver reads it). */
    var springRunning by mutableStateOf(false)
        private set

    /** A pointer is down (iOS `isTracking`). Plain field, never read in composition. */
    var tracking = false
        private set

    /**
     * A drag passed touch slop (`pan .began` … `.ended`). 133 reads it,
     * and 134's scroll indicator reads it in composition (snapshot state,
     * so its `snapshotFlow` fade driver re-emits on the drag edges).
     */
    var dragging by mutableStateOf(false)
        private set

    /**
     * Momentum after lift (iOS `isDecelerating`): the fling wrapper and
     * the rubber band's bounce set it, and 134's scroll indicator reads
     * it in composition (snapshot state, as `dragging`).
     */
    var flinging by mutableStateOf(false)
        private set

    /** Ticket 69: `true` while the runway holds (glide step instead of settle). */
    var glide = false

    /** Ticket 69: recompute the runway after an inset/viewport change (iOS `refreshRunway`, TLV:147-149). */
    var onGeometryChanged: (() -> Unit)? = null

    /** The resting top (dp): `−topInset` (TLV:218). */
    val minOffset: Float get() = -topInset

    /** The scroll range end (dp): `max(−topInset, contentHeight + bottomInset − viewportHeight)` (TLV:219-220). */
    val maxOffset: Float get() = max(-topInset, contentHeight + bottomInset - viewportHeight)

    /** How far the tail is below the bottom edge (dp); negative when past the tail (TLV:221-222). */
    val distanceFromBottom: Float get() = maxOffset - offset

    /** iOS `contentOffset.y + adjustedContentInset.top`: 52/75's `edgeEngaged` reads it. */
    val scrollY: Float get() = offset + topInset

    /** iOS `lastReportedY` (TLV:225): the momentum rule's movement reference. */
    private var lastReportedOffset = 0f

    /** The clock base of the running spring, from [nanoTime] at [startSpring]. */
    private var lastTickNanos = 0L

    /**
     * The frame-apply rules R2–R4, called from `TranscriptState.apply`
     * (61) with `contentHeight = frame.totalHeight()`:
     * R2 — `first` (the previous frame was null or had 0 rows) sets the
     * height first, then lands `offset = maxOffset` with no animation,
     * ignoring `following`, `tracking` and `dragging`, and never starting
     * the spring — a finger resting on an empty, loading transcript does
     * not stop the landing (TLV:300, 319-321);
     * R3 — not first, following: set the height and, while not tracking,
     * start the spring (TLV:322-323);
     * R4 — not first, not following: set the height and leave the offset
     * alone; returns `needsAnchor` for ticket 130.
     */
    fun onFrameApplied(contentHeight: Float, first: Boolean): Boolean {
        this.contentHeight = contentHeight
        val rewrapped = widthChanged
        widthChanged = false
        if (first) {
            offset = maxOffset
        } else if (following) {
            if (rewrapped && !tracking) {
                // Wave-10 42 (upstream #981): a width change (rotation, a
                // fold, the split resizing) re-wrapped every row; landing
                // while following pins to the tail at once instead of
                // springing there from the old geometry.
                stopSpring()
                offset = maxOffset
            } else if (!tracking) {
                startSpring()
            }
        }
        report()
        return !first && !following
    }

    /** Set by [noteWidthChange]; consumed by the next [onFrameApplied]. */
    private var widthChanged = false

    /**
     * The transcript's width changed and the engine is re-wrapping: the
     * frame that lands next pins to the tail while following (#981).
     */
    fun noteWidthChange() {
        widthChanged = true
    }

    /** R12: a top-inset change > 0.5 snaps while following, after the geometry hook. */
    @JvmName("applyTopInset")
    fun setTopInset(value: Float) = changeGeometry(value, topInset) { topInset = it }

    /** R11: a bottom-inset change > 0.5 snaps while following, after the geometry hook (SVC:373-378). */
    @JvmName("applyBottomInset")
    fun setBottomInset(value: Float) = changeGeometry(value, bottomInset) { bottomInset = it }

    /** R12: like [setTopInset]; in Compose the box size can arrive after the first frame landed. */
    @JvmName("applyViewportHeight")
    fun setViewportHeight(value: Float) = changeGeometry(value, viewportHeight) { viewportHeight = it }

    /** Plain height set, then [report] — ticket 69's runway refresh. */
    @JvmName("applyContentHeight")
    fun setContentHeight(value: Float) {
        contentHeight = value
        report()
    }

    /** Programmatic offset set (130 anchor, 135 `-top`, 136 bench); never touches [following]. */
    @JvmName("applyOffset")
    fun setOffset(value: Float) {
        offset = value
        report()
    }

    /** R9: stop following the tail (135/136, and any future "scroll to top"). */
    fun releaseFollow() = setFollowing(false)

    /**
     * R10 (TLV:271-279): jump (or glide) to the newest content and resume
     * following. Animated starts the spring; otherwise the offset snaps
     * to `maxOffset` and any running spring stops.
     */
    fun scrollToBottom(animated: Boolean) {
        setFollowing(true)
        if (animated) {
            startSpring()
        } else {
            stopSpring()
            offset = maxOffset
        }
        report()
    }

    /** A pointer went down (iOS `isTracking`); only the scroll modifier calls this. */
    internal fun onPointerDown() {
        tracking = true
    }

    /**
     * The pointer went up. R6 (TLV:253-255): a drag that ends within 70 dp
     * of the tail while the finger is not flinging away from it (velocity
     * ≤ 50 dp/s, positive = finger down) hands control back — the spring
     * is *not* started (iOS leaves that to the next frame). R13: a press
     * that never became a drag re-arms the spring while following, so
     * frames that arrived while the finger rested do not leave the list
     * short of the tail (an iOS next-frame would cover it; a finished
     * reply has no next frame). A cancelled gesture reports 0 velocity.
     */
    internal fun onPointerUp(fingerVelocityY: Float) {
        tracking = false
        if (dragging) {
            dragging = false
            if (distanceFromBottom < RELATCH_DP && fingerVelocityY <= RELEASE_VELOCITY_DP) {
                setFollowing(true)
            }
        } else if (following && abs(maxOffset - offset) > SNAP_DP) {
            startSpring()
        }
    }

    /**
     * R5 + R14: the user's scroll delta (drag or fling, dp). The first
     * delta while a pointer is down is the drag start past touch slop —
     * it hands control to the user and stops the spring. The delta is
     * clamped to the range spanned by the current offset and the legal
     * bounds: moving back into range is always allowed, moving further
     * out of range is refused. Content or inset changes never clamp the
     * offset by themselves (UIKit does not clamp on a `contentSize`
     * shrink). Returns the consumed dp.
     */
    internal fun scrollByUser(delta: Float): Float {
        if (tracking && !dragging) {
            dragging = true
            setFollowing(false)
        }
        val previous = offset
        val next = (offset + delta).coerceIn(min(offset, minOffset), max(offset, maxOffset))
        offset = next
        report()
        return next - previous
    }

    /** Momentum started after lift (iOS `isDecelerating`); the fling wrapper calls this. */
    internal fun onFlingStart() {
        flinging = true
    }

    /** Momentum ended (settled or cancelled). */
    internal fun onFlingEnd() {
        flinging = false
    }

    /**
     * Arms the tail spring (TLV:503-513): records the tick base and
     * requests frames. No-op while already running. Started only from R3,
     * R10, R13 and ticket 69's runway refresh — never from a snapshot-flow
     * auto-start (#609's bug: it sprang to the bottom right after a
     * drag-release re-latch, which iOS never does).
     */
    internal fun startSpring() {
        if (springRunning) return
        lastTickNanos = nanoTime()
        springRunning = true
    }

    /** Stops requesting frames; setting `following = true` never starts the spring by itself (R8). */
    internal fun stopSpring() {
        springRunning = false
    }

    /**
     * One display frame of the tail spring (TLV:514-538). `dt` is clamped
     * to `[0, 1/30]` s; a guard tick while unfollowing or tracking stops
     * the spring without moving; `|delta| < 0.5` snaps to the target; the
     * target is re-read every tick, so the spring chases a moving tail.
     * Returns whether the spring is still running.
     */
    internal fun springTick(frameNanos: Long): Boolean {
        val dt = ((frameNanos - lastTickNanos).toDouble() / 1e9).coerceIn(0.0, MAX_DT)
        lastTickNanos = frameNanos
        if (!following || tracking) {
            stopSpring()
            return false
        }
        val delta = maxOffset - offset
        if (abs(delta) < SNAP_DP) {
            offset = maxOffset
            stopSpring()
            report()
            return false
        }
        val factor = if (glide) {
            // Runway glide: the desktop's frame-rate-independent ease-out
            // (TLV:528-532). dt ≤ 1/30 makes the min(8, …) never bind.
            1.0 - GLIDE_RETAIN.toDouble().pow(min(8.0, dt * 60.0))
        } else {
            // Critically damped approach: the tail "settles" into place.
            1.0 - exp(-SETTLE_RATE * dt)
        }
        offset += delta * factor.toFloat()
        report()
        return true
    }

    /**
     * iOS `setFollowing` (TLV:240-248): no-op if unchanged, stops the
     * spring on `false`, never starts it on `true`. `internal` for ticket
     * 69's `beginOwnTurn` / queued-turn promotion.
     */
    internal fun setFollowing(value: Boolean) {
        if (following == value) return
        following = value
        if (!value) stopSpring()
    }

    /**
     * R11/R12 (SVC:373-378): a change of 0.5 or less is ignored entirely;
     * otherwise the hook runs first (69's runway refresh), then the offset
     * snaps to the new `maxOffset` **only while following** — without the
     * snap, a first-frame landing computed against a zero viewport would
     * stay past the tail (R12's adaptation: on iOS the bounds are always
     * known before the first apply).
     */
    private inline fun changeGeometry(value: Float, old: Float, set: (Float) -> Unit) {
        if (abs(value - old) <= GEOMETRY_EPSILON) return
        val wasFollowing = following
        set(value)
        onGeometryChanged?.invoke()
        if (wasFollowing) offset = maxOffset
        report()
    }

    /**
     * iOS `reportDistance()` (TLV:224-238): runs after every offset,
     * content or inset change. The momentum re-latch (R7): follow
     * re-engages only while momentum carries the list back down into the
     * last 70 dp — moving toward the tail, or already past it — and never
     * while the finger is down (a drag that starts at the bottom would
     * otherwise re-latch immediately and spring back down on release).
     * The spring is never started here.
     */
    private fun report() {
        val distance = distanceFromBottom
        val movingDown = offset > lastReportedOffset + 0.1f
        lastReportedOffset = offset
        if (!following && !tracking && flinging && (movingDown || distance <= 0f) && distance < RELATCH_DP) {
            setFollowing(true)
        }
    }

    private companion object {
        /** iOS settle rate k = 16 (`1 − e^(−16·dt)`, TLV:535). */
        const val SETTLE_RATE = 16.0

        /** iOS glide retain (`1 − 0.85^frames`, TLV:64). */
        const val GLIDE_RETAIN = 0.85

        /** The `dt` clamp: 1/30 s (TLV:513). */
        const val MAX_DT = 1.0 / 30.0

        /** The snap threshold (dp, TLV:527) and the geometry-change epsilon (SVC:373). */
        const val SNAP_DP = 0.5f
        const val GEOMETRY_EPSILON = 0.5f

        /** Release and momentum re-latch distance (dp, TLV:224/253). */
        const val RELATCH_DP = 70f

        /** Release finger-velocity limit (dp/s, positive = finger down, TLV:255). */
        const val RELEASE_VELOCITY_DP = 50f
    }
}
