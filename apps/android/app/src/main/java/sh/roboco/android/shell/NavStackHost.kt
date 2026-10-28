package sh.roboco.android.shell

import android.content.res.Configuration
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CancellationException
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.Motion
import sh.roboco.android.design.RobocoTheme

/** The [NavStack] of the host currently composing this content. */
val LocalNavStack = staticCompositionLocalOf<NavStack> { error("No NavStack: compose content inside NavStackHost") }

/** The [NavEntry] whose screen this content renders. */
val LocalNavEntry = staticCompositionLocalOf<NavEntry> { error("No NavEntry: compose content inside NavStackHost") }

/** [LocalNavEntry] without the error default: null outside a `NavStackHost` (galleries, previews, plain hosts). */
val LocalNavEntryOrNull = staticCompositionLocalOf<NavEntry?> { null }

/** Layout ids of the host's non-entry children. */
private object NavScrimId
private object NavChromeId
private object NavBlockerId

/** The touch-blocker z: above every layer including the chrome. */
private const val BlockerZ = 10f

/**
 * Renders a [NavStack]: the Android port of one tab's `UINavigationController`
 * view. Pushes slide the incoming screen in from the trailing edge while the
 * outgoing screen parallaxes under a scrim; pops reverse it; Android's
 * predictive back gesture drives the pop interactively and can be cancelled.
 *
 * One `Layout` places at most two entries (idle: `top`; transition: `from`
 * and `to`) with `placeWithLayer`, so the animation re-runs **placement**
 * only, never measure. Leaving entries stay composed until their teardown
 * has been delivered, then leave composition. During a transition a top-z
 * layer consumes all pointer events at `PointerEventPass.Initial`, as UIKit
 * disables interaction during transitions.
 *
 * [active] is false for unselected tabs: the back handler stays off, so an
 * unselected tab never swallows system back. Flipping [active] delivers the
 * tab-switch appear/disappear pair (UIKit does the same on tab change).
 *
 * [bottomChrome] is an optional slot that is **always composed** (state such
 * as "minimized" survives) and only changes placement: it rides whichever
 * layer shows it (`hidesBottomBarWhenPushed` + the tab bar travelling with
 * the transition). The phone tab shell does not use it (ticket 30 emulates
 * the z-order outside the host); the gallery, previews and goldens do.
 */
