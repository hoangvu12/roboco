package sh.roboco.android.composer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The public composer assembly (android-ui-parity #78, step 6) — the one
 * composable a host composes to get a whole composer: ticket 55's
 * [ComposerSurface] with every slot wired to its owner ticket's part
 * around ticket 54's [ComposerController]:
 *
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
 * Ticket 88 (thumbnails) has not landed: the `thumbnails` slot stays at
 * its empty default and this line is where 88 swaps
 * [StagedThumbnailStrip] in.
 */
@Composable
fun ComposerBar(
    controller: ComposerController,
    morph: ComposerMorphState,
    modifier: Modifier = Modifier,
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
        text = {
            ComposerTextField(
                controller = controller,
                isCard = morph.isCard,
                focusRequester = morph.focusRequester,
                modifier = Modifier.composerInput(morph),
            )
        },
        attach = { ComposerAttachMenuButton(controller, morph) },
        chips = {
            ChipStrip(
                chips = controller.chips,
                chipMenus = controller.chipMenus,
                canWrite = controller.canWrite,
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
