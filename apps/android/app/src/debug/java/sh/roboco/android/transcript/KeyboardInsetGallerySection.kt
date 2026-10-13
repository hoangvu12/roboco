package sh.roboco.android.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import sh.roboco.android.composer.composerInput
import sh.roboco.android.composer.composerMorphHost
import sh.roboco.android.composer.rememberComposerMorphState
import sh.roboco.android.core.Face
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.LocalGalleryCapture
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassSurface
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.design.uiStyle
import sh.roboco.android.session.BottomStackModel
import sh.roboco.android.session.BottomStackState
import sh.roboco.android.session.SessionBottomStack
import sh.roboco.android.session.rememberBottomStackState

/**
 * The "Keyboard inset sync" page of the debug design gallery
 * (android-ui-parity #133): the ticket's two transcript behaviours over
 * a fake 4000 dp transcript, with no real core.
 *
 * - **Inset sync** — 74's [SessionBottomStack] over a fake transcript
 *   that receives `insets.covered` through [Modifier.transcriptBottomInset]
 *   (the same-pass measure-time delivery), so while following, the
 *   content end (the danger marker) rides `stackTop − 8` on every IME
 *   animation frame. The per-frame `drift` readout pins it (`maxDrift`
 *   must stay ≤ 4 dp through a full keyboard open/close/reversal).
 * - **Interactive dismiss** — the fake transcript carries
 *   [Modifier.interactiveImeDismiss] and 68's real scroll chain, so a
 *   drag that reaches the IME tracks the finger; the composer is a real
 *   56 morph (glass capsule + field), so the drag gate and the
 *   "closed IME blurs to the capsule" rule are exercised.
 * - **Fake runway** — switches the content to 69's worked example
 *   (natural 1100 dp, prompt marker at 1000) with the runway refresh
 *   installed as the viewport's geometry hook: while the IME moves, the
 *   prompt marker stays under the window's top inset.
 * - **Force fallback** — [InteractiveImeDismiss.forceFallback]: the drag
 *   never requests the controller; the IME closes only on release past
 *   half, with the system animation.
 *
 * Registered in `debug/…/design/Gallery.kt` as
 * `GallerySection("Keyboard inset sync", GalleryLayout.Fill, screenshotHeightDp = 915)`.
 */
