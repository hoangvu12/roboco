//! The Android asset pipeline: SVG line icons → VectorDrawables, tool and
//! file icons → 96x96 PNGs. Library half of the `roboco-android-assets`
//! binary; see `src/main.rs` for the CLI Gradle calls.
//!
//! Rules (android-app#13):
//! - VectorDrawable ink is always opaque `#FF000000`; the caller tints.
//! - Line icons skip `*-mark.svg` (harness brand marks, painted from path
//!   data at runtime) and `roboco-logo.svg` (wraps a PNG).
//! - Rasters are 96x96 RGBA, aspect-fit and centered.
//! - Output is deterministic: the same input tree yields the same bytes.

use std::fmt::Write as _;
use std::path::{Path, PathBuf};

/// The repository root, baked at compile time: this crate is always built
/// from the checkout it converts (integration tests and the CLI both start
/// here). Mirrors the xtask's `repo_root()`.
pub fn repo_root() -> &'static Path {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(Path::parent)
        .expect("crates/android-assets lives two levels below the repo root")
}

use anyhow::{Context, bail};
use usvg::Node;
use tiny_skia;

/// PNG edge length for tool and file icons. 96 px covers a 24 dp glyph at
/// xxxhdpi (4x); file icons render smaller, so this is headroom, not waste.
pub const ICON_SIZE: u32 = 96;

/// What the generator produced, for the CLI's summary line.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct Summary {
    pub vector_drawables: usize,
    pub tool_pngs: usize,
    pub file_icon_pngs: usize,
}

/// Converts one SVG into one VectorDrawable XML document.
///
/// usvg has already resolved inheritance, `currentColor`, transforms and
/// shapes (rect/circle/line/polyline) into absolute paths, so this walks
/// `Path` nodes, applies `abs_transform` and writes `M/L/Q/C/Z` segments.
/// Ink (stroke and fill) becomes opaque `#FF000000`, tinted at use.
pub fn vector_drawable(svg_file: &Path) -> anyhow::Result<String> {
    let source = std::fs::read_to_string(svg_file)
        .with_context(|| format!("reading {}", svg_file.display()))?;
    let tree = parse(svg_file, &source)?;
    vector_drawable_from_tree(&tree, svg_file)
}

fn parse(svg_file: &Path, source: &str) -> anyhow::Result<usvg::Tree> {
    usvg::Tree::from_str(source, &usvg::Options::default())
        .map_err(|error| anyhow::anyhow!("{}: {error}", svg_file.display()))
}

fn vector_drawable_from_tree(tree: &usvg::Tree, svg_file: &Path) -> anyhow::Result<String> {
    let size = tree.size();
    let width = size.width();
    let height = size.height();
    if !(width > 0.0 && height > 0.0) {
        bail!("{}: zero-size SVG", svg_file.display());
    }

    let mut paths = String::new();
    collect_paths(tree.root(), &mut |path: &usvg::Path| {
        // One VectorDrawable path element per SVG path: strokes and fills
        // with the same geometry stay in lockstep with the SVG paint order.
        write_path_element(&mut paths, path).map_err(anyhow::Error::from)
    })
    .map_err(|error| anyhow::anyhow!("{}: {error}", svg_file.display()))?;

    if paths.is_empty() {
        bail!("{}: no drawable paths", svg_file.display());
    }

    let mut xml = String::new();
    let _ = writeln!(
        xml,
        "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\
         \n    android:width=\"{w}dp\"\
         \n    android:height=\"{h}dp\"\
         \n    android:viewportWidth=\"{w}\"\
         \n    android:viewportHeight=\"{h}\">",
        w = num(width),
        h = num(height),
    );
    xml.push_str(&paths);
    xml.push_str("</vector>\n");
    Ok(xml)
}

