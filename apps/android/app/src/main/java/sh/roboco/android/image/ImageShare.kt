package sh.roboco.android.image

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The viewer's share step (android-ui-parity #125; the port of iOS
 * `ImageViewer.share()`'s `UIActivityViewController`): write the
 * full-resolution bitmap as a PNG into `cacheDir/shared/` (pruned),
 * hand it to the system chooser through the FileProvider as an
 * `ACTION_SEND` of `image/png` with a `ClipData` (which gives the
 * chooser its preview and Copy). "Save Image" has no direct Android
 * equivalent — the sheet's targets decide.
 *
 * The compression and the prune run on [Dispatchers.IO]; the chooser
 * starts on Main. `ActivityNotFoundException` is swallowed (no handler,
 * no viewer change), as the ticket's Roboco adaptation notes.
 */
object ImageShare {

    /** Files older than this are pruned before a new share. */
    internal const val PruneAgeMs = 3600_000L

    /** The shared-image folder under the cache dir. */
    internal const val SharedDir = "shared"

    /**
     * Writes [bitmap] out and starts the chooser. Never throws into the
     * caller's scope (an unreadable cache dir or a failed write simply
     * aborts the share — the viewer stays open either way).
     */
    suspend fun share(context: Context, bitmap: ImageBitmap) {
        val uri = withContext(Dispatchers.IO) { writePng(context, bitmap.asAndroidBitmap()) } ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "Image", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        withContext(Dispatchers.Main) {
            try {
                context.startActivity(Intent.createChooser(send, null))
            } catch (e: ActivityNotFoundException) {
                // No chooser: nothing to do, the viewer stays open.
            }
        }
    }

    /**
     * Prunes the folder, compresses the PNG (quality 100) and returns
     * its FileProvider URI, or null when the cache dir or the file
     * cannot be written. Runs on the caller's dispatcher (IO in
     * production).
     */
    private fun writePng(context: Context, bitmap: Bitmap): Uri? {
        val dir = File(context.cacheDir, SharedDir)
        prune(dir, System.currentTimeMillis())
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val file = File(dir, "image-" + UUID.randomUUID().toString().take(8) + ".png")
        return try {
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Deletes the files in [dir] whose `lastModified` is older than
     * `nowMs − 1 h`. Runs on the caller's dispatcher (IO in production).
     */
    fun prune(dir: File, nowMs: Long) {
        val files = dir.listFiles() ?: return
        val cutoff = nowMs - PruneAgeMs
        for (file in files) {
            if (file.isFile && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }
}
