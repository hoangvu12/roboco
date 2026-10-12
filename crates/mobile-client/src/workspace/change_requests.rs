//! PR badges: `WatchCheckoutChangeRequest` streams for the active chats
//! with a source context, deduped per (engine, device, repo root, branch)
//! and capped at 40 per engine — a mobile bound; the web has none.
//!
//! The web rule (`change-requests-store.ts:385-400`): the watch's `cwd` is
//! `source_context.repo_root`, never the chat's working directory, and the
//! branch must be non-blank after trimming. A status applies when
//! `device_id`, `cwd` and `branch` all match; a `change_request: null`
//! status clears the badge, a failed resolution keeps the previous one
//! (only successful lookups reach the statuses map).
//!
//! The first stream item after a (re)subscribe is the readiness ack
//! `{"ok":{"stream":true}}` — `RpcClient::subscribe_checked`, which the
//! engine handle's `watch_feed` uses, consumes it as the handshake and the
//! frames flow as items. An engine that answers `unknown method` is
//! unsupported for that link generation (`EngineHandle::spawn_watch` waits
//! for a new generation before retrying, and a `Device.version` change
//! rebuilds the target set here, which re-subscribes).

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use roboco_engine_client::registry::EngineKey;
use roboco_engine_client::cache::CachedRows;
use roboco_proto::CheckoutChangeRequestStatus;

use crate::ClientInner;
use crate::workspace::view::{ChangeRequests, EngineRows};

/// How many checkout targets one engine may watch (most recent first).
pub const MAX_TARGETS_PER_ENGINE: usize = 40;

/// A checkout target: one `WatchCheckoutChangeRequest` subscription.
#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct Target {
    pub engine_id: String,
    /// The chat's owning device (raw engine id).
    pub device_id: String,
    pub cwd: String,
    pub branch: String,
}

/// The watch-eligible targets of one engine's chats: every active chat
/// with a source context and a non-blank trimmed branch; deduped, sorted
/// most-recent-first (the newest chat referencing the target), capped at
/// [`MAX_TARGETS_PER_ENGINE`]. Active = the derive's rule: not archived,
/// top-level, space alive (or projectless).
pub fn select_targets(engine_id: &str, rows: &CachedRows) -> Vec<Target> {
    let spaces: std::collections::HashSet<&str> =
        rows.spaces.iter().map(|s| s.id.as_str()).collect();
    let mut newest: HashMap<(String, String, String), (chrono::DateTime<chrono::Utc>, Target)> =
        HashMap::new();
    for chat in &rows.chats {
        if chat.archived || chat.parent_chat_id.is_some() {
            continue;
        }
        if chat
            .space_id
            .as_deref()
            .is_some_and(|id| !spaces.contains(id))
        {
            continue;
        }
        let Some(source) = &chat.source_context else {
            continue;
        };
        let branch = source.branch.trim().to_owned();
        if branch.is_empty() {
            continue;
        }
        let key = (chat.device_id.clone(), source.repo_root.clone(), branch.clone());
        let activity = chat.last_message_at.unwrap_or(chat.created_at);
        let entry = newest.entry(key).or_insert((
            activity,
            Target {
                engine_id: engine_id.to_owned(),
                device_id: chat.device_id.clone(),
                cwd: source.repo_root.clone(),
                branch,
            },
        ));
        if activity > entry.0 {
            entry.0 = activity;
        }
    }
    let mut targets: Vec<(chrono::DateTime<chrono::Utc>, Target)> = newest.into_values().collect();
    targets.sort_by_key(|(activity, _)| std::cmp::Reverse(*activity));
    targets.truncate(MAX_TARGETS_PER_ENGINE);
    targets.into_iter().map(|(_, target)| target).collect()
}

