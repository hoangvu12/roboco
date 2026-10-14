package sh.roboco.android.shell

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The launch extras (android-ui-parity #36): `LaunchOptions.parse`'s
 * debug gate and type rules, the ungated `route` read, and
 * `applyWallpaper`'s call order against a recording [WallpaperTarget] —
 * every row of the ticket's `LaunchOptionsTest` list. Plain JUnit: the
 * seam over `android.util.Log` ("not mocked" on the plain JVM) is
 * replaced in [setUp] and restored in [tearDown].
 */
class LaunchOptionsTest {

    private val logs = mutableListOf<String>()
    private val originalLog = launchOptionsLog

    @Before
    fun setUp() {
        launchOptionsLog = { message -> logs.add(message) }
    }

    @After
    fun tearDown() {
        launchOptionsLog = originalLog
    }

    // --------------------------------------------------------------- parse

    @Test
    fun debuggableParsesEveryExtra() {
        val options = LaunchOptions.parse(
            mapOf(
                "wallpaper" to "/x/a.png",
                "wallpaper-effect" to "halftone",
                "harness" to "mock",
                "measureopen" to true,
                "pair" to "http://127.0.0.1:27701/pair#token=a, http://127.0.0.1:27702/pair#token=b",
            ),
            debuggable = true,
        )
        assertEquals("/x/a.png", options.wallpaper)
        assertEquals("halftone", options.wallpaperEffect)
        assertEquals("mock", options.harness)
        assertTrue(options.measureOpen)
        assertEquals(
            listOf(
                "http://127.0.0.1:27701/pair#token=a",
                "http://127.0.0.1:27702/pair#token=b",
            ),
            options.pair,
        )
    }

    // android-app#30: the flow rig's `pair` extra — comma-separated
    // pairing URLs, trimmed, blanks dropped.
    @Test
    fun pairSplitsTrimsAndDropsBlanks() {
        assertEquals(
            listOf("http://h:1/pair#token=a", "http://h:2/pair#token=b"),
            LaunchOptions.parse(
                mapOf("pair" to " http://h:1/pair#token=a ,,http://h:2/pair#token=b "),
                debuggable = true,
            ).pair,
        )
        assertEquals(emptyList<String>(), LaunchOptions.parse(mapOf("pair" to ""), debuggable = true).pair)
        assertEquals(emptyList<String>(), LaunchOptions.parse(emptyMap(), debuggable = true).pair)
    }

    @Test
    fun measureOpenAcceptsTrueAndCaseInsensitiveStrings() {
        assertTrue(LaunchOptions.parse(mapOf("measureopen" to true), debuggable = true).measureOpen)
        assertTrue(LaunchOptions.parse(mapOf("measureopen" to "true"), debuggable = true).measureOpen)
        assertTrue(LaunchOptions.parse(mapOf("measureopen" to "TRUE"), debuggable = true).measureOpen)
        // "1" (String) and 1 (Int) are not true.
        assertFalse(LaunchOptions.parse(mapOf("measureopen" to "1"), debuggable = true).measureOpen)
        assertFalse(LaunchOptions.parse(mapOf("measureopen" to 1), debuggable = true).measureOpen)
        // Absent is false.
        assertFalse(LaunchOptions.parse(emptyMap(), debuggable = true).measureOpen)
    }

    @Test
    fun nonDebuggableIgnoresEveryDebugExtra() {
        val extras = mapOf(
            "wallpaper" to "/x/a.png",
            "pair" to "http://h:1/pair#token=a",
            "wallpaper-effect" to "halftone",
            "harness" to "mock",
            "measureopen" to true,
        )
        assertEquals(LaunchOptions(), LaunchOptions.parse(extras, debuggable = false))
        assertEquals(emptyList<String>(), LaunchOptions.parse(extras, debuggable = false).pair)
    }

    @Test
    fun blankStringsAndWrongTypesReadAsAbsent() {
        val options = LaunchOptions.parse(
            mapOf(
                "wallpaper" to "   ",
                "wallpaper-effect" to 5,
                "harness" to "",
                "measureopen" to "yes",
                "pair" to 27,
            ),
            debuggable = true,
        )
        assertNull(options.wallpaper)
        assertNull(options.wallpaperEffect)
        assertNull(options.harness)
        assertFalse(options.measureOpen)
        assertEquals(emptyList<String>(), options.pair)
    }

    // --------------------------------------------------------------- route

    @Test
    fun routeIsUngatedAndNeverALaunchOptionsField() {
        val extras = mapOf<String, Any?>("route" to "chat:abc")
        // The route parses whatever the debug value is (it is not one of
        // the gated fields — parse with debuggable=false returns the empty
        // record, and route still reads it).
        assertEquals(DeepLink.OpenChat("abc"), LaunchOptions.route(extras))
        assertEquals(LaunchOptions(), LaunchOptions.parse(extras, debuggable = false))
        assertEquals(LaunchOptions(), LaunchOptions.parse(extras, debuggable = true))
        // Non-string, absent and unknown routes read as nothing.
        assertNull(LaunchOptions.route(mapOf("route" to 7)))
        assertNull(LaunchOptions.route(emptyMap()))
        assertNull(LaunchOptions.route(mapOf("route" to "gallery")))
    }

    // ------------------------------------------------------ applyWallpaper

    @Test
    fun wallpaperNoneOnlyRemoves() {
        val target = RecordingWallpapers()
        LaunchOptions(wallpaper = "none").applyWallpaper(target)
        assertEquals(listOf<Any>("remove"), target.calls)
    }

    @Test
    fun wallpaperFirstThenEffect() {
        val target = RecordingWallpapers()
        LaunchOptions(wallpaper = "/x/a.png", wallpaperEffect = "halftone")
            .applyWallpaper(target)
        assertEquals(listOf<Any>(File("/x/a.png"), "effect(halftone)"), target.calls)
    }

    @Test
    fun effectOnly() {
        val target = RecordingWallpapers()
        LaunchOptions(wallpaperEffect = "dither").applyWallpaper(target)
        assertEquals(listOf<Any>("effect(dither)"), target.calls)
    }

    @Test
    fun wallpaperIsCaseSensitive() {
        val target = RecordingWallpapers()
        // "None" is a file path, not the clear keyword (iOS: exact match).
        LaunchOptions(wallpaper = "None").applyWallpaper(target)
        assertEquals(listOf<Any>(File("None")), target.calls)
    }

    @Test
    fun neitherMeansNoCalls() {
        val target = RecordingWallpapers()
        LaunchOptions().applyWallpaper(target)
        assertTrue(target.calls.isEmpty())
    }

    @Test
    fun anUnknownEffectKeyWarnsAndChangesNothingElse() {
        val target = RecordingWallpapers()
        LaunchOptions(wallpaperEffect = "bogus").applyWallpaper(target)
        assertEquals(listOf<Any>("effect(bogus)"), target.calls)
        assertEquals(listOf("wallpaper-effect: unknown key bogus"), logs)
    }

    // ---------------------------------------------------------------- rig

    /** Records the calls, in order, and knows the five real effect keys. */
    private class RecordingWallpapers : WallpaperTarget {
        val calls = mutableListOf<Any>()

        override fun remove() {
            calls.add("remove")
        }

        override fun set(path: File) {
            calls.add(path)
        }

        override fun setEffect(key: String): Boolean {
            calls.add("effect($key)")
            return key in EFFECT_KEYS
        }
    }

    private companion object {
        val EFFECT_KEYS = setOf("none", "dither", "ascii", "halftone", "scanlines")
    }
}
