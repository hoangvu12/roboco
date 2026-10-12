//! `cargo xtask` — repo maintenance tasks.
//!
//! One task today: `android-core` (android-app#10), the single entry point
//! that builds the mobile core for Android and generates its UniFFI Kotlin
//! bindings from **both Windows and Linux hosts**. Gradle's plain `Exec`
//! task (ticket 13) and humans call the same command; no bash, so there is
//! no WSL `bash.exe` trap, and `.dll`/`.exe` naming is handled by
//! `std::env::consts`.
//!
//! ```text
//! cargo xtask android-core [--out DIR]                 default target/android-core
//!                         [--abis arm64-v8a,x86_64]     default $ROBOCO_ANDROID_ABIS or both
//!                         [--profile mobile|mobile-dist] default mobile
//!                         [--languages mobile|all]       default mobile
//!                         [--skip-host] [--skip-ndk] [--check]
//! ```
//!
//! Outputs (all under `target/`, git-ignored — generated bindings are never
//! committed): `<out>/kotlin/uniffi/roboco_core/roboco_core.kt`,
//! `<out>/jniLibs/<abi>/libroboco_mobile.so`, `<out>/host/<libroboco_mobile
//! or roboco_mobile.dll>` (JVM/Robolectric tests), `<out>/sizes.json`.

mod args;
mod elf;
mod kotlin;
mod ndk;
mod sizes;

use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, bail};

/// The library name JNA resolves (library mode): `roboco_mobile` becomes
/// `roboco_mobile.dll` on Windows and `libroboco_mobile.so` elsewhere.
const LIB_NAME: &str = "roboco_mobile";

fn main() -> anyhow::Result<()> {
    let argv: Vec<String> = std::env::args().skip(1).collect();
    match argv.first().map(String::as_str) {
        Some("android-core") => android_core(&argv[1..]),
        Some(other) => bail!("unknown task {other:?}\n{}", args::USAGE),
        None => bail!("{}", args::USAGE),
    }
}

/// The repo root, baked at compile time (the binary is always built from
/// this checkout — `cargo xtask` recompiles on demand).
fn repo_root() -> &'static Path {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(Path::parent)
        .expect("crates/xtask lives two levels below the repo root")
}

/// `cargo`, preferring the exact binary that invoked us.
fn cargo() -> String {
    std::env::var("CARGO").unwrap_or_else(|_| "cargo".to_owned())
}

