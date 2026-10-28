//! `libroboco_tailcat.so` — the Tailcat transport the Android core loads
//! with `dlopen` (android-next#02).
//!
//! `adapters/roboco-tailcat/capi` is built with `go build -buildmode=c-shared`
//! once per ABI, with the NDK's clang as the cgo compiler and linker, into
//! `<out>/tailcat/<abi>/` (the `.so` plus cgo's generated header), and the
//! `.so` is copied into `<out>/jniLibs/<abi>/` beside `libroboco_mobile.so`
//! so Gradle packages it into the matching ABI split. The 16 KB
//! `max-page-size` goes on the command line (`-extldflags`), never through
//! the environment, so an ambient `GOFLAGS`/`CGO_LDFLAGS` cannot drop it.
//!
//! `--skip-tailcat` or `ROBOCO_ANDROID_TAILCAT=0` builds without it; the
//! core then reports Tailcat as unavailable at pair time.

use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, bail};

/// The file name the Rust core opens under `nativeLibraryDir`.
pub const LIB_FILE: &str = "libroboco_tailcat.so";

/// The Go package built into the library, relative to [adapter_dir].
const PACKAGE: &str = "./capi";

/// minSdk (spec D8): the API level the NDK clang targets.
const API_LEVEL: u32 = 31;

/// The adapter module root.
pub fn adapter_dir(root: &Path) -> PathBuf {
    root.join("adapters").join("roboco-tailcat")
}

/// Whether `ROBOCO_ANDROID_TAILCAT` leaves the library enabled: only `0`,
/// `false`, `no` and `off` (any case) turn it off.
pub fn enabled_from_env(value: Option<&str>) -> bool {
    !matches!(
        value.map(|value| value.trim().to_ascii_lowercase()).as_deref(),
        Some("0" | "false" | "no" | "off")
    )
}

/// `GOARCH` for an Android ABI.
pub fn goarch(abi: &str) -> Option<&'static str> {
    match abi {
        "arm64-v8a" => Some("arm64"),
        "x86_64" => Some("amd64"),
        _ => None,
    }
}

/// The clang target triple (without the API level) for an Android ABI.
fn clang_triple(abi: &str) -> Option<&'static str> {
    crate::ndk::target_triple(abi)
}

/// The NDK's prebuilt host directory name. The NDK ships x86_64 host
/// binaries only (they run under emulation on arm64 hosts).
pub fn ndk_host_tag() -> &'static str {
    if cfg!(windows) {
        "windows-x86_64"
    } else if cfg!(target_os = "macos") {
        "darwin-x86_64"
    } else {
        "linux-x86_64"
    }
}

/// The NDK's clang driver.
pub fn ndk_clang(ndk_home: &Path, host_tag: &str, exe_suffix: &str) -> PathBuf {
    ndk_home
        .join("toolchains")
        .join("llvm")
        .join("prebuilt")
        .join(host_tag)
        .join("bin")
        .join(format!("clang{exe_suffix}"))
}

/// The `CC` value cgo uses for an ABI: clang itself with an explicit
/// `--target`, which is what the NDK's `<triple><api>-clang` wrapper scripts
/// run (and avoids the `.cmd` wrapper on Windows hosts). cgo splits `CC` on
/// spaces and honours quotes, so a path with spaces is quoted.
pub fn cc_value(clang: &Path, abi: &str) -> Option<String> {
    let triple = clang_triple(abi)?;
    let clang = clang.to_string_lossy();
    let clang = if clang.contains(' ') {
        format!("'{clang}'")
    } else {
        clang.into_owned()
    };
    Some(format!("{clang} --target={triple}{API_LEVEL}"))
}

/// `go build` arguments for one library.
pub fn build_args(output: &Path) -> Vec<String> {
    vec![
        "build".to_owned(),
        "-mod=readonly".to_owned(),
        "-trimpath".to_owned(),
        // No VCS stamp: the build stays reproducible and needs no git.
        "-buildvcs=false".to_owned(),
        "-buildmode=c-shared".to_owned(),
        // Go's c-shared link sets no DT_SONAME (cmd/link/internal/ld/lib.go);
        // the NDK guide requires one for shared libraries.
        format!("-ldflags=-s -w -extldflags=-Wl,-z,max-page-size=16384,-soname,{LIB_FILE}"),
        "-o".to_owned(),
        output.to_string_lossy().into_owned(),
        PACKAGE.to_owned(),
    ]
}

/// The environment for one ABI's build.
pub fn build_env(abi: &str, cc: &str) -> anyhow::Result<Vec<(&'static str, String)>> {
    let Some(arch) = goarch(abi) else {
        bail!("no Go architecture for ABI {abi:?}");
    };
    Ok(vec![
        ("GOOS", "android".to_owned()),
        ("GOARCH", arch.to_owned()),
        ("CGO_ENABLED", "1".to_owned()),
        ("CC", cc.to_owned()),
    ])
}

/// The actionable error when `go` is missing and Tailcat was not skipped.
pub const GO_MISSING: &str = "go is missing — install Go (the version in \
     adapters/roboco-tailcat/go.mod; GOTOOLCHAIN=auto fetches it) to build \
     libroboco_tailcat.so, or build without Tailcat: --skip-tailcat or \
     ROBOCO_ANDROID_TAILCAT=0";

