package sh.roboco.android.transcript

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * One way to get an image's bytes. The loader tries a reference's sources
 * in order and fails only when every one fails (a source whose bytes the
 * phone can't decode — SVG — counts as failed, so the next one runs).
 */
enum class ImageSource {
    /** A `data:` URI, decoded on the phone. */
    Inline,

    /** An `http(s)` URL, fetched by the phone ([WebImageFetcher]). */
    Web,

    /**
     * The engine's `ReadImageChunk`: any path (absolute, `~`, relative to
     * the chat's cwd), `file:`/`roboco-file:`/`pending://` references,
     * URLs only the PC can reach, and SVG rasterized to PNG.
     */
    Engine,
}

/**
 * The sources for [reference], most direct first. Nothing is refused up
 * front: every non-blank reference reaches the engine at least.
 */
fun imageSources(reference: String): List<ImageSource> = when {
    reference.isBlank() -> emptyList()
    isDataUri(reference) -> listOf(ImageSource.Inline, ImageSource.Engine)
    isWebUrl(reference) -> listOf(ImageSource.Web, ImageSource.Engine)
    else -> listOf(ImageSource.Engine)
}

/** Whether any source can try [reference] (only blank ones have none). */
fun isFetchableImageReference(ref: String): Boolean = imageSources(ref).isNotEmpty()

internal fun isWebUrl(reference: String): Boolean {
    val ref = reference.trim()
    return ref.startsWith("http://", ignoreCase = true) || ref.startsWith("https://", ignoreCase = true)
}

internal fun isDataUri(reference: String): Boolean = reference.trim().startsWith("data:", ignoreCase = true)

/**
 * The bytes of a `data:[<mime>][;base64],<payload>` URI (base64, URL-safe
 * base64 or percent-encoded), or null when it is malformed.
 */
fun decodeDataUri(reference: String): ByteArray? {
    val ref = reference.trim()
    if (!ref.startsWith("data:", ignoreCase = true)) return null
    val comma = ref.indexOf(',')
    if (comma < 0) return null
    val meta = ref.substring(5, comma)
    val payload = ref.substring(comma + 1)
    val base64 = meta.split(';').any { it.trim().equals("base64", ignoreCase = true) }
    return if (base64) {
        val compact = percentDecode(payload).filterNot { it.isWhitespace() }.trimEnd('=')
        runCatching { Base64.getDecoder().decode(compact) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(compact) }.getOrNull()
    } else {
        percentDecodeBytes(payload)
    }
}

private fun percentDecode(text: String): String = String(percentDecodeBytes(text), Charsets.UTF_8)

/** The value of an ASCII hex digit, or -1 (no sign, unlike `toIntOrNull(16)`). */
private fun hexDigit(b: Byte): Int = when (val c = b.toInt()) {
    in '0'.code..'9'.code -> c - '0'.code
    in 'a'.code..'f'.code -> c - 'a'.code + 10
    in 'A'.code..'F'.code -> c - 'A'.code + 10
    else -> -1
}

/** `%XX` escapes decoded to bytes; a `%` not followed by two hex digits stays literal. */
internal fun percentDecodeBytes(text: String): ByteArray {
    val raw = text.toByteArray(Charsets.UTF_8)
    val out = ByteArrayOutputStream(raw.size)
    var at = 0
    while (at < raw.size) {
        val hex = if (raw[at] == '%'.code.toByte() && at + 2 < raw.size) {
            val hi = hexDigit(raw[at + 1])
            val lo = hexDigit(raw[at + 2])
            if (hi >= 0 && lo >= 0) hi * 16 + lo else null
        } else {
            null
        }
        if (hex != null) {
            out.write(hex)
            at += 3
        } else {
            out.write(raw[at].toInt())
            at += 1
        }
    }
    return out.toByteArray()
}

/** The phone's own fetch of a web image. Throws on any failure. */
fun interface WebImageFetcher {
    suspend fun fetch(url: String): ByteArray
}

/** Image cap shared with the engine's reads. */
internal const val MaxImageBytes = 32 * 1024 * 1024

/**
 * Platform HTTP (`HttpURLConnection`, no Play or Google dependency):
 * follows up to ten redirects — including http↔https hops, which
 * `HttpURLConnection` refuses on its own — with a Roboco user agent,
 * bounded connect/read timeouts and the [MaxImageBytes] cap.
 */
object PlatformWebImageFetcher : WebImageFetcher {
    private const val ConnectTimeoutMs = 8_000
    private const val ReadTimeoutMs = 15_000
    private const val MaxRedirects = 10
    private const val UserAgent = "Mozilla/5.0 (Linux; Android) Roboco (+https://github.com/hoangvu12/roboco)"
    private const val Accept = "image/avif,image/webp,image/apng,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5"

    override suspend fun fetch(url: String): ByteArray = withContext(Dispatchers.IO) {
        var current = URL(url.trim())
        repeat(MaxRedirects + 1) {
            val connection = current.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = ConnectTimeoutMs
                connection.readTimeout = ReadTimeoutMs
                connection.setRequestProperty("User-Agent", UserAgent)
                connection.setRequestProperty("Accept", Accept)
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("HTTP $code without a Location")
                    current = URI(current.toString()).resolve(location).toURL()
                    return@repeat
                }
                if (code !in 200..299) throw IOException("HTTP $code for $current")
                val length = connection.contentLengthLong
                if (length > MaxImageBytes) throw IOException("image is larger than ${MaxImageBytes shr 20} MB")
                connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream(if (length > 0) length.toInt() else 64 * 1024)
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (out.size() + n > MaxImageBytes) {
                            throw IOException("image is larger than ${MaxImageBytes shr 20} MB")
                        }
                        out.write(buffer, 0, n)
                    }
                    return@withContext out.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("too many redirects for $url")
    }
}

/**
 * The delay before a failed tile's [attempt]-th automatic retry (1-based):
 * 2 s, 4 s, 8 s … capped at a minute, so a dead URL is never hammered.
 */
fun imageRetryDelayMs(attempt: Int): Long {
    val exponent = (attempt.coerceAtLeast(1) - 1).coerceAtMost(5)
    return min(2_000L shl exponent, 60_000L)
}

/**
 * Automatic retries a failed tile makes on its own backoff before it stops
 * (2 + 4 + 8 + 16 + 32 + 60 + 60 + 60 s ≈ 4 min). After that only a
 * reconnect or a tap tries again, and either restarts the count.
 */
const val MaxAutoImageRetries = 8

/**
 * One emission each time [connected] turns true after the first value:
 * the engine came back, so failed tiles retry at once.
 */
fun reconnects(connected: Flow<Boolean>): Flow<Unit> =
    connected.distinctUntilChanged().drop(1).filter { it }.map { }
