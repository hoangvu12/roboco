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
 * Copies the 12 bundled Geist faces and their licenses into the generated
 * APK assets (android-app#13). The exact font bytes the Rust layout engine
 * measures; the OFL requires shipping the license, and the file icons'
 * vscode-symbols art requires its LICENSE.symbols.
 *
 * A dedicated task (rather than a bare `Sync`) so AGP's
 * `addGeneratedSourceDirectory` wiring sees a plain `@OutputDirectory`
 * `DirectoryProperty`.
 */
abstract class FontsSync : DefaultTask() {

    /** The 12 faces, `crates/ui/assets/fonts/<file>`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val fontFiles: ConfigurableFileCollection

    /** The OFL and the vscode-symbols license. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val licenseFiles: ConfigurableFileCollection

    /** The generated assets root; fonts land in `fonts/`, licenses in `licenses/`. */
    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val out = assetsDir.get().asFile
        val fonts = out.resolve("fonts")
        val licenses = out.resolve("licenses")
        for (dir in listOf(out, fonts, licenses)) {
            dir.mkdirs()
        }
        copy(fontFiles, fonts)
        copy(licenseFiles, licenses)
    }

    private fun copy(files: ConfigurableFileCollection, into: File) {
        for (file in files) {
            val target = if (file.name == "LICENSE.symbols") "vscode-symbols-LICENSE.txt" else file.name
            file.copyTo(into.resolve(target), overwrite = true)
        }
    }
}
