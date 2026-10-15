package sh.roboco.android.composer

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * One pick heading for the staging pipeline (android-ui-parity #87): a
 * content/file URI plus the caller's base name for the staged file.
 *
 * [baseName] is `null` for Photo Library, camera and paste picks (iOS
 * passes `name: nil`, so the file is named `image-<first 8 id chars>`);
 * Choose File passes the document's `DISPLAY_NAME` without its extension
 * (iOS `url.deletingPathExtension().lastPathComponent`).
 */
data class PickedImage(val uri: Uri, val baseName: String? = null)

/**
 * The image staging pipeline (android-ui-parity #87): the port of
 * `AttachmentPicker.stage(image:name:)` (`ios:Composer/AttachmentPicker.swift:110-122`)
 * — decode (EXIF applied), downscale to a 2560 px long side, re-encode as
 * JPEG at quality 86, drop anything over 24 MiB, plus a 180 px thumbnail,
 * a fresh lowercased UUID id and an `image-<8 hex>.jpg` (or
 * `<baseName>.jpg`) file name.
 *
 * Roboco adaptations (see the ticket): decoding goes through
 * [ImageDecoder] (EXIF orientation applied by the decoder itself, and
 * `info.size` is the **upright** size, exactly what `UIImage.size` gives
 * iOS), the long-side bound is applied **at decode time** via
 * `setTargetSize` so a 6000 px photo never materialises as a full-size
 * bitmap, the output colour space is forced to sRGB, and the caller
 * (`AttachPicker`) stops staging once the free slots are full instead of
 * decoding images that would be dropped (#609's fix). Images that fail to
 * open, decode, encode or fit are dropped **silently** (`compactMap` /
 * `guard … else { return nil }` on iOS) — no error UI, ever.
 *
 * Every function that touches pixels must run **off the main thread**
 * ([stage] documents that; [stageAll] switches to `Dispatchers.IO`
 * itself). [encode] recycles the decoded bitmap (unless the bitmap itself
 * is the thumbnail, in which case it is handed to the [StagedImage]
 * alive).
 */
object ImageStaging {

    /** `maxSide` (`:111`). */
    const val MaxSide = 2560

    /** `jpegData(compressionQuality: 0.86)` (`:118`). */
    const val JpegQuality = 86

    /** `24 * 1024 * 1024` (`:118`) = 25 165 824 bytes. */
    const val MaxBytes = 24 * 1024 * 1024

    /** `preparingThumbnail(of: 180×180)` (`:119`) — the long-side fit, never upscaled. */
    const val ThumbSide = 180

    private const val TAG = "ImageStaging"

    /**
     * The decode target for an upright w×h image, or `null` when the image
     * already fits [MaxSide] (scale ≥ 1: no downsampling, no upscaling).
     * `min(1, maxSide / max(w, h))`, each side rounded half away from zero
     * and floored at 1 (`:111-116`).
     */
    fun targetSize(w: Int, h: Int): IntSize? {
        val scale = min(1f, MaxSide.toFloat() / max(w, h))
        if (scale >= 1f) return null
        return IntSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
    }

    /**
     * The thumbnail size for a w×h image: the long side fits
     * [ThumbSide], never upscaled (`:119`).
     */
    fun thumbSize(w: Int, h: Int): IntSize {
        val t = ThumbSide.toFloat() / max(w, h)
        if (t >= 1f) return IntSize(w, h)
        return IntSize(max(1, (w * t).roundToInt()), max(1, (h * t).roundToInt()))
    }

    /**
     * iOS `url.deletingPathExtension().lastPathComponent` on a
     * `DISPLAY_NAME`: everything before the **last** dot, only when the
     * dot is not the first character (a leading dot is not an extension),
     * `null` when the display name is null or blank.
     *
     * | `DISPLAY_NAME` | result |
     * |---|---|
     * | `IMG_1234.HEIC` | `IMG_1234` |
     * | `photo.tar.png` | `photo.tar` |
     * | `scan` | `scan` |
     * | `.hidden` | `.hidden` |
     * | `""` / `null` | `null` |
     */
    fun baseName(displayName: String?): String? {
        if (displayName == null) return null
        val dot = displayName.lastIndexOf('.')
        val base = if (dot > 0) displayName.substring(0, dot) else displayName
        return base.ifBlank { null }
    }

    /**
     * The staged file name: `"\(name ?? "image-\(id.prefix(8))").jpg"`
     * (`:121`). A UUID's first 8 characters are always hex digits.
     */
    fun fileName(baseName: String?, id: String): String = "${baseName ?: "image-" + id.take(8)}.jpg"

    /**
     * Encodes an already-decoded upright bitmap (the `UIGraphicsImageRenderer`
     * + `jpegData` half of `stage`, `:111-118`): JPEG at [JpegQuality], kept
     * only when the bytes fit [MaxBytes], plus the [ThumbSide] thumbnail,
     * a fresh lowercased UUID id and the file name. **Recycles [bitmap]** on
     * every path except when the bitmap itself is the thumbnail.
     *
     * Returns `null` when the encode fails or the bytes exceed the cap —
     * the silent drop.
     */
    fun encode(bitmap: Bitmap, baseName: String?): StagedImage? = encode(bitmap, baseName, MaxBytes)

    /**
     * The test seam of [encode]: the same pipeline with an explicit byte
     * cap, so an over-cap drop can be exercised without a 24 MiB bitmap.
     */
    internal fun encode(bitmap: Bitmap, baseName: String?, maxBytes: Int): StagedImage? {
        val bytes = try {
            val out = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, JpegQuality, out)) { "JPEG compress failed" }
            out.toByteArray()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            Log.w(TAG, "JPEG encode failed", failure)
            bitmap.recycle()
            return null
        }
        if (bytes.size > maxBytes) {
            Log.w(TAG, "encoded ${bytes.size} bytes over the $maxBytes-byte cap; dropped")
            bitmap.recycle()
            return null
        }
        val id = UUID.randomUUID().toString().lowercase()
        return try {
            val size = thumbSize(bitmap.width, bitmap.height)
            // createScaledBitmap returns the source bitmap when the sizes
            // match, so compare identity, not equality, before recycling.
            val thumb = if (size.width == bitmap.width && size.height == bitmap.height) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, size.width, size.height, true)
            }
            val staged = StagedImage(id, fileName(baseName, id), bytes, thumb.asImageBitmap())
            if (thumb !== bitmap) bitmap.recycle()
            staged
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            Log.w(TAG, "thumbnail generation failed", failure)
            bitmap.recycle()
            null
        }
    }

    /**
     * Decodes and encodes one pick (the whole of `stage`, `:110-122`).
     * **Must be called off the main thread.**
     *
     * [ImageDecoder] applies the EXIF orientation itself, so `info.size`
     * is the upright size the long-side rule must use (`UIImage.size`
     * parity), HEIC/PNG/WebP/GIF(first frame)/JPEG/BMP all take the same
     * path, and `setTargetSize` downsamples a 6000 px photo at decode time
     * — no full-size bitmap is ever allocated. [ALLOCATOR_SOFTWARE] is
     * mandatory: hardware bitmaps cannot be compressed or scaled. The
     * output colour space is forced to sRGB (ticket 20's rule). A revoked
     * URI throws [android.content.SecurityException], a corrupt file
     * throws from the decoder — both, and any I/O failure, are logged and
     * turned into `null` (the silent drop). `CancellationException` is
     * rethrown so [stageAll] can stop early.
     */
    fun stage(resolver: ContentResolver, pick: PickedImage): StagedImage? {
        val source = try {
            ImageDecoder.createSource(resolver, pick.uri)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Log.w(TAG, "cannot open ${pick.uri}", failure)
            return null
        }
        val bitmap = try {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                targetSize(info.size.width, info.size.height)?.let { target ->
                    decoder.setTargetSize(target.width, target.height)
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Log.w(TAG, "decode failed for ${pick.uri}", failure)
            return null
        }
        return encode(bitmap, pick.baseName)
    }

    /**
     * Stages [picks] **in order, one at a time**, stopping as soon as the
     * list holds [limit] images: a failed pick does not use a slot, so the
     * next one fills it (#609's fix — iOS stages everything and keeps the
     * first `8 − n`; same result, without decoding images that would be
     * dropped). [limit] ≤ 0 returns empty without touching anything. The
     * batch is returned whole — the caller delivers it in **one**
     * `ComposerController.addImages` call, like iOS's single
     * `completion([staged])`.
     *
     * Switches to `Dispatchers.IO` itself; stages sequentially (eight
     * 2560 px ARGB bitmaps at once would be about 160 MB) and checks
     * cancellation between picks.
     */
    suspend fun stageAll(resolver: ContentResolver, picks: List<PickedImage>, limit: Int): List<StagedImage> {
        if (limit <= 0) return emptyList()
        return withContext(Dispatchers.IO) {
            val staged = ArrayList<StagedImage>()
            for (pick in picks) {
                currentCoroutineContext().ensureActive()
                stage(resolver, pick)?.let { staged += it }
                if (staged.size >= limit) break
            }
            staged
        }
    }
}
