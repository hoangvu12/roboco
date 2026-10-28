import roboco.gradle.AndroidAssetsGen
import roboco.gradle.AndroidCoreBuild
import roboco.gradle.HostCore
import roboco.gradle.LicensesSync
import roboco.gradle.RepoPaths
import roboco.gradle.RobocoFonts
import roboco.gradle.RobocoVersion
import roboco.gradle.RustLicensesTask
import java.io.File
import java.time.Duration
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 carries built-in Kotlin: org.jetbrains.kotlin.android is NOT applied.
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.roborazzi)
    // AboutLibraries (ui-parity#101): the JSON emitter only — no
    // aboutlibraries-compose/-core library artifacts (ticket rule: the
    // plugin's UI is Material, the app's is not).
    alias(libs.plugins.aboutlibraries.android)
}

// ---------------------------------------------------------------------------
// Repo geometry: apps/android sits two levels below the repository root.
// ---------------------------------------------------------------------------
val repoRootDir: File = RepoPaths.root(project)

// ---------------------------------------------------------------------------
// ABIs (android-app#13): -ProbocoAbis / ROBOCO_ANDROID_ABIS, default both;
// unit-test-only invocations default to the host emulator ABI (x86_64) so
// day-to-day test runs never cross-compile arm64.
// ---------------------------------------------------------------------------
val unitTestOnly: Boolean = gradle.startParameter.taskNames.isNotEmpty() &&
    gradle.startParameter.taskNames.all { task ->
        val name = task.substringAfterLast(':').lowercase()
        name in setOf(
            "test",
            "testdebugunittest",
            "compiledebugunittestkotlin",
            "recordroborazzidebug",
            "verifyroborazzidebug",
            "parityreport",
            "paritymanifest",
        )
    }

val requestedAbis: List<String> = providers.gradleProperty("robocoAbis")
    .orElse(providers.environmentVariable("ROBOCO_ANDROID_ABIS"))
    .map { it.split(',', ' ', ';').filter(String::isNotBlank) }
    .orElse(
        providers.provider {
            if (unitTestOnly) listOf("x86_64") else listOf("arm64-v8a", "x86_64")
        },
    )
    .get()

// ---------------------------------------------------------------------------
// Version derivation from the workspace Cargo.toml (android-app#13).
// ---------------------------------------------------------------------------
val appVersionName: String = RobocoVersion.versionName(
    repoRootDir.resolve("Cargo.toml"),
    providers.gradleProperty("robocoVersionName").orNull,
)
val appVersionCode: Int = RobocoVersion.versionCode(
    appVersionName,
    providers.gradleProperty("robocoVersionCode").map { it.toInt() }.orNull,
)

// ---------------------------------------------------------------------------
// The Rust core (android-app#10): one AndroidCoreBuild per build type, into
// target/android-core/<profile> so switching build types never thrashes one
// output directory. -ProbocoCoreProfile overrides the profile per build type.
// ---------------------------------------------------------------------------
val skipCore = providers.gradleProperty("robocoSkipCore").isPresent
// android-next#02: the Go Tailcat transport is built unless
// -ProbocoSkipTailcat or ROBOCO_ANDROID_TAILCAT=0/false/no/off (the xtask
// reads the same variable).
val tailcatEnabled: Boolean = !providers.gradleProperty("robocoSkipTailcat").isPresent &&
    providers.environmentVariable("ROBOCO_ANDROID_TAILCAT")
        .map { it.trim().lowercase() !in setOf("0", "false", "no", "off") }
        .orElse(true)
        .get()
val coreLanguages = providers.gradleProperty("robocoCoreLanguages").orElse("mobile")
// ci-speed#07: only the JVM tests load a host core (debugHostDir below), so
// release and benchmark never build one, and debug builds it only when the
// invocation may run those tests (HostCore). Without it the xtask reads the
// bindings out of the first ABI's library (--skip-host).
val debugHostCore: Boolean = HostCore.needed(gradle.startParameter.taskNames)

