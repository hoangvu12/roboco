package sh.roboco.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The update version comparator (android-next#04): semver 2.0.0
 * precedence with an optional `v` prefix, mirroring the desktop's
 * lenient parse (`crates/proto/src/compat.rs`, `trim_start_matches('v')`)
 * but with real pre-release ordering — a pre-release is **lower** than
 * the release it precedes. Plain JVM.
 */
class SemVerTest {

    // -------------------------------------------------------- the parse

    @Test
    fun parsesTheThreePartCore() {
        assertEquals(SemVer(0, 10, 0), SemVer.parse("0.10.0"))
        assertEquals(SemVer(1, 2, 3), SemVer.parse("1.2.3"))
    }

    @Test
    fun parsesAnOptionalVPrefix() {
        // release.yml writes the manifest version without `v`
        // (`ver="${GITHUB_REF_NAME#v}"`), but a `v` is tolerated like the
        // desktop's parse_version.
        assertEquals(SemVer(1, 0, 0), SemVer.parse("v1.0.0"))
        assertEquals(SemVer(0, 9, 2), SemVer.parse(" 0.9.2 "))
    }

    @Test
    fun parsesPreReleaseIdentifiersAndIgnoresBuildMetadata() {
        assertEquals(SemVer(1, 0, 0, listOf("rc", "1")), SemVer.parse("1.0.0-rc.1"))
        assertEquals(SemVer(1, 0, 0), SemVer.parse("1.0.0+build.5"))
        assertEquals(SemVer(1, 0, 0, listOf("alpha")), SemVer.parse("1.0.0-alpha+001"))
    }

    @Test
    fun missingPatchAndMinorPartsDefaultToZero() {
        // Lenient like the desktop's parse: missing parts are 0.
        assertEquals(SemVer(2, 0, 0), SemVer.parse("2"))
        assertEquals(SemVer(2, 5, 0), SemVer.parse("2.5"))
    }

    @Test
    fun unparseableVersionsReturnNull() {
        assertNull(SemVer.parse(""))
        assertNull(SemVer.parse("dev"))
        assertNull(SemVer.parse("1.x.0"))
        assertNull(SemVer.parse("1.0.0-"))
        assertNull(SemVer.parse(null))
    }

    // ---------------------------------------------------- the ordering

    @Test
    fun theTicketsComparisons() {
        // "0.9.0 < 0.10.0" — numeric, never lexical.
        assertEquals(-1, SemVer.parse("0.9.0")!!.compareTo(SemVer.parse("0.10.0")!!))
        // "1.0.0-rc.1 < 1.0.0" — a pre-release is lower than its release.
        assertEquals(-1, SemVer.parse("1.0.0-rc.1")!!.compareTo(SemVer.parse("1.0.0")!!))
    }

    @Test
    fun equalVersionsCompareZero() {
        assertEquals(0, SemVer.parse("0.9.2")!!.compareTo(SemVer.parse("0.9.2")!!))
        assertEquals(0, SemVer.parse("1.0.0")!!.compareTo(SemVer.parse("v1.0.0")!!))
        // Build metadata is ignored in precedence (semver §10).
        assertEquals(0, SemVer.parse("1.0.0+1")!!.compareTo(SemVer.parse("1.0.0+2")!!))
    }

    @Test
    fun olderAndNewerBothOrder() {
        assertEquals(-1, SemVer.parse("0.9.9")!!.compareTo(SemVer.parse("1.0.0")!!))
        assertEquals(1, SemVer.parse("1.0.1")!!.compareTo(SemVer.parse("1.0.0")!!))
        assertEquals(1, SemVer.parse("1.1.0")!!.compareTo(SemVer.parse("1.0.9")!!))
    }

    @Test
    fun theSemverPreReleasePrecedenceLadder() {
        // semver 2.0.0 §11, verbatim example.
        val ladder = listOf(
            "1.0.0-alpha",
            "1.0.0-alpha.1",
            "1.0.0-alpha.beta",
            "1.0.0-beta",
            "1.0.0-beta.2",
            "1.0.0-beta.11",
            "1.0.0-rc.1",
            "1.0.0",
        )
        for (i in ladder.indices) {
            for (j in ladder.indices) {
                val a = SemVer.parse(ladder[i])!!
                val b = SemVer.parse(ladder[j])!!
                assertEquals("i=$i j=$j", i.compareTo(j), a.compareTo(b))
            }
        }
    }

    @Test
    fun numericIdentifiersCompareNumericallyAndBelowAlphanumeric() {
        assertEquals(-1, SemVer.parse("1.0.0-2")!!.compareTo(SemVer.parse("1.0.0-10")!!))
        // Numeric identifiers always have lower precedence than
        // alphanumeric ones (semver §11.4).
        assertEquals(-1, SemVer.parse("1.0.0-1")!!.compareTo(SemVer.parse("1.0.0-alpha")!!))
        assertEquals(1, SemVer.parse("1.0.0-alpha")!!.compareTo(SemVer.parse("1.0.0-1")!!))
    }
}
