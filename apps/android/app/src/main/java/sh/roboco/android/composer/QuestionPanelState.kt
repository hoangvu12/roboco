package sh.roboco.android.composer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.roboco.android.design.Feedback
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.Motion
import sh.roboco.android.session.SessionChrome

/**
 * Kotlin twin of the FFI `UserInputAnswer` (android-ui-parity #91), kept
 * FFI-free so the panel state is plain-JVM testable: the port
 * ([QuestionPort]) maps it onto `uniffi.roboco_core.UserInputAnswer`
 * only when it actually crosses FFI.
 */
data class QuestionAnswer(val questionId: String, val labels: List<String>)

/**
 * The question panel's behaviour (android-ui-parity #91) — the port of
 * `ios:Composer/QuestionPanel.swift:7-21, 98-214`: page, picks, custom
 * text, the answered rule, the 220 ms single-select auto-advance, the
 * double-submit guard and the answer build. Main thread only (snapshot
 * state); ticket 90's card renders the values this class exposes and
 * calls back into it.
 *
 * The two Roboco adaptations iOS does not have (both from the ticket):
 * - a reset keyed on the request id **as well as** the item list, so a
 *   re-asked identical question set can be submitted again;
 * - one pending auto-advance job, bound to the page it was scheduled
 *   from: a new pick restarts it, [go]/[configure] cancel it, and it
 *   fires only on its own page (iOS never cancels its `asyncAfter`, so
 *   two quick taps can skip an answered page and Back within 220 ms is
 *   undone by a stale advance; the web does the same cancel as here).
 *
 * Submitting sets [submitted] before the first haptic and the
 * [onSubmit] callback, exactly iOS's order; a failed RPC re-arms the
 * guard through [onSubmitFailed] (iOS's `try?` would leave the panel
 * locked; nothing is shown either way — decision COMPOSER-D2).
 */