/// Apply one status frame: a status lands on the target it matches
/// (`device_id`, `cwd`, `branch`), `change_request: null` clears the
/// badge, a failed resolution never reaches here (the stream keeps the
/// previous value). Returns `true` when the badge content changed.
pub fn apply_status(
    statuses: &mut ChangeRequests,
    engine_id: &str,
    status: &CheckoutChangeRequestStatus,
) -> bool {
    let key = (
        engine_id.to_owned(),
        ScopedDevice::of(engine_id, &status.device_id).0,
        status.cwd.clone(),
        status.branch.clone(),
    );
    let previous = statuses.insert(key, status.change_request.clone());
    previous != Some(status.change_request.clone())
}

/// The scoped device id half of the statuses key (a plain string with the
/// engine's key, so a raw device id of one engine never collides with
/// another's).
struct ScopedDevice(String);

impl ScopedDevice {
    fn of(engine_id: &str, device_id: &str) -> Self {
        Self(roboco_engine_client::registry::ScopedId::encode(
            &EngineKey(engine_id.to_owned()),
            device_id,
        ))
    }
}

/// The shared PR badge state plus the watch registry. The store drives it
/// on every fleet change; the derive reads the statuses.
pub(crate) struct ChangeRequestWatches {
    inner: Arc<ChangeRequestInner>,
}

pub(crate) struct ChangeRequestInner {
    weak: std::sync::Weak<ClientInner>,
    statuses: Mutex<ChangeRequests>,
    /// Cancellation tokens of the active watches, keyed by target —
    /// cancelling the token ends the forwarding task, whose `FeedWatch`
    /// drop cancels the subscription server-side.
    watches: Mutex<HashMap<Target, tokio_util::sync::CancellationToken>>,
    /// The last-seen device version per engine: a version change rebuilds
    /// the target set (an engine that was `unknown method` may have
    /// learned the method).
    versions: Mutex<HashMap<String, String>>,
    /// Signals the store to re-derive (a badge changed).
    notify: Arc<tokio::sync::Notify>,
}

impl ChangeRequestWatches {
    pub(crate) fn new(
        weak: std::sync::Weak<ClientInner>,
        notify: Arc<tokio::sync::Notify>,
    ) -> Self {
        Self {
            inner: Arc::new(ChangeRequestInner {
                weak,
                statuses: Mutex::new(HashMap::new()),
                watches: Mutex::new(HashMap::new()),
                versions: Mutex::new(HashMap::new()),
                notify,
            }),
        }
    }

    pub(crate) fn inner(&self) -> Arc<ChangeRequestInner> {
        self.inner.clone()
    }

    /// The statuses for the derive (clone under the lock).
    pub(crate) fn statuses(&self) -> ChangeRequests {
        crate::lock(&self.inner.statuses).clone()
    }
}

