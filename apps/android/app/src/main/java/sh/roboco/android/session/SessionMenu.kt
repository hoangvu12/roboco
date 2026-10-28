package sh.roboco.android.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.roboco.android.composer.ACCOUNT_USAGE_ROW
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalToaster
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.Toaster
import sh.roboco.android.design.glass.GlassCircleButton
import sh.roboco.android.design.glass.GlassMenuPanel
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.design.glass.MenuSection
import sh.roboco.android.design.glass.flattenLevel
import sh.roboco.android.design.glass.rememberGlassMenuState
import sh.roboco.android.sessions.ActionOutcome
import sh.roboco.android.sessions.LocalRowActions
import sh.roboco.android.sessions.rememberSessionPrompts
import sh.roboco.android.shell.AppRouter
import sh.roboco.android.shell.LocalAppRouter
import sh.roboco.android.shell.LocalNavEntry
import sh.roboco.android.shell.LocalNavStack
import sh.roboco.android.transcript.TranscriptState
import uniffi.roboco_core.LayoutFrame

/**
 * The header's ⋯ pull-down (android-ui-parity #80) — the port of
 * `sessionMenu()` (`ios:Session/SessionViewController.swift:523-544`):
 * three rows in one untitled group, **Pin/Unpin, Copy Transcript,
 * Archive** (destructive). Everything visual belongs to ticket 15's
 * [Menu]/[MenuItem]/[GlassMenuPanel]; this file owns only the content,
 * the three actions and the one router hook the Archive action needs.
 *
 * The menu is **rebuilt on every open** (iOS
 * `UIDeferredMenuElement.uncached`): [rememberSessionMenu] builds a fresh
 * [Menu] inside the click lambda, so the pin title and the disabled flags
 * are always current, never cached in composition.
 *
 * Roboco adaptations (79/42/22): Pin/Unpin and Archive are disabled while
 * `canWrite == false` (Copy Transcript stays live — it reads only local
 * state); the leave after Archive happens only on `Ok`/`Gone` outcomes;
 * Copy Transcript builds the string off main and handles Binder's
 * transaction limit with a failure toast.
 */

/**
 * Pure: the ⋯ menu for one open. iOS `SessionViewController.sessionMenu()`
 * (PR #578 adds the fourth row) — no `Menu` title, one group, no
 * separators, rows in order:
 *
 * - row 1: "Pin" (SF `pin`) / "Unpin" (SF `pin.slash`) by [pinned],
 *   `app.setPinned(chatId, !pinned)`;
 * - row 2: "Copy Transcript" (SF `doc.on.doc`), never disabled — it reads
 *   only the local transcript;
 * - row 3: "Previews" (the app's `Safari` globe glyph — the agent-previews
 *   ticket's panel: this chat's dev servers on the engine, relayed through
 *   the phone's in-app 7332 proxy), never disabled — the panel owns its
 *   own honest states (loading, reconnect, empty);
 * - row 4: "Account & Usage" (android-next#05, PR #578's SF
 *   `gauge.with.dots.needle.33percent` — the app's `Gauge` glyph), never
 *   disabled — it only reads cached state and pushes a page;
 * - row 5: "Archive" (SF `archivebox`), disabled with Pin while the
 *   engine is offline (79's `canWrite`) — no longer destructive (#967);
 * - row 6: "Delete" (SF `trash`), destructive, disabled like Archive —
 *   upstream #967, after the "Delete session?" confirm. `null`
 *   [onDelete] (galleries, older tests) leaves the row out.
 *
 * Upstream #967 also heads the menu with the session's title over
 * "project @ device" ([title]/[subtitle], one wrapped `Menu.title`; blank
 * = no title line). Roboco keeps the header bar's two-line title as well.
 *
 * Keep it pure: no `@Composable`, no state reads. [rememberSessionMenu]
 * calls this once per open, from the click lambda.
 */
