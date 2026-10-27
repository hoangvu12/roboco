package sh.roboco.android.pairing

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import sh.roboco.android.R
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GroupedAccessory
import sh.roboco.android.design.GroupedFooter
import sh.roboco.android.design.GroupedHeader
import sh.roboco.android.design.GroupedListTokens
import sh.roboco.android.design.GroupedPosition
import sh.roboco.android.design.GroupedRow
import sh.roboco.android.design.GroupedRowContainer
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.ProminentGlassButton
import sh.roboco.android.design.glass.ProminentGlassButtonDefaults
import sh.roboco.android.design.settingsTag
import sh.roboco.android.design.sheet.SheetHeaderRow
import sh.roboco.android.design.sheet.SheetLeading
import sh.roboco.android.design.uiStyle
import sh.roboco.android.shell.PageSheetScope
import uniffi.roboco_core.PairingTarget
import uniffi.roboco_core.RoomInviteTarget
import kotlinx.coroutines.delay

/**
 * The clipboard seam (android-ui-parity #72): what the Pair page needs
 * from the system clipboard. The Paste visibility check reads the
 * **description only** (no "pasted from your clipboard" toast); the clip
 * itself is read once, on the Paste tap. Tests count [readText] calls
 * with a fake instead of spying the system service.
 */
interface PairClipboard {

    /**
     * `hasPrimaryClip() && primaryClipDescription?.hasMimeType("text/any") == true`
     * — a description-only read, safe while unfocused and toast-free.
     */
    fun hasText(): Boolean

    /**
     * `primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()`;
     * only ever called from the Paste tap. `null` when there is no clip.
     */
    fun readText(): String?

    fun addListener(listener: ClipboardManager.OnPrimaryClipChangedListener)

    fun removeListener(listener: ClipboardManager.OnPrimaryClipChangedListener)
}

/**
 * The system clipboard as the Pair page needs it
 * (`context.getSystemService(ClipboardManager::class.java)`).
 *
 * [hasText] never calls `getPrimaryClip()` — the description read does
 * not trigger Android 12+'s "pasted from your clipboard" toast and is
 * allowed by Android 10+'s unfocused-window restriction; the clip is
 * read once, in [readText], from the Paste tap (the system toast then is
 * expected).
 */
class AndroidPairClipboard(private val context: Context) : PairClipboard {

    private val clipboard: ClipboardManager? =
        context.getSystemService(ClipboardManager::class.java)

    override fun hasText(): Boolean =
        clipboard?.hasPrimaryClip() == true &&
            clipboard.primaryClipDescription?.hasMimeType("text/*") == true

    override fun readText(): String? {
        val clip = clipboard?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
    }

    override fun addListener(listener: ClipboardManager.OnPrimaryClipChangedListener) {
        clipboard?.addPrimaryClipChangedListener(listener)
    }

    override fun removeListener(listener: ClipboardManager.OnPrimaryClipChangedListener) {
        clipboard?.removePrimaryClipChangedListener(listener)
    }
}

// ---------------------------------------------------------------------------
// The stateful page (hosted in a 31 page sheet)
// ---------------------------------------------------------------------------

/**
 * The stateful Pair page body hosted in the sheet (android-ui-parity
 * #72): wires the [PairPageModel] to the clipboard, the camera feature,
 * the haptic and the sheet's [PageSheetScope]. Everything visual is
 * [PairPageContent] (stateless — the previews and goldens use it
 * directly).
 *
 * - **Live parse**: every field change re-parses the trimmed text off
 *   the main thread (`mapLatest` + `Dispatchers.Default`); parse
 *   failures show nothing while typing (errors come only from a submit).
 * - **Success hook**: `model.onPaired` fires [Haptic.Confirm] and
 *   dismisses the sheet — set only while the page is composed, so a
 *   background redeem persists without a haptic.
 */
