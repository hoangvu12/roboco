package sh.roboco.android.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ticket 24's state-machine seam (plain JVM): the committed stack, the
 * one-transition-at-a-time rule, UIKit-parity callback order, teardown only
 * after a committed pop, and the hostless / held-op modes.
 *
 * The stack's snapshot state is pure Kotlin, so the whole machine runs
 * without Robolectric. Ops without a host complete synchronously; to observe
 * the callback order of an *animated* flow the tests hold ops
 * ([NavStack.holdHostlessOps]) and drive [NavStack.sendWillEvents] /
 * [NavStack.complete] exactly as the host's driver effect does.
 */
class NavStackTest {

    /** A route with value equality, like the folder and list routes. */
    private data class TestRoute(
        val name: String,
        override val hidesBottomChrome: Boolean = false,
        override val paneTitle: String? = name,
    ) : NavRoute

    /** Records every NavEntryListener callback as "tag.event(args)". */
    private class RecordingListener(private val tag: String) : NavEntryListener {
        val log = mutableListOf<String>()
        private fun t(t: NavTransition?) = t?.kind?.name?.lowercase() ?: "null"
        override fun onWillAppear(t: NavTransition?) {
            log += "$tag.willAppear(${t(t)})"
        }

        override fun onDidAppear(animated: Boolean) {
            log += "$tag.didAppear($animated)"
        }

        override fun onWillDisappear(t: NavTransition?, removing: Boolean) {
            log += "$tag.willDisappear(${t(t)},$removing)"
        }

        override fun onDidDisappear(removed: Boolean) {
            log += "$tag.didDisappear($removed)"
        }
    }

    private class CountingCloseable(val tag: String, val closes: MutableList<String>) : AutoCloseable {
        override fun close() {
            closes += tag
        }
    }

    // ------------------------------------------------------------- push

    @Test
    fun pushAppendsImmediately() {
        val stack = NavStack(TestRoute("root"))
        val root = stack.top
        stack.holdHostlessOps()
        val b = stack.push(TestRoute("b"))
        assertEquals(listOf(root, b), stack.entries)
        assertEquals(2, stack.entries.size)
        assertFalse(b === root)
        val t = stack.transition
        assertNotNull(t)
        assertEquals(NavKind.Push, t!!.kind)
        assertSame(root, t.from)
        assertSame(b, t.to)
        assertTrue(t.removing.isEmpty())
        assertTrue(t.animated)
        assertFalse(t.isInteractive)
        assertEquals(0f, t.progress, 1e-6f)
        assertNull(t.cancelled)
    }

    @Test
    fun nonAnimatedPushStartsAtProgressOne() {
        val stack = NavStack(TestRoute("root"))
        stack.holdHostlessOps()
        stack.push(TestRoute("b"), animated = false)
        val t = stack.transition!!
        assertFalse(t.animated)
        assertEquals(1f, t.progress, 1e-6f)
    }

    @Test
    fun hostlessPushCompletesSynchronously() {
        val stack = NavStack(TestRoute("root"))
        val rootRec = RecordingListener("root")
        stack.top.addListener(rootRec)
        val b = stack.push(TestRoute("b"))
        // No host: the transition completed at call time with the same
        // callback order (B's side had no listener registered yet).
        assertNull(stack.transition)
        assertEquals(listOf("root.willDisappear(push,false)", "root.didDisappear(false)"), rootRec.log)
        assertTrue(b.hasAppeared)
        assertFalse(b.isClosed)
        assertEquals(listOf(stack.entries[0], b), stack.entries)
    }

    @Test
    fun pushCallbackOrderMatchesTheLifecycleTable() {
        val stack = NavStack(TestRoute("root"))
        stack.holdHostlessOps()
        val rootRec = RecordingListener("root")
        stack.top.addListener(rootRec)
        val b = stack.push(TestRoute("b"))
        val bRec = RecordingListener("b")
        b.addListener(bRec)
        val t = stack.transition!!
        val ends = mutableListOf<Boolean>()
        t.onEnd { ends += it }

        stack.sendWillEvents(t)
        assertEquals(listOf("root.willDisappear(push,false)", "b.willAppear(push)"), rootRec.log + bRec.log)
        // Idempotent: the host's effect may re-deliver.
        stack.sendWillEvents(t)
        assertEquals(2, rootRec.log.size + bRec.log.size)

        stack.complete(t, cancelled = false)
        assertEquals(listOf("root.willDisappear(push,false)", "root.didDisappear(false)"), rootRec.log)
        assertEquals(listOf("b.willAppear(push)", "b.didAppear(true)"), bRec.log)
        assertEquals(listOf(false), ends)
        assertTrue(b.hasAppeared)
        assertNull(stack.transition)
    }

