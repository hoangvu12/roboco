use crate::wire::BridgeError;
use serde::{Deserialize, Serialize};

pub const PROTOCOL_VERSION: u32 = 1;
pub const DEFAULT_FRAME_BYTES: usize = 1 << 20;
pub const MIN_FRAME_BYTES: usize = 64 << 10;
pub const MAX_FRAME_BYTES: usize = 16 << 20;
pub const DEFAULT_CHUNK_BYTES: usize = 256 << 10;
pub const MIN_CHUNK_BYTES: usize = 1 << 10;
pub const MAX_ID_BYTES: usize = 256;
pub const MAX_PENDING_REQUESTS: usize = 256;
pub const EVENT_HIGH_WATER_BYTES: usize = 4 << 20;
pub const CONTROL_HIGH_WATER_BYTES: usize = 8 << 20;

const HOST_PAGE_MIN_BYTES: usize = 1024;
const HOST_PAGE_MAX_BYTES: usize = 4 << 20;
const HOST_PAGE_MAX_ENTRIES: usize = 4096;
const FRAGMENT_ENVELOPE_RESERVE: usize = 512;

/// What a client proposes in `initialize`. Absent fields take the defaults.
#[derive(Debug, Default, Clone, Copy, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LimitsRequest {
    pub max_frame_bytes: Option<usize>,
    pub chunk_bytes: Option<usize>,
}

/// The negotiated budget every frame in both directions obeys.
///
/// `max_frame_bytes` bounds one newline-delimited frame, excluding the newline.
/// `chunk_bytes` bounds the raw bytes in one detail chunk or one fragment before
/// base64 encoding.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub struct Limits {
    pub max_frame_bytes: usize,
    pub chunk_bytes: usize,
}

impl Default for Limits {
    fn default() -> Self {
        Self {
            max_frame_bytes: DEFAULT_FRAME_BYTES,
            chunk_bytes: DEFAULT_CHUNK_BYTES,
        }
    }
}

impl Limits {
    pub fn negotiate(request: LimitsRequest) -> Result<Self, BridgeError> {
        let max_frame_bytes = request.max_frame_bytes.unwrap_or(DEFAULT_FRAME_BYTES);
        if !(MIN_FRAME_BYTES..=MAX_FRAME_BYTES).contains(&max_frame_bytes) {
            return Err(BridgeError::invalid_params(format!(
                "max_frame_bytes must be between {MIN_FRAME_BYTES} and {MAX_FRAME_BYTES}"
            )));
        }
        let ceiling = Self::raw_ceiling(max_frame_bytes);
        let chunk_bytes = request.chunk_bytes.unwrap_or(DEFAULT_CHUNK_BYTES).min(ceiling);
        if chunk_bytes < MIN_CHUNK_BYTES {
            return Err(BridgeError::invalid_params(format!(
                "chunk_bytes must be at least {MIN_CHUNK_BYTES}"
            )));
        }
        Ok(Self {
            max_frame_bytes,
            chunk_bytes,
        })
    }

    /// The most raw bytes whose base64 form still fits a frame beside its envelope.
    fn raw_ceiling(max_frame_bytes: usize) -> usize {
        (max_frame_bytes - FRAGMENT_ENVELOPE_RESERVE) / 4 * 3
    }

    /// The `max_bytes` to ask the host for when a client asks for a page of
    /// `requested`. A quarter of the frame stays free for the page's cut,
    /// per-entry wrappers and the response envelope.
    pub fn page_bytes(&self, requested: u32) -> u32 {
        let ceiling = (self.max_frame_bytes / 4 * 3).min(HOST_PAGE_MAX_BYTES);
        (requested as usize).clamp(HOST_PAGE_MIN_BYTES, ceiling) as u32
    }

    /// Entry count per page, so a page of entries that only count their
    /// header (oversized ones) still fits a small frame.
    pub fn page_entries(&self, requested: u32) -> u32 {
        let ceiling = (self.max_frame_bytes / 256).clamp(1, HOST_PAGE_MAX_ENTRIES);
        (requested as usize).clamp(1, ceiling) as u32
    }

    pub fn chunk_request(&self, requested: u32) -> u32 {
        (requested as usize).clamp(1, self.chunk_bytes) as u32
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_are_one_mebibyte_and_two_fifty_six_kibibytes() {
        let limits = Limits::negotiate(LimitsRequest::default()).unwrap();
        assert_eq!(limits.max_frame_bytes, 1_048_576);
        assert_eq!(limits.chunk_bytes, 262_144);
    }

    #[test]
    fn chunk_is_clamped_so_its_base64_always_fits_the_frame() {
        for frame in [MIN_FRAME_BYTES, 100_000, DEFAULT_FRAME_BYTES, MAX_FRAME_BYTES] {
            let limits = Limits::negotiate(LimitsRequest {
                max_frame_bytes: Some(frame),
                chunk_bytes: Some(usize::MAX),
            })
            .unwrap();
            let encoded = limits.chunk_bytes.div_ceil(3) * 4;
            assert!(encoded + FRAGMENT_ENVELOPE_RESERVE <= frame, "frame {frame}");
        }
    }

    #[test]
    fn out_of_range_proposals_are_rejected_not_clamped() {
        for request in [
            LimitsRequest { max_frame_bytes: Some(MIN_FRAME_BYTES - 1), chunk_bytes: None },
            LimitsRequest { max_frame_bytes: Some(MAX_FRAME_BYTES + 1), chunk_bytes: None },
            LimitsRequest { max_frame_bytes: None, chunk_bytes: Some(MIN_CHUNK_BYTES - 1) },
        ] {
            assert_eq!(Limits::negotiate(request).unwrap_err().code, -32602);
        }
    }

    #[test]
    fn page_budget_never_leaves_the_host_range() {
        let limits = Limits::default();
        assert_eq!(limits.page_bytes(0), 1024);
        assert_eq!(limits.page_bytes(1), 1024);
        assert_eq!(limits.page_bytes(1023), 1024);
        assert_eq!(limits.page_bytes(1024), 1024);
        assert_eq!(limits.page_bytes(u32::MAX), (DEFAULT_FRAME_BYTES / 4 * 3) as u32);
        assert_eq!(limits.page_bytes(5000), 5000);
        assert_eq!(limits.page_entries(0), 1);
        assert_eq!(limits.page_entries(u32::MAX), 4096);
        let small = Limits::negotiate(LimitsRequest { max_frame_bytes: Some(MIN_FRAME_BYTES), chunk_bytes: None }).unwrap();
        assert_eq!(small.page_entries(u32::MAX), 256);
    }
}
