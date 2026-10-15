//! Stage the web client bundle into `web-staging/` for rust-embed to bake
//! into the engine binary. The default source is the Vite production build
//! at `web/packages/app/dist/`; `ROBOCO_WEB_DIST` overrides that for
//! vendored bundles or custom layouts.
//!
//! Behaviour by profile:
//! - source has files: copy them into `web-staging/`. rust-embed embeds
//!   that tree in release, reads it from disk in debug.
//! - source is empty/missing in release: panic. A release binary must
//!   never ship without the React app.
//! - source is empty/missing in debug: fall back to the hand-written
//!   placeholder pages under `assets/web/` so `cargo test` keeps working
//!   on a clean checkout. The placeholder is ticket 01's safety net;
//!   ticket 17 turns it into an explicit debug-only fallback.

use std::{
    env, fs,
    path::{Path, PathBuf},
};

fn main() {
    embed_tailcat_adapter();
    println!("cargo:rerun-if-env-changed=ROBOCO_WEB_DIST");

    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let source = env::var_os("ROBOCO_WEB_DIST")
        .map(PathBuf::from)
        .unwrap_or_else(|| manifest_dir.join("../../web/packages/app/dist"));
    let fallback = manifest_dir.join("assets/web");
    let staged = manifest_dir.join("web-staging");

    println!("cargo:rerun-if-changed={}", source.display());
    println!("cargo:rerun-if-changed={}", fallback.display());

    // Wipe any prior staging so removed files don't linger.
    let _ = fs::remove_dir_all(&staged);
    fs::create_dir_all(&staged).expect("could not create crates/engine/web-staging/");

    let profile = env::var("PROFILE").unwrap_or_default();
    let source_has_files = source.is_dir()
        && fs::read_dir(&source)
            .map(|mut it| it.next().is_some())
            .unwrap_or(false);

    let (copied_from, count) = if source_has_files {
        let n = copy_tree(&source, &staged).expect("could not stage web bundle");
        (source.clone(), n)
    } else if profile != "release" && fallback.is_dir() {
        let n = copy_tree(&fallback, &staged).expect("could not stage placeholder web pages");
        (fallback.clone(), n)
    } else {
        panic!(
            "the web bundle at {} is missing or empty — run \
             `pnpm --filter @roboco/app build` first, or set \
             ROBOCO_WEB_DIST to a built bundle directory",
            source.display()
        );
    };

    println!(
        "cargo:warning=roboco engine: staged {} entries from {} into {}",
        count,
        copied_from.display(),
        staged.display()
    );
}

/// `ROBOCO_TAILCAT_EMBED=<absolute path>` bakes that `roboco-tailcat` build
/// into the engine (`src/tailcat.rs` extracts it on first use). Windows
/// release packaging sets it: in-app updates replace only `roboco.exe`, so a
/// companion file beside it went missing or stale. Unset, nothing is embedded
/// and the adapter is looked up beside the executable as before.
fn embed_tailcat_adapter() {
    println!("cargo:rerun-if-env-changed=ROBOCO_TAILCAT_EMBED");
    println!("cargo:rustc-check-cfg=cfg(roboco_tailcat_embedded)");
    let Some(adapter) = env::var_os("ROBOCO_TAILCAT_EMBED").filter(|value| !value.is_empty())
    else {
        return;
    };
    let adapter = PathBuf::from(adapter);
    assert!(
        adapter.is_absolute() && adapter.is_file(),
        "ROBOCO_TAILCAT_EMBED must be the absolute path of a built roboco-tailcat, not {}",
        adapter.display()
    );
    println!("cargo:rerun-if-changed={}", adapter.display());
    println!(
        "cargo:rustc-env=ROBOCO_TAILCAT_EMBED_FILE={}",
        adapter.display()
    );
    println!("cargo:rustc-cfg=roboco_tailcat_embedded");
}

fn copy_tree(src: &Path, dst: &Path) -> std::io::Result<usize> {
    let mut count = 0;
    fs::create_dir_all(dst)?;
    for entry in fs::read_dir(src)? {
        let entry = entry?;
        let file_type = entry.file_type()?;
        let target = dst.join(entry.file_name());
        if file_type.is_dir() {
            count += copy_tree(&entry.path(), &target)?;
        } else {
            fs::copy(&entry.path(), &target)?;
            count += 1;
        }
    }
    Ok(count)
}
