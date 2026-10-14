package sh.roboco.android.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** ωT at which a critically damped spring's tail (1 + ωT)·e^(−ωT) reaches 0.001. */
private const val CriticalSettle = 9.2334

/**
 * A UIKit `usingSpringWithDamping` spring carried as data.
 *
 * [durationSec] is UIKit's *total* animation duration T (seconds) and [damping]
 * is the damping ratio ζ; every UIKit call site in the app passes
 * `initialSpringVelocity: 0`, which is Compose's default too.
 */
@Immutable
data class IosSpring(val name: String, val durationSec: Double, val damping: Float) {

    /**
     * Settle-matched stiffness (decision FOUNDATION-D1): ω chosen so the motion
     * is within 0.1 % at T, with mass 1 and zero initial velocity.
     * ζ < 1: ω = ln(1000) / (ζ·T). ζ ≥ 1: ω = 9.2334 / T, the root of
     * (1 + ωT)·e^(−ωT) = 0.001 (the critically damped tail).
     *
     * Never paste the resulting number elsewhere: consumer tickets go through
     * this property or the `Motion` spring tokens.
     */
    val stiffness: Float
        get() {
            val omega = naturalFrequency
            return (omega * omega).toFloat()
        }

    /**
     * The settle-matched natural frequency ω (rad/s) behind [stiffness]:
     * for consumers that step the spring ODE themselves (the transcript
     * bounce) instead of building a Compose spring.
     */
    val naturalFrequency: Double
        get() = if (damping < 1f) ln(1000.0) / (damping * durationSec) else CriticalSettle / durationSec

    /** The Compose spring with this damping ratio and stiffness. */
    fun <T> spec(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(dampingRatio = damping, stiffness = stiffness, visibilityThreshold = visibilityThreshold)
}

/**
 * The DraftHandoff flight's timing set (`ios:Session/DraftHandoff.swift:59-131,162-174`):
 * one normal profile and one reduced profile, selected by Reduce Motion.
 */
@Immutable
data class HandoffTimings(
    val startDelayMs: Long,
    val dissolveMs: Int,
    val dissolveScale: Float,
    val glideMs: Int,
    val composerFadeMs: Int,
    val composerFadeDelayMs: Int,
    val transcriptRevealMs: Int,
    val transcriptRevealDelayMs: Int,
    val bubbleSpring: IosSpring,
    val bubbleFadeMs: Int,
    val bubblePollMs: Long,
    val bubblePollTries: Int,
)

/**
 * Every spring, easing curve, duration, delay and timer the iOS app uses
 * (ticket 04). The single source of motion: consumer tickets reference these
 * names, never literal numbers. Every value was read from
 * `upstream/main:apps/ios/Zeron/`; each token's KDoc names its site.
 */
object Motion {

    // ----------------------------------------------------------------- curves

    /** UIKit `.curveEaseInOut`, also `UIView.animate`'s default curve. */
    val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** Desktop `EASE_OUT` — folds, chevrons. ios:Transcript/ToolViews.swift:5 */
    val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    /** UIKit `.curveEaseIn`; no iOS call site today, defined for completeness. */
    val EaseIn = CubicBezierEasing(0.42f, 0f, 1f, 1f)

    /** Desktop `EASE_OUT_EXPO` — the new tool row height reveal. ios:Transcript/ToolViews.swift:7-9 */
    val ExpoOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** Desktop `EASE_OUT_QUINT` — the connector draw. ios:Transcript/ToolViews.swift:10-11 */
    val QuintOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    /**
     * The exact quartic polynomial `1 − (1 − t)⁴`, not a bezier approximation
     * (Compose's own `EaseOutQuart` is a bezier). ios:Session/DraftHandoff.swift:164-165
     */
    val EaseOutQuart: Easing = Easing { fraction ->
        val u = 1f - fraction
        1f - u * u * u * u
    }