fn android_core(argv: &[String]) -> anyhow::Result<()> {
    let root = repo_root();
    let default_out = root.join("target").join("android-core");
    let env_abis = std::env::var("ROBOCO_ANDROID_ABIS").ok();
    let parsed = args::parse(
        argv,
        &default_out,
        args::abis_from_env_value(env_abis.as_deref()),
    )?;
    // A relative --out resolves against the repo root, not the caller's
    // working directory (Gradle runs from apps/android).
    let out = if parsed.out.is_absolute() {
        parsed.out
    } else {
        root.join(&parsed.out)
    };
    let args::AndroidCoreArgs {
        out: _,
        abis,
        profile,
        languages,
        skip_host,
        skip_ndk,
        check,
    } = parsed;

    if skip_host && skip_ndk {
        bail!(
            "--skip-host and --skip-ndk together leave no library to generate Kotlin bindings from"
        );
    }
    // The Android targets need the .cargo/config.toml rustflags (16 KB page
    // alignment); a RUSTFLAGS override would replace them entirely, so the
    // Android steps refuse to run. Host-only builds are unaffected.
    if !skip_ndk {
        refuse_rustflags()?;
    }

    println!(
        "android-core: profile={} languages={} abis={} out={}",
        profile.as_str(),
        languages.as_str(),
        abis.join(","),
        out.display()
    );

    // 1. The bindgen binary, built from this crate so generator and runtime
    //    share one UniFFI version. Feature-matched to the library build so
    //    the two steps reuse each other's artifacts.
    let language_feature = languages.feature();
    let features = format!("bindgen,{language_feature}");
    run(
        root,
        &cargo(),
        &[
            "build",
            "--locked",
            "-p",
            "roboco-mobile",
            "--bin",
            "uniffi-bindgen",
            "--features",
            &features,
            "--profile",
            profile.as_str(),
        ],
        &[],
    )?;
    let profile_dir = root.join("target").join(profile.as_str());
    let bindgen = profile_dir.join(format!("uniffi-bindgen{}", std::env::consts::EXE_SUFFIX));

    // 2. The host library: what JVM (Robolectric) tests load through JNA,
    //    and the default bindgen input.
    let host_library: Option<PathBuf>;
    if skip_host {
        host_library = None;
    } else {
        run(
            root,
            &cargo(),
            &[
                "build",
                "--locked",
                "-p",
                "roboco-mobile",
                "--lib",
                "--features",
                language_feature,
                "--profile",
                profile.as_str(),
            ],
            &[],
        )?;
        let host_name = format!("{LIB_NAME}{}", std::env::consts::DLL_SUFFIX);
        let host_name = format!("{}{host_name}", std::env::consts::DLL_PREFIX);
        let built = profile_dir.join(&host_name);
        let host_dir = out.join("host");
        fs::create_dir_all(&host_dir)
            .with_context(|| format!("creating {}", host_dir.display()))?;
        fs::copy(&built, host_dir.join(&host_name))
            .with_context(|| format!("copying {} to host output", built.display()))?;
        println!("host library: {}", host_dir.join(&host_name).display());
        host_library = Some(host_dir.join(&host_name));
    }

    // 3. The Android libraries (cargo-ndk). Runs before the bindings when
    //    --skip-host leaves the arm64 artifact as the only bindgen input.
    if !skip_ndk {
        let ndk_home =
            ndk::find_ndk(&ndk::sdk_roots(|name| std::env::var(name).ok())).ok_or_else(|| {
                anyhow::anyhow!(
                    "no Android NDK found: set ANDROID_NDK_HOME or install one under \
                     <sdk>/ndk (sdkmanager \"ndk;28.2.13676358\")"
                )
            })?;
        if ndk::ndk_major_version(&ndk_home).is_some_and(|major| major < 28) {
            eprintln!(
                "warning: NDK at {} is older than r28 — 16 KB PT_LOAD alignment then \
                 relies entirely on the .cargo/config.toml rustflags",
                ndk_home.display()
            );
        }
        ndk::find_in_path(std::env::var_os("PATH").as_deref(), "cargo-ndk").ok_or_else(|| {
            anyhow::anyhow!(
                "cargo-ndk is missing — install it with: cargo install --locked cargo-ndk@^4"
            )
        })?;

        let jni_libs = out.join("jniLibs");
        let jni_libs_arg = jni_libs.to_string_lossy().into_owned();
        let mut ndk_argv: Vec<String> = vec!["ndk".to_owned()];
        for abi in &abis {
            ndk_argv.push("-t".to_owned());
            ndk_argv.push(abi.clone());
        }
        ndk_argv.extend([
            "-o".to_owned(),
            jni_libs_arg,
            "build".to_owned(),
            "--locked".to_owned(),
            "-p".to_owned(),
            "roboco-mobile".to_owned(),
            "--lib".to_owned(),
            "--profile".to_owned(),
            profile.as_str().to_owned(),
            "--no-default-features".to_owned(),
            "--features".to_owned(),
            language_feature.to_owned(),
        ]);
        let ndk_argv = ndk_argv.iter().map(String::as_str).collect::<Vec<_>>();
        run(
            root,
            &cargo(),
            &ndk_argv,
            &[
                // minSdk (spec D8): the API level cargo-ndk links against.
                // NDK_HOME is set too: cargo-ndk warns when ambient NDK_*
                // variables disagree with the NDK we resolved here.
                ("CARGO_NDK_PLATFORM", "31"),
                ("ANDROID_NDK_HOME", &ndk_home.to_string_lossy()),
                ("ANDROID_NDK_ROOT", &ndk_home.to_string_lossy()),
                ("NDK_HOME", &ndk_home.to_string_lossy()),
            ],
        )?;
    }

    // 4. Kotlin bindings, from the host library or (with --skip-host) the
    //    unstripped arm64 cargo output — never a stripped or copied library:
    //    library mode reads the UNIFFI_META_* symbols.
    let bindgen_library = match host_library {
        Some(path) => path,
        None => {
            let arm64 = root
                .join("target")
                .join("aarch64-linux-android")
                .join(profile.as_str())
                .join("libroboco_mobile.so");
            if !arm64.is_file() {
                bail!(
                    "--skip-host needs an Android build to generate bindings from: \
                     {} does not exist",
                    arm64.display()
                );
            }
            arm64
        }
    };
    let kotlin_dir = out.join("kotlin");
    // Delete first: removed items must not linger in the generated file.
    if kotlin_dir.exists() {
        fs::remove_dir_all(&kotlin_dir)
            .with_context(|| format!("clearing {}", kotlin_dir.display()))?;
    }
    fs::create_dir_all(&kotlin_dir)
        .with_context(|| format!("creating {}", kotlin_dir.display()))?;
    let bindgen = bindgen.to_string_lossy().into_owned();
    let library_arg = bindgen_library.to_string_lossy().into_owned();
    let out_dir_arg = kotlin_dir.to_string_lossy().into_owned();
    run(
        // Repo root as working directory: library mode finds
        // crates/mobile/uniffi.toml through `cargo metadata`.
        root,
        &bindgen,
        &[
            "generate",
            "--library",
            &library_arg,
            "--language",
            "kotlin",
            "--no-format",
            "--out-dir",
            &out_dir_arg,
        ],
        &[],
    )?;

    println!(
        "kotlin bindings: {}",
        kotlin_dir
            .join("uniffi")
            .join("roboco_core")
            .join("roboco_core.kt")
            .display()
    );
    println!("android-core: outputs in {}", out.display());

    if !check {
        return Ok(());
    }
    android_core_checks(&out, profile.as_str(), languages.as_str(), &abis)
}

