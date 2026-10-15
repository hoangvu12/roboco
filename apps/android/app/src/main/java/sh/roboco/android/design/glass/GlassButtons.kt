package sh.roboco.android.design.glass

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.GlassStage
import sh.roboco.android.design.Icon
import sh.roboco.android.design.LocalIsDark
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoIcon
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.rgb
import sh.roboco.android.design.uiStyle

/**
 * The `testTag` of [ProminentGlassButton]'s busy spinner
 * (`ActivitySpinner(Modifier.size(20.dp).testTag(…))`).
 */
internal const val BusySpinnerTag = "prominent-busy-spinner"

/**
 * Defaults shared by the glass buttons (ticket 08).
 */
object GlassButtonDefaults {

    /**
     * The measured iOS 26 glass bar-item ink (decision SESSIONS-D1): light
     * `#19191B`, dark `#F4F4F4`. UIKit adapts bar-item ink on glass; Compose
     * has no such system step, so this is the default ink of every glass
     * bar item (nav bars, sheet headers, the tablet toolbar). The
     * `Glass.circleButton` ports (jump to latest, image viewer, scanner ✕)
     * pass `ink = RobocoTheme.palette.text`, which the Swift sets itself.
     *
     * Reads [LocalIsDark] (the resolved appearance), not
     * `isSystemInDarkTheme()`.
     */
    @Composable
    @ReadOnlyComposable
    fun barGlyphInk(): Color = if (LocalIsDark.current) rgb(0xF4F4F4) else rgb(0x19191B)
}

// ---------------------------------------------------------------------------
// GlassCircleButton — the round glass icon button (ios:Design/Glass.swift:38-51)
// ---------------------------------------------------------------------------

/**
 * A round Liquid Glass icon button (ticket 08), the port of iOS
 * `Glass.circleButton` (`ios:Design/Glass.swift:38-51`): a capsule-cornered
 * square frame, so a **circle** of [size] dp, with a centred SF-symbol
 * glyph at [iconPt] points and [weight] (ticket 06's [Icon], inked [ink]).
 * It also stands in for the system-drawn iOS 26 nav-bar glass items — ⋯
 * menus, sheet close ✕, the back chevron — and for the prominent filled
 * toolbar `+` via [filled].
 *
 * - `filled == null` (glass): ticket 07's [GlassSurface] with
 *   `interactive = enabled`, sharing this button's `clickable`
 *   interaction source — 07 draws the press swell and highlight. Never
 *   stack [Modifier.glassPress] on it (double press).
 * - `filled != null` (opaque, e.g. the tablet sidebar's accent `+` and the
 *   sheets' ✓): the flat fill behind the glyph, and the press from
 *   [Modifier.glassPress] with the ink @0.12 highlight. No blur, rim or
 *   shadow — the fill is opaque.
 * - Disabled: glyph in `palette.tertiary`, no press, no click.
 * - No ripple ever (`indication = null`), no haptics (iOS fires none here —
 *   action haptics belong to the owning ticket).
 *
 * [contentDescription] is required for the glyph label; pass `null` only
 * when a parent merges its own label. [interactionSource] lets callers
 * observe or drive the press externally (the gallery's "hold to press"
 * toggle); a new source is remembered otherwise. Ticket 131's
 * [interactive] flag detaches all pointer input (the hiding jump button:
 * UIKit's model-value hit testing) — no `clickable`, no press, while the
 * glass and the ink stay as they are (unlike `enabled = false`, which
 * re-inks tertiary).
 *
 * @param size the frame, square; the capsule corner style makes it a circle
 * @param iconPt the SF Symbol point size passed verbatim to [Icon]
 * @param weight the SF weight — Medium (iOS `Glass.swift:41`) by default,
 *   Semibold for the system back chevron
 * @param filled null = glass; non-null = an opaque fill colour (the tablet
 *   `+` passes `palette.accent`, the sheets' ✓ too)
 * @param ink the glyph colour; the default is the measured iOS 26 glass
 *   bar-item ink ([GlassButtonDefaults.barGlyphInk], SESSIONS-D1), the
 *   `Glass.circleButton` ports pass `RobocoTheme.palette.text`, filled
 *   circles pass white
 * @param interactive `false` = no pointer input at all: no `clickable`, no
 *   press, nothing forwarded to the inner [GlassSurface]'s press tracker
 *   (ticket 131; default `true` — every existing caller is unaffected)
 */
