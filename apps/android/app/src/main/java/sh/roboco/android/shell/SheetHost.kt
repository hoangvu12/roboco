package sh.roboco.android.shell

import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SmoothRoundedShape
import sh.roboco.android.design.sheet.GlassSheetDefaults

/**
 * How [SheetHost] presents a sheet (android-ui-parity #31): the port of
 * UIKit's `UINavigationController` presentations the shell uses.
 */
sealed interface SheetStyle {

    /**
     * iOS `.pageSheet` at the `.large()` detent
     * (`MainTabController.swift:136-148`): full window width (704 dp
     * centred on tablets), top corners 38, and the card-stack recession
     * of the presenting content. [grabber] matches
     * `prefersGrabberVisible` — New Session and the Pair page show it,
     * New Project does not (`NewSessionViewController.swift:317`).
     */
    data class Page(val grabber: Boolean = true) : SheetStyle

    /**
     * Extension point for the sheets that are not page sheets: ticket
     * 104 implements the tablet Settings `.formSheet` dialog with this.
     * The host honours [scrimAlpha] and [motionSpec] per entry, and
     * calls [Frame] instead of [PageSheet]; the Frame itself must snap
     * its entry's `offset` from `NaN` to its height on first layout and
     * launch its own present animation (the host never does), so every
     * dismissal path keeps working unchanged.
     */
    interface Custom : SheetStyle {

        /** Whether the entry below shrinks into a card while this one is up (Page: true). */
        val recedesPresenter: Boolean

        /** Per-entry scrim maximum; `null` → the host's own scrim (ticket 104 overrides). */
        val scrimAlpha: Float? get() = null

        /** Spring for this entry's animated dismiss; ticket 104 overrides. */
        fun motionSpec(reduceMotion: Boolean): AnimationSpec<Float> = PageSheetDefaults.spring()

        /** The entry's frame: must present and dismiss `entry.offset` itself. */
        @Composable
        fun Frame(entry: SheetEntry, content: @Composable () -> Unit)
    }
}

/**
 * One presented sheet: its identity ([key]), its [style] and its content.
 * [offset] (px, 0 = fully presented, `H` = fully off-screen below) is the
 * single driver — scrim, recession and the covered-sheet peek are all
 * derived from [progress], never separately animated — and [PageSheet]
 * owns it, because the host's recession and handoff hide must read and
 * control it.
 */
@Stable
class SheetEntry internal constructor(
    val key: String,
    val style: SheetStyle,
    internal val onDismissed: (() -> Unit)?,
    internal val content: @Composable PageSheetScope.() -> Unit,
) {
    /** `NaN` until the first layout (= `H`), so the frame presents from below. */
    internal val offset = Animatable(Float.NaN)

    /** The sheet's height in px (`H`); written by the entry's frame. */
    internal var heightPx by mutableFloatStateOf(0f)

    /** Hidden for the new-session → chat handoff: composed, invisible, inert. */
    var hiddenForHandoff by mutableStateOf(false)
        internal set

    /**
     * Ticket 111's [PageSheetScope.dismissBlocked] backing state: while
     * true the frame ignores drag-to-dismiss and back, and drops the a11y
     * Dismiss action. Programmatic dismissal ignores it.
     */
    var dismissBlocked by mutableStateOf(false)
        internal set

    /** True while an animated dismissal is in flight; the once-guard. */
    internal var dismissing by mutableStateOf(false)

    /**
     * 0 = gone, 1 = fully presented; 0 while hidden for handoff. Read it
     * inside `graphicsLayer {}` / `drawBehind {}` lambdas only, so a drag
     * or animation frame redraws and never recomposes.
     */
    val progress: Float
        get() = PageSheetMath.progress(offset.value, heightPx, hiddenForHandoff)
}

/** The deferred teardown of [SheetHostState.hideForHandoff]: call [complete] when the motion is done. */
fun interface SheetHandoff {
    /** Removes the hidden entries now, without animation. Calling it twice is a no-op. */
    fun complete()
}

/**
 * The sheet host state (android-ui-parity #31): the stack of presented
 * sheets, bottom → top. One sheet at a time from the root ([present]),
 * one more stacked from inside the top sheet (`PageSheetScope.present`) —
 * New Project over New Session, the Pair page over the tablet Settings
 * dialog. Everything is UIKit-faithful:
 *
 * - `present` from the root while anything is presented (hidden included)
 *   is the UIKit no-op — `Log.w` and `false`;
 * - [dismiss] is the presenter's `dismiss(animated:)`: the whole stack
 *   goes together, hidden-for-handoff entries too (`dismiss(false)` on
 *   them equals `SheetHandoff.complete()`);
 * - [hideForHandoff] is `MainTabController.swift:170-190`: the stack
 *   vanishes in one frame and is torn down only once the handoff motion
 *   finished calling the returned [SheetHandoff].
 *
 * All mutators run on the main thread.
 */
