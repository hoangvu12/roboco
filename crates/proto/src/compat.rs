//! Client-side compatibility assessment against an engine's [`EngineInfo`].
//!
//! Pure and dependency-free so any client (mobile, desktop) can call it
//! right after the first `EngineInfo` round trip. The mobile client is the
//! first consumer: it is installed separately from its engine and will meet
//! engines both older and newer than itself.

use crate::EngineInfo;

/// What the client knows about itself (compile-time constants on each client).
pub struct ClientBuild<'a> {
    /// The release the client was built with, e.g. its `CARGO_PKG_VERSION`.
    /// Roboco releases the app and the engine from one tag, so this is also
    /// the engine version the client was tested with.
    pub version: &'a str,
    /// The `PROTOCOL_VERSION` the client was built with.
    pub protocol_version: u32,
    /// The oldest engine protocol generation the client can drive.
    pub min_engine_protocol_version: u32,
}

/// The verdict for one engine, from [`assess`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Compatibility {
    /// Same or older-but-compatible engine. Features still gate on
    /// capabilities.
    Compatible,
    /// The engine is newer (by protocol generation, or by major.minor
    /// version). Non-blocking: keep working, decode leniently, show a notice
    /// once per engine version.
    NewerEngine { engine_version: Option<String> },
    /// The engine refuses this client's generation. Blocking: park the
    /// connection (no reconnect loop), like an identity mismatch.
    ClientTooOld {
        required: u32,
        engine_version: Option<String>,
    },
    /// The engine predates what this client can drive. Blocking: park the
    /// connection, like an identity mismatch.
    EngineTooOld {
        engine_protocol: u32,
        engine_version: Option<String>,
    },
}

/// Assess one engine against one client build, evaluated in this order:
///
/// 1. `info.min_client_protocol() > client.protocol_version` →
///    [`Compatibility::ClientTooOld`].
/// 2. `info.protocol() < client.min_engine_protocol_version` →
///    [`Compatibility::EngineTooOld`].
/// 3. `info.protocol() > client.protocol_version` →
///    [`Compatibility::NewerEngine`].
/// 4. Both versions parse and the engine's `(major, minor)` is greater than
///    the client's → [`Compatibility::NewerEngine`]. A newer *patch* is
///    silent; an unparseable version never blocks.
/// 5. Otherwise [`Compatibility::Compatible`]. An older engine is never
///    flagged as a whole: each missing feature says so individually when its
///    capability is absent.
pub fn assess(info: &EngineInfo, client: &ClientBuild<'_>) -> Compatibility {
    let engine_version = info.version.clone();
    // Rule order matters: `ClientTooOld` must win over `NewerEngine` when
    // both apply.
    let required = info.min_client_protocol();
    if required > client.protocol_version {
        return Compatibility::ClientTooOld {
            required,
            engine_version,
        };
    }
    let engine_protocol = info.protocol();
    if engine_protocol < client.min_engine_protocol_version {
        return Compatibility::EngineTooOld {
            engine_protocol,
            engine_version,
        };
    }
    if engine_protocol > client.protocol_version {
        return Compatibility::NewerEngine { engine_version };
    }
    if let (Some(engine), Some(client_version)) = (
        info.version.as_deref().and_then(parse_version),
        parse_version(client.version),
    ) {
        if (engine.0, engine.1) > (client_version.0, client_version.1) {
            return Compatibility::NewerEngine { engine_version };
        }
    }
    Compatibility::Compatible
}

