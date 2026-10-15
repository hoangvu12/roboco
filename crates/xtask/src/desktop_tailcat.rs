//! `cargo xtask tailcat-adapter` — the desktop Tailcat adapter for source
//! builds.
//!
//! A release `roboco.exe` embeds `roboco-tailcat` (`ROBOCO_TAILCAT_EMBED`,
//! crates/engine/build.rs); a `cargo run` build embeds nothing and looks
//! beside its own executable. This builds the pinned adapter for the host
//! into `<target dir>/<debug|release>/`, where that lookup finds it.
//!
//! ```text
//! cargo xtask tailcat-adapter [--release]
//! ```

use std::path::{Path, PathBuf};

use anyhow::bail;

const USAGE: &str = "usage: cargo xtask tailcat-adapter [--release]";

pub(crate) fn run(argv: &[String]) -> anyhow::Result<()> {
    let profile = parse_profile(argv)?;
    let root = crate::repo_root();
    let target = std::env::var_os("CARGO_TARGET_DIR")
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .map(|dir| {
            if dir.is_absolute() {
                dir
            } else {
                root.join(dir)
            }
        })
        .unwrap_or_else(|| root.join("target"));
    let output = output_path(&target, profile, std::env::consts::EXE_SUFFIX);
    std::fs::create_dir_all(output.parent().expect("the output has a profile directory"))?;
    let go =
        crate::ndk::find_in_path(std::env::var_os("PATH").as_deref(), "go").ok_or_else(|| {
            anyhow::anyhow!(
                "go is missing — install Go (the version in adapters/roboco-tailcat/go.mod; \
                 GOTOOLCHAIN=auto fetches it)"
            )
        })?;
    let output_arg = output.to_string_lossy();
    crate::run(
        &crate::tailcat::adapter_dir(root),
        &go.to_string_lossy(),
        &[
            "build",
            "-mod=readonly",
            "-trimpath",
            "-o",
            &output_arg,
            "./cmd/roboco-tailcat",
        ],
        &[("CGO_ENABLED", "0")],
    )?;
    println!("tailcat adapter: {}", output.display());
    Ok(())
}

fn parse_profile(argv: &[String]) -> anyhow::Result<&'static str> {
    match argv {
        [] => Ok("debug"),
        [flag] if flag == "--release" => Ok("release"),
        [flag] if flag == "--help" || flag == "-h" => bail!("{USAGE}"),
        _ => bail!("unexpected arguments {argv:?}\n{USAGE}"),
    }
}

/// Beside the `roboco` executable cargo builds for `profile`.
fn output_path(target: &Path, profile: &str, exe_suffix: &str) -> PathBuf {
    target
        .join(profile)
        .join(format!("roboco-tailcat{exe_suffix}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builds_beside_the_profiles_roboco_executable() {
        assert_eq!(parse_profile(&[]).unwrap(), "debug");
        assert_eq!(parse_profile(&["--release".to_owned()]).unwrap(), "release");
        assert!(parse_profile(&["--profile".to_owned()]).is_err());
        assert_eq!(
            output_path(Path::new("target"), "debug", ".exe"),
            Path::new("target").join("debug").join("roboco-tailcat.exe")
        );
        assert_eq!(
            output_path(Path::new("target"), "release", ""),
            Path::new("target").join("release").join("roboco-tailcat")
        );
    }
}
