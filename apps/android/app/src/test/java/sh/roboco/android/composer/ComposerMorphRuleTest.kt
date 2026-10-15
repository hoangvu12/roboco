package sh.roboco.android.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure rule tables of android-ui-parity #56, pinned without Compose:
 * [wantsCard] (the capsule ⇄ card rule, `ios:Composer/ComposerBar.swift:308-310`)
 * and [imeBlurStep] (the Android-only "a closed IME blurs the field" watch).
 * The spring, the taps and the sequences are `ComposerMorphBehaviourTest`'s.
 */
class ComposerMorphRuleTest {

    // ------------------------------------------------------------- wantsCard

    @Test
    fun theWantsCardTableMatchesIosExactly() {
        // The ticket's 5-row table, exhaustive over the inputs.
        assertFalse("an unfocused, image-free composer rests as the capsule", wantsCard(false, false, false))
        assertTrue("staged images hold the card", wantsCard(false, false, true))
        assertTrue("focus holds the card", wantsCard(false, true, false))
        assertTrue("focus with images holds the card", wantsCard(false, true, true))
        assertTrue("alwaysCard pins the card, unfocused and empty", wantsCard(true, false, false))
        assertTrue("alwaysCard with images", wantsCard(true, false, true))
        assertTrue("alwaysCard while focused", wantsCard(true, true, false))
        assertTrue("alwaysCard, focused, with images", wantsCard(true, true, true))
    }

    @Test
    fun anUnfocusedTextDraftRestsAsTheCapsule() {
        // The pr621/pr609 divergence this port rejects: text is not an
        // input. wantsCard takes no text argument at all — the signature
        // itself pins the rule, so a future "draft holds the card" change
        // cannot slip in silently.
        val wantsCard = ComposerMorphRuleTest::class.java.classLoader
            .loadClass("sh.roboco.android.composer.ComposerMorphKt")
            .getMethod(
                "wantsCard",
                Boolean::class.java,
                Boolean::class.java,
                Boolean::class.java,
            )
        assertEquals("wantsCard(alwaysCard, focused, hasImages) — text is deliberately absent", 3, wantsCard.parameterCount)
    }

    // ------------------------------------------------------------ imeBlurStep

    @Test
    fun notFocusedResetsTheWatchAndNeverBlurs() {
        assertEquals(false to false, imeBlurStep(focused = false, imeVisible = false, windowFocused = false, imeSeen = true))
        assertEquals(false to false, imeBlurStep(focused = false, imeVisible = true, windowFocused = true, imeSeen = true))
        assertEquals(false to false, imeBlurStep(focused = false, imeVisible = false, windowFocused = true, imeSeen = false))
    }

    @Test
    fun aVisibleImeIsOnlyMarkedSeen() {
        assertEquals(true to false, imeBlurStep(focused = true, imeVisible = true, windowFocused = true, imeSeen = false))
        assertEquals(true to false, imeBlurStep(focused = true, imeVisible = true, windowFocused = false, imeSeen = false))
        assertEquals(true to false, imeBlurStep(focused = true, imeVisible = true, windowFocused = true, imeSeen = true))
    }

    @Test
    fun anImeThatWentAwayWithTheWindowFocusedBlurs() {
        val (seen, blur) = imeBlurStep(focused = true, imeVisible = false, windowFocused = true, imeSeen = true)
        assertTrue("the closed IME blurs the focused field", blur)
        assertFalse("the watch resets after the blur", seen)
    }

    @Test
    fun anImeThatWentAwayWhileTheWindowIsUnfocusedKeepsTheWatch() {
        // A focusable popup or app backgrounding must not collapse the composer.
        val (seen, blur) = imeBlurStep(focused = true, imeVisible = false, windowFocused = false, imeSeen = true)
        assertFalse("no blur while the window is unfocused", blur)
        assertTrue("seen is kept: the IME may come back with the window", seen)
    }

    @Test
    fun anImeNeverShownNeverBlurs() {
        // A hardware keyboard: isImeVisible stays false the whole time.
        assertEquals(false to false, imeBlurStep(focused = true, imeVisible = false, windowFocused = true, imeSeen = false))
        assertEquals(false to false, imeBlurStep(focused = true, imeVisible = false, windowFocused = false, imeSeen = false))
    }

    @Test
    fun theWatchSurvivesWindowFocusLossAndThenBlursOnReturn() {
        // Focused with the IME up; a menu steals window focus (the IME
        // stays up); the menu closes; the IME then goes away with the
        // window focused again → blur.
        var (seen, blur) = imeBlurStep(focused = true, imeVisible = true, windowFocused = false, imeSeen = false)
        assertFalse(blur)
        assertTrue(seen)
        val second = imeBlurStep(focused = true, imeVisible = true, windowFocused = true, imeSeen = seen)
        assertEquals(true to false, second)
        val third = imeBlurStep(focused = true, imeVisible = false, windowFocused = true, imeSeen = second.first)
        assertEquals(false to true, third)
    }
}
