//! NDK discovery and cargo-ndk presence (android-app#10).
//!
//! Resolution order: `ANDROID_NDK_HOME`, then the highest `ndk/<version>`
//! under the first SDK root that has one (`ANDROID_HOME`, `ANDROID_SDK_ROOT`,
//! `%LOCALAPPDATA%\\Android\\Sdk`, `~/Android/Sdk`).

use std::ffi::OsStr;
use std::path::{Path, PathBuf};

/// SDK roots to probe, in order. Env values are gathered by the caller so
/// the selection itself stays testable.
pub fn sdk_roots(env: impl Fn(&str) -> Option<String>) -> Vec<PathBuf> {
    let mut roots = Vec::new();
    let mut push = |root: Option<PathBuf>| {
        if let Some(root) = root {
            if !roots.contains(&root) {
                roots.push(root);
            }
        }
    };
    for variable in ["ANDROID_HOME", "ANDROID_SDK_ROOT"] {
        push(env(variable).map(PathBuf::from));
    }
    // The default install locations: `%LOCALAPPDATA%\Android\Sdk` on
    // Windows, `~/Android/Sdk` on Linux.
    push(env("LOCALAPPDATA").map(|local| Path::new(&local).join("Android").join("Sdk")));
    push(env("HOME").map(|home| Path::new(&home).join("Android").join("Sdk")));
    roots
}

/// The NDK to build with: an explicitly set `ANDROID_NDK_HOME` wins (it is
/// how CI and users pin a specific NDK — the acceptance runs exercise 28.2
/// and 27.2 that way); otherwise the highest `ndk/<version>` under the first
/// SDK root that has one. `None` when nothing points at an NDK.
pub fn ndk_home(explicit: Option<&Path>, sdk_roots: &[PathBuf]) -> Option<PathBuf> {
    explicit.map(Path::to_owned).or_else(|| find_ndk(sdk_roots))
}

/// The highest-versioned `ndk/<version>` directory under the first SDK root
/// that has one.
pub fn find_ndk(sdk_roots: &[PathBuf]) -> Option<PathBuf> {
    for root in sdk_roots {
        let mut versions: Vec<(u32, u32, u32, PathBuf)> = read_ndk_versions(root);
        versions.sort_by_key(|&(major, minor, patch, _)| (major, minor, patch));
        if let Some((_, _, _, path)) = versions.pop() {
            return Some(path);
        }
    }
    None
}

fn read_ndk_versions(sdk: &Path) -> Vec<(u32, u32, u32, PathBuf)> {
    let Ok(entries) = std::fs::read_dir(sdk.join("ndk")) else {
        return Vec::new();
    };
    entries
        .filter_map(|entry| entry.ok())
        .filter(|entry| entry.file_type().is_ok_and(|kind| kind.is_dir()))
        .filter_map(|entry| {
            let path = entry.path();
            let name = path.file_name()?.to_str()?;
            let mut parts = name.split('.');
            let major = parts.next()?.parse().ok()?;
            let minor = parts.next().unwrap_or("0").parse().unwrap_or(0);
            let patch = parts.next().unwrap_or("0").parse().unwrap_or(0);
            Some((major, minor, patch, path))
        })
        .collect()
}

/// The NDK's major version, parsed from the `ndk/<major.minor.patch>`
/// directory name — used to warn when it is older than r28 (16 KB alignment
/// then relies entirely on the pinned rustflags in `.cargo/config.toml`).
pub fn ndk_major_version(ndk_home: &Path) -> Option<u32> {
    ndk_home
        .file_name()
        .and_then(OsStr::to_str)?
        .split('.')
        .next()
        .and_then(|major| major.parse().ok())
}

/// The Rust (and clang) target triple for an Android ABI: cargo-ndk builds
/// `arm64-v8a` into `target/aarch64-linux-android/<profile>/`.
pub fn target_triple(abi: &str) -> Option<&'static str> {
    match abi {
        "arm64-v8a" => Some("aarch64-linux-android"),
        "x86_64" => Some("x86_64-linux-android"),
        _ => None,
    }
}

