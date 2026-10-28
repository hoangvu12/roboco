package sh.roboco.android.flows

/**
 * Marks the tests written against the phone shell (a single-pane stack, the
 * one-row bottom bar, the New session accessory, Back popping a session
 * off the list): excluded at a 600 dp smallest width and above by
 * [sh.roboco.android.RobocoTestRunner]'s device filters, so the tablet job
 * (the split shell, sidebar beside the session) does not run them —
 * ci-speed ticket 23. [reason] says what is phone-shaped; a tag whose
 * reason names a ticket is a suspected tablet bug parked there, not a
 * phone-only flow.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class PhoneOnly(val reason: String)
