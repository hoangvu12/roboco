//! Re-export shim: the engine registry moved to the GPUI-free
//! `roboco-engine-client` crate (its Rust twin of `@roboco/engine-client`).
//! Every historical `crate::engine_registry::*` path keeps resolving, plus
//! the mobile seams (session store, pairing resolvers, registry options).

pub use roboco_engine_client::registry::*;
pub use roboco_engine_client::preview_proxy::PreviewProxy;
pub use roboco_engine_client::{
    FileSessionStore, LocalEngine, PairingResolver, RegistryOptions, ResolvedPairing, SessionStore,
};
/// The shared client proxy's listener status (re-exported so views need no
/// direct `roboco-preview-relay` dependency).
pub use roboco_preview_relay::client::ClientStatus;
