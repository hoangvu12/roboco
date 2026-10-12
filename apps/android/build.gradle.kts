// One module today (:app); buildSrc carries the Rust-pipeline tasks and the
// version derivation. See apps/android/README.md.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.roborazzi) apply false
}
