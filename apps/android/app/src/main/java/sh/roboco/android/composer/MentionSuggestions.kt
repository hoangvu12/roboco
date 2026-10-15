package sh.roboco.android.composer

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.Icon
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Motion
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassSurface
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.design.uiStyle
import uniffi.roboco_core.FileMatch

/**
 * The mention-suggestion card and its screen-level host
 * (android-ui-parity #86): the port of `ios:Composer/Mentions.swift:50-105`
 * plus the hosting half of `ios:Composer/ComposerBar.swift:291-302`.
 *
 * iOS puts [MentionSuggestions] in the **screen's root view**, outside the
 * composer, so no ancestor clips it or swallows its taps. Android does the
 * same with [MentionSuggestionsHost]: a screen-root wrapper that draws the
 * card as its **last (topmost) child** — the Compose form of
 * `findViewController()?.view.addSubview`. No `Popup`/`Dialog`: a separate
 * window cannot sample the screen's Haze source and would fight the IME.
 *
 * The composer registers its state with the host through
 * [LocalMentionLayerSlot] ([MentionSuggestionsBinding] does it), and the
 * glass container carries [Modifier.mentionAnchor]. The layer re-places the
 * card from the anchor's coordinates every layout pass, so it follows the
 * composer through the keyboard, the text growth and the 56 morph (with the
 * accepted one-frame lag).
 */

/**
 * The registration slot a [MentionSuggestionsBinding] writes itself into;
 * provided by [MentionSuggestionsHost], `null` outside one.
 */
internal class MentionLayerSlot {
    var state: MentionSuggestionsState? by mutableStateOf(null)
}

/** The host's slot; `null` when no [MentionSuggestionsHost] wraps the screen. */
internal val LocalMentionLayerSlot = staticCompositionLocalOf<MentionLayerSlot?> { null }

/**
 * Screen-root wrapper: the content, then the suggestion layer on top. Must
 * sit inside the screen's `ProvideHazeState` and **outside** any
 * `hazeSource` (the card blurs the transcript and must never be part of
 * its own source — ticket 07's convention).
 */
@Composable
fun MentionSuggestionsHost(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val slot = remember { MentionLayerSlot() }
    CompositionLocalProvider(LocalMentionLayerSlot provides slot) {
        Box(modifier) {
            content()
            // The last child: drawn on top and hit-tested first.
            slot.state?.let { MentionSuggestionsLayer(it, Modifier.matchParentSize()) }
        }
    }
}

// ---------------------------------------------------------------------------
// The layer: visibility animation + anchor-relative placement
// ---------------------------------------------------------------------------

/**
 * The card's overlay layer. Two jobs:
 *
 * - **Animation** (`ComposerBar.swift:351-367`): one [Animatable] per card,
 *   `animateTo` over [Motion.mentionList] (220 ms ease-out) from the
 *   **current** value — a show requested during a fade-out interrupts it,
 *   which iOS's `isHidden == visible` guard would ignore. `alpha = v`,
 *   `translationY = (1 − v) × 8 dp`. The layer composes while
 *   `visible || progress > 0`, so the rows stay on screen through a
 *   fade-out (no 8 pt sliver collapse) and disappear at its end.
 * - **Placement**: the card is as wide as the anchor (the composer glass)
 *   with its bottom 8 dp above the anchor's top, measured through
 *   `localPositionOf(anchor)` so transforms (nav parallax, sheet recession)
 *   are honoured — `boundsInRoot` subtraction would get them wrong. A
 *   24 dp margin pads the animated layer so the glass shadow (≈ 20 dp,
 *   ticket 07) is not clipped by the `graphicsLayer(alpha < 1)` offscreen
 *   buffer.
 */
@Composable
private fun MentionSuggestionsLayer(state: MentionSuggestionsState, modifier: Modifier) {
    val target = state.visible
    LaunchedEffect(target) {
        state.progress.animateTo(if (target) 1f else 0f, Motion.mentionList())
    }
    // Not progress.value directly: the derived state recomposes this scope
    // only when the layer enters/leaves, never per frame.
    val shown by remember { derivedStateOf { state.visible || state.progress.value > 0f } }
    if (!shown) return

    val margin = 24.dp // the glass-shadow margin inside the alpha layer
    val gap = 8.dp // card bottom → anchor top
    Layout(
        modifier = modifier,
        content = {
            Box(
                Modifier
                    .graphicsLayer {
                        val v = state.progress.value
                        alpha = v
                        translationY = (1f - v) * Motion.MentionListOffsetDp.dp.toPx()
                    }
                    .padding(margin),
            ) {
                MentionSuggestionsCard(state.files, enabled = target, onPick = state::pick)
            }
        },
    ) { measurables, constraints ->
        val key = state.anchorKey // snapshot read: relayout when the composer moves
        val w = key.size.width
        val mPx = margin.roundToPx()
        val p = measurables.single().measure(
            Constraints.fixedWidth(max(0, w) + 2 * mPx).copy(maxHeight = Constraints.Infinity),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            val me = coordinates // Placeable.PlacementScope.coordinates
            val anchor = state.anchorCoords
            if (w <= 0 || me == null || anchor == null || !anchor.isAttached) return@layout
            val a = me.localPositionOf(anchor, Offset.Zero)
            // The padded box's top-left: the card inside it is inset by m,
            // so the card's left lands on the anchor's left edge and its
            // bottom on the anchor's top − 8.
            val x = a.x.roundToInt() - mPx
            val y = a.y.roundToInt() - gap.roundToPx() - (p.height - mPx)
            p.place(x, y)
        }
    }
}

