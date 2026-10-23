package sh.roboco.android.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [routeLink] and its helpers (ticket 126's routing table): the Web /
 * Preview / External / Ignore rows, the ASCII case-insensitive scheme
 * matching, the Windows-drive check before the scheme row, and
 * [percentDecodeUtf8] / [previewRef] — including the `+` that
 * `Uri.getQueryParameter` and `URLDecoder` would turn into a space.
 * Plain JUnit 4: the routing is pure Kotlin.
 */
class LinkRouteTest {

    // --------------------------------------------------------------- routing

    @Test
    fun webLinksOpenInTheCustomTab() {
        assertEquals(LinkRoute.Web("https://ja.wikipedia.org/wiki/x"), routeLink("https://ja.wikipedia.org/wiki/x"))
        // Scheme matching is case-insensitive (RFC 3986; iOS would send
        // HTTP: to the Safari app — see the ticket's adaptation).
        assertEquals(LinkRoute.Web("HTTP://example.com"), routeLink("HTTP://example.com"))
    }

    @Test
    fun imageChipsRouteToThePreviewWithADecodedRef() {
        assertEquals(
            LinkRoute.Preview("/tmp/uploads/ab12cd34-Image_1.png"),
            routeLink("roboco-preview://image?ref=%2Ftmp%2Fuploads%2Fab12cd34-Image_1.png"),
        )
        // `+` stays `+`: iOS's URLComponents.queryItems does not decode it.
        assertEquals(
            LinkRoute.Preview("a+b+c"),
            routeLink("roboco-preview://image?ref=a+b%2Bc"),
        )
    }

    @Test
    fun badPreviewRefsAreIgnored() {
        assertEquals(LinkRoute.Ignore, routeLink("roboco-preview://image?ref=%E0%A4"))
        assertEquals(LinkRoute.Ignore, routeLink("roboco-preview://image?ref="))
        assertEquals(LinkRoute.Ignore, routeLink("roboco-preview://image?x=1"))
    }

    @Test
    fun robocoFileLinksOpenImagesAndIgnoreTheRest() {
        assertEquals(LinkRoute.Ignore, routeLink("roboco-invoke:7b7d"))
        assertEquals(LinkRoute.Preview("roboco-file:src/a%20file.png"), routeLink("roboco-file:src/a%20file.png"))
        assertEquals(LinkRoute.Preview("ROBOCO-FILE:x.png"), routeLink("ROBOCO-FILE:x.png"))
        assertEquals(LinkRoute.Ignore, routeLink("roboco-file:src/components/"))
        assertEquals(LinkRoute.Ignore, routeLink("roboco-file:src/main.rs"))
    }

    @Test
    fun schemelessImagePathsOpenTheViewerOtherPathsAreIgnored() {
        for (url in listOf("assets/logo.png", "src/a.png#L3", "/abs/shot.webp", "icon.svg", "~/x.png", "a/../b.png")) {
            assertEquals(url, LinkRoute.Preview(url), routeLink(url))
        }
        for (url in listOf("src/main.rs:42", "docs/README.md", "src/")) {
            assertEquals(url, LinkRoute.Ignore, routeLink(url))
        }
    }

    @Test
    fun windowsDrivePathsOpenImagesNotExternal() {
        // `C:` looks like a scheme; the drive check runs before the
        // scheme row.
        assertEquals(LinkRoute.Preview("C:\\work\\shot.png"), routeLink("C:\\work\\shot.png"))
        assertEquals(LinkRoute.Preview("C:/work/shot.png"), routeLink("C:/work/shot.png"))
        assertEquals(LinkRoute.Ignore, routeLink("C:/work/notes.txt"))
    }

    @Test
    fun fileUrisOpenImagesAndNeverReachView() {
        assertEquals(LinkRoute.Preview("file:///abs/x.png"), routeLink("file:///abs/x.png"))
        assertEquals(LinkRoute.Preview("file://host/x.png"), routeLink("file://host/x.png"))
        assertEquals(LinkRoute.Ignore, routeLink("file:///abs/notes.txt"))
    }

    @Test
    fun otherSchemesGoToTheSystem() {
        assertEquals(LinkRoute.External("mailto:dev@example.com"), routeLink("mailto:dev@example.com"))
        assertEquals(LinkRoute.External("tel:12345"), routeLink("tel:12345"))
        assertEquals(LinkRoute.External("roboco://chat/abc"), routeLink("roboco://chat/abc"))
        // Scheme-shaped: ACTION_VIEW finds no handler, so nothing happens,
        // as iOS's UIApplication.open does.
        assertEquals(LinkRoute.External("main.rs:42"), routeLink("main.rs:42"))
    }

    @Test
    fun anchorsEmptyAndBareNamesAreIgnored() {
        for (url in listOf("#install", "", "Makefile", ".png")) {
            assertEquals(url, LinkRoute.Ignore, routeLink(url))
        }
    }

    // ------------------------------------------------------------- decoding

    @Test
    fun percentDecodeUtf8DecodesAndRejectsBadEscapes() {
        assertEquals("a+b", percentDecodeUtf8("a%2Bb"))
        assertEquals("/tmp/x.png", percentDecodeUtf8("%2Ftmp%2Fx.png"))
        assertNull("a truncated UTF-8 sequence must fail", percentDecodeUtf8("%E0%A4"))
        assertNull("a truncated escape must fail", percentDecodeUtf8("%2"))
        assertNull("a non-hex escape must fail", percentDecodeUtf8("%2G"))
        assertNull("a bare percent must fail", percentDecodeUtf8("100%"))
        assertEquals("both hex cases decode", "AB", percentDecodeUtf8("%41%42"))
    }

    @Test
    fun previewRefTakesTheFirstRefItemAfterTheHash() {
        assertEquals("/tmp/x.png", previewRef("roboco-preview://image?ref=%2Ftmp%2Fx.png&x=1"))
        assertEquals("a+b", previewRef("roboco-preview://image?ref=a+b"))
        // The query ends at the first `#`.
        assertNull(previewRef("roboco-preview://image?x=1#ref=y"))
        // An item without `=` does not count; the search continues.
        assertNull(previewRef("roboco-preview://image?ref&x=1"))
        assertEquals("late", previewRef("roboco-preview://image?ref&ref=late"))
        assertNull(previewRef("roboco-preview://image"))
        assertNull(previewRef("roboco-preview://image?ref="))
    }
}
