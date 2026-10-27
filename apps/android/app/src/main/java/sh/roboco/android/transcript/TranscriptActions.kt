package sh.roboco.android.transcript

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * One link tap (ticket 126). Both rects are in window px: [rowBounds]
 * is the row (the viewer's zoom source, as iOS passes the whole
 * `RowView` for an image chip), [linkBounds] the painted fragment.
 * No route reads [linkBounds] since decision TRANSCRIPT-D4 dropped the
 * file menu it anchored; it stays in the contract because 65's tap
 * test and 129's link nodes build it.
 */
@Immutable
data class LinkTap(val url: String, val rowBounds: Rect, val linkBounds: Rect)

/**
 * The transcript host's callbacks to its screen (ticket 65). Defaults are
 * no-ops so additions stay non-breaking (128 added `selectText` the same
 * way) and so the gallery and tests can construct one directly. The screen
 * that builds these is not this ticket: 75/78/81/125/126/127 wire them.
 *
 * Later tickets change some signatures in place, each updating this class,
 * its callers and #65's tests: **66** replaced the one-arg [loadImage] with
 * the per-engine two-arg form and added [cachedImage] (tickets 75/126 get
 * `loadFull` straight from the binding); **126** changed [openLink] to
 * `(LinkTap) -> Unit` and the `linkAt` stub to return `LinkAt?`; **137**
 * added `label: String?` to [openImage] (the alt text, forwarded into
 * 125's `ImageViewerRequest.label`).
 */
@Stable
class TranscriptActions(
    /**
     * A painted link was tapped. Routing is ticket 126's `LinkRouter`
     * (Custom Tab, viewer, the system, or a silent no-op). `rowBounds`
     * is the row's bounds in window px (iOS passes the RowView as the
     * zoom source).
     */
    val openLink: (tap: LinkTap) -> Unit = {},
    /**
     * An image tile was tapped (66 → viewer 125). `source` = the tile's
     * bounds in window px, `bitmap` the decoded tile when it is already in
     * memory (a head start for the viewer's cross-fade), `label` the alt
     * text (137: the tile's accessibility label, forwarded into the
     * viewer's `ImageViewerRequest.label`).
     */
    val openImage: (reference: String, source: Rect, bitmap: ImageBitmap?, label: String?) -> Unit = { _, _, _, _ -> },
    /**
     * Load an attachment / generated image by widget reference (iOS
     * `imageLoader` + `imageFetcher`). [sidePx] is the decode target (the
     * shorter source side, never upscaled); the implementation runs the
     * read off-main and decodes on a background dispatcher.
     */
    val loadImage: suspend (reference: String, sidePx: Int) -> ImageBitmap? = { _, _ -> null },
    /**
     * The synchronous cache peek (ticket 66): called during the tile's
     * composition so a cached picture shows in the first frame, with no
     * animation and no read. Cheap and main-thread safe.
     */
    val cachedImage: (reference: String, sidePx: Int) -> ImageBitmap? = { _, _ -> null },
    /**
     * Fires when the image source came back (the engine reconnected): a
     * Failed tile retries at once instead of waiting out its backoff.
     */
    val imageRetries: Flow<Unit> = emptyFlow(),
    /** The mono tool-detail sheet for a Detail widget (127). */
    val showDetail: (title: String, text: String) -> Unit = { _, _ -> },
    /** Row menu "Select Text" (128) → this ticket's Select Text sheet. */
    val selectText: (text: String) -> Unit = { _ -> },
    /**
     * Any tap no widget or scroll consumed (Final pass). A generic hook
     * (e.g. the lab); ticket 81 does **not** use it — the session screen
     * leaves it a no-op and dismisses the composer through its own
     * Initial-pass observer.
     */
    val onTap: () -> Unit = {},
    /**
     * A subagent spawn card was tapped (android-next #06, upstream #899's
     * `onOpenSubagent`): open the subagent's read-only transcript.
     * [docId] is the scoped subagent doc, [title] the spawn's description,
     * [frozen] whether the subagent had already finished when tapped.
     */
    val openSubagent: (docId: String, title: String, frozen: Boolean) -> Unit = { _, _, _ -> },
    /**
     * A row's button was tapped (wave-9 #14, upstream #967's
     * `rowView(_:perform:)`): [action] names what — `sign-in` on an error
     * card. Unknown actions are the screen's to ignore.
     */
    val perform: (action: String) -> Unit = { _ -> },
)