// ---------------------------------------------------------------------------
// The card and its rows
// ---------------------------------------------------------------------------

/**
 * The stateless card — the port of `MentionSuggestions` itself: radius 18
 * glass, a vertical stack inset 4 dp top/bottom and 0 left/right, at most
 * [MentionSuggestionsState.MaxRows] rows, no spacing, no separators, an
 * intrinsic height. Previews, screenshots and the gallery drive it
 * directly; the layer drives it through the state.
 *
 * While [enabled] (the UIKit model value: the moment a show starts, the
 * card is interactive even mid-fade-in) the card's own area — the 4 dp
 * padding included — is a hit target, so a tap there is swallowed instead
 * of reaching the transcript below. While fading out it contains no
 * pointer-input node at all, so taps fall through.
 */
@Composable
fun MentionSuggestionsCard(
    files: List<FileMatch>,
    enabled: Boolean,
    onPick: (FileMatch) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("mention-card")
            .then(
                if (enabled) {
                    // The iOS glass view swallows every tap on itself — and
                    // Compose dispatches pointer events to *all* overlapping
                    // nodes, so the card's handler must also consume the
                    // down. Without the consume, a tap on the 4 dp padding
                    // would keep falling through to the transcript below
                    // (81's tap-to-dismiss). The rows are children of this
                    // node and get the Main pass first, so their clicks
                    // still fire.
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                        }
                    }
                } else {
                    // Fading out: no semantics (VoiceOver skips it), and no
                    // pointer-input anywhere — taps fall through.
                    Modifier.clearAndSetSemantics { }
                },
            ),
        shape = Glass.shape(18.dp),
        animateVisibility = false,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            files.take(MentionSuggestionsState.MaxRows).forEachIndexed { i, f ->
                MentionSuggestionRow(f, i, enabled) { onPick(f) }
            }
        }
    }
}

/**
 * One row (`Mentions.swift:79-103`): a full-width, background-less plain
 * button — a `folder` / `doc.text` glyph at 13 pt medium, 10 dp gap, the
 * basename in Geist Medium 15 and the parent path in Geist Mono 11.5
 * underneath, 8/14/14/8 content insets, and the whole rectangle as the hit
 * target. Long names and paths wrap (no `maxLines`, no ellipsis). Pressed:
 * the content dims to alpha 0.5; no background, no ripple.
 *
 * `clickable` merges the descendants' semantics, so TalkBack reads
 * "name, dir, Button"; the glyph is decorative. No `focusable()`: in touch
 * mode a `clickable` never takes focus, so the composer field keeps it and
 * the IME stays up.
 */
