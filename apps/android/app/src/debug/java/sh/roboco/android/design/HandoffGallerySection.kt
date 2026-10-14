package sh.roboco.android.design

import android.content.res.Configuration
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt
import sh.roboco.android.core.Face
import sh.roboco.android.design.sheet.SmoothTopRoundedShape
import sh.roboco.android.newsession.CapturedDraftHandoff
import sh.roboco.android.newsession.HandoffArrivalTarget
import sh.roboco.android.newsession.HandoffOverlayCanvas
import sh.roboco.android.newsession.HandoffOverlayHost
import sh.roboco.android.newsession.HandoffOverlaySet
import sh.roboco.android.newsession.HandoffOverlayState
import sh.roboco.android.newsession.bubbleStartRect
import sh.roboco.android.newsession.fallbackBubbleTarget
import sh.roboco.android.newsession.flightRect
import sh.roboco.android.newsession.glideRect
import sh.roboco.android.newsession.glideTarget
import sh.roboco.android.shell.RouterHooks
import sh.roboco.android.shell.SessionRoute

/**
 * The "Handoff" page of the debug design gallery (android-ui-parity #95),
 * registered in ticket 22's registry as
 * `GallerySection("Handoff", GalleryLayout.Fill, 852)` (slug `handoff`).
 *
 * The demo stage: [HandoffOverlayHost] over a fake chat (the fake
 * first-user bubble the flight lands on and the fake composer capsule
 * the glide settles onto), driven by a fake [HandoffArrivalTarget]. The
 * stage owns its [RouterHooks] — never the app's — and the **Play**
 * capsule (ticket 22's segment-capsule look) resets the arrival state
 * and runs a fresh fixture handoff through
 * `hooks.handoffAnimator.run`, so the full motion — the dissolve, the
 * glide, the flight, the two reveals — plays over the stage. It follows
 * the gallery's "Force reduce motion" toggle because `run` reads
 * [ReduceMotion.isReduced].
 *
 * The fixture handoff is rebuilt on every Play (never remembered: the
 * set is the only thing that may hold the bitmaps) from **software**
 * bitmaps — `ImageBitmap(w, h)` + [Canvas] — so they also render under
 * Robolectric in the screenshot test.
 */
@Composable
fun HandoffGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val hooks = remember { RouterHooks() }
    val target = remember { HandoffGalleryTarget() }
    val route = remember { RouterlessHandoffRoute(target) }
    // The flying bubble's style (Geist Regular, body-scaled 16.5) and the
    // fixture page's hero label (SansSemibold fixed 28).
    val style = uiStyle(Face.Sans, typeScale.bodyScaled(16.5f), palette.text)
    val heroStyle = uiStyle(Face.SansSemibold, typeScale.fixed(28f), palette.text)
    val topInsetPx = WindowInsets.safeDrawing.getTop(density)
    val bottomInsetPx = WindowInsets.safeDrawing.getBottom(density)
    // The stage's own geometry, read at Play time (plain fields: layout
    // writes them, the click reads them, nothing recomposes on them).
    val stage = remember { HandoffGalleryStage() }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                stage.widthPx = it.size.width.toFloat()
                stage.heightPx = it.size.height.toFloat()
            },
    ) {
        HandoffOverlayHost(hooks = hooks, targetOf = { target }) {
            Box(Modifier.fillMaxSize()) {
                HandoffGalleryChat(target)
                // The Play row: 44 dp at the top of the stage. Taps reach
                // it through the overlays (they are non-interactive).
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .height(30.dp)
                            .clip(CapsuleShape)
                            .background(palette.controlFill)
                            .clickable(interactionSource = null, indication = null) {
                                // The Reset pass, then a fresh fixture —
                                // never a remembered one.
                                target.reset()
                                val handoff = buildHandoffGalleryFixture(
                                    background = palette.background,
                                    card = palette.userBubble,
                                    cardBorder = palette.text.copy(alpha = 0.12f),
                                    hero = HandoffGalleryHero,
                                    text = HandoffGalleryText,
                                    measurer = measurer,
                                    style = style,
                                    heroStyle = heroStyle,
                                    widthPx = stage.widthPx,
                                    heightPx = stage.heightPx,
                                    topInsetPx = topInsetPx.toFloat(),
                                    bottomInsetPx = bottomInsetPx.toFloat(),
                                    density = density.density,
                                )
                                hooks.handoffAnimator.run(handoff, route) {}
                            }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            "Play",
                            style = uiStyle(Face.SansMedium, typeScale.fixed(13f), palette.text),
                        )
                    }
                }
            }
        }
    }
}

