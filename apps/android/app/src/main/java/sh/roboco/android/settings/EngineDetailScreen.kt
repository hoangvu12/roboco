package sh.roboco.android.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import sh.roboco.android.RobocoApplication
import sh.roboco.android.core.Face
import sh.roboco.android.design.FadingText
import sh.roboco.android.design.GroupedAccessory
import sh.roboco.android.design.GroupedFooter
import sh.roboco.android.design.GroupedHeader
import sh.roboco.android.design.GroupedListTokens
import sh.roboco.android.design.GroupedPosition
import sh.roboco.android.design.GroupedRow
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.LocalActionSheet
import sh.roboco.android.design.LocalAlerts
import sh.roboco.android.design.LocalBottomChrome
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.LocalToaster
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle
import sh.roboco.android.pairing.LocalPairPage
import sh.roboco.android.shell.LargeTitleScaffold
import sh.roboco.android.shell.LocalEnclosingSheet
import sh.roboco.android.shell.LocalNavEntry
import sh.roboco.android.shell.LocalNavStack
import uniffi.roboco_core.EngineView

/**
 * The Engine detail page (android-ui-parity #140): the pushed page the
 * Settings "Devices" engine row opens (102's push, the route is
 * [EngineDetailRoute]). A [LargeTitleScaffold] (25) whose large title is
 * the engine name, over three inset-grouped cards (70):
 *
 *  1. **Status** — value rows (Status with the 8 dp dot, Address, Version,
 *     Platform, Last connected, Device ID) and the optional notice/error
 *     footer;
 *  2. **Actions** — "Rename…" (18's alert → `renameEngine`) and "Pair
 *     again" for re-pairable parked engines (72's Pair page in re-pair
 *     mode);
 *  3. **Destructive** — "Forget", confirmed through 19's action sheet
 *     (decision SHELL-D1), revoking the phone's own session first
 *     (best-effort), then `forgetEngine`, the engine's notifications
 *     (28), the stored record, the drafts (84/92) and one pop. Forgetting the last engine leaves the rest to
 *     ticket 35's root cross-fade.
 *
 * The engine comes from ticket 71's `engines` flow — never an FFI call in
 * composition — and the page keeps drawing the last view while it pops
 * (the engine vanishes from the flow the moment it is forgotten). `now`
 * re-reads every 15 s (the web's `useNow(15_000)`) and on every flow
 * emission, so "Last connected" stays fresh.
 */
