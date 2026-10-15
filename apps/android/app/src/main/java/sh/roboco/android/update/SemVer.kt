package sh.roboco.android.update

import androidx.compose.runtime.Immutable
import kotlin.math.sign

/**
 * A semantic version (android-next#04): `major.minor.patch` with an
 * optional pre-release, ordered by semver 2.0.0 §11 — numeric triple
 * first, then pre-release precedence (a pre-release is **lower** than
 * the release it precedes, numeric identifiers beat alphanumeric ones,
 * and a longer identifier list wins when the prefixes are equal). Build
 * metadata (`+…`) is ignored in precedence.
 *
 * The parse is deliberately lenient where the desktop's is
 * (`crates/proto/src/compat.rs`): a leading `v` is tolerated, missing
 * minor/patch parts read as 0, and whitespace is trimmed — because the
 * comparator also feeds the engine-version nudge, where an engine's
 * `Device.version` row is user-visible text. Anything unparseable is
 * `null` and never blocks (no update, no nudge), like the desktop.
 */
@Immutable
data class SemVer(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val preRelease: List<String> = emptyList(),
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        return comparePreRelease(preRelease, other.preRelease)
    }

    companion object {

        /** Parses [text]; `null` when it is not a version at all. */
        fun parse(text: String?): SemVer? {
            if (text == null) return null
            val trimmed = text.trim()
            val vStripped = trimmed.removePrefix("v")
            if (vStripped.isEmpty()) return null
            // Build metadata never participates in precedence.
            val noBuild = vStripped.substringBefore('+')
            val (core, pre) = noBuild.split('-', limit = 2).let {
                if (it.size == 2) it[0] to it[1] else (it[0] to null)
            }
            val parts = core.split('.')
            if (parts.isEmpty() || parts.size > 3) return null
            val numbers = LongArray(parts.size)
            for ((i, part) in parts.withIndex()) {
                numbers[i] = part.toLongOrNull() ?: return null
            }
            val identifiers = pre?.split('.')?.map { segment ->
                if (segment.isEmpty()) return null
                segment
            } ?: emptyList()
            return SemVer(
                major = numbers[0],
                minor = numbers.getOrElse(1) { 0L },
                patch = numbers.getOrElse(2) { 0L },
                preRelease = identifiers,
            )
        }

        /** semver 2.0.0 §11.4: the pre-release precedence ladder. */
        private fun comparePreRelease(a: List<String>, b: List<String>): Int {
            if (a == b) return 0
            // A version without a pre-release has higher precedence.
            if (a.isEmpty()) return 1
            if (b.isEmpty()) return -1
            val shared = minOf(a.size, b.size)
            for (i in 0 until shared) {
                val x = a[i]
                val y = b[i]
                val xn = x.toLongOrNull()
                val yn = y.toLongOrNull()
                when {
                    xn != null && yn != null -> xn.compareTo(yn).let { if (it != 0) return it }
                    // Numeric identifiers always have lower precedence.
                    xn != null -> return -1
                    yn != null -> return 1
                    // ASCII order (identifiers are [0-9A-Za-z-]), as a
                    // sign: String.compareTo returns the raw char gap, and
                    // every other branch here is -1 / 0 / 1.
                    else -> x.compareTo(y).sign.let { if (it != 0) return it }
                }
            }
            // A larger set of fields has higher precedence when the
            // preceding identifiers are equal.
            return a.size.compareTo(b.size)
        }
    }
}
