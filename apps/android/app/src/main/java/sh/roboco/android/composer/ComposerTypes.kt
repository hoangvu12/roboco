package sh.roboco.android.composer

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import sh.roboco.android.design.glass.Menu

/**
 * How a message sent during a running turn is delivered
 * (android-ui-parity #54). Doc comments verbatim from
 * `ios:Composer/ComposerBar.swift:12-19`. The iOS raw values
 * (`queue` / `steer` / `interrupt`) are never used on the wire, so the
 * Kotlin enum carries no serial name.
 */
enum class DeliveryMode {
    /** Waits in the shared queue for the turn to end (default). */
    Queue,

    /** Injected mid-turn when the harness supports it. */
    Steer,

    /** Stops the turn, then sends. */
    Interrupt,
}

/**
 * What happened to a send, so the composer knows whether to clear
 * (`ios:Composer/ComposerBar.swift:43-58`, doc comments verbatim).
 */
enum class SendResult {
    /** Taken: the composer clears. */
    Sent,

    /** Not taken (e.g. couldn't start the session): text and images stay. */
    Kept,

    /**
     * Taken, and the handler already put the composer's next contents in
     * place (committing a queued edit restores the stashed draft).
     */
    Replaced,
}

/**
 * The primary action the action button currently performs — iOS
 * `ComposerBar.Action` (`ios:Composer/ComposerBar.swift:42-46`): `Send`
 * when idle, `Queue` (or Steer) while a turn runs with content, `Stop`
 * while one runs without. Named `ComposerAction`, not `Action`, because
 * tickets 58 and 78 import it under this name and a bare `Action` is too
 * generic for the `composer` package.
 */
enum class ComposerAction { Send, Queue, Stop }

/**
 * The derived state of the action button: a pure function of the
 * controller's inputs (see [ComposerController.deriveAction]), read by
 * ticket 58's button and asserted row by row in the Robolectric
 * `ComposerControllerTest`.
 *
 * [action] and [steering] pick the glyph and label; [enabled] gates taps
 * (Stop is always enabled on iOS — only Roboco's `canWrite` can disable
 * it); [deliveryMenu] says whether the long-press "While the agent works"
 * menu (58) is offered.
 */
@Immutable
data class ActionState(
    val action: ComposerAction,
    val enabled: Boolean,
    val steering: Boolean,
    val deliveryMenu: Boolean,
) {
    /**
     * The button's accessibility label, the exact copy from
     * `ios:Composer/ComposerBar.swift:618-622`. A computed `val` (not a
     * constructor parameter) so 58 and the tests always share it.
     */
    val accessibilityLabel: String
        get() = when (action) {
            ComposerAction.Send -> "Send message"
            ComposerAction.Queue -> if (steering) "Steer" else "Queue message"
            ComposerAction.Stop -> "Stop response"
        }
}

/**
 * A chip's ink, as a role rather than a resolved colour: chips are built
 * by non-composable code (the session binding, 78), and a baked `Color`
 * would keep the old theme's value after a light/dark switch. Ticket 59
 * resolves each role against the current palette at draw time.
 */
enum class ChipTint {
    PrOpen,
    PrDraft,
    PrMerged,
    PrClosed,
    Warning,
    Accent,

    /** android-next#05: the plan-usage chip at the critical level (≥ 95 %) — `Palette.danger`. */
    Critical,
}

/**
 * A chip's brand mark / custom glyph, replacing iOS's `UIImage?` with data
 * (tickets 51 / 78 / 109 build these). Takes precedence over
 * [ComposerChip.symbol]; ticket 59 draws them: `Brand` at 13 dp (ticket
 * 13's brand marks), `PullRequest` at 12 dp and `Branch` at 13 dp (ticket
 * 12's glyphs), `Project` as a 14 dp tile (ticket 12).
 */
sealed interface ChipIcon {

    /** The harness's brand mark (`BrandMarks.image(for:side:)`). */
    data class Brand(val harness: String) : ChipIcon

    /** The branch glyph (`BranchIcon.sized()`, 13 dp). */
    data object Branch : ChipIcon

    /** The pull-request glyph (`PRIcon.image(side: 12)`). */
    data object PullRequest : ChipIcon

    /** A project tile (`ProjectTile.image(name:colorIndex:)`, 14 dp). */
    data class Project(val name: String, val colorIndex: Int) : ChipIcon
}

/**
 * A context chip in the composer toolbar (model, effort, branch…) — iOS
 * `ComposerChip` (`ios:Composer/ComposerBar.swift:22-28`). Ticket 59
 * renders the strip; menus open through
 * [ComposerController.chipMenus].
 *
 * [symbol] is the **SF Symbol name** (e.g. `gauge.with.dots.needle.67percent`),
 * resolved by 59 via ticket 06's `RobocoIcons.forSfName`; [icon] wins over
 * it when both are set.
 */
@Immutable
data class ComposerChip(
    val id: String,
    val title: String,
    val symbol: String? = null,
    val tint: ChipTint? = null,
    /** Brand mark / custom glyph (takes precedence over [symbol]). */
    val icon: ChipIcon? = null,
    /** TalkBack's label when the title alone is too terse (android-next #01's context chip); `null` reads the title. */
    val contentDescription: String? = null,
)

/**
 * An image staged in the composer before sending — iOS `StagedImage`
 * (`ios:Composer/ComposerBar.swift:4-9`). Producers are ticket 87
 * (staging: JPEG, ≤2560 px, q86); the FFI `OutgoingAttachment` conversion
 * is 78's job, not this type's.
 *
 * Equality is by [id] alone (iOS is `Identifiable`; its `UIImage` and
 * Kotlin's `ByteArray` both compare by reference, so content equality
 * could never work anyway).
 */
class StagedImage(
    val id: String,
    val name: String,
    val bytes: ByteArray,
    val thumb: ImageBitmap,
) {
    override fun equals(other: Any?): Boolean = other is StagedImage && other.id == id

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Lazily builds a chip's menu (`ios:Composer/ComposerBar.swift:72-73`;
 * `() -> UIMenu?`). The provider runs when the chip's menu opens; `null`
 * means no menu. Held only — ticket 59 presents it.
 */
typealias ChipMenuProvider = () -> Menu?
