//! `cargo xtask android-licenses` (android-ui-parity #101): the Rust side
//! of the Android Licenses screen's "Rust core" section.
//!
//! Turns `cargo metadata` into a small JSON file
//! (`assets/licenses/rust-crates.json`) listing every **external** crate
//! linked into `libroboco_mobile.so`, with its licence expression and the
//! full text of the licence files it bundles. Gradle's `RustLicensesTask`
//! calls this with the same `--languages` value it passes to
//! `android-core`; the Licenses screen parses the file with Android's
//! built-in `org.json` (ticket 101's rule: no parsing library in the app).
//!
//! ```text
//! cargo xtask android-licenses --out FILE     required; relative to the repo root
//!                           [--languages mobile|all]   default mobile
//! ```
//!
//! Output (compact, deterministic byte-for-byte for one lockfile):
//!
//! ```json
//! {"schema":1,"crates":[{"name":"anyhow","version":"1.0.104",
//!   "license":"MIT OR Apache-2.0","repository":"https://github.com/dtolnay/anyhow",
//!   "texts":[0]}],"texts":["…"]}
//! ```
//!
//! How the crate list is computed:
//!
//! - `cargo metadata --format-version 1 --locked --manifest-path
//!   crates/mobile/Cargo.toml --filter-platform <target>` for
//!   `aarch64-linux-android` and `x86_64-linux-android`, with the language
//!   feature flags; the two results are unioned by package id;
//! - breadth-first over `resolve.nodes` from `roboco-mobile`, following
//!   only `deps[]` whose `dep_kinds` contain a `{"kind": null}` entry
//!   (normal dependencies — dev and build edges are skipped);
//! - packages with `"source": null` (this workspace's own path crates —
//!   covered by the screen's "Roboco" entry) and `roboco-mobile` itself
//!   are excluded;
//! - the list is a **superset**: the resolve graph is resolved for the
//!   whole workspace, so optional dependencies other members enable can
//!   appear. Over-listing licences is harmless; under-listing is not.
//!   Never hand-filter it.
//!
//! Licence texts: regular files in the crate's manifest directory whose
//! lower-cased name starts with `license`, `licence`, `copying`, `notice`,
//! `copyright` or `unlicense`, plus `license_file` (relative to that
//! directory) when the manifest sets it — sorted by file name, read as
//! UTF-8 lossy, `\r\n` → `\n`, trailing whitespace trimmed. Identical
//! texts de-duplicate into the shared `texts` array, in first-appearance
//! order over the sorted crate list.
//!
//! `serde_json` is a deliberate, light exception to this crate's
//! "std + anyhow" rule (noted in ticket 101's Comments): it is already in
//! `Cargo.lock` and parsing `cargo metadata` by hand is error-prone.

use std::collections::{HashMap, HashSet, VecDeque};
use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, bail};
use serde_json::{Value, json};

/// The mobile crate whose closure we list.
const MOBILE_CRATE: &str = "roboco-mobile";

/// The Android targets `libroboco_mobile.so` is built for.
const ANDROID_TARGETS: &[&str] = &["aarch64-linux-android", "x86_64-linux-android"];

/// The schema number of the output file; the Kotlin parser rejects others.
const SCHEMA: i64 = 1;

/// File-name prefixes (lower-cased) that mark a bundled licence file.
const LICENCE_NAME_PREFIXES: &[&str] = &[
    "license",
    "licence",
    "copying",
    "notice",
    "copyright",
    "unlicense",
];

