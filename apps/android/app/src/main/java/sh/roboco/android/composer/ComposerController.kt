package sh.roboco.android.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import sh.roboco.android.design.Feedback
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.glass.Menu
import uniffi.roboco_core.FileMatch

/**
 * The composer's non-visual state holder (android-ui-parity #54): the
 * Android port of what `ios:Composer/ComposerBar.swift` mixes into its
 * `UIView`. Every composer part reads and writes this one object —
 * surface/layout (55), morph (56), text field (57), action button (58),
 * chip strip (59), session binding (78), queue edit lease (83), drafts
 * (84), mention suggestions (86), attach menu + staging (87), thumbnails
 * (88), chip menus (89) and the new-session canvas (93/94).
 *
 * It owns the content (text + selection, staged images, the `@file`
 * mention index), the inputs that drive the composer (`running`,
 * `canSteer`, `preferredDelivery`, `placeholder`, `chips`, `chipMenus`,
 * `alwaysCard`, `canWrite`, `attachEnabled`) and the logic: [hasContent],
 * [actionState] (Send / Queue / Stop + enabled + steering + a11y label),
 * primary-action routing and [send] with its [SendResult] outcomes.
 *
 * No rendering, no session wiring. The only Compose dependencies are the
 * runtime snapshot state, the `TextFieldValue` / `TextRange` /
 * `ImageBitmap` value types and the [rememberComposerController] factory.
 * Main thread only.
 *
 * Roboco adaptations on top of the iOS rules (see the ticket):
 * - [onSend] is `suspend` (the FFI send is async), launched
 *   [CoroutineStart.UNDISPATCHED] so a non-suspending handler completes
 *   inside the [send] call exactly like iOS; while a send is in flight a
 *   further [send] is ignored ([sending]), so a double tap cannot send
 *   twice. A handler that throws any non-cancellation `Throwable` is
 *   treated as [SendResult.Kept], so a failed FFI call never clears the
 *   composer.
 * - On [SendResult.Sent] the text is cleared only if it still equals the
 *   sent snapshot, and only the staged images that were sent are removed:
 *   typing during an in-flight send is never lost ("never lose typing"
 *   wins over iOS's synchronous clear).
 */
