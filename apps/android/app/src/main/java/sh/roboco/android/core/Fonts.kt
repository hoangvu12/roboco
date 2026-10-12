package sh.roboco.android.core

import android.content.Context
import android.graphics.Typeface
import androidx.annotation.VisibleForTesting
import java.io.IOException
import java.io.InputStream
import uniffi.roboco_core.FaceData
import uniffi.roboco_core.FaceRole

/**
 * A bundled face that could not be read or parsed. Subclass of
 * [IllegalStateException] so the startup path fails loudly; `path` feeds
 * [FontGate.fail], which is what the release fatal screen lists.
 */
class FontLoadException(
    val path: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * The 12 bundled faces, read once: the bytes for the Rust `TextSystem`
 * (the same bytes the platform fonts were built from) and the per-role
 * [Typeface] chains. Kept for the process lifetime (~1.6 MB of byte
 * arrays + 1.6 MB of direct buffers), like iOS's `static let registry`
 * (`Fonts.swift:26-38`).
 */
class LoadedFonts(
    /** 12 entries in `FaceRole.entries` order, byte-equal to the assets. */
    val faceData: List<FaceData>,
    /** One chain [Typeface] per role (an [EnumMap] with all 12). */
    val typefaces: Map<FaceRole, Typeface>,
    /** `"<file>#<ttc index>"` or null when no CJK font was found. */
    val cjkSource: String?,
)

/**
 * Reads the 12 TTFs once and hands the **same bytes** to Rust
 * (`FaceData`) and to the platform ([FontChain.build]'s `Font.Builder`),
 * exactly as `Fonts.swift:22-38` registers one `CGFont` from one `Data`.
 *
 * Fail-loud, unlike #609's `runCatching { … }.getOrNull()` (its review
 * bug 8: a missing Sans face makes the Rust layout worker skip every
 * pass, blanking the transcript) and unlike iOS's silent skip
 * (`Fonts.swift:26-30`): a missing, short, wrong-magic or unparseable
 * asset throws [FontLoadException] naming the path. Success **and**
 * failure are memoised: a second [load] returns the same
 * [LoadedFonts] or rethrows the same exception without re-reading.
 */
object Fonts {

    private val lock = Any()

    /** The memoised [LoadedFonts], or the thrown failure. */
    @Volatile
    private var memo: Any? = null

    /** TrueType version 1.0: every bundled face starts `00 01 00 00`. */
    private val trueTypeMagic = byteArrayOf(0, 1, 0, 0)

    /** The production entry point: reads the APK's merged assets. */
    fun load(context: Context): LoadedFonts = load(
        open = { path -> context.applicationContext.assets.open(path) },
        buildTypefaces = { pairs -> FontChain.build(pairs) },
        cjk = { FontChain.cjkSource },
    )

    /**
     * Testable core: [open] throws (e.g. `FileNotFoundException`) for a
     * missing path or returns a stream that is empty, truncated or has
     * the wrong magic; [buildTypefaces] builds (or fakes) the chains;
     * [cjk] reports the CJK source.
     */
    internal fun load(
        open: (String) -> InputStream,
        buildTypefaces: (List<Pair<Face, ByteArray>>) -> Map<FaceRole, Typeface>,
        cjk: () -> String?,
    ): LoadedFonts {
        memo?.let { return unpack(it) }
        synchronized(lock) {
            memo?.let { return unpack(it) }
            val result: Any = try {
                readAndAssemble(open, buildTypefaces, cjk)
            } catch (failure: Throwable) {
                failure
            }
            memo = result
            return unpack(result)
        }
    }

    /**
     * The per-role accessor ticket 62's `StyleFonts` takes as its
     * constructor lambda.
     */
    fun typeface(context: Context, role: FaceRole): Typeface = load(context).typefaces.getValue(role)

    @VisibleForTesting
    internal fun resetForTest() {
        synchronized(lock) { memo = null }
        FontChain.resetForTest()
    }

    private fun unpack(stored: Any): LoadedFonts {
        if (stored is LoadedFonts) return stored
        throw stored as Throwable
    }

    private fun readAndAssemble(
        open: (String) -> InputStream,
        buildTypefaces: (List<Pair<Face, ByteArray>>) -> Map<FaceRole, Typeface>,
        cjk: () -> String?,
    ): LoadedFonts {
        val faces = Face.entries.map { face -> face to readFace(open, face.assetPath) }
        return LoadedFonts(
            faceData = faces.map { (face, bytes) -> FaceData(face.role, bytes) },
            typefaces = buildTypefaces(faces),
            cjkSource = cjk(),
        )
    }

    private fun readFace(open: (String) -> InputStream, path: String): ByteArray {
        val bytes = try {
            open(path).use { stream -> stream.readBytes() }
        } catch (failure: IOException) {
            throw FontLoadException(path, "Unreadable font asset: $path", failure)
        }
        if (bytes.size < 4 || !bytes.copyOf(4).contentEquals(trueTypeMagic)) {
            throw FontLoadException(path, "Unreadable font asset: $path", null)
        }
        return bytes
    }
}
