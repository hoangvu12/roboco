package sh.roboco.android.debug

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import sh.roboco.android.design.glass.GlassTextCapsule

/**
 * The "Transcript Lab" section of the debug design gallery
 * (android-ui-parity #135): a column of launch buttons, each starting
 * [TranscriptLabActivity] with the extras of one lab scenario. Static
 * under the Roborazzi capture — the launches are live taps; the lab's
 * own goldens live in `TranscriptLabScreenshotTest`.
 */
@Composable
fun TranscriptLabGallerySection() {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassTextCapsule("Open lab", onClick = {
            context.startActivity(labIntent(context))
        })
        GlassTextCapsule("300 turns + autostream", onClick = {
            context.startActivity(
                labIntent(context)
                    .putExtra(LAB_EXTRA_TURNS, 300)
                    .putExtra(LAB_EXTRA_AUTOSTREAM, true),
            )
        })
        GlassTextCapsule("Top", onClick = {
            context.startActivity(labIntent(context).putExtra(LAB_EXTRA_TOP, true))
        })
        GlassTextCapsule("Autoscroll", onClick = {
            context.startActivity(
                labIntent(context)
                    .putExtra(LAB_EXTRA_TURNS, 40)
                    .putExtra(LAB_EXTRA_AUTOSCROLL, true),
            )
        })
    }
}

/** A plain launch intent for the lab (the gallery's context is the activity). */
private fun labIntent(context: Context): Intent =
    Intent(context, TranscriptLabActivity::class.java)
