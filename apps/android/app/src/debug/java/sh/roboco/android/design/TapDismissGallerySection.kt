package sh.roboco.android.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import sh.roboco.android.composer.composerInput
import sh.roboco.android.composer.rememberComposerMorphState
import sh.roboco.android.core.Face
import sh.roboco.android.session.dismissComposerOnTap
import sh.roboco.android.transcript.LocalTranscriptTapGate
import sh.roboco.android.transcript.TranscriptTapGate
import sh.roboco.android.transcript.transcriptControl

/**
 * The "Tap to dismiss composer" page of the debug design gallery
 * (android-ui-parity #81): a 300 dp scrolling "transcript" stand-in
 * carrying the Initial-pass tap observer, with a painted-link row (its
 * own `clickable` still fires — upstream #585), a 44 dp control marked
 * [transcriptControl] (its tap never dismisses) and filler text, plus a
 * bottom strip over the transcript's bottom edge whose container carries
 * 74's observe-only blocker (taps in it never reach the transcript) and
 * holds the real composer morph's `BasicTextField`.
 *
 * Try it live: tap the field (the composer becomes the focused card), type
 * a draft, then tap anywhere on the transcript — the IME goes down and the
 * composer rests as the capsule again, draft kept. Tap the marked control
 * instead and the composer stays focused. Tap the strip (including the gap
 * around it) and nothing happens.
 *
 * No visuals of its own: foundation components only (`BasicText`,
 * `BasicTextField`, `clickable`), no glass, no Material. The readout line
 * below the stage is the ticket's status line.
 *
 * Registered in `Gallery.sections` as "Tap to dismiss composer" (slug
 * `tap_to_dismiss_composer`).
 */
@Composable
fun TapDismissGallerySection() {
    val gate = remember { TranscriptTapGate() }
    val morph = rememberComposerMorphState(alwaysCard = false, hasImages = { false })
    val field = remember { TextFieldState("Half-written thought") }
    var dismissals by remember { mutableStateOf(0) }
    var linkTaps by remember { mutableStateOf(0) }
    var controlTaps by remember { mutableStateOf(0) }
    // Typed: the lambda ends in `dismissals++` (an Int expression).
    val dismiss: () -> Unit = remember(morph) {
        {
            morph.blur()
            dismissals++
        }
    }

    Column {
        CompositionLocalProvider(LocalTranscriptTapGate provides gate) {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                Column(
                    Modifier
                        .fillMaxSize()
                        // 81: outermost of the chain, before the scroll — the
                        // Initial-pass observer that puts the composer away.
                        .dismissComposerOnTap(gate, dismiss)
                        .verticalScroll(rememberScrollState()),
                ) {
                    BasicText(
                        "A painted link, which opens too",
                        style = uiStyle(Face.Sans, LocalTypeScale.current.fixed(15f), RobocoTheme.palette.accent),
                        modifier = Modifier
                            .clickable { linkTaps++ }
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                    )
                    Box(
                        Modifier
                            .padding(vertical = 4.dp)
                            .size(44.dp)
                            .background(RobocoTheme.palette.controlFill)
                            // The marker: a tap whose down lands here belongs
                            // to the control, never to the dismiss.
                            .transcriptControl()
                            .clickable { controlTaps++ },
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            "ctl",
                            style = uiStyle(Face.Mono, LocalTypeScale.current.fixed(11f), RobocoTheme.palette.secondary),
                        )
                    }
                    BasicText(
                        "Half-written thought",
                        style = uiStyle(Face.Sans, LocalTypeScale.current.fixed(15f), RobocoTheme.palette.text),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                    )
                    // Filler: enough of it that the transcript really scrolls
                    // (a scroll never dismisses, only a tap does).
                    repeat(24) { i ->
                        BasicText(
                            "filler line $i — tap me, scroll me",
                            style = uiStyle(Face.Sans, LocalTypeScale.current.fixed(15f), RobocoTheme.palette.text),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                        )
                    }
                }
                // The bottom-strip stand-in: a sibling above the transcript
                // (placed after it in this Box) whose container observes
                // without ever consuming — 74's "blocker". Children (the
                // field) still get their taps; the transcript behind never
                // sees a down inside the strip's rect.
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(8.dp)
                        .background(RobocoTheme.palette.controlFill)
                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                ) {
                    BasicTextField(
                        state = field,
                        textStyle = uiStyle(Face.Sans, LocalTypeScale.current.fixed(16.5f), RobocoTheme.palette.text),
                        lineLimits = TextFieldLineLimits.SingleLine,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 14.dp)
                            .composerInput(morph),
                    )
                }
            }
        }
        BasicText(
            "focused=${morph.isFocused} dismissals=$dismissals link=$linkTaps control=$controlTaps",
            style = uiStyle(Face.Mono, LocalTypeScale.current.fixed(11f), RobocoTheme.palette.secondary),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
