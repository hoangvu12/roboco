package sh.roboco.android.composer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap

/**
 * The (top, bottom) colour pairs the staged-image fixtures cycle through
 * (android-ui-parity #88): saturated, distinct per seed, never a palette
 * colour, so a mis-cropped or mis-scaled tile is visible at a glance.
 */
private val FixturePairs = listOf(
    0xFF5B43E8 to 0xFFF59E0B,
    0xFF15803D to 0xFF93C5FD,
    0xFFDC2626 to 0xFFFDE68A,
    0xFF0EA5E9 to 0xFF1E293B,
)

/**
 * A deterministic staged image (android-ui-parity #88's debug fixture,
 * shared by the gallery, the previews and the tests): a 180 x 135 px
 * landscape thumbnail (so the tile's aspect-fill must crop the sides),
 * the top half in colour A and the bottom half in colour B — the pair
 * cycles by `seed mod 4` — plus a magenta stripe over x 0..19 that the
 * centred crop must always cut away (the crop takes ~22 px from each
 * side). `bytes` is empty: nothing stages or sends a fixture.
 *
 * Landscape 180 x 135 mirrors the shape of a camera photo: the tile's
 * centred aspect-fill crop cuts ~22 px from each side, so the visible
 * square spans the A/B boundary and shows both halves.
 */
fun fixtureStagedImage(seed: Int): StagedImage {
    val (a, b) = FixturePairs[seed.mod(FixturePairs.size)]
    val w = 180
    val h = 135
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val paint = Paint()
    Canvas(bitmap).apply {
        paint.color = a.toInt()
        drawRect(0f, 0f, w.toFloat(), h / 2f, paint)
        paint.color = b.toInt()
        drawRect(0f, h / 2f, w.toFloat(), h.toFloat(), paint)
        // The must-be-cropped stripe.
        paint.color = 0xFFFF00FF.toInt()
        drawRect(0f, 0f, 20f, h.toFloat(), paint)
    }
    return StagedImage(
        id = "fixture-$seed",
        name = "image-$seed.jpg",
        bytes = ByteArray(0),
        thumb = bitmap.asImageBitmap(),
    )
}
