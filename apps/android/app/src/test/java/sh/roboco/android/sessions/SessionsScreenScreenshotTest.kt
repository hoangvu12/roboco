package sh.roboco.android.sessions

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ContextMenuHost
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.WallpaperArtwork
import sh.roboco.android.design.WallpaperHero
import sh.roboco.android.design.WallpaperRender
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.wallpaperFixture
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.NavStackHost
import sh.roboco.android.shell.TabId
import sh.roboco.android.shell.TabRootRoute

/**
 * The Sessions tab root's goldens (android-ui-parity #46): the ticket's
 * fixture on a 402 × 874 dp window — at rest and scrolled (the "P0"
 * header at ≈ 142 dp, the inline title, as in `1-off.png`), the grouped
 * (3-on) set at the same scroll, the fixture wallpaper hero at travel 0
 * and at travel 188.78 (fade 0.5), and the two open menus (⋯ with the
 * Group-by-Project check on, and the P0 header lifted with its three
 * rows).
 *
 * Robolectric with NATIVE graphics at xxhdpi (3 px/dp; Robolectric
 * reports no insets, so the nav row is 0…44); the glyph clock is frozen
 * so nothing animates while capturing, and the menu goldens settle the
 * lift (0.38 s) and the open spring (0.35 s) before the shot. The scroll
 * is a programmatic `scrollBy` from rest — the incremental [ScrollTravel]
 * path, exact at every distance (composing at a restored position would
 * take the mean-size estimate). Roborazzi paths are relative to
 * `src/test/screenshots`, so the goldens land at
 * `src/test/screenshots/sessions/`. Recorded by
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.sessions.SessionsScreenScreenshotTest`,
 * verified by `:app:verifyRoborazziDebug --tests …`, then reviewed side
 * by side with `pr-assets/group-by-project/{1-off,3-on}.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class SessionsScreenScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /** The goldens' recorder: nothing is picked, so nothing is recorded. */
    private class GoldenActions(override val groupByProject: Boolean) : SessionsScreenActions {
        override fun setGroupByProject(on: Boolean) {}
        override fun newSection() {}
        override fun collapseAll() {}
        override fun toggleSection(id: String) {}
        override fun openPinned() {}
        override fun openArchived() {}
        override fun openSection(id: String, name: String) {}
        override fun renameSection(id: String, currentName: String) {}
        override fun deleteSection(id: String) {}
        override fun userSectionName(id: String): String? = SessionsScreenFixtures.sectionNames[id]
    }

    /** 21's fixture artwork at opacity 1 — the wallpaper goldens' hero. */
    private val fixtureHero: (@Composable (Modifier, () -> Float) -> Unit) = { m, f ->
        WallpaperArtwork(
            render = WallpaperRender(wallpaperFixture(), 1f),
            appear = { 1f },
            modifier = m,
            scrollFade = f,
        )
    }

    /**
     * Composes the root and captures the whole window. [hero] null → the
     * production [WallpaperHero] (no wallpaper is set under Robolectric —
     * "no wallpaper set" is exactly the plain-background golden);
     * [beforeCapture] runs the interaction (a scroll, the ⋯ tap, the
     * long press) with the clock advancing, then the shot settles 1 s.
     */
    private fun shot(
        appearance: Appearance,
        file: String,
        sections: List<ListSection> = SessionsScreenFixtures.sections(),
        actions: SessionsScreenActions = GoldenActions(groupByProject = false),
        hero: (@Composable (Modifier, () -> Float) -> Unit)? = null,
        listState: LazyListState = LazyListState(),
        bottomPadding: Dp = 150.dp, // 30's gesture-navigation value: rows scroll under the chrome
        beforeCapture: () -> Unit = {},
    ) {
        val resolvedHero: @Composable (Modifier, () -> Float) -> Unit =
            hero ?: { m, f -> WallpaperHero(modifier = m, scrollFade = f) }
        val stack = NavStack(TabRootRoute(TabId.Sessions))
        compose.setContent {
            RobocoTheme(appearance) {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    ContextMenuHost {
                        GlassMenuHost(hazeState = rememberHazeState()) {
                            NavStackHost(stack = stack, active = true) { entry ->
                                if (entry.route is TabRootRoute) {
                                    SessionsScreenContent(
                                        sections = sections,
                                        collapsedIds = emptySet(),
                                        loading = false,
                                        actions = actions,
                                        showsNavOptions = true,
                                        currentChatId = null,
                                        listState = listState,
                                        bottomPadding = bottomPadding,
                                        hero = resolvedHero,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = true
        beforeCapture()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("sessions/$file")
    }

    /** Scrolls [distanceDp] forward from the current position (exact travel). */
    private fun scrollBy(listState: LazyListState, distanceDp: Float) {
        val px = with(compose.density) { distanceDp.dp.toPx() }
        compose.runOnUiThread { runBlocking { listState.scrollBy(px) } }
        compose.waitForIdle()
    }

    // ------------------------------------------------------ the root, rest

    @Test
    fun sessionsRootExpandedLight() {
        shot(Appearance.Light, "sessions_root_expanded_light.png")
    }

    @Test
    fun sessionsRootExpandedDark() {
        shot(Appearance.Dark, "sessions_root_expanded_dark.png")
    }

    @Test
    fun sessionsRootWallpaperRestLight() {
        shot(Appearance.Light, "sessions_root_wallpaper_rest_light.png", hero = fixtureHero)
    }

    @Test
    fun sessionsRootWallpaperRestDark() {
        shot(Appearance.Dark, "sessions_root_wallpaper_rest_dark.png", hero = fixtureHero)
    }

    // --------------------------------------------------- the root, scrolled

    /**
     * `1-off.png`: scrolled 118 dp — the large title collapsed to the
     * centred inline "Sessions", the "P0" header at ≈ 142 dp from the
     * top, rows under the (unplaced) bottom chrome's 150 dp padding.
     */
    @Test
    fun sessionsRootScrolledLight() {
        val listState = LazyListState()
        shot(Appearance.Light, "sessions_root_scrolled_light.png", listState = listState) {
            scrollBy(listState, 118f)
        }
    }

    @Test
    fun sessionsRootScrolledDark() {
        val listState = LazyListState()
        shot(Appearance.Dark, "sessions_root_scrolled_dark.png", listState = listState) {
            scrollBy(listState, 118f)
        }
    }

    /** `3-on.png`: the grouped set, the same 118 dp scroll. */
    @Test
    fun sessionsRootGroupedScrolledLight() {
        val listState = LazyListState()
        shot(
            Appearance.Light,
            "sessions_root_grouped_scrolled_light.png",
            sections = SessionsScreenFixtures.groupedSections(),
            listState = listState,
        ) {
            scrollBy(listState, 118f)
        }
    }

    @Test
    fun sessionsRootGroupedScrolledDark() {
        val listState = LazyListState()
        shot(
            Appearance.Dark,
            "sessions_root_grouped_scrolled_dark.png",
            sections = SessionsScreenFixtures.groupedSections(),
            listState = listState,
        ) {
            scrollBy(listState, 118f)
        }
    }

    /** Travel 188.78 dp → fade 0.5: the hero at half alpha behind the rows. */
    @Test
    fun sessionsRootWallpaperHalfLight() {
        val listState = LazyListState()
        shot(
            Appearance.Light,
            "sessions_root_wallpaper_half_light.png",
            sections = SessionsScreenFixtures.longSections(),
            hero = fixtureHero,
            listState = listState,
        ) {
            scrollBy(listState, 188.784f)
        }
    }

    @Test
    fun sessionsRootWallpaperHalfDark() {
        val listState = LazyListState()
        shot(
            Appearance.Dark,
            "sessions_root_wallpaper_half_dark.png",
            sections = SessionsScreenFixtures.longSections(),
            hero = fixtureHero,
            listState = listState,
        ) {
            scrollBy(listState, 188.784f)
        }
    }

    // ---------------------------------------------------------- the menus

    /** The ⋯ menu open, Group by Project checked. */
    @Test
    fun sessionsOptionsMenuLight() {
        shot(
            Appearance.Light,
            "sessions_options_menu_light.png",
            actions = GoldenActions(groupByProject = true),
        ) {
            compose.onNodeWithTag("sessions-options").performClick()
            compose.waitForIdle()
        }
    }

    @Test
    fun sessionsOptionsMenuDark() {
        shot(
            Appearance.Dark,
            "sessions_options_menu_dark.png",
            actions = GoldenActions(groupByProject = true),
        ) {
            compose.onNodeWithTag("sessions-options").performClick()
            compose.waitForIdle()
        }
    }

    /** The "P0" header lifted with its three rows (Open / Rename / Delete). */
    @Test
    fun sessionsHeaderMenuSectionLight() {
        shot(Appearance.Light, "sessions_header_menu_section_light.png") {
            compose.onNodeWithTag("section-sec-p0").performTouchInput { longClick() }
            compose.waitForIdle()
        }
    }

    @Test
    fun sessionsHeaderMenuSectionDark() {
        shot(Appearance.Dark, "sessions_header_menu_section_dark.png") {
            compose.onNodeWithTag("section-sec-p0").performTouchInput { longClick() }
            compose.waitForIdle()
        }
    }
}
