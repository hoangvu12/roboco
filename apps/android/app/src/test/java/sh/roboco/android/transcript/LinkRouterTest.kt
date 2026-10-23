package sh.roboco.android.transcript

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [LinkRouter] with a recording [LinkLauncher] (Robolectric only for
 * [ImageBitmap]): the web fallback when no Custom Tabs provider exists,
 * the image-chip load → viewer from the row's bounds, the null / failed
 * load that shows nothing, the cancel of an in-flight preview when a
 * newer tap arrives, the TRANSCRIPT-D4 no-ops, and the external /
 * invoke / anchor rows.
 *
 * The scope runs on a [StandardTestDispatcher] sharing `runTest`'s
 * scheduler, so [advanceUntilIdle] drives the loads (the tap callback
 * itself is synchronous).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LinkRouterTest {

    /** Records every call; [customTabResult] drives the provider-present paths. */
    private class RecordingLauncher(var customTabResult: Boolean = true) : LinkLauncher {
        val customTabs = mutableListOf<Pair<String, Boolean>>()
        val views = mutableListOf<String>()

        override fun customTab(url: String, dark: Boolean): Boolean {
            customTabs += url to dark
            return customTabResult
        }

        override fun view(url: String) {
            views += url
        }
    }

    private val rowBounds = Rect(10f, 20f, 110f, 120f)

    private fun tap(url: String): LinkTap = LinkTap(url, rowBounds, rowBounds)

    private fun TestScope.router(
        launcher: LinkLauncher,
        dark: Boolean = false,
        loadFull: suspend (String) -> ImageBitmap? = { null },
        showImage: (String, Rect, ImageBitmap) -> Unit = { _, _, _ -> },
    ): LinkRouter = LinkRouter(
        CoroutineScope(StandardTestDispatcher(testScheduler)),
        { dark },
        loadFull,
        showImage,
        launcher,
    )

    // ----------------------------------------------------------------- web

    @Test
    fun webWithAProviderUsesOnlyTheCustomTab() = runTest {
        val launcher = RecordingLauncher()
        router(launcher).open(tap("https://example.com/x"))
        assertEquals(listOf("https://example.com/x" to false), launcher.customTabs)
        assertTrue("no view fallback while a provider exists", launcher.views.isEmpty())
    }

    @Test
    fun webWithoutAProviderFallsBackToViewOnce() = runTest {
        val launcher = RecordingLauncher(customTabResult = false)
        router(launcher, dark = true).open(tap("https://example.com/x"))
        assertEquals(listOf("https://example.com/x" to true), launcher.customTabs)
        assertEquals("the fallback fires exactly once", listOf("https://example.com/x"), launcher.views)
    }

    // -------------------------------------------------------------- preview

    @Test
    fun aPreviewLoadShowsTheImageFromTheRowBounds() = runTest {
        val launcher = RecordingLauncher()
        val bitmap = ImageBitmap(4, 4)
        val loads = mutableListOf<String>()
        val shown = mutableListOf<Triple<String, Rect, ImageBitmap>>()
        router(
            launcher,
            loadFull = { reference -> loads += reference; bitmap },
            showImage = { reference, source, bmp -> shown += Triple(reference, source, bmp) },
        ).open(tap("roboco-preview://image?ref=assets/shot.png"))
        advanceUntilIdle()
        assertEquals(listOf("assets/shot.png"), loads)
        assertEquals(1, shown.size)
        assertSame(bitmap, shown[0].third)
        assertEquals("the viewer's source is the row, from the LinkTap", rowBounds, shown[0].second)
    }

    @Test
    fun aFailedPreviewLoadShowsNothing() = runTest {
        val shown = mutableListOf<Triple<String, Rect, ImageBitmap>>()
        val showImage: (String, Rect, ImageBitmap) -> Unit = { reference, source, bmp ->
            shown += Triple(reference, source, bmp)
        }

        router(RecordingLauncher(), loadFull = { null }, showImage = showImage)
            .open(tap("roboco-preview://image?ref=gone.png"))
        advanceUntilIdle()
        assertTrue("a null load must not show anything", shown.isEmpty())

        router(RecordingLauncher(), loadFull = { throw IllegalStateException("boom") }, showImage = showImage)
            .open(tap("roboco-preview://image?ref=boom.png"))
        advanceUntilIdle()
        assertTrue("a throwing load must not show anything (and must not crash the scope)", shown.isEmpty())
    }

    @Test
    fun aSecondPreviewTapCancelsTheFirstInFlightLoad() = runTest {
        val loads = mutableListOf<String>()
        val shown = mutableListOf<String>()
        val bitmap = ImageBitmap(4, 4)
        router(
            RecordingLauncher(),
            loadFull = { reference -> loads += reference; delay(100); bitmap },
            showImage = { reference, _, _ -> shown += reference },
        ).apply {
            open(LinkTap("roboco-preview://image?ref=first.png", rowBounds, rowBounds))
            open(LinkTap("roboco-preview://image?ref=second.png", rowBounds, rowBounds))
        }
        advanceUntilIdle()
        // The first load never ran (the dispatcher queues it) and the
        // second tap's cancel killed it: the latest tap wins.
        assertEquals(listOf("second.png"), loads)
        assertEquals(listOf("second.png"), shown)
    }

    // -------------------------------------------------------- no-ops and co

    @Test
    fun fileShapedNonImageLinksDoNothing() = runTest {
        val launcher = RecordingLauncher()
        val loads = mutableListOf<String>()
        val shown = mutableListOf<String>()
        val r = router(
            launcher,
            loadFull = { reference -> loads += reference; null },
            showImage = { reference, _, _ -> shown += reference },
        )
        for (url in listOf("assets/notes.md", "roboco-file:src/lib.rs", "file:///x.txt", "C:\\x.log")) {
            r.open(tap(url))
        }
        advanceUntilIdle()
        assertTrue("no launcher calls: ${launcher.customTabs} / ${launcher.views}", launcher.customTabs.isEmpty())
        assertTrue("no launcher view calls: ${launcher.views}", launcher.views.isEmpty())
        assertTrue("no loads: $loads", loads.isEmpty())
        assertTrue("no viewer: $shown", shown.isEmpty())
    }

    @Test
    fun fileShapedImageLinksOpenTheViewer() = runTest {
        val bitmap = ImageBitmap(4, 4)
        for (url in listOf("assets/logo.png", "roboco-file:shots/a%20b.png", "file:///x.png", "C:\\x.png")) {
            val launcher = RecordingLauncher()
            val loads = mutableListOf<String>()
            val shown = mutableListOf<String>()
            router(
                launcher,
                loadFull = { reference -> loads += reference; bitmap },
                showImage = { reference, _, _ -> shown += reference },
            ).open(tap(url))
            advanceUntilIdle()
            assertEquals(url, listOf(url), loads)
            assertEquals(url, listOf(url), shown)
            assertTrue(launcher.views.isEmpty())
        }
    }

    @Test
    fun externalSchemesGoToViewAndInvokeAndAnchorsDoNothing() = runTest {
        val launcher = RecordingLauncher()
        val loads = mutableListOf<String>()
        val shown = mutableListOf<String>()
        val r = router(
            launcher,
            loadFull = { reference -> loads += reference; null },
            showImage = { reference, _, _ -> shown += reference },
        )
        r.open(tap("mailto:dev@example.com"))
        advanceUntilIdle()
        assertEquals(listOf("mailto:dev@example.com"), launcher.views)

        r.open(tap("roboco-invoke:7b7d"))
        r.open(tap("#install"))
        advanceUntilIdle()
        assertEquals("no further launcher calls", 1, launcher.views.size)
        assertTrue(launcher.customTabs.isEmpty())
        assertTrue(loads.isEmpty())
        assertTrue(shown.isEmpty())
    }
}
