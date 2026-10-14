package sh.roboco.android.composer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect

/**
 * The public composer assembly (android-ui-parity #78, step 6) — the one
 * composable a host composes to get a whole composer: ticket 55's
 * [ComposerSurface] with every slot wired to its owner ticket's part
 * around ticket 54's [ComposerController]:
 *
 * - **thumbnails** — 88's [StagedThumbnailStrip]: the staged-photo strip
 *   (68 dp when images are staged, 0 otherwise, removed with a 250 ms
 *   slide/collapse), hidden while `attachEnabled` is false (a queued
 *   edit), each x carrying [Modifier.composerControl] so it never
 *   focuses the field;
 * - **text** — 57's [ComposerTextField]: one `BasicTextField` for both the
 *   capsule and the card, carrying 56's focus requester through
 *   [Modifier.composerInput];
 * - **attach** — 87's [ComposerAttachMenuButton] (the `+` and its
 *   pull-down; it applies [Modifier.composerControl] itself);
 * - **chips** — 59's [ChipStrip], each chip carrying
 *   [Modifier.composerControl] (the strip itself never does, so a tap on
 *   the empty space still reaches 56's tap-to-focus);
 * - **action** — 58's controller overload ([ComposerActionButton]): it
 *   routes through 54's `primaryAction()` / `send(mode)`, so the Stop and
 *   Confirm haptics fire exactly once — never pass `onStop` to it
 *   directly;
 * - **mentions** — 86's [rememberMentionSuggestionsState] +
 *   [MentionSuggestionsBinding], with [Modifier.mentionAnchor] on the
 *   same glass node as [Modifier.composerMorphHost].
 *
 * The host owns the controller and the morph state (78's
 * `rememberSessionComposer` for the session screen, 93's canvas for a new
 * session), so a retained controller survives re-composition. The
 * surface's width comes from the caller (74's stack slot, 93's canvas);
 * the placeholder is 57's (it draws `controller.placeholder`).
 *
 * Ticket 94 added three pass-through parameters for the New Session
 * canvas's handoff capture — `textHidden` and `onTextRect` forward to
 * 57's [ComposerTextField], `chipsCanWrite` overrides the chips' write
 * gate (59's [ChipStrip]) — all defaulted to today's behaviour, so the
 * session composer is unchanged.
 */
@Composable
fun ComposerBar(
    controller: ComposerController,
    morph: ComposerMorphState,
    modifier: Modifier = Modifier,
    /** 94: hide the text layer for the handoff capture — 57's `textHidden` (iOS `textView.alpha = 0`). */
    textHidden: Boolean = false,
    /** 94: the text rect in root px — 57's `onTextRect` (94's capture reads it). */
    onTextRect: ((Rect) -> Unit)? = null,
    /**
     * 94: the `canWrite` the chips get (59's param). The session composer
     * keeps the controller's value; the New Session canvas passes `true`
     * so the project and host chips stay live while Send is gated on an
     * offline engine (the user must be able to move off it).
     */
    chipsCanWrite: Boolean = controller.canWrite,
) {
    // 86: the mention card's state and the screen-side binding (the
    // observers, the host registration and the debounced search over
    // controller.mentionSearch). The anchor modifier below lands on the
    // glass node itself.
    val mentionUi = rememberMentionSuggestionsState(controller)
    MentionSuggestionsBinding(mentionUi, isFocused = { morph.isFocused })

    ComposerSurface(
        progress = { morph.progress },
        modifier = modifier.composerMorphHost(morph).mentionAnchor(mentionUi),
        thumbnails = {
            StagedThumbnailStrip(
                images = controller.images,
                onRemove = { controller.removeImage(it) },
                visible = controller.attachEnabled,
                controlModifier = Modifier.composerControl(morph),
            )
        },
        text = {
            ComposerTextField(
                controller = controller,
                isCard = morph.isCard,
                focusRequester = morph.focusRequester,
                modifier = Modifier.composerInput(morph),
                textHidden = textHidden,
                onTextRect = onTextRect,
            )
        },
        attach = { ComposerAttachMenuButton(controller, morph) },
        chips = {
            ChipStrip(
                chips = controller.chips,
                chipMenus = controller.chipMenus,
                canWrite = chipsCanWrite,
                onChipTap = { controller.onChipTap(it) },
                chipModifier = Modifier.composerControl(morph),
            )
        },
        // 55: the chips go dead the instant the target mode flips, like
        // UIKit's model-alpha hit-testing.
        chipsInteractive = morph.isCard,
        action = { ComposerActionButton(controller, Modifier.composerControl(morph)) },
    )
}
