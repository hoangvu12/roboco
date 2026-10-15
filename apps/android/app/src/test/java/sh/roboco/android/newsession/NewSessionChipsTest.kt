package sh.roboco.android.newsession

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.composer.ChipIcon
import sh.roboco.android.composer.ChipMenuProvider
import sh.roboco.android.composer.ChipTint
import sh.roboco.android.composer.ComposerChip

/**
 * [newSessionChips] + [String.swiftCapitalized] + [newSessionChipMenus]
 * (android-ui-parity #109) — the chip set rules of the ticket's acceptance
 * table, on the plain JVM: the pure function over ticket 92's records
 * with a **fake [ChipLabels]** that records every call, so the rows also
 * pin which clause of the model-title chain ran (no native library, no
 * Compose UI — the chip records are plain data).
 *
 * The visual row (capsule sizes, tone washes, fades) is 59's; the binding
 * ([NewSessionChipsEffect]) is the Robolectric test in
 * [NewSessionChipsScreenshotTest].
 */
class NewSessionChipsTest {

    // ── fixtures (the ticket's acceptance constants) ───────────────────

    /** `catalog.rs` FULL_LADDER — Fable 5.1's 7 levels. */
    private val ladder = listOf("low", "medium", "high", "xhigh", "max", "ultracode", "ultrathink")

    private val c1 = ModelChoice("claude-code", "Claude Code", "claude-fable-5-1", "Fable 5.1", ladder)

    private val c2 = ModelChoice("claude-code", "Claude Code", "claude-haiku-4-5", "Haiku 4.5", emptyList())

    private val x1 = ModelChoice("codex", "Codex", "gpt-5.5", "GPT-5.5", listOf("low", "medium", "high", "xhigh"))

    /** The ticket's fixture A project (`P`). */
    private val p = ProjectOption(
        id = "p1",
        name = "roboco",
        path = "/srv/roboco",
        device = "d1",
        deviceName = "Workstation",
        online = true,
        git = true,
        groupKey = "repo:r",
        groupName = "roboco",
        colorIndex = 2,
    )

    /**
     * The fake [ChipLabels]: every call is recorded as
     * `method(args)` so the rows assert which clauses ran, and each
     * result is configurable (the last row fakes an empty fallback
     * catalog).
     */
    private class RecordingLabels(
        private val modelLabelResult: String = "FFI-model",
        private val fallbackResult: String? = "FFI-fallback",
        private val harnessResult: String = "FFI-harness",
    ) : ChipLabels {
        val calls = mutableListOf<String>()

        override fun modelLabel(harness: String, model: String): String {
            calls += "modelLabel($harness, $model)"
            return modelLabelResult
        }

        override fun firstFallbackModelLabel(harness: String): String? {
            calls += "firstFallbackModelLabel($harness)"
            return fallbackResult
        }

        override fun harnessLabel(harness: String): String {
            calls += "harnessLabel($harness)"
            return harnessResult
        }
    }

    /** The row under test: the pure function over the fake labels. */
    private fun chips(
        draft: NewSessionDraft,
        project: ProjectOption? = p,
        hosts: List<HostOption> = emptyList(),
        models: List<ModelChoice> = listOf(c1, c2),
        labels: ChipLabels = RecordingLabels(),
    ): List<ComposerChip> = newSessionChips(draft, project, hosts, models, labels)

    // ── the five-chip row ──────────────────────────────────────────────

    @Test
    fun theFullGitProjectRow() {
        val out = chips(NewSessionDraft(), project = p, models = listOf(c1, c2, x1))
        assertEquals(listOf("project", "host", "branch", "model", "effort"), out.map { it.id })
        assertEquals(
            listOf("roboco", "Workstation", "Current branch", "Fable 5.1", "Xhigh"),
            out.map { it.title },
        )
        assertEquals(
            listOf(ChipIcon.Project("roboco", 2), null, ChipIcon.Branch, ChipIcon.Brand("claude-code"), null),
            out.map { it.icon },
        )
        assertEquals(
            listOf(null, "desktopcomputer", null, null, "gauge.with.dots.needle.67percent"),
            out.map { it.symbol },
        )
        assertEquals(listOf<ChipTint?>(null, null, null, null, null), out.map { it.tint })
    }

