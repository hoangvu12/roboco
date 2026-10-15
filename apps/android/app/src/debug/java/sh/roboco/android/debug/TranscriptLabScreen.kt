package sh.roboco.android.debug

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.roboco.android.core.Face
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Motion
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.glass.GlassCircleButton
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.design.glass.ScrollEdgeEffect
import sh.roboco.android.design.glass.ScrollEdgeEffectDefaults
import sh.roboco.android.design.hairlineBorder
import sh.roboco.android.design.uiStyle
import sh.roboco.android.image.ImageViewerHost
import sh.roboco.android.image.ImageViewerRequest
import sh.roboco.android.image.LocalImageViewer
import sh.roboco.android.image.rememberImageViewerState
import sh.roboco.android.transcript.TranscriptActions
import sh.roboco.android.transcript.TranscriptHost
import sh.roboco.android.transcript.TranscriptImageBinding
import sh.roboco.android.transcript.TranscriptImageLoader
import sh.roboco.android.transcript.TranscriptViewport

/**
 * The Transcript Lab screen (android-ui-parity #135): the full-window
 * transcript (ticket 65's host over the real Rust layout), the inline
 * "Transcript Lab" title over ticket 09's soft scroll edge, and the
 * minimal debug input row — *not* the real composer (tickets 54–59): a
 * plain field and a glass circle Send/Stop button, whose covered height
 * is the transcript's bottom inset so the IME path is exercised anyway.
 *
 * Everything runs in one [SubcomposeLayout]: the insets are read in the
 * measure block (every IME animation frame re-measures only, nothing
 * recomposes — ticket 74's pitfall), the input row is measured first so
 * its `covered` height feeds `viewport.setBottomInset` in the same pass
 * the transcript measures — 133's sync, done inline (the modifier would
 * cost a recomposition per IME frame here).
 *
 * No Material anywhere: `BasicText`, `BasicTextField` and the ticket
 * 01/03/06/08/09 parts only.
 *
 * @param controller the lab's state (entries, stream, ticker)
 * @param options the parsed launch extras
 * @param runScripts `savedInstanceState == null`: the `top`/`autostream`/`autoscroll` scripts run
 * @param overlay ticket 136's instruments slot; `contentTop` is the title
 *   bar's bottom (status-bar top + 44 dp, iOS `safeAreaLayoutGuide.top`)
 */
@Composable
fun TranscriptLabScreen(
    controller: TranscriptLabController,
    options: LabOptions,
    runScripts: Boolean,
    overlay: @Composable BoxScope.(contentTop: Dp) -> Unit = {},
) {
    val context = LocalContext.current
    if (options.images) {
        // Ticket 137 (`--ez images true`): 135's fake PNG loader would
        // answer the missing image too, so the screen swaps in a real
        // [TranscriptImageBinding] over [LabImageReader] (the two
        // workspace PNGs after 600 ms, NotFound otherwise) and mounts
        // 125's viewer host so a loaded tile opens the preview with the
        // alt text as its label.
        ImageViewerHost(rememberImageViewerState()) {
            val viewer = LocalImageViewer.current
            val binding = remember {
                TranscriptImageBinding(TranscriptImageLoader.get(context), { "lab" }, LabImageReader)
            }
            val actions = remember(viewer, binding) {
                TranscriptActions(
                    // 126's router is not merged: open anything through the system.
                    openLink = { tap -> openLabLink(context, tap.url) },
                    loadImage = binding::load,
                    cachedImage = binding::cached,
                    openImage = { reference, source, bitmap, label ->
                        viewer?.show(
                            ImageViewerRequest(
                                reference = reference,
                                preview = bitmap,
                                sourceBounds = source,
                                sourceCornerRadius = 14.dp,
                                label = label,
                                loadFull = { binding.loadFull(reference) },
                            ),
                        )
                    },
                )
            }
            LabScreenBody(controller, options, runScripts, actions, overlay)
        }
    } else {
        val actions = remember {
            TranscriptActions(
                // 126's router is not merged: open anything through the system.
                openLink = { tap -> openLabLink(context, tap.url) },
                // 66's two-arg loader: the fake PNG answers every reference.
                loadImage = { _, sidePx -> LabImages.load(context, sidePx) },
                cachedImage = { _, sidePx -> LabImages.cached(sidePx) },
            )
        }
        LabScreenBody(controller, options, runScripts, actions, overlay)
    }
}