    /** `easeOutQuart` for frame loops: `1 − (1 − t)⁴`. ios:Session/DraftHandoff.swift:164-165 */
    fun easeOutQuart(t: Double): Double {
        val u = 1 - t
        return 1 - u * u * u * u
    }

    /** cubic-bezier(0.22, 1, 0.36, 1) at `t` (0…1). ios:Transcript/ToolViews.swift:17-19 */
    fun quintOut(t: Double): Double = bezier(t, 0.22, 1.0, 0.36, 1.0)

    /**
     * The Newton bezier solver, ported line for line from
     * ios:Transcript/ToolViews.swift:21-34: solve x(s) = t, return y(s).
     */
    internal fun bezier(t: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
        fun coord(s: Double, a: Double, b: Double): Double =
            3 * (1 - s) * (1 - s) * s * a + 3 * (1 - s) * s * s * b + s * s * s

        var s = t
        for (unused in 0 until 8) {
            val x = coord(s, x1, x2) - t
            val dx = 3 * (1 - s) * (1 - s) * x1 + 6 * (1 - s) * s * (x2 - x1) + 3 * s * s * (1 - x2)
            if (abs(dx) < 1e-6) break
            s = min(1.0, max(0.0, s - x / dx))
        }
        return coord(s, y1, y2)
    }

    // -------------------------------------------------------- the iOS Motion enum
    // ios:Transcript/ToolViews.swift:3-35.

    /** Fold / chevron flip, with [EaseOut]. ios:Transcript/ToolViews.swift:4 */
    const val FoldMs = 140

    /** New tool row height reveal, with [ExpoOut]. ios:Transcript/ToolViews.swift:6 */
    const val RowRevealMs = 360

    /** Connector draw, progress `quintOut` of linear t. ios:Transcript/ToolViews.swift:10 */
    const val ConnectorMs = 480

    /** Per-row start offset of new rail rows. ios:Transcript/ToolViews.swift:13 */
    const val StaggerMs = 65

    /** First-row lead-in of a brand-new group. ios:Transcript/ToolViews.swift:14 */
    const val LeadInMs = 90

    // ----------------------------------------------------------------- springs
    // UIKit `usingSpringWithDamping`, initialSpringVelocity 0, settle-matched
    // per FOUNDATION-D1. Each stiffness is computed, never pasted.

    /** Composer capsule ⇄ card morph. ios:Composer/ComposerBar.swift:333 (consumer: 56) */
    val ComposerMorph = IosSpring("composerMorph", 0.42, 0.86f)

    /** Action button send/queue/stop config swap. ios:Composer/ComposerBar.swift:613 (58) */
    val ActionButton = IosSpring("actionButton", 0.32, 0.80f)

    /** Toast rise from translationY 16. ios:Design/Toast.swift:95 (14) */
    val ToastRise = IosSpring("toastRise", 0.40, 0.85f)

    /** Handoff bubble flight to its transcript frame. ios:Session/DraftHandoff.swift:106 (95) */
    val HandoffBubble = IosSpring("handoffBubble", 0.50, 0.86f)

    /** The same flight under Reduce Motion. ios:Session/DraftHandoff.swift:106 (95) */
    val HandoffBubbleReduced = IosSpring("handoffBubbleReduced", 0.20, 0.86f)

    /** Jump-to-latest button: alpha 0↔1, scale 0.6↔1. ios:Session/SessionViewController.swift:473 (131) */
    val JumpButton = IosSpring("jumpButton", 0.35, 0.80f)

    /** Bottom stack structural reflow. ios:Session/SessionViewController.swift:517 (74) */
    val BottomStackReflow = IosSpring("bottomStackReflow", 0.38, 0.86f)

    /** Accessory relayout; voice-only on iOS, kept for parity with no consumer. ios:Shell/MainTabController.swift:353 */
    val AccessoryRelayout = IosSpring("accessoryRelayout", 0.35, 0.90f)

    /** Section header chevron rotation 0 ↔ −π/2. ios:Threads/SessionCell.swift:284 (39) */
    val SectionChevron = IosSpring("sectionChevron", 0.30, 0.85f)

