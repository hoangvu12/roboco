package sh.roboco.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.composer.ChipIcon
import sh.roboco.android.composer.ChipTint
import sh.roboco.android.composer.ComposerChip
import uniffi.roboco_core.ContextUsage
import uniffi.roboco_core.PullRequestState

/**
 * The chip derivation (android-ui-parity #78): every row of the ticket's
 * two worked-example tables (context usage and rows), the fixed order
 * (model, effort, pr **or** branch, context), the PR-replaces-branch
 * rule, the `claude-code` default mark, the empty-harness rule and the
 * filler's output feeding [SessionChrome.chips].
 *
 * Plain JUnit 4, no Robolectric: `sessionChips` is pure and the injected
 * `reasoningLabel` is a Kotlin lambda (the FFI free function is exercised
 * under Robolectric in [SessionComposerBindingTest]).
 */
class SessionChipsTest {

    /** The injected label fn (the FFI table, for the tests that need real titles). */
    private fun label(level: String): String = when (level) {
        "minimal" -> "Minimal"
        "low" -> "Low"
        "medium" -> "Medium"
        "high" -> "High"
        "xhigh" -> "X-High"
        "max" -> "Max"
        "ultra" -> "Ultra"
        "ultracode" -> "Ultracode"
        "ultrathink" -> "Ultrathink"
        else -> level.replaceFirstChar(Char::uppercase)
    }

    private fun chips(
        source: ChipSource,
        reasoningLabel: (String) -> String = ::label,
    ): List<ComposerChip> = sessionChips(source, reasoningLabel)

    private fun contextSource(tokens: ULong?, window: ULong?): ChipSource = ChipSource(
        harness = null,
        harnessLabel = null,
        modelLabel = null,
        reasoning = null,
        branch = null,
        prNumber = null,
        prTint = null,
        tokens = tokens,
        window = window,
    )

    // ── the context-usage worked examples ─────────────────────────────

    @Test
    fun belowHalfShowsNoContextChip() {
        assertEquals(emptyList<ComposerChip>(), chips(contextSource(499uL, 1000uL)))
    }

    @Test
    fun atHalfShowsFiftyPercentWithTheCalmSymbol() {
        val chip = chips(contextSource(500uL, 1000uL)).single()
        assertEquals(SessionChipIds.Context, chip.id)
        assertEquals("50% context", chip.title)
        assertEquals("circle.lefthalf.filled", chip.symbol)
        assertEquals(null, chip.tint)
        assertEquals(null, chip.icon)
    }

    @Test
    fun halfRoundsAwayFromZeroNotToEven() {
        // 62.5 % must round up (Swift's .rounded()); kotlin.math.round
        // would give 62 (half to even).
        val chip = chips(contextSource(6250uL, 10000uL)).single()
        assertEquals("63% context", chip.title)
        assertEquals("circle.lefthalf.filled", chip.symbol)
        assertEquals(null, chip.tint)
    }

    @Test
    fun theRoundedTitleCanOvershootTheSymbolThreshold() {
        // 0.845 * 100 == 84.5 exactly in IEEE doubles: the title is 85%
        // while the symbol/tint test uses the UNROUNDED fraction.
        val chip = chips(contextSource(845uL, 1000uL)).single()
        assertEquals("85% context", chip.title)
        assertEquals("circle.lefthalf.filled", chip.symbol)
        assertEquals(null, chip.tint)
    }

    @Test
    fun exactlyTheWarningThresholdWarns() {
        val chip = chips(contextSource(17uL, 20uL)).single()
        assertEquals("85% context", chip.title)
        assertEquals("exclamationmark.circle", chip.symbol)
        assertEquals(ChipTint.Warning, chip.tint)
    }

    @Test
    fun justUnderTheWindowEndStillWarns() {
        val chip = chips(contextSource(999uL, 1000uL)).single()
        assertEquals("100% context", chip.title)
        assertEquals("exclamationmark.circle", chip.symbol)
        assertEquals(ChipTint.Warning, chip.tint)
    }

    @Test
    fun aboveTheWindowIsNotClamped() {
        val chip = chips(contextSource(12000uL, 10000uL)).single()
        assertEquals("120% context", chip.title)
        assertEquals("exclamationmark.circle", chip.symbol)
        assertEquals(ChipTint.Warning, chip.tint)
    }

    @Test
    fun aZeroWindowShowsNoChip() {
        assertEquals(emptyList<ComposerChip>(), chips(contextSource(5uL, 0uL)))
    }

    @Test
    fun missingTokensShowNoChip() {
        assertEquals(emptyList<ComposerChip>(), chips(contextSource(null, 1000uL)))
    }

    // ── the row worked examples ───────────────────────────────────────

    /** The iOS lab fixture (SessionSource.swift:108-112). */
    private val labRow = ChipSource(
        harness = "claude-code",
        harnessLabel = "Claude Code",
        modelLabel = "Opus 4.5",
        reasoning = "high",
        branch = "ios-rewrite",
        prNumber = null,
        prTint = null,
        tokens = null,
        window = null,
    )

    @Test
    fun theIosLabRowIsModelEffortBranch() {
        assertEquals(
            listOf(
                ComposerChip(SessionChipIds.Model, "Opus 4.5", icon = ChipIcon.Brand("claude-code")),
                ComposerChip(SessionChipIds.Effort, "High", symbol = "gauge.with.dots.needle.67percent"),
                ComposerChip(SessionChipIds.Branch, "ios-rewrite", icon = ChipIcon.Branch),
            ),
            chips(labRow),
        )
    }

    @Test
    fun aNullHarnessUsesTheDefaultMark() {
        val out = chips(labRow.copy(harness = null))
        assertEquals(ChipIcon.Brand("claude-code"), out[0].icon)
    }