    @Test
    fun pushOfTheSameRouteTwiceCreatesDistinctEntries() {
        val stack = NavStack(TestRoute("root"))
        val first = stack.push(TestRoute("x"))
        val second = stack.push(TestRoute("x"))
        assertFalse(first === second)
        assertEquals(3, stack.entries.size)
    }

    // ------------------------------------------------------------- pop

    @Test
    fun popHoldsEntriesUntilCommitThenMovesToLeaving() {
        val stack = NavStack(TestRoute("root"))
        stack.holdHostlessOps()
        stack.push(TestRoute("b"))
        stack.complete(stack.transition!!, cancelled = false)
        val b = stack.entries[1]
        val bRec = RecordingListener("b")
        b.addListener(bRec)
        val rootRec = RecordingListener("root")
        stack.entries[0].addListener(rootRec)
        val ends = mutableListOf<Boolean>()

        val t = stack.beginInteractivePop()!!
        t.onEnd { ends += it }
        assertEquals(NavKind.Pop, t.kind)
        assertSame(b, t.from)
        assertSame(stack.entries[0], t.to)
        assertEquals(listOf(b), t.removing)
        // Interactive: progress starts at 0 and entries are untouched.
        assertEquals(0f, t.progress, 1e-6f)
        assertTrue(t.isInteractive)
        assertEquals(2, stack.entries.size)

        stack.sendWillEvents(t)
        assertEquals(listOf("b.willDisappear(pop,true)", "root.willAppear(pop)"), bRec.log + rootRec.log)

        stack.complete(t, cancelled = false)
        // Commit: the entry moved out of entries into leaving, but is still
        // composed (not closed) until the host's sweep finalizes it.
        assertEquals(listOf<NavEntry>(stack.entries[0]), stack.entries)
        assertEquals(1, stack.entries.size)
        assertEquals(listOf(b), stack.leaving.toList())
        assertEquals(listOf("b.willDisappear(pop,true)", "b.didDisappear(true)"), bRec.log)
        assertEquals(listOf("root.willAppear(pop)", "root.didAppear(true)"), rootRec.log)
        assertEquals(listOf(false), ends)
        assertFalse(b.isClosed)

        stack.finalizeLeaving(b)
        assertTrue(b.isClosed)
        assertTrue(stack.leaving.isEmpty())
    }

    @Test
    fun popAtDepthOneReturnsFalse() {
        val stack = NavStack(TestRoute("root"))
        assertFalse(stack.pop())
        assertNull(stack.transition)
        assertEquals(1, stack.entries.size)

        stack.holdHostlessOps()
        stack.push(TestRoute("b"))
        stack.complete(stack.transition!!, cancelled = false)
        // Depth 2 now; popping is possible again, at depth 1 it was not.
        assertTrue(stack.pop())
    }

    @Test
    fun cancelledInteractivePopRestoresWithoutRemoval() {
        val stack = NavStack(TestRoute("root"))
        stack.holdHostlessOps()
        stack.push(TestRoute("b"))
        stack.complete(stack.transition!!, cancelled = false)
        val root = stack.entries[0]
        val b = stack.entries[1]
        val rootRec = RecordingListener("root")
        val bRec = RecordingListener("b")
        root.addListener(rootRec)
        b.addListener(bRec)
        val ends = mutableListOf<Boolean>()

        val t = stack.beginInteractivePop()!!
        t.onEnd { ends += it }
        stack.sendWillEvents(t)
        assertEquals(listOf("b.willDisappear(pop,true)", "root.willAppear(pop)"), bRec.log + rootRec.log)

        stack.complete(t, cancelled = true)
        // Nothing removed, nothing closed; the cancel-restore callbacks ran
        // with a null transition; the top re-appeared.
        assertEquals(listOf(root, b), stack.entries)
        assertEquals(0, stack.leaving.size)
        assertFalse(b.isClosed)
        assertEquals(listOf("b.willDisappear(pop,true)", "b.willAppear(null)", "b.didAppear(false)"), bRec.log)
        assertEquals(
            listOf("root.willAppear(pop)", "root.willDisappear(null,false)", "root.didDisappear(false)"),
            rootRec.log,
        )
        assertEquals(listOf(true), ends)
        assertTrue(b.hasAppeared)
        assertNull(stack.transition)
    }

