package sh.roboco.android.composer

import android.content.res.Configuration
import androidx.compose.animation.VectorConverter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.abs
import kotlinx.coroutines.launch
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GlassStage
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.Icon
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.Motion
import sh.roboco.android.design.RobocoIcon
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.design.glass.rememberGlassMenuState

/**
 * The composer's trailing action button (android-ui-parity #58): the
 * `[↑]` of `ios:Composer/ComposerBar.swift`'s `actionButton`
 * (`:94`, `:197-201`, `:237-238`, `:572-674`) — one round control whose
 * look follows ticket 54's [ActionState]:
 *
 * - **send** — `arrow.up` 15 bold, white on `accent` when there is
 *   something to send, `tertiary` on `controlFill` when empty;
 * - **queue** — the same arrow-on-accent look while a turn runs with
 *   content (the long-press menu is the only extra affordance);
 * - **stop** — `stop.fill` 11 bold, `background` on `text`, while a turn
 *   runs with an empty composer;
 * - **offline** (Roboco) — `canWrite == false` draws the disabled **send**
 *   look for every action, whatever is running.
 *
 * A change of the *visual* action animates the fill and ink with
 * [Motion.ActionButton] (UIKit 0.32 s / ζ 0.8, `:613`), swaps the glyph
 * instantly and gives the new glyph a one-shot 0.85 → 1 scale bounce
 * (the port of `addSymbolEffect(.bounce.byLayer, .nonRepeating)`).
 * `enabled`-only changes (typing the first character), `steering`,
 * `canSteer` and theme switches snap — only an action change animates,
 * exactly like `refreshAction`'s `changed` check (`:608`).
 *
 * The button owns **rendering and gestures only**: a tap calls [54]'s
 * `primaryAction()` ([onPrimary] — it fires the Stop haptic and routes
 * the queue delivery); the menu rows call [54]'s `send(mode)`
 * ([onSend] — it fires the Confirm haptic). This button fires exactly one
 * haptic of its own: [Haptic.LongPress] when the delivery menu opens
 * (UIKit plays that one for free, `:633`).
 *
 * The long press works only in the queue state: it opens the "While the
 * agent works" glass menu (ticket 15, presented through
 * [rememberGlassMenuState] from the button's own `detectTapGestures`
 * handler, not `Modifier.glassMenu` — the button has its own click
 * handling and its disabled pass-through is not specified). In the send
 * and stop states a long hold is just a slow tap (`:276-285`: a release
 * inside an enabled `UIControl` still fires `.touchUpInside`).
 */
// ---------------------------------------------------------------------------

/**
 * Test-only: the glyph bounce's current scale, exposed on the merged
 * `composer-send` node (the GlassMenuProgressKey convention: an invisible
 * child recomposes alone, so the per-frame value never re-runs the button
 * itself). Production code never reads it.
 */
val ActionButtonScaleKey = SemanticsPropertyKey<Float>("ActionButtonScale")

/** The fill colour role of each look (`Palette` roles, ticket 01). */
enum class ActionFill { Accent, ControlFill, Text }

/** The glyph colour role of each look (white is a literal, like UIKit). */
enum class ActionInk { White, Tertiary, Background }

/**
 * Everything drawn and exposed by the button, a pure function of ticket
 * 54's [ActionState]: the *visual* action ([ComposerAction.Send] whenever
 * `!state.enabled`, so the empty composer and the offline engine render
 * the same disabled-send look), the glyph and its point size, the colour
 * roles, the a11y label and whether the long-press menu exists.
 */
@Immutable
data class ActionButtonLook(
    /** What is drawn: [ComposerAction.Send] whenever `!state.enabled`. */
    val visual: ComposerAction,
    /** [RobocoIcons.ArrowUp] or [RobocoIcons.StopFill]. */
    val icon: RobocoIcon,
    /** 15 pt (send/queue/offline) or 11 pt (stop). */
    val iconPt: Float,
    val fill: ActionFill,
    val ink: ActionInk,
    val enabled: Boolean,
    val a11yLabel: String,
    /** The long-press delivery menu: queue state with `canWrite`. */
    val hasMenu: Boolean,
)

