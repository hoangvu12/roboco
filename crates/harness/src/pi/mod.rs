//! Pi harness: spawns the installed `pi` CLI as `pi --mode rpc` and speaks
//! pi's first-party JSONL protocol directly (commands on stdin, id-correlated
//! responses + events on stdout) — the maintainer-supported embedding path
//! (`pi.dev/docs/latest/rpc`, `pi-mono` `packages/coding-agent/docs/rpc.md`,
//! reference client `src/modes/rpc/rpc-client.ts`; validated against
//! `@earendil-works/pi-coding-agent` 0.87.0). Replaces the community
//! `pi-acp` adapter bridge (and our `@hoangnguyenvu12/pi-acp` fork of it):
//! ACP support in pi is not first-party (maintainer issue #175), and every
//! adapter-mediated path re-derives what this wire owns natively —
//! authoritative turn settlement, usage/context stats, extension dialogs.
//!
//! Roboco-first port (ticket 21): no upstream SHA — upstream zeron still
//! ships the community adapter. Decision record: this file + PARITY.md.
//!
//! - **Framing**: strict JSONL, LF-only records, one trailing `\r`
//!   tolerated, non-JSON noise skipped ([`wire`]; the `371d79b1` lessons).
//!   No handshake: the first command may be sent immediately.
//! - **Session**: spawn with no session flags (new persistent session) or
//!   `--session <file>` when resuming — always the FULL file path from
//!   `get_state.sessionFile` (a bare id can hit pi's global session search,
//!   which prompts interactively on stdin — the protocol channel). The
//!   resume pointer rides `SessionStarted/Done.session_id`.
//! - **Prompting**: `prompt` while idle (response = preflight acceptance);
//!   a mid-run steer goes through pi's `steer` queue (delivered after the
//!   current assistant turn's tool calls); `abort` interrupts, waiting for
//!   idle before responding. `follow_up` stays unused: a parked session's
//!   next message is a fresh `prompt` in roboco's model.
//! - **Turn settlement**: `agent_settled` is the authoritative end — every
//!   turn shape ends with a deterministic `Done` (the engine retires its
//!   quiesce watchdogs). Turn errors reject the prompt: a final assistant
//!   error (`stopReason: "error"`), exhausted `auto_retry_*`, or a failed
//!   `compaction_end` without `willRetry` produce `Done { Errored }`.
//! - **Stats**: `get_session_stats.contextUsage` feeds the context meter
//!   after each settle; `message_update.usage` rides per-turn `Usage`
//!   passthrough — first-party replacements for the pi-acp fork's
//!   usage_update/turn-error patches.
//! - **Extension UI**: blocking dialogs (`select`/`confirm`/`input`/`editor`)
//!   map to roboco's question flow and are answered with
//!   `extension_ui_response`; fire-and-forget methods are tolerated.
//! - **Models**: the live catalog comes from `get_available_models`
//!   (provider-scoped rows, composite `<provider>/<modelId>` ids — the
//!   pi-acp spelling pre-native chats saved). A requested model is applied
//!   with `set_model` at startup (best-effort, pi-acp parity); a bare
//!   `default`/unset id passes through to pi's own `~/.pi` selection.
//!   `set_thinking_level` applies the run's reasoning (pi clamps to the
//!   model's ladder itself).
//! - **Titles**: `run_title` spawns an isolated one-shot (`--no-session`,
//!   `--no-tools`, pi's other discovery-off flags, `--system-prompt` = the
//!   shared title instructions). `--no-extensions` is deliberately NOT
//!   passed: provider configs can be extension-registered, and the model
//!   must still resolve.
//! - **Interrupt**: `abort`, then SIGTERM → SIGKILL escalation; the stream
//!   always ends with `Done { status: Interrupted }`.

mod normalize;
mod wire;

use std::collections::VecDeque;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use async_trait::async_trait;
use futures::StreamExt;
use futures::future::BoxFuture;
use futures::stream::{FuturesUnordered, BoxStream};
use serde_json::{Value, json};
use tokio::io::AsyncBufReadExt;
use tokio::sync::{mpsc, oneshot};

use roboco_proto::{
    AgentEvent, DoneStatus, HarnessId, Model, ReasoningLevel, RunRequest, SteeringMode,
    UserInputAnswer, UserInputQuestion,
};

use crate::acp::child;
use crate::process::{Command, Stdio};
use crate::{Harness, HarnessError, RunControls, Signal, send_signal};
use normalize::{
    context_usage_event, dialog, dialog_response, message_end_error, message_end_events,
    message_update_events, output_text, thinking_level, tool_diff, typed_call, usage_event,
};
use wire::{PiClient, PiIncoming};

/// The pass-through picker row, used as the fallback catalog when live
/// discovery fails cold (offline, no provider configured): pi decides the
/// model from its own `~/.pi` provider config, so roboco advertises one
/// entry and never switches models underneath the user.
fn pass_through_models() -> Vec<Model> {
    vec![Model {
        id: "default".into(),
        label: "pi default".into(),
        description: Some("Runs the model configured in pi (`pi` settings)".into()),
        reasoning_levels: vec![
            ReasoningLevel::Minimal,
            ReasoningLevel::Low,
            ReasoningLevel::Medium,
            ReasoningLevel::High,
            ReasoningLevel::XHigh,
            ReasoningLevel::Max,
        ],
        options: Vec::new(),
    }]
}

const REASONING_LEVELS: [ReasoningLevel; 6] = [
    ReasoningLevel::Minimal,
    ReasoningLevel::Low,
    ReasoningLevel::Medium,
    ReasoningLevel::High,
    ReasoningLevel::XHigh,
    ReasoningLevel::Max,
];

const INSTALL_HINT: &str = "pi (searched PATH, the login shell's PATH, npm global bins, \
     and fnm/nvm/volta/pnpm/bun install dirs; install with \
     `npm install -g @earendil-works/pi-coding-agent` — Node.js >= 24; set \
     PI_EXECUTABLE to override)";

/// pi accepts both spellings for its npm-global install locations.
fn pi_install_paths() -> Vec<PathBuf> {
    let mut dirs = Vec::new();
    if let Some(home) = crate::executable::home_dir() {
        dirs.push(home.join(".local").join("bin").join("pi"));
        dirs.push(home.join(".npm-global").join("bin").join("pi"));
    }
    dirs.push(PathBuf::from("/opt/homebrew/bin/pi"));
    dirs.push(PathBuf::from("/usr/local/bin/pi"));
    dirs
}

/// Resolve the device's installed pi CLI: `PI_EXECUTABLE`, then our own PATH,
/// then the login-shell PATH snapshot, then npm-global and node-version
/// manager install locations. Resolved per call — cheap after the snapshot
/// is cached.
fn resolve_pi_executable() -> Option<PathBuf> {
    crate::executable::find_on_paths("pi", pi_install_paths())
}