fun sessionMenu(
    pinned: Boolean,
    canWrite: Boolean,
    onSetPinned: (Boolean) -> Unit,
    onCopyTranscript: () -> Unit,
    onShowPreviews: () -> Unit,
    onShowAccounts: () -> Unit,
    onArchive: () -> Unit,
    sideChats: SideChatMenuRows? = null,
    onDelete: (() -> Unit)? = null,
    title: String = "",
    subtitle: String = "",
): Menu = Menu(
    title = sessionMenuHeader(title, subtitle),
    children = listOfNotNull(
        MenuItem(
            title = if (pinned) "Unpin" else "Pin",
            icon = MenuIcon.Symbol(if (pinned) RobocoIcons.PinSlash else RobocoIcons.Pin),
            disabled = !canWrite,
            testTag = "session-menu-pin",
            action = { onSetPinned(!pinned) },
        ),
        MenuItem(
            title = "Copy Transcript",
            icon = MenuIcon.Symbol(RobocoIcons.DocOnDoc),
            testTag = "session-menu-copy",
            action = onCopyTranscript,
        ),
        sideChats?.let { sideChatSection(it, canWrite) },
        MenuItem(
            title = sh.roboco.android.browser.PreviewsCopy.SessionMenuRow,
            icon = MenuIcon.Symbol(RobocoIcons.Safari),
            testTag = "session-menu-previews",
            action = onShowPreviews,
        ),
        MenuItem(
            title = ACCOUNT_USAGE_ROW,
            icon = MenuIcon.Symbol(RobocoIcons.Gauge),
            testTag = "session-menu-accounts",
            action = onShowAccounts,
        ),
        MenuItem(
            title = "Archive",
            icon = MenuIcon.Symbol(RobocoIcons.Archivebox),
            disabled = !canWrite,
            testTag = "session-menu-archive",
            action = onArchive,
        ),
        onDelete?.let {
            MenuItem(
                title = "Delete",
                icon = MenuIcon.Symbol(RobocoIcons.Trash),
                destructive = true,
                disabled = !canWrite,
                testTag = "session-menu-delete",
                action = it,
            )
        },
    ),
)

/**
 * The ⋯ menu's header (upstream #967): the title, then where it runs on
 * its own line — a menu subtitle isn't drawn in the header, so the two
 * share the title, split by a line break (the menu draws each line once,
 * ellipsized). `null` when both are blank.
 */
internal fun sessionMenuHeader(title: String, subtitle: String): String? = when {
    title.isBlank() && subtitle.isBlank() -> null
    subtitle.isBlank() -> title
    title.isBlank() -> subtitle
    else -> "$title\n$subtitle"
}

/**
 * android-next #06's ⋯ rows (upstream #899's inline group between Copy
 * Transcript and Archive): New Side Chat and Fork into Side Chat. `null`
 * — an engine without side chats — leaves the menu as it was. [forking]
 * disables the fork while one is in flight (one at a time).
 */
class SideChatMenuRows(
    val forking: Boolean,
    val onNew: () -> Unit,
    val onFork: () -> Unit,
)

/** The inline group (hairlines above and below); both rows need a writable engine. */
private fun sideChatSection(rows: SideChatMenuRows, canWrite: Boolean): MenuSection = MenuSection(
    inline = true,
    children = listOf(
        MenuItem(
            title = "New Side Chat",
            icon = MenuIcon.Symbol(RobocoIcons.QuestionmarkBubble),
            disabled = !canWrite,
            testTag = "session-menu-new-side-chat",
            action = rows.onNew,
        ),
        MenuItem(
            title = "Fork into Side Chat",
            icon = MenuIcon.Symbol(RobocoIcons.ArrowTriangleBranch),
            disabled = !canWrite || rows.forking,
            testTag = "session-menu-fork-side-chat",
            action = rows.onFork,
        ),
    ),
)

/**
 * The [SessionHeader] `onMore` handler (80's step 7): returns a lambda
 * that builds a fresh [sessionMenu] from the **current** pin state and
 * `SessionChrome.canWrite`, and opens it on ticket 15's host at the ⋯
 * circle's window rect. The values are read inside the click lambda —
 * never during composition — so each open is current (the uncached
 * deferred element) and a pin toggle between opens needs no
 * recomposition.
 *
 * The writes go through [LocalRowActions] (42), the Undo through the
 * app-scoped [LocalToaster] (14) — both outlive this screen, so the
 * "Archived · Undo" toast still works after the pop. Copy runs in the
 * composition scope: if the screen leaves mid-copy the copy is cancelled
 * (acceptable — the menu is closed and the user is gone). android-next
 * #05's "Account & Usage" pushes the Accounts & Usage page scoped to
 * this session's harness and computer (PR #578's `showAccounts`).
 *
 * Requires [sh.roboco.android.shell.LocalNavStack] /
 * [sh.roboco.android.shell.LocalNavEntry] (any screen inside a
 * `NavStackHost`) and a mounted `GlassMenuHost` (the app root, 35).
 */
