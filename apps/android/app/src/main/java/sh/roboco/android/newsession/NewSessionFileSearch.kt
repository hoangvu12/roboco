package sh.roboco.android.newsession

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.roboco.android.RobocoApplication
import uniffi.roboco_core.CoreClient
import uniffi.roboco_core.FileMatch

/**
 * The New Session page's `@file` search seam (android-ui-parity #93) — the
 * port of `AppModel.searchFiles` (`ios:App/AppModel.swift:607-610`), which
 * the page's `mentionSearch` calls through: **`chatId = null`**, the scope
 * is the picked project (its `spaceId`), and errors read as no matches.
 *
 * [rememberCoreFileSearch] resolves the client per call over
 * [RobocoApplication.clientOrNull] (ticket 71's accessor, the same seam 92's
 * catalog uses), so a pairing that completes after the page opened still
 * works. Tests pass a fake.
 */
fun interface NewSessionFileSearch {

    /**
     * `AppModel.searchFiles`: never throws (except cancellation); a missing
     * engine or any failure returns an empty list. The 120 ms debounce, the
     * stale-query drop and the 5-row cap are 86's, not this seam's.
     */
    suspend fun search(deviceId: String, spaceId: String, query: String): List<FileMatch>
}

/**
 * The production [NewSessionFileSearch] over the live client: `null` before
 * `CoreStartState.Ready` reads as no matches (AppModel's `guard let client`,
 * `:608`), the FFI call runs on `Dispatchers.IO`, and every error except
 * cancellation — which 86 uses to drop stale queries — returns `[]`.
 */
fun CoreFileSearch(client: () -> CoreClient?): NewSessionFileSearch =
    NewSessionFileSearch { deviceId, spaceId, query ->
        val live = client() ?: return@NewSessionFileSearch emptyList()
        withContext(Dispatchers.IO) {
            try {
                live.searchFiles(deviceId, null, spaceId, query)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList() // never throw into 86's search coroutine
            }
        }
    }

/** The page's default search: the process's client, resolved per call. */
@Composable
fun rememberCoreFileSearch(): NewSessionFileSearch {
    val app = LocalContext.current.applicationContext as RobocoApplication
    return remember { CoreFileSearch { app.clientOrNull() } }
}