/// Map pi's `get_available_models` payload onto picker rows. Every entry is
/// scoped by the provider config that serves it (`provider` + `id` — the id
/// may itself contain slashes); the row id is the composite
/// `<provider>/<id>`: the spelling `set_model` splits back into its two
/// fields, the `--model` CLI flag accepts, and pre-native chats saved under
/// the pi-acp adapter. Entries without a provider or id are skipped; a
/// missing name falls back to the id. Reasoning models carry
/// pi's own ladder; non-reasoning models get none (the agent default runs —
/// `set_thinking_level` clamps against the model's ladder on pi's side).
fn available_models(value: &Value) -> Vec<Model> {
    value
        .get("models")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|entry| {
            let provider = entry
                .get("provider")
                .and_then(Value::as_str)
                .map(str::trim)
                .filter(|p| !p.is_empty())?;
            let id = entry
                .get("id")
                .and_then(Value::as_str)
                .map(str::trim)
                .filter(|id| !id.is_empty())?;
            let name = entry
                .get("name")
                .and_then(Value::as_str)
                .map(str::trim)
                .filter(|name| !name.is_empty())
                .unwrap_or(id);
            Some(Model {
                id: format!("{provider}/{id}"),
                label: format!("{provider}/{name}"),
                description: None,
                reasoning_levels: entry
                    .get("reasoning")
                    .and_then(Value::as_bool)
                    .unwrap_or(false)
                    .then(|| REASONING_LEVELS.to_vec())
                    .unwrap_or_default(),
                options: Vec::new(),
            })
        })
        .collect()
}

/// Resolve + apply a requested model id through pi's `set_model`. Composite
/// ids split on the FIRST slash (provider, then pi's own model id); a bare
/// id is a legacy spelling, recovered by looking the model up in the live
/// catalog for its provider (exactly the pi-acp fork's `setSessionModel`).
async fn apply_model(client: &PiClient, model: &str) -> Result<(), HarnessError> {
    let (provider, model_id) = match model.split_once('/') {
        Some((provider, id)) => (provider.to_owned(), id.to_owned()),
        None => {
            let available = client.request("get_available_models", json!({})).await?;
            let entry = available
                .get("models")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
                .find(|entry| entry.get("id").and_then(Value::as_str) == Some(model))
                .ok_or_else(|| HarnessError::Protocol(format!("unknown model: {model}")))?;
            let provider = entry
                .get("provider")
                .and_then(Value::as_str)
                .map(str::trim)
                .filter(|p| !p.is_empty())
                .ok_or_else(|| {
                    HarnessError::Protocol(format!("model {model} has no provider"))
                })?;
            (provider.to_owned(), model.to_owned())
        }
    };
    client
        .request("set_model", json!({ "provider": provider, "modelId": model_id }))
        .await
        .map(|_| ())
}

/// The pi harness. Construct with [`PiHarness::new`]; tests point it at a
/// fixture RPC process with [`PiHarness::with_executable`].
pub struct PiHarness {
    executable: Option<PathBuf>,
    /// Grace between `abort` and SIGTERM.
    interrupt_grace: Duration,
    /// Grace between SIGTERM and SIGKILL.
    kill_grace: Duration,
    /// Bound on spawn → first `get_state` response; pi's startup (session
    /// load, background catalog refresh) must finish inside it or the run
    /// errors instead of spinning "Working" forever.
    startup_timeout: Duration,
    /// Provider-scoped successful catalog, with bounded refresh and backoff.
    models_cache: crate::catalog::Catalog,
}

impl Default for PiHarness {
    fn default() -> Self {
        Self {
            executable: None,
            interrupt_grace: Duration::from_secs(2),
            kill_grace: Duration::from_secs(3),
            startup_timeout: Duration::from_secs(60),
            models_cache: crate::catalog::Catalog::default(),
        }
    }
}

impl PiHarness {
    pub fn new() -> Self {
        Self::default()
    }

    /// Use a fixed CLI binary instead of PATH/known-location resolution.
    pub fn with_executable(mut self, path: impl Into<PathBuf>) -> Self {
        self.executable = Some(path.into());
        self
    }

    /// Tune the interrupt→SIGTERM→SIGKILL escalation timing.
    pub fn with_graces(mut self, interrupt_grace: Duration, kill_grace: Duration) -> Self {
        self.interrupt_grace = interrupt_grace;
        self.kill_grace = kill_grace;
        self
    }

    /// Tune the spawn → `get_state` budget (tests).
    pub fn with_startup_timeout(mut self, timeout: Duration) -> Self {
        self.startup_timeout = timeout;
        self
    }

    fn resolve_executable(&self) -> Result<PathBuf, HarnessError> {
        if let Some(p) = &self.executable {
            return crate::executable::validate_native_override(p);
        }
        if let Some(p) = std::env::var_os("PI_EXECUTABLE") && !p.is_empty() {
            return crate::executable::validate_native_override(&PathBuf::from(p));
        }
        resolve_pi_executable().ok_or_else(|| HarnessError::NotInstalled(INSTALL_HINT.into()))
    }

