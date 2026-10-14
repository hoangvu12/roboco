package sh.roboco.android.transcript.widgets

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Looper
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import java.io.IOException
import java.io.InputStream
import sh.roboco.android.BuildConfig
import sh.roboco.android.R
import sh.roboco.android.design.Icon
import sh.roboco.android.design.LocalIsDark
import sh.roboco.android.design.RobocoIcon
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.TranscriptPalette
import sh.roboco.android.design.color
import uniffi.roboco_core.ColorRole

/**
 * How one icon-widget name is drawn (android-ui-parity #124; the three
 * ways iOS `RowView.swift:251-262` picks between an asset and an SF
 * Symbol, plus the "nothing" case):
 *
 * 1. [Tool] — a `tool-*` template VectorDrawable from #13's pipeline
 *    (Roboco's own line SVGs, identical geometry to the iOS catalog),
 *    tinted with the widget's color and aspect-fit into the rect;
 * 2. [FileIcon] — a full-colour `fileicon-*` PNG (96 px, with a
 *    `-dark` variant picked by the appearance), **never** tinted;
 * 3. [Symbol] — ticket 06's glyph for the SF Symbol name, at point
 *    size `rect.height × 0.8`, Medium, centred, never clipped;
 * 4. [Unknown] — nothing is drawn; the name is logged once.
 */
internal sealed interface TranscriptIconSource {
    /** A `tool-*` stem: `R.drawable.zi_<stem>` in template ink. */
    data class Tool(@DrawableRes val res: Int) : TranscriptIconSource

    /**
     * A `fileicon-*` name: `assets/icons/<name>[-dark].png`, original
     * colours. Resolution (and the dark-variant fallback) happens at
     * decode time, per appearance.
     */
    data class FileIcon(val name: String) : TranscriptIconSource

    /** An SF Symbol name resolved through [RobocoIcons.forSfName]. */
    data class Symbol(val icon: RobocoIcon) : TranscriptIconSource

    /** Neither an asset nor a known symbol: draw nothing. */
    data object Unknown : TranscriptIconSource
}

/**
 * The name → art resolver and the file-icon bitmap cache (android-ui-parity
 * #124): the port of iOS `RowView.asset(_:)` (`ios:Transcript/RowView.swift:
 * 287-294`) plus the SF fallback. Process-wide and main-thread only — the
 * maps are deliberately not synchronized, like iOS's `static var assets`.
 *
 * Misses are cached (a `null` bitmap is stored under its stem), so a name
 * the core repeats on every row costs one classification and at most two
 * asset opens per process. Nothing is ever evicted, like iOS; see the
 * ticket's memoization note for the worst case.
 */
internal object TranscriptIcons {

    /**
     * The 15 `tool-*` stems the iOS ToolIcons catalog carries → the #13
     * VectorDrawables (Roboco's `crates/ui/assets/icons` line SVGs, the
     * same geometry; the four stems the core never emits are kept for
     * catalog parity). A literal table, never `Resources.getIdentifier`
     * (reflection, slow, breaks resource shrinking).
     */
    val toolDrawables: Map<String, Int> = mapOf(
        "tool-terminal" to R.drawable.zi_terminal,
        "tool-document" to R.drawable.zi_document,
        "tool-document-add" to R.drawable.zi_document_add,
        "tool-pen" to R.drawable.zi_pen,
        "tool-magnifer" to R.drawable.zi_magnifer,
        "tool-folder-with-files" to R.drawable.zi_folder_with_files,
        "tool-global" to R.drawable.zi_global,
        "tool-checklist" to R.drawable.zi_checklist,
        "tool-bot" to R.drawable.zi_bot,
        "tool-widget" to R.drawable.zi_widget,
        "tool-chat-round-line" to R.drawable.zi_chat_round_line,
        "tool-alt-arrow-down" to R.drawable.zi_alt_arrow_down,
        "tool-alt-arrow-right" to R.drawable.zi_alt_arrow_right,
        "tool-arrow-up-right" to R.drawable.zi_arrow_up_right,
        "tool-git-branch" to R.drawable.zi_git_branch,
    )