/**
 * The States table of the ticket, from [ActionState] only — this never
 * re-derives `hasContent` or `canWrite`; 54's
 * [ComposerController.deriveAction] owns the derivation.
 *
 * `visual = if (state.enabled) state.action else Send`: `!enabled` happens
 * in exactly two cases (the empty idle composer and `canWrite == false`),
 * and both render the same disabled-send look with the label
 * `Send message` — the label must match the drawn arrow (79 §Spec).
 */
fun actionButtonLook(state: ActionState): ActionButtonLook {
    val visual = if (state.enabled) state.action else ComposerAction.Send
    val a11yLabel = if (state.enabled) state.accessibilityLabel else "Send message"
    return when (visual) {
        ComposerAction.Send -> ActionButtonLook(
            visual = visual,
            icon = RobocoIcons.ArrowUp,
            iconPt = ActionButtonTokens.SendIconPt,
            fill = if (state.enabled) ActionFill.Accent else ActionFill.ControlFill,
            ink = if (state.enabled) ActionInk.White else ActionInk.Tertiary,
            enabled = state.enabled,
            a11yLabel = a11yLabel,
            hasMenu = false,
        )
        // Queue keeps send's look (the menu still offers steer /
        // stop-and-send); only the a11y label says Queue or Steer.
        ComposerAction.Queue -> ActionButtonLook(
            visual = visual,
            icon = RobocoIcons.ArrowUp,
            iconPt = ActionButtonTokens.SendIconPt,
            fill = ActionFill.Accent,
            ink = ActionInk.White,
            enabled = state.enabled,
            a11yLabel = a11yLabel,
            hasMenu = state.deliveryMenu,
        )
        ComposerAction.Stop -> ActionButtonLook(
            visual = visual,
            icon = RobocoIcons.StopFill,
            iconPt = ActionButtonTokens.StopIconPt,
            fill = ActionFill.Text,
            ink = ActionInk.Background,
            enabled = state.enabled,
            a11yLabel = a11yLabel,
            hasMenu = false,
        )
    }
}

/**
 * The "While the agent works" menu (`ios:Composer/ComposerBar.swift:624-632`):
 * Queue for next turn, Steer now (only when the harness supports it, with
 * the "Deliver into the running turn" subtitle) and the destructive Stop
 * and send. Pure — built when the long press fires, so `canSteer` is
 * always current; [onSend] is ticket 54's `send(mode)`.
 */
fun deliveryMenu(canSteer: Boolean, onSend: (DeliveryMode) -> Unit): Menu = Menu(
    title = ActionButtonTokens.MenuTitle,
    children = listOfNotNull(
        MenuItem(
            title = "Queue for next turn",
            icon = MenuIcon.Symbol(RobocoIcons.QueueForNextTurn),
            testTag = "composer-delivery-queue",
            action = { onSend(DeliveryMode.Queue) },
        ),
        if (canSteer) {
            MenuItem(
                title = "Steer now",
                subtitle = "Deliver into the running turn",
                icon = MenuIcon.Symbol(RobocoIcons.ArrowTurnDownRight),
                testTag = "composer-delivery-steer",
                action = { onSend(DeliveryMode.Steer) },
            )
        } else {
            null
        },
        MenuItem(
            title = "Stop and send",
            icon = MenuIcon.Symbol(RobocoIcons.StopCircle),
            destructive = true,
            testTag = "composer-delivery-interrupt",
            action = { onSend(DeliveryMode.Interrupt) },
        ),
    ),
)

