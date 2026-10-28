package sh.roboco.android.shell

import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import sh.roboco.android.design.IosSpring
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Navigation-stack motion tokens (ticket 24). `UINavigationController`'s
 * push/pop curve is system motion with no numbers in the iOS source, so the
 * values are the approximations fixed by `android-app#19` §Navigation stacks
 * and `.scratch/android-app/research/04-android-stack.md` §6: a 0.5 s
 * critically damped spring, outgoing at −30 %, scrim 0 → 0.1.
 *
 * [Push] is defined here, not in ticket 04's `Motion.allSprings`: 04 leaves
 * nav motion to this ticket.
 */
object NavMotion {
    /** The push/pop spring: 0.5 s, critically damped (stiffness 341.02). */
    val Push = IosSpring("navPush", 0.50, 1.0f)

    /** The outgoing screen's parallax fraction of the host width. */
    const val ParallaxFraction = 0.3f

    /** The black scrim's peak alpha between the layers. */
    const val ScrimMaxAlpha = 0.1f

    /** The reduced-motion cross-fade duration. */
    const val ReducedFadeMs = 150

    /** The spring's animation visibility threshold. */
    const val VisibilityThreshold = 0.001f
}

/**
 * A screen in a [NavStack]. Routes are defined by consumer tickets
 * (76's `SessionScreenRoute`, 45's folder route, 101's `LicensesRoute`, …);
 * the stack never inspects anything but [hidesBottomChrome] and [paneTitle].
 */
interface NavRoute {
    /** Hide the tab pill + accessory while this route is on top (iOS `hidesBottomBarWhenPushed`). */
    val hidesBottomChrome: Boolean get() = false

    /** TalkBack pane title; null = none. */
    val paneTitle: String? get() = null
}

/** The direction a transition runs in. */
enum class NavKind { Push, Pop }

/**
 * UIKit-parity lifecycle callbacks, delivered by the host on the main thread
 * from inside the transition's `LaunchedEffect` — so after the composition
 * that first composed a newly pushed entry, when its `DisposableEffect`
 * listeners are already registered.
 *
 * The pairing follows `viewWill/DidAppear` / `viewWill/DidDisappear`
 * (`ios:Session/SessionViewController.swift:173-315`): only reversible work
 * in the `will*` callbacks (a cancelled interactive pop re-runs appear on the
 * top screen), irreversible teardown in [onDidDisappear] with
 * `removed = true`, which runs only after a committed pop or replace.
 */
interface NavEntryListener {
    fun onWillAppear(transition: NavTransition?) {}

    fun onDidAppear(animated: Boolean) {}

    /** `removing` = this entry leaves the stack if the transition commits (iOS `isMovingFromParent`). */
    fun onWillDisappear(transition: NavTransition?, removing: Boolean) {}

    /** `removed` = true only after a committed pop / replace (the place for irreversible teardown). */
    fun onDidDisappear(removed: Boolean) {}
}

/** Process-wide entry ids: entries are distinct by [id] even for equal routes. */
private val navEntryIds = AtomicLong(0L)

/**
 * One entry of the navigation stack. The state holder for a screen that
 * survives tab switches and cancelled back gestures: it keeps its listener
 * list, its retained objects and its [scope] until the entry is closed after
 * a committed pop.
 */
@Stable
class NavEntry internal constructor(val id: Long, val route: NavRoute) {

    private val listeners = CopyOnWriteArrayList<NavEntryListener>()
    private val retained = LinkedHashMap<Any, Any>()

    private var scopeImpl: CoroutineScope? = null
    private var _hasAppeared = mutableStateOf(false)
    private var _isClosed = mutableStateOf(false)

    /**
     * Lives until the entry is removed after a committed pop; cancelled in
     * [close]. `Dispatchers.Main.immediate + SupervisorJob()`. Created
     * lazily; first read after [close] returns an already-cancelled scope, so
     * a closed entry never hands out a live one.
     */
    val scope: CoroutineScope
        get() = scopeImpl ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            .also {
                scopeImpl = it
                if (isClosed) it.cancel()
            }

    /** Set after the first [NavEntryListener.onDidAppear]; snapshot state. */
    var hasAppeared: Boolean
        get() = _hasAppeared.value
        internal set(value) {
            _hasAppeared.value = value
        }

    /** True once the entry was closed after a committed pop / replace. Snapshot state. */
    var isClosed: Boolean
        get() = _isClosed.value
        internal set(value) {
            _isClosed.value = value
        }