    @Test
    fun popToRootFromDepthThreeClosesTheIntermediateSilently() {
        val stack = NavStack(TestRoute("root"))
        stack.holdHostlessOps()
        stack.push(TestRoute("b"))
        stack.complete(stack.transition!!, cancelled = false)
        stack.push(TestRoute("c"))
        stack.complete(stack.transition!!, cancelled = false)
        val root = stack.entries[0]
        val b = stack.entries[1]
        val c = stack.entries[2]
        val rootRec = RecordingListener("root")
        val bRec = RecordingListener("b")
        val cRec = RecordingListener("c")
        root.addListener(rootRec)
        b.addListener(bRec)
        c.addListener(cRec)

        stack.popToRoot()
        val t = stack.transition!!
        assertSame(c, t.from)
        assertSame(root, t.to)
        assertEquals(listOf(b, c), t.removing)
        assertEquals(3, stack.entries.size)

        stack.sendWillEvents(t)
        stack.complete(t, cancelled = false)
        assertEquals(listOf<NavEntry>(root), stack.entries)
        // The intermediate b got no appear/disappear callbacks at all.
        assertEquals(0, bRec.log.size)
        assertEquals(listOf("c.willDisappear(pop,true)", "c.didDisappear(true)"), cRec.log)
        assertEquals(listOf("root.willAppear(pop)", "root.didAppear(true)"), rootRec.log)

        stack.finalizeLeaving(b)
        stack.finalizeLeaving(c)
        assertTrue(b.isClosed)
        assertTrue(c.isClosed)
        assertTrue(stack.leaving.isEmpty())
    }

    @Test
    fun popToRootAtDepthOneIsANoop() {
        val stack = NavStack(TestRoute("root"))
        stack.popToRoot()
        assertNull(stack.transition)
        assertEquals(1, stack.entries.size)
    }

    // A second op during a running pop: the depth guard must see the
    // post-commit depth, never index -1.

    @Test
    fun aSecondPopDuringARunningPopCommitsItAndReturnsFalseAtDepthOne() {
        val stack = NavStack(TestRoute("root"))
        stack.holdHostlessOps()
        stack.push(TestRoute("b"))
        stack.complete(stack.transition!!, cancelled = false)

        assertTrue(stack.pop())
        // Mid-pop (nothing animated it yet), a second pop arrives: the first
        // commits synchronously and the second finds depth 1.
        assertFalse(stack.pop())
        assertEquals(1, stack.entries.size)
        assertNull(stack.transition)
        assertTrue(stack.entries[0].route.paneTitle == "root")
        assertFalse(stack.pop())
    }

    @Test
    fun beginInteractivePopDuringARunningPopCommitsItAndReturnsNull() {
        val stack = NavStack(TestRoute("root"))
        stack.holdHostlessOps()
        stack.push(TestRoute("b"))
        stack.complete(stack.transition!!, cancelled = false)

        assertTrue(stack.pop())
        assertNull(stack.beginInteractivePop())
        assertEquals(1, stack.entries.size)
    }

    @Test
    fun popToRootDuringARunningPopCommitsItFirst() {
        val stack = NavStack(TestRoute("root"))
        stack.holdHostlessOps()
        stack.push(TestRoute("b"))
        stack.complete(stack.transition!!, cancelled = false)
        stack.push(TestRoute("c"))
        stack.complete(stack.transition!!, cancelled = false)

        assertTrue(stack.pop()) // the c → b pop is now running (held)
        stack.popToRoot()
        // The running pop committed first; popToRoot then ran from b.
        val t = stack.transition!!
        assertEquals(NavKind.Pop, t.kind)
        assertSame(stack.entries[1], t.from)
        assertSame(stack.entries[0], t.to)
        assertEquals(2, stack.entries.size)
    }

    // -------------------------------------------------------- setRoutes