/** The sent body the fixture stages. */
internal const val HandoffGalleryText = "Hello from the draft"

/** The fixture page's hero label. */
internal const val HandoffGalleryHero = "What are we building?"

/** The stage's geometry, written by its `onGloballyPositioned`. */
private class HandoffGalleryStage {
    var widthPx = 0f
    var heightPx = 0f
}

/**
 * The gallery's fake [SessionRoute] (chatId `"gallery"`): an empty
 * [Content][SessionRoute.Content], `prepareArrival` = the Reset pass,
 * `finishArrival` = the target's. Never registered with a router — the
 * Play capsule calls `hooks.handoffAnimator.run` directly. Shared with
 * the Roborazzi screenshot test.
 */
internal class RouterlessHandoffRoute(private val target: HandoffGalleryTarget) : SessionRoute {
    override val chatId: String = "gallery"

    @Composable
    override fun Content() {}

    override fun prepareArrival() = target.reset()

    override fun finishArrival() = target.finishArrival()

    override fun discard() {}
}

/**
 * The fake arrival target the gallery stage and the screenshot tests
 * drive: the fake bubble's and capsule's root bounds (reported by
 * [HandoffGalleryChat]), the two alpha channels as plain state, and the
 * reveal/finish rules of ticket 77's real `SessionArrival` — the reveals
 * animate with the caller's spec, `finishArrival` snaps only channels
 * without a running reveal, and [reset] is the pre-Play hidden state.
 */
@Stable
internal class HandoffGalleryTarget : HandoffArrivalTarget {

    /** The fake first user bubble's root bounds, px (set by the chat). */
    var bubbleBounds: Rect? = null

    /** The fake composer capsule's root bounds, px (set by the chat). */
    var composerBounds: Rect? = null

    /** The fake transcript's alpha (the bubble's). */
    var bubbleAlpha by mutableFloatStateOf(1f)

    /** The fake bottom stack's alpha (the capsule's). */
    var stackAlpha by mutableFloatStateOf(1f)

    /** `finishArrival()` calls — the screenshot test's land trigger. */
    var finishArrivalCalls = 0
        internal set

    private var bubbleRevealing = false
    private var stackRevealing = false

    override fun arrivalComposerBounds(): Rect? = composerBounds

    override fun arrivalBubbleBounds(): Rect? = bubbleBounds

    override suspend fun revealArrivalComposer(spec: AnimationSpec<Float>) {
        stackRevealing = true
        try {
            animate(stackAlpha, 1f, animationSpec = spec) { value, _ -> stackAlpha = value }
        } finally {
            stackRevealing = false
        }
    }

    override suspend fun revealArrivalTranscript(spec: AnimationSpec<Float>) {
        bubbleRevealing = true
        try {
            animate(bubbleAlpha, 1f, animationSpec = spec) { value, _ -> bubbleAlpha = value }
        } finally {
            bubbleRevealing = false
        }
    }

    override fun finishArrival() {
        finishArrivalCalls++
        if (!bubbleRevealing) bubbleAlpha = 1f
        if (!stackRevealing) stackAlpha = 1f
    }

    /** The Reset pass (before every Play): both channels hidden. */
    fun reset() {
        bubbleAlpha = 0f
        stackAlpha = 0f
    }
}

/**
 * The fake chat the handoff plays over: a `Palette.background` page, the
 * fake first user bubble (a `SmoothRoundedShape(20.dp)` `userBubble` fill
 * with the sent text at the 15/10 padding, 72 dp below the safe-area top,
 * 16 dp from the right edge — the flight lands exactly on it) and the
 * fake composer capsule (50 dp, 16 dp margins, `CapsuleShape`,
 * `userBubble` with a 1 px `text` @ 12 % border, 8 dp above the bottom
 * safe area — the glide settles onto it). The two alphas are the fake
 * arrival channels, animated by [HandoffGalleryTarget].
 */
