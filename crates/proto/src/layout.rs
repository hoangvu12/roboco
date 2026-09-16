//! Layout + glass tokens — the fixed geometry of the roboco chrome.
//!
//! These constants are the desktop's (`roboco-ui/src/theme.rs` exposes them as
//! `Theme::…` associated constants), lifted here so any surface — including
//! the web client's generated theme artifact — lays out with the *same*
//! numbers rather than re-deriving its own spacing ladder.
//!
//! Everything is plain px. UI text scales rems off the 16px root; these
//! constants are the fixed chrome that does not scale.

// ---------------------------------------------------------------------------
// Spacing ladder
// ---------------------------------------------------------------------------

/// Base spacing steps.
pub const SPACE_XS: f32 = 4.0;
pub const SPACE_SM: f32 = 8.0;
pub const SPACE_MD: f32 = 12.0;
pub const SPACE_LG: f32 = 16.0;
/// Optical separation for a tightly coupled title/description stack. This is
/// intentionally outside the base spacing ladder: it corrects line-box
/// whitespace rather than separating layout regions.
pub const TEXT_STACK_GAP: f32 = 1.0;

// ---------------------------------------------------------------------------
// Radii
// ---------------------------------------------------------------------------

/// Message bubble corner radius.
pub const BUBBLE_RADIUS: f32 = 16.0;
/// Panel / card corner radius.
pub const PANEL_RADIUS: f32 = 10.0;
/// Small control radius (buttons, chips).
pub const CONTROL_RADIUS: f32 = 6.0;

// ---------------------------------------------------------------------------
// Chrome heights
// ---------------------------------------------------------------------------

/// Main-panel header height (roboco `h-11`) — in-card headers (changes pane).
pub const HEADER_HEIGHT: f32 = 44.0;
/// The unified window titlebar (traffic lights + cluster + tabs). Content
/// rides [`TITLEBAR_TOP_PAD`] lower than center so the air above matches the
/// perceived gap to the inset card below (border + card body).
pub const TITLEBAR_HEIGHT: f32 = 38.0;
/// Top-only padding moves the flex center by half this value. On macOS,
/// 38 / 2 + 4 / 2 = 21 matches the native traffic lights' center.
pub const TITLEBAR_TOP_PAD: f32 = 4.0;
/// Reserved status strip under the content outlet (roboco `h-6`) — the
/// WorkingIndicator row; reserving it keeps the composer from shifting.
pub const STATUS_STRIP_HEIGHT: f32 = 24.0;
/// Height of the gradient that fades the transcript into the panel background
/// at its bottom edge. The transcript's last row must pad itself past this
/// band so settled content (message text, the hover-revealed timestamp) never
/// sits inside the fade when scrolled to the bottom.
pub const TRANSCRIPT_FADE_BAND: f32 = 24.0;

// ---------------------------------------------------------------------------
// Glass (frosted surface treatment)
// ---------------------------------------------------------------------------

/// Frost translucency over the blurred window background on platforms with
/// compositor vibrancy (macOS/Windows).
pub const GLASS_ALPHA_FROSTED: f32 = 0.80;
/// Frost alpha where compositor blur is not guaranteed (Linux): a merely
/// transparent window would expose the raw desktop, so chrome stays opaque.
pub const GLASS_ALPHA_OPAQUE: f32 = 1.0;
/// Frost translucency over the blurred window background (macOS vibrancy or
/// Windows Acrylic) for dark chrome. Darkness matched by eye to a reference
/// Electron app's dark glass; see `Theme::glass` in roboco-ui.
pub const GLASS_ALPHA: f32 = if cfg!(any(target_os = "macos", target_os = "windows")) {
    GLASS_ALPHA_FROSTED
} else {
    GLASS_ALPHA_OPAQUE
};
/// Light-mode frost alpha — glass-forward, like dark mode. A light tint
/// controls the blur less than a dark one, so light frost runs *heavier* than
/// an equal-looking dark frost to keep the chrome on a known-enough background
/// for its labels (macOS light sidebars do the same).
pub const GLASS_ALPHA_LIGHT: f32 = if cfg!(any(target_os = "macos", target_os = "windows")) {
    GLASS_ALPHA_FROSTED
} else {
    GLASS_ALPHA_OPAQUE
};
/// Base coverage of the floating-card tint (`Theme::glass_overlay`) in dark
/// mode, before the contrast check raises it.
pub const GLASS_OVERLAY_ALPHA_DARK: f32 = 0.50;
/// Base coverage of the floating-card tint in light mode — heavier, because
/// dark text is more vulnerable to unpredictable content behind a popover.
pub const GLASS_OVERLAY_ALPHA_LIGHT: f32 = 0.85;
/// Base coverage of the composer/queue/input tint (`Theme::input_glass_bg`)
/// in light mode; dark mode keeps the theme's authored input alpha.
pub const INPUT_GLASS_ALPHA_LIGHT: f32 = 0.30;
/// Base coverage of the section-card tint (`Theme::card_glass_bg`) — glass
/// thins the opaque card tone to a translucent tint.
pub const CARD_GLASS_ALPHA: f32 = 0.40;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ladders_are_monotone() {
        let spacing = [SPACE_XS, SPACE_SM, SPACE_MD, SPACE_LG];
        assert!(spacing.windows(2).all(|w| w[0] < w[1]), "{spacing:?}");
        let radii = [CONTROL_RADIUS, PANEL_RADIUS, BUBBLE_RADIUS];
        assert!(radii.windows(2).all(|w| w[0] < w[1]), "{radii:?}");
    }

    #[test]
    fn glass_alphas_stay_in_unit_interval() {
        let alphas = [
            GLASS_ALPHA_FROSTED,
            GLASS_ALPHA_OPAQUE,
            GLASS_OVERLAY_ALPHA_DARK,
            GLASS_OVERLAY_ALPHA_LIGHT,
            INPUT_GLASS_ALPHA_LIGHT,
            CARD_GLASS_ALPHA,
        ];
        assert!(alphas.iter().all(|a| (0.0..=1.0).contains(a)), "{alphas:?}");
        // The frosted value is the transparent one; the opaque fallback is solid.
        let pair = [GLASS_ALPHA_FROSTED, GLASS_ALPHA_OPAQUE];
        assert!(pair.windows(2).all(|w| w[0] < w[1]), "{pair:?}");
    }
}
