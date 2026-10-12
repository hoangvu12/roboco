package sh.roboco.android.core

import android.content.res.AssetManager
import android.util.Log
import java.io.IOException
import java.io.InputStream

/**
 * The startup font check's result: [Ok], or the bad asset paths in `Face`
 * table order. Ticket 60 consults [FontGate.status] before building the
 * Rust `TextSystem` (a missing face must never reach the layout worker).
 */
sealed interface FontStatus {
    data object Ok : FontStatus

    /** Every path that is missing, empty, truncated or not a TrueType file. */
    data class Missing(val paths: List<String>) : FontStatus
}

/**
 * The fail-loud font gate. Upstream iOS **silently skips** a face it cannot
 * register and falls back to the system font (`Fonts.swift:26-30, 55-59`);
 * Roboco does not: on Android the same pattern (#609 review bug 8) left the
 * transcript silently blank, because the Rust layout worker never ran a pass
 * without faces (`mob:layout/mod.rs:561`). The bytes are build artefacts, so
 * a missing one is a packaging bug that must surface:
 *
 * - **debug** builds: `IllegalStateException` from `RobocoApplication` naming
 *   every bad path;
 * - **release** builds: [status] stores [FontStatus.Missing], the message is
 *   logged, and `MainActivity` shows `FontsMissingScreen` instead of the app.
 *
 * Only 4 magic bytes per file are read (≈1.6 MB of faces are never touched
 * on the main thread here); ticket 60 reads the full bytes off-thread for
 * the `TextSystem`.
 */
object FontGate {

    private const val TAG = "Roboco"

    /** TrueType version 1.0: every bundled face starts `00 01 00 00`. */
    private val TRUE_TYPE_MAGIC = byteArrayOf(0, 1, 0, 0)

    /** The last result of [check]; `MainActivity` branches on it. */
    @Volatile
    var status: FontStatus = FontStatus.Ok
        private set

    /**
     * The production entry point: checks the APK's merged assets. Debug
     * builds throw on the first bad pack; release builds record the
     * failure and keep starting (the activity shows the fatal screen).
     */
    fun check(assets: AssetManager, debug: Boolean): FontStatus =
        check({ path -> assets.open(path) }, debug)

    /**
     * Testable core: [open] throws (e.g. `FileNotFoundException`) for a
     * missing path, or returns a stream that is empty, truncated or has
     * the wrong magic.
     *
     * @param log the release-build log sink; production defaults to
     *   `Log.e`, plain-JVM tests inject a recorder because `android.util.Log`
     *   is not mocked there.
     */
    fun check(
        open: (String) -> InputStream,
        debug: Boolean,
        log: (String) -> Unit = { message -> Log.e(TAG, message) },
    ): FontStatus {
        val missing = Face.entries.mapNotNull { face ->
            if (faceIsGood(open, face.assetPath)) null else face.assetPath
        }
        val result = if (missing.isEmpty()) FontStatus.Ok else FontStatus.Missing(missing)
        status = result
        if (missing.isNotEmpty()) {
            val message = "Missing font asset(s): ${missing.joinToString(", ")}"
            log(message)
            if (debug) throw IllegalStateException(message)
        }
        return result
    }

    /** A face is good when its asset opens and starts with the TrueType magic. */
    private fun faceIsGood(open: (String) -> InputStream, path: String): Boolean {
        val magic = readMagic(open, path) ?: return false
        return magic.contentEquals(TRUE_TYPE_MAGIC)
    }

    /**
     * The first 4 bytes of the asset, or null when it cannot be opened,
     * ends early or is empty. `InputStream.readNBytes` is API 33, so this
     * loops on `read` (minSdk 31).
     */
    private fun readMagic(open: (String) -> InputStream, path: String): ByteArray? {
        return try {
            open(path).use { stream ->
                val bytes = ByteArray(4)
                var read = 0
                while (read < 4) {
                    val n = stream.read(bytes, read, 4 - read)
                    // EOF (or a non-conforming 0 return): a short file.
                    if (n <= 0) return@use null
                    read += n
                }
                bytes
            }
        } catch (_: IOException) {
            null
        }
    }
}
