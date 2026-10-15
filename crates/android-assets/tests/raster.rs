//! Rasterization of the file icons (android-app#13): full-colour 96x96
//! RGBA PNGs, aspect-fit and centered, gradients included (that is exactly
//! why the light/dark file icons rasterize instead of becoming
//! VectorDrawables).

use std::path::Path;

fn repo_root() -> &'static Path {
    roboco_android_assets::repo_root()
}

/// Minimal PNG IHDR reader: (width, height, bit depth, color type).
fn png_header(bytes: &[u8]) -> (u32, u32, u8, u8) {
    // PNG magic (8) + IHDR chunk header (4 len + 4 type).
    assert!(bytes.starts_with(&[0x89, b'P', b'N', b'G']), "not a PNG");
    assert_eq!(&bytes[12..16], b"IHDR", "first chunk is not IHDR");
    let width = u32::from_be_bytes(bytes[16..20].try_into().unwrap());
    let height = u32::from_be_bytes(bytes[20..24].try_into().unwrap());
    (width, height, bytes[24], bytes[25])
}

#[test]
fn gradient_file_icon_rasterizes_opaque_at_96() {
    // `next.svg` uses linearGradients: it needs a real rasterizer, which is
    // the point of this pipeline.
    let svg = repo_root()
        .join("crates/ui/assets/file-icons")
        .join("files/next.svg");
    let png = roboco_android_assets::raster_png(&svg, 96).unwrap();

    let (width, height, depth, color) = png_header(&png);
    assert_eq!((width, height), (96, 96));
    assert_eq!(depth, 8, "8-bit RGBA expected");
    assert_eq!(color, 6, "truecolour with alpha expected");

    let file = tempfile::tempdir().unwrap();
    let path = file.path().join("out.png");
    std::fs::write(&path, &png).unwrap();
    let rasterized = tiny_skia::Pixmap::load_png(&path).expect("decoding our own PNG");
    let opaque = rasterized.pixels().iter().filter(|p| p.alpha() > 0).count();
    assert!(
        opaque > 96 * 96 / 100,
        "gradient raster drew no ink: {opaque} opaque pixels"
    );
}

#[test]
fn wide_artwork_is_aspect_fit_and_centered() {
    // 32x16 artwork in a 96x96 canvas: the height fills, the width centers
    // with transparent side bands.
    let dir = tempfile::tempdir().unwrap();
    let svg = dir.path().join("wide.svg");
    std::fs::write(
        &svg,
        concat!(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 32 16\" fill=\"#FF0000\">",
            "<rect x=\"0\" y=\"0\" width=\"32\" height=\"16\"/>",
            "</svg>",
        ),
    )
    .unwrap();
    let png = roboco_android_assets::raster_png(&svg, 96).unwrap();
    let path = dir.path().join("out.png");
    std::fs::write(&path, &png).unwrap();
    let rasterized = tiny_skia::Pixmap::load_png(&path).unwrap();

    // Aspect-fit fills the larger dimension (the 32-wide side), so the
    // vertical bands are transparent and the middle carries ink.
    assert_eq!(rasterized.pixel(48, 0).unwrap().alpha(), 0, "top band");
    assert_eq!(rasterized.pixel(48, 95).unwrap().alpha(), 0, "bottom band");
    assert_eq!(rasterized.pixel(48, 48).unwrap().alpha(), 255, "center");
    assert_eq!(rasterized.pixel(0, 48).unwrap().alpha(), 255, "left edge");
}
