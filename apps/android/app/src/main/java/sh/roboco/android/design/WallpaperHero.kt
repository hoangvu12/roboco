package sh.roboco.android.design

import android.graphics.BlurMaskFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Paint as AndroidPaint
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.launch
import sh.roboco.android.core.WallpaperStore

/**
 * ios:Design/Wallpaper.swift:189-286 (`WallpaperView`): the wallpaper hero
 * — aspect-filled artwork at the top of a page that fades out downward
 * through an **alpha mask** (never a tinted overlay: the page background
 * shows through), at the opacity the contrast guard allows, with an
 * optional feathered cutout softening the artwork behind a composer.
 *
 * The hero is a passive backdrop: it fills the frame the caller's
 * [modifier] gives it (both consumers use `min(0.72 × page height, 760.dp)`
 * at the top of the page), takes no touches, is invisible to TalkBack, and
 * draws nothing while no wallpaper is set. It fades the artwork with alpha
 * only, so it never draws a colour of its own.
 *
 * Passing the composer rect (tickets 46 and 93 do this wiring; it is the
 * `composer.convert(composer.bounds, to: wallpaper)` of
 * `NewSessionViewController.swift:220-226`): the host captures both
 * coordinate holders and converts the composer rect into **hero-local**
 * pixels — never root coordinates:
 *
 * ```
 * var heroCoords: LayoutCoordinates? = null
 * var composerCoords: LayoutCoordinates? = null
 * WallpaperHero(
 *     modifier = Modifier.onGloballyPositioned { heroCoords = it },
 *     cutout = {
 *         heroCoords?.localBoundingBoxOf(composerCoords!!, clipBounds = false)
 *     },
 * )
 * ```
 *
 * Keep both holders in non-snapshot `var`s or `mutableStateOf` and read them
 * inside the [cutout] lambda only — the rect is recomputed every layout pass
 * and must never recompose the hero.
 */
@Composable
fun WallpaperHero(
    modifier: Modifier = Modifier,
    /** Host fade (the Sessions scroll). Read in the draw phase; clamped to 0…1. */
    scrollFade: () -> Float = { 1f },
    /** Rect to soften (the composer), in this hero's local pixels; null = no cutout. Read in the draw phase. */
    cutout: () -> Rect? = { null },
) {
    val context = LocalContext.current
    val store = remember(context) { WallpaperStore.get(context) }
    val state by store.state.collectAsState()
    WallpaperHero(
        changeKey = state.generation,
        load = store::render,
        modifier = modifier,
        scrollFade = scrollFade,
        cutout = cutout,
    )
}

/** Ticket 20's render result under a short name (ticket 46's tests construct it). */
internal typealias WallpaperRender = WallpaperStore.Render

/**
 * Testable core of [WallpaperHero]: [load] stands in for the store
 * (`WallpaperStore.render(dark)`), [changeKey] for the store's
 * `State.generation`. Everything else — the reload on light/dark change, the
 * first-arrival fade, the dispose-and-return re-fade — is the component's
 * own behaviour.
 */
@Composable
internal fun WallpaperHero(
    changeKey: Any?,
    load: suspend (dark: Boolean) -> WallpaperRender?,
    modifier: Modifier = Modifier,
    scrollFade: () -> Float = { 1f },
    cutout: () -> Rect? = { null },
) {
    val dark = LocalIsDark.current
    val reduce by rememberUpdatedState(LocalReduceMotion.current)
    val view = LocalView.current
    var render by remember { mutableStateOf<WallpaperRender?>(null) }
    // Wallpaper.swift:211: the view starts at alpha 0; the first render on
    // screen animates this to 1 over 120 ms.
    val appear = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // One reload per store change (the generation) and per appearance flip
    // (Wallpaper.swift:212-213). The old render stays visible while the next
    // one loads (Wallpaper.swift:230).
    LaunchedEffect(changeKey, dark) {
        val next = load(dark)
        val previous = render
        render = next
        when (wallpaperArrival(previous, next, view.isAttachedToWindow, reduce)) {
            WallpaperArrival.FadeIn ->
                // rememberCoroutineScope, not this effect: a key change that
                // lands inside the 120 ms (a dark flip) must not strand
                // `appear` mid-fade while the next load runs.
                scope.launch {
                    appear.snapTo(0f)
                    appear.animateTo(1f, Motion.wallpaperFadeIn())
                }
            WallpaperArrival.Snap ->
                scope.launch { appear.snapTo(1f) } // snapTo cancels a running fade
        }
    }

    // No wallpaper set (including while the very first render is still
    // loading): nothing is drawn — no node at all, like isHidden.
    val current = render ?: return
    WallpaperArtwork(
        render = current,
        appear = { appear.value },
        modifier = modifier,
        scrollFade = scrollFade,
        cutout = cutout,
    )
}

/**
 * Stateless draw: the artwork with the mask, at layer alpha
 * `render.opacity × appear × clamp(scrollFade)` (Wallpaper.swift:244-246).
 * [WallpaperHero] owns the state; the gallery renders this directly.
 */
