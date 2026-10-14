package sh.roboco.android.flows

/**
 * Marks the tests that only mean something on a physical device (frame
 * timing benches): excluded from every emulator run through the runner's
 * `notAnnotation` filter (`apps/android/scripts/flow-tests.sh`). The
 * `assumeTrue(!isEmulator())` guard stays for local runs, but this runner
 * setup records an assumption failure as a failure, not a skip.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class PhysicalDeviceOnly