    @Test
    fun setRoutesReplacesTheStackImmediatelyAndReusesEntries() {
        val stack = NavStack(TestRoute("root"))
        stack.holdHostlessOps()
        stack.push(TestRoute("b"))
        stack.complete(stack.transition!!, cancelled = false)
        stack.push(TestRoute("c"))
        stack.complete(stack.transition!!, cancelled = false)
        val root = stack.entries[0]
        val b = stack.entries[1]
        val c = stack.entries[2]
        val cRec = RecordingListener("c")
        c.addListener(cRec)

        val dRoute = TestRoute("d")
        stack.setRoutes(listOf(TestRoute("root"), dRoute))
        // entries replaced at op time; the root entry is reused by equality,
        // old top c and b are already in leaving.
        assertSame(root, stack.entries[0])
        val d = stack.entries[1]
        assertEquals(dRoute, d.route)
        assertEquals(2, stack.entries.size)
        assertEquals(setOf(b, c), stack.leaving.toSet())

        val t = stack.transition!!
        assertEquals(NavKind.Push, t.kind)
        assertSame(c, t.from)
        assertSame(d, t.to)
        assertEquals(setOf(b, c), t.removing.toSet())
        assertFalse(t.animated)
        assertEquals(1f, t.progress, 1e-6f)

        val dRec = RecordingListener("d")
        d.addListener(dRec)
        stack.sendWillEvents(t)
        stack.complete(t, cancelled = false)
        assertEquals(listOf("c.willDisappear(push,true)", "c.didDisappear(true)"), cRec.log)
        assertEquals(listOf("d.willAppear(push)", "d.didAppear(false)"), dRec.log)
        assertTrue(d.hasAppeared)

        stack.finalizeLeaving(b)
        stack.finalizeLeaving(c)
        assertTrue(stack.leaving.isEmpty())
    }

    @Test
    fun setRoutesReusesEachOldEntryAtMostOnce() {
        val stack = NavStack(TestRoute("root"))
        stack.holdHostlessOps()
        stack.push(TestRoute("x"))
        stack.complete(stack.transition!!, cancelled = false)
        val x = stack.entries[1]

        stack.setRoutes(listOf(TestRoute("x"), TestRoute("x")))
        assertSame(x, stack.entries[0])
        assertEquals(2, stack.entries.size)
        assertFalse(x === stack.entries[1])
        assertEquals("x", stack.entries[1].route.paneTitle)
    }

    @Test
    fun setRoutesWithTheSameTopLogsNothing() {
        val stack = NavStack(TestRoute("root"))
        stack.holdHostlessOps()
        stack.push(TestRoute("b"))
        stack.complete(stack.transition!!, cancelled = false)
        stack.push(TestRoute("c"))
        stack.complete(stack.transition!!, cancelled = false)
        val root = stack.entries[0]
        val b = stack.entries[1]
        val c = stack.entries[2]
        val rootRec = RecordingListener("root")
        val bRec = RecordingListener("b")
        val cRec = RecordingListener("c")
        root.addListener(rootRec)
        b.addListener(bRec)
        c.addListener(cRec)

        // The new top is the old top entry (c is reused on top): no
        // transition and no callbacks; the removed non-top b only gets
        // close().
        stack.setRoutes(listOf(TestRoute("root"), TestRoute("c")))
        assertNull(stack.transition)
        assertEquals(0, rootRec.log.size)
        assertEquals(0, bRec.log.size)
        assertEquals(0, cRec.log.size)
        assertSame(c, stack.top)
        assertEquals(listOf(b), stack.leaving.toList())
        assertFalse(b.isClosed)
        stack.finalizeLeaving(b)
        assertTrue(b.isClosed)
        assertEquals(0, bRec.log.size)

        // At depth 1, replacing with the same top route is the same case.
        stack.setRoutes(listOf(TestRoute("c")))
        assertNull(stack.transition)
        assertEquals(0, rootRec.log.size + bRec.log.size + cRec.log.size)
    }

    @Test
    fun setRoutesWithAnEmptyListThrows() {
        val stack = NavStack(TestRoute("root"))
        try {
            stack.setRoutes(emptyList())
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertEquals(1, stack.entries.size)
        }
    }

    // ---------------------------------------------------- finishInFlight