impl ChangeRequestInner {
    /// Reconcile the watch set against the fleet: subscribe new targets,
    /// drop cancelled ones, and track the per-engine device versions.
    /// Returns `true` when the target set changed.
    pub(crate) fn retarget(self: &Arc<Self>, engines: &[EngineRows]) -> bool {
        let mut wanted: HashMap<Target, ()> = HashMap::new();
        let mut versions = std::mem::take(&mut *crate::lock(&self.versions));
        let mut version_changed = false;
        for engine in engines {
            for target in select_targets(&engine.engine_id, &engine.rows) {
                wanted.insert(target, ());
            }
            let version = engine
                .rows
                .devices
                .iter()
                .find(|device| device.id == engine.device_id.clone().unwrap_or_default())
                .and_then(|device| device.version.clone())
                .or_else(|| engine.info.as_ref().and_then(|info| info.version.clone()))
                .unwrap_or_default();
            let previous = versions.insert(engine.engine_id.clone(), version.clone());
            version_changed |= previous.as_deref() != Some(version.as_str());
        }
        *crate::lock(&self.versions) = versions;

        let mut watches = std::mem::take(&mut *crate::lock(&self.watches));
        let mut changed = false;
        // Drop cancelled targets.
        let dropped: Vec<Target> = watches
            .keys()
            .filter(|target| !wanted.contains_key(*target))
            .cloned()
            .collect();
        for target in dropped {
            if let Some(token) = watches.remove(&target) {
                token.cancel();
            }
            changed = true;
        }
        // Subscribe new ones. A device-version change re-subscribes the
        // whole set: an engine that was `unknown method` (older than the
        // stream) gets a fresh chance once it upgrades.
        let fresh: Vec<Target> = if version_changed {
            for token in watches.values() {
                token.cancel();
            }
            watches.clear();
            changed = true;
            wanted.keys().cloned().collect()
        } else {
            wanted
                .keys()
                .filter(|target| !watches.contains_key(*target))
                .cloned()
                .collect()
        };
        for target in fresh {
            let Some(inner) = self.weak.upgrade() else {
                break;
            };
            let Ok(handle) = crate::engine_handle_of(&inner, &target.engine_id) else {
                continue;
            };
            let params = serde_json::json!({
                "cwd": target.cwd,
                "branch": target.branch,
            });
            let watch =
                handle.watch_feed(roboco_rpc::methods::WATCH_CHECKOUT_CHANGE_REQUEST, params);
            let sink = Arc::downgrade(self);
            let engine_id = target.engine_id.clone();
            let token = tokio_util::sync::CancellationToken::new();
            let cancel = token.clone();
            crate::runtime::shared().spawn(async move {
                forward_items(sink, engine_id, watch, cancel).await;
            });
            watches.insert(target, token);
            changed = true;
        }
        *crate::lock(&self.watches) = watches;
        changed
    }

    /// Store one delivered status; notifies the store to re-derive.
    pub(crate) fn deliver(&self, engine_id: &str, status: CheckoutChangeRequestStatus) {
        let mut statuses = crate::lock(&self.statuses);
        let changed = apply_status(&mut statuses, engine_id, &status);
        drop(statuses);
        if changed {
            self.notify.notify_one();
        }
    }
}

