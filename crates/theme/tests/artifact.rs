//! Round-trip the checked-in web theme artifact against the live sources of
//! truth: `builtin_registry()` (post-hardening role set) for the variants and
//! accent derivations, and `roboco_proto::{layout, motion}` for the layout
//! and motion constants. If any of these fail, regenerate with
//! `cargo run -p roboco-theme --bin roboco-theme-export`.

use std::path::PathBuf;

use roboco_theme::artifact::{self, FONT_FACES, render_files};
use roboco_theme::{
    AccentPreset, AccentRoles, AccentSelection, Color, ThemeFamily, ThemeVariant, builtin_registry,
};

fn web_package_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../web/packages/theme")
}

fn artifact_json() -> serde_json::Value {
    let path = web_package_dir().join("src/generated/artifact.json");
    let content = std::fs::read_to_string(&path)
        .unwrap_or_else(|err| panic!("could not read {}: {err}", path.display()));
    serde_json::from_str(&content).expect("the theme artifact is valid JSON")
}

#[test]
fn artifact_is_fresh() {
    let generated = web_package_dir().join("src/generated");
    for (name, content) in render_files() {
        let path = generated.join(name);
        let existing = std::fs::read_to_string(&path)
            .unwrap_or_else(|err| panic!("could not read {}: {err}", path.display()));
        assert_eq!(
            existing,
            content,
            "{} is stale — regenerate with `cargo run -p roboco-theme --bin roboco-theme-export`",
            path.display()
        );
    }
}

#[test]
fn artifact_contains_every_builtin_family_and_variant() {
    let json = artifact_json();
    let families: Vec<ThemeFamily> =
        serde_json::from_value(json["families"].clone()).expect("families match the model");
    let registry = builtin_registry();

    // The full resolved registry — every role, derivation, and provenance
    // hash — round-trips exactly.
    assert_eq!(families, registry.families);
    assert_eq!(families.len(), 19);
    assert_eq!(
        families
            .iter()
            .map(|family| family.variants.len())
            .sum::<usize>(),
        30
    );
}

/// The three roles the desktop authors by hand travel on every exported
/// variant: Roboco carries the authored tones, every other family carries the
/// fallback the desktop's variant loader uses for a theme that did not author
/// them. The web reads them as `--rb-text-dim`, `--rb-raised-hover`, and
/// `--rb-danger-strong`.
#[test]
fn exported_variants_carry_the_hand_authored_roles() {
    let json = artifact_json();
    let families: Vec<ThemeFamily> =
        serde_json::from_value(json["families"].clone()).expect("families match the model");
    let variant = |id: &str| -> ThemeVariant {
        families
            .iter()
            .flat_map(|family| &family.variants)
            .find(|variant| variant.id == id)
            .unwrap_or_else(|| panic!("{id} is exported"))
            .clone()
    };

    let dark = variant("roboco-dark").colors;
    let light = variant("roboco-light").colors;
    assert_eq!(dark.text_dim, Color::grey(0x98));
    assert_eq!(light.text_dim, Color::neutral(0.50));
    assert_eq!(dark.raised_hover, Color::neutral(0.29));
    assert_eq!(light.raised_hover, Color::neutral(0.900));
    assert_eq!(dark.danger_strong, Color::oklch(0.58, 0.16, 25.0));
    assert_eq!(light.danger_strong, Color::oklch(0.51, 0.20, 25.0));
    // An opaque pill's hover has to move off the plate it hovers.
    assert_ne!(dark.raised_hover, dark.raised);
    assert_ne!(light.raised_hover, light.raised);

    for family in &families {
        if family.id == "roboco" {
            continue;
        }
        for variant in &family.variants {
            let colors = &variant.colors;
            assert_eq!(colors.text_dim, colors.text_muted, "{}", variant.id);
            assert_eq!(colors.raised_hover, colors.raised, "{}", variant.id);
            assert_eq!(colors.danger_strong, colors.danger, "{}", variant.id);
        }
    }
}

