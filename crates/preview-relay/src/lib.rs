//! The session-relay data plane shared by the engine and every client:
//! the preview mux frame protocol, the HTTP relay hygiene, and the
//! client-side preview proxy (Phase C, ticket 04).
//!
//! Split out of `roboco-preview` so a client (`roboco-engine-client`, which
//! the desktop and the Android mobile core both link) can speak the relay
//! without dragging the engine-only preview stack — process discovery and the
//! dormant WebRTC peers — into a client binary. `roboco-preview` re-exports
//! [`mux`], so the engine-side paths (`roboco_preview::mux`) are unchanged.

pub mod client;
pub mod http;
pub mod mux;

pub use client::ClientProxy;
