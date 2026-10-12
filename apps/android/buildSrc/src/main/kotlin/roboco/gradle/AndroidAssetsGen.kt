package roboco.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Runs `roboco-android-assets` (android-app#13): the Rust generator that
 * converts the shared line icons to VectorDrawables and rasterizes the tool
 * and file icons to 96×96 PNGs. It replaces upstream #609's
 * `svg2vd.py`/`gen-icons.sh` so the pipeline is bash-free and works on
 * Windows hosts (no `rsvg-convert`, no locale-encoded Python).
 *
 * Deterministic by contract (see `crates/android-assets/tests/pipeline.rs`),
 * so Gradle's up-to-date checks are meaningful.
 */
abstract class AndroidAssetsGen : DefaultTask() {

    @get:Inject
    protected abstract val exec: ExecOperations

    /** The repository root — passed as `--repo` (the crate's working dir). */
    @get:Internal
    abstract val repoRoot: DirectoryProperty

    /** The generator crate, its lock inputs, and every SVG source it reads. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sources: ConfigurableFileCollection

    /** `res/` root: the generator writes `drawable/zi_<name>.xml` under it. */
    @get:OutputDirectory
    abstract val resDir: DirectoryProperty

    /** `assets/` root: the generator writes `icons/<name>.png` under it. */
    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    @TaskAction
    fun generate() {
        exec.exec {
            workingDir(repoRoot.get().asFile)
            commandLine(
                "cargo",
                "run",
                "--locked",
                "-p",
                "roboco-android-assets",
                "--release",
                "--",
                "--repo",
                repoRoot.get().asFile.absolutePath,
                "--res-out",
                resDir.get().asFile.absolutePath,
                "--assets-out",
                assetsDir.get().asFile.absolutePath,
            )
        }
    }
}
