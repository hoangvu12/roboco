package sh.roboco.android.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
import sh.roboco.android.composer.ComposerBar
import sh.roboco.android.composer.ComposerController
import sh.roboco.android.composer.ComposerEditHost
import sh.roboco.android.composer.EditFinish
import sh.roboco.android.composer.EditRow
import sh.roboco.android.composer.FixtureQueueEditPort
import sh.roboco.android.composer.QueueAction
import sh.roboco.android.composer.QueueEditController
import sh.roboco.android.composer.QueuePanel
import sh.roboco.android.composer.SendResult
import sh.roboco.android.composer.rememberComposerMorphState
import sh.roboco.android.core.Face
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.session.BottomStackModel
import sh.roboco.android.session.SessionBottomStack
import sh.roboco.android.session.SessionChrome
import sh.roboco.android.session.StatusBanner
import sh.roboco.android.session.StatusPillRow
import sh.roboco.android.session.rememberBottomStackState
import sh.roboco.android.session.shownBanner

/**
 * The "Queue edit" page of the debug design gallery (android-ui-parity
 * #83), registered in ticket 22's registry as
 * `GallerySection("Queue edit", GalleryLayout.Fill) { QueueEditGallerySection() }`.
 *
 * The session bottom stack (74's container, 82's real queue panel, 78's
 * real composer bar, 73's real pill row) over a
 * [FixtureQueueEditPort] with the ticket's two rows (`q1` / `q2`) and the
 * draft `"my draft"`, with `renewEveryMs = 3_000` — **never 0 ms** (a
 * zero interval would spin the main thread while the renew succeeds).
 * The queue rows' `Editing` gate follows the live `editingId`, and the
 * composer's send commits an open edit (78's `onSend` step 1 shape:
 * `interceptSend(body) ?: …`).
 *
 * Buttons (the ticket's list): **Edit row 1 / Edit row 2** begin (or
 * switch) an edit — the draft is stashed, the row's text lands in the
 * composer, `+` greys out, the pill reads `Editing queued message · Tap
 * to cancel` (tap it to cancel). **Lose lease** flips `renewOk = false`
 * — the next tick (within 3 s) loses the lease: the edited text merges
 * in front of the draft and the row stays gated (the engine turns it
 * `Needs review` on its own). **Fail commit (conflict)** flips
 * `finishOutcome = Conflict` — the next send-from-edit commits, the
 * "engine" refuses, and the edited text merges back into the composer
 * with the conflict copy (through the real [StatusBanner]'s sticky
 * slot; the next acquired edit clears it). **Cancel** cancels the open
 * edit. The mono log line shows the live state.
 *
 * The pill slot here is the gallery's own stand for 73's production
 * wiring (`shownBanner(chrome.banner, editing)`), and the banner is a
 * real [StatusBanner] — the same class [ComposerEditHost] reports
 * recovery copy into. No nav entry in the gallery: the controller and
 * the host live with the composition.
 */
