use std::fs;
use std::path::PathBuf;

use anyhow::{Context as _, Result, bail};
use clap::Parser;
use roboco_theme::artifact::render_files;

/// Export the web theme artifact (resolved builtin variants, accent
/// derivations, layout constants, motion catalog) into
/// `web/packages/theme/src/generated/`.
#[derive(Debug, Parser)]
struct Args {
    /// Directory to write the generated files into.
    #[arg(long, default_value = default_out_dir())]
    out: PathBuf,
    /// Regenerate in memory and fail if the checked-in artifact differs —
    /// the CI freshness gate.
    #[arg(long)]
    check: bool,
}

fn default_out_dir() -> &'static str {
    concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../web/packages/theme/src/generated"
    )
}

fn main() -> Result<()> {
    let args = Args::parse();
    let expected = render_files();

    if args.check {
        let mut stale = Vec::new();
        for (name, content) in &expected {
            let path = args.out.join(name);
            match fs::read_to_string(&path) {
                Ok(existing) if existing == *content => {}
                Ok(_) => stale.push(format!("{name} differs")),
                Err(_) => stale.push(format!("{name} is missing")),
            }
        }
        if !stale.is_empty() {
            bail!(
                "theme artifact is stale ({}); regenerate with \
                 `cargo run -p roboco-theme --bin roboco-theme-export`",
                stale.join(", ")
            );
        }
        println!(
            "theme artifact is fresh ({} files in {})",
            expected.len(),
            args.out.display()
        );
        return Ok(());
    }

    fs::create_dir_all(&args.out)
        .with_context(|| format!("could not create {}", args.out.display()))?;
    for (name, content) in &expected {
        let path = args.out.join(name);
        fs::write(&path, content).with_context(|| format!("could not write {}", path.display()))?;
    }
    println!(
        "wrote {} generated files to {}",
        expected.len(),
        args.out.display()
    );
    Ok(())
}