@Composable
fun GlassCircleButton(
    icon: RobocoIcon,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconPt: Float = 17f,
    weight: SymbolWeight = SymbolWeight.Medium,
    filled: Color? = null,
    ink: Color = if (filled != null) Color.White else GlassButtonDefaults.barGlyphInk(),
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    interactive: Boolean = true,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val palette = RobocoTheme.palette
    val glyphInk = if (enabled) ink else palette.tertiary
    Box(
        modifier
            .size(size)
            .then(
                // Opaque variants press via glassPress; glass variants get
                // their press from GlassSurface below — never both. A
                // non-interactive button (131's hiding jump) attaches
                // neither, so there is no pointer-input node at all and
                // touches reach whatever is underneath.
                if (filled != null && interactive) {
                    Modifier.glassPress(
                        interactionSource = source,
                        shape = CircleShape,
                        highlight = ink.copy(alpha = 0.12f),
                        enabled = enabled,
                    )
                } else {
                    Modifier
                },
            )
            .then(
                if (interactive) {
                    Modifier.clickable(
                        interactionSource = source,
                        indication = null,
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (filled != null) {
            // Opaque fill behind the glyph; no blur, no rim, no shadow.
            Box(Modifier.matchParentSize().background(filled, CircleShape))
            Icon(icon, glyphInk, iconPt, weight, contentDescription = contentDescription)
        } else {
            GlassSurface(
                Modifier.matchParentSize(),
                shape = CircleShape,
                interactive = enabled && interactive,
                interactionSource = source,
            ) {
                // GlassSurface's content box wraps its content (07: "no
                // layout of its own"), so the button owns its centring: a
                // filling box that centres the glyph.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(icon, glyphInk, iconPt, weight, contentDescription = contentDescription)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// ProminentGlassButton — the filled capsule (QuestionPanel/SignIn/NewProject)
// ---------------------------------------------------------------------------

/**
 * Defaults of [ProminentGlassButton] (ticket 08): the three iOS padding
 * sets, one per source, and the shared metrics.
 */
object ProminentGlassButtonDefaults {

    /** Folder browser "Use" (`ios:Threads/NewProjectViewController.swift:65`): 14/20. */
    val Padding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)

    /** Pairing primary (`ios:Shell/SignInViewController.swift:55`): 15/20. */
    val PairingPadding = PaddingValues(horizontal = 20.dp, vertical = 15.dp)

    /** Question Next/Submit, tuned to land ≈44 dp tall (ticket Comments). */
    val CompactPadding = PaddingValues(horizontal = 20.dp, vertical = 11.dp)

    /** The question panel's ≈44 dp (android-app#24). */
    val MinHeight = 44.dp

    /** The busy spinner's gap before the title (starred; gallery-tuned). */
    val SpinnerGap = 8.dp
}

/**
 * The prominent capsule (ticket 08): the one opaque button the three iOS
 * sources render — the question "Next"/"Submit"
 * (`ios:Composer/QuestionPanel.swift:61-68`, `.prominentGlass()`), the
 * pairing primary (`ios:Shell/SignInViewController.swift:50-63`, `.filled()`)
 * and the folder browser "Use “…”"
 * (`ios:Threads/NewProjectViewController.swift:61-68`, `.filled()`) — a
 * capsule filled `palette.text`, title in `palette.background`, with the
 * per-call padding ([contentPadding]) and the caller's width (intrinsic, or
 * `Modifier.fillMaxWidth()` for Pair and Use).
 *
 * - Title: Geist SemiBold 17, `fixed` (a UIKit point — the height ignores
 *   font scale).
 * - Press: [Modifier.glassPress] on `CapsuleShape` with the
 *   `palette.background` @0.12 highlight, 07's press tokens. Opaque, so no
 *   `GlassSurface`, no blur, no rim, no shadow.
 * - Disabled: fill `palette.controlFill`, title `palette.tertiary`, no
 *   press, no click.
 * - [busy]: a 20 dp 8-spoke spinner ([ActivitySpinner]) 8 dp before the
 *   title, in the button ink; the title stays visible and **clicks are
 *   ignored** (Android closes UIKit's double-create race). Semantics get
 *   `stateDescription = "In progress"` while busy.
 * - No ripple (`indication = null`), no haptics.
 */
@Composable
fun ProminentGlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ProminentGlassButtonDefaults.Padding,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    // Busy buttons ignore clicks but are not otherwise "off" (UIKit keeps
    // the title visible; the activity indicator replaces the image slot).
    val interactive = enabled && !busy
    Row(
        modifier
            .defaultMinSize(minHeight = ProminentGlassButtonDefaults.MinHeight)
            .glassPress(
                interactionSource = source,
                shape = CapsuleShape,
                highlight = palette.background.copy(alpha = 0.12f),
                enabled = interactive,
            )
            .clip(CapsuleShape)
            .background(if (enabled) palette.text else palette.controlFill)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = interactive,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { if (busy) stateDescription = "In progress" }
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            ActivitySpinner(
                ink = palette.background,
                modifier = Modifier.size(20.dp).testTag(BusySpinnerTag),
            )
            Spacer(Modifier.width(ProminentGlassButtonDefaults.SpinnerGap))
        }
        BasicText(
            text,
            style = uiStyle(
                Face.SansSemibold,
                typeScale.fixed(17f),
                if (enabled) palette.background else palette.tertiary,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// GlassTextCapsule — the iOS 26 nav-bar text item
// ---------------------------------------------------------------------------

/**
 * An iOS 26 nav-bar text item drawn as a glass capsule (ticket 08): the
 * folder browser's device picker (`ios:Threads/NewProjectViewController.swift:88`),
 * the sheet/dialog "Done" and the Pinned folder's "Edit"/"Done"
 * (`ios:Threads/SessionListController.swift:455`).
 *
 * - 44 dp high, 14 dp side padding, 44 dp min width (system nav-bar item
 *   metrics); the caller caps width with `Modifier.widthIn(max = …)` for
 *   long names — the single-line text then ellipsizes at the tail.
 * - Text: Geist Medium 17 ([Face.SansMedium]) for plain items, SemiBold
 *   when [emphasized] (UIKit `.done`), `fixed` — a UIKit point, ignoring
 *   font scale.
 * - Ink: the measured iOS 26 glass bar-item ink
 *   ([GlassButtonDefaults.barGlyphInk], SESSIONS-D1), `palette.tertiary`
 *   when disabled.
 * - Surface: ticket 07's [GlassSurface] with `interactive = enabled`,
 *   sharing the button's `clickable` source — 07 draws the press. No
 *   `glassPress` (that would scale twice).
 * - No ripple, no haptics.
 */
@Composable
fun GlassTextCapsule(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    ink: Color = GlassButtonDefaults.barGlyphInk(),
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    GlassSurface(
        modifier
            .height(44.dp)
            .defaultMinSize(minWidth = 44.dp)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        shape = CapsuleShape,
        interactive = enabled,
        interactionSource = source,
    ) {
        // The capsule's width comes from the text (07's content box wraps);
        // the button owns the 44 dp band and the centring: a box that fills
        // the height, keeps the item's min width, and centres the label.
        Box(
            Modifier.fillMaxHeight().defaultMinSize(minWidth = 44.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text,
                Modifier.padding(horizontal = 14.dp),
                style = uiStyle(
                    if (emphasized) Face.SansSemibold else Face.SansMedium,
                    typeScale.fixed(17f),
                    if (enabled) ink else palette.tertiary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The gallery / preview / golden fixture: every variant (ticket 08 steps 6-7)
// ---------------------------------------------------------------------------

/**
 * Every circle variant (ticket 08 step 7): the 44/17 defaults (⋯, ✕, the
 * Semibold back chevron), the jump-to-latest 40/15 ↓, the image-viewer
 * 44/15 share, the filled accent `+` and ✓ (white glyph), and the disabled
 * ⋯. All buttons share [press] when the gallery drives one fake press for
 * the whole row.
 */
@Composable
internal fun GlassCircleVariants(press: MutableInteractionSource? = null) {
    val palette = RobocoTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassCircleButton(RobocoIcons.Ellipsis, "More", {}, interactionSource = press)
            GlassCircleButton(RobocoIcons.Xmark, "Close", {}, interactionSource = press)
            GlassCircleButton(
                RobocoIcons.ChevronBackward, "Back", {},
                weight = SymbolWeight.Semibold,
                interactionSource = press,
            )
            GlassCircleButton(
                RobocoIcons.ArrowDown, "Jump to latest", {},
                size = 40.dp, iconPt = 15f,
                interactionSource = press,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassCircleButton(
                RobocoIcons.SquareAndArrowUp, "Share image", {},
                iconPt = 15f,
                interactionSource = press,
            )
            GlassCircleButton(
                RobocoIcons.Plus, "New session", {},
                filled = palette.accent,
                interactionSource = press,
            )
            GlassCircleButton(
                RobocoIcons.Checkmark, "Done", {},
                filled = palette.accent,
                interactionSource = press,
            )
            GlassCircleButton(RobocoIcons.Ellipsis, "More", {}, enabled = false, interactionSource = press)
        }
    }
}

/**
 * Every text-capsule variant (ticket 08 step 7): "Edit", the emphasized
 * "Done", the device name "MacBook Pro", and a long engine name clipped by
 * `widthIn(max = 160.dp)`.
 */
@Composable
internal fun GlassTextCapsuleVariants(press: MutableInteractionSource? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassTextCapsule("Edit", {}, interactionSource = press)
            GlassTextCapsule("Done", {}, emphasized = true, interactionSource = press)
            GlassTextCapsule("MacBook Pro", {}, interactionSource = press)
        }
        GlassTextCapsule(
            "rust-build-engine-macbook-pro", {},
            modifier = Modifier.widthIn(max = 160.dp),
            interactionSource = press,
        )
    }
}

/**
 * Every prominent variant (ticket 08 step 7): the compact "Next", the
 * disabled "Submit", the full-width `Use “roboco”` (default padding), the
 * busy `Use “roboco”` at intrinsic width (the spinner grows it by 28 dp,
 * like UIKit's image slot), and the full-width pairing "Pair an engine"
 * (pairing padding).
 */
@Composable
internal fun GlassProminentVariants(press: MutableInteractionSource? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProminentGlassButton(
            "Next", {},
            contentPadding = ProminentGlassButtonDefaults.CompactPadding,
            interactionSource = press,
        )
        ProminentGlassButton("Submit", {}, enabled = false, interactionSource = press)
        ProminentGlassButton("Use “roboco”", {}, modifier = Modifier.fillMaxWidth(), interactionSource = press)
        ProminentGlassButton("Use “roboco”", {}, busy = true, interactionSource = press)
        ProminentGlassButton(
            "Pair an engine", {},
            contentPadding = ProminentGlassButtonDefaults.PairingPadding,
            modifier = Modifier.fillMaxWidth(),
            interactionSource = press,
        )
    }
}

/** All three groups together, as the previews show them (ticket 08 step 6). */
@Composable
private fun GlassButtonsVariants() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GlassCircleVariants()
        GlassTextCapsuleVariants()
        GlassProminentVariants()
    }
}

/** The step 6 stage: every variant on ticket 22's backdrop, glass above it. */
@Composable
private fun GlassButtonsStage() {
    ProvideHazeState {
        GlassStage(
            height = 600.dp,
            backdropModifier = Modifier.hazeSource(LocalHazeState.current!!),
        ) {
            GlassButtonsVariants()
        }
    }
}

@Preview(name = "Glass buttons light")
@Composable
private fun GlassButtonsLightPreview() {
    RobocoTheme(Appearance.Light) { GlassButtonsStage() }
}

@Preview(name = "Glass buttons dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GlassButtonsDarkPreview() {
    RobocoTheme(Appearance.Dark) { GlassButtonsStage() }
}
