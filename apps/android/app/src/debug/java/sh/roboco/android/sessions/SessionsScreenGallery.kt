package sh.roboco.android.sessions

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.util.Locale
import sh.roboco.android.core.Face
import sh.roboco.android.design.AlertHost
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.ToastHost
import sh.roboco.android.design.WallpaperArtwork
import sh.roboco.android.design.WallpaperRender
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.uiStyle
import sh.roboco.android.design.wallpaperFixture
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.NavStackHost
import sh.roboco.android.shell.PlaceholderTabRoot
import sh.roboco.android.shell.ShellRouteContent
import sh.roboco.android.shell.TabId
import sh.roboco.android.shell.TabRootRoute

/**
 * The gallery's recorder: every call logs one line under the frame (the
 * "Last result" readout, e.g. `deleteSection(sec-p0)`), the pushes land
 * on the gallery's own [NavStack] (so "Open" actually navigates to 45's
 * folder page), and the toggle flips the demo flag the menu check reads.
 */
private class GallerySessionsActions(
    private val stack: NavStack,
    private val isGrouped: () -> Boolean,
    private val setGrouped: (Boolean) -> Unit,
    private val log: (String) -> Unit,
) : SessionsScreenActions {

    override val groupByProject: Boolean get() = isGrouped()

    override fun setGroupByProject(on: Boolean) {
        setGrouped(on)
        log("setGroupByProject($on)")
    }

    override fun newSection() = log("newSection()")

    override fun collapseAll() = log("collapseAll()")

    override fun toggleSection(id: String) = log("toggleSection($id)")

    override fun openPinned() {
        log("openPinned()")
        stack.push(FolderRoute.pinned(), animated = true)
    }

    override fun openArchived() {
        log("openArchived()")
        stack.push(FolderRoute.archived(), animated = true)
    }

    override fun openSection(id: String, name: String) {
        log("openSection($id, $name)")
        stack.push(FolderRoute.section(id, name), animated = true)
    }

    override fun renameSection(id: String, currentName: String) = log("renameSection($id, $currentName)")

    override fun deleteSection(id: String) = log("deleteSection($id)")

    override fun userSectionName(id: String): String? = SessionsScreenFixtures.sectionNames[id]
}

/**
 * The "Sessions screen" page of the debug design gallery
 * (android-ui-parity #46): a full-height frame of the preview content —
 * the `1-off.png` fixture set (Pinned 2, "P0" 2, "Mobile" 2, Recent 4)
 * with the fixture wallpaper hero — plus:
 *
 * - the "Wallpaper" toggle (the fixture hero on/off — off emits no node,
 *   like "no wallpaper set");
 * - the "Group by Project" toggle (the `1-off` ↔ `3-on` section sets; the
 *   recorder's flag follows it, so the ⋯ menu's check does too);
 * - a live caption of the hero's `travel` / `scrollFade` (debug only),
 *   so the fade can be checked by scrolling the list;
 * - the live ⋯ and header menus wired to the recorder, whose last line
 *   (e.g. `deleteSection(sec-p0)`) shows under the frame.
 *
 * The screen sits inside a real [NavStackHost] over ticket 26's root, so
 * the ⋯ "Archived" and the header "Open" pushes really navigate (the
 * pushed folder pages render through [ShellRouteContent]). Hosts: the
 * gallery's `GalleryProviders` already provides
 * `ContextMenuHost { AlertHost { ToastHost { … } } }`; this section adds
 * the [GlassMenuHost] the ⋯ needs (15 throws without one). No row swipes
 * here — 44's own gallery owns those.
 *
 * Registered in `design/Gallery.kt` (ticket 22's registry) as
 * `GallerySection("Sessions screen", GalleryLayout.Fill, screenshotHeightDp = 874) { SessionsScreenGallerySection() }`.
 */
