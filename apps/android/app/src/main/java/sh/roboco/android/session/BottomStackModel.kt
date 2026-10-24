package sh.roboco.android.session

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

/**
 * What the bottom stack shows (android-ui-parity #74) — the visibility
 * slice of [SessionChrome] the container animates on, the port of the
 * render block of `ios:Session/SessionViewController.swift:492-521`:
 *
 * - the questions panel replaces the composer exactly (`questions != nil`
 *   hides the composer, `:497-498`);
 * - the queue hides when it is empty **or** while questions show
 *   (`queue.isHidden = c.queue.isEmpty || asking`, `:500`);
 * - the pill row hides when there is no banner and no queue edit
 *   (`pill.superview?.isHidden = c.banner == .none && !editing`, `:502`);
 * - a **structural** change (questions appear or vanish, the queue count
 *   changes, the banner appears or vanishes) reflows the stack with the
 *   0.38 s spring; everything else snaps (`:511, 517-521`).
 *
 * [editing] is ticket 83's queue-edit lease flag, kept out of the
 * structural rule: iOS shows and hides the pill row instantly during
 * edits (`:432-433`, `:451`), outside the reflow animation.
 */
@Immutable
data class BottomStackModel(
    /** `c.questions != nil` — the question panel replaces the composer. */
    val questionsShown: Boolean = false,
    /** `c.queue.count` — 0 hides the queue; the count itself drives the reflow. */
    val queueCount: Int = 0,
    /** `chrome.banner != None` (73: `chrome.banner` never holds `Editing`). */
    val bannerShown: Boolean = false,
    /** The Limited banner card (the failover port): `chrome.limited != null`. */
    val limitedShown: Boolean = false,
    /** Ticket 83's edit-lease flag (73's display overlay; never structural). */
    val editing: Boolean = false,
) {
    /** The pill row shows for a banner **or** an edit (`:502`). */
    val pillShown: Boolean
        get() = bannerShown || editing

    /** The queue shows when non-empty and no questions (`:500`). */
    val queueShown: Boolean
        get() = queueCount > 0 && !questionsShown

    /** Exactly one of questions and composer is shown (`:497-498`). */
    val composerShown: Boolean
        get() = !questionsShown

    companion object {
        /**
         * Maps the chrome snapshot into the stack's visibility model.
         *
         * [editing] is ticket 83's `editingId != null` lease flag, passed
         * **separately** from `chrome.banner`: ticket 73 folds Editing into
         * the *displayed* pill banner (`shownBanner`), never into
         * `chrome.banner` itself, so starting or ending an edit is NOT
         * structural here — matching iOS `:432-433` and `:451`, where the
         * pill row appears and disappears instantly during edits. The
         * production call site (75) is `BottomStackModel.from(chrome,
         * isEditing)`, and the pill slot still renders
         * `shownBanner(chrome.banner, editing)` (73).
         */
        fun from(chrome: SessionChrome, editing: Boolean): BottomStackModel = BottomStackModel(
            questionsShown = chrome.questions != null,
            queueCount = chrome.queue.size,
            bannerShown = chrome.banner != SessionChrome.Banner.None,
            limitedShown = chrome.limited != null,
            editing = editing,
        )
    }
}

/**
 * Whether the change from [old] to [new] reflows the stack with the
 * spring (iOS "structural", `SessionViewController.swift:511`):
 * questions present-ness flipped, the queue *count* changed, or the
 * banner flipped between none and some. `editing` is deliberately absent
 * — an edit toggle snaps.
 */
fun isStructural(old: BottomStackModel, new: BottomStackModel): Boolean =
    old.questionsShown != new.questionsShown ||
        old.queueCount != new.queueCount ||
        old.bannerShown != new.bannerShown ||
        old.limitedShown != new.limitedShown

/** The five stack slots, top to bottom (`SessionViewController.swift:122`
 *  plus the Limited banner above the pill row). */
enum class BottomStackSlot { Limited, Pill, Queue, Questions, Composer }

/**
 * What the container tells one slot during its composition. The slots are
 * plain lambdas supplied by their owner tickets, so the scope is the only
 * wiring: [shown] and [glassVisible] come from [BottomStackModel],
 * [animateGlass] is `false` only during the container's first composition
 * (the panels start dematerialized and grow their glass in later,
 * `SessionViewController.swift:129-130`).
 *
 * The slots feed [glassVisible] and [animateGlass] straight into ticket
 * 07's `GlassSurface(visible = …, animateVisibility = …)`; [shown] is the
 * container's business (it hides, clips and re-places the slot), provided
 * here so a slot can adapt its own content if it needs to.
 */
@Stable
interface BottomStackSlotScope {
    /** The slot's target visibility (a hidden slot still composes). */
    val shown: Boolean

    /**
     * The glass materialization target: the queue keeps its glass on while
     * it is non-empty even when questions hide it (it only collapses);
     * questions mirror their visibility; the pill and composer own their
     * glass elsewhere, so theirs is always `true`.
     */
    val glassVisible: Boolean

    /** `false` only during the container's first composition. */
    val animateGlass: Boolean
}
