package sh.roboco.android.shell

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.ActionSheetHost
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GroupedAccessory
import sh.roboco.android.design.GroupedRow
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoIcon
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoSwitch
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.groupedSection
import sh.roboco.android.design.hairlineWidth
import sh.roboco.android.design.sheet.SheetHeaderRow
import sh.roboco.android.design.sheet.SheetLeading
import sh.roboco.android.design.uiStyle
import sh.roboco.android.sessions.rememberSessionListOpener

/**
 * The "Settings dialog" page of the debug design gallery
 * (android-ui-parity #104): a [SheetHost] around a **fake split**
 * ([SettingsDialogFakeSplit] — a 360 dp column of 12 placeholder rows on
 * `background`, a 1-px `hairline` divider, and a detail area with the
 * centred "Detail" text and a "Show Settings" button), so the whole
 * `.formSheet` story is exercisable:
 *
 * - "Show Settings" presents the card through the real
 *   [rememberSettingsDialogPresenter] — the 0.94 scale-in with the fade,
 *   the 0.2 dim over the split, outside taps ignored;
 * - the card's root is the gallery's fake Settings root
 *   ([SettingsDialogFakeRoot]): ticket 70's grouped fixture rows with the
 *   iOS copy (`MoreViewController.swift:132,200-202,205,214-215`) plus the
 *   two demo rows — **"Push detail"** (pushes 24's `DemoRoute("Engine")`
 *   inside the card) and **"Stack page sheet"** (presents the demo Pair
 *   page over the card through [LocalEnclosingSheet], exactly like 72/140
 *   stack on the real card);
 * - "Archived Sessions" routes through 41's real session-list opener, so
 *   the Roboco adaptation (a session tap goes to the app router, not the
 *   card's stack) can be tried live;
 * - the sidebar's field demonstrates the keyboard being put away on
 *   present (focus it, then Show Settings).
 *
 * The [ActionSheetHost] sits **outside** the [SheetHost] (35's mount
 * order), so sheets presented from inside the card — the Wallpaper
 * "Effect" sheet shape — draw above it. Registered in ticket 22's
 * `design/Gallery.kt` as
 * `GallerySection("Settings dialog", GalleryLayout.Fill, 800) { SettingsDialogGallerySection() }`
 * — Fill because the section owns its full-screen `SheetHost`. "Force
 * reduce motion" comes from the gallery chrome (22's launch extra).
 */
@Composable
fun SettingsDialogGallerySection() {
    val state = rememberSheetHostState()
    ActionSheetHost {
        SheetHost(state) { SettingsDialogFakeSplit(state) }
    }
}

/**
 * The fake split the dialog presents over — shared by the gallery, the
 * previews and `SettingsDialogTest`'s fixture (the ticket's Verification:
 * "`RobocoTheme { SheetHost(state) { FakeSplit() } }` with the gallery's
 * fake root"). Builds the presenter with the gallery's fake root and 24's
 * demo screen, and reports it (and the sidebar taps / the root screen's
 * disposal) back to the test through the optional hooks.
 */
@Composable
internal fun SettingsDialogFakeSplit(
    state: SheetHostState,
    onSidebarRowTap: () -> Unit = {},
    onPresenterReady: (SettingsDialogPresenter) -> Unit = {},
    onRootDispose: () -> Unit = {},
) {
    val presenter = rememberSettingsDialogPresenter(
        root = { done -> SettingsDialogFakeRoot(done, onRootDispose) },
        routeScreen = { entry -> NavDemoScreen(entry) },
    )
    SideEffect { onPresenterReady(presenter) }
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    var query by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(palette.background)) {
        Row(Modifier.fillMaxSize()) {
            // The 360 dp sidebar: a focusable field (the keyboard story)
            // over 12 placeholder rows.
            Column(
                Modifier
                    .width(360.dp)
                    .fillMaxSize()
                    .testTag("fake-sidebar"),
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("split-field"),
                    textStyle = uiStyle(Face.Sans, typeScale.fixed(15f), palette.text),
                )
                repeat(12) { index ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clickable(onClick = onSidebarRowTap),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        BasicText(
                            "Session ${index + 1}",
                            Modifier.padding(start = 16.dp),
                            style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.text),
                        )
                    }
                }
            }
            Box(Modifier.width(hairlineWidth()).fillMaxHeight().background(palette.hairline))
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText(
                        "Detail",
                        style = uiStyle(Face.Sans, typeScale.scaled(17f), palette.secondary),
                    )
                    GalleryTextButton("Show Settings") { presenter.present() }
                }
            }
        }
    }
}

