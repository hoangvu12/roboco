package sh.roboco.android.newsession

import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import sh.roboco.android.composer.DEFAULT_HARNESS

/**
 * What a new session will be created with (android-ui-parity #92) — the
 * port of iOS `NewSessionDraft` (`ios:Session/NewSessionViewController.swift:5-14`,
 * verbatim in the ticket): the **sticky** options of the New Session page.
 * Whatever was picked is still there the next time the page opens, also
 * after a relaunch, until a send uses up the text (only text and images
 * are cleared; these options survive).
 *
 * The ids are **scoped** ids (`ProjectView.id` / `DeviceView.id`,
 * `engine:v1:…`) — they mean nothing on another phone, so the store keeps
 * them in `noBackupFilesDir` (ticket 71/84 rule), never in a backup.
 * `hostId` is set only for projectless sessions ("No Project…"): when a
 * project is picked, `hostId == null` (the invariant `pickCheckout` keeps,
 * `:333-338`).
 */
data class NewSessionDraft(
    val projectId: String? = null,
    val hostId: String? = null,
    val branch: String? = null,
    val worktree: Boolean = false,
    val harness: String = DEFAULT_HARNESS,
    val model: String? = null,
    val effort: String? = null,
) {
    /**
     * Forgotten-engine ids removed (ticket 92's engine-forget rule): a
     * forgotten `projectId` also drops its `branch` (the branch belongs to
     * the checkout); a forgotten `hostId` drops only itself. Everything a
     * page can later assign runs through this, so a page that was open
     * during a forget can never write a forgotten id back.
     */
    fun stripping(forgotten: Set<String>): NewSessionDraft {
        var d = this
        val project = d.projectId
        if (project != null && project in forgotten) d = d.copy(projectId = null, branch = null)
        val host = d.hostId
        if (host != null && host in forgotten) d = d.copy(hostId = null)
        return d
    }
}

/**
 * The draft's JSON codec seam. [NewSessionDraftJson] (the production
 * codec, `org.json`) is a stub on the plain JVM — ticket 71's rule, the
 * same reason the JSON and store tests run under Robolectric — so
 * [NewSessionModelTest]'s store runs over a plain-Kotlin fake and stays
 * plain JVM, as the ticket's Verification section requires.
 */
interface NewSessionDraftCodec {
    /** The DataStore value: iOS's `JSONEncoder` output, nulls omitted. */
    fun encode(d: NewSessionDraft): String

    /** Lenient decode: null, garbage or wrong types read as [NewSessionDraft]. */
    fun decode(json: String?): NewSessionDraft
}

/**
 * The iOS `Codable` shape, hand-written on `org.json` (ticket 71's
 * library): synthesized Swift `Codable` uses the property names as keys
 * and **omits** nil optionals (`encodeIfPresent`) — so `worktree` and
 * `harness` are always written, the five optionals only when set, and
 * JSON `null` is never written.
 *
 * The decoder is deliberately **more lenient** than iOS's synthesized
 * `Decodable` (the ticket's ADAPT line): a missing `worktree` or
 * `harness` takes its default instead of failing the whole decode and
 * resetting the draft — every draft this app writes carries both keys
 * anyway. Not a JSON object, a `JSONException` or a wrong-typed value
 * still resets to [NewSessionDraft] (logged content-free: never the JSON).
 */
object NewSessionDraftJson : NewSessionDraftCodec {

    override fun encode(d: NewSessionDraft): String {
        val o = JSONObject()
        d.projectId?.let { o.put("projectId", it) }
        d.hostId?.let { o.put("hostId", it) }
        d.branch?.let { o.put("branch", it) }
        o.put("worktree", d.worktree)
        o.put("harness", d.harness)
        d.model?.let { o.put("model", it) }
        d.effort?.let { o.put("effort", it) }
        return o.toString()
    }

    override fun decode(json: String?): NewSessionDraft {
        if (json == null) return NewSessionDraft()
        return try {
            val o = JSONObject(json)
            // Strictly a JSON string: Android's getString coerces any value
            // (5 -> "5", an array -> its JSON text), which would keep a
            // wrong-typed field instead of resetting (the KDoc's contract).
            fun string(key: String): String? =
                if (o.has(key) && !o.isNull(key)) {
                    o.get(key) as? String ?: throw JSONException("$key is not a string")
                } else {
                    null
                }
            NewSessionDraft(
                projectId = string("projectId"),
                hostId = string("hostId"),
                branch = string("branch"),
                worktree = o.optBoolean("worktree", false),
                harness = string("harness") ?: DEFAULT_HARNESS,
                model = string("model"),
                effort = string("effort"),
            )
        } catch (e: JSONException) {
            reset()
        } catch (e: ClassCastException) {
            reset()
        }
    }

    /** Content-free: never the JSON, never ids. */
    private fun reset(): NewSessionDraft {
        Log.w("Roboco", "newSessionDraft unreadable; reset")
        return NewSessionDraft()
    }
}