pub(crate) fn run(argv: &[String]) -> anyhow::Result<()> {
    let root = crate::repo_root();
    let parsed = parse_args(argv)?;

    // A relative --out resolves against the repo root (Gradle runs from
    // apps/android), like android-core's --out.
    let out = if parsed.out.is_absolute() {
        parsed.out
    } else {
        root.join(&parsed.out)
    };

    println!(
        "android-licenses: languages={} out={}",
        parsed.languages,
        out.display()
    );

    // 1. cargo metadata per Android target, unioned by package id.
    let mut metadatas: Vec<Value> = Vec::new();
    for target in ANDROID_TARGETS {
        let mut argv_meta = vec![
            "metadata".to_owned(),
            "--format-version".to_owned(),
            "1".to_owned(),
            "--locked".to_owned(),
            "--manifest-path".to_owned(),
            "crates/mobile/Cargo.toml".to_owned(),
            "--filter-platform".to_owned(),
            target.to_owned(),
        ];
        match parsed.languages.as_str() {
            "all" => {
                argv_meta.push("--no-default-features".to_owned());
                argv_meta.push("--features".to_owned());
                argv_meta.push("all-languages".to_owned());
            }
            _ => {}
        }
        let argv_meta = argv_meta.iter().map(String::as_str).collect::<Vec<_>>();
        let stdout = crate::run_capture(root, &crate::cargo(), &argv_meta)
            .with_context(|| format!("cargo metadata for {target}"))?;
        let value: Value = serde_json::from_str(&stdout)
            .with_context(|| format!("parsing cargo metadata for {target}"))?;
        metadatas.push(value);
    }

    // 2. The external crate closure from roboco-mobile, sorted.
    let packages = resolve_external_packages(&metadatas)?;

    // 3. Licence files per crate (texts read here, de-duplicated in
    //    assemble_output by first appearance over the sorted crate list).
    let mut crate_texts: Vec<Vec<String>> = Vec::with_capacity(packages.len());
    for package in &packages {
        let manifest = PathBuf::from(&package.manifest_path);
        let dir = manifest.parent().unwrap_or_else(|| Path::new(""));
        if !dir.is_dir() {
            bail!(
                "manifest directory of {} ({}) does not exist — run the core build first",
                package.name,
                dir.display()
            );
        }
        let mut texts = Vec::new();
        for path in licence_file_paths(dir, package.license_file.as_deref())? {
            let bytes = fs::read(&path).with_context(|| format!("reading {}", path.display()))?;
            let text = normalise_licence_text(&String::from_utf8_lossy(&bytes));
            texts.push(text);
        }
        crate_texts.push(texts);
    }

    // 4. Compact JSON, deterministic for one lockfile.
    let output = assemble_output(&packages, &crate_texts);
    let bytes = serde_json::to_string(&output).context("serialising rust-crates.json")?;
    if let Some(parent) = out.parent() {
        fs::create_dir_all(parent).with_context(|| format!("creating {}", parent.display()))?;
    }
    fs::write(&out, bytes).with_context(|| format!("writing {}", out.display()))?;
    println!(
        "android-licenses: {} crates, {} licence texts",
        packages.len(),
        output["texts"].as_array().map(Vec::len).unwrap_or(0)
    );
    Ok(())
}

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

/// The task's flags: `--out FILE --languages mobile|all`.
struct Args {
    out: PathBuf,
    languages: String,
}

fn parse_args(argv: &[String]) -> anyhow::Result<Args> {
    let mut out: Option<PathBuf> = None;
    let mut languages = "mobile".to_owned();
    let mut index = 0;
    while index < argv.len() {
        let arg = argv[index].as_str();
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
            "--languages" => {
                languages = value()?;
                if languages != "mobile" && languages != "all" {
                    bail!("unknown languages {languages:?} (expected mobile or all)");
                }
            }
            "--help" | "-h" => bail!("{USAGE}"),
            _ => bail!("unknown flag {arg:?}\n{USAGE}"),
        }
        index += 1;
    }
    Ok(Args {
        out: out.ok_or_else(|| anyhow::anyhow!("--out is required\n{USAGE}"))?,
        languages,
    })
}

const USAGE: &str = "usage: cargo xtask android-licenses --out FILE [--languages mobile|all]";

// ---------------------------------------------------------------------------
// The crate closure (pure, testable)
// ---------------------------------------------------------------------------

/// One external crate the BFS reached, with the fields the output needs.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct ExternalPackage {
    pub(crate) id: String,
    pub(crate) name: String,
    pub(crate) version: String,
    pub(crate) license: Option<String>,
    pub(crate) repository: Option<String>,
    pub(crate) license_file: Option<String>,
    pub(crate) manifest_path: String,
}