@Composable
internal fun WallpaperArtwork(
    render: WallpaperRender,
    appear: () -> Float,
    modifier: Modifier = Modifier,
    scrollFade: () -> Float = { 1f },
    cutout: () -> Rect? = { null },
) {
    Box(
        modifier
            // A test hook only (the behaviour test reads the fade's
            // progress); a plain UIView is no VoiceOver element, and this
            // key adds none of the properties TalkBack needs to focus one.
            .testTag("wallpaper")
            .semantics { this[WallpaperAppearKey] = appear() }
            .graphicsLayer {
                // The mask's DstIn and the cutout's DST_OUT must only hit
                // this layer; without an offscreen layer they punch through
                // to whatever lies behind the hero.
                compositingStrategy = CompositingStrategy.Offscreen
                clip = true
                shape = RectangleShape
                // Wallpaper.swift:244-246 — (render?.opacity ?? 0) × max(0, min(1, scrollFade)),
                // times the arrival fade. Read here, in the layer block, so
                // a scrolling host never recomposes this composable.
                alpha = wallpaperAlpha(render.opacity, appear(), scrollFade())
            }
            .drawWithCache {
                // Wallpaper.swift:264-276 — the 25-stop smoothstep fade over
                // the full height, top opaque, bottom clear.
                val brush = Brush.verticalGradient(*wallpaperMaskStops(), startY = 0f, endY = size.height)
                // Wallpaper.swift:278 — feather in dp, then converted to px
                // for this density (the mask is drawn at device resolution).
                val featherPx = cutoutFeatherDp(size.height / density) * density
                val outsetPx = 8.dp.toPx()
                val cornerPx = 26.dp.toPx()
                // The CG shadow of Wallpaper.swift:279-282: destinationOut
                // draws both the blurred shadow and the shape, and the
                // shadow's alpha is the shadow colour's alpha times the
                // shape's — 0.5 × 0.5 = 0.25 (ARGB 0x40000000).
                val shadowPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.argb(0x40, 0, 0, 0)
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                    maskFilter = BlurMaskFilter(blurMaskRadiusPx(featherPx), BlurMaskFilter.Blur.NORMAL)
                }
                // The CG fill: the same outset rounded rect, sharp, black at 0.5.
                val fillPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.argb(0x80, 0, 0, 0)
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                }
                val bounds = Rect(Offset.Zero, size)
                onDrawWithContent {
                    drawContent()
                    // Alpha only: the page background shows through the fade.
                    drawRect(brush, blendMode = BlendMode.DstIn)
                    // Wallpaper.swift:277 — the cutout is drawn only if the
                    // un-outset rect overlaps the bounds.
                    val cut = cutout()
                    if (cut != null && cut.overlaps(bounds)) {
                        drawIntoCanvas { canvas ->
                            val native = canvas.nativeCanvas
                            val left = cut.left - outsetPx
                            val top = cut.top - outsetPx
                            val right = cut.right + outsetPx
                            val bottom = cut.bottom + outsetPx
                            // Two sequential destinationOut passes (blurred
                            // 0.25, then sharp 0.5) equal one pass of the two
                            // drawn together — the setShadow compositing.
                            native.drawRoundRect(left, top, right, bottom, cornerPx, cornerPx, shadowPaint)
                            native.drawRoundRect(left, top, right, bottom, cornerPx, cornerPx, fillPaint)
                        }
                    }
                }
            },
    ) {
        // scaleAspectFill, centred, clipped (Wallpaper.swift:207-209).
        Image(
            bitmap = render.image,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
        )
    }
}

/** Wallpaper.swift:230-240: how the next render arrives. */
internal enum class WallpaperArrival { FadeIn, Snap }

/**
 * Wallpaper.swift:230-240, pure: the first render on screen without reduce
 * motion fades in (120 ms); every other arrival — a re-render after an
 * effect change, a dark/light flip, a removal, a render under reduce motion,
 * a render while detached — applies its alpha instantly.
 */
internal fun wallpaperArrival(
    previous: WallpaperRender?,
    next: WallpaperRender?,
    onScreen: Boolean,
    reduceMotion: Boolean,
): WallpaperArrival =
    if (previous == null && next != null && onScreen && !reduceMotion) WallpaperArrival.FadeIn
    else WallpaperArrival.Snap

/** Wallpaper.swift:264-276: 25 (position, alpha) pairs, black with alpha `1 − t²(3 − 2t)`. */
internal fun wallpaperMaskStops(): Array<Pair<Float, Color>> =
    Array(25) { i ->
        val t = i / 24f
        t to Color.Black.copy(alpha = 1f - t * t * (3f - 2f * t))
    }

/** Wallpaper.swift:278: `min(280, max(120, height × 0.52)) / 3`, in dp. */
internal fun cutoutFeatherDp(heroHeightDp: Float): Float = min(280f, max(120f, heroHeightDp * 0.52f)) / 3f

/**
 * CG blur = 2σ (the CSS/canvas convention) → Skia `BlurMaskFilter` radius.
 * Skia uses σ = 0.57735·radius + 0.5, so the radius is the inverse; a
 * sub-half-pixel σ clamps to the 0.5 px minimum.
 */
internal fun blurMaskRadiusPx(featherPx: Float): Float = max(0.5f, (featherPx / 2f - 0.5f) / 0.57735f)

/** Wallpaper.swift:244-246: `(render?.opacity ?? 0) × max(0, min(1, scrollFade))`. */
internal fun wallpaperAlpha(opacity: Float, appear: Float, scrollFade: Float): Float =
    opacity * appear * scrollFade.coerceIn(0f, 1f)

/** Test hook: the current `appear` value (0…1), the hero's fade-in progress. */
internal val WallpaperAppearKey = SemanticsPropertyKey<Float>("WallpaperAppear")