/**
 * The lab's body (135) — everything but the actions wiring, split so the
 * `images` mode (137) can wrap it in 125's [ImageViewerHost].
 */
@Composable
private fun LabScreenBody(
    controller: TranscriptLabController,
    options: LabOptions,
    runScripts: Boolean,
    actions: TranscriptActions,
    overlay: @Composable BoxScope.(contentTop: Dp) -> Unit,
) {
    val palette = RobocoTheme.palette
    val viewport = controller.state.viewport
    val hazeState = rememberHazeState()
    // The insets are captured in composition (they are @Composable
    // getters) and READ in the measure block — the live values update
    // per IME animation frame and re-measure only.
    val ime = WindowInsets.ime
    val nav = WindowInsets.navigationBars
    val status = WindowInsets.statusBars
    val safe = WindowInsets.safeDrawing
    val layoutDir = LocalLayoutDirection.current
    // The scroll edge engages once content scrolled past the top
    // (68's `scrollY = offset + topInset`); the derived state changes
    // only when the Boolean flips.
    val engaged by remember(viewport) { derivedStateOf { viewport.scrollY > 0.5f } }

    if (runScripts) {
        LaunchedEffect(controller) {
            val t0 = SystemClock.uptimeMillis()

            // -top: snap to the resting top without releasing follow (the
            // iOS lab's one-line move, `TranscriptLabViewController.swift:70-73`).
            if (options.top) launch {
                awaitLabTime(t0, LabScript.TopDelayMillis, controller, waitFirstFrame = true)
                viewport.setOffset(viewport.minOffset)
            }
            // -autostream: with turns 0 the frame never has rows, so only
            // here the frame wait is skipped (a stream can still start).
            if (options.autostream) launch {
                awaitLabTime(t0, LabScript.AutostreamDelayMillis, controller, waitFirstFrame = options.turns > 0)
                controller.send(LabScript.AutostreamPrompt)
            }
            // -autoscroll: 4 s easeInOut up, 0.3 s pause, 4 s easeInOut
            // down; a finger drag cancels it.
            if (options.autoscroll) launch {
                awaitLabTime(t0, LabScript.AutoscrollDelayMillis, controller, waitFirstFrame = true)
                val job = launch { autoscroll(viewport) }
                val watcher = launch {
                    controller.state.scrollInteractions.interactions.first { it is DragInteraction.Start }
                    job.cancel()
                }
                job.invokeOnCompletion { watcher.cancel() }
            }
        }
    }

    ProvideHazeState(hazeState) {
        SubcomposeLayout(Modifier.fillMaxSize().background(palette.background)) { constraints ->
            check(constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
                "TranscriptLabScreen needs a bounded window (the caller passes Modifier.fillMaxSize())"
            }
            val W = constraints.maxWidth
            val H = constraints.maxHeight

            // The insets in px, read at measure time (density = `this`).
            val imeB = ime.getBottom(this)
            val navB = nav.getBottom(this)
            val statusT = status.getTop(this)
            val safeL = safe.getLeft(this, layoutDir)
            val safeR = safe.getRight(this, layoutDir)

            // The input row: 12 dp from both safe sides, measured at that
            // width so its height (44 dp single-line) is known before the
            // inset is applied.
            val sideInset = 12.dp.roundToPx()
            val keyboardGap = 8.dp.roundToPx()
            val inputW = (W - safeL - safeR - 2 * sideInset).coerceAtLeast(0)
            val inputPlaceable = subcompose(LabSlot.Input) { LabInputRow(controller) }
                .first()
                .measure(Constraints(minWidth = inputW, maxWidth = inputW))

            // The row sits 8 dp above max(ime, nav); its covered height
            // is the transcript's bottom inset (iOS `:85-95`).
            val inputTop = H - maxOf(imeB, navB) - keyboardGap - inputPlaceable.height
            val covered = H - inputTop + keyboardGap

            // 68 R12/R11: threshold 0.5 dp, re-pin while following. This
            // measure-block call IS 133's same-pass sync — the host below
            // measures after it, in the same pass the row moved.
            viewport.setTopInset(statusT.toFloat().toDp().value + 44f)
            viewport.setBottomInset(maxOf(navB, covered).toFloat().toDp().value)

            // The transcript: the full window, never padded and never
            // shrunk by the IME. It is the hazeSource; the scroll edge
            // samples it.
            val transcriptPlaceable = subcompose(LabSlot.Transcript) {
                Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
                    TranscriptHost(controller.state, actions, Modifier.fillMaxSize())
                }
            }.first().measure(Constraints.fixed(W, H))

            // The header: the scroll edge over the status bar + the
            // 44-dp title bar, no hit testing (touches pass through).
            val headerPlaceable = subcompose(LabSlot.Header) { LabHeader(engaged) }
                .first()
                .measure(Constraints(maxWidth = W))

            // Ticket 136's overlay slot: contentTop = the title bar's bottom.
            val contentTop = statusT.toFloat().toDp() + 44.dp
            val overlayPlaceable = subcompose(LabSlot.Overlay) {
                Box(Modifier.fillMaxSize()) { overlay(contentTop) }
            }.first().measure(Constraints.fixed(W, H))

            layout(W, H) {
                // Placement order is z-order: transcript under, header,
                // input row, overlay on top.
                transcriptPlaceable.place(0, 0)
                headerPlaceable.place(0, 0)
                inputPlaceable.place(safeL + sideInset, inputTop)
                overlayPlaceable.place(0, 0)
            }
        }
    }
}

