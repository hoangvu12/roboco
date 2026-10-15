package sh.roboco.android.newsession

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [NewSessionDraftJson] (android-ui-parity #92): the iOS `Codable` shape —
 * the seven exact keys, nil optionals omitted (never JSON `null`), the
 * lenient decode (missing `worktree`/`harness` take their defaults; a
 * missing or JSON-null optional is `null`; garbage / non-object / wrong
 * types reset to `NewSessionDraft()` without crashing) — plus
 * [NewSessionDraft.stripping].
 *
 * Robolectric, like ticket 71/84's JSON tests: `org.json` is a stub on
 * the plain JVM (`@Config(sdk = [36])` per the repo's Robolectric rule).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NewSessionDraftJsonTest {

    @Test
    fun encodeOmitsNullOptionalsAndWritesWorktreeAndHarness() {
        val json = NewSessionDraftJson.encode(NewSessionDraft(projectId = "p", effort = "low"))
        val o = JSONObject(json)
        assertEquals(setOf("projectId", "worktree", "harness", "effort"), o.keys().asSequence().toSet())
        // Never a JSON null: every written key carries a value.
        for (key in o.keys().asSequence().toSet()) assertFalse("key $key is null", o.isNull(key))
        assertEquals("p", o.getString("projectId"))
        assertEquals(false, o.getBoolean("worktree"))
        assertEquals("claude-code", o.getString("harness"))
        assertEquals("low", o.getString("effort"))
    }

    @Test
    fun roundTripAllSevenFields() {
        val d = NewSessionDraft(
            projectId = "engine:v1:abc",
            hostId = "engine:v1:def",
            branch = "feature/x",
            worktree = true,
            harness = "codex",
            model = "gpt-5.5",
            effort = "high",
        )
        assertEquals(d, NewSessionDraftJson.decode(NewSessionDraftJson.encode(d)))
    }

    @Test
    fun decodeMissingKeysTakeDefaults() {
        val d = NewSessionDraftJson.decode("{\"projectId\":\"p\"}")
        assertEquals("p", d.projectId)
        assertEquals(null, d.hostId)
        assertEquals(null, d.branch)
        assertEquals(false, d.worktree)
        assertEquals("claude-code", d.harness)
        assertEquals(null, d.model)
        assertEquals(null, d.effort)
    }

    @Test
    fun decodeJsonNullOptionalIsNull() {
        val d = NewSessionDraftJson.decode("{\"projectId\":null,\"branch\":\"b\",\"worktree\":true}")
        assertEquals(null, d.projectId)
        assertEquals("b", d.branch)
        assertEquals(true, d.worktree)
    }

    @Test
    fun decodeGarbageArrayAndNullReset() {
        assertEquals(NewSessionDraft(), NewSessionDraftJson.decode("garbage"))
        assertEquals(NewSessionDraft(), NewSessionDraftJson.decode("[]"))
        assertEquals(NewSessionDraft(), NewSessionDraftJson.decode("42"))
        assertEquals(NewSessionDraft(), NewSessionDraftJson.decode(null))
        assertEquals(NewSessionDraft(), NewSessionDraftJson.decode(""))
    }

    @Test
    fun decodeWrongTypeWorktreeDoesNotCrash() {
        // `optBoolean` falls back to `false` instead of throwing.
        assertEquals(NewSessionDraft(), NewSessionDraftJson.decode("{\"worktree\":\"yes\"}"))
    }

    @Test
    fun decodeWrongTypeStringFieldResets() {
        assertEquals(NewSessionDraft(), NewSessionDraftJson.decode("{\"projectId\":5}"))
        assertEquals(NewSessionDraft(), NewSessionDraftJson.decode("{\"harness\":[\"claude-code\"]}"))
    }

    @Test
    fun strippingRemovesForgottenIdsAndTheProjectsBranch() {
        val d = NewSessionDraft(projectId = "A", hostId = "H1", branch = "main", model = "m")
        val goneProject = d.stripping(setOf("A"))
        assertEquals(null, goneProject.projectId)
        assertEquals(null, goneProject.branch) // the branch belongs to the checkout
        assertEquals("H1", goneProject.hostId) // untouched
        assertEquals("m", goneProject.model) // untouched
        val goneHost = d.stripping(setOf("H1"))
        assertEquals("A", goneHost.projectId)
        assertEquals("main", goneHost.branch)
        assertEquals(null, goneHost.hostId)
        val goneBoth = d.stripping(setOf("A", "H1"))
        assertEquals(null, goneBoth.projectId)
        assertEquals(null, goneBoth.branch)
        assertEquals(null, goneBoth.hostId)
        assertEquals(d, d.stripping(emptySet()))
        // A forgotten host does not clip the project's branch (iOS n/a; the rule is per-field).
        val onlyHost = d.stripping(setOf("H1"))
        assertEquals("main", onlyHost.branch)
    }
}
