package sh.roboco.android.composer

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.NoFeedback
import sh.roboco.android.shell.LocalNavEntry
import sh.roboco.android.shell.LocalNavStack
import sh.roboco.android.shell.NavRoute
import sh.roboco.android.shell.NavStack

/**
 * [SessionDraftSaver] and [SessionDraftEffect] (android-ui-parity #84) —
 * every saver row of the ticket's acceptance table over [FakePrefsStore]
 * and a real 54 [ComposerController] (the bind target, exactly what the
 * effect binds):
 *
 * - unbound writes nothing; bound without a stash saves the composer
 *   text; `onBackground` saves only for the top entry;
 * - an open queued edit saves the **stash**, never the queued row's
 *   text; a pending lease (stash null) saves the composer text;
 * - rebinding moves the saver to the new composer;
 * - the pending-load guard: a blank save before the first read lands
 *   does not delete a draft the user never saw; non-blank goes through;
 * - staged images are never persisted (blank text removes the entry;
 *   the composer keeps them);
 * - `NavEntry.sessionDraftSaver` retains one instance per entry;
 * - the effect itself: binds on composition, saves on disposal with the
 *   last text, and never unbinds (a later `saveNow` still reads the
 *   composer).
 *
 * Robolectric (android-app#13): [StagedImage] holds an [ImageBitmap],
 * and the effect test composes. `StandardTestDispatcher(testScheduler)`
 * is `io`; [foregroundAppScope] is the Drafts scope. The effect test runs
 * on `Dispatchers.Unconfined` for both, so every write lands
 * synchronously and the compose clock is the only timing source.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SessionDraftSaverTest {

    @get:Rule
    val compose = createComposeRule()

    /** A real 54 controller: what the saver binds to, like the effect's `composer.text`. */
    private fun newComposer(text: String = ""): ComposerController =
        ComposerController(CoroutineScope(Dispatchers.Unconfined), NoFeedback, text)

    /** A loaded Drafts over the in-memory store (warm already ran). */
    private fun TestScope.loadedDrafts(store: FakePrefsStore): Drafts {
        val drafts = Drafts(store, foregroundAppScope(), StandardTestDispatcher(testScheduler))
        drafts.warm()
        advanceUntilIdle()
        assertTrue(drafts.isLoaded)
        return drafts
    }

    private val key = DraftKey.Chat("E1", "c1")

    @Test
    fun anUnboundSaverWritesNothing() = runTest {
        val store = FakePrefsStore()
        val drafts = loadedDrafts(store)
        val saver = SessionDraftSaver(key, drafts)
        assertFalse(saver.pendingLoad)
        saver.saveNow()
        advanceUntilIdle()
        assertEquals("", drafts.peek(key))
        assertTrue(store.names.isEmpty())
    }

    @Test
    fun aBoundSaverWithoutAStashSavesTheComposerText() = runTest {
        val store = FakePrefsStore()
        val drafts = loadedDrafts(store)
        val saver = SessionDraftSaver(key, drafts)
        val composer = newComposer("hello")
        saver.bind({ composer.text }, { null })

        saver.saveNow()
        advanceUntilIdle()
        assertEquals("hello", drafts.peek(key))
        assertEquals("hello", store.stored(key))

        // ON_STOP, top entry: saved. Not top: nothing is written.
        composer.text = "more"
        saver.onBackground(isTop = false)
        advanceUntilIdle()
        assertEquals("hello", store.stored(key))
        saver.onBackground(isTop = true)
        advanceUntilIdle()
        assertEquals("more", store.stored(key))
    }

    @Test
    fun anOpenEditSavesTheStashNeverTheQueuedText() = runTest {
        val store = FakePrefsStore()
        val drafts = loadedDrafts(store)
        val saver = SessionDraftSaver(key, drafts)
        val composer = newComposer("queued")
        val stashed: String? = "mine"
        saver.bind({ composer.text }, { stashed })

        saver.saveNow()
        advanceUntilIdle()
        assertEquals("mine", store.stored(key))

        // The background save picks the stash too (no edit cancel, ever).
        saver.onBackground(isTop = true)
        advanceUntilIdle()
        assertEquals("mine", store.stored(key))
        assertEquals("mine", drafts.peek(key))
    }

    @Test
    fun aPendingLeaseWithNoStashSavesTheComposerText() = runTest {
        val store = FakePrefsStore()
        val drafts = loadedDrafts(store)
        val saver = SessionDraftSaver(key, drafts)
        val composer = newComposer("draft")
        saver.bind({ composer.text }, { null })
        saver.saveNow()
        advanceUntilIdle()
        assertEquals("draft", store.stored(key))
    }

    @Test
    fun rebindingMovesTheSaverToTheNewComposer() = runTest {
        val store = FakePrefsStore()
        val drafts = loadedDrafts(store)
        val saver = SessionDraftSaver(key, drafts)
        val old = newComposer("old")
        val fresh = newComposer("new")
        saver.bind({ old.text }, { null })
        saver.bind({ fresh.text }, { null })
        saver.saveNow()
        advanceUntilIdle()
        assertEquals("new", store.stored(key))
    }

    @Test
    fun aBlankSaveWhileTheLoadIsPendingKeepsTheUnshownDraft() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = FakePrefsStore(mapOf("chat:E1:c1" to "kept"), gate)
        val io = StandardTestDispatcher(testScheduler)
        val drafts = Drafts(store, foregroundAppScope(), io)
        drafts.warm()
        runCurrent() // the read is parked on the gate
        assertFalse(drafts.isLoaded)

        val saver = SessionDraftSaver(key, drafts)
        assertTrue(saver.pendingLoad)
        val composer = newComposer("")
        saver.bind({ composer.text }, { null })
        saver.saveNow() // blank + pendingLoad: suppressed

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("kept", drafts.peek(key))
        assertEquals("kept", store.stored(key))

        // Non-blank text goes through even while pendingLoad is still set
        // (only the effect's fallback load clears it).
        composer.text = "typed"
        saver.saveNow()
        advanceUntilIdle()
        assertEquals("typed", drafts.peek(key))
        assertEquals("typed", store.stored(key))
    }

    @Test
    fun stagedImagesAreNeverPersistedAndBlankTextRemovesTheEntry() = runTest {
        val store = FakePrefsStore(mapOf("chat:E1:c1" to "old"))
        val drafts = loadedDrafts(store)
        val composer = newComposer("")
        composer.images = listOf(
            StagedImage("i1", "a.jpg", byteArrayOf(1), ImageBitmap(1, 1)),
            StagedImage("i2", "b.jpg", byteArrayOf(2), ImageBitmap(1, 1)),
        )
        val saver = SessionDraftSaver(key, drafts)
        saver.bind({ composer.text }, { null })
        saver.saveNow()
        advanceUntilIdle()
        // Blank text removes the entry — images never count as draft
        // content, and nothing image-shaped is ever written.
        assertEquals("", drafts.peek(key))
        assertNull(store.stored(key))
        assertEquals(2, composer.images.size)
    }

    @Test
    fun theSaverIsRetainedPerNavEntry() {
        val drafts = Drafts(FakePrefsStore(), CoroutineScope(Dispatchers.Unconfined))
        val stack = NavStack(DraftTestRoute)
        val entry = stack.entries.first()
        assertSame(entry.sessionDraftSaver(key, drafts), entry.sessionDraftSaver(key, drafts))
        // A different entry gets its own saver, even for the same key.
        val other = stack.push(DraftTestRoute, animated = false)
        assertNotSame(entry.sessionDraftSaver(key, drafts), other.sessionDraftSaver(key, drafts))
    }

    /**
     * The effect's own wiring: it binds the retained saver to the live
     * composer on every composition entry (no save on entry), saves with
     * the last composer text when its composition is disposed — the
     * safety net that runs before 85's dispose-time `leave()` — and never
     * unbinds, so a later `saveNow` (85's) still reads the composer.
     */
    @Test
    fun theEffectBindsSavesOnDisposeAndNeverUnbinds() {
        val store = FakePrefsStore()
        val drafts = Drafts(store, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        drafts.warm()
        assertTrue(drafts.isLoaded)
        val composer = newComposer("half")
        val saver = SessionDraftSaver(key, drafts)
        val stack = NavStack(DraftTestRoute)
        val entry = stack.entries.first()
        var shown by mutableStateOf(true)

        compose.setContent {
            CompositionLocalProvider(
                LocalNavStack provides stack,
                LocalNavEntry provides entry,
            ) {
                if (shown) SessionDraftEffect(saver, composer, null, drafts)
            }
        }
        compose.waitForIdle()
        // Bound (not saved): the effect only binds on entry.
        assertEquals("", drafts.peek(key))

        compose.runOnIdle { saver.saveNow() }
        compose.waitForIdle()
        assertEquals("half", drafts.peek(key))

        compose.runOnIdle { composer.text = "final" }
        compose.waitForIdle()
        compose.runOnIdle { shown = false } // disposes the effect
        compose.waitForIdle()
        assertEquals("final", drafts.peek(key))

        // Never unbound: 85's later saveNow still reads the composer.
        compose.runOnIdle {
            composer.text = "after"
            saver.saveNow()
        }
        compose.waitForIdle()
        assertEquals("after", drafts.peek(key))
    }

    /** A route for the nav fixtures above. */
    private object DraftTestRoute : NavRoute

    /**
     * The effect's fallback load (84's behaviour row): the composer was
     * seeded before the store finished its first read, so the disk text
     * arrives late and applies only into an **empty** composer, then
     * clears the flag. The gate holds the read back; the read runs on the
     * completing thread (Unconfined), and the effect's await
     * resumes on the main dispatcher, so the looper is drained the way
     * CoreStartupTest does (shadowOf(looper).idle()).
     */
    @Test
    fun theEffectAppliesTheLateLoadIntoAnEmptyComposer() {
        val gate = CompletableDeferred<Unit>()
        val store = FakePrefsStore(mapOf("chat:E1:c1" to "kept"), gate)
        val drafts = Drafts(store, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        drafts.warm()
        assertFalse(drafts.isLoaded)
        val composer = newComposer("")
        val saver = SessionDraftSaver(key, drafts)
        assertTrue(saver.pendingLoad)
        val stack = NavStack(DraftTestRoute)
        val entry = stack.entries.first()

        compose.setContent {
            CompositionLocalProvider(
                LocalNavStack provides stack,
                LocalNavEntry provides entry,
            ) {
                SessionDraftEffect(saver, composer, null, drafts)
            }
        }
        compose.waitForIdle()
        // The load is parked on the gate: nothing applied, flag still set.
        assertEquals("", composer.text)
        assertTrue(saver.pendingLoad)

        compose.runOnIdle { gate.complete(Unit) }
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        assertEquals("kept", composer.text)
        assertFalse(saver.pendingLoad)
    }

    /**
     * Same setup with a non-empty composer: the late load is **not**
     * applied — the live composer is the source of truth once it holds
     * text (78's entry-retained controller after a tab switch).
     */
    @Test
    fun theEffectSkipsTheLateLoadWhenTheComposerHasText() {
        val gate = CompletableDeferred<Unit>()
        val store = FakePrefsStore(mapOf("chat:E1:c1" to "kept"), gate)
        val drafts = Drafts(store, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        drafts.warm()
        assertFalse(drafts.isLoaded)
        val composer = newComposer("mine")
        val saver = SessionDraftSaver(key, drafts)
        assertTrue(saver.pendingLoad)
        val stack = NavStack(DraftTestRoute)
        val entry = stack.entries.first()

        compose.setContent {
            CompositionLocalProvider(
                LocalNavStack provides stack,
                LocalNavEntry provides entry,
            ) {
                SessionDraftEffect(saver, composer, null, drafts)
            }
        }
        compose.waitForIdle()

        compose.runOnIdle { gate.complete(Unit) }
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        // The composer's own text wins; the flag still cleared.
        assertEquals("mine", composer.text)
        assertFalse(saver.pendingLoad)
    }
}