/** The subcompose slots, in measure order. */
private enum class LabSlot { Input, Transcript, Header, Overlay }

/**
 * The inline title over the soft scroll edge: ticket 09's
 * [ScrollEdgeEffect] (status + 44 + 16 dp, sampling the haze source) and
 * the "Transcript Lab" label in SemiBold fixed 17, centred in the 44-dp
 * bar directly below the status bar (`MainTabController.nav(_:)`'s
 * inline title, `Palette.text`). No click handlers — touches reach the
 * transcript (ticket 09's rule).
 */
@Composable
private fun LabHeader(engaged: Boolean) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Box(Modifier.fillMaxWidth()) {
        ScrollEdgeEffect(height = ScrollEdgeEffectDefaults.height(), engaged = engaged)
        Box(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .fillMaxWidth()
                .height(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                "Transcript Lab",
                style = uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text),
                maxLines = 1,
                modifier = Modifier
                    .testTag("lab-title")
                    .semantics { heading() },
            )
        }
    }
}

/**
 * The minimal debug input row — a stand-in for the real composer
 * (tickets 54–59), pulled in deliberately thin so the lab tests the
 * transcript: one capsule field and a 44-dp glass circle Send/Stop
 * button. The button mirrors `ComposerBar.refreshAction`
 * (`ios:Composer/ComposerBar.swift:578-622`, `canSteer = false`): send
 * disabled on an empty field, queue while streaming with text, stop
 * while streaming empty. The state change is instant — the composer's
 * springs and symbol bounce are ticket 58.
 */