    @Test
    fun aNullHarnessWithNoModelLabelStillShowsTheDefaultMark() {
        // The ticket's row: harness=null, modelLabel=null,
        // harnessLabel="Codex" → model "Codex" over Brand("claude-code").
        val out = chips(labRow.copy(harness = null, modelLabel = null))
        assertEquals("Codex", out[0].title)
        assertEquals(ChipIcon.Brand("claude-code"), out[0].icon)
    }

    @Test
    fun anEmptyHarnessHasNoIcon() {
        val out = chips(labRow.copy(harness = ""))
        assertEquals("model title falls back to harnessLabel", "Opus 4.5", out[0].title)
        assertEquals(null, out[0].icon)
    }

    @Test
    fun aMissingModelFallsBackToTheHarnessLabel() {
        val out = chips(labRow.copy(modelLabel = null))
        assertEquals("Claude Code", out[0].title)
        assertEquals(ChipIcon.Brand("claude-code"), out[0].icon)
    }

    @Test
    fun neitherModelNorHarnessLabelMeansNoModelChip() {
        val out = chips(labRow.copy(modelLabel = null, harnessLabel = null))
        assertEquals(listOf("effort", "branch"), out.map { it.id })
    }

    @Test
    fun anEmptyReasoningHasNoEffortChip() {
        val out = chips(labRow.copy(reasoning = ""))
        assertEquals(listOf("model", "branch"), out.map { it.id })
    }

    @Test
    fun xhighLabelsAsXHigh() {
        val out = chips(labRow.copy(reasoning = "xhigh"))
        assertEquals("X-High", out[1].title)
    }

    @Test
    fun aPrReplacesTheBranchChipEvenWhenBranchIsSet() {
        val out = chips(
            labRow.copy(
                branch = "feat/x",
                prNumber = 1234uL,
                prTint = ChipTint.PrMerged,
            ),
        )
        assertEquals(listOf("model", "effort", "pr"), out.map { it.id })
        assertEquals("1234", out[2].title)
        assertEquals(ChipTint.PrMerged, out[2].tint)
        assertEquals(ChipIcon.PullRequest, out[2].icon)
        assertEquals(null, out[2].symbol)
    }

    @Test
    fun everyPrStateMapsToItsTint() {
        assertEquals(ChipTint.PrOpen, prTintFor(PullRequestState.OPEN))
        assertEquals(ChipTint.PrMerged, prTintFor(PullRequestState.MERGED))
        assertEquals(ChipTint.PrClosed, prTintFor(PullRequestState.CLOSED))
    }

    @Test
    fun anEmptyBranchAloneMeansNoBranchChip() {
        val out = chips(labRow.copy(reasoning = null, branch = ""))
        assertEquals(listOf("model"), out.map { it.id })
    }

    @Test
    fun aNullRowStillShowsTheContextChip() {
        val out = chips(contextSource(620uL, 1000uL))
        assertEquals(listOf(ComposerChip(SessionChipIds.Context, "62% context", symbol = "circle.lefthalf.filled")), out)
    }

    @Test
    fun theOrderIsAlwaysModelEffortPrOrBranchContext() {
        val out = chips(
            labRow.copy(
                prNumber = 1234uL,
                prTint = ChipTint.PrOpen,
                tokens = 620uL,
                window = 1000uL,
            ),
        )
        assertEquals(listOf("model", "effort", "pr", "context"), out.map { it.id })
    }

    // ── the filler ────────────────────────────────────────────────────

    @Test
    fun theFillerFeedsChromeChipsFromTheRowAndComposer() {
        val filler = SessionChipsFiller(::label)
        val input = ChromeInput(
            chatId = "chat-a",
            composer = composerState(contextUsage = ContextUsage(tokens = 620uL, window = 1000uL)),
            row = sessionRow(
                harness = "claude-code",
                harnessLabel = "Claude Code",
                modelLabel = "Opus 4.5",
                reasoning = "high",
                branch = "ios-rewrite",
            ),
            engine = null,
        )
        val filled = filler.fill(SessionChrome(), input)
        assertEquals(
            listOf(
                ComposerChip(SessionChipIds.Model, "Opus 4.5", icon = ChipIcon.Brand("claude-code")),
                ComposerChip(SessionChipIds.Effort, "High", symbol = "gauge.with.dots.needle.67percent"),
                ComposerChip(SessionChipIds.Branch, "ios-rewrite", icon = ChipIcon.Branch),
                ComposerChip(SessionChipIds.Context, "62% context", symbol = "circle.lefthalf.filled"),
            ),
            filled.chips,
        )
    }

    @Test
    fun twoRefreshesWithTheSameInputsProduceEqualChromes() {
        val filler = SessionChipsFiller(::label)
        val input = ChromeInput(
            chatId = "chat-a",
            composer = composerState(),
            row = sessionRow(harness = "claude-code", reasoning = "high", branch = "main"),
            engine = null,
        )
        val once = filler.fill(SessionChrome(), input)
        val twice = filler.fill(SessionChrome(), input)
        assertEquals(once, twice)
        assertTrue("the StateFlow drops the equal second value", once == twice)
    }

    @Test
    fun theFillerLeavesEveryOtherFieldAlone() {
        val filler = SessionChipsFiller(::label)
        val base = SessionChrome(title = "T", running = true)
        val out = filler.fill(base, ChromeInput(chatId = "chat-a", composer = composerState(), row = null, engine = null))
        assertEquals(base.copy(chips = emptyList()), out)
    }

    // ── helpers ───────────────────────────────────────────────────────

    private fun prTintFor(state: PullRequestState): ChipTint? =
        ChipSource.from(
            sessionRow(pullRequest = pullRequest(1234, state)),
            composerState(),
        ).prTint
}
