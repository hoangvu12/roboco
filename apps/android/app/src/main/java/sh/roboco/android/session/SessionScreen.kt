package sh.roboco.android.session

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import sh.roboco.android.image.ImageViewerRequest
import sh.roboco.android.image.LocalImageViewer
import sh.roboco.android.shell.LocalNavEntry
import sh.roboco.android.shell.LocalNavStack
import sh.roboco.android.shell.NavEntryEffect
import sh.roboco.android.shell.NavKind
import sh.roboco.android.transcript.AttachmentReader
import sh.roboco.android.transcript.TranscriptActions
import sh.roboco.android.transcript.TranscriptHost
import sh.roboco.android.transcript.TranscriptImageBinding
import sh.roboco.android.transcript.TranscriptImageLoader
import sh.roboco.android.transcript.TranscriptState
import sh.roboco.android.transcript.transcriptBottomInset
import sh.roboco.android.transcript.widgets.imageTileRadius

/**
 * The Session screen (android-ui-parity #75) — the composition that stacks
 * the parts other tickets build, the port of
 * `ios:Session/SessionViewController.swift:20-30, 60-79, 119-170`:
 *
 * - the per-screen [SessionScreenState] (built by 76's route factory and
 *   held by the route, so it exists before the push for 77's
 *   `prepareArrival()` and survives a tab-host disposal — 85 relies on
 *   both). **Never created here**: a `remember {}` would recreate it on
 *   every tab switch;
 * - the first-composition wiring 51/52/66/85 delegate here: the hold
 *   decision, the one-time `attach` + `markSeen` (after the frame listener
 *   is registered, so the first frame is never missed), the landing hook,
 *   the transcript's top inset, the header's `edgeEngaged` signal and the
 *   [TranscriptActions];
 * - the layer stack: [SessionScaffold] (background, haze state, the header
 *   over everything) wrapping ticket 74's `SessionBottomStack`, whose
 *   transcript slot carries [OpenRevealLayers] (the transcript host, the
 *   hold blocker, the loader).
 *
 * The `OPEN` probe is wired through the frame listener: the first applied
 * frame with rows produces the JSON ([SessionScreenState.onFrameApplied])
 * and this screen emits it ([OpenProbe.emit]).
 *
 * **Detaching is 85's, not this ticket's** (`onDispose { }` is empty on
 * purpose): a cancelled predictive back and a tab-host disposal also run
 * it, and neither may detach the view (upstream #609's trap). 85 detaches
 * and closes on pop commit only.
 *
 * @param controller 51's screen state holder (chrome, attach, markSeen)
 * @param state the route-held per-screen state; its [SessionScreenState.transcript]
 *   is created on the first main-thread read (60's prewarm built the
 *   engine already)
 * @param onMore the ⋯ tap (ticket 80 fills it); the [Rect] is the circle's
 *   bounds in window px
 * @param attachments ticket 66's attachment reader; 76's route passes
 *   `AttachmentReader { d, p -> core.client.readAttachment(d, p) }`. When
 *   non-null the transcript actions load and cache images through a
 *   [TranscriptImageBinding] pinned to the controller's host device;
 *   otherwise the defaults stay no-ops
 */