    @Test
    fun aSecondOpDuringARunningPushCompletesItSynchronously() {
        val stack = NavStack(TestRoute("root"))
        stack.holdHostlessOps()
        val rootRec = RecordingListener("root")
        stack.top.addListener(rootRec)
        val b = stack.push(TestRoute("b"))
        val bRec = RecordingListener("b")
        b.addListener(bRec)

        val c = stack.push(TestRoute("c"))
        // The first push snapped to its target and completed: all callbacks
        // fired, nothing lost.
        assertEquals(
            listOf(
                "root.willDisappear(push,false)",
                "root.didDisappear(false)",
            ),
            rootRec.log,
        )
        assertEquals(listOf("b.willAppear(push)", "b.didAppear(true)"), bRec.log)
        assertTrue(b.hasAppeared)
        // The new transition runs from the new top.
        val t = stack.transition!!
        assertSame(b, t.from)
        assertSame(c, t.to)
    }

    @Test
    fun popToRootThenPushInOneFrameProducesAPushFromRoot() {
        val stack = NavStack(TestRoute("root"))
        stack.holdHostlessOps()
        stack.push(TestRoute("b"))
        stack.complete(stack.transition!!, cancelled = false)
        stack.push(TestRoute("c"))
        stack.complete(stack.transition!!, cancelled = false)
        val root = stack.entries[0]

        stack.popToRoot(animated = false)
        // Held: the pop waits for the host (progress already 1).
        assertNotNull(stack.transition)
        val x = stack.push(TestRoute("x"), animated = true)
        // The push finished the pop synchronously and ran from the root —
        // the iOS openSession behaviour.
        val t = stack.transition!!
        assertEquals(NavKind.Push, t.kind)
        assertSame(root, t.from)
        assertSame(x, t.to)
        assertEquals(listOf(root, x), stack.entries)
        // The popped entries left for teardown.
        assertEquals(2, stack.leaving.size)
    }

    @Test
    fun aNewOpAbandonsAHeldGestureAndItsLateCompletionIsIgnored() {
        val stack = NavStack(TestRoute("root"))
        stack.holdHostlessOps()
        stack.push(TestRoute("b"))
        stack.complete(stack.transition!!, cancelled = false)
        val b = stack.top
        val ends = mutableListOf<Boolean>()

        val t = stack.beginInteractivePop()!!
        t.onEnd { ends += it }
        t.progress = 0.4f
        stack.sendWillEvents(t)

        // A deep link arrives mid-gesture: the gesture is abandoned at once.
        stack.popToRoot(animated = false)
        assertTrue(t.abandoned)
        assertEquals(0f, t.progress, 1e-6f)
        assertEquals(true, t.cancelled)
        assertTrue(ends.contains(true))
        // The popToRoot itself is now the running (held) transition.
        assertEquals(NavKind.Pop, stack.transition!!.kind)

        val x = stack.push(TestRoute("x"), animated = true)
        val t2 = stack.transition!!
        assertSame(stack.entries[0], t2.from)
        assertSame(x, t2.to)
        // The late gesture completion is ignored: the abandoned transition
        // stays cancelled and the push is untouched.
        t.target = 1f
        t.isInteractive = false
        assertTrue(t.abandoned)
        assertSame(t2, stack.transition)
        assertEquals(NavKind.Push, stack.transition!!.kind)
    }

    // ------------------------------------------------------- host detach

    @Test
    fun onHostDetachedCommitsAnInterruptedPopAndFinalizes() {
        val stack = NavStack(TestRoute("root"))
        stack.holdHostlessOps()
        stack.push(TestRoute("b"))
        stack.complete(stack.transition!!, cancelled = false)
        val b = stack.top
        val closes = mutableListOf<String>()
        b.retain("res") { CountingCloseable("res", closes) }

        val t = stack.pop()
        assertTrue(t)
        val heldPop = stack.transition!!
        stack.sendWillEvents(heldPop)
        heldPop.progress = 0.55f

        stack.onHostDetached()
        // The pop committed in the same call and the entry was torn down.
        assertEquals(1, stack.entries.size)
        assertTrue(stack.leaving.isEmpty())
        assertTrue(b.isClosed)
        assertEquals(listOf("res"), closes)
        assertEquals(false, heldPop.cancelled)
    }

    // ----------------------------------------------------- hold/freeze