    /** Registers a listener; returns its remove function. */
    @MainThread
    fun addListener(listener: NavEntryListener): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /**
     * Per-entry retained object: survives host disposal (tab hosts are
     * disposed on tab switches), so a transition interrupted by a tab switch
     * can still tear down through it. Repeated calls with the same key return
     * the same instance. `AutoCloseable` values are closed by [close] in
     * reverse creation order.
     */
    @MainThread
    fun <T : Any> retain(key: Any, factory: () -> T): T {
        @Suppress("UNCHECKED_CAST")
        val existing = retained[key] as? T
        if (existing != null) return existing
        val value = factory()
        retained[key] = value
        return value
    }

    /**
     * Read-only lookup of a retained object: the value [retain] stored
     * under [key], or null when nothing was retained yet (no factory
     * runs, nothing is created). Ticket 85's nav-leave adapters use it
     * to reach 78's entry-retained composer binding at call time —
     * after the screen composed it, long after the route's own
     * composition created them.
     */
    @MainThread
    internal fun <T : Any> retainedOrNull(key: Any): T? {
        @Suppress("UNCHECKED_CAST")
        val value = retained[key] as? T
        return value
    }

    /**
     * Cancels [scope] and closes retained `AutoCloseable`s in reverse
     * creation order. Idempotent. Runs only after `didDisappear(removed =
     * true)` was delivered (or for close-only entries: setRoutes removals,
     * popToRoot intermediates), so a cancelled back gesture never reaches it.
     */
    internal fun close() {
        if (isClosed) return
        isClosed = true
        scopeImpl?.cancel()
        val keys = retained.keys.toList()
        for (index in keys.indices.reversed()) {
            (retained.remove(keys[index]) as? AutoCloseable)?.close()
        }
    }

    internal fun notifyWillAppear(t: NavTransition?) {
        for (listener in listeners) listener.onWillAppear(t)
    }

    internal fun notifyDidAppear(animated: Boolean) {
        for (listener in listeners) listener.onDidAppear(animated)
        hasAppeared = true
    }

    internal fun notifyWillDisappear(t: NavTransition?, removing: Boolean) {
        for (listener in listeners) listener.onWillDisappear(t, removing)
    }

    internal fun notifyDidDisappear(removed: Boolean) {
        for (listener in listeners) listener.onDidDisappear(removed)
    }
}

/**
 * One running (or held) navigation transition: the layer pair, the entries
 * that leave on commit, and the progress the gesture or animation drives.
 */
@Stable
class NavTransition internal constructor(
    val kind: NavKind,
    val from: NavEntry,
    val to: NavEntry,
    /** Pop / setRoutes: entries that leave on commit; plain push: empty. */
    val removing: List<NavEntry>,
    val animated: Boolean,
    interactive: Boolean,
) {
    private val _progress = mutableStateOf(if (animated) 0f else 1f)
    private val _isInteractive = mutableStateOf(interactive)
    private val _cancelled = mutableStateOf<Boolean?>(null)
    private val onEndBlocks = mutableListOf<(cancelled: Boolean) -> Unit>()

    /**
     * 0 = `from` fully shown, 1 = `to` fully shown. Snapshot state; follows
     * the predictive back gesture with no smoothing. Initial value: 0 if
     * animated, 1 if not (non-animated ops never show a frame of `from`
     * alone).
     */
    var progress: Float
        get() = _progress.value
        internal set(value) {
            _progress.value = value
        }

    /** True while the predictive back gesture drives [progress]. Snapshot state. */
    var isInteractive: Boolean
        get() = _isInteractive.value
        internal set(value) {
            _isInteractive.value = value
        }

    /** null while running; true/false once ended. Snapshot state. */
    var cancelled: Boolean?
        get() = _cancelled.value
        internal set(value) {
            _cancelled.value = value
        }

    /** 1 = commit, 0 = cancel; set by the back handler on release/cancel. */
    internal var target: Float = 1f

    /** Interactive pop overridden by another op; the rest of its gesture is ignored. */
    internal var abandoned = false

    /** `will*` callbacks delivered. */
    internal var willSent = false

    /**
     * The coordinator completion (iOS `transitionCoordinator.animate(alongsideTransition:
     * completion:)` reading `context.isCancelled`). Fires immediately if the
     * transition already ended.
     */
    @MainThread
    fun onEnd(block: (cancelled: Boolean) -> Unit) {
        val ended = cancelled
        if (ended != null) {
            block(ended)
            return
        }
        onEndBlocks.add(block)
    }

    internal fun fireOnEnd(cancelled: Boolean) {
        for (block in onEndBlocks.toList()) block(cancelled)
        onEndBlocks.clear()
    }
}