/**
 * The measured numbers of the button, all `ios:Composer/ComposerBar.swift`:
 * height 34 (`:237`, `Self.control`), width ≥ 34 (`:238`; an image-only
 * configuration with zero insets makes it exactly 34 in every state),
 * 15 pt bold `arrow.up` / 11 pt bold `stop.fill` (`:604`), the 0.85 → 1
 * bounce (this ticket's port of `.bounce`), the pressed alpha (UIKit's
 * automatic highlight approximation) and the menu title copy.
 */
object ActionButtonTokens {
    /** `actionButton.height == 34`, `actionButton.width >= 34` (`:237-238`). */
    val Size = 34.dp

    /** `arrow.up` point size (`:604`). */
    const val SendIconPt = 15f

    /** `stop.fill` point size (`:604`). */
    const val StopIconPt = 11f

    /** The glyph's bounce start scale (`addSymbolEffect(.bounce)` port). */
    const val BounceFrom = 0.85f

    /** UIKit's automatic highlighted look for `.filled()` buttons. */
    const val PressedAlpha = 0.75f

    /** The long-press menu's title copy (`:629`). */
    const val MenuTitle = "While the agent works"
}

// ---------------------------------------------------------------------------
// The button
// ---------------------------------------------------------------------------

/**
 * The action button itself. [state] is ticket 54's `controller.actionState`
 * (the controller overload below passes it for you); [onPrimary] is 54's
 * `primaryAction()` — it fires the Stop haptic before `onStop` and routes
 * the queue delivery, so this button never fires those haptics itself;
 * [onSend] is 54's `send(mode)`, called by the delivery menu rows.
 *
 * A tap is always consumed (an enabled or disabled `UIControl` never
 * focuses the text field, `ComposerBar.swift:276-285`): the detector stays
 * installed while disabled and simply runs nothing.
 *
 * Needs a [GlassMenuHost] above (the queue state opens menus through
 * [rememberGlassMenuState]); previews, the gallery and tests wrap their
 * content in one.
 *
 * @param modifier stays **first** in the chain: ticket 78 passes 56's
 *   `Modifier.composerControl(morph)` as the outermost modifier, so a tap
 *   on the button never triggers the surface's tap-to-focus
 */
