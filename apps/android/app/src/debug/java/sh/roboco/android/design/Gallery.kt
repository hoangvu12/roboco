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
import sh.roboco.android.composer.AttachMenuGallerySection
import sh.roboco.android.composer.ChipStripGallerySection
import sh.roboco.android.composer.ComposerTextFieldGallerySection
import sh.roboco.android.composer.ComposerActionButtonGallerySection
import sh.roboco.android.composer.MentionStyleDemo
import sh.roboco.android.composer.MentionSuggestionsGallerySection
import sh.roboco.android.composer.MentionsGallerySection
import sh.roboco.android.composer.StagedThumbnailsGallerySection
import sh.roboco.android.composer.QuestionPanelBehaviourGallerySection
import sh.roboco.android.debug.LabMetersGallerySection
import sh.roboco.android.debug.TranscriptLabGallerySection
import sh.roboco.android.design.gallery.AlertGallerySection
import sh.roboco.android.design.gallery.GlassGallerySection
import sh.roboco.android.design.gallery.QuestionCardGallerySection
import sh.roboco.android.design.gallery.ScrollEdgeEffectGallerySection
import sh.roboco.android.design.gallery.SearchTabMorphGallerySection
import sh.roboco.android.design.gallery.TabBarMinimizeGallerySection
import sh.roboco.android.design.gallery.ToastGallerySection
import sh.roboco.android.design.reference.ReferenceGalleryPage
import sh.roboco.android.design.sheet.GlassSheetGallerySection
import sh.roboco.android.newsession.CheckoutModelEffortMenusGallerySection
import sh.roboco.android.newsession.FolderBrowserGallerySection
import sh.roboco.android.newsession.FolderBottomBarGallerySection
import sh.roboco.android.newsession.HandoffCaptureGallerySection
import sh.roboco.android.newsession.NewSessionCanvasGallerySection
import sh.roboco.android.newsession.NewSessionChipsGallerySection
import sh.roboco.android.newsession.ProjectMenusGallerySection
import sh.roboco.android.sessions.ConnectionLineGallerySection
import sh.roboco.android.newsession.FolderLocationsGallerySection
import sh.roboco.android.sessions.FolderScreensGallerySection
import sh.roboco.android.sessions.LocalSidebarFixtures
import sh.roboco.android.sessions.PinnedReorderGallerySection
import sh.roboco.android.shell.ChromeVisibilityGallerySection
import sh.roboco.android.shell.LargeTitleGallerySection
import sh.roboco.android.pairing.PairingRootGallerySection
import sh.roboco.android.pairing.PairPageGallerySection
import sh.roboco.android.pairing.QrScannerGallerySection
import sh.roboco.android.session.BottomStackGallerySection
import sh.roboco.android.session.SessionNavGallerySection
import sh.roboco.android.session.JumpToLatestGallerySection
import sh.roboco.android.session.SessionHeaderGallerySection
import sh.roboco.android.session.SessionOpenGallerySection
import sh.roboco.android.session.StatusPillGallerySection
import sh.roboco.android.sessions.RowActionsGallerySection
import sh.roboco.android.sessions.SearchGallerySection
import sh.roboco.android.sessions.SessionListGallerySection
import sh.roboco.android.sessions.SessionPromptsGallerySection
import sh.roboco.android.sessions.SessionRowGallerySection
import sh.roboco.android.sessions.SessionRowMetaGallerySection
import sh.roboco.android.sessions.SessionsScreenGallerySection
import sh.roboco.android.sessions.SectionHeaderGallerySection
import sh.roboco.android.sessions.SwipeRowGallerySection
import sh.roboco.android.settings.WallpaperRowsGallerySection
import sh.roboco.android.settings.AboutGallerySection
import sh.roboco.android.settings.DevicesRowsGallerySection
import sh.roboco.android.settings.EngineDetailGallerySection
import sh.roboco.android.settings.AppearanceGallerySection
import sh.roboco.android.settings.NotificationRowsGallerySection
import sh.roboco.android.settings.SettingsScreenGallerySection
import sh.roboco.android.settings.SessionsSettingsGallerySection
import sh.roboco.android.shell.NavGallerySection
import sh.roboco.android.shell.AppRootGallerySection
import sh.roboco.android.shell.NewSessionAccessoryGallerySection
import sh.roboco.android.shell.PageSheetGallerySection
import sh.roboco.android.shell.SettingsDialogGallerySection
import sh.roboco.android.shell.TabBarGallerySection
import sh.roboco.android.shell.SidebarToolbarGallerySection
import sh.roboco.android.shell.tablet.SidebarGallerySection
import sh.roboco.android.shell.tablet.settledEmptySessionsFixtures
import sh.roboco.android.sheets.TextSheetsGallerySection
import sh.roboco.android.image.ImageViewerGallerySection
import sh.roboco.android.transcript.KeyboardInsetGallerySection
import sh.roboco.android.transcript.RowChromeFixturePage
import sh.roboco.android.transcript.RowMotionGallerySection
import sh.roboco.android.transcript.ScrollerFixturePage
import sh.roboco.android.transcript.TextRunsSpecimen
import sh.roboco.android.transcript.TranscriptA11yGallerySection
import sh.roboco.android.transcript.TranscriptHostGallerySection
import sh.roboco.android.transcript.TranscriptLinksGallerySection
import sh.roboco.android.transcript.widgets.CopyCodeGallerySection
import sh.roboco.android.transcript.widgets.DisclosureGallerySection
import sh.roboco.android.transcript.widgets.ImageTileGallerySection
import sh.roboco.android.transcript.widgets.ImageTileStatesGallerySection
import sh.roboco.android.transcript.widgets.ShimmerGallerySection
import sh.roboco.android.transcript.widgets.StatusWidgetsGallerySection
import sh.roboco.android.transcript.widgets.ToolRailGallerySection
import sh.roboco.android.transcript.widgets.TranscriptIconGallerySection

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
        GallerySection("Tab bar", screenshotHeightDp = 1700) { SettledSessionsUnderCapture { TabBarGallerySection() } },
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
        // Fill: the section owns its fillMaxSize + verticalScroll column
        // (inside its GlassMenuHost). Under the default Scroll frame that
        // column was a vertical scroller measured with infinite height.
        // android-next #01: 1400 → 1600 for the two extra context cards
        // (~165 dp), so fixture H stays inside the capture.
        GallerySection("Composer chips", GalleryLayout.Fill, screenshotHeightDp = 1600) { ChipStripGallerySection() },
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
        // Ticket 57 (composer text field): the text slot — one field for
        // both modes, the #593 two-line resting preview, the 180 ms height
        // tween and Ctrl+Enter, in the 268/338 dp slots of a 370 dp composer.
        GallerySection("Composer text field", screenshotHeightDp = 1100) { ComposerTextFieldGallerySection() },
        // Ticket 39 (foldable section header): the sessions front page's
        // group header — title, count, live glyph, spring chevron, and
        // the pressed controlFill. Tap a row to fold it.
        GallerySection("Section header", screenshotHeightDp = 480) { SectionHeaderGallerySection() },
        // Ticket 42 (row action dispatch): the write surface — mode
        // switch for the scripted failures, the write buttons and the
        // notice toast, through the fake SessionWrites.
        GallerySection("Row actions") { RowActionsGallerySection() },
        // Ticket 30 (chrome visibility): hidden over a Session, the
        // after-Search fade, the pop reveal, the bottom insets.
        GallerySection("Chrome visibility", GalleryLayout.Scroll, screenshotHeightDp = 1200) {
            SettledSessionsUnderCapture { ChromeVisibilityGallerySection() }
        },
        // Ticket 58 (composer action button): the trailing send/queue/stop
        // control — the state morph, the glyph bounce and the long-press
        // delivery menu over the busy backdrop.
        GallerySection("Composer action button", screenshotHeightDp = 420) { ComposerActionButtonGallerySection() },
        // Ticket 21 (wallpaper hero): the page-top backdrop — plain,
        // opacity×scrollFade and cutout fixtures, plus the live store item.
        GallerySection("Wallpaper hero", screenshotHeightDp = 1400) { WallpaperHeroGallerySection() },
        // Ticket 43 (session prompts): the five session/section prompts —
        // rename session, new section (with the "Create on" engine
        // chooser), rename section, move to section and the delete
        // confirm — over fakes. Fill because the section owns an
        // ActionSheetHost, which needs the body's bounded height; the
        // gallery's own AlertHost/ToastHost (GalleryProviders) wrap it.
        GallerySection("Session Prompts", GalleryLayout.Fill) { SessionPromptsGallerySection() },
        // Ticket 66 (image tile): the rounded picture tile over the
        // Rust-painted placeholder — three 76 dp user thumbnails and a
        // 260 dp image row, the 200 ms cross-fade, the per-engine LRU
        // loader and the openImage tap.
        GallerySection("Image tile", screenshotHeightDp = 1100) { ImageTileGallerySection() },
        // Ticket 67 (upload ring): the ring over pending thumbnails —
        // the percent and geometry rows, the live demo cadence and the
        // non-pending control.
        GallerySection("Upload ring") { UploadRingGallerySection() },
        // Ticket 86 (mention suggestions): the floating glass card above the
        // composer — the 120 ms debounced search, the 220 ms rise, the pick
        // token and the offline gate, over the striped backdrop.
        GallerySection("Mention suggestions", GalleryLayout.Fill, screenshotHeightDp = 800) {
            MentionSuggestionsGallerySection()
        },
        // Ticket 98 (iOS toggle switch): the UISwitch port — 63 × 28 track,
        // accent on-tint, 37 × 24 capsule thumb, tap/drag, press lift,
        // ToggleOn/Off haptics; 420 dp of fixture card + interactive rows.
        GallerySection("Switch", screenshotHeightDp = 420) { RobocoSwitchGallerySection() },
        // Ticket 87 (composer attach menu): the + pull-down and its staging
        // demo — static panels, a live ComposerSurface with the real button
        // (the live half is skipped under capture: it needs an activity's
        // result registry).
        GallerySection("Composer attach menu", screenshotHeightDp = 700) { AttachMenuGallerySection() },
        // Ticket 33 (tablet split): the 1280 × 800 dp two-pane frame —
        // 360 dp sidebar + hairline + detail column, the 0.3 s easeInOut
        // show/hide, and the hardware shortcut buttons.
        GallerySection("Split layout") { SplitGallerySection() },
        // Ticket 41 (shared session list): the front page's list host —
        // keyed items, folds, fixed heights, the deselect fade, the
        // empty/loading overlay; Fill — the list owns the LazyColumn.
        GallerySection("Session list", layout = GalleryLayout.Fill, screenshotHeightDp = 852) {
            SessionListGallerySection()
        },
        // Ticket 97 (wallpaper rows): the Settings "Wallpaper" section —
        // the three row fixtures plus the live fake-store block (the real
        // photo picker and the real "Wallpaper Effect" sheet). Fill, and the
        // registration owns the ActionSheetHost, for the same reason as 19
        // and 43: a sheet host needs the body's bounded height.
        GallerySection("Wallpaper rows", GalleryLayout.Fill, screenshotHeightDp = 1100) {
            ActionSheetHost { WallpaperRowsGallerySection() }
        },
        // Ticket 110 (New Project folder browser): the nav bar, engine menu,
        // folder rows and the load-guard states in a settled page sheet,
        // over the fixture source and FakeHostPaths (no engine needed).
        GallerySection("New Project browser", GalleryLayout.Fill, screenshotHeightDp = 874) {
            FolderBrowserGallerySection()
        },
        // Ticket 101 (About & Licenses): the Settings About card — the
        // Version/Licenses rows and the long-press copy — plus the Licenses
        // list and the licence-text page sheet in a fixed-height host.
        GallerySection("About & Licenses", screenshotHeightDp = 1200) { AboutGallerySection() },
        // Ticket 45 (folder screens): the section page with ⋯ Rename/Delete,
        // Archived and Pinned — pushed inside a NavStackHost over the
        // fixture workspaces, with the kind picker and the "Fail writes"
        // toggle. Fill: the section owns the ActionSheetHost (19) and the
        // GlassMenuHost (15) the ⋯ menu needs.
        GallerySection("Folder screens", GalleryLayout.Fill, screenshotHeightDp = 852) {
            FolderScreensGallerySection()
        },
        // Ticket 127 (Select Text + tool detail sheets): the text sheets
        // themselves and the invisible Detail widget tap target, through a
        // TextSheetController + TextSheetOverlay.
        GallerySection("Text sheets", GalleryLayout.Fill, screenshotHeightDp = 420) {
            TextSheetsGallerySection()
        },
        // Ticket 35 (app root): pairing / phone / split selection, the
        // 350 ms cross-fade and the instant sheet dismissal before a swap.
        GallerySection("App root", GalleryLayout.Fill) { AppRootGallerySection() },
        // Ticket 48 (pull to refresh): the scaffold + the 21-dp cell spinner
        // over the three fake refresh durations — the rubber-band pull, the
        // 60-dp hold and the 0.6 s / 3 s window.
        GallerySection("Pull to refresh", GalleryLayout.Fill, 891) { PullToRefreshGallerySection() },
        // Ticket 44 (row swipe actions + the row's long-press menu): the
        // swipe panes (Pin/Unpin, Archive + Move, Unarchive), the settle,
        // the armed full swipe, the refusal spring-back and the context
        // menu wiring, live over fakes plus the static golden block. The
        // ActionSheetHost gives 43's sheets a host, like "Wallpaper rows".
        GallerySection("Swipe rows", GalleryLayout.Scroll, screenshotHeightDp = 891) {
            ActionSheetHost { SwipeRowGallerySection() }
        },
        // Ticket 100 (Settings · Sessions): the Group by Project switch
        // row bound to the phone-local preference and the Archived push
        // row — the live block toggles for real and Archived toasts.
        GallerySection("Settings · Sessions", screenshotHeightDp = 520) { SessionsSettingsGallerySection() },
        // Ticket 90 (question panel card): the Session screen's question
        // card — header, question, option rows, the Other field/editor and
        // Back + Next, one page per question, plus the interactive toy.
        GallerySection("Question card", screenshotHeightDp = 4200) { QuestionCardGallerySection() },
        // Ticket 72 (Pair page): the link field, Paste, Scan QR row,
        // footers, error line and capsule in a 393 × 852 frame, plus the
        // real page presented in a gallery-local sheet host with the
        // fake backend. Fill: the section owns its SheetHost.
        GallerySection("Pair page", GalleryLayout.Fill) { PairPageGallerySection() },
        // Ticket 73 (status pill): the Session screen's glass capsule —
        // the fixture column of every banner plus the live ticking Working
        // pill (frozen under capture so the golden stays byte-stable).
        GallerySection("Status pill", screenshotHeightDp = 760) {
            StatusPillGallerySection(liveTicker = !LocalGalleryCapture.current)
        },
        // Ticket 102 (Devices rows): the Settings Devices section — one
        // row per paired engine (dot + disclosure) and the "Pair an
        // engine" row, from DevicesRowSpec fixtures: connected,
        // reconnecting, revoked, the wrapped incompatible sentence, plus
        // the pressed and no-engines blocks.
        GallerySection("Devices rows", screenshotHeightDp = 900) { DevicesRowsGallerySection() },
        // Ticket 96 (Settings · Appearance): the System/Light/Dark rows —
        // the checkmark moves locally only; the gallery's own appearance
        // segment (its top bar) drives the theme, never the app preference.
        GallerySection("Settings · Appearance", screenshotHeightDp = 400) { AppearanceGallerySection() },
        // Ticket 49 (Search screen): the Search tab's root — the large
        // title, the Recent / hits / no-results / loading states over the
        // fixture, in the goldens' 412 × 700 dp frame.
        GallerySection("Search screen", GalleryLayout.Fill, 700) { SearchGallerySection() },
        // Ticket 46 (Sessions tab root): the tab's real screen — large
        // title, ⋯ options menu, header long-press menus, the wallpaper
        // hero with the live scroll fade — over the 1-off/3-on fixtures,
        // with the Wallpaper and Group-by-Project toggles and the recorder
        // readout. Fill: the screen owns its LazyColumn.
        GallerySection("Sessions screen", GalleryLayout.Fill, screenshotHeightDp = 874) {
            SessionsScreenGallerySection()
        },
        // Ticket 74 (bottom stack): the Session screen's floating bottom
        // stack — slot order, width and IME placement, the structural
        // reflow spring, the transcript inset, the bottom edge fade and the
        // jump anchor, over the striped transcript stand-in with live
        // toggles. Fill: the section owns its fixed-height session window.
        GallerySection("Bottom stack", layout = GalleryLayout.Fill, screenshotHeightDp = 915) {
            BottomStackGallerySection()
        },
        // Ticket 105 (tablet sidebar): the sidebar pane's content — the
        // front page in its own nav stack with the stacked glass search
        // field, the query filter over the fixture search, the current
        // row, focusSearch()/showSessions() and 107's toolbar slot.
        // Scroll like the split gallery: the sidebar's LazyColumn is
        // bounded by its fixed 360 × 800 dp frame.
        GallerySection("Tablet sidebar", GalleryLayout.Scroll, screenshotHeightDp = 1000) {
            SidebarGallerySection()
        },
        // Ticket 111 (folder browser bottom bar): the status line, the
        // plain New Folder… button and the full-width Use capsule over
        // the folder rows — the four state fixtures (a)–(d), each with
        // rows running under the background-less stack, plus the New
        // Folder alert in its retry-error state.
        GallerySection("Folder bottom bar", screenshotHeightDp = 600) { FolderBottomBarGallerySection() },
        // Ticket 116 (transcript scrollers): the real horizontal scrollers —
        // the code-block body at rest, 12 dp in and at the end, a wide
        // table (header and rules scroll with it) and a fitting block.
        // Every item stays interactive for manual axis-lock checks.
        GallerySection("Transcript scrollers", screenshotHeightDp = 760) { ScrollerFixturePage() },
        // Ticket 120 (transcript disclosure): the tool-group chevron flip
        // and the invisible disclosure / tool-toggle tap targets — the
        // static fixture rows plus the live expand / collapse demo.
        GallerySection("Transcript disclosure") { DisclosureGallerySection() },
        // Ticket 75 (session open): the Session screen's open choreography —
        // the hold with the ×1.75 spinner, the 180 ms ease-out reveal, the
        // 8 s give-up and the OPEN probe — over the fixture scaffold with
        // the Push / Rows-arrive / Replay controls. Fill: the section owns
        // its fixed-height session window.
        GallerySection("Session open", GalleryLayout.Fill, screenshotHeightDp = 900) {
            SessionOpenGallerySection()
        },
        // Ticket 133 (keyboard inset sync): the transcript's IME ride —
        // the same-pass bottom-inset delivery to the viewport and the
        // interactive IME dismiss drag, over a fake 4000 dp transcript
        // with the runway and fallback toggles and the per-frame drift
        // readout. Fill: the section owns its fixed-height session window.
        GallerySection("Keyboard inset sync", GalleryLayout.Fill, screenshotHeightDp = 915) {
            KeyboardInsetGallerySection()
        },
        // Ticket 99 (notification rows): the Settings "Notifications"
        // section — the master switch and the three kind rows over the
        // three fixtures (fresh install, granted, denied-with-alert).
        // Fill: the section is one LazyColumn of three keyed
        // sub-sections; the denied fixture's alert goes through the
        // AlertHost GalleryProviders already mounts.
        GallerySection("Notification rows", GalleryLayout.Fill, screenshotHeightDp = 760) {
            NotificationRowsGallerySection()
        },
        // Ticket 23 (iOS reference board): the static Sessions-tab mock of
        // 1-off.png built from the foundation parts — glass chrome, flat
        // rows, accessory, tab pill, search circle — on the fixed iOS
        // canvas, scaled to fit. Fill: the page owns its scrolling.
        GallerySection("Reference", GalleryLayout.Fill, screenshotHeightDp = 1000) {
            ReferenceGalleryPage()
        },
        // Ticket 82 (queue panel): the glass card above the composer —
        // the static fixtures (F1, F3, F5, FG), the live action demo over
        // the fixture source's queue rules, and the re-materializing
        // glass; the section owns its GlassMenuHost for the ⋯ rows.
        GallerySection("Queue panel", screenshotHeightDp = 891) { QueuePanelGallerySection() },
        // Ticket 47 (Pinned reorder): the Pinned folder's Edit mode — the
        // Edit/Done capsule, the drag handles, the engine-block clamp and
        // the lift look over 41's standalone SessionList with a fake
        // two-engine fixture; the readout line shows the last PinMove and
        // "Fail next drop" shows the snap-back. Fill: the list owns the
        // LazyColumn.
        GallerySection("Pinned reorder", layout = GalleryLayout.Fill, screenshotHeightDp = 852) {
            PinnedReorderGallerySection()
        },
        // Ticket 103 (Settings screen): the tab's real root — the large
        // title over the six sections in order (Devices, Notifications,
        // Appearance, Wallpaper, Sessions, About), each through its
        // section ticket's real emitter and fixture state; "Licenses"
        // really pushes onto the gallery's stack. Fill: the screen owns
        // its LazyColumn; the ActionSheetHost makes the Wallpaper "Effect"
        // row's sheet work (19's bounded-height rule, like "Wallpaper
        // rows").
        GallerySection("Settings screen", GalleryLayout.Fill, screenshotHeightDp = 874) {
            ActionSheetHost { SettingsScreenGallerySection() }
        },
        // Ticket 125 (full-screen image viewer): the three fixture tiles —
        // landscape, square, portrait — opening the real viewer over its
        // own host: the zoom-out open, the 0.6 s full-resolution swap, the
        // pinch/double-tap/pan/drag-dismiss, predictive back, Close and
        // Share, with the off-screen-source toggle. Fill: the section owns
        // its full-window ImageViewerHost.
        GallerySection("Image viewer", GalleryLayout.Fill) { ImageViewerGallerySection() },
        // Ticket 135 (Transcript Lab): the -lab launch buttons — the lab
        // itself is a separate debug activity, so the section is just the
        // launchers (no engine runs under the capture).
        GallerySection("Transcript Lab", screenshotHeightDp = 320) {
            TranscriptLabGallerySection()
        },
        // Ticket 78 (session composer): the Session screen's composer in
        // 74's stack slot — the chrome→composer binding (chips, running /
        // canSteer / canWrite, placeholder, upload progress), send / stop /
        // search over the fake commands and the send-failure pill above it,
        // with live toggles and a command log.
        GallerySection("Session composer", screenshotHeightDp = 900) {
            SessionComposerGallerySection()
        },
        // Ticket 104 (tablet Settings dialog): the `.formSheet` card — a
        // SheetHost around a fake split (360 dp sidebar + hairline + detail),
        // with "Show Settings" presenting the centred 540 × 620 card (Done
        // circle, push, page-sheet stacking, predictive back). Fill: the
        // section owns its SheetHost and the ActionSheetHost above it.
        GallerySection("Settings dialog", GalleryLayout.Fill, screenshotHeightDp = 800) {
            SettingsDialogGallerySection()
        },
        // Ticket 126 (link hit-testing and routing): the real host over
        // the LinkFixture — a web link, a mailto link, the five no-op
        // links and a scroller-carried table link — with the real
        // LinkRouter: Custom Tabs for http(s), the viewer for image
        // chips, ACTION_VIEW for other schemes, and the iOS no-op links
        // left as no-ops (decision TRANSCRIPT-D4); the readout shows the
        // last route. Fill: the section owns its full-window
        // ImageViewerHost and scrolling.
        GallerySection("Transcript links", GalleryLayout.Fill) { TranscriptLinksGallerySection() },
        // Ticket 88 (staged thumbnails): the composer card's photo strip —
        // 3 and 8 fixture images over 55's surface (at 8 only 5 tiles fit,
        // so the trailing edge fade shows), plus a live stage where x
        // removes with the 250 ms slide and collapse and Add image / Reset
        // drive the 8-image cap. Static 3-image fixture under the capture.
        GallerySection("Composer thumbnails", screenshotHeightDp = 760) {
            StagedThumbnailsGallerySection()
        },
        // Ticket 83 (queue edit lease): the composer editing a queued row —
        // the draft's stash/restore, the renewal (3 s here), commit /
        // cancel through the send hook, the recovery when the lease is
        // lost or the commit refused, over a FixtureQueueEditPort with
        // 82's real queue panel and 78's real composer in 74's stack.
        // Fill: the section owns its fixed-height session window.
        GallerySection("Queue edit", GalleryLayout.Fill) { QueueEditGallerySection() },
        // Ticket 50 (connection line): the multi-engine offline
        // indication — every examples-table fixture (spinners, dots,
        // overflow, the long INCOMPATIBLE sentence), the offline Home
        // header with its wifi-off glyph next to the spinner one, and the
        // live "Engine down" simulate block with the real 4 s grace.
        GallerySection("Connection line", screenshotHeightDp = 900) { ConnectionLineGallerySection() },
        // Ticket 91 (question panel behaviour): the panel's live state —
        // configure, picks, the 220 ms auto-advance, the 200 ms paging
        // fade, submit and its re-arm — driving 90's card in 74's slot
        // scope, with the fixture buttons (F1/F2/F3/dup ids/offline/
        // fail next submit) and a one-line answer caption under the
        // stage.
        GallerySection("Question panel behaviour") { QuestionPanelBehaviourGallerySection() },
        // Ticket 112 (folder browser locations): the Roboco-only rail and
        // search/typed-path field between the nav bar and the list —
        // Home + drives chips (the selected one in accentSoft/accent) and
        // the 40 dp search field, over the ticket's four fixtures A–D
        // (Linux/Windows drives, a typed path, the disabled state).
        GallerySection("Folder browser locations", screenshotHeightDp = 560) {
            FolderLocationsGallerySection()
        },
        // Ticket 89 (session chip menus): the Model / Reasoning effort / PR
        // menus behind the composer chips — a live ChipStrip over a fake
        // core (1.5 s catalog load, optimistic chip updates, failing
        // writes) plus the static M1/M2/E/P panels the goldens capture.
        // Fill: the section owns its GlassMenuHost and its own scroll.
        GallerySection("Session chip menus", GalleryLayout.Fill) {
            ChipMenuGallerySection()
        },
        // Ticket 79 (offline read-only): the session's read-only fixture —
        // 73's Reconnecting pill 8 dp above the offline composer (disabled
        // send look while a turn runs, tertiary chips, the kept draft, the
        // attach + still enabled) on the striped backdrop, plus a live
        // canWrite toggle under the fixture (hidden under capture).
        GallerySection("Session offline", screenshotHeightDp = 300) { SessionOfflineGallerySection() },
        // Ticket 121 (tool rail): the activity rail's phased draw-in — the
        // static three-row fixture plus the live Replay / Add row / Add 3
        // demo over stand-in row content.
        GallerySection("Tool rail") { ToolRailGallerySection() },
        // Ticket 119 (code-block copy button): the code card's top-right
        // corner cell — the static idle and copied fixtures, the live tap
        // demo (the check holds 1.4 s) and the clipboard readout line.
        GallerySection("Copy code") { CopyCodeGallerySection() },
        // Ticket 81 (tap to dismiss the composer): the transcript's
        // Initial-pass tap observer and the "this is a control" marker —
        // a 300 dp scrolling transcript stand-in with a link, a marked
        // control and 74-style blocker strip over the real morph's field.
        GallerySection("Tap to dismiss composer", screenshotHeightDp = 420) {
            TapDismissGallerySection()
        },
        // Ticket 122 (live title shimmer): the sweeping highlight over a
        // streaming tool group's title — the live specimen (frozen at
        // t = 1/3 under capture, hidden by Force reduce motion) plus the
        // frozen phase table from t = 0 to 0.8333.
        GallerySection("Shimmer") { ShimmerGallerySection() },
        // Ticket 80 (session ⋯ menu): the header's pull-down — the three
        // rows (Pin/Unpin, Copy Transcript, Archive) over the striped
        // backdrop inside the section's own GlassMenuHost and ToastHost,
        // with live Pinned / Can write toggles, the Ok/Offline mode switch
        // on the fake RowActions and its call log. Fill: the section owns
        // the host pair and its column.
        GallerySection("Session ⋯ menu", screenshotHeightDp = 420) { SessionMenuGallerySection() },
        // Ticket 85 (session push/pop choreography + nav lifecycle): a
        // 402 × 874 framed NavStackHost pushing a demo session — the
        // fake bottom stack and jump circle fading with the push/pop and
        // the predictive back gesture — over a real SessionNavLifecycle on
        // fakes, with the live phase/p/α readout and the lifecycle log.
        GallerySection("Session push/pop", GalleryLayout.Scroll, screenshotHeightDp = 1200) {
            SessionNavGallerySection()
        },
        // Ticket 123 (status widgets): the ToolStatus glyph, the subagent
        // card's trailing spinner and the Working/Writing tail row — the
        // fixture plus the live ticking row with its streaming toggle.
        GallerySection("Status widgets") { StatusWidgetsGallerySection() },
        // Ticket 124 (icon widget): the tool, file-icon and SF-glyph
        // fixture at the real core rect sizes, light and dark art.
        GallerySection("Transcript icons") { TranscriptIconGallerySection() },
        // Ticket 93 (New session canvas): the composer-first page — harness
        // mark + headline centred over the wallpaper hero, the composer
        // cutout, the sheet nav bar, a real ComposerBar, and the five
        // toggles (embedded / keyboard / harness / no wallpaper / narrow).
        GallerySection("New session canvas", GalleryLayout.Fill) {
            NewSessionCanvasGallerySection()
        },
        // Ticket 131 (jump to latest): the round glass ↓ above the bottom
        // stack — the visibility rule, the show/hide spring, the tap glide
        // and 85's pop-fade multiplier, over the stage fixture with live
        // toggles and a motion readout. Fill: the stage is a fixed
        // 412 dp-wide phone frame, like the Bottom stack section.
        GallerySection("Jump to latest", GalleryLayout.Fill, screenshotHeightDp = 480) {
            JumpToLatestGallerySection()
        },
        // Ticket 129 (transcript accessibility): the real host over the
        // A11yFixture with the bounds outlines on — link fragments in
        // accent (violet), exposed widget groups in danger (red), on the
        // painted text; the table's `wiki` outline slides with the painted
        // text when the table is swiped. Fill: the host owns scrolling.
        GallerySection("Transcript accessibility", GalleryLayout.Fill, screenshotHeightDp = 891) {
            TranscriptA11yGallerySection()
        },
        // Ticket 132 (fold and tool-group growth row tweening): the real
        // host over MotionFixture — "Show more" tweens the rows below over
        // 140 ms easeOut, "Slow ×10" stretches the curve, and the gallery's
        // Force reduce motion toggle makes it jump. Fill: the section owns
        // the transcript's scrolling; the golden (22's parameterized
        // capture, the only one) pins the pre-frame chrome.
        GallerySection("Transcript row motion", GalleryLayout.Fill, 891) { RowMotionGallerySection() },
        // Ticket 136 (lab meters): the Transcript Lab's two debug
        // instruments — the hitch meter label and the bench result label
        // (static fixtures; the live ones are the lab's `meter`/`bench`
        // launch extras).
        GallerySection("Lab meters") { LabMetersGallerySection() },
        // Ticket 94 (create + first send): the handoff capture — the fake
        // sheet page with both capture modifiers, the three captured
        // bitmaps (scene, composer, text rect) and the failure alert
        // capsule.
        GallerySection("New session: handoff capture", screenshotHeightDp = 891) {
            HandoffCaptureGallerySection()
        },
        // Ticket 109 (new-session chips): the New Session composer's chip
        // set — the project / host / branch / model / effort rows of
        // fixtures A–E (order, titles, icons, the offline engine tint),
        // built by newSessionChips over the hard-coded GalleryLabels.
        GallerySection("New-session chips", screenshotHeightDp = 420) {
            NewSessionChipsGallerySection()
        },
        // Ticket 137 (inline markdown images): every tile state at both
        // tile sizes — Loading/Loaded/Failed, compact and full, retry and
        // the unfetchable remote — over the Rust placeholder boxes.
        GallerySection("Image tile states", screenshotHeightDp = 1300) { ImageTileStatesGallerySection() },
        // Ticket 138 (QR scanner): the forced-dark camera stage over the
        // fake preview — the reticle, caption and the ✕ in the image
        // viewer's placement, with the phase/foreign toggles (the real
        // 2 s foreign timer), the denied state's Open Settings capsule and
        // a Launch real scanner row that reports only the scan's length
        // and parse verdict. Fill: the stage owns the full body.
        GallerySection("QR scanner", GalleryLayout.Fill) {
            QrScannerGallerySection()
        },
        // Ticket 140 (Engine detail page): the pushed page's three cards
        // for the five fixtures — connected (every row, success dot),
        // copied, revoked (greyed Rename…, Pair again, error footer,
        // fading tailnet URL), incompatible (Update required, no Pair
        // again) and the newer-engine notice — built from EngineDetailUi
        // directly (no core, no FFI), clicks no-ops.
        GallerySection("Engine detail", screenshotHeightDp = 3200) {
            EngineDetailGallerySection()
        },
        // Ticket 113 (new-session project / run-on menus): the project
        // chip's repository-grouped menu and the host chip's Run on menu
        // — the five static panel fixtures (P1, P2, R1, R2, R3) over
        // GlassStage plus the live demo of the two chips over the pure
        // builders ("New Project…" fakes the created space after 600 ms).
        GallerySection("New session project menus", screenshotHeightDp = 1400) {
            ProjectMenusGallerySection()
        },
        // Ticket 114 (new-session menus): the Checkout / Model / Reasoning
        // effort chip-menu content — the static panels K1–K3, M, E1–E2 and
        // the live demo (hero stand-in + chips over the in-memory draft, a
        // 1.5 s fake ref load) over the shared GalleryBackdrop. Fill: the
        // live demo owns its GlassMenuHost.
        GallerySection("New-session menus", GalleryLayout.Fill) { CheckoutModelEffortMenusGallerySection() },
        // Ticket 95 (draft handoff animation): the window-level overlay
        // host playing the captured pictures — the dissolve, glide and
        // bubble flight over the fake chat, driven by the Play capsule
        // (and the Force reduce motion toggle).
        GallerySection("Handoff", GalleryLayout.Fill, 852) { HandoffGallerySection() },
        // Ticket 107 (tablet sidebar bottom toolbar): the `[gear ⋯]` glass
        // capsule and the accent `+` in 360 × 200 dp frames — at rest, the
        // 0.5 folder-push fade, the ellipsis held pressed (glassPress), the
        // ⋯ menu opened from the ellipsis (46's rows over a recorder, inside
        // a GlassMenuHost) and RTL.
        GallerySection("Sidebar toolbar", GalleryLayout.Scroll, screenshotHeightDp = 900) {
            SidebarToolbarGallerySection()
        },
        // Later registrations, one line per ticket. The owner ticket's own
        // registration line wins if it differs (names and shot heights are
        // theirs to choose); if the composable already exists when its ticket
        // lands, replace the comment with the registration:
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
 * Under capture, the real shell's Sessions root (Tab bar, Chrome visibility)
 * renders the settled empty front page ([settledEmptySessionsFixtures])
 * instead of the app's live `FrontPageModel`, whose async first load raced
 * the capture (spinner vs "No sessions yet"). Interactive gallery use keeps
 * the live data.
 */
@Composable
private fun SettledSessionsUnderCapture(content: @Composable () -> Unit) {
    if (LocalGalleryCapture.current) {
        val fixtures = remember { settledEmptySessionsFixtures() }
        CompositionLocalProvider(LocalSidebarFixtures provides fixtures, content = content)
    } else {
        content()
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
