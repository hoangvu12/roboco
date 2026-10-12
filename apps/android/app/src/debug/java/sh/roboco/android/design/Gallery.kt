package sh.roboco.android.design

import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale
import sh.roboco.android.design.gallery.GlassGallerySection
import sh.roboco.android.design.gallery.ToastGallerySection
import sh.roboco.android.shell.NavGallerySection

/**
 * How the gallery lays a section out inside the body (android-ui-parity #22).
 */
enum class GalleryLayout {
    /** The frame scrolls vertically and pads: 16 dp padding, children 16 dp apart. */
    Scroll,

    /** The section gets the whole body, bounded, unpadded; it owns scrolling and insets. */
    Fill,
}

/**
 * One gallery page. [name] is unique (a test enforces this) and shown as the
 * index row and the open section's title; [slug] is its launch-extra key.
 */
@Immutable
class GallerySection(
    val name: String,
    val layout: GalleryLayout = GalleryLayout.Scroll,
    val screenshotHeightDp: Int = 891,
    val content: @Composable () -> Unit,
) {
    val slug: String get() = gallerySlug(name)
}

/**
 * `name.lowercase(ROOT)`, every run of non-`[a-z0-9]` characters becomes one
 * `_`, then leading/trailing `_` are trimmed:
 * "Motion lab" → "motion_lab", "Status glyph" → "status_glyph".
 */
fun gallerySlug(name: String): String =
    name.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')

/**
 * The section registry: one line per section, in ticket order — every
 * component ticket adds exactly one line here. No reflection or classpath
 * scanning: the explicit list keeps the order stable and the build R8-safe.
 */
object Gallery {

    val sections: List<GallerySection> = listOf(
        GallerySection("Palette", screenshotHeightDp = 1400) { PaletteGallerySection() },
        GallerySection("Shapes") { ShapesGallerySection() },
        GallerySection("Type", screenshotHeightDp = 1500) { TypeGallerySection() },
        GallerySection("Motion lab", screenshotHeightDp = 2600) { MotionLabSection() },
        GallerySection("Haptics", screenshotHeightDp = 1000) { HapticsGalleryHost() },
        // Ticket 13 shipped BrandMarksGallerySection with this line in mind.
        GallerySection("Brand marks") { BrandMarksGallerySection() },
        // Registered after merging feat/android-app (07 GlassSurface,
        // 10 Edge fades, 24 NavStack); each owner ticket named its
        // registration line in the section's KDoc:
        GallerySection("Glass surface") { GlassGallerySection() },
        GallerySection("Edge fades", screenshotHeightDp = 1100) { EdgeFadeGallerySection() },
        GallerySection("Navigation", GalleryLayout.Scroll, screenshotHeightDp = 891) { NavGallerySection() },
        GallerySection("Icons") { IconsGallerySection() },
        GallerySection("Toast", GalleryLayout.Fill) { ToastGallerySection() },
        GallerySection("GlassMenu", GalleryLayout.Fill) { GlassMenuGallerySection() },
        // Later registrations, one line per ticket. The owner ticket's own
        // registration line wins if it differs (names and shot heights are
        // theirs to choose); if the composable already exists when its ticket
        // lands, replace the comment with the registration:
        // 08 "Glass buttons"      GallerySection("Glass buttons") { GlassButtonsGallerySection() }
        // 09 "Scroll edge effect" GallerySection("Scroll edge effect", layout = GalleryLayout.Fill) { ScrollEdgeEffectGallerySection() }
        // 11 "Status glyph"       GallerySection("Status glyph") { StatusGlyphGallerySection() }
        // 12 "Meta glyphs"        GallerySection("Meta glyphs") { MetaGlyphsGallerySection() }
        // 16 "Context menu"       GallerySection("Context menu") { ContextMenuGallerySection() }
        // 17 "Glass sheet"        GallerySection("Glass sheet", GalleryLayout.Fill, screenshotHeightDp = 740) { GlassSheetGallerySection() }
        // 18 "Alert"              GallerySection("Alert") { AlertGallerySection() }  + AlertHost in GalleryProviders
        // 19 "Action sheet"       GallerySection("Action sheet") { ActionSheetGallerySection() }  (wraps its own ActionSheetHost)
        // 21 "Wallpaper hero"     GallerySection("Wallpaper hero") { WallpaperHeroGallerySection() }
        // 23 "Reference"          GallerySection("Reference", GalleryLayout.Fill) { ReferenceGalleryPage() }  (a full page)
        // Ticket 20 (WallpaperStore) is non-visual and has no gallery entry.
    )

    /** The section named [key] (ignore case) or slugged [key], or null. */
    fun section(key: String?): GallerySection? {
        if (key == null) return null
        val slug = gallerySlug(key)
        return sections.firstOrNull {
            it.name.equals(key, ignoreCase = true) || it.slug == slug
        }
    }
}

/**
 * True while the Roborazzi helper captures a section: sections swap any
 * environment probing (the haptics hardware probe) for deterministic
 * fixtures, and `GalleryProviders` installs `NoFeedback`.
 */
val LocalGalleryCapture = staticCompositionLocalOf { false }

/**
 * The theme root for everything the gallery shows: the appearance, reduce
 * motion and the haptic engine, so sections never depend on the wiring of
 * whatever screen hosts them.
 */
@Composable
fun GalleryProviders(appearance: Appearance, capture: Boolean = false, content: @Composable () -> Unit) {
    RobocoTheme(appearance) {
        // Harmless if RobocoTheme already installs it (install is
        // idempotent); kept so the gallery does not depend on that wiring.
        ProvideReduceMotion {
            val inner: @Composable () -> Unit = {
                CompositionLocalProvider(LocalGalleryCapture provides capture) {
                    // Ticket 18 wraps ToastHost in AlertHost { } here (18 step 7).
                    ToastHost { content() }
                }
            }
            if (capture) {
                // No vibrator probing under Robolectric.
                CompositionLocalProvider(LocalFeedback provides NoFeedback, content = inner)
            } else {
                ProvideFeedback(inner)
            }
        }
    }
}

/**
 * The frame a section renders in. `Scroll` (the default) scrolls and pads;
 * a `Scroll` section must not contain an unbounded vertical scroller unless
 * it sits in a fixed-height box. `Fill` is full bleed — the section owns
 * both scrolling and insets.
 */
@Composable
fun GallerySectionFrame(section: GallerySection, modifier: Modifier = Modifier) {
    val background = RobocoTheme.palette.background
    when (section.layout) {
        GalleryLayout.Scroll -> Column(
            modifier
                .fillMaxSize()
                .background(background)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            section.content()
        }
        GalleryLayout.Fill -> Box(modifier.fillMaxSize().background(background)) {
            section.content()
        }
    }
}

/**
 * Ticket 05's Haptics section, hosted: the real hardware outside captures,
 * the F34 fixture inside them (ticket 05 step 7's fixture, reused verbatim
 * through [ALL_PRIMITIVES]). Taps still go through the real
 * [LocalFeedback], so the gate and ladder apply.
 */
@Composable
fun HapticsGalleryHost() {
    val context = LocalContext.current
    val hardware = if (LocalGalleryCapture.current) {
        HapticsFixture
    } else {
        remember(context) {
            HapticHardware.probe(context, context.getSystemService(VibratorManager::class.java)?.defaultVibrator)
        }
    }
    HapticsGallerySection(hardware, hasView = true)
}

/** Ticket 05's preview/test fixture: SDK 34, all 8 primitives, a vibrator. */
private val HapticsFixture = HapticHardware(34, ALL_PRIMITIVES.toSet(), hasVibrator = true)