@Composable
private fun LabInputRow(controller: TranscriptLabController) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    var text by rememberSaveable { mutableStateOf("") }
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .clip(CapsuleShape)
                .background(palette.elevated)
                .hairlineBorder(palette.hairline, CapsuleShape)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) {
                BasicText(
                    "Message Claude",
                    style = uiStyle(Face.Sans, typeScale.scaled(17f), palette.tertiary),
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = uiStyle(Face.Sans, typeScale.scaled(17f), palette.text),
                cursorBrush = SolidColor(palette.accent),
                maxLines = 5,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("lab-input")
                    // TalkBack reads the text once there is any; while
                    // empty the placeholder stands in (the composer's
                    // pattern, `ComposerTextField.kt`).
                    .semantics { if (text.isEmpty()) contentDescription = "Message Claude" },
            )
        }
        val hasContent = text.trim().isNotEmpty()
        val running = controller.stream.running
        val stop = running && !hasContent
        GlassCircleButton(
            icon = if (stop) RobocoIcons.StopFill else RobocoIcons.ArrowUp,
            contentDescription = when {
                stop -> "Stop response"
                running -> "Queue message"
                else -> "Send message"
            },
            onClick = {
                if (stop) {
                    controller.stopStream()
                } else {
                    controller.send(text.trim())
                    text = ""
                }
            },
            modifier = Modifier.testTag("lab-action"),
            iconPt = if (stop) 11f else 15f,
            weight = SymbolWeight.Bold,
            filled = when {
                stop -> palette.text
                hasContent -> palette.accent
                else -> palette.controlFill
            },
            ink = if (stop) palette.background else Color.White,
            enabled = stop || hasContent,
        )
    }
}

/**
 * One script's wait (the first-frame rule, the ticket's ADAPT): the iOS
 * delay counted from [t0] — the first composition — and the first
 * applied frame with rows, whichever is later. With a huge turn count
 * on a slow device the first layout can outlast 0.6 s, and on iOS the
 * `-top` jump would then be silently overwritten by the first-frame
 * landing; waiting here keeps the script's write last. [waitFirstFrame]
 * is skipped for `autostream` on an empty lab (`turns 0`), so a stream
 * can still start when the frame never has rows.
 */
private suspend fun awaitLabTime(t0: Long, ms: Long, controller: TranscriptLabController, waitFirstFrame: Boolean) {
    delay((t0 + ms - SystemClock.uptimeMillis()).coerceAtLeast(0))
    if (waitFirstFrame) {
        snapshotFlow { controller.state.frame?.rowCount() ?: 0u }.first { it > 0u }
    }
}

/**
 * The `-autoscroll` script (`TranscriptLabViewController.swift:136-146`):
 * 4 s easeInOut to `minOffset`, a 0.3 s pause, then 4 s easeInOut to
 * `maxOffset` (read before the pause, like the iOS completion). Neither
 * leg releases follow.
 */
private suspend fun autoscroll(viewport: TranscriptViewport) {
    val top = viewport.minOffset
    leg(viewport, viewport.offset, top, LabScript.AutoscrollLegMillis)
    val target = viewport.maxOffset
    delay(LabScript.AutoscrollPauseMillis)
    leg(viewport, top, target, LabScript.AutoscrollLegMillis)
}

/**
 * One scripted scroll leg: `setOffset(lerp(from, to, e(t)))` per display
 * frame, `e` = UIKit easeInOut (`Motion.EaseInOut`). Driven by
 * [withFrameNanos] and the elapsed wall time — not `Animatable`/`tween`
 * — so it runs identically under the system "remove animations" scale
 * (it is a scripted benchmark, like iOS `UIView.animate`, which ignores
 * Reduce Motion).
 */
private suspend fun leg(viewport: TranscriptViewport, from: Float, to: Float, ms: Long) {
    val ease = Motion.EaseInOut
    val start = withFrameNanos { it }
    while (true) {
        val now = withFrameNanos { it }
        val t = ((now - start) / 1e6f / ms).coerceIn(0f, 1f)
        viewport.setOffset(from + (to - from) * ease.transform(t))
        if (t >= 1f) break
    }
}

/**
 * The lab's link handler: ticket 126's router is not merged, so anything
 * goes to the system (`ACTION_VIEW`) inside `runCatching` — the lab sets
 * no custom router, like the iOS lab's default `SFSafariViewController`
 * path.
 */
internal fun openLabLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
