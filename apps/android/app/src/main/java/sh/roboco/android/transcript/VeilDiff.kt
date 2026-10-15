package sh.roboco.android.transcript

/**
 * Where `new` is `old` with exactly one run of `new.length - old.length`
 * UTF-16 units inserted (prefix + suffix diff, pr609 Transcript.kt:333-349).
 * Returns the insertion index, or -1 when `new` is not longer or is more than
 * one insertion (a re-flow). Prefix + suffix, not startsWith: list rows end
 * with their bullet (mob markdown.rs:561-567), so streamed words land before it.
 */
internal object VeilDiff {
    fun insertionAt(old: CharSequence, new: CharSequence): Int {
        val delta = new.length - old.length
        if (delta <= 0) return -1
        val max = old.length
        var p = 0
        while (p < max && old[p] == new[p]) p++
        var sfx = 0
        while (sfx < max - p && old[old.length - 1 - sfx] == new[new.length - 1 - sfx]) sfx++
        return if (new.length - p - sfx == delta) p else -1
    }
}
