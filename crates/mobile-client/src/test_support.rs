//! The shared test rig (tickets 06-09): one **real** in-process engine on
//! an ephemeral port and one paired mobile client — no protocol mocks
//! (spec "Testing Decisions"). Each test gets its own engine data dir and
//! client cache dir; `Rig::stop` tears the trio down.
//!
//! Git-dependent helpers ([`init_repo`], [`git`]) skip nothing themselves:
//! call [`git_available`] first and print a reason (Windows CI has git).

#![allow(dead_code)] // shared by several test modules; not every test uses every helper

use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use roboco_engine_client::registry::{EngineKey, ScopedId};

use crate::timings::NotifyTimings;
use crate::{Client, ClientConfig, EngineState, NullListener};

/// Every rig uses the same fast timings (the workspace store's own test
/// set): liveness behaviour runs in seconds.
pub(crate) fn test_timings() -> crate::timings::Timings {
    crate::timings::Timings {
        initial_backoff: Duration::from_millis(50),
        max_backoff: Duration::from_millis(200),
        backoff_reset_after: Duration::from_secs(1),
        identity_timeout: Duration::from_secs(2),
        subscribe_timeout: Duration::from_secs(2),
        sync_deadline: Duration::from_secs(5),
        probe_every: Duration::from_millis(200),
        wake_probe_timeout: Duration::from_secs(1),
        lag_every: Duration::from_millis(300),
        lag_timeout: Duration::from_millis(400),
        cache_debounce: Duration::from_millis(50),
        background_flush: Duration::from_secs(1),
        refresh_cap: Duration::from_secs(1),
        undelivered_grace: Duration::from_millis(120_000),
        undelivered_heal: Duration::from_secs(20),
        workspace_tick: Duration::from_millis(100),
        accounts_forced_deadline: Duration::from_millis(400),
        notify: NotifyTimings::default(),
    }
}

/// A real engine plus the paired client driving it.
pub(crate) struct Rig {
    /// The engine's data dir (spaces live under it in tests).
    pub(crate) engine_dir: tempfile::TempDir,
    /// The client's cache dir.
    #[allow(dead_code)]
    pub(crate) data_dir: tempfile::TempDir,
    /// The engine itself (workspace rows read straight off it).
    pub(crate) core: roboco_engine::EngineCore,
    /// The remote listener; `None` once [`Rig::disconnect`] stopped it.
    listener: Option<roboco_engine::EngineListener>,
    /// Further engines paired into the same client (multi-engine tests).
    extra: Vec<ExtraEngine>,
    /// The paired client.
    pub(crate) client: Arc<Client>,
    /// The paired engine's id (the `EngineKey` string).
    pub(crate) engine_id: String,
}

/// A second engine paired into a rig's client.
struct ExtraEngine {
    engine_id: String,
    dir: tempfile::TempDir,
    core: roboco_engine::EngineCore,
    listener: Option<roboco_engine::EngineListener>,
}

