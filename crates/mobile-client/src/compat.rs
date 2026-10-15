//! The "newer engine" notice and the version-compatibility park rules,
//! driven by `roboco_proto::compat::assess` (ticket 11).
//!
//! On every identity check — connect and each health probe — the client
//! runs [`assess_engine`] and applies the verdict:
//!
//! - `Compatible`: nothing;
//! - `NewerEngine`: a non-blocking notice (decoding is lenient anyway, and
//!   a newer *patch* is silent);
//! - `ClientTooOld` / `EngineTooOld`: `Parked { Incompatible }` with no
//!   reconnect loop; re-assessed on app start, `reconnect_engine`, or a
//!   re-pair.

use roboco_proto::compat::{ClientBuild, Compatibility, assess};
use roboco_proto::{EngineInfo, PROTOCOL_VERSION};

/// The oldest engine protocol generation this client can drive.
pub(crate) const MIN_ENGINE_PROTOCOL_VERSION: u32 = 1;

/// What a compatibility assessment means for one engine's link.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub(crate) struct VersionVerdict {
    /// The "newer engine" notice for `EngineView.notice`.
    pub notice: Option<String>,
    /// Set when the engine must be parked as incompatible.
    pub incompatible: bool,
    /// The park sentence for `EngineView.last_error`.
    pub last_error: Option<String>,
}

/// The client build this crate speaks for. `app_version` is the platform's
/// display version (the Android app's `versionName`); the core and the
/// engine are released from one tag, so it is also the engine version this
/// app was tested with.
pub(crate) fn client_build(app_version: &str) -> ClientBuild<'_> {
    ClientBuild {
        version: app_version,
        protocol_version: PROTOCOL_VERSION,
        min_engine_protocol_version: MIN_ENGINE_PROTOCOL_VERSION,
    }
}

