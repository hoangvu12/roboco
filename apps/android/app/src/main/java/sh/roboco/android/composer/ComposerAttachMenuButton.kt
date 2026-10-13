package sh.roboco.android.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import sh.roboco.android.design.glass.rememberGlassMenuState

/**
 * The composer's attach `+` as the attach-menu anchor (android-ui-parity
 * #87): ticket 55's [ComposerAttachButton] wired to ticket 15's
 * [rememberGlassMenuState] — the port of
 * `ios:Composer/ComposerBar.swift:146-161`
 * (`attachButton.showsMenuAsPrimaryAction = true` plus the uncached
 * deferred menu).
 *
 * A single **tap** opens the pull-down menu (`showsMenuAsPrimaryAction`):
 * the menu is built **inside the tap handler** — never during
 * composition — so the camera row, the clipboard row and the photo limit
 * (`8 − images.count`) reflect the moment of the open, exactly as iOS's
 * `UIDeferredMenuElement.uncached` + synchronous `done` rebuilds the
 * content on every open with no "Loading…" row. The anchor is the
 * button's own window-space bounds from [onGloballyPositioned], read at
 * tap time (the latest placement, like UIKit's frame).
 *
 * Upstream PR #714's rules, met with no system transition: the host is
 * ticket 15's in-window [sh.roboco.android.design.glass.GlassMenuHost]
 * (never a `Popup` or dialog — the IME and the field's focus are
 * untouched), and the [Modifier.composerControl] marker keeps the glass's
 * tap-to-focus from eating the tap, so opening the menu neither focuses
 * the text field, nor morphs the capsule into the card, nor moves the
 * composer. Nothing here calls `focus`, `clearFocus` or touches
 * [ComposerController] state on open or close; the dismissing tap is
 * consumed by the menu's catcher and never reaches the transcript.
 *
 * [enabled][ComposerController.attachEnabled] is the Roboco gate (83:
 * `false` while a queued edit is open): the button is ticket 55's
 * disabled state — no menu, no press look, `Palette.tertiary` glyph. The
 * same button serves the session composer (78) and the new-session
 * canvas (93), both through [ComposerSurface]'s `attach` slot with
 * `limit = 8 − images.size` at each open.
 *
 * [controller.attachMenu] (ticket 54's hook, iOS `composer.attachMenu`)
 * overrides the default menu when a host sets it; no host in this set
 * does.
 */
@Composable
fun ComposerAttachMenuButton(
    controller: ComposerController,
    morph: ComposerMorphState,
    modifier: Modifier = Modifier,
) {
    ComposerAttachMenuButton(controller, morph, modifier, stager = null)
}

/**
 * The implementation, with the test seam: [stager] `null` = the real
 * [ImageStaging.stageAll] over this context's resolver; the Robolectric
 * behaviour tests inject a fake through this overload so they observe
 * the batch and control its delivery.
 */
@Composable
internal fun ComposerAttachMenuButton(
    controller: ComposerController,
    morph: ComposerMorphState,
    modifier: Modifier = Modifier,
    stager: (suspend (List<PickedImage>, Int) -> List<StagedImage>)?,
) {
    val menuState = rememberGlassMenuState()
    val picker = rememberAttachPicker(
        limit = { ComposerController.MaxImages - controller.images.size },
        deliver = { if (controller.attachEnabled) controller.addImages(it) },
        stager = stager,
    )
    var anchor by remember { mutableStateOf(Rect.Zero) }
    ComposerAttachButton(
        onClick = { menuState.open(anchor, controller.attachMenu?.invoke() ?: picker.menu()) },
        enabled = controller.attachEnabled,
        modifier = modifier
            // Ticket 56: the marker sits outermost of the button's own
            // chrome so its bounds equal the on-screen control and the
            // glass's tap-to-focus ignores taps on `+`.
            .composerControl(morph)
            .onGloballyPositioned { anchor = it.boundsInWindow() },
    )
}
