//! The web theme artifact — one serializable bundle of everything the browser
//! client needs to reproduce the desktop's visuals: the resolved builtin
//! registry (post-hardening role set), every accent preset's derived roles per
//! variant, the layout constants, the motion catalog, and the physical spring
//! parameters.
//!
//! The constants come from `roboco_proto::{layout, motion}` — the same source
//! the desktop compiles — so the artifact can only drift if someone edits the
//! artifact by hand; the `--check` gate and the round-trip test in
//! `crates/theme/tests/artifact.rs` catch that.

use std::collections::BTreeMap;

use serde::Serialize;

use crate::{
    AccentPreset, AccentRoles, AccentSelection, Appearance, ThemeFamily, builtin_registry,
};

/// Version of the artifact's JSON *shape* (not the data). Bump on schema
/// changes so the web client can pin instead of sniff.
pub const ARTIFACT_SCHEMA_VERSION: u32 = 1;

/// Stable id of the generator, stamped into the artifact for provenance.
pub const GENERATOR: &str = "roboco-theme-export";

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ThemeArtifact {
    pub schema_version: u32,
    pub generator: &'static str,
    /// The builtin registry exactly as `builtin_registry()` resolves it —
    /// every family, every variant, the full post-hardening role set.
    pub families: Vec<ThemeFamily>,
    pub accent_presets: Vec<AccentPresetTokens>,
    /// `variantId -> presetId -> derived roles`, precomputed for all
    /// 30 variants × 7 presets so the web needs zero color math. The
    /// theme-authored accent (`themeDefault`) travels on the variant itself.
    pub accents: BTreeMap<String, BTreeMap<String, AccentRoles>>,
    pub layout: LayoutTokens,
    pub motion: MotionTokens,
    /// Manifest of the bundled font faces (files live beside the artifact in
    /// the web package; they are copies of `crates/ui/assets/fonts/`).
    pub fonts: Vec<FontFace>,
}

