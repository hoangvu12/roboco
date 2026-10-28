//! One UniFFI for the generator and the runtime (ci-speed#07).
//!
//! `uniffi-bindgen` (`crates/mobile-bindgen`) reads the metadata that
//! `roboco-mobile`'s `uniffi` writes into the built library, so the two
//! crates must resolve the same `uniffi`. Both manifests name the same
//! requirement; this check reads `Cargo.lock`, which is what the builds
//! actually use, and fails when the two resolve differently.

use anyhow::bail;

/// The crate whose library the generator reads.
const RUNTIME: &str = "roboco-mobile";
/// The generator's crate.
const GENERATOR: &str = "roboco-mobile-bindgen";

/// Fails unless [RUNTIME] and [GENERATOR] lock the same `uniffi`.
pub fn check(lock: &str) -> anyhow::Result<()> {
    let runtime = uniffi_dependency(lock, RUNTIME)?;
    let generator = uniffi_dependency(lock, GENERATOR)?;
    if runtime != generator {
        bail!(
            "{GENERATOR} locks {generator:?} but {RUNTIME} locks {runtime:?}: the generator \
             must read the metadata of the uniffi it was built with. Give \
             crates/mobile-bindgen/Cargo.toml the same uniffi requirement as \
             crates/mobile/Cargo.toml"
        );
    }
    Ok(())
}

/// `package`'s `uniffi` entry in the lockfile: `uniffi` while one version
/// is locked, `uniffi <version>` once there are several, so equal entries
/// mean the same version.
fn uniffi_dependency<'a>(lock: &'a str, package: &str) -> anyhow::Result<&'a str> {
    let name_line = format!("name = \"{package}\"");
    let Some(block) = lock
        .split("[[package]]")
        .find(|block| block.lines().any(|line| line.trim() == name_line))
    else {
        bail!("Cargo.lock has no {package} package");
    };
    let entry = block
        .lines()
        .map(|line| line.trim().trim_end_matches(',').trim_matches('"'))
        .find(|entry| *entry == "uniffi" || entry.starts_with("uniffi "));
    match entry {
        Some(entry) => Ok(entry),
        None => bail!("{package} does not depend on uniffi in Cargo.lock"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lock(runtime: &str, generator: &str) -> String {
        format!(
            "version = 4\n\n\
             [[package]]\nname = \"roboco-mobile\"\nversion = \"0.9.2\"\n\
             dependencies = [\n \"roboco-text\",\n \"{runtime}\",\n \"uniffi_core\",\n]\n\n\
             [[package]]\nname = \"roboco-mobile-bindgen\"\nversion = \"0.9.2\"\n\
             dependencies = [\n \"{generator}\",\n]\n\n\
             [[package]]\nname = \"uniffi\"\nversion = \"0.32.0\"\n"
        )
    }

    #[test]
    fn the_workspace_lock_matches() {
        let lock = std::fs::read_to_string(crate::repo_root().join("Cargo.lock")).unwrap();
        check(&lock).unwrap();
    }

    #[test]
    fn one_locked_version_matches() {
        check(&lock("uniffi", "uniffi")).unwrap();
    }

    #[test]
    fn drift_fails() {
        let error = check(&lock("uniffi 0.32.0", "uniffi 0.33.1"))
            .unwrap_err()
            .to_string();
        assert!(error.contains("roboco-mobile-bindgen locks"), "{error}");
    }

    #[test]
    fn a_missing_package_or_dependency_fails() {
        let without_generator = lock("uniffi", "uniffi").replace("roboco-mobile-bindgen", "other");
        assert!(check(&without_generator).is_err());
        // uniffi_core alone is not uniffi.
        let without_uniffi = lock("roboco-doc", "uniffi");
        assert!(check(&without_uniffi).is_err());
    }
}
