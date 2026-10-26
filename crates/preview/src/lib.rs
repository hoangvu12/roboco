//! Project-scoped HTTP discovery, stable local routing, and peer transport primitives.
//! The engine discovers and proxies local previews without a cloud coordinator.
pub mod catalog;
pub mod discovery;
pub mod login;
pub mod peer;
pub mod proxy;

// The mux frame protocol moved to `roboco-preview-relay` (Phase C, ticket 04)
// so client crates can speak the relay without linking this crate's
// engine-only stack (process discovery, the dormant WebRTC peers). The
// re-export keeps every historical `roboco_preview::mux` / `crate::mux`
// path — engine, tests and peers — resolving unchanged.
pub use roboco_preview_relay::mux;

pub mod service;
pub use service::{PreviewService, Scope};