#[test]
fn exported_accent_derivations_match_the_registry() {
    let json = artifact_json();
    let registry = builtin_registry();
    let accents = json["accents"].as_object().expect("accents is an object");

    for variant in registry.families.iter().flat_map(|family| &family.variants) {
        // The theme-authored accent ships on the variant itself (checked by
        // the exact round-trip above); presets are precomputed per variant.
        let per_preset = accents
            .get(&variant.id)
            .and_then(|value| value.as_object())
            .unwrap_or_else(|| panic!("{} has no accent derivations", variant.id));
        assert_eq!(
            per_preset.len(),
            AccentPreset::ALL.len(),
            "{} must derive every preset",
            variant.id
        );
        for preset in AccentPreset::ALL {
            let exported: AccentRoles =
                serde_json::from_value(per_preset[&artifact::preset_id(preset)].clone())
                    .expect("accent roles match the model");
            let expected = variant.accent_for(AccentSelection::Preset(preset));
            assert_eq!(
                exported,
                expected,
                "{} + {} derivation drifted",
                variant.id,
                artifact::preset_id(preset)
            );
        }
    }
}

#[test]
fn exported_layout_and_motion_match_the_shared_constants() {
    let json = artifact_json();
    let layout = &json["layout"];
    let motion = &json["motion"];
    let px = |value: &serde_json::Value| value.as_f64().expect("a number") as f32;

    use roboco_proto::layout as l;
    assert_eq!(px(&layout["space"]["xs"]), l::SPACE_XS);
    assert_eq!(px(&layout["space"]["sm"]), l::SPACE_SM);
    assert_eq!(px(&layout["space"]["md"]), l::SPACE_MD);
    assert_eq!(px(&layout["space"]["lg"]), l::SPACE_LG);
    assert_eq!(px(&layout["space"]["textStackGap"]), l::TEXT_STACK_GAP);
    assert_eq!(px(&layout["radius"]["bubble"]), l::BUBBLE_RADIUS);
    assert_eq!(px(&layout["radius"]["panel"]), l::PANEL_RADIUS);
    assert_eq!(px(&layout["radius"]["control"]), l::CONTROL_RADIUS);
    assert_eq!(px(&layout["chrome"]["headerHeight"]), l::HEADER_HEIGHT);
    assert_eq!(px(&layout["chrome"]["titlebarHeight"]), l::TITLEBAR_HEIGHT);
    assert_eq!(px(&layout["chrome"]["titlebarTopPad"]), l::TITLEBAR_TOP_PAD);
    assert_eq!(
        px(&layout["chrome"]["statusStripHeight"]),
        l::STATUS_STRIP_HEIGHT
    );
    assert_eq!(
        px(&layout["chrome"]["transcriptFadeBand"]),
        l::TRANSCRIPT_FADE_BAND
    );
    assert_eq!(px(&layout["glass"]["windowAlpha"]), l::GLASS_ALPHA_FROSTED);
    assert_eq!(
        px(&layout["glass"]["windowAlphaOpaque"]),
        l::GLASS_ALPHA_OPAQUE
    );
    assert_eq!(
        px(&layout["glass"]["overlayAlphaDark"]),
        l::GLASS_OVERLAY_ALPHA_DARK
    );
    assert_eq!(
        px(&layout["glass"]["overlayAlphaLight"]),
        l::GLASS_OVERLAY_ALPHA_LIGHT
    );
    assert_eq!(
        px(&layout["glass"]["inputAlphaLight"]),
        l::INPUT_GLASS_ALPHA_LIGHT
    );
    assert_eq!(px(&layout["glass"]["cardAlpha"]), l::CARD_GLASS_ALPHA);
    assert_eq!(
        px(&layout["glass"]["selectedWashAlphaDark"]),
        artifact::SELECTED_WASH_ALPHA_DARK
    );
    assert_eq!(
        px(&layout["glass"]["selectedWashAlphaLight"]),
        artifact::SELECTED_WASH_ALPHA_LIGHT
    );
    assert_eq!(px(&layout["glass"]["bandAlphaDark"]), artifact::BAND_ALPHA_DARK);
    assert_eq!(
        px(&layout["glass"]["bandAlphaLight"]),
        artifact::BAND_ALPHA_LIGHT
    );
    assert_eq!(px(&layout["glass"]["scrimAlphaDark"]), artifact::SCRIM_ALPHA_DARK);
    assert_eq!(
        px(&layout["glass"]["scrimAlphaLight"]),
        artifact::SCRIM_ALPHA_LIGHT
    );

    use roboco_proto::motion as m;
    let curves = motion["curves"].as_object().expect("curves is an object");
    assert_eq!(curves.len(), m::CURVES.len());
    for (name, curve) in m::CURVES {
        let exported = curves[*name]
            .as_array()
            .expect("curve is [x1, y1, x2, y2]")
            .iter()
            .map(|v| v.as_f64().expect("a number") as f32)
            .collect::<Vec<_>>();
        assert_eq!(
            *exported,
            [curve.x1, curve.y1, curve.x2, curve.y2],
            "{name}"
        );
    }

    let specs = motion["specs"].as_array().expect("specs is an array");
    assert_eq!(specs.len(), m::CATALOG.len());
    for (exported, entry) in specs.iter().zip(m::CATALOG) {
        assert_eq!(exported["name"].as_str().unwrap(), entry.name);
        assert_eq!(exported["curve"].as_str().unwrap(), entry.curve);
        assert_eq!(
            exported["durationMs"].as_u64().unwrap(),
            entry.spec.duration_ms,
            "{}",
            entry.name
        );
        assert_eq!(
            exported["delayMs"].as_u64().unwrap(),
            entry.spec.delay_ms,
            "{}",
            entry.name
        );
    }

    assert_eq!(px(&motion["resizeBounce"]["nudgePx"]), m::RESIZE_EDGE_NUDGE);
    assert_eq!(
        motion["resizeBounce"]["durationMs"].as_u64().unwrap(),
        m::RESIZE_EDGE_BOUNCE_MS
    );
    assert_eq!(
        px(&motion["resizeBounce"]["outFraction"]),
        m::RESIZE_EDGE_BOUNCE_OUT_FRACTION
    );

    let spring = &motion["stickSpring"];
    assert_eq!(px(&spring["damping"]), m::SPRING_DAMPING);
    assert_eq!(px(&spring["stiffness"]), m::SPRING_STIFFNESS);
    assert_eq!(px(&spring["mass"]), m::SPRING_MASS);
    assert_eq!(px(&spring["frameMs"]), m::SPRING_FRAME_MS);
    assert_eq!(
        px(&spring["maxCatchupFrames"]),
        m::SPRING_MAX_CATCHUP_FRAMES
    );
    assert_eq!(px(&spring["growthEma"]), m::SPRING_GROWTH_EMA);
    assert_eq!(px(&spring["chaseMaxLeadPx"]), m::SPRING_CHASE_MAX_LEAD);
    assert_eq!(px(&spring["atBottomPx"]), m::AT_BOTTOM_PX);
    assert_eq!(px(&spring["stickThresholdPx"]), m::STICK_THRESHOLD_PX);
    assert_eq!(
        spring["settleGraceMs"].as_u64().unwrap(),
        m::SPRING_SETTLE_GRACE_MS
    );
    assert_eq!(px(&spring["glideMaxViewports"]), m::GLIDE_MAX_VIEWPORTS);

    let glide = &motion["dockGlide"];
    assert_eq!(px(&glide["timeConstants"]), m::DOCK_GLIDE_TIME_CONSTANTS);
    assert_eq!(px(&glide["dockSeconds"]), m::DOCK_GLIDE_DOCK_SECONDS);
    assert_eq!(px(&glide["undockSeconds"]), m::DOCK_GLIDE_UNDOCK_SECONDS);
    assert_eq!(px(&glide["settlePosition"]), m::DOCK_GLIDE_SETTLE_POSITION);
    assert_eq!(px(&glide["settleVelocity"]), m::DOCK_GLIDE_SETTLE_VELOCITY);
}

#[test]
fn fonts_manifest_files_are_bundled() {
    let fonts_dir = web_package_dir().join("fonts");
    assert_eq!(FONT_FACES.len(), 16, "8 Geist + 8 Geist Mono faces");
    let json = artifact_json();
    assert_eq!(
        json["fonts"].as_array().expect("fonts is an array").len(),
        FONT_FACES.len()
    );
    for face in FONT_FACES {
        assert!(
            fonts_dir.join(face.file).is_file(),
            "{} is missing from the web package",
            face.file
        );
    }
    assert!(
        fonts_dir.join("licenses/Geist-OFL.txt").is_file(),
        "the OFL license ships with the fonts"
    );
}
