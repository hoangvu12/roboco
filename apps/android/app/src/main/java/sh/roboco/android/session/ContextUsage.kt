package sh.roboco.android.session

import java.math.BigInteger
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt
import sh.roboco.android.composer.ChipTint
import sh.roboco.android.composer.ComposerChip
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuItem

/**
 * The composer's context usage (android-next #01): the always-visible
 * `context` chip and its tap → details card, the Android port of desktop
 * `crates/ui/src/context_usage.rs` (`details()` / `card()`). It goes past
 * iOS on purpose (user decision 2026-10-15), superseding parity ticket
 * 78's `fraction >= 0.5` rule.
 *
 * Pure (plain-JVM tested): the chip is built on the controller's IO lane
 * by [sessionChips], the card on main at tap time by `SessionChipMenus`,
 * both from `ComposerState.contextUsage`.
 */
sealed interface ContextReading {
    val tokens: ULong

    /** A reported window: the percentage, the remaining tokens. */
    data class Measured(override val tokens: ULong, val window: ULong) : ContextReading {
        /** Unclamped (`120% context` above the window). */
        val fraction: Double get() = tokens.toDouble() / window.toDouble()

        /** Rounded **half away from zero** ([roundToInt], never `kotlin.math.round`'s half to even). */
        val percent: Int get() = (fraction * 100).roundToInt()

        /** Never negative: an overflowing context has `0` left (desktop `saturating_sub`). */
        val remaining: ULong get() = if (tokens >= window) 0uL else window - tokens
    }

    /** The harness never reported a window: the bare token count. */
    data class TokensOnly(override val tokens: ULong) : ContextReading
}

/**
 * What the chip shows, or `null` for no chip:
 * - a window `> 0` with a token count → [ContextReading.Measured], at
 *   **any** fraction, 0 % included;
 * - no window (or `0`) but `tokens > 0` → [ContextReading.TokensOnly];
 * - no token count at all (no turn yet, or a window-only report), or a
 *   zero count with no window → no chip.
 */
fun contextReading(tokens: ULong?, window: ULong?): ContextReading? = when {
    tokens == null -> null
    window != null && window > 0uL -> ContextReading.Measured(tokens, window)
    tokens > 0uL -> ContextReading.TokensOnly(tokens)
    else -> null
}

/**
 * A token count with the [locale]'s grouping (`5,417` in en-US, `5.417`
 * in de-DE) — desktop's `with_separators`, made locale-aware. Formats the
 * full `u64` range (a `BigInteger`, never a `Long` that would wrap).
 */
fun groupedTokens(count: ULong, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getIntegerInstance(locale).format(BigInteger(count.toString()))

/** The context chip's calm glyph. */
const val CONTEXT_SYMBOL = "circle.lefthalf.filled"

/** The context chip's glyph from 85 % (the **unrounded** fraction). */
const val CONTEXT_WARNING_SYMBOL = "exclamationmark.circle"

/** The warning threshold (glyph + `warning` tint), unchanged from ticket 78. */
const val CONTEXT_WARNING_FRACTION = 0.85

/**
 * The `context` chip of [reading]: `NN% context` (warning glyph and tint
 * from 85 % of the unrounded fraction, so `845 / 1000` shows `85% context`
 * calm) or `N tokens` without a window. The content description spells
 * the numbers out for TalkBack.
 */
fun contextChip(reading: ContextReading, locale: Locale = Locale.getDefault()): ComposerChip = when (reading) {
    is ContextReading.Measured -> {
        val warn = reading.fraction >= CONTEXT_WARNING_FRACTION
        ComposerChip(
            id = SessionChipIds.Context,
            title = "${reading.percent}% context",
            symbol = if (warn) CONTEXT_WARNING_SYMBOL else CONTEXT_SYMBOL,
            tint = if (warn) ChipTint.Warning else null,
            contentDescription = "Context ${reading.percent} percent used, " +
                "${groupedTokens(reading.tokens, locale)} of ${groupedTokens(reading.window, locale)} tokens",
        )
    }
    is ContextReading.TokensOnly -> ComposerChip(
        id = SessionChipIds.Context,
        title = "${groupedTokens(reading.tokens, locale)} tokens",
        symbol = CONTEXT_SYMBOL,
        contentDescription = "Context ${groupedTokens(reading.tokens, locale)} tokens used",
    )
}

/** The details card's title (desktop `card()`'s heading). */
const val CONTEXT_CARD_TITLE = "Context window"

/**
 * The details card the `context` chip opens through ticket 15's glass
 * menu: read-only rows ([MenuItem.informational]) under the "Context
 * window" title, worded like desktop `details()`:
 * - measured: `5,417 / 1,048,576 tokens` (subtitle `1% used`), then
 *   `1,043,159 tokens remaining`;
 * - no window: `48,210 tokens used`, then `Context limit not reported`.
 *
 * Tapping a row does nothing (the panel swallows it); a tap outside closes.
 */
fun contextUsageCard(reading: ContextReading, locale: Locale = Locale.getDefault()): Menu = Menu(
    title = CONTEXT_CARD_TITLE,
    children = when (reading) {
        is ContextReading.Measured -> listOf(
            contextCardRow(
                "${groupedTokens(reading.tokens, locale)} / ${groupedTokens(reading.window, locale)} tokens",
                subtitle = "${reading.percent}% used",
                testTag = "context-card-usage",
            ),
            contextCardRow(
                "${groupedTokens(reading.remaining, locale)} tokens remaining",
                testTag = "context-card-remaining",
            ),
        )
        is ContextReading.TokensOnly -> listOf(
            contextCardRow("${groupedTokens(reading.tokens, locale)} tokens used", testTag = "context-card-usage"),
            contextCardRow("Context limit not reported", testTag = "context-card-remaining"),
        )
    },
)

private fun contextCardRow(title: String, subtitle: String? = null, testTag: String): MenuItem =
    MenuItem(title = title, subtitle = subtitle, testTag = testTag, informational = true) {}
