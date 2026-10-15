package sh.roboco.android.settings

import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import androidx.compose.runtime.Immutable
import java.io.FileNotFoundException
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import sh.roboco.android.R

/**
 * The Licenses screen's data (android-ui-parity #101): the four sections
 * ("Roboco", "Fonts and assets", "Android libraries", "Rust core"), the
 * pure parsers of the two generated JSON inputs, and the repository that
 * loads everything from the APK assets on `Dispatchers.IO`.
 *
 * Parsing is Android's built-in `org.json` — the ticket's rule: no
 * parsing library in the app (and no AboutLibraries library artifacts;
 * only the Gradle plugin's JSON).
 */

private const val TAG = "Roboco"

/** Asset paths, the names android-app#13/#31 and this ticket stage. */
object LicenseSources {
    const val RobocoLicense = "licenses/LICENSE"
    const val RobocoNotices = "licenses/THIRD_PARTY_NOTICES.md"
    const val GeistOfl = "licenses/Geist-OFL.txt"
    const val SymbolsLicense = "licenses/vscode-symbols-LICENSE.txt"
    const val SpdxDir = "licenses/spdx"
    const val RustCrates = "licenses/rust-crates.json"

    /** The Tailcat transport's Go licence bundle (android-next#02). */
    const val GoDir = "licenses/go"
    const val GoDependencies = "licenses/go/DEPENDENCIES.txt"
    const val KratosLicense = "licenses/go/LICENSE.kratos"
}

/**
 * The Symbols file icons' sheet header, from `THIRD_PARTY_NOTICES.md`'s
 * `## Symbols` paragraph (the pinned commit is part of the URL).
 */
const val SymbolsHeader =
    "Roboco bundles the SVG icon set and filename/folder associations from Symbols:\n" +
    "https://github.com/miguelsolorio/vscode-symbols/tree/296ef1b62287fb2315cb5651e552e09e8c8e1de8"

/**
 * The Solar Icons attribution (CC BY 4.0 requires visible attribution):
 * `THIRD_PARTY_NOTICES.md` does not list Solar, so the Licenses screen
 * carries it as its own entry (`crates/ui/src/icons.rs:4-7`).
 */
const val SolarNotice =
    "Solar Icons by 480 Design\n\n" +
    "Most of Roboco's line icons come from the Solar Icons set (Linear weight) by 480 Design. " +
    "Some Android icons are drawn for Roboco in the Solar Linear style.\n\n" +
    "Solar Icons is licensed under the Creative Commons Attribution 4.0 International License (CC BY 4.0):\n" +
    "https://creativecommons.org/licenses/by/4.0/"

/** Fallback strings when a library carries no licence at all. */
const val NoLicenseInformation = "No license information"

/** The Rust subtitle when `license` is null but texts are bundled. */
const val SeeLicenseFile = "See license file"

/**
 * One licence entry: a list row plus the full text its sheet shows. The
 * [texts] share `String` instances across entries (the Rust JSON
 * de-duplicates licence texts by index), so a few hundred crates stay a
 * few MB, not tens.
 */
@Immutable
data class LicenceEntry(
    /** `roboco`, `geist`, `android:<uniqueId>`, `rust:<name>@<version>`, … */
    val id: String,
    val title: String,
    val subtitle: String?,
    /** The sheet's first line(s); `null` for the asset-backed entries. */
    val header: String?,
    val texts: List<String>,
) {
    /** The sheet's whole body: the header plus the texts, blank-line-joined. */
    fun fullText(): String = (listOfNotNull(header) + texts).joinToString("\n\n")
}

/** One section of the Licenses screen; empty sections never exist. */
@Immutable
data class LicenceSection(
    val key: String,
    val title: String,
    val entries: List<LicenceEntry>,
)

// ---------------------------------------------------------------------------
// The pure parsers
// ---------------------------------------------------------------------------

/**
 * Parses the AboutLibraries JSON (`R.raw.aboutlibraries`) into the
 * "Android libraries" entries. Every field is optional (`optString`),
 * and the entries come out sorted case-insensitively by title, then by
 * version string.
 *
 * @param spdxText returns the committed canonical SPDX text for an id, or
 *   null — the caller's asset lookup. A licence resolves to
 *   `licenses[id].content` when non-blank, else the SPDX fallback, else
 *   `"<licence name>\n<licence url>"`
 */
