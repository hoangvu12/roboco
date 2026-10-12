package sh.roboco.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/**
 * The version derivation (android-app#13): `BuildConfig.VERSION_NAME`
 * comes from the workspace `Cargo.toml`, and `VERSION_CODE` follows
 * `major × 1_000_000 + minor × 1_000 + patch`.
 *
 * The buildSrc formula itself is pinned by buildSrc's own
 * `RobocoVersionTest` (run via `:buildSrc:test`); this test pins the
 * derivation wiring with worked literals.
 */
class VersionTest {

    private val repoRoot = File(System.getProperty("roboco.repoRoot"))

    @Test
    fun versionNameComesFromTheWorkspaceCargoToml() {
        // Independent parse: the `[workspace.package]` version line.
        val cargo = repoRoot.resolve("Cargo.toml").readLines()
        var inSection = false
        var parsed: String? = null
        for (line in cargo) {
            if (line.trim().startsWith("[")) {
                inSection = line.trim() == "[workspace.package]"
            } else if (inSection) {
                val match = Pattern.compile("""^\s*version\s*=\s*"([^"]+)"""").matcher(line)
                if (match.find()) parsed = match.group(1)
            }
        }
        assertEquals(parsed, BuildConfig.VERSION_NAME)
    }

    @Test
    fun versionCodeIsTheFormulaForTheCurrentVersion() {
        // 0.9.2 -> 0 × 1_000_000 + 9 × 1_000 + 2 = 9002 (worked literal).
        val (major, minor, patch) = BuildConfig.VERSION_NAME
            .substringBefore('-').split('.').map(String::toInt)
        val expected = major * 1_000_000 + minor * 1_000 + patch
        assertEquals(expected, BuildConfig.VERSION_CODE)
        assertTrue(BuildConfig.VERSION_CODE > 0)
    }
}
