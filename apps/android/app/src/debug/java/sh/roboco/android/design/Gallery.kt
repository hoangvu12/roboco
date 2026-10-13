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
import sh.roboco.android.composer.ChipStripGallerySection
import sh.roboco.android.composer.MentionStyleDemo
import sh.roboco.android.composer.MentionsGallerySection
import sh.roboco.android.design.gallery.AlertGallerySection
import sh.roboco.android.design.gallery.GlassGallerySection
import sh.roboco.android.design.gallery.ScrollEdgeEffectGallerySection
import sh.roboco.android.design.gallery.SearchTabMorphGallerySection
import sh.roboco.android.design.gallery.TabBarMinimizeGallerySection
import sh.roboco.android.design.gallery.ToastGallerySection
import sh.roboco.android.design.sheet.GlassSheetGallerySection
import sh.roboco.android.shell.LargeTitleGallerySection
import sh.roboco.android.pairing.PairingRootGallerySection
import sh.roboco.android.session.SessionHeaderGallerySection
import sh.roboco.android.sessions.SessionRowGallerySection
import sh.roboco.android.sessions.SessionRowMetaGallerySection
import sh.roboco.android.shell.NavGallerySection
import sh.roboco.android.shell.NewSessionAccessoryGallerySection
import sh.roboco.android.shell.PageSheetGallerySection
import sh.roboco.android.shell.TabBarGallerySection
import sh.roboco.android.transcript.RowChromeFixturePage
import sh.roboco.android.transcript.TextRunsSpecimen
import sh.roboco.android.transcript.TranscriptHostGallerySection

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
        GallerySection("Alert", GalleryLayout.Scroll, screenshotHeightDp = 640) { AlertGallerySection() },
        GallerySection("Status glyph") { StatusGlyphGallerySection() },
        // Ticket 60: fixed values under capture keep the golden host-independent.
        GallerySection("Text engine", GalleryLayout.Fill) {
            if (LocalGalleryCapture.current) TextEngineSpecimen(cjkSource = "fixture", measurerCalls = 0) else TextEngineSpecimen()
        },
        // Ticket 27 (the ticket names this registration line):
        GallerySection("New session accessory") { NewSessionAccessoryGallerySection() },
        GallerySection("Glass buttons") { GlassButtonsGallerySection() },
        GallerySection("GlassMenu", GalleryLayout.Fill) { GlassMenuGallerySection() },
        // Ticket 26 (glass tab bar): the phone shell's bottom chrome.
        GallerySection("Tab bar", screenshotHeightDp = 1700) { TabBarGallerySection() },
        // Ticket 09: Fill because it owns the LazyColumn.
        GallerySection("Scroll edge effect", layout = GalleryLayout.Fill) { ScrollEdgeEffectGallerySection() },
        GallerySection("Action sheet", GalleryLayout.Fill) { ActionSheetGallerySection() },
        // Ticket 28 (tab bar minimize): the scroll-driven chrome morph.
        GallerySection("Tab bar minimize", GalleryLayout.Scroll, screenshotHeightDp = 1120) {
            TabBarMinimizeGallerySection()
        },
        // Ticket 16 (context menu): long-press lift, dimmed backdrop, menu at the row.
        GallerySection("Context menu", GalleryLayout.Fill, screenshotHeightDp = 891) { ContextMenuGallerySection() },
        // Ticket 70 (inset-grouped list): Fill — the cards sit 16 dp from
        // the screen edges, so the section owns its scrolling unpadded.
        GallerySection("Grouped list", layout = GalleryLayout.Fill, screenshotHeightDp = 1300) { GroupedListGallerySection() },
        GallerySection("Glass sheet", GalleryLayout.Fill, screenshotHeightDp = 740) { GlassSheetGallerySection() },
        // Ticket 25: Fill, the scaffold owns the LazyColumn.
        GallerySection("Large title scaffold", layout = GalleryLayout.Fill) { LargeTitleGallerySection() },
        // Ticket 53 (mentions): the composer's live @token accent styling
        // (typing @ in the section shows the live activeQuery readout).
        // Under capture the encode readout is off — it calls the native
        // core (ticket 60's LocalGalleryCapture rule).
        GallerySection("Mentions", screenshotHeightDp = 420) {
            if (LocalGalleryCapture.current) MentionStyleDemo() else MentionsGallerySection()
        },
        // Ticket 34 (pairing root): Fill — the section scales the whole
        // 393 × 852 phone frame into the body.
        GallerySection("Pairing root", layout = GalleryLayout.Fill, screenshotHeightDp = 960) {
            PairingRootGallerySection()
        },
        // Ticket 29 (search tab morph): the bar-to-field morph, the IME
        // ride and the accessory fade.
        GallerySection("Search tab morph", GalleryLayout.Scroll, screenshotHeightDp = 1240) {
            SearchTabMorphGallerySection()
        },
        // Ticket 63 (text runs): every run-painting case through the real
        // RowModel path — Rust positions, decorations, mono double-width.
        GallerySection("Transcript · Text runs") { TextRunsSpecimen(dark = RobocoTheme.isDark) },
        // Ticket 31 (PageSheet + SheetHost): the large-detent page sheet
        // with the card-stack recession, stacking and the handoff hide.
        GallerySection("Page sheet", GalleryLayout.Fill, screenshotHeightDp = 891) { PageSheetGallerySection() },
        // Ticket 12 (meta glyphs): the row meta line's static glyphs —
        // PrBadge, PrIcon, ProjectTile, BranchIcon.
        GallerySection("Meta glyphs") { MetaGlyphsGallerySection() },
        // Ticket 37 (session row): the two-line session list row — harness
        // mark, fading title, corner status word/glyph, highlight fills.
        GallerySection("Session row", screenshotHeightDp = 1250) { SessionRowGallerySection() },
        // Ticket 59 (context chips): the composer toolbar's chip strip —
        // every variant row (session, PR states, context, new session,
        // offline, overflow, rail sample, live menus).
        GallerySection("Composer chips", screenshotHeightDp = 1400) { ChipStripGallerySection() },
        // Ticket 64 (box prims + overflow fades): the transcript's chrome —
        // code block, table, quote bar, folded bubble, file pill, tool
        // badge and agent card, through the real RowModel path.
        GallerySection("Transcript chrome", screenshotHeightDp = 760) { RowChromeFixturePage() },
        // Ticket 55 (composer surface): the static half of the composer's
        // capsule ⇄ card morph — the glass surface, the six-slot layout
        // and the attach button, over the striped backdrop.
        GallerySection("Composer surface", screenshotHeightDp = 1300) { ComposerSurfaceGallerySection() },
        // Ticket 56 (composer morph): the live state machine — the wantsCard
        // rule, the 0.42 s spring, tap-to-focus and the blur sequence, over
        // 55's ComposerSurface (Focus/Blur/Add image/Always card chips and
        // a per-frame isCard/focused/progress readout).
        GallerySection("Composer morph", screenshotHeightDp = 420) { ComposerMorphGallerySection() },
        // Ticket 52 (session header): the Session screen's bar — glass back
        // circle, two-line fading title view, ⋯ circle over the soft edge.
        GallerySection("Session header", GalleryLayout.Fill, screenshotHeightDp = 420) { SessionHeaderGallerySection() },
        // Ticket 38 (row meta line): line 2 of the session row — project
        // tile, project name, branch and the PR badge at the meta slot's
        // right end, plus the k = 1.6 clamp end.
        GallerySection("Row meta line", GalleryLayout.Scroll, screenshotHeightDp = 760) {
            SessionRowMetaGallerySection()
        },
        // Ticket 65 (virtualized transcript host): the realized band, keyed
        // row hosts, layers and the new-row fade-in over the real core.
        GallerySection("Transcript host", GalleryLayout.Fill, screenshotHeightDp = 891) {
            TranscriptHostGallerySection()
        },
        // Later registrations, one line per ticket. The owner ticket's own
        // registration line wins if it differs (names and shot heights are
        // theirs to choose); if the composable already exists when its ticket
        // lands, replace the comment with the registration:
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
                    // Ticket 16: the context-menu host sits outermost (its
                    // overlay dims and blurs everything below it, top bar
                    // and the alert/toast hosts included).
                    ContextMenuHost { AlertHost { ToastHost { content() } } }
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