@Composable
fun QueueEditGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val scope = rememberCoroutineScope()
    val alerts = LocalAlerts.current
    val keyboard = LocalSoftwareKeyboardController.current
    val normalPlaceholder = "Message the agent"

    val port = remember {
        FixtureQueueEditPort(
            rows = linkedMapOf(
                "q1" to EditRow(raw = "fix the build", visible = "fix the build"),
                "q2" to EditRow(raw = "what's wrong here?", visible = "what's wrong here?"),
            ),
        )
    }
    val banner = remember { StatusBanner(retryDelivery = {}) }
    val composer = remember(scope) {
        ComposerController(scope, NoFeedback).apply {
            text = "my draft" // the user's own draft, stashed by the first edit
            placeholder = normalPlaceholder
        }
    }
    val morph = rememberComposerMorphState(alwaysCard = false, hasImages = { composer.images.isNotEmpty() })

    // The host and the controller: gallery-lifetime (the composition's
    // own scope — no nav entry here). The host's composition-scoped half
    // is refreshed every recomposition, exactly like 83's production
    // wiring.
    val host = remember {
        ComposerEditHost(
            composer = composer,
            morph = morph,
            keyboard = { null },
            alerts = { Alerts.Noop },
            banner = banner,
            chromePlaceholder = { normalPlaceholder },
        )
    }
    val edit = remember { QueueEditController(port, host, scope, scope, renewEveryMs = 3_000L) }

    // The send hook (78's step 1 shape): while an edit is open, sending
    // commits it (Replaced — the stash was just restored into the
    // composer, so nothing clears); otherwise the gallery's stand-in
    // send clears like a real one.
    remember(edit) {
        composer.onSend = { body, _, _ -> edit.interceptSend(body) ?: SendResult.Sent }
    }

    SideEffect {
        host.refresh(
            morph = morph,
            keyboard = { keyboard },
            alerts = { alerts },
            chromePlaceholder = { normalPlaceholder },
        )
        banner.onEditingTap = { edit.cancelEdit() }
    }

    val editingId = edit.editingId
    val items = listOf(
        SessionChrome.QueuedItem(id = "q1", text = "fix the build", gate = if (editingId == "q1") "Editing" else null),
        SessionChrome.QueuedItem(id = "q2", text = "what's wrong here?", gate = if (editingId == "q2") "Editing" else null),
    )
    val chrome = SessionChrome(placeholder = normalPlaceholder, queue = items)

    val menuHaze = rememberHazeState()
    GlassMenuHost(hazeState = menuHaze) {
        Column(Modifier.fillMaxSize().background(palette.background)) {
            Box(Modifier.fillMaxWidth().height(560.dp)) {
                ProvideHazeState(menuHaze) {
                    SessionBottomStack(
                        state = rememberBottomStackState(),
                        model = BottomStackModel.from(chrome, editing = edit.isEditing),
                        transcript = { GalleryQueueEditTranscript() },
                        pill = {
                            // 73's display overlay: while editing, the pill
                            // shows Editing over every chrome banner, and it
                            // appears and disappears instantly (the
                            // non-structural rule).
                            StatusPillRow(
                                shownBanner(chrome.banner, edit.isEditing),
                                onTap = banner::onTap,
                            )
                        },
                        queue = {
                            QueuePanel(
                                items = items,
                                glassVisible = glassVisible,
                                animateGlass = animateGlass,
                                onAction = { id, action ->
                                    if (action == QueueAction.Edit) edit.beginEdit(id)
                                },
                            )
                        },
                        questions = {},
                        composer = { ComposerBar(composer, morph, Modifier.fillMaxWidth()) },
                        jump = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QueueEditChip("queue-edit-edit-1", "Edit row 1") { edit.beginEdit("q1") }
                QueueEditChip("queue-edit-edit-2", "Edit row 2") { edit.beginEdit("q2") }
                QueueEditChip("queue-edit-lose-lease", "Lose lease") { port.renewOk = false }
                QueueEditChip("queue-edit-fail-commit", "Fail commit (conflict)") {
                    port.finishOutcome = EditFinish.Conflict
                }
                QueueEditChip("queue-edit-cancel", "Cancel") { edit.cancelEdit() }
            }
            BasicText(
                if (editingId != null) {
                    "editing $editingId · stashed draft: \"${edit.stashedDraftOrNull}\""
                } else {
                    "idle · composer: \"${composer.text}\""
                },
                style = uiStyle(Face.Mono, typeScale.fixed(12f), palette.secondary),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

/** The transcript stand-in behind the stack, so the glass and the fade have content to sample. */
@Composable
private fun GalleryQueueEditTranscript() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(6) { index ->
            BasicText(
                "Transcript line ${index + 1} — the turn's earlier rows run behind the stack.",
                style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.secondary),
            )
        }
    }
}

/** One gallery chip: a clickable BasicText in accent, tagged for the golden test. No Material controls. */
@Composable
private fun QueueEditChip(tag: String, text: String, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BasicText(
        text,
        style = uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.accent),
        modifier = Modifier
            .testTag(tag)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
