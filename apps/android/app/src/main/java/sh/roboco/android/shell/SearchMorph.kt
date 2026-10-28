package sh.roboco.android.shell

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import sh.roboco.android.core.Face
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.GlassStage
import sh.roboco.android.design.IosSpring
import sh.roboco.android.design.Icon
import sh.roboco.android.design.IconInBox
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassSurface
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.uiStyle

/**
 * The search morph's constants (android-ui-parity #29).
 *
 * - [Spring] is the morph's spring, `IosSpring("searchMorph", 0.40, 0.85f)`
 *   → ζ 0.85, settle-matched stiffness. It carries the **same numbers** as
 *   ticket 28's `Motion.TabBarMinimize` but is a separate constant: a
 *   different system animation, tuned separately (28 forbids sharing
 *   tokens across sites), kept here — not in `Motion` — so ticket 04's
 *   `allSprings` count is unaffected. Checkpoints from rest to 1:
 *   100 ms → 0.663, 200 ms → 0.974, peak ≈ 1.006 at ≈ 294 ms,
 *   500 ms → 1.000. Interruptible: reversing mid-flight continues from
 *   the current value and velocity.
 */
object SearchMorph {

    /** The bar → search-row morph spring. */
    val Spring = IosSpring("searchMorph", 0.40, 0.85f)

    /** The placeholder, verbatim (`SessionListController.swift:527`). */
    const val Placeholder = "Sessions, projects, branches"

    /** The field's content description (the `UISearchBar`'s own label). */
    const val FieldDescription = "Search"

    /** The return circle's label (the UI test taps "Close", `#577`). */
    const val CloseDescription = "Close"

    /** VoiceOver's label for the system clear button. */
    const val ClearDescription = "Clear text"

    /**
     * `p` above this counts as "the search chrome is showing": the
     * composition gates (the pill slot's switch, the text subtree) flip
     * once per morph, not per frame.
     */
    const val VisibleThreshold = 0.001f

    /**
     * True while the search chrome must be composed / placed / ride the
     * IME: the **target** (Search is selected), or a morph in flight. The
     * one shared predicate so the layout's `searchOn` and the slots'
     * composition gates can never drift apart.
     */
    fun chromeShowing(active: Boolean, p: Float): Boolean =
        active || p > VisibleThreshold
}

/**
 * The accessory's alpha while the search row rides on the IME:
 * `1 − clamp(lift / 56)`, 56 = accessory 48 + gap 8 — exactly 0 by the time
 * the row's top edge reaches the accessory's top edge. Never removes the
 * accessory from composition (upstream #577).
 */
fun accessoryImeAlpha(imeLiftDp: Float): Float =
    1f - (imeLiftDp / SearchMorphGeometry.AccessoryFadeSpan).coerceIn(0f, 1f)

/**
 * The search morph's progress `p` (0 = normal bar, 1 = search row) as a
 * [State]. The initial value **is** the target, so launching straight into
 * Search shows the search row without a morph; every later flip animates
 * with [SearchMorph.Spring], interruptibly from the current value.
 * Under Reduce Motion ([LocalReduceMotion]) it snaps.
 *
 * Read it in measure / placement / draw lambdas (or in the small
 * composition gates that flip once) — never in a body that recomposes per
 * frame.
 */
@Composable
fun rememberSearchMorphProgress(searchSelected: Boolean): State<Float> {
    val reduce = LocalReduceMotion.current
    val animatable = remember { Animatable(if (searchSelected) 1f else 0f) }
    LaunchedEffect(searchSelected, reduce) {
        val target = if (searchSelected) 1f else 0f
        if (reduce) {
            animatable.snapTo(target)
        } else {
            animatable.animateTo(target, SearchMorph.Spring.spec())
        }
    }
    return remember { derivedStateOf { animatable.value } }
}

// ---------------------------------------------------------------------------
// The field (the search circle, stretched)
// ---------------------------------------------------------------------------