    /** Tab bar minimize-on-scroll morph. ios:Shell/MainTabController.swift:22 (system minimize; values from android-app#19) */
    val TabBarMinimize = IosSpring("tabBarMinimize", 0.40, 0.85f)

    /**
     * Interactive IME dismiss settle (ticket 133): the standard iOS
     * keyboard animation duration (`UIKeyboardAnimationDurationUserInfoKey`,
     * 0.25 s) mapped by the settle-matched rule's critically damped
     * branch, ζ ≥ 1: `(9.2334 / 0.25)² = 1364.09`. Carries the release
     * velocity; never oscillates, but can overshoot once toward the
     * target, so each step is clamped to `[hidden, shown]`.
     */
    val ImeSettle = IosSpring("imeSettle", 0.25, 1f)

    /** Every spring of ticket 04's Motion table, in table order (MotionTest pins
     * this list). Later tickets' springs stay out and are pinned by their own
     * tickets' tests — except ui-parity#28's [TabBarMinimize] and
     * ui-parity#133's [ImeSettle], which their tickets explicitly add here
     * (18's AlertScaleIn remains out).
     */
    val allSprings: List<IosSpring> = listOf(
        ComposerMorph,
        ActionButton,
        ToastRise,
        HandoffBubble,
        HandoffBubbleReduced,
        JumpButton,
        BottomStackReflow,
        AccessoryRelayout,
        SectionChevron,
        TabBarMinimize,
        ImeSettle,
    )

    // ------------------------------------------------------------ tween tokens
    // `tween()` defaults to FastOutSlowInEasing (0.4, 0, 0.2, 1), which is not
    // an iOS curve, so every factory names its easing. A tween with no curve
    // option in UIKit means easeInOut.

    /** Root swap cross-dissolve. ios:App/SceneDelegate.swift:99 */
    const val RootSwapMs = 350
    fun <T> rootSwap(): TweenSpec<T> = tween(RootSwapMs, easing = EaseInOut)

    /** Glass materialize. ios:Design/Glass.swift:35 */
    const val GlassInMs = 350
    fun <T> glassIn(): TweenSpec<T> = tween(GlassInMs, easing = EaseInOut)

    /** Glass dematerialize. ios:Design/Glass.swift:35 */
    const val GlassOutMs = 250
    fun <T> glassOut(): TweenSpec<T> = tween(GlassOutMs, easing = EaseInOut)

    /** Mention list rise (alpha 0→1, translationY 8→0), same timing in reverse on hide. ios:Composer/ComposerBar.swift:356-367 */
    const val MentionListMs = 220
    const val MentionListOffsetDp = 8f
    fun <T> mentionList(): TweenSpec<T> = tween(MentionListMs, easing = EaseOut)

    /** Staged thumbnail removed. ios:Composer/ComposerBar.swift:502 */
    const val ThumbRemoveMs = 250
    fun <T> thumbRemove(): TweenSpec<T> = tween(ThumbRemoveMs, easing = EaseInOut)

    /** Composer text height change (> 0.5 pt). ios:Composer/ComposerBar.swift:557 */
    const val ComposerTextHeightMs = 180
    fun <T> composerTextHeight(): TweenSpec<T> = tween(ComposerTextHeightMs, easing = EaseInOut)

    /** Question page cross-fade. ios:Composer/QuestionPanel.swift:189 */
    const val QuestionPageMs = 200
    fun <T> questionPage(): TweenSpec<T> = tween(QuestionPageMs, easing = EaseInOut)

    /** Toast dematerialize and sink to translationY 12. ios:Design/Toast.swift:108-111 */
    const val ToastOutMs = 250
    const val ToastRiseFromDp = 16f
    const val ToastOutToDp = 12f
    fun <T> toastOut(): TweenSpec<T> = tween(ToastOutMs, easing = EaseInOut)