@Composable
fun rememberSessionMenu(
    controller: SessionController,
    transcript: TranscriptState,
    /** android-next #06: the side-chat rows, read at open time; `null` result = none. */
    sideChats: () -> SideChatMenuRows? = { null },
): (anchorInWindow: Rect) -> Unit {
    val currentSideChats by rememberUpdatedState(sideChats)
    val menuState = rememberGlassMenuState()
    val actions = LocalRowActions.current
    val router = LocalAppRouter.current
    val nav = LocalNavStack.current
    val entry = LocalNavEntry.current
    val toaster = LocalToaster.current
    val prompts = rememberSessionPrompts(rowActions = actions)
    // The application context: the clipboard service outlives any
    // activity-level context flip while this remembered lambda is held.
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember(controller, transcript, menuState, actions, router, nav, entry, toaster, prompts) {
        val open: (Rect) -> Unit = { anchor: Rect ->
            val chatId = controller.chatId
            val chrome = controller.chrome.value
            // Off this session once it's gone (iOS `leave()`): the split's
            // draft, else the guarded pop. Shared by Archive and Delete.
            val leave = {
                leaveAfterArchive(
                    chatId,
                    router,
                    isTopAndIdle = { nav.top == entry && nav.transition == null },
                    pop = { nav.pop(animated = true) },
                )
            }
            // android-next #05: the session's harness and computer scope
            // the Accounts & Usage page (a mid-transition guard like the
            // Devices section's).
            val showAccounts = {
                if (nav.top.id == entry.id) {
                    nav.push(
                        sh.roboco.android.accounts.AgentAccountsRoute(
                            deviceId = controller.hostDeviceId.ifEmpty { null },
                            focusHarness = controller.harness,
                        ),
                        animated = true,
                    )
                }
            }
            // agent-previews ticket 05: the preview panel — this chat's
            // dev servers on the engine, relayed through the phone's
            // in-app 7332 proxy. The title is read at open time (the
            // chrome's, like the uncached pin state above).
            val showPreviews = {
                if (nav.top.id == entry.id) {
                    nav.push(
                        sh.roboco.android.browser.PreviewPanelRoute(
                            chatId = chatId,
                            chatTitle = controller.chrome.value.title,
                        ),
                        animated = true,
                    )
                }
            }
            val menu = sessionMenu(
                pinned = controller.pinned,
                canWrite = controller.chrome.value.canWrite,
                onSetPinned = { pinned -> actions.setPinned(chatId, pinned) },
                onCopyTranscript = {
                    scope.launch {
                        val clipboard = appContext.getSystemService(ClipboardManager::class.java)
                            ?: return@launch
                        copyTranscript(transcript, clipboard, toaster)
                    }
                },
                onShowPreviews = showPreviews,
                onShowAccounts = showAccounts,
                onArchive = {
                    actions.archive(chatId) { outcome ->
                        if (outcome == ActionOutcome.Ok || outcome == ActionOutcome.Gone) leave()
                    }
                },
                sideChats = currentSideChats(),
                onDelete = { prompts.confirmDeleteSession(chatId, chrome.title, onDeleted = leave) },
                title = chrome.title,
                subtitle = chrome.subtitle,
            )
            menuState.open(anchor, menu)
        }
        open
    }
}

/**
 * Copies `plainText()` of the **latest** frame (iOS
 * `UIPasteboard.general.string = engine.frame().plainText()`), off main:
 *
 * 1. on main: `transcript.engine.frame()` — a fresh Kotlin wrapper this
 *    code owns — unless the transcript is closed, in which case the last
 *    applied [TranscriptState.frame] is used (owned by the state,
 *    **never closed**);
 * 2. on [work]: `plainText()` and the fresh wrapper's `close()` (the
 *    pr621 closing rule);
 * 3. back on main: the platform `ClipboardManager` write.
 *
 * Feedback (android-app#22): API 33+ shows the system's own copy overlay
 * — nothing here; API 31–32 shows Roboco's "Copied" toast. A
 * `TransactionTooLargeException`-style `RuntimeException` from Binder
 * logs and toasts "Could not copy the transcript." (iOS's pasteboard has
 * no such limit; Android's must not crash the app). An empty transcript
 * copies `""`, as iOS does.
 */