/// Builds the library for every ABI and copies it into `jni_libs/<abi>/`.
pub fn build(
    root: &Path,
    ndk_home: &Path,
    out: &Path,
    jni_libs: &Path,
    abis: &[String],
) -> anyhow::Result<()> {
    let go = crate::ndk::find_in_path(std::env::var_os("PATH").as_deref(), "go")
        .ok_or_else(|| anyhow::anyhow!("{GO_MISSING}"))?;
    let go = go.to_string_lossy().into_owned();
    let clang = ndk_clang(ndk_home, ndk_host_tag(), std::env::consts::EXE_SUFFIX);
    if !clang.is_file() {
        bail!(
            "the NDK at {} has no clang at {} — reinstall the NDK",
            ndk_home.display(),
            clang.display()
        );
    }
    let adapter = adapter_dir(root);
    for abi in abis {
        let Some(cc) = cc_value(&clang, abi) else {
            bail!("no clang target for ABI {abi:?}");
        };
        let built_dir = out.join("tailcat").join(abi);
        fs::create_dir_all(&built_dir)
            .with_context(|| format!("creating {}", built_dir.display()))?;
        let built = built_dir.join(LIB_FILE);
        let argv = build_args(&built);
        let argv = argv.iter().map(String::as_str).collect::<Vec<_>>();
        let env = build_env(abi, &cc)?;
        let env = env
            .iter()
            .map(|(key, value)| (*key, value.as_str()))
            .collect::<Vec<_>>();
        crate::run(&adapter, &go, &argv, &env)?;
        let abi_dir = jni_libs.join(abi);
        fs::create_dir_all(&abi_dir).with_context(|| format!("creating {}", abi_dir.display()))?;
        fs::copy(&built, abi_dir.join(LIB_FILE))
            .with_context(|| format!("copying {} into jniLibs", built.display()))?;
        println!("tailcat library: {}", abi_dir.join(LIB_FILE).display());
    }
    Ok(())
}

/// `--skip-tailcat`: a library left by an earlier build must not ship.
pub fn remove_stale(jni_libs: &Path, abis: &[String]) -> anyhow::Result<()> {
    for abi in abis {
        let stale = jni_libs.join(abi).join(LIB_FILE);
        if stale.is_file() {
            fs::remove_file(&stale).with_context(|| format!("removing {}", stale.display()))?;
            println!("removed {} (built with --skip-tailcat)", stale.display());
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_explicit_off_values_disable_tailcat() {
        for off in ["0", "false", "FALSE", " no ", "off"] {
            assert!(!enabled_from_env(Some(off)), "{off:?}");
        }
        for on in [None, Some(""), Some("1"), Some("true"), Some("yes")] {
            assert!(enabled_from_env(on), "{on:?}");
        }
    }

    #[test]
    fn abis_map_to_go_and_clang_targets() {
        assert_eq!(goarch("arm64-v8a"), Some("arm64"));
        assert_eq!(goarch("x86_64"), Some("amd64"));
        assert_eq!(goarch("armeabi-v7a"), None);
        let clang = Path::new("/ndk/bin/clang");
        assert_eq!(
            cc_value(clang, "arm64-v8a").as_deref(),
            Some("/ndk/bin/clang --target=aarch64-linux-android31")
        );
        assert_eq!(
            cc_value(clang, "x86_64").as_deref(),
            Some("/ndk/bin/clang --target=x86_64-linux-android31")
        );
        assert_eq!(cc_value(clang, "x86"), None);
    }

    #[test]
    fn a_clang_path_with_spaces_is_quoted_for_cgo() {
        let cc = cc_value(Path::new("C:/Android Sdk/clang.exe"), "arm64-v8a").unwrap();
        assert_eq!(cc, "'C:/Android Sdk/clang.exe' --target=aarch64-linux-android31");
    }

    #[test]
    fn the_clang_path_follows_the_ndk_layout() {
        assert_eq!(
            ndk_clang(Path::new("ndk"), "windows-x86_64", ".exe"),
            Path::new("ndk")
                .join("toolchains")
                .join("llvm")
                .join("prebuilt")
                .join("windows-x86_64")
                .join("bin")
                .join("clang.exe")
        );
    }

    #[test]
    fn build_args_pin_c_shared_16k_pages_and_the_capi_package() {
        let args = build_args(Path::new("out/libroboco_tailcat.so"));
        assert_eq!(args.first().map(String::as_str), Some("build"));
        assert!(args.contains(&"-buildmode=c-shared".to_owned()));
        assert!(args.contains(&"-mod=readonly".to_owned()));
        assert!(args.contains(&"-trimpath".to_owned()));
        assert!(
            args.iter()
                .any(|arg| arg.starts_with("-ldflags=")
                    && arg.contains("max-page-size=16384")
                    && arg.contains("-soname,libroboco_tailcat.so")),
            "{args:?}"
        );
        assert_eq!(args.last().map(String::as_str), Some("./capi"));
    }

    #[test]
    fn build_env_selects_android_with_cgo() {
        let env = build_env("x86_64", "cc").unwrap();
        assert!(env.contains(&("GOOS", "android".to_owned())));
        assert!(env.contains(&("GOARCH", "amd64".to_owned())));
        assert!(env.contains(&("CGO_ENABLED", "1".to_owned())));
        assert!(env.contains(&("CC", "cc".to_owned())));
        assert!(build_env("mips", "cc").is_err());
    }

    #[test]
    fn skip_removes_a_stale_library_only() {
        let root = std::env::temp_dir().join("roboco-xtask-tailcat-stale");
        let _ = fs::remove_dir_all(&root);
        let abi_dir = root.join("arm64-v8a");
        fs::create_dir_all(&abi_dir).unwrap();
        fs::write(abi_dir.join(LIB_FILE), b"old").unwrap();
        fs::write(abi_dir.join("libroboco_mobile.so"), b"core").unwrap();
        remove_stale(&root, &["arm64-v8a".to_owned(), "x86_64".to_owned()]).unwrap();
        assert!(!abi_dir.join(LIB_FILE).exists());
        assert!(abi_dir.join("libroboco_mobile.so").is_file());
    }
}