@Stable
class ComposerController(
    /** The main-thread scope sends launch on (UI: `rememberCoroutineScope`). */
    private val scope: CoroutineScope,
    /** Fires the send/stop haptics; `NoFeedback` in tests and previews. */
    private val feedback: Feedback,
    initialText: String = "",
    /** The `@file` mention index (ticket 53). Never re-created here. */
    val mentions: MentionIndex = MentionIndex(),
) {

    /** Text + selection + IME composition; 57 binds `BasicTextField` to it. */
    var value: TextFieldValue by mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length)))

    /**
     * The whole text. Setting it replaces [value] and puts the caret at
     * the end, exactly like UIKit's `textView.text = s` (`:83-88`); it
     * does **not** touch the mention tokens — the queue-edit stash/restore
     * (83) and a `Replaced` draft rely on them surviving.
     */
    var text: String
        get() = value.text
        set(s) {
            value = TextFieldValue(s, TextRange(s.length))
        }

    /** Staged images. A direct set has **no cap** (the stash restore sets 10); only [addImages] caps at [MaxImages]. */
    var images: List<StagedImage> by mutableStateOf(emptyList())

    /** A turn is running (`:69`). */
    var running by mutableStateOf(false)

    /** The harness supports mid-turn steering (`:70`, from the engine's advertised capabilities). */
    var canSteer by mutableStateOf(false)

    /** Preferred delivery while queued (`:71`). The controller never changes it — no #609 delivery-chip reset. */
    var preferredDelivery by mutableStateOf(DeliveryMode.Queue)

    /** Placeholder copy (`:72`): `Message`, `Message <harness>`, `Edit queued message`, `Describe the task`. */
    var placeholder by mutableStateOf("Message")

    /** Context chips (`:73`); 59 renders the strip. */
    var chips: List<ComposerChip> by mutableStateOf(emptyList())

    /** Chips whose id has a provider open a menu on tap (`:74-75`); 89 fills them. */
    var chipMenus: Map<String, ChipMenuProvider> by mutableStateOf(emptyMap())

    /** Stay in the card state even when idle — iOS `chipsAlwaysVisible` (`:76-77`); 56 reads it as `wantsCard`. */
    var alwaysCard by mutableStateOf(false)

    /** Roboco: `false` while the owning engine is offline (79 derives it). Text and chips stay editable. */
    var canWrite by mutableStateOf(true)

    /** Roboco: `false` while a queued edit is open (83); 55/87 grey out `+`. */
    var attachEnabled by mutableStateOf(true)

    /** Mention-suggestion card visibility (86 renders it; [send] hides it). */
    var suggestionsVisible by mutableStateOf(false)

    /**
     * `body, staged, mode` → outcome. `null` ⇒ [SendResult.Sent] (iOS
     * `?? .sent`). The session handler (78) moves its FFI work off main
     * inside this call.
     */
    var onSend: (suspend (String, List<StagedImage>, DeliveryMode) -> SendResult)? = null

    /** The turn stop (78's `interrupt()`); no haptic of its own — [primaryAction] owns that. */
    var onStop: () -> Unit = {}

    /** Chip taps for chips without a menu (59). */
    var onChipTap: (String) -> Unit = {}

    /** Held for 87 (the attach menu builder). */
    var attachMenu: (() -> Menu)? = null

    /** `@` file search; `null` disables mentions (86). */
    var mentionSearch: (suspend (String) -> List<FileMatch>)? = null

    /** iOS `:574-576` — non-blank text or any staged image. */
    val hasContent: Boolean
        get() = value.text.isNotBlank() || images.isNotEmpty()

    /** The action button's derived state — a pure function of the state above (`:579-581`), with Roboco's `canWrite`. */
    val actionState: ActionState
        get() = deriveAction(running, hasContent, canWrite, canSteer, preferredDelivery)

    /** `@` mentions are live only when writable and searchable (`:66-67`, `:339`). */
    val mentionsEnabled: Boolean
        get() = canWrite && mentionSearch != null

    /** True while a [send] is in flight (plain: main-thread only, not observable). */
    private var sending = false

    // ── images (`:472-476`, remove at `:501-503`) ───────────────────────────

    /** Appends up to [MaxImages] images in total; no [attachEnabled] check (the UI gates entry points). */
    fun addImages(new: List<StagedImage>) {
        images = images + new.take(max(0, MaxImages - images.size))
    }

    fun clearImages() {
        images = emptyList()
    }

    /** 88 animates the removal over 250 ms; this is the state change it animates. */
    fun removeImage(id: String) {
        images = images.filterNot { it.id == id }
    }

    // ── suggestions ────────────────────────────────────────────────────────

    /** Hides the mention card (86 sets it; also part of every taken send). */
    fun hideSuggestions() {
        suggestionsVisible = false
    }

    // ── actions (`:639-665`) ───────────────────────────────────────────────

    /**
     * The action button's tap (`:639-648`): Stop fires [Haptic.Stop] then
     * [onStop]; Send sends `.queue`; Queue sends [routeQueue]'s delivery.
     * The single owner of both the Stop haptic and the routing — 58 calls
     * this for every tap and [send] for its menu rows, so each path fires
     * exactly one haptic.
     */
    fun primaryAction() {
        if (!canWrite) return
        when (actionState.action) {
            ComposerAction.Stop -> {
                feedback.haptic(Haptic.Stop)
                onStop()
            }
            ComposerAction.Send -> send(DeliveryMode.Queue)
            ComposerAction.Queue -> send(routeQueue(preferredDelivery, canSteer))
        }
    }

    /** ⌘↩ / Ctrl+Enter (`:667-674`): only with content, so it never triggers Stop. 57 calls it. */
    fun keyboardSend() {
        if (hasContent) primaryAction()
    }

    /**
     * Sends now (`:650-665`), in the order iOS does it: guard →
     * [Haptic.Confirm] → snapshot (`raw`, encoded `body`, `staged`) →
     * handler → outcome. The haptic fires after the guard and before the
     * handler, even when the result turns out [SendResult.Kept].
     *
     * Returns the in-flight [Job] (tests join it; a non-suspending
     * handler has already completed), or `null` when nothing was sent: no
     * content, [canWrite] `false`, or a send already in flight.
     */
    fun send(mode: DeliveryMode): Job? {
        if (!canWrite || !hasContent || sending) return null
        feedback.haptic(Haptic.Confirm)
        val raw = value.text
        // iOS trims first, then encodes (`:653-654`); #609 does it backwards.
        val body = mentions.encode(raw.trim())
        val staged = images
        val stagedIds = staged.mapTo(HashSet()) { it.id }
        val handler = onSend
        sending = true
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = try {
                    handler?.invoke(body, staged, mode) ?: SendResult.Sent
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    SendResult.Kept
                }
                when (result) {
                    SendResult.Kept -> Unit // nothing changes
                    SendResult.Replaced -> {
                        // The handler already put the next contents in place
                        // (its @file tokens must still resolve): only hide.
                        suggestionsVisible = false
                    }
                    SendResult.Sent -> {
                        suggestionsVisible = false
                        images = images.filterNot { it.id in stagedIds }
                        if (value.text == raw) {
                            mentions.reset()
                            text = ""
                        }
                        // else: the user typed during an in-flight send —
                        // the new text and its tokens stay.
                    }
                }
            } finally {
                sending = false
            }
        }
    }

    companion object {
        /** iOS's image cap, only enforced in [addImages] (`:472-476`). */
        const val MaxImages = 8

        /**
         * The action derivation, `ios:Composer/ComposerBar.swift:579-581`
         * plus Roboco's `canWrite`:
         * - action: `running ? (hasContent ? queue : stop) : send`;
         * - enabled: Stop is always enabled, everything else needs content,
         *   and `canWrite` gates all of it;
         * - steering: a queued send would deliver [DeliveryMode.Steer];
         * - deliveryMenu: the long-press menu exists only mid-turn.
         */
        fun deriveAction(
            running: Boolean,
            hasContent: Boolean,
            canWrite: Boolean,
            canSteer: Boolean,
            preferred: DeliveryMode,
        ): ActionState {
            val action =
                if (running) {
                    if (hasContent) ComposerAction.Queue else ComposerAction.Stop
                } else {
                    ComposerAction.Send
                }
            return ActionState(
                action = action,
                enabled = canWrite && (action == ComposerAction.Stop || hasContent),
                steering = action == ComposerAction.Queue && preferred == DeliveryMode.Steer && canSteer,
                deliveryMenu = action == ComposerAction.Queue && canWrite,
            )
        }

        /**
         * The Queue-state delivery (`:647`): with steering support the
         * preferred mode wins; without it an unusable `Steer` falls back to
         * `Queue` (the user asked for mid-turn, the harness can't).
         */
        fun routeQueue(preferred: DeliveryMode, canSteer: Boolean): DeliveryMode =
            if (canSteer) preferred
            else if (preferred == DeliveryMode.Steer) DeliveryMode.Queue
            else preferred
    }
}

/**
 * One [ComposerController] for the composition: the ambient [Feedback]
 * (ticket 05) and the composition's own scope, created once and kept
 * across recompositions (state survives; `initialText` seeds it the first
 * time only).
 */
@Composable
fun rememberComposerController(initialText: String = ""): ComposerController {
    val feedback = LocalFeedback.current
    val scope = rememberCoroutineScope()
    return remember { ComposerController(scope, feedback, initialText) }
}
