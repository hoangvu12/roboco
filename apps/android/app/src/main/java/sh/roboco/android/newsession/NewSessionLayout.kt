package sh.roboco.android.newsession

import kotlin.math.max
import kotlin.math.min

/**
 * The New Session canvas's pure geometry (android-ui-parity #93) — every
 * number of `ios:Session/NewSessionViewController.swift:101-237` as dp
 * floats, no Compose runtime: [NewSessionCanvas]'s `Layout` converts with
 * the density, and [NewSessionLayoutTest] pins the arithmetic directly.
 *
 * The page's own coordinate system (dp): `W` = page width, `Hc` = the
 * height the page is laid out in (31's sheet content slot, shrunk by the
 * IME), `Hp = Hc + imeAbove` = the page height **without** the IME (the
 * wallpaper is sized from `Hp` so the artwork never re-crops when the
 * keyboard opens, `:224`).
 */
internal object NewSessionLayout {

    /** 17's sheet header row — `GlassSheetDefaults.HeaderHeight` (4 + 44 + 4). */
    val SheetNavBarHeight = 52f

    /** 25's `NavRowHeight` — the tablet detail column's plain row. */
    val EmbeddedNavRowHeight = 44f

    /** `mark.image = BrandMarks.image(for:side: 34)` (`:120`). */
    const val MarkSide = 34f

    /** `heroStack.spacing = 14` (`:130`). */
    const val HeroSpacing = 14f

    /** `hero.font = Fonts.ui(.sansSemibold, 22)` (`:124`). */
    const val HeadlineSize = 22f

    /** `hero.numberOfLines = 2` (`:123`). */
    const val HeadlineMaxLines = 2

    /** `heroStack.leading ≥ safe.leading + 24` (`:152`); symmetric on Android. */
    const val HeroSideMargin = 24f

    /** `free.bottomAnchor = composer.top − 20` (`:164`). */
    const val HeroComposerGap = 20f

    /** `width = safeWidth − 24` (`:160`, 12 each side). */
    const val ComposerSideInsets = 24f

    /** `composer.width ≤ 768` (`:172`). */
    const val ComposerMaxWidth = 768f

    /** `composer.bottom = keyboardLayoutGuide.top − 8` (`:175`). */
    const val ComposerBottomGap = 8f

    /** 86's card bottom = anchor top − 8 (listed for reference; 86 owns it). */
    const val SuggestionsGap = 8f

    /** `min(0.72 × Hp, 760)` (`:224`). */
    const val WallpaperFraction = 0.72f

    /** The wallpaper hero's height cap (`:224`). */
    const val WallpaperMaxHeight = 760f

    /** `width = min(safeWidth − 24, 768)` (`:160-174`). */
    fun composerWidth(safeWidth: Float): Float =
        min(safeWidth - ComposerSideInsets, ComposerMaxWidth).coerceAtLeast(0f)

    /** `composer.centerX == safe.centerX` (`:171`). */
    fun composerX(safeLeft: Float, safeWidth: Float, width: Float): Float =
        safeLeft + (safeWidth - width) / 2f

    /** `composer.bottom = Hc − bottomInset − 8` (`:175`). */
    fun composerBottom(contentHeight: Float, bottomInset: Float): Float =
        contentHeight - bottomInset - ComposerBottomGap

    /**
     * The headline's max width: iOS constrains only the leading edge
     * (`≥ safe.leading + 24`, `:152`); centring makes the right margin the
     * same, so Android uses `max(safeL, safeR)` on both sides.
     */
    fun headlineMaxWidth(width: Float, safeLeft: Float, safeRight: Float): Float =
        (width - 2f * (HeroSideMargin + max(safeLeft, safeRight))).coerceAtLeast(0f)

    /**
     * The hero's top: centred in the free space between the nav bar and
     * the composer; when it does not fit (landscape + keyboard), **bottom
     * wins** — the hero ends `HeroComposerGap` above the composer and may
     * slide up under the nav bar (the chosen break of iOS's three
     * conflicting required constraints).
     */
    fun heroTop(freeTop: Float, freeBottom: Float, heroHeight: Float): Float {
        val free = freeBottom - freeTop
        return if (heroHeight <= free) freeTop + (free - heroHeight) / 2f else freeBottom - heroHeight
    }

    /** The wallpaper frame's height: `min(0.72 × Hp, 760)` (`:224`). */
    fun wallpaperHeight(pageHeight: Float): Float =
        min(pageHeight * WallpaperFraction, WallpaperMaxHeight)
}