/// A selectable accent preset and its authored dark/light base colors.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccentPresetTokens {
    /// The serde name of [`AccentPreset`] — the same string the desktop's
    /// settings file carries (`{"preset": "<id>"}`).
    pub id: String,
    pub label: &'static str,
    pub dark: crate::Color,
    pub light: crate::Color,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LayoutTokens {
    pub space: SpaceTokens,
    pub radius: RadiusTokens,
    pub chrome: ChromeTokens,
    pub glass: GlassTokens,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SpaceTokens {
    pub xs: f32,
    pub sm: f32,
    pub md: f32,
    pub lg: f32,
    pub text_stack_gap: f32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RadiusTokens {
    pub bubble: f32,
    pub panel: f32,
    pub control: f32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ChromeTokens {
    pub header_height: f32,
    pub titlebar_height: f32,
    pub titlebar_top_pad: f32,
    pub status_strip_height: f32,
    pub transcript_fade_band: f32,
}

/// Frosted-surface coverage. `windowAlpha*` carries both platform values
/// explicitly (rather than the build machine's `cfg!` pick) so the artifact
/// is byte-identical on every host.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GlassTokens {
    /// Window chrome tint on compositor-blur platforms (macOS/Windows).
    pub window_alpha: f32,
    /// Window chrome tint where compositor blur is unavailable (Linux).
    pub window_alpha_opaque: f32,
    pub overlay_alpha_dark: f32,
    pub overlay_alpha_light: f32,
    pub input_alpha_light: f32,
    pub card_alpha: f32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MotionTokens {
    /// Named CSS cubic-bezier curves as `[x1, y1, x2, y2]`.
    pub curves: BTreeMap<&'static str, [f32; 4]>,
    pub specs: Vec<MotionSpecTokens>,
    pub resize_bounce: ResizeBounceTokens,
    pub stick_spring: StickSpringTokens,
    pub dock_glide: DockGlideTokens,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MotionSpecTokens {
    pub name: &'static str,
    pub duration_ms: u64,
    pub delay_ms: u64,
    /// Name of the entry's curve in [`MotionTokens::curves`].
    pub curve: &'static str,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResizeBounceTokens {
    pub nudge_px: f32,
    pub duration_ms: u64,
    pub out_fraction: f32,
}

/// The transcript's stick-to-bottom spring (mugen DEFAULT_SPRING shape).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StickSpringTokens {
    pub damping: f32,
    pub stiffness: f32,
    pub mass: f32,
    pub frame_ms: f32,
    pub max_catchup_frames: f32,
    pub growth_ema: f32,
    pub chase_max_lead_px: f32,
    pub at_bottom_px: f32,
    pub stick_threshold_px: f32,
    pub settle_grace_ms: u64,
    pub glide_max_viewports: f32,
}

/// The composer's critically damped dock glide (new-thread ↔ thread handoff).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DockGlideTokens {
    /// Omega per second of requested duration (`omega = timeConstants /
    /// duration`); twelve time constants settle within a fraction of a pixel.
    pub time_constants: f32,
    pub dock_seconds: f32,
    pub undock_seconds: f32,
    pub settle_position: f32,
    pub settle_velocity: f32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FontFace {
    pub family: &'static str,
    /// File name under `web/packages/theme/fonts/`.
    pub file: &'static str,
    pub weight: u16,
    pub style: &'static str,
}

/// Geist + Geist Mono, weights 400/500/600/700 × roman/italic — the same
/// static TTFs the desktop embeds from `crates/ui/assets/fonts/`
/// (vercel/geist-font v1.7.2, SIL OFL 1.1).
pub const FONT_FACES: &[FontFace] = &[
    FontFace {
        family: "Geist",
        file: "Geist.ttf",
        weight: 400,
        style: "normal",
    },
    FontFace {
        family: "Geist",
        file: "Geist-Italic.ttf",
        weight: 400,
        style: "italic",
    },
    FontFace {
        family: "Geist",
        file: "Geist-Medium.ttf",
        weight: 500,
        style: "normal",
    },
    FontFace {
        family: "Geist",
        file: "Geist-MediumItalic.ttf",
        weight: 500,
        style: "italic",
    },
    FontFace {
        family: "Geist",
        file: "Geist-SemiBold.ttf",
        weight: 600,
        style: "normal",
    },
    FontFace {
        family: "Geist",
        file: "Geist-SemiBoldItalic.ttf",
        weight: 600,
        style: "italic",
    },
    FontFace {
        family: "Geist",
        file: "Geist-Bold.ttf",
        weight: 700,
        style: "normal",
    },
    FontFace {
        family: "Geist",
        file: "Geist-BoldItalic.ttf",
        weight: 700,
        style: "italic",
    },
    FontFace {
        family: "Geist Mono",
        file: "GeistMono.ttf",
        weight: 400,
        style: "normal",
    },
    FontFace {
        family: "Geist Mono",
        file: "GeistMono-Italic.ttf",
        weight: 400,
        style: "italic",
    },
    FontFace {
        family: "Geist Mono",
        file: "GeistMono-Medium.ttf",
        weight: 500,
        style: "normal",
    },
    FontFace {
        family: "Geist Mono",
        file: "GeistMono-MediumItalic.ttf",
        weight: 500,
        style: "italic",
    },
    FontFace {
        family: "Geist Mono",
        file: "GeistMono-SemiBold.ttf",
        weight: 600,
        style: "normal",
    },
    FontFace {
        family: "Geist Mono",
        file: "GeistMono-SemiBoldItalic.ttf",
        weight: 600,
        style: "italic",
    },
    FontFace {
        family: "Geist Mono",
        file: "GeistMono-Bold.ttf",
        weight: 700,
        style: "normal",
    },
    FontFace {
        family: "Geist Mono",
        file: "GeistMono-BoldItalic.ttf",
        weight: 700,
        style: "italic",
    },
];

/// The serde name of an accent preset (`"roboco"`, `"orange"`, …) — the same
/// spelling `AccentSelection` carries in settings.
pub fn preset_id(preset: AccentPreset) -> String {
    serde_json::to_value(preset)
        .expect("accent preset serializes")
        .as_str()
        .expect("accent preset serializes as a string")
        .to_string()
}

/// Build the artifact from the live registry and proto constants.
pub fn build_artifact() -> ThemeArtifact {
    use roboco_proto::{layout, motion as m};

    let families = builtin_registry().families.clone();
    let mut accents: BTreeMap<String, BTreeMap<String, AccentRoles>> = BTreeMap::new();
    for variant in families.iter().flat_map(|family| &family.variants) {
        let per_preset = AccentPreset::ALL
            .into_iter()
            .map(|preset| {
                (
                    preset_id(preset),
                    variant.accent_for(AccentSelection::Preset(preset)),
                )
            })
            .collect();
        accents.insert(variant.id.clone(), per_preset);
    }

    let accent_presets = AccentPreset::ALL
        .into_iter()
        .map(|preset| AccentPresetTokens {
            id: preset_id(preset),
            label: preset.label(),
            dark: preset.color(Appearance::Dark),
            light: preset.color(Appearance::Light),
        })
        .collect();

    let curves = m::CURVES
        .iter()
        .map(|(name, curve)| (*name, [curve.x1, curve.y1, curve.x2, curve.y2]))
        .collect();
    let specs = m::CATALOG
        .iter()
        .map(|entry| MotionSpecTokens {
            name: entry.name,
            duration_ms: entry.spec.duration_ms,
            delay_ms: entry.spec.delay_ms,
            curve: entry.curve,
        })
        .collect();

    ThemeArtifact {
        schema_version: ARTIFACT_SCHEMA_VERSION,
        generator: GENERATOR,
        families,
        accent_presets,
        accents,
        layout: LayoutTokens {
            space: SpaceTokens {
                xs: layout::SPACE_XS,
                sm: layout::SPACE_SM,
                md: layout::SPACE_MD,
                lg: layout::SPACE_LG,
                text_stack_gap: layout::TEXT_STACK_GAP,
            },
            radius: RadiusTokens {
                bubble: layout::BUBBLE_RADIUS,
                panel: layout::PANEL_RADIUS,
                control: layout::CONTROL_RADIUS,
            },
            chrome: ChromeTokens {
                header_height: layout::HEADER_HEIGHT,
                titlebar_height: layout::TITLEBAR_HEIGHT,
                titlebar_top_pad: layout::TITLEBAR_TOP_PAD,
                status_strip_height: layout::STATUS_STRIP_HEIGHT,
                transcript_fade_band: layout::TRANSCRIPT_FADE_BAND,
            },
            glass: GlassTokens {
                window_alpha: layout::GLASS_ALPHA_FROSTED,
                window_alpha_opaque: layout::GLASS_ALPHA_OPAQUE,
                overlay_alpha_dark: layout::GLASS_OVERLAY_ALPHA_DARK,
                overlay_alpha_light: layout::GLASS_OVERLAY_ALPHA_LIGHT,
                input_alpha_light: layout::INPUT_GLASS_ALPHA_LIGHT,
                card_alpha: layout::CARD_GLASS_ALPHA,
            },
        },
        motion: MotionTokens {
            curves,
            specs,
            resize_bounce: ResizeBounceTokens {
                nudge_px: m::RESIZE_EDGE_NUDGE,
                duration_ms: m::RESIZE_EDGE_BOUNCE_MS,
                out_fraction: m::RESIZE_EDGE_BOUNCE_OUT_FRACTION,
            },
            stick_spring: StickSpringTokens {
                damping: m::SPRING_DAMPING,
                stiffness: m::SPRING_STIFFNESS,
                mass: m::SPRING_MASS,
                frame_ms: m::SPRING_FRAME_MS,
                max_catchup_frames: m::SPRING_MAX_CATCHUP_FRAMES,
                growth_ema: m::SPRING_GROWTH_EMA,
                chase_max_lead_px: m::SPRING_CHASE_MAX_LEAD,
                at_bottom_px: m::AT_BOTTOM_PX,
                stick_threshold_px: m::STICK_THRESHOLD_PX,
                settle_grace_ms: m::SPRING_SETTLE_GRACE_MS,
                glide_max_viewports: m::GLIDE_MAX_VIEWPORTS,
            },
            dock_glide: DockGlideTokens {
                time_constants: m::DOCK_GLIDE_TIME_CONSTANTS,
                dock_seconds: m::DOCK_GLIDE_DOCK_SECONDS,
                undock_seconds: m::DOCK_GLIDE_UNDOCK_SECONDS,
                settle_position: m::DOCK_GLIDE_SETTLE_POSITION,
                settle_velocity: m::DOCK_GLIDE_SETTLE_VELOCITY,
            },
        },
        fonts: FONT_FACES.to_vec(),
    }
}

/// Header stamped on every generated file.
const GENERATED_HEADER: &str = "DO NOT EDIT — generated by roboco-theme-export";

/// The TS wrapper beside `artifact.json`. Static content, but written by the
/// exporter so everything under `src/generated/` is machine-owned.
fn index_ts() -> String {
    format!(
        "// {GENERATED_HEADER} (cargo run -p roboco-theme --bin roboco-theme-export)\n\
         import type {{ ThemeArtifact }} from \"../types\";\n\
         import json from \"./artifact.json\";\n\
         \n\
         /** The whole theme artifact, typed. */\n\
         export const themeArtifact = json as unknown as ThemeArtifact;\n"
    )
}

/// Render the artifact as `(file name, content)` pairs — the full contents of
/// `web/packages/theme/src/generated/`.
pub fn render_files() -> Vec<(&'static str, String)> {
    let artifact = build_artifact();
    let mut json = serde_json::to_string_pretty(&artifact).expect("the theme artifact serializes");
    json.push('\n');
    vec![("artifact.json", json), ("index.ts", index_ts())]
}