    /** Name → classification; misses are cached as [TranscriptIconSource.Unknown]. */
    private val sources = HashMap<String, TranscriptIconSource>()

    /** Asset stem → decoded bitmap; nulls (misses) are cached too. */
    private val bitmaps = HashMap<String, ImageBitmap?>()

    /** Names already logged as unknown (one warning per name per process). */
    private val logged = HashSet<String>()

    /** Opens `assets/<path>` — a seam so tests can count opens and miss on demand. */
    @VisibleForTesting
    internal var openAsset: (Context, String) -> InputStream? = ::openAssetFromApk

    /** Emits one warning line — a seam so tests can capture the log. */
    @VisibleForTesting
    internal var log: (String) -> Unit = ::logWarning

    /**
     * Memoized classification of [name] (rules 1–4 of the ticket's spec).
     * A `tool-`/`fileicon-` name that misses never falls through to the SF
     * registry: no SF name starts with those prefixes, exactly like iOS's
     * `asset(_:)` returning nil for them.
     */
    fun source(name: String): TranscriptIconSource {
        if (BuildConfig.DEBUG) checkMainThread("source")
        return sources.getOrPut(name) { resolve(name) }
    }

    /**
     * The `assets/icons/<name>[-dark].png` for [name]: in dark mode the
     * `-dark` file when it exists, falling back to the universal one
     * (iOS's asset-catalog appearance rule). Decodes synchronously on the
     * caller's thread — the same as `UIImage(named:)`; a 96 px PNG decodes
     * in well under a millisecond and each stem is decoded at most once
     * per process.
     */
    fun fileIcon(context: Context, name: String, dark: Boolean): ImageBitmap? {
        if (BuildConfig.DEBUG) checkMainThread("fileIcon")
        val bitmap = if (dark) {
            bitmap(context, "$name-dark") ?: bitmap(context, name)
        } else {
            bitmap(context, name)
        }
        if (bitmap == null) logOnce(name)
        return bitmap
    }

    /** Clears both caches and the log-once memory and restores the seams. */
    @VisibleForTesting
    internal fun resetForTest() {
        sources.clear()
        bitmaps.clear()
        logged.clear()
        openAsset = ::openAssetFromApk
        log = ::logWarning
    }

    /**
     * The uncached rule table: asset prefixes first, then the SF registry
     * (which also handles SF `N.circle` names through [RobocoIcons]).
     */
    private fun resolve(name: String): TranscriptIconSource {
        val resolved = when {
            name.startsWith("tool-") -> toolDrawables[name]?.let { TranscriptIconSource.Tool(it) }
            name.startsWith("fileicon-") -> TranscriptIconSource.FileIcon(name)
            else -> RobocoIcons.forSfName(name)?.let { TranscriptIconSource.Symbol(it) }
        }
        return resolved ?: TranscriptIconSource.Unknown.also { logOnce(name) }
    }

    /**
     * The cached decode for one stem. `containsKey`, never `getOrPut`: a
     * `HashMap.getOrPut` cannot cache a null value (Kotlin treats it as
     * absent), and #609's version re-opened every missing asset on every
     * recomposition.
     */
    private fun bitmap(context: Context, stem: String): ImageBitmap? =
        if (bitmaps.containsKey(stem)) {
            bitmaps[stem]
        } else {
            decode(context, stem).also { bitmaps[stem] = it }
        }

    /**
     * Decodes `assets/icons/<stem>.png` into an [ImageBitmap] with
     * mipmapping armed (a 96 px raster is filtered down 2–3× at real
     * sizes; HWUI then samples the mip chain instead of aliasing).
     */
    private fun decode(context: Context, stem: String): ImageBitmap? =
        openAsset(context, "icons/$stem.png")
            ?.use { BitmapFactory.decodeStream(it) }
            ?.also { it.setHasMipMap(true) }
            ?.asImageBitmap()

