package sh.roboco.android.design

import java.io.File

/**
 * Walks up from the working directory (Gradle sets it to `apps/android/app`)
 * to the repository root: the first ancestor containing
 * `web/packages/theme/src/generated/artifact.json`.
 *
 * Shared by the pin tests that read repo files
 * (`ThemeArtifactPinTest`, `ProjectTonesPinTest`).
 */
internal object TestRepoRoot {
    fun root(): File {
        val start = System.getProperty("user.dir")?.let(::File)?.absoluteFile
            ?: throw AssertionError("user.dir is not set; run from the Gradle module directory")
        var dir: File? = start
        while (dir != null) {
            if (File(dir, "web/packages/theme/src/generated/artifact.json").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError(
            "repo root not found: walked up from $start " +
                "without finding web/packages/theme/src/generated/artifact.json",
        )
    }
}