@Stable
class QuestionPanelState(
    /** The composition scope (main) — the auto-advance timer's home. */
    private val scope: CoroutineScope,
    /** Ticket 05's engine: `Select` on every option tap, `Confirm` on submit. */
    private val feedback: Feedback,
    /** Receives `(requestId, answers)` once per question set, on main. */
    private val onSubmit: (requestId: String, answers: List<QuestionAnswer>) -> Unit,
    /** `Motion.QuestionAutoAdvanceMs` (220); tests and fixtures override. */
    private val autoAdvanceMs: Long = Motion.QuestionAutoAdvanceMs,
) {
    /** Last configured list. **Kept** when the chrome's questions go null, so the collapsing slot still shows its content. */
    var items: List<SessionChrome.Question> by mutableStateOf(emptyList())
        private set

    /** The shown chrome's request id; `null` once the chrome's questions are null. */
    var requestId: String? by mutableStateOf(null)
        private set

    /** 0-based page index. */
    var page: Int by mutableIntStateOf(0)
        private set

    /** The double-submit guard (iOS `submitted`, `:13`). */
    var submitted: Boolean by mutableStateOf(false)
        private set

    /** Copied from `chrome.canWrite` (ticket 79): `false` disables Submit only. */
    var canWrite: Boolean by mutableStateOf(true)

    /** Incremented on every reset; keys the paging fade (a reset never fades). */
    var generation: Int by mutableIntStateOf(0)
        private set

    /** Picked option labels by question id (answers use option order, so order never matters). */
    private var picks: Map<String, Set<String>> by mutableStateOf(emptyMap())

    /** Raw text by question id — the Other field and the editor share it. */
    private var custom: Map<String, String> by mutableStateOf(emptyMap())

    /** The request id the current `items` were configured under (the second reset key). */
    private var resetRequestId: String? = null

    /** The pending auto-advance job, or null. At most one exists (the Roboco adaptation). */
    private var autoAdvance: Job? = null

    val count: Int get() = items.size

    /** `items[page]`, or null (the shrunken-list guard for the fade's outgoing page). */
    val current: SessionChrome.Question? get() = items.getOrNull(page)

    val isLastPage: Boolean get() = items.isNotEmpty() && page == items.lastIndex

    /** `page > 0` — iOS removes the Back view entirely (`:142`). */
    val backVisible: Boolean get() = page > 0

    /**
     * `answered(current) && (!isLastPage || canWrite)`: the canWrite gate
     * applies only to Submit, never to Next (`nextEnabled` feeds 90's
     * `canContinue`).
     */
    val nextEnabled: Boolean
        get() = current?.let { answered(it) && (!isLastPage || canWrite) } ?: false

    /** The labels picked for [questionId] (empty when none). */
    fun picked(questionId: String): Set<String> = picks[questionId].orEmpty()

    /** The raw text for [questionId] (prefill or typed; `""` when none). */
    fun customText(questionId: String): String = custom[questionId].orEmpty()

    /**
     * iOS `answered(_:)` (`:150-152`): multiline is always answered, else
     * one pick or non-blank custom text. The blank test trims **only**
     * iOS's `CharacterSet.whitespaces` (Zs + TAB) via [trimIos] — never
     * Kotlin's `trim()`, which also strips newlines.
     */
    fun answered(q: SessionChrome.Question): Boolean =
        q.multiline || !picked(q.id).isEmpty() || !customText(q.id).trimIos().isEmpty()

    // ---------------------------------------------------------------- configure

    /**
     * iOS `configure(_:)` (`:98-107`), called from the screen's render on
     * every chrome change while a question is open (never with a stale
     * id) — plus the request-id reset key.
     *
     * `null` (the request resolved): `requestId = null` and the pending
     * auto-advance is cancelled; nothing else changes, so the collapsing
     * slot keeps its items, page and picks.
     *
     * A non-null set resets when the item list **or** the request id
     * changed since the last reset: `page = 0`, picks cleared,
     * `submitted = false`, `custom` seeded with the prefills
     * (`putIfAbsent` — duplicate ids keep the **first** question's
     * prefill, never a crash), auto-advance cancelled, `generation++`.
     * An unchanged list under an unchanged id (every ordinary chrome
     * refresh) changes nothing but `requestId`.
     */
    fun configure(questions: SessionChrome.Questions?) {
        if (questions == null) {
            requestId = null
            cancelAutoAdvance()
            return
        }
        if (questions.items != items || questions.requestId != resetRequestId) {
            items = questions.items
            page = 0
            picks = emptyMap()
            submitted = false
            val seeded = LinkedHashMap<String, String>()
            for (item in questions.items) seeded.putIfAbsent(item.id, item.prefill ?: "")
            custom = seeded.toMap()
            cancelAutoAdvance()
            generation++
            resetRequestId = questions.requestId
        }
        requestId = questions.requestId
    }

    // ---------------------------------------------------------------- actions

    /**
     * iOS `pick(_:)` (`:154-168`): the `Select` haptic fires on every tap,
     * deselects included, before `picks` is written. Multi toggles;
     * single replaces (re-tapping the picked option keeps it picked).
     * Single-select then schedules [advance] in
     * [autoAdvanceMs][Motion.QuestionAutoAdvanceMs], bound to this page
     * (the Roboco single-job rule).
     */
    fun pick(pageIndex: Int, option: String) {
        if (pageIndex != page) return
        val q = current ?: return
        feedback.haptic(Haptic.Select)
        val set = picks[q.id].orEmpty()
        val next = if (q.multiSelect) {
            if (option in set) set - option else set + option
        } else {
            setOf(option)
        }
        picks = picks + (q.id to next)
        if (!q.multiSelect) {
            cancelAutoAdvance()
            val from = page
            autoAdvance = scope.launch {
                delay(autoAdvanceMs)
                if (page == from) advance()
            }
        }
    }

    /**
     * The Other field's and the editor's edits (iOS `customChanged` /
     * `textViewDidChange`, `:170-180`): raw, untrimmed text, synchronous
     * so the caret never jumps (90's pitfall).
     */
    fun setCustom(pageIndex: Int, text: String) {
        if (pageIndex != page) return
        val q = current ?: return
        custom = custom + (q.id to text)
    }

    /**
     * IME Done on the Other field (iOS `textFieldShouldReturn`, `:190`):
     * `advance()` — which does nothing while the page is unanswered. Focus
     * is not touched here (the refocus is 90's/91's composition glue).
     */
    fun otherDone(pageIndex: Int) {
        if (pageIndex != page) return
        advance()
    }

    /** Back tap (iOS `go(-1)`, `:60`). */
    fun back() = go(-1)

    /** Next/Submit tap (iOS `advance()`, `:68`). */
    fun next() = advance()

    /**
     * iOS `go(_:)` (`:187-190`) minus the transition (the fade is a
     * compositional concern keyed on `PageKey(generation, page)`): cancel
     * the pending auto-advance, clamp the page. No haptic.
     */
    fun go(delta: Int) {
        cancelAutoAdvance()
        page = (page + delta).coerceIn(0, maxOf(0, items.lastIndex))
    }

    /**
     * iOS `advance()` (`:196-214`): nothing while `submitted`, unanswered
     * or pageless; a non-last page pages forward; the last page submits.
     * Submitting returns early with no haptic when `requestId == null`
     * (a stale timer after the request resolved elsewhere) or
     * `!canWrite` (Roboco's read-only mode) — then builds every
     * question's answer in option order, sets `submitted`, plays
     * `Haptic.Confirm` and calls [onSubmit] exactly once per set.
     */
    fun advance() {
        if (submitted) return
        val q = current ?: return
        if (!answered(q)) return
        if (!isLastPage) {
            go(1)
            return
        }
        val id = requestId ?: return
        if (!canWrite) return
        val answers = buildAnswers(items, picks, custom)
        submitted = true
        feedback.haptic(Haptic.Confirm)
        onSubmit(id, answers)
    }

    /**
     * A failed `respondInput` for [requestId] (the RPC's failure callback,
     * on main): re-arms the guard so Submit works again, for this request
     * only. Nothing is shown (decision COMPOSER-D2).
     */
    fun onSubmitFailed(requestId: String) {
        if (requestId == this.requestId) submitted = false
    }

    private fun cancelAutoAdvance() {
        autoAdvance?.cancel()
        autoAdvance = null
    }
}