@Composable
fun EngineDetailScreen(
    engineId: String,
    engines: StateFlow<List<EngineView>?> =
        (LocalContext.current.applicationContext as RobocoApplication).coreStartup.engines,
    backend: EngineDetailBackend? = null,
    forgetDrafts: ForgetDrafts? = null,
    forgetContext: (() -> ForgetDraftsContext)? = null,
    onEngineForgotten: ((engineId: String) -> Unit)? = null,
    nowMs: () -> Long = System::currentTimeMillis,
    io: CoroutineDispatcher = Dispatchers.IO,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as RobocoApplication
    val list by engines.collectAsState()
    val view = list?.firstOrNull { it.engineId == engineId }

    // "Keep the last view": the engine can vanish from the flow (forgotten
    // here or elsewhere) while the page is still up or popping — the page
    // never draws an empty frame, it keeps the last non-null view.
    var lastView by remember { mutableStateOf<EngineView?>(null) }
    if (view != null) SideEffect { lastView = view }
    val shown = view ?: lastView

    // The relative-time clock: re-read every 15 s and on every emission.
    var now by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(EngineDetailTimings.NowTickMs)
            now = nowMs()
        }
    }
    LaunchedEffect(list) { now = nowMs() }

    // One pop, ever: the flow dropping the engine (forget elsewhere) and
    // the forget sequence's own requestPop both funnel through this guard.
    val stack = LocalNavStack.current
    val entry = LocalNavEntry.current
    var popRequested by remember { mutableStateOf(false) }
    val requestPop = remember(stack, entry) {
        {
            if (!popRequested && !entry.isClosed && stack.top.id == entry.id) {
                popRequested = true
                stack.pop(animated = true)
            }
        }
    }
    // The engine is gone from a loaded flow: pop (once). A `null` list (the
    // core still starting) never pops; the first composition with neither
    // view nor lastView pops through this same call.
    LaunchedEffect(view == null, list != null) {
        if (list != null && view == null) requestPop()
    }

    // The behaviour. workScope is the application scope (Main.immediate):
    // a rename or forget must survive this page popping under it. uiScope
    // (the page scope) carries only the 1.5 s Copied timer.
    val uiScope = rememberCoroutineScope()
    val workScope = remember { CoroutineScope(app.appScope.coroutineContext + Dispatchers.Main.immediate) }
    val toaster = LocalToaster.current
    val alerts = LocalAlerts.current
    val sheets = LocalActionSheet.current
    val effectiveBackend = backend ?: remember(app) {
        CoreEngineDetailBackend(client = app::clientOrNull, store = app.engineStore)
    }
    // Tickets 84 + 92's forget hooks, read at Forget-confirm time with the
    // snapshot taken before any FFI call and the engines left after it.
    val effectiveDrafts = forgetDrafts ?: remember(app) {
        ForgetDrafts { id, context ->
            app.drafts.forgetEngine(id)
            app.newSessionDrafts.forgetEngine(id, context.snapshot, context.enginesLeft)
        }
    }
    val effectiveContext = forgetContext ?: remember(app, engines, engineId) {
        {
            ForgetDraftsContext(
                snapshot = app.workspaceFeed.snapshot.value,
                enginesLeft = engines.value.orEmpty().count { it.engineId != engineId },
            )
        }
    }
    // Ticket 28: forgetting clears the engine's notifications.
    val effectiveForgotten = onEngineForgotten ?: remember(app) { { id: String -> app.notifier.cancelEngine(id) } }
    val state = remember(
        engineId, effectiveBackend, toaster, alerts, sheets, requestPop, effectiveDrafts, effectiveContext, effectiveForgotten,
    ) {
        EngineDetailState(
            engineId = engineId,
            backend = effectiveBackend,
            uiScope = uiScope,
            workScope = workScope,
            toaster = toaster,
            alerts = alerts,
            sheets = sheets,
            requestPop = requestPop,
            forgetDrafts = effectiveDrafts,
            onEngineForgotten = effectiveForgotten,
            forgetContext = effectiveContext,
            io = io,
        )
    }

    // The model of the shown view at the current clock.
    val ui = shown?.let { remember(it, now) { engineDetailUi(it, now) } }

    val chrome = LocalBottomChrome.current
    val feedback = LocalFeedback.current
    val context = LocalContext.current
    // Read outside the callback: the composition locals are resolved at
    // composition time, the click only uses the values (72's contract).
    val pairPage = LocalPairPage.current
    val enclosingSheet = LocalEnclosingSheet.current

    val onCopyId: () -> Unit = {
        val id = ui?.deviceId
        if (id != null) {
            state.onCopyId(
                fullId = id,
                copy = { value -> clipDeviceId(context, value) },
                haptic = { feedback.haptic(Haptic.Light) },
            )
        }
    }
    val onRename: () -> Unit = { shown?.let(state::onRename) }
    val onPairAgain: () -> Unit = {
        val launcher = pairPage
        if (launcher != null) {
            launcher.openPairPage(prefill = null, replaceEngineId = engineId, over = enclosingSheet)
        } else {
            Log.w("Roboco", "no PairPageLauncher")
        }
    }
    val onForget: () -> Unit = { shown?.let(state::onForget) }

    // 25's own root paints `Palette.background` — never paint it twice.
    LargeTitleScaffold(
        title = ui?.title.orEmpty(),
        modifier = modifier.testTag("engine-detail"),
        contentPadding = PaddingValues(bottom = chrome.contentPadding + EngineDetailScreenTokens.BottomExtra),
        content = {
            if (ui != null) {
                // One lazy item holds all the cards (at most 11 rows — 70's
                // one-item-per-row rule is for long lists; this also lets
                // the gallery and the goldens reuse the exact same column).
                item(key = "engine-detail:cards", contentType = "engine-detail-cards") {
                    EngineDetailCards(
                        ui = ui,
                        copied = state.copied,
                        forgetPhase = state.forgetPhase,
                        onCopyId = onCopyId,
                        onRename = onRename,
                        onPairAgain = onPairAgain,
                        onForget = onForget,
                    )
                }
            }
        },
    )
}