val coreSources = files(
    repoRootDir.resolve("Cargo.toml"),
    repoRootDir.resolve("Cargo.lock"),
    repoRootDir.resolve(".cargo/config.toml"),
    fileTree(repoRootDir.resolve("crates/mobile")),
    fileTree(repoRootDir.resolve("crates/mobile-bindgen")),
    fileTree(repoRootDir.resolve("crates/mobile-client")),
    fileTree(repoRootDir.resolve("crates/text")),
    fileTree(repoRootDir.resolve("crates/markdown")),
    fileTree(repoRootDir.resolve("crates/doc")),
    fileTree(repoRootDir.resolve("crates/proto")),
    fileTree(repoRootDir.resolve("crates/syntax")),
    fileTree(repoRootDir.resolve("crates/xtask")),
    fileTree(repoRootDir.resolve("adapters/roboco-tailcat")) {
        include("**/*.go", "go.mod", "go.sum")
    },
)

fun coreTaskName(buildType: String): String =
    "build" + buildType.replaceFirstChar { it.uppercase() } + "Core"

// ui-parity #136: the `benchmark` build type runs the lab against a
// release-config core, so it maps to `mobile-dist` like release.
fun coreProfile(buildType: String): String =
    providers.gradleProperty("robocoCoreProfile")
        .orElse(if (buildType == "release" || buildType == "benchmark") "mobile-dist" else "mobile")
        .get()

// ui-parity#101: the Rust licence data's inputs — the workspace manifests
// and lockfile (the resolved graph), every crate manifest (the licence
// fields) and the xtask's own sources. Shared by both build types; each
// type gets its own task and output directory below.
val rustLicensesSources = files(
    repoRootDir.resolve("Cargo.toml"),
    repoRootDir.resolve("Cargo.lock"),
    fileTree(repoRootDir.resolve("crates")) { include("*/Cargo.toml") },
    fileTree(repoRootDir.resolve("crates/xtask/src")),
)

fun rustLicensesTaskName(buildType: String): String =
    "rustLicenses" + buildType.replaceFirstChar { it.uppercase() }

for (buildType in listOf("debug", "release", "benchmark")) {
    val profileName = coreProfile(buildType)
    val out = RepoPaths.androidCore(project, profileName)
    tasks.register<AndroidCoreBuild>(coreTaskName(buildType)) {
        group = "roboco"
        description =
            "Builds the Rust mobile core (profile $profileName) for " +
                requestedAbis.joinToString() + " and generates the UniFFI Kotlin " +
                "bindings (cargo xtask android-core)."
        repoRoot.set(repoRootDir)
        abis.set(requestedAbis)
        profile.set(profileName)
        languages.set(coreLanguages)
        ndkHome.set(androidComponents.sdkComponents.ndkDirectory.map { it.asFile.absolutePath })
        skipIfOutputsExist.set(skipCore)
        buildHost.set(buildType == "debug" && debugHostCore)
        tailcat.set(tailcatEnabled)
        sources.from(coreSources)
        kotlinDir.set(out.resolve("kotlin"))
        jniLibsDir.set(out.resolve("jniLibs"))
        hostDir.set(out.resolve("host"))
    }
    // ui-parity#101: the same build type's Rust licence data, run after the
    // core build (cargo metadata needs the registry sources unpacked).
    tasks.register<RustLicensesTask>(rustLicensesTaskName(buildType)) {
        group = "roboco"
        description =
            "Writes assets/licenses/rust-crates.json (cargo xtask android-licenses, " +
                "languages ${coreLanguages.get()})."
        repoRoot.set(repoRootDir)
        languages.set(coreLanguages)
        sources.from(rustLicensesSources)
        assetsDir.set(layout.buildDirectory.dir("generated/licenses/$buildType"))
        dependsOn(coreTaskName(buildType))
    }
}

// The debug core task, for JVM tests (they load the host library through JNA).
val debugHostDir: File = RepoPaths.androidCore(project, coreProfile("debug")).resolve("host")

