//! One live `mimir plugin run sh.roboco.bridge` process: launch, handshake,
//! typed session calls, and ownership of the process tree until it is reaped.
//! Many chats share one runtime; each holds its own native attachment.

use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::Duration;

use serde::de::DeserializeOwned;
use serde_json::{Value, json};
use tokio::io::AsyncBufReadExt;
use tokio::sync::{mpsc, oneshot, watch};

use super::client::{Client, DEFAULT_TIMEOUT};
use super::protocol::*;
use crate::StderrTail;
use crate::process::{Command, Stdio};

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

/// Why a runtime could not start.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum Failure {
    #[error("{0}")]
    MissingExecutable(String),
    #[error("{0}")]
    PluginMissing(String),
    #[error("{0}")]
    Incompatible(String),
    #[error("{0}")]
    Failed(String),
}

#[derive(Debug, Clone)]
pub struct Exit {
    pub message: String,
    /// The engine asked the bridge to stop; not a crash.
    pub requested: bool,
}

pub(crate) struct Launch {
    pub executable: PathBuf,
    pub env: Vec<(String, String)>,
    pub env_removed: Vec<String>,
    pub gate: Option<Arc<tokio::sync::RwLock<()>>>,
    pub generation: u64,
}

const INITIALIZE_TIMEOUT: Duration = Duration::from_secs(120);
const EXIT_GRACE: Duration = Duration::from_secs(30);
const KILL_GRACE: Duration = Duration::from_secs(3);

pub struct Runtime {
    client: Client,
    pub hello: Hello,
    pub executable: PathBuf,
    /// Increases with every launch; attachments are only valid in theirs.
    pub generation: u64,
    /// The bridge process id, for diagnostics.
    pub pid: Option<u32>,
    exit: watch::Receiver<Option<Exit>>,
    stop: Mutex<Option<oneshot::Sender<()>>>,
    attachments: Mutex<HashSet<String>>,
}

impl Runtime {
    pub(crate) async fn launch(spec: Launch) -> Result<Arc<Self>, Failure> {
        // The process holds the harness execution lease from spawn until its
        // tree is reaped, so an accepted CLI update never replaces a binary
        // under a live bridge.
        let lease = match &spec.gate {
            Some(gate) => Some(gate.clone().read_owned().await),
            None => None,
        };
        let mut command = Command::new(&spec.executable);
        command.arg("plugin").arg("run").arg(PLUGIN_ID);
        command.current_dir(crate::executable::home_or_current_dir());
        for key in &spec.env_removed {
            command.env_remove(key);
        }
        for (key, value) in &spec.env {
            command.env(key, value);
        }
        crate::acp::child::configure(&mut command);
        crate::compose_child_path(&mut command, &spec.executable);
        command
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        let child = command.spawn().map_err(|error| {
            if error.kind() == std::io::ErrorKind::NotFound {
                Failure::MissingExecutable(crate::executable::binary_hint(&spec.executable))
            } else {
                Failure::Failed(format!("could not start the Mimir bridge: {error}"))
            }
        })?;
        let mut child = crate::acp::child::Child::new(child);
        let pid = child.id();
        let stdin = child.stdin.take();
        let stdout = child.stdout.take();
        let (Some(stdin), Some(stdout)) = (stdin, stdout) else {
            return Err(Failure::Failed(
                "the Mimir bridge has no stdio pipes".into(),
            ));
        };
        let stderr = StderrTail::default();
        if let Some(pipe) = child.stderr.take() {
            let tail = stderr.clone();
            tokio::spawn(async move {
                let mut lines = tokio::io::BufReader::new(pipe).lines();
                while let Ok(Some(line)) = lines.next_line().await {
                    tracing::debug!(target: "roboco_harness::mimir", "bridge stderr: {line}");
                    tail.push(&line);
                }
                tail.close();
            });
        } else {
            stderr.close();
        }
        let client = Client::start(stdout, stdin);
        let (exit_tx, exit) = watch::channel(None);
        let (stop_tx, stop_rx) = oneshot::channel();
        tokio::spawn(monitor(child, stop_rx, stderr.clone(), exit_tx, lease));

        let params = json!({
            "protocol_versions": [PROTOCOL_VERSION],
            "client": {"name": "roboco", "version": env!("CARGO_PKG_VERSION")},
            "limits": {"max_frame_bytes": super::FRAME_BYTES, "chunk_bytes": super::CHUNK_BYTES},
        });
        let started = client.call("initialize", params, INITIALIZE_TIMEOUT).await;
        let fail = |failure: Failure, stop: oneshot::Sender<()>, client: &Client| {
            client.close_input();
            let _ = stop.send(());
            failure
        };
        let hello = match started {
            Ok(value) => match serde_json::from_value::<Hello>(value) {
                Ok(hello) => hello,
                Err(error) => {
                    return Err(fail(
                        Failure::Incompatible(format!("unreadable bridge handshake: {error}")),
                        stop_tx,
                        &client,
                    ));
                }
            },
            Err(error) if error.kind == ErrorKind::IncompatibleProtocol => {
                return Err(fail(
                    Failure::Incompatible(format!(
                        "the installed bridge does not speak protocol {PROTOCOL_VERSION}: {}",
                        error.message
                    )),
                    stop_tx,
                    &client,
                ));
            }
            Err(error) if error.kind == ErrorKind::Disconnected => {
                // The process ended before the handshake; its stderr says why.
                let mut exit = exit.clone();
                let ended = tokio::time::timeout(EXIT_GRACE, exit.wait_for(Option::is_some)).await;
                let message = match ended {
                    Ok(Ok(done)) => done.as_ref().map(|e| e.message.clone()).unwrap_or_default(),
                    _ => {
                        let _ = stop_tx.send(());
                        error.message.clone()
                    }
                };
                return Err(if message.contains("must be installed") {
                    Failure::PluginMissing(message)
                } else {
                    Failure::Failed(format!("the Mimir bridge did not start: {message}"))
                });
            }
            Err(error) => {
                return Err(fail(
                    Failure::Failed(format!(
                        "the Mimir bridge refused initialize: {}",
                        error.message
                    )),
                    stop_tx,
                    &client,
                ));
            }
        };
        if let Err(reason) = hello.check() {
            return Err(fail(Failure::Incompatible(reason), stop_tx, &client));
        }
        client.set_max_frame(hello.limits.max_frame_bytes);
        Ok(Arc::new(Self {
            client,
            hello,
            executable: spec.executable,
            generation: spec.generation,
            pid,
            exit,
            stop: Mutex::new(Some(stop_tx)),
            attachments: Mutex::new(HashSet::new()),
        }))
    }