    @Test
    fun freezeWithoutAHeldTransitionThrows() {
        val stack = NavStack(TestRoute("root"))
        // The op completed hostless: freeze has nothing to freeze.
        stack.push(TestRoute("b"))
        try {
            stack.freeze(0.5f)
            fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
        }
    }

    @Test
    fun freezeMarksTheHeldTransitionInteractiveAtProgress() {
        val stack = NavStack(TestRoute("root"))
        stack.holdHostlessOps()
        stack.push(TestRoute("b", hidesBottomChrome = true))
        stack.freeze(0.5f)
        val t = stack.transition!!
        assertTrue(t.isInteractive)
        assertEquals(0.5f, t.progress, 1e-6f)
        assertEquals(2, stack.entries.size)
    }

    // -------------------------------------------------------- lifecycle

    @Test
    fun onEndFiresImmediatelyWhenTheTransitionAlreadyEnded() {
        val stack = NavStack(TestRoute("root"))
        stack.holdHostlessOps()
        stack.push(TestRoute("b"))
        val t = stack.transition!!
        stack.sendWillEvents(t)
        stack.complete(t, cancelled = false)
        val seen = mutableListOf<Boolean>()
        t.onEnd { seen += it }
        assertEquals(listOf(false), seen)
    }

    @Test
    fun completeIsIdempotent() {
        val stack = NavStack(TestRoute("root"))
        stack.holdHostlessOps()
        val rootRec = RecordingListener("root")
        stack.top.addListener(rootRec)
        stack.push(TestRoute("b"))
        val t = stack.transition!!
        stack.sendWillEvents(t)
        stack.complete(t, cancelled = false)
        stack.complete(t, cancelled = false)
        assertEquals(listOf("root.willDisappear(push,false)", "root.didDisappear(false)"), rootRec.log)
        assertNull(stack.transition)
    }

    @Test
    fun removedListenersReceiveNothing() {
        val stack = NavStack(TestRoute("root"))
        stack.holdHostlessOps()
        val rec = RecordingListener("root")
        val remove = stack.top.addListener(rec)
        remove()
        stack.push(TestRoute("b"))
        val t = stack.transition!!
        stack.sendWillEvents(t)
        stack.complete(t, cancelled = false)
        assertEquals(0, rec.log.size)
    }

    @Test
    fun retainReturnsOneInstancePerKeyAndClosesInReverseOrder() {
        val stack = NavStack(TestRoute("root"))
        stack.holdHostlessOps()
        stack.push(TestRoute("b"))
        val b = stack.top
        val closes = mutableListOf<String>()
        val first = b.retain("a") { CountingCloseable("a", closes) }
        val firstAgain = b.retain("a") { CountingCloseable("a-again", closes) }
        assertSame(first, firstAgain)
        val second = b.retain("b") { CountingCloseable("b", closes) }
        assertFalse(first === second)

        b.close()
        assertEquals(listOf("b", "a"), closes)
        // close() is idempotent.
        b.close()
        assertEquals(listOf("b", "a"), closes)
    }

    // -------------------------------------------------- chrome visibility

    @Test
    fun bottomChromeVisibilityCrossFadesAcrossAPushToAHidingRoute() {
        val stack = NavStack(TestRoute("root"))
        assertEquals(1f, stack.bottomChromeVisibility, 1e-6f)
        stack.holdHostlessOps()
        stack.push(TestRoute("session", hidesBottomChrome = true))
        stack.freeze(0.5f)
        assertEquals(0.5f, stack.bottomChromeVisibility, 1e-4f)

        // The push commits: idle on the hiding route shows no chrome.
        val t = stack.transition!!
        t.isInteractive = false
        stack.complete(t, cancelled = false)
        assertEquals(0f, stack.bottomChromeVisibility, 1e-6f)

        // And back: a pop from the hiding route reveals it with the lerp.
        val pop = stack.beginInteractivePop()!!
        pop.progress = 0.5f
        assertEquals(0.5f, stack.bottomChromeVisibility, 1e-4f)
        stack.complete(pop, cancelled = false)
        assertEquals(1f, stack.bottomChromeVisibility, 1e-6f)
    }

    @Test
    fun entryIdsAreUniqueAcrossStacks() {
        val a = NavStack(TestRoute("root"))
        val b = NavStack(TestRoute("root"))
        assertTrue(a.top.id != b.top.id)
    }
}