internal suspend fun copyTranscript(
    transcript: TranscriptState,
    clipboard: ClipboardManager,
    toaster: Toaster,
    sdkInt: Int = Build.VERSION.SDK_INT,
    work: CoroutineDispatcher = Dispatchers.Default,
) {
    // Both reads happen on main; never read the snapshot-state
    // `transcript.frame` off main.
    val fresh: LayoutFrame? = if (transcript.isClosed) null else transcript.engine.frame()
    val held: LayoutFrame? = if (fresh == null) transcript.frame else null
    val text = withContext(work) {
        when {
            // pr621's closing rule: the fresh wrapper is closed in a
            // finally, so a throwing plainText() never leaks it. (A bare
            // `use` does not resolve here: kotlin.io's applies to
            // java.io.Closeable, the bindings' own to `Disposable` in
            // uniffi.roboco_core.)
            fresh != null -> try {
                fresh.plainText()
            } finally {
                fresh.close()
            }
            held != null -> held.plainText()
            else -> ""
        }
    }
    try {
        clipboard.setPrimaryClip(ClipData.newPlainText("Transcript", text))
        // Android 12/12L has no copy confirmation of its own; Android 13+
        // shows its overlay and a second toast would duplicate it.
        if (sdkInt <= Build.VERSION_CODES.S_V2) toaster.show("Copied")
    } catch (e: RuntimeException) {
        Log.w("Roboco", "copy transcript failed: $e")
        toaster.show("Could not copy the transcript.")
    }
}

/**
 * Router first (the tablet split's embedded draft, iOS
 * `split.showDraft(prompt: nil, focus: false)`), else pop this screen if
 * it is still the stack's top and no transition is running. The guard
 * stops a late `onResult` from popping a *different* screen — the user
 * may have swiped back while the write was in flight.
 *
 * Lambdas instead of `NavStack`/`NavEntry` because `NavStack` is a final
 * class (ticket 24) and cannot be faked; the production call site is
 * `{ nav.top == entry && nav.transition == null }` / `{ nav.pop(animated = true) }`.
 */
internal fun leaveAfterArchive(
    chatId: String,
    router: AppRouter,
    isTopAndIdle: () -> Boolean,
    pop: () -> Unit,
) {
    if (router.leaveArchivedSession(chatId)) return
    if (isTopAndIdle()) pop()
}

// ---------------------------------------------------------------------------
// Previews (step 9): the open state, statically — a @Preview cannot drive
// the host's open animation, so the panel is rendered directly through
// [GlassMenuPanel] next to a 44 dp ⋯ circle, at the placement the host
// would use (panel trailing edge = circle trailing edge, panel top =
// circle top: `cover = true`).
// ---------------------------------------------------------------------------

/** The previews' shared fixture: the ⋯ circle with the menu open below it. */
@Composable
private fun SessionMenuOpenPreview(pinned: Boolean, canWrite: Boolean) {
    Box(Modifier.fillMaxWidth().height(220.dp).background(RobocoTheme.palette.background)) {
        GlassCircleButton(
            icon = RobocoIcons.Ellipsis,
            contentDescription = "More",
            onClick = {},
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 16.dp),
        )
        GlassMenuPanel(
            lines = flattenLevel(
                null,
                sessionMenu(
                    pinned = pinned,
                    canWrite = canWrite,
                    onSetPinned = {},
                    onCopyTranscript = {},
                    onShowPreviews = {},
                    onShowAccounts = {},
                    onArchive = {},
                    onDelete = {},
                ).children,
                { null },
                isRoot = true,
                backTitle = null,
            ),
            onRow = {},
            onSubmenu = {},
            onBack = {},
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 16.dp),
        )
    }
}

@Preview(name = "light")
@Composable
private fun SessionMenuUnpinnedLightPreview() {
    RobocoTheme(Appearance.Light) { SessionMenuOpenPreview(pinned = false, canWrite = true) }
}

@Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SessionMenuPinnedDarkPreview() {
    RobocoTheme(Appearance.Dark) { SessionMenuOpenPreview(pinned = true, canWrite = true) }
}

@Preview(name = "read-only light")
@Composable
private fun SessionMenuReadOnlyLightPreview() {
    RobocoTheme(Appearance.Light) { SessionMenuOpenPreview(pinned = false, canWrite = false) }
}
