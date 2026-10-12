package roboco.gradle

import java.io.File

/**
 * Version derivation from the workspace `Cargo.toml` (android-app#13).
 *
 * `versionName` is the `[workspace.package]` version (overridable with
 * `-ProbocoVersionName`); `versionCode` is
 * `major × 1_000_000 + minor × 1_000 + patch` (overridable with
 * `-ProbocoVersionCode`), ignoring any `-suffix`. Ticket 31 (release
 * packaging) relies on `tag == versionName` matching this derivation.
 */
object RobocoVersion {

    private val VERSION_LINE = Regex("""^\s*version\s*=\s*"([^"]+)"""")

    /** The `[workspace.package]` version of the given `Cargo.toml`. */
    fun versionName(cargoToml: File): String {
        var inWorkspacePackage = false
        for (line in cargoToml.readLines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) {
                inWorkspacePackage = trimmed == "[workspace.package]"
                continue
            }
            if (inWorkspacePackage) {
                val match = VERSION_LINE.find(line) ?: continue
                return match.groupValues[1]
            }
        }
        error("no [workspace.package] version in ${cargoToml.path}")
    }

    /** `0.9.2` → 9002, `1.0.0` → 1000000; minor/patch ≥ 1000 fails. */
    fun versionCode(versionName: String): Int {
        val numbers = versionName.substringBefore('-').split('.')
        require(numbers.size == 3) { "expected major.minor.patch, got $versionName" }
        val major = numbers[0].toInt()
        val minor = numbers[1].toInt()
        val patch = numbers[2].toInt()
        require(minor < 1000 && patch < 1000) {
            "minor and patch must stay below 1000 for versionCode: $versionName"
        }
        return major * 1_000_000 + minor * 1_000 + patch
    }

    /** `versionName` with the `-ProbocoVersionName` override applied. */
    fun versionName(cargoToml: File, override: String?): String =
        override ?: versionName(cargoToml)

    /** `versionCode` with the `-ProbocoVersionCode` override applied. */
    fun versionCode(versionName: String, override: Int?): Int =
        override ?: versionCode(versionName)
}