/**
 * The search slot (android-ui-parity #29): **at every** morph position it
 * is one glass piece — ticket 26's round search circle at `p = 0`
 * (pixel-identical: the same `CapsuleShape` — a capsule over a square is
 * the circle —, magnifier at 22 pt in `text`, `selectable(Role.Tab)` /
 * "Search" / `tab-search`, the shared-source press swell), stretching into
 * the 48 dp **field capsule** as `p → 1`. [TabChromeLayout] measures it at
 * `SearchMorphGeometry.fieldRect(search0, rowWidth, p)`.
 *
 * While Search is the selected tab ([active] — the *target*, so the
 * semantics flip with the selection, not mid-morph) the slot is the field:
 * `interactive = false`, no tab semantics; a tap anywhere on the capsule
 * (magnifier included) focuses the field and shows the IME. While Search
 * is not selected it carries ticket 26's semantics and calls
 * [onSearchTabTap] (26's `onTabTap(Search)`, haptic there).
 *
 * The content is laid out at the **final** field rect and clipped by the
 * capsule's shape, so the text never reflows mid-morph:
 *  - the magnifier — 22 pt centred at `p = 0`, 17 pt (20.4 dp box) leading
 *    14 at `p = 1`, tint `lerp(text, secondary, p)` — placed by the custom
 *    [Layout] from `SearchMorphGeometry.magnifierCenterX` (its size and
 *    tint are the one per-frame composition read, bounded to this slot);
 *  - the text and placeholder — Geist Regular 17 `fixed`, one line,
 *    [SearchTabState.text] via the state-based `BasicTextField` (IME
 *    action Search, no autocorrect — identifiers), the placeholder in
 *    `tertiary`, both faded with `contentAlpha(p)`, the caret `accent`;
 *  - the clear button — 17 pt `XmarkCircleFill` in `tertiary`, 44 × 48
 *    touch target 12 ★ from the trailing edge, visible iff [active] and
 *    the text is non-empty (no animation — UIKit swaps it instantly);
 *    tapping it empties the query and focuses the field.
 *
 * Auto-focus ([SearchTabState.activationToken]) lands one frame after the
 * field is composed — the caret at the end — with the IME shown
 * (`automaticallyActivatesSearch`). The IME "Search" key hides the IME and
 * clears focus (the text is kept). The IME hidden **while focused** (system
 * back) clears the focus too — no orphan caret — but only after the IME has
 * been seen up, never right after focus. Leaving the tab clears the focus
 * at selection time.
 *
 * Not this ticket's: the results list behind the field (49), the IME lift
 * the *list* adds (49 — this ticket lifts the row and fades the accessory).
 *
 * @param state the query holder; its text survives tab switches
 * @param progress the morph position 0…1 (unclamped), read in layout and
 *   draw lambdas (and the magnifier's two composition params)
 * @param rowWidth R = `tabBarRowWidth(...)` (26), for the final content
 *   frame. (The ticket's signature also passed `search0`, the circle rect
 *   at p = 0; the magnifier instead tracks the slot's **measured** width —
 *   `TabChromeLayout` measures this slot at `fieldRect(search0, rowWidth,
 *   p)` for the *current* minimize position, so the glyph stays centred
 *   even entering Search from a minimized bar, without a per-frame
 *   recomposition to hand the rect down.)
 * @param active Search is the selected tab (the target)
 * @param onSearchTabTap 26's `onTabTap(TabId.Search)`, used while `!active`
 * @param held wave-10 37: whether the field may keep its focus. The
 *   one-row bar stays composed under a pushed page, so a push flips this
 *   off and a focused field lets go (IME hidden) — the old Search screen's
 *   leave. Only this field's own focus is cleared.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchTabField(
    state: SearchTabState,
    progress: () -> Float,
    rowWidth: Float,
    active: Boolean,
    onSearchTabTap: () -> Unit,
    modifier: Modifier = Modifier,
    held: Boolean = true,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val searchSource = remember { MutableInteractionSource() }
    val focused by searchSource.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }

    // Composition flips, once per morph / per edit batch: the text subtree
    // appears with the search chrome, the clear with (non-empty ∧ active).
    val showText by remember(active) {
        derivedStateOf { SearchMorph.chromeShowing(active, progress()) }
    }
    val hasText by remember(state) { derivedStateOf { state.text.text.isNotEmpty() } }
    val imeVisible = WindowInsets.isImeVisible

    // Auto-focus: one token per entry into Search, one frame after the
    // field is composed (requesting focus on the same frame a requester is
    // first attached throws "FocusRequester is not initialized"), the
    // caret placed at the end of the surviving text. A recreated
    // composition (rotation) does not refocus: the token was consumed.
    LaunchedEffect(state.activationToken) {
        val token = state.activationToken
        if (token == 0 || token == state.consumedToken) return@LaunchedEffect
        state.consumedToken = token
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
        state.text.edit { placeCursorAtEnd() }
    }

    // Leaving the tab clears the focus at selection time, so the IME hides
    // (26's select already flipped the target; the morph is still running).
    val wasActive = remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (!active && wasActive.value) focusManager.clearFocus(force = true)
        wasActive.value = active
    }

    // A push over the one-row bar: the field lets go before the page lands.
    LaunchedEffect(held) {
        if (!held && focused) {
            keyboard?.hide()
            focusManager.clearFocus(force = true)
        }
    }

    // The orphan-caret guard: with the IME up and the field focused, the
    // IME hiding (system back, swipe) must drop the caret. `sawIme` is only
    // ever set once the IME has actually been seen up — never right after
    // focus, while it is still rising.
    var sawIme by remember { mutableStateOf(false) }
    LaunchedEffect(focused, imeVisible) {
        if (focused && imeVisible) sawIme = true
    }
    LaunchedEffect(imeVisible) {
        if (!imeVisible && sawIme) {
            focusManager.clearFocus()
            sawIme = false
        }
    }

    GlassSurface(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (active) {
                    // A tap anywhere on the capsule (the magnifier included)
                    // focuses the field and raises the IME.
                    Modifier.clickable(
                        interactionSource = searchSource,
                        indication = null,
                    ) {
                        runCatching { focusRequester.requestFocus() }
                        keyboard?.show()
                    }
                } else {
                    // Ticket 26's circle: the tab semantics and the haptic tap.
                    Modifier.selectable(
                        selected = false,
                        interactionSource = searchSource,
                        indication = null,
                        role = Role.Tab,
                    ) { onSearchTabTap() }
                },
            )
            .then(
                if (active) {
                    // The field carries no tab semantics while Search is
                    // selected (the text field's own node is the "Search").
                    Modifier
                } else {
                    // Ticket 26's circle: the tab semantics, the label and
                    // the tag, over the shared-source press swell.
                    Modifier
                        .semantics { contentDescription = SearchMorph.FieldDescription }
                        .testTag("tab-search")
                },
            ),
        shape = CapsuleShape,
        interactive = !active,
        interactionSource = searchSource,
    ) {
        Layout(
            content = {
                SearchMagnifier(progress)
                if (showText) {
                    SearchFieldText(
                        state = state,
                        hasText = hasText,
                        progress = progress,
                        focusRequester = focusRequester,
                        interactionSource = searchSource,
                    )
                }
                if (active && hasText) {
                    SearchClearButton(
                        state = state,
                        focused = focused,
                        focusRequester = focusRequester,
                    )
                }
            },
        ) { measurables, constraints ->
            val p = progress()
            val height = constraints.maxHeight
            // The content frame is the FINAL field rect: laid out once at
            // its final width, clipped by the capsule's shape while the
            // glass is still stretching — it never reflows mid-morph.
            val finalRect = SearchMorphGeometry.fieldTarget(rowWidth)
            val clear = measurables.getOrNull(2)
            val textEnd = SearchMorphGeometry.textEnd(clear != null)
            val textWidth =
                (finalRect.w - SearchMorphGeometry.TextStart - textEnd).dp.roundToPx()
            val textHeight = SearchMorphGeometry.Circle.dp.roundToPx()
            val magnifier = measurables.getOrNull(0)?.measure(Constraints())
            val textField = measurables.getOrNull(1)?.measure(Constraints.fixed(textWidth, textHeight))
            val clearBox = clear?.measure(
                Constraints.fixed(
                    SearchMorphGeometry.ClearTouchWidth.dp.roundToPx(),
                    textHeight,
                ),
            )

            // Placement coordinates are precomputed in the measure block:
            // the placement scope is not a Density. The magnifier follows
            // the field's ACTUAL current rect — the slot was measured at
            // `fieldRect(search0, rowWidth, p)` for the *current* minimize
            // position too, so using the measured width keeps the glyph
            // centred even when entering Search from a minimized bar.
            val currentFieldW = constraints.maxWidth.toDp().value
            val centreXpx = SearchMorphGeometry.magnifierCenterX(
                BarRect(0f, 0f, currentFieldW, currentFieldW),
                p,
            ).dp.roundToPx()
            val textXpx = SearchMorphGeometry.TextStart.dp.roundToPx()
            val clearXpx = SearchMorphGeometry.clearTouchStart(finalRect.w).dp.roundToPx()

            layout(constraints.maxWidth, constraints.maxHeight) {
                magnifier?.let { m ->
                    // (2·centre − w) / 2, integer division — the same
                    // centring arithmetic a centred Box applies, so the
                    // p = 0 glyph matches 26's circle exactly.
                    m.placeRelative((centreXpx * 2 - m.width) / 2, (height - m.height) / 2)
                }
                textField?.let { t ->
                    t.placeRelative(textXpx, (height - t.height) / 2)
                }
                clearBox?.let { c ->
                    c.placeRelative(clearXpx, (height - c.height) / 2)
                }
            }
        }
    }
}

/** The magnifier: the one content piece whose params follow `p` in composition. */
@Composable
private fun SearchMagnifier(progress: () -> Float) {
    val palette = RobocoTheme.palette
    val p = progress()
    Icon(
        RobocoIcons.Magnifyingglass,
        tint = lerp(palette.text, palette.secondary, p.coerceIn(0f, 1f)),
        sizePt = SearchMorphGeometry.magnifierPt(p),
    )
}