    pub fn alive(&self) -> bool {
        self.exit.borrow().is_none() && self.client.closed().borrow().is_none()
    }

    pub fn exited(&self) -> watch::Receiver<Option<Exit>> {
        self.exit.clone()
    }

    /// Settles only after the process tree is reaped and its lease released.
    pub async fn wait_exit(&self) -> Exit {
        let mut exit = self.exit.clone();
        match exit.wait_for(Option::is_some).await {
            Ok(done) => done.clone().expect("checked"),
            Err(_) => Exit {
                message: "bridge monitor ended".into(),
                requested: false,
            },
        }
    }

    /// Escalate toward exit without the graceful handshake (a dead pipe).
    pub fn request_stop(&self) {
        self.client.close_input();
        if let Some(stop) = lock(&self.stop).take() {
            let _ = stop.send(());
        }
    }

    pub fn attachment_count(&self) -> usize {
        lock(&self.attachments).len()
    }

    /// Release every attachment through the host, then stop the process.
    pub async fn shutdown(&self) -> Exit {
        if self.alive() {
            let _ = self
                .client
                .call("bridge.shutdown", json!({}), EXIT_GRACE)
                .await;
        }
        self.request_stop();
        lock(&self.attachments).clear();
        self.wait_exit().await
    }

    async fn call<T: DeserializeOwned>(
        &self,
        method: &str,
        params: Value,
    ) -> Result<T, BridgeError> {
        let value = self.client.call(method, params, DEFAULT_TIMEOUT).await?;
        serde_json::from_value(value)
            .map_err(|error| BridgeError::malformed(format!("{method} reply: {error}")))
    }

    async fn field<T: DeserializeOwned>(
        &self,
        method: &str,
        params: Value,
        field: &str,
    ) -> Result<T, BridgeError> {
        let mut value: Value = self.call(method, params).await?;
        let inner = value.get_mut(field).map(Value::take).unwrap_or(Value::Null);
        serde_json::from_value(inner).map_err(|error| {
            BridgeError::malformed(format!("{method} reply field {field}: {error}"))
        })
    }

    pub async fn catalog(&self, cwd: &str) -> Result<Vec<ProviderChoice>, BridgeError> {
        self.field("catalog", json!({"cwd": cwd}), "providers")
            .await
    }

