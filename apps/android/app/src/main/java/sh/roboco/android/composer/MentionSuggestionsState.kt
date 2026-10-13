package sh.roboco.android.composer

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import sh.roboco.android.BuildConfig
import sh.roboco.android.design.Feedback
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.Motion
import uniffi.roboco_core.FileMatch

/**
 * iOS Mentions.swift:83 — the row title: the last **non-empty** `/`-piece
 * (Swift's `split(separator:)` omits empty pieces), or the whole [path] when
 * none survives (`""`, `"/"`).
 */
internal fun mentionRowName(path: String): String =
    path.split('/').lastOrNull { it.isNotEmpty() } ?: path

/**
 * iOS Mentions.swift:84 — the row subtitle: every piece but the last, joined
 * with `/` (Swift's `components(separatedBy:)` **keeps** empty pieces, so
 * `a//b.rs` → `a/` and `src/components/` → `src/components`). Empty for a
 * bare name, in which case the row draws no dir line.
 */
internal fun mentionRowDir(path: String): String =
    path.split('/').dropLast(1).joinToString("/")

/**
 * iOS insertMention's text math (`ios:Composer/ComposerBar.swift:372-375`):
 * replace `[q.start, q.end)` (the `@` up to the caret) with `"<token> "` and
 * put the caret after the inserted space. `null` when [q] no longer fits
 * [text] or no longer starts at an `@` — the Android guard for a stale
 * range, which iOS would crash on (`NSRange` raises).
 *
 * Indices are UTF-16 code units on both platforms (`NSString` ↔ Kotlin
 * `String`).
 */
internal fun applyMentionPick(text: String, q: MentionQuery, token: String): TextFieldValue? {
    if (q.start < 0 || q.end > text.length || q.start >= text.length || q.start >= q.end) return null
    if (text[q.start] != '@') return null
    val ins = "$token "
    return TextFieldValue(text.replaceRange(q.start, q.end, ins), TextRange(q.start + ins.length))
}

/**
 * The anchor's position and size, in window coordinates — the snapshot key
 * the layer reads in measure so the card follows the composer when it moves
 * (keyboard, text growth, morph). Written by [Modifier.mentionAnchor]'s
 * `onGloballyPositioned`; [None] until the composer has placed.
 */
@Immutable
internal data class AnchorKey(val topLeftInWindow: Offset, val size: IntSize) {
    companion object {
        val None = AnchorKey(Offset.Unspecified, IntSize.Zero)
    }
}

/**
 * The mention card's state holder (android-ui-parity #86): the port of
 * ComposerBar's mention fields + `updateMentionQuery` /
 * `setSuggestionsVisible` / `insertMention`
 * (`ios:Composer/ComposerBar.swift:107-110,336-379`). It owns the debounce
 * job, the query, the rows and the pick; the *target* visibility lives on
 * the controller ([ComposerController.suggestionsVisible]) so 54's `send`
 * can clear it, and the *animated* visibility lives here
 * ([progress], driven by the layer).
 *
 * iOS races fixed on purpose (see the ticket's Roboco adaptation):
 * - blur also **cancels** the in-flight search (iOS lets it finish and a
 *   late result could reopen the card over a resting capsule);
 * - a result that arrives while unfocused or disabled is dropped;
 * - an empty result while shown keeps the last non-empty rows through the
 *   fade-out (iOS first collapses to an 8 pt sliver, then fades).
 *
 * Threading: main thread only. [query] is plain (never read in
 * composition); [files] and [anchorKey] are snapshot state. Created by
 * [rememberMentionSuggestionsState]; registered with the screen's
 * `MentionSuggestionsHost` by [MentionSuggestionsBinding].
 */
