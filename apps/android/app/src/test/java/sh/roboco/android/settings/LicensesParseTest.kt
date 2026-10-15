package sh.roboco.android.settings

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two pure parsers (android-ui-parity #101): the AboutLibraries JSON
 * (`R.raw.aboutlibraries`) and the xtask's `rust-crates.json`.
 *
 * Robolectric: `org.json` is a stub on the plain JVM. Fixtures are built
 * with the same `org.json` API the parsers read, so the shapes match
 * byte-for-byte what the Gradle plugin and the xtask write.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LicensesParseTest {

    // ---------------------------------------------------------- helpers

    private fun licence(
        id: String,
        name: String,
        url: String,
        spdxId: String,
        content: String,
    ): Pair<String, JSONObject> =
        id to JSONObject()
            .put("name", name)
            .put("url", url)
            .put("spdxId", spdxId)
            .put("content", content)

    private fun library(
        uniqueId: String,
        name: String,
        version: String,
        website: String,
        licenceIds: List<String>,
    ): JSONObject =
        JSONObject()
            .put("uniqueId", uniqueId)
            .put("name", name)
            .put("artifactVersion", version)
            .put("website", website)
            .put("licenses", JSONArray(licenceIds))

    private fun aboutlibrariesJson(
        licenses: List<Pair<String, JSONObject>>,
        libraries: List<JSONObject>,
    ): String {
        val licensesObject = JSONObject()
        for ((id, licence) in licenses) licensesObject.put(id, licence)
        return JSONObject()
            .put("libraries", JSONArray(libraries))
            .put("licenses", licensesObject)
            .toString()
    }

    // ------------------------------------------- parseAndroidLibraries

    @Test
    fun buildsIdsSubtitlesAndHeaders() {
        val json = aboutlibrariesJson(
            licenses = listOf(
                licence(
                    id = "apache-2",
                    name = "Apache License 2.0",
                    url = "https://www.apache.org/licenses/LICENSE-2.0",
                    spdxId = "Apache-2.0",
                    content = "Apache text",
                ),
                licence(id = "mit", name = "MIT License", url = "https://mit", spdxId = "MIT", content = ""),
            ),
            libraries = listOf(
                library(
                    uniqueId = "androidx.compose.foundation:foundation",
                    name = "Compose Foundation",
                    version = "1.10.0",
                    website = "https://developer.android.com/jetpack/compose",
                    licenceIds = listOf("apache-2"),
                ),
                library(
                    uniqueId = "org.jetbrains.kotlin:kotlin-stdlib",
                    name = "Kotlin Stdlib",
                    version = "2.4.21",
                    website = "",
                    licenceIds = listOf("apache-2", "mit"),
                ),
            ),
        )

        val entries = parseAndroidLibraries(json) { null }

        assertEquals(2, entries.size)
        val foundation = entries.first { it.id == "android:androidx.compose.foundation:foundation" }
        assertEquals("Compose Foundation", foundation.title)
        assertEquals("1.10.0 · Apache License 2.0", foundation.subtitle)
        assertEquals(
            "Compose Foundation 1.10.0\nhttps://developer.android.com/jetpack/compose",
            foundation.header,
        )
        assertEquals(listOf("Apache text"), foundation.texts)
        // fullText = the whole header (website line included) + the texts.
        assertEquals(
            "Compose Foundation 1.10.0\nhttps://developer.android.com/jetpack/compose\n\nApache text",
            foundation.fullText(),
        )

        // Multiple licences join with ", "; no website → no second header line.
        val stdlib = entries.first { it.id == "android:org.jetbrains.kotlin:kotlin-stdlib" }
        assertEquals("2.4.21 · Apache License 2.0, MIT License", stdlib.subtitle)
        assertEquals("Kotlin Stdlib 2.4.21", stdlib.header)
    }

    @Test
    fun licenceTextsFallBackToSpdxThenNameUrl() {
        val json = aboutlibrariesJson(
            licenses = listOf(
                licence(id = "content", name = "With content", url = "https://with", spdxId = "X-1", content = "the content"),
                licence(id = "spdx", name = "Without content", url = "https://spdx", spdxId = "X-2", content = ""),
                licence(id = "no-id", name = "No spdx id", url = "https://noid", spdxId = "", content = ""),
                licence(id = "no-url", name = "No url", url = "", spdxId = "", content = ""),
            ),
            libraries = listOf(
                library("l:1", "L1", "1", "", listOf("content")),
                library("l:2", "L2", "2", "", listOf("spdx")),
                library("l:3", "L3", "3", "", listOf("no-id")),
                library("l:4", "L4", "4", "", listOf("no-url")),
            ),
        )
        val spdx = mapOf("X-2" to "the spdx fallback text")

        val entries = parseAndroidLibraries(json) { id -> spdx[id] }

        assertEquals("the content", entries.first { it.id == "android:l:1" }.texts.single())
        assertEquals("the spdx fallback text", entries.first { it.id == "android:l:2" }.texts.single())
        assertEquals("No spdx id\nhttps://noid", entries.first { it.id == "android:l:3" }.texts.single())
        assertEquals("No url", entries.first { it.id == "android:l:4" }.texts.single())
    }

    @Test
    fun noLicensesMeansNoLicenseInformation() {
        val json = aboutlibrariesJson(
            licenses = emptyList(),
            libraries = listOf(library("l:1", "L1", "1", "", emptyList())),
        )
        assertEquals(
            "1 · No license information",
            parseAndroidLibraries(json) { null }.single().subtitle,
        )
        // Without a version: only the names.
        val noVersion = aboutlibrariesJson(
            licenses = emptyList(),
            libraries = listOf(library("l:2", "L2", "", "", emptyList())),
        )
        assertEquals(
            "No license information",
            parseAndroidLibraries(noVersion) { null }.single().subtitle,
        )
    }

    @Test
    fun aBlankNameFallsBackToTheUniqueId() {
        val json = aboutlibrariesJson(
            licenses = emptyList(),
            libraries = listOf(library("l:anon", "", "1", "", emptyList())),
        )
        val entry = parseAndroidLibraries(json) { null }.single()
        assertEquals("android:l:anon", entry.id)
        assertEquals("l:anon", entry.title)
        assertEquals("l:anon 1", entry.header)
    }

    @Test
    fun entriesSortCaseInsensitivelyByTitleThenVersion() {
        val json = aboutlibrariesJson(
            licenses = emptyList(),
            libraries = listOf(
                library("l:b", "beta", "2", "", emptyList()),
                library("l:A", "Alpha", "2", "", emptyList()),
                library("l:a1", "alpha", "10", "", emptyList()),
                library("l:a0", "alpha", "9", "", emptyList()),
            ),
        )
        // The ticket orders ties "by version string" (§A, like the Rust
        // test below): lexicographically "10" < "2" < "9".
        assertEquals(
            listOf("android:l:a1", "android:l:A", "android:l:a0", "android:l:b"),
            parseAndroidLibraries(json) { null }.map { it.id },
        )
    }

    // -------------------------------------------------- parseRustCrates

    private fun rustJson(
        crates: List<Triple<String, String, JSONObject>>,
        texts: List<String>,
    ): String {
        val cratesArray = JSONArray()
        for ((name, version, fields) in crates) {
            val crate = JSONObject()
                .put("name", name)
                .put("version", version)
            for (key in fields.keys()) crate.put(key, fields.get(key))
            cratesArray.put(crate)
        }
        return JSONObject()
            .put("schema", 1)
            .put("crates", cratesArray)
            .put("texts", JSONArray(texts))
            .toString()
    }

    @Test
    fun resolvesSharedTextIndicesToTheSameStringInstance() {
        val json = rustJson(
            crates = listOf(
                Triple("anyhow", "1.0.104", JSONObject().put("license", "MIT OR Apache-2.0").put("repository", "https://github.com/dtolnay/anyhow").put("texts", JSONArray(listOf(0, 1)))),
                Triple("serde", "1.0.229", JSONObject().put("license", "MIT OR Apache-2.0").put("texts", JSONArray(listOf(1)))),
            ),
            texts = listOf("MIT text", "Apache text"),
        )
        val entries = parseRustCrates(json)
        assertEquals(2, entries.size)
        val anyhow = entries.first { it.id == "rust:anyhow@1.0.104" }
        val serde = entries.first { it.id == "rust:serde@1.0.229" }
        assertEquals(listOf("MIT text", "Apache text"), anyhow.texts)
        assertSame("index 1 must be the same instance in both crates", anyhow.texts[1], serde.texts[0])
        assertEquals(
            "anyhow 1.0.104\nhttps://github.com/dtolnay/anyhow",
            anyhow.header,
        )
        // No repository → the header is just "<name> <version>".
        assertEquals("serde 1.0.229", serde.header)
    }

    @Test
    fun nullLicenseMeansSeeLicenseFileOrNoLicenseInformation() {
        val json = rustJson(
            crates = listOf(
                Triple("with-text", "1.0", JSONObject().put("license", JSONObject.NULL).put("texts", JSONArray(listOf(0)))),
                Triple("without-text", "1.0", JSONObject().put("license", JSONObject.NULL)),
            ),
            texts = listOf("some bundled licence file"),
        )
        val entries = parseRustCrates(json)
        val withText = entries.first { it.id == "rust:with-text@1.0" }
        val withoutText = entries.first { it.id == "rust:without-text@1.0" }
        assertEquals("1.0 · See license file", withText.subtitle)
        assertEquals(listOf("some bundled licence file"), withText.texts)
        assertEquals("1.0 · No license information", withoutText.subtitle)
        assertEquals("No license text is bundled for this crate.", withoutText.texts.single())
    }

    @Test
    fun emptyTextsWithALicenceSayLicensedUnder() {
        val json = rustJson(
            crates = listOf(
                Triple("uniffi", "0.32.2", JSONObject().put("license", "MPL-2.0")),
            ),
            texts = emptyList(),
        )
        val entry = parseRustCrates(json).single()
        assertEquals("0.32.2 · MPL-2.0", entry.subtitle)
        assertEquals("Licensed under MPL-2.0.", entry.texts.single())
        assertEquals("uniffi 0.32.2\n\nLicensed under MPL-2.0.", entry.fullText())
    }

    @Test
    fun fullTextJoinsHeaderAndTextsWithABlankLine() {
        val withHeader = LicenceEntry(
            id = "rust:anyhow@1.0.104",
            title = "anyhow",
            subtitle = "1.0.104 · MIT OR Apache-2.0",
            header = "anyhow 1.0.104\nhttps://github.com/dtolnay/anyhow",
            texts = listOf("MIT text", "Apache text"),
        )
        assertEquals(
            "anyhow 1.0.104\nhttps://github.com/dtolnay/anyhow\n\nMIT text\n\nApache text",
            withHeader.fullText(),
        )
        val withoutHeader = LicenceEntry(
            id = "geist",
            title = "Geist and Geist Mono",
            subtitle = "SIL Open Font License 1.1",
            header = null,
            texts = listOf("OFL text"),
        )
        assertEquals("OFL text", withoutHeader.fullText())
    }

    @Test
    fun aSchemaOtherThanOneYieldsAnEmptyList() {
        for (schema in listOf(0, 2, -1)) {
            val json = JSONObject()
                .put("schema", schema)
                .put("crates", JSONArray().put(JSONObject().put("name", "anyhow").put("version", "1.0")))
                .put("texts", JSONArray(listOf("text")))
                .toString()
            assertTrue("schema $schema must be ignored", parseRustCrates(json).isEmpty())
        }
    }

    @Test
    fun rustEntriesSortCaseInsensitivelyByNameThenVersion() {
        val json = rustJson(
            crates = listOf(
                Triple("Zed", "1.0", JSONObject().put("texts", JSONArray(listOf(0)))),
                Triple("anyhow", "1.0.9", JSONObject().put("texts", JSONArray(listOf(0)))),
                Triple("Anyhow", "1.0.104", JSONObject().put("texts", JSONArray(listOf(0)))),
            ),
            texts = listOf("text"),
        )
        // "Anyhow" and "anyhow" tie case-insensitively; the version string
        // then orders them ("1.0.104" < "1.0.9" lexicographically), and
        // "Zed" sorts after both.
        assertEquals(
            listOf("rust:Anyhow@1.0.104", "rust:anyhow@1.0.9", "rust:Zed@1.0"),
            parseRustCrates(json).map { it.id },
        )
    }

    // android-next#02: the Tailcat transport's Go bundle ------------------

    @Test
    fun goDependenciesBecomeOneEntryPerModuleWithTheirTexts() {
        val table = listOf(
            "module\tversion\tlicense_files",
            "github.com/tailscale/tailcat\tv0.6.1\tmodules/028-tailcat-LICENSE.txt",
            "golang.org/x/net\tv0.59.0\tmodules/037-net-LICENSE.txt,modules/037-net-PATENTS.txt",
            "example.com/missing\tv1.0.0\tmodules/999-missing-LICENSE.txt",
            "Go standard library/runtime\tgo1.27.1\tgo/LICENSE.txt,go/PATENTS.txt",
            "",
        ).joinToString("\n")
        val files = mapOf(
            "modules/028-tailcat-LICENSE.txt" to "BSD-3 tailcat",
            "modules/037-net-LICENSE.txt" to "BSD-3 x/net",
            "modules/037-net-PATENTS.txt" to "patents x/net",
            "go/LICENSE.txt" to "BSD-3 Go",
            "go/PATENTS.txt" to "patents Go",
        )

        val entries = parseGoDependencies(table) { files[it] }

        assertEquals(
            listOf(
                "go:github.com/tailscale/tailcat",
                "go:golang.org/x/net",
                "go:example.com/missing",
                "go:Go standard library/runtime",
            ),
            entries.map { it.id },
        )
        assertEquals(listOf("BSD-3 x/net", "patents x/net"), entries[1].texts)
        assertEquals("v0.59.0 \u00B7 See license file", entries[1].subtitle)
        assertEquals(listOf("No license text is bundled for this module."), entries[2].texts)
        assertEquals(listOf("BSD-3 Go", "patents Go"), entries[3].texts)
    }

    @Test
    fun aMalformedGoTableYieldsNoEntries() {
        assertTrue(parseGoDependencies("not a table") { null }.isEmpty())
        assertTrue(parseGoDependencies("module\tversion\tlicense_files\n") { null }.isEmpty())
    }
}
