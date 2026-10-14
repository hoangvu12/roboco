package sh.roboco.android.composer

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Feedback
import sh.roboco.android.design.GlassStage
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Motion
import sh.roboco.android.design.NoFeedback
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.design.uiStyle
import sh.roboco.android.session.BottomStackSlotScope
import sh.roboco.android.session.SessionChrome
import sh.roboco.android.session.SessionController

/**
 * The question panel's screen glue (android-ui-parity #91): the state
 * ([rememberQuestionPanelState]) fed from the controller's chrome, and
 * the slot composable ([SessionQuestionsSlot]) that puts ticket 90's
 * card into ticket 74's questions slot with the 200 ms page
 * cross-dissolve of `ios:Composer/QuestionPanel.swift:187-190`.
 */
internal object QuestionPanelTags {

    /** The fading page container (Android-only test hook; iOS has none). */
    const val PAGE = "question-page"
}

/** One composed page of the fade: a reset changes the generation, a page move does not. */
private data class PageKey(val generation: Int, val page: Int)

/** The gallery's "the request resolved" delay after a submit (the ticket's step 8). */
private const val GalleryResolveAfterMs = 600L

// ---------------------------------------------------------------------------
// The state, fed from the chrome
// ---------------------------------------------------------------------------

/**
 * The screen-scoped [QuestionPanelState] (91's step 5 — call in the
 * session screen, NOT inside the slot): created once per controller and
 * configured from the **controller's chrome flow on main** — the first
 * value inside [remember] so the first frame is already correct, then a
 * [LaunchedEffect] collector for every later emission (never a
 * `SideEffect`/keyed effect: those run after the frame, so the slot
 * would compose one frame with stale items while 74 measures it).
 *
 * Submitting routes through [actions] ([QuestionActions], off main); a
 * failed RPC re-arms the panel's double-submit guard and nothing else
 * (decision COMPOSER-D2). [canWrite][QuestionPanelState.canWrite] rides
 * along from the chrome (ticket 79).
 */
@Composable
fun rememberQuestionPanelState(
    controller: SessionController,
    actions: QuestionActions,
): QuestionPanelState {
    val feedback = LocalFeedback.current
    val scope = rememberCoroutineScope()
    val state = remember(controller) {
        lateinit var self: QuestionPanelState
        self = QuestionPanelState(scope, feedback, onSubmit = { requestId, answers ->
            // Re-arm only; no toast (decision COMPOSER-D2).
            actions.respond(requestId, answers) { self.onSubmitFailed(requestId) }
        })
        val chrome = controller.chrome.value
        self.configure(chrome.questions)
        self.canWrite = chrome.canWrite
        self
    }
    LaunchedEffect(state) {
        controller.chrome.collect { chrome ->
            state.configure(chrome.questions)
            state.canWrite = chrome.canWrite
        }
    }
    return state
}

// ---------------------------------------------------------------------------
// The slot
// ---------------------------------------------------------------------------

/**
 * The question panel in ticket 74's questions slot (91's step 6):
 * [QuestionCardGlass][sh.roboco.android.composer.QuestionCardGlass] (90)
 * wrapping one [AnimatedContent] whose target is [PageKey], so a page
 * move cross-dissolves the card **content** — 200 ms easeInOut
 * ([Motion.questionPage]), both pages interactive (iOS
 * `.allowUserInteraction`) — while the glass itself never fades, and a
 * reset (a new generation) swaps instantly. The size snaps to the new
 * page's height in the first frame on **both** branches
 * (`SizeTransform(clip = true) { snap() }`): the default would spring
 * it. Content is top-aligned.
 *
 * The state ignores the outgoing page's actions (every callback carries
 * its page index), so a tap during the fade changes nothing. Focus:
 * composing the slot never steals focus from the composer; when the
 * questions go away (`requestId == null`) and the panel itself holds
 * focus, it is cleared so the IME goes down while the slot collapses
 * (Roboco adaptation — 74 keeps the slot composed; iOS hides the view).
 * After IME Done the incoming page's Other field takes the focus the
 * outgoing field kept, so the keyboard stays up across the page move
 * (iOS: the same `UITextField` stays first responder).
 */