/**
 * A navigation stack: the Android port of one tab's `UINavigationController`
 * (`ios:Shell/MainTabController.swift:24-35`), as a plain state holder that
 * lives outside composition so a tab keeps its back stack across tab
 * switches.
 *
 * `entries` is the **committed** stack: a push appends immediately, a pop
 * removes only at commit, so a cancelled back gesture never touches it.
 * [transition] is the one running transition; any new op first finishes it
 * ([finishInFlight]).
 *
 * With no host attached, ops complete synchronously at call time (the
 * `openSession` handoff path and plain construction); after
 * [holdHostlessOps] (test/preview only) they wait for a host instead.
 */
@Stable
class NavStack(root: NavRoute) {

    private val entryList = mutableStateListOf<NavEntry>()
    private val _transition = mutableStateOf<NavTransition?>(null)
    private var holdHostless = false

    /** Entries that left on a committed pop / replace, still composed until finalized. */
    internal val leaving = SnapshotStateList<NavEntry>()

    /** Number of attached [NavStackHost]s; the last detach runs [onHostDetached]. */
    internal var hostCount = 0

    private val chromeOnRootOnlyState = mutableStateOf(false)

    /**
     * Wave-10 37 (upstream #967/#971): the phone's one-row bottom bar
     * belongs to the front page alone, so on that stack every entry above
     * the root hides the bottom chrome — Settings, folders and whatever
     * they push — whatever its own [NavRoute.hidesBottomChrome] says (the
     * UIKit `hidesBottomBarWhenPushed` of the iPhone's pushed pages). Off
     * by default; the phone shell turns it on for the Sessions stack.
     */
    var chromeOnRootOnly: Boolean
        get() = chromeOnRootOnlyState.value
        set(value) {
            chromeOnRootOnlyState.value = value
        }

    /**
     * Whether [entry] hides the bottom chrome on this stack: its route's
     * own [NavRoute.hidesBottomChrome], or — with [chromeOnRootOnly] — any
     * entry but the root. Every chrome reader (the slot's cross-fade, the
     * host's layers, ticket 30's visibility) asks this, never the route.
     */
    fun hidesChrome(entry: NavEntry): Boolean =
        entry.route.hidesBottomChrome || (chromeOnRootOnly && entryList.firstOrNull()?.id != entry.id)

    init {
        entryList.add(NavEntry(navEntryIds.incrementAndGet(), root))
    }

    /** The committed stack, backed by a `SnapshotStateList`. Never empty. */
    val entries: List<NavEntry>
        get() = entryList

    val top: NavEntry
        get() = entryList.last()

    val canPop: Boolean
        get() = entryList.size > 1

    /** The one running (or held) transition; null when idle. Snapshot state. */
    val transition: NavTransition?
        get() = _transition.value

    private val bottomChromeState = derivedStateOf {
        val t = _transition.value
        if (t == null || t.cancelled != null) {
            if (hidesChrome(entryList.last())) 0f else 1f
        } else {
            val from = if (hidesChrome(t.from)) 0f else 1f
            val to = if (hidesChrome(t.to)) 0f else 1f
            lerp(from, to, t.progress)
        }
    }

    /**
     * The bottom-chrome slot's visibility (derived snapshot state): 1 when
     * the top shows chrome, 0 when it hides it, and the cross-fade lerp
     * during a transition. Tickets 30 and 85 read it (or [transition]'s
     * progress directly) for the accessory and composer cross-fades.
     */
    val bottomChromeVisibility: Float
        get() = bottomChromeState.value

    /**
     * Pushes [route] (iOS `pushViewController(_:animated:)`). Appends
     * immediately; the transition animates and completes on commit.
     * Pushing an equal route is allowed: entries are distinct by id.
     */
    @MainThread
    fun push(route: NavRoute, animated: Boolean = true): NavEntry {
        finishInFlight()
        val entry = NavEntry(navEntryIds.incrementAndGet(), route)
        val oldTop = entryList.last()
        entryList.add(entry)
        startTransition(
            NavTransition(
                kind = NavKind.Push,
                from = oldTop,
                to = entry,
                removing = emptyList(),
                animated = animated,
                interactive = false,
            ),
        )
        return entry
    }