@Stable
class MentionSuggestionsState internal constructor(
    /** The composer this card serves; the search, text and target visibility live here. */
    val controller: ComposerController,
    /** The composition's scope (`rememberCoroutineScope`): leaving the screen cancels the debounce. */
    private val scope: CoroutineScope,
    /** Fires [Haptic.Select] on a pick. */
    private val feedback: Feedback,
    /** iOS `Task.sleep(for: .milliseconds(120))` (`ComposerBar.swift:347`); injectable for tests. */
    private val debounceMs: Long = Motion.MentionDebounceMs,
) {

    /** Rows on the card (≤ [MaxRows]). Kept through a fade-out on an empty result. */
    var files: List<FileMatch> by mutableStateOf(emptyList())
        private set

    /**
     * iOS `mentionQuery`. Survives blur (iOS `textViewDidEndEditing` does
     * not clear it); cleared by [pick] and [disable]. Plain state — read
     * only in callbacks, never in composition.
     */
    var query: MentionQuery? = null
        private set

    /** Target visibility (the UIKit model value): the controller flag **and** rows to show. */
    val visible: Boolean
        get() = controller.suggestionsVisible && files.isNotEmpty()

    /** 0 = hidden, 1 = shown; `animateTo`n by the layer over [Motion.mentionList]. Exposed for tests. */
    internal val progress = Animatable(0f)

    /** The field's focus, fed by [MentionSuggestionsBinding] (56's `morph.isFocused`). */
    internal var isFocused: () -> Boolean = { false }

    /** The anchor's coordinates (written by [Modifier.mentionAnchor]); plain, read in placement. */
    internal var anchorCoords: LayoutCoordinates? = null

    /** The anchor's window position + size — the snapshot read that re-lays the card out. */
    internal var anchorKey: AnchorKey by mutableStateOf(AnchorKey.None)

    /** The debounce job (`mentionTask`), cancelled by every recompute, [onBlur] and [disable]. */
    private var job: Job? = null

    /** `textViewDidChange` → `updateMentionQuery`: recompute on every user edit while focused. */
    fun onTextChanged() {
        update()
    }

    /**
     * `textViewDidChangeSelection` (`:526-529`): caret moves recompute
     * **only while a query is active** — moving the caret into an existing
     * `@foo` does not open the card, typing does.
     */
    fun onSelectionChanged() {
        if (query != null) update()
    }

    /**
     * `textViewDidEndEditing` → `setSuggestionsVisible(false)` (`:415-420`):
     * hide and cancel the in-flight search, but **keep** [query] exactly as
     * iOS keeps `mentionQuery`. Idempotent.
     */
    fun onBlur() {
        job?.cancel()
        job = null
        hide()
    }

    /** Mentions off (`canWrite == false` or no search): cancel, forget the query, hide. */
    fun disable() {
        job?.cancel()
        job = null
        query = null
        hide()
    }

    /**
     * `insertMention` (`:369-379`), in order: guard the query → validate the
     * range → [Haptic.Select] → allocate the token → replace `[start, end)`
     * with `token + " "` and put the caret after the space → hide.
     *
     * A stale range (the text was shortened programmatically) hides the card
     * and returns **without** a haptic and without touching the token index
     * — iOS would raise on the `NSRange`. The field keeps focus; a row tap
     * never moves it.
     */
    fun pick(file: FileMatch) {
        val q = query ?: return
        val text = controller.value.text
        if (q.start < 0 || q.end > text.length || q.start >= text.length || text[q.start] != '@') {
            hide()
            return
        }
        feedback.haptic(Haptic.Select)
        val token = controller.mentions.token(file)
        applyMentionPick(text, q, token)?.let { controller.value = it }
        query = null
        job?.cancel()
        job = null
        controller.suggestionsVisible = false
    }

    /**
     * `updateMentionQuery` (`:336-359`) with Roboco's `canWrite` gate:
     * recompute the query synchronously, cancel the pending job, and on a
     * query debounce [debounceMs] before searching. The result is applied
     * only when the job was not cancelled, the query string still matches,
     * the field is still focused and mentions are still enabled — the
     * stale-drop rule (only the **query string** is compared, as iOS does).
     * A `null` query hides at once, without waiting for the debounce.
     */
    private fun update() {
        if (!controller.mentionsEnabled) {
            disable()
            return
        }
        val q = MentionIndex.activeQuery(controller.value)
        query = q
        job?.cancel()
        if (q == null) {
            hide()
            return
        }
        job = scope.launch {
            delay(debounceMs)
            val search = controller.mentionSearch ?: return@launch
            val result = try {
                search(q.query)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            ensureActive() // !Task.isCancelled
            if (query?.query != q.query || !isFocused() || !controller.mentionsEnabled) return@launch
            if (result.isNotEmpty()) files = result.take(MaxRows)
            controller.suggestionsVisible = result.isNotEmpty()
        }
    }

    /** `setSuggestionsVisible(false)`'s model write; the layer animates it. */
    private fun hide() {
        controller.hideSuggestions()
    }

    companion object {
        /** iOS `prefix(5)` (`Mentions.swift:81`); the engine caps the reply at 8. */
        const val MaxRows = 5
    }
}

/**
 * One [MentionSuggestionsState] per composer: the ambient [Feedback] and
 * the composition's own scope, created once and kept across recompositions.
 */
@Composable
fun rememberMentionSuggestionsState(controller: ComposerController): MentionSuggestionsState {
    val scope = rememberCoroutineScope()
    val feedback = LocalFeedback.current
    return remember(controller) { MentionSuggestionsState(controller, scope, feedback) }
}

/**
 * The observers + registration that glue the state to the screen
 * (android-ui-parity #86 step 5). Call once, inside the composer assembly
 * (78's `ComposerBar`):
 *
 * - [SideEffect] feeds [MentionSuggestionsState.isFocused];
 * - a `DisposableEffect` registers the state with the screen's
 *   `MentionSuggestionsHost` through `LocalMentionLayerSlot` — the Compose
 *   form of iOS's `root.addSubview(suggestions)` (`didMoveToWindow`);
 * - a `snapshotFlow` over the controller's `(text, selection)` stands in
 *   for the UIKit text/selection delegate, gated on focus: only **user**
 *   edits happen while focused, so a programmatic draft restore does not
 *   open the card over a resting capsule. IME composition-only changes
 *   emit the same pair and are filtered;
 * - `canWrite == false` (offline engine, 79) disables mentions outright;
 * - focus loss runs [MentionSuggestionsState.onBlur] — the port of
 *   `textViewDidEndEditing`'s `setSuggestionsVisible(false)`, whatever
 *   composable created the morph state (56's `hideSuggestions` hook is not
 *   wired by 78). [isFocused] must read snapshot state for the flow to see
 *   it.
 */
@Composable
fun MentionSuggestionsBinding(state: MentionSuggestionsState, isFocused: () -> Boolean) {
    SideEffect { state.isFocused = isFocused }

    val slot = LocalMentionLayerSlot.current
    if (slot == null && BuildConfig.DEBUG && !warnedAboutMissingHost) {
        warnedAboutMissingHost = true
        Log.w("RobocoMentions", "no MentionSuggestionsHost: the card will never show")
    }
    DisposableEffect(slot, state) {
        slot?.state = state
        onDispose {
            if (slot?.state === state) slot.state = null
            state.anchorCoords = null
            state.anchorKey = AnchorKey.None
        }
    }

    // Edit observer: the UIKit delegate. `now == last` skips the first
    // emission and composition-only changes; the focus gate skips
    // programmatic writes while the composer rests.
    LaunchedEffect(state) {
        var last = state.controller.value.let { it.text to it.selection }
        snapshotFlow { state.controller.value.let { it.text to it.selection } }.collect { now ->
            if (now == last) return@collect
            val textChanged = now.first != last.first
            last = now
            if (!state.isFocused()) return@collect
            if (textChanged) state.onTextChanged() else state.onSelectionChanged()
        }
    }

    // Offline observer: a visible card hides the moment the engine dies.
    LaunchedEffect(state) {
        snapshotFlow { state.controller.canWrite }.collect { if (!it) state.disable() }
    }

    // Blur observer: the first emission (false, before focus) just runs a
    // harmless, idempotent onBlur().
    val focused by rememberUpdatedState(isFocused)
    LaunchedEffect(state) {
        snapshotFlow { focused() }.collect { if (!it) state.onBlur() }
    }
}

/** Warn-once for a binding without a host (debug builds only). */
private var warnedAboutMissingHost = false

/**
 * On the composer's glass container — the **same node** as 56's
 * `composerMorphHost`, whose bounds are the composer glass. The card's
 * width, x and bottom (− 8 dp) derive from this node's coordinates; never
 * put it on the bottom-stack slot or an outer padding box.
 */
fun Modifier.mentionAnchor(state: MentionSuggestionsState): Modifier = onGloballyPositioned { c ->
    state.anchorCoords = c
    val key = AnchorKey(c.positionInWindow(), c.size)
    if (state.anchorKey != key) state.anchorKey = key
}
