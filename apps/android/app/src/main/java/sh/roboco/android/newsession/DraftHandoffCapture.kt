package sh.roboco.android.newsession

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.unit.toSize
import kotlin.coroutines.cancellation.CancellationException
import sh.roboco.android.shell.DraftHandoff

/**
 * The captured draft handoff (android-ui-parity #94) — the port of
 * `ios:Session/DraftHandoff.swift:5-51`: the three bitmaps (and frames)
 * the new-session page hands the chat it created, so ticket 95 can play
 * the page dissolve, the composer glide and the bubble flight from real
 * pixels. A marker of ticket 32's [DraftHandoff]; 95 casts it back.
 *
 * The field names are the ones ticket 95 reads (also iOS's). Every frame
 * is in **root px = window px** (one edge-to-edge activity; the sheet is
 * an in-window overlay, ticket 31) — the same space 77's arrival hooks
 * and 95's animator use.
 *
 * `scene` is recorded **unclipped**: the sheet's rounded top corners
 * live on an ancestor (31), so the page root's own recording has square
 * corners; [sceneShape] carries the page's clip shape and 95 applies it
 * at draw time (exactly one of the two tickets clips).
 */
class CapturedDraftHandoff(
    /** The whole page (nav bar + canvas) with the composer at alpha 0, unclipped. */
    val scene: ImageBitmap,
    /** The scene's root frame, root px (`positionInRoot` + size). */
    val sceneFrame: Rect,
    /** The page's clip shape (31's `pageShape`; `RectangleShape` embedded). */
    val sceneShape: Shape,
    /** The composer root with its text layer at alpha 0 (glass, chips, +, Send, thumbnails). */
    val composer: ImageBitmap,
    /** The composer's root frame, root px, unclipped (like 77's `arrivalComposerBounds`). */
    val composerFrame: Rect,
    /** The sent body — the encoded wire text, mention links included (decision SESSIONS-D2). */
    val text: String,
    /** The text slot's inset rect (57's `onTextRect`), root px. */
    val textFrame: Rect,
) : DraftHandoff

/**
 * The capture state (android-ui-parity #94): the two
 * [GraphicsLayer]s the scene and composer roots record into, the
 * coordinates those roots report, and the capture flags 57's text field
 * and the composer root read. One instance per page
 * ([rememberHandoffCaptureState]); **main thread only**.
 *
 * The Roboco adaptation of `DraftHandoff.capture`'s offscreen render:
 * Compose records `GraphicsLayer`s during the draw pass, so the hidden
 * states (text layer alpha 0, composer alpha 0) must be drawn for real
 * — a **freeze frame** (the scene layer's own bitmap, drawn above the
 * recording and outside it) keeps them off screen while
 * `afterScreenUpdates: true`'s equivalent (`awaitDrawn`) runs. The
 * freeze is identical to the live page at the moment of capture, so
 * nothing visible changes during the ~4 frames the capture takes.
 *
 * After a successful capture the state stays frozen (the composer at
 * alpha 0, the freeze drawn): every router path tears the page down
 * right after (32/106), so nothing restores it — never add an undo
 * timer.
 */
