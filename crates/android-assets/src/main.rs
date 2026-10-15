//! `roboco-android-assets` — the Android asset pipeline CLI (android-app#13).
//!
//! Called by Gradle (no bash, no Python) as:
//!
//! ```text
//! cargo run --locked -p roboco-android-assets --release -- \
//!     --repo <repo root> --res-out <dir> --assets-out <dir>
//! ```
//!
//! Everything meaningful lives in the library so the integration tests
//! exercise the same code paths.

use std::path::PathBuf;

use anyhow::Context;

fn main() -> anyhow::Result<()> {
    let mut repo: Option<PathBuf> = None;
    let mut res_out: Option<PathBuf> = None;
    let mut assets_out: Option<PathBuf> = None;
    let mut args = std::env::args().skip(1);
    while let Some(flag) = args.next() {
        let value = args
            .next()
            .with_context(|| format!("{flag} needs a value"))?;
        let value = PathBuf::from(value);
        match flag.as_str() {
            "--repo" => repo = Some(value),
            "--res-out" => res_out = Some(value),
            "--assets-out" => assets_out = Some(value),
            other => anyhow::bail!("unknown flag {other:?}"),
        }
    }
    let repo = repo
        .unwrap_or_else(|| std::env::current_dir().expect("current directory"));

    let summary = roboco_android_assets::generate(
        &repo,
        &res_out.context("--res-out is required")?,
        &assets_out.context("--assets-out is required")?,
    )?;
    println!(
        "{} vector drawables, {} tool PNGs, {} file-icon PNGs",
        summary.vector_drawables, summary.tool_pngs, summary.file_icon_pngs
    );
    Ok(())
}