    @Test
    fun theProjectChipUsesTheGroupNameNotTheCheckoutName() {
        val clone = p.copy(name = "roboco-clone")
        val out = chips(NewSessionDraft(projectId = "p1"), project = clone)
        assertEquals("roboco", out[0].title)
        assertEquals(ChipIcon.Project("roboco", 2), out[0].icon)
    }

    @Test
    fun theBranchTitleRules() {
        assertEquals("main", chips(NewSessionDraft(branch = "main"))[2].title)
        assertEquals(
            "New worktree",
            chips(NewSessionDraft(branch = "main", worktree = true))[2].title,
        )
        val nonGit = chips(NewSessionDraft(branch = "main"), project = p.copy(git = false))
        assertFalse("git = false drops the branch chip", nonGit.any { it.id == "branch" })
    }

    @Test
    fun anOfflineProjectTintsOnlyTheHostChip() {
        val out = chips(NewSessionDraft(), project = p.copy(online = false))
        assertEquals(ChipTint.Warning, out[1].tint)
        assertEquals(listOf<ChipTint?>(null, ChipTint.Warning, null, null, null), out.map { it.tint })
    }

    // ── the projectless pair ───────────────────────────────────────────

    @Test
    fun withoutAProjectTheHostChipFollowsTheDraftHost() {
        val h1 = HostOption("h1", "Laptop", online = true)
        val h2 = HostOption("h2", "Old", online = false)
        val hosts = listOf(h1, h2)

        val online = chips(NewSessionDraft(hostId = "h1"), project = null, hosts = hosts)
        assertEquals("No project", online[0].title)
        assertEquals("tray", online[0].symbol)
        assertEquals("Laptop", online[1].title)
        assertNull(online[1].tint)
        // The pair sits before the model chip, and there is never a branch.
        assertEquals("model", online[2].id)
        assertFalse(online.any { it.id == "branch" })

        assertEquals("Old", chips(NewSessionDraft(hostId = "h2"), project = null, hosts = hosts)[1].title)
        assertEquals(
            ChipTint.Warning,
            chips(NewSessionDraft(hostId = "h2"), project = null, hosts = hosts)[1].tint,
        )
        for (id in listOf(null, "gone")) {
            val out = chips(NewSessionDraft(hostId = id), project = null, hosts = hosts)
            assertEquals("Choose host", out[1].title)
            assertNull("no tint for a missing host", out[1].tint)
        }
    }

    @Test
    fun aProjectIdTheOptionsHaveNotLoadedFallsBackToTheNoProjectPair() {
        val out = chips(NewSessionDraft(projectId = "p1"), project = null)
        assertEquals(listOf("project", "host", "model", "effort"), out.map { it.id })
        assertEquals("No project", out[0].title)
    }

    // ── the model chip ─────────────────────────────────────────────────

    @Test
    fun theModelChipNamesThePickedModelAndDropsEffortForAnEmptyLadder() {
        val out = chips(NewSessionDraft(harness = "claude-code", model = "claude-haiku-4-5"))
        assertEquals("Haiku 4.5", out.first { it.id == "model" }.title)
        assertFalse(out.any { it.id == "effort" })
    }

    @Test
    fun theEffortChipShowsTheDraftEffortSwiftCapitalized() {
        val out = chips(
            NewSessionDraft(projectId = "p1", harness = "codex", model = "gpt-5.5", effort = "low"),
            models = listOf(x1),
        )
        assertEquals("GPT-5.5", out.first { it.id == "model" }.title)
        assertEquals("Low", out.first { it.id == "effort" }.title)
    }

    @Test
    fun anUnlistedModelIdStillNamesTheFirstListedModelOfTheHarness() {
        // The host lists X1 for codex but not gpt-9: clause 1 wins over the
        // draft's id, and the label chain's modelLabel never runs.
        val labels = RecordingLabels()
        val out = chips(
            NewSessionDraft(harness = "codex", model = "gpt-9"),
            models = listOf(c1, x1),
            labels = labels,
        )
        assertEquals("GPT-5.5", out.first { it.id == "model" }.title)
        // 4 levels → index 4 / 2 = 2 → "high" → "High".
        assertEquals("High", out.first { it.id == "effort" }.title)
        assertTrue("modelLabel must not run: ${labels.calls}", labels.calls.isEmpty())
    }