    /** Late first-render wallpaper fade-in. ios:Design/Wallpaper.swift:237 */
    const val WallpaperFadeInMs = 120
    fun <T> wallpaperFadeIn(): TweenSpec<T> = tween(WallpaperFadeInMs, easing = EaseInOut)

    /** Transcript image tile cross-fade. ios:Session/CoreSessionSource.swift:280 */
    const val ImageTileCrossfadeMs = 200
    fun <T> imageTileCrossfade(): TweenSpec<T> = tween(ImageTileCrossfadeMs, easing = EaseInOut)

    /** Session reveal: list alpha 1, loader alpha 0. ios:Session/SessionViewController.swift:273 */
    const val SessionRevealMs = 180
    fun <T> sessionReveal(): TweenSpec<T> = tween(SessionRevealMs, easing = EaseOut)

    /** Accessory content fade after leaving Search (0.12 s delayed). ios:Shell/MainTabController.swift:72-80 */
    const val AccessoryContentReturnMs = 250
    const val AccessoryContentReturnDelayMs = 120
    // Named arguments: NoPlatformEasingTest requires every tween call to name
    // its easing, and a positional third argument hides it from the scan.
    fun <T> accessoryContentReturn(): TweenSpec<T> = tween(
        durationMillis = AccessoryContentReturnMs,
        delayMillis = AccessoryContentReturnDelayMs,
        easing = EaseOut,
    )

    /** Sidebar show/hide (⌘B). ios:Shell/SplitRootController.swift:64 */
    const val SidebarToggleMs = 300
    fun <T> sidebarToggle(): TweenSpec<T> = tween(SidebarToggleMs, easing = EaseInOut)

    /** Streaming veil pass (alpha 0→1). ios:Transcript/RowView.swift:121-131 */
    const val VeilMs = 220
    fun <T> veil(): TweenSpec<T> = tween(VeilMs, easing = EaseOut)

    /** New row fade-in. ios:Transcript/TranscriptListView.swift:389 */
    const val RowFadeInMs = 280
    fun <T> rowFadeIn(): TweenSpec<T> = tween(RowFadeInMs, easing = EaseOut)

    /** Upload ring fade in. ios:Transcript/RowView.swift:463 */
    const val UploadRingInMs = 200
    fun <T> uploadRingIn(): TweenSpec<T> = tween(UploadRingInMs, easing = EaseInOut)

    /** Upload ring fade out. ios:Transcript/RowView.swift:460 */
    const val UploadRingOutMs = 250
    fun <T> uploadRingOut(): TweenSpec<T> = tween(UploadRingOutMs, easing = EaseInOut)

    /** Tool group fold. ios:Transcript/TranscriptListView.swift:343 */
    fun <T> fold(): TweenSpec<T> = tween(FoldMs, easing = EaseOut)

    /** Tool group chevron flip. ios:Transcript/ToolViews.swift:51-54 */
    fun <T> toolChevron(): TweenSpec<T> = tween(FoldMs, easing = EaseOut)

    /** Visible tool group grew: row height reveal. ios:Transcript/TranscriptListView.swift:348 */
    fun <T> toolRowReveal(): TweenSpec<T> = tween(RowRevealMs, easing = ExpoOut)

    /** Rail connector draw (24 keyframe samples of `quintOut`). ios:Transcript/ToolViews.swift:209-226 */
    fun <T> railConnector(): TweenSpec<T> = tween(ConnectorMs, easing = QuintOut)

    /** Status cell blink cycle (linear, repeating). ios:Design/StatusGlyph.swift:159-161 */
    const val SpinnerCycleMs = 750
    fun <T> spinnerCycle(): InfiniteRepeatableSpec<T> =
        infiniteRepeatable(tween(SpinnerCycleMs, easing = LinearEasing), RepeatMode.Restart)

