//! The engine-side sidebar state bridge — desktop side.
//!
//! Upstream zeron synced pins and sections through the cloud registry;
//! roboco removed that layer on purpose (ADR 0004). Ticket 11 re-homes the
//! STATE engine-side on the project-actions precedent: the engine owning
//! this profile persists `sidebar-state.json` and fans changes out over
//! `WatchSidebarState`, while `UiSettings` (`ui-settings.json`) stays as
//! the offline cache and optimistic write-ahead. Writes flow through
//! `SetSidebarPins` / `SetSidebarSections` on the LOCAL engine; conflicts
//! resolve last-write-wins (the engine holds the last replace to arrive).
//!
//! Routing matches the existing surface: the requests ride the local
//! engine's own connection through `EngineTarget`, where `wire_params`
//! strips any `targetDeviceId` before the frame leaves this client — no
//! engine forwards a sidebar request on to another device.

use super::*;
use roboco_proto::SidebarStateSnapshot;

/// Client-side bookkeeping for the engine bridge. `UiSettings` remains the
/// render source; this only tracks what the engine has confirmed.
#[derive(Default)]
pub(super) struct SidebarStateSync {
    /// The latest engine snapshot. `None` until the first frame lands —
    /// until then the store is local-only (the pre-ticket behavior).
    snapshot: Option<SidebarStateSnapshot>,
    /// True while local pin/section writes have not landed on the engine.
    /// While set, engine frames for that surface are stale by construction
    /// (they predate our push) and are not applied to the cache.
    dirty_pins: bool,
    dirty_sections: bool,
    /// Guards the single-flight write task (`Shell::sidebar_state_write`).
    write_running: bool,
}

/// One surface's current wire payload — the exact value a send carries, so
/// the reply can tell "the user changed it mid-flight" from "landed".
enum SidebarSurface {
    Pins {
        profile_key: String,
        params: serde_json::Value,
    },
    Sections {
        profile_key: String,
        params: serde_json::Value,
    },
}

impl Shell {
    fn sidebar_pins_payload(&self, cx: &App) -> Option<SidebarSurface> {
        let profile_key = self.active_sidebar_pin_profile_key(cx)?;
        let params = serde_json::json!({
            "profileKey": profile_key,
            "sessionIds": self.settings.sidebar_pins(&profile_key),
        });
        Some(SidebarSurface::Pins { profile_key, params })
    }

    fn sidebar_sections_payload(&self, cx: &App) -> Option<SidebarSurface> {
        let profile_key = self.active_sidebar_pin_profile_key(cx)?;
        let params = serde_json::json!({
            "profileKey": profile_key,
            "sections": self
                .settings
                .sidebar_sections_by_profile
                .get(&profile_key)
                .map(|sections| sections.iter().map(section_wire).collect::<Vec<_>>())
                .unwrap_or_default(),
        });
        Some(SidebarSurface::Sections { profile_key, params })
    }

    /// Mark both surfaces dirty and start (or let the running task pick up)
    /// the single-flight write-through. Called after every local pin or
    /// section mutation lands in `UiSettings`.
    pub(super) fn push_sidebar_state(&mut self, cx: &mut Context<Self>) {
        self.sidebar_state_sync.dirty_pins = true;
        self.sidebar_state_sync.dirty_sections = true;
        self.start_sidebar_state_write(cx);
    }

    /// Mark only the pin surface dirty (pin mutations that never touch
    /// sections, e.g. the synced-chats prune).
    pub(super) fn push_sidebar_pins(&mut self, cx: &mut Context<Self>) {
        self.sidebar_state_sync.dirty_pins = true;
        self.start_sidebar_state_write(cx);
    }

