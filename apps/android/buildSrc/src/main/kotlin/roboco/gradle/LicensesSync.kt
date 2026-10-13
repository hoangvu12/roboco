package roboco.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Copies the repository's `LICENSE` and `THIRD_PARTY_NOTICES.md`, plus the
 * committed SPDX fallback texts (android-ui-parity #101), into the
 * generated APK assets (android-app#31): a sideloaded APK is the whole
 * distribution, so its licenses ship inside it. Settings → About (ticket
 * 26) displays these files from `assets/licenses/`, beside the font
 * licenses `syncRobocoFonts` stages and the `rust-crates.json` the
 * `RustLicensesTask` writes.
 *
 * A dedicated task (rather than a bare `Sync`) so AGP's
 * `addGeneratedSourceDirectory` wiring sees a plain `@OutputDirectory`
 * `DirectoryProperty` (same shape as [FontsSync]).
 */
abstract class LicensesSync : DefaultTask() {

    /** The repo root's `LICENSE` and `THIRD_PARTY_NOTICES.md`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val noticeFiles: ConfigurableFileCollection

    /**
     * The committed canonical SPDX texts (`apps/android/licenses/spdx/`),
     * the fallback for AboutLibraries licences whose `content` is empty
     * in offline mode (ticket 101). Empty = the folder is empty, which is
     * valid when the plugin fills every `content`.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val spdxFiles: ConfigurableFileCollection

    /** The generated assets root; notices land in `licenses/`, SPDX texts in `licenses/spdx/`. */
    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val licenses: File = assetsDir.get().asFile.resolve("licenses")
        licenses.mkdirs()
        for (file in noticeFiles) {
            file.copyTo(licenses.resolve(file.name), overwrite = true)
        }
        // Reset first (unconditionally) so a removed SPDX text never
        // lingers as a stale asset; recreate only when there is something
        // to stage (an empty spdx/ dir would zip into every APK).
        val spdx = licenses.resolve("spdx")
        spdx.deleteRecursively()
        val spdxSources = spdxFiles.toList()
        if (spdxSources.isNotEmpty()) {
            spdx.mkdirs()
            for (file in spdxSources) {
                file.copyTo(spdx.resolve(file.name), overwrite = true)
            }
        }
    }
}
