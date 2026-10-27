//! Optionally bake the Tailcat adapter into the engine binary (see
//! [`embed_tailcat_adapter`]).

use std::{env, path::PathBuf};

fn main() {
    embed_tailcat_adapter();
}

/// `ROBOCO_TAILCAT_EMBED=<absolute path>` bakes that `roboco-tailcat` build
/// into the engine (`src/tailcat.rs` extracts it on first use). Windows
/// release packaging sets it: in-app updates replace only `roboco.exe`, so a
/// companion file beside it went missing or stale. Unset, nothing is embedded
/// and the adapter is looked up beside the executable as before.
fn embed_tailcat_adapter() {
    println!("cargo:rerun-if-env-changed=ROBOCO_TAILCAT_EMBED");
    println!("cargo:rustc-check-cfg=cfg(roboco_tailcat_embedded)");
    let Some(adapter) = env::var_os("ROBOCO_TAILCAT_EMBED").filter(|value| !value.is_empty())
    else {
        return;
    };
    let adapter = PathBuf::from(adapter);
    assert!(
        adapter.is_absolute() && adapter.is_file(),
        "ROBOCO_TAILCAT_EMBED must be the absolute path of a built roboco-tailcat, not {}",
        adapter.display()
    );
    println!("cargo:rerun-if-changed={}", adapter.display());
    println!(
        "cargo:rustc-env=ROBOCO_TAILCAT_EMBED_FILE={}",
        adapter.display()
    );
    println!("cargo:rustc-cfg=roboco_tailcat_embedded");
}
