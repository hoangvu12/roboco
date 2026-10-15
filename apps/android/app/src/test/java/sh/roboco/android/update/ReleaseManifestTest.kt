package sh.roboco.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `manifest.json` parser (android-next#04): the exact shape
 * `.github/workflows/release.yml` writes —
 * `{"version": "0.10.0", "date": "…", "files": {"<basename>": {"sha256": "…", "size": …}}}`
 * — read by a small hand-rolled reader so the parsing stays plain-JVM
 * testable (`org.json` is stubbed off Robolectric). Missing `files` map,
 * unknown keys, and missing `sha256`/`size` are all tolerated; malformed
 * JSON returns null. Plain JVM.
 */
class ReleaseManifestTest {

    private val full = """
        {
          "version": "0.10.0",
          "date": "2026-10-20T13:37:00Z",
          "files": {
            "roboco-0.10.0-android-arm64-v8a.apk": {
              "sha256": "aabb", "size": 25700000
            },
            "roboco-0.10.0-android-x86_64.apk": {
              "sha256": "ccdd", "size": 24100000
            },
            "roboco-0.10.0-linux-x86_64.tar.gz": { "sha256": "eeff" }
          }
        }
    """.trimIndent()

    @Test
    fun parsesTheFullShape() {
        val m = ReleaseManifest.parse(full)!!
        assertEquals("0.10.0", m.version)
        assertEquals("2026-10-20T13:37:00Z", m.date)
        assertEquals(3, m.files.size)
        val arm = m.files["roboco-0.10.0-android-arm64-v8a.apk"]!!
        assertEquals("aabb", arm.sha256)
        assertEquals(25700000L, arm.sizeBytes)
        val tar = m.files["roboco-0.10.0-linux-x86_64.tar.gz"]!!
        assertEquals("eeff", tar.sha256)
        assertNull(tar.sizeBytes)
    }

    @Test
    fun aMissingFilesMapIsEmpty() {
        val m = ReleaseManifest.parse("""{"version": "0.10.0"}""")!!
        assertTrue(m.files.isEmpty())
        assertNull(m.date)
    }

    @Test
    fun unknownKeysAreIgnoredAtEveryLevel() {
        // The desktop's serde parser ignores unknown fields (no
        // deny_unknown_fields); this parser must do the same, including
        // nested objects and arrays of unknown shape.
        val m = ReleaseManifest.parse(
            """
            {"version": "1.0.0", "extra": {"nested": [1, 2, {"x": true}]},
             "files": {"a.apk": {"sha256": "h", "unknown": 5, "deep": [null, false]}}}
            """,
        )!!
        assertEquals("1.0.0", m.version)
        assertEquals(1, m.files.size)
        assertEquals("h", m.files["a.apk"]!!.sha256)
    }

    @Test
    fun sha256AndSizeAreOptional() {
        val m = ReleaseManifest.parse(
            """{"version": "1.0.0", "files": {"a.apk": {}}}""",
        )!!
        assertNull(m.files["a.apk"]!!.sha256)
        assertNull(m.files["a.apk"]!!.sizeBytes)
    }

    @Test
    fun aNonObjectFileEntryIsSkipped() {
        val m = ReleaseManifest.parse(
            """{"version": "1.0.0", "files": {"a.apk": 5, "b.apk": {"sha256": "x"}}}""",
        )!!
        assertEquals(1, m.files.size)
        assertNotNull(m.files["b.apk"])
    }

    @Test
    fun aFloatingPointSizeRoundsToBytes() {
        val m = ReleaseManifest.parse(
            """{"version": "1.0.0", "files": {"a.apk": {"size": 24.9}}}""",
        )!!
        assertEquals(25L, m.files["a.apk"]!!.sizeBytes)
    }

    @Test
    fun malformedOrEmptyVersionsReturnNull() {
        assertNull(ReleaseManifest.parse("{"))
        assertNull(ReleaseManifest.parse(""))
        assertNull(ReleaseManifest.parse("""{"files": {}}"""))
        assertNull(ReleaseManifest.parse("""{"version": ""}"""))
        assertNull(ReleaseManifest.parse("""{"version": 5}"""))
        assertNull(ReleaseManifest.parse("[]"))
        // Trailing content after the object is malformed.
        assertNull(ReleaseManifest.parse("""{"version": "1"} trailing"""))
    }

    // ---------------------------------------------- the asset-name rules

    @Test
    fun apkAssetNamesMatchTheReleaseWorkflow() {
        // release.yml: dist/roboco-$ver-android-$abi.apk, with $ver the
        // manifest's own version string (no `v`).
        assertEquals(
            "roboco-0.10.0-android-arm64-v8a.apk",
            apkAssetName("0.10.0", "arm64-v8a"),
        )
        assertEquals(
            "roboco-0.10.0-android-x86_64.apk",
            apkAssetName("0.10.0", "x86_64"),
        )
    }

    @Test
    fun apkForPicksTheMatchingAssetByAbi() {
        val m = ReleaseManifest.parse(full)!!
        assertEquals(
            "aabb",
            m.apkFor("arm64-v8a")!!.sha256,
        )
        assertEquals(
            "ccdd",
            m.apkFor("x86_64")!!.sha256,
        )
        assertNull(m.apkFor("riscv64"))
    }

    @Test
    fun assetAbiMapsTheDeviceAbiToAReleasedAbi() {
        assertEquals("arm64-v8a", assetAbi("arm64-v8a"))
        assertEquals("x86_64", assetAbi("x86_64"))
        // Secondary ABIs the device may report first are not released as
        // split APKs; the flow falls back to the releases page.
        assertNull(assetAbi("armeabi-v7a"))
        assertNull(assetAbi("x86"))
        assertNull(assetAbi("riscv64"))
        assertNull(assetAbi(""))
    }
}
