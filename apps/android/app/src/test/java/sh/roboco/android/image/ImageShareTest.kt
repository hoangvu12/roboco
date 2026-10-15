package sh.roboco.android.image

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The viewer's share step (ticket 125, [ImageShare]), Robolectric: the
 * chooser intent's shape (ACTION_SEND of an image PNG through the
 * FileProvider, with EXTRA_STREAM, ClipData and the read grant), the
 * written PNG decoding back to the bitmap's size, and the one-hour
 * prune. The system bars, the sheet's own UI and "Save Image" are not
 * testable here (CI-only / manual).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImageShareTest {

    @Before
    fun setUp() {
        // Robolectric's paused main looper would deadlock a withContext(Main)
        // on the test thread; run it inline instead.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun shareSendsThePngThroughTheFileProviderWithAGrantAndAClip() = runBlocking {
        // androidx.core's FileProvider matches a file to its root with
        // `canonicalPath.startsWith(rootPath + '/')` (SimplePathStrategy.
        // belongsToRoot, a hard-coded '/'). On a Windows host the canonical
        // paths use '\', so getUriForFile throws "Failed to find configured
        // root", writePng returns null and no chooser starts. A host-JVM
        // limit only (Android paths always use '/'): run on POSIX hosts.
        assumeTrue(
            "skipped on a Windows host: androidx.core FileProvider (SimplePathStrategy.belongsToRoot) " +
                "hard-codes '/' when matching a file to its root; runs on POSIX hosts (ubuntu CI)",
            File.separatorChar == '/',
        )
        // An Activity context, as the app passes LocalContext (the chooser
        // needs no NEW_TASK from one).
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val bitmap = solidBitmap(64, 32, 0xFF3366CC.toInt())

        ImageShare.share(activity, bitmap)

        val chooser = shadowOf(activity).nextStartedActivity
            ?: throw AssertionError("the chooser must have been started")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            ?: throw AssertionError("the chooser's EXTRA_INTENT must be the ACTION_SEND")
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        assertTrue(
            "the read grant must be flagged",
            (send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0,
        )
        assertTrue("the chooser preview needs the ClipData", send.clipData != null)
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            ?: throw AssertionError("EXTRA_STREAM must carry the PNG")
        assertEquals("${activity.packageName}.fileprovider", uri.authority)

        // The file exists and decodes back to the bitmap's size.
        val shared = File(activity.cacheDir, ImageShare.SharedDir)
        val files = shared.listFiles() ?: throw AssertionError("cacheDir/shared must exist")
        assertEquals("exactly one file per share", 1, files.size)
        val decoded = BitmapFactory.decodeStream(activity.contentResolver.openInputStream(uri))
            ?: throw AssertionError("the shared PNG must decode")
        assertEquals(64, decoded.width)
        assertEquals(32, decoded.height)
    }

    @Test
    fun pruneDeletesOnlyFilesOlderThanAnHour() {
        val dir = createTempDirectory("image-share-prune").toFile()
        try {
            val now = System.currentTimeMillis()
            val old = File(dir, "image-old.png").apply {
                writeText("stale")
                setLastModified(now - 2 * 60 * 60 * 1000)
            }
            val fresh = File(dir, "image-new.png").apply {
                writeText("fresh")
                setLastModified(now - 10 * 60 * 1000)
            }
            ImageShare.prune(dir, now)
            assertFalse("older than an hour must be deleted", old.exists())
            assertTrue("newer than an hour must be kept", fresh.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pruneLeavesAMissingDirectoryAlone() {
        val dir = File(createTempDirectory("image-share-gone").toFile(), "missing")
        // Must not throw: the share's write step mkdirs first.
        ImageShare.prune(dir, System.currentTimeMillis())
        assertTrue("still absent", !dir.exists())
    }

    /** A solid ARGB_8888 bitmap (CompressFormat needs a non-alpha-only config). */
    private fun solidBitmap(width: Int, height: Int, color: Int): ImageBitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return bitmap.asImageBitmap()
    }
}