@Composable
private fun MentionSuggestionRow(file: FileMatch, index: Int, enabled: Boolean, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("mention-$index")
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = src,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 8.dp)
            .graphicsLayer { alpha = if (pressed) 0.5f else 1f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (file.isDir) RobocoIcons.Folder else RobocoIcons.DocText,
            tint = palette.text,
            sizePt = 13f,
            weight = SymbolWeight.Medium,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                mentionRowName(file.path),
                style = uiStyle(Face.SansMedium, ts.fixed(15f), palette.text)
                    .copy(textAlign = TextAlign.Start),
                softWrap = true,
            )
            val dir = mentionRowDir(file.path)
            if (dir.isNotEmpty()) {
                BasicText(
                    dir,
                    style = uiStyle(Face.Mono, ts.fixed(11.5f), palette.tertiary)
                        .copy(textAlign = TextAlign.Start),
                    softWrap = true,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The shared fixture (tests, previews, screenshots, gallery)
// ---------------------------------------------------------------------------

/**
 * The ticket's fixture: five `@lay`-sized rows — four with a dir line
 * (50.45 dp each) and one without (35.5 dp) — totalling the 245.3 dp card.
 * Constructing a [FileMatch] record does not load the native library.
 */
internal object MentionFixture {
    val files = listOf(
        FileMatch("crates/ui/src/layout/mod.rs", false),
        FileMatch("crates/ui/src/layout", true),
        FileMatch("docs/reference/layout.md", false),
        FileMatch("apps/android/app/src/main/res/layout", true),
        FileMatch("LAYOUT.md", false),
    )
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

/** The preview body: the 376 dp card at the bottom of a padded page. */
@Composable
private fun MentionSuggestionsPreviewBody() {
    RobocoTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(RobocoTheme.palette.background)
                .padding(12.dp),
        ) {
            MentionSuggestionsCard(
                MentionFixture.files,
                enabled = true,
                onPick = {},
                modifier = Modifier.width(376.dp).align(Alignment.BottomCenter),
            )
        }
    }
}

@Preview(name = "Mention suggestions light", widthDp = 400, heightDp = 320)
@Composable
private fun MentionSuggestionsLightPreview() {
    MentionSuggestionsPreviewBody()
}

@Preview(name = "Mention suggestions dark", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 400, heightDp = 320)
@Composable
private fun MentionSuggestionsDarkPreview() {
    MentionSuggestionsPreviewBody()
}

// ---------------------------------------------------------------------------
// The design-gallery section (registered by 22's Gallery.kt)
// ---------------------------------------------------------------------------

/**
 * The "Mention suggestions" page of the debug design gallery
 * (android-ui-parity #86): the live harness the ticket's step 9 describes —
 * a striped `hazeSource` backdrop inside [ProvideHazeState], the host
 * around it, and at the bottom a 50 dp glass capsule carrying
 * [Modifier.mentionAnchor] with a foundation `BasicTextField` bound to a
 * [rememberComposerController]. The controller's search answers from
 * [MentionFixture] after 150 ms.
 *
 * Try it live: type `@lay` — the card rises above the capsule after the
 * 120 ms debounce; pick a row — the token is inserted and the keyboard
 * stays; the **Offline** toggle flips `controller.canWrite` (no card at all
 * while offline); Light/Dark and Force-reduce-motion apply.
 */
@Composable
fun MentionSuggestionsGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    var offline by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }

    val controller = rememberComposerController()
    remember(controller) {
        controller.mentionSearch = { q ->
            delay(150)
            MentionFixture.files.filter { it.path.contains(q, ignoreCase = true) }
        }
    }
    val mentionUi = rememberMentionSuggestionsState(controller)

    Column {
        ProvideHazeState {
            MentionSuggestionsHost(Modifier.fillMaxWidth().height(560.dp)) {
                // Inside the host: the binding registers through the host's
                // LocalMentionLayerSlot, which is null outside it.
                MentionSuggestionsBinding(mentionUi, isFocused = { focused })
                // 24 striped rows: the busy backdrop the card blurs.
                Column(Modifier.matchParentSize().hazeSource(LocalHazeState.current!!)) {
                    repeat(24) { i ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(20.dp)
                                .background(if (i % 2 == 0) palette.elevated else palette.chip),
                        )
                    }
                }
                // The composer stand-in: a 50 dp glass capsule carrying the
                // anchor, holding the field (57 owns the real one).
                GlassSurface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                        .fillMaxWidth()
                        .height(50.dp)
                        .mentionAnchor(mentionUi),
                    shape = CapsuleShape,
                    animateVisibility = false,
                ) {
                    BasicTextField(
                        value = controller.value,
                        onValueChange = { controller.value = it },
                        textStyle = uiStyle(Face.Sans, typeScale.fixed(16.5f), palette.text),
                        cursorBrush = SolidColor(palette.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = 14.dp, end = 16.dp)
                            .onFocusChanged {
                                focused = it.isFocused
                                // The binding's blur observer also fires; onBlur is idempotent.
                                if (!it.isFocused) mentionUi.onBlur()
                            },
                    )
                }
            }
        }
        Row(
            Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(
                if (offline) "Offline ✓" else "Offline",
                style = uiStyle(
                    Face.SansMedium,
                    typeScale.fixed(14f),
                    if (offline) palette.accent else palette.secondary,
                ),
                modifier = Modifier
                    .clickable {
                        offline = !offline
                        controller.canWrite = !offline
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            BasicText(
                "Type @ in the field above; tap a row to insert.",
                style = uiStyle(Face.Mono, typeScale.fixed(11f), palette.tertiary),
            )
        }
    }
}

@Preview(name = "Mention suggestions gallery light")
@Composable
private fun MentionSuggestionsGalleryLightPreview() {
    RobocoTheme(Appearance.Light) { MentionSuggestionsGallerySection() }
}

@Preview(name = "Mention suggestions gallery dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MentionSuggestionsGalleryDarkPreview() {
    RobocoTheme(Appearance.Dark) { MentionSuggestionsGallerySection() }
}
