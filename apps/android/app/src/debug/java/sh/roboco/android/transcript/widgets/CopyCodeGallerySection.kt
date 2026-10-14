package sh.roboco.android.transcript.widgets

import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.hairlineBorder
import sh.roboco.android.design.uiStyle

/**
 * The "Copy code" section of the debug design gallery (ticket 119): the
 * Rust geometry of the code-card corner cell on `palette.background` —
 *
 *  - the static fixture: a 357 × 104 dp mock code card (393 − 2 × 18
 *    margin; header 32 + 3 × 20 line height + 12 bottom pad, per
 *    `place_code`) with circular 12 dp corners, `codeBackground` fill and
 *    the 1-px `codeBorder` hairline, carrying a static [CopyCodeGlyph] at
 *    the cell's exact rect (313, 0, 44, 32);
 *  - the live fixture: the same card with a working [CopyCodeButton] —
 *    tap it: the clipboard gets the payload, one light haptic plays, the
 *    check shows for 1.4 s and swaps back with no animation;
 *  - the clipboard line: `Clipboard: <first 40 chars>` refreshed through
 *    an [ClipboardManager.OnPrimaryClipChangedListener], so a real tap
 *    shows what was copied. The text is read **only** inside the change
 *    callback (reading `primaryClip` in composition would trigger the
 *    system's "pasted from clipboard" notice on API 31+): until the first
 *    change event the line shows a description.
 *
 * No label text and no code text in the cards: Rust paints those (62/63);
 * the widget ticket only owns the corner cell.
 *
 * Registered in `Gallery.sections` as "Copy code" (slug `copy_code`).
 */
@Composable
fun CopyCodeGallerySection() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CopyCodeFixture(copied = false)
        CopyCodeFixture(copied = true)
        CopyCodeFixture(copied = null)
        ClipboardLine()
    }
}

/**
 * The shared fixture (previews, goldens, the gallery): [copied] `null`
 * renders the live [CopyCodeButton], `true`/`false` the static glyph.
 * Composed at `w393dp-h852dp-xxhdpi` for the Roborazzi goldens
 * (`transcript/copy_code_{idle,copied}_{light,dark}.png`).
 */
@Composable
fun CopyCodeFixture(copied: Boolean?) {
    val palette = RobocoTheme.palette
    // Circular corners (drawRoundRect circular arcs, ticket 64) — not the
    // transcript's SmoothRoundedShape.
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .size(357.dp, 104.dp)
            .clip(shape)
            .background(palette.codeBackground)
            .hairlineBorder(palette.codeBorder, shape),
    ) {
        // The cell Rust reserves: (313, 0, 44, 32), flush with the card's
        // top and right edges.
        Box(Modifier.absoluteOffset(313.dp, 0.dp).size(44.dp, 32.dp)) {
            if (copied == null) {
                CopyCodeButton("val band = floor((offset - 700f) / 256f) * 256f")
            } else {
                CopyCodeGlyph(copied)
            }
        }
    }
}

/**
 * The clipboard readout. `primaryClip` is read only inside the change
 * listener (the system does not flag the app's own writes); until then
 * the line shows the description, so opening the section never triggers
 * the paste notice.
 */
@Composable
private fun ClipboardLine() {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    DisposableEffect(context) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            text = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        }
        cm?.addPrimaryClipChangedListener(listener)
        onDispose { cm?.removePrimaryClipChangedListener(listener) }
    }
    BasicText(
        "Clipboard: " + (text?.take(40) ?: "tap the live card above"),
        style = uiStyle(Face.Sans, LocalTypeScale.current.fixed(13f), RobocoTheme.palette.secondary),
    )
}
