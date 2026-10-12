import roboco.gradle.AndroidAssetsGen
import roboco.gradle.AndroidCoreBuild
import roboco.gradle.RepoPaths
import roboco.gradle.RobocoFonts
import roboco.gradle.RobocoVersion
import java.io.File

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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += requestedAbis
        }
    }

    buildTypes {
        release {
            // No debug signing and no R8 here: ticket 31 (release packaging).
            isMinifyEnabled = false
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
    dependsOn(tasks.named(coreTaskName("debug")), generateAssets, syncFonts)
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
    systemProperty("jna.library.path", debugHostDir.absolutePath)
    systemProperty("robolectric.graphicsMode", "NATIVE")
    systemProperty("robolectric.pixelCopyRenderMode", "hardware")
    systemProperty("roboco.repoRoot", repoRootDir.absolutePath)
    systemProperty("roboco.generatedAssets", iconsAssetsDir.get().asFile.absolutePath)
    systemProperty("roboco.generatedRes", iconsResDir.get().asFile.absolutePath)
    systemProperty("roboco.generatedFonts", fontsDir.get().asFile.absolutePath)
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
    // iOS continuous (squircle) corners for SmoothRoundedShape (ui-parity#01).
    implementation(libs.graphics.shapes)

    implementation(libs.kotlinx.coroutines.android)
    // Engine-credential storage (ticket 71): no-backup Preferences DataStore.
    // androidx.security:security-crypto is deliberately absent (deprecated
    // and forbidden by android-app#18).
    implementation(libs.androidx.datastore.preferences)
    // The AAR carries arm64/x86_64 libjnidispatch.so (16 KB aligned).
    implementation("${libs.jna.get()}@aar")

    debugImplementation(libs.compose.ui.tooling)

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
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
