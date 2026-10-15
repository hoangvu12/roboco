//! Re-export shim: the engine cache moved to the GPUI-free
//! `roboco-engine-client` crate. Every historical `crate::engine_cache::*`
//! path keeps resolving.

pub use roboco_engine_client::cache::*;
