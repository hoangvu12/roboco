package sh.roboco.android.flows

/**
 * Marks the transition-tour tests (android-app#30, the port of
 * `uit:TransitionTourTests.swift`): slow, animation-on walkthroughs
 * recorded with `screenrecord` for motion review. Excluded by
 * [sh.roboco.android.RobocoTestRunner] unless the run passes
 * `-Pandroid.testInstrumentationRunnerArguments.flowIncludeTour=true` —
 * the tablet job does (`FLOW_INCLUDE_TOUR=1`), nightly and on
 * `workflow_dispatch`.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class Tour
