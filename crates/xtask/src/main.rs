//! `cargo xtask` — repo maintenance tasks.
//!
//! Two Android tasks: `android-core` (android-app#10), the single entry point
//! that builds the mobile core for Android and generates its UniFFI Kotlin
//! bindings from **both Windows and Linux hosts**; and `android-licenses`
//! (android-ui-parity #101), the Rust core's licence data for the Android
//! Licenses screen. Gradle's plain `Exec` task (ticket 13) and humans call
//! the same commands; no bash, so there is no WSL `bash.exe` trap, and
//! `.dll`/`.exe` naming is handled by `std::env::consts`.
//!
//! ```text
//! cargo xtask android-core [--out DIR]                 default target/android-core
//!                         [--abis arm64-v8a,x86_64]     default $ROBOCO_ANDROID_ABIS or both
//!                         [--profile mobile|mobile-dist] default mobile
//!                         [--languages mobile|all]       default mobile
//!                         [--skip-host] [--skip-ndk] [--skip-tailcat] [--check]
//!
//! cargo xtask android-licenses --out FILE [--languages mobile|all]
//!
//! cargo xtask tailcat-adapter [--release]
//! ```
//!
//! `tailcat-adapter` builds the desktop Tailcat adapter beside a source
//! build's `roboco` executable (see `desktop_tailcat`).
//!
//! Outputs (all under `target/`, git-ignored — generated bindings are never
//! committed): `<out>/kotlin/uniffi/roboco_core/roboco_core.kt`,
//! `<out>/jniLibs/<abi>/libroboco_mobile.so`, `<out>/jniLibs/<abi>/
//! libroboco_tailcat.so` (the Go Tailcat transport, android-next#02; off with
//! `--skip-tailcat` or `ROBOCO_ANDROID_TAILCAT=0`), `<out>/host/<libroboco_mobile
//! or roboco_mobile.dll>` (JVM/Robolectric tests; not with `--skip-host`,
//! which reads the bindings out of the first ABI's library instead),
//! `<out>/sizes.json`. The generator is `uniffi-bindgen` from
//! `crates/mobile-bindgen`, never a host build of the core.

mod android_licenses;
mod args;
mod desktop_tailcat;
mod elf;
mod kotlin;
mod ndk;
mod sizes;
mod tailcat;
mod uniffi_lock;

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, bail};

/// The library name JNA resolves (library mode): `roboco_mobile` becomes
/// `roboco_mobile.dll` on Windows and `libroboco_mobile.so` elsewhere.
const LIB_NAME: &str = "roboco_mobile";

/// The Cargo profile `uniffi-bindgen` (`roboco-mobile-bindgen`) builds at,
/// whatever the core's profile.
const BINDGEN_PROFILE: &str = "mobile";

/// The core's file name inside `jniLibs/<abi>/`.
const LIB_FILE_MOBILE: &str = "libroboco_mobile.so";

fn main() -> anyhow::Result<()> {
    let argv: Vec<String> = std::env::args().skip(1).collect();
    match argv.first().map(String::as_str) {
        Some("android-core") => android_core(&argv[1..]),
        Some("android-licenses") => android_licenses::run(&argv[1..]),
        Some("tailcat-adapter") => desktop_tailcat::run(&argv[1..]),
        Some(other) => bail!("unknown task {other:?}\n{}", args::USAGE),
        None => bail!("{}", args::USAGE),
    }
}

/// The repo root, baked at compile time (the binary is always built from
/// this checkout — `cargo xtask` recompiles on demand).
pub(crate) fn repo_root() -> &'static Path {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(Path::parent)
        .expect("crates/xtask lives two levels below the repo root")
}

