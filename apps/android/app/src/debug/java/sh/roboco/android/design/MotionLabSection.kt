package sh.roboco.android.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.launch
import sh.roboco.android.core.Face

/**
 * One Motion-lab row (android-ui-parity #22): a name, a label computed from
 * ticket 04's tokens, and (except for timers) a dot on a track.
 */
sealed interface LabEntry {
    val name: String
    val label: String
}

/** One [Motion.allSprings] entry: `spring ζ0.86 T0.42s k365.75`. */
data class SpringEntry(val spring: IosSpring) : LabEntry {
    override val name get() = spring.name
    override val label: String
        get() = String.format(
            Locale.ROOT,
            "spring ζ%.2f T%.2fs k%.2f",
            spring.damping,
            spring.durationSec,
            spring.stiffness,
        )
}

/** One finite tween: `350ms EaseInOut`, `250ms +120ms EaseOut`. */
data class TweenEntry(override val name: String, val spec: TweenSpec<Float>) : LabEntry {
    override val label get() = MotionLab.tweenLabel(spec)
}

/** One repeating tween: `∞ 750ms Linear`. */
data class RepeatEntry(override val name: String, val spec: InfiniteRepeatableSpec<Float>) : LabEntry {
    override val label: String
        get() = (spec.animation as TweenSpec<*>).let {
            "∞ ${it.durationMillis}ms ${MotionLab.easingName(it.easing)}"
        }
}

/** One frame loop: the row runs [step] itself, in dp. */
data class LoopEntry(
    override val name: String,
    override val label: String,
    val step: (delta: Float, dtSec: Double) -> Float,
) : LabEntry

/**
 * The Motion-lab model: every entry list built from the `Motion`/`IosSpring`
 * tokens — no literal durations here, so the lab exposes a wrong token
 * instead of hiding it. Pure data, testable on the JVM.
 */
object MotionLab {

    /** Easing names by equality with the ticket-04 tokens; anything else is custom. */
    fun easingName(e: Easing): String = when {
        e == Motion.EaseInOut -> "EaseInOut"
        e == Motion.EaseOut -> "EaseOut"
        e == Motion.EaseIn -> "EaseIn"
        e == Motion.ExpoOut -> "ExpoOut"
        e == Motion.QuintOut -> "QuintOut"
        e == Motion.EaseOutQuart -> "EaseOutQuart"
        e == LinearEasing -> "Linear"
        else -> "custom"
    }

    /** `<durationMillis>ms[ +<delay>ms] <Easing>`; the delay part is omitted at 0. */
    fun tweenLabel(spec: TweenSpec<*>): String {
        val delay = if (spec.delay == 0) "" else " +${spec.delay}ms"
        return "${spec.durationMillis}ms$delay ${easingName(spec.easing)}"
    }

    fun springs(): List<SpringEntry> = Motion.allSprings.map(::SpringEntry)

    fun tweens(): List<TweenEntry> = listOf(
        TweenEntry("rootSwap", Motion.rootSwap()),
        TweenEntry("glassIn", Motion.glassIn()),
        TweenEntry("glassOut", Motion.glassOut()),
        TweenEntry("mentionList", Motion.mentionList()),
        TweenEntry("thumbRemove", Motion.thumbRemove()),
        TweenEntry("composerTextHeight", Motion.composerTextHeight()),
        TweenEntry("questionPage", Motion.questionPage()),
        TweenEntry("toastOut", Motion.toastOut()),
        TweenEntry("wallpaperFadeIn", Motion.wallpaperFadeIn()),
        TweenEntry("imageTileCrossfade", Motion.imageTileCrossfade()),
        TweenEntry("sessionReveal", Motion.sessionReveal()),
        TweenEntry("accessoryContentReturn", Motion.accessoryContentReturn()),
        TweenEntry("sidebarToggle", Motion.sidebarToggle()),
        TweenEntry("veil", Motion.veil()),
        TweenEntry("rowFadeIn", Motion.rowFadeIn()),
        TweenEntry("uploadRingIn", Motion.uploadRingIn()),
        TweenEntry("uploadRingOut", Motion.uploadRingOut()),
        TweenEntry("fold", Motion.fold()),
        TweenEntry("toolChevron", Motion.toolChevron()),
        TweenEntry("toolRowReveal", Motion.toolRowReveal()),
        TweenEntry("railConnector", Motion.railConnector()),
    )

    fun repeats(): List<RepeatEntry> = listOf(
        RepeatEntry("spinnerCycle", Motion.spinnerCycle()),
        RepeatEntry("shimmerSweep", Motion.shimmerSweep()),
    )