fun parseAndroidLibraries(json: String, spdxText: (String) -> String?): List<LicenceEntry> {
    val root = JSONObject(json)
    val licenses = root.optJSONObject("licenses") ?: JSONObject()
    val libraries = root.optJSONArray("libraries") ?: return emptyList()
    // (sort version, entry): the sort key is the version string the
    // subtitle carries, kept next to the entry it belongs to.
    val parsed = ArrayList<Pair<String, LicenceEntry>>(libraries.length())
    for (index in 0 until libraries.length()) {
        val library = libraries.optJSONObject(index) ?: continue
        val uniqueId = library.optString("uniqueId", "")
        val name = library.optString("name", "")
        val version = library.optString("artifactVersion", "")
        val title = name.ifBlank { uniqueId }
        if (title.isBlank()) continue

        val licenceIds = library.optJSONArray("licenses") ?: JSONArray()
        val texts = ArrayList<String>(licenceIds.length())
        val licenceNames = ArrayList<String>(licenceIds.length())
        for (licenceIndex in 0 until licenceIds.length()) {
            val id = licenceIds.optString(licenceIndex, "")
            if (id.isBlank()) continue
            val licence = licenses.optJSONObject(id)
            val licenceName = licence?.optString("name", "")?.ifBlank { id } ?: id
            licenceNames += licenceName
            val content = licence?.optString("content", "") ?: ""
            if (content.isNotBlank()) {
                texts += content
            } else {
                val spdxId = licence?.optString("spdxId", "") ?: ""
                val fallback = if (spdxId.isBlank()) null else spdxText(spdxId)
                if (fallback != null) {
                    texts += fallback
                } else {
                    val url = licence?.optString("url", "") ?: ""
                    texts += if (url.isBlank()) licenceName else "$licenceName\n$url"
                }
            }
        }
        val licenceList = licenceNames.joinToString(", ")
        val names = licenceList.ifBlank { NoLicenseInformation }
        val subtitle = if (version.isBlank()) names else "$version · $names"
        val entry = LicenceEntry(
            id = "android:$uniqueId",
            title = title,
            subtitle = subtitle,
            header = buildString {
                append(title)
                if (version.isNotBlank()) append(' ').append(version)
                val website = library.optString("website", "")
                if (website.isNotBlank()) append('\n').append(website)
            }.ifBlank { null },
            texts = texts,
        )
        parsed += version to entry
    }
    parsed.sortWith(compareBy({ it.second.title.lowercase() }, { it.first }))
    return parsed.map { it.second }
}

/**
 * Parses `licenses/rust-crates.json` (`cargo xtask android-licenses`)
 * into the "Rust core" entries, sorted case-insensitively by name, then
 * by version. A `schema` other than 1 yields an empty list (the caller
 * logs). Shared text indices resolve to the **same** [String] instance:
 * each element of the `texts` array is read exactly once.
 */
fun parseRustCrates(json: String): List<LicenceEntry> {
    val root = JSONObject(json)
    if (root.optInt("schema", -1) != 1) return emptyList()
    val shared = root.optJSONArray("texts") ?: JSONArray()
    val texts = ArrayList<String>(shared.length())
    for (index in 0 until shared.length()) {
        texts += shared.optString(index, "")
    }
    val crates = root.optJSONArray("crates") ?: return emptyList()
    // (sort version, entry): the sort key is the crate's version string.
    val parsed = ArrayList<Pair<String, LicenceEntry>>(crates.length())
    for (index in 0 until crates.length()) {
        val crate = crates.optJSONObject(index) ?: continue
        val name = crate.optString("name", "")
        val version = crate.optString("version", "")
        if (name.isBlank() && version.isBlank()) continue
        // JSON `null` must read as absent: `optString` turns JSONObject.NULL
        // into the literal string "null".
        val licence = crate.optStringOrNull("license")
        val repository = crate.optStringOrNull("repository")
        val indices = crate.optJSONArray("texts") ?: JSONArray()
        val crateTexts = ArrayList<String>(indices.length())
        for (textIndex in 0 until indices.length()) {
            val sharedIndex = indices.optInt(textIndex, -1)
            if (sharedIndex in texts.indices) {
                texts[sharedIndex].let { text -> if (text.isNotBlank()) crateTexts += text }
            }
        }
        // "<version> · See license file" only when the crate *bundles*
        // texts; the fallback text below is not a bundled one.
        val bundlesTexts = crateTexts.isNotEmpty()
        if (!bundlesTexts) {
            crateTexts +=
                if (licence != null) "Licensed under $licence." else "No license text is bundled for this crate."
        }
        val subtitle = when {
            licence != null -> "$version · $licence"
            bundlesTexts -> "$version · $SeeLicenseFile"
            else -> "$version · $NoLicenseInformation"
        }
        val entry = LicenceEntry(
            id = "rust:$name@$version",
            title = name,
            subtitle = subtitle,
            header = buildString {
                append(name)
                if (version.isNotBlank()) append(' ').append(version)
                if (repository != null) append('\n').append(repository)
            },
            texts = crateTexts,
        )
        parsed += version to entry
    }
    parsed.sortWith(compareBy({ it.second.title.lowercase() }, { it.first }))
    return parsed.map { it.second }
}

/** The non-blank string at [key], or null when absent, JSON `null` or blank. */
/**
 * Parses the Go licence bundle's `DEPENDENCIES.txt` (android-next#02): a
 * tab-separated table with a `module  version  license_files` header and
 * one row per module, `license_files` comma-separated paths relative to
 * the bundle. One entry per row, in file order (the bundle is already
 * sorted, with the Go standard library last); a row whose texts are all
 * missing still lists the module with a fallback line.
 *
 * @param readText the text of a bundle-relative path, or null.
 */