@Composable
internal fun PairPage(
    model: PairPageModel,
    sheet: PageSheetScope,
    qrScan: QrScanRequest?,
    clipboard: PairClipboard,
) {
    val feedback = LocalFeedback.current
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val title = stringResource(R.string.pair_title)

    // The live parse of the trimmed field. `mapLatest` cancels the
    // previous parse on every keystroke; a blank field maps to null
    // without a parse. A `roboco-room:` invite parses to the room target
    // (ticket 04) — never both: the room parser refuses anything the
    // engine parser accepts, and vice versa.
    LaunchedEffect(model) {
        snapshotFlow { model.text.text.toString().trim() }
            .mapLatest { s ->
                if (s.isEmpty()) {
                    null to null
                } else {
                    withContext(Dispatchers.Default) {
                        model.parseRoomTarget(s) to model.parseTarget(s)
                    }
                }
            }
            .collect { (room, target) ->
                model.roomTarget = room
                model.target = if (room != null) null else target
            }
    }

    // The success hook: haptic + close, only while the page is shown.
    // 31's dismiss is idempotent, so the double-dismiss (35 tears every
    // sheet down on a root swap) is a no-op.
    DisposableEffect(model) {
        model.onPaired = {
            feedback.haptic(Haptic.Confirm)
            sheet.dismiss(animated = true)
        }
        onDispose { model.onPaired = null }
    }

    val hasClip = rememberClipboardHasText(clipboard)
    val showPaste = hasClip.value && model.text.text.isBlank() && model.phase !is PairPhase.Redeeming
    val cameraPresent = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    val showScan = qrScan != null && cameraPresent

    PairPageContent(
        text = model.text,
        target = model.target,
        room = model.roomTarget,
        phase = model.phase,
        showPaste = showPaste,
        showScan = showScan,
        onClose = { sheet.dismiss(true) },
        onPaste = {
            clipboard.readText()?.takeIf { it.isNotEmpty() }?.let { model.text.setTextAndPlaceCursorAtEnd(it) }
        },
        onScan = { if (model.phase !is PairPhase.Redeeming) qrScan?.scan(model::acceptScanned) },
        onSubmit = {
            // Behaviour step 1: not ignored → hide the IME and clear the
            // focus before the redeem starts.
            if (model.canSubmit) {
                keyboard?.hide()
                focusManager.clearFocus()
            }
            model.submit()
        },
        modifier = Modifier.semantics { paneTitle = title },
    )
}

/**
 * `clipboard.hasText()` as observable state: the initial read, a
 * `OnPrimaryClipChangedListener` (added/removed with the page), a
 * re-check on [Lifecycle.Event.ON_RESUME] and one whenever the window
 * regains focus — Android 10+ returns null from clipboard reads while
 * the window is unfocused, and ON_RESUME can arrive before focus, so
 * the focus re-check is what makes Paste appear after copying a link in
 * another app. Only `hasText()` here, never `readText()`.
 */
@Composable
internal fun rememberClipboardHasText(clipboard: PairClipboard): State<Boolean> {
    val hasText = remember { mutableStateOf(clipboard.hasText()) }
    val listener = remember {
        ClipboardManager.OnPrimaryClipChangedListener { hasText.value = clipboard.hasText() }
    }
    DisposableEffect(clipboard) {
        clipboard.addListener(listener)
        onDispose { clipboard.removeListener(listener) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasText.value = clipboard.hasText() }
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo) {
        snapshotFlow { windowInfo.isWindowFocused }
            .filter { it }
            .collect { hasText.value = clipboard.hasText() }
    }
    return hasText
}

// ---------------------------------------------------------------------------
// The stateless page
// ---------------------------------------------------------------------------

/**
 * The Pair page's UI (android-ui-parity #72): 17's 52 dp header row with
 * the ✕, a scrolling body (70's "Pairing link" section: the link-field
 * row, the Paste box, the Scan QR row, the hint/target/cleartext
 * footers and the error line) and the full-width "Pair engine" capsule
 * (08) outside the scroll, 24 dp from the sides and 24 dp above
 * `max(ime, navigationBars)`. State is injected; the previews, the
 * gallery and the goldens compose it directly. A `roboco-room:` invite
 * (ticket 04) swaps the target line for the room line and the capsule
 * for "Join room" — the field is the same one paste/scan fill.
 */
