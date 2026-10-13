package sh.roboco.android.shell

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The deep-link and route grammars (android-ui-parity #36) — every row of
 * the ticket's parse / parseRoute / ScopedIds / ChatIds tables. Plain
 * JUnit: `DeepLinks.kt` has no `android.*` dependency, so nothing needs
 * Robolectric and no log seam is touched here (the hub's drop log is
 * `DeepLinkHubTest`'s business).
 */
class DeepLinksTest {

    // ------------------------------------------------------------ the rig

    /** `engine:v1:` + base64url-no-pad of [json] — Rust's `ScopedId::encode`. */
    private fun scopedOf(json: String): String =
        ScopedIds.PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))

    private val pairLink = "roboco://pair#url=https%3A%2F%2Fh%3A27655%2Fpair%23token%3Dx"

    // --------------------------------------------------------------- parse

    @Test
    fun parseChat() {
        assertEquals(DeepLink.OpenChat("abc"), DeepLinks.parse("roboco://chat/abc"))
        // Scheme and host case-insensitive; the id keeps its case.
        assertEquals(DeepLink.OpenChat("AbC"), DeepLinks.parse("ROBOCO://Chat/AbC"))
        // A trailing slash is accepted.
        assertEquals(DeepLink.OpenChat("abc"), DeepLinks.parse("roboco://chat/abc/"))
        // The query and the fragment are split off before the path check.
        assertEquals(DeepLink.OpenChat("a b"), DeepLinks.parse("roboco://chat/a%20b?x=1#y"))
        // A scoped id passes through verbatim (no base64 validation here).
        assertEquals(
            DeepLink.OpenChat("engine:v1:WyJrIiwiciJd"),
            DeepLinks.parse("roboco://chat/engine:v1:WyJrIiwiciJd"),
        )
        // Whitespace around the whole link is trimmed.
        assertEquals(DeepLink.OpenChat("abc"), DeepLinks.parse("  roboco://chat/abc  "))
    }

    @Test
    fun parseNew() {
        assertEquals(DeepLink.NewSession(null), DeepLinks.parse("roboco://new"))
        assertEquals(DeepLink.NewSession(null), DeepLinks.parse("roboco://new/"))
        assertEquals(DeepLink.NewSession("Fix tests"), DeepLinks.parse("roboco://new?prompt=Fix%20tests"))
        // Form-style: `+` is a space in the prompt.
        assertEquals(DeepLink.NewSession("Fix tests"), DeepLinks.parse("roboco://new?prompt=Fix+tests"))
        // The first `prompt=` parameter wins; UTF-8 escapes decode.
        assertEquals(DeepLink.NewSession("\u2713"), DeepLinks.parse("roboco://new?a=1&prompt=%E2%9C%93"))
        // Newlines are kept (the prompt is verbatim, not trimmed).
        assertEquals(DeepLink.NewSession("line1\nline2"), DeepLinks.parse("roboco://new?prompt=line1%0Aline2"))
        assertEquals(DeepLink.NewSession(" a"), DeepLinks.parse("roboco://new?prompt=%20a"))
        // Blank results read as no prompt.
        assertEquals(DeepLink.NewSession(null), DeepLinks.parse("roboco://new?prompt="))
        assertEquals(DeepLink.NewSession(null), DeepLinks.parse("roboco://new?prompt=%20+"))
        // A malformed escape still opens the sheet, with no prompt.
        assertEquals(DeepLink.NewSession(null), DeepLinks.parse("roboco://new?prompt=%GG"))
        assertEquals(DeepLink.NewSession(null), DeepLinks.parse("roboco://new?prompt=a%"))
        // Only the first `prompt=` counts.
        assertEquals(DeepLink.NewSession("a"), DeepLinks.parse("roboco://new?a=1&prompt=a&prompt=b"))
    }

    @Test
    fun parseSettingsSearch() {
        assertEquals(DeepLink.Settings, DeepLinks.parse("roboco://settings"))
        assertEquals(DeepLink.Settings, DeepLinks.parse("roboco://Settings/"))
        assertEquals(DeepLink.Search, DeepLinks.parse("roboco://search"))
        assertEquals(DeepLink.Search, DeepLinks.parse("ROBOCO://SEARCH"))
    }

    @Test
    fun parsePairKeepsTheWholeTrimmedString() {
        assertEquals(DeepLink.Pair(pairLink), DeepLinks.parse(pairLink))
        assertEquals(DeepLink.Pair("roboco://pair"), DeepLinks.parse("roboco://pair"))
        // Any path/fragment is accepted for the pair host.
        assertEquals(DeepLink.Pair("roboco://pair/x#y"), DeepLinks.parse("roboco://pair/x#y"))
        assertEquals(DeepLink.Pair("roboco://PAIR#url=x"), DeepLinks.parse("roboco://PAIR#url=x"))
        // The link is the trimmed original string.
        assertEquals(DeepLink.Pair("roboco://pair"), DeepLinks.parse(" roboco://pair "))
    }

    @Test
    fun parseIgnoresEverythingElse() {
        assertNull(DeepLinks.parse(null))
        assertNull(DeepLinks.parse(""))
        assertNull(DeepLinks.parse("   "))
        assertNull(DeepLinks.parse("roboco"))
        assertNull(DeepLinks.parse("roboco:settings"))
        assertNull(DeepLinks.parse("://settings"))
        assertNull(DeepLinks.parse("roboco://"))
        assertNull(DeepLinks.parse("https://roboco/chat/abc"))
        // iOS-only and desktop-only hosts.
        assertNull(DeepLinks.parse("roboco://voice"))
        assertNull(DeepLinks.parse("roboco://more"))
        assertNull(DeepLinks.parse("roboco://open/chat/abc?workspace=0123456789abcdef"))
    }

    @Test
    fun parseRejectsMalformedChatPaths() {
        assertNull(DeepLinks.parse("roboco://chat"))
        assertNull(DeepLinks.parse("roboco://chat/"))
        assertNull(DeepLinks.parse("roboco://chat/a/b"))
        // A decoded slash is rejected like a literal one.
        assertNull(DeepLinks.parse("roboco://chat/a%2Fb"))
        // Malformed escapes.
        assertNull(DeepLinks.parse("roboco://chat/%GG"))
        assertNull(DeepLinks.parse("roboco://chat/%G1"))
        assertNull(DeepLinks.parse("roboco://chat/%"))
        assertNull(DeepLinks.parse("roboco://chat/a%"))
    }

    @Test
    fun parseRejectsNonEmptyPaths() {
        assertNull(DeepLinks.parse("roboco://settings/x"))
        assertNull(DeepLinks.parse("roboco://search/x"))
        assertNull(DeepLinks.parse("roboco://new/x"))
    }

    // ---------------------------------------------------------- parseRoute

    @Test
    fun parseRouteChat() {
        assertEquals(DeepLink.OpenChat("abc"), DeepLinks.parseRoute("chat:abc"))
        // Everything after the first 5 chars, verbatim.
        assertEquals(DeepLink.OpenChat("engine:v1:x"), DeepLinks.parseRoute("chat:engine:v1:x"))
        assertEquals(DeepLink.OpenChat("a b"), DeepLinks.parseRoute("chat:a b"))
    }

    @Test
    fun parseRouteKeywords() {
        assertEquals(DeepLink.NewSession(null), DeepLinks.parseRoute("new"))
        assertEquals(DeepLink.Settings, DeepLinks.parseRoute("more"))
        assertEquals(DeepLink.Search, DeepLinks.parseRoute("search"))
    }

    @Test
    fun parseRouteRejectsEverythingElse() {
        assertNull(DeepLinks.parseRoute(null))
        assertNull(DeepLinks.parseRoute(""))
        // An empty id.
        assertNull(DeepLinks.parseRoute("chat:"))
        // Exact and case-sensitive, not trimmed: `settings` (not an iOS
        // tab id), `New`, ` new`, `pair`, `chat` without a colon.
        assertNull(DeepLinks.parseRoute("settings"))
        assertNull(DeepLinks.parseRoute("New"))
        assertNull(DeepLinks.parseRoute(" new"))
        assertNull(DeepLinks.parseRoute("pair"))
        assertNull(DeepLinks.parseRoute("chat"))
        assertNull(DeepLinks.parseRoute("more "))
    }

    // -------------------------------------------------------- percentDecode

    @Test
    fun percentDecodePlainAndSpace() {
        assertEquals("a b", percentDecode("a%20b", plusAsSpace = false))
        assertEquals("a b", percentDecode("a+b", plusAsSpace = true))
        // `+` is data when plusAsSpace is false (the chat path rule).
        assertEquals("a+b", percentDecode("a+b", plusAsSpace = false))
        assertEquals("", percentDecode("", plusAsSpace = true))
        // Invalid UTF-8 becomes U+FFFD and is accepted.
        assertEquals("\uFFFD", percentDecode("%FF", plusAsSpace = false))
    }

    @Test
    fun percentDecodeMalformed() {
        assertNull(percentDecode("%G1", plusAsSpace = false))
        assertNull(percentDecode("%", plusAsSpace = false))
        assertNull(percentDecode("a%", plusAsSpace = false))
        assertNull(percentDecode("%1", plusAsSpace = false))
        assertNull(percentDecode("%0", plusAsSpace = true))
    }

    // ------------------------------------------------------------ ScopedIds

    @Test
    fun rawIdDecodesTheTuple() {
        assertEquals("raw-1", ScopedIds.rawId(scopedOf("""["k1","raw-1"]""")))
        assertEquals("raw-1", ScopedIds.rawId(scopedOf(""" ["k1", "raw-1"] """)))
    }

    @Test
    fun rawIdDecodesEscapes() {
        // JSON escapes: `["k\"1","r\u00e9\\x"]` decodes to raw id `r\u00e9\\x`
        // in Kotlin literals (r, é, backslash, x) — the ticket's expected
        // value `"ré\\x"`; its input column's `e6` is the typo (æ ≠ é).
        assertEquals("r\u00e9\\x", ScopedIds.rawId(scopedOf("""["k\"1","r\u00e9\\x"]""")))
    }

    @Test
    fun rawIdRejectsBadPayloads() {
        // A bad base64 payload.
        assertNull(ScopedIds.rawId(ScopedIds.PREFIX + "%%%"))
        assertNull(ScopedIds.rawId(ScopedIds.PREFIX + "a"))
        assertNull(ScopedIds.rawId(ScopedIds.PREFIX))
        // Not exactly a 2-element string array.
        assertNull(ScopedIds.rawId(scopedOf("""["k"]""")))
        assertNull(ScopedIds.rawId(scopedOf("""["k","r","x"]""")))
        assertNull(ScopedIds.rawId(scopedOf("""["k","r"]x""")))
        assertNull(ScopedIds.rawId(scopedOf("""{"a":1}""")))
        // An empty engine key (Rust rejects it too).
        assertNull(ScopedIds.rawId(scopedOf("""["","r"]""")))
        // A non-prefixed id.
        assertNull(ScopedIds.rawId("raw-1"))
        assertNull(ScopedIds.rawId(""))
    }

    // -------------------------------------------------------------- ChatIds

    @Test
    fun resolveKeepsAScopedIdUnchanged() {
        val scoped = scopedOf("""["k1","raw-1"]""")
        assertEquals(scoped, ChatIds.resolve(scoped, listOf(scopedOf("""["k2","raw-2"]"""))))
        // Even when it is not currently known (a just-created chat).
        assertEquals(scoped, ChatIds.resolve(scoped, emptyList()))
    }

    @Test
    fun resolveMapsARawIdToItsSingleScopedId() {
        val scoped = scopedOf("""["k1","raw-1"]""")
        val known = listOf(scopedOf("""["k2","raw-2"]"""), scoped)
        assertEquals(scoped, ChatIds.resolve("raw-1", known))
    }

    @Test
    fun resolveRefusesAnAmbiguousRawId() {
        // The same raw id on two engines: unchanged, so the router's open
        // fails and shows the error toast instead of misrouting.
        val one = scopedOf("""["k1","raw-1"]""")
        val two = scopedOf("""["k2","raw-1"]""")
        assertEquals("raw-1", ChatIds.resolve("raw-1", listOf(one, two)))
    }

    @Test
    fun resolvePassesAnUnknownRawIdThrough() {
        assertEquals("raw-9", ChatIds.resolve("raw-9", listOf(scopedOf("""["k1","raw-1"]"""))))
        assertEquals("raw-9", ChatIds.resolve("raw-9", emptyList()))
    }
}