/// Whether `cargo-ndk` is runnable from `path_var` (the `PATH` value).
pub fn find_in_path(path_var: Option<&OsStr>, program: &str) -> Option<PathBuf> {
    let path_var = path_var?;
    let exe = format!("{program}{}", std::env::consts::EXE_SUFFIX);
    std::env::split_paths(&path_var)
        .map(|dir| dir.join(&exe))
        .find(|candidate| candidate.is_file())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn temp_root(tag: &str) -> PathBuf {
        let root = std::env::temp_dir().join(format!("roboco-xtask-ndk-{tag}"));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(&root).expect("temp dir");
        root
    }

    fn make_ndk(sdk: &Path, version: &str) -> PathBuf {
        let ndk = sdk.join("ndk").join(version);
        fs::create_dir_all(&ndk).expect("ndk dir");
        ndk
    }

    #[test]
    fn the_highest_ndk_version_wins_and_roots_fall_through() {
        let first = temp_root("first");
        let second = temp_root("second");
        let old = make_ndk(&first, "27.2.12479018");
        make_ndk(&first, "28.2.13676358");
        make_ndk(&second, "29.0.1");

        // Highest within the first root wins.
        assert_eq!(
            find_ndk(&[first.clone()]),
            Some(old.parent().unwrap().join("28.2.13676358"))
        );
        // A root with no ndk/ falls through to the next one.
        let empty = temp_root("empty");
        assert_eq!(
            find_ndk(&[empty.clone(), second.clone()]),
            Some(second.join("ndk").join("29.0.1"))
        );
        assert_eq!(find_ndk(&[empty]), None);
    }

    #[test]
    fn major_version_comes_from_the_directory_name() {
        assert_eq!(
            ndk_major_version(Path::new("C:/sdk/ndk/28.2.13676358")),
            Some(28)
        );
        assert_eq!(ndk_major_version(Path::new("ndk/27.2.12479018")), Some(27));
        assert_eq!(ndk_major_version(Path::new("ndk/not-a-version")), None);
        assert_eq!(ndk_major_version(Path::new("ndk")), None);
    }

    #[test]
    fn sdk_roots_deduplicate_and_follow_the_ticket_order() {
        let env = |name: &str| match name {
            "ANDROID_HOME" => Some("Z:/sdk".to_owned()),
            "ANDROID_SDK_ROOT" => Some("Z:/sdk".to_owned()),
            "LOCALAPPDATA" => Some("C:/Users/ADMIN/AppData/Local".to_owned()),
            "HOME" => Some("/home/dev".to_owned()),
            _ => None,
        };
        let roots = sdk_roots(env);
        assert_eq!(
            roots,
            [
                PathBuf::from("Z:/sdk"),
                Path::new("C:/Users/ADMIN/AppData/Local")
                    .join("Android")
                    .join("Sdk"),
                Path::new("/home/dev").join("Android").join("Sdk"),
            ]
        );
    }

    #[test]
    fn explicit_android_ndk_home_pins_the_ndk() {
        let first = temp_root("explicit");
        let pinned = make_ndk(&first, "27.2.12479018");
        make_ndk(&first, "28.2.13676358");
        let newest = pinned.parent().unwrap().join("28.2.13676358");
        // A pinned 27.2 must not be silently upgraded to the SDK's 28.2.
        assert_eq!(
            ndk_home(Some(&pinned), &[first.clone()]),
            Some(pinned.clone())
        );
        assert_eq!(ndk_home(None, &[first]), Some(newest));
        assert_eq!(ndk_home(Some(&pinned), &[]), Some(pinned));
    }

    #[test]
    fn cargo_ndk_is_found_by_executable_name() {
        let root = temp_root("path");
        let bin = root.join("bin");
        fs::create_dir_all(&bin).expect("bin dir");
        let exe = format!("cargo-ndk{}", std::env::consts::EXE_SUFFIX);
        fs::write(bin.join(&exe), b"").expect("exe");
        let path_var = std::env::join_paths([root.join("missing"), bin.clone()]).expect("join");
        assert_eq!(
            find_in_path(Some(path_var.as_os_str()), "cargo-ndk"),
            Some(bin.join(exe))
        );
        assert_eq!(find_in_path(None, "cargo-ndk"), None);
    }
}
