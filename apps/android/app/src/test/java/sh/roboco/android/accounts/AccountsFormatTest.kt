package sh.roboco.android.accounts

import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.roboco_core.AgentAccount
import uniffi.roboco_core.AgentAccountWarning
import uniffi.roboco_core.AgentAccountsSnapshot
import uniffi.roboco_core.AgentUsageWindow
import uniffi.roboco_core.CoreException

/**
 * The accounts derivations (android-next#05) on the plain JVM: the usage
 * thresholds, the percent, the provider order and grouping, the row copy
 * (title / detail / top window / missing usage), the reset and freshness
 * wording, the optimistic switch and the error table — the Android port
 * of PR #578's `ProviderUsage` with the web's copy of the same rules.
 * The FFI records construct without JNA; the label and clock seams are
 * injected.
 */
class AccountsFormatTest {

    private val now = 1_760_000_000_000L // 2025-10-09 08:53:20 UTC (Thursday)
    private val locale = Locale.US
    private val zone = ZoneId.of("UTC")

    // ------------------------------------------------------- the fixtures

    private fun account(
        id: String,
        harness: String = "claude-code",
        email: String? = "$id@example.com",
        active: Boolean = false,
        planLabel: String? = "Max 20\u00d7",
        organization: String? = null,
        windows: List<AgentUsageWindow> = emptyList(),
        fetchedAt: Long? = null,
        usageError: String? = null,
        apiKey: Boolean = false,
        switchable: Boolean = true,
        provider: String? = null,
    ): AgentAccount = AgentAccount(
        id = id,
        harness = harness,
        email = email,
        planLabel = planLabel,
        active = active,
        usageWindows = windows,
        usageFetchedAtMs = fetchedAt,
        usageError = usageError,
        displayName = null,
        organization = organization,
        apiKey = apiKey,
        switchable = switchable,
        provider = provider,
    )

    private fun window(label: String, fraction: Float, resetsAtMs: Long? = null) =
        AgentUsageWindow(label = label, usedFraction = fraction, resetsAtMs = resetsAtMs)

    // -------------------------------------------------- usage levels

    @Test
    fun usageLevelsMatchTheDesktopThresholds() {
        assertEquals(UsageLevel.Normal, usageLevelOf(0.79f))
        assertEquals(UsageLevel.Normal, usageLevelOf(0.799f))
        assertEquals(UsageLevel.Warning, usageLevelOf(0.80f))
        assertEquals(UsageLevel.Warning, usageLevelOf(0.94f))
        assertEquals(UsageLevel.Critical, usageLevelOf(0.95f))
        assertEquals(UsageLevel.Critical, usageLevelOf(1f))
    }

    @Test
    fun percentRoundsHalfAwayFromZeroAndClamps() {
        assertEquals("42%", usagePercent(0.42f))
        assertEquals("88%", usagePercent(0.875f))
        assertEquals("100%", usagePercent(1.2f))
        assertEquals("0%", usagePercent(-0.1f))
    }

    // -------------------------------------------------- order + grouping

    @Test
    fun groupsFollowTheDesktopOrderActiveFirstThenTitle() {
        val snapshot = AgentAccountsSnapshot(
            accounts = listOf(
                account("b", harness = "codex", active = false, email = "bob@x.com"),
                account("z", harness = "opencode", active = true),
                account("a", harness = "codex", active = true, email = "alice@x.com"),
                account("c", harness = "unknown-agent", active = true),
                account("d", harness = "claude-code", active = false, email = "dana@x.com"),
            ),
            warnings = listOf(
                AgentAccountWarning(harness = "claude-code", message = "Keychain denied"),
            ),
        )
        val groups = providerGroups(snapshot)
        assertEquals(
            listOf("claude-code", "codex", "opencode", "unknown-agent"),
            groups.map { it.harness },
        )
        // Active first, then case-insensitive title.
        assertEquals(listOf("alice@x.com", "bob@x.com"), groups[1].accounts.map { it.email })
        assertEquals(
            listOf("Keychain denied"),
            groups.first { it.harness == "claude-code" }.warnings,
        )
    }

    @Test
    fun activeAndOtherLoginsOfTheSingleChoiceGroup() {
        val snapshot = AgentAccountsSnapshot(
            accounts = listOf(
                account("live", active = true, provider = "openai"),
                account("saved", active = false, provider = "openai"),
                account("other-provider", active = false, provider = "anthropic"),
                account("codex-login", harness = "codex", active = true),
            ),
            warnings = emptyList(),
        )
        val active = activeAccountOf(snapshot, "claude-code")!!
        assertEquals("live", active.id)
        assertEquals(
            listOf("saved"),
            otherAccountsOf(snapshot, active).map { it.id },
        )
        assertEquals("codex-login", activeAccountOf(snapshot, "codex")!!.id)
        assertNull(activeAccountOf(snapshot, "pi"))
    }

    // -------------------------------------------------- the row copy

