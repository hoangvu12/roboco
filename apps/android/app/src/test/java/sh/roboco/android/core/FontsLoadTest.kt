package sh.roboco.android.core

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import android.graphics.Typeface
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import uniffi.roboco_core.FaceRole

/**
 * `Fonts.load` (plain JVM, for the merge check, through the injectable
 * overload — no assets, no Robolectric): the 12 faces are read once in
 * role order with the TrueType magic validated, success **and** failure
 * are memoised without re-reading, and every failure path throws
 * [FontLoadException] with the ticket's message. The real-asset load
 * (12 chain typefaces, byte-equality against the merged assets) is
 * [FontChainTest] under Robolectric.
 */
class FontsLoadTest {

    private val magic = byteArrayOf(0, 1, 0, 0)

    /** Distinct per-face bytes, so equality proves the per-face routing. */
    private fun payload(path: String): ByteArray =
        magic + path.toByteArray(Charsets.UTF_8) + ByteArray(16)

    /** Serves every face with its distinct payload. */
    private fun goodOpener(): (String) -> InputStream = { path ->
        ByteArrayInputStream(payload(path))
    }

    /** The chains are only assembled on this path, never dereferenced. */
    private val stubTypefaces: (List<Pair<Face, ByteArray>>) -> Map<FaceRole, Typeface> =
        { emptyMap() }

    @After
    fun reset() {
        Fonts.resetForTest()
    }

    @Test
    fun typefaceThrowsBeforeAnyLoad() {
        // No FontChain.build runs in the plain-JVM classloader, so this is
        // the no-silent-default guarantee, deterministically.
        val failure = assertThrows(IllegalStateException::class.java) {
            FontChain.typeface(FaceRole.SANS)
        }
        assertEquals("FontChain not built: call Fonts.load(context) first", failure.message)
    }

    @Test
    fun loadReadsTwelveValidatedFacesInRoleOrder() {
        val loaded = Fonts.load(goodOpener(), stubTypefaces, { null })

        assertEquals(12, loaded.faceData.size)
        assertEquals(FaceRole.entries, loaded.faceData.map { it.role })
        for (face in Face.entries) {
            val data = loaded.faceData[face.ordinal]
            // Byte-equal to what the opener served: the same bytes go to
            // Rust and to the platform.
            assertArrayEquals("bytes for ${face.name}", payload(face.assetPath), data.bytes)
        }
        for (data in loaded.faceData) {
            assertArrayEquals("magic for ${data.role}", magic, data.bytes.copyOf(4))
        }
    }

    @Test
    fun theBuilderReceivesTheTwelveReadFaces() {
        var seen: List<Pair<Face, ByteArray>>? = null
        Fonts.load(
            goodOpener(),
            { faces -> seen = faces; emptyMap() },
            { null },
        )
        assertNotNull(seen)
        assertEquals(Face.entries, seen!!.map { it.first })
        for ((face, bytes) in seen!!) {
            assertArrayEquals(payload(face.assetPath), bytes)
        }
    }

    @Test
    fun cjkSourceComesFromTheHook() {
        assertEquals("fixture#2", Fonts.load(goodOpener(), stubTypefaces, { "fixture#2" }).cjkSource)
        // Loads are memoised (see secondLoadReturnsTheSameInstanceWithoutReopening):
        // reset so the second hook is actually consulted.
        Fonts.resetForTest()
        assertEquals(null, Fonts.load(goodOpener(), stubTypefaces, { null }).cjkSource)
    }

    @Test
    fun secondLoadReturnsTheSameInstanceWithoutReopening() {
        var opens = 0
        val open: (String) -> InputStream = { path ->
            opens += 1
            ByteArrayInputStream(payload(path))
        }
        val first = Fonts.load(open, stubTypefaces, { null })
        val second = Fonts.load(open, stubTypefaces, { null })
        assertSame(first, second)
        assertEquals(12, opens)
    }

    @Test
    fun missingAssetFailsLoudly() {
        val open: (String) -> InputStream = { path ->
            if (path == "fonts/GeistMono.ttf") throw FileNotFoundException(path)
            ByteArrayInputStream(payload(path))
        }
        val failure = assertThrows(FontLoadException::class.java) {
            Fonts.load(open, stubTypefaces, { null })
        }
        assertEquals("fonts/GeistMono.ttf", failure.path)
        assertEquals("Unreadable font asset: fonts/GeistMono.ttf", failure.message)
        assertEquals(FileNotFoundException::class.java, failure.cause?.javaClass)
    }

    @Test
    fun shortAssetFailsLoudly() {
        // Two of the four magic bytes: a truncated asset must not pass.
        val open: (String) -> InputStream = { path ->
            if (path == "fonts/Geist.ttf") ByteArrayInputStream(byteArrayOf(0, 1))
            else ByteArrayInputStream(payload(path))
        }
        val failure = assertThrows(FontLoadException::class.java) {
            Fonts.load(open, stubTypefaces, { null })
        }
        assertEquals("fonts/Geist.ttf", failure.path)
        assertEquals("Unreadable font asset: fonts/Geist.ttf", failure.message)
    }

    @Test
    fun wrongMagicFailsLoudly() {
        // 4F 54 54 4F = "OTTO", the CFF magic: a substituted file is bad.
        val otto = byteArrayOf(0x4F, 0x54, 0x54, 0x4F)
        val open: (String) -> InputStream = { path ->
            if (path == "fonts/Geist-SemiBold.ttf") ByteArrayInputStream(otto + ByteArray(16))
            else ByteArrayInputStream(payload(path))
        }
        val failure = assertThrows(FontLoadException::class.java) {
            Fonts.load(open, stubTypefaces, { null })
        }
        assertEquals("fonts/Geist-SemiBold.ttf", failure.path)
        assertEquals("Unreadable font asset: fonts/Geist-SemiBold.ttf", failure.message)
    }

    @Test
    fun failureIsMemoisedWithoutRereading() {
        var opens = 0
        val open: (String) -> InputStream = { path ->
            opens += 1
            throw FileNotFoundException(path)
        }
        val first = assertThrows(FontLoadException::class.java) {
            Fonts.load(open, stubTypefaces, { null })
        }
        val second = assertThrows(FontLoadException::class.java) {
            Fonts.load(open, stubTypefaces, { null })
        }
        // The same exception instance, and only the first face was read.
        assertSame(first, second)
        assertEquals(1, opens)
    }
}
