package sh.roboco.android.composer

import androidx.compose.runtime.Immutable

/**
 * One rendered page of the question panel (android-ui-parity #90): the
 * immutable model the static card renders. Ticket 91 builds it from
 * `SessionChrome.Question` plus its own picks/custom state; this ticket
 * owns no state at all — every field is a plain value the caller re-creates
 * on each change (the iOS `render()` rebuild, `QuestionPanel.swift:111-148`).
 *
 * `picked` holds **option labels**, not indices: iOS decides "picked" with
 * `chosen.contains(option)` (`:120`), so two options with the same label
 * light up together, and `picked` survives option reordering.
 */
@Immutable
data class QuestionPageModel(
    /** Raw `Question.header` — `""` renders as the fallback "Question" (not trimmed: `" "` shows as is). */
    val header: String,
    /** 0-based page index into the question list. */
    val page: Int,
    /** `items.size`, ≥ 1. */
    val pageCount: Int,
    /** `Question.text`. */
    val question: String,
    /** `Question.options`, in order. */
    val options: List<String>,
    /** Picked option labels for this question (by label, like iOS). */
    val picked: Set<String>,
    val multiSelect: Boolean,
    /** `true` → the 140 dp editor instead of the Other field. Roboco engines never send it today. */
    val multiline: Boolean,
    /** `custom[q.id]` (prefill or typed); `""` when none. */
    val customText: String,
    /** `answered(q)`, computed by ticket 91 — gates Next/Submit. */
    val canContinue: Boolean,
) {
    /** The composed header line ("Question · 1 of 2" / "Question" / a custom header). */
    val headerText: String get() = QuestionCardText.header(header, page, pageCount)

    /** `page == 0` → no Back button at all (removed, not just invisible: `:142`). */
    val showBack: Boolean get() = page != 0

    /** "Submit" on the last page, "Next" otherwise (`:144`). */
    val nextTitle: String get() = QuestionCardText.nextTitle(page, pageCount)
}

/**
 * The page's callbacks (android-ui-parity #90), one per iOS action site.
 * Ticket 91 supplies them; previews, the gallery and tests pass no-ops or
 * counters. Every callback must fire haptics itself if iOS does — this
 * ticket fires none.
 */
@Immutable
class QuestionPageActions(
    /** iOS `pick(option)` (`QuestionPanel.swift:156`) — ticket 91 adds the SEGMENT_TICK haptic. */
    val onPick: (option: String) -> Unit,
    /** Other field and editor edits (iOS `customChanged`, `:168`). */
    val onCustomChange: (String) -> Unit,
    /** IME Done on the Other field (iOS `textFieldShouldReturn` → advance, `:190`). */
    val onOtherDone: () -> Unit,
    /** iOS `go(-1)` (`:148`). */
    val onBack: () -> Unit,
    /** iOS `advance()` (`:159`) — ticket 91 adds the CONFIRM haptic on submit. */
    val onNext: () -> Unit,
)

/**
 * The question card's copy (android-ui-parity #90), verbatim from
 * `ios:Composer/QuestionPanel.swift`. Pure string logic, plain-JVM
 * testable.
 */
object QuestionCardText {

    /** Header fallback when `header.isEmpty()` (`:113` — `isEmpty`, never trimmed). */
    const val FALLBACK_HEADER = "Question"

    /** The Other field's placeholder (`:34`) — U+2026, one character. */
    const val OTHER_PLACEHOLDER = "Other…"

    /** The plain Back title (`:57`). */
    const val BACK = "Back"

    /** The prominent title on non-last pages (`:144`). */
    const val NEXT = "Next"

    /** The prominent title on the last page (`:144`). */
    const val SUBMIT = "Submit"

    /**
     * `header.isEmpty() ? "Question" : header` — one page shows the header
     * alone, more show `<header> · <page+1> of <count>` (`:113`). The
     * separator is space, U+00B7 `·`, space.
     */
    fun header(header: String, page: Int, count: Int): String {
        val h = header.ifEmpty { FALLBACK_HEADER }
        return if (count > 1) "$h · ${page + 1} of $count" else h
    }

    /** "Submit" on the last page, "Next" otherwise (`:144`). */
    fun nextTitle(page: Int, count: Int): String = if (page == count - 1) SUBMIT else NEXT
}

/**
 * The card's test tags (android-ui-parity #90): the four iOS
 * `accessibilityIdentifier`s (`:45,:54,:67,:134`) plus the Android-only
 * card tag. Option rows are `question-option-<i>`, 0-based.
 */
object QuestionCardTags {

    /** Option row `i` (`question-option-\(i)`, `:134`). */
    fun option(i: Int) = "question-option-$i"

    /** The Other field (`:45`). */
    const val OTHER = "question-other"

    /** The multi-line editor (`:54`). */
    const val EDITOR = "question-editor"

    /** The Next/Submit button (`:67`). */
    const val NEXT = "question-next"

    /** Android-only: the glass card itself, for geometry tests. */
    const val CARD = "question-card"
}