    /// Spawn the probe `pi --mode rpc` and read `get_available_models`.
    /// Ephemeral (`--no-session`): discovery never litters pi's session
    /// history. Runs with extensions ENABLED — provider configs can be
    /// extension-registered (a custom gateway), and the catalog must mirror
    /// what a real run would see. Events (notify noise) are drained so a
    /// chatty extension can never stall the reader.
    async fn discover_models(&self) -> Result<Vec<Model>, HarnessError> {
        let exe = self.resolve_executable()?;
        let mut cmd = Command::new(&exe);
        cmd.arg("--mode").arg("rpc").arg("--no-session");
        cmd.current_dir(crate::executable::home_or_current_dir());
        child::configure(&mut cmd);
        crate::compose_child_path(&mut cmd, &exe);
        cmd.stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .kill_on_drop(true);
        let child = cmd.spawn().map_err(|e| {
            if e.kind() == std::io::ErrorKind::NotFound {
                HarnessError::NotInstalled(crate::executable::binary_hint(&exe))
            } else {
                HarnessError::Io(e)
            }
        })?;
        let mut child = child::Child::new(child);
        let stdin = child
            .stdin
            .take()
            .ok_or_else(|| HarnessError::Protocol("pi child has no stdin".into()))?;
        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| HarnessError::Protocol("pi child has no stdout".into()))?;
        let (client, mut incoming) = PiClient::new(stdin, stdout);
        tokio::spawn(async move {
            while incoming.recv().await.is_some() {}
        });
        let probe = client.request("get_available_models", json!({}));
        let models = tokio::time::timeout(Duration::from_secs(15), probe)
            .await
            .map_err(|_| HarnessError::Protocol("pi model discovery timed out".into()))??;
        child.shutdown(self.kill_grace).await;
        Ok(available_models(&models))
    }

    /// The shared run body: `run` passes `title_only = false`;
    /// [`Harness::run_title`] sanitizes the request and passes `true`,
    /// which restricts the spawn (see the title-sandbox comment in the
    /// command construction below).
    async fn run_with_mode(
        &self,
        request: RunRequest,
        controls: RunControls,
        title_only: bool,
    ) -> Result<BoxStream<'static, Result<AgentEvent, HarnessError>>, HarnessError> {
        let exe = self.resolve_executable()?;
        // The resume pointer is the FULL session file path pi reported
        // (`get_state.sessionFile`): a bare id can resolve through pi's
        // global session search into an interactive "fork into this
        // directory?" prompt — on stdin, the protocol channel. A pointer
        // that is not an existing file (a legacy ACP-era id, a moved
        // session) degrades to a fresh session with a visible notice.
        let resume_path = request
            .resume
            .as_deref()
            .filter(|resume| Path::new(resume).is_file())
            .map(str::to_owned);

        let mut cmd = Command::new(&exe);
        cmd.arg("--mode").arg("rpc");
        if title_only {
            // The title sandbox: no session file, no tools (pi's --no-tools
            // covers built-in AND extension tools), no skills / prompt
            // templates / context files / themes — and the shared title
            // instructions replace pi's coding system prompt. Extensions
            // stay ON: provider configs can be extension-registered, and
            // the model must still resolve (verified against a real CLI:
            // --no-extensions leaves get_state reporting `model: unknown`).
            cmd.arg("--no-session")
                .arg("--no-tools")
                .arg("--no-skills")
                .arg("--no-prompt-templates")
                .arg("--no-context-files")
                .arg("--no-themes")
                .arg("--system-prompt")
                .arg(crate::TITLE_INSTRUCTIONS);
        }
        if let Some(resume) = &resume_path {
            cmd.arg("--session").arg(resume);
        }
        // pi takes the working directory as the child process cwd (there is
        // no --cwd flag; the reference client does the same).
        if !request.cwd.is_empty() {
            cmd.current_dir(&request.cwd);
        }
        child::configure(&mut cmd);
        crate::compose_child_path(&mut cmd, &exe);
        cmd.stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        let child = cmd.spawn().map_err(|e| {
            if e.kind() == std::io::ErrorKind::NotFound {
                HarnessError::NotInstalled(crate::executable::binary_hint(&exe))
            } else {
                HarnessError::Io(e)
            }
        })?;
        let mut child = child::Child::new(child);
        let stdin = child
            .stdin
            .take()
            .ok_or_else(|| HarnessError::Protocol("pi child has no stdin".into()))?;
        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| HarnessError::Protocol("pi child has no stdout".into()))?;
        let stderr_tail = crate::StderrTail::default();
        if let Some(stderr) = child.stderr.take() {
            let tail = stderr_tail.clone();
            tokio::spawn(async move {
                let mut lines = tokio::io::BufReader::new(stderr).lines();
                while let Ok(Some(line)) = lines.next_line().await {
                    tracing::debug!(target: "roboco_harness::pi", "stderr: {line}");
                    tail.push(&line);
                }
                tail.close();
            });
        }

        let (client, incoming) = PiClient::new(stdin, stdout);
        let (event_tx, event_rx) = mpsc::channel::<Result<AgentEvent, HarnessError>>(256);
        tokio::spawn(run_session(Session {
            child,
            client,
            incoming,
            event_tx,
            controls,
            request,
            resume_path,
            interrupt_grace: self.interrupt_grace,
            kill_grace: self.kill_grace,
            startup_timeout: self.startup_timeout,
            stderr_tail,
        }));

        Ok(futures::stream::unfold(event_rx, |mut rx| async move {
            rx.recv().await.map(|ev| (ev, rx))
        })
        .boxed())
    }
}

#[async_trait]
impl Harness for PiHarness {
    fn id(&self) -> HarnessId {
        HarnessId::Pi
    }
    fn display_name(&self) -> &str {
        "Pi"
    }
    fn supports_steering(&self) -> bool {
        true
    }
    /// pi's `steer` queue delivers after the current assistant turn's tool
    /// calls — turn boundaries, matching the registry descriptor.
    fn steering_mode(&self) -> SteeringMode {
        SteeringMode::TurnBoundary
    }
    fn reasoning_levels(&self) -> &[ReasoningLevel] {
        &REASONING_LEVELS
    }
    fn installed(&self) -> bool {
        self.resolve_executable().is_ok()
    }
    fn executable_path(&self) -> Option<std::path::PathBuf> {
        self.resolve_executable().ok()
    }
    /// `agent_settled` ends every turn shape — user-prompted and
    /// agent-initiated (extension runs) — with a deterministic `Done`.
    fn deterministic_turn_end(&self) -> bool {
        true
    }

    /// Credential context (auth.json + binary identity). The provider
    /// catalog (`models-store.json`, what `pi update` refreshes) deliberately
    /// does NOT ride the hash: pi rewrites it on every RPC-mode startup
    /// (background catalog refresh), so hashing it makes each probe
    /// invalidate its own context. Store changes surface through the
    /// harness catalog's bounded refresh (60s window) and the engine's
    /// background re-probe instead.
    fn model_context(&self) -> Result<Option<crate::ModelContext>, HarnessError> {
        crate::model_context::context(self.id(), &self.resolve_executable()?, &[]).map(Some)
    }
    fn fallback_models(&self) -> Vec<Model> {
        pass_through_models()
    }
    /// Live catalog via `get_available_models` — provider-scoped rows with
    /// composite `<provider>/<modelId>` ids (what `set_model` accepts and
    /// pre-native chats saved). A cold failure surfaces the error; the
    /// engine serves the pass-through fallback row when nothing is known.
    async fn model_catalog(&self, force: bool) -> Result<crate::ModelCatalog, HarnessError> {
        self.model_context()?.expect("pi context is always present").log();
        self.models_cache
            .get_with(
                force,
                || {
                    self.model_context()
                        .map(|context| context.expect("pi context is always present").key())
                },
                || self.discover_models(),
            )
            .await
    }
    async fn models(&self) -> Result<Vec<Model>, HarnessError> {
        self.model_catalog(false).await.map(|catalog| catalog.models)
    }

    async fn run(
        &self,
        request: RunRequest,
        controls: RunControls,
    ) -> Result<BoxStream<'static, Result<AgentEvent, HarnessError>>, HarnessError> {
        self.run_with_mode(request, controls, false).await
    }

    /// One-shot title runs: sanitized request, isolated spawn. The engine
    /// already builds a bare request; clearing the interaction surfaces
    /// here too means a future caller cannot smuggle a resume, worktree or
    /// attachment into a titling subprocess.
    async fn run_title(
        &self,
        mut request: RunRequest,
        controls: RunControls,
    ) -> Result<BoxStream<'static, Result<AgentEvent, HarnessError>>, HarnessError> {
        request.resume = None;
        request.worktree = None;
        request.attachments.clear();
        request.mcp = None;
        request.model_options.clear();
        request.auto_approve = false;
        self.run_with_mode(request, controls, true).await
    }
}

// ---------------------------------------------------------------------------
// Session
// ---------------------------------------------------------------------------

