//! Command-line parsing for `cargo xtask android-core`.
//!
//! Hand-rolled on purpose: the xtask stays std-only plus `anyhow` (no clap),
//! and the surface is one subcommand with a handful of flags — the same on
//! Windows and Linux, since Gradle's plain `Exec` task and humans both call
//! it (android-app#10).

use std::path::{Path, PathBuf};

/// The ABIs this ticket ships. armeabi-v7a / x86 are out of scope
/// (android-app#10); `--abis` validates against this list so a request for
/// an unsupported ABI fails up front with a clear message instead of
/// producing an untested artifact.
pub const SUPPORTED_ABIS: &[&str] = &["arm64-v8a", "x86_64"];
pub const DEFAULT_ABIS: &[&str] = &["arm64-v8a", "x86_64"];

pub const USAGE: &str = "usage: cargo xtask android-core [--out DIR]
                         [--abis arm64-v8a,x86_64] [--profile mobile|mobile-dist]
                         [--languages mobile|all] [--skip-host] [--skip-ndk]
                         [--skip-tailcat] [--check]";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Profile {
    /// Dev loop: unstripped, no LTO, incremental.
    Mobile,
    /// Play / GitHub release APKs: thin LTO, debuginfo stripped.
    MobileDist,
}

impl Profile {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Mobile => "mobile",
            Self::MobileDist => "mobile-dist",
        }
    }

    fn parse(value: &str) -> anyhow::Result<Self> {
        match value {
            "mobile" => Ok(Self::Mobile),
            "mobile-dist" => Ok(Self::MobileDist),
            _ => anyhow::bail!("unknown profile {value:?} (expected mobile or mobile-dist)"),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Languages {
    /// The middle grammar set (Kotlin, Swift, C++ kept; ~18.9 MB per ABI).
    Mobile,
    /// Every grammar (~30 MB per ABI, desktop-identical).
    All,
}

impl Languages {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Mobile => "mobile",
            Self::All => "all",
        }
    }

    /// The `roboco-mobile` feature name the cargo build passes.
    pub fn feature(self) -> &'static str {
        match self {
            Self::Mobile => "mobile-languages",
            Self::All => "all-languages",
        }
    }

    fn parse(value: &str) -> anyhow::Result<Self> {
        match value {
            "mobile" => Ok(Self::Mobile),
            "all" => Ok(Self::All),
            _ => anyhow::bail!("unknown languages {value:?} (expected mobile or all)"),
        }
    }
}

#[derive(Debug, PartialEq, Eq)]
pub struct AndroidCoreArgs {
    pub out: PathBuf,
    pub abis: Vec<String>,
    pub profile: Profile,
    pub languages: Languages,
    pub skip_host: bool,
    pub skip_ndk: bool,
    /// Build without `libroboco_tailcat.so` (android-next#02). Also set by
    /// `ROBOCO_ANDROID_TAILCAT=0`, which the caller folds in.
    pub skip_tailcat: bool,
    pub check: bool,
}

/// Parses the flags after `android-core` against the given defaults.
pub fn parse(
    argv: &[String],
    default_out: &Path,
    default_abis: Vec<String>,
) -> anyhow::Result<AndroidCoreArgs> {
    let mut out: Option<PathBuf> = None;
    let mut abis: Option<Vec<String>> = None;
    let mut profile = Profile::Mobile;
    let mut languages = Languages::Mobile;
    let mut skip_host = false;
    let mut skip_ndk = false;
    let mut skip_tailcat = false;
    let mut check = false;

    let mut index = 0;
    while index < argv.len() {
        let arg = argv[index].as_str();
        // `--flag value` and `--flag=value` both parse; Gradle's
        // commandLine arrays use the spaced form.
        let (flag, inline) = match arg.split_once('=') {
            Some((flag, value)) => (flag, Some(value.to_owned())),
            None => (arg, None),
        };
        let value = || -> anyhow::Result<String> {
            if let Some(value) = inline {
                Ok(value)
            } else {
                index += 1;
                argv.get(index)
                    .cloned()
                    .ok_or_else(|| anyhow::anyhow!("{flag} needs a value\n{USAGE}"))
            }
        };
        match flag {
            "--out" => out = Some(PathBuf::from(value()?)),
            "--abis" => abis = Some(parse_abis(&value()?)?),
            "--profile" => profile = Profile::parse(&value()?)?,
            "--languages" => languages = Languages::parse(&value()?)?,
            "--skip-host" => skip_host = true,
            "--skip-ndk" => skip_ndk = true,
            "--skip-tailcat" => skip_tailcat = true,
            "--check" => check = true,
            "--help" | "-h" => anyhow::bail!("{USAGE}"),
            _ => anyhow::bail!("unknown flag {arg:?}\n{USAGE}"),
        }
        index += 1;
    }

    let abis = abis.unwrap_or(default_abis);
    if abis.is_empty() {
        anyhow::bail!("--abis selected no ABI (ROBOCO_ANDROID_ABIS is empty?)");
    }

    Ok(AndroidCoreArgs {
        out: out.unwrap_or_else(|| default_out.to_owned()),
        abis,
        profile,
        languages,
        skip_host,
        skip_ndk,
        skip_tailcat,
        check,
    })
}

