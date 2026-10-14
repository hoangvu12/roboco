package sh.roboco.android.flows

/**
 * Marks the tests that need the tablet split shell (smallest width ≥ 600):
 * excluded from the phone emulator run through the runner's
 * `notAnnotation` filter (`apps/android/scripts/flow-tests.sh`) and run by
 * the tablet job. The tests keep their `assumeTrue` guard for local runs,
 * but the filter is what keeps them out of the phone report: this runner
 * setup records an assumption failure as a failure, not a skip.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class TabletOnly