@Composable
fun NavStackHost(
    stack: NavStack,
    active: Boolean,
    modifier: Modifier = Modifier,
    bottomChrome: (@Composable () -> Unit)? = null,
    content: @Composable (NavEntry) -> Unit,
) {
    val transition = stack.transition
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val reduce = LocalReduceMotion.current
    val focusManager = LocalFocusManager.current

    // ---------------------------------------------------------------- back
    // Disabled at depth 1 and for unselected tabs, or the app could never be
    // left. Sheets and menus register their handlers later in composition, so
    // they win while shown.
    val backEnabled = active && (stack.canPop || transition?.kind == NavKind.Pop)
    PredictiveBackHandler(enabled = backEnabled) { events ->
        val t = stack.beginInteractivePop() ?: return@PredictiveBackHandler
        try {
            events.collect { event ->
                // The system already applies its own non-linear progress;
                // no extra interpolation. Late events of an abandoned
                // gesture are ignored.
                if (!t.abandoned) t.progress = event.progress.coerceIn(0f, 1f)
            }
            // Released to commit: the composition-scope effect animates the
            // rest and commits — never this handler's coroutine, which the
            // system cancels on a back cancel.
            if (!t.abandoned) {
                t.target = 1f
                t.isInteractive = false
            }
        } catch (e: CancellationException) {
            if (!t.abandoned) {
                t.target = 0f
                t.isInteractive = false
            }
            throw e
        }
    }

    // -------------------------------------------------------- the driver
    // Delivers the will* pair after the composition that first composed the
    // pushed entry, animates progress, completes the transition. Relaunches
    // when the gesture flips isInteractive, so commit and cancel animations
    // run in composition scope.
    LaunchedEffect(transition, transition?.isInteractive) {
        val t = transition ?: return@LaunchedEffect
        stack.sendWillEvents(t)
        if (t.isInteractive) return@LaunchedEffect // the back handler drives progress
        if (t.target == 1f && t.kind == NavKind.Pop) focusManager.clearFocus(force = true)
        if (t.animated) {
            val spec: AnimationSpec<Float> = if (reduce) {
                tween(NavMotion.ReducedFadeMs, easing = Motion.EaseInOut)
            } else {
                NavMotion.Push.spec(NavMotion.VisibilityThreshold)
            }
            // Never snap on interrupt: start from where the gesture or the
            // interrupted transition left the progress. The onEach block
            // receives the Animatable; write through while the transition
            // has not been completed by a newer op.
            Animatable(t.progress).animateTo(t.target, spec) {
                if (t.cancelled == null) t.progress = value
            }
        } else {
            t.progress = t.target
        }
        stack.complete(t, cancelled = t.target == 0f)
    }

    // ------------------------------------------------------ the sweep
    // Finalizes leaving entries once nothing holds them: after the transition
    // completed (didDisappear delivered), close-only entries included.
    val leavingList = stack.leaving.toList()
    LaunchedEffect(leavingList, transition) {
        for (entry in leavingList) {
            val pending = stack.transition
            val held = pending != null && pending.cancelled == null &&
                (entry === pending.from || entry in pending.removing)
            if (!held) stack.finalizeLeaving(entry)
        }
    }

    // ------------------------------------------------ tab-switch callbacks
    var firstActiveRun by remember { mutableStateOf(true) }
    LaunchedEffect(active) {
        if (firstActiveRun) {
            firstActiveRun = false
            return@LaunchedEffect
        }
        val top = stack.top
        if (active) {
            top.notifyWillAppear(null)
            top.notifyDidAppear(animated = false)
        } else {
            top.notifyWillDisappear(null, removing = false)
            top.notifyDidDisappear(removed = false)
        }
    }

    // -------------------------------------------------------- host attach
    DisposableEffect(stack) {
        stack.hostCount++
        onDispose {
            stack.hostCount--
            if (stack.hostCount == 0) stack.onHostDetached()
        }
    }

    // --------------------------------------------------------- the layout
    val belowEntry = transition?.let { if (it.kind == NavKind.Push) it.from else it.to }
    Layout(
        modifier = modifier
            .clipToBounds()
            .background(RobocoTheme.palette.background),
        content = {
            // entries + leaving in one keyed sequence, so an entry that moves
            // from entries to leaving at commit keeps its composition slot —
            // its onDispose runs only when it is finalized, after
            // didDisappear(removed = true).
            (stack.entries + stack.leaving).forEach { entry ->
                key(entry.id) {
                    CompositionLocalProvider(
                        LocalNavStack provides stack,
                        LocalNavEntry provides entry,
                        LocalNavEntryOrNull provides entry,
                    ) {
                        // During a transition the below layer (and a leaving
                        // `from`) is clearAndSetSemantics: TalkBack announces
                        // the above layer, which keeps its pane title.
                        val cleared = transition != null &&
                            (entry === belowEntry ||
                                (entry === transition.from && entry in stack.leaving))
                        val entryModifier = if (cleared) {
                            Modifier.clearAndSetSemantics { }
                        } else {
                            entry.route.paneTitle?.let { title ->
                                Modifier.semantics { paneTitle = title }
                            } ?: Modifier
                        }
                        Box(Modifier.layoutId(entry).then(entryModifier)) {
                            content(entry)
                        }
                    }
                }
            }
            // The scrim: drawn at draw time from the live progress, so the
            // gesture drives it with no re-layout. Not placed under reduce.
            Box(
                Modifier
                    .layoutId(NavScrimId)
                    .drawBehind {
                        val t = transition ?: return@drawBehind
                        val alpha = navLayerTransforms(
                            t.kind,
                            t.progress,
                            size.width,
                            rtl,
                            reduce,
                        ).scrimAlpha
                        if (alpha > 0f) drawRect(Color.Black, alpha = alpha)
                    },
            )
            // The chrome slot: always composed, only its placement changes.
            if (bottomChrome != null) {
                Box(Modifier.layoutId(NavChromeId)) { bottomChrome() }
            }
            // The touch-blocker: full size, top z, consumes everything at the
            // Initial pass while a transition runs.
            if (transition != null) {
                Box(
                    Modifier
                        .layoutId(NavBlockerId)
                        .fillMaxSize()
                        .consumeAllPointers(),
                )
            }
        },
    ) { measurables, constraints ->
        // Group the children by their layout ids.
        val entryMeasurables = HashMap<NavEntry, Measurable>(measurables.size)
        var scrimMeasurable: Measurable? = null
        var chromeMeasurable: Measurable? = null
        var blockerMeasurable: Measurable? = null
        for (measurable in measurables) {
            when (val id = measurable.layoutId) {
                is NavEntry -> entryMeasurables[id] = measurable
                NavScrimId -> scrimMeasurable = measurable
                NavChromeId -> chromeMeasurable = measurable
                NavBlockerId -> blockerMeasurable = measurable
            }
        }

        val t = transition
        val below = if (t != null) belowEntry else null
        val above = t?.let { if (it.kind == NavKind.Push) it.to else it.from }

        // Only the placed entries are measured; the others stay composed but
        // unmeasured (and therefore unplaced and outside the a11y tree).
        val placed = if (t != null) listOfNotNull(below, above) else listOf(stack.top)
        val bounded = constraints.hasBoundedWidth && constraints.hasBoundedHeight
        val entryConstraints = if (bounded) {
            Constraints.fixed(constraints.maxWidth, constraints.maxHeight)
        } else {
            constraints
        }
        var widest = 0
        var tallest = 0
        val placeables = HashMap<NavEntry, Placeable>(placed.size)
        for (entry in placed) {
            val measurable = entryMeasurables[entry] ?: continue
            val placeable = measurable.measure(entryConstraints)
            placeables[entry] = placeable
            widest = maxOf(widest, placeable.width)
            tallest = maxOf(tallest, placeable.height)
        }
        val w = if (constraints.hasBoundedWidth) constraints.maxWidth else maxOf(widest, constraints.minWidth)
        val h = if (constraints.hasBoundedHeight) constraints.maxHeight else maxOf(tallest, constraints.minHeight)

        val chromeMode = if (t != null && below != null && above != null) {
            chromePlacement(
                showBelow = !stack.hidesChrome(below),
                showAbove = !stack.hidesChrome(above),
            )
        } else {
            if (stack.hidesChrome(stack.top)) ChromeLayer.Hidden else ChromeLayer.Fixed
        }
        val chromePlaceable = if (chromeMode != ChromeLayer.Hidden) {
            chromeMeasurable?.measure(Constraints(maxWidth = w, maxHeight = h))
        } else {
            null
        }
        val scrimPlaceable = if (t != null && !reduce) {
            scrimMeasurable?.measure(Constraints.fixed(w, h))
        } else {
            null
        }
        val blockerPlaceable = if (t != null) {
            blockerMeasurable?.measure(Constraints.fixed(w, h))
        } else {
            null
        }

        // z-order (low → high), from the chrome table:
        // RidesBelow: below, chrome, scrim, above — the chrome is dimmed by
        // the scrim and covered by the incoming screen, as the UIKit tab bar is.
        // otherwise:   below, scrim, above, chrome.
        val ridesBelow = chromeMode == ChromeLayer.RidesBelow
        val scrimZ = if (ridesBelow) 2f else 1f
        val aboveZ = if (ridesBelow) 3f else 2f
        val chromeZ = if (ridesBelow) 1f else 3f

        layout(w, h) {
            // The transforms are computed inside the placement block: state
            // reads here re-run placement only, never measure.
            if (t != null) {
                val tr = navLayerTransforms(t.kind, t.progress, w.toFloat(), rtl, reduce)
                placeables[below]?.let { belowPlaceable ->
                    belowPlaceable.placeWithLayer(0, 0, 0f) {
                        translationX = tr.belowTx
                        alpha = 1f
                    }
                }
                placeables[above]?.let { abovePlaceable ->
                    abovePlaceable.placeWithLayer(0, 0, aboveZ) {
                        translationX = tr.aboveTx
                        alpha = tr.aboveAlpha
                    }
                }
                scrimPlaceable?.let { scrim -> scrim.place(0, 0, scrimZ) }
                chromePlaceable?.let { chrome ->
                    val y = h - chrome.height
                    when (chromeMode) {
                        ChromeLayer.RidesBelow -> chrome.placeWithLayer(0, y, chromeZ) {
                            translationX = tr.belowTx
                            alpha = 1f
                        }

                        ChromeLayer.RidesAbove -> chrome.placeWithLayer(0, y, chromeZ) {
                            translationX = tr.aboveTx
                            alpha = tr.aboveAlpha
                        }

                        else -> chrome.placeWithLayer(0, y, chromeZ) {
                            translationX = 0f
                            alpha = 1f
                        }
                    }
                }
                blockerPlaceable?.let { blocker -> blocker.place(0, 0, BlockerZ) }
            } else {
                placeables[stack.top]?.let { top ->
                    top.placeWithLayer(0, 0, 0f) {
                        translationX = 0f
                        alpha = 1f
                    }
                }
                chromePlaceable?.let { chrome ->
                    chrome.placeWithLayer(0, h - chrome.height, 1f) {
                        translationX = 0f
                        alpha = 1f
                    }
                }
            }
        }
    }
}

