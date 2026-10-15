package sh.roboco.android.newsession

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import sh.roboco.android.RobocoApplication
import sh.roboco.android.core.Face
import sh.roboco.android.design.GroupedAccessory
import sh.roboco.android.design.GroupedPosition
import sh.roboco.android.design.GroupedRow
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.groupedSection
import sh.roboco.android.design.uiStyle
import sh.roboco.android.design.glass.GlassCircleButton
import sh.roboco.android.design.glass.GlassTextCapsule
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.design.glass.hasContent
import sh.roboco.android.design.glass.rememberGlassMenuState
import sh.roboco.android.shell.PageSheetScope
import sh.roboco.android.shell.SheetHostState
import sh.roboco.android.shell.SheetStyle

/**
 * The New Project screen (android-ui-parity #110): the page-sheet skeleton
 * — the nav bar (✕ / folder title / engine capsule), the inset-grouped
 * folder list and the tap wiring — over the [FolderBrowserController]'s
 * load guard. The
 * port of `ios:Threads/NewProjectViewController.swift:29-96` (nav bar,
 * list, cells) with the `pr685` bottom inset; ticket 111 adds the bottom
 * bar over the list, ticket 112 the rail and path field between the nav
 * bar and the list.
 *
 * No Material anywhere: the nav bar is a custom [Layout] (its title
 * centring is UIKit nav-bar behaviour — see [navTitleX]), the rows are
 * ticket 70's [GroupedRow]s, the buttons are ticket 08's glass items and
 * the engine menu is ticket 15's model.
 */

/** The screen's geometry tokens (dp); everything not listed is ticket 70's. */
object FolderBrowserTokens {
    /** The nav bar's height (ticket 17's `GlassSheetDefaults.HeaderHeight`). */
    val NavHeight = 52.dp

    /** The ✕ at the start; the capsule at the end (16 from each screen edge). */
    val SideInset = 16.dp

    /** The title region's clearance from the ✕ and the capsule. */
    val ItemGap = 8.dp

    /** The capsule's max width: 0.45 × the sheet width (system rendering, starred; freeze in the side-by-side). */
    const val CapsuleMaxFraction = 0.45f

    /** UIKit's inset-grouped spacing above a header-less section (starred; freeze in the side-by-side). */
    val ListTop = 35.dp

    /** `pr685:…:93` `contentInset.bottom = 160`, plus the navigation-bar inset (ticket 111's bottom bar). */
    val ListBottom = 160.dp
}

/**
 * The New Project page: the nav bar, then 112's locations rail and
 * search/typed-path field (only when an engine is selected), then the
 * folder list filling the rest of the page down to the physical bottom,
 * with 111's bottom bar (status / New Folder… / Use) overlaid on it. The
 * create callback and the dismissal live in [actions] (111's `onCreated`
 * and `dismissSheet`); while a write runs, [sheet]'s `dismissBlocked`
 * keeps it up.
 *
 * 112's [nav] owns the field's and the rail's state; its typed-path state
 * overrides the status label and the Use button (111's own values apply
 * in `Idle`/`Failed`), and row taps browse through it so the query
 * clears.
 */
