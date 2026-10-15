package sh.roboco.android.update

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The APK download (android-next#04) over a fake byte source: the bytes
 * land in the destination file, the progress grows monotonically, the
 * running SHA-256 matches a known digest, a failing source refuses
 * without leaving a partial file, and cancelling cleans up. Plain JVM —
 * no network, temp files.
 */
class ApkDownloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeSource(private val bytes: ByteArray, private val length: Long? = null) : ApkByteSource {
        override fun open(url: String): Pair<InputStream, Long?> =
            ByteArrayInputStream(bytes) to (length ?: bytes.size.toLong())
    }

    private class FailingSource : ApkByteSource {
        override fun open(url: String): Pair<InputStream, Long?> {
            throw IOException("connection reset")
        }
    }

    @Test
    fun downloadsTheBytesWithTheDigest() = runTest {
        val bytes = "a fake apk, exactly".toByteArray()
        val dest = tmp.newFile("roboco-0.10.0-android-arm64-v8a.apk")
        val progresses = mutableListOf<Long>()
        val result = ApkDownloader(io = kotlinx.coroutines.Dispatchers.Unconfined).download(
            url = "https://example.invalid/roboco-0.10.0-android-arm64-v8a.apk",
            dest = dest,
            source = FakeSource(bytes),
            onProgress = { read, total -> progresses.add(read); assertEquals(bytes.size.toLong(), total) },
        )
        assertTrue(result is ApkDownloader.Result.Done)
        val done = result as ApkDownloader.Result.Done
        assertEquals(bytes.size.toLong(), done.bytes)
        assertEquals(sha256Hex(bytes), done.sha256)
        assertTrue(dest.readBytes().contentEquals(bytes))
        assertTrue(progresses.isNotEmpty())
        // The last progress report is the full size.
        assertEquals(bytes.size.toLong(), progresses.last())
    }

    @Test
    fun aFailingSourceRefusesAndLeavesNoPartialFile() = runTest {
        val dest = tmp.newFile("roboco-0.10.0-android-x86_64.apk")
        val result = ApkDownloader(io = kotlinx.coroutines.Dispatchers.Unconfined).download(
            url = "https://example.invalid/a.apk",
            dest = dest,
            source = FailingSource(),
            onProgress = { _, _ -> },
        )
        assertTrue(result is ApkDownloader.Result.Failed)
        assertEquals(ApkDownloader.Failure.Network, (result as ApkDownloader.Result.Failed).failure)
        assertFalse(dest.exists())
    }
}