    /** The handoff tween set — re-derived whenever Reduce Motion flips. */
    fun handoff(reduce: Boolean): List<TweenEntry> {
        val h = Motion.handoff(reduce)
        return listOf(
            TweenEntry("handoff.dissolve", tween(durationMillis = h.dissolveMs, easing = Motion.EaseOut)),
            TweenEntry("handoff.glide", tween(durationMillis = h.glideMs, easing = Motion.EaseOutQuart)),
            TweenEntry(
                "handoff.composerFade",
                tween(durationMillis = h.composerFadeMs, delayMillis = h.composerFadeDelayMs, easing = Motion.EaseInOut),
            ),
            TweenEntry(
                "handoff.transcriptReveal",
                tween(durationMillis = h.transcriptRevealMs, delayMillis = h.transcriptRevealDelayMs, easing = Motion.EaseOut),
            ),
            TweenEntry("handoff.bubbleFade", tween(durationMillis = h.bubbleFadeMs, easing = Motion.EaseInOut)),
        )
    }

    fun loops(): List<LoopEntry> = listOf(
        LoopEntry(
            "followStep",
            String.format(Locale.ROOT, "frame loop · 1−e^(−%.0f·dt)", Motion.FollowRate),
            Motion::followStep,
        ),
        LoopEntry(
            "runwayStep",
            String.format(Locale.ROOT, "frame loop · 1−%.2f^frames", Motion.GlideRetain),
            Motion::runwayStep,
        ),
    )

    /** The timers, read from the Motion constants — never retyped. */
    val timers: List<Pair<String, Long>> = listOf(
        "ToastHoldMs" to Motion.ToastHoldMs,
        "SessionGiveUpMs" to Motion.SessionGiveUpMs,
        "CopyConfirmHoldMs" to Motion.CopyConfirmHoldMs,
        "StatusTickMs" to Motion.StatusTickMs,
        "QuestionAutoAdvanceMs" to Motion.QuestionAutoAdvanceMs,
        "MentionDebounceMs" to Motion.MentionDebounceMs,
        "RailQuietWindowMs" to Motion.RailQuietWindowMs,
        "RefreshMinMs" to Motion.RefreshMinMs,
        "RefreshMaxMs" to Motion.RefreshMaxMs,
    )
}

/**
 * The "Motion lab" gallery section (android-ui-parity #22, on ticket 04):
 * one dot row per spring, tween, repeating tween, handoff profile and frame
 * loop, plus the timers as static lines. Tap a row to run its spec; the
 * handoff rows re-derive when Force reduce motion flips, mirroring
 * `DraftHandoff.swift:59`.
 */
@Composable
fun MotionLabSection() {
    val typeScale = LocalTypeScale.current
    val reduce = LocalReduceMotion.current
    val springs = remember { MotionLab.springs() }
    val tweens = remember { MotionLab.tweens() }
    val repeats = remember { MotionLab.repeats() }
    val handoff = remember(reduce) { MotionLab.handoff(reduce) }
    val loops = remember { MotionLab.loops() }
    var playAll by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                "Tap a row to run its spec (0 → 1, tap again for 1 → 0). Handoff rows follow Force reduce motion.",
                style = uiStyle(Face.Sans, typeScale.fixed(13f), RobocoTheme.palette.secondary),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            PlayAllCapsule { playAll++ }
        }

        LabGroupHeader("Springs (${springs.size})")
        springs.forEach { entry ->
            key(entry.name) { FiniteLabRow(entry, entry.spring.spec(), playAll) }
        }

        LabGroupHeader("Tweens (${tweens.size})")
        tweens.forEach { entry ->
            key(entry.name) { FiniteLabRow(entry, entry.spec, playAll) }
        }

        LabGroupHeader("Repeating (${repeats.size})")
        repeats.forEach { entry ->
            key(entry.name) { RepeatingLabRow(entry) }
        }

        LabGroupHeader(if (reduce) "Handoff (reduced)" else "Handoff (normal)")
        handoff.forEach { entry ->
            key(entry.name) { FiniteLabRow(entry, entry.spec, playAll) }
        }

        LabGroupHeader("Frame loops (${loops.size})")
        loops.forEach { entry ->
            key(entry.name) { LoopLabRow(entry, playAll) }
        }

        LabGroupHeader("Timers")
        MotionLab.timers.forEach { (name, ms) ->
            BasicText(
                "$name $ms",
                style = uiStyle(Face.Mono, typeScale.fixed(11f), RobocoTheme.palette.secondary),
                maxLines = 1,
            )
        }
    }
}

/** Group header: SemiBold 13, secondary, 16 dp above. */
@Composable
private fun LabGroupHeader(title: String) {
    Spacer(Modifier.height(16.dp))
    BasicText(
        title,
        style = uiStyle(Face.SansSemibold, LocalTypeScale.current.fixed(13f), RobocoTheme.palette.secondary),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The "Play all" capsule (same look as an unselected segment): increments a
 * counter; every finite and frame-loop row toggles once per increment.
 * Repeating rows are not affected.
 */
@Composable
private fun PlayAllCapsule(onPlay: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Box(
        Modifier
            .height(30.dp)
            .clip(CapsuleShape)
            .background(palette.controlFill)
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onPlay)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            "Play all",
            style = uiStyle(Face.SansMedium, typeScale.fixed(13f), palette.text),
            maxLines = 1,
        )
    }
}

