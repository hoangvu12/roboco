//! VectorDrawable conversion of the shared line icons (android-app#13).
//!
//! Seams under test:
//! - `vector_drawable(file)`: one SVG file → one `res/drawable/zi_*.xml`
//!   string, ink always opaque black (`#FF000000`), stroke geometry
//!   preserved, viewBox → viewport.

use std::path::Path;

fn repo_root() -> &'static Path {
    roboco_android_assets::repo_root()
}

fn icon(name: &str) -> std::path::PathBuf {
    repo_root().join("crates/ui/assets/icons").join(name)
}

#[test]
fn plus_svg_becomes_a_stroked_vector_drawable() {
    let xml = roboco_android_assets::vector_drawable(&icon("plus.svg")).unwrap();

    // viewport/size come from the SVG's 16x16 viewBox.
    assert!(
        xml.contains("android:viewportWidth=\"16\""),
        "viewport width missing in:\n{xml}"
    );
    assert!(
        xml.contains("android:viewportHeight=\"16\""),
        "viewport height missing in:\n{xml}"
    );
    assert!(xml.contains("android:width=\"16dp\""), "no 16dp width");
    assert!(xml.contains("android:height=\"16dp\""), "no 16dp height");

    // `stroke="currentColor" stroke-width="1.25" stroke-linecap="round"`
    // becomes opaque black ink at the same width and cap.
    assert!(xml.contains("android:strokeColor=\"#FF000000\""));
    assert!(xml.contains("android:strokeWidth=\"1.25\""));
    assert!(xml.contains("android:strokeLineCap=\"round\""));

    // `M8 3.25v9.5M3.25 8h9.5` — two subpaths, absolute, `v`/`h`
    // resolved to lines.
    assert!(
        xml.contains("M8,3.25"),
        "first subpath start missing in:\n{xml}"
    );
    assert!(
        xml.contains("M3.25,8"),
        "second subpath start missing in:\n{xml}"
    );
    // `fill="none"` must stay explicitly transparent, never inherit ink.
    assert!(xml.contains("android:fillColor=\"#00000000\""));
}

#[test]
fn group_transforms_bake_into_absolute_coordinates_and_stroke_width() {
    // A `<line>` (usvg converts it to a path) inside a translated+scaled
    // group: the VD must carry absolute, transformed coordinates, and the
    // stroke width must scale by sqrt(|det|) = 2.
    let svg = [
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\" fill=\"none\">",
        "<g transform=\"translate(2 3) scale(2)\">",
        "<line x1=\"1\" y1=\"1\" x2=\"2\" y2=\"1\" stroke=\"currentColor\" stroke-width=\"1\"/>",
        "</g></svg>",
    ]
    .join("");
    let dir = tempfile::tempdir().unwrap();
    let file = dir.path().join("transformed.svg");
    std::fs::write(&file, svg).unwrap();

    let xml = roboco_android_assets::vector_drawable(&file).unwrap();

    // (1,1) -> translate(2,3) -> (3,4) -> scale(2) -> wait: transform order
    // is `translate(2 3)` then `scale(2)` applied to child coordinates, so
    // (1,1) -> scale(2) -> (2,2) -> translate(2,3) -> (4,5). Same for
    // (2,1) -> (6,5).
    assert!(
        xml.contains("M4,5"),
        "transformed start point missing in:\n{xml}"
    );
    assert!(
        xml.contains("L6,5"),
        "transformed end point missing in:\n{xml}"
    );
    assert!(
        xml.contains("android:strokeWidth=\"2\""),
        "stroke width must scale by sqrt(|det|) = 2 in:\n{xml}"
    );
}