/**
 * The text, placeholder and IME wiring — the state-based `BasicTextField`
 * over [SearchTabState.text].
 */
@Composable
private fun SearchFieldText(
    state: SearchTabState,
    hasText: Boolean,
    progress: () -> Float,
    focusRequester: FocusRequester,
    interactionSource: MutableInteractionSource,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    BasicTextField(
        state = state.text,
        modifier = Modifier
            .focusRequester(focusRequester)
            .testTag("search-field")
            .semantics { contentDescription = SearchMorph.FieldDescription }
            .graphicsLayer { alpha = SearchMorphGeometry.contentAlpha(progress()) },
        textStyle = uiStyle(Face.Sans, typeScale.fixed(17f), palette.text),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Search,
        ),
        onKeyboardAction = object : KeyboardActionHandler {
            override fun onKeyboardAction(performKeyboardAction: () -> Unit) {
                // The IME "Search" key: hide the IME, clear the focus; the
                // text stays and the row drops back to its rest inset.
                keyboard?.hide()
                focusManager.clearFocus()
            }
        },
        lineLimits = TextFieldLineLimits.SingleLine,
        interactionSource = interactionSource,
        cursorBrush = SolidColor(palette.accent),
        decorator = TextFieldDecorator { innerTextField ->
            Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                if (!hasText) {
                    BasicText(
                        SearchMorph.Placeholder,
                        style = uiStyle(Face.Sans, typeScale.fixed(17f), palette.tertiary),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
                innerTextField()
            }
        },
    )
}