    fn start_sidebar_state_write(&mut self, cx: &mut Context<Self>) {
        if self.sidebar_state_sync.write_running {
            // The running task re-reads the dirty flags after every pass;
            // it carries the new values on its next iteration.
            return;
        }
        if self.state.read(cx).local_target().is_err() {
            // Engine not attached yet: the writes stay cached in UiSettings
            // (the offline cache). The first watch frame after attach
            // either imports them (engine bucket absent) or adopts the
            // engine's state — LWW either way.
            return;
        }
        self.sidebar_state_sync.write_running = true;
        self.sidebar_state_write = Some(cx.spawn(async move |this, cx| {
            loop {
                let request = match this.update(cx, |shell, cx| {
                    let pins = shell
                        .sidebar_state_sync
                        .dirty_pins
                        .then(|| shell.sidebar_pins_payload(cx));
                    let sections = shell
                        .sidebar_state_sync
                        .dirty_sections
                        .then(|| shell.sidebar_sections_payload(cx));
                    (pins.flatten(), sections.flatten(), shell.state.read(cx).local_target().ok())
                }) {
                    Ok(request) => request,
                    Err(_) => return,
                };
                let (pins, sections, target) = request;
                if pins.is_none() && sections.is_none() {
                    break;
                }
                let Some(target) = target else {
                    // The engine went away mid-flight: keep the surfaces
                    // dirty; the next watch (re)start retries them.
                    break;
                };
                let mut failed = false;
                for (surface, method) in [
                    (pins, methods::SET_SIDEBAR_PINS),
                    (sections, methods::SET_SIDEBAR_SECTIONS),
                ] {
                    let Some(surface) = surface else {
                        continue;
                    };
                    // Every mutation replies with the fresh snapshot; it
                    // clears the surface only when the sent value is still
                    // current (a mid-flight user edit stays dirty and is
                    // re-sent on the next pass).
                    let landed = match target
                        .call_as::<SidebarStateSnapshot>(method, surface_params(&surface))
                        .await
                    {
                        Ok(snapshot) => this
                            .update(cx, |shell, cx| {
                                let current = shell.sidebar_surface_matches(&surface, cx);
                                match surface {
                                    SidebarSurface::Pins { .. } => {
                                        shell.sidebar_state_sync.dirty_pins = !current;
                                    }
                                    SidebarSurface::Sections { .. } => {
                                        shell.sidebar_state_sync.dirty_sections = !current;
                                    }
                                }
                                shell.apply_engine_sidebar_state(snapshot, cx);
                                current
                            })
                            .unwrap_or(false),
                        Err(err) => {
                            tracing::debug!(%err, "sidebar state write failed; cached locally");
                            false
                        }
                    };
                    if !landed {
                        failed = true;
                        break;
                    }
                }
                if failed {
                    break;
                }
            }
            let _ = this.update(cx, |shell, cx| {
                shell.sidebar_state_sync.write_running = false;
                shell.sidebar_state_write = None;
                cx.notify();
            });
        }));
    }

    /// Whether the current settings value for a surface is still the one a
    /// just-landed write sent (a diverging value means a mid-flight edit).
    fn sidebar_surface_matches(&self, surface: &SidebarSurface, cx: &App) -> bool {
        match surface {
            SidebarSurface::Pins { profile_key, .. } => {
                self.active_sidebar_pin_profile_key(cx).as_deref() == Some(profile_key.as_str())
                    && self.sidebar_pins_payload(cx).is_some_and(|current| {
                        surface_params(&current) == surface_params(surface)
                    })
            }
            SidebarSurface::Sections { profile_key, .. } => {
                self.active_sidebar_pin_profile_key(cx).as_deref() == Some(profile_key.as_str())
                    && self.sidebar_sections_payload(cx).is_some_and(|current| {
                        surface_params(&current) == surface_params(surface)
                    })
            }
        }
    }

