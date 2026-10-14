package sh.roboco.android.flows

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Shared semantics matchers and artefact helpers for the instrumented
 * flow suite (android-app#30) — the pieces every ported iOS test needs.
 */
object FlowSemantics {

    /**
     * Matches a node whose text **or** content description starts with
     * [prefix] — the transcript's user-message label prefix ("You: ",
     * ui-parity #129) is exposed both ways depending on the row, so the
     * iOS `label BEGINSWITH 'You: …'` predicate ports to this.
     */
    fun startsWith(prefix: String): SemanticsMatcher =
        SemanticsMatcher("text or content description starts with '$prefix'") { node ->
            val config = node.config
            (config.contains(SemanticsProperties.Text) &&
                config[SemanticsProperties.Text].any { it.text.startsWith(prefix) }) ||
                (config.contains(SemanticsProperties.ContentDescription) &&
                    config[SemanticsProperties.ContentDescription].any { it.startsWith(prefix) })
        }

    /** [startsWith] over "You: " — the optimistic echo + settled user rows. */
    fun userEcho(): SemanticsMatcher = startsWith("You: ")

    /**
     * Writes a PNG artefact into the app's external files dir, where the
     * flow script's artefact copy step picks it up (`adb pull
     * /sdcard/Android/data/sh.roboco.android/files/<name>`), like the
     * pre-rig `SessionFlowTest` did.
     */
    fun saveScreenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = context.getExternalFilesDir(null) ?: return
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        try {
            dir.resolve(name).outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