@Stable
class SheetHostState {

    private val backing = mutableStateListOf<SheetEntry>()

    /** The presented entries, bottom → top; hidden-for-handoff entries count. */
    val entries: List<SheetEntry> get() = backing

    /** True while any entry exists, hidden included (ticket 32's PhoneShell reads this). */
    val isPresenting: Boolean get() = backing.isNotEmpty()

    /** Alias of [isPresenting]: ticket 32's port reads this name. */
    val isPresented: Boolean get() = isPresenting

    /** The composition scope of the mounted [SheetHost]; animations run on it. */
    internal var hostScope: CoroutineScope? = null
        private set

    /** `LocalReduceMotion.current`, read in [SheetHost] (stored via `SideEffect`). */
    internal var reduceMotion: Boolean = false

    /**
     * Presents [key] from the root, animated. No-op (Log.w, `false`) if
     * anything is presented — the UIKit "already presenting" log — or if
     * [key] is already up (guards a double tap on the accessory).
     */
    fun present(
        key: String,
        style: SheetStyle = SheetStyle.Page(),
        onDismissed: (() -> Unit)? = null,
        content: @Composable PageSheetScope.() -> Unit,
    ): Boolean {
        checkMainThread()
        if (backing.isNotEmpty()) {
            Log.w(TAG, "already presenting $key")
            return false
        }
        return insertEntry(key, style, onDismissed, content) != null
    }

    /**
     * The presenter's `dismiss(animated:)`: [SheetHostState.dismiss]
     * covers the whole stack; `PageSheetScope.dismiss` covers its entry
     * and everything above it (iOS `self.dismiss(animated:)` from inside
     * a sheet tears down what that sheet presented, too).
     *
     * Not animated: the entries (hidden ones included) are removed in the
     * same frame and their `onDismissed` fired top → bottom — the recession,
     * scrim and backdrop vanish with them. Animated: every entry springs
     * to its height at the same time; when they have settled the entries
     * are removed and `onDismissed` fires.
     */
    fun dismiss(animated: Boolean = true) {
        checkMainThread()
        if (backing.isEmpty()) return
        dismissEntryAndAbove(0, animated)
    }

    /**
     * `MainTabController.swift:170-190`: everything the sheet host shows
     * becomes invisible and inert in one frame — container alpha 0, scrim
     * 0, recession reset — while the entries stay composed for the handoff
     * capture. [SheetHandoff.complete] removes them once the motion is
     * done; the caller must call it in a `finally`/completion path.
     */
    fun hideForHandoff(): SheetHandoff {
        checkMainThread()
        val hidden = backing.toList()
        hidden.forEach { entry ->
            entry.hiddenForHandoff = true
            // Cancel a running offset animation (a dismissal in flight);
            // the offset itself stays where it is — progress reads 0 now.
            hostScope?.launch { entry.offset.stop() }
        }
        return SheetHandoff { completeHandoff(hidden) }
    }

    /** The [PageSheetScope] of [entry] — the host hands it to the content. */
    internal fun scopeFor(entry: SheetEntry): PageSheetScopeImpl = PageSheetScopeImpl(this, entry)

    /**
     * Dismisses `entries[index]` and every entry above it (the ✕, back,
     * a drag past the threshold, the router's calls). [velocity] is the
     * release velocity of the gesture that started it, px/s, and seeds
     * every animated dismissal.
     */
    internal fun dismissEntryAndAbove(index: Int, animated: Boolean, velocity: Float = 0f) {
        checkMainThread()
        if (index < 0 || index >= backing.size) return
        val doomed = backing.subList(index, backing.size).toList()
        if (doomed.any { it.dismissing }) return
        val scope = hostScope
        if (!animated || scope == null) {
            removeEntries(doomed)
            return
        }
        doomed.forEach { it.dismissing = true }
        scope.launch {
            try {
                // Every entry animates to its own height at the same time;
                // the whole stack goes away together (the bottom entry
                // drives the recession, and the entries are removed when
                // the animations have settled — never while one is visible).
                coroutineScope {
                    doomed.forEach { entry ->
                        launch {
                            if (!entry.offset.value.isNaN()) {
                                entry.offset.animateTo(
                                    targetValue = entry.heightPx,
                                    animationSpec = specFor(entry),
                                    initialVelocity = velocity,
                                )
                            }
                        }
                    }
                }
            } finally {
                removeEntries(doomed)
            }
        }
    }