    @Test
    fun aHarnessWithNoListedModelFallsBackToTheCatalogLabel() {
        // No codex row at all: draft.model is set, so modelLabel runs.
        val labels = RecordingLabels(modelLabelResult = "GPT-9 Turbo")
        val out = chips(NewSessionDraft(harness = "codex", model = "gpt-9"), models = listOf(c1), labels = labels)
        assertEquals("GPT-9 Turbo", out.first { it.id == "model" }.title)
        assertEquals(listOf("modelLabel(codex, gpt-9)"), labels.calls)
        assertFalse(out.any { it.id == "effort" })
    }

    @Test
    fun withoutADraftModelTheFallbackCatalogNamesTheDefault() {
        val labels = RecordingLabels()
        val out = chips(NewSessionDraft(harness = "codex"), models = listOf(c1), labels = labels)
        assertEquals("FFI-fallback", out.first { it.id == "model" }.title)
        assertEquals(listOf("firstFallbackModelLabel(codex)"), labels.calls)
    }

    @Test
    fun anEmptyFallbackCatalogFallsThroughToTheHarnessLabel() {
        val labels = RecordingLabels(fallbackResult = null, harnessResult = "Codex")
        val out = chips(NewSessionDraft(harness = "codex"), models = listOf(c1), labels = labels)
        assertEquals("Codex", out.first { it.id == "model" }.title)
        assertEquals(listOf("firstFallbackModelLabel(codex)", "harnessLabel(codex)"), labels.calls)
    }

    @Test
    fun anEmptyModelListUsesTheFallbackLabelNeverTheHarnessName() {
        val labels = RecordingLabels()
        val out = chips(NewSessionDraft(harness = "claude-code"), models = emptyList(), labels = labels)
        assertEquals("FFI-fallback", out.first { it.id == "model" }.title)
        assertEquals(listOf("firstFallbackModelLabel(claude-code)"), labels.calls)
    }

    @Test
    fun anEmptyHarnessHasNoBrandIcon() {
        val out = chips(NewSessionDraft(harness = ""))
        assertNull(out.first { it.id == "model" }.icon)
    }

    @Test
    fun anEffortOutsideTheLadderIsStillShown() {
        val out = chips(NewSessionDraft(projectId = "p1", effort = "turbo"))
        assertEquals("Turbo", out.first { it.id == "effort" }.title)
    }

    // ── swiftCapitalized (the ticket's table, every row) ───────────────

    @Test
    fun swiftCapitalizedFollowsCFStringCapitalize() {
        assertEquals("Low", "low".swiftCapitalized())
        assertEquals("Medium", "medium".swiftCapitalized())
        assertEquals("Xhigh", "xhigh".swiftCapitalized())
        assertEquals("Ultrathink", "ultrathink".swiftCapitalized())
        assertEquals("High", "HIGH".swiftCapitalized())
        assertEquals("Two Words", "two words".swiftCapitalized())
        assertEquals("X-High", "x-high".swiftCapitalized())
        assertEquals("1St", "1st".swiftCapitalized())
        assertEquals("Don't", "don't".swiftCapitalized())
        assertEquals("", "".swiftCapitalized())
    }

    // ── newSessionChipMenus ────────────────────────────────────────────

    @Test
    fun chipMenusMergeKeepsTheFiveIdsInOrderAndDropsExtras() {
        val a: ChipMenuProvider = { null }
        val b: ChipMenuProvider = { null }
        val c: ChipMenuProvider = { null }
        val d: ChipMenuProvider = { null }
        val e: ChipMenuProvider = { null }
        val extra: ChipMenuProvider = { null }
        val overridden: ChipMenuProvider = { null }
        val out = newSessionChipMenus(
            mapOf("project" to a, "host" to b, "extra" to extra),
            mapOf("branch" to c, "model" to d, "effort" to e, "project" to overridden),
        )
        assertEquals(listOf("project", "host", "branch", "model", "effort"), out.keys.toList())
        assertSame("a later part overrides an earlier one", overridden, out.getValue("project"))
        assertSame(b, out.getValue("host"))
        assertSame(c, out.getValue("branch"))
        assertSame(d, out.getValue("model"))
        assertSame(e, out.getValue("effort"))
        assertFalse(out.containsKey("extra"))
    }

    @Test
    fun chipMenusWithoutPartsMapEveryIdToNoMenu() {
        val out = newSessionChipMenus()
        assertEquals(NewSessionChipIds.All, out.keys.toList())
        for (id in NewSessionChipIds.All) {
            assertNull("no menu for $id", out.getValue(id).invoke())
        }
    }
}