fun parseGoDependencies(table: String, readText: (String) -> String?): List<LicenceEntry> {
    val entries = ArrayList<LicenceEntry>()
    for (line in table.lineSequence()) {
        val columns = line.split('\t')
        if (columns.size < 3) continue
        val (module, version, files) = columns
        if (module == "module" || module.isBlank()) continue
        val texts = files.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull(readText)
            .filter(String::isNotBlank)
        entries += LicenceEntry(
            id = "go:$module",
            title = module,
            subtitle = "$version \u00B7 $SeeLicenseFile",
            header = null,
            texts = texts.ifEmpty { listOf("No license text is bundled for this module.") },
        )
    }
    return entries
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key, "").ifBlank { null }

// ---------------------------------------------------------------------------
// The repository
// ---------------------------------------------------------------------------

/**
 * Loads the four sections of the Licenses screen from the APK assets and
 * the AboutLibraries raw resource. **Blocking**; call on
 * `Dispatchers.IO`. A missing asset drops just its entry (and an entry-less
 * section, header included) with a `Log.w` — neither ever crashes the
 * screen.
 */
class LicensesRepository(private val assets: AssetManager, private val resources: Resources) {

    /** The sections in screen order, empty sections dropped. */
    fun load(): List<LicenceSection> {
        val sections = listOf(
            LicenceSection(key = "roboco", title = "Roboco", entries = robocoEntries()),
            LicenceSection(key = "assets", title = "Fonts and assets", entries = assetEntries()),
            LicenceSection(key = "android", title = "Android libraries", entries = androidEntries()),
            LicenceSection(key = "rust", title = "Rust core", entries = rustEntries()),
            LicenceSection(key = "go", title = "Tailcat transport (Go)", entries = goEntries()),
        )
        return sections.filter { it.entries.isNotEmpty() }
    }

    private fun robocoEntries(): List<LicenceEntry> = listOfNotNull(
        assetEntry(id = "roboco", title = "Roboco", subtitle = "MIT License", path = LicenseSources.RobocoLicense),
        assetEntry(id = "notices", title = ThirdPartyNotices, subtitle = null, path = LicenseSources.RobocoNotices),
    )

    private fun assetEntries(): List<LicenceEntry> = listOfNotNull(
        assetEntry(id = "geist", title = "Geist and Geist Mono", subtitle = "SIL Open Font License 1.1", path = LicenseSources.GeistOfl),
        symbolEntry(),
        LicenceEntry(
            id = "solar",
            title = "Solar Icons",
            subtitle = "CC BY 4.0",
            header = null,
            texts = listOf(SolarNotice),
        ),
    )

    private fun symbolEntry(): LicenceEntry? {
        val text = readAsset(LicenseSources.SymbolsLicense) ?: return null
        return LicenceEntry(
            id = "symbols",
            title = "Symbols file icons",
            subtitle = "MIT License",
            header = null,
            texts = listOf(SymbolsHeader, text),
        )
    }

    private fun androidEntries(): List<LicenceEntry> {
        val json = try {
            resources.openRawResource(R.raw.aboutlibraries)
                .bufferedReader().use { it.readText() }
        } catch (e: Resources.NotFoundException) {
            Log.w(TAG, "aboutlibraries resource missing: $e")
            return emptyList()
        }
        val entries = try {
            parseAndroidLibraries(json) { spdxId -> readAsset("${LicenseSources.SpdxDir}/$spdxId.txt") }
        } catch (e: JSONException) {
            Log.w(TAG, "aboutlibraries JSON is malformed: $e")
            emptyList()
        }
        return entries
    }

    private fun rustEntries(): List<LicenceEntry> {
        val json = readAsset(LicenseSources.RustCrates) ?: return emptyList()
        return try {
            val entries = parseRustCrates(json)
            if (entries.isEmpty() && JSONObject(json).optInt("schema", -1) != 1) {
                Log.w(TAG, "rust-crates.json schema is not 1 — ignoring the file")
            }
            entries
        } catch (e: JSONException) {
            Log.w(TAG, "rust-crates.json is malformed: $e")
            emptyList()
        }
    }

    private fun goEntries(): List<LicenceEntry> {
        val table = readAsset(LicenseSources.GoDependencies) ?: return emptyList()
        val kratos = assetEntry(
            id = "go:roboco-tailcat",
            title = "roboco-tailcat adapter (from Kratos)",
            subtitle = "MIT License",
            path = LicenseSources.KratosLicense,
        )
        return listOfNotNull(kratos) +
            parseGoDependencies(table) { path -> readAsset("${LicenseSources.GoDir}/$path") }
    }

    /** One asset-backed entry, or null (logged) when the asset is missing. */
    private fun assetEntry(id: String, title: String, subtitle: String?, path: String): LicenceEntry? {
        val text = readAsset(path) ?: return null
        return LicenceEntry(id = id, title = title, subtitle = subtitle, header = null, texts = listOf(text))
    }

    private fun readAsset(path: String): String? = try {
        assets.open(path).bufferedReader().use { it.readText() }
    } catch (e: FileNotFoundException) {
        Log.w(TAG, "missing licence asset $path")
        null
    } catch (e: IOException) {
        Log.w(TAG, "could not read licence asset $path: $e")
        null
    }
}
