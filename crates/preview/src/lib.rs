//! Project-scoped HTTP discovery, stable local routing, and peer transport primitives.
//! The engine discovers and proxies local previews without a cloud coordinator.
pub mod catalog;
pub mod discovery;
pub mod login;
pub mod mux;
pub mod peer;
pub mod proxy;

pub mod service;
pub use service::PreviewService;