/**
 * Trims only what iOS's `CharacterSet.whitespaces` trims
 * (`QuestionPanel.swift:152`): Unicode Zs (SPACE_SEPARATOR) plus TAB —
 * **not** newlines, so Kotlin's `trim()` is wrong here.
 */
internal fun String.trimIos(): String =
    trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

/**
 * iOS `advance`'s answer build (`:201-210`), as a pure function so the
 * worked-examples table is plain-JVM testable: one [QuestionAnswer] per
 * question (every question, visited or not), labels in **option order**
 * (a duplicate label lights up twice), followed by the free text — raw
 * for `multiline` (even empty), whitespace-trimmed via [trimIos] and
 * only when non-empty otherwise. Unanswered pages carry `labels = []`,
 * as on iOS.
 */
internal fun buildAnswers(
    items: List<SessionChrome.Question>,
    picks: Map<String, Set<String>>,
    custom: Map<String, String>,
): List<QuestionAnswer> = items.map { q ->
    val picked = picks[q.id].orEmpty()
    val labels = q.options.filter { it in picked }.toMutableList()
    if (q.multiline) {
        labels += custom[q.id].orEmpty()
    } else {
        custom[q.id]?.trimIos()?.takeIf { it.isNotEmpty() }?.let { labels += it }
    }
    QuestionAnswer(questionId = q.id, labels = labels)
}