/// `major.minor.patch`; a leading `v` and any `-suffix`/`+build` are ignored;
/// missing parts are 0. Unlike [`crate::version_triple`] — the strict fleet
/// feature-gate primitive that reads a short version as "too old" — this
/// lenient form only feeds the client notice rules, where an unparseable
/// version never blocks.
pub fn parse_version(text: &str) -> Option<(u32, u32, u32)> {
    let core = text.trim().trim_start_matches('v');
    let core = core.split(['-', '+', ' ']).next()?;
    let mut it = core.split('.');
    let major = it.next()?.parse().ok()?;
    let minor = it.next().map_or(Some(0), |p| p.parse().ok())?;
    let patch = it.next().map_or(Some(0), |p| p.parse().ok())?;
    Some((major, minor, patch))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::WorkspaceScope;

    fn engine(version: Option<&str>, protocol: Option<u32>, min_client: Option<u32>) -> EngineInfo {
        EngineInfo {
            device_id: "engine".into(),
            workspace_scope: WorkspaceScope::Local,
            cursor_sdk_version: None,
            capabilities: Vec::new(),
            version: version.map(Into::into),
            protocol_version: protocol,
            min_client_protocol_version: min_client,
        }
    }

    fn client() -> ClientBuild<'static> {
        ClientBuild {
            version: "0.9.2",
            protocol_version: 1,
            min_engine_protocol_version: 1,
        }
    }

    #[test]
    fn assess_matches_the_documented_table() {
        let cases = [
            (
                engine(Some("0.9.2"), Some(1), Some(1)),
                Compatibility::Compatible,
            ),
            // A newer patch is silent.
            (
                engine(Some("0.9.7"), Some(1), Some(1)),
                Compatibility::Compatible,
            ),
            (
                engine(Some("0.10.0"), Some(1), Some(1)),
                Compatibility::NewerEngine {
                    engine_version: Some("0.10.0".into()),
                },
            ),
            (
                engine(Some("1.0.0"), Some(1), Some(1)),
                Compatibility::NewerEngine {
                    engine_version: Some("1.0.0".into()),
                },
            ),
            // An older engine is never flagged as a whole.
            (
                engine(Some("0.8.9"), Some(1), Some(1)),
                Compatibility::Compatible,
            ),
            // A pre-field engine decodes as protocol 1 and stays usable.
            (engine(None, None, None), Compatibility::Compatible),
            (
                engine(None, Some(2), Some(1)),
                Compatibility::NewerEngine {
                    engine_version: None,
                },
            ),
            (
                engine(None, Some(2), Some(2)),
                Compatibility::ClientTooOld {
                    required: 2,
                    engine_version: None,
                },
            ),
            // Rule 1 (ClientTooOld) wins over the newer-engine rules.
            (
                engine(Some("0.10.0"), Some(1), Some(2)),
                Compatibility::ClientTooOld {
                    required: 2,
                    engine_version: Some("0.10.0".into()),
                },
            ),
            // An unparseable version never blocks.
            (
                engine(Some("garbage"), Some(1), Some(1)),
                Compatibility::Compatible,
            ),
        ];
        for (info, expected) in cases {
            assert_eq!(assess(&info, &client()), expected);
        }
    }

    #[test]
    fn assess_flags_an_engine_older_than_the_client_can_drive() {
        let strict = ClientBuild {
            version: "0.9.2",
            protocol_version: 1,
            min_engine_protocol_version: 2,
        };
        // A pre-field engine reads as protocol 1, below the client's floor.
        assert_eq!(
            assess(&engine(None, None, None), &strict),
            Compatibility::EngineTooOld {
                engine_protocol: 1,
                engine_version: None,
            }
        );
    }

    #[test]
    fn parse_version_accepts_lenient_forms() {
        assert_eq!(parse_version("0.9.2"), Some((0, 9, 2)));
        assert_eq!(parse_version("v0.10.0"), Some((0, 10, 0)));
        assert_eq!(parse_version("1.2.3-beta.1"), Some((1, 2, 3)));
        assert_eq!(parse_version("1.2+abc"), Some((1, 2, 0)));
        assert_eq!(parse_version("2"), Some((2, 0, 0)));
        assert_eq!(parse_version(""), None);
        assert_eq!(parse_version("abc"), None);
    }
}