struct Session {
    child: child::Child,
    client: PiClient,
    incoming: mpsc::Receiver<PiIncoming>,
    event_tx: mpsc::Sender<Result<AgentEvent, HarnessError>>,
    controls: RunControls,
    request: RunRequest,
    /// The resume file actually passed as `--session` (None = fresh session).
    resume_path: Option<String>,
    interrupt_grace: Duration,
    kill_grace: Duration,
    startup_timeout: Duration,
    /// Rolling stderr tail for the crash message on an unexpected exit.
    stderr_tail: crate::StderrTail,
}

fn new_message_id() -> String {
    uuid::Uuid::new_v4().to_string()
}

/// Rotate the assistant message id; returns (previous, next).
fn rotate(id: &mut String) -> (String, String) {
    let prev = std::mem::replace(id, new_message_id());
    (prev, id.clone())
}

async fn send(tx: &mpsc::Sender<Result<AgentEvent, HarnessError>>, ev: AgentEvent) -> bool {
    tx.send(Ok(ev)).await.is_ok()
}

type RequestInputFn = Box<
    dyn Fn(Vec<UserInputQuestion>) -> oneshot::Receiver<Vec<UserInputAnswer>> + Send + Sync,
>;

/// Bound on the prompt/steer acceptance responses (pi answers at preflight —
/// fast); a hang past it is a wedged process, not a slow one.
const ACCEPT_TIMEOUT: Duration = Duration::from_secs(30);
/// Bound on the post-settle `clear_queue` + `get_session_stats` round trips.
const SETTLE_TIMEOUT: Duration = Duration::from_secs(15);
/// Drain window for frames already written when the child exits: a crash
/// right after the final response must still surface that response.
const EXIT_DRAIN: Duration = Duration::from_millis(200);
/// Short window for a response that already resolved while EOF was being
/// delivered — it rides a different channel than events, so the loop may
/// see EOF first.
const EOF_DRAIN: Duration = Duration::from_millis(50);

/// A steer request in flight: (text, already-retried-as-prompt, response).
type SteerCall = BoxFuture<'static, (String, bool, Result<Value, HarnessError>)>;

/// The post-`agent_settled` sequence: recover any steers stranded in pi's
/// queue (the settle race), then fetch the session stats for the context
/// meter. Resolves with (recovered steer texts, stats data).
type SettleOutcome = (Vec<String>, Option<Value>);

fn settle_future(client: &PiClient, pending_steers: usize) -> BoxFuture<'static, SettleOutcome> {
    let client = client.clone();
    Box::pin(async move {
        // A steer accepted while the run was active can land in pi's queue
        // during the settle window (the queue check happened first): the
        // queue would then sit undelivered while the session parks.
        // `clear_queue` hands the texts back; they redeliver as prompts.
        let recovered = if pending_steers > 0 {
            match client.request("clear_queue", json!({})).await {
                Ok(data) => ["steering", "followUp"]
                    .iter()
                    .flat_map(|key| {
                        data.get(*key)
                            .and_then(Value::as_array)
                            .cloned()
                            .unwrap_or_default()
                    })
                    .filter_map(|value| value.as_str().map(str::to_owned))
                    .collect(),
                Err(e) => {
                    tracing::debug!(
                        target: "roboco_harness::pi",
                        "clear_queue failed (stranded steers ride the next prompt): {e}"
                    );
                    Vec::new()
                }
            }
        } else {
            Vec::new()
        };
        let stats = client.request("get_session_stats", json!({})).await.ok();
        (recovered, stats)
    })
}

