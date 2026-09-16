//! The engine-served web client bundle (ADR 0006).
//!
//! The pages under `assets/web` are a hand-written placeholder that proves
//! the browser path end-to-end; the real React build output takes their place
//! without touching this plumbing. Embedding keeps the release a single
//! binary with no runtime file dependency.

use rust_embed::RustEmbed;

#[derive(RustEmbed)]
#[folder = "assets/web"]
pub(crate) struct WebAssets;