/**
 * The gallery's fake Settings root: a `LargeTitleScaffold("Settings")`
 * (25) with the Done circle in the trailing slot, listing ticket 70's
 * fixture rows with the iOS copy — **Notifications** (the master row with
 * the switch), **Appearance** (System/Light/Dark, System checked),
 * **Wallpaper** ("Choose Wallpaper…"), **Sessions** (Group by Project
 * with the switch, Archived) — plus the two demo rows.
 *
 * "Archived Sessions" stands in for "a list inside the card that opens a
 * session": it goes through 41's real `rememberSessionListOpener()`, which
 * the card overrides with `LocalSessionListOpener` → the app router (the
 * Roboco adaptation; iOS would push inside the sheet because
 * `splitViewController` is nil there).
 */
@Composable
internal fun SettingsDialogFakeRoot(
    done: @Composable RowScope.() -> Unit,
    onRootDispose: () -> Unit = {},
) {
    DisposableEffect(Unit) { onDispose { onRootDispose() } }
    val nav = LocalNavStack.current
    val enclosing = LocalEnclosingSheet.current
    val openSession = rememberSessionListOpener()
    var notificationsOn by remember { mutableStateOf(false) }
    var groupByProject by remember { mutableStateOf(true) }

    LargeTitleScaffold(title = "Settings", mode = TitleMode.Always, trailing = done) {
        groupedSection(
            key = "notifications",
            header = "Notifications",
            items = listOf(
                FakeSettingsRow("Notifications", "When a session finishes, needs you or fails", RobocoIcons.Bell),
            ),
            itemKey = { it.title },
        ) { row, position ->
            GroupedRow(
                title = row.title,
                subtitle = row.subtitle,
                icon = row.icon,
                position = position,
                accessories = listOf(
                    GroupedAccessory.Custom { RobocoSwitch(notificationsOn, { notificationsOn = it }) },
                ),
                onClick = null,
            )
        }
        groupedSection(
            key = "appearance",
            header = "Appearance",
            items = listOf(
                FakeSettingsRow("System", icon = RobocoIcons.CircleLeftHalfFilled, checked = true),
                FakeSettingsRow("Light", icon = RobocoIcons.SunMax),
                FakeSettingsRow("Dark", icon = RobocoIcons.Moon),
            ),
            itemKey = { it.title },
        ) { row, position ->
            GroupedRow(
                title = row.title,
                icon = row.icon,
                position = position,
                accessories = listOf(GroupedAccessory.Check(on = row.checked)),
                onClick = null,
            )
        }
        groupedSection(
            key = "wallpaper",
            header = "Wallpaper",
            items = listOf(
                FakeSettingsRow("Choose Wallpaper…", "Shown behind new chats and the sessions list", RobocoIcons.Photo),
            ),
            itemKey = { it.title },
        ) { row, position ->
            GroupedRow(
                title = row.title,
                subtitle = row.subtitle,
                icon = row.icon,
                position = position,
                onClick = null,
            )
        }
        groupedSection(
            key = "sessions",
            header = "Sessions",
            items = listOf(
                FakeSettingsRow(
                    "Group by Project",
                    "Sessions outside your sections, under their project",
                    RobocoIcons.SquareStack3dUp,
                ),
                FakeSettingsRow("Archived Sessions", icon = RobocoIcons.Archivebox),
            ),
            itemKey = { it.title },
        ) { row, position ->
            GroupedRow(
                title = row.title,
                subtitle = row.subtitle,
                icon = row.icon,
                position = position,
                accessories = if (row.title == "Group by Project") {
                    listOf(GroupedAccessory.Custom { RobocoSwitch(groupByProject, { groupByProject = it }) })
                } else {
                    listOf(GroupedAccessory.Disclosure)
                },
                onClick = if (row.title == "Archived Sessions") {
                    // A session tap inside the card: 41's opener, overridden
                    // by the card to the app router (the adaptation).
                    { openSession("s1") }
                } else {
                    null
                },
            )
        }
        groupedSection(
            key = "demo",
            header = null,
            items = listOf(
                FakeSettingsRow("Push detail"),
                FakeSettingsRow("Stack page sheet"),
            ),
            itemKey = { it.title },
        ) { row, position ->
            GroupedRow(
                title = row.title,
                position = position,
                onClick = when (row.title) {
                    "Push detail" -> {
                        // 24's demo route, pushed inside the card's stack.
                        { nav.push(DemoRoute("Engine")) }
                    }
                    else -> {
                        // The Pair page's shape: a page sheet stacked over
                        // the card through the enclosing scope (72's `over`).
                        { enclosing?.present("demo-pair", SheetStyle.Page(grabber = true)) { DemoPairPage(this) } }
                    }
                },
            )
        }
    }
}

