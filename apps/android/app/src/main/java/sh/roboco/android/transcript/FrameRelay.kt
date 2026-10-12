package sh.roboco.android.transcript

import android.view.Choreographer
import java.util.concurrent.atomic.AtomicBoolean
import uniffi.roboco_core.LayoutListener

/**
 * Posts one-shot main-thread frame callbacks. Thread-safe.
 * Production: [ChoreographerPoster]; tests: a manual queue.
 */
interface FramePoster {
    fun post(callback: Choreographer.FrameCallback)
    fun remove(callback: Choreographer.FrameCallback)
}

/**
 * [choreographer] must be the main thread's (capture it on main). Its
 * post/remove are callable from any thread.
 */
class ChoreographerPoster(private val choreographer: Choreographer) : FramePoster {
    override fun post(callback: Choreographer.FrameCallback) = choreographer.postFrameCallback(callback)
    override fun remove(callback: Choreographer.FrameCallback) = choreographer.removeFrameCallback(callback)
}

/**
 * Hops layout-thread frame notifications to the main thread, coalescing: if
 * several frames land before the next vsync, only the newest is applied
 * (`ios:Transcript/FrameRelay.swift`).
 *
 * `frameReady` runs on the Rust layout thread (`roboco-layout`) through a
 * JNA callback, so it must never throw and never touch the engine or
 * Compose state: it only flips [scheduled] and posts [callback]. The main
 * hop is a single [Choreographer.FrameCallback] — the apply lands on the
 * next vsync, before that frame's draw, so a streaming burst collapses to
 * at most one apply per display frame.
 */
internal class FrameRelay(
    private val poster: FramePoster,
    private val apply: () -> Unit,
) : LayoutListener {
    private val scheduled = AtomicBoolean(false)

    @Volatile
    private var closed = false

    private val callback = Choreographer.FrameCallback {
        scheduled.set(false) // before apply: a frame published during apply schedules again
        if (!closed) apply()
    }

    /** Layout thread. Never touches the engine or Compose state; must not throw. */
    override fun frameReady(revision: ULong) {
        if (closed) return
        if (scheduled.compareAndSet(false, true)) poster.post(callback)
    }

    /** Main thread. Late notifications and an already-posted callback become no-ops. */
    fun close() {
        closed = true
        poster.remove(callback)
    }
}
