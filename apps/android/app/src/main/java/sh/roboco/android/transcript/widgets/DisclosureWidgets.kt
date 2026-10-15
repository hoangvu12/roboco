package sh.roboco.android.transcript.widgets

import android.content.res.Configuration
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.roboco.android.R
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.IconInBox
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Motion
import sh.roboco.android.design.RobocoIcon
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.uiStyle

/**
 * `WidgetKind.Disclosure`'s view (ticket 120; iOS `DisclosureControl`,
 * `ios:Transcript/RowView.swift:346-373` with `chevron = false`): an
 * **invisible** tap target over a tool group's header and the "Show more"
 * line of a long user bubble. Ticket 65's [WidgetBox] places it at the
 * Rust rect `(x, top, min(31.5·s + sw + 13.5·s, cw), 29.25·s)`; no
 * minimum touch size, no widening — taps right of the title fall through
 * to the transcript, as on iOS.
 *
 * Pressed, iOS sets `alpha = 0.5` on the empty control, which changes
 * nothing the eye can see (the chevron and the title are siblings, never
 * dimmed); the one `graphicsLayer` line is kept for structural parity.
 * A tap plays the `Select` haptic **before** [onToggle] (iOS
 * `TranscriptListView.swift:564`), and the labels are the iOS ones —
 * `Collapse` while expanded, `Expand` otherwise, on the user bubble too
 * (the "Show more" text is Rust paint; the node still says Expand).
 *
 * `clickable` consumes the up event, so 65's tap observer never fires for
 * it; the down event stays unconsumed, so a drag that starts here still
 * scrolls the transcript.
 */
@Composable
fun DisclosureTarget(expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val feedback = LocalFeedback.current
    val interactions = remember { MutableInteractionSource() }
    val pressed = interactions.collectIsPressedAsState()
    Box(
        modifier
            .fillMaxSize()
            .testTag("transcript-disclosure")
            // iOS isHighlighted → alpha 0.5 (DisclosureControl.swift:369-370).
            // The box is empty, so nothing visible changes.
            .graphicsLayer { alpha = if (pressed.value) 0.5f else 1f }
            .clickable(interactionSource = interactions, indication = null, role = Role.Button) {
                feedback.haptic(Haptic.Select) // UISelectionFeedbackGenerator (TLV:564)
                onToggle()
            }
            .semantics { contentDescription = if (expanded) "Collapse" else "Expand" },
    )
}

/**
 * `WidgetKind.ToolToggle`'s view (ticket 120; iOS `ToolToggleControl`,
 * `ios:Transcript/ToolViews.swift:314-319`): an invisible full-width tap
 * target over one 36 dp tool row inside an expanded group. While the
 * finger is down the whole rect fills with the translucent
 * `controlFill`, **square-cornered and instant** both ways (iOS sets
 * `backgroundColor` with no animation). In a scrollable container the
 * fill appears after Compose's tap-indication delay (UIKit's
 * `delaysContentTouches` equivalent) and a drag cancels it.
 *
 * A tap plays the `Select` haptic exactly **once** (iOS `RowView.swift:274`
 * — the delegate path plays no second one) then [onToggle], which is
 * [TranscriptState.toggleDetail] with `open` **as painted** (the widget's
 * own field): two taps before the next frame land once, so the value is
 * never inverted locally.
 */
@Composable
fun ToolToggleTarget(open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val feedback = LocalFeedback.current
    val interactions = remember { MutableInteractionSource() }
    val pressed = interactions.collectIsPressedAsState()
    val fill = RobocoTheme.palette.controlFill
    Box(
        modifier
            .fillMaxSize()
            .testTag("transcript-tool-toggle")
            // Reading the State inside the draw block: a press invalidates
            // drawing only, never recomposition.
            .drawBehind { if (pressed.value) drawRect(fill) }
            .clickable(interactionSource = interactions, indication = null, role = Role.Button) {
                feedback.haptic(Haptic.Select) // UISelectionFeedbackGenerator (RowView:274)
                onToggle()
            }
            .semantics { contentDescription = if (open) "Hide details" else "Show details" },
    )
}

