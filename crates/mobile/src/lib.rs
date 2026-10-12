//! Roboco mobile core — the UniFFI surface shared by the Android app.
//!
//! - [`layout`]: analytic transcript layout — markdown → measured display lists
//!   (wraps `roboco-markdown` + `roboco-text`).
//! - [`wallpaper`]: chat wallpaper effects and the contrast guard.
//! - [`attachments`]: pure attachment-transport helpers (the layout's user
//!   message parsing; the mobile client reuses them).

uniffi::setup_scaffolding!("roboco_core");

pub mod layout;
pub mod attachments;
pub mod wallpaper;

/// Version handshake: the Kotlin bindings must match the linked library.
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}
