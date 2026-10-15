package sh.roboco.android.settings

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import sh.roboco.android.core.Face
import sh.roboco.android.core.WallpaperStore
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GroupedHeader
import sh.roboco.android.design.GroupedPosition
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle
import uniffi.roboco_core.WallpaperEffect

/**
 * The "Wallpaper rows" page of the debug design gallery (android-ui-parity
 * #97): the three row fixtures of the ticket's step 7 — (a) not set, (b)
 * set with `IMG_0412` and Halftone, (c) set with no name (the title-only
 * `choose` row) — each as a `GroupedHeader("Wallpaper")` plus the rows of
 * [wallpaperRows], with a caption above, **plus** a live block over a
 * gallery-local [MutableStateFlow] with fake actions:
 * `rememberWallpaperSectionState(fake)`, so tapping "Choose Wallpaper…" opens the **real**
 * photo picker (the launcher hoists through the activity) and tapping
 * "Effect" opens the real "Wallpaper Effect" sheet through the gallery's
 * `ActionSheetHost`.
 *
 * Registered in ticket 22's `design/Gallery.kt` (debug source set) as
 * `GallerySection("Wallpaper rows", GalleryLayout.Fill, screenshotHeightDp
 * = 1100) { ActionSheetHost { WallpaperRowsGallerySection() } }` — `Fill`,
 * like ticket 19's and 43's registrations, because the section owns an
 * in-window `ActionSheetHost`: the Scroll frame's `verticalScroll` would
 * give the host's `fillMaxSize` overlay unbounded height and the sheet
 * would anchor to the bottom of the scrolled content, not the screen. The
 * section therefore scrolls itself.
 *
 * The ticket's step 7 sketches the owning column with an extra
 * `.padding(16.dp)` (left over from the Scroll-frame idea its reviewer
 * fix replaced with Fill). That padding is deliberately dropped, like
 * ticket 70's own Fill gallery: the rows' [GroupedHeader]/`GroupedRow`
 * already inset the cards 16 dp from the screen edges, and the golden's
 * fixture (a) card must line up with `2-settings.png`'s Wallpaper card.
 * The column is capped at the reference phone's 402 dp width on wider
 * bodies, exactly as `GroupedListGallerySection` does.
 *
 * [showLive] drops the live block — the Roborazzi goldens of
 * `WallpaperRowsScreenshotTest` capture fixtures (a)–(c) only, exactly as
 * the ticket's acceptance list states.
 */
@Composable
fun WallpaperRowsGallerySection(showLive: Boolean = true) {
    val palette = RobocoTheme.palette
    Column(
        Modifier
            .widthIn(max = 402.dp)
            .fillMaxSize()
            .background(palette.background)
            .verticalScroll(rememberScrollState()),
    ) {
        WallpaperRowsFixture(
            caption = "a \u00b7 not set",
            state = WallpaperStore.State(set = false, name = null, effect = WallpaperEffect.NONE, generation = 0),
        )
        WallpaperRowsFixture(
            caption = "b \u00b7 set (IMG_0412, Halftone)",
            state = WallpaperStore.State(set = true, name = "IMG_0412", effect = WallpaperEffect.HALFTONE, generation = 1),
        )
        WallpaperRowsFixture(
            caption = "c \u00b7 set, no name (title-only choose row)",
            state = WallpaperStore.State(set = true, name = null, effect = WallpaperEffect.NONE, generation = 1),
        )
        if (showLive) {
            WallpaperRowsLiveBlock()
        }
    }
}

/**
 * One fixture: the caption, the "Wallpaper" header, then the rows drawn
 * non-lazily (this gallery page is not a list — `WallpaperRow` is the
 * drawing unit, positions from the row count, no clicks).
 */
@Composable
private fun WallpaperRowsFixture(caption: String, state: WallpaperStore.State) {
    WallpaperRowsCaption(caption)
    GroupedHeader(WallpaperSectionTitle)
    val rows = remember(state) { wallpaperRows(state) }
    rows.forEachIndexed { index, spec ->
        WallpaperRow(spec, GroupedPosition.of(index, rows.size), onClick = {})
    }
}

/** The block caption: Geist 12 fixed, secondary, at the header's inset. */
@Composable
private fun WallpaperRowsCaption(text: String) {
    val typeScale = LocalTypeScale.current
    val palette = RobocoTheme.palette
    BasicText(
        text,
        style = uiStyle(Face.Sans, typeScale.fixed(12f), palette.secondary),
        modifier = Modifier.padding(start = 32.dp, top = 16.dp),
    )
}

/**
 * The live block: the store state is a gallery-local flow the fake actions
 * mutate — `set` (a picked Uri) flips to `set = true, name = "Picked
 * photo"`, `setEffect` updates the effect, `remove` clears it — so the
 * rows change exactly as they do on the real screen, instantly, and the
 * effect sheet / picker run through [rememberWallpaperSectionState]'s real
 * wiring (the sheet host itself comes from the gallery registration).
 */
@Composable
private fun WallpaperRowsLiveBlock() {
    WallpaperRowsCaption("live \u00b7 fake store; the picker and the sheet are real")
    GroupedHeader(WallpaperSectionTitle)
    val flow = remember {
        MutableStateFlow(WallpaperStore.State(set = false, name = null, effect = WallpaperEffect.NONE, generation = 0))
    }
    val actions = remember(flow) {
        WallpaperSectionActions(
            state = flow,
            // The picked Uri is not kept (ticket 20 copies the image into
            // filesDir); the fake just flips the state like the store would.
            set = { _ ->
                flow.update { it.copy(set = true, name = "Picked photo", generation = it.generation + 1) }
            },
            setEffect = { effect ->
                flow.update { it.copy(effect = effect, generation = it.generation + 1) }
            },
            remove = {
                flow.update { it.copy(set = false, name = null, generation = it.generation + 1) }
            },
        )
    }
    val section = rememberWallpaperSectionState(actions)
    // Read here (in composition), so the rows re-derive on the next frame.
    val rows = section.rows
    rows.forEachIndexed { index, spec ->
        WallpaperRow(spec, GroupedPosition.of(index, rows.size), onClick = { section.onRowClick(spec.id) })
    }
}

@Preview(name = "light")
@Composable
private fun WallpaperRowsGalleryLightPreview() {
    RobocoTheme(Appearance.Light) { WallpaperRowsGallerySection(showLive = false) }
}

@Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WallpaperRowsGalleryDarkPreview() {
    RobocoTheme(Appearance.Dark) { WallpaperRowsGallerySection(showLive = false) }
}