/** The trailing space on top of the bottom chrome padding (103's formula). */
private object EngineDetailScreenTokens {
    val BottomExtra = 16.dp
}

/**
 * `ClipboardManager.setPrimaryClip` of the **raw** device id (never the
 * short form), errors swallowed — the web's `.catch(() => {})`. The
 * Android 13+ system confirmation chip may also appear; expected.
 */
private fun clipDeviceId(context: Context, id: String) {
    runCatching {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
            ClipData.newPlainText(EngineDetailCopy.DeviceId, id),
        )
    }
}

// ---------------------------------------------------------------------------
// The cards (stateless: the screen, the gallery, the goldens)
// ---------------------------------------------------------------------------

/**
 * The page's three cards with their blank headers, top → bottom: status
 * rows + optional footer, actions, destructive. Everything visual is
 * ticket 70's ([GroupedRow], [GroupedHeader], [GroupedFooter]) — this
 * composable sets no geometry of its own beyond `valueMax` (50 % of the
 * card's inner width, 70's value rule) and the order. Data changes are
 * instant: no fade, no debounce, no `animateItem`.
 *
 * @param copied the Device ID row's "Copied" state (the screen's state
 *   holder owns it; fixtures and goldens pass it directly)
 * @param forgetPhase `Forgetting` removes the Forget row's click (taps
 *   ignored, no highlight)
 */
@Composable
fun EngineDetailCards(
    ui: EngineDetailUi,
    copied: Boolean,
    forgetPhase: ForgetPhase,
    onCopyId: () -> Unit,
    onRename: () -> Unit,
    onPairAgain: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val valueMax = (maxWidth - GroupedListTokens.CardInset * 2) * 0.5f
        Column(Modifier.fillMaxWidth()) {
            // 1. The status card: value rows, each shown only when its rule
            //    holds, positions over the rows actually shown.
            GroupedHeader(" ")
            val rows = statusRows(ui)
            rows.forEachIndexed { index, row ->
                EngineStatusCardRow(
                    row = row,
                    ui = ui,
                    copied = copied,
                    valueMax = valueMax,
                    onCopyId = onCopyId,
                    position = GroupedPosition.of(index, rows.size),
                )
            }
            ui.footer?.let { footer ->
                GroupedFooter(
                    footer.text,
                    Modifier.testTag("engine-detail-footer"),
                    color = if (footer.tone == FooterTone.Error) palette.tertiary else palette.secondary,
                )
            }

            // 2. The actions card: Rename…, then Pair again for the
            //    re-pairable park reasons (positions recomputed instantly).
            GroupedHeader(" ")
            val pairAgainShown = ui.showPairAgain
            GroupedRow(
                title = EngineDetailCopy.Rename,
                position = if (pairAgainShown) GroupedPosition.First else GroupedPosition.Single,
                icon = RobocoIcons.Pencil,
                // The VoiceViewController disabled-row look while the
                // engine is not connected: title and icon in tertiary,
                // still tappable (the toast explains why).
                titleStyle = if (ui.canRename) null else uiStyle(Face.SansMedium, typeScale.fixed(16f), palette.tertiary),
                iconTint = if (ui.canRename) palette.secondary else palette.tertiary,
                testTag = "engine-detail-rename",
                onClick = onRename,
            )
            if (pairAgainShown) {
                GroupedRow(
                    title = EngineDetailCopy.PairAgain,
                    position = GroupedPosition.Last,
                    icon = RobocoIcons.Link,
                    testTag = "engine-detail-pair-again",
                    onClick = onPairAgain,
                )
            }

            // 3. The destructive card: the iOS "Remove Wallpaper" /
            //    "Sign Out" shape — trash icon, danger title, no accessory.
            GroupedHeader(" ")
            GroupedRow(
                title = EngineDetailCopy.Forget,
                position = GroupedPosition.Single,
                icon = RobocoIcons.Trash,
                destructive = true,
                accessories = emptyList(),
                // Forgetting: no highlight, taps ignored (the sheet already
                // dismissed; the phase resets only if the forget fails).
                onClick = if (forgetPhase == ForgetPhase.Forgetting) null else onForget,
                testTag = "engine-detail-forget",
            )
        }
    }
}