// ---------------------------------------------------------------------------
// Release signing (android-app#31): CI env vars first, then a local
// properties file. With neither, the release build stays **unsigned** —
// never debug-signed (upstream #609's review bug 10). Read through
// `providers` so the configuration cache tracks the inputs; nothing is
// consulted unless a source is present, and only the release signingConfig
// ever sees these values.
//
// Env: ROBOCO_ANDROID_KEYSTORE (path), ROBOCO_ANDROID_KEYSTORE_PASSWORD,
//      ROBOCO_ANDROID_KEY_ALIAS, ROBOCO_ANDROID_KEY_PASSWORD
// Local: -ProbocoKeystoreProperties=<path> with storeFile/storePassword/
//        keyAlias/keyPassword (path resolution: apps/android/app-relative).
// ---------------------------------------------------------------------------
/** One resolved signing source; `null` means build unsigned. */
data class ReleaseKeystore(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

fun requiredKeystoreValue(name: String, source: String, value: String?): String =
    requireNotNull(value?.takeIf { it.isNotBlank() }) {
        "$name must be set ($source)"
    }

fun keystorePropertiesFile(path: String): ReleaseKeystore {
    val props = Properties().apply { file(path).inputStream().use { load(it) } }
    fun required(key: String): String =
        requiredKeystoreValue(key, "-ProbocoKeystoreProperties ($path)", props.getProperty(key))
    return ReleaseKeystore(
        storeFile = file(required("storeFile")),
        storePassword = required("storePassword"),
        keyAlias = required("keyAlias"),
        keyPassword = required("keyPassword"),
    )
}

val releaseKeystore: ReleaseKeystore? =
    providers.environmentVariable("ROBOCO_ANDROID_KEYSTORE").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { path ->
            ReleaseKeystore(
                storeFile = file(path),
                storePassword = requiredKeystoreValue(
                    "ROBOCO_ANDROID_KEYSTORE_PASSWORD",
                    "ROBOCO_ANDROID_KEYSTORE env vars",
                    providers.environmentVariable("ROBOCO_ANDROID_KEYSTORE_PASSWORD").orNull,
                ),
                keyAlias = requiredKeystoreValue(
                    "ROBOCO_ANDROID_KEY_ALIAS",
                    "ROBOCO_ANDROID_KEYSTORE env vars",
                    providers.environmentVariable("ROBOCO_ANDROID_KEY_ALIAS").orNull,
                ),
                keyPassword = requiredKeystoreValue(
                    "ROBOCO_ANDROID_KEY_PASSWORD",
                    "ROBOCO_ANDROID_KEYSTORE env vars",
                    providers.environmentVariable("ROBOCO_ANDROID_KEY_PASSWORD").orNull,
                ),
            )
        }
        ?: providers.gradleProperty("robocoKeystoreProperties").orNull
            ?.let(::keystorePropertiesFile)

// ---------------------------------------------------------------------------
// ABI-split release APKs (android-app#31): one APK per ABI, no universal APK
// (it would double the download for every phone). Intersected with
// -ProbocoAbis, so a local -ProbocoAbis=arm64-v8a build produces one split.
// Splits apply to every variant: debug is also split, and Gradle's install
// task picks the APK matching the connected device's ABI.
// ---------------------------------------------------------------------------
val splitAbis: List<String> = requestedAbis.filter { it in setOf("arm64-v8a", "x86_64") }

// ---------------------------------------------------------------------------
// The asset pipeline (android-app#13): roboco-android-assets converts the
// shared line icons to VectorDrawables and rasterizes tool/file icons.
// ---------------------------------------------------------------------------
val iconsResDir = layout.buildDirectory.dir("generated/roboco-icons/res")
val iconsAssetsDir = layout.buildDirectory.dir("generated/roboco-icons/assets")

val generateAssets = tasks.register<AndroidAssetsGen>("generateRobocoAssets") {
    group = "roboco"
    description =
        "Converts the shared SVG icons to VectorDrawables and rasterized PNGs " +
            "(roboco-android-assets)."
    repoRoot.set(repoRootDir)
    sources.from(
        repoRootDir.resolve("Cargo.toml"),
        repoRootDir.resolve("Cargo.lock"),
        fileTree(repoRootDir.resolve("crates/android-assets")),
        fileTree(repoRootDir.resolve("crates/ui/assets/icons")),
        fileTree(repoRootDir.resolve("crates/ui/assets/file-icons")),
        fileTree(repoRootDir.resolve("apps/android/icons")),
    )
    resDir.set(iconsResDir)
    assetsDir.set(iconsAssetsDir)
}

// The exact font bytes the Rust layout engine measures (the 12 faces the
// iOS app registers with CoreText), plus the licenses the OFL requires.
val fontsDir = layout.buildDirectory.dir("generated/roboco-fonts")

val syncFonts = tasks.register<roboco.gradle.FontsSync>("syncRobocoFonts") {
    group = "roboco"
    description = "Copies the 12 bundled Geist faces and their licenses into the APK assets."
    fontFiles.setFrom(
        RobocoFonts.FILES.map { repoRootDir.resolve("crates/ui/assets/fonts").resolve(it) },
    )
    licenseFiles.setFrom(
        listOf(
            repoRootDir.resolve("crates/ui/assets/fonts/licenses/Geist-OFL.txt"),
            repoRootDir.resolve("crates/ui/assets/file-icons/LICENSE.symbols"),
        ),
    )
    assetsDir.set(fontsDir)
}

// The repo's distribution licenses (android-app#31): a sideloaded APK is the
// whole distribution, so LICENSE and THIRD_PARTY_NOTICES.md ship inside it
// under assets/licenses/, beside the font licenses above. The same task
// stages the committed SPDX fallback texts (ui-parity#101) — licences the
// AboutLibraries plugin cannot fill offline — under assets/licenses/spdx/.
val licensesDir = layout.buildDirectory.dir("generated/roboco-licenses")

val syncLicenses = tasks.register<LicensesSync>("syncRobocoLicenses") {
    group = "roboco"
    description = "Copies the repository license, notices and SPDX texts into the APK assets."
    noticeFiles.setFrom(
        repoRootDir.resolve("LICENSE"),
        repoRootDir.resolve("THIRD_PARTY_NOTICES.md"),
    )
    spdxFiles.setFrom(
        fileTree(repoRootDir.resolve("apps/android/licenses/spdx")) { include("*.txt") },
    )
    // android-next#02: the Go Tailcat transport's licences.
    goBundleDir.set(repoRootDir.resolve("adapters/roboco-tailcat/licenses/bundle"))
    goLicenseFiles.setFrom(repoRootDir.resolve("adapters/roboco-tailcat/LICENSE.kratos"))
    assetsDir.set(licensesDir)
}

// ---------------------------------------------------------------------------
// The desktop's session chimes (android-app#28): byte-identical copies into
// res/raw — the channels reference them as their sound. The "middle set"
// name is the ticket's: `syncChimes`.
// ---------------------------------------------------------------------------
val chimesDir = layout.buildDirectory.dir("generated/roboco-chimes/res")

val syncChimes = tasks.register<roboco.gradle.ChimesSync>("syncChimes") {
    group = "roboco"
    description =
        "Copies the desktop's session chimes into res/raw (chime_done/request/attention.wav)."
    chimeFiles.setFrom(
        repoRootDir.resolve("crates/ui/assets/sounds/done.wav"),
        repoRootDir.resolve("crates/ui/assets/sounds/request.wav"),
        repoRootDir.resolve("crates/ui/assets/sounds/attention.wav"),
    )
    outputDir.set(chimesDir)
}

androidComponents {
    // Tests that reference src/debug classes (the design gallery suite of
    // android-ui-parity #22, 05's HapticsGalleryScreenshotTest, 11's gallery
    // test) do not compile for testReleaseUnitTest: the release variant has
    // no debug source set. Disable its unit tests in one place instead of
    // moving every such test to src/testDebug. ui-parity #136: the benchmark
    // variant carries the same debug sources, so it gets the same disable
    // (release/benchmark unit-test variants stay disabled).
    for (disableType in listOf("release", "benchmark")) {
        beforeVariants(selector().withBuildType(disableType)) { v ->
            (v as? com.android.build.api.variant.HasHostTestsBuilder)
                ?.hostTests?.get(com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE)?.enable = false
        }
    }
    // ui-parity #136: the benchmark variant (release config, debug-signed,
    // profileable, non-debuggable) runs the debug-only lab sources — the
    // Macrobenchmark builds need release-speed Compose, and the lab lives in
    // src/debug. The AGP 9 variant API feeds the debug source set in as
    // static sources (there is no src/debug/res; the assets carry the lab's
    // fixture image, the manifest its two debug activities).
    onVariants(selector().withBuildType("benchmark")) { v ->
        v.sources.kotlin?.addStaticSourceDirectory("src/debug/java")
        v.sources.assets?.addStaticSourceDirectory("src/debug/assets")
        v.sources.manifests.addStaticManifestFile("src/debug/AndroidManifest.xml")
    }
    onVariants { variant ->
        val buildType = variant.buildType ?: return@onVariants
        val coreTask = tasks.named<AndroidCoreBuild>(coreTaskName(buildType))
        val rustLicensesTask = tasks.named<RustLicensesTask>(rustLicensesTaskName(buildType))
        // AGP 9 variant API (no hard-coded sourceSets dirs): the generated
        // Kotlin and jniLibs are outputs of the core task.
        variant.sources.kotlin?.addGeneratedSourceDirectory(coreTask) { it.kotlinDir }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(coreTask) { it.jniLibsDir }

        variant.sources.res?.addGeneratedSourceDirectory(generateAssets) { it.resDir }
        variant.sources.res?.addGeneratedSourceDirectory(syncChimes) { it.outputDir }
        variant.sources.assets?.addGeneratedSourceDirectory(generateAssets) { it.assetsDir }
        variant.sources.assets?.addGeneratedSourceDirectory(syncFonts) { it.assetsDir }
        variant.sources.assets?.addGeneratedSourceDirectory(syncLicenses) { it.assetsDir }
        variant.sources.assets?.addGeneratedSourceDirectory(rustLicensesTask) { it.assetsDir }
    }
}

// ---------------------------------------------------------------------------
// AboutLibraries (ui-parity#101): emit the JSON only. offlineMode keeps the
// build reproducible (no network in CI; the SPDX texts the plugin would
// otherwise pull from spdx.org are committed under apps/android/licenses/
// and staged as assets/licenses/spdx/); fetchRemote* stays off too. The
// plugin registers the per-variant export tasks that generate
// res/raw/aboutlibraries.json from each variant's runtime classpath
// (includeTestVariants defaults to false, so Robolectric/Roborazzi/JUnit
// never appear; debug-only debugImplementation tooling may, which is
// acceptable). No aboutlibraries-* library artifacts are used.
// ---------------------------------------------------------------------------
aboutLibraries {
    offlineMode = true
    collect {
        fetchRemoteLicense = false
        fetchRemoteFunding = false
        // Licence overrides (aboutlibraries/licenses/<id>.json, the plugin's
        // documented config layout; relative to this module): camera-core's
        // POM declares a second licence for its bundled libyuv ("BSD License"
        // -> README.chromium) that the plugin cannot map offline, so it got a
        // hash id with no SPDX id and no text (AndroidLicenceTextsTest). The
        // override maps it to BSD-3-Clause with the committed spdx text.
        configPath = file("aboutlibraries")
    }
    export {
        excludeFields.addAll("developers", "funding")
        prettyPrint = false
    }
    library {
        duplicationMode = com.mikepenz.aboutlibraries.plugin.DuplicateMode.MERGE
    }
}

// ---------------------------------------------------------------------------
android {
    namespace = "sh.roboco.android"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "sh.roboco.android"
        minSdk = 31
        targetSdk = 36 // compile 37; target stays 36 for local-network permission
        versionCode = appVersionCode
        versionName = appVersionName
        // android-app#30: AndroidJUnitRunner + the in-process device filters
        // (@TabletOnly / @PhysicalDeviceOnly / @Tour / @Ignore) — see
        // RobocoTestRunner for why not a comma-separated `notAnnotation`.
        testInstrumentationRunner = "sh.roboco.android.RobocoTestRunner"
        // android-app#30: the flow suite's isolation — each instrumented
        // test runs in its own process with wiped app data, so every test
        // starts unpaired and clean (the rig re-pairs via the `pair`
        // extra). Requires the ANDROIDX_TEST_ORCHESTRATOR execution below
        // and the `androidTestUtil` orchestrator APK.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        // No ndk.abiFilters here: AGP refuses abiFilters alongside the ABI
        // splits below, and the splits' include (intersected with
        // -ProbocoAbis) now decides which libroboco_mobile.so is packaged
        // per APK. The core build tasks take the ABIs directly.
    }

    signingConfigs {
        // Filled only when a signing source resolved above. PKCS12 needs no
        // storeType: the JDK's default KeyStore type handles it. Declared
        // before buildTypes, which look the config up by name.
        releaseKeystore?.let { keystore ->
            create("release") {
                storeFile = keystore.storeFile
                storePassword = keystore.storePassword
                keyAlias = keystore.keyAlias
                keyPassword = keystore.keyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 + resource shrinking (android-app#31). The keep rules for the
            // reflection- and native-reached JNA/UniFFI layers live in
            // proguard-rules.pro; the R8 mapping lands in
            // build/outputs/mapping/release/ and ships as a release asset.
            // Minification is unconditional: the unsigned and signed paths
            // must produce the same R8 output.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Only when a signing source is present; otherwise the APK is
            // built unsigned (CI's workflow_dispatch path) — never the
            // debug key.
            releaseKeystore?.let { signingConfig = signingConfigs.getByName("release") }
            // android-next#04: only release builds install updates — their
            // signer is the one the release APK must match. Debug and
            // benchmark builds still check, but their Update action opens
            // the releases page and the launch prompt stays off.
            buildConfigField("boolean", "UPDATE_INSTALLER_ENABLED", "true")
        }
        debug {
            buildConfigField("boolean", "UPDATE_INSTALLER_ENABLED", "false")
        }
        // ui-parity #136: the Macrobenchmark build — release performance
        // (initWith keeps R8 and the mobile-dist core), debug-signed so it
        // installs locally, profileable and non-debuggable. The debug lab
        // sources are fed in through the variant API above, and the
        // `:benchmark` module targets this variant (matchingFallbacks for
        // every other consumer).
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            isProfileable = true
            // android-next#04: debug-signed, like `debug` above — an update
            // would refuse on the signer gate.
            buildConfigField("boolean", "UPDATE_INSTALLER_ENABLED", "false")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*splitAbis.toTypedArray())
            // A universal APK would double the download for every phone.
            // versionCode stays identical across splits: sideload-only, a
            // device installs exactly one.
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME backs the core handshake in CoreLoader.
        buildConfig = true
    }

    testOptions {
        // android-app#30: every instrumented test in its own process with
        // `clearPackageData` (above), so the flow suite's pairing state
        // never bleeds between tests.
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        unitTests {
            // Robolectric needs the app resources/assets (fonts, icons).
            isIncludeAndroidResources = true
        }
    }

    packaging {
        jniLibs {
            // Sideloaded APKs are downloaded from GitHub without store-side
            // compression; extracting the .so at install keeps the APK small.
            // 16 KB compatibility comes from ticket 10's ELF alignment.
            // android-next#02 also relies on it: the core dlopens
            // libroboco_tailcat.so by absolute path under nativeLibraryDir,
            // which holds the file only when libraries are extracted.
            useLegacyPackaging = true
        }
    }
}

// ---------------------------------------------------------------------------
// JVM tests: JDK 21 launcher (Robolectric needs it for SDK 36+), the host
// core on jna.library.path, and the generated dirs for the plain tests.
// ---------------------------------------------------------------------------
tasks.withType<Test>().configureEach {
    dependsOn(
        tasks.named(coreTaskName("debug")),
        generateAssets,
        syncFonts,
        syncLicenses,
        syncChimes,
        tasks.named(rustLicensesTaskName("debug")),
    )
    javaLauncher.set(
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) },
    )
    // A custom launcher means no IDE/AGP-injected module flags; Robolectric's
    // interceptors (SharedSecrets et al.) need the opens/exports on JDK 21.
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
        "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED",
        // UniFFI objects register with android.system.SystemCleaner, which
        // Robolectric backs with jdk.internal.ref.CleanerFactory.
        "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED",
    )
    maxHeapSize = "2g"
    // A failure prints its message, causes and stack in the job log. The
    // default short form is just "IllegalStateException at X.kt:32". Record
    // run 38595804909 failed 12 captures that way: no message, and on the
    // record path nothing else was uploaded.
    // STARTED too (round 51): a hung test then shows as the last STARTED
    // line in the job log. Run 88b067d0's testDebugUnitTest hung for
    // 73 min with no output at all, so nothing named the hanging test.
    testLogging {
        events(
            org.gradle.api.tasks.testing.logging.TestLogEvent.STARTED,
            org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED,
        )
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
    // Safety net (round 51): a hang fails the task after 45 min instead of
    // eating the whole 90-min CI job. Imported: inside this block `java`
    // resolves to the project's java extension, not the package.
    timeout.set(Duration.ofMinutes(45))
    // android-app#30's parity gate (G2–G4) is armed against goldens that
    // are not recorded yet, so it fails until the first sign-off. It runs
    // only when asked (-ProbocoParity: the android-screenshots CI job), not
    // in the default unit run that gates every PR.
    if (!providers.gradleProperty("robocoParity").isPresent) {
        filter { excludeTestsMatching("sh.roboco.android.parity.*") }
    }
    systemProperty("jna.library.path", debugHostDir.absolutePath)
    systemProperty("robolectric.graphicsMode", "NATIVE")
    systemProperty("robolectric.pixelCopyRenderMode", "hardware")
    systemProperty("roboco.repoRoot", repoRootDir.absolutePath)
    systemProperty("roboco.generatedAssets", iconsAssetsDir.get().asFile.absolutePath)
    systemProperty("roboco.generatedRes", iconsResDir.get().asFile.absolutePath)
    systemProperty("roboco.generatedFonts", fontsDir.get().asFile.absolutePath)
    systemProperty("roboco.generatedLicenses", licensesDir.get().asFile.absolutePath)
    systemProperty("roboco.generatedChimes", chimesDir.get().asFile.absolutePath)
}

