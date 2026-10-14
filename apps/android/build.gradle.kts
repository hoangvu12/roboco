// :app plus the device-only :benchmark (com.android.test, ui-parity #136);
// buildSrc carries the Rust-pipeline tasks and the version derivation. See
// apps/android/README.md.
plugins {
    alias(libs.plugins.android.application) apply false
    // Declared here too: the AGP jar (which also ships com.android.test) is on
    // the root classpath via android.application, so a versioned request from
    // :benchmark alone fails - "already on the classpath with an unknown
    // version" - and with it the configuration of every invocation.
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.roborazzi) apply false
}