/// `cargo`, preferring the exact binary that invoked us.
pub(crate) fn cargo() -> String {
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
        skip_tailcat,
        check,
    } = parsed;
    let tailcat_enabled = !skip_tailcat
        && tailcat::enabled_from_env(std::env::var("ROBOCO_ANDROID_TAILCAT").ok().as_deref());

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
        "android-core: profile={} languages={} abis={} tailcat={} out={}",
        profile.as_str(),
        languages.as_str(),
        abis.join(","),
        if tailcat_enabled { "on" } else { "off" },
        out.display()
    );

    // 1. UniFFI's generator on its own (crates/mobile-bindgen, upstream
    //    684becf0f): it reads the bindings out of any target's built core,
    //    so it never compiles the core itself. Always at the `mobile`
    //    profile: its own optimisation does not change what it generates,
    //    and mobile-dist's thin LTO would only slow it down. Its uniffi
    //    must be the core's, so drift in Cargo.lock stops here.
    let lock_path = root.join("Cargo.lock");
    let lock = fs::read_to_string(&lock_path)
        .with_context(|| format!("reading {}", lock_path.display()))?;
    uniffi_lock::check(&lock)?;
    run(
        root,
        &cargo(),
        &[
            "build",
            "--locked",
            "-p",
            "roboco-mobile-bindgen",
            "--profile",
            BINDGEN_PROFILE,
        ],
        &[],
    )?;
    let bindgen = root
        .join("target")
        .join(BINDGEN_PROFILE)
        .join(format!("uniffi-bindgen{}", std::env::consts::EXE_SUFFIX));

    // 2. The host library: what JVM (Robolectric) tests load through JNA,
    //    and the bindgen input unless --skip-host.
    let language_feature = languages.feature();
    let profile_dir = root.join("target").join(profile.as_str());
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
    //    --skip-host leaves the first ABI's artifact as the only bindgen
    //    input.
    if !skip_ndk {
        // Resolution order per the ticket: ANDROID_NDK_HOME first (it pins
        // a specific NDK — the acceptance runs use it to build with 28.2
        // and 27.2), else the highest ndk/<version> under an SDK root.
        let explicit = std::env::var_os("ANDROID_NDK_HOME").map(PathBuf::from);
        let ndk_home = ndk::ndk_home(
            explicit.as_deref(),
            &ndk::sdk_roots(|name| std::env::var(name).ok()),
        )
        .ok_or_else(|| {
            anyhow::anyhow!(
                "no Android NDK found: set ANDROID_NDK_HOME or install one under \
                 <sdk>/ndk (sdkmanager \"ndk;28.2.13676358\")"
            )
        })?;
        if !ndk_home.is_dir() {
            bail!(
                "ANDROID_NDK_HOME points at {}, which is not a directory",
                ndk_home.display()
            );
        }
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

        // 3b. The Go Tailcat transport, beside the core in each ABI.
        if tailcat_enabled {
            tailcat::build(root, &ndk_home, &out, &jni_libs, &abis)?;
        } else {
            tailcat::remove_stale(&jni_libs, &abis)?;
        }
    }

    // 4. Kotlin bindings, from the host library or (with --skip-host) the
    //    first ABI's unstripped cargo output — never a stripped or copied
    //    library: library mode reads the UNIFFI_META_* symbols.
    let first_abi = &abis[0];
    let bindgen_library = match &host_library {
        Some(path) => path.clone(),
        None => {
            let library = ndk_library(root, profile, first_abi)?;
            if !library.is_file() {
                bail!(
                    "--skip-host needs an Android build to generate bindings from: \
                     {} does not exist",
                    library.display()
                );
            }
            library
        }
    };
    let kotlin_dir = out.join("kotlin");
    generate_kotlin(root, &bindgen, &bindgen_library, &kotlin_dir)?;

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
    // With both a host and an Android library at hand, prove that
    // --skip-host builds (APK-only and emulator runs) get the same bindings
    // as the JVM tests: generate again from the first ABI and compare.
    let mut failures = Vec::new();
    if host_library.is_some() && !skip_ndk {
        let ndk_kotlin = out.join(format!("kotlin-from-{first_abi}"));
        generate_kotlin(
            root,
            &bindgen,
            &ndk_library(root, profile, first_abi)?,
            &ndk_kotlin,
        )?;
        let host_tree = read_tree(&kotlin_dir)?;
        let ndk_tree = read_tree(&ndk_kotlin)?;
        fs::remove_dir_all(&ndk_kotlin)
            .with_context(|| format!("clearing {}", ndk_kotlin.display()))?;
        if host_tree != ndk_tree {
            failures.push(format!(
                "Kotlin generated from the {first_abi} library differs from the host \
                 library's: --skip-host builds would ship other bindings than the JVM \
                 tests load"
            ));
        } else {
            println!("bindings from the host and {first_abi} libraries are identical");
        }
    }
    // --skip-ndk produced no Android libraries, so no ABI is *expected*:
    // the checks still verify any jniLibs a previous full run left behind,
    // plus the generated Kotlin and sizes.json.
    let expected_abis: &[String] = if skip_ndk { &[] } else { &abis };
    android_core_checks(
        &out,
        profile,
        languages,
        expected_abis,
        tailcat_enabled,
        failures,
    )
}

/// The unstripped `libroboco_mobile.so` cargo-ndk built for `abi`.
fn ndk_library(root: &Path, profile: args::Profile, abi: &str) -> anyhow::Result<PathBuf> {
    let triple = ndk::target_triple(abi)
        .ok_or_else(|| anyhow::anyhow!("no Rust target for Android ABI {abi:?}"))?;
    Ok(root
        .join("target")
        .join(triple)
        .join(profile.as_str())
        .join(LIB_FILE_MOBILE))
}