/**
 * Registers a [NavEntryListener] on [LocalNavEntry] for the composable's
 * lifetime, always invoking the latest lambdas (rememberUpdatedState). The
 * entry delivers the callbacks; a cancelled back gesture removes nothing, so
 * the listener keeps firing until the screen is really torn down.
 */
@Composable
fun NavEntryEffect(
    onWillAppear: ((NavTransition?) -> Unit)? = null,
    onDidAppear: ((Boolean) -> Unit)? = null,
    onWillDisappear: ((NavTransition?, Boolean) -> Unit)? = null,
    onDidDisappear: ((Boolean) -> Unit)? = null,
) {
    val entry = LocalNavEntry.current
    val willAppear by rememberUpdatedState(onWillAppear)
    val didAppear by rememberUpdatedState(onDidAppear)
    val willDisappear by rememberUpdatedState(onWillDisappear)
    val didDisappear by rememberUpdatedState(onDidDisappear)
    DisposableEffect(entry) {
        val listener = object : NavEntryListener {
            override fun onWillAppear(transition: NavTransition?) {
                willAppear?.invoke(transition)
            }

            override fun onDidAppear(animated: Boolean) {
                didAppear?.invoke(animated)
            }

            override fun onWillDisappear(transition: NavTransition?, removing: Boolean) {
                willDisappear?.invoke(transition, removing)
            }

            override fun onDidDisappear(removed: Boolean) {
                didDisappear?.invoke(removed)
            }
        }
        val remove = entry.addListener(listener)
        onDispose { remove() }
    }
}

/** A full-size layer that consumes every pointer change at the Initial pass. */
private fun Modifier.consumeAllPointers(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            for (change in event.changes) change.consume()
        }
    }
}

// ---------------------------------------------------------------------
// Previews (step 8): a push frozen at 0.5, light and dark. Without
// holdHostlessOps() the push completes synchronously (no host yet) and
// freeze has nothing to freeze.
// ---------------------------------------------------------------------

@Composable
private fun NavPushHalfPreviewContent() {
    RobocoTheme {
        val stack = remember {
            NavStack(DemoRoute("Sessions")).apply {
                holdHostlessOps()
                push(DemoRoute("Session", hides = true))
                freeze(0.5f)
            }
        }
        NavStackHost(
            stack = stack,
            active = true,
            bottomChrome = { NavFakeChrome() },
        ) { entry -> NavDemoScreen(entry) }
    }
}

@Preview(name = "push 0.5 light", widthDp = 360, heightDp = 640)
@Composable
private fun NavPushHalfLightPreview() {
    NavPushHalfPreviewContent()
}

@Preview(name = "push 0.5 dark", widthDp = 360, heightDp = 640, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NavPushHalfDarkPreview() {
    NavPushHalfPreviewContent()
}
