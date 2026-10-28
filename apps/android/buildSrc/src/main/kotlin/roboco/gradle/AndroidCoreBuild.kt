package roboco.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
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
 * One invocation of `cargo xtask android-core` (android-app#10, wired by
 * android-app#13): builds the Rust mobile core for the requested ABIs,
 * generates the UniFFI Kotlin bindings, and copies the host library that
 * JVM (Robolectric) tests load through JNA.
 *
 * The xtask is the single entry point on Windows and Linux — Gradle never
 * re-implements its steps (bindgen, NDK lookup, the 16 KB ELF check), never
 * shells out through bash, and never hard-codes `.exe`.
 *
 * `-ProbocoSkipCore` skips the build **only while every requested ABI plus
 * the bindings and the host library exist** (upstream #609 skipped on arm64
 * alone, review bug 11: JNA then dies with `UnsatisfiedLinkError`).
 *
 * Without [buildHost] (ci-speed#07, see [HostCore]) the xtask runs with
 * `--skip-host`: no host library, and the bindings come from the first
 * requested ABI's library.
 */
abstract class AndroidCoreBuild : DefaultTask() {

    @get:Inject
    protected abstract val exec: ExecOperations

    /** ABIs to build; also selects which `jniLibs/<abi>` directories exist. */
    @get:Input
    abstract val abis: ListProperty<String>

    /** xtask profile: `mobile` (debug) or `mobile-dist` (release). */
    @get:Input
    abstract val profile: Property<String>

    /** xtask languages: `mobile` (grammar subset) or `all`. */
    @get:Input
    abstract val languages: Property<String>

    /** `ANDROID_NDK_HOME` for the xtask, from AGP's resolved NDK. */
    @get:Input
    abstract val ndkHome: Property<String>

    /**
     * Whether `libroboco_tailcat.so` (the Go Tailcat transport, android-next#02)
     * is built into every ABI. Off: `--skip-tailcat`, and the core reports
     * Tailcat as unavailable at pair time.
     */
    @get:Input
    abstract val tailcat: Property<Boolean>

    /** `-ProbocoSkipCore`: skip while all outputs exist (never when missing). */
    @get:Input
    abstract val skipIfOutputsExist: Property<Boolean>

    /**
     * Whether to build the host library (JVM tests only). An input, so a
     * test run after an APK-only build reruns the task and gets it.
     */
    @get:Input
    abstract val buildHost: Property<Boolean>

    /** The repository root — the xtask's working directory. */
    @get:Internal
    abstract val repoRoot: DirectoryProperty

    /** The workspace manifest, lock, `.cargo` config, the core's crates and the Tailcat adapter. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sources: ConfigurableFileCollection

    /** Generated UniFFI Kotlin (package `uniffi.roboco_core`). */
    @get:OutputDirectory
    abstract val kotlinDir: DirectoryProperty

    /** `jniLibs/<abi>/libroboco_mobile.so` (and `libroboco_tailcat.so`) for the APK. */
    @get:OutputDirectory
    abstract val jniLibsDir: DirectoryProperty

    /** The host library JVM tests load through JNA. */
    @get:OutputDirectory
    abstract val hostDir: DirectoryProperty

    init {
        // Skips only while the bindings, the host library (when built) and a
        // `.so` for every requested ABI are all present.
        onlyIf { !skipIfOutputsExist.get() || !outputsComplete() }
    }

    private fun outputsComplete(): Boolean {
        val out = hostDir.get().asFile.parentFile
        if (!out.resolve("kotlin/uniffi/roboco_core/roboco_core.kt").isFile) return false
        val host = hostDir.get().asFile
        val hostLibrary = host.resolve("roboco_mobile.dll").isFile ||
            host.resolve("libroboco_mobile.so").isFile
        if (buildHost.get() && !hostLibrary) return false
        val jniLibs = jniLibsDir.get().asFile
        return abis.get().all { abi ->
            jniLibs.resolve("$abi/libroboco_mobile.so").isFile &&
                (!tailcat.get() || jniLibs.resolve("$abi/libroboco_tailcat.so").isFile)
        }
    }

    @TaskAction
    fun buildCore() {
        exec.exec {
            workingDir(repoRoot.get().asFile)
            commandLine(
                "cargo",
                "xtask",
                "android-core",
                "--out",
                hostDir.get().asFile.parentFile.absolutePath,
                "--abis",
                abis.get().joinToString(","),
                "--profile",
                profile.get(),
                "--languages",
                languages.get(),
                "--check",
                *(if (tailcat.get()) emptyArray<String>() else arrayOf("--skip-tailcat")),
                *(if (buildHost.get()) emptyArray<String>() else arrayOf("--skip-host")),
            )
            // Pin the NDK AGP resolved, not "the highest installed".
            environment("ANDROID_NDK_HOME", ndkHome.get())
        }
    }
}