@Stable
class HandoffCaptureState internal constructor(
    internal val sceneLayer: GraphicsLayer,
    internal val composerLayer: GraphicsLayer,
    /**
     * `afterScreenUpdates: true`'s stand-in: two frame waits, so the
     * draw pass re-recorded the layers after a state write. Tests
     * inject a clock-driven form.
     */
    internal val awaitDrawn: suspend () -> Unit = { withFrameNanos {}; withFrameNanos {} },
) {
    /**
     * The page's clip shape, copied into the record — 93 sets 31's
     * `pageShape` on phone (top corners 38 dp continuous); the embedded
     * tablet page has no sheet scope, so a rectangle. A plain field:
     * written in composition, before the first draw.
     */
    var sceneShape: Shape = RectangleShape

    /** 57's `ComposerTextField(textHidden = …)` — the text layer at alpha 0 (the caret too). */
    var textHidden by mutableStateOf(false)
        private set

    /** The composer root at alpha 0 — outside its own recording, so the bitmap stays full. */
    var composerHidden by mutableStateOf(false)
        private set

    /** The freeze frame: the pre-send scene as the user still sees it, drawn above the recording. */
    internal var freeze by mutableStateOf<ImageBitmap?>(null)

    /** The scene root's coordinates (plain fields, written in `onGloballyPositioned`). */
    internal var sceneCoords: LayoutCoordinates? = null

    /** The composer root's coordinates. */
    internal var composerCoords: LayoutCoordinates? = null

    /** The text slot's rect, root px (57's `onTextRect` reports it on every layout). */
    private var textRect: Rect? = null

    /** 57's `ComposerTextField(onTextRect = …)` target. */
    fun onTextRect(r: Rect) {
        textRect = r
    }

    /**
     * Capture from the page at the moment of sending (the Capture
     * procedure table). `null` when the page is detached (no window —
     * iOS's `guard let window`), a layer is zero-sized, or no text rect
     * has been reported; any other `Throwable` (except cancellation) is
     * logged, [reset] runs, and `null` returns — the router then does a
     * plain open. Leaves [freeze] and [composerHidden] set on success.
     */
    suspend fun capture(text: String): CapturedDraftHandoff? {
        // 0. The guards — iOS's `guard let window`, in Compose form.
        val scene = sceneCoords?.takeIf { it.isAttached } ?: return null
        val composer = composerCoords?.takeIf { it.isAttached } ?: return null
        if (scene.size.width == 0 || scene.size.height == 0) return null
        if (composer.size.width == 0 || composer.size.height == 0) return null
        if (sceneLayer.size.width == 0 || sceneLayer.size.height == 0) return null
        if (composerLayer.size.width == 0 || composerLayer.size.height == 0) return null
        if (textRect == null) return null
        try {
            // 1. The freeze: the pre-send page (composer and text
            //    included) as the user still sees it. Real pixels, not
            //    replicas: a `drawLayer` reference goes blank once the
            //    layer is released, a bitmap does not.
            freeze = sceneLayer.toImageBitmap()

            // 2. Hide the text (iOS `composer.textView.alpha = 0`).
            textHidden = true

            // 3. Wait for a frame that drew it hidden.
            awaitDrawn()

            // 4. The composer travels empty — its text is the flying
            //    bubble. Read the frames after the draw, so the text
            //    rect is the laid-out value.
            val composerBitmap = composerLayer.toImageBitmap()
            val composerFrame = Rect(composer.positionInRoot(), composer.size.toSize())
            val textFrame = checkNotNull(textRect)

            // 5. Text back, whole composer hidden (iOS `textView.alpha = 1`,
            //    `composer.alpha = 0`).
            textHidden = false
            composerHidden = true

            // 6. Wait for the redraw.
            awaitDrawn()

            // 7. The page without its composer — unclipped: 95 applies
            //    sceneShape when drawing.
            val sceneBitmap = sceneLayer.toImageBitmap()
            val sceneFrame = Rect(scene.positionInRoot(), scene.size.toSize())
            val shape = sceneShape

            // 8. Leave the freeze and composerHidden set: the router
            //    tears the page down right after.
            return CapturedDraftHandoff(
                scene = sceneBitmap,
                sceneFrame = sceneFrame,
                sceneShape = shape,
                composer = composerBitmap,
                composerFrame = composerFrame,
                text = text,
                textFrame = textFrame,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Log.w("NewSession", "handoff capture failed", t)
            reset()
            return null
        }
    }

    /** Back to the live page: freeze null, both flags false (the gallery's repeat capture). */
    fun reset() {
        freeze = null
        textHidden = false
        composerHidden = false
    }
}

/**
 * One [HandoffCaptureState] for the page: two composition-scoped layers
 * (the scene's and the composer's) and the state object that ties them
 * together.
 */
@Composable
fun rememberHandoffCaptureState(): HandoffCaptureState {
    val sceneLayer = rememberGraphicsLayer()
    val composerLayer = rememberGraphicsLayer()
    return remember(sceneLayer, composerLayer) { HandoffCaptureState(sceneLayer, composerLayer) }
}

/**
 * On the page root (93's `NewSessionCanvas` modifier — its Layout holds
 * the backdrop, the nav bar and the composer): records the root into
 * the scene layer **unclipped** (95 clips with the record's
 * `sceneShape`) and draws the freeze frame above it, outside the
 * recording — inside it, the scene bitmap would capture the freeze,
 * composer included.
 */
fun Modifier.handoffScene(state: HandoffCaptureState): Modifier =
    onGloballyPositioned { state.sceneCoords = it }
        .drawWithContent {
            // DrawScope's `GraphicsLayer.record(size) {}`, not the member
            // `record(density, …)`: the node's scope re-binds its draw node
            // on every replay. A software snapshot (`toImageBitmap` on a
            // software path) replays the block outside the draw pass, where
            // the member form's captured `drawContent()` throws ("drawContent
            // for a `null` node") and the capture returned null.
            state.sceneLayer.record(size.toIntSize()) {
                this@drawWithContent.drawContent()
            }
            drawLayer(state.sceneLayer)
            state.freeze?.let { freeze -> drawImage(freeze) }
        }

/**
 * On the composer root (the modifier 93 passes to [ComposerBar], which
 * 78 forwards to `ComposerSurface`'s root). The hidden-composer alpha
 * layer sits **before** (outside) the recording `drawWithContent`, so
 * `composerHidden` blanks the screen but never the composer bitmap; the
 * bitmap itself is the recorded layer — glass, chips, `+`, Send and
 * thumbnails, its text hidden by 57's `textHidden`.
 */
fun Modifier.handoffComposer(state: HandoffCaptureState): Modifier =
    onGloballyPositioned { state.composerCoords = it }
        .graphicsLayer { this.alpha = if (state.composerHidden) 0f else 1f }
        .drawWithContent {
            // DrawScope's record (see handoffScene): replay-safe outside the draw pass.
            state.composerLayer.record(size.toIntSize()) {
                this@drawWithContent.drawContent()
            }
            drawLayer(state.composerLayer)
        }