/**
 * The tool toggle's stateless look: nothing at rest, the whole rect in
 * `controlFill` while [pressed] — square corners, no stroke, no fade.
 * Previews, the gallery and the goldens drive it directly; production
 * presses go through [ToolToggleTarget]'s interaction source.
 */
@Composable
internal fun ToolToggleSurface(pressed: Boolean, modifier: Modifier = Modifier) {
    val fill = RobocoTheme.palette.controlFill
    Box(
        modifier
            .fillMaxSize()
            .drawBehind { if (pressed) drawRect(fill) },
    )
}

// ---------------------------------------------------------------------------
// The tool-group chevron (WidgetKind.Chevron).
// ---------------------------------------------------------------------------

/**
 * The chevron glyph: iOS `tool-alt-arrow-down` (24-unit viewBox, one "v"
 * path, stroke 1.5, round caps and joins), the same asset Roboco compiles
 * as `zi_alt_arrow_down`. `opticalScale = 1` — unlike
 * `RobocoIcons.ChevronDown` (1.1, tuned for SF `chevron.down`), the glyph
 * aspect-fits the whole 15.75 dp widget rect exactly (stroke 1.5 / 24 of
 * the rect, ≈ 0.98 dp at text scale 1) and may not overhang it.
 */
private val ToolChevronIcon = RobocoIcon.Vector(R.drawable.zi_alt_arrow_down)

/** Down (expanded, 0°) or right (collapsed, −90°). */
internal fun chevronAngle(expanded: Boolean): Float = if (expanded) 0f else -90f

/**
 * iOS `ToolChevronView.lastExpanded` (`ToolViews.swift:39-48`): the last
 * state painted per row key, process-wide, main thread only, never
 * cleared in production. Written on **every** chevron composition — under
 * reduced motion too — which is what makes a group that auto-collapsed
 * while scrolled out (Rust drops it from `live`) flip when it comes back
 * into the realized band, and a re-entered row animate its remembered
 * state. Row keys are hashes of message/part ids, so collisions across
 * sessions are practically impossible; the map grows by one entry per
 * tool group ever shown, like the iOS static. No eviction was added.
 */
internal object ToolChevronMemory {
    private val last = HashMap<ULong, Boolean>()

    /** The last state painted for [key], `null` on first sighting. */
    fun peek(key: ULong): Boolean? = last[key]

    /** Stores [expanded] and returns the previous value. */
    fun swap(key: ULong, expanded: Boolean): Boolean? = last.put(key, expanded)

    /** Empties the map; tests only (the iOS app never clears it). */
    @VisibleForTesting
    fun clearForTest() = last.clear()
}

/** Test-only semantics: the chevron's current rotation in degrees (not read by TalkBack). */
internal val ChevronAngleKey = SemanticsPropertyKey<Float>("ChevronAngle")

/**
 * `WidgetKind.Chevron`'s view (ticket 120; iOS `ToolChevronView`,
 * `ios:Transcript/ToolViews.swift:37-62`): the `zi_alt_arrow_down` glyph
 * aspect-fit into the whole widget rect, tinted `palette.secondary`,
 * pointing down (0°) when expanded and right (−90°) when collapsed,
 * rotation pivoting on the rect centre.
 *
 * When the key's painted state differs from [ToolChevronMemory]'s last
 * entry — a user tap, Rust auto-collapsing a finished group, or a row
 * re-entering the realized band — it flips over `Motion.toolChevron()`
 * (140 ms easeOut, the desktop `EASE_OUT` fold curve). A first sighting,
 * an unchanged state and Reduce Motion snap instead, and the memory is
 * still written. The flip survives row re-layouts: the [Animatable] is
 * keyed on [rowKey] only, so a new model version mid-flip (a streaming
 * group) restarts from the current angle toward the same target — smoother
 * than iOS, which rebuilds the view and snaps (see the ticket's ADAPT).
 *
 * Not interactive and not an accessibility element: taps go to the
 * `Disclosure` widget that sits on top of it, and [ChevronAngleKey] is a
 * test-only channel.
 */