    /** Live title shimmer sweep (linear, repeating). ios:Transcript/ToolViews.swift:269-271 */
    const val ShimmerSweepMs = 3400
    fun <T> shimmerSweep(): InfiniteRepeatableSpec<T> =
        infiniteRepeatable(tween(ShimmerSweepMs, easing = LinearEasing), RepeatMode.Restart)

    // ----------------------------------------------------------- alert (18)
    // The UIAlertController present/dismiss set. UIKit draws the alert with
    // system chrome, so these numbers are chosen to read like iOS 26 (the
    // ticket's Comments own the choice); ui-parity#18 is the consumer.

    /** Alert present: scrim and card alpha 0 → 1. ui-parity#18 (chosen, not measured). */
    const val AlertFadeInMs = 250
    fun <T> alertFadeIn(): TweenSpec<T> = tween(AlertFadeInMs, easing = EaseOut)

    /**
     * Alert present: card scale 1.1 → 1, UIKit-style 0.4 s critically damped
     * (settle-matched stiffness 532.85). ui-parity#18 (chosen, not measured).
     */
    val AlertScaleIn = IosSpring("alertScaleIn", 0.40, 1f)

    /** Alert dismiss: scrim and card alpha → 0, no scale. ui-parity#18 (chosen, not measured). */
    const val AlertFadeOutMs = 200
    fun <T> alertFadeOut(): TweenSpec<T> = tween(AlertFadeOutMs, easing = EaseInOut)

    // ------------------------------------------------------ action sheet (19)
    // The bottom-anchored UIAlertController(.actionSheet) present/dismiss
    // set. UIKit draws the sheet with system chrome, so these numbers are
    // the parent epic's approximation chosen to read like iOS (the ticket's
    // Comments own the choice); ui-parity#19 is the consumer. Stays out of
    // allSprings (04's table only), pinned by 19's own tests.

    /**
     * Action sheet present: the stack slides up from below the window with
     * UIKit-style 0.4 s critically damped motion (settle-matched stiffness
     * 532.85, no overshoot). ui-parity#19 (chosen, not measured).
     */
    val ActionSheetRise = IosSpring("actionSheet", 0.40, 1f)

    /** Action sheet dismiss: the stack sinks, 250 ms ease-in. ui-parity#19 (chosen, not measured). */
    const val ActionSheetFallMs = 250
    fun <T> actionSheetFall(): TweenSpec<T> = tween(ActionSheetFallMs, easing = EaseIn)

    /** Action sheet under Reduce Motion: cards and scrim cross-fade 250 ms, both directions. ui-parity#19 (chosen, not measured). */
    const val ActionSheetFadeMs = 250
    fun <T> actionSheetFade(): TweenSpec<T> = tween(ActionSheetFadeMs, easing = EaseInOut)

    // ------------------------------------------------------ large title (25)
    // The end-of-scroll snap of a large title: UIKit's snap is internal with
    // no exposed numbers, so ticket 25 approximates it as a 0.46 s critically
    // damped settle — stiffness ≈ 400 (the ticket's tunable `SnapStiffness`
    // in spirit, carried as an [IosSpring] so no literal is ever pasted,
    // FOUNDATION-D1). Large titles never rest half-collapsed; this is the
    // only 25 motion token, everything else follows the finger.

    /** Large-title end-of-scroll snap: 0.46 s critically damped (stiffness ≈ 400). ui-parity#25 (chosen, not measured). */
    val LargeTitleSnap = IosSpring("largeTitleSnap", 0.4617, 1f)

    // -------------------------------------------------- pull to refresh (48)
    // The refresh control's bounce-back settle (release → the 60 dp hold,
    // refresh end → 0). UIKit's bounce-back curve is internal with no
    // exposed numbers, so ticket 48 approximates it as the same 0.4617 s
    // critically damped settle as the large-title snap — stiffness ≈ 400,
    // the ticket's tunable `SettleStiffness` in spirit, carried as an
    // [IosSpring] so no literal is ever pasted (FOUNDATION-D1). A 60 → 0
    // settle reaches 0.5 dp after ≈ 0.34 s. Stays out of allSprings (04's
    // table only), pinned by 48's own tests.