@Composable
fun SessionsScreenGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()

    var wallpaper by remember { mutableStateOf(true) }
    var grouped by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("—") }
    var fade by remember { mutableStateOf(1f) }
    var heroHDp by remember { mutableStateOf(0f) }
    val stack = remember { NavStack(TabRootRoute(TabId.Sessions)) }
    val actions = remember(stack) {
        GallerySessionsActions(stack, { grouped }, { grouped = it }, { result = it })
    }

    GlassMenuHost {
        Column(Modifier.fillMaxSize().background(palette.background)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                val labelStyle = uiStyle(Face.SansMedium, typeScale.fixed(13f), palette.text)
                GalleryRow(
                    text = if (wallpaper) "Wallpaper: on" else "Wallpaper: off",
                    selected = wallpaper,
                    style = labelStyle,
                ) { wallpaper = !wallpaper }
                GalleryRow(
                    text = if (grouped) "Group by Project: on" else "Group by Project: off",
                    selected = grouped,
                    style = labelStyle,
                ) { grouped = !grouped }
                // The live fade readout (debug only): derived from the
                // hero's scrollFade captures, with the travel back-solved
                // over the frame's own hero height.
                val fadeDistance = 0.6f * heroHDp
                val travel = if (fadeDistance > 0f) (1f - fade) * fadeDistance else 0f
                BasicText(
                    "travel ${String.format(Locale.ROOT, "%.1f", travel)} dp · " +
                        "scrollFade ${String.format(Locale.ROOT, "%.3f", fade)}",
                    style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.tertiary),
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        with(density) { heroHDp = sessionsHeroHeight(size.height.toDp()).value }
                    },
            ) {
                NavStackHost(stack = stack, active = true) { entry ->
                    val route = entry.route
                    if (route is TabRootRoute && route.tab == TabId.Sessions) {
                        SessionsScreenContent(
                            sections = if (grouped) {
                                SessionsScreenFixtures.groupedSections()
                            } else {
                                SessionsScreenFixtures.sections()
                            },
                            collapsedIds = emptySet(),
                            loading = false,
                            actions = actions,
                            showsNavOptions = true,
                            currentChatId = null,
                            listState = listState,
                            bottomPadding = 150.dp,
                            hero = if (wallpaper) {
                                { m, f ->
                                    WallpaperArtwork(
                                        render = WallpaperRender(wallpaperFixture(), 1f),
                                        appear = { 1f },
                                        modifier = m,
                                        scrollFade = {
                                            val v = f()
                                            fade = v
                                            v
                                        },
                                    )
                                }
                            } else {
                                // No wallpaper set: no hero node at all.
                                { _, _ -> }
                            },
                        )
                    } else if (route is TabRootRoute) {
                        PlaceholderTabRoot(route.tab)
                    } else {
                        ShellRouteContent(route)
                    }
                }
            }
            // The recorder's line (e.g. `deleteSection(sec-p0)`), under the
            // frame as the ticket's step 9 asks.
            BasicText(
                "Last result: $result",
                style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** A toggle row: the label in `accent` when on, `secondary` when off. */
@Composable
private fun GalleryRow(text: String, selected: Boolean, style: TextStyle, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val ink = if (selected) palette.accent else palette.secondary
    BasicText(
        text,
        style = style.copy(color = ink),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    )
}

// ---------------------------------------------------------------------------
// Previews: the section needs the alert and toast hosts around it (the
// real gallery's GalleryProviders provides them); its own GlassMenuHost
// comes with the section.
// ---------------------------------------------------------------------------

@Preview(name = "sessions screen gallery light")
@Composable
private fun SessionsScreenGalleryLightPreview() {
    RobocoTheme(Appearance.Light) {
        AlertHost { ToastHost { SessionsScreenGallerySection() } }
    }
}

@Preview(name = "sessions screen gallery dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SessionsScreenGalleryDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        AlertHost { ToastHost { SessionsScreenGallerySection() } }
    }
}