/// Runs `uniffi-bindgen generate` over `library` into a fresh `out_dir`.
fn generate_kotlin(
    root: &Path,
    bindgen: &Path,
    library: &Path,
    out_dir: &Path,
) -> anyhow::Result<()> {
    // Delete first: removed items must not linger in the generated file.
    if out_dir.exists() {
        fs::remove_dir_all(out_dir).with_context(|| format!("clearing {}", out_dir.display()))?;
    }
    fs::create_dir_all(out_dir).with_context(|| format!("creating {}", out_dir.display()))?;
    let bindgen = bindgen.to_string_lossy().into_owned();
    let library_arg = library.to_string_lossy().into_owned();
    let out_dir_arg = out_dir.to_string_lossy().into_owned();
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
    )
}

/// Every file under `dir`, keyed by its path relative to `dir`.
fn read_tree(dir: &Path) -> anyhow::Result<BTreeMap<PathBuf, Vec<u8>>> {
    let mut files = BTreeMap::new();
    let mut pending = vec![dir.to_owned()];
    while let Some(current) = pending.pop() {
        for entry in
            fs::read_dir(&current).with_context(|| format!("reading {}", current.display()))?
        {
            let path = entry
                .with_context(|| format!("reading {}", current.display()))?
                .path();
            if path.is_dir() {
                pending.push(path);
            } else {
                let bytes =
                    fs::read(&path).with_context(|| format!("reading {}", path.display()))?;
                let relative = path.strip_prefix(dir).unwrap_or(&path).to_owned();
                files.insert(relative, bytes);
            }
        }
    }
    Ok(files)
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
    profile: args::Profile,
    languages: args::Languages,
    expected_abis: &[String],
    tailcat_expected: bool,
    mut failures: Vec<String>,
) -> anyhow::Result<()> {
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
    } else if expected_abis.is_empty() {
        // `--skip-ndk` runs (the Windows CI host path) expect no ABI; the
        // Android job runs the full command.
        println!("android-core checks: no jniLibs to verify (built with --skip-ndk)");
    }
    for abi in expected_abis {
        if !abi_dirs
            .iter()
            .any(|dir| dir.file_name().is_some_and(|name| name == abi.as_str()))
        {
            failures.push(format!(
                "jniLibs/{abi}/libroboco_mobile.so was not produced"
            ));
        } else if tailcat_expected && !jni_libs.join(abi).join(tailcat::LIB_FILE).is_file() {
            failures.push(format!(
                "jniLibs/{abi}/{} was not produced",
                tailcat::LIB_FILE
            ));
        }
    }
    for dir in &abi_dirs {
        let abi = dir
            .file_name()
            .unwrap_or_default()
            .to_string_lossy()
            .into_owned();
        for (library_name, alignment_hint) in [
            (
                LIB_FILE_MOBILE,
                "a RUSTFLAGS override or a non-NDK linker stripped the .cargo/config.toml rustflags",
            ),
            (
                tailcat::LIB_FILE,
                "the go build lost its -extldflags=-Wl,-z,max-page-size=16384",
            ),
        ] {
            let library = dir.join(library_name);
            if !library.is_file() {
                continue;
            }
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
                    failures.push(format!("{abi}/{library_name}: {error}"));
                    continue;
                }
            };
            if !info.load_alignments_ok() {
                failures.push(format!(
                    "{abi}/{library_name}: PT_LOAD alignment {:#x} is below 16 KB — {alignment_hint}",
                    info.load_alignments.iter().copied().min().unwrap_or(0)
                ));
            }
            abi_sizes.push(sizes::AbiSizes::from_file(
                &abi,
                library_name,
                bytes.len() as u64,
                &info,
            ));
        }
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

/// Like [run], but returns the command's standard output as text (stdout
/// only; stderr passes through to the console). `cargo metadata`'s JSON is
/// the payload here.
pub(crate) fn run_capture(cwd: &Path, program: &str, argv: &[&str]) -> anyhow::Result<String> {
    println!("+ {program} {}", argv.join(" "));
    let output = std::process::Command::new(program)
        .args(argv)
        .current_dir(cwd)
        .output()
        .with_context(|| format!("could not run {program}"))?;
    if !output.status.success() {
        bail!("{program} {} failed with {}", argv.join(" "), output.status);
    }
    String::from_utf8(output.stdout).with_context(|| format!("{program} stdout is not UTF-8"))
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