roborazzi {
    // Reference images are committed goldens (ticket 14 extends this suite,
    // ticket 30 owns the emulator-based part).
    outputDir.set(file("src/test/screenshots"))
}

// ---------------------------------------------------------------------------
// The iOS parity gate (android-app#30): the G5 report. G1 is Roborazzi's
// `verifyRoborazziDebug`; G2–G4 are the `sh.roboco.android.parity` JVM
// tests; this task writes the side-by-side board the reviewer walks
// (`apps/android/parity/CHECKLIST.md`), uploaded by the
// `android-screenshots` CI job on every run.
// ---------------------------------------------------------------------------
val parityDir: File = projectDir.parentFile.resolve("parity")

val parityReport = tasks.register<roboco.gradle.ParityReport>("parityReport") {
    group = "roboco"
    description =
        "Writes the iOS parity side-by-side board ([Android | iOS | abs-diff] per " +
            "golden) plus index.html into build/parity/."
    manifest.set(file(parityDir.resolve("manifest.json")))
    goldens.from(fileTree("src/test/screenshots") { include("**/*.png") })
    goldensRoot.set(file("src/test/screenshots"))
    iosRefs.from(fileTree(parityDir.resolve("ios")) { include("**/*.png") })
    iosRoot.set(parityDir.resolve("ios"))
    outputDir.set(layout.buildDirectory.dir("parity"))
}

