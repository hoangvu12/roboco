package sh.roboco.android.newsession

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.roboco_core.CoreClient
import uniffi.roboco_core.PreparedPath

/**
 * The New Project write seam (android-ui-parity #111): the two FFI calls
 * the bottom bar's actions need — `create_project` and
 * `prepare_space_path` (android-app#09). A seam, not the client itself,
 * so the plain-JVM tests script it without the native core, exactly as
 * 110's [FolderBrowserSource] splits browsing off.
 */
interface ProjectWriteApi {

    /**
     * android-app#09 `createProject`: the **scoped** space id. Dedupes
     * against an existing space in the same folder on the same device —
     * never dedupe again in Kotlin. Throws on failure (CoreException;
     * HostUnavailable when the engine is offline).
     */
    suspend fun createProject(deviceId: String, path: String, gitDetected: Boolean): String

    /**
     * android-app#09 `prepareSpacePath`: interprets [path] on the engine —
     * `~` expands there, `createIfMissing` runs `create_dir_all` there.
     * Error strings ("the path is a file, not a folder", io texts) arrive
     * as `CoreException.HostException` with the engine's text as `reason`.
     */
    suspend fun prepareSpacePath(deviceId: String, path: String, createIfMissing: Boolean): PreparedPath
}

/**
 * The live [ProjectWriteApi] over the app's [CoreClient] — the same
 * provider 110's [CoreFolderBrowserSource] uses (`app.clientOrNull()`).
 * With no client it throws `IllegalStateException("Roboco is disconnected.")`
 * (the alert's message maps that the same way iOS maps `CoreError.Closed`).
 *
 * The FFI calls run on [io] (never main, never `runBlocking` — the #609
 * trap: create must not run synchronously on the main thread), and
 * failures propagate to the caller, which owns the copy.
 */
class CoreProjectWriteApi(
    private val client: () -> CoreClient?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ProjectWriteApi {

    override suspend fun createProject(deviceId: String, path: String, gitDetected: Boolean): String {
        val c = client() ?: throw IllegalStateException("Roboco is disconnected.")
        return withContext(io) { c.createProject(deviceId, path, gitDetected) }
    }

    override suspend fun prepareSpacePath(
        deviceId: String,
        path: String,
        createIfMissing: Boolean,
    ): PreparedPath {
        val c = client() ?: throw IllegalStateException("Roboco is disconnected.")
        return withContext(io) { c.prepareSpacePath(deviceId, path, createIfMissing) }
    }
}
