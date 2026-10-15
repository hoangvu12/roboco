package sh.roboco.android.update

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The release feed's endpoints (android-next#04), mirroring
 * `crates/update/src/lib.rs:258-268`: GitHub's latest-release download
 * base (`manifest.json` and the artifacts live here) and the releases
 * page the app opens when it cannot replace itself.
 */
const val RELEASES_DOWNLOAD: String = "https://github.com/hoangvu12/roboco/releases/latest/download"

/** The repository's GitHub Releases page (the advisory destination). */
const val RELEASES_PAGE: String = "https://github.com/hoangvu12/roboco/releases/latest"

/** The manifest fetch's budget: short, like the desktop's feed poll. */
private const val CONNECT_TIMEOUT_MS = 10_000

/** The manifest fetch's per-read budget. */
private const val READ_TIMEOUT_MS = 10_000

/** The APK download's connect budget (reads get the same 10 s). */
private const val APK_CONNECT_TIMEOUT_MS = 15_000

/** The APK download's per-read budget. */
private const val APK_READ_TIMEOUT_MS = 15_000

/** `manifest.json`'s body is capped here; a sane manifest is sub-kilobyte. */
private const val MANIFEST_CAP_BYTES = 1 shl 20

/**
 * Where the manifest text comes from — a seam so tests feed a fake
 * document and production dials GitHub. The contract mirrors the desktop
 * checker: `null` when the manifest does not exist (a 404 from a repo
 * with no releases yet), [IOException] for network failures; parse
 * errors are the caller's (a parsed `null` is "no manifest").
 */
interface ManifestSource {

    /** Fetches the latest `manifest.json` text; null when it is absent. */
    @Throws(IOException::class)
    suspend fun fetch(): String?
}

/**
 * The production manifest source: one HTTPS GET over
 * [RELEASES_DOWNLOAD]/manifest.json on the IO lane. HttpURLConnection
 * follows GitHub's https→https redirect to the CDN by default; a 404 is
 * null, any other non-2xx is an [IOException]. No credentials, no query,
 * no fragment (the desktop validates the same shape).
 */
class HttpManifestSource(
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ManifestSource {

    override suspend fun fetch(): String? = withContext(io) {
        val connection = openHttps("$RELEASES_DOWNLOAD/manifest.json", CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
        try {
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> readBody(connection)?.toString(Charsets.UTF_8)
                HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> throw IOException("manifest.json: HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readBody(connection: HttpURLConnection): ByteArray? {
        val stream = connection.inputStream ?: return null
        val body = stream.use { it.readBytes().takeIf { bytes -> bytes.size <= MANIFEST_CAP_BYTES } }
            ?: throw IOException("manifest.json is larger than the cap")
        return body
    }
}

/**
 * Where an APK's bytes come from — the downloader's seam. The pair is the
 * stream and the total length when the server reports it
 * (`Content-Length`; -1 means unknown).
 */
interface ApkByteSource {

    /** Opens [url] for reading; callers close the stream. */
    @Throws(IOException::class)
    fun open(url: String): Pair<InputStream, Long?>
}

/**
 * The production APK source: one HTTPS GET to the release asset, streamed
 * (never `readBytes` — the progress and the digest are computed chunk by
 * chunk, and a 20+ MB body must never sit in memory).
 */
class HttpApkByteSource : ApkByteSource {

    override fun open(url: String): Pair<InputStream, Long?> {
        val connection = openHttps(url, APK_CONNECT_TIMEOUT_MS, APK_READ_TIMEOUT_MS)
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw IOException("apk: HTTP $code")
        }
        val length = connection.contentLengthLong
        return connection.inputStream to (length.takeIf { it > 0 })
    }
}

/** One connection, the shared setup: timeouts, redirects, the UA. */
private fun openHttps(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Roboco-Android")
    }