    /**
     * Pops the top (iOS `popViewController(animated:)`). `entries` is
     * unchanged until commit; during the pop read [transition]'s `to` for the
     * destination. Returns false at depth 1.
     */
    @MainThread
    fun pop(animated: Boolean = true): Boolean {
        // finishInFlight() first: a running pop commits here and drops the
        // depth to 1, so the guard must see the post-commit depth (a second
        // pop or back press during the 500 ms window must not index -1).
        finishInFlight()
        if (entryList.size <= 1) return false
        val from = entryList.last()
        startTransition(
            NavTransition(
                kind = NavKind.Pop,
                from = from,
                to = entryList[entryList.size - 2],
                removing = listOf(from),
                animated = animated,
                interactive = false,
            ),
        )
        return true
    }

    /**
     * Pops to the root (iOS `popToRootViewController`). No-op at depth 1.
     * Intermediates are never placed, get no appear/disappear callbacks, and
     * only ever get `close()` — as UIKit.
     */
    @MainThread
    fun popToRoot(animated: Boolean = true) {
        finishInFlight()
        if (entryList.size <= 1) return
        startTransition(
            NavTransition(
                kind = NavKind.Pop,
                from = entryList.last(),
                to = entryList.first(),
                removing = entryList.drop(1),
                animated = animated,
                interactive = false,
            ),
        )
    }

    /**
     * Replaces the whole stack without animation (the tablet detail column,
     * iOS `setViewControllers`). For each new route an old entry with an
     * equal route is reused (first match, each old entry at most once);
     * non-reused old entries move to `leaving` immediately and get only
     * `close()`, except the old top when it leaves, which gets the full
     * appear/disappear pair (iOS `navigationController == nil`). Throws
     * [IllegalArgumentException] for an empty list: a stack is never empty.
     */
    @MainThread
    fun setRoutes(routes: List<NavRoute>) {
        require(routes.isNotEmpty()) { "setRoutes requires a non-empty route list (a NavStack is never empty)" }
        finishInFlight()
        val oldEntries = entryList.toList()
        val oldTop = oldEntries.last()
        val used = HashSet<NavEntry>()
        val newEntries = ArrayList<NavEntry>(routes.size)
        for (route in routes) {
            val match = oldEntries.firstOrNull { it !in used && it.route == route }
            if (match != null) {
                used.add(match)
                newEntries.add(match)
            } else {
                newEntries.add(NavEntry(navEntryIds.incrementAndGet(), route))
            }
        }
        val removed = oldEntries.filter { it !in used }
        entryList.clear()
        entryList.addAll(newEntries)
        for (entry in removed) leaving.add(entry)

        if (newEntries.last() === oldTop) {
            // Same top entry: no transition and no callbacks.
            if (hostCount == 0 && !holdHostless) {
                for (entry in removed) finalizeLeaving(entry)
            }
            return
        }
        startTransition(
            NavTransition(
                kind = NavKind.Push,
                from = oldTop,
                to = newEntries.last(),
                removing = removed,
                animated = false,
                interactive = false,
            ),
        )
    }

    /**
     * Finishes whatever transition is running. A non-interactive one snaps to
     * its target and completes synchronously (all callbacks fire, its commit
     * happens); an interactive one (finger still down) is abandoned and
     * cancelled at once, and the rest of that gesture is ignored. So
     * `popToRoot(false); push(x, true)` in one frame gives a
     * `Push(from = root, to = x)` — the iOS `openSession` behaviour.
     */
    internal fun finishInFlight() {
        val t = _transition.value ?: return
        if (t.cancelled != null) {
            _transition.value = null
            return
        }
        if (t.isInteractive) {
            t.abandoned = true
            t.target = 0f
        }
        complete(t, cancelled = t.target == 0f)
    }

    /**
     * Starts the interactive pop for a predictive back gesture
     * (`finishInFlight` runs first). Returns null at depth 1 — the handler
     * stays disabled there. The gesture then drives `t.progress` directly.
     */
    internal fun beginInteractivePop(): NavTransition? {
        // finishInFlight() first: a running pop commits here, so the depth
        // check must see the post-commit depth.
        finishInFlight()
        if (!canPop) return null
        val from = entryList.last()
        return startTransition(
            NavTransition(
                kind = NavKind.Pop,
                from = from,
                to = entryList[entryList.size - 2],
                removing = listOf(from),
                animated = true,
                interactive = true,
            ),
        )
    }

    /** Delivers the `will*` pair of the lifecycle table's row 1; idempotent. */
    internal fun sendWillEvents(t: NavTransition) {
        if (t.willSent) return
        t.willSent = true
        val fromLeaves = t.from in t.removing
        t.from.notifyWillDisappear(t, removing = fromLeaves)
        t.to.notifyWillAppear(t)
    }