/// The per-run event loop: one task multiplexing pi events, the prompt
/// acceptance, the settle sequence, the steering mailbox, the interrupt
/// token, and consumer liveness.
async fn run_session(session: Session) {
    let Session {
        mut child,
        client,
        mut incoming,
        event_tx,
        controls,
        request,
        resume_path,
        interrupt_grace,
        kill_grace,
        startup_timeout,
        stderr_tail,
    } = session;
    let RunControls {
        execution_lease: _execution_lease,
        request_input,
        mut steering,
        interrupt,
    } = controls;
    let request_input: Arc<RequestInputFn> = Arc::new(request_input);

    // ---- startup: get_state (interruptible, bounded) ----------------------
    let setup = async {
        let state = client.request("get_state", json!({})).await?;
        // Apply the run's model before anything else: rows are composite
        // `<provider>/<modelId>` (or the bare `default`, which means "pi's
        // own configured model" and switches nothing). Best-effort — a
        // rejected switch logs and the agent default runs, pi-acp parity;
        // the picker pins unknown ids as "absent from the current list".
        if let Some(model) = request
            .model
            .as_deref()
            .filter(|model| !model.is_empty() && *model != "default")
        {
            if let Err(e) = apply_model(&client, model).await {
                tracing::warn!(
                    target: "roboco_harness::pi",
                    "set_model {model} rejected (pi default runs): {e}"
                );
            }
        }
        // Apply the run's thinking level (pi clamps to the model's own
        // ladder; a rejected level leaves the agent default). Best-effort:
        // an unavailable level is not a failed run.
        if let Some(reasoning) = request.reasoning {
            let level = thinking_level(reasoning);
            if let Err(e) = client
                .request("set_thinking_level", json!({ "level": level }))
                .await
            {
                tracing::debug!(
                    target: "roboco_harness::pi",
                    "set_thinking_level {level} rejected (agent default runs): {e}"
                );
            }
        }
        Ok::<Value, HarnessError>(state)
    };
    let state = tokio::select! {
        state = tokio::time::timeout(startup_timeout, setup) => {
            let state = state.unwrap_or_else(|_| {
                Err(HarnessError::Protocol(format!(
                    "pi did not answer get_state within {}s (the CLI may be \
                     waiting on a login or provider config — try running it \
                     once in a terminal)",
                    startup_timeout.as_secs()
                )))
            });
            match state {
                Ok(state) => state,
                Err(e) => {
                    // A child that dies before answering carries its exit
                    // status and stderr in hand already — give the reader a
                    // beat to drain the pipe, then append the crash text.
                    let error = match child.try_wait() {
                        Ok(Some(status)) => {
                            tokio::time::sleep(EXIT_DRAIN).await;
                            format!("{e}; {}", crate::crash_message("pi", Some(status), &stderr_tail))
                        }
                        _ => match stderr_tail.snapshot() {
                            Some(tail) => format!("{e}; stderr: {tail}"),
                            None => e.to_string(),
                        },
                    };
                    tracing::warn!(target: "roboco_harness::pi", %error, "pi setup failed");
                    let _ = event_tx
                        .send(Ok(AgentEvent::Done {
                            status: DoneStatus::Errored,
                            result: None,
                            error: Some(error),
                            session_id: None,
                        }))
                        .await;
                    child.shutdown(kill_grace).await;
                    return;
                }
            }
        },
        _ = interrupt.cancelled() => {
            let _ = event_tx
                .send(Ok(AgentEvent::Done {
                    status: DoneStatus::Interrupted,
                    result: None,
                    error: None,
                    session_id: None,
                }))
                .await;
            child.shutdown(kill_grace).await;
            return;
        }
    };

    // The durable resume pointer: the session FILE path (never the bare id —
    // see the spawn-side comment); fall back to the id only when pi reports
    // no file (in-memory sessions).
    let session_pointer = state
        .pointer("/sessionFile")
        .and_then(Value::as_str)
        .filter(|file| !file.is_empty())
        .or_else(|| state.pointer("/sessionId").and_then(Value::as_str))
        .unwrap_or_default()
        .to_owned();
    if session_pointer.is_empty() {
        let _ = event_tx
            .send(Ok(AgentEvent::Done {
                status: DoneStatus::Errored,
                result: None,
                error: Some("pi's get_state returned no session identity".into()),
                session_id: None,
            }))
            .await;
        child.shutdown(kill_grace).await;
        return;
    }

    let mut assistant_message_id = new_message_id();
    if !send(
        &event_tx,
        AgentEvent::SessionStarted {
            harness: HarnessId::Pi,
            model: request.model.clone().unwrap_or_default(),
            // pi's RPC surface has no tool listing; the driver knows only
            // that pi's own toolset is enabled (the ACP path sent none
            // either).
            tools: Vec::new(),
            cwd: request.cwd.clone(),
            session_id: session_pointer.clone(),
            assistant_message_id: assistant_message_id.clone(),
        },
    )
    .await
    {
        child.shutdown(kill_grace).await;
        return;
    }
    if request.resume.is_some() && resume_path.is_none() {
        // Parity with the ACP driver's lost-session notice: the pointer is
        // not an existing file, so this run starts fresh without it.
        let _ = send(
            &event_tx,
            AgentEvent::Error {
                message: format!(
                    "pi could not restore session {} (not found); starting a new session \
                     without the previous context",
                    request.resume.as_deref().unwrap_or_default()
                ),
            },
        )
        .await;
    }

    // ---- main loop --------------------------------------------------------
    // Agent activity since the last settle (extension-initiated runs deliver
    // output with no prompt of ours; their settle still emits a Done).
    let mut saw_activity = false;
    // Steers accepted into pi's queue (or retried as steer-behavior prompts)
    // whose delivery pi confirms with a user message.
    let mut pending_steer_deliveries: usize = 0;
    let mut steer_calls: FuturesUnordered<SteerCall> = FuturesUnordered::new();
    // Steers the driver itself owns (stranded in pi's queue at settle, or
    // queued while the settle sequence runs) — delivered as fresh prompts
    // once the current turn's Done is out.
    let mut driver_steers: VecDeque<String> = VecDeque::new();
    // The post-settle sequence (clear_queue + stats), then the Done.
    let mut settle: Option<BoxFuture<'static, SettleOutcome>> = None;
    // Per-turn token usage: the latest cumulative provider usage, emitted
    // at each turn boundary (codex parity).
    let mut last_usage: Option<Value> = None;
    // The terminal error that rejects the prompt: a failed assistant
    // message, exhausted auto-retries, or a failed compaction. A recovered
    // auto-retry clears it.
    let mut turn_error: Option<String> = None;
    let mut tracker = normalize::DeltaTracker::new();
    let mut interrupted = false;
    let mut interrupt_sent = false;
    let mut done_after_interrupt = false;
    let mut steering_open = true;
    let mut escalation: Option<tokio::task::JoinHandle<()>> = None;
    let mut child_exit: Option<Option<std::process::ExitStatus>> = None;
    let mut exit_drain_deadline: Option<tokio::time::Instant> = None;

    // Arm the first prompt. Prompt acceptance (preflight) — set when a
    // prompt is armed, cleared when its response lands (Ok = run started,
    // Err = rejected). A prompt sent while idle makes pi emit its own user
    // message first — the echo, not a steer delivery. A prompt-owned run is
    // between acceptance and its Done.
    let images = load_image_contents(&request.attachments).await;
    let mut acceptance: Option<BoxFuture<'static, Result<Value, HarnessError>>> = Some(
        prompt_acceptance(&client, &request.prompt, images, None),
    );
    let mut awaiting_prompt_echo = true;
    let mut run_active = true;
    let mut done_current = false;

    'main: loop {
        tokio::select! {
            status = child.wait(), if child_exit.is_none() => {
                child_exit = Some(status.ok());
                child.request_group_shutdown();
                // Descendants can hold stdout open after a crash: drain
                // already-written frames, but never wait forever.
                exit_drain_deadline = Some(tokio::time::Instant::now() + EXIT_DRAIN);
            },
            _ = tokio::time::sleep_until(
                exit_drain_deadline.unwrap_or_else(tokio::time::Instant::now)
            ), if exit_drain_deadline.is_some() => break 'main,

            res = async { acceptance.as_mut().expect("guarded by if").await }, if acceptance.is_some() => {
                acceptance = None;
                match res {
                    Ok(_) => { /* accepted: the run streams until agent_settled */ }
                    Err(e) => {
                        // Pre-acceptance rejection: the prompt never ran —
                        // the turn errors with pi's own reason (an
                        // interrupt in flight still wins, ACP parity).
                        let (status, error) = if interrupted {
                            (DoneStatus::Interrupted, None)
                        } else {
                            (DoneStatus::Errored, Some(e.to_string()))
                        };
                        done_current = true;
                        if interrupted {
                            done_after_interrupt = true;
                        }
                        if !send(
                            &event_tx,
                            AgentEvent::Done {
                                status,
                                result: None,
                                error,
                                session_id: Some(session_pointer.clone()),
                            },
                        )
                        .await
                        {
                            break 'main;
                        }
                        break 'main;
                    }
                }
            },

            outcome = async { settle.as_mut().expect("guarded by if").await }, if settle.is_some() => {
                settle = None;
                let (recovered, stats) = outcome;
                if !recovered.is_empty() {
                    let stranded = pending_steer_deliveries.saturating_sub(recovered.len());
                    pending_steer_deliveries = stranded;
                    let mut queue: Vec<String> = driver_steers.drain(..).collect();
                    queue.extend(recovered);
                    driver_steers = queue.into();
                } else {
                    pending_steer_deliveries = 0;
                }
                if let Some(stats) = stats.as_ref().and_then(|stats| context_usage_event(stats))
                    && !send(&event_tx, stats).await
                {
                    break 'main;
                }
                let status = if turn_error.is_some() {
                    DoneStatus::Errored
                } else {
                    DoneStatus::Completed
                };
                let error = turn_error.take();
                done_current = true;
                if interrupted {
                    done_after_interrupt = true;
                }
                if !send(
                    &event_tx,
                    AgentEvent::Done {
                        status,
                        result: None,
                        error,
                        session_id: Some(session_pointer.clone()),
                    },
                )
                .await
                {
                    break 'main;
                }
                if interrupted {
                    break 'main;
                }
                // Agent activity during the settle round-trip (an
                // extension-initiated run) needs its own settle + Done.
                if saw_activity {
                    saw_activity = false;
                    let settle_client = client.clone();
                    settle = Some(Box::pin(async move {
                        match tokio::time::timeout(SETTLE_TIMEOUT, settle_future(&settle_client, 0)).await {
                            Ok(outcome) => outcome,
                            Err(_) => (Vec::new(), None),
                        }
                    }));
                }
                // A stranded steer becomes the next turn; otherwise the
                // session parks — the caller owns teardown.
                if settle.is_none()
                    && let Some(text) = driver_steers.pop_front()
                    && !deliver_parked_steer(
                        &client,
                        &event_tx,
                        &mut assistant_message_id,
                        &mut acceptance,
                        &mut awaiting_prompt_echo,
                        &mut run_active,
                        &mut done_current,
                        &text,
                    )
                    .await
                {
                    break 'main;
                }
            },

            res = steer_calls.next(), if !steer_calls.is_empty() => match res {
                Some((text, retried, res)) => match res {
                    Ok(_) => { /* queued in pi; delivery shows as a user message */ }
                    Err(e) if client.is_closed() => {
                        tracing::debug!(target: "roboco_harness::pi", "steer lost to exit: {e}");
                    }
                    Err(e) if !retried => {
                        // Rejection (pi errors on extension-command text in
                        // a steer): redeliver as a prompt with steer
                        // behavior — pi runs extension commands immediately.
                        tracing::debug!(
                            target: "roboco_harness::pi",
                            "steer rejected (retrying as a steer-behavior prompt): {e}"
                        );
                        steer_calls.push(steer_request(&client, &text, true));
                    }
                    Err(e) => {
                        let _ = send(
                            &event_tx,
                            AgentEvent::Error { message: format!("Steering failed: {e}") },
                        )
                        .await;
                    }
                },
                None => {}
            },


            inc = incoming.recv() => match inc {
                Some(PiIncoming::Event(event)) => {
                    let kind = event.get("type").and_then(Value::as_str).unwrap_or("");
                    match kind {
                        "agent_start" => {
                            saw_activity = true;
                        }
                        "message_start" => {
                            let role = event.pointer("/message/role").and_then(Value::as_str);
                            if role == Some("assistant") {
                                tracker.reset();
                            } else if role == Some("user") {
                                // Our idle prompt's own echo, or a steer
                                // delivery out of pi's queue.
                                if awaiting_prompt_echo {
                                    awaiting_prompt_echo = false;
                                } else if pending_steer_deliveries > 0 {
                                    pending_steer_deliveries -= 1;
                                    let (prev, next) = rotate(&mut assistant_message_id);
                                    if !send(
                                        &event_tx,
                                        AgentEvent::Steered {
                                            assistant_message_id: Some(prev),
                                            next_assistant_message_id: Some(next),
                                        },
                                    )
                                    .await
                                    {
                                        break 'main;
                                    }
                                }
                                // Other user messages (extension-injected)
                                // stream without a roboco boundary.
                            }
                        }
                        "message_update" => {
                            if let Some(usage) = event.get("usage") {
                                last_usage = Some(usage.clone());
                            }
                            for ev in message_update_events(&event, &mut tracker) {
                                if !send(&event_tx, ev).await {
                                    break 'main;
                                }
                            }
                        }
                        "message_end" => {
                            if let Some(usage) = event.pointer("/message/usage") {
                                last_usage = Some(usage.clone());
                            }
                            for ev in message_end_events(&event, &mut tracker) {
                                if !send(&event_tx, ev).await {
                                    break 'main;
                                }
                            }
                            if let Some(error) = message_end_error(&event) {
                                let _ = send(&event_tx, AgentEvent::Error {
                                    message: error.clone(),
                                })
                                .await;
                                turn_error = Some(error);
                            }
                        }
                        "turn_end" => {
                            // One assistant response + its tool calls: a
                            // segment boundary (codex parity — the id
                            // rotation is backend-internal). The turn's
                            // latest cumulative usage rides the boundary.
                            if let Some(usage) = last_usage
                                .take()
                                .and_then(|usage| usage_event(&usage))
                                && !send(&event_tx, usage).await
                            {
                                break 'main;
                            }
                            let (prev, _next) = rotate(&mut assistant_message_id);
                            if !send(
                                &event_tx,
                                AgentEvent::AssistantMessageCompleted {
                                    assistant_message_id: prev,
                                },
                            )
                            .await
                            {
                                break 'main;
                            }
                        }
                        "tool_execution_start" => {
                            let id = event.get("toolCallId").and_then(Value::as_str).unwrap_or_default().to_owned();
                            let name = event.get("toolName").and_then(Value::as_str).unwrap_or_default().to_owned();
                            let args = event.get("args").cloned().unwrap_or(Value::Null);
                            if !id.is_empty() {
                                if !send(
                                    &event_tx,
                                    AgentEvent::ToolCall { id, call: typed_call(&name, &args) },
                                )
                                .await
                                {
                                    break 'main;
                                }
                            }
                        }
                        "tool_execution_end" => {
                            let id = event.get("toolCallId").and_then(Value::as_str).unwrap_or_default().to_owned();
                            let result = event.get("result").cloned().unwrap_or(Value::Null);
                            let is_error = event.get("isError").and_then(Value::as_bool).unwrap_or(false);
                            if !id.is_empty() {
                                if !send(
                                    &event_tx,
                                    AgentEvent::ToolResult {
                                        id,
                                        is_error,
                                        output: result.get("content").and_then(output_text),
                                        diff: tool_diff(&result),
                                    },
                                )
                                .await
                                {
                                    break 'main;
                                }
                            }
                        }
                        "auto_retry_start" => {
                            let attempt = event.get("attempt").and_then(Value::as_u64).unwrap_or(0);
                            let max = event.get("maxAttempts").and_then(Value::as_u64).unwrap_or(0);
                            let message = event
                                .get("errorMessage")
                                .and_then(Value::as_str)
                                .unwrap_or("transient provider error");
                            let _ = send(
                                &event_tx,
                                AgentEvent::Error {
                                    message: format!(
                                        "pi hit a transient error and is retrying \
                                         (attempt {attempt}/{max}): {message}"
                                    ),
                                },
                            )
                            .await;
                        }
                        "auto_retry_end" => {
                            let success = event.get("success").and_then(Value::as_bool).unwrap_or(false);
                            if success {
                                turn_error = None;
                            } else {
                                turn_error = Some(
                                    event
                                        .get("finalError")
                                        .and_then(Value::as_str)
                                        .filter(|error| !error.is_empty())
                                        .unwrap_or("pi exhausted its automatic retries.")
                                        .to_owned(),
                                );
                            }
                        }
                        "compaction_end" => {
                            // A failed compaction that will not retry ends
                            // the run as an error; success (manual, threshold,
                            // overflow-retry) just continues or re-prompts.
                            let will_retry = event.get("willRetry").and_then(Value::as_bool).unwrap_or(false);
                            let aborted = event.get("aborted").and_then(Value::as_bool).unwrap_or(false);
                            let failed = event.get("result").map(Value::is_null).unwrap_or(true)
                                && event.get("errorMessage").is_some();
                            if failed && !aborted && !will_retry {
                                turn_error = Some(
                                    event
                                        .get("errorMessage")
                                        .and_then(Value::as_str)
                                        .unwrap_or("pi's compaction failed.")
                                        .to_owned(),
                                );
                            }
                        }
                        "extension_ui_request" => {
                            if let Some(dialog) = dialog(&event) {
                                answer_dialog(&client, &request_input, dialog);
                            }
                        }
                        "extension_error" => {
                            let detail = event
                                .get("error")
                                .and_then(|value| value.as_str())
                                .unwrap_or_default();
                            tracing::debug!(target: "roboco_harness::pi", "pi extension error: {detail}");
                        }
                        "agent_settled" => {
                            if interrupted {
                                // abort() landed: close out as Interrupted
                                // without waiting for anything else.
                                done_current = true;
                                done_after_interrupt = true;
                                let _ = send(
                                    &event_tx,
                                    AgentEvent::Done {
                                        status: DoneStatus::Interrupted,
                                        result: None,
                                        error: None,
                                        session_id: Some(session_pointer.clone()),
                                    },
                                )
                                .await;
                                break 'main;
                            }
                            if (run_active || saw_activity) && settle.is_none() {
                                run_active = false;
                                saw_activity = false;
                                let settle_client = client.clone();
                                let pending = pending_steer_deliveries;
                                settle = Some(Box::pin(async move {
                                    match tokio::time::timeout(SETTLE_TIMEOUT, settle_future(&settle_client, pending)).await {
                                        Ok(outcome) => outcome,
                                        Err(_) => (Vec::new(), None),
                                    }
                                }));
                            }
                            // A settle with no run behind it (spurious, a
                            // settle already in flight — its completion
                            // re-arms for any activity seen meanwhile — or
                            // post-abort) is ignored.
                        }
                        // turn_start, queue_update, bash_execution_update
                        // (the driver never sends `bash`), entry_appended,
                        // session_info_changed, thinking_level_changed,
                        // summarization_retry_*, message_start for other
                        // roles, and unknown future events: tolerated by
                        // design — pi's event surface is additive upstream.
                        _ => {}
                    }
                }
                Some(PiIncoming::Eof) | None => {
                    // The settle sequence and the acceptance both race EOF
                    // through the pending map: a response that already
                    // resolved must still close out the turn. Give each a
                    // short drain before falling to crash bookkeeping.
                    if let Some(mut fut) = settle.take() {
                        if let Ok((recovered, stats)) =
                            tokio::time::timeout(EOF_DRAIN, &mut fut).await
                        {
                            let _ = recovered;
                            if let Some(usage) = stats.as_ref().and_then(|s| context_usage_event(s)) {
                                let _ = send(&event_tx, usage).await;
                            }
                            done_current = true;
                            let _ = send(
                                &event_tx,
                                AgentEvent::Done {
                                    status: if turn_error.is_some() {
                                        DoneStatus::Errored
                                    } else {
                                        DoneStatus::Completed
                                    },
                                    result: None,
                                    error: turn_error.take(),
                                    session_id: Some(session_pointer.clone()),
                                },
                            )
                            .await;
                        }
                    }
                    if let Some(mut fut) = acceptance.take() {
                        // A resolved REJECTION carries pi's own reason; a
                        // resolved acceptance (or a request failed by the
                        // EOF cleanup) falls through to the crash message.
                        if let Ok(Err(e)) = tokio::time::timeout(EOF_DRAIN, &mut fut).await {
                            done_current = true;
                            let _ = send(
                                &event_tx,
                                AgentEvent::Done {
                                    status: DoneStatus::Errored,
                                    result: None,
                                    error: Some(e.to_string()),
                                    session_id: Some(session_pointer.clone()),
                                },
                            )
                            .await;
                        }
                    }
                    break 'main;
                }
            },

            steer = steering.recv(), if steering_open && !interrupted => match steer {
                Some(msg) => {
                    let text = msg.prompt;
                    if run_active || acceptance.is_some() {
                        // Live turn: pi's own steer queue (delivered after
                        // the current assistant turn's tool calls).
                        steer_calls.push(steer_request(&client, &text, false));
                        pending_steer_deliveries += 1;
                    } else if settle.is_some() || !steer_calls.is_empty() || !driver_steers.is_empty() {
                        // The settle sequence or a steer acknowledgement is
                        // still winding down: FIFO behind it.
                        driver_steers.push_back(text);
                    } else {
                        if !deliver_parked_steer(
                            &client,
                            &event_tx,
                            &mut assistant_message_id,
                            &mut acceptance,
                            &mut awaiting_prompt_echo,
                            &mut run_active,
                            &mut done_current,
                            &text,
                        )
                        .await
                        {
                            break 'main;
                        }
                    }
                }
                None => {
                    // Mailbox closed (the caller's graceful idle-reap):
                    // finish once nothing is in flight.
                    steering_open = false;
                    if !run_active
                        && acceptance.is_none()
                        && settle.is_none()
                        && steer_calls.is_empty()
                        && driver_steers.is_empty()
                    {
                        break 'main;
                    }
                }
            },

            _ = interrupt.cancelled(), if !interrupt_sent => {
                interrupt_sent = true;
                interrupted = true;
                if settle.is_some() {
                    // The turn already settled; the stats round-trip was the
                    // only thing in flight. Close out as interrupted.
                    let _ = settle.take();
                    done_current = true;
                    done_after_interrupt = true;
                    let _ = send(
                        &event_tx,
                        AgentEvent::Done {
                            status: DoneStatus::Interrupted,
                            result: None,
                            error: None,
                            session_id: Some(session_pointer.clone()),
                        },
                    )
                    .await;
                    break 'main;
                }
                let in_flight = run_active || acceptance.is_some() || saw_activity;
                if in_flight {
                    // abort() waits for idle before responding; do not block
                    // the loop on it — the agent_settled it triggers (or the
                    // signal escalation below) ends the run.
                    let client = client.clone();
                    tokio::spawn(async move {
                        if let Err(e) = client.request("abort", json!({})).await {
                            tracing::debug!(target: "roboco_harness::pi", "abort failed (escalation will reap): {e}");
                        }
                    });
                    // Escalate if pi does not wind down within the grace
                    // periods: SIGTERM (pi exits 143), then SIGKILL.
                    if let Some(pid) = crate::process::signal_target(&child) {
                        escalation = Some(tokio::spawn(async move {
                            tokio::time::sleep(interrupt_grace).await;
                            send_signal(&pid, Signal::Term);
                            tokio::time::sleep(kill_grace).await;
                            send_signal(&pid, Signal::Kill);
                        }));
                    }
                } else {
                    // Idle between turns: nothing to abort — the terminal
                    // bookkeeping below still guarantees Done { Interrupted }.
                    break 'main;
                }
            },

            _ = event_tx.closed() => break 'main,
        }
    }

    // Terminal bookkeeping: never end the stream without a Done unless the
    // consumer already hung up.
    if !event_tx.is_closed() {
        if interrupted && !done_after_interrupt {
            let _ = event_tx
                .send(Ok(AgentEvent::Done {
                    status: DoneStatus::Interrupted,
                    result: None,
                    error: None,
                    session_id: Some(session_pointer.clone()),
                }))
                .await;
        } else if !interrupted && !done_current {
            // A child killed mid-turn must not read as a silent success.
            let status = match child_exit {
                Some(status) => status,
                None => tokio::time::timeout(EXIT_DRAIN, child.wait())
                    .await
                    .ok()
                    .and_then(Result::ok),
            };
            child.request_group_shutdown();
            stderr_tail.wait_closed().await;
            let _ = event_tx
                .send(Ok(AgentEvent::Done {
                    status: DoneStatus::Errored,
                    result: None,
                    error: Some(crate::crash_message("pi", status, &stderr_tail)),
                    session_id: Some(session_pointer.clone()),
                }))
                .await;
        }
    }

    child.shutdown(kill_grace).await;
    if let Some(handle) = escalation {
        handle.abort();
    }
}

