package sh.roboco.android.core

import android.graphics.Paint
import android.graphics.Typeface
import android.icu.text.BreakIterator
import android.util.Log
import android.util.LruCache
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import uniffi.roboco_core.FaceRole
import uniffi.roboco_core.PlatformMeasurer

/**
 * Minikin as ground truth for glyphs the bundled faces don't cover
 * (CoreText's `CoreTextMeasurer` equivalent): widths in the same point
 * space Rust measures with, taken from the same [FontChain] typefaces the
 * painter draws with. The whole string is laid out as one run
 * (`Paint.getTextRunAdvances`), so fallback fonts, shaping and kerning
 * are chosen *in context*; a grapheme cluster's width lands on its first
 * scalar, zero on the rest. In mono faces a double-width cluster is
 * exactly two Geist Mono cells ([FontChain.isDoubleWidth]), which is also
 * how ticket 63's painter places it.
 *
 * Called on Rust layout worker threads, possibly several at once, and
 * through JNA: [Paint] and [BreakIterator] are thread-local, the memo is
 * an internally synchronised [LruCache], and neither method ever throws
 * (an exception across UniFFI would take down the layout worker).
 */
class AndroidMeasurer(private val typefaces: Map<FaceRole, Typeface>) : PlatformMeasurer {

    private val memo = LruCache<MKey, Any>(4096)

    private data class MKey(
        val face: FaceRole,
        val size: Float,
        val ligatures: Boolean,
        val text: String,
        val run: Boolean,
    )

    private val paints = ThreadLocal.withInitial {
        Paint(FontChain.TEXT_PAINT_FLAGS).apply { hinting = Paint.HINTING_OFF }
    }

    private val breaks = ThreadLocal.withInitial { BreakIterator.getCharacterInstance() }

    override fun measure(face: FaceRole, size: Float, ligatures: Boolean, text: String): Float {
        return try {
            measureMemoised(face, size, ligatures, text)
        } catch (failure: Throwable) {
            Log.e(TAG, "measurer failed", failure)
            0f
        }
    }

    override fun measureRun(face: FaceRole, size: Float, ligatures: Boolean, text: String): List<Float> {
        return try {
            measureRunMemoised(face, size, ligatures, text)
        } catch (failure: Throwable) {
            Log.e(TAG, "measurer failed", failure)
            List(text.codePointCount(0, text.length)) { 0f }
        }
    }

    private fun measureMemoised(face: FaceRole, size: Float, ligatures: Boolean, text: String): Float {
        val key = MKey(face, size, ligatures, text, run = false)
        (memo.get(key) as? Float)?.let { return it }
        val t0 = System.nanoTime()
        val width = if (FontChain.isMono(face) && FontChain.hasWide(text)) {
            measureRun(face, size, ligatures, text).sum()
        } else {
            // Never `measureText`: it rounds the advance up to a whole unit.
            FontChain.advance(paint(face, size, ligatures), text, 0, text.length, firstStrongIsRtl(text))
        }
        Stats.calls.incrementAndGet()
        Stats.nanos.addAndGet(System.nanoTime() - t0)
        memo.put(key, width)
        return width
    }

    private fun measureRunMemoised(face: FaceRole, size: Float, ligatures: Boolean, text: String): List<Float> {
        val key = MKey(face, size, ligatures, text, run = true)
        (memo.get(key) as? List<*>)?.let { stored ->
            @Suppress("UNCHECKED_CAST")
            return stored as List<Float>
        }
        val t0 = System.nanoTime()
        val advances = computeRun(face, size, ligatures, text)
        Stats.calls.incrementAndGet()
        Stats.nanos.addAndGet(System.nanoTime() - t0)
        val immutable = Collections.unmodifiableList(advances)
        memo.put(key, immutable)
        return immutable
    }

    private fun computeRun(face: FaceRole, size: Float, ligatures: Boolean, text: String): List<Float> {
        val n = text.length
        if (n == 0) return emptyList()
        val mono = FontChain.isMono(face)
        val p = paint(face, size, ligatures)
        val cell = if (mono) FontChain.cellWidth(p) else 0f
        val chars = text.toCharArray()
        val units = FloatArray(n)
        p.getTextRunAdvances(chars, 0, n, 0, n, firstStrongIsRtl(text), units, 0)
        val out = ArrayList<Float>(text.codePointCount(0, n))
        val boundaries = breaks.get()!!
        boundaries.setText(text)
        var start = boundaries.first()
        var end = boundaries.next()
        while (end != BreakIterator.DONE) {
            val first = text.codePointAt(start)
            val width = if (mono && FontChain.isDoubleWidth(first)) {
                cell * 2f
            } else {
                var sum = 0f
                for (unit in start until end) sum += units[unit]
                sum
            }
            out.add(width)
            var next = start + Character.charCount(first)
            while (next < end) {
                out.add(0f)
                next += Character.charCount(text.codePointAt(next))
            }
            start = end
            end = boundaries.next()
        }
        // Rust turns a wrong-length answer into `runs_unsupported` for
        // the whole width cache (`crates/text/src/cache.rs:340-344`), so
        // the length contract is absolute. This must not happen; if it
        // ever does, fold per scalar instead (the #609/iOS shape).
        if (out.size != text.codePointCount(0, n)) {
            return foldPerScalar(text, units, n)
        }
        return out
    }

    private fun foldPerScalar(text: String, units: FloatArray, n: Int): List<Float> {
        val out = ArrayList<Float>(text.codePointCount(0, n))
        var index = 0
        while (index < n) {
            val cp = text.codePointAt(index)
            val charCount = Character.charCount(cp)
            var sum = 0f
            for (unit in index until minOf(n, index + charCount)) sum += units[unit]
            out.add(sum)
            index += charCount
        }
        return out
    }

    private fun paint(face: FaceRole, size: Float, ligatures: Boolean): Paint =
        paints.get()!!.also { FontChain.applyTo(it, face, size, ligatures, typefaces.getValue(face)) }

    /**
     * Unicode bidi P2/P3: the first strongly-directional code point
     * decides the run direction (R/AL → rtl). Rust sends uncovered
     * Arabic/Hebrew runs here, and the direction keeps their shaping and
     * joining widths correct. False when there is none.
     */
    private fun firstStrongIsRtl(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            val directionality = Character.getDirectionality(cp)
            if (directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return false
            if (directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
            ) return true
            index += Character.charCount(cp)
        }
        return false
    }

    /**
     * Diagnostics for the transcript lab (tickets 135/136): call and time
     * counters, incremented on **memo misses only**, since a hit costs
     * nothing to serve.
     */
    companion object Stats {
        private const val TAG = "Roboco"

        val calls = AtomicLong()
        val nanos = AtomicLong()

        fun reset() {
            calls.set(0)
            nanos.set(0)
        }

        fun report() {
            val measured = calls.get()
            if (measured > 0) {
                Log.i("RobocoTranscript", "measurer: $measured calls, ${nanos.get() / 1_000_000}ms total")
            }
        }
    }
}
