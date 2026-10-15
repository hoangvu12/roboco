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
 * Copies the desktop's session chimes into the generated APK resources
 * (android-app#28): `crates/ui/assets/sounds/{done,request,attention}.wav`
 * → `res/raw/chime_*.wav`, byte-identical — the channel sounds must be the
 * exact bytes the desktop plays (the ticket's SHA-256 pins). `appshot.wav`
 * is deliberately NOT copied; #699 committed mastered `fx_chime_*` copies,
 * Roboco does not re-master.
 *
 * A dedicated task (rather than a bare `Sync`) so AGP's
 * `addGeneratedSourceDirectory` wiring sees a plain `@OutputDirectory`
 * `DirectoryProperty` (same shape as [FontsSync]).
 */
abstract class ChimesSync : DefaultTask() {

    /** The three desktop chimes; the task copies nothing else. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val chimeFiles: ConfigurableFileCollection

    /** The generated res root; chimes land in `raw/`. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val raw: File = outputDir.get().asFile.resolve("raw")
        // Reset first (unconditionally) so a renamed or removed chime never
        // lingers as a stale resource.
        raw.deleteRecursively()
        // No inputs → no `raw/` at all (ChimesSyncTest's empty case).
        if (!chimeFiles.isEmpty) raw.mkdirs()
        for (file in chimeFiles) {
            val target = "chime_${file.nameWithoutExtension}.wav"
            file.copyTo(raw.resolve(target), overwrite = true)
        }
    }
}