@Composable
fun ToolChevron(rowKey: ULong, expanded: Boolean, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val tint = RobocoTheme.palette.secondary
    // Keyed on the row only, never on `expanded`: a flip starts from the
    // angle currently shown, and an interrupted flip restarts there toward
    // its new target. Under reduce motion the first value is already the
    // target, so there is no one-frame flash of the old angle.
    val rotation = remember(rowKey) {
        Animatable(chevronAngle(if (reduce) expanded else ToolChevronMemory.peek(rowKey) ?: expanded))
    }
    LaunchedEffect(rowKey, expanded) {
        // The memory is read in composition (above) and written here, after
        // commit — an abandoned composition never corrupts it. iOS writes
        // the static dictionary on every construction, including under
        // reduce motion (ToolViews.swift:46-48).
        val previous = ToolChevronMemory.swap(rowKey, expanded)
        val target = chevronAngle(expanded)
        if (previous != null && previous != expanded && !reduce) {
            rotation.animateTo(target, Motion.toolChevron())
        } else {
            rotation.snapTo(target)
        }
    }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .testTag("transcript-tool-chevron")
            .semantics { this[ChevronAngleKey] = rotation.value }
            // The UIView default anchor: the rect centre.
            .graphicsLayer { rotationZ = rotation.value },
    ) {
        // scaleAspectFit (ToolViews.swift:42): the glyph square is the
        // rect's shorter side, centred. Read here: the inner Box's scope is a
        // DSL-marked receiver, so the outer constraints scope's maxWidth is
        // not reachable implicitly inside it.
        val side = minOf(maxWidth, maxHeight)
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
            IconInBox(ToolChevronIcon, tint, box = side, weight = SymbolWeight.Regular)
        }
    }
}

// ---------------------------------------------------------------------------
// Previews — the debug fixture (DisclosureGallerySection.kt) is not on this
// source set's classpath, so a minimal copy of its static rows lives here.
// ---------------------------------------------------------------------------

@Preview(name = "Disclosure light")
@Composable
private fun DisclosureLightPreview() {
    RobocoTheme(Appearance.System) {
        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
            DisclosurePreviewRows()
        }
    }
}

@Preview(name = "Disclosure dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DisclosureDarkPreview() {
    RobocoTheme(Appearance.System) {
        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
            DisclosurePreviewRows()
        }
    }
}

/**
 * The static fixture's four rows at the Rust geometry (row origin x = 18,
 * content width 357): two tool headers — chevron at (6.1875, 6.75) 15.75²,
 * title at TITLE_X = 31.5 — and an idle plus a pressed 36 dp tool row with
 * its label at the rail's end (63 dp).
 */
@Composable
private fun DisclosurePreviewRows() {
    Column(
        Modifier.padding(start = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PreviewHeaderRow("Ran 3 commands", 1uL, expanded = true)
        PreviewHeaderRow("Read 2 files", 2uL, expanded = false)
        PreviewToolRow("Ran ls -la", pressed = false)
        PreviewToolRow("Ran git status", pressed = true)
    }
}

@Composable
private fun PreviewHeaderRow(title: String, key: ULong, expanded: Boolean) {
    Box(Modifier.width(357.dp).height(29.25.dp)) {
        ToolChevron(key, expanded, Modifier.absoluteOffset(6.1875.dp, 6.75.dp).size(15.75.dp))
        PreviewLabel(title, 31.5.dp)
    }
}

@Composable
private fun PreviewToolRow(label: String, pressed: Boolean) {
    Box(Modifier.width(357.dp).height(36.dp)) {
        PreviewLabel(label, 63.dp)
        ToolToggleSurface(pressed)
    }
}

/**
 * The stand-in for the text Rust paints (tickets 62/63): Geist 13.5 dp
 * fixed in `secondary`, vertically centred in the row.
 */
@Composable
private fun PreviewLabel(text: String, x: Dp) {
    Box(Modifier.absoluteOffset(x, 0.dp).fillMaxHeight().width(240.dp)) {
        BasicText(
            text,
            Modifier.align(Alignment.CenterStart),
            style = uiStyle(Face.Sans, LocalTypeScale.current.fixed(13.5f), RobocoTheme.palette.secondary),
        )
    }
}