    /** Refresh-control settle: 0.46 s critically damped (stiffness ≈ 400). ui-parity#48 (chosen, not measured). */
    val RefreshSettle = IosSpring("refreshSettle", 0.4617, 1f)

    // Glass press (07-owned approximations of the iOS 26 system-glass touch
    // response): the ticket gives damping/stiffness pairs (0.7/600 and
    // 0.55/380). The durations are those pairs' settle-matched T =
    // ln(1000)/(ζ·√k), written out so no stiffness is pasted; IosSpring's
    // stiffness reproduces 600f and 380f exactly (the former in-place
    // back-solve in Glass.kt, bit for bit).

    /** Glass press-down: damping 0.7, settle-matched stiffness 600. ui-parity#07. */
    val GlassPressDown = IosSpring("glassPressDown", 0.40286847594469244, 0.7f)

    /** Glass press release/cancel: damping 0.55, settle-matched stiffness 380, one small overshoot. ui-parity#07. */
    val GlassPressRelease = IosSpring("glassPressRelease", 0.64429156582968516, 0.55f)

    /**
     * Transcript overscroll bounce (134): critically damped, ω = 20 rad/s
     * (stiffness 400, android-app#16 line 214), T = 9.2334 / 20. Stepped
     * per frame as an exact ODE, so consumers read [IosSpring.naturalFrequency]
     * (exactly 20f as a Float). Not [LargeTitleSnap]: its 0.4617 s gives
     * ω = 19.9987.
     */
    val TranscriptBounce = IosSpring("transcriptBounce", 0.46167, 1f)

    // ------------------------------------------------ connection line (50)
    // The Sessions front page's multi-engine connection line (the Roboco
    // stand-in for the never-shipped offline banner). This is the one
    // entrance iOS has no counterpart for, so the numbers are the Roboco
    // desktop's `motion::fade_in` (`crates/ui/src/motion.rs:442-450`) over
    // its `FADE_IN` (`crates/proto/src/motion.rs:215,289`) — the same 500 ms
    // ease-out-expo entrance the web's `rb-fade-in` uses
    // (`web/packages/app/src/styles/app.css:1836,4258-4263`); ui-parity#50
    // is the consumer. Stays out of allSprings (04's table only).

    /** Connection-line entrance: opacity 0 → 1 with a 4 dp rise, [ExpoOut]. Desktop `FADE_IN`. ui-parity#50. */
    const val ConnectionLineInMs = 500
    fun <T> connectionLineIn(): TweenSpec<T> = tween(ConnectionLineInMs, easing = ExpoOut)

    // ------------------------------------------------------ handoff timing set

    /** The full handoff timing set, normal or reduced. ios:Session/DraftHandoff.swift:59-131,162-174 */
    fun handoff(reduce: Boolean): HandoffTimings = if (reduce) HandoffReduced else HandoffNormal

    private val HandoffNormal = HandoffTimings(
        startDelayMs = 50L,
        dissolveMs = 320,
        dissolveScale = 0.985f,
        glideMs = 550,
        composerFadeMs = 180,
        composerFadeDelayMs = 360,
        transcriptRevealMs = 350,
        transcriptRevealDelayMs = 120,
        bubbleSpring = HandoffBubble,
        bubbleFadeMs = 140,
        bubblePollMs = 16L,
        bubblePollTries = 20,
    )

    private val HandoffReduced = HandoffTimings(
        // The start delay also applies under reduce motion.
        startDelayMs = 50L,
        dissolveMs = 200,
        // No scale under reduce motion.
        dissolveScale = 1f,
        glideMs = 200,
        composerFadeMs = 180,
        composerFadeDelayMs = 0,
        transcriptRevealMs = 350,
        transcriptRevealDelayMs = 0,
        bubbleSpring = HandoffBubbleReduced,
        bubbleFadeMs = 140,
        bubblePollMs = 16L,
        bubblePollTries = 20,
    )