/// Map one engine's `EngineInfo` to the notice/park verdict.
/// `engine_name` is the display name from `EngineView.name` (the engine's
/// own `Device.name`, falling back to the paired label, then the host).
/// `device_version` is the engine's own `Device.version` row: ticket 11's
/// fallback when `EngineInfo.version` is absent (an engine older than the
/// field).
pub(crate) fn assess_engine(
    info: &EngineInfo,
    app_version: &str,
    engine_name: &str,
    device_version: Option<&str>,
) -> VersionVerdict {
    let fallback;
    let info = if info.version.is_none() && device_version.is_some() {
        fallback = EngineInfo {
            version: device_version.map(Into::into),
            ..info.clone()
        };
        &fallback
    } else {
        info
    };
    match assess(info, &client_build(app_version)) {
        Compatibility::Compatible => VersionVerdict::default(),
        Compatibility::NewerEngine { engine_version } => VersionVerdict {
            // Ticket 11: no version at all reads "runs a newer Roboco",
            // never "runs Roboco unknown".
            notice: Some(match engine_version.as_deref() {
                Some(version) => format!(
                    "{engine_name} runs Roboco {version}, newer than this app ({app_version}). \
                     Everything should keep working; if something looks wrong, update the app."
                ),
                None => format!(
                    "{engine_name} runs a newer Roboco than this app. \
                     Everything should keep working; if something looks wrong, update the app."
                ),
            }),
            incompatible: false,
            last_error: None,
        },
        Compatibility::ClientTooOld { .. } => VersionVerdict {
            notice: None,
            incompatible: true,
            last_error: Some(format!(
                "{engine_name} needs a newer Roboco app. Update the app to connect."
            )),
        },
        Compatibility::EngineTooOld { engine_version, .. } => VersionVerdict {
            notice: None,
            incompatible: true,
            last_error: Some(match engine_version.as_deref() {
                Some(version) => format!(
                    "{engine_name} runs an older Roboco ({version}). \
                     Update Roboco on that device to connect."
                ),
                None => format!(
                    "{engine_name} runs an older Roboco. Update Roboco on that device to connect."
                ),
            }),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use roboco_proto::WorkspaceScope;

    const APP: &str = "0.9.2";

    fn info(version: Option<&str>, protocol: Option<u32>, min_client: Option<u32>) -> EngineInfo {
        EngineInfo {
            device_id: "engine-1".into(),
            workspace_scope: WorkspaceScope::Local,
            cursor_sdk_version: None,
            capabilities: Vec::new(),
            version: version.map(Into::into),
            protocol_version: protocol,
            min_client_protocol_version: min_client,
        }
    }

    #[test]
    fn a_newer_minor_version_gives_the_exact_notice_sentence() {
        let verdict = assess_engine(
            &info(Some("0.10.0"), Some(1), Some(1)),
            APP,
            "Studio Mac",
            None,
        );
        assert!(!verdict.incompatible);
        assert_eq!(
            verdict.notice.as_deref(),
            Some(
                "Studio Mac runs Roboco 0.10.0, newer than this app (0.9.2). Everything should keep working; if something looks wrong, update the app."
            )
        );
        assert_eq!(verdict.last_error, None);
    }

    #[test]
    fn a_newer_patch_or_equal_version_gives_no_notice() {
        for version in ["0.9.7", "0.9.2", "0.8.4"] {
            let verdict = assess_engine(&info(Some(version), Some(1), Some(1)), APP, "Mac", None);
            assert_eq!(verdict, VersionVerdict::default(), "version {version}");
        }
    }

    #[test]
    fn a_min_client_protocol_above_ours_parks_as_incompatible() {
        let verdict = assess_engine(
            &info(Some("1.0.0"), Some(3), Some(2)),
            APP,
            "Studio Mac",
            None,
        );
        assert!(verdict.incompatible);
        assert_eq!(
            verdict.last_error.as_deref(),
            Some("Studio Mac needs a newer Roboco app. Update the app to connect.")
        );
        assert_eq!(verdict.notice, None);
    }

    #[test]
    fn an_engine_protocol_below_ours_parks_as_incompatible() {
        let verdict = assess_engine(
            &info(Some("0.1.0"), Some(0), Some(1)),
            APP,
            "Studio Mac",
            None,
        );
        assert!(verdict.incompatible);
        assert_eq!(
            verdict.last_error.as_deref(),
            Some(
                "Studio Mac runs an older Roboco (0.1.0). Update Roboco on that device to connect."
            )
        );
    }

    #[test]
    fn a_newer_engine_protocol_gives_the_notice_not_a_park() {
        // Protocol generation ahead of ours, versions equal: still lenient
        // with a notice — rule 3 of `assess` (only rules 1-2 park).
        let verdict = assess_engine(&info(Some("0.9.2"), Some(2), Some(1)), APP, "Mac", None);
        assert!(!verdict.incompatible);
        assert!(verdict.notice.is_some());
    }

    #[test]
    fn a_newer_protocol_without_any_version_says_a_newer_roboco() {
        // Ticket 11: never "runs Roboco unknown".
        let verdict = assess_engine(&info(None, Some(2), Some(1)), APP, "Studio Mac", None);
        assert!(!verdict.incompatible);
        assert_eq!(
            verdict.notice.as_deref(),
            Some(
                "Studio Mac runs a newer Roboco than this app. Everything should keep working; if something looks wrong, update the app."
            )
        );
    }

    #[test]
    fn an_older_protocol_without_any_version_parks_without_unknown() {
        let verdict = assess_engine(&info(None, Some(0), Some(1)), APP, "Studio Mac", None);
        assert!(verdict.incompatible);
        assert_eq!(
            verdict.last_error.as_deref(),
            Some("Studio Mac runs an older Roboco. Update Roboco on that device to connect.")
        );
    }

    #[test]
    fn the_device_row_version_is_the_fallback_for_a_missing_engine_version() {
        // Ticket 11: an engine older than `EngineInfo.version` still stamps
        // its own `Device.version`; the notice reads it.
        let verdict = assess_engine(
            &info(None, Some(1), Some(1)),
            APP,
            "Studio Mac",
            Some("0.10.0"),
        );
        assert_eq!(
            verdict.notice.as_deref(),
            Some(
                "Studio Mac runs Roboco 0.10.0, newer than this app (0.9.2). Everything should keep working; if something looks wrong, update the app."
            )
        );
        // `EngineInfo.version`, when present, wins over the row.
        let verdict = assess_engine(
            &info(Some("0.9.2"), Some(1), Some(1)),
            APP,
            "Mac",
            Some("0.10.0"),
        );
        assert_eq!(verdict, VersionVerdict::default());
    }

    #[test]
    fn a_missing_engine_version_says_unknown_without_blocking() {
        // Pre-field engines: no version at all. Compatible, no notice.
        let verdict = assess_engine(&info(None, None, None), APP, "Mac", None);
        assert_eq!(verdict, VersionVerdict::default());
    }
}
