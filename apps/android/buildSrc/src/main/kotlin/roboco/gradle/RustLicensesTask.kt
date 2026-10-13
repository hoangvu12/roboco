package roboco.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * One invocation of `cargo xtask android-licenses` (android-ui-parity
 * #101): writes the Rust core's licence data
 * (`assets/licenses/rust-crates.json`) — every external crate linked into
 * `libroboco_mobile.so`, with its licence expression and bundled licence
 * texts.
 *
 * Like [AndroidCoreBuild], the xtask is the single entry point on Windows
 * and Linux; Gradle never re-implements the `cargo metadata` walk. The
 * task declares its output directory and the full input set (the root
 * manifests, the lockfile, every crate manifest, the xtask sources, the
 * language property) so up-to-date checks skip it (#609 review bug 11:
 * tasks without outputs rerun every build).
 *
 * One task per build type, each depending on the matching
 * `build<BuildType>Core` task: `cargo metadata` needs the registry sources
 * unpacked, which the core build guarantees, and a release-only build
 * must never drag the debug core in through a shared task's dependency.
 */
abstract class RustLicensesTask : DefaultTask() {

    @get:Inject
    protected abstract val exec: ExecOperations

    /** xtask languages: `mobile` (grammar subset) or `all`. */
    @get:Input
    abstract val languages: Property<String>

    /** The repository root — the xtask's working directory. */
    @get:Internal
    abstract val repoRoot: DirectoryProperty

    /**
     * Everything the output depends on: the workspace manifests and
     * lockfile (the resolved graph), every crate manifest (the licence
     * fields) and the xtask's own sources.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sources: ConfigurableFileCollection

    /**
     * The assets root AGP wires in; the JSON lands at
     * `licenses/rust-crates.json` inside it.
     */
    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = assetsDir.get().asFile.resolve("licenses").resolve("rust-crates.json")
        exec.exec {
            workingDir(repoRoot.get().asFile)
            commandLine(
                "cargo",
                "xtask",
                "android-licenses",
                "--out",
                out.absolutePath,
                "--languages",
                languages.get(),
            )
        }
    }
}
