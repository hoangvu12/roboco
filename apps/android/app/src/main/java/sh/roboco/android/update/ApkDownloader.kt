package sh.roboco.android.update

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The APK download (android-next#04): streams the asset into the app's
 * cache while digesting it, so the verification never re-reads the file.
 * Resumable is not required; a failed or cancelled download leaves no
 * partial file behind. The byte source is a seam ([ApkByteSource]) so
 * the plain-JVM tests feed known bytes.
 *
 * [onProgress] runs on the download lane (not the main thread) — the
 * caller hops; the callback itself is cheap (two numbers).
 */
class ApkDownloader(
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** One download's outcome. */
    sealed interface Result {
        /** The file is complete and its digest is [sha256] (lowercase hex). */
        data class Done(val file: File, val bytes: Long, val sha256: String) : Result

        /** The download could not complete; the partial file is removed. */
        data class Failed(val failure: Failure) : Result

        /** Coroutines cancelled the download; the partial file is removed. */
        data object Cancelled : Result
    }

    /** Why a download failed — short reasons the alert maps to copy. */
    enum class Failure {
        /** The network failed (connect, read, HTTP status). */
        Network,

        /** The destination could not be written (cache full, IO error). */
        Disk,
    }

    /** Which step an [IOException] came from — network reads vs disk writes. */
    private enum class Phase { Open, Read, Write }

    /**
     * Downloads [url] into [dest] (overwriting), reporting progress as
     * `(bytesRead, totalBytesOrNull)` on this downloader's lane. The
     * alert side coalesces the reports; this side reports every chunk.
     */
    suspend fun download(
        url: String,
        dest: File,
        source: ApkByteSource,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
    ): Result = withContext(io) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var read = 0L
        var input: InputStream? = null
        var output: OutputStream? = null
        var phase = Phase.Open
        try {
            val (stream, total) = source.open(url)
            input = stream
            output = dest.outputStream()
            while (true) {
                // Cancellation between chunks unwinds through the catch
                // below: streams closed, partial file removed.
                coroutineContext.ensureActive()
                phase = Phase.Read
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) {
                    phase = Phase.Write
                    output.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    read += n
                    onProgress(read, total)
                }
            }
            phase = Phase.Write
            output.flush()
            Result.Done(dest, read, digest.digest().toHex())
        } catch (e: IOException) {
            dest.delete()
            Result.Failed(if (phase == Phase.Write) Failure.Disk else Failure.Network)
        } catch (e: CancellationException) {
            dest.delete()
            Result.Cancelled
        } finally {
            runCatching { output?.close() }
            runCatching { input?.close() }
        }
    }
}

/** The digest bytes as lowercase hex. */
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
