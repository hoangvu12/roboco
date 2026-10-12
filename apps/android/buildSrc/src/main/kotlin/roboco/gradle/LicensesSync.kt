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
 * Copies the repository's `LICENSE` and `THIRD_PARTY_NOTICES.md` into the
 * generated APK assets (android-app#31): a sideloaded APK is the whole
 * distribution, so its licenses ship inside it. Settings → About (ticket 26)
 * displays these files from `assets/licenses/`, beside the font licenses
 * `syncRobocoFonts` stages.
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

    /** The generated assets root; notices land in `licenses/`. */
    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val licenses: File = assetsDir.get().asFile.resolve("licenses")
        licenses.mkdirs()
        for (file in noticeFiles) {
            file.copyTo(licenses.resolve(file.name), overwrite = true)
        }
    }
}