    /// Apply an engine snapshot to the offline cache. Surfaces with pending
    /// local writes keep their local value (LWW: our write is newer than
    /// the frame); everything else adopts the engine state verbatim.
    pub(super) fn apply_engine_sidebar_state(
        &mut self,
        snapshot: SidebarStateSnapshot,
        cx: &mut Context<Self>,
    ) {
        let first = self.sidebar_state_sync.snapshot.is_none();
        self.sidebar_state_sync.snapshot = Some(snapshot.clone());
        // One-time import: the engine has no bucket for a surface while the
        // local cache does (the pre-ticket state, or offline authoring that
        // never landed) — push the local value; last write wins.
        if first
            && let Some(profile_key) = self.active_sidebar_pin_profile_key(cx)
        {
            let engine_has_pins = snapshot.pins_by_profile.contains_key(&profile_key);
            let engine_has_sections = snapshot.sections_by_profile.contains_key(&profile_key);
            let local_has_pins = !self.settings.sidebar_pins(&profile_key).is_empty();
            let local_has_sections = self
                .settings
                .sidebar_sections_by_profile
                .get(&profile_key)
                .is_some_and(|sections| !sections.is_empty());
            if (!engine_has_pins && local_has_pins) || (!engine_has_sections && local_has_sections)
            {
                self.sidebar_state_sync.dirty_pins |= !engine_has_pins && local_has_pins;
                self.sidebar_state_sync.dirty_sections |=
                    !engine_has_sections && local_has_sections;
                self.start_sidebar_state_write(cx);
                return;
            }
        }
        if !self.sidebar_state_sync.dirty_pins {
            let next: std::collections::HashMap<_, _> =
                snapshot.pins_by_profile.into_iter().collect();
            if self.settings.sidebar_pinned_session_ids_by_profile != next {
                self.settings.sidebar_pinned_session_ids_by_profile = next;
                self.schedule_save(cx);
            }
        }
        if !self.sidebar_state_sync.dirty_sections {
            let next: std::collections::HashMap<_, _> = snapshot
                .sections_by_profile
                .into_iter()
                .map(|(key, sections)| (key, sections_from_wire(sections)))
                .collect();
            if self.settings.sidebar_sections_by_profile != next {
                self.settings.sidebar_sections_by_profile = next;
                self.schedule_save(cx);
            }
        }
        cx.notify();
    }

    /// A fresh watch stream (first attach or reconnect): if local writes
    /// never landed, retry them now — the engine came back.
    pub(super) fn retry_sidebar_state_push(&mut self, cx: &mut Context<Self>) {
        if self.sidebar_state_sync.dirty_pins || self.sidebar_state_sync.dirty_sections {
            self.start_sidebar_state_write(cx);
        }
    }

    /// The standing `WatchSidebarState` subscription on the local engine:
    /// resubscribe with backoff while the engine owns the profile, apply
    /// every frame, and retry cached writes when the stream (re)starts.
    pub(super) fn spawn_sidebar_state_watch(&mut self, cx: &mut Context<Self>) {
        if self.sidebar_state_watch.is_some() {
            return;
        }
        let Ok(target) = self.state.read(cx).local_target() else {
            return;
        };
        self.sidebar_state_watch = Some(cx.spawn(async move |this, cx| {
            const RETRY_DELAY: Duration = Duration::from_secs(2);
            let mut rx = None;
            loop {
                if rx.is_none() {
                    rx = match target
                        .subscribe(methods::WATCH_SIDEBAR_STATE, serde_json::json!({}))
                        .await
                    {
                        Ok(rx) => Some(rx),
                        Err(roboco_rpc::RpcError::UnknownMethod(method)) => {
                            // An engine from before this surface: stay in
                            // local-only mode permanently.
                            tracing::debug!(%method, "sidebar state watch unsupported");
                            let _ = this.update(cx, |shell, _| {
                                shell.sidebar_state_watch = None;
                            });
                            return;
                        }
                        Err(err) => {
                            tracing::debug!(%err, "sidebar state watch unavailable; retrying");
                            if this.update(cx, |_, _| ()).is_err() {
                                return;
                            }
                            cx.background_executor().timer(RETRY_DELAY).await;
                            continue;
                        }
                    };
                }
                // A fresh stream instance: cached offline writes retry now.
                let _ = this.update(cx, |shell, cx| shell.retry_sidebar_state_push(cx));
                let Some(stream) = rx.as_mut() else {
                    cx.background_executor().timer(RETRY_DELAY).await;
                    continue;
                };
                while let Some(value) = stream.recv().await {
                    match serde_json::from_value::<SidebarStateSnapshot>(value) {
                        Ok(frame) => {
                            let _ = this.update(cx, |shell, cx| {
                                shell.apply_engine_sidebar_state(frame, cx);
                            });
                        }
                        Err(err) => {
                            tracing::warn!(%err, "dropping malformed sidebar state frame");
                        }
                    }
                }
                // The stream ended (engine restart / disconnect): drop it
                // and resubscribe; UiSettings keeps serving reads offline.
                rx = None;
                if this.update(cx, |_, _| ()).is_err() {
                    return;
                }
                cx.background_executor().timer(RETRY_DELAY).await;
            }
        }));
    }
}