    /**
     * Completes [t] (idempotent): fires `onEnd`, delivers the lifecycle
     * table's row 4, commits (a pop moves `t.removing` out of `entries` into
     * `leaving`), and clears the transition. Hostless and not held, the
     * removed entries are finalized right away.
     */
    internal fun complete(t: NavTransition, cancelled: Boolean) {
        if (t.cancelled != null) return
        sendWillEvents(t)
        t.progress = if (cancelled) 0f else 1f
        t.cancelled = cancelled
        t.fireOnEnd(cancelled)
        if (!cancelled) {
            if (t.kind == NavKind.Push) {
                // Plain push: from stays in the stack, buried under the new
                // top. setRoutes replace: from leaves (iOS navigationController == nil).
                t.from.notifyDidDisappear(removed = t.from in t.removing)
                t.to.notifyDidAppear(animated = t.animated)
            } else {
                // Committed pop: the entries leave the stack before the
                // callbacks, as UIKit removes the controller first.
                for (entry in t.removing) {
                    entryList.remove(entry)
                    leaving.add(entry)
                }
                t.from.notifyDidDisappear(removed = true)
                t.to.notifyDidAppear(animated = t.animated)
            }
            if (t.removing.isNotEmpty() && hostCount == 0 && !holdHostless) {
                for (entry in t.removing.toList()) finalizeLeaving(entry)
            }
        } else {
            // Cancelled: the below screen goes back under, the top screen
            // re-appears ("a cancelled pop also re-runs viewWillAppear"),
            // nothing is removed and nothing is closed.
            t.to.notifyWillDisappear(null, removing = false)
            t.from.notifyWillAppear(null)
            t.to.notifyDidDisappear(removed = false)
            t.from.notifyDidAppear(animated = false)
        }
        if (_transition.value === t) _transition.value = null
    }

    /**
     * Finalizes a leaving entry: `close()` (cancels `entry.scope`, closes
     * retained `AutoCloseable`s) and drops it from `leaving`, so the host's
     * recomposition disposes its composition. Only called once
     * `didDisappear(removed = true)` was delivered — or for close-only
     * entries (setRoutes removals, popToRoot intermediates).
     */
    internal fun finalizeLeaving(e: NavEntry) {
        leaving.remove(e)
        e.close()
    }

    /**
     * The last host detached: finish the in-flight transition (non-interactive
     * snaps to target and completes; interactive is abandoned and cancelled)
     * and finalize every leaving entry, so a transition interrupted by a tab
     * switch or the 840-dp shell swap does not keep a popped session open.
     * Deliberately delivers no appear/disappear callbacks of its own.
     */
    internal fun onHostDetached() {
        finishInFlight()
        for (entry in leaving.toList()) finalizeLeaving(entry)
    }

    /**
     * Closes every entry — [entries] and `leaving`, top first — with no
     * appear/disappear callbacks: the whole stack is going away (104's
     * Settings card teardown — iOS deallocates the nav controller and every
     * screen in it). Cancels each [NavEntry.scope] and closes its retained
     * `AutoCloseable`s, exactly as [NavEntry.close] does; idempotent.
     */
    @MainThread
    internal fun closeAll() {
        (entries + leaving).asReversed().forEach { it.close() }
    }

    /**
     * Test/preview only: while no host is attached, ops do NOT complete
     * synchronously; their transition waits for a host. Needed so
     * `push(...)` + [freeze] work before any host exists.
     */
    @VisibleForTesting
    internal fun holdHostlessOps() {
        holdHostless = true
    }

    /**
     * Marks the current transition interactive at [progress] (the host's
     * driver then leaves it alone). Throws [IllegalStateException] if there
     * is no transition (e.g. the op already completed hostless: call
     * [holdHostlessOps] first, or attach a host).
     */
    @VisibleForTesting
    internal fun freeze(progress: Float) {
        val t = _transition.value ?: throw IllegalStateException(
            "No transition to freeze: the op completed hostless — call holdHostlessOps() first, " +
                "or attach a host before the ops",
        )
        t.isInteractive = true
        t.progress = progress
    }

    private fun completeIfHostless(t: NavTransition) {
        if (hostCount == 0 && !holdHostless) complete(t, cancelled = false)
    }

    /** Assigns the new transition and completes it hostlessly if it must. */
    private fun startTransition(t: NavTransition): NavTransition {
        _transition.value = t
        completeIfHostless(t)
        return t
    }
}
