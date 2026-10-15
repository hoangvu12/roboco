package sh.roboco.android.transcript

import android.net.Uri
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import sh.roboco.android.design.RobocoTheme
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.LinkHit
import uniffi.roboco_core.RowDisplay
import uniffi.roboco_core.RowKind
import uniffi.roboco_core.WidgetKind
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * The transcript's semantics tree (android-ui-parity #129): the port of
 * the tree iOS builds by hand in `RowView.accessibilityElements`
 * (`ios:Transcript/RowView.swift:49-90`). The transcript is paint-only
 * (65), so text, links and headings exist only as pixels — this file adds
 * the invisible nodes TalkBack reads:
 *  - one **row text node** per realized row (`"You: …"` for prompts, the
 *    block's markdown source otherwise, `display.text` for tool rows),
 *    carrying the `row-*` test tag and the **custom actions** Copy /
 *    Copy Message / Select Text — 128's own [rowMenuActions] builder and
 *    [RowMenuPerformer], so the menu and the actions cannot diverge;
 *  - one **link node** per [uniffi.roboco_core.LinkHit] fragment, at its
 *    painted rect (unclipped, following its scroller's offset through the
 *    placement phase), labelled with the URL's encoded host, opening
 *    through 126's [TranscriptActions.openLink];
 *  - the **row group**: `isTraversalGroup` + `CollectionItemInfo`, with
 *    the exposed widget kinds (128's [WidgetKind.isControl]) forming
 *    their own `0f` groups and the hidden kinds cleared entirely (iOS
 *    `isUserInteractionEnabled = false` — "Working…" is never spoken);
 *  - the **host**: `CollectionInfo`, a px `ScrollAxisRange` and a
 *    `scrollBy` action that pages by the uncovered page height and
 *    releases/re-latches follow-the-tail (see [TranscriptViewport.accessibilityScrollBy]).
 *
 * Nothing here draws (the debug bounds overlay excepted) and nothing here
 * handles pointer input — touches keep flowing to 65's tap detector,
 * 126's link hit test, 128's long press and 116's scrollers. A semantics
 * `onClick` is enough for TalkBack activation.
 */

/**
 * A link node's label: the URL's encoded host, or the whole URL when
 * there is none (`URL(string:)?.host()` with Swift's default
 * `percentEncoded: true`, `RowView.swift:69`): `example.com` for
 * `https://example.com/docs`, `image` for a `roboco-preview://image`
 * chip, the full string for `mailto:`/`roboco-file:`/scheme-less URLs.
 */
internal fun linkLabel(url: String): String =
    encodedHostOf(Uri.parse(url).encodedAuthority)?.takeIf { it.isNotEmpty() } ?: url

/**
 * The host part of an encoded authority (`user@host:port`): android.net.Uri
 * has no `encodedHost`, so strip the userinfo and the port by hand,
 * keeping an IPv6 literal's brackets.
 */
private fun encodedHostOf(authority: String?): String? {
    val hostPort = authority?.substringAfterLast('@') ?: return null
    return if (hostPort.startsWith("[")) {
        hostPort.substringBefore(']') + "]"
    } else {
        hostPort.substringBefore(':')
    }
}

/**
 * The row text node's label (`RowView.swift:142-149`): `"You: "` plus the
 * (untrimmed) prompt for user rows — even when [copyText] is empty — else
 * [copyText], falling back to `display.text` (tool rows concatenate every
 * placed text with no separator; iOS speaks exactly that). An empty
 * result sets no description, so TalkBack skips the node.
 */
internal fun rowAccessibilityLabel(kind: RowKind, copyText: String, text: String): String =
    if (kind == RowKind.USER) "You: $copyText" else copyText.ifEmpty { text }

/**
 * The widget half of the tree: the **exposed** kinds (128's
 * [WidgetKind.isControl] — the exact set iOS puts in
 * `accessibilityElements`) become their own traversal group at index `0f`
 * around the widget's own node (its label is the widget ticket's); every
 * other kind is cleared outright, matching iOS's `isUserInteractionEnabled
 * = false` views, which VoiceOver never sees. The same predicate decides
 * both halves — no second list.
 */
internal fun Modifier.widgetSemantics(kind: WidgetKind): Modifier =
    if (kind.isControl()) {
        semantics {
            isTraversalGroup = true
            traversalIndex = 0f
        }
    } else {
        clearAndSetSemantics { }
    }

/**
 * The row's full-row text node — the **first** child of the row Box, so
 * explore-by-touch lands on widgets and links first (Compose's
 * accessibility hit test takes the topmost node; iOS orders the text
 * element last for the same reason). A `matchParentSize` `Spacer`: it
 * draws nothing and adds no pointer input. `traversalIndex = 1f` puts it
 * after every `0f` node inside the row group.
 *
 * The label is empty (image, working, text-less rows) → no
 * `contentDescription`, and the tag stays. The custom actions are built
 * by 128's [rowMenuActions] with [LayoutFrame.messageText] — O(rows), so
 * the message sits in a `remember`ed `lazy` read **from the semantics
 * block**: a frame nobody reads semantics for pays nothing, and a frame
 * that does pays once per row.
 */
@Composable
internal fun BoxScope.RowTextNode(
    kind: RowKind,
    model: RowModel?,
    frame: LayoutFrame,
    index: UInt,
    performer: RowMenuPerformer,
) {
    val message = remember(frame, index) { lazy(LazyThreadSafetyMode.NONE) { frame.messageText(index) } }
    Spacer(
        Modifier
            .matchParentSize()
            .testTag(rowTag(kind))
            .semantics {
                traversalIndex = 1f
                val d = model?.display ?: return@semantics
                rowAccessibilityLabel(kind, d.copyText, d.text)
                    .takeIf { it.isNotEmpty() }
                    ?.let { contentDescription = it }
                val items = rowMenuActions(block = d.copyText, message = message.value)
                if (items.isNotEmpty()) {
                    customActions = items.map { a ->
                        CustomAccessibilityAction(a.kind.title) {
                            performer.perform(a)
                            true
                        }
                    }
                }
            },
    )
}

/**
 * The row's link nodes — the **last** children of the row Box, one per
 * [uniffi.roboco_core.LinkHit] fragment in `d.links` order (a link that
 * wraps onto two lines gives two nodes with the same label, like iOS).
 * Position and size are the fragment's rect, dp, no slop; a fragment in
 * scroller `s` sits at `(scrollers[s].x + l.x − scrollerOffsetDp(s),
 * scrollers[s].y + l.y)` and is **not clipped** to the scroller's
 * viewport (the iOS frame is unclipped). The offset is read only in the
 * placement block and at click time, so a scroller scroll re-places link
 * nodes and never recomposes the row; a bad scroller index emits no node.
 *
 * Activation is a semantics `onClick` (a TalkBack double-tap), which does
 * what a real tap does: 126's `openLink(LinkTap)` then `onTap()` — in a
 * `finally`, always. [rowBounds] is the row's window rect; [linkBounds]
 * the fragment rect translated into window px (the scroller offset read
 * at click time).
 */
@Composable
internal fun BoxScope.RowLinkNodes(row: RealizedRow, d: RowDisplay, actions: () -> TranscriptActions) {
    val debug = LocalTranscriptA11yBounds.current
    val stroke = RobocoTheme.palette.accent // #5B43E8 light / #8B7CF6 dark
    val dens = LocalDensity.current.density // px per dp, for the click rects
    d.links.forEachIndexed { i, l ->
        val s = l.scroller?.toInt()
        if (s != null && s >= d.scrollers.size) return@forEachIndexed
        key(i) {
            Spacer(
                Modifier
                    .absoluteOffset {
                        val pos = linkPosition(d, l, s, if (s == null) 0f else row.scrollerOffsetDp(s))
                        IntOffset((pos.x * density).roundToInt(), (pos.y * density).roundToInt())
                    }
                    .size(max(l.w, 0f).dp, max(l.h, 0f).dp)
                    .then(if (debug) Modifier.drawBehind { drawRect(stroke, style = Stroke(1f)) } else Modifier)
                    .testTag("transcript-link")
                    .semantics {
                        traversalIndex = 0f
                        contentDescription = linkLabel(l.url)
                        stateDescription = l.url
                        onClick {
                            try {
                                val rb = row.boundsInWindow()
                                val pos = linkPosition(d, l, s, if (s == null) 0f else row.scrollerOffsetDp(s))
                                val lb = Rect(
                                    rb.left + pos.x * dens,
                                    rb.top + pos.y * dens,
                                    rb.left + (pos.x + l.w) * dens,
                                    rb.top + (pos.y + l.h) * dens,
                                )
                                actions().openLink(LinkTap(l.url, rb, lb)) // 126
                            } finally {
                                actions().onTap()
                            }
                            true
                        }
                    },
            )
        }
    }
}

/**
 * A fragment's painted position in row coordinates (dp): its own rect
 * when it is row-level, else the scroller viewport's origin plus the
 * rect minus the live offset — the link half of iOS's
 * `positionedLinkElements` (`RowView.swift:76-90`, unclipped). Pure; the
 * caller chooses **when** the offset is read (placement phase for the
 * node's position, click time for the activation rect).
 */
private fun linkPosition(d: RowDisplay, l: LinkHit, s: Int?, scrollerOffsetDp: Float): Offset =
    if (s == null) {
        Offset(l.x, l.y)
    } else {
        Offset(d.scrollers[s].x + l.x - scrollerOffsetDp, d.scrollers[s].y + l.y)
    }

/**
 * The host's semantics (the `transcript` node): TalkBack's scrollable
 * collection. `testTagsAsResourceId` exposes the tags (this subtree is
 * the only place on the session screen that does) so UiAutomator and
 * Maestro can find `transcript`, `row-*` and `transcript-link`;
 * `CollectionInfo` tracks the row count of the applied frame; the
 * **px** scroll range and the `scrollBy` action replace the raw one the
 * custom `scrollable` would register (this modifier sits outer to it, so
 * its `ScrollBy` wins) and page by the uncovered page height instead of
 * the host height.
 *
 * iOS gets all of this from `UIScrollView` for free
 * (`TranscriptListView.swift:8`); a custom scroll model exposes none of
 * it, hence the explicit properties. The scroll-range lambdas read
 * snapshot state at accessibility time, so Compose emits
 * `TYPE_VIEW_SCROLLED` as the offset moves. (The ticket asked for
 * `@OptIn(ExperimentalComposeUiApi::class)` on `testTagsAsResourceId`;
 * the marker no longer exists in the pinned Compose UI — the property is
 * stable there — so no opt-in is needed or possible.)
 */
internal fun Modifier.transcriptHostSemantics(
    state: TranscriptState,
    frame: LayoutFrame?,
    density: Float,
): Modifier = semantics {
    val vp = state.viewport
    testTagsAsResourceId = true
    if (frame != null) collectionInfo = CollectionInfo(rowCount = frame.rowCount().toInt(), columnCount = 1)
    verticalScrollAxisRange = ScrollAxisRange(
        value = { (vp.offset - vp.minOffset) * density },
        maxValue = { (max(vp.minOffset, vp.maxOffset) - vp.minOffset) * density },
        reverseScrolling = false,
    )
    scrollBy { _, y -> vp.accessibilityScrollBy(y / density) }
}

/**
 * The TalkBack paging target, pure (dp). `ACTION_SCROLL_FORWARD`/`BACKWARD`
 * arrive as `scrollBy(0, ±hostHeightPx)` and `ACTION_SHOW_ON_SCREEN` as
 * the exact delta that brings the node into view — the step is a **page**
 * (the area not under the header and bottom stack, so a page never skips
 * text hidden under the composer) only for deltas of at least a host
 * height, else the delta itself; the result is clamped to the legal
 * range. Worked values in the ticket (viewport 891, `min = −topInset`):
 * 1000→109, 1000→277 with 68/100 insets, 4800→5000 (clamp),
 * 1000→1120 (show-on-screen), −68→−68 (clamped: no move).
 */
internal fun a11yScrollTarget(
    offset: Float,
    minOffset: Float,
    maxOffset: Float,
    dy: Float,
    viewportHeight: Float,
    topInset: Float,
    bottomInset: Float,
): Float {
    val page = max(1f, viewportHeight - topInset - bottomInset)
    val step = if (viewportHeight > 0f && abs(dy) >= viewportHeight - 0.5f) sign(dy) * page else dy
    return (offset + step).coerceIn(minOffset, max(minOffset, maxOffset))
}

/**
 * Runs a TalkBack scroll (dp, positive = toward the tail): instant (no
 * animation, nothing here animates), `false` when already at the end so
 * TalkBack knows to stop, and follow-aware — a page toward older content
 * releases the tail first (the spring cannot pull the view back, like a
 * finger drag, 68 R5), and reaching the bottom re-latches follow (R10).
 */
internal fun TranscriptViewport.accessibilityScrollBy(dyDp: Float): Boolean {
    val target = a11yScrollTarget(offset, minOffset, maxOffset, dyDp, viewportHeight, topInset, bottomInset)
    val delta = target - offset
    if (abs(delta) < 0.5f) return false
    if (delta < 0f) releaseFollow()
    setOffset(target)
    if (distanceFromBottom <= 0.5f) scrollToBottom(animated = false)
    return true
}

/**
 * Debug only (the gallery's "Transcript accessibility" section): outline
 * the invisible nodes — links in `Palette.accent`, exposed widget groups
 * in `Palette.danger` — as 1 px strokes, so the outlines can be compared
 * against the painted text. `false` in production and in every other
 * debug section, so the draw never happens outside this gallery page.
 */
val LocalTranscriptA11yBounds = staticCompositionLocalOf { false }