    pub async fn create(
        &self,
        cwd: &str,
        change: &ConfigurationChange,
    ) -> Result<Attached, BridgeError> {
        let attached: Attached = self
            .call(
                "session.create",
                json!({"cwd": cwd, "configuration": change}),
            )
            .await?;
        lock(&self.attachments).insert(attached.session.clone());
        Ok(attached)
    }

    /// Busy when another Mimir process owns the conversation.
    pub async fn open(&self, session: &str) -> Result<Attached, BridgeError> {
        let attached: Attached = self
            .call("session.open", json!({"session": session}))
            .await?;
        lock(&self.attachments).insert(attached.session.clone());
        Ok(attached)
    }

    /// Release the attachment and await the host's cleanup and lease release.
    pub async fn close(&self, session: &str) -> Result<bool, BridgeError> {
        let released = self
            .field("session.close", json!({"session": session}), "released")
            .await;
        if released.is_ok() || !self.alive() {
            lock(&self.attachments).remove(session);
        }
        released
    }

    pub async fn state(&self, session: &str) -> Result<ViewState, BridgeError> {
        self.call("session.state", json!({"session": session}))
            .await
    }

    pub async fn open_view(
        &self,
        session: &str,
    ) -> Result<(String, mpsc::UnboundedReceiver<ViewNote>), BridgeError> {
        self.client
            .open_view(
                json!({"session": session, "detail": "full"}),
                DEFAULT_TIMEOUT,
            )
            .await
    }

    pub async fn close_view(&self, view: &str) {
        let _ = self
            .client
            .call("view.close", json!({"view": view}), DEFAULT_TIMEOUT)
            .await;
        self.client.forget_view(view);
    }

    pub async fn read_entries(
        &self,
        session: &str,
        cut: &JournalCut,
        anchor: &EntryAnchor,
    ) -> Result<EntryPage, BridgeError> {
        self.call(
            "session.read_entries",
            json!({"session": session, "cut": cut, "anchor": anchor, "max_entries": 512, "max_bytes": 4u32 << 20}),
        )
        .await
    }

    /// The exact JSON bytes of one oversized entry, read in chunks.
    pub async fn read_entry(
        &self,
        session: &str,
        cut: &JournalCut,
        id: &str,
        total: u64,
    ) -> Result<Vec<u8>, BridgeError> {
        use base64::Engine as _;
        let mut bytes = Vec::with_capacity(total.min(64 << 20) as usize);
        while (bytes.len() as u64) < total {
            let chunk: EntryChunk = self
                .call(
                    "session.read_entry_chunk",
                    json!({"session": session, "cut": cut, "id": id, "offset": bytes.len()}),
                )
                .await?;
            if chunk.encoding != "base64" || chunk.offset != bytes.len() as u64 {
                return Err(BridgeError::malformed("entry chunk out of order"));
            }
            let data = base64::engine::general_purpose::STANDARD
                .decode(chunk.data)
                .map_err(|error| BridgeError::malformed(format!("entry chunk: {error}")))?;
            if data.is_empty() || data.len() != chunk.length {
                return Err(BridgeError::malformed("entry chunk length mismatch"));
            }
            bytes.extend_from_slice(&data);
            if chunk.total != total {
                return Err(BridgeError::malformed("entry size changed between chunks"));
            }
        }
        Ok(bytes)
    }

    pub async fn commands(&self, session: &str) -> Result<Vec<CommandDescriptor>, BridgeError> {
        self.field("session.commands", json!({"session": session}), "commands")
            .await
    }

    pub async fn command(
        &self,
        session: &str,
        name: &str,
        tail: &str,
    ) -> Result<CommandResult, BridgeError> {
        self.field(
            "session.command",
            json!({"session": session, "name": name, "tail": tail}),
            "result",
        )
        .await
    }

    pub async fn skills(&self, session: &str) -> Result<Vec<SkillDescription>, BridgeError> {
        self.field("session.skills", json!({"session": session}), "skills")
            .await
    }

    pub async fn plan(&self, session: &str) -> Result<Option<PlanArtifact>, BridgeError> {
        self.field("session.plan", json!({"session": session}), "plan")
            .await
    }

    pub async fn children(&self, session: &str) -> Result<Vec<ChildInfo>, BridgeError> {
        self.field("session.children", json!({"session": session}), "children")
            .await
    }

    /// Exactly `attempt`'s outcome once it is terminal, also after the child
    /// was continued; `None` while it runs.
    pub async fn child_outcome(
        &self,
        session: &str,
        handle: &str,
        attempt: u32,
    ) -> Result<Option<ChildOutcome>, BridgeError> {
        self.field(
            "session.child_outcome",
            json!({"session": session, "handle": handle, "attempt": attempt}),
            "outcome",
        )
        .await
    }