/// Recursively collects usvg path nodes, refusing anything a VectorDrawable
/// cannot express (text, images, group effects). Failures name the file.
fn collect_paths(group: &usvg::Group, emit: &mut dyn FnMut(&usvg::Path) -> anyhow::Result<()>) -> anyhow::Result<()> {
    if group.opacity().get() < 1.0 {
        bail!("group opacity is not representable in a VectorDrawable");
    }
    if group.clip_path().is_some() || group.mask().is_some() || !group.filters().is_empty() {
        bail!("clip paths, masks and filters are not representable in a VectorDrawable");
    }
    if group.blend_mode() != usvg::BlendMode::Normal {
        bail!("blend modes are not representable in a VectorDrawable");
    }
    for node in group.children() {
        match node {
            Node::Group(child) => collect_paths(child, emit)?,
            Node::Path(path) if path.is_visible() => emit(path)?,
            Node::Path(_) => {}
            Node::Image(_) => bail!("embedded images are not representable in a VectorDrawable"),
            Node::Text(_) => bail!("text is not representable in a VectorDrawable"),
        }
    }
    Ok(())
}

fn write_path_element(out: &mut String, path: &usvg::Path) -> anyhow::Result<()> {
    let transform = path.abs_transform();
    let data = match path.data().clone().transform(transform) {
        Some(data) => data,
        None => return Ok(()), // Degenerate transform: nothing to draw.
    };

    let mut attrs = String::new();
    let fill = path.fill();
    match fill {
        Some(fill) => {
            if !matches!(fill.paint(), usvg::Paint::Color(_)) {
                // Gradients and patterns need the raster pipeline instead.
                bail!("gradient/pattern fills are not representable in a VectorDrawable");
            }
            let alpha = num(fill.opacity().get());
            write!(
                attrs,
                "\n        android:fillColor=\"#FF000000\"\
                 \n        android:fillAlpha=\"{alpha}\""
            )?;
            if fill.rule() == usvg::FillRule::EvenOdd {
                attrs.push_str("\n        android:fillType=\"evenOdd\"");
            }
        }
        None => attrs.push_str("\n        android:fillColor=\"#00000000\""),
    }

    if let Some(stroke) = path.stroke() {
        if !matches!(stroke.paint(), usvg::Paint::Color(_)) {
            bail!("gradient/pattern strokes are not representable in a VectorDrawable");
        }
        let scale = stroke_scale(transform);
        let width = num(stroke.width().get() * scale);
        let alpha = num(stroke.opacity().get());
        let cap = match stroke.linecap() {
            usvg::LineCap::Butt => "butt",
            usvg::LineCap::Round => "round",
            usvg::LineCap::Square => "square",
        };
        let join = match stroke.linejoin() {
            usvg::LineJoin::Miter | usvg::LineJoin::MiterClip => "miter",
            usvg::LineJoin::Round => "round",
            usvg::LineJoin::Bevel => "bevel",
        };
        let miter = num(stroke.miterlimit().get());
        write!(
            attrs,
            "\n        android:strokeColor=\"#FF000000\"\
             \n        android:strokeAlpha=\"{alpha}\"\
             \n        android:strokeWidth=\"{width}\"\
             \n        android:strokeLineCap=\"{cap}\"\
             \n        android:strokeLineJoin=\"{join}\"\
             \n        android:strokeMiterLimit=\"{miter}\""
        )?;
    }

    let _ = writeln!(
        out,
        "    <path\
         \n        android:pathData=\"{}\"{} />",
        path_data(&data),
        attrs
    );
    Ok(())
}

/// `M/L/Q/C/Z` in absolute coordinates, with compact number formatting.
fn path_data(path: &tiny_skia::Path) -> String {
    let mut out = String::new();
    for segment in path.segments() {
        match segment {
            tiny_skia::PathSegment::MoveTo(p) => {
                let _ = write!(out, "M{},{} ", num(p.x), num(p.y));
            }
            tiny_skia::PathSegment::LineTo(p) => {
                let _ = write!(out, "L{},{} ", num(p.x), num(p.y));
            }
            tiny_skia::PathSegment::QuadTo(c, p) => {
                let _ = write!(out, "Q{},{} {},{} ", num(c.x), num(c.y), num(p.x), num(p.y));
            }
            tiny_skia::PathSegment::CubicTo(c1, c2, p) => {
                let _ = write!(
                    out,
                    "C{},{} {},{} {},{} ",
                    num(c1.x), num(c1.y), num(c2.x), num(c2.y), num(p.x), num(p.y)
                );
            }
            tiny_skia::PathSegment::Close => out.push('Z'),
        }
    }
    while out.ends_with(' ') {
        out.pop();
    }
    out
}