/** Springs, tweens and handoff rows: one `Animatable` per row. */
@Composable
private fun FiniteLabRow(entry: LabEntry, spec: AnimationSpec<Float>, playAll: Int) {
    val anim = remember(entry.name) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    fun toggle() {
        // animateTo from the current value and velocity: a tap during a run
        // retargets instead of snapping.
        scope.launch { anim.animateTo(if (anim.targetValue == 0f) 1f else 0f, spec) }
    }
    LaunchedEffect(playAll) {
        if (playAll > 0) toggle()
    }
    LabRowShell(entry, p = { anim.value }, onClick = { toggle() })
}

/** Repeating rows: a tap toggles running; Play all does not touch them. */
@Composable
private fun RepeatingLabRow(entry: RepeatEntry) {
    val anim = remember(entry.name) { Animatable(0f) }
    var running by remember(entry.name) { mutableStateOf(false) }
    LaunchedEffect(running) {
        if (running) {
            anim.snapTo(0f)
            anim.animateTo(1f, entry.spec)
        } else {
            // Stopping cancels the run and parks the dot at 0.
            anim.snapTo(0f)
        }
    }
    LabRowShell(entry, p = { anim.value }, onClick = { running = !running })
}

/** Frame-loop rows, in dp: follow-the-tail and runway retain. */
@Composable
private fun LoopLabRow(entry: LoopEntry, playAll: Int) {
    var pos by remember(entry.name) { mutableFloatStateOf(0f) }
    var target by remember(entry.name) { mutableFloatStateOf(0f) }
    var trackDp by remember(entry.name) { mutableStateOf(0f) }
    LaunchedEffect(target) {
        if (trackDp <= 0f || target == pos) return@LaunchedEffect
        var last = -1L
        while (true) {
            val now = withFrameNanos { it }
            if (last >= 0L) {
                val dt = (now - last) / 1e9
                val delta = target - pos
                if (abs(delta) < Motion.FollowSnapPx) {
                    pos = target
                    break
                }
                pos += entry.step(delta, dt)
            }
            last = now
        }
    }
    fun toggle() {
        target = if (target == 0f) trackDp else 0f
    }
    LaunchedEffect(playAll) {
        if (playAll > 0) toggle()
    }
    LabRowShell(
        entry,
        p = { if (trackDp > 0f) pos / trackDp else 0f },
        onClick = { toggle() },
        onTrackSized = { trackDp = it },
    )
}

/** Row geometry: height 44, a 184 dp label column, the track to the end. */
@Composable
private fun LabRowShell(
    entry: LabEntry,
    p: () -> Float,
    onClick: () -> Unit,
    onTrackSized: ((Float) -> Unit)? = null,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Run ${entry.name}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(184.dp)) {
            BasicText(
                entry.name,
                style = uiStyle(Face.SansMedium, typeScale.fixed(13f), palette.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                entry.label,
                style = uiStyle(Face.Mono, typeScale.fixed(11f), palette.secondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LabTrack(Modifier.weight(1f), p, onTrackSized)
    }
}

/**
 * The track: a 2 dp hairline, 2×10 dp tertiary ticks at the dot-centre
 * positions for p = 0 and p = 1, and the 12 dp accent dot whose leading edge
 * sits at `x = p · (trackWidth − 12 dp)` — so its centre lands on the ticks.
 * `p` is **not** clamped — under-damped springs overshoot, and that must be
 * visible.
 */
@Composable
private fun LabTrack(modifier: Modifier, p: () -> Float, onTrackSized: ((Float) -> Unit)?) {
    val palette = RobocoTheme.palette
    val density = LocalDensity.current
    Canvas(
        modifier
            .fillMaxHeight()
            .padding(end = 12.dp)
            .onSizeChanged { s ->
                if (onTrackSized != null) {
                    // The loop rows work in dp: report the dot's travel length.
                    val travelPx = with(density) { s.width - 12.dp.toPx() }
                    onTrackSized(travelPx / density.density)
                }
            },
    ) {
        val w = size.width
        val cy = size.height / 2f
        val r = 6.dp.toPx()
        val travel = w - 12.dp.toPx()
        drawLine(palette.hairline, Offset(0f, cy), Offset(w, cy), strokeWidth = 2.dp.toPx())
        val tickW = 2.dp.toPx()
        val tickH = 10.dp.toPx()
        for (cx in listOf(r, travel + r)) {
            drawRect(
                palette.tertiary,
                topLeft = Offset(cx - tickW / 2f, cy - tickH / 2f),
                size = Size(tickW, tickH),
            )
        }
        // x = p·travel is the dot's leading edge, so its centre lands exactly
        // on the ticks at p = 0 and p = 1. p is **not** clamped — under-damped
        // springs overshoot, and that must be visible.
        drawCircle(palette.accent, radius = r, center = Offset(p() * travel + r, cy))
    }
}