fn refuse_rustflags() -> anyhow::Result<()> {
    for variable in ["RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS"] {
        if let Ok(value) = std::env::var(variable) {
            if !value.trim().is_empty() {
                bail!(
                    "{variable} is set — it overrides the target.<triple>.rustflags that pin \
                     16 KB PT_LOAD alignment for Android builds. Unset it and rerun"
                );
            }
        }
    }
    Ok(())
}

/// `--check`: ELF alignment of every produced `.so`, the generated-Kotlin
/// contract, and `sizes.json`.
fn android_core_checks(
    out: &Path,
    profile: &str,
    languages: &str,
    expected_abis: &[String],
) -> anyhow::Result<()> {
    let mut failures = Vec::new();
    let mut abi_sizes = Vec::new();

    let jni_libs = out.join("jniLibs");
    let mut abi_dirs: Vec<PathBuf> = Vec::new();
    if jni_libs.is_dir() {
        for entry in
            fs::read_dir(&jni_libs).with_context(|| format!("reading {}", jni_libs.display()))?
        {
            let entry = entry.with_context(|| format!("reading {}", jni_libs.display()))?;
            if entry.file_type().is_ok_and(|kind| kind.is_dir()) {
                abi_dirs.push(entry.path());
            }
        }
        abi_dirs.sort();
    } else if !expected_abis.is_empty() {
        // Fine for `--skip-ndk` runs (the Windows CI host path); the
        // Android job runs the full command.
        println!("android-core checks: no jniLibs to verify (built with --skip-ndk?)");
    }
    for abi in expected_abis {
        if !abi_dirs
            .iter()
            .any(|dir| dir.file_name().is_some_and(|name| name == abi.as_str()))
        {
            failures.push(format!(
                "jniLibs/{abi}/libroboco_mobile.so was not produced"
            ));
        }
    }
    for dir in &abi_dirs {
        let library = dir.join("libroboco_mobile.so");
        if !library.is_file() {
            continue;
        }
        let abi = dir
            .file_name()
            .unwrap_or_default()
            .to_string_lossy()
            .into_owned();
        let bytes = match fs::read(&library) {
            Ok(bytes) => bytes,
            Err(error) => {
                failures.push(format!(
                    "{abi}: could not read {}: {error}",
                    library.display()
                ));
                continue;
            }
        };
        let info = match elf::parse(&bytes) {
            Ok(info) => info,
            Err(error) => {
                failures.push(format!("{abi}: {error}"));
                continue;
            }
        };
        if !info.load_alignments_ok() {
            failures.push(format!(
                "{abi}: PT_LOAD alignment {:#x} is below 16 KB — a RUSTFLAGS \
                 override or a non-NDK linker stripped the .cargo/config.toml rustflags",
                info.load_alignments.iter().copied().min().unwrap_or(0)
            ));
        }
        abi_sizes.push(sizes::AbiSizes::from_file(&abi, bytes.len() as u64, &info));
    }

    let kotlin_file = out
        .join("kotlin")
        .join("uniffi")
        .join("roboco_core")
        .join("roboco_core.kt");
    match fs::read_to_string(&kotlin_file) {
        Ok(source) => {
            for failure in kotlin::check_generated(&source) {
                failures.push(format!("roboco_core.kt: {failure}"));
            }
        }
        Err(error) => failures.push(format!(
            "generated Kotlin is missing ({}): {error}",
            kotlin_file.display()
        )),
    }

    let report = sizes::SizesReport::new(profile, languages, abi_sizes);
    let sizes_path = out.join("sizes.json");
    fs::write(&sizes_path, report.to_json())
        .with_context(|| format!("writing {}", sizes_path.display()))?;
    print!("{}", report.summary());

    if failures.is_empty() {
        Ok(())
    } else {
        bail!(
            "android-core checks failed:\n  - {}",
            failures.join("\n  - ")
        );
    }
}

fn run(cwd: &Path, program: &str, argv: &[&str], env: &[(&str, &str)]) -> anyhow::Result<()> {
    println!("+ {program} {}", argv.join(" "));
    let mut command = std::process::Command::new(program);
    command.args(argv).current_dir(cwd);
    for (key, value) in env {
        command.env(key, value);
    }
    let status = command
        .status()
        .with_context(|| format!("could not run {program}"))?;
    if !status.success() {
        bail!("{program} {} failed with {status}", argv.join(" "));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    #[test]
    fn repo_root_points_at_the_workspace() {
        let root = super::repo_root();
        assert!(root.join("Cargo.toml").is_file(), "{}", root.display());
        assert!(
            root.join("crates").join("mobile").is_dir(),
            "{}",
            root.display()
        );
    }
}