/**
 * The clear button: 17 pt `XmarkCircleFill` in `tertiary`, the 44 × 48
 * touch target from [SearchMorphGeometry.clearTouchStart]. Tapping it
 * empties the query at once — and focuses the field if it was not focused
 * (UIKit's `UITextField` begins editing on clear).
 */
@Composable
private fun SearchClearButton(
    state: SearchTabState,
    focused: Boolean,
    focusRequester: FocusRequester,
) {
    val palette = RobocoTheme.palette
    val keyboard = LocalSoftwareKeyboardController.current
    Box(
        Modifier
            .testTag("search-clear")
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
            ) {
                state.text.clearText()
                if (!focused) {
                    runCatching { focusRequester.requestFocus() }
                    keyboard?.show()
                }
            }
            .clearAndSetSemantics {
                contentDescription = SearchMorph.ClearDescription
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(RobocoIcons.XmarkCircleFill, tint = palette.tertiary, sizePt = 17f)
    }
}

// ---------------------------------------------------------------------------
// The return circle (the pill, collapsed)
// ---------------------------------------------------------------------------

/**
 * The pill slot while the search chrome is on screen
 * (android-ui-parity #29): the glass capsule — `CapsuleShape`, a circle at
 * the 48 dp target — with **two content layers**:
 *
 *  - ticket 26's expanded pill (platter + both cells, via
 *    [TabPillCellsAndPlatter]) fading out with `pillItemsAlpha(p)` —
 *    gone by p = 0.5 — and, when [active] holds, also gated by ticket 28's
 *    minimize progress (entering Search from a minimized bar keeps the
 *    expanded content hidden until 28's spring relaxes, so the joint morph
 *    is continuous: at `m = 1, p = 0` this is 28's minimized look);
 *  - the **previous** tab's 24 dp glyph (`MainTabState.previousTab`:
 *    `TabChat` or `TabSettings`, `text`) centred in the rect, fading in
 *    with `collapsedIconAlpha(p)` — from p = 0.5 —, or at full alpha while
 *    the pill is minimized (`max(m̂, collapsedIconAlpha(p))`, the same
 *    continuity rule).
 *
 * While [active] (target-based, so it can be tapped mid-morph) a
 * full-size overlay carries the **"Close"** button — `search-close`,
 * `Role.Button` — which clears the focus and calls [onReturn] (back to the
 * tab you came from). While Search is not selected the fading cells stay
 * hit-testable (26's own `onTabTap` wiring, haptic there), and at p = 0
 * settled the slot hands back to `GlassTabPill` pixel-identically.
 *
 * @param previousTab [MainTabState.previousTab] (26): the glyph to collapse to
 * @param progress the search morph position, read in draw lambdas only
 * @param minimizeProgress ticket 28's morph position, for the joint case
 * @param pill0 the pill rect at `p = 0`, row-local (26/28); its width is
 *   the expanded pill width the content is measured at
 * @param active Search is the selected tab (the target)
 * @param onReturn called after the focus is cleared: back to [previousTab]
 *   (26's programmatic `select` — no haptic, like the system Close)
 * @param onTabTap 26's `onTabTap`, for the fading pill cells mid-leave
 */