impl Rig {
    /// Assemble an engine, serve it on an ephemeral port, pair a client
    /// with it, and wait until the engine is connected with its device
    /// row known (WatchDevices + EngineInfo delivered).
    pub(crate) async fn start() -> Rig {
        let engine_dir = tempfile::tempdir().expect("engine tempdir");
        let data_dir = tempfile::tempdir().expect("client tempdir");
        let core = roboco_engine::EngineCore::assemble_with_profile(
            roboco_engine::EngineProfile::local(engine_dir.path()).expect("engine profile"),
            Arc::new(roboco_engine::smoke_registry()),
            roboco_engine::HarnessId::Mock,
        )
        .expect("engine core");
        let listener = roboco_engine::serve_engine_remote(
            "127.0.0.1:0".parse().expect("loopback address"),
            core.rpc_service(),
            engine_dir.path(),
        )
        .await
        .expect("engine listener");
        let client = Client::new_with_timings(
            ClientConfig {
                data_dir: data_dir.path().to_owned(),
                device_name: "Pixel 9".into(),
                platform: "android".into(),
                app_version: "0.9.2".into(),
                show_mock_harness: true,
                native_lib_dir: None,
            },
            Vec::new(),
            Arc::new(NullListener),
            test_timings(),
        )
        .expect("client");
        let pairing = roboco_engine::pairing::PairingStore::open(engine_dir.path())
            .expect("pairing store");
        let code = pairing.create_code("test", 300).expect("pairing code");
        let url = format!("http://{}/pair#token={}", listener.address, code.credential);
        let record = client.pair_engine(&url, None).await.expect("pairing");
        let rig = Rig {
            engine_dir,
            data_dir,
            core,
            listener: Some(listener),
            extra: Vec::new(),
            client,
            engine_id: record.engine_id,
        };
        let deadline = std::time::Instant::now() + Duration::from_secs(20);
        loop {
            let ready = !rig.client.workspace().execution_devices().is_empty()
                && rig
                    .client
                    .engine(&rig.engine_id)
                    .is_some_and(|view| view.state == EngineState::Connected);
            if ready {
                return rig;
            }
            assert!(
                std::time::Instant::now() < deadline,
                "the paired engine never became ready"
            );
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }

    /// Assemble a second real engine and pair it into this rig's client
    /// (multi-engine tests). Returns the new engine's id.
    pub(crate) async fn add_engine(&mut self) -> String {
        let dir = tempfile::tempdir().expect("engine tempdir");
        let core = roboco_engine::EngineCore::assemble_with_profile(
            roboco_engine::EngineProfile::local(dir.path()).expect("engine profile"),
            Arc::new(roboco_engine::smoke_registry()),
            roboco_engine::HarnessId::Mock,
        )
        .expect("engine core");
        let listener = roboco_engine::serve_engine_remote(
            "127.0.0.1:0".parse().expect("loopback address"),
            core.rpc_service(),
            dir.path(),
        )
        .await
        .expect("engine listener");
        let pairing = roboco_engine::pairing::PairingStore::open(dir.path())
            .expect("pairing store");
        let code = pairing.create_code("test", 300).expect("pairing code");
        let url = format!("http://{}/pair#token={}", listener.address, code.credential);
        let record = self.client.pair_engine(&url, None).await.expect("pairing");
        let engine_id = record.engine_id;
        self.extra.push(ExtraEngine {
            engine_id: engine_id.clone(),
            dir,
            core,
            listener: Some(listener),
        });
        // Connected with its device row known.
        let deadline = std::time::Instant::now() + Duration::from_secs(20);
        loop {
            let ready = self
                .client
                .workspace()
                .devices
                .iter()
                .any(|device| device.engine_id == engine_id)
                && self
                    .client
                    .engine(&engine_id)
                    .is_some_and(|view| view.state == EngineState::Connected);
            if ready {
                return engine_id;
            }
            assert!(
                std::time::Instant::now() < deadline,
                "the second engine never became ready"
            );
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }

    /// The engine core of a rig engine (the first or an added one).
    pub(crate) fn core_of(&self, engine_id: &str) -> &roboco_engine::EngineCore {
        if engine_id == self.engine_id {
            return &self.core;
        }
        self.extra
            .iter()
            .find(|engine| engine.engine_id == engine_id)
            .map(|engine| &engine.core)
            .expect("a rig engine")
    }

    /// The execution device as the workspace shows it (scoped id).
    pub(crate) fn device_id(&self) -> String {
        self.client
            .workspace()
            .execution_devices()
            .first()
            .expect("the rig's engine device")
            .id
            .clone()
    }

    /// The scoped device id of one rig engine.
    pub(crate) fn device_id_of(&self, engine_id: &str) -> String {
        self.client
            .workspace()
            .devices
            .iter()
            .find(|device| device.engine_id == engine_id)
            .expect("the engine's device")
            .id
            .clone()
    }

    /// The engine's own device id (raw wire form).
    pub(crate) fn raw_device_id(&self) -> String {
        ScopedId::parse(&self.device_id()).expect("scoped id parses").raw_id
    }

    /// A scratch folder under the engine's tempdir (spaces, repos, …).
    pub(crate) fn temp_folder(&self, name: &str) -> std::path::PathBuf {
        let dir = self.engine_dir.path().join(name);
        std::fs::create_dir_all(&dir).expect("temp folder");
        dir
    }

    /// Create a space straight on the engine (bypassing the client's RPC)
    /// and wait for the merged workspace to carry it. Returns the scoped
    /// space id.
    pub(crate) async fn create_space(
        &self,
        path: &Path,
        raw_id: &str,
        git_detected: bool,
    ) -> String {
        self.create_space_on(&self.engine_id, path, raw_id, git_detected)
            .await
    }

    /// [`Rig::create_space`] for a specific rig engine.
    pub(crate) async fn create_space_on(
        &self,
        engine_id: &str,
        path: &Path,
        raw_id: &str,
        git_detected: bool,
    ) -> String {
        let raw_device =
            ScopedId::parse(&self.device_id_of(engine_id)).expect("scoped id parses").raw_id;
        self.core_of(engine_id)
            .workspace
            .create_space(
                raw_id,
                &raw_device,
                &path.to_string_lossy(),
                None,
                git_detected,
            )
            .expect("create space");
        let scoped = ScopedId::encode(&EngineKey(engine_id.to_owned()), raw_id);
        let deadline = std::time::Instant::now() + Duration::from_secs(15);
        loop {
            if self.client.workspace().project(&scoped).is_some() {
                return scoped;
            }
            assert!(
                std::time::Instant::now() < deadline,
                "the space never reached the workspace"
            );
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }

    /// Wait for the workspace row of a chat (the pending overlay first,
    /// the engine echo after).
    pub(crate) async fn wait_for_session_row(
        &self,
        chat_id: &str,
    ) -> Arc<crate::workspace::SessionRow> {
        let deadline = std::time::Instant::now() + Duration::from_secs(20);
        loop {
            if let Some(row) = self.client.workspace().session(chat_id) {
                return row.clone();
            }
            assert!(
                std::time::Instant::now() < deadline,
                "the chat row never appeared"
            );
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }

    /// Whether ticket 09's pending-chat overlay for `chat_id` is gone
    /// (dropped on the WatchChats echo or the 30 s TTL).
    pub(crate) async fn pending_chat_gone(&self, chat_id: &str) -> bool {
        let deadline = std::time::Instant::now() + Duration::from_secs(20);
        loop {
            let gone = self
                .client
                .inner_arc()
                .workspace
                .get()
                .is_none_or(|store| store.pending_chat(chat_id).is_none());
            if gone {
                return true;
            }
            if std::time::Instant::now() > deadline {
                return false;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }

    /// Stop the remote listener and wait until the client noticed — the
    /// engine core stays alive (rows are still readable engine-side).
    pub(crate) async fn disconnect(&mut self) {
        if let Some(mut listener) = self.listener.take() {
            listener.stop().await;
        }
        self.wait_disconnected(&self.engine_id).await;
    }

    /// [`Rig::disconnect`] for a specific (added) rig engine.
    pub(crate) async fn disconnect_engine(&mut self, engine_id: &str) {
        if let Some(engine) = self
            .extra
            .iter_mut()
            .find(|engine| engine.engine_id == engine_id)
        {
            if let Some(mut listener) = engine.listener.take() {
                listener.stop().await;
            }
        }
        self.wait_disconnected(engine_id).await;
    }

    async fn wait_disconnected(&self, engine_id: &str) {
        let deadline = std::time::Instant::now() + Duration::from_secs(20);
        loop {
            let offline = self
                .client
                .engine(engine_id)
                .is_some_and(|view| view.state != EngineState::Connected);
            if offline {
                return;
            }
            assert!(
                std::time::Instant::now() < deadline,
                "the client never noticed the engine went away"
            );
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }

    /// Tear the trio down (added engines too).
    pub(crate) async fn stop(mut self) {
        self.client.shutdown();
        if let Some(mut listener) = self.listener.take() {
            listener.stop().await;
        }
        for mut engine in std::mem::take(&mut self.extra) {
            if let Some(mut listener) = engine.listener.take() {
                listener.stop().await;
            }
            engine.core.shutdown().await;
        }
        self.core.shutdown().await;
    }
}

/// Whether `git` is runnable on this host (git-dependent tests skip with
/// a printed reason when it is not; Windows CI has git).
pub(crate) fn git_available() -> bool {
    std::process::Command::new("git")
        .arg("--version")
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .is_ok_and(|status| status.success())
}

/// Run git in `cwd`, asserting success.
pub(crate) fn git(cwd: &Path, args: &[&str]) {
    let out = std::process::Command::new("git")
        .args(args)
        .current_dir(cwd)
        .output()
        .expect("git runs");
    assert!(
        out.status.success(),
        "git {args:?} failed: {}",
        String::from_utf8_lossy(&out.stderr)
    );
}

/// Init a repo at `dir` with one committed file and extra branches, as
/// `crates/engine/tests/worktree_on_run.rs` does.
pub(crate) fn init_repo(dir: &Path, branches: &[&str]) {
    std::fs::create_dir_all(dir).expect("repo dir");
    git(dir, &["init", "-b", "main"]);
    std::fs::write(dir.join("a.txt"), "one\ntwo\n").expect("write a.txt");
    git(dir, &["add", "."]);
    git(
        dir,
        &[
            "-c",
            "user.name=Rig",
            "-c",
            "user.email=rig@roboco.test",
            "commit",
            "-m",
            "initial",
        ],
    );
    for branch in branches {
        git(dir, &["branch", branch]);
    }
}