fn surface_params(surface: &SidebarSurface) -> serde_json::Value {
    match surface {
        SidebarSurface::Pins { params, .. } | SidebarSurface::Sections { params, .. } => {
            params.clone()
        }
    }
}

fn section_wire(section: &crate::settings::SidebarSection) -> roboco_proto::SidebarSection {
    roboco_proto::SidebarSection {
        id: section.id.clone(),
        name: section.name.clone(),
        session_ids: section.session_ids.clone(),
        collapsed: section.collapsed,
    }
}

fn sections_from_wire(
    sections: Vec<roboco_proto::SidebarSection>,
) -> Vec<crate::settings::SidebarSection> {
    sections
        .into_iter()
        .map(|section| crate::settings::SidebarSection {
            id: section.id,
            name: section.name,
            session_ids: section.session_ids,
            collapsed: section.collapsed,
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::state::{AppState, EngineBootConfig};

    fn test_shell(
        cx: &mut gpui::TestAppContext,
        path: &std::path::Path,
    ) -> gpui::WindowHandle<super::Shell> {
        cx.update(|cx| {
            gpui_base::init(cx);
            cx.set_global(Theme::default());
            crate::app_menus::init(cx);
            crate::history::init(
                Default::default(),
                Default::default(),
                Default::default(),
                Default::default(),
                cx,
            );
        });
        cx.add_window(|_, cx| {
            let state = cx.new(|_| AppState::new());
            Shell::new(
                state,
                EngineBootConfig {
                    data_dir: path.into(),
                    ipc_port: 0,
                    default_harness: roboco_proto::HarnessId::Mock,
                },
                cx,
            )
        })
    }

    fn prepare(shell: &mut Shell, cx: &mut Context<Shell>) {
        shell.state.update(cx, |state, _| {
            state.workspace_scope = Some(roboco_proto::WorkspaceScope::Local);
            state.local_device_id = Some("local".into());
        });
    }

    fn chat(id: &str) -> roboco_proto::Chat {
        serde_json::from_value(serde_json::json!({
            "id": id, "title": id, "deviceId": "local", "archived": false,
            "createdAt": "2026-09-20T00:00:00Z"
        }))
        .unwrap()
    }

    /// The offline cache semantics: with no engine attached, a local pin
    /// write stays in `UiSettings` and is marked for the next sync; the
    /// first engine frame with an empty store imports it (LWW), and frames
    /// for a dirty surface never clobber the cached value.
    #[gpui::test]
    fn offline_writes_cache_import_and_never_clobber(cx: &mut gpui::TestAppContext) {
        let dir = tempfile::tempdir().unwrap();
        let window = test_shell(cx, dir.path());
        window
            .update(cx, |shell, _, cx| {
                prepare(shell, cx);
                // Offline: a pin write lands in the cache and is marked
                // dirty but never leaves this device.
                shell
                    .settings
                    .sidebar_pinned_session_ids_by_profile
                    .insert("local".into(), vec!["pin-1".into()]);
                shell.push_sidebar_pins(cx);
                assert!(shell.sidebar_state_sync.dirty_pins);
                assert!(shell.sidebar_state_write.is_none());

                // The first frame over an empty engine store imports the
                // cached bucket (the pre-ticket migration path): the value
                // stands, the surface stays dirty for the push.
                shell.apply_engine_sidebar_state(SidebarStateSnapshot::default(), cx);
                assert_eq!(shell.settings.sidebar_pins("local"), ["pin-1"]);
                assert!(shell.sidebar_state_sync.dirty_pins);

                // A later frame carrying a stale pin list (it predates our
                // pending push — LWW: ours is newer) must NOT clobber the
                // cached pins, while the clean sections surface adopts.
                shell.apply_engine_sidebar_state(
                    SidebarStateSnapshot {
                        pins_by_profile: [(
                            "local".to_string(),
                            vec!["engine-stale".to_string()],
                        )]
                        .into_iter()
                        .collect(),
                        sections_by_profile: [(
                            "local".to_string(),
                            vec![roboco_proto::SidebarSection {
                                id: "s1".into(),
                                name: "Focus".into(),
                                session_ids: vec![],
                                collapsed: false,
                            }],
                        )]
                        .into_iter()
                        .collect(),
                    },
                    cx,
                );
                assert_eq!(shell.settings.sidebar_pins("local"), ["pin-1"]);
                assert_eq!(shell.settings.sidebar_sections_by_profile["local"].len(), 1);
                // The dirty pin surface survives the frame.
                assert!(shell.sidebar_state_sync.dirty_pins);
            })
            .unwrap();
        cx.run_until_parked();
    }

    /// The first frame over an empty engine store imports the local cache
    /// (the pre-ticket migration path): the buckets are marked dirty so
    /// the push carries them to the engine.
    #[gpui::test]
    fn first_empty_frame_imports_local_buckets(cx: &mut gpui::TestAppContext) {
        let dir = tempfile::tempdir().unwrap();
        let window = test_shell(cx, dir.path());
        window
            .update(cx, |shell, _, cx| {
                prepare(shell, cx);
                shell
                    .settings
                    .sidebar_pinned_session_ids_by_profile
                    .insert("local".into(), vec!["legacy".into()]);
                shell
                    .settings
                    .sidebar_sections_by_profile
                    .insert(
                        "local".into(),
                        vec![crate::settings::SidebarSection {
                            id: "s".into(),
                            name: "Later".into(),
                            session_ids: vec![],
                            collapsed: false,
                        }],
                    );
                shell.apply_engine_sidebar_state(SidebarStateSnapshot::default(), cx);
                assert!(shell.sidebar_state_sync.dirty_pins);
                assert!(shell.sidebar_state_sync.dirty_sections);
                // Nothing was clobbered — the local values stand.
                assert_eq!(shell.settings.sidebar_pins("local"), ["legacy"]);
                assert_eq!(shell.settings.sidebar_sections_by_profile["local"].len(), 1);
            })
            .unwrap();
        cx.run_until_parked();
    }

    /// The plain mirror: a clean client adopts the engine's buckets
    /// verbatim (the offline cache stays fresh for the next offline gap).
    #[gpui::test]
    fn clean_client_adopts_engine_frames(cx: &mut gpui::TestAppContext) {
        let dir = tempfile::tempdir().unwrap();
        let window = test_shell(cx, dir.path());
        window
            .update(cx, |shell, _, cx| {
                prepare(shell, cx);
                shell.apply_engine_sidebar_state(
                    SidebarStateSnapshot {
                        pins_by_profile: [("local".to_string(), vec!["a".to_string(), "b".to_string()])]
                            .into_iter()
                            .collect(),
                        sections_by_profile: Default::default(),
                    },
                    cx,
                );
                assert_eq!(shell.settings.sidebar_pins("local"), ["a", "b"]);
                assert!(!shell.sidebar_state_sync.dirty_pins);
            })
            .unwrap();
        cx.run_until_parked();
    }

    /// The write-through contract: a pin mutation rides `SetSidebarPins` on
    /// the local engine's connection (the project-actions surface), the
    /// mutation replies with the fresh snapshot, and that reply clears the
    /// dirty surface in the offline cache.
    #[gpui::test]
    fn pin_writes_route_through_the_engine_rpc(cx: &mut gpui::TestAppContext) {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let _guard = runtime.enter();
        let (out, mut requests) = tokio::sync::mpsc::channel(16);
        let (replies, inbound) = tokio::sync::mpsc::channel(16);
        let engine =
            crate::state::EngineHandle::from_test_client(roboco_rpc::RpcClient::new(out, inbound));
        let dir = tempfile::tempdir().unwrap();
        let window = test_shell(cx, dir.path());
        window
            .update(cx, |shell, _, cx| {
                prepare(shell, cx);
                shell.state.update(cx, |state, _| {
                    state.chats = vec![chat("pin"), chat("regular")];
                    state.set_test_engine(engine);
                });
                shell
                    .settings
                    .sidebar_pinned_session_ids_by_profile
                    .insert("local".into(), vec!["pin".into()]);
                shell.set_chat_pinned("regular".into(), true, cx);
            })
            .unwrap();
        cx.run_until_parked();

        // `EngineTarget::call` spawns onto the registry's runtime handle —
        // this current_thread runtime — so the frame lands only once the
        // runtime is driven; `run_until_parked` alone polls only the GPUI
        // task up to the spawn point.
        for _ in 0..16 {
            runtime.block_on(async {
                tokio::task::yield_now().await;
            });
        }
        cx.run_until_parked();

        // The pin write landed on the engine's own connection as an
        // ordered-list replace for the active profile bucket.
        let request: serde_json::Value = serde_json::from_str(
            &requests
                .try_recv()
                .expect("the pin write must route through the engine RPC"),
        )
        .unwrap();
        assert_eq!(request["method"], "SetSidebarPins");
        assert_eq!(request["params"]["profileKey"], "local");
        assert_eq!(
            request["params"]["sessionIds"],
            serde_json::json!(["pin", "regular"])
        );

        // Reply with the fresh snapshot; the dirty surface clears and the
        // cache mirrors the engine state (LWW: this write won).
        let snapshot = serde_json::json!({
            "pinsByProfile": {"local": ["pin", "regular"]},
            "sectionsByProfile": {}
        });
        runtime.block_on(async {
            replies
                .send(
                    serde_json::json!({"id": request["id"], "ok": snapshot}).to_string(),
                )
                .await
                .unwrap();
            // Drive the client's reader task until the reply is routed to
            // the pending call (the capacity returns when it is consumed).
            while replies.capacity() < replies.max_capacity() {
                tokio::task::yield_now().await;
            }
        });
        cx.run_until_parked();

        // The section clear that follows the pin (membership is exclusive)
        // writes through the same surface — and spawns onto the runtime the
        // same way, so drive again before reading it off the channel.
        for _ in 0..16 {
            runtime.block_on(async {
                tokio::task::yield_now().await;
            });
        }
        cx.run_until_parked();
        let request: serde_json::Value =
            serde_json::from_str(&requests.try_recv().expect("the section clear follows")).unwrap();
        assert_eq!(request["method"], "SetSidebarSections");
        assert_eq!(request["params"]["profileKey"], "local");
        runtime.block_on(async {
            replies
                .send(
                    serde_json::json!({"id": request["id"], "ok": snapshot}).to_string(),
                )
                .await
                .unwrap();
            while replies.capacity() < replies.max_capacity() {
                tokio::task::yield_now().await;
            }
        });
        cx.run_until_parked();

        window
            .update(cx, |shell, _, cx| {
                assert_eq!(shell.settings.sidebar_pins("local"), ["pin", "regular"]);
                assert!(!shell.sidebar_state_sync.dirty_pins);
                assert!(!shell.sidebar_state_sync.dirty_sections);
                assert_eq!(shell.active_sidebar_pins(cx), ["pin", "regular"]);
            })
            .unwrap();
    }
}
