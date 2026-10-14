package sh.roboco.android.session

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.Motion
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassCircleButton
import sh.roboco.android.design.glass.GlassSurface
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.transcript.TranscriptViewport

/**
 * The jump-to-latest button's constants and pure rules
 * (android-ui-parity #131) — the port of SVC's `jump` circle
 * (`ios:Session/SessionViewController.swift:17, 65-69, 145-150,
 * 471-477`). Units: iOS pt → dp 1:1.
 */
object JumpToLatest {

    /** The show threshold (dp, strict `>`): `d > 140` (SVC:66). */
    const val ShowDistance = 140f

    /** The hidden spring scale (SVC:148-149). */
    const val HiddenScale = 0.6f

    /** The circle's frame: `Glass.circleButton(symbol: "arrow.down", size: 40, pointSize: 15)` (SVC:17). */
    val Size = 40.dp

    /** The SF Symbol point size (SVC:17); the weight is Medium (08's default). */
    const val IconPt = 15f

    /** The accessibility label, verbatim (SVC:147). */
    const val Label = "Jump to latest"

    /** The accessibility identifier / test tag (SVC:146). */
    const val Tag = "jump-to-latest"

    /**
     * The visibility rule (SVC:65-69): `d > 140 && !following`, strict on
     * the distance. While the own-turn runway (69) holds, the held
     * position *is* the bottom (`maxOffset`), so `d` is 0 there and this
     * needs no runway branch of its own.
     */
    fun isVisible(distanceFromBottom: Float, following: Boolean): Boolean =
        distanceFromBottom > ShowDistance && !following

    /**
     * Layer alpha: the spring progress (clamped — alpha never overshoots)
     * × the external multiplier ticket 85 feeds (1 → 0 while the session
     * pops). The multiplier never touches the spring itself.
     */
    fun alpha(p: Float, nav: Float): Float = p.coerceIn(0f, 1f) * nav.coerceIn(0f, 1f)

    /** Layer scale: `0.6 + 0.4 · p`, **not** clamped — the 1.5 % spring overshoot shows (SVC:475). */
    fun scale(p: Float): Float = HiddenScale + (1f - HiddenScale) * p
}

/**
 * The show/hide motion — the port of `setJumpVisible` (SVC:471-477): one
 * progress value `p` (0 hidden … 1 shown) drives alpha and scale on a
 * single interruptible spring, re-targeted **only when the target
 * changes** (UIKit's `(alpha > 0.5) != visible` guard — the rule fires on
 * every scroll step, and repeated identical calls never restart the
 * spring).
 *
 * A reversal mid-flight continues from the current value *and* velocity:
 * `animateTo` reads the in-flight velocity before its mutex cancels the
 * old run — `.beginFromCurrentState`. Never `snapTo` first.
 */
@Stable
internal class JumpToLatestMotion(initiallyVisible: Boolean) {

    /** 0 = hidden, 1 = shown; the overshoot passes 1 (and dips under 0). */
    val progress = Animatable(if (initiallyVisible) 1f else 0f, visibilityThreshold = 0.001f)

    /** The model value — the rule's latest result, like `jump.alpha` on iOS. */
    var target by mutableStateOf(initiallyVisible)
        private set

    /**
     * SVC:472's guard, SVC:473's animate. The visibility threshold rides in
     * the **spec** (the `Animatable` constructor's is ignored when an
     * explicit `SpringSpec` is passed), so the tail of the overshoot is not
     * cut by Compose's 0.01 default.
     */
    fun set(visible: Boolean, scope: CoroutineScope) {
        if (visible == target) return
        target = visible
        scope.launch {
            progress.animateTo(if (visible) 1f else 0f, Motion.JumpButton.spec(visibilityThreshold = 0.001f))
        }
    }
}

/**
 * The session-screen entry point: the rule read from ticket 68's viewport
 * (as a `derivedStateOf`, so only a threshold crossing recomposes — the
 * #621 trap was a state write per scrolled pixel), the tap gliding the
 * transcript back to the tail (`scrollToBottom(animated = true)`, the
 * runway glide while 69 holds). Placement belongs to ticket 74's `jump`
 * slot: pass this the plain 40 × 40 button, never an `align`/`offset`.
 *
 * @param navAlpha ticket 85's pop-fade multiplier (`1 − p` while the
 *   session pops, 1 otherwise) — folded into the layer only. 85 had not
 *   landed when this shipped; the default keeps it 1.
 */
@Composable
fun JumpToLatestButton(
    viewport: TranscriptViewport,
    modifier: Modifier = Modifier,
    navAlpha: () -> Float = { 1f },
) {
    // iOS evaluates this in `layoutSubviews` — every scroll step, every
    // applied frame, every inset change; derivedStateOf over 68's snapshot
    // state reacts to the same inputs (and to a bare `following` flip,
    // about one frame earlier than iOS's next layout pass).
    val visible = remember(viewport) {
        derivedStateOf { JumpToLatest.isVisible(viewport.distanceFromBottom, viewport.following) }
    }
    JumpToLatestButton(
        visible = visible.value,
        onClick = { viewport.scrollToBottom(animated = true) },
        modifier = modifier,
        navAlpha = navAlpha,
    )
}

/**
 * The stateless core (previews, gallery, tests): [visible] is the rule's
 * Boolean, [motion] the remembered spring state. A first composition
 * while already visible starts shown with no pop-in (a retained,
 * scrolled-up session; iOS never recreates the view, so it simply stays
 * shown).
 */
