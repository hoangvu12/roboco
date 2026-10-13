package sh.roboco.android.transcript

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Rect

/**
 * The transcript host's callbacks to its screen (ticket 65). Defaults are
 * no-ops so additions stay non-breaking (128 may add `selectText` the same
 * way) and so the gallery and tests can construct one directly. The screen
 * that builds these is not this ticket: 75/78/81/125/126/127 wire them.
 *
 * Later tickets change some signatures in place, each updating this class,
 * its callers and #65's tests: **66** replaced the one-arg [loadImage] with
 * the per-engine two-arg form and added [cachedImage] (tickets 75/126 get
 * `loadFull` straight from the binding); **126** changes [openLink] to
 * `(LinkTap) -> Unit` and the `linkAt` stub to return `LinkAt?`; **137**
 * adds `label: String?` to [openImage].
 */
@Stable
class TranscriptActions(
    /**
     * A painted link was tapped. `source` = the row's bounds in window px
     * (iOS passes the RowView as the zoom source). Routing: ticket 126.
     */
    val openLink: (url: String, source: Rect) -> Unit = { _, _ -> },
    /**
     * An image tile was tapped (66 → viewer 125). `source` = the tile's
     * bounds in window px, `bitmap` the decoded tile when it is already in
     * memory (a head start for the viewer's cross-fade).
     */
    val openImage: (reference: String, source: Rect, bitmap: ImageBitmap?) -> Unit = { _, _, _ -> },
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
    /** The mono tool-detail sheet for a Detail widget (127). */
    val showDetail: (title: String, text: String) -> Unit = { _, _ -> },
    /**
     * Any tap no widget or scroll consumed (Final pass). A generic hook
     * (e.g. the lab); ticket 81 does **not** use it — the session screen
     * leaves it a no-op and dismisses the composer through its own
     * Initial-pass observer.
     */
    val onTap: () -> Unit = {},
)