@Composable
fun NewProjectScreen(
    sheet: PageSheetScope,
    controller: FolderBrowserController,
    actions: FolderBrowserActions,
    nav: FolderNavState,
    modifier: Modifier = Modifier,
) {
    // 112: row taps navigate (clearing the query and the typed-path
    // state) instead of loading directly; the tap guard and the path
    // arithmetic stay in the controller.
    SideEffect { controller.onNavigate = nav::navigate }
    Column(
        modifier
            .fillMaxSize()
            .semantics { paneTitle = FolderBrowserCopy.DefaultTitle },
    ) {
        FolderBrowserNavBar(
            controller,
            enabled = !controller.submitting,
            onClose = { sheet.dismiss(animated = true) },
        )
        // 112 inserts the locations rail and the path field here.
        LaunchedEffect(controller.engine?.id, controller.engine?.online) {
            nav.onDevice(controller.engine?.id, controller.engine?.online == true)
        }
        if (controller.engine != null) {
            FolderLocationsHeader(
                chips = nav.chips,
                selected = nav.selectedChip,
                query = nav.query,
                enabled = !actions.submitting,
                onPickLocation = nav::onPickLocation,
                onQueryChange = nav::onQueryChange,
                onGo = nav::onGo,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            FolderList(controller, nav)
            // 111's bottom stack, overlaid bottom-centre on the list (no
            // background — rows scroll underneath; 110's 160 dp bottom
            // content padding keeps the last row reachable). 112's
            // typed-path state overrides the status and the Use button;
            // `null` keeps 111's own values.
            FolderBrowserBottomBar(
                status = nav.statusOverride ?: controller.status,
                useTitle = nav.useOverride?.title ?: actions.useTitle,
                useEnabled = (nav.useOverride?.enabled ?: actions.canAct) || actions.creatingProject,
                useBusy = actions.creatingProject,
                newFolderEnabled = actions.canAct,
                onUse = nav.useOverride?.onClick ?: actions::createProject,
                onNewFolder = { actions.promptForFolder() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
    // A write in flight locks the sheet: no drag, no back, no a11y
    // dismiss — programmatic dismissal (the success path) still works.
    LaunchedEffect(controller.submitting) {
        sheet.dismissBlocked = controller.submitting
    }
}

// ---------------------------------------------------------------------------
// The nav bar
// ---------------------------------------------------------------------------

/**
 * The 52 dp nav bar: a glass ✕ at the start, the folder name centred (see
 * [navTitleX]), a glass text capsule with the selected engine at the end
 * (`NewProjectViewController.swift:30-96`). Opaque — the list starts below
 * it (ticket 17's ADAPT). While a write runs (111's `submitting`) both
 * ends are disabled (`setSubmitting`, `pr685:…:230-237`). The capsule opens
 * the engine menu on tap (`rememberGlassMenuState().open(...)` — ticket
 * 15's rule for elements that already have their own `clickable`), building
 * the menu at tap time so it always sees the live engine list; with no
 * engines the [Menu.hasContent] guard opens nothing.
 */
@Composable
private fun FolderBrowserNavBar(controller: FolderBrowserController, enabled: Boolean, onClose: () -> Unit) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    Layout(
        content = {
            // 0: the ✕. 1: the title. 2: the capsule — the measure block
            // positions them by index.
            GlassCircleButton(
                icon = RobocoIcons.Xmark,
                contentDescription = "Close",
                onClick = onClose,
                size = 44.dp,
                iconPt = 17f,
                weight = SymbolWeight.Medium,
                enabled = enabled,
                modifier = Modifier.testTag("sheet-close"),
            )
            BasicText(
                controller.title,
                style = uiStyle(Face.SansSemibold, ts.fixed(17f), palette.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .semantics { heading() }
                    .testTag("folder-title"),
            )
            val menuState = rememberGlassMenuState()
            var capsuleBounds by remember { mutableStateOf(Rect.Zero) }
            GlassTextCapsule(
                text = controller.capsuleTitle,
                onClick = {
                    val menu = engineMenu(controller)
                    if (menu.hasContent()) menuState.open(capsuleBounds, menu)
                },
                enabled = enabled,
                modifier = Modifier
                    .onGloballyPositioned { capsuleBounds = it.boundsInWindow() }
                    .testTag("folder-engine"),
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(FolderBrowserTokens.NavHeight),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val navHeight = FolderBrowserTokens.NavHeight.roundToPx()
        val inset = FolderBrowserTokens.SideInset.roundToPx()
        val gap = FolderBrowserTokens.ItemGap.roundToPx()

        val close = measurables[0].measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxHeight = navHeight),
        )
        val capsuleMax = (FolderBrowserTokens.CapsuleMaxFraction * width).roundToInt()
        val capsule = measurables[2].measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxWidth = capsuleMax, maxHeight = navHeight),
        )
        val regionStart = inset + close.width + gap
        val regionEnd = width - inset - capsule.width - gap
        val titleMax = (regionEnd - regionStart).coerceAtLeast(0)
        val title = measurables[1].measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxWidth = titleMax, maxHeight = navHeight),
        )
        val titleX = navTitleX(width, title.width, regionStart, regionEnd)
        layout(width, navHeight) {
            // placeRelative mirrors every x in RTL; all three centre vertically.
            close.placeRelative(inset, (navHeight - close.height) / 2)
            title.placeRelative(titleX, (navHeight - title.height) / 2)
            capsule.placeRelative(width - inset - capsule.width, (navHeight - capsule.height) / 2)
        }
    }
}

/**
 * The title's x: centred on the **sheet** when it fits, otherwise slid
 * just enough to clear the button it would overlap (UIKit nav-bar
 * behaviour). All values in px; pure and unit-tested.
 */
internal fun navTitleX(width: Int, titleWidth: Int, regionStart: Int, regionEnd: Int): Int =
    ((width - titleWidth) / 2).coerceIn(regionStart, maxOf(regionStart, regionEnd - titleWidth))

/**
 * The engine menu (ticket 15's model), built at tap time from the live
 * engine list: `Engines`/`Devices` by count, one row per engine with its
 * Online/Offline subtitle and the `desktopcomputer` icon, no checkmark
 * (iOS marks no current engine). Picking a row — including the current
 * one — re-titles the capsule and reloads at home.
 */
internal fun engineMenu(controller: FolderBrowserController): Menu = Menu(
    title = controller.menuTitle,
    children = controller.engines.map { host ->
        MenuItem(
            title = host.name,
            subtitle = if (host.online) FolderBrowserCopy.Online else FolderBrowserCopy.Offline,
            icon = MenuIcon.Symbol(RobocoIcons.Desktopcomputer),
            testTag = "folder-engine-row",
            action = { controller.selectEngine(host.id) },
        )
    },
)

// ---------------------------------------------------------------------------
// The folder list
// ---------------------------------------------------------------------------

/**
 * The inset-grouped card of folders (ticket 70's geometry, unchanged):
 * one section, no header, a `Parent Folder` row plus one row per
 * directory, 35 dp of top padding and 160 dp + the navigation-bar inset
 * of bottom padding so the last rows scroll clear of 111's bottom bar.
 *
 * 112: the rows come from `nav.rows` — plain text filters them
 * (prefix-first), `..` hides while a plain filter is active (it stays for
 * a path-shaped query, which never filters), and a non-blank plain query
 * with no survivor shows the centred `No folders match` line instead of
 * the card, 20 dp below the list top.
 *
 * The section key carries the listing serial, so every new listing gives
 * every row a new key: the LazyColumn keeps the old first-visible index
 * and offset, clamped to the new item count — UIKit's content-offset
 * behaviour across `apply(animatingDifferences: false)`. Never
 * `scrollToItem(0)`; no `Modifier.animateItem()`.
 */
@Composable
private fun FolderList(controller: FolderBrowserController, nav: FolderNavState) {
    val listState = rememberLazyListState()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val listing = controller.listing
    val rows = listing?.let { nav.rows(it.entries, { entry -> entry.name }, { entry -> entry.isDir }) }
    val noMatch = rows?.noMatch == true
    val items: List<FolderRow> = if (listing == null || rows == null) {
        controller.rows
    } else {
        buildList {
            if (controller.listingHasParent && rows.parentAllowed) add(FolderRow.Parent)
            for (entry in rows.folders) add(FolderRow.Folder(entry.name, entry.isRepo))
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("folder-list"),
        contentPadding = PaddingValues(
            top = if (noMatch) FolderHeaderTokens.NoMatchTop else FolderBrowserTokens.ListTop,
            bottom = FolderBrowserTokens.ListBottom + navBottom,
        ),
    ) {
        if (noMatch) {
            item(key = "folder-no-match") { NoFoldersMatchLine() }
        } else {
            val serial = controller.listingSerial
            groupedSection(
                key = "folders-$serial",
                header = null,
                items = items,
                itemKey = { it.key },
            ) { row, position ->
                FolderRowView(row, position) { controller.tap(row) }
            }
        }
    }
}

/** 112: the filtered list's empty state — Geist Regular 13, `secondary`, centred. */
@Composable
private fun NoFoldersMatchLine() {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    BasicText(
        FolderHeaderTokens.NoMatch,
        Modifier
            .fillMaxWidth()
            .testTag("folder-no-match"),
        style = uiStyle(Face.Sans, ts.fixed(13f), palette.secondary).copy(textAlign = TextAlign.Center),
    )
}

/**
 * One row: the parent (Geist Regular `bodyScaled(17)`, `accent` icon, no
 * accessory) or a folder (ticket 70's default Geist Medium `fixed(16)`
 * title, disclosure chevron; `folder.badge.gearshape` in `accent` for
 * repos, `folder` in `secondary` otherwise). The click is passed always —
 * the controller ignores the taps it must ignore, and the row still
 * shows its press highlight (iOS still selects and deselects the cell).
 */
@Composable
private fun FolderRowView(row: FolderRow, position: GroupedPosition, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    when (row) {
        FolderRow.Parent -> GroupedRow(
            title = FolderBrowserCopy.ParentFolder,
            position = position,
            icon = RobocoIcons.ArrowTurnLeftUp,
            iconTint = palette.accent,
            accessories = emptyList(),
            titleStyle = uiStyle(Face.Sans, ts.bodyScaled(17f), palette.text),
            testTag = "folder-parent",
            onClick = onClick,
        )
        is FolderRow.Folder -> GroupedRow(
            title = row.name,
            position = position,
            icon = if (row.isRepo) RobocoIcons.FolderBadgeGearshape else RobocoIcons.Folder,
            iconTint = if (row.isRepo) palette.accent else palette.secondary,
            accessories = listOf(GroupedAccessory.Disclosure),
            testTag = "folder-row",
            onClick = onClick,
        )
    }
}

// ---------------------------------------------------------------------------
// The sheet body and the presentation entry points
// ---------------------------------------------------------------------------

/** From the New Session sheet's project-chip menu (ticket 113, phone). */
fun PageSheetScope.presentNewProject(onCreated: (String) -> Unit): Boolean =
    present("new-project", SheetStyle.Page(grabber = false)) { NewProjectSheetBody(onCreated) }

/** From the root host (the tablet's embedded New Session, tickets 113/106). */
fun SheetHostState.presentNewProject(onCreated: (String) -> Unit): Boolean =
    present("new-project", SheetStyle.Page(grabber = false)) { NewProjectSheetBody(onCreated) }

/**
 * The shared body of both [presentNewProject] overloads (ticket 92 step
 * 10's wiring, mirrored exactly): the app's single workspace feed mapped
 * into the hosts flow (initial value = the current snapshot, never
 * `null`), the core source over `clientOrNull()`, and the screen over the
 * controller — the same feed and the same client provider 92's model
 * uses; never a second workspace listener. 111 adds the write seam
 * ([CoreProjectWriteApi], the same provider) and the actions over it.
 */
@Composable
internal fun PageSheetScope.NewProjectSheetBody(onCreated: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as RobocoApplication
    val pageScope = rememberCoroutineScope()
    val feed = app.workspaceFeed
    val hosts = remember {
        feed.snapshot
            .map { it?.toNewSessionOptions()?.hosts }
            .stateIn(
                pageScope,
                SharingStarted.Eagerly,
                feed.snapshot.value?.toNewSessionOptions()?.hosts,
            )
    }
    val source = remember { CoreFolderBrowserSource({ app.clientOrNull() }) }
    val controller = rememberFolderBrowserController(hosts, source)
    val writeApi = remember { CoreProjectWriteApi({ app.clientOrNull() }) }
    val actions = rememberFolderBrowserActions(
        controller = controller,
        paths = FfiHostPaths,
        api = writeApi,
        onCreated = onCreated,
        dismissSheet = { dismiss(animated = true) },
    )
    val calls = remember { CoreFolderCalls({ app.clientOrNull() }, writeApi, FfiHostPaths) }
    val nav = rememberFolderNavState(controller, actions, calls)
    NewProjectScreen(sheet = this, controller = controller, actions = actions, nav = nav)
}