@Composable
internal fun HandoffGalleryChat(target: HandoffGalleryTarget, modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val density = LocalDensity.current
    val topDp = with(density) { WindowInsets.safeDrawing.getTop(this).toDp() }
    val bottomDp = with(density) { WindowInsets.safeDrawing.getBottom(this).toDp() }
    Box(modifier.fillMaxSize().background(palette.background)) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-16).dp, y = topDp + 72.dp)
                .graphicsLayer { this.alpha = target.bubbleAlpha }
                .onGloballyPositioned { target.bubbleBounds = it.boundsInRoot() }
                .clip(SmoothRoundedShape(20.dp))
                .background(palette.userBubble)
                .padding(horizontal = 15.dp, vertical = 10.dp),
        ) {
            BasicText(
                HandoffGalleryText,
                style = uiStyle(Face.Sans, typeScale.bodyScaled(16.5f), palette.text),
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, end = 16.dp, bottom = bottomDp + 8.dp)
                .graphicsLayer { this.alpha = target.stackAlpha }
                .onGloballyPositioned { target.composerBounds = it.boundsInRoot() }
                .fillMaxWidth()
                .height(50.dp)
                .clip(CapsuleShape)
                .background(palette.userBubble)
                .hairlineBorder(palette.text.copy(alpha = 0.12f), CapsuleShape),
        )
    }
}

/**
 * Builds the fixture handoff from **software** bitmaps (the gallery and
 * the Roborazzi test render them under Robolectric): the scene page
 * (`background` fill with the hero label centred horizontally at 40 % of
 * its height, top inset = safe area + 10 dp, 31's 38 dp top-corner
 * shape), the composer card (a 26 dp continuous-corner `userBubble` card
 * with a 1 px border, transparent outside the shape, sized `W − 32 dp ×
 * 120 dp` with its bottom `300 dp` above the capsule's resting place, as
 * if above a keyboard) and the text slot (the composer inset 16/14, one
 * line of [text]'s style, at least 20 dp).
 */
