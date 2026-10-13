package sh.roboco.android.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.roboco.android.R

/**
 * The licence-text completeness gate (android-ui-parity #101): every
 * licence the AboutLibraries JSON references must resolve to real text —
 * the plugin's `content`, else the committed `assets/licenses/spdx/<id>.txt`
 * — never the bare "`<name>\n<url>`" fallback. With `offlineMode = true`
 * the plugin pulls no SPDX texts from spdx.org, so the committed fallbacks
 * close the gap offline; this test fails whenever one is missing.
 *
 * Also checks the four licence assets the About/Licenses screens read
 * (`licenses/LICENSE`, `THIRD_PARTY_NOTICES.md`, `Geist-OFL.txt`,
 * `vscode-symbols-LICENSE.txt` — staged by `syncRobocoFonts` /
 * `syncRobocoLicenses`) and that every staged SPDX asset is non-empty.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidLicenceTextsTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun readAsset(path: String): String? = try {
        context().assets.open(path).bufferedReader().use { it.readText() }
    } catch (e: java.io.FileNotFoundException) {
        null
    }

    @Test
    fun everyAndroidLicenceResolvesToContentOrASpdxAsset() {
        val json = context().resources.openRawResource(R.raw.aboutlibraries)
            .bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val licenses = root.optJSONObject("licenses") ?: JSONObject()
        val libraries = root.optJSONArray("libraries") ?: org.json.JSONArray()

        val missing = mutableListOf<String>()
        for (index in 0 until libraries.length()) {
            val library = libraries.optJSONObject(index) ?: continue
            val uniqueId = library.optString("uniqueId", "<no uniqueId>")
            val licenceIds = library.optJSONArray("licenses") ?: continue
            for (licenceIndex in 0 until licenceIds.length()) {
                val id = licenceIds.optString(licenceIndex, "")
                val licence = licenses.optJSONObject(id)
                val content = licence?.optString("content", "") ?: ""
                if (content.isNotBlank()) continue
                val spdxId = licence?.optString("spdxId", "") ?: ""
                val fallback = if (spdxId.isBlank()) null else readAsset("licenses/spdx/$spdxId.txt")
                if (fallback == null || fallback.isBlank()) {
                    missing += "$uniqueId → $id (${spdxId.ifBlank { "no spdxId" }})"
                }
            }
        }
        assertTrue(
            "licences with no content and no assets/licenses/spdx/<id>.txt " +
                "(the sheet would show only name + URL): ${missing.joinToString()}",
            missing.isEmpty(),
        )
    }

    @Test
    fun theLicenceAssetsTheScreensReadAreStaged() {
        for (path in listOf(
            LicenseSources.RobocoLicense,
            LicenseSources.RobocoNotices,
            LicenseSources.GeistOfl,
            LicenseSources.SymbolsLicense,
        )) {
            val text = readAsset(path)
            assertTrue("$path must be staged and non-empty", !text.isNullOrEmpty())
        }
    }

    @Test
    fun stagedSpdxAssetsAreNonEmpty() {
        for (spdxId in listOf("Apache-2.0", "LGPL-2.1-or-later")) {
            val text = readAsset("${LicenseSources.SpdxDir}/$spdxId.txt")
            assertTrue("licenses/spdx/$spdxId.txt must be staged and non-empty", !text.isNullOrEmpty())
        }
    }
}