/**
 * One row of the status card: a title-only [GroupedRow] (no icon, no
 * click) with the row's accessory — `[Dot, Value]` for Status, the mono
 * [FadingText] for Address, `[Value]` for Version / Platform / Last
 * connected, and the copyable mono value for Device ID.
 */
@Composable
private fun EngineStatusCardRow(
    row: EngineStatusRow,
    ui: EngineDetailUi,
    copied: Boolean,
    valueMax: Dp,
    onCopyId: () -> Unit,
    position: GroupedPosition,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    when (row) {
        EngineStatusRow.Status -> GroupedRow(
            title = EngineDetailCopy.Status,
            position = position,
            accessories = listOf(
                GroupedAccessory.Dot(ui.connected),
                GroupedAccessory.Value(ui.status),
            ),
            onClick = null,
            testTag = row.tag,
        )
        EngineStatusRow.Address -> GroupedRow(
            title = EngineDetailCopy.Address,
            position = position,
            // FadingText caps at `valueMax` and fades the tail (24 dp, no
            // "…"); its semantics expose the full URL.
            accessories = listOf(
                GroupedAccessory.Custom {
                    FadingText(
                        ui.address,
                        uiStyle(Face.Mono, typeScale.fixed(14f), palette.secondary),
                        Modifier.widthIn(max = valueMax),
                    )
                },
            ),
            onClick = null,
            testTag = row.tag,
        )
        is EngineStatusRow.Version -> GroupedRow(
            title = EngineDetailCopy.Version,
            position = position,
            accessories = listOf(GroupedAccessory.Value(row.value)),
            onClick = null,
            testTag = row.tag,
        )
        is EngineStatusRow.Platform -> GroupedRow(
            title = EngineDetailCopy.Platform,
            position = position,
            accessories = listOf(GroupedAccessory.Value(row.value)),
            onClick = null,
            testTag = row.tag,
        )
        is EngineStatusRow.LastConnected -> GroupedRow(
            title = EngineDetailCopy.LastConnected,
            position = position,
            accessories = listOf(GroupedAccessory.Value(row.value)),
            onClick = null,
            testTag = row.tag,
        )
        is EngineStatusRow.DeviceId -> GroupedRow(
            title = EngineDetailCopy.DeviceId,
            position = position,
            // The a11y click label rides on the row's modifier: the row
            // keeps 70's press highlight and Role.Button, and the labelled
            // action is what TalkBack announces (both run the same copy).
            modifier = Modifier.semantics {
                onClick(label = EngineDetailCopy.CopyIdAction) {
                    onCopyId()
                    true
                }
            },
            accessories = listOf(
                GroupedAccessory.Custom {
                    BasicText(
                        if (copied) EngineDetailCopy.Copied else shortId(row.id),
                        style = uiStyle(
                            Face.Mono,
                            typeScale.fixed(14f),
                            if (copied) palette.success else palette.secondary,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = valueMax)
                            // TalkBack reads the full id (not the short
                            // form) and announces the "Copied" flip.
                            .clearAndSetSemantics {
                                text = AnnotatedString(if (copied) EngineDetailCopy.Copied else row.id)
                                liveRegion = LiveRegionMode.Polite
                            },
                    )
                },
            ),
            onClick = onCopyId,
            testTag = row.tag,
        )
        is EngineStatusRow.Room -> GroupedRow(
            title = EngineDetailCopy.Room,
            position = position,
            accessories = listOf(GroupedAccessory.Value(row.value)),
            onClick = null,
            testTag = row.tag,
        )
    }
}