@Composable
fun KeyboardInsetGallerySection() {
    val palette = RobocoTheme.palette
    val viewport = remember { TranscriptViewport() }
    val stackState = rememberBottomStackState()
    var runway by remember { mutableStateOf(false) }
    var forceFallback by remember { mutableStateOf(false) }

    // Sync the shared debug flag (an equal write is a no-op).
    InteractiveImeDismiss.forceFallback = forceFallback

    Column(Modifier.fillMaxSize().background(palette.background)) {
        // The fixed-height session window: the fake transcript fills it
        // (the box's measured height flows into the viewport through 68's
        // onSizeChanged), the stack floats over its lower edge.
        Box(Modifier.fillMaxWidth().height(SESSION_WINDOW)) {
            ProvideHazeState {
                SessionBottomStack(
                    state = stackState,
                    model = BottomStackModel(),
                    transcript = { insets -> FakeTranscript(viewport, insets.covered, runway) },
                    pill = {},
                    queue = {},
                    questions = {},
                    composer = { ComposerStandIn() },
                    jump = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            KeyboardToggleRow(
                "Fake runway",
                if (runway) "on (natural 1100, prompt 1000)" else "off (4000)",
            ) { runway = setRunway(viewport, runway, !runway) }
            KeyboardToggleRow("Force fallback", if (forceFallback) "on" else "off") {
                forceFallback = !forceFallback
                InteractiveImeDismiss.forceFallback = forceFallback
            }
            DriftReadout(viewport, stackState)
        }
    }
}

/**
 * The fake transcript (133): the full-window box with 68's real scroll
 * chain and 133's two modifiers — [Modifier.transcriptBottomInset] first
 * (outermost, so it measures before the rows), then
 * [Modifier.interactiveImeDismiss] — and 40 rows of 100 dp (11 in runway
 * mode), the content-end marker at `contentHeight − offset` and, in
 * runway mode, the prompt marker at `1000 − offset`, placed at the
 * viewport's offset in the placement lambda.
 */
@Composable
private fun FakeTranscript(viewport: TranscriptViewport, covered: Dp, runway: Boolean) {
    val palette = RobocoTheme.palette
    val density = LocalDensity.current.density
    // A remembered holder (like 65's state.hostCoordinates): both the
    // onPlaced write and the observer's read go through the SAME object,
    // so a recomposition's fresher lambdas never leave the still-running
    // pointerInput closure reading a stale local.
    val hostCoords = remember { HostCoords() }

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .transcriptBottomInset(viewport, covered) // 133, outermost
            .clipToBounds()
            .onPlaced { hostCoords.value = it }
            .interactiveImeDismiss(viewport) { hostCoords.value }
            .then(rememberTranscriptScroll(viewport)),
    ) {
        // 65's equivalent of the size/inset SideEffect: the box height
        // arrives through the scroll modifier's onSizeChanged; the top
        // inset is this stand-in's own (a real header is 52's).
        LaunchedEffect(Unit) {
            viewport.setTopInset(TOP_INSET)
            viewport.onFrameApplied(CONTENT_HEIGHT, first = true)
        }
        FakeRows(viewport, runway, density, palette.text, palette.accent, palette.danger, palette.secondary)
    }
}

/**
 * The fake content: [ROWS] / [RUNWAY_ROWS] stripes of 100 dp (alternating
 * the ticket's two role colours) plus the 2 dp markers, placed at
 * `y − offset` in the placement lambda — `offset` is read per pixel only
 * here, exactly like 65's row placement.
 */
@Composable
private fun FakeRows(
    viewport: TranscriptViewport,
    runway: Boolean,
    density: Float,
    stripe: Color,
    alt: Color,
    end: Color,
    prompt: Color,
) {
    val rows = if (runway) RUNWAY_ROWS else ROWS
    Layout(
        content = {
            repeat(rows) { i ->
                Box(Modifier.fillMaxWidth().height(100.dp).background(if (i % 2 == 0) stripe else alt))
            }
            // The content-end marker: 2 dp danger at contentHeight − offset.
            Box(Modifier.fillMaxWidth().height(2.dp).background(end).testTag("gallery-content-end"))
            // The prompt marker (runway mode): 2 dp secondary at 1000 − offset.
            Box(Modifier.fillMaxWidth().height(2.dp).background(prompt))
        },
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val rowPx = 100.dp.roundToPx()
        val placeables = measurables.mapIndexed { i, m ->
            if (i < rows) m.measure(Constraints.fixed(w, rowPx)) else m.measure(Constraints.fixedWidth(w))
        }
        layout(w, constraints.maxHeight) {
            val offPx = (viewport.offset * density).roundToInt()
            placeables.forEachIndexed { i, p ->
                if (i < rows) p.place(0, i * rowPx - offPx)
            }
            placeables[rows].place(0, ((viewport.contentHeight - viewport.offset) * density).roundToInt())
            if (runway) {
                placeables[rows + 1].place(0, (PROMPT_Y * density).roundToInt() - offPx)
            }
        }
    }
}

/**
 * The composer stand-in: a 50 dp glass capsule with a real 56 morph and
 * one field — tapping a plain glass area focuses it (the real IME opens,
 * the stack rides it), and the drag gate plus the "closed IME blurs"
 * rule run for real.
 */
@Composable
private fun ComposerStandIn() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val morph = rememberComposerMorphState(alwaysCard = false, hasImages = { false })
    val field = remember { TextFieldState("") }
    GlassSurface(
        modifier = Modifier.fillMaxWidth().height(50.dp).composerMorphHost(morph),
        shape = CapsuleShape,
    ) {
        Box(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                state = field,
                textStyle = uiStyle(Face.Sans, typeScale.fixed(16.5f), palette.text),
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 2),
                modifier = Modifier.fillMaxWidth().composerInput(morph),
            )
        }
    }
}

/**
 * The fake-runway toggle (69's worked example, natural 1100 / prompt
 * 1000): installs the runway refresh as the viewport's geometry hook
 * (`minimum = hold + viewportHeight − bottomInset`,
 * `hold = p.y − topInset`) and re-lands at the bottom; disabling restores
 * the natural 4000 dp content and clears the hook. Returns the new state.
 */
