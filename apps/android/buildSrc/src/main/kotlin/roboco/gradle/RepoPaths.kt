package roboco.gradle

import org.gradle.api.Project
import java.io.File

/**
 * The repository root as seen from `apps/android` (two levels above the
 * Gradle root project). Resolved at configuration time only; task inputs
 * hold plain files, so nothing reads `Project` state at execution time
 * (configuration-cache safe).
 */
object RepoPaths {

    fun root(project: Project): File = project.rootDir.parentFile.parentFile

    /** `target/android-core/<profile>` — the xtask's output root. */
    fun androidCore(project: Project, profile: String): File =
        root(project).resolve("target/android-core").resolve(profile)
}