// G2's tooling (android-app#30): stub manifest entries for unlisted goldens,
// and the sign-off mode (--sign=pending|all|<paths> --reviewer=… --date=…)
// that pins reviewedSha256 to the recorded PNGs. See apps/android/parity/README.md.
tasks.register<roboco.gradle.ParityManifest>("parityManifest") {
    group = "roboco"
    description =
        "Adds stub parity-manifest entries for unlisted goldens; with --sign, pins " +
            "reviewedSha256/reviewer/date for the reviewed entries."
    manifest.set(file(parityDir.resolve("manifest.json")))
    goldensDir.set(file("src/test/screenshots"))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    // iOS continuous (squircle) corners for SmoothRoundedShape (ui-parity#01).
    implementation(libs.graphics.shapes)
    // Liquid-glass backdrop blur (ui-parity#07): pulls in the core `haze`
    // and `haze-utils` artifacts transitively. Deliberately NOT
    // haze-blur-materials (HazeMaterials are not the iOS look). Its POM
    // needs Compose ui ≥ 1.12.1 and Kotlin stdlib ≥ 2.4.20 — both already
    // pinned (BOM 2026.09.00 / Kotlin 2.4.21).
    implementation(libs.haze.blur)
    // Custom Tabs (ui-parity#89): PR chip "Open Pull Request" (a PR link
    // and a transcript link must look identical — ticket 126's recipe).
    // Not a Material artifact; version shared with 126.
    implementation(libs.androidx.browser)

    // Pinned-folder drag-to-reorder (ui-parity#47): Compose-foundation
    // only (runtime, animation, foundation — no material3), Apache-2.0.
    // The library is picked up by the AboutLibraries JSON (101) from its
    // POM licence automatically.
    implementation(libs.reorderable)

    // The QR scanner (ui-parity#138): CameraX (core + camera2 backend +
    // lifecycle + view — `LifecycleCameraController`/`PreviewView`) and
    // ZXing's plain core. Both Apache-2.0, ABI-free and Play-services-free
    // (android-app#18's decision: ML Kit was rejected), so they land in the
    // AboutLibraries JSON (101) automatically. Never ZXing's embedded
    // Android artifact (it brings its own Activity and UI) and never
    // camera-compose (a Material-free AndroidView hosts the PreviewView
    // directly).
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    implementation(libs.kotlinx.coroutines.android)
    // Engine-credential storage (ticket 71): no-backup Preferences DataStore.
    // androidx.security:security-crypto is deliberately absent (deprecated
    // and forbidden by android-app#18).
    implementation(libs.androidx.datastore.preferences)
    // The AAR carries arm64/x86_64 libjnidispatch.so (16 KB aligned).
    implementation("${libs.jna.get()}@aar")

    debugImplementation(libs.compose.ui.tooling)
    // JankStats, the lab bench's cross-check (ui-parity #136): debug and
    // benchmark only — the lab code that imports it lives in src/debug,
    // which the benchmark variant also compiles (the variant API above).
    debugImplementation(libs.androidx.metrics.performance)

    // ui-parity #136: the benchmark variant compiles the same debug
    // sources, so it mirrors every debugImplementation line. `add` with the
    // configuration name: the Kotlin DSL has no typed accessor for a build
    // type created in this same script.
    add("benchmarkImplementation", libs.compose.ui.tooling)
    add("benchmarkImplementation", libs.compose.ui.test.manifest)
    add("benchmarkImplementation", libs.androidx.metrics.performance)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // ApplicationProvider for the Robolectric pairing/core tests.
    testImplementation(libs.androidx.test.core)
    // Plain jar: the host libjnidispatch for JVM (Robolectric) tests.
    testImplementation(libs.jna)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    debugImplementation(libs.compose.ui.test.manifest)

    // Instrumented Keystore tests (ticket 71) — CI-only, no emulator locally.
    // androidx-test-runner/ext-junit (and plain JUnit for the accuracy
    // tests' asserts, ui-parity #115) are on the androidTest classpath.
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
    // Instrumented compose tests (ui-parity #97's WallpaperSettingsFlowTest,
    // CI-only): the compose test rule and its matchers need the BOM and
    // ui-test-junit4 on the androidTest classpath as well.
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    // androidTestUtil: the Orchestrator APK itself (android-app#30) —
    // installed on the device beside the test APK, not on any classpath.
    androidTestUtil(libs.androidx.test.orchestrator)
    // android-app#28's NotificationFlowTest: the system permission dialog,
    // pressHome and the notification shade (UiDevice), CI-only. Also
    // ui-parity #133's KeyboardFlowTest (raw swipes over the IME).
    androidTestImplementation(libs.androidx.test.uiautomator)
    // ui-parity #137's MarkdownImageLabTest: Intents.init/intended pins
    // the viewer's share chooser (ACTION_CHOOSER), CI-only.
    androidTestImplementation(libs.androidx.test.espresso.intents)
}
