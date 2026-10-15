package sh.roboco.android.session

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFirstOrNull
import sh.roboco.android.transcript.TranscriptTapGate

/**
 * On the transcript host (android-ui-parity #81): observe single taps on
 * the **Initial** pass — ancestors first, before any row, widget, link
 * handler, the horizontal scrollers or the scrollable — and call
 * [onDismiss], **never consuming anything**.
 *
 * The port of iOS's dismiss tap recognizer
 * (`ios:Session/SessionViewController.swift:71-75, 346-365`):
 * `cancelsTouchesInView = false`, so a tap on a painted link opens the
 * link **and** dismisses (upstream #585 — tap recognizers are exclusive
 * by default and every link tap was dropped until `7a4343b0` let them
 * recognize simultaneously), a tap on an image tile opens the viewer and
 * dismisses, and a tap on a control belongs to the control: the
 * `transcriptControl()` markers claim the gesture through [gate] and the
 * observer stays silent.
 *
 * The recognized gesture is a `UITapGestureRecognizer`'s: one tap, one
 * pointer, no movement beyond `viewConfiguration.touchSlop` (a scroll or
 * a scroller drag never dismisses), released within
 * `viewConfiguration.longPressTimeoutMillis` (a row long-press opens the
 * menu of 16/128 and cancels the touch), and not already consumed when
 * it reaches the Initial pass (a synthetic cancel up arrives consumed).
 * A second pointer down before the first lifts cancels the recognition.
 *
 * Put it outermost of the host's chain — 75 passes it through the host's
 * `modifier` argument, before `testTag`, `clipToBounds`, `onPlaced`,
 * `scrollable` and 65's Final-pass tap hook — and give it the same
 * [gate] the rows' `transcriptControl()` markers read. The dismiss itself
 * is 56's `ComposerMorphState.blur()` (the session screen passes it as
 * [onDismiss]): a no-op when the composer is not focused, so the draft,
 * the staged images and a queued-edit lease are never touched.
 */
fun Modifier.dismissComposerOnTap(gate: TranscriptTapGate, onDismiss: () -> Unit): Modifier =
    pointerInput(gate, onDismiss) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            gate.arm(down.id)
            val slop = viewConfiguration.touchSlop
            val deadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                // A second finger before the first lifts: not a tap.
                if (event.changes.fastAny { it.id != down.id && it.pressed }) return@awaitEachGesture
                val c = event.changes.fastFirstOrNull { it.id == down.id } ?: return@awaitEachGesture
                // Beyond slop at any time: a scroll or a scroller drag, never a tap.
                if ((c.position - down.position).getDistance() > slop) return@awaitEachGesture
                if (!c.pressed) {
                    // A real up is still unconsumed here (Initial runs ancestors
                    // first; consumers run later); a synthetic cancel up arrives
                    // pre-consumed. A marked control's down claimed the gesture:
                    // the control keeps its tap and the composer stays focused.
                    if (!c.isConsumed && c.uptimeMillis <= deadline && !gate.isClaimed(down.id)) onDismiss()
                    return@awaitEachGesture
                }
            }
        }
    }