    /** First-seen [name] only: `icon widget: no glyph for '<name>'`. */
    private fun logOnce(name: String) {
        if (!logged.add(name)) return
        log("icon widget: no glyph for '$name'")
    }

    private fun checkMainThread(where: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "TranscriptIcons.$where runs on the main thread only (its caches are unsynchronized)"
        }
    }
}

/** The default [TranscriptIcons.openAsset]: the APK's merged assets. */
private fun openAssetFromApk(context: Context, path: String): InputStream? =
    try {
        context.assets.open(path)
    } catch (e: IOException) {
        null
    }

/** The default [TranscriptIcons.log]: one warning, `RobocoTranscript`. */
private fun logWarning(message: String) {
    Log.w("RobocoTranscript", message)
}

/**
 * The `Icon` widget (android-ui-parity #124; iOS `RowView.swift:251-262`):
 * the small glyph the core places at an exact rect — the 18 dp tool-line
 * icon, the 15.75 dp file badges and diff-stat icons, the 13.5 dp
 * sub-agent icon, the 16 dp chip-row and file-pill icons, the 15 dp inline
 * chips, the 18 dp markdown task boxes. Fills the box ticket 65 placed
 * (the rect is the core's, already scaled by its text scale — this widget
 * never sizes itself).
 *
 * Static and decorative: no semantics node, no click handling (taps pass
 * through to the transcript), no animation — recomposition happens only
 * when the name, the color role, the rect height or the appearance
 * changes. An appearance flip re-tints `tool-*` glyphs and swaps
 * `fileicon-*` art between the light and `-dark` PNGs.
 *
 * @param name the core's widget name: `tool-*`, `fileicon-*` or an SF name
 * @param color the role the tint comes from (ignored for `fileicon-*`)
 * @param heightDp the rect's height in dp — the SF point size is
 *   `heightDp × 0.8` (iOS uses `rect.height`, not `min(w, h)`)
 * @param palette the row's [TranscriptPalette] (`ctx.palette` at the
 *   dispatch site)
 */
@Composable
fun TranscriptIcon(
    name: String,
    color: ColorRole,
    heightDp: Float,
    palette: IntArray,
    modifier: Modifier = Modifier,
) {
    val tint = Color(palette.color(color))
    when (val source = TranscriptIcons.source(name)) {
        // Template asset: tinted (SrcIn ≅ UIImageView's template rendering)
        // and aspect-fit — the 24-unit viewport and its 1.5 stroke scale
        // with the rect, exactly like iOS's template SVGs.
        is TranscriptIconSource.Tool -> Image(
            painter = painterResource(source.res),
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(tint),
        )

        // Full-colour asset: never tinted (the role is ignored, like iOS's
        // non-template file icons), the dark art picked by the appearance.
        // A miss draws nothing — the name was logged at classification.
        is TranscriptIconSource.FileIcon -> {
            val context = LocalContext.current
            val dark = LocalIsDark.current
            val bitmap = remember(source.name, dark) {
                TranscriptIcons.fileIcon(context, source.name, dark)
            } ?: return
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }

        // SF Symbol stand-in: drawn at its natural point size and centred,
        // not scaled to the rect — ticket 06's Icon reserves the
        // `p × 1.2` box itself and never clips, so no fillMaxSize here.
        is TranscriptIconSource.Symbol -> Box(
            modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                source.icon,
                tint,
                sizePt = heightDp * 0.8f,
                weight = SymbolWeight.Medium,
                contentDescription = null,
            )
        }

        // iOS gets a nil image and stays silent; we log once per name so a
        // core/app name drift shows up in logcat instead of as a gap.
        TranscriptIconSource.Unknown -> Unit
    }
}
