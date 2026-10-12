//! `roboco-engine-client` — the GPUI-free client side of the engine
//! connection: pairing, session persistence, engine-scoped identities,
//! supervised reconnects, and the disposable per-engine cache.
//!
//! This is the Rust twin of `@roboco/engine-client` (web). The desktop UI
//! links it through `roboco-ui`; the Android mobile core links it directly.
//! No GPUI crate and (outside the Tailcat pairing shim that lives in
//! `roboco-ui`) no `roboco-engine` dependency may appear in its tree.

pub mod cache;
pub mod registry;
pub mod routing;