/// A number a VectorDrawable parser accepts: up to 3 decimals, no trailing
/// zeros, no `-0`.
fn num(value: f32) -> String {
    let mut text = format!("{value:.3}");
    while text.ends_with('0') {
        text.pop();
    }
    if text.ends_with('.') {
        text.pop();
    }
    if text == "-0" {
        text = "0".to_owned();
    }
    text
}

/// Stroke widths scale with the path's transform; the uniform factor is
/// `sqrt(|det|)`.
fn stroke_scale(transform: tiny_skia::Transform) -> f32 {
    let det = transform.sx * transform.sy - transform.kx * transform.ky;
    det.abs().sqrt()
}

/// Rasterizes one SVG into a square `size`x`size` RGBA PNG, aspect-fit and
/// centered (full colour, transparent background).
pub fn raster_png(svg_file: &Path, size: u32) -> anyhow::Result<Vec<u8>> {
    let source = std::fs::read_to_string(svg_file)
        .with_context(|| format!("reading {}", svg_file.display()))?;
    let tree = parse(svg_file, &source)?;
    raster_png_from_tree(&tree, size)
}

fn raster_png_from_tree(tree: &usvg::Tree, size: u32) -> anyhow::Result<Vec<u8>> {
    let width = tree.size().width();
    let height = tree.size().height();
    if !(width > 0.0 && height > 0.0) {
        bail!("cannot rasterize a zero-size SVG");
    }
    let target = size as f32;
    let scale = target / width.max(height);
    let dx = (target - width * scale) / 2.0;
    let dy = (target - height * scale) / 2.0;
    let transform =
        tiny_skia::Transform::from_scale(scale, scale).post_translate(dx, dy);

    let mut pixmap = tiny_skia::Pixmap::new(size, size).context("allocating pixmap")?;
    let mut canvas = pixmap.as_mut();
    resvg::render(&tree, transform, &mut canvas);
    Ok(pixmap.encode_png().context("encoding PNG")?)
}

/// The generated project layout, mirroring `generate`'s fixed inputs.
mod layout {
    use std::path::{Path, PathBuf};

    pub fn line_icons(repo: &Path) -> PathBuf {
        repo.join("crates/ui/assets/icons")
    }

    pub fn extra_icons(repo: &Path) -> PathBuf {
        repo.join("apps/android/icons")
    }

    pub fn dark_file_icons(repo: &Path) -> PathBuf {
        repo.join("apps/android/icons/file-icons-dark")
    }

    pub fn light_file_icons(repo: &Path) -> PathBuf {
        repo.join("crates/ui/assets/file-icons")
    }
}

/// `some-icon-name.svg` → `zi_some_icon_name` (Android resource names must
/// start with a letter and contain only [a-z0-9_]).
pub fn snake_name(stem: &str) -> String {
    let mut name = String::with_capacity(stem.len() + 3);
    name.push_str("zi_");
    for character in stem.chars() {
        if character.is_ascii_uppercase() {
            name.push(character.to_ascii_lowercase());
        } else if character == '-' || character == '.' {
            name.push('_');
        } else {
            name.push(character);
        }
    }
    name
}

/// Line icons that are painted from path data at runtime, never converted.
fn is_skipped_line_icon(file_name: &str) -> bool {
    file_name.ends_with("-mark.svg") || file_name == "roboco-logo.svg"
}

