package sh.roboco.android.flows

/**
 * Marks the transition-tour tests (android-app#30, the port of
 * `uit:TransitionTourTests.swift`): slow, animation-on walkthroughs
 * recorded with `screenrecord` for motion review. Excluded from the PR
 * emulator run (the rig passes
 * `-Pandroid.testInstrumentationRunnerArguments.notAnnotation=sh.roboco.android.flows.Tour`)
 * and run by the tablet job on pushes to main and `workflow_dispatch`.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class Tour