/// `ROBOCO_ANDROID_ABIS` accepts commas or spaces; empty entries drop out.
pub fn abis_from_env_value(value: Option<&str>) -> Vec<String> {
    value
        .map(|value| {
            value
                .split([',', ' '])
                .map(str::trim)
                .filter(|abi| !abi.is_empty())
                .map(str::to_owned)
                .collect()
        })
        .filter(|abis: &Vec<_>| !abis.is_empty())
        .unwrap_or_else(|| DEFAULT_ABIS.iter().map(|abi| abi.to_string()).collect())
}

fn parse_abis(value: &str) -> anyhow::Result<Vec<String>> {
    let abis = abis_from_env_value(Some(value));
    for abi in &abis {
        if !SUPPORTED_ABIS.contains(&abi.as_str()) {
            anyhow::bail!(
                "unsupported ABI {abi:?} — the Android core ships \
                 {SUPPORTED_ABIS:?} (others are out of scope, android-app#10)"
            );
        }
    }
    Ok(abis)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn argv(values: &[&str]) -> Vec<String> {
        values.iter().map(|value| value.to_string()).collect()
    }

    #[test]
    fn defaults_match_the_ticket() {
        let args = parse(
            &[],
            Path::new("target/android-core"),
            abis_from_env_value(None),
        )
        .expect("defaults parse");
        assert_eq!(args.out, Path::new("target/android-core"));
        assert_eq!(args.abis, ["arm64-v8a", "x86_64"]);
        assert_eq!(args.profile, Profile::Mobile);
        assert_eq!(args.languages, Languages::Mobile);
        assert!(!args.skip_host && !args.skip_ndk && !args.skip_tailcat && !args.check);
    }

    #[test]
    fn flags_parse_in_spaced_and_inline_forms() {
        let args = parse(
            &argv(&[
                "--out",
                "o",
                "--abis",
                "arm64-v8a,x86_64",
                "--profile",
                "mobile-dist",
                "--languages",
                "all",
                "--skip-host",
                "--skip-ndk",
                "--skip-tailcat",
                "--check",
            ]),
            Path::new("default"),
            abis_from_env_value(None),
        )
        .expect("spaced flags parse");
        assert_eq!(args.out, Path::new("o"));
        assert_eq!(args.abis, ["arm64-v8a", "x86_64"]);
        assert_eq!(args.profile, Profile::MobileDist);
        assert_eq!(args.languages, Languages::All);
        assert!(args.skip_host && args.skip_ndk && args.skip_tailcat && args.check);

        let inline = parse(
            &argv(&["--out=o2", "--profile=mobile", "--languages=mobile"]),
            Path::new("default"),
            abis_from_env_value(None),
        )
        .expect("inline flags parse");
        assert_eq!(inline.out, Path::new("o2"));
        assert_eq!(inline.profile, Profile::Mobile);
        assert_eq!(inline.languages, Languages::Mobile);
    }

    #[test]
    fn bad_values_are_rejected_with_the_valid_set() {
        let parse_error = |values: &[&str]| {
            parse(
                &argv(values),
                Path::new("default"),
                abis_from_env_value(None),
            )
            .expect_err("should fail")
            .to_string()
        };
        assert!(parse_error(&["--profile", "release"]).contains("mobile-dist"));
        assert!(parse_error(&["--languages", "middle"]).contains("mobile or all"));
        assert!(parse_error(&["--abis", "arm64"]).contains("unsupported ABI"));
        assert!(parse_error(&["--nope"]).contains("unknown flag"));
        assert!(parse_error(&["--out"]).contains("needs a value"));
    }

    #[test]
    fn env_abis_override_the_default_and_may_be_empty() {
        assert_eq!(
            abis_from_env_value(Some("arm64-v8a x86_64")),
            ["arm64-v8a", "x86_64"]
        );
        assert_eq!(abis_from_env_value(Some("arm64-v8a")), ["arm64-v8a"]);
        assert_eq!(abis_from_env_value(Some("  ")), ["arm64-v8a", "x86_64"]);
        assert_eq!(abis_from_env_value(None), ["arm64-v8a", "x86_64"]);
    }
}