    // -------------------------------------------------------- timers, delays
    // Long ms for `delay(…)`; ios `asyncAfter` / `Timer` sites.

    /** Toast auto-dismiss. ios:Design/Toast.swift:102 */
    const val ToastHoldMs = 4000L

    /** Give up holding an empty transcript. ios:Session/SessionViewController.swift:286 */
    const val SessionGiveUpMs = 8000L

    /** Copy-code checkmark hold. ios:Transcript/RowView.swift:339 */
    const val CopyConfirmHoldMs = 1400L

    /** Status pill / working trailer elapsed tick. ios:Session/SessionViewController.swift:636, Transcript/RowView.swift:440 */
    const val StatusTickMs = 1000L

    /** Auto-advance after a single-select pick. ios:Composer/QuestionPanel.swift:166 */
    const val QuestionAutoAdvanceMs = 220L

    /** Mention query debounce. ios:Composer/ComposerBar.swift:347 */
    const val MentionDebounceMs = 120L

    /** The tool rail stays quiet after the transcript attaches. ios:Transcript/TranscriptListView.swift:312 */
    const val RailQuietWindowMs = 800L

    /** Pull-to-refresh hold window bounds. ios:App/AppModel.swift:296 */
    const val RefreshMinMs = 600L
    const val RefreshMaxMs = 3000L

    // ---------------------------------------------------- follow-the-tail loop
    // ios:Transcript/TranscriptListView.swift:64,519-537. The loop itself
    // belongs to tickets 68 and 69; only the step math lives here.

    /** Follow: `y += δ·(1 − e^(−16·dt))`. */
    const val FollowRate = 16.0

    /** Runway retain: `y += δ·(1 − 0.85^frames)`. */
    const val GlideRetain = 0.85

    /** Runway frame cap: `frames = min(8, dt·60)`. */
    const val MaxGlideFrames = 8.0

    /** Frame dt clamp: `dt = min(1/30, max(0, now − last))`. */
    const val MaxFrameDtSec = 1.0 / 30

    /** Snap and stop when `|δ| < 0.5` (iOS pt → dp). */
    const val FollowSnapPx = 0.5f

    /** One follow step `δ·(1 − e^(−16·dt))`; dt clamped to [0, 1/30]. */
    fun followStep(delta: Float, dtSec: Double): Float {
        val dt = min(MaxFrameDtSec, max(0.0, dtSec))
        return (delta * (1.0 - exp(-dt * FollowRate))).toFloat()
    }

    /** One runway step `δ·(1 − 0.85^frames)` with `frames = min(8, dt·60)`; dt clamped to [0, 1/30]. */
    fun runwayStep(delta: Float, dtSec: Double): Float {
        val dt = min(MaxFrameDtSec, max(0.0, dtSec))
        val frames = min(MaxGlideFrames, dt / (1.0 / 60.0))
        return (delta * (1.0 - GlideRetain.pow(frames))).toFloat()
    }

    // ------------------------------------------------------- session list (41)
    // The shared session list's two Roboco-chosen timings. iOS has no
    // source numbers for either (UIKit's diffable animation is
    // unmeasured and `deselectItem(at:animated: true)`'s fade is
    // unstated), so both are approximations to tune side by side with an
    // iOS 26 simulator; ui-parity#41 is the consumer.

    /** The rows' deselect fade, with [EaseInOut]. ui-parity#41 (chosen, not measured). */
    const val RowHighlightFadeOutMs = 300

    /**
     * The `LazyColumn` items' placement spring — Compose's **own**
     * `animateItem` default (critically damped, stiffness 400, 1-px
     * threshold), which stands in for UIKit's diffable insert/move
     * animation. The one Motion token whose value is Compose's, not an
     * iOS one. ui-parity#41 (chosen per the ticket).
     */
    val ListItemPlacement: SpringSpec<IntOffset> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = IntOffset.VisibilityThreshold,
    )
}