@Composable
fun SessionScreen(
    controller: SessionController,
    state: SessionScreenState,
    modifier: Modifier = Modifier,
    onMore: (anchorInWindow: Rect) -> Unit = {},
    attachments: AttachmentReader? = null,
) {
    // 1. The per-screen state: the same instance the route holds. The
    //    first main-thread read creates the transcript (76's factory
    //    passed `{ TranscriptState(TextEngine.shared) }`; the app-start
    //    prewarm built the engine already).
    val entry = LocalNavEntry.current
    val transcript = state.transcript

    // 2. Chrome, collected once and passed field by field (51).
    val chrome by controller.chrome.collectAsState()

    // 3. The hold decision — at FIRST composition, before SessionScaffold
    //    below (a `LaunchedEffect`/onWillAppear would be a frame too late:
    //    one frame could show the transcript). `begin` is once-only, so a
    //    host that re-enters composition later changes nothing.
    val stack = LocalNavStack.current
    remember(state) {
        val t = stack.transition
        state.reveal.begin(
            OpenReveal.shouldHold(
                animatedPushToThis = t != null && t.kind == NavKind.Push && t.to === entry && t.animated,
                hasAppeared = entry.hasAppeared,
                arrivalPrepared = state.arrivalPrepared,
            ),
        )
    }

    // 4. The one-time start: the frame listener (before `attach`, so the
    //    first frame is never missed), the catch-up for a frame that was
    //    applied before we listened, then `attach` + `markSeen`
    //    (iOS :167-170). Once per screen state; the listener is never
    //    removed here — 85's `close()` clears it.
    val context = LocalContext.current
    DisposableEffect(state) {
        if (!state.started) {
            state.started = true
            // The listener outlives this composition (the route holds the
            // transcript): capture the application context, never the Activity.
            val appContext = context.applicationContext
            transcript.addFrameListener { _, new ->
                state.onFrameApplied(new.rowCount().toLong(), new.buildMicros().toLong())
                    ?.let { OpenProbe.emit(appContext, it) }
            }
            transcript.frame?.let { applied ->
                state.onFrameApplied(applied.rowCount().toLong(), applied.buildMicros().toLong())
                    ?.let { OpenProbe.emit(appContext, it) }
            }
            controller.attach(transcript.engine)
            controller.markSeen()
        }
        onDispose { } // never detach here (ticket 85 does, on pop commit)
    }

    // 5. Landing: the push transition completed. The give-up timer lives
    //    in SessionScaffold, so the gallery and the tests get it too.
    NavEntryEffect(onDidAppear = { _ -> state.reveal.onLanded() })

    // 6. The transcript's top inset: 52's `contentTopInset` (status bar +
    //    44), written every composition pass; the viewport only reacts
    //    when it moves (68 R12).
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    SideEffect { transcript.topInset = SessionHeaderDefaults.contentTopInset(statusBarTop).value }

    // 7. The header's edge signal (52 step 6): content sits under the bar
    //    once the viewport scrolled (68's `scrollY = offset + topInset`).
    val edgeEngaged by remember(transcript) { derivedStateOf { transcript.viewport.scrollY > 0.5f } }

    // 8. The transcript's actions (65): the defaults are no-ops; 66's
    //    binding fills the image pair when the route passed a reader, and
    //    125 fills `openImage` through the root overlay's viewer state —
    //    the tile's downsampled bitmap is the start frame, `loadFull` the
    //    binding's full-size read, and the source rect the unclipped
    //    window rect the tile reports with its tap. 81, 126 and 127
    //    replace their own defaults here later.
    val viewer = LocalImageViewer.current
    val density = LocalDensity.current
    val actions = remember(controller, attachments, viewer) {
        val binding = attachments?.let {
            TranscriptImageBinding(TranscriptImageLoader.get(context), { controller.hostDeviceId }, it)
        }
        val openImage: (String, Rect, ImageBitmap?) -> Unit = { reference, source, bitmap ->
            viewer?.show(
                ImageViewerRequest(
                    reference = reference,
                    preview = bitmap,
                    sourceBounds = source,
                    sourceCornerRadius = with(density) {
                        imageTileRadius(source.width.toDp().value, source.height.toDp().value)
                    },
                    label = null, // 137 fills this with the alt text
                    loadFull = if (binding != null) {
                        { binding.loadFull(reference) }
                    } else {
                        null
                    },
                ),
            )
        }
        if (binding != null) {
            TranscriptActions(
                openImage = openImage,
                loadImage = binding::load,
                cachedImage = binding::cached,
            )
        } else {
            TranscriptActions()
        }
    }

    // 9. The bottom stack's state and visibility model (74). 83 replaces
    //    the `editing` flag with its lease state.
    val bottomState = rememberBottomStackState()
    val model = BottomStackModel.from(chrome, editing = false)

    // 10. The layer stack: the scaffold (background, haze, header) around
    //     74's container, with the open-reveal layers in its transcript
    //     slot. 133's `Modifier.transcriptBottomInset` is the viewport's
    //     bottom inset's only writer: it delivers `covered` in the same
    //     measure pass that moved the stack (never one frame late, the
    //     pr621 trap), so 68 pins the tail under the composer on every
    //     IME animation frame and the runway re-sizes in the same pass
    //     (69). `covered` (not `contentInset`) is the effective viewport
    //     bottom inset — iOS's `adjustedContentInset.bottom`.
    SessionScaffold(
        reveal = state.reveal,
        header = {
            SessionHeader(
                title = chrome.title,
                subtitle = chrome.subtitle,
                edgeEngaged = edgeEngaged,
                onMore = onMore,
                statusBarTop = statusBarTop,
            )
        },
        modifier = modifier,
    ) {
        SessionBottomStack(
            state = bottomState,
            model = model,
            transcript = { insets ->
                OpenRevealLayers(state.reveal, statusBarTop = statusBarTop) {
                    TranscriptHost(
                        transcript, actions,
                        // 133, outermost of the host's chain, so the inset
                        // node measures before the host measures and places
                        // its rows; the host itself adds the IME-drag
                        // observer (interactiveImeDismiss) and 68's scroll.
                        Modifier.fillMaxSize().transcriptBottomInset(transcript.viewport, insets.covered),
                    )
                }
            },
            pill = {}, // 73
            queue = {}, // 82
            questions = {}, // 90/91
            composer = {}, // 78
            jump = {}, // 131
            // 85 and 77 make this { nav.stackAlpha() * state.arrival.stackAlpha }.
            stackAlpha = { 1f },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