internal fun buildHandoffGalleryFixture(
    background: Color,
    card: Color,
    cardBorder: Color,
    hero: String,
    text: String,
    measurer: TextMeasurer,
    style: TextStyle,
    heroStyle: TextStyle,
    widthPx: Float,
    heightPx: Float,
    topInsetPx: Float,
    bottomInsetPx: Float,
    density: Float,
): CapturedDraftHandoff {
    val d = density

    // ── the scene picture: the whole draft page.
    val sceneTop = topInsetPx + 10f * d
    val sceneWidth = widthPx
    val sceneHeight = (heightPx - sceneTop).coerceAtLeast(1f)
    val scene = ImageBitmap(sceneWidth.roundToInt(), sceneHeight.roundToInt())
    Canvas(scene).apply {
        drawRect(0f, 0f, scene.width.toFloat(), scene.height.toFloat(), Paint().apply { color = background })
        val heroLayout = measurer.measure(hero, heroStyle, softWrap = false, maxLines = 1)
        save()
        translate(
            (scene.width - heroLayout.size.width) / 2f,
            scene.height * 0.4f - heroLayout.size.height / 2f,
        )
        heroLayout.multiParagraph.paint(this)
        restore()
    }

    // ── the composer picture: the draft card as if above a keyboard
    //    (a 26 dp continuous-corner card — `smoothRoundedPath` is
    //    `SmoothRoundedShape`'s path, for Canvas painters).
    val composerLeft = 16f * d
    val composerWidth = (widthPx - 32f * d).coerceAtLeast(1f)
    val composerHeight = 120f * d
    val composerBottom = heightPx - bottomInsetPx - 8f * d - 300f * d
    val composer = ImageBitmap(composerWidth.roundToInt(), composerHeight.roundToInt())
    Canvas(composer).apply {
        val w = composer.width.toFloat()
        val h = composer.height.toFloat()
        val cardPath = smoothRoundedPath(w, h, 26f * d)
        drawPath(cardPath, Paint().apply { this.color = card })
        drawPath(
            cardPath,
            Paint().apply {
                this.color = cardBorder
                this.style = PaintingStyle.Stroke
                this.strokeWidth = 1f
            },
        )
    }

    // ── the text slot: one line of the flying style.
    val textLeft = composerLeft + 16f * d
    val textWidth = (widthPx - 108f * d).coerceAtLeast(1f)
    val oneLine = measurer.measure(text, style, softWrap = false, maxLines = 1).size.height.toFloat()
    val textTop = composerBottom - composerHeight + 14f * d
    val textFrame = Rect(
        textLeft,
        textTop,
        textLeft + textWidth,
        textTop + max(20f * d, oneLine),
    )

    return CapturedDraftHandoff(
        scene = scene,
        sceneFrame = Rect(0f, sceneTop, sceneWidth, sceneTop + sceneHeight),
        sceneShape = SmoothTopRoundedShape(38.dp),
        composer = composer,
        composerFrame = Rect(
            composerLeft,
            composerBottom - composerHeight,
            composerLeft + composerWidth,
            composerBottom,
        ),
        text = text,
        textFrame = textFrame,
    )
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

/**
 * The preview stage: [HandoffOverlayCanvas] over the fake chat with one
 * set frozen mid-flight — the dissolve at half, the glide half-way home,
 * the bubble 60 % of the way to its fallback target with the fill at
 * 0.6.
 */
@Composable
private fun HandoffMidFlightStage(appearance: Appearance) {
    val density = LocalDensity.current
    val d = density.density
    val topInsetPx = WindowInsets.safeDrawing.getTop(density).toFloat()
    RobocoTheme(appearance) {
        val palette = RobocoTheme.palette
        val typeScale = LocalTypeScale.current
        val measurer = rememberTextMeasurer()
        val style = uiStyle(Face.Sans, typeScale.bodyScaled(16.5f), palette.text)
        val heroStyle = uiStyle(Face.SansSemibold, typeScale.fixed(28f), palette.text)
        val widthPx = 393f * d
        val heightPx = 852f * d
        val target = remember { HandoffGalleryTarget() }
        val state = remember(palette, d, topInsetPx) {
            val fixture = buildHandoffGalleryFixture(
                background = palette.background,
                card = palette.userBubble,
                cardBorder = palette.text.copy(alpha = 0.12f),
                hero = HandoffGalleryHero,
                text = HandoffGalleryText,
                measurer = measurer,
                style = style,
                heroStyle = heroStyle,
                widthPx = widthPx,
                heightPx = heightPx,
                topInsetPx = topInsetPx,
                bottomInsetPx = 0f,
                density = d,
            )
            val from = fixture.composerFrame
            val to = glideTarget(
                Rect(16f * d, heightPx - 8f * d - 50f * d, widthPx - 16f * d, heightPx - 8f * d),
                from,
            )
            val bubbleStart = bubbleStartRect(fixture.textFrame, d)
            val bubbleDest = fallbackBubbleTarget(bubbleStart, widthPx, topInsetPx, d)
            HandoffOverlayState().apply {
                sets += HandoffOverlaySet(fixture, Motion.handoff(false), d).apply {
                    sceneProgress = 0.5f
                    composerRect = glideRect(from, to, 0.5f)
                    bubbleRect = flightRect(bubbleStart, bubbleDest, 0.6f)
                    bubbleFill = 0.6f
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            HandoffGalleryChat(target)
            HandoffOverlayCanvas(state, Modifier.fillMaxSize())
        }
    }
}

@Preview(name = "Handoff mid-flight light", widthDp = 393, heightDp = 852)
@Composable
private fun HandoffMidFlightLightPreview() {
    HandoffMidFlightStage(Appearance.Light)
}

@Preview(
    name = "Handoff mid-flight dark",
    widthDp = 393,
    heightDp = 852,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun HandoffMidFlightDarkPreview() {
    HandoffMidFlightStage(Appearance.Dark)
}
