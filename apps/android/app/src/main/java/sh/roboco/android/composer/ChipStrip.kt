package sh.roboco.android.composer

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import sh.roboco.android.design.FadingHorizontalScroll
import sh.roboco.android.design.glass.rememberGlassMenuState

/**
 * The composer toolbar's chip row (android-ui-parity #59), the port of
 * iOS's `chipScroll` + `chipStrip` (`ios:Composer/ComposerBar.swift:97-99,
 * 188-195, 240-248`): a 34 dp tall, horizontally scrolling row of
 * [ContextChip]s 6 dp apart, each centred at its intrinsic height, with
 * **no content inset** (the first chip is flush with the strip's start)
 * and the 24 dp edge fades of ticket 10's [FadingHorizontalScroll].
 *
 * Ticket 55's custom `Layout` sizes the slot (`attach.end + 8` …
 * `action.start − 8`) and applies the capsule↔card alpha and band clip in
 * its own placement layer — this composable never sets an alpha. A tap on
 * a chip opens its menu through ticket 15's [rememberGlassMenuState]
 * anchored to the chip's window bounds (the provider runs at tap time,
 * never during composition); a chip without a menu — or whose provider
 * returns `null` — calls [onChipTap] instead, exactly UIKit's
 * `showsMenuAsPrimaryAction` split (`ComposerBar.swift:460-468`).
 *
 * `canWrite == false` (an offline engine, ticket 79): the chips render
 * disabled (ink `tertiary`, taps consumed but no-ops; the menu providers
 * are not invoked).
 *
 * @param chipMenus id → lazy menu provider; a `null` menu falls back to [onChipTap]
 * @param chipModifier the outermost modifier of **every chip** (56's
 *   `Modifier.composerControl(morph)`); it goes on the chips, not the
 *   strip, so a tap on the strip's empty space still reaches 56's
 *   tap-to-focus
 */
@Composable
fun ChipStrip(
    chips: List<ComposerChip>,
    chipMenus: Map<String, ChipMenuProvider>,
    canWrite: Boolean,
    onChipTap: (String) -> Unit,
    modifier: Modifier = Modifier,
    chipModifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
) {
    // Throws without a GlassMenuHost above — a strip that cannot open its
    // menus is a wiring bug (ticket 15's rule). Previews, gallery sections
    // and tests wrap their content in GlassMenuHost.
    val menu = rememberGlassMenuState()
    FadingHorizontalScroll(
        modifier = modifier
            .height(ChipTokens.StripHeight)
            .testTag("composer-chip-strip"),
        state = state,
        horizontalArrangement = Arrangement.spacedBy(ChipTokens.Spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEach { chip ->
            // iOS rebuilds the whole stack when `chips` changes; Compose's
            // key(chip.id) gives the same identity, nothing is animated,
            // and the scroll position survives (ScrollState clamps it if
            // the content shrinks).
            key(chip.id) {
                var bounds by remember { mutableStateOf(Rect.Zero) }
                ContextChip(
                    chip = chip,
                    enabled = canWrite,
                    // Only the a11y click label knows about the menu; the
                    // provider itself runs at tap time (containsKey only).
                    hasMenu = canWrite && chipMenus.containsKey(chip.id),
                    modifier = chipModifier.onGloballyPositioned { bounds = it.boundsInWindow() },
                    onClick = {
                        val target = chipMenus[chip.id]?.invoke()
                        if (target != null) {
                            // Anchored to the visible part (boundsInWindow is
                            // clipped to the strip's viewport), like UIKit's
                            // menu anchored to the button's frame.
                            menu.open(bounds, target)
                        } else {
                            onChipTap(chip.id)
                        }
                    },
                )
            }
        }
    }
}