/** One of the fake root's grouped rows (70's fixture shape). */
private data class FakeSettingsRow(
    val title: String,
    val subtitle: String? = null,
    val icon: RobocoIcon? = null,
    val checked: Boolean = false,
)

/** The placeholder page the "Stack page sheet" row presents over the card. */
@Composable
private fun DemoPairPage(sheetScope: PageSheetScope) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(Modifier.fillMaxSize()) {
        SheetHeaderRow(
            title = "Pair an engine",
            leading = SheetLeading.Close,
            onLeading = { sheetScope.dismiss(true) },
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BasicText(
                "Pair page placeholder",
                style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.secondary),
            )
        }
    }
}

/** One plain-text button row (no Material controls). */
@Composable
private fun GalleryTextButton(text: String, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BasicText(
        text,
        style = uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.accent),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 2.dp),
    )
}

// ---------------------------------------------------------------------------
// Previews (step 9): the settled card over the fake split, light and dark,
// at the reference tablet window. The entry is inserted with 31's
// presentSettledForPreview so the preview never animates; the root is the
// gallery's fake one. These live here (debug source set) because they use
// the debug-only fake root.
// ---------------------------------------------------------------------------

@Preview(name = "settings dialog light", widthDp = 1280, heightDp = 800)
@Composable
private fun SettingsDialogLightPreview() {
    SettingsDialogPreviewStage(Appearance.Light)
}

@Preview(name = "settings dialog dark", widthDp = 1280, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsDialogDarkPreview() {
    SettingsDialogPreviewStage(Appearance.Dark)
}

@Composable
private fun SettingsDialogPreviewStage(appearance: Appearance) {
    RobocoTheme(appearance) {
        ActionSheetHost {
            val state = rememberSheetHostState()
            val locals = rememberUpdatedState(currentCompositionLocalContext)
            // Presented settled (idempotent while up): the captured frame
            // is at rest, never mid-animation.
            state.presentSettledForPreview(SettingsDialogDefaults.Key, SettingsFormSheetStyle) {
                SettingsDialogContent(
                    scope = this,
                    locals = { locals.value },
                    root = { done -> SettingsDialogFakeRoot(done) },
                    routeScreen = { entry -> NavDemoScreen(entry) },
                )
            }
            SheetHost(state) { SettingsDialogFakeSplit(state) }
        }
    }
}
