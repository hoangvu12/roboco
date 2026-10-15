plugins {
    // Built-in Kotlin, like :app under AGP 9 — no kotlin-android plugin.
    alias(libs.plugins.android.test)
}

// ---------------------------------------------------------------------------
// The device-only macrobenchmark module (android-ui-parity #136): the
// ports of upstream's ScrollPerformanceTests
// (apps/ios/ZeronUITests/ScrollPerformanceTests.swift) — Macrobenchmark
// against :app's `benchmark` build type (release config, debug-signed,
// profileable, non-debuggable, carrying the debug lab sources). Nothing
// here ships in an app APK; `com.android.test` builds a self-instrumenting
// test APK installed beside the target.
// Run: ./gradlew :benchmark:connectedBenchmarkAndroidTest (a physical
// device — the hitch assertions assumeFixed on emulators).
// ---------------------------------------------------------------------------

android {
    namespace = "sh.roboco.android.benchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // Matches :app's benchmark build type; debuggable so the
        // macrobenchmark rules accept this test APK, fallback to release
        // for every other consumer.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    // The measured app: :app, resolved to its benchmark variant.
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

androidComponents {
    // Only the benchmark variant is built — the debug/release variants of
    // this module have no purpose.
    beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