    /** Springs [entry] back to fully presented with the release velocity. */
    internal fun settleBack(entry: SheetEntry, velocity: Float) {
        val scope = hostScope ?: return
        scope.launch {
            if (!entry.offset.value.isNaN()) {
                entry.offset.animateTo(0f, PageSheetDefaults.spring(), velocity)
            }
        }
    }

    internal fun insertEntry(
        key: String,
        style: SheetStyle,
        onDismissed: (() -> Unit)?,
        content: @Composable PageSheetScope.() -> Unit,
    ): SheetEntry? {
        if (backing.any { it.key == key }) {
            Log.w(TAG, "a sheet with key \"$key\" is already presented")
            return null
        }
        val entry = SheetEntry(key, style, onDismissed, content)
        backing.add(entry)
        return entry
    }

    private fun removeEntries(doomed: List<SheetEntry>) {
        backing.removeAll(doomed)
        doomed.asReversed().forEach { it.onDismissed?.invoke() }
    }

    private fun completeHandoff(hidden: List<SheetEntry>) {
        checkMainThread()
        val still = hidden.filter { it.hiddenForHandoff && backing.contains(it) }
        if (still.isEmpty()) return
        backing.removeAll(still)
        still.asReversed().forEach { it.onDismissed?.invoke() }
    }

    /** Wiring from the mounted host: its composition scope and reduce motion. */
    internal fun attach(scope: CoroutineScope, reduceMotion: Boolean) {
        hostScope = scope
        this.reduceMotion = reduceMotion
    }

    /**
     * The screenshot/preview helper: inserts an entry with `offset`
     * snapped to 0, so the frame never launches the present animation
     * and the first composed frame is already at rest.
     */
    @VisibleForTesting
    fun presentSettledForPreview(
        key: String,
        style: SheetStyle = SheetStyle.Page(),
        onDismissed: (() -> Unit)? = null,
        content: @Composable PageSheetScope.() -> Unit,
    ): Boolean {
        checkMainThread()
        val entry = insertEntry(key, style, onDismissed, content) ?: return false
        // The snap never really suspends (nothing else holds the
        // Animatable's mutex yet), so runBlocking returns at once.
        runBlocking { entry.offset.snapTo(0f) }
        return true
    }

    private fun specFor(entry: SheetEntry): AnimationSpec<Float> =
        (entry.style as? SheetStyle.Custom)?.motionSpec(reduceMotion) ?: PageSheetDefaults.spring()

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SheetHostState mutations must run on the main thread"
        }
    }

    private companion object {
        const val TAG = "SheetHost"
    }
}

/** The nearest mounted [SheetHostState]; `null` outside a host (ticket 32 reads it). */
val LocalSheetHost = staticCompositionLocalOf<SheetHostState?> { null }

/**
 * The [PageSheetScope] of the sheet the caller is composed in, `null`
 * outside sheets. Provided around each entry's content; ticket 101 stacks
 * its licence sheet through it.
 */
val LocalPageSheetScope = staticCompositionLocalOf<PageSheetScope?> { null }

/** Remembers a [SheetHostState] (ticket 35's app root calls this). */
@Composable
fun rememberSheetHostState(): SheetHostState = remember { SheetHostState() }

/**
 * The in-window sheet host (android-ui-parity #31): wraps the app's
 * content and presents the page sheets above it — never a `Dialog` or
 * `Popup` window, so Haze keeps sampling and toasts, menus and alerts
 * still draw above the sheet (the same rule as tickets 15, 18 and 19).
 * Ticket 35 mounts it innermost:
 * `ContextMenuHost { AlertHost { ToastHost { ActionSheetHost { GlassMenuHost { HandoffOverlayHost { SheetHost { content } } } } } } } }`,
 * inside every other overlay host and outside the root crossfade.
 *
 * One progress value drives everything: the presenting content shrinks
 * to [PageSheetDefaults.RecededScale] about its top centre behind a
 * `#000000` backdrop (the iOS card stack), each entry draws a scrim
 * under itself, and a stacked sheet recedes the one below it with a
 * 10 dp peek. All of it is read inside draw/layer lambdas, so a drag or
 * animation frame only redraws.
 */
