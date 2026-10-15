package sh.roboco.android.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.roboco_core.AgentAccount
import uniffi.roboco_core.AgentAccountsSnapshot
import uniffi.roboco_core.AgentUsageWindow

/**
 * The Settings "Agent Accounts" section's pure derivation
 * (android-next#05): [agentAccountsRows] — the no-computer / loading /
 * offline / empty meta rows, the per-harness rows with their subtitles
 * and meters (desktop order), and the `null` that hides the section for
 * an engine too old for the accounts RPCs. Plain JUnit: the label seam
 * is injected, the records construct without JNA.
 */
class AgentAccountsSectionTest {

    private val labels = mapOf(
        "claude-code" to "Claude Code",
        "codex" to "Codex",
    )
    private val label: (String) -> String = { labels[it] ?: it }

    private fun account(
        id: String,
        harness: String = "claude-code",
        email: String = "$id@example.com",
        active: Boolean = false,
        windows: List<AgentUsageWindow> = emptyList(),
        apiKey: Boolean = false,
    ): AgentAccount = AgentAccount(
        id = id,
        harness = harness,
        email = email,
        planLabel = "Max 20\u00d7",
        active = active,
        usageWindows = windows,
        usageFetchedAtMs = null,
        usageError = null,
        displayName = null,
        organization = null,
        apiKey = apiKey,
        switchable = true,
        provider = null,
    )

    private fun window(label: String, fraction: Float) =
        AgentUsageWindow(label = label, usedFraction = fraction, resetsAtMs = null)

    private fun rows(
        deviceId: String? = "device-1",
        deviceName: String = "Studio",
        snapshot: AgentAccountsSnapshot? = null,
        loadMessage: String? = null,
        unsupported: Boolean = false,
    ) = agentAccountsRows(deviceId, deviceName, snapshot, loadMessage, unsupported, label)

    // ------------------------------------------------------- meta rows

    @Test
    fun noComputerShowsOneDisabledRow() {
        val rows = rows(deviceId = null)!!
        assertEquals(1, rows.size)
        assertEquals(AgentAccountsRowSpec.NoComputer, rows[0])
        assertEquals("agent-accounts:none", rows[0].id)
    }

    @Test
    fun loadingAndOfflineTakeOneRowEach() {
        assertEquals(
            listOf(AgentAccountsRowSpec.Loading),
            rows(snapshot = null, loadMessage = null)!!,
        )
        assertEquals(
            listOf(AgentAccountsRowSpec.Unavailable("Studio is offline. Accounts show up when it's back.")),
            rows(snapshot = null, loadMessage = "Studio is offline. Accounts show up when it's back.")!!,
        )
    }

    @Test
    fun noAccountsIsEmptyWithTheSignInSectionCopy() {
        val rows = rows(snapshot = AgentAccountsSnapshot(accounts = emptyList(), warnings = emptyList()))!!
        assertEquals(
            listOf(AgentAccountsRowSpec.Empty("Sign in to an agent from Roboco on Studio.")),
            rows,
        )
    }

    @Test
    fun anUnsupportedEngineHidesTheWholeSection() {
        assertNull(
            rows(
                snapshot = AgentAccountsSnapshot(accounts = listOf(account("a")), warnings = emptyList()),
                unsupported = true,
            ),
        )
    }

    // ------------------------------------------------------ harness rows

    @Test
    fun harnessRowsCarryTheLoginTheCountAndTheMeter() {
        val snapshot = AgentAccountsSnapshot(
            accounts = listOf(
                account(
                    "alice",
                    active = true,
                    windows = listOf(window("Session", 0.88f), window("Week", 0.2f)),
                ),
                account("bob"),
                account("dev", harness = "codex", active = true, apiKey = true),
            ),
            warnings = emptyList(),
        )
        val rows = rows(snapshot = snapshot)!!
        assertEquals(2, rows.size)
        val claude = rows[0] as AgentAccountsRowSpec.Harness
        assertEquals("Claude Code", claude.title)
        assertEquals("alice@example.com \u00b7 2 accounts", claude.subtitle)
        assertEquals("Session", claude.topWindow!!.label)
        assertEquals("agent-account:claude-code", claude.id)
        // An API key shows no window and no "Usage unavailable" note.
        val codex = rows[1] as AgentAccountsRowSpec.Harness
        assertEquals("dev@example.com", codex.subtitle)
        assertNull(codex.topWindow)
    }

    @Test
    fun aHarnessWithoutAnActiveLoginReadsNotSignedIn() {
        val snapshot = AgentAccountsSnapshot(
            accounts = listOf(account("saved")),
            warnings = emptyList(),
        )
        val rows = rows(snapshot = snapshot)!!
        // No active login: no usage note either (nothing is in use whose
        // usage could be missing).
        assertEquals("Not signed in", (rows[0] as AgentAccountsRowSpec.Harness).subtitle)
    }

    @Test
    fun harnessRowsFollowTheDesktopOrder() {
        val snapshot = AgentAccountsSnapshot(
            accounts = listOf(
                account("dev", harness = "codex", active = true),
                account("a", harness = "claude-code", active = true),
            ),
            warnings = emptyList(),
        )
        assertEquals(
            listOf("claude-code", "codex"),
            rows(snapshot = snapshot)!!.map { (it as AgentAccountsRowSpec.Harness).harness },
        )
    }
}