    pub async fn steer_child(
        &self,
        session: &str,
        handle: &str,
        attempt: u32,
        text: &str,
    ) -> Result<ChildControl, BridgeError> {
        self.field(
            "session.steer_child",
            json!({"session": session, "handle": handle, "attempt": attempt, "text": text}),
            "control",
        )
        .await
    }

    pub async fn stop_child(
        &self,
        session: &str,
        handle: &str,
        attempt: u32,
    ) -> Result<ChildControl, BridgeError> {
        self.field(
            "session.stop_child",
            json!({"session": session, "handle": handle, "attempt": attempt}),
            "control",
        )
        .await
    }

    /// A repeated `submission_key` returns the original admission without new work.
    pub async fn prompt(
        &self,
        session: &str,
        input: &PromptInput,
        delivery: Delivery,
        submission_key: &str,
    ) -> Result<String, BridgeError> {
        self.field(
            "session.prompt",
            json!({"session": session, "input": input, "delivery": delivery, "submission_key": submission_key}),
            "request_id",
        )
        .await
    }

    pub async fn steer(&self, session: &str, text: &str) -> Result<(), BridgeError> {
        self.call::<Value>("session.steer", json!({"session": session, "text": text}))
            .await
            .map(drop)
    }

    pub async fn configure(
        &self,
        session: &str,
        change: &ConfigurationChange,
    ) -> Result<Configuration, BridgeError> {
        self.field(
            "session.configure",
            json!({"session": session, "change": change}),
            "configuration",
        )
        .await
    }

    pub async fn decide_plan(
        &self,
        session: &str,
        plan_id: &str,
        decision: PlanDecision,
    ) -> Result<Option<String>, BridgeError> {
        self.field(
            "session.decide_plan",
            json!({"session": session, "plan_id": plan_id, "decision": decision}),
            "request_id",
        )
        .await
    }

    pub async fn change_goal(
        &self,
        session: &str,
        change: &GoalChange,
    ) -> Result<Option<String>, BridgeError> {
        self.field(
            "session.change_goal",
            json!({"session": session, "change": change}),
            "request_id",
        )
        .await
    }

    pub async fn answer(
        &self,
        session: &str,
        request_id: &str,
        answers: &[Answer],
    ) -> Result<(), BridgeError> {
        self.call::<Value>(
            "session.answer",
            json!({"session": session, "request_id": request_id, "answers": answers}),
        )
        .await
        .map(drop)
    }

    /// True only when this call newly cancelled the request.
    pub async fn cancel_request(
        &self,
        session: &str,
        request_id: &str,
    ) -> Result<bool, BridgeError> {
        self.field(
            "session.cancel_request",
            json!({"session": session, "request_id": request_id}),
            "cancelled",
        )
        .await
    }

    pub async fn attach_mcp(
        &self,
        session: &str,
        server: &roboco_proto::McpServer,
    ) -> Result<String, BridgeError> {
        let env: Vec<(String, String)> = server
            .env
            .iter()
            .map(|(k, v)| (k.clone(), v.clone()))
            .collect();
        self.field(
            "session.attach_mcp",
            json!({"session": session, "servers": [{"name": server.name, "transport": {"stdio": {
                "command": server.command, "args": server.args, "env": env}}}]}),
            "attachment",
        )
        .await
    }
}

async fn monitor(
    mut child: crate::acp::child::Child,
    stop: oneshot::Receiver<()>,
    stderr: StderrTail,
    exit: watch::Sender<Option<Exit>>,
    lease: Option<tokio::sync::OwnedRwLockReadGuard<()>>,
) {
    let (status, requested) = tokio::select! {
        status = child.wait() => (status.ok(), false),
        _ = stop => {
            let status = match tokio::time::timeout(EXIT_GRACE, child.wait()).await {
                Ok(status) => status.ok(),
                Err(_) => {
                    tracing::warn!(target: "roboco_harness::mimir", "bridge ignored shutdown; terminating its process tree");
                    child.shutdown(KILL_GRACE).await;
                    child.try_wait().ok().flatten()
                }
            };
            (status, true)
        }
    };
    // Descendants in the bridge's process group or job go with it.
    child.terminate_group();
    stderr.wait_closed().await;
    let message = if requested {
        "Mimir bridge stopped".to_owned()
    } else {
        crate::crash_message("Mimir bridge", status, &stderr)
    };
    drop(child);
    drop(lease);
    exit.send_replace(Some(Exit { message, requested }));
}
