package sh.roboco.android.pairing

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import uniffi.roboco_core.EngineRoomRef
import uniffi.roboco_core.EngineTransport
import uniffi.roboco_core.PairedEngine

/**
 * How a stored engine is dialed (android-next#02). The metadata says only
 * which kind; a Tailcat engine's address and client identity are secrets
 * and live encrypted under `tc:<engineId>`, never here.
 */
internal enum class TransportKind(val wire: String) {
    DIRECT("direct"),
    TAILCAT("tailcat"),
    ;

    companion object {
        fun fromWire(value: String): TransportKind? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The non-secret half of a paired-engine record, JSON-encoded as the
 * `engines` preference (pairing order). Only [PairedEngine.credential] —
 * and, for a Tailcat engine, its address and client state — are secret
 * and encrypted; keeping this metadata readable is what lets a lost
 * Keystore key degrade to "Session revoked / Pair again" instead of a
 * vanished engine (spec W8). For a Tailcat engine [baseUrl] is the
 * non-secret `tailcat:<8 hex>` fingerprint.
 */
internal data class EngineMeta(
    val engineId: String,
    val baseUrl: String,
    val sessionId: String,
    val label: String,
    val pairedAtMs: Long,
    val deviceId: String?,
    val name: String?,
    val transport: TransportKind = TransportKind.DIRECT,
    /** The room this engine was joined through (ADR 0011), if any.
     * Absent on records written before rooms — decoding stays additive. */
    val room: EngineRoomMeta? = null,
) {
    fun toPaired(credential: String, transport: EngineTransport): PairedEngine = PairedEngine(
        engineId = engineId,
        baseUrl = baseUrl,
        credential = credential,
        sessionId = sessionId,
        label = label,
        pairedAtMs = pairedAtMs,
        deviceId = deviceId,
        name = name,
        transport = transport,
        room = room?.let { EngineRoomRef(roomId = it.roomId, label = it.label) },
    )
}

/**
 * The room an engine was joined through (ADR 0011, ticket 04) as the
 * metadata carries it: the public room id and the room's name — no
 * secret, no addresses (those live in the `room:` blob).
 */
internal data class EngineRoomMeta(
    val roomId: String,
    val label: String,
)

internal fun PairedEngine.meta(): EngineMeta = EngineMeta(
    engineId = engineId,
    baseUrl = baseUrl,
    sessionId = sessionId,
    label = label,
    pairedAtMs = pairedAtMs,
    deviceId = deviceId,
    name = name,
    transport = if (transport is EngineTransport.Tailcat) TransportKind.TAILCAT else TransportKind.DIRECT,
    room = room?.let { EngineRoomMeta(roomId = it.roomId, label = it.label) },
)

/**
 * Codec for the `engines` preference: a JSON array of [EngineMeta]
 * objects. Decoding is lenient — a missing required key, a wrong type or
 * a duplicate `engineId` skips just that element (the first of a
 * duplicate wins); unknown keys are ignored. `decode` returns `null`
 * when the text is not a JSON array at all, which the store logs and
 * replaces on its next write.
 */
internal object EngineMetaJson {

    private const val TAG = "Roboco"

    fun encode(metas: List<EngineMeta>): String {
        val array = JSONArray()
        for (meta in metas) {
            val element = JSONObject()
                .put("engineId", meta.engineId)
                .put("baseUrl", meta.baseUrl)
                .put("sessionId", meta.sessionId)
                .put("label", meta.label)
                .put("pairedAtMs", meta.pairedAtMs)
                .put("transport", meta.transport.wire)
            meta.deviceId?.let { element.put("deviceId", it) }
            meta.name?.let { element.put("name", it) }
            meta.room?.let { room ->
                element.put("room", JSONObject().put("roomId", room.roomId).put("label", room.label))
            }
            array.put(element)
        }
        return array.toString()
    }

    /** `null` = not a JSON array; otherwise the readable elements, in order. */
    fun decode(text: String): List<EngineMeta>? {
        val array = try {
            JSONArray(text)
        } catch (_: JSONException) {
            return null
        }
        val metas = ArrayList<EngineMeta>(array.length())
        val seen = HashSet<String>()
        for (index in 0 until array.length()) {
            val meta = (array.opt(index) as? JSONObject)?.let(::readMeta)
            if (meta == null) {
                Log.w(TAG, "paired engine entry unreadable index=$index")
                continue
            }
            if (seen.add(meta.engineId)) {
                metas.add(meta)
            }
        }
        return metas
    }

    private fun readMeta(element: JSONObject): EngineMeta? = try {
        EngineMeta(
            engineId = string(element, "engineId"),
            baseUrl = string(element, "baseUrl"),
            sessionId = string(element, "sessionId"),
            label = string(element, "label"),
            pairedAtMs = element.getLong("pairedAtMs"),
            deviceId = optional(element, "deviceId"),
            name = optional(element, "name"),
            // Records written before android-next#02 have no transport:
            // they are direct. An unknown kind (a newer app's) skips the
            // element rather than dial it the wrong way.
            transport = optional(element, "transport")
                ?.let { TransportKind.fromWire(it) ?: throw JSONException("unknown transport") }
                ?: TransportKind.DIRECT,
            // ADR 0011 (ticket 04): the room reference is additive — a
            // missing `room` (every record written before rooms) reads as
            // none, and a malformed one degrades to none rather than
            // dropping the whole engine over metadata.
            room = roomMeta(element.optJSONObject("room")),
        )
    } catch (_: JSONException) {
        null
    }

    /**
     * The `room` object → [EngineRoomMeta], or null. A missing id (or a
     * malformed room of any shape) degrades to "no room" — never a
     * thrown [JSONException], which would drop the whole engine over
     * metadata (the credential store is the source of truth, not this
     * JSON; an empty label is legal — it only ever was a display name).
     */
    private fun roomMeta(element: JSONObject?): EngineRoomMeta? {
        // Absent on records written before rooms: additive — no room.
        if (element == null) return null
        val roomId = element.optString("roomId")
        if (roomId.isEmpty()) return null
        return EngineRoomMeta(roomId = roomId, label = element.optString("label"))
    }

    /** `getString` coerces any value (`{}` included); only a real string is accepted. */
    private fun string(element: JSONObject, key: String): String =
        element.get(key) as? String ?: throw JSONException("$key is not a string")

    private fun optional(element: JSONObject, key: String): String? =
        if (element.has(key) && !element.isNull(key)) string(element, key) else null
}