@Composable
fun BottomStackSlotScope.SessionQuestionsSlot(
    state: QuestionPanelState,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    var panelFocused by remember { mutableStateOf(false) }
    var otherFocused by remember { mutableStateOf(false) }
    var refocusOther by remember { mutableStateOf(false) }

    // Clear only the focus the panel owns: an unconditional clearFocus()
    // on first composition (requestId == null) would steal the
    // composer's.
    LaunchedEffect(state.requestId) {
        if (state.requestId == null) {
            refocusOther = false
            if (panelFocused) focus.clearFocus()
        }
    }

    QuestionCardGlass(visible = glassVisible, animate = animateGlass, modifier = modifier) {
        if (state.items.isEmpty()) return@QuestionCardGlass // nothing configured yet
        // A reset (new generation) recreates the AnimatedContent: a fresh
        // one composes and measures only the new first page in the same
        // frame, while a target change on the old one would keep the old
        // page and its height for one more frame (the Transition only
        // advances — even a snap — on its next frame callback).
        key(state.generation) {
            AnimatedContent(
                targetState = PageKey(state.generation, state.page),
                modifier = Modifier
                    .onFocusChanged { panelFocused = it.hasFocus }
                    .testTag(QuestionPanelTags.PAGE)
                    .semantics { stateDescription = "page ${state.page + 1} of ${state.count}" },
                transitionSpec = {
                    val content = if (initialState.generation != targetState.generation) {
                        // A reset (or a pick) renders instantly, like UIKit's
                        // plain render() — never a fade.
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        // UIView.transition(duration: 0.2, options:
                        // [.transitionCrossDissolve, .allowUserInteraction]):
                        // default curve easeInOut, both pages take touches.
                        fadeIn(Motion.questionPage()) togetherWith fadeOut(Motion.questionPage())
                    }
                    // Both branches snap the size (AnimatedContent's default
                    // SizeTransform springs the height; iOS applies the new
                    // height in the next layout pass, unanimated).
                    content using SizeTransform(clip = true) { _, _ -> snap() }
                },
                contentAlignment = Alignment.TopStart,
                label = "questionPage",
            ) { key ->
                val q = state.items.getOrNull(key.page) ?: return@AnimatedContent
                val requester = remember { FocusRequester() }
                if (!q.multiline) {
                    // Refocus after Done: the incoming page's Other field
                    // takes over so the IME stays up. The flag is set only
                    // when a non-multiline page follows an answered one, so
                    // the requester is always attached here.
                    LaunchedEffect(Unit) {
                        if (refocusOther) {
                            refocusOther = false
                            requester.requestFocus()
                        }
                    }
                }
                QuestionPageContent(
                    model = QuestionPageModel(
                        header = q.header,
                        page = key.page,
                        pageCount = state.count,
                        question = q.text,
                        options = q.options,
                        picked = state.picked(q.id),
                        multiSelect = q.multiSelect,
                        multiline = q.multiline,
                        customText = state.customText(q.id),
                        // The live page gates on the full rule (answered plus
                        // the canWrite gate on Submit); the outgoing page
                        // greys out instead of showing the new page's state.
                        canContinue = key.page == state.page && state.nextEnabled,
                    ),
                    actions = QuestionPageActions(
                        onPick = { option -> state.pick(key.page, option) },
                        onCustomChange = { text -> state.setCustom(key.page, text) },
                        onOtherDone = {
                            val next = state.items.getOrNull(key.page + 1)
                            if (otherFocused && next != null && !next.multiline && state.nextEnabled) {
                                refocusOther = true
                            }
                            state.otherDone(key.page)
                        },
                        onBack = { state.back() },
                        onNext = { state.next() },
                    ),
                    otherFocusRequester = if (q.multiline) null else requester,
                    onOtherFocusChanged = { otherFocused = it },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The fixtures (91's step 8): F1 is upstream's demo question
// (transcripts.rs:801-815), F2 is Roboco's mock harness set
// (crates/harness/src/mock.rs:19-45), F3 the multiline + prefill pair,
// Dup the duplicate-id guard.
// ---------------------------------------------------------------------------

internal object QuestionPanelFixtures {

    val F1 = SessionChrome.Questions(
        requestId = "r1",
        items = listOf(
            SessionChrome.Question(
                id = "q1",
                header = "Scope",
                text = "Should the fix cover Android too?",
                options = listOf("Yes, both platforms", "iOS only"),
                multiSelect = false,
            ),
        ),
    )

    val F2 = SessionChrome.Questions(
        requestId = "r2",
        items = listOf(
            SessionChrome.Question(
                id = "q-sync",
                header = "Question",
                text = "Which sync strategy should the rewrite use?",
                options = listOf(
                    "Poll the doc host every 120ms",
                    "Event-driven fold with coalesced commits",
                    "Hybrid: event-driven with a polling fallback",
                ),
                multiSelect = false,
            ),
            SessionChrome.Question(
                id = "q-gates",
                header = "Question",
                text = "Which suites should gate the merge?",
                options = listOf("Unit tests", "End-to-end (two-device)", "Golden screenshots"),
                multiSelect = true,
            ),
        ),
    )

    val F3 = SessionChrome.Questions(
        requestId = "r3",
        items = listOf(
            SessionChrome.Question(
                id = "a",
                header = "Tone",
                text = "Pick a tone",
                options = listOf("Formal", "Casual"),
                multiSelect = false,
            ),
            SessionChrome.Question(
                id = "m",
                header = "Notes",
                text = "Anything else?",
                options = emptyList(),
                multiSelect = false,
                prefill = "draft",
                multiline = true,
            ),
        ),
    )

    /** Two questions with one id: the first prefill wins, both pages share picks and text. */
    val Dup = SessionChrome.Questions(
        requestId = "r-dup",
        items = listOf(
            SessionChrome.Question(
                id = "x",
                header = "Question",
                text = "First copy",
                options = listOf("First", "Second"),
                multiSelect = false,
                prefill = "p1",
            ),
            SessionChrome.Question(
                id = "x",
                header = "Question",
                text = "Second copy",
                options = listOf("First", "Second"),
                multiSelect = false,
                prefill = "p2",
            ),
        ),
    )
}

/**
 * The F2-picked fixture state the previews, the Roborazzi goldens and
 * the behaviour tests share: configured with [QuestionPanelFixtures.F2],
 * option 1 of page 0 picked, `autoAdvanceMs = Long.MAX_VALUE` so the
 * pick never advances (page 0, one option picked).
 */
internal fun questionFixtureState(
    scope: CoroutineScope,
    feedback: Feedback = NoFeedback,
): QuestionPanelState {
    val state = QuestionPanelState(scope, feedback, onSubmit = { _, _ -> }, autoAdvanceMs = Long.MAX_VALUE)
    state.configure(QuestionPanelFixtures.F2)
    state.pick(0, "Event-driven fold with coalesced commits")
    return state
}

/** The previews' and goldens' slot scope: shown, glass on, no materialize animation. */
internal object PreviewSlotScope : BottomStackSlotScope {
    override val shown: Boolean = true
    override val glassVisible: Boolean = true
    override val animateGlass: Boolean = false
}

// ---------------------------------------------------------------------------
// The gallery section (ticket 22's registry, registered in
// debug/…/design/Gallery.kt)
// ---------------------------------------------------------------------------

/**
 * The "Question panel behaviour" page of the debug design gallery
 * (91's step 8): the real [SessionQuestionsSlot] over a live
 * [QuestionPanelState] with a fake `onSubmit` — the answers' `toString()`
 * lands in the caption under the stage and the request "resolves"
 * (`configure(null)`) 600 ms later, like the engine would. The buttons
 * switch the ticket's fixtures; "Offline" toggles [canWrite][QuestionPanelState.canWrite]
 * (Submit disables, the picks survive); "Fail next submit" injects
 * [onSubmitFailed][QuestionPanelState.onSubmitFailed], re-arming Submit
 * with nothing shown (decision COMPOSER-D2). Foundation rows only — no
 * Material controls.
 */
@Composable
fun QuestionPanelBehaviourGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val feedback = LocalFeedback.current

    var caption by remember { mutableStateOf("Pick a fixture; Submit answers into this line.") }
    var offline by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val state = remember(scope, feedback) {
        lateinit var self: QuestionPanelState
        self = QuestionPanelState(scope, feedback, onSubmit = { _, answers ->
            caption = answers.toString()
            scope.launch {
                delay(GalleryResolveAfterMs)
                self.configure(null)
            }
        })
        self.configure(QuestionPanelFixtures.F1)
        self
    }
    SideEffect { state.canWrite = !offline }

    Column(Modifier.fillMaxWidth()) {
        QuestionPanelStage(state, height = 520.dp)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            BehaviourRow("F1") { state.configure(QuestionPanelFixtures.F1) }
            BehaviourRow("F2") { state.configure(QuestionPanelFixtures.F2) }
            BehaviourRow("F3 (multiline + prefill)") { state.configure(QuestionPanelFixtures.F3) }
            BehaviourRow("Dup ids") { state.configure(QuestionPanelFixtures.Dup) }
            BehaviourRow("Offline", if (offline) "on" else "off") { offline = !offline }
            BehaviourRow("Fail next submit") {
                val id = state.requestId
                if (id == null) {
                    caption = "No live request to fail."
                } else {
                    state.onSubmitFailed(id)
                    caption = "Answer failed for $id — Submit re-armed."
                }
            }
            BasicText(
                caption,
                style = uiStyle(Face.Mono, typeScale.fixed(12f), palette.secondary),
                maxLines = 1,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

/** One gallery row: label left, current value in accent right (no Material). */
@Composable
private fun BehaviourRow(label: String, value: String = "", onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClickLabel = label, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.text))
        Spacer(Modifier.weight(1f))
        if (value.isNotEmpty()) {
            BasicText(value, style = uiStyle(Face.SansMedium, typeScale.fixed(15f), palette.accent))
        }
    }
}

// ---------------------------------------------------------------------------
// The shared stage and the previews (91's step 8): F2 page 1 with option 1
// picked, in 90's stage
// ---------------------------------------------------------------------------

/**
 * The shared 388 dp stage: the striped backdrop's sibling carries the
 * panel (ticket 90's B1 stage is 480 dp for the same page height; the
 * gallery gives the taller fixtures 520 dp). The Roborazzi goldens
 * mount through it too, so their geometry never diverges.
 */
@Composable
internal fun QuestionPanelStage(state: QuestionPanelState, height: Dp) {
    ProvideHazeState {
        GlassStage(
            modifier = Modifier.width(388.dp),
            height = height,
            backdropModifier = Modifier.hazeSource(LocalHazeState.current!!),
        ) {
            with(PreviewSlotScope) { SessionQuestionsSlot(state) }
        }
    }
}

@Composable
private fun QuestionBehaviourPreviewBody() {
    val scope = rememberCoroutineScope()
    val state = remember(scope) { questionFixtureState(scope) }
    QuestionPanelStage(state, height = 480.dp)
}

@Preview(name = "Question behaviour – light")
@Composable
private fun QuestionBehaviourLightPreview() {
    RobocoTheme(Appearance.Light) { QuestionBehaviourPreviewBody() }
}

@Preview(name = "Question behaviour – dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun QuestionBehaviourDarkPreview() {
    RobocoTheme(Appearance.Dark) { QuestionBehaviourPreviewBody() }
}