/// Arm a prompt and return its acceptance future. `streaming_behavior` is
/// set only for the steer-rejection retry (a prompt arriving while pi is
/// streaming must declare its queue behavior or pi rejects it).
fn prompt_acceptance(
    client: &PiClient,
    text: &str,
    images: Vec<Value>,
    streaming_behavior: Option<&str>,
) -> BoxFuture<'static, Result<Value, HarnessError>> {
    let client = client.clone();
    let text = text.to_owned();
    let mut params = json!({ "message": text });
    if !images.is_empty() {
        params["images"] = Value::Array(images);
    }
    if let Some(behavior) = streaming_behavior {
        params["streamingBehavior"] = Value::String(behavior.to_owned());
    }
    Box::pin(async move {
        match tokio::time::timeout(ACCEPT_TIMEOUT, client.request("prompt", params)).await {
            Ok(result) => result,
            Err(_) => Err(HarnessError::Protocol(
                "pi did not accept the prompt within 30s (the process is likely wedged)".into(),
            )),
        }
    })
}

/// One steer request, optionally retrying a rejected steer as a
/// steer-behavior prompt (pi rejects extension-command text in `steer`
/// but runs it immediately as a prompt).
fn steer_request(client: &PiClient, text: &str, as_prompt: bool) -> SteerCall {
    let client = client.clone();
    let text = text.to_owned();
    Box::pin(async move {
        let result = if as_prompt {
            let params = json!({ "message": text, "streamingBehavior": "steer" });
            client.request("prompt", params).await
        } else {
            client.request("steer", json!({ "message": text })).await
        };
        (text, as_prompt, result)
    })
}

