plugins {
    `kotlin-dsl`
}

// buildSrc is its own Gradle build: its repositories are independent of the
// main build's `dependencyResolutionManagement`.
repositories {
    mavenCentral()
}

dependencies {
    // Pure-JVM tests for the version derivation (the only logic in here
    // that is not Gradle task plumbing).
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    // Keep the buildSrc test JVM small: it never touches Android.
    maxHeapSize = "512m"
}
