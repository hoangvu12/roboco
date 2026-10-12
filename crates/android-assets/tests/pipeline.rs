//! The whole-pipeline contract (android-app#13): which SVGs convert to
//! which outputs, which are skipped, and that two runs produce
//! byte-identical files.

use std::path::{Path, PathBuf};

use roboco_android_assets::generate;

fn repo_root() -> &'static Path {
    roboco_android_assets::repo_root()
}

/// The skip rule restated independently of the implementation: this test
/// counts what should convert, so a bug in the rule fails here too.
fn line_icon_count() -> usize {
    let dir = repo_root().join("crates/ui/assets/icons");
    let mut n = 0;
    for entry in std::fs::read_dir(&dir).unwrap() {
        let name = entry.unwrap().file_name().to_string_lossy().into_owned();
        if name.ends_with(".svg") && !name.ends_with("-mark.svg") && name != "roboco-logo.svg" {
            n += 1;
        }
    }
    n
}

fn light_file_icon_count() -> usize {
    let mut n = 0;
    for kind in ["files", "folders"] {
        let dir = repo_root().join("crates/ui/assets/file-icons").join(kind);
        for entry in std::fs::read_dir(&dir).unwrap() {
            if entry.unwrap().path().extension().is_some_and(|e| e == "svg") {
                n += 1;
            }
        }
    }
    n
}

fn extra_icon_count() -> usize {
    // The tab glyphs and the Roboco glyph, directly under apps/android/icons.
    let dir = repo_root().join("apps/android/icons");
    std::fs::read_dir(&dir)
        .unwrap()
        .filter(|entry| {
            entry
                .as_ref()
                .unwrap()
                .path()
                .extension()
                .is_some_and(|e| e == "svg")
        })
        .count()
}

fn snapshot(dir: &Path) -> Vec<(String, Vec<u8>)> {
    let mut files = Vec::new();
    fn walk(dir: &Path, files: &mut Vec<(String, Vec<u8>)>) {
        let mut entries: Vec<PathBuf> =
            std::fs::read_dir(dir).unwrap().map(|e| e.unwrap().path()).collect();
        entries.sort();
        for path in entries {
            if path.is_dir() {
                walk(&path, files);
            } else {
                let name = path
                    .strip_prefix(path.ancestors().nth(2).unwrap())
                    .unwrap()
                    .to_string_lossy()
                    .replace('\\', "/");
                files.push((name, std::fs::read(&path).unwrap()));
            }
        }
    }
    walk(dir, &mut files);
    files
}

#[test]
fn names_skip_rules_and_counts() {
    let out = tempfile::tempdir().unwrap();
    let res_out = out.path().join("res");
    let assets_out = out.path().join("assets");
    let summary = generate(repo_root(), &res_out, &assets_out).unwrap();

    let lines = line_icon_count();
    let extras = extra_icon_count();
    let light = light_file_icon_count();
    let dark = std::fs::read_dir(repo_root().join("apps/android/icons/file-icons-dark"))
        .unwrap()
        .count();

    assert_eq!(summary.vector_drawables, lines + extras);
    assert_eq!(summary.tool_pngs, lines);
    assert_eq!(summary.file_icon_pngs, light + dark);

    // The acceptance-critical outputs exist with the right names.
    for expected in [
        "res/drawable/zi_tab_chat.xml",
        "res/drawable/zi_tab_settings.xml",
        "res/drawable/zi_plus.xml",
        "res/drawable/zi_roboco_glyph.xml",
        "assets/icons/tool-terminal.png",
        "assets/icons/tool-bot.png",
        "assets/icons/fileicon-files-rust.png",
        "assets/icons/fileicon-folders-folder-src.png",
        "assets/icons/fileicon-files-angular-directive-dark.png",
        "assets/icons/fileicon-folders-folder-actions-dark.png",
    ] {
        let path = out.path().join(expected.replace('/', "\\"));
        assert!(path.is_file(), "missing {}", expected);
    }

    // Brand marks and the logo produce nothing, in either pipeline. (A
    // *file* icon legitimately named `julia-markdown` is not a brand mark.)
    for name in snapshot(&res_out).iter().map(|(name, _)| name) {
        assert!(!name.ends_with("_mark.xml"), "{name} must not exist");
        assert!(!name.contains("roboco_logo"), "{name} must not exist");
    }
    for name in snapshot(&assets_out).iter().map(|(name, _)| name) {
        assert!(
            !(name.starts_with("icons/tool-") && name.ends_with("-mark.png")),
            "{name} must not exist"
        );
        assert!(!name.contains("roboco_logo"), "{name} must not exist");
    }
    // nuxt.png (a raster) is not an input, so it has no PNG output beyond
    // the SVG-derived one; the files dir's PNG is simply not globbed.
    assert!(
        assets_out.join("icons").join("fileicon-files-nuxt.png").is_file(),
        "nuxt.svg should rasterize like any other file icon"
    );
}

#[test]
fn two_runs_are_byte_identical() {
    let first = tempfile::tempdir().unwrap();
    let second = tempfile::tempdir().unwrap();
    generate(repo_root(), &first.path().join("res"), &first.path().join("assets")).unwrap();
    generate(repo_root(), &second.path().join("res"), &second.path().join("assets")).unwrap();

    let left = snapshot(first.path());
    let right = snapshot(second.path());
    assert_eq!(left.len(), right.len(), "different file counts");
    for ((name_l, bytes_l), (name_r, bytes_r)) in left.into_iter().zip(right) {
        assert_eq!(name_l, name_r, "different file sets");
        assert_eq!(bytes_l, bytes_r, "{name_l} differs between runs");
    }
}

#[test]
fn reruns_after_stale_files_stay_clean() {
    // A leftover file in the output must not survive the next run: the
    // generator wipes its output trees first.
    let out = tempfile::tempdir().unwrap();
    generate(repo_root(), &out.path().join("res"), &out.path().join("assets")).unwrap();
    let stale = out.path().join("res/drawable/zi_stale_icon.xml");
    std::fs::write(&stale, "<vector/>").unwrap();
    generate(repo_root(), &out.path().join("res"), &out.path().join("assets")).unwrap();
    assert!(!stale.exists(), "stale output survived the rerun");
}
