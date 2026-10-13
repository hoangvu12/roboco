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
 * its callers and #65's tests: **66** replaces the one-arg [loadImage]
 * (per-engine loader, `cachedImage`); **126** changes [openLink] to
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
     * `imageLoader` + `imageFetcher`). The implementation picks its
     * dispatcher; the host only ever calls this from a widget's scope.
     */
    val loadImage: suspend (reference: String) -> ImageBitmap? = { null },
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