@Composable
fun ComposerActionButton(
    state: ActionState,
    onPrimary: () -> Unit,
    onSend: (DeliveryMode) -> Unit,
    modifier: Modifier = Modifier,
    canSteer: Boolean = false,
) {
    val look = actionButtonLook(state)
    val fillTarget = look.fill.color()
    val inkTarget = look.ink.color()

    // ── animated state ─────────────────────────────────────────────────
    // The fill and ink spring only on a visual-action change; everything
    // else snaps. The Animatables start at their targets, so the first
    // composition never animates (`refreshAction(animated: false)` at
    // build, `:273`).
    val fill = remember { Animatable(fillTarget, Color.VectorConverter(fillTarget.colorSpace)) }
    val ink = remember { Animatable(inkTarget, Color.VectorConverter(inkTarget.colorSpace)) }
    val scale = remember { Animatable(1f) }
    var shown by remember { mutableStateOf(look.visual) }

    LaunchedEffect(look.visual, fillTarget, inkTarget) {
        if (look.visual != shown) {
            shown = look.visual
            // Only an action change animates (fact 1: `changed` compares
            // the action, not the look). A new change mid-flight cancels
            // these and the new animateTo continues from the current
            // values (`beginFromCurrentState`, `:615`).
            val spec = Motion.ActionButton.spec<Color>()
            launch { fill.animateTo(fillTarget, spec) }
            launch { ink.animateTo(inkTarget, spec) }
            launch {
                // The glyph swaps instantly and pops in from slightly
                // smaller — `addSymbolEffect(.bounce.byLayer, .nonRepeating)`
                // has no app-defined numbers, so this is the port.
                scale.snapTo(ActionButtonTokens.BounceFrom)
                scale.animateTo(1f, Motion.ActionButton.spec())
            }
        } else {
            // Enabled-only changes (send empty ↔ send with content),
            // steering/canSteer and theme switches snap; a bounce still
            // in flight (a theme switch landed inside it) keeps settling
            // from its current scale rather than sticking below 1.
            fill.snapTo(fillTarget)
            ink.snapTo(inkTarget)
            if (abs(scale.value - 1f) > 0.0001f) {
                scale.animateTo(1f, Motion.ActionButton.spec())
            }
        }
    }

    // ── gestures and the menu ──────────────────────────────────────────
    val feedback = LocalFeedback.current
    val menuState = rememberGlassMenuState()
    // The pointerInput block restarts only on (enabled, hasMenu) changes,
    // so the callbacks it captures must always see the current values.
    val onPrimaryCurrent by rememberUpdatedState(onPrimary)
    val onSendCurrent by rememberUpdatedState(onSend)
    val canSteerCurrent by rememberUpdatedState(canSteer)
    var pressed by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(Rect.Zero) } // window coordinates

    fun tap() {
        // Disabled (empty composer, offline engine): consumed, nothing
        // runs — no callback, no haptic, no focus change.
        if (!look.enabled) return
        onPrimaryCurrent()
    }

    fun openMenu() {
        // Built when the long press fires — never during composition — so
        // `canSteer` is current; the haptic fires only if it actually
        // opens (ticket 15's convention, the moment the menu opens).
        val menu = deliveryMenu(canSteerCurrent, onSendCurrent)
        if (menuState.open(anchor, menu)) {
            feedback.haptic(Haptic.LongPress)
        }
    }

    Box(
        modifier
            .height(ActionButtonTokens.Size)
            .widthIn(min = ActionButtonTokens.Size)
            .onGloballyPositioned { anchor = it.boundsInWindow() }
            // The press look is a layer alpha (draw-phase only, no
            // recomposition); disabled buttons show none.
            .graphicsLayer {
                alpha = if (pressed && look.enabled) ActionButtonTokens.PressedAlpha else 1f
            }
            // The capsule fill: drawBehind reads the Animatable in the
            // draw phase, so the spring never re-lays-out or recomposes.
            .drawBehind {
                drawRoundRect(
                    color = fill.value,
                    cornerRadius = CornerRadius(size.height / 2f),
                )
            }
            .pointerInput(look.enabled, look.hasMenu) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { tap() },
                    // No menu in this state: a long hold stays a slow tap
                    // on release (`:276-285`, fact 7). With a menu, the
                    // release after the long press is not a tap.
                    onLongPress = if (look.hasMenu) {
                        { openMenu() }
                    } else {
                        null
                    },
                )
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = look.a11yLabel
                if (!look.enabled) disabled()
                // The same tap handler through the a11y path; a no-op when
                // disabled, but it always returns true.
                onClick(label = null) { tap(); true }
                if (look.hasMenu) {
                    // TalkBack reaches Steer and Stop-and-send through here.
                    onLongClick(label = "Show menu") { openMenu(); true }
                }
            }
            .testTag("composer-send"),
        contentAlignment = Alignment.Center,
    ) {
        // The glyph carries the (non-merged) box tag the geometry tests
        // read with useUnmergedTree = true, like the chip's `chip-icon`;
        // its description stays null — the button carries the label.
        Icon(
            look.icon,
            tint = ink.value,
            sizePt = look.iconPt,
            weight = SymbolWeight.Bold,
            modifier = Modifier
                .testTag("composer-send-glyph")
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
        )
        BounceScaleSemantics(scale)
    }
}

/**
 * Convenience overload for the composer assembly (56 / 78 / 93): the plain
 * overload over the controller's derived [ActionState], `primaryAction()`
 * and `send(mode)`, with `controller.canSteer` for the menu's Steer row.
 * Passing `controller.onStop()` straight here instead would skip 54's
 * Stop haptic — never do that.
 */