/// Forward one watch's items into the shared statuses; ends when the
/// target is cancelled (its `FeedWatch` drop cancels the subscription
/// server-side).
async fn forward_items(
    sink: std::sync::Weak<ChangeRequestInner>,
    engine_id: String,
    mut watch: crate::engines::FeedWatch,
    cancel: tokio_util::sync::CancellationToken,
) {
    loop {
        let item = tokio::select! {
            _ = cancel.cancelled() => return,
            item = watch.recv() => match item {
                Some(item) => item,
                None => return,
            },
        };
        let Some(inner) = sink.upgrade() else {
            return;
        };
        let status: CheckoutChangeRequestStatus = match serde_json::from_value(item.value) {
            Ok(status) => status,
            Err(error) => {
                // Lenient: a newer engine's row shape degrades this one
                // status; the previous value stands.
                tracing::debug!(engine = %engine_id, %error, "PR status decode failed");
                continue;
            }
        };
        inner.deliver(&engine_id, status);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::{TimeZone, Utc};
    use roboco_proto::{ChangeRequestSummary, Chat};

    fn chat(id: &str, branch: Option<&str>, archived: bool) -> Chat {
        Chat {
            id: id.into(),
            device_id: "dev-1".into(),
            title: None,
            archived,
            cwd: None,
            branch: None,
            checkout_id: None,
            source_context: branch.map(|branch| roboco_proto::ConversationSourceContext {
                checkout_id: "c1".into(),
                repo_root: "/repo".into(),
                cwd: "/repo".into(),
                branch: branch.into(),
                head_sha: None,
                observed_at: Utc.timestamp_opt(1, 0).unwrap(),
            }),
            config: None,
            last_message_preview: None,
            last_message_at: None,
            created_at: Utc.timestamp_opt(1, 0).unwrap(),
            harness_session_id: None,
            harness_session_cwd: None,
            space_id: None,
            last_seen_at: None,
            room_gen: None,
            parent_chat_id: None,
        }
    }

    fn rows(chats: Vec<Chat>) -> CachedRows {
        CachedRows {
            chats,
            ..Default::default()
        }
    }

    fn pr(number: u64) -> Option<ChangeRequestSummary> {
        Some(ChangeRequestSummary {
            provider: "github".into(),
            number,
            title: "Fix".into(),
            url: "https://example".into(),
            state: roboco_proto::ChangeRequestState::Open,
            base_ref: "main".into(),
            head_ref: "topic".into(),
        })
    }

    fn status(device_id: &str, cwd: &str, branch: &str, change_request: Option<u64>) -> CheckoutChangeRequestStatus {
        CheckoutChangeRequestStatus {
            checkout_id: "c1".into(),
            device_id: device_id.into(),
            cwd: cwd.into(),
            branch: branch.into(),
            change_request: change_request.map(|number| ChangeRequestSummary {
                provider: "github".into(),
                number,
                title: "Fix".into(),
                url: "https://example".into(),
                state: roboco_proto::ChangeRequestState::Open,
                base_ref: "main".into(),
                head_ref: "topic".into(),
            }),
            updated_at: Utc.timestamp_opt(1, 0).unwrap(),
        }
    }

    #[test]
    fn targets_dedupe_and_skip_blank_branches_and_archived() {
        let rows = rows(vec![
            chat("a", Some("main"), false),
            chat("b", Some("main"), false), // same target as a
            chat("c", Some("  "), false),   // blank branch
            chat("d", None, false),         // no source context
            chat("e", Some("dev"), true),   // archived
            chat("f", Some("dev"), false),
        ]);
        let targets = select_targets("eng-1", &rows);
        assert_eq!(targets.len(), 2);
        assert!(targets.contains(&Target {
            engine_id: "eng-1".into(),
            device_id: "dev-1".into(),
            cwd: "/repo".into(),
            branch: "main".into(),
        }));
        assert!(targets.contains(&Target {
            engine_id: "eng-1".into(),
            device_id: "dev-1".into(),
            cwd: "/repo".into(),
            branch: "dev".into(),
        }));
    }

    #[test]
    fn targets_cap_at_forty_most_recent_first() {
        let chats: Vec<Chat> = (0..60)
            .map(|i| {
                let mut chat = chat(&format!("c{i}"), Some(&format!("branch-{i}")), false);
                chat.last_message_at = Some(Utc.timestamp_opt(i as i64, 0).unwrap());
                chat
            })
            .collect();
        let targets = select_targets("eng-1", &rows(chats));
        assert_eq!(targets.len(), MAX_TARGETS_PER_ENGINE);
        // The most recent chat's branch comes first.
        assert_eq!(targets[0].branch, "branch-59");
        assert_eq!(targets[39].branch, "branch-20");
    }

    #[test]
    fn statuses_match_on_device_cwd_and_branch_and_null_clears() {
        let mut statuses = ChangeRequests::new();
        let key = |engine: &str, device: &str| {
            (
                engine.to_owned(),
                ScopedDevice::of(engine, device).0,
                "/repo".to_owned(),
                "main".to_owned(),
            )
        };
        assert!(apply_status(
            &mut statuses,
            "eng-1",
            &status("dev-1", "/repo", "main", Some(7))
        ));
        assert_eq!(statuses[&key("eng-1", "dev-1")], pr(7));
        // A different device / cwd / branch never lands on the same slot.
        assert!(apply_status(
            &mut statuses,
            "eng-1",
            &status("dev-2", "/repo", "main", Some(9))
        ));
        assert_eq!(statuses[&key("eng-1", "dev-1")], pr(7));
        assert_eq!(statuses[&key("eng-1", "dev-2")], pr(9));
        // An identical re-delivery is not a change.
        assert!(!apply_status(
            &mut statuses,
            "eng-1",
            &status("dev-1", "/repo", "main", Some(7))
        ));
        // `change_request: null` clears the badge.
        assert!(apply_status(
            &mut statuses,
            "eng-1",
            &status("dev-1", "/repo", "main", None)
        ));
        assert_eq!(statuses[&key("eng-1", "dev-1")], None);
    }
}
