//! Pairing-link QR rendering — the one encoder every surface shares.
//!
//! Roboco has no cloud and no account: the engine pairs clients itself with
//! single-use links (CONTEXT.md), and the link surfaces (desktop Settings →
//! Remote access, web, headless startup, the CLI) all want the same QR for
//! that link. Encoding lives here so a change to level or layout lands once.
//!
//! Rendered properties are scanner requirements, not theme choices: error
//! correction M, a 4-module quiet zone, and black on white.

use qrcode::{EcLevel, QrCode, render::svg, render::unicode};

/// Encode at error-correction level M: a pairing URL is ~80–150 bytes
/// (version 6–8), which scans easily from a laptop screen and still heals
/// the odd damaged module. Panics only for inputs beyond QR capacity
/// (~2 KB at M) — pairing links are engine-minted and bounded far below
/// that, so the surfaces can treat this as infallible.
fn encode(text: &str) -> QrCode {
    QrCode::with_error_correction_level(text, EcLevel::M)
        .unwrap_or_else(|error| panic!("QR encode of {} bytes failed: {error}", text.len()))
}

/// A compact module-unit SVG: `shape-rendering="crispEdges"`, a 4-module
/// quiet zone, black modules on a white ground. Never theme the colours —
/// scanners need dark on light.
pub fn qr_svg(text: &str) -> String {
    encode(text)
        .render::<svg::Color>()
        .module_dimensions(1, 1)
        .build()
}

/// Unicode half-block rendering for terminals (one glyph per two modules).
/// Print it with `println!`; it already ends without a trailing newline.
pub fn qr_terminal(text: &str) -> String {
    encode(text).render::<unicode::Dense1x2>().build()
}

#[cfg(test)]
mod tests {
    use super::*;

    const URL: &str = "http://192.168.1.10:27655/pair#token=6S7FneJLTGEoQRtEwMWFVw8CwsVXtLyAioSpQRh8Y5D";

    /// Parse the module matrix (quiet zone included) back out of the compact
    /// SVG `qr_svg` emits: viewBox size plus one dark module per path segment.
    fn svg_modules(svg: &str) -> (usize, Vec<Vec<bool>>) {
        let viewBox = "\" viewBox=\"0 0 ";
        let start = svg.find(viewBox).expect("viewBox") + viewBox.len();
        let end = start + svg[start..].find(' ').expect("viewBox height");
        let size: usize = svg[start..end].parse().expect("module count");
        let d = svg.find("\" d=\"").expect("path data") + "\" d=\"".len();
        let d_end = svg[d..].find("\"/>").expect("path ends") + d;
        let mut modules = vec![vec![false; size]; size];
        for segment in svg[d..d_end].split('M').filter(|s| !s.is_empty()) {
            let mut coords = segment.split('h').next().expect("h run").split(' ');
            let x: usize = coords.next().expect("x").parse().expect("x number");
            let y: usize = coords.next().expect("y").parse().expect("y number");
            modules[y][x] = true;
        }
        (size, modules)
    }

    #[test]
    fn pairing_url_round_trips_through_qr_svg() {
        let (size, modules) = svg_modules(&qr_svg(URL));
        // Render the module matrix to a grayscale image and decode it, the
        // way a phone camera would.
        let mut image = image::GrayImage::new(size as u32, size as u32);
        for (x, y, pixel) in image.enumerate_pixels_mut() {
            let (x, y) = (x as usize, y as usize);
            *pixel = image::Luma([if modules[y][x] { 0 } else { 255 }]);
        }
        let mut prepared = rqrr::PreparedImage::prepare(image);
        let grids = prepared.detect_grids();
        assert_eq!(grids.len(), 1, "exactly one QR code in the SVG");
        let (meta, content) = grids[0].decode().expect("decodes");
        assert_eq!(content, URL);
        // rqrr reports the format-info level: 0 = M, 1 = L, 2 = H, 3 = Q.
        assert_eq!(meta.ecc_level, 0, "error correction level M");
    }

    #[test]
    fn qr_svg_keeps_a_four_module_quiet_zone() {
        let (size, modules) = svg_modules(&qr_svg(URL));
        let quiet = 4;
        for (y, row) in modules.iter().enumerate() {
            for (x, dark) in row.iter().enumerate() {
                let border = y < quiet || size - quiet <= y || x < quiet || size - quiet <= x;
                if border {
                    assert!(!dark, "quiet zone module at ({x},{y}) must be light");
                }
            }
        }
        // And the code itself must actually be there.
        assert!(modules[quiet..size - quiet]
            .iter()
            .any(|row| row[quiet..size - quiet].iter().any(|dark| *dark)));
    }

    #[test]
    fn qr_terminal_uses_only_half_block_characters() {
        let terminal = qr_terminal(URL);
        assert!(terminal.lines().count() > 8, "a terminal block per two modules");
        assert!(
            terminal
                .chars()
                .all(|ch| matches!(ch, '█' | '▀' | '▄' | ' ' | '\n' | '\r')),
            "only half-block glyphs and blanks: {terminal:?}"
        );
    }

    #[test]
    fn qr_svg_is_crisp_and_module_sized() {
        let svg = qr_svg(URL);
        assert!(svg.contains("shape-rendering=\"crispEdges\""));
        // Module-unit geometry: width, height and viewBox all equal the module
        // grid, so every module lands on an integer boundary however the host
        // scales the SVG.
        let (size, _) = svg_modules(&svg);
        let dims = format!("width=\"{size}\" height=\"{size}\" viewBox=\"0 0 {size} {size}\"");
        assert!(svg.contains(&dims), "square module-unit SVG: {dims}");
    }
}
