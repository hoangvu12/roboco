package sh.roboco.android.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import sh.roboco.android.RobocoApplication
import sh.roboco.android.core.Face
import sh.roboco.android.design.GroupedAccessory
import sh.roboco.android.design.GroupedFooter
import sh.roboco.android.design.GroupedPosition
import sh.roboco.android.design.GroupedRow
import sh.roboco.android.design.GroupedRowContainer
import sh.roboco.android.design.LocalBottomChrome
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.groupedSection
import sh.roboco.android.design.uiStyle
import sh.roboco.android.shell.LargeTitleScaffold
import sh.roboco.android.shell.LocalNavStack
import uniffi.roboco_core.PreviewPanelPhase
import uniffi.roboco_core.PreviewPanelState
import uniffi.roboco_core.PreviewServiceRow

/**
 * The preview panel page's test tags (ticket 22's rule: the tags live with
 * the component that owns the node). The desktop's row id shape is kept
 * (`preview-row-<id>`).
 */
internal object PanelTags {
    const val Screen = "previews-panel"
    const val Row = "preview-row"
    const val Empty = "preview-empty"
    const val Note = "preview-note"
    const val Error = "preview-error"

    fun row(serviceId: String): String = "$Row-$serviceId"
}

/**
 * The preview panel page (agent-previews ticket 05) — the phone's port of
 * the desktop browser pane's preview list (`ui/src/browser/view.rs`
 * `preview_body`): the "Running on your device" header, one grouped row
 * per service (name, `device · localhost:port` subtitle), the honest
 * remote empty state, the loading and reconnect states, and the inline
 * error (a busy 7332, the engine's own discovery error) as the footer.
 *
 * The behaviour lives in [PreviewPanelModel]; this file only draws. Open
 * pushes [BrowserRoute] on the same stack (the default [onOpen]), so the
 * panel stays composed beneath the browser entry — the Rust panel's
 * watcher keeps the in-app 7332 listener bound while the preview is
 * viewed, and stopping on the entry's close (the [DisposableEffect]
 * below) is what lets the listener idle out once nobody views previews.
 */
@Composable
fun PreviewPanelScreen(
    route: PreviewPanelRoute,
    modifier: Modifier = Modifier,
    core: PreviewsCore? = null,
    scope: CoroutineScope? = null,
    io: CoroutineDispatcher = Dispatchers.IO,
    /** The Open action; the default pushes the browser entry on this stack. */
    onOpen: ((PreviewServiceRow) -> Unit)? = null,
) {
    val app = LocalContext.current.applicationContext as? RobocoApplication
    val effectiveCore = core ?: remember(app) {
        app?.let { CorePreviewsCore(client = { it.clientOrNull() }) }
    }
    val uiScope = scope ?: remember(app) {
        CoroutineScope(
            (app?.appScope ?: CoroutineScope(Dispatchers.Main.immediate)).coroutineContext +
                Dispatchers.Main.immediate,
        )
    }
    val model = remember(route.chatId, effectiveCore, uiScope, io) {
        effectiveCore?.let { live ->
            PreviewPanelModel(chatId = route.chatId, core = { live }, scope = uiScope, io = io)
        }
    }
    // The watch starts with the entry's first composition and stops when
    // the entry leaves it (a committed pop, a setRoutes removal, or a tab
    // host disposal) — the Rust panel's close releases the engine's
    // watcher, so the in-app 7332 listener idles out with no panel open.
    DisposableEffect(model) {
        model?.start()
        onDispose { model?.stop() }
    }
    val state: PreviewPanelState? = model?.state?.collectAsState()?.value
    val stack = LocalNavStack.current
    val currentOnOpen by rememberUpdatedState(
        onOpen ?: { service: PreviewServiceRow ->
            stack.push(BrowserRoute(url = service.url, title = service.name), animated = true)
        },
    )
    val listState: LazyListState = rememberLazyListState()
    val chrome = LocalBottomChrome.current
    LargeTitleScaffold(
        title = PreviewsCopy.pageTitle(route.chatTitle),
        modifier = modifier.testTag(PanelTags.Screen),
        listState = listState,
        contentPadding = PaddingValues(bottom = chrome.contentPadding + 16.dp),
    ) {
        if (state == null || state.phase == PreviewPanelPhase.LOADING) {
            noteRow(PreviewsCopy.Loading, PanelTags.Note)
        } else when (state.phase) {
            PreviewPanelPhase.LIVE ->
                if (state.services.isEmpty()) {
                    noteRow(PreviewsCopy.EmptyRemote, PanelTags.Empty)
                } else {
                    groupedSection(
                        key = "previews",
                        header = PreviewsCopy.RunningOnYourDevice,
                        items = state.services,
                        itemKey = { it.id },
                    ) { service, position ->
                        GroupedRow(
                            title = service.name,
                            subtitle = PreviewsCopy.rowSubtitle(service.deviceName, service.port),
                            position = position,
                            accessories = listOf(GroupedAccessory.Disclosure),
                            testTag = PanelTags.row(service.id),
                            onClick = { currentOnOpen(service) },
                        )
                    }
                }
            PreviewPanelPhase.RECONNECTING -> noteRow(PreviewsCopy.Reconnecting, PanelTags.Note)
            PreviewPanelPhase.UNSUPPORTED -> noteRow(PreviewsCopy.Unsupported, PanelTags.Note)
            // LOADING is handled above; the branch keeps the `when`
            // exhaustive (a new phase degrades to the loading note).
            PreviewPanelPhase.LOADING -> noteRow(PreviewsCopy.Loading, PanelTags.Note)
        }
        state?.error?.let { error ->
            item(key = "preview-error", contentType = "grouped-footer") {
                GroupedFooter(error, Modifier.testTag(PanelTags.Error))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The note row (the loading / reconnect / unsupported / empty state)
// ---------------------------------------------------------------------------

/** One centered note row over the grouped-card idiom (`AccountsStatusRow`). */
private fun androidx.compose.foundation.lazy.LazyListScope.noteRow(text: String, tag: String) {
    item(key = tag, contentType = "grouped-row") {
        val palette = RobocoTheme.palette
        val typeScale = LocalTypeScale.current
        GroupedRowContainer(
            position = GroupedPosition.Single,
            onClick = null,
            testTag = tag,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text,
                    style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.secondary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
