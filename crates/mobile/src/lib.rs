//! Roboco mobile core — the UniFFI surface shared by the Android app.
//!
//! - [`layout`]: analytic transcript layout — markdown → measured display lists
//!   (wraps `roboco-markdown` + `roboco-text`).
//! - [`wallpaper`]: chat wallpaper effects and the contrast guard.
//! - [`attachments`]: a re-export of `roboco-mobile-client`'s attachment
//!   transport (the layout's user-message parsing; ticket 08 moved the
//!   implementation into the data-plane crate).
//! - [`client_ffi`]: the engine-connection data plane (`CoreClient`,
//!   pairing, per-engine supervision, the offline cache) over
//!   `roboco-mobile-client`.

uniffi::setup_scaffolding!("roboco_core");

pub mod attachments;
pub mod client_ffi;
pub mod layout;
pub mod wallpaper;

/// Version handshake: the Kotlin bindings must match the linked library.
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}