/// A parked session's next message: confirm the boundary, then arm the
/// prompt (the mailbox's FIFO ledger retires on the Steered boundary).
async fn deliver_parked_steer(
    client: &PiClient,
    event_tx: &mpsc::Sender<Result<AgentEvent, HarnessError>>,
    assistant_message_id: &mut String,
    acceptance: &mut Option<BoxFuture<'static, Result<Value, HarnessError>>>,
    awaiting_prompt_echo: &mut bool,
    run_active: &mut bool,
    done_current: &mut bool,
    text: &str,
) -> bool {
    // Confirm the boundary FIRST (the mailbox ledger retires here), then
    // arm the prompt — ACP parity for the between-turns delivery path.
    let (prev, next) = rotate(assistant_message_id);
    if !send(
        event_tx,
        AgentEvent::Steered {
            assistant_message_id: Some(prev),
            next_assistant_message_id: Some(next),
        },
    )
    .await
    {
        return false;
    }
    *acceptance = Some(prompt_acceptance(client, text, Vec::new(), None));
    *awaiting_prompt_echo = true;
    *run_active = true;
    *done_current = false;
    true
}

/// Route one blocking extension dialog through the engine's input bridge
/// and answer it on the wire. The bridge owns the InputRequested /
/// InputResolved lifecycle; a dropped resolver degrades to `cancelled` —
/// never a silent default the extension might act on.
fn answer_dialog(
    client: &PiClient,
    request_input: &Arc<RequestInputFn>,
    dialog: normalize::Dialog,
) {
    let client = client.clone();
    let request_input = Arc::clone(request_input);
    tokio::spawn(async move {
        let answers = (request_input)(vec![dialog.question.clone()])
            .await
            .unwrap_or_default();
        let response = dialog_response(&dialog.wire_id, dialog.kind, &answers);
        client.send_value(&response);
    });
}