@Composable
fun PairPageContent(
    text: TextFieldState,
    target: PairingTarget?,
    room: RoomInviteTarget? = null,
    phase: PairPhase,
    showPaste: Boolean,
    showScan: Boolean,
    onClose: () -> Unit,
    onPaste: () -> Unit,
    onScan: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = RobocoTheme.palette
    Column(modifier.fillMaxSize().testTag("pair-page")) {
        SheetHeaderRow(
            title = stringResource(R.string.pair_title),
            leading = SheetLeading.Close,
            onLeading = onClose,
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            GroupedHeader(stringResource(R.string.pair_section))
            // 70's rows apply the 16 dp card inset themselves.
            PairLinkFieldRow(
                text = text,
                showPaste = showPaste,
                onPaste = onPaste,
                onSubmit = onSubmit,
                position = if (showScan) GroupedPosition.First else GroupedPosition.Single,
            )
            if (showScan) {
                GroupedRow(
                    title = stringResource(R.string.pair_scan),
                    position = GroupedPosition.Last,
                    icon = RobocoIcons.Camera,
                    accessories = listOf(GroupedAccessory.Disclosure),
                    testTag = settingsTag("pair-scan"),
                    onClick = onScan,
                )
            }
            GroupedFooter(
                stringResource(R.string.pair_hint),
                Modifier.testTag("pair-hint"),
            )
            room?.let {
                // Ticket 04: the room invite's confirm line — the name and
                // the member count, never a secret or an address.
                GroupedFooter(
                    stringResource(R.string.pair_room_target, it.name, it.memberLabels.size),
                    Modifier.testTag("pair-room-target"),
                )
            }
            target?.let {
                val expiresAtMs = it.expiresAtMs
                val line = if (it.tailcat && expiresAtMs != null) {
                    // android-next#02: the invite's code is short-lived;
                    // the countdown refreshes each minute.
                    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(expiresAtMs) {
                        while (true) {
                            nowMs = System.currentTimeMillis()
                            delay(INVITE_EXPIRY_TICK_MS)
                        }
                    }
                    stringResource(R.string.pair_target_tailcat, inviteExpiryLabel(expiresAtMs - nowMs))
                } else {
                    stringResource(R.string.pair_target, it.host)
                }
                GroupedFooter(line, Modifier.testTag("pair-target"))
            }
            // WHATWG lower-cases the scheme, so a plain prefix check is
            // enough. A Tailcat invite never shows it: WireGuard encrypts
            // the whole route.
            if (target?.tailcat != true && target?.baseUrl?.startsWith("http://") == true) {
                GroupedFooter(
                    stringResource(R.string.pair_cleartext),
                    Modifier.testTag("pair-cleartext"),
                    color = palette.warning,
                )
            }
            (phase as? PairPhase.Error)?.let { PairErrorLine(it.message) }
            // Content never touches the capsule area.
            Spacer(Modifier.height(16.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                // The host's content slot already applies imePadding (31);
                // the union then adds only the missing part, so the capsule
                // rides above max(ime, nav) either way.
                .windowInsetsPadding(
                    WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom),
                )
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
        ) {
            val busy = phase is PairPhase.Redeeming
            // A Tailcat open pings the engine first (up to ~20 s); a room
            // join dials every member (ticket 04).
            val busyLabel = when {
                room != null -> R.string.pair_room_submitting
                target?.tailcat == true -> R.string.pair_submitting_tailcat
                else -> R.string.pair_submitting
            }
            ProminentGlassButton(
                text = stringResource(
                    if (busy) busyLabel else if (room != null) R.string.pair_room_submit else R.string.pair_submit
                ),
                onClick = onSubmit,
                busy = busy,
                // Busy is not "off": clicks are ignored via busy, the
                // prominent look stays. Disabled only for a blank field.
                enabled = busy || text.text.isNotBlank(),
                contentPadding = ProminentGlassButtonDefaults.PairingPadding,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pair-submit"),
            )
        }
    }
}

/**
 * The error line: Geist Regular 14 `danger`, unlimited lines,
 * start-aligned in the footer column (x 32 … W − 32), 12 dp under the
 * last footer, a polite live region. Absent (not composed) unless the
 * phase is [PairPhase.Error]. It appears and disappears instantly —
 * iOS assigns `status.text` directly (`SignInViewController.swift:66`).
 *
 * A [PairMessage.Res.detail] adds a collapsed "Show details" toggle
 * (Geist Medium 13 `accent`) under the sentence; expanded, the reason
 * shows verbatim in Geist Mono 13 `secondary`, selectable for a bug
 * report. Each new error starts collapsed.
 */
@Composable
private fun PairErrorLine(message: PairMessage) {
    val copy = when (message) {
        is PairMessage.Res -> stringResource(message.id)
        is PairMessage.Verbatim -> message.text
    }
    val detail = (message as? PairMessage.Res)?.detail
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 12.dp)) {
        BasicText(
            copy,
            Modifier
                .fillMaxWidth()
                .testTag("pair-error")
                .semantics { liveRegion = LiveRegionMode.Polite },
            style = uiStyle(Face.Sans, typeScale.fixed(14f), palette.danger),
        )
        if (detail != null) {
            var expanded by remember(message) { mutableStateOf(false) }
            BasicText(
                stringResource(if (expanded) R.string.pair_error_details_hide else R.string.pair_error_details_show),
                Modifier
                    .padding(top = 6.dp)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .testTag("pair-error-details"),
                style = uiStyle(Face.SansMedium, typeScale.fixed(13f), palette.accent),
            )
            if (expanded) {
                SelectionContainer {
                    BasicText(
                        detail,
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .testTag("pair-error-detail"),
                        style = uiStyle(Face.Mono, typeScale.fixed(13f), palette.secondary),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The link-field row
// ---------------------------------------------------------------------------

/**
 * The "Pairing link" field row: a 70 [GroupedRowContainer] with custom
 * content — the single-line link field (URI keyboard, no autocorrect,
 * Go submits) and, while the field is blank and the clipboard holds
 * text, the trailing Paste box. No separator is drawn here: the
 * container draws it (in 70's system separator colour) from the field's
 * text start, 16 dp inside the card, to 16 before the card's trailing
 * edge — and none at all in the [GroupedPosition.Single] card.
 */
@Composable
private fun PairLinkFieldRow(
    text: TextFieldState,
    showPaste: Boolean,
    onPaste: () -> Unit,
    onSubmit: () -> Unit,
    position: GroupedPosition,
) {
    GroupedRowContainer(
        position = position,
        separatorStart = 16.dp,
        testTag = "pair-link-row",
        onClick = null,
    ) {
        PairLinkField(text, showPaste, onSubmit)
        if (showPaste) PairPasteBox(onPaste)
    }
}

/** The link field itself, weighted to fill the row up to the Paste box. */
@Composable
private fun RowScope.PairLinkField(
    text: TextFieldState,
    showPaste: Boolean,
    onSubmit: () -> Unit,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BasicTextField(
        state = text,
        modifier = Modifier
            .weight(1f)
            .padding(start = 16.dp, end = if (showPaste) 0.dp else 16.dp)
            .semantics { contentDescription = "Pairing link" }
            .testTag("pair-url"),
        textStyle = uiStyle(Face.Sans, typeScale.fixed(16f), palette.text),
        // The caret and the handles; the selection wash comes from
        // RobocoTheme's LocalTextSelectionColors.
        cursorBrush = SolidColor(palette.accent),
        lineLimits = TextFieldLineLimits.SingleLine,
        inputTransformation = StripLineBreaks,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        onKeyboardAction = KeyboardActionHandler { onSubmit() },
        decorator = TextFieldDecorator { inner ->
            Box {
                if (text.text.isEmpty()) {
                    BasicText(
                        stringResource(R.string.pair_placeholder),
                        Modifier.clearAndSetSemantics { },
                        style = uiStyle(Face.Sans, typeScale.fixed(16f), palette.tertiary),
                        maxLines = 1,
                    )
                }
                inner()
            }
        },
    )
}

/**
 * The trailing Paste box: full row height (a 52 dp box covers the
 * single-line row), 16 dp horizontal padding around the word so its
 * trailing edge sits 16 dp inside the card, the whole box the click
 * target. Geist Medium 15 `accent`, pressed at 0.35 alpha (the UIKit
 * `.system` highlighted approximation; tune in the gallery). The paste
 * tap reads the clip (the system toast is expected) and never submits.
 */
@Composable
private fun PairPasteBox(onPaste: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .heightIn(min = GroupedListTokens.RowMinHeight)
            .clickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClick = onPaste,
            )
            .padding(horizontal = 16.dp)
            .testTag("pair-paste"),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            stringResource(R.string.pair_paste),
            style = uiStyle(
                Face.SansMedium,
                typeScale.fixed(15f),
                if (pressed) palette.accent.copy(alpha = 0.35f) else palette.accent,
            ),
        )
    }
}

/**
 * Deletes `\r` and `\n` from every input change, so a pasted multi-line
 * selection becomes one line (the field is single-line; the strip keeps
 * the text and the buffer consistent without the IME inserting its own
 * newline handling).
 */
private object StripLineBreaks : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        var index = 0
        while (index < length) {
            if (charAt(index) == '\r' || charAt(index) == '\n') {
                delete(index, index + 1)
            } else {
                index++
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Previews (ticket step 9): the five states, light and dark, 393 × 852
// ---------------------------------------------------------------------------

/** One preview fixture: the field text, the parse target(s), the phase, Paste. */
private data class PairPagePreview(
    val text: String,
    val target: PairingTarget?,
    val phase: PairPhase,
    val showPaste: Boolean,
    val room: RoomInviteTarget? = null,
)

private val PairPagePreviewEmpty = PairPagePreview(
    text = "",
    target = null,
    phase = PairPhase.Idle,
    showPaste = true,
)

private val PairPagePreviewHttp = PairPagePreview(
    text = "http://192.168.1.10:27655/pair#token=abc",
    target = PairingTarget(baseUrl = "http://192.168.1.10:27655", host = "192.168.1.10:27655"),
    phase = PairPhase.Idle,
    showPaste = false,
)

private val PairPagePreviewHttps = PairPagePreview(
    text = "https://engine.example.ts.net/pair#token=abc",
    target = PairingTarget(baseUrl = "https://engine.example.ts.net", host = "engine.example.ts.net"),
    phase = PairPhase.Idle,
    showPaste = false,
)

private val PairPagePreviewRedeeming = PairPagePreviewHttp.copy(phase = PairPhase.Redeeming)

private val PairPagePreviewError = PairPagePreviewHttp.copy(
    phase = PairPhase.Error(PairMessage.Res(R.string.pair_error_refused)),
)

@Composable
private fun PairPagePreviewBody(appearance: Appearance, state: PairPagePreview) {
    val field = remember { TextFieldState(state.text) }
    RobocoTheme(appearance) {
        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
            PairPageContent(
                text = field,
                target = state.target,
                room = state.room,
                phase = state.phase,
                showPaste = state.showPaste,
                showScan = true,
                onClose = {},
                onPaste = {},
                onScan = {},
                onSubmit = {},
            )
        }
    }
}

@Preview(name = "Pair page empty light", widthDp = 393, heightDp = 852)
@Composable
private fun PairPageEmptyLightPreview() {
    PairPagePreviewBody(Appearance.Light, PairPagePreviewEmpty)
}

@Preview(name = "Pair page empty dark", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PairPageEmptyDarkPreview() {
    PairPagePreviewBody(Appearance.Dark, PairPagePreviewEmpty)
}

@Preview(name = "Pair page http light", widthDp = 393, heightDp = 852)
@Composable
private fun PairPageHttpLightPreview() {
    PairPagePreviewBody(Appearance.Light, PairPagePreviewHttp)
}

@Preview(name = "Pair page http dark", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PairPageHttpDarkPreview() {
    PairPagePreviewBody(Appearance.Dark, PairPagePreviewHttp)
}

@Preview(name = "Pair page https light", widthDp = 393, heightDp = 852)
@Composable
private fun PairPageHttpsLightPreview() {
    PairPagePreviewBody(Appearance.Light, PairPagePreviewHttps)
}

@Preview(name = "Pair page https dark", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PairPageHttpsDarkPreview() {
    PairPagePreviewBody(Appearance.Dark, PairPagePreviewHttps)
}

/** Ticket 04: a `roboco-room:` invite parsed — the room line + capsule. */
private val PairPagePreviewRoom = PairPagePreview(
    text = "roboco-room:eyJ2IjoxLCJuYW1lIjoiSG9tZSBmbGVldCIsInNlY3JldCI6ImV4YW1wbGUiLCJtZW1iZXJzIjpbXX0",
    target = null,
    phase = PairPhase.Idle,
    showPaste = false,
    room = RoomInviteTarget(name = "Home fleet", memberLabels = listOf("desktop", "server")),
)

@Preview(name = "Pair page room light", widthDp = 393, heightDp = 852)
@Composable
private fun PairPageRoomLightPreview() {
    PairPagePreviewBody(Appearance.Light, PairPagePreviewRoom)
}

@Preview(name = "Pair page room dark", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PairPageRoomDarkPreview() {
    PairPagePreviewBody(Appearance.Dark, PairPagePreviewRoom)
}

@Preview(name = "Pair page redeeming light", widthDp = 393, heightDp = 852)
@Composable
private fun PairPageRedeemingLightPreview() {
    PairPagePreviewBody(Appearance.Light, PairPagePreviewRedeeming)
}

@Preview(name = "Pair page redeeming dark", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PairPageRedeemingDarkPreview() {
    PairPagePreviewBody(Appearance.Dark, PairPagePreviewRedeeming)
}

@Preview(name = "Pair page error light", widthDp = 393, heightDp = 852)
@Composable
private fun PairPageErrorLightPreview() {
    PairPagePreviewBody(Appearance.Light, PairPagePreviewError)
}

@Preview(name = "Pair page error dark", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PairPageErrorDarkPreview() {
    PairPagePreviewBody(Appearance.Dark, PairPagePreviewError)
}

/** How often a Tailcat invite's countdown refreshes (android-next#02). */
internal const val INVITE_EXPIRY_TICK_MS = 60_000L

/**
 * A Tailcat invite's remaining time for `pair_target_tailcat`: whole
 * minutes, rounded up ("5 min"), or "under a minute" at the end (and once
 * past it — the engine re-checks on redeem).
 */
internal fun inviteExpiryLabel(remainingMs: Long): String =
    if (remainingMs < 60_000L) "under a minute" else "${(remainingMs + 59_999L) / 60_000L} min"