/// The external packages of `roboco-mobile`'s normal-dependency closure
/// over every metadata output, sorted by `(name.to_lowercase(), version)`.
///
/// Dev and build dependency edges are skipped, `"source": null` packages
/// (path and workspace crates) are excluded, and `roboco-mobile` itself
/// never appears. A diamond (two crates sharing a dependency) is visited
/// once.
pub(crate) fn resolve_external_packages(metadatas: &[Value]) -> anyhow::Result<Vec<ExternalPackage>> {
    // id → package, unioned over the targets (identical for the same id).
    let mut packages: HashMap<String, &Value> = HashMap::new();
    for metadata in metadatas {
        for package in metadata["packages"].as_array().into_iter().flatten() {
            if let Some(id) = package["id"].as_str() {
                packages.insert(id.to_owned(), package);
            }
        }
    }

    // roboco-mobile's package id: the BFS root.
    let root_id = packages
        .values()
        .find(|package| package["name"].as_str() == Some(MOBILE_CRATE))
        .and_then(|package| package["id"].as_str())
        .map(str::to_owned)
        .ok_or_else(|| anyhow::anyhow!("package {MOBILE_CRATE} not found in cargo metadata"))?;

    // nodes by id for every target (the graphs differ; union the visits).
    let mut visited: HashSet<String> = HashSet::new();
    let mut queue: VecDeque<String> = VecDeque::new();
    queue.push_back(root_id.clone());
    while let Some(id) = queue.pop_front() {
        if !visited.insert(id.clone()) {
            continue;
        }
        for metadata in metadatas {
            let Some(nodes) = metadata["resolve"]["nodes"].as_array() else {
                continue;
            };
            let Some(node) = nodes.iter().find(|node| node["id"].as_str() == Some(id.as_str())) else {
                continue;
            };
            for dep in node["deps"].as_array().into_iter().flatten() {
                // Only normal dependencies: some dep_kinds entry with
                // "kind": null (dev and build edges are skipped).
                let normal = dep["dep_kinds"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .any(|kind| kind.get("kind").map(Value::is_null).unwrap_or(false));
                if normal {
                    if let Some(pkg) = dep["pkg"].as_str() {
                        if !visited.contains(pkg) {
                            queue.push_back(pkg.to_owned());
                        }
                    }
                }
            }
        }
    }

    // External packages only: source != null, not roboco-mobile itself.
    let mut external: Vec<ExternalPackage> = visited
        .iter()
        .filter_map(|id| {
            let package = packages.get(id)?;
            if id == &root_id {
                return None;
            }
            // Path/workspace crates have "source": null — covered by the
            // screen's "Roboco" entry.
            if package.get("source").map(Value::is_null).unwrap_or(true) {
                return None;
            }
            Some(ExternalPackage {
                id: id.clone(),
                name: package["name"].as_str().unwrap_or_default().to_owned(),
                version: package["version"].as_str().unwrap_or_default().to_owned(),
                license: package["license"].as_str().map(str::to_owned),
                repository: package["repository"].as_str().map(str::to_owned),
                license_file: package["license_file"].as_str().map(str::to_owned),
                manifest_path: package["manifest_path"].as_str().unwrap_or_default().to_owned(),
            })
        })
        .collect();
    external.sort_by(|a, b| {
        (a.name.to_lowercase(), a.version.as_str()).cmp(&(b.name.to_lowercase(), b.version.as_str()))
    });
    Ok(external)
}

// ---------------------------------------------------------------------------
// The output file (pure, testable)
// ---------------------------------------------------------------------------

/// The output JSON: texts de-duplicated by exact string into the shared
/// `texts` array — assigned in first-appearance order while iterating the
/// **sorted** [packages] — each crate referencing the indices it owns.
/// Empty texts (a licence file that normalised to nothing) are skipped.
pub(crate) fn assemble_output(packages: &[ExternalPackage], crate_texts: &[Vec<String>]) -> Value {
    let mut texts: Vec<String> = Vec::new();
    let mut seen: HashMap<String, usize> = HashMap::new();
    let mut crates: Vec<Value> = Vec::with_capacity(packages.len());
    for (package, package_texts) in packages.iter().zip(crate_texts) {
        let mut indices: Vec<Value> = Vec::new();
        for text in package_texts {
            if text.is_empty() {
                continue;
            }
            let index = *seen.entry(text.clone()).or_insert_with(|| {
                texts.push(text.clone());
                texts.len() - 1
            });
            indices.push(json!(index));
        }
        crates.push(json!({
            "name": package.name,
            "version": package.version,
            "license": package.license,
            "repository": package.repository,
            "texts": indices,
        }));
    }
    json!({"schema": SCHEMA, "crates": crates, "texts": texts})
}

// ---------------------------------------------------------------------------
// Licence files (pure, testable)
// ---------------------------------------------------------------------------

/// The licence files of the crate rooted at [dir]: regular files whose
/// lower-cased name starts with a [LICENCE_NAME_PREFIXES] entry, plus
/// [license_file] (relative to [dir]) when set and not already matched,
/// sorted by file name. An unreadable directory is an error — a silently
/// empty licence list is the under-listing failure mode this task must
/// avoid.
pub(crate) fn licence_file_paths(dir: &Path, license_file: Option<&str>) -> anyhow::Result<Vec<PathBuf>> {
    let mut paths: Vec<PathBuf> = Vec::new();
    let entries = fs::read_dir(dir).with_context(|| format!("reading {}", dir.display()))?;
    for entry in entries {
        let entry = entry.with_context(|| format!("reading {}", dir.display()))?;
        let path = entry.path();
        if !entry.file_type().is_ok_and(|kind| kind.is_file()) {
            continue;
        }
        let name = entry.file_name().to_string_lossy().to_lowercase();
        if LICENCE_NAME_PREFIXES.iter().any(|prefix| name.starts_with(prefix)) {
            paths.push(path);
        }
    }
    if let Some(license_file) = license_file {
        let explicit = dir.join(license_file);
        if !paths.iter().any(|path| path == &explicit) {
            paths.push(explicit);
        }
    }
    paths.sort();
    Ok(paths)
}

/// `\r\n` → `\n`, trailing whitespace trimmed; empty stays empty.
pub(crate) fn normalise_licence_text(raw: &str) -> String {
    raw.replace("\r\n", "\n").trim_end().to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn metadata(
        packages: &[(&str, &str, Option<&str>, Option<&str>)],
        nodes: &[(&str, &[(&str, &str)])],
    ) -> Value {
        // packages: (id, name, source, license); nodes: (id, deps of (pkg, kind))
        let packages_json: Vec<Value> = packages
            .iter()
            .copied()
            .map(|(id, name, source, license)| {
                json!({
                    "id": id,
                    "name": name,
                    "version": "1.0.0",
                    "license": license,
                    "license_file": Value::Null,
                    "repository": format!("https://example.com/{name}"),
                    "manifest_path": format!("/tmp/{name}/Cargo.toml"),
                    "source": source,
                })
            })
            .collect();
        let nodes_json: Vec<Value> = nodes
            .iter()
            .copied()
            .map(|(id, deps)| {
                let deps_json: Vec<Value> = deps
                    .iter()
                    .map(|&(pkg, kind)| {
                        let dep_kind = match kind {
                            "dev" | "build" => json!(kind),
                            _ => Value::Null,
                        };
                        json!({"name": "dep", "pkg": pkg, "dep_kinds": [{"kind": dep_kind, "target": Value::Null}]})
                    })
                    .collect();
                json!({"id": id, "dependencies": [], "deps": deps_json})
            })
            .collect();
        json!({"packages": packages_json, "resolve": {"root": "mobile", "nodes": nodes_json}})
    }

    /// The BFS: a dev-only edge is skipped, a path crate (source null) is
    /// excluded, a diamond is visited once, roboco-mobile itself never
    /// appears.
    #[test]
    fn bfs_skips_dev_edges_excludes_path_crates_and_visits_diamonds_once() {
        let value = metadata(
            &[
                ("mobile", "roboco-mobile", None, None),
                ("anyhow", "anyhow", Some("registry+https://github.com/rust-lang/crates.io-index"), Some("MIT OR Apache-2.0")),
                ("serde", "serde", Some("registry+https://github.com/rust-lang/crates.io-index"), Some("MIT OR Apache-2.0")),
                ("syn", "syn", Some("registry+https://github.com/rust-lang/crates.io-index"), Some("MIT OR Apache-2.0")),
                ("criterion", "criterion", Some("registry+https://github.com/rust-lang/crates.io-index"), Some("Apache-2.0")),
                ("text", "roboco-text", None, None),
            ],
            &[
                ("mobile", &[("anyhow", "normal"), ("serde", "normal"), ("text", "normal"), ("criterion", "dev")]),
                ("anyhow", &[("syn", "normal")]),
                ("serde", &[("syn", "normal")]),
                ("syn", &[]),
                ("criterion", &[]),
                ("text", &[]),
            ],
        );
        let external = resolve_external_packages(&[value]).expect("resolve");
        let names: Vec<&str> = external.iter().map(|p| p.name.as_str()).collect();
        // anyhow and serde both reach syn (the diamond); syn appears once.
        assert_eq!(names, ["anyhow", "serde", "syn"]);
        // roboco-mobile and the path crate roboco-text are excluded.
        assert!(!external.iter().any(|p| p.name == "roboco-mobile"));
        assert!(!external.iter().any(|p| p.name == "roboco-text"));
        // Sorted case-insensitively by name.
        assert!(external.windows(2).all(|pair| {
            let (a, b) = (&pair[0], &pair[1]);
            (a.name.to_lowercase(), a.version.as_str()) <= (b.name.to_lowercase(), b.version.as_str())
        }));
    }

    /// Two metadata outputs (the two Android targets) are unioned by id.
    #[test]
    fn the_two_target_graphs_are_unioned() {
        let a = metadata(
            &[
                ("mobile", "roboco-mobile", None, None),
                ("anyhow", "anyhow", Some("registry+https://github.com/rust-lang/crates.io-index"), Some("MIT OR Apache-2.0")),
            ],
            &[("mobile", &[("anyhow", "normal")])],
        );
        // The second target adds one crate the first does not reach.
        let b = metadata(
            &[
                ("mobile", "roboco-mobile", None, None),
                ("zstd", "zstd", Some("registry+https://github.com/rust-lang/crates.io-index"), Some("MIT OR Apache-2.0")),
            ],
            &[("mobile", &[("zstd", "normal")])],
        );
        let external = resolve_external_packages(&[a, b]).expect("resolve");
        let mut names: Vec<&str> = external.iter().map(|p| p.name.as_str()).collect();
        names.sort();
        assert_eq!(names, ["anyhow", "zstd"]);
    }

    /// A missing roboco-mobile fails loudly instead of writing an empty file.
    #[test]
    fn missing_mobile_package_fails() {
        let value = metadata(
            &[("anyhow", "anyhow", Some("registry+https://github.com/rust-lang/crates.io-index"), None)],
            &[("anyhow", &[])],
        );
        assert!(resolve_external_packages(&[value]).is_err());
    }

    /// The licence-file name filter: prefixes, case-insensitivity,
    /// directories excluded, sorted; `license_file` joins in when not
    /// already matched and never duplicates. An unreadable dir is an error.
    #[test]
    fn licence_file_names_and_sorting() {
        let dir = std::env::temp_dir().join("xtask-android-licenses-test");
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(dir.join("src")).expect("mkdir");
        for name in [
            "Cargo.toml",
            "README.md",
            "LICENSE",
            "LICENSE-MIT",
            "LICENCE.md",
            "COPYING.txt",
            "COPYRIGHT",
            "NOTICE",
            "UNLICENSE",
            "license-file",
        ] {
            fs::write(dir.join(name), "text\n").expect("write");
        }
        let mut paths = licence_file_paths(&dir, Some("license-file")).expect("licence files");
        let names: Vec<String> = paths
            .iter()
            .map(|p| p.file_name().unwrap().to_string_lossy().into_owned())
            .collect();
        // Byte-wise sort: upper-case before lower-case ("license-file" last).
        assert_eq!(
            names,
            ["COPYING.txt", "COPYRIGHT", "LICENCE.md", "LICENSE", "LICENSE-MIT", "NOTICE", "UNLICENSE", "license-file"]
        );
        // The src directory is never matched.
        assert_eq!(paths.len(), 8);
        // license_file already matched by name → no duplicate.
        paths = licence_file_paths(&dir, Some("LICENSE")).expect("licence files");
        assert_eq!(paths.len(), 8);
        // An unmatched license_file joins in (it need not exist on disk to
        // be listed; the reader fails loudly on a missing file).
        paths = licence_file_paths(&dir, Some("docs/COPYING.adoc")).expect("licence files");
        assert!(paths.iter().any(|p| p.ends_with("COPYING.adoc")));
        // A missing directory is an error, never a silently empty list.
        assert!(licence_file_paths(&dir.join("nope"), None).is_err());
    }

    /// Text de-duplication: identical texts collapse into one shared entry
    /// (first appearance over the sorted crate list), empties are skipped,
    /// and the JSON carries the ticket's schema-1 shape.
    #[test]
    fn texts_deduplicate_by_exact_string() {
        let packages = vec![
            ExternalPackage {
                id: "p1".to_owned(),
                name: "anyhow".to_owned(),
                version: "1.0.104".to_owned(),
                license: Some("MIT OR Apache-2.0".to_owned()),
                repository: Some("https://github.com/dtolnay/anyhow".to_owned()),
                license_file: None,
                manifest_path: String::new(),
            },
            ExternalPackage {
                id: "p2".to_owned(),
                name: "serde".to_owned(),
                version: "1.0.229".to_owned(),
                license: Some("MIT OR Apache-2.0".to_owned()),
                repository: None,
                license_file: None,
                manifest_path: String::new(),
            },
            ExternalPackage {
                id: "p3".to_owned(),
                name: "uniffi".to_owned(),
                version: "0.32.2".to_owned(),
                license: Some("MPL-2.0".to_owned()),
                repository: None,
                license_file: None,
                manifest_path: String::new(),
            },
        ];
        // anyhow and serde share both texts; uniffi adds one; one crate
        // bundles an empty text (skipped).
        let crate_texts = vec![
            vec!["MIT".to_owned(), "Apache".to_owned()],
            vec!["Apache".to_owned(), "MIT".to_owned()],
            vec!["MPL-2.0".to_owned(), String::new()],
        ];
        let output = assemble_output(&packages, &crate_texts);

        // First appearance over the sorted crates: anyhow's "MIT" (index 0)
        // then "Apache" (1); serde reuses both, in its own order; uniffi's
        // empties are skipped and MPL-2.0 becomes index 2.
        assert_eq!(output["texts"], json!(["MIT", "Apache", "MPL-2.0"]));
        let crates = output["crates"].as_array().expect("crates");
        assert_eq!(
            crates[0]["texts"],
            json!([0, 1]),
            "anyhow keeps its own order into the shared texts"
        );
        assert_eq!(crates[1]["texts"], json!([1, 0]), "serde reuses the shared texts");
        assert_eq!(crates[2]["texts"], json!([2]), "empty texts are skipped");
        assert_eq!(output["schema"], json!(1));
        // The crate fields round-trip, repository null when absent.
        assert_eq!(crates[1]["repository"], Value::Null);
        assert_eq!(crates[0]["repository"], json!("https://github.com/dtolnay/anyhow"));
    }

    /// `\r\n` → `\n` and trailing whitespace trimmed.
    #[test]
    fn licence_text_normalisation() {
        assert_eq!(normalise_licence_text("MIT\r\n\r\n"), "MIT\n");
        assert_eq!(normalise_licence_text(" MIT \n\n"), " MIT");
        assert_eq!(normalise_licence_text(""), "");
    }
}