/// Runs the whole pipeline into `res_out`/`assets_out`, which are wiped
/// first so stale files cannot linger. Deterministic: the same input tree
/// produces byte-identical output.
pub fn generate(repo: &Path, res_out: &Path, assets_out: &Path) -> anyhow::Result<Summary> {
    let drawable_dir = res_out.join("drawable");
    let icons_dir = assets_out.join("icons");
    reset_dir(&drawable_dir)?;
    reset_dir(&icons_dir)?;

    let mut summary = Summary::default();

    // 1. VectorDrawables from the line icons (skip marks and the logo).
    for file in sorted_svgs(&layout::line_icons(repo))? {
        if is_skipped_line_icon(&file.name) {
            continue;
        }
        let out = drawable_dir.join(format!("{}.xml", snake_name(&file.stem)));
        convert_vector_drawable(&file.path, &out)
            .with_context(|| format!("converting {}", file.path.display()))?;
        summary.vector_drawables += 1;
    }

    // 2. VectorDrawables from the extra icons (tab glyphs, the Roboco
    //    bow): no raster output — the app tints these at use.
    for file in sorted_svgs(&layout::extra_icons(repo))? {
        let out = drawable_dir.join(format!("{}.xml", snake_name(&file.stem)));
        convert_vector_drawable(&file.path, &out)
            .with_context(|| format!("converting {}", file.path.display()))?;
        summary.vector_drawables += 1;
    }

    // 3. Tool PNGs: every line icon (not marks, not the logo), black ink.
    for file in sorted_svgs(&layout::line_icons(repo))? {
        if is_skipped_line_icon(&file.name) {
            continue;
        }
        let out = icons_dir.join(format!("tool-{}.png", file.stem));
        raster_file(&file.path, &out)
            .with_context(|| format!("rasterizing {}", file.path.display()))?;
        summary.tool_pngs += 1;
    }

    // 4. File icons: the light variants from the shared assets, the dark
    //    variants from the imported iOS set. Output names keep the
    //    `fileicon-…` stem, so `-dark` selects itself in dark mode.
    let light_root = layout::light_file_icons(repo);
    for kind in ["files", "folders"] {
        for file in sorted_svgs(&light_root.join(kind))? {
            let out = icons_dir.join(format!("fileicon-{kind}-{}.png", file.stem));
            raster_file(&file.path, &out)
                .with_context(|| format!("rasterizing {}", file.path.display()))?;
            summary.file_icon_pngs += 1;
        }
    }
    for file in sorted_svgs(&layout::dark_file_icons(repo))? {
        let out = icons_dir.join(format!("{}.png", file.stem));
        raster_file(&file.path, &out)
            .with_context(|| format!("rasterizing {}", file.path.display()))?;
        summary.file_icon_pngs += 1;
    }

    Ok(summary)
}

struct SourceFile {
    path: PathBuf,
    name: String,
    stem: String,
}

/// `*.svg` in a directory, sorted by name so output order (and thus any
/// future zip/manifest bytes) is stable across platforms.
fn sorted_svgs(dir: &Path) -> anyhow::Result<Vec<SourceFile>> {
    if !dir.is_dir() {
        bail!("{} does not exist (is the checkout complete?)", dir.display());
    }
    let mut files: Vec<PathBuf> = std::fs::read_dir(dir)
        .with_context(|| format!("reading {}", dir.display()))?
        .filter_map(|entry| entry.ok().map(|entry| entry.path()))
        .filter(|path| path.is_file() && path.extension().is_some_and(|ext| ext == "svg"))
        .collect();
    files.sort();
    Ok(files
        .into_iter()
        .map(|path| {
            let name = path
                .file_name()
                .expect("read_dir entries have names")
                .to_string_lossy()
                .into_owned();
            let stem = name.trim_end_matches(".svg").to_owned();
            SourceFile { path, name, stem }
        })
        .collect())
}

fn convert_vector_drawable(source: &Path, out: &Path) -> anyhow::Result<()> {
    let xml = vector_drawable(source)?;
    std::fs::write(out, xml).with_context(|| format!("writing {}", out.display()))
}

fn raster_file(source: &Path, out: &Path) -> anyhow::Result<()> {
    let png = raster_png(source, ICON_SIZE)?;
    std::fs::write(out, png).with_context(|| format!("writing {}", out.display()))
}

fn reset_dir(dir: &Path) -> anyhow::Result<()> {
    if dir.exists() {
        std::fs::remove_dir_all(dir).with_context(|| format!("clearing {}", dir.display()))?;
    }
    std::fs::create_dir_all(dir).with_context(|| format!("creating {}", dir.display()))
}