/// Staged image attachments as pi's inline `ImageContent` blocks
/// (`{type:"image", data: <base64>, mimeType}`), best-effort: an unreadable,
/// oversized, or unsupported file is skipped — its path ref still rides the
/// prompt text — never fatal to the run (claude-driver parity).
const MAX_INLINE_IMAGE_BYTES: u64 = 5 * 1024 * 1024;

async fn load_image_contents(paths: &[String]) -> Vec<Value> {
    use base64::Engine as _;
    let mut blocks = Vec::new();
    for path in paths {
        let bytes = match tokio::fs::read(path).await {
            Ok(bytes) => bytes,
            Err(err) => {
                tracing::warn!(target: "roboco_harness::pi", %path, error = %err, "attachment unreadable; path ref only");
                continue;
            }
        };
        if bytes.len() as u64 > MAX_INLINE_IMAGE_BYTES {
            tracing::debug!(target: "roboco_harness::pi", %path, "attachment over inline cap; path ref only");
            continue;
        }
        let Some(mime_type) = image_mime_type(std::path::Path::new(path), &bytes) else {
            tracing::debug!(target: "roboco_harness::pi", %path, "attachment not an inline-supported image; path ref only");
            continue;
        };
        blocks.push(json!({
            "type": "image",
            "data": base64::engine::general_purpose::STANDARD.encode(&bytes),
            "mimeType": mime_type,
        }));
    }
    blocks
}

/// Extension first, magic bytes as the fallback (pasted screenshots can
/// carry odd names).
fn image_mime_type(path: &std::path::Path, bytes: &[u8]) -> Option<&'static str> {
    let by_ext = match path
        .extension()
        .and_then(|e| e.to_str())
        .map(|e| e.to_ascii_lowercase())
        .as_deref()
    {
        Some("png") => Some("image/png"),
        Some("jpg" | "jpeg") => Some("image/jpeg"),
        Some("gif") => Some("image/gif"),
        Some("webp") => Some("image/webp"),
        _ => None,
    };
    by_ext.or(match bytes {
        [0x89, b'P', b'N', b'G', ..] => Some("image/png"),
        [0xFF, 0xD8, 0xFF, ..] => Some("image/jpeg"),
        [b'G', b'I', b'F', b'8', ..] => Some("image/gif"),
        [
            b'R',
            b'I',
            b'F',
            b'F',
            _,
            _,
            _,
            _,
            b'W',
            b'E',
            b'B',
            b'P',
            ..,
        ] => Some("image/webp"),
        _ => None,
    })
}
