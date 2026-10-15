package sh.roboco.android.pairing

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import uniffi.roboco_core.EngineTransport

/**
 * The `engines` preference's JSON codec (ticket 71): round-trip of all
 * eight metadata fields, null optionals omitted, lenient decoding.
 *
 * Robolectric: `org.json` is a stub on the plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EngineMetaJsonTest {

    private val metaA = EngineMeta(
        engineId = "11111111-1111-4111-8111-111111111111",
        baseUrl = "http://10.0.2.2:27655",
        sessionId = "s1",
        label = "Pixel 9",
        pairedAtMs = 1_760_000_000_000,
        deviceId = null,
        name = null,
    )

    @Test
    fun roundTripsAllFieldsIncludingNullOptionals() {
        val meta = metaA.copy(deviceId = "dev-1", name = "MacBook Pro")

        val decoded = EngineMetaJson.decode(EngineMetaJson.encode(listOf(meta)))

        assertEquals(listOf(meta), decoded)
    }

    @Test
    fun nullOptionalsAreOmittedFromTheJson() {
        val encoded = EngineMetaJson.encode(listOf(metaA))
        val objectA = JSONArray(encoded).getJSONObject(0)

        assertEquals(
            setOf("engineId", "baseUrl", "sessionId", "label", "pairedAtMs", "transport"),
            objectA.keys().asSequence().toSet(),
        )
        assertEquals("direct", objectA.getString("transport"))
        assertEquals("http://10.0.2.2:27655", objectA.getString("baseUrl"))
        assertEquals(1_760_000_000_000, objectA.getLong("pairedAtMs"))
    }

    @Test
    fun anElementMissingARequiredKeyIsSkippedAndTheOthersLoad() {
        val missingBaseUrl = JSONObject()
            .put("engineId", "33333333-3333-4333-8333-333333333333")
            .put("sessionId", "s3")
            .put("label", "Pixel 9")
            .put("pairedAtMs", 1_760_000_000_000)
        val good = JSONObject()
            .put("engineId", metaA.engineId)
            .put("baseUrl", metaA.baseUrl)
            .put("sessionId", "s1")
            .put("label", "Pixel 9")
            .put("pairedAtMs", 1_760_000_000_000)
            .put("deviceId", "dev-1")
            .put("name", "MacBook Pro")

        val decoded = EngineMetaJson.decode(JSONArray().put(missingBaseUrl).put(good).toString())

        assertEquals(1, decoded?.size)
        assertEquals(metaA.copy(deviceId = "dev-1", name = "MacBook Pro"), decoded?.single())
    }

    @Test
    fun aWronglyTypedElementIsSkipped() {
        val wrongType = JSONObject()
            .put("engineId", JSONObject())
            .put("baseUrl", "http://10.0.2.2:27655")
            .put("sessionId", "s3")
            .put("label", "Pixel 9")
            .put("pairedAtMs", 1_760_000_000_000)

        val decoded = EngineMetaJson.decode(JSONArray().put(wrongType).toString())

        assertTrue(decoded != null && decoded.isEmpty())
    }

    @Test
    fun unknownKeysAreIgnored() {
        val withExtra = JSONObject()
            .put("engineId", metaA.engineId)
            .put("baseUrl", metaA.baseUrl)
            .put("sessionId", "s1")
            .put("label", "Pixel 9")
            .put("pairedAtMs", 1_760_000_000_000)
            .put("futureField", "surprise")

        val decoded = EngineMetaJson.decode(JSONArray().put(withExtra).toString())

        assertEquals(listOf(metaA), decoded)
    }

    @Test
    fun theFirstDuplicateEngineIdWins() {
        val first = JSONObject()
            .put("engineId", metaA.engineId)
            .put("baseUrl", "http://first:1")
            .put("sessionId", "s1")
            .put("label", "First")
            .put("pairedAtMs", 1)
        val second = JSONObject()
            .put("engineId", metaA.engineId)
            .put("baseUrl", "http://second:2")
            .put("sessionId", "s2")
            .put("label", "Second")
            .put("pairedAtMs", 2)

        val decoded = EngineMetaJson.decode(JSONArray().put(first).put(second).toString())

        assertEquals("http://first:1", decoded?.single()?.baseUrl)
        assertEquals(1L, decoded?.single()?.pairedAtMs)
    }

    @Test
    fun nonArrayTextIsRejectedAsUnreadable() {
        assertNull(EngineMetaJson.decode("not json at all"))
        assertNull(EngineMetaJson.decode("{}"))
    }

    @Test
    fun anEmptyArrayDecodesToNoEngines() {
        val decoded = EngineMetaJson.decode("[]")

        assertTrue(decoded != null && decoded.isEmpty())
    }

    @Test
    fun aSkippedElementLogsItsIndexWithoutTheRecord() {
        ShadowLog.clear()
        val missingLabel = JSONObject()
            .put("engineId", metaA.engineId)
            .put("baseUrl", metaA.baseUrl)
            .put("sessionId", "s1")
            .put("pairedAtMs", 1_760_000_000_000)

        EngineMetaJson.decode(JSONArray().put(missingLabel).toString())

        val logged = ShadowLog.getLogs().mapNotNull { it.msg }
        assertEquals(listOf("paired engine entry unreadable index=0"), logged)
    }

    @Test
    fun metaAndPairedEngineCarryTheSameEightFields() {
        val engine = newEngine(
            engineId = metaA.engineId,
            baseUrl = metaA.baseUrl,
            sessionId = "s1",
            pairedAtMs = 1_760_000_000_000,
        )

        assertEquals(metaA, engine.meta())
        assertEquals(
            engine.copy(credential = "cred-x"),
            metaA.toPaired("cred-x", EngineTransport.Direct),
        )
    }

    // android-next#02 ------------------------------------------------------

    @Test
    fun aRecordWithoutTransportDecodesAsDirect() {
        val legacy = JSONObject()
            .put("engineId", metaA.engineId)
            .put("baseUrl", metaA.baseUrl)
            .put("sessionId", "s1")
            .put("label", "Pixel 9")
            .put("pairedAtMs", 1_760_000_000_000)

        val decoded = EngineMetaJson.decode(JSONArray().put(legacy).toString())

        assertEquals(listOf(metaA), decoded)
        assertEquals(TransportKind.DIRECT, decoded?.single()?.transport)
    }

    @Test
    fun aTailcatMetaRoundTripsWithoutItsSecrets() {
        val engine = newTailcatEngine()
        val meta = engine.meta()
        val encoded = EngineMetaJson.encode(listOf(meta))

        assertEquals(TransportKind.TAILCAT, meta.transport)
        assertEquals(listOf(meta), EngineMetaJson.decode(encoded))
        assertEquals("tailcat", JSONArray(encoded).getJSONObject(0).getString("transport"))
        assertTrue("the address leaked into metadata", !encoded.contains("SECRET"))
    }

    @Test
    fun anUnknownTransportSkipsThatElement() {
        val future = JSONObject()
            .put("engineId", metaA.engineId)
            .put("baseUrl", metaA.baseUrl)
            .put("sessionId", "s1")
            .put("label", "Pixel 9")
            .put("pairedAtMs", 1_760_000_000_000)
            .put("transport", "carrier-pigeon")

        val decoded = EngineMetaJson.decode(JSONArray().put(future).toString())

        assertTrue(decoded != null && decoded.isEmpty())
    }
}