    @Test
    fun titleDetailAndTopWindow() {
        assertEquals("alice@example.com", accountTitle(account("alice")))
        assertEquals("Alice", accountTitle(account("alice", email = null).copy(displayName = "Alice")))
        assertEquals("Unknown account", accountTitle(account("x", email = null)))

        assertEquals("Max 20\u00d7 \u00b7 Test Org", accountDetail(account("a", organization = "Test Org")))
        // The organization that repeats the email is dropped.
        assertEquals("Max 20\u00d7", accountDetail(account("alice", organization = "alice@example.com")))
        assertNull(accountDetail(account("a", planLabel = null)))

        val a = account(
            "a",
            windows = listOf(window("Session", 0.42f), window("Week", 0.8f)),
        )
        assertEquals("Week", topWindowOf(a)!!.label)
        assertNull(topWindowOf(account("a")))
    }

    @Test
    fun missingUsageNotes() {
        assertEquals("Rate limited", missingUsage(account("a", usageError = "Rate limited")))
        assertEquals(
            "API keys have no plan usage",
            missingUsage(account("a", apiKey = true)),
        )
        assertEquals("Usage unavailable", missingUsage(account("a")))
    }

    // -------------------------------------------------- reset wording

    @Test
    fun resetTextIsWrittenOut() {
        fun reset(at: Long?) = resetText(at, now, locale, zone)
        assertNull(reset(null))
        // ≤ 1 min left (past included).
        assertEquals("Resets now", reset(now))
        assertEquals("Resets now", reset(now - 60_000))
        // Under an hour: minutes only.
        assertEquals("Resets in 13m", reset(now + 13 * 60_000))
        // Under a day: hours and minutes, at most two units.
        assertEquals("Resets in 2h 13m", reset(now + (2 * 3_600 + 13 * 60) * 1_000))
        assertEquals("Resets in 5h", reset(now + 5 * 3_600_000))
        // Under a week: weekday and clock time.
        assertEquals("Resets Sun 8:53 AM", reset(now + 3 * 86_400_000))
        // Beyond: month and day.
        assertEquals("Resets Oct 19", reset(now + 10 * 86_400_000))
    }

    @Test
    fun updatedTextReadsTheNewestProbe() {
        assertNull(updatedText(AgentAccountsSnapshot(accounts = emptyList(), warnings = emptyList()), now))
        val snapshot = AgentAccountsSnapshot(
            accounts = listOf(
                account("a", fetchedAt = now - 4 * 60_000),
                account("b", fetchedAt = now - 2 * 60_000),
            ),
            warnings = emptyList(),
        )
        val newest = now - 2 * 60_000
        assertEquals("Usage updated 2 minutes ago", updatedText(snapshot, now))
        assertEquals("Usage updated just now", updatedText(snapshot, newest + 30_000))
        assertEquals(
            "Usage updated 3 hours ago",
            updatedText(snapshot, newest + 3 * 3_600_000 + 1_000),
        )
    }

    // -------------------------------------------------- the switch

    @Test
    fun markSwitchedFlipsOnlyTheChosenGroup() {
        val snapshot = AgentAccountsSnapshot(
            accounts = listOf(
                account("live", active = true, provider = "openai"),
                account("saved", active = false, provider = "openai"),
                account("other", active = false, provider = "anthropic"),
                account("codex-live", harness = "codex", active = true),
            ),
            warnings = emptyList(),
        )
        val switched = markSwitched(snapshot, snapshot.accounts[1])
        assertEquals(
            listOf(false, true, false, true),
            switched.accounts.map { it.active },
        )
    }

    // -------------------------------------------------- the error table

    @Test
    fun describeAccountsErrorMapsTheThreeCases() {
        assertEquals(
            "Studio is offline. Accounts show up when it's back.",
            describeAccountsError(CoreException.HostUnavailable("down"), "Studio"),
        )
        assertEquals(
            "Studio needs a newer Roboco to show accounts here.",
            describeAccountsError(CoreException.Unsupported("unknown method"), "Studio"),
        )
        assertEquals(
            "Couldn't load accounts from Studio.",
            describeAccountsError(CoreException.HostException("boom"), "Studio"),
        )
        assertEquals(isUnsupportedEngine(CoreException.Unsupported("x")), true)
        assertEquals(isUnsupportedEngine(CoreException.HostUnavailable("x")), false)
    }

    // -------------------------------------------------- the primary device

    @Test
    fun primaryDevicePrefersOnlineThenSessions() {
        fun device(id: String, online: Boolean, sessions: UInt = 0u) =
            uniffi.roboco_core.DeviceView(
                id = id,
                engineId = id,
                name = id,
                platform = "windows",
                online = online,
                lastSeenMs = null,
                version = null,
                capabilities = emptyList(),
                isExecutionHost = true,
                isSelf = false,
                sessionCount = sessions,
            )
        assertEquals("online-many", primaryDevice(listOf(device("offline", false, 9u), device("online-many", true, 3u)))!!.id)
        assertEquals("offline", primaryDevice(listOf(device("offline", false, 9u)))!!.id)
        assertNull(primaryDevice(emptyList()))
    }
}
