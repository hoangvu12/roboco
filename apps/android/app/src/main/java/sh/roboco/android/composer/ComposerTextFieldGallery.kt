package sh.roboco.android.composer

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.NoFeedback
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.hairlineBorder
import sh.roboco.android.design.uiStyle

/**
 * The "Composer text field" page of the debug design gallery
 * (android-ui-parity #57), registered by ticket 22's registry as
 * `GallerySection("Composer text field") { ComposerTextFieldGallerySection() }`.
 *
 * Lives in the main source set on purpose (same as 53's
 * `MentionsGallerySection` / 59's `ChipStripGallerySection`): the
 * `@Preview`s and the Roborazzi goldens use the same fixtures.
 *
 * Stacks (12 dp gaps), over `Palette.background`:
 *
 * - **(a)** capsule, empty, placeholder `Message Claude Code`;
 * - **(b)** capsule, `Ship it`;
 * - **(c)** capsule with the iOS UI-test draft — it wraps to 5 lines at
 *   268 dp, so the preview shows 2 whole lines + `…` (#593);
 * - **(d)** card with the same draft (full; 4 lines at 338 dp);
 * - **(e)** card with 12 numbered lines (capped at `20 + 8 × L`: 191.6 on
 *   iOS, 196 at mdpi; scrolled to the top);
 * - **(f)** a live, interactive fixture: `isCard = focused`, the slot
 *   width follows it (268 ↔ 338), a non-Material "Blur" text button, and
 *   the `keyboardSend` log line below — try the 180 ms growth and
 *   Ctrl+Enter there.
 *
 * The hairline frame is gallery-only: it shows the slot rect, nothing
 * draws it in production (the glass is 55's).
 */

/**
 * The iOS UI-test draft (SessionFlowTests.swift:66), verbatim: at 268 dp
 * it wraps onto 5 lines, at 338 dp onto 4.
 */
internal val ComposerUIDraft =
    "let's fix the new worktree icon it makes no sense, also let's make it so that " +
        "there's no descriptions the line is too long and wraps onto a fourth line"

/** Fixture (e): 12 numbered lines, capped by the card's 8-line limit. */
internal val ComposerTwelveLines: String = (1..12).joinToString("\n") { "line $it" }

/**
 * One static fixture: [ComposerTextField] alone in a slot-width box with
 * the gallery's hairline frame, on the page background. A
 * non-suspending [ComposerController] (NoFeedback); its own
 * [FocusRequester] — requested once when [focused].
 */
@Composable
fun ComposerTextFieldFixture(
    text: String,
    isCard: Boolean,
    slotWidth: Dp,
    focused: Boolean = false,
    placeholder: String = "Message",
) {
    val scope = rememberCoroutineScope()
    val controller = remember(text, placeholder) {
        val c = ComposerController(scope, NoFeedback, text)
        c.placeholder = placeholder
        c
    }
    val requester = remember { FocusRequester() }
    if (focused) {
        LaunchedEffect(requester) { runCatching { requester.requestFocus() } }
    }
    Box(Modifier.width(slotWidth).hairlineBorder(RobocoTheme.palette.hairline, RectangleShape)) {
        ComposerTextField(
            controller = controller,
            isCard = isCard,
            focusRequester = requester,
        )
    }
}

/**
 * Stages (a)–(e) — the static half, shared by the section, the previews
 * and the Roborazzi goldens (no interaction, so nothing animates under
 * capture).
 */
@Composable
internal fun ComposerTextFieldGalleryStages() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ComposerTextFieldFixture(text = "", isCard = false, slotWidth = 268.dp, placeholder = "Message Claude Code")
        ComposerTextFieldFixture(text = "Ship it", isCard = false, slotWidth = 268.dp)
        ComposerTextFieldFixture(text = ComposerUIDraft, isCard = false, slotWidth = 268.dp)
        ComposerTextFieldFixture(text = ComposerUIDraft, isCard = true, slotWidth = 338.dp)
        ComposerTextFieldFixture(text = ComposerTwelveLines, isCard = true, slotWidth = 338.dp)
    }
}

/** The section: stages (a)–(e), then the live fixture (f). */
@Composable
fun ComposerTextFieldGallerySection() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ComposerTextFieldGalleryStages()
        ComposerTextFieldLiveFixture()
    }
}

/**
 * Fixture (f): the live one. `isCard` follows the field's focus (the
 * production rule is 56's `wantsCard`; text alone never holds the card),
 * the slot width follows the mode (268 ↔ 338 — the slots of a 370 dp
 * composer), "Blur" clears the focus (a foundation `BasicText` +
 * `clickable`, never a Material button), and every `keyboardSend` —
 * Ctrl+Enter — logs a line below the field.
 */
@Composable
private fun ComposerTextFieldLiveFixture() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val sendLog = remember { mutableStateOf("Ctrl+Enter logs keyboardSend here") }
    val controller = remember {
        ComposerController(scope, NoFeedback, "").apply {
            onSend = { body, _, _ ->
                sendLog.value = "keyboardSend: \"$body\""
                SendResult.Sent
            }
        }
    }
    var focused by remember { mutableStateOf(false) }
    val slotWidth = if (focused) 338.dp else 268.dp

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(slotWidth).hairlineBorder(palette.hairline, RectangleShape)) {
            ComposerTextField(
                controller = controller,
                isCard = focused,
                focusRequester = remember { FocusRequester() },
                onFocusChange = { focused = it },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BasicText(
                "Blur",
                style = uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.accent),
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = { focusManager.clearFocus() },
                ),
            )
            BasicText(
                sendLog.value,
                style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "Composer text field light")
@Composable
private fun ComposerTextFieldPreviewLight() {
    RobocoTheme(Appearance.Light) {
        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background).padding(16.dp)) {
            ComposerTextFieldGalleryStages()
        }
    }
}

@Preview(name = "Composer text field dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ComposerTextFieldPreviewDark() {
    RobocoTheme(Appearance.Dark) {
        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background).padding(16.dp)) {
            ComposerTextFieldGalleryStages()
        }
    }
}