@Composable
fun SheetHost(state: SheetHostState = remember { SheetHostState() }, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val tabletPx = with(density) { PageSheetDefaults.TabletBreakpoint.toPx() }
    val scrimMax = if (RobocoTheme.isDark) PageSheetDefaults.ScrimAlphaDark else PageSheetDefaults.ScrimAlphaLight

    // The host's scope is where every animated dismissal (and the
    // spring-back) runs, so they follow the composition's
    // MotionDurationScale; reduce motion is stored for 104's spec hook.
    SideEffect { state.attach(scope, reduceMotion) }

    CompositionLocalProvider(LocalSheetHost provides state) {
        Box(Modifier.fillMaxSize()) {
            // 1. Backdrop + presenting content. The black backdrop draws
            //    behind the content (drawBehind precedes the layer), so it
            //    shows in the 4 % side gaps and above the receded card.
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        if (rootRecession(state.entries.firstOrNull(), size.width, tabletPx) > 0f) {
                            drawRect(Color.Black)
                        }
                    },
            ) {
                // While any visible entry exists the presenting content has
                // no semantics at all — TalkBack cannot reach the screen
                // behind (iOS modal sheets are `accessibilityViewIsModal`).
                val anyVisible = state.entries.any { !it.hiddenForHandoff }
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = rootRecession(state.entries.firstOrNull(), size.width, tabletPx)
                            val s = PageSheetMath.recededScale(p)
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(0.5f, 0f)
                            translationY = PageSheetDefaults.RecessionTopOffset.toPx() * p
                            clip = p > 0f
                            if (p > 0f) shape = SmoothRoundedShape(PageSheetDefaults.CornerRadius * p)
                        }
                        .let { m -> if (anyVisible) m.clearAndSetSemantics { } else m },
                ) { content() }
            }

            // 2. The entries, bottom → top: each scrim is a full-window
            //    sibling just under its sheet, so a stacked entry's scrim
            //    dims the sheet below it (UIKit dims every presentation).
            state.entries.forEachIndexed { i, entry ->
                key(entry.key) {
                    SheetScrim(entry, scrimMax)
                    val sheetScope = remember(entry) { state.scopeFor(entry) }
                    when (val style = entry.style) {
                        is SheetStyle.Page -> PageSheet(
                            host = state,
                            entry = entry,
                            scope = sheetScope,
                            isTop = i == state.entries.lastIndex,
                            coveredProgress = { state.entries.getOrNull(i + 1)?.progress ?: 0f },
                        )
                        is SheetStyle.Custom -> style.Frame(entry) {
                            CompositionLocalProvider(LocalPageSheetScope provides sheetScope) {
                                entry.content(sheetScope)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The entry's dimming scrim, drawn just under its sheet: black at the
 * entry's [scrimMax] × [SheetEntry.progress], full window. Taps land on
 * it and do nothing (the chosen iOS 26 behaviour) — it consumes every
 * gesture, so the receded content is never reachable while a sheet is
 * up. While the entry is hidden for handoff the pointer input is gone,
 * so touches pass straight through.
 */
@Composable
private fun SheetScrim(entry: SheetEntry, scrimMax: Float) {
    val alpha = (entry.style as? SheetStyle.Custom)?.scrimAlpha ?: scrimMax
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha * entry.progress }
            .background(Color.Black)
            .testTag("sheet-scrim")
            // Hidden: no pointer-input node at all. A node whose block
            // returns early is still a hit-test target, and a hit sibling
            // stops the hit test before the presenting content below it.
            .then(if (entry.hiddenForHandoff) Modifier else Modifier.scrimConsumesTouches(entry)),
    )
}

/** The visible scrim's pointer input: consumes every gesture, does nothing. */
private fun Modifier.scrimConsumesTouches(entry: SheetEntry): Modifier =
    pointerInput(entry) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            var released = false
            while (!released) {
                val event = awaitPointerEvent()
                for (change in event.changes) if (!change.isConsumed) change.consume()
                released = event.changes.all { !it.pressed }
            }
        }
    }

/**
 * The presenting content's recession progress: the first entry's, forced
 * to 0 on tablets (regular-width sheets dim without the card stack) and
 * behind `recedesPresenter == false` customs (ticket 104).
 */
private fun rootRecession(first: SheetEntry?, windowWidthPx: Float, tabletPx: Float): Float {
    if (first == null) return 0f
    if (windowWidthPx >= tabletPx) return 0f
    if ((first.style as? SheetStyle.Custom)?.recedesPresenter == false) return 0f
    return first.progress
}
