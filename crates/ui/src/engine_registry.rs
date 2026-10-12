//! Re-export shim: the engine registry moved to the GPUI-free
//! `roboco-engine-client` crate (its Rust twin of `@roboco/engine-client`).
//! Every historical `crate::engine_registry::*` path keeps resolving.

pub use roboco_engine_client::registry::*;
