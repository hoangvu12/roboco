import roboco.gradle.AndroidAssetsGen
import roboco.gradle.AndroidCoreBuild
import roboco.gradle.LicensesSync
import roboco.gradle.RepoPaths
import roboco.gradle.RobocoFonts
import roboco.gradle.RobocoVersion
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 carries built-in Kotlin: org.jetbrains.kotlin.android is NOT applied.
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.roborazzi)
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
val coreLanguages = providers.gradleProperty("robocoCoreLanguages").orElse("mobile")

val coreSources = files(
    repoRootDir.resolve("Cargo.toml"),
    repoRootDir.resolve("Cargo.lock"),
    repoRootDir.resolve(".cargo/config.toml"),
    fileTree(repoRootDir.resolve("crates/mobile")),
    fileTree(repoRootDir.resolve("crates/mobile-client")),
    fileTree(repoRootDir.resolve("crates/text")),
    fileTree(repoRootDir.resolve("crates/markdown")),
    fileTree(repoRootDir.resolve("crates/doc")),
    fileTree(repoRootDir.resolve("crates/proto")),
    fileTree(repoRootDir.resolve("crates/syntax")),
    fileTree(repoRootDir.resolve("crates/xtask")),
)

fun coreTaskName(buildType: String): String =
    "build" + buildType.replaceFirstChar { it.uppercase() } + "Core"

fun coreProfile(buildType: String): String =
    providers.gradleProperty("robocoCoreProfile")
        .orElse(if (buildType == "release") "mobile-dist" else "mobile")
        .get()

for (buildType in listOf("debug", "release")) {
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
        sources.from(coreSources)
        kotlinDir.set(out.resolve("kotlin"))
        jniLibsDir.set(out.resolve("jniLibs"))
        hostDir.set(out.resolve("host"))
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

fun requiredKeystoreValue(name: String, value: String?): String =
    requireNotNull(value?.takeIf { it.isNotBlank() }) {
        "$name must be set alongside ROBOCO_ANDROID_KEYSTORE"
    }

fun keystorePropertiesFile(path: String): ReleaseKeystore {
    val props = Properties().apply { file(path).inputStream().use { load(it) } }
    fun required(key: String): String =
        requireNotNull(props.getProperty(key)?.takeIf { it.isNotBlank() }) {
            "-ProbocoKeystoreProperties ($path) is missing $key"
        }
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
                    providers.environmentVariable("ROBOCO_ANDROID_KEYSTORE_PASSWORD").orNull,
                ),
                keyAlias = requiredKeystoreValue(
                    "ROBOCO_ANDROID_KEY_ALIAS",
                    providers.environmentVariable("ROBOCO_ANDROID_KEY_ALIAS").orNull,
                ),
                keyPassword = requiredKeystoreValue(
                    "ROBOCO_ANDROID_KEY_PASSWORD",
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
// under assets/licenses/, beside the font licenses above.
val licensesDir = layout.buildDirectory.dir("generated/roboco-licenses")

val syncLicenses = tasks.register<LicensesSync>("syncRobocoLicenses") {
    group = "roboco"
    description = "Copies the repository LICENSE and THIRD_PARTY_NOTICES.md into the APK assets."
    noticeFiles.setFrom(
        repoRootDir.resolve("LICENSE"),
        repoRootDir.resolve("THIRD_PARTY_NOTICES.md"),
    )
    assetsDir.set(licensesDir)
}

androidComponents {
    onVariants { variant ->
        val buildType = variant.buildType ?: return@onVariants
        val coreTask = tasks.named<AndroidCoreBuild>(coreTaskName(buildType))
        // AGP 9 variant API (no hard-coded sourceSets dirs): the generated
        // Kotlin and jniLibs are outputs of the core task.
        variant.sources.kotlin?.addGeneratedSourceDirectory(coreTask) { it.kotlinDir }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(coreTask) { it.jniLibsDir }

        variant.sources.res?.addGeneratedSourceDirectory(generateAssets) { it.resDir }
        variant.sources.assets?.addGeneratedSourceDirectory(generateAssets) { it.assetsDir }
        variant.sources.assets?.addGeneratedSourceDirectory(syncFonts) { it.assetsDir }
        variant.sources.assets?.addGeneratedSourceDirectory(syncLicenses) { it.assetsDir }
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
            useLegacyPackaging = true
        }
    }
}

// ---------------------------------------------------------------------------
// JVM tests: JDK 21 launcher (Robolectric needs it for SDK 36+), the host
// core on jna.library.path, and the generated dirs for the plain tests.
// ---------------------------------------------------------------------------
tasks.withType<Test>().configureEach {
    dependsOn(tasks.named(coreTaskName("debug")), generateAssets, syncFonts, syncLicenses)
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
    )
    maxHeapSize = "2g"
    systemProperty("jna.library.path", debugHostDir.absolutePath)
    systemProperty("robolectric.graphicsMode", "NATIVE")
    systemProperty("robolectric.pixelCopyRenderMode", "hardware")
    systemProperty("roboco.repoRoot", repoRootDir.absolutePath)
    systemProperty("roboco.generatedAssets", iconsAssetsDir.get().asFile.absolutePath)
    systemProperty("roboco.generatedRes", iconsResDir.get().asFile.absolutePath)
    systemProperty("roboco.generatedFonts", fontsDir.get().asFile.absolutePath)
    systemProperty("roboco.generatedLicenses", licensesDir.get().asFile.absolutePath)
}

roborazzi {
    // Reference images are committed goldens (ticket 14 extends this suite,
    // ticket 30 owns the emulator-based part).
    outputDir.set(file("src/test/screenshots"))
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

    implementation(libs.kotlinx.coroutines.android)
    // The AAR carries arm64/x86_64 libjnidispatch.so (16 KB aligned).
    implementation("${libs.jna.get()}@aar")

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    // Plain jar: the host libjnidispatch for JVM (Robolectric) tests.
    testImplementation(libs.jna)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    debugImplementation(libs.compose.ui.test.manifest)
}