@Composable
internal fun JumpToLatestButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    navAlpha: () -> Float = { 1f },
    motion: JumpToLatestMotion = remember { JumpToLatestMotion(visible) },
) {
    val scope = rememberCoroutineScope()
    val currentVisible by rememberUpdatedState(visible)
    LaunchedEffect(motion) {
        // collect, not collectLatest: cancelling the collector must never
        // cancel the spring; the animation runs on `scope`, which survives
        // the next emission.
        snapshotFlow { currentVisible }.collect { motion.set(it, scope) }
    }

    // The 85 multiplier never restarts the spring: it is folded into the
    // layer (below) and only its "gone" threshold reaches composition.
    val currentNavAlpha = rememberUpdatedState(navAlpha)
    val navGone by remember { derivedStateOf { currentNavAlpha.value() < 0.01f } }

    // UIKit's model-value hit testing: interactivity follows the target,
    // not the animated alpha — the button is tappable from the instant it
    // starts showing and untappable from the instant it starts hiding.
    val interactive = motion.target && !navGone
    // Flips only at the end points: while hidden-settled the glass is not
    // even composed (no Haze blur pass over the scrolling transcript for
    // an invisible button). The 40 × 40 outer box keeps 74's anchor.
    val idleHidden by remember(motion) {
        derivedStateOf { !motion.target && !motion.progress.isRunning && motion.progress.value <= 0f }
    }

    Box(
        modifier
            .size(JumpToLatest.Size)
            .graphicsLayer {
                // Draw-phase reads only: the spring animates the layer
                // without recomposing the session screen.
                val p = motion.progress.value
                alpha = JumpToLatest.alpha(p, currentNavAlpha.value())
                scaleX = JumpToLatest.scale(p)
                scaleY = scaleX
                transformOrigin = TransformOrigin.Center
            }
            // Hidden or hiding: the whole subtree leaves the semantics
            // tree (UIKit: model alpha 0 is not hit-testable, and
            // VoiceOver skips it). The testTag sits inside, so
            // `onNodeWithTag` finds nothing while hidden.
            .then(if (interactive) Modifier else Modifier.clearAndSetSemantics { }),
    ) {
        if (!idleHidden) {
            GlassCircleButton(
                icon = RobocoIcons.ArrowDown,
                contentDescription = JumpToLatest.Label,
                onClick = onClick,
                modifier = Modifier.testTag(JumpToLatest.Tag),
                size = JumpToLatest.Size,
                iconPt = JumpToLatest.IconPt, // weight: 08's default Medium
                // Glass.circleButton inks the glyph Palette.text
                // (SESSIONS-D1): 08's default is the measured bar-item ink.
                ink = RobocoTheme.palette.text,
                interactive = interactive,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The shared fixture (previews, Roborazzi goldens, the gallery): the
// ticket's step-7 stage.
// ---------------------------------------------------------------------------

/** The stage's session window (412 × 300 dp, the ticket's preview frame). */
private val JumpStageWidth = 412.dp
private val JumpStageHeight = 300.dp

/** The stack stand-in: a 50 dp glass capsule, 12 dp side insets, 8 dp off the bottom. */
private val JumpStackHeight = 50.dp
private val JumpStackSideInset = 12.dp
private val JumpStackBottomGap = 8.dp

/** The backdrop's stripe height (74's stripe transcript stand-in). */
private val JumpStripeHeight = 10.dp

/**
 * The ticket's step-7 stage, shared by the previews, the Roborazzi
 * goldens and the gallery: a 412 × 300 dp window with alternating 10 dp
 * `palette.text` / `palette.accent` stripes as the haze source, 74's stack
 * stand-in (a 50 dp glass capsule, 12 dp side insets, 8 dp from the
 * bottom) and the button placed at 74's jump anchor — right edge 16 dp
 * inside the trailing edge, bottom 12 dp above the capsule's top — via
 * [BottomStackMetrics.jumpOrigin], the same arithmetic the real container
 * applies.
 */
@Composable
internal fun JumpToLatestStage(
    visible: Boolean,
    navAlpha: () -> Float = { 1f },
    motion: JumpToLatestMotion = remember { JumpToLatestMotion(visible) },
) {
    val palette = RobocoTheme.palette
    val stackTop = JumpStageHeight - JumpStackBottomGap - JumpStackHeight
    val origin = BottomStackMetrics.jumpOrigin(
        JumpStageWidth.value,
        safeR = 0f,
        stackTop = stackTop.value,
        jumpWidth = JumpToLatest.Size.value,
        jumpHeight = JumpToLatest.Size.value,
    )
    ProvideHazeState {
        Box(Modifier.size(JumpStageWidth, JumpStageHeight)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .hazeSource(LocalHazeState.current!!)
                    .drawBehind {
                        val stripe = JumpStripeHeight.toPx()
                        val colors = listOf(palette.text, palette.accent)
                        var stripeTop = 0f
                        var index = 0
                        while (stripeTop < size.height) {
                            drawRect(
                                colors[index % 2],
                                topLeft = Offset(0f, stripeTop),
                                size = Size(size.width, min(stripe, size.height - stripeTop)),
                            )
                            stripeTop += stripe
                            index++
                        }
                    },
            )
            GlassSurface(
                Modifier
                    .offset(x = JumpStackSideInset, y = stackTop)
                    .size(JumpStageWidth - JumpStackSideInset * 2, JumpStackHeight),
                shape = CapsuleShape,
            )
            JumpToLatestButton(
                visible = visible,
                onClick = {},
                modifier = Modifier.offset(x = origin.first.dp, y = origin.second.dp),
                navAlpha = navAlpha,
                motion = motion,
            )
        }
    }
}

@Preview(name = "Jump to latest light")
@Composable
private fun JumpToLatestLightPreview() {
    RobocoTheme(Appearance.Light) { JumpToLatestStage(visible = true) }
}

@Preview(name = "Jump to latest dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun JumpToLatestDarkPreview() {
    RobocoTheme(Appearance.Dark) { JumpToLatestStage(visible = true) }
}