@Composable
fun SearchReturnCircle(
    previousTab: TabId,
    progress: () -> Float,
    minimizeProgress: () -> Float,
    pill0: BarRect,
    active: Boolean,
    onReturn: () -> Unit,
    onTabTap: (TabId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = RobocoTheme.palette
    val focusManager = LocalFocusManager.current
    val source = remember { MutableInteractionSource() }
    val cellWidth = (pill0.w.dp - GlassTabBarDefaults.PlatterInset * 2) / 2

    GlassSurface(
        modifier = modifier.fillMaxSize(),
        shape = CapsuleShape,
        interactive = true,
        interactionSource = source,
    ) {
        Layout(
            content = {
                // Layer 0: 26's expanded pill content, fading with p (and m).
                Box(
                    Modifier
                        .then(if (active) Modifier.clearAndSetSemantics { } else Modifier)
                        .graphicsLayer {
                            alpha = (1f - minimizeProgress().coerceIn(0f, 1f)) *
                                SearchMorphGeometry.pillItemsAlpha(progress())
                        },
                ) {
                    TabPillCellsAndPlatter(
                        platterIndex = { if (previousTab == TabId.Settings) 1f else 0f },
                        selectedCell = if (active) null else previousTab,
                        onTabTap = onTabTap,
                        pillSource = source,
                        hideSessionsIcon = false,
                        hideSettingsIcon = false,
                        cellWidth = cellWidth,
                    )
                }
                // Layer 1: the previous tab's glyph, centred, fading in.
                IconInBox(
                    icon = if (previousTab == TabId.Settings) RobocoIcons.TabSettings else RobocoIcons.TabChat,
                    tint = palette.text,
                    box = GlassTabBarDefaults.IconBox,
                    modifier = Modifier.graphicsLayer {
                        alpha = maxOf(
                            minimizeProgress().coerceIn(0f, 1f),
                            SearchMorphGeometry.collapsedIconAlpha(progress()),
                        )
                    },
                )
            },
        ) { measurables, constraints ->
            // The expanded content is measured once at the expanded pill
            // size (its layout never reflows), shifted so it stays centred
            // while the glass shrinks; the glyph is centred in the rect.
            val expandedHeightPx = GlassTabBarDefaults.PillHeight.roundToPx()
            val expandedWidthPx = pill0.w.dp.roundToPx()
            val expanded = measurables[0].measure(Constraints.fixed(expandedWidthPx, expandedHeightPx))
            val iconBoxPx = GlassTabBarDefaults.IconBox.roundToPx()
            val icon = measurables[1].measure(Constraints.fixed(iconBoxPx, iconBoxPx))
            layout(constraints.maxWidth, constraints.maxHeight) {
                expanded.placeRelative(0, (constraints.maxHeight - expandedHeightPx) / 2)
                icon.placeRelative(
                    (constraints.maxWidth - iconBoxPx) / 2,
                    (constraints.maxHeight - iconBoxPx) / 2,
                )
            }
        }

        // The "Close" button: one node above the fading content, sized to
        // the glass. It also takes the taps the faded cells underneath
        // would otherwise still receive while Search is selected.
        if (active) {
            Box(
                Modifier
                    .matchParentSize()
                    .testTag("search-close")
                    .clickable(
                        interactionSource = source,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = null,
                    ) {
                        focusManager.clearFocus(force = true)
                        onReturn()
                    }
                    .clearAndSetSemantics {
                        contentDescription = SearchMorph.CloseDescription
                        role = Role.Button
                    },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The shared fixture: previews, the gallery section, the screenshot tests
// ---------------------------------------------------------------------------

/**
 * The ticket's fixture frame (internal): [TabChromeLayout] with this
 * ticket's real slots at a scrubbed morph position, over 22's striped
 * [GlassStage] backdrop (the chrome is the backdrop's sibling, never
 * inside its `hazeSource`). **Fixed at a 402 dp frame with zero insets**,
 * so R = 360: the return circle at row x 0, the field from row x 56.
 *
 * @param progress the search morph position (scrubbed)
 * @param active the Search-selected target (the field's semantics)
 * @param minimizeProgress ticket 28's morph position
 * @param minimized 28's target, for the normal-bar branch
 * @param imeLift the IME lift in dp (the fixture's stand-in for
 *   `WindowInsets.ime`; drives the row rise and the accessory fade)
 * @param bottomInset ticket 26's `tabBarBottomGap` (21 = gesture nav)
 */
@Composable
internal fun SearchMorphFixture(
    state: SearchTabState,
    progress: () -> Float,
    active: Boolean,
    minimizeProgress: () -> Float,
    modifier: Modifier = Modifier,
    minimized: Boolean = false,
    imeLift: Float = 0f,
    bottomInset: Dp = 21.dp,
    height: Dp = 220.dp,
    onSearchTabTap: () -> Unit = {},
    onReturn: () -> Unit = {},
    onTabTap: (TabId) -> Unit = {},
    onNewSession: () -> Unit = {},
) {
    val haze = rememberHazeState()
    CompositionLocalProvider(LocalHazeState provides haze) {
        GlassStage(
            modifier = modifier,
            height = height,
            backdropModifier = Modifier.hazeSource(haze),
        ) {
            // 26's row at a 402 dp window, zero insets: R = 360.
            val rowW = tabBarRowWidth(402.dp).value
            val pillW = tabPillExpandedWidth(402f)
            val showSearchChrome by remember(active) {
                derivedStateOf { SearchMorph.chromeShowing(active, progress()) }
            }
            TabChromeLayout(
                progress = minimizeProgress,
                searchProgress = progress,
                searchActive = active,
                bottomInset = bottomInset,
                hasAccessory = true,
                imeLiftDp = imeLift,
                modifier = Modifier.matchParentSize().selectableGroup(),
                pill = {
                    if (showSearchChrome) {
                        SearchReturnCircle(
                            previousTab = TabId.Sessions,
                            progress = progress,
                            minimizeProgress = minimizeProgress,
                            pill0 = BarRect(0f, 0f, pillW, SearchMorphGeometry.RowHeight),
                            active = active,
                            onReturn = onReturn,
                            onTabTap = onTabTap,
                        )
                    } else {
                        GlassTabPill(
                            selected = TabId.Sessions,
                            onTabTap = onTabTap,
                            minimizeProgress = minimizeProgress,
                            minimized = minimized,
                            onRestore = {},
                            expandedWidth = pillW.dp,
                        )
                    }
                },
                search = {
                    SearchTabField(
                        state = state,
                        progress = progress,
                        rowWidth = rowW,
                        active = active,
                        onSearchTabTap = onSearchTabTap,
                    )
                },
                accessory = {
                    // The fixture drives the accessory's IME fade from the
                    // lift parameter (the shell reads WindowInsets.ime).
                    Box(Modifier.graphicsLayer { alpha = accessoryImeAlpha(imeLift) }) {
                        NewSessionAccessory(
                            counts = LiveCounts(working = 2, awaiting = 1),
                            onTap = onNewSession,
                            inline = minimized,
                            labelAlpha = { 1f - minimizeProgress().coerceIn(0f, 1f) },
                            enabled = accessoryImeAlpha(imeLift) >= 0.01f,
                        )
                    }
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Previews (step 10): a 402 × 220 dp frame over the striped backdrop at
// p = 1, text "zeron" — zero insets, so R = 360. Light and dark.
// ---------------------------------------------------------------------------

@Composable
private fun SearchMorphPreviewContent() {
    RobocoTheme {
        val state = remember {
            SearchTabState().also { it.text.setTextAndPlaceCursorAtEnd("zeron") }
        }
        SearchMorphFixture(
            state = state,
            progress = { 1f },
            active = true,
            minimizeProgress = { 0f },
            height = 220.dp,
        )
    }
}

@Preview(name = "search tab light", widthDp = 402, heightDp = 220)
@Composable
private fun SearchTabLightPreview() {
    SearchMorphPreviewContent()
}

@Preview(name = "search tab dark", widthDp = 402, heightDp = 220, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SearchTabDarkPreview() {
    SearchMorphPreviewContent()
}