@Composable
fun ComposerActionButton(controller: ComposerController, modifier: Modifier = Modifier) {
    ComposerActionButton(
        state = controller.actionState,
        onPrimary = { controller.primaryAction() },
        onSend = { controller.send(it) },
        modifier = modifier,
        canSteer = controller.canSteer,
    )
}

/** The test-only bounce scale, isolated so its per-frame recomposition never re-runs the button. */
@Composable
private fun BounceScaleSemantics(scale: Animatable<Float, AnimationVector1D>) {
    val value = scale.value
    Box(Modifier.semantics { this[ActionButtonScaleKey] = value })
}

// ---------------------------------------------------------------------------
// Colour roles (resolved against the current palette at composition time)
// ---------------------------------------------------------------------------

/** [ActionFill] → [Color]: `accent`, `controlFill`, `text` from the palette. */
@Composable
private fun ActionFill.color(): Color = when (this) {
    ActionFill.Accent -> RobocoTheme.palette.accent
    ActionFill.ControlFill -> RobocoTheme.palette.controlFill
    ActionFill.Text -> RobocoTheme.palette.text
}

/** [ActionInk] → [Color]: literal white, `tertiary`, `background`. */
@Composable
private fun ActionInk.color(): Color = when (this) {
    ActionInk.White -> Color.White
    ActionInk.Tertiary -> RobocoTheme.palette.tertiary
    ActionInk.Background -> RobocoTheme.palette.background
}

// ---------------------------------------------------------------------------
// The fixture row: previews, the gallery section and the Roborazzi goldens
// ---------------------------------------------------------------------------

/**
 * The five visual states (with their gallery captions): send empty, send,
 * queue, stop and the offline engine. `Offline` is the running composer
 * with `canWrite == false` — the grey arrow replaces whatever the action
 * says. The queue state carries `deliveryMenu = true`, so the fixture row
 * can open the delivery menu.
 */
internal val ActionButtonFixtureStates: List<Pair<String, ActionState>> = listOf(
    "Send (empty)" to ActionState(ComposerAction.Send, enabled = false, steering = false, deliveryMenu = false),
    "Send" to ActionState(ComposerAction.Send, enabled = true, steering = false, deliveryMenu = false),
    "Queue" to ActionState(ComposerAction.Queue, enabled = true, steering = false, deliveryMenu = true),
    "Stop" to ActionState(ComposerAction.Stop, enabled = true, steering = false, deliveryMenu = false),
    "Offline" to ActionState(ComposerAction.Stop, enabled = false, steering = false, deliveryMenu = false),
)

/**
 * The five-state row — 34 dp buttons 16 dp apart — shared by the previews,
 * the screenshot goldens and (with captions) the gallery section. Purely
 * visual: the callbacks do nothing; the queue button opens the real
 * delivery menu when [canSteer] is true (it is for the goldens).
 */
@Composable
internal fun ComposerActionButtonFixtureRow(
    canSteer: Boolean = true,
    onPrimary: () -> Unit = {},
    onSend: (DeliveryMode) -> Unit = {},
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((_, state) in ActionButtonFixtureStates) {
            ComposerActionButton(state, onPrimary, onSend, canSteer = canSteer)
        }
    }
}

// ---------------------------------------------------------------------------
// Previews (step 7): the five states on the gallery backdrop, light + dark
// ---------------------------------------------------------------------------

@Preview(name = "Action button light")
@Composable
private fun ComposerActionButtonLightPreview() {
    ComposerActionButtonPreviewBody(Appearance.Light)
}

@Preview(name = "Action button dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ComposerActionButtonDarkPreview() {
    ComposerActionButtonPreviewBody(Appearance.Dark)
}

@Composable
private fun ComposerActionButtonPreviewBody(appearance: Appearance) {
    RobocoTheme(appearance) {
        GlassMenuHost(hazeState = rememberHazeState()) {
            GlassStage(height = 160.dp) {
                ComposerActionButtonFixtureRow()
            }
        }
    }
}