private fun setRunway(viewport: TranscriptViewport, was: Boolean, on: Boolean): Boolean {
    if (was == on) return on
    if (on) {
        // 69's minimum: hold + viewportHeight − bottomInset, hold = p.y − topInset.
        viewport.onGeometryChanged = { viewport.setContentHeight(runwayHeight(viewport)) }
        viewport.setContentHeight(runwayHeight(viewport))
        viewport.scrollToBottom(false)
    } else {
        viewport.onGeometryChanged = null
        viewport.setContentHeight(CONTENT_HEIGHT)
        viewport.scrollToBottom(false)
    }
    return on
}

/** The runway height for the current geometry: `max(natural, minimum)`. */
private fun runwayHeight(viewport: TranscriptViewport): Float = max(
    RUNWAY_NATURAL,
    PROMPT_Y - viewport.topInset + viewport.viewportHeight - viewport.bottomInset,
)

/**
 * The per-frame mono readout: `ime`, `stackTop`, `covered`, `end`,
 * `drift` (`(contentHeight − offset) − (stackTop − 8)` while following),
 * `maxDrift` (reset on the chip), the pipeline mode and `active`. Under
 * the Roborazzi capture the frame loop never runs, so the golden shows
 * the initial (idle, empty-IME) line.
 */
@Composable
private fun DriftReadout(viewport: TranscriptViewport, stackState: BottomStackState) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    var text by remember { mutableStateOf("") }
    var maxDrift by remember { mutableStateOf(0f) }
    // Ticket 22's "no running loops" rule: under the Roborazzi capture
    // the line is computed once, the frame loop never starts.
    val capture = LocalGalleryCapture.current
    LaunchedEffect(viewport, stackState, capture) {
        var first = true
        while (true) {
            if (!first) {
                if (capture) break
                withFrameNanos { }
            }
            first = false
            val stackTop = stackState.insets.stackTop.value
            val drift = if (viewport.following) {
                (viewport.contentHeight - viewport.offset) - (stackTop - 8f)
            } else {
                Float.NaN
            }
            if (!drift.isNaN()) maxDrift = max(maxDrift, abs(drift))
            text = buildString {
                append("ime=").append(ime.getBottom(density))
                append(" stackTop=").append(fmt1(stackTop))
                append(" covered=").append(fmt1(viewport.bottomInset))
                append(" end=").append(fmt1(viewport.contentHeight - viewport.offset))
                append(" drift=").append(if (drift.isNaN()) "-" else fmt1(drift))
                append(" maxDrift=").append(fmt1(maxDrift))
                append(" mode=").append(InteractiveImeDismiss.debugMode)
                append(" active=").append(InteractiveImeDismiss.active)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            text,
            style = uiStyle(Face.Mono, typeScale.fixed(11f), palette.secondary),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        BasicText(
            "Reset",
            style = uiStyle(Face.Mono, typeScale.fixed(11f), palette.accent),
            modifier = Modifier
                .width(64.dp)
                .clickable(onClickLabel = "Reset max drift") { maxDrift = 0f }
                .padding(vertical = 6.dp),
            maxLines = 1,
        )
    }
}

/** One toggle row, label left, current value in accent right. */
@Composable
private fun KeyboardToggleRow(label: String, value: String, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClickLabel = label, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.text))
        Spacer(Modifier.weight(1f))
        BasicText(value, style = uiStyle(Face.SansMedium, typeScale.fixed(15f), palette.accent))
    }
}

/** One decimal, ROOT locale, for the readout. */
private fun fmt1(v: Float): String = String.format(Locale.ROOT, "%.1f", v)

/** The fake host's coordinates holder (65 uses TranscriptState's field). */
private class HostCoords {
    var value: LayoutCoordinates? = null
}

/** The session window's height (the phone gallery frame is 74's 560). */
private val SESSION_WINDOW = 560.dp

/** The stand-in top inset: status bar + 44 header, in the window's spirit. */
private const val TOP_INSET = 96f

/** The fake content: 40 rows of 100 dp. */
private const val ROWS = 40

/** The runway content: 11 rows (natural 1100 dp), prompt marker at 1000. */
private const val RUNWAY_ROWS = 11

/** The natural content height (dp). */
private const val CONTENT_HEIGHT = 4000f

/** The runway's natural height (dp) — 69's worked example. */
private const val RUNWAY_NATURAL = 1100f

/** The prompt row's content y (dp) — 69's worked example. */
private const val PROMPT_Y = 1000f
