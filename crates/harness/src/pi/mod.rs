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
//! - **Prompting**: every prompt — turn starts and mid-run steers alike —
//!   goes out as `prompt` with `streamingBehavior: "steer"`: pi starts a
//!   run when idle and queues the prompt when an extension run is
//!   streaming, atomically against settlement, so a prompt can never strand
//!   against the end of a run. The acknowledgement's `disposition` reports
//!   what became of it (`started` / `queued` / `handled`; pi < 0.99 reports
//!   none and the driver infers from whether a run starts). The acceptance
//!   is unbounded ON PURPOSE — there is deliberately no acceptance timeout
//!   (ticket 02 removed the fixed one): a healthy preflight can take
//!   arbitrarily long (extensions, the Roboco MCP bridge, compaction), so
//!   a fixed budget killed slow-but-fine starts. The cost is that a WEDGED
//!   pi holds its turn open until the user stops it — only Stop interrupts
//!   a slow acceptance; nothing else ever times the run out. A
//!   rejected prompt never ends the session — the message returns to the
//!   driver queue and the rejection surfaces. Delivery tracking keeps one
//!   FIFO record per original input, created when the input goes out (the
//!   acknowledgement is never a consumption receipt, upstream PROTOCOL.md):
//!   a consumed user `message_start` echo pops the oldest record and emits
//!   its `Steered` boundary — whichever of the echo or the
//!   started/handled acknowledgement processes first consumes the record,
//!   so a burst is never short a boundary. A mid-run steer carries its
//!   staged images inline, exactly like a turn-start prompt. Pi is a
//!   STEP-BOUNDARY harness (the engine registry descriptor agrees): the
//!   steering mode is set to `"all"` at session start unless the user
//!   already configured one (agent-dir or project `.pi/settings.json`),
//!   so a burst of steers is delivered together, in order, at the next
//!   step boundary. `abort` interrupts, waiting for idle before
//!   responding. `follow_up` stays unused: a parked session's next message
//!   is a fresh prompt in roboco's model.
//! - **Turn settlement**: `agent_settled`, then a state probe: the turn
//!   ends only once pi reports it is not streaming, not compacting and
//!   holds no pending messages, and the settle generation is unchanged
//!   (`agent_start`, `compaction_start`, every prompt or steer, and every
//!   settle bump it). Busy and stale probes re-arm; a probe that keeps
//!   failing ends the turn errored — never a silent kill. Every turn shape
//!   still ends with a deterministic `Done` (the engine retires its
//!   quiesce watchdogs). Turn errors reject the prompt: a final assistant
//!   error (`stopReason: "error"`), exhausted `auto_retry_*`, or a failed
//!   `compaction_end` without `willRetry` produce `Done { Errored }` — and
//!   a successful compaction retry clears the error state, so a run that
//!   recovered through compaction ends completed. The turn is never over
//!   while an input is still awaiting its acknowledgement: a quiescent
//!   probe re-arms until the preflight lands. A probe that keeps failing
//!   ends the turn errored — never a silent kill — and drains the queued
//!   mailbox messages like a clean settle (the next one starts the next
//!   turn), so the mailbox never wedges behind the dead turn. A message pi
//!   keeps REJECTING is capped, not retried forever: past two deliveries
//!   it is dropped from the driver (the engine's at-least-once ledger
//!   still owns it) and the turn ends errored — never
//!   a loop of Error + Completed for a message that never ran. A steer
//!   stranded in pi's queue at settle is recovered by `clear_queue` and
//!   re-queued INTACT (its attachments and run config ride along). Stop
//!   sends `clear_queue` before `abort` — and only takes the "already
//!   settled" shortcut when the run truly settled, never while work is
//!   still in flight: steers pi queued but never consumed are cancelled
//!   with the turn (the engine's at-least-once ledger already owns their
//!   fate), never delivered into the aborted run.
//! - **Stats**: `get_session_stats.contextUsage` feeds the context meter
//!   after each settle; `message_update.usage` rides per-turn `Usage`
//!   passthrough — first-party replacements for the pi-acp fork's
//!   usage_update/turn-error patches.
//! - **Native forks** (orchestration v2 ticket 19): the forked chat's
//!   first run continues the SOURCE's real session instead of pasting
//!   the copied transcript. A short-lived `pi --mode rpc --fork <source
//!   file>` process in the run's cwd performs the fork — the CLI copies
//!   the source session into a new file for the current project, and,
//!   when the boundary is not the source's latest turn, the RPC `fork`
//!   re-roots that copy BEFORE the next turn's user entry (pi's fork is
//!   exclusive) — then the main spawn resumes the new file with
//!   `--session` and the copied history rides the provider: nothing is
//!   pasted. Transcript entries record a provider turn ref (the session
//!   file plus the turn's user entry id, `get_entries` after each
//!   settle): for an unsteered turn that is its FIRST user entry; a turn
//!   a steer landed in names its LAST user entry instead, because the
//!   fork boundary is "the user entry after the ref" and naming the last
//!   consumed entry makes that the next turn's start exactly. A missing
//!   ref, a stale boundary, or a refused/cancelled fork falls back to
//!   the budgeted transcript in front of the prompt — never a failed
//!   send.
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
//! - **MCP bridge**: pi's RPC has no MCP surface (no flag, no settings key,
//!   no runtime command), so the engine-injected `roboco mcp` server rides
//!   a `--extension` bridge that re-exposes its tools as `mcp__roboco__*`
//!   through `pi.registerTool` — see [`mcp`] (docs/mcp.md, Injection).
//! - **Interrupt**: `abort`, then SIGTERM → SIGKILL escalation; the stream
//!   always ends with `Done { status: Interrupted }`.

mod mcp;
mod normalize;
mod wire;

use std::collections::{HashMap, VecDeque};
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
    AgentEvent, DoneStatus, HarnessId, Model, ProviderFork, ProviderTurnRef, ReasoningLevel,
    RunRequest, SteeringMode, UserInputAnswer, UserInputQuestion,
};

use crate::acp::child;
use crate::process::{Command, Stdio};
use crate::{Harness, HarnessError, RunControls, Signal, SteerMessage, send_signal};
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

/// pi's settings directory: an explicit override (tests), else
/// `PI_CODING_AGENT_DIR`, else `~/.pi/agent`.
fn pi_agent_dir(explicit: Option<&Path>) -> PathBuf {
    explicit.map(Path::to_path_buf).unwrap_or_else(|| {
        crate::model_context::root(
            "PI_CODING_AGENT_DIR",
            crate::executable::home_or_current_dir().join(".pi").join("agent"),
        )
    })
}

/// Whether a steering mode is already set in the settings pi loads for
/// `cwd` (the agent directory's global settings, then the project's):
/// `set_steering_mode "all"` at session start must never overwrite an
/// explicit choice — the user's, or `/steering` picked inside a chat
/// (pi persists that in the same files). A missing, unreadable, or
/// malformed file simply means "not configured".
fn steering_mode_configured(agent_dir: Option<&Path>, cwd: &str) -> bool {
    [
        pi_agent_dir(agent_dir).join("settings.json"),
        Path::new(cwd).join(".pi").join("settings.json"),
    ]
    .into_iter()
    .any(|settings| {
        std::fs::read(&settings)
            .ok()
            .and_then(|bytes| serde_json::from_slice::<Value>(&bytes).ok())
            .is_some_and(|settings| settings.get("steeringMode").is_some())
    })
}

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

/// Whether `request` picks a thinking level (an effort, or thinking off).
fn thinking_selected(request: &RunRequest) -> bool {
    request.reasoning.is_some()
        || request
            .model_options
            .get("pi_thinking")
            .is_some_and(|v| v == "off")
}

/// Apply `request`'s model and thinking level to the LIVE process
/// (`set_model` / `set_thinking_level`), best-effort: a rejected switch
/// logs and the current configuration runs (pi-acp parity — the run is not
/// failed by an unavailable model or level).
async fn configure_live(
    client: &PiClient,
    request: &RunRequest,
) -> Result<(), HarnessError> {
    if let Some(model) = request
        .model
        .as_deref()
        .filter(|model| !model.is_empty() && *model != "default")
    {
        if let Err(e) = apply_model(client, model).await {
            tracing::warn!(
                target: "roboco_harness::pi",
                "set_model {model} rejected (pi default runs): {e}"
            );
        }
    }
    if thinking_selected(request) {
        let level = if request
            .model_options
            .get("pi_thinking")
            .is_some_and(|v| v == "off")
        {
            "off"
        } else {
            thinking_level(request.reasoning.expect("gated by thinking_selected"))
        };
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
    Ok(())
}

/// Resolve + apply a requested model id through pi's `set_model`. Composite
/// `<provider>/<modelId>` ids (what `set_model` accepts and the catalog emits)
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
    /// The pi agent directory the steering-mode default reads settings
    /// from: `PI_CODING_AGENT_DIR` / `~/.pi/agent` unless overridden. Only
    /// the steering-mode check consults it — model contexts keep hashing
    /// `auth.json` alone (see [`PiHarness::model_context`]).
    agent_dir: Option<PathBuf>,
    /// Grace between `abort` and SIGTERM.
    interrupt_grace: Duration,
    /// Grace between SIGTERM and SIGKILL.
    kill_grace: Duration,
    /// Bound on spawn → first `get_state` response; pi's startup (session
    /// load, background catalog refresh) must finish inside it or the run
    /// errors instead of spinning "Working" forever. Defaults to 120s — the
    /// budget the ACP handshake gave pi — because a cold extension (the
    /// per-run `--extension` bridge spawns one) can push a first `get_state`
    /// past 60s.
    startup_timeout: Duration,
    /// Provider-scoped successful catalog, with bounded refresh and backoff.
    models_cache: crate::catalog::Catalog,
}

impl Default for PiHarness {
    fn default() -> Self {
        Self {
            executable: None,
            agent_dir: None,
            interrupt_grace: Duration::from_secs(2),
            kill_grace: Duration::from_secs(3),
            startup_timeout: Duration::from_secs(120),
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

    /// Pin the pi agent directory the steering-mode default reads
    /// `settings.json` from (tests: a temp dir makes the "user configured
    /// a mode" check hermetic against the real `~/.pi/agent`).
    pub fn with_agent_dir(mut self, path: impl Into<PathBuf>) -> Self {
        self.agent_dir = Some(path.into());
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
    ///
    /// The probe's cwd is a fresh EMPTY temp dir, never `$HOME`: a globally
    /// installed extension that indexes its working directory (pi-fff scans
    /// and indexes its cwd on boot) would otherwise walk the entire home
    /// directory on every probe — a multi-minute index on a real $HOME, so
    /// the 15 s discovery budget always expires and the harness's models
    /// never go live. The empty dir makes any cwd-scoped scan free, and
    /// `FFF_ENABLE_HOME_SCAN=0` turns pi-fff's $HOME scan off outright;
    /// extensions themselves stay on so extension-registered models still
    /// appear.
    async fn discover_models(&self) -> Result<Vec<Model>, HarnessError> {
        let exe = self.resolve_executable()?;
        let probe_cwd = tempfile::tempdir().map_err(HarnessError::Io)?;
        let mut cmd = Command::new(&exe);
        cmd.arg("--mode").arg("rpc").arg("--no-session");
        cmd.current_dir(probe_cwd.path());
        cmd.env("FFF_ENABLE_HOME_SCAN", "0");
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
        mut request: RunRequest,
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
        let mut resume_path = request
            .resume
            .as_deref()
            .filter(|resume| Path::new(resume).is_file())
            .map(str::to_owned);
        // A native fork (t3-orchestration-v2 ticket 19): the forked chat's
        // first run continues the SOURCE's real pi session instead of
        // pasting the copied transcript into a fresh one. A short-lived
        // `pi --mode rpc --fork <source file>` process performs the fork
        // (see [`fork_source_session`]) and the main spawn below resumes
        // the forked session file with `--session`, so the history rides
        // the provider and nothing is pasted. Any failure — a missing
        // source file, a stale boundary ref, a refused or cancelled fork,
        // a pi that answers with anything but a distinct new file — falls
        // back to a fresh session with the engine's budgeted transcript
        // in front of the prompt: the send never fails. The fork owns
        // session continuity, so a plain resume never competes with it
        // (codex parity: `thread/fork` instead of `thread/resume`).
        let mut fork_notice: Option<String> = None;
        if !title_only
            && let Some(fork) = request.provider_fork.clone()
        {
            request.resume = None;
            resume_path = None;
            let launch = if !Path::new(&fork.session_id).is_file() {
                ForkLaunch::Failed("the source session file is gone".into())
            } else {
                tokio::select! {
                    biased;
                    // Stop before the fork could start ends the run
                    // interrupted, exactly like a Stop during startup — a
                    // fork is not a fallback candidate after that.
                    _ = controls.interrupt.cancelled() => ForkLaunch::Interrupted,
                    launched = fork_source_session(
                        &exe,
                        &fork,
                        &request.cwd,
                        self.startup_timeout,
                        self.kill_grace,
                    ) => match launched {
                        Ok(file) => ForkLaunch::Session(file),
                        Err(reason) => ForkLaunch::Failed(reason),
                    },
                }
            };
            match launch {
                ForkLaunch::Session(file) => resume_path = Some(file),
                ForkLaunch::Failed(reason) => {
                    // The budgeted transcript rides the prompt in front of
                    // the user message (codex parity: the refused-fork
                    // fallback), and a notice says what happened.
                    let history = fork.fallback_history.as_deref().unwrap_or("");
                    request.prompt = crate::prompt_with_history(history, &request.prompt);
                    fork_notice = Some(format!(
                        "pi could not fork the source session ({reason}); starting a new \
                         session with the chat transcript, trimmed to fit"
                    ));
                }
                ForkLaunch::Interrupted => {
                    let (event_tx, event_rx) =
                        mpsc::channel::<Result<AgentEvent, HarnessError>>(256);
                    let _ = event_tx
                        .send(Ok(AgentEvent::Done {
                            status: DoneStatus::Interrupted,
                            result: None,
                            error: None,
                            session_id: None,
                        }))
                        .await;
                    return Ok(event_stream(event_rx));
                }
            }
        }

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
        // Roboco's MCP server rides the extension bridge (see [`mcp`]):
        // pi's RPC protocol has no MCP surface, but `--extension` loads the
        // bridge that dials `roboco mcp` and re-exposes its tools. Degraded,
        // not failed, when the bridge cannot be written: the run proceeds
        // without Roboco tools, the same policy as a run with no served IPC
        // port. Title runs never reach here (`run_title` clears the field).
        let mcp_scratch = request
            .mcp
            .as_ref()
            .and_then(|mcp| match mcp::install(&mut cmd, mcp) {
                Ok(dir) => Some(dir),
                Err(error) => {
                    tracing::warn!(
                        target: "roboco_harness::pi",
                        %error,
                        "could not install the roboco mcp bridge"
                    );
                    None
                }
            });
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
        // Steering mode "all" at session start unless the user configured
        // one: pi defaults to one-at-a-time, and a burst of roboco steers
        // belongs together at the next step boundary, in order. Title runs
        // never steer, so the one-shot sandbox never touches the setting.
        let steering_mode_all =
            !title_only && !steering_mode_configured(self.agent_dir.as_deref(), &request.cwd);
        tokio::spawn(run_session(Session {
            child,
            client,
            incoming,
            event_tx,
            controls,
            request,
            resume_path,
            mcp_scratch,
            steering_mode_all,
            interrupt_grace: self.interrupt_grace,
            kill_grace: self.kill_grace,
            startup_timeout: self.startup_timeout,
            stderr_tail,
            fork_notice,
        }));

        Ok(event_stream(event_rx))
    }
}

/// The consumer's event stream: one channel receiver unfolded into a
/// boxed stream (the run task owns the sender side).
fn event_stream(
    event_rx: mpsc::Receiver<Result<AgentEvent, HarnessError>>,
) -> BoxStream<'static, Result<AgentEvent, HarnessError>> {
    futures::stream::unfold(event_rx, |mut rx| async move {
        rx.recv().await.map(|ev| (ev, rx))
    })
    .boxed()
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
    /// pi's steer queue (reached through `prompt` + steer behavior, with the
    /// mode set to "all") delivers after the current assistant turn's tool
    /// calls — the next STEP boundary inside the live turn — matching the
    /// registry descriptor exactly (both must flip together; orchestration
    /// v2 ticket 03).
    fn steering_mode(&self) -> SteeringMode {
        SteeringMode::StepBoundary
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
    /// `set_model` and `set_thinking_level` change the live process. Clearing
    /// either back to the default cannot be expressed that way.
    fn reconfigures_in_place(&self, live: &RunRequest, next: &RunRequest) -> bool {
        let model = |r: &RunRequest| r.model.as_deref().filter(|m| *m != "default").is_some();
        live.cwd == next.cwd
            && (model(next) || !model(live))
            && (thinking_selected(next) || !thinking_selected(live))
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
        // A native-fork directive must never reach the title sandbox: the
        // one-shot never continues a session, so a fork would only spawn a
        // doomed subprocess (its history belongs to a chat, not a title).
        request.provider_fork = None;
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
    /// The scratch dir holding the MCP bridge file for exactly this run
    /// (dropped after the pi child shuts down, when the file is closed).
    mcp_scratch: Option<crate::scratch::ScratchDir>,
    /// Send `set_steering_mode "all"` once pi answers `get_state` — armed
    /// in `run_with_mode` only when no mode is configured (see there).
    steering_mode_all: bool,
    interrupt_grace: Duration,
    kill_grace: Duration,
    startup_timeout: Duration,
    /// Rolling stderr tail for the crash message on an unexpected exit.
    stderr_tail: crate::StderrTail,
    /// A refused native fork's notice (ticket 19): the run starts fresh
    /// with the budgeted transcript pasted in front of the prompt, and
    /// this surfaces what happened to the fold (parity with the
    /// lost-resume notice).
    fork_notice: Option<String>,
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

/// Bound on the post-settle `get_session_stats` round trip (best-effort:
/// a failure skips the context meter, never the turn).
const SETTLE_TIMEOUT: Duration = Duration::from_secs(15);
/// Bound on one settle-probe round (`get_state`, `clear_queue`): pi answers
/// in milliseconds; a hang past it is one failed attempt.
const PROBE_TIMEOUT: Duration = Duration::from_secs(5);
/// How many times one mailbox message may be delivered before repeated
/// rejection hands it back to the engine as a failed send (B1): the
/// original delivery plus ONE re-delivery. Past it, the turn ends errored
/// — never a loop of Error + Completed for a message that never ran.
const MESSAGE_DELIVERY_ATTEMPTS: u32 = 2;
/// Bound on the settle capture's `get_entries` round trip (B7): the
/// capture must never wedge the run loop for its full 2×15s budget —
/// an interrupt landing mid-capture cuts even this short, and a lost
/// capture costs the provider turn ref (a later fork falls back to the
/// budgeted paste), never the turn.
const REF_CAPTURE_BUDGET: Duration = Duration::from_secs(3);
/// Settle-probe attempts before the turn ends errored: a failed probe is
/// retried, and after this many the turn fails visibly instead of the
/// process being killed silently.
const PROBE_ATTEMPTS: u32 = 3;
/// Delay before a re-armed probe (a busy report, or the inference after an
/// acknowledgement that carries no disposition): the probe must never
/// hot-loop while pi streams.
const PROBE_REARM_DELAY: Duration = Duration::from_millis(500);
/// Drain window for frames already written when the child exits: a crash
/// right after the final response must still surface that response.
const EXIT_DRAIN: Duration = Duration::from_millis(200);
/// Short window for a response that already resolved while EOF was being
/// delivered — it rides a different channel than events, so the loop may
/// see EOF first.
const EOF_DRAIN: Duration = Duration::from_millis(50);

/// A mid-run steer in flight: the mailbox message and its `prompt`
/// acknowledgement.
type SteerCall = BoxFuture<'static, (u64, SteerMessage, Result<Value, HarnessError>)>;

/// One armed prompt: its acceptance future and the mailbox message it
/// delivers (None = the run's own first prompt). The pair always travels
/// together — the message is owed a `Steered` boundary once pi accepts the
/// prompt, and returns to the queue if pi rejects it. `delivery` names the
/// [`DeliveryRecord`] the arming created, so the acknowledgement (or a
/// rejection) can retire it.
struct Armed {
    acceptance: BoxFuture<'static, Result<Value, HarnessError>>,
    message: Option<SteerMessage>,
    delivery: Option<u64>,
}

/// One FIFO delivery record per original input sent to pi (upstream
/// PROTOCOL.md: neither the prompt ACK nor `queue_update` is a consumption
/// receipt). The record is created when the input GOES OUT, so a user
/// message echo that races its acknowledgement still consumes it — the
/// driver's select may process the echo before the ready acceptance
/// future. Whoever consumes the record first — the echo, or the
/// Started/Handled acknowledgement that confirms delivery at acceptance —
/// emits the one `Steered` boundary the input owes; a record pi never
/// consumes (a steer stranded in its queue at settle) is retired by
/// clear_queue and re-delivered as the next turn's prompt.
struct DeliveryRecord {
    id: u64,
    text: String,
    /// The original mailbox message for an input accepted into pi's steer
    /// queue: a strand recovered by the settle probe's `clear_queue`
    /// re-queues it INTACT — attachments and run config included — instead
    /// of rebuilding it as bare text.
    message: Option<SteerMessage>,
}

/// Count one more rejection of `msg` (keyed by its engine message id, or
/// its text when it has none) and report whether the re-delivery cap is
/// reached (B1). A capped message's count is forgotten: the engine owns
/// its fate from here.
fn note_rejection(rejections: &mut HashMap<String, u32>, msg: &SteerMessage) -> Option<u32> {
    let key = msg.message_id.clone().unwrap_or_else(|| msg.prompt.clone());
    let attempts = rejections.entry(key.clone()).or_insert(0);
    *attempts += 1;
    let attempts = *attempts;
    if attempts >= MESSAGE_DELIVERY_ATTEMPTS {
        rejections.remove(&key);
        Some(attempts)
    } else {
        None
    }
}

/// Retire `id`'s delivery record; `true` when it was still pending (the
/// boundary it owes has not been emitted at the echo yet).
fn retire_delivery(records: &mut VecDeque<DeliveryRecord>, id: u64) -> bool {
    let before = records.len();
    records.retain(|record| record.id != id);
    before != records.len()
}

/// pi's answer to a `prompt`: what became of it. Pi ≥ 0.99 reports
/// `disposition` on the acknowledgement; older builds report nothing and
/// the driver infers (see [`Disposition::Unknown`]).
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
enum Disposition {
    /// A run began with the prompt.
    Started,
    /// Accepted into pi's steer queue: delivered (consumed) when its user
    /// `message_start` echo arrives.
    Queued,
    /// pi handled the prompt without starting a run (an extension
    /// command): the turn ends once pi reports it is not streaming.
    Handled,
    /// pi < 0.99 reported nothing: infer from whether a run starts — the
    /// settle probe ends the turn when nothing does.
    Unknown,
}

fn disposition_of(data: &Value) -> Disposition {
    match data.get("disposition").and_then(Value::as_str) {
        Some("started") => Disposition::Started,
        Some("queued") => Disposition::Queued,
        Some("handled") => Disposition::Handled,
        _ => Disposition::Unknown,
    }
}

/// One settle-probe round: read pi's state and decide whether the turn may
/// end. Pi is at rest only when it is neither streaming nor compacting and
/// holds no pending messages; messages left in pi's queue while it is idle
/// (steers stranded by the settle race) are cleared and handed back — they
/// re-deliver as the next turn's prompts.
enum ProbeRound {
    /// pi is streaming or compacting: the turn cannot end yet.
    Busy,
    /// pi is idle with messages still queued: they were cleared; the texts
    /// return for re-delivery.
    Recovered(Vec<String>),
    /// pi is at rest: the turn may end (stats for the context meter are
    /// best-effort).
    Quiescent(Option<Value>),
    /// The probe itself failed (the request was rejected or timed out).
    Failed(String),
}

/// The texts pi's `clear_queue` handed back (its steering + followUp
/// queues).
fn queue_texts(data: &Value) -> Vec<String> {
    ["steering", "followUp"]
        .iter()
        .flat_map(|key| {
            data.get(*key)
                .and_then(Value::as_array)
                .cloned()
                .unwrap_or_default()
        })
        .filter_map(|value| value.as_str().map(str::to_owned))
        .collect()
}

/// One probe round. Bounded: a request pi never answers counts as one
/// failed attempt.
fn probe_round(client: &PiClient) -> BoxFuture<'static, ProbeRound> {
    let client = client.clone();
    Box::pin(async move {
        let state = match tokio::time::timeout(
            PROBE_TIMEOUT,
            client.request("get_state", json!({})),
        )
        .await
        {
            Ok(Ok(state)) => state,
            Ok(Err(e)) => return ProbeRound::Failed(e.to_string()),
            Err(_) => {
                return ProbeRound::Failed(
                    "get_state did not answer within the probe budget".into(),
                )
            }
        };
        let busy = |key: &str| state.get(key).and_then(Value::as_bool).unwrap_or(false);
        if busy("isStreaming") || busy("isCompacting") {
            return ProbeRound::Busy;
        }
        let pending = state
            .get("pendingMessageCount")
            .and_then(Value::as_u64)
            .unwrap_or(0);
        if pending > 0 {
            return match tokio::time::timeout(
                PROBE_TIMEOUT,
                client.request("clear_queue", json!({})),
            )
            .await
            {
                Ok(Ok(data)) => ProbeRound::Recovered(queue_texts(&data)),
                Ok(Err(e)) => ProbeRound::Failed(e.to_string()),
                Err(_) => ProbeRound::Failed(
                    "clear_queue did not answer within the probe budget".into(),
                ),
            };
        }
        let stats = match tokio::time::timeout(
            SETTLE_TIMEOUT,
            client.request("get_session_stats", json!({})),
        )
        .await
        {
            Ok(Ok(stats)) => Some(stats),
            // Best-effort: a failed read skips the context meter, never
            // the turn.
            _ => None,
        };
        ProbeRound::Quiescent(stats)
    })
}

/// Arm one more probe round, optionally after the re-arm delay: a busy
/// report must not hot-loop the probe while pi streams.
fn rearm_probe(client: &PiClient, delay: bool) -> BoxFuture<'static, ProbeRound> {
    let client = client.clone();
    Box::pin(async move {
        if delay {
            tokio::time::sleep(PROBE_REARM_DELAY).await;
        }
        probe_round(&client).await
    })
}

/// One `get_entries` listing parsed for the session-tree walks (ticket
/// 19): the entry map (id → parent + whether the entry is a user message)
/// and the current leaf id. Entries without an id are skipped — the walks
/// simply cannot pass through them; a listing without the entries array
/// fails outright. Only `type: "message"` entries carry a role; every
/// other entry type (compaction, model changes, extension entries) walks
/// as a pass-through node.
struct SessionEntries {
    by_id: HashMap<String, (Option<String>, bool)>,
    leaf: Option<String>,
}

fn parse_entries(data: &Value) -> Option<SessionEntries> {
    let entries = data.get("entries")?.as_array()?;
    let mut by_id = HashMap::with_capacity(entries.len());
    for entry in entries {
        let Some(id) = entry.get("id").and_then(Value::as_str) else {
            continue;
        };
        let parent = entry
            .get("parentId")
            .and_then(Value::as_str)
            .map(str::to_owned);
        let is_user = entry.pointer("/message/role").and_then(Value::as_str) == Some("user");
        by_id.insert(id.to_owned(), (parent, is_user));
    }
    let leaf = data.get("leafId").and_then(Value::as_str).map(str::to_owned);
    Some(SessionEntries { by_id, leaf })
}

/// The newest user entry on the active branch: walking the branch from
/// the leaf, the FIRST user entry encountered is the LAST user entry the
/// settling turn consumed — the turn's steer when one landed mid-run,
/// else its prompt (for an unsteered turn that is also the turn's FIRST
/// user entry, the ticket's spelling). That is the turn's provider turn
/// ref, because pi's exclusive fork boundary is "the user entry after the
/// ref" and naming the last consumed entry makes that the next turn's
/// start exactly — see [`fork_boundary`]. A walk that leaves the listing
/// (a since-bounded stretch with no user entry of its own — an
/// extension-initiated run) or reaches the root names nothing.
fn newest_user_entry(entries: &SessionEntries) -> Option<String> {
    // The first entry of the branch walk (newest first) is exactly the
    // newest user entry; the shared walk carries the cycle guard.
    branch_user_entries(entries).into_iter().next()
}

/// The user entry ids on the active branch, NEWEST first. The fork
/// boundary search walks the whole branch: the fork subprocess reads a
/// full `get_entries` listing, so the walk always reaches the root (and
/// the same cycle guard bounds a corrupt tree). A walk that leaves the
/// listing stops — the entries after a `since` cursor bound it to one
/// turn's stretch.
fn branch_user_entries(entries: &SessionEntries) -> Vec<String> {
    let mut users = Vec::new();
    let mut cursor = entries.leaf.clone();
    let mut steps = entries.by_id.len().saturating_add(1);
    while let Some(current) = cursor {
        let Some((parent, is_user)) = entries.by_id.get(&current).cloned() else {
            break;
        };
        if is_user {
            users.push(current);
        }
        if steps == 0 {
            break;
        }
        steps -= 1;
        cursor = parent;
    }
    users
}

/// The resolved fork boundary for a native fork at `last_turn` (the
/// provider turn ref recorded on the boundary entry). pi's RPC `fork`
/// re-roots the session BEFORE the named user message, so the boundary
/// names the user entry of the turn AFTER the boundary turn — keeping
/// everything through it, exactly the prefix the fork copied.
#[derive(Debug, PartialEq, Eq)]
enum ForkBoundary {
    /// Re-root the session before this user entry (one turn newer than
    /// the boundary).
    RootAt(String),
    /// The boundary IS the branch's newest turn: the `--fork` copy
    /// already ends there — fork the whole session, no RPC.
    NewestTurn,
    /// The ref names no entry on the active branch (a stale ref, or one
    /// from a re-rooted/compacted-away branch) — the fork falls back to
    /// the budgeted paste rather than silently keeping turns the
    /// boundary excluded.
    OffBranch,
}

fn fork_boundary(users: &[String], last_turn: &str) -> ForkBoundary {
    let Some(index) = users.iter().position(|id| id == last_turn) else {
        return ForkBoundary::OffBranch;
    };
    // `users` is newest-first: index 0 is the branch's newest user entry,
    // and one step towards the head is one turn newer.
    match index.checked_sub(1).and_then(|newer| users.get(newer)) {
        Some(entry_id) => ForkBoundary::RootAt(entry_id.clone()),
        None => ForkBoundary::NewestTurn,
    }
}

/// One bounded `get_entries` round: a request pi never answers counts as a
/// failed capture (the ref is skipped, never the turn).
async fn read_entries(client: &PiClient, params: Value) -> Result<Value, String> {
    match tokio::time::timeout(SETTLE_TIMEOUT, client.request("get_entries", params)).await {
        Ok(Ok(data)) => Ok(data),
        Ok(Err(e)) => Err(e.to_string()),
        Err(_) => Err("get_entries did not answer within the settle budget".into()),
    }
}

/// One settle's provider turn ref capture (ticket 19): read the session
/// tree and name the LAST user entry the turn consumed — the session file
/// plus that entry id (see [`newest_user_entry`] for why the last, not
/// the first). The previous capture's leaf rides as `since`, bounding the
/// listing to the turn's entries and the walk to the turn's stretch; a
/// cursor pi no longer knows (a re-rooted branch) or a slow read falls
/// back to one full listing — the walk's answer is the same either way,
/// so a failed bounded read costs only the round trip. Best-effort: any
/// failed read costs the ref, never the turn. Advances the cursor on
/// every successful listing, so the next settle bounds itself to the next
/// turn.
async fn capture_turn_ref(
    client: &PiClient,
    session_pointer: &str,
    cursor: &mut Option<String>,
) -> Option<ProviderTurnRef> {
    let params = match cursor.as_deref() {
        Some(since) => json!({ "since": since }),
        None => json!({}),
    };
    let data = match read_entries(client, params).await {
        Ok(data) => data,
        Err(_) => read_entries(client, json!({})).await.ok()?,
    };
    let listing = parse_entries(&data)?;
    let reference =
        newest_user_entry(&listing).map(|turn_id| ProviderTurnRef {
            harness: HarnessId::Pi,
            session_id: session_pointer.to_owned(),
            turn_id,
        });
    *cursor = listing.leaf.clone();
    reference
}

/// The outcome of the fork-launch phase in [`PiHarness::run_with_mode`]:
/// the forked session file the main spawn resumes, the reason a fork
/// could not be performed (the budgeted-paste fallback), or a Stop that
/// landed before the fork could start.
enum ForkLaunch {
    Session(String),
    Failed(String),
    Interrupted,
}

/// Perform one native fork of a source pi session (ticket 19) in a
/// short-lived `pi --mode rpc --fork <source file>` process running in the
/// run's cwd: `--fork` files the new session under the CURRENT project —
/// the same place the main spawn below resumes from (an RPC
/// clone/switch_session alone would keep the source's cwd), and the
/// process never runs a prompt, so it needs no MCP bridge and no model
/// selection. The sequence is pi's own fork surface: the CLI copies the
/// source session into a new file; `get_entries` walks the copy's active
/// branch; when the boundary is not the source's latest turn, the RPC
/// `fork` re-roots the copy BEFORE the next turn's user entry (pi's fork
/// is exclusive); `get_state` reports the new session file. Returns that
/// file — never the source itself (a pi answering with the source would
/// have the main run CONTINUE the source session, mutating it) — or the
/// reason the fork failed (the caller falls back to the budgeted paste).
async fn fork_source_session(
    exe: &Path,
    fork: &ProviderFork,
    cwd: &str,
    startup_timeout: Duration,
    kill_grace: Duration,
) -> Result<String, String> {
    let source = Path::new(&fork.session_id);
    let mut cmd = Command::new(exe);
    cmd.arg("--mode").arg("rpc").arg("--fork").arg(source);
    if !cwd.is_empty() {
        cmd.current_dir(cwd);
    }
    child::configure(&mut cmd);
    crate::compose_child_path(&mut cmd, exe);
    cmd.stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .kill_on_drop(true);
    let child = cmd
        .spawn()
        .map_err(|e| format!("could not spawn the pi fork process: {e}"))?;
    let mut child = child::Child::new(child);
    let stdin = child
        .stdin
        .take()
        .ok_or_else(|| "the pi fork process has no stdin".to_owned())?;
    let stdout = child
        .stdout
        .take()
        .ok_or_else(|| "the pi fork process has no stdout".to_owned())?;
    let (client, mut incoming) = PiClient::new(stdin, stdout);
    // Events (notify noise) are drained so a chatty extension can never
    // stall the reader.
    tokio::spawn(async move {
        while incoming.recv().await.is_some() {}
    });
    let work = async {
        // The forked copy's session file: must exist and be a DIFFERENT
        // file from the source, or the main spawn would continue the
        // source session itself.
        let state = client
            .request("get_state", json!({}))
            .await
            .map_err(|e| e.to_string())?;
        let mut file = state
            .pointer("/sessionFile")
            .and_then(Value::as_str)
            .filter(|file| !file.is_empty())
            .map(str::to_owned)
            .ok_or_else(|| "pi reported no session file for the fork".to_owned())?;
        if Path::new(&file) == source {
            return Err("pi forked onto the source session file".to_owned());
        }
        // The boundary: the copy's active branch tells whether the fork is
        // already at the right place (the source's latest turn) or needs
        // the exclusive re-root at the turn after the boundary.
        let data = client
            .request("get_entries", json!({}))
            .await
            .map_err(|e| e.to_string())?;
        let listing = parse_entries(&data)
            .ok_or_else(|| "pi returned an unreadable session tree".to_owned())?;
        let users = branch_user_entries(&listing);
        let boundary = match fork_boundary(&users, &fork.last_turn) {
            ForkBoundary::OffBranch => {
                return Err(format!(
                    "the boundary entry {} is no longer on the session branch",
                    fork.last_turn
                ))
            }
            ForkBoundary::NewestTurn => None,
            ForkBoundary::RootAt(entry_id) => Some(entry_id),
        };
        if let Some(entry_id) = boundary {
            let data = client
                .request("fork", json!({ "entryId": entry_id }))
                .await
                .map_err(|e| e.to_string())?;
            if data
                .get("cancelled")
                .and_then(Value::as_bool)
                .unwrap_or(false)
            {
                return Err("a pi extension cancelled the session fork".to_owned());
            }
            // The re-rooted session file, re-read from `get_state` (the
            // fork happens inside the copy, so this is the file the main
            // spawn resumes): a failed re-read keeps the copy's file — the
            // fork itself already succeeded.
            if let Ok(state) = client.request("get_state", json!({})).await
                && let Some(after) = state
                    .pointer("/sessionFile")
                    .and_then(Value::as_str)
                    .filter(|file| !file.is_empty())
                && Path::new(after) != source
            {
                file = after.to_owned();
            }
        }
        Ok(file)
    };
    let result = tokio::time::timeout(startup_timeout, work)
        .await
        .map_err(|_| "the pi fork process did not answer in time".to_owned())
        .and_then(|result| result);
    child.shutdown(kill_grace).await;
    result
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
        // Held for the whole run: dropping it removes the bridge file, so it
        // must outlive the pi child (shut down before this function ends).
        mcp_scratch: _mcp_scratch,
        steering_mode_all,
        interrupt_grace,
        kill_grace,
        startup_timeout,
        stderr_tail,
        fork_notice,
    } = session;
    let RunControls {
        execution_lease: _execution_lease,
        request_input,
        mut steering,
        interrupt,
        turn: _turn,
    } = controls;
    let request_input: Arc<RequestInputFn> = Arc::new(request_input);

    // ---- startup: get_state (interruptible, bounded) ----------------------
    let setup = async {
        let state = client.request("get_state", json!({})).await?;
        // The steering mode rides the same startup: pi defaults to
        // one-at-a-time and roboco's steers belong together. Best-effort —
        // a pi old enough to lack the command logs and runs on its own
        // default (the mode is a delivery-batching preference, never a
        // run-shape contract).
        if steering_mode_all
            && let Err(e) = client
                .request("set_steering_mode", json!({ "mode": "all" }))
                .await
        {
            tracing::debug!(
                target: "roboco_harness::pi",
                "set_steering_mode all rejected (pi default runs): {e}"
            );
        }
        // Apply the run's model and thinking level (pi clamps to the model's
        // own ladder; a rejected switch logs and the agent default runs,
        // pi-acp parity). Best-effort: an unavailable model or level is not
        // a failed run.
        configure_live(&client, &request).await?;
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
                kind: None,
            },
        )
        .await;
    }
    if let Some(notice) = &fork_notice {
        // A refused native fork (ticket 19): the fresh session carries the
        // budgeted transcript in front of the prompt (pasted in
        // run_with_mode), so the notice says the transcript rides along
        // (trimmed), not lost — parity with the lost-resume notice above.
        let _ = send(
            &event_tx,
            AgentEvent::Error {
                message: notice.clone(),
                kind: None,
            },
        )
        .await;
    }

    // ---- main loop --------------------------------------------------------
    // Agent activity since the last settle (extension-initiated runs deliver
    // output with no prompt of ours; their settle still emits a Done).
    let mut saw_activity = false;
    // FIFO delivery records for the mailbox inputs sent to pi — the
    // consumption ledger behind the `Steered` boundaries. One record per
    // original input, created when it goes out (not when pi acknowledges
    // it), so an echo racing its acknowledgement still consumes it.
    let mut sent_deliveries: VecDeque<DeliveryRecord> = VecDeque::new();
    // The delivery-record id sequence.
    let mut delivery_seq: u64 = 0;
    let mut steer_calls: FuturesUnordered<SteerCall> = FuturesUnordered::new();
    // Messages the driver itself owns — rejected by pi, stranded in pi's
    // queue at settle, or queued while the settle probe winds down —
    // delivered as fresh prompts once the current turn's Done is out,
    // carrying their attachments and run config.
    let mut driver_steers: VecDeque<SteerMessage> = VecDeque::new();
    // Rejections per message (B1's re-delivery cap), keyed by the message
    // id (the engine's, unique per send) or, for id-less messages, the
    // prompt text: a pi that keeps rejecting one message must be capped,
    // not retried forever.
    let mut rejections: HashMap<String, u32> = HashMap::new();
    // The settle probe: armed at `agent_settled` (and after a handled or
    // undispositioned acknowledgement, or a rejection), re-armed while pi
    // reports busy. The turn ends only when a round reports pi at rest with
    // an unchanged generation.
    let mut probe: Option<BoxFuture<'static, ProbeRound>> = None;
    // The generation the in-flight probe was armed at: `agent_start`,
    // `compaction_start`, every prompt or steer sent, and every settle bump
    // the counter; a probe result armed at a superseded generation is
    // stale (a prompt acknowledgement raced it) and re-arms.
    let mut probe_generation: u64 = 0;
    // The session-tree cursor for provider turn refs (ticket 19): the leaf
    // entry id of the last settle capture. Each settle's `get_entries` runs
    // with it as `since`, bounding the listing — and the ref walk — to the
    // entries the settling turn appended; a cursor pi no longer knows (a
    // re-rooted branch) falls back to one full listing. `None` until the
    // first capture: a fresh session's first walk simply runs to the root,
    // and the first user entry it meets is this run's own prompt.
    let mut ref_cursor: Option<String> = None;
    // Consecutive failed probe rounds (any success resets it).
    let mut probe_failures: u32 = 0;
    let mut generation: u64 = 0;
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

    // Arm the first prompt. The acceptance — pi's preflight — is set when a
    // prompt is armed and cleared when its response lands (Ok = accepted,
    // read by disposition; Err = rejected). A prompt that starts a run
    // makes pi emit its own user message first — the echo, not a steer
    // delivery; a prompt pi queues delivers at that echo instead. A
    // prompt-owned run is between acceptance and its Done.
    let images = load_image_contents(&request.attachments).await;
    let mut armed = Some(Armed {
        acceptance: prompt_acceptance(&client, &request.prompt, images),
        message: None,
        delivery: None,
    });
    // Whether the current acceptance cycle has already spent its one
    // immediate at-rest re-delivery of a rejected message: a pi that keeps
    // rejecting falls back to the probe path (bounded by its re-arm delay)
    // instead of hot-looping round trips.
    let mut retried_at_rest = false;
    generation += 1; // the prompt went out
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

            res = async { (&mut armed.as_mut().expect("guarded by if").acceptance).await }, if armed.is_some() => {
                let Armed { message: mailbox, delivery, .. } =
                    armed.take().expect("guarded by if");
                // The delivery record this prompt created (None = the run's
                // own first prompt, whose echo is the awaiting one).
                // Started/Handled retire it — the boundary fires here only
                // when the echo has not consumed the record yet: the record
                // is the single owner of the one `Steered` each input owes.
                match res {
                    Ok(data) => {
                        // A prompt acknowledgement bumps the settle
                        // generation: a probe whose reading predates it must
                        // re-arm — the steer it accepted may have landed in
                        // pi queue after the probe read it (T3 bug 16846).
                        generation += 1;
                        // A new acceptance cycle earns a fresh immediate
                        // re-delivery budget.
                        retried_at_rest = false;
                        match disposition_of(&data) {
                            Disposition::Started => {
                                // A run began: it streams until
                                // agent_settled, where the settle probe
                                // takes over. A mailbox message is delivered
                                // at acceptance — unless its echo already
                                // consumed the record (the processing race).
                                let retired = delivery
                                    .is_some_and(|id| retire_delivery(&mut sent_deliveries, id));
                                if retired
                                    && !confirm_delivery(
                                        &event_tx,
                                        &mut assistant_message_id,
                                    )
                                    .await
                                {
                                    break 'main;
                                }
                                // Only when the boundary fired here does the
                                // run's own user echo become the awaiting
                                // one; otherwise the echo already delivered.
                                awaiting_prompt_echo |= retired;
                                run_active = true;
                            }
                            Disposition::Queued => {
                                // Accepted into pi steer queue: the delivery
                                // record stays — the user message echo
                                // consumes it (the delivery signal), in
                                // whatever order the acknowledgement
                                // processes. A strand is recovered by the
                                // settle probe clear_queue — the record now
                                // keeps the ORIGINAL message so that
                                // recovery re-queues it intact, attachments
                                // and run config included.
                                if let Some(id) = delivery
                                    && let Some(record) =
                                        sent_deliveries.iter_mut().find(|r| r.id == id)
                                {
                                    record.message = mailbox.clone();
                                }
                                //
                                // The run own first prompt queued behind an
                                // extension run: awaiting_prompt_echo
                                // already covers its echo.
                                run_active = true;
                            }
                            Disposition::Handled => {
                                // pi handled the prompt without a run (an
                                // extension command): the boundary is owed
                                // now, and the turn ends once pi reports it
                                // is not streaming.
                                let retired = delivery
                                    .is_some_and(|id| retire_delivery(&mut sent_deliveries, id));
                                if retired
                                    && !confirm_delivery(
                                        &event_tx,
                                        &mut assistant_message_id,
                                    )
                                    .await
                                {
                                    break 'main;
                                }
                                awaiting_prompt_echo = false;
                                run_active = false;
                                if probe.is_none() {
                                    probe_generation = generation;
                                    probe = Some(rearm_probe(&client, false));
                                }
                            }
                            Disposition::Unknown => {
                                // pi < 0.99 reports no disposition:
                                // acceptance still confirms delivery, but
                                // whether a run started is inferred — the
                                // probe (after a grace, so a starting run
                                // can flip isStreaming) ends the turn if
                                // nothing runs.
                                let retired = delivery
                                    .is_some_and(|id| retire_delivery(&mut sent_deliveries, id));
                                if retired
                                    && !confirm_delivery(
                                        &event_tx,
                                        &mut assistant_message_id,
                                    )
                                    .await
                                {
                                    break 'main;
                                }
                                awaiting_prompt_echo |= retired;
                                if probe.is_none() {
                                    probe_generation = generation;
                                    probe = Some(rearm_probe(&client, true));
                                }
                            }
                        }
                    },
                    Err(e) => match mailbox {
                        Some(msg) => {
                            // A rejected mailbox prompt never ends the
                            // session: the rejection surfaces, the message
                            // returns to the queue, and it re-delivers once
                            // the turn ends — immediately when the session
                            // is already at rest, else after the probe
                            // closes the turn. The delivery record retires
                            // without a boundary: pi never consumed this
                            // input, so the engine at-least-once ledger
                            // keeps its entry until the re-delivery.
                            if let Some(id) = delivery {
                                retire_delivery(&mut sent_deliveries, id);
                            }
                            let _ = send(
                                &event_tx,
                                AgentEvent::Error {
                                    message: format!("pi rejected the message: {e}"),
                                    kind: None,
                                },
                            )
                            .await;
                            // B1's re-delivery cap: past the attempts, the
                            // message is NOT re-armed (an endlessly
                            // rejecting pi would otherwise Error + Done-loop
                            // on it forever) — it is handed back to the
                            // engine as a failed send, the at-least-once
                            // ledger re-dispatches it on a fresh runtime,
                            // and this turn ends ERRORED: never Completed
                            // for a message that never ran.
                            if let Some(attempts) = note_rejection(&mut rejections, &msg) {
                                turn_error = Some(format!(
                                    "pi rejected the message {attempts} times: {e}"
                                ));
                                run_active = false;
                                if probe.is_none() {
                                    probe_generation = generation;
                                    probe = Some(rearm_probe(&client, true));
                                }
                            } else {
                                driver_steers.push_front(msg);
                                run_active = false;
                                let at_rest = !saw_activity
                                    && probe.is_none()
                                    && steer_calls.is_empty();
                                if at_rest && !retried_at_rest {
                                    // One immediate re-delivery per acceptance
                                    // cycle; a pi that keeps rejecting an idle
                                    // prompt falls back to the probe path below,
                                    // whose re-arm delay bounds the retry rate.
                                    retried_at_rest = true;
                                    if let Some(parked) = driver_steers.pop_front()
                                        && !deliver_parked_steer(
                                            &client,
                                            &mut armed,
                                            &mut sent_deliveries,
                                            &mut delivery_seq,
                                            &mut awaiting_prompt_echo,
                                            &mut run_active,
                                            &mut done_current,
                                            &mut generation,
                                            parked,
                                        )
                                        .await
                                    {
                                        break 'main;
                                    }
                                } else if probe.is_none() {
                                    probe_generation = generation;
                                    probe = Some(rearm_probe(&client, true));
                                }
                            }
                        }
                        None => {
                            // The run's own first prompt: the turn errors
                            // with pi's own reason (an interrupt in flight
                            // still wins, ACP parity). The session itself
                            // parks — the consumer decides its fate.
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
                            run_active = false;
                        }
                    },
                }
            },

            round = async { probe.as_mut().expect("guarded by if").await }, if probe.is_some() => {
                probe = None;
                match round {
                    ProbeRound::Busy => {
                        // pi is streaming or compacting: re-arm after the
                        // delay (the probe must never hot-loop while pi
                        // streams).
                        probe_failures = 0;
                        probe_generation = generation;
                        probe = Some(rearm_probe(&client, true));
                    }
                    ProbeRound::Recovered(texts) => {
                        // pi is idle yet still held messages (steers
                        // stranded by the settle race): they were cleared
                        // and re-queue, in send order, as the next turn's
                        // prompts — INTACT (B6): a stranded steer's record
                        // keeps the original mailbox message, so its
                        // re-delivery carries the attachments and run
                        // config it was sent with, and its delivery record
                        // retires (pi will never echo it) so the
                        // re-delivery's own echo consumes the fresh record
                        // it creates. A stranded text with no record (the
                        // run's own first prompt) re-queues as bare text.
                        probe_failures = 0;
                        let mut recovered: VecDeque<SteerMessage> = VecDeque::new();
                        for text in texts {
                            let record = sent_deliveries
                                .iter()
                                .position(|record| record.text == text)
                                .and_then(|pos| sent_deliveries.remove(pos));
                            let message = record
                                .and_then(|record| record.message)
                                .unwrap_or_else(|| SteerMessage::text(text));
                            recovered.push_back(message);
                        }
                        for message in recovered.into_iter().rev() {
                            driver_steers.push_front(message);
                        }
                        probe_generation = generation;
                        probe = Some(rearm_probe(&client, false));
                    }
                    ProbeRound::Quiescent(stats) => {
                        if generation != probe_generation {
                            // A prompt, steer or settle bumped since arming:
                            // this reading is stale — re-probe. This is what
                            // re-arms the probe after a prompt
                            // acknowledgement that raced it.
                            probe_failures = 0;
                            probe_generation = generation;
                            probe = Some(rearm_probe(&client, false));
                        } else if armed.is_some() || !steer_calls.is_empty() {
                            // A prompt or steer is still awaiting pi's
                            // acknowledgement (B4): generation equality
                            // cannot see an input that is in preflight — the
                            // bump lands when its acknowledgement does —
                            // and the turn is not over while one is. Wait it
                            // out: re-arm (after the delay — a slow
                            // preflight must not hot-loop the probe) and
                            // re-check once the acceptance resolves.
                            probe_failures = 0;
                            probe_generation = generation;
                            probe = Some(rearm_probe(&client, true));
                        } else {
                            probe_failures = 0;
                            if let Some(usage) = stats
                                .as_ref()
                                .and_then(|stats| context_usage_event(stats))
                                && !send(&event_tx, usage).await
                            {
                                break 'main;
                            }
                            // The provider turn ref (ticket 19): `get_entries`
                            // now that the turn has settled — the session
                            // file plus the LAST user entry the turn
                            // consumed (its steer when one landed mid-run,
                            // else its prompt — for an unsteered turn that
                            // is also its first user entry, the ticket's
                            // spelling). The engine holds it for the stamp
                            // when the entry's segment finishes at the
                            // `Done` below. Best-effort: a failed read costs
                            // the ref — a later fork falls back to the
                            // budgeted paste — never the turn. The capture
                            // is BOUNDED and interruptible (B7): its round
                            // trips must never wedge the run loop for their
                            // full 2×15s budget — a Stop landing mid-capture
                            // is read at once and cuts the wait short (the
                            // ref is lost, never the turn), and the Done
                            // below waits at most the short budget.
                            let capture =
                                capture_turn_ref(&client, &session_pointer, &mut ref_cursor);
                            tokio::pin!(capture);
                            let mut stopped = false;
                            let captured = tokio::select! {
                                reference = &mut capture => reference,
                                _ = interrupt.cancelled(), if !interrupt_sent => {
                                    stopped = true;
                                    None
                                }
                                _ = tokio::time::sleep(REF_CAPTURE_BUDGET) => None,
                            };
                            if stopped {
                                // Stop landed mid-capture: the turn already
                                // settled with nothing in flight, so it closes
                                // out as interrupted at once (the "already
                                // settled" shortcut of the interrupt arm).
                                interrupted = true;
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
                            if let Some(reference) = captured
                                && !send(&event_tx, AgentEvent::TurnRef { reference }).await
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
                            // A queued message becomes the next turn;
                            // otherwise the session parks — the caller owns
                            // teardown.
                            if let Some(parked) = driver_steers.pop_front()
                                && !deliver_parked_steer(
                                    &client,
                                    &mut armed,
                                    &mut sent_deliveries,
                                    &mut delivery_seq,
                                    &mut awaiting_prompt_echo,
                                    &mut run_active,
                                    &mut done_current,
                                    &mut generation,
                                    parked,
                                )
                                .await
                            {
                                break 'main;
                            }
                        }
                    }
                    ProbeRound::Failed(reason) => {
                        probe_failures += 1;
                        if probe_failures >= PROBE_ATTEMPTS {
                            // The probe failed repeatedly: end the turn
                            // errored — visibly, with the reason — never a
                            // silent kill. The session parks; the queued
                            // messages' ledger entries survive for the
                            // engine's re-dispatch. The counter resets so
                            // the next turn's probe starts a fresh budget.
                            //
                            // The mailbox must not wedge behind the dead
                            // turn (B2): the delivery records clear — no
                            // later turn's user echo may consume a stale
                            // one — and the driver-owned messages drain
                            // below exactly like after a clean settle (the
                            // next one becomes the next turn), so nothing
                            // lines up behind the dead turn forever and a
                            // later mailbox message still gets its turn.
                            probe_failures = 0;
                            sent_deliveries.clear();
                            turn_error = None;
                            done_current = true;
                            if interrupted {
                                done_after_interrupt = true;
                            }
                            if !send(
                                &event_tx,
                                AgentEvent::Done {
                                    status: DoneStatus::Errored,
                                    result: None,
                                    error: Some(format!(
                                        "pi's settle probe failed after \
                                         {PROBE_ATTEMPTS} attempts: {reason}"
                                    )),
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
                            if let Some(parked) = driver_steers.pop_front()
                                && !deliver_parked_steer(
                                    &client,
                                    &mut armed,
                                    &mut sent_deliveries,
                                    &mut delivery_seq,
                                    &mut awaiting_prompt_echo,
                                    &mut run_active,
                                    &mut done_current,
                                    &mut generation,
                                    parked,
                                )
                                .await
                            {
                                break 'main;
                            }
                        } else {
                            probe_generation = generation;
                            probe = Some(rearm_probe(&client, true));
                        }
                    }
                }
            },

            res = steer_calls.next(), if !steer_calls.is_empty() => match res {
                Some((delivery, msg, res)) => match res {
                    Ok(data) => {
                        // The steer acknowledgement bumps the settle
                        // generation: a probe whose reading predates it must
                        // re-arm — the steer may have landed in pi queue
                        // after the probe read it (T3 bug 16846).
                        generation += 1;
                        match disposition_of(&data) {
                            Disposition::Started => {
                                // The steer raced the run end and pi started
                                // a run with it: delivered now, and the run
                                // streams to its own settle — unless its
                                // echo already consumed the record (the
                                // processing race).
                                let retired =
                                    retire_delivery(&mut sent_deliveries, delivery);
                                if retired
                                    && !confirm_delivery(
                                        &event_tx,
                                        &mut assistant_message_id,
                                    )
                                    .await
                                {
                                    break 'main;
                                }
                                // The started run's own user echo is the
                                // awaiting one — it must not consume the
                                // next queued steer's record.
                                awaiting_prompt_echo |= retired;
                                run_active = true;
                            }
                            Disposition::Queued => {
                                // Accepted into pi steer queue: the delivery
                                // record stays — the user message echo
                                // consumes it, in whatever order this
                                // acknowledgement processes. The record keeps
                                // the message so a strand recovered by
                                // clear_queue re-queues it intact (B6).
                                if let Some(record) =
                                    sent_deliveries.iter_mut().find(|r| r.id == delivery)
                                {
                                    record.message = Some(msg.clone());
                                }
                            }
                            Disposition::Handled => {
                                // pi handled it without starting a run: the
                                // message is delivered, at acceptance unless
                                // an echo already consumed the record.
                                if retire_delivery(&mut sent_deliveries, delivery)
                                    && !confirm_delivery(
                                        &event_tx,
                                        &mut assistant_message_id,
                                    )
                                    .await
                                {
                                    break 'main;
                                }
                            }
                            Disposition::Unknown => {
                                // pi < 0.99: delivery lands at the echo, if
                                // one ever comes — the record stays for it.
                            }
                        }
                    },
                    Err(e) if client.is_closed() => {
                        tracing::debug!(target: "roboco_harness::pi", "steer lost to exit: {e}");
                    }
                    Err(e) => {
                        // Rejected: surface it and hand the message back to
                        // the queue — it re-delivers once the turn ends
                        // (immediately when the session is already at
                        // rest, else when the settle probe closes the
                        // turn). The delivery record retires without a
                        // boundary: pi never consumed this input, so the
                        // engine at-least-once ledger keeps its entry until
                        // the re-delivery. Past B1's re-delivery cap the
                        // message is handed back to the engine as a failed
                        // send (the turn ends errored — never a loop of
                        // Error + Completed for a message that never ran);
                        // a rejection landing after the turn already ended
                        // (a probe failure closed it) goes straight back too.
                        retire_delivery(&mut sent_deliveries, delivery);
                        let _ = send(
                            &event_tx,
                            AgentEvent::Error {
                                message: format!("pi rejected the steer: {e}"),
                                kind: None,
                            },
                        )
                        .await;
                        let at_rest = !run_active
                            && !saw_activity
                            && probe.is_none()
                            && armed.is_none()
                            && steer_calls.is_empty();
                        if let Some(attempts) = note_rejection(&mut rejections, &msg) {
                            if done_current {
                                // The turn already ended (a probe failure
                                // closed it): there is no turn left to fail —
                                // the Error above surfaced the rejection, and
                                // the engine's ledger still owns the message.
                            } else {
                                turn_error = Some(format!(
                                    "pi rejected the message {attempts} times: {e}"
                                ));
                                if at_rest {
                                    // Nothing else will settle the turn: the
                                    // probe closes it errored.
                                    probe_generation = generation;
                                    probe = Some(rearm_probe(&client, true));
                                }
                            }
                        } else {
                            driver_steers.push_front(msg);
                            if at_rest && (done_current || !retried_at_rest) {
                                // Same one-shot immediate re-delivery budget as a
                                // rejected parked prompt: bounded by the probe
                                // re-arm delay if pi keeps rejecting.
                                retried_at_rest = true;
                                if let Some(parked) = driver_steers.pop_front()
                                    && !deliver_parked_steer(
                                        &client,
                                        &mut armed,
                                        &mut sent_deliveries,
                                        &mut delivery_seq,
                                        &mut awaiting_prompt_echo,
                                        &mut run_active,
                                        &mut done_current,
                                        &mut generation,
                                        parked,
                                    )
                                    .await
                                {
                                    break 'main;
                                }
                            } else if at_rest && probe.is_none() {
                                // The immediate budget is spent: the probe ends
                                // the turn and its Done pops the message back.
                                probe_generation = generation;
                                probe = Some(rearm_probe(&client, true));
                            }
                        }
                    }
                },
                None => {}
            },


            inc = incoming.recv() => match inc {
                Some(PiIncoming::Event(event)) => {
                    let kind = event.get("type").and_then(Value::as_str).unwrap_or("");
                    match kind {
                        "agent_start" => {
                            // Agent activity: bumps the settle generation
                            // (an in-flight probe would read stale) and
                            // marks the turn active until its settle.
                            generation += 1;
                            saw_activity = true;
                        }
                        "message_start" => {
                            let role = event.pointer("/message/role").and_then(Value::as_str);
                            if role == Some("assistant") {
                                tracker.reset();
                            } else if role == Some("user") {
                                // Our prompt's own echo, or a queued input's
                                // delivery out of pi's queue (upstream
                                // PROTOCOL.md: message_start for each
                                // consumed user input emits its own
                                // Steered; the ACK and queue_update are
                                // never consumption receipts).
                                if awaiting_prompt_echo {
                                    awaiting_prompt_echo = false;
                                } else if sent_deliveries.pop_front().is_some() {
                                    // The consumed signal: this echo
                                    // delivers the oldest record — whether
                                    // or not its acknowledgement has been
                                    // processed yet.
                                    if !confirm_delivery(
                                        &event_tx,
                                        &mut assistant_message_id,
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
                                    kind: None,
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
                                    kind: None,
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
                            // the run as an error; success (manual,
                            // threshold, overflow-retry) just continues or
                            // re-prompts. A compaction retry that SUCCEEDED
                            // recovered the turn: the error that triggered
                            // it (the overflow error message, an exhausted
                            // auto-retry) no longer rejects the prompt — the
                            // retried run may still complete it.
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
                            } else if will_retry && !failed && !aborted {
                                turn_error = None;
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
                        "compaction_start" => {
                            // Compaction is agent activity too: it bumps
                            // the settle generation — the probe confirms
                            // rest only after compaction_end.
                            generation += 1;
                        }
                        "agent_settled" => {
                            // Every settle bumps the generation.
                            generation += 1;
                            if interrupted {
                                // abort() landed: close out as Interrupted
                                // without waiting for anything else. The
                                // settle capture still runs — pi is at rest
                                // (it just answered agent_settled), and the
                                // interrupted reply keeps its provider turn
                                // ref so a fork from it continues natively
                                // instead of pasting (spec story 59:
                                // interrupted replies are fork boundaries
                                // too). Best-effort, as at every settle —
                                // and bounded (B7): the round trip never
                                // delays the Interrupted Done by more than
                                // the short budget.
                                let reference = tokio::time::timeout(
                                    REF_CAPTURE_BUDGET,
                                    capture_turn_ref(
                                        &client,
                                        &session_pointer,
                                        &mut ref_cursor,
                                    ),
                                )
                                .await
                                .ok()
                                .flatten();
                                if let Some(reference) = reference
                                    && !send(&event_tx, AgentEvent::TurnRef { reference }).await
                                {
                                    break 'main;
                                }
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
                            if run_active || saw_activity {
                                run_active = false;
                                saw_activity = false;
                                if probe.is_none() {
                                    probe_generation = generation;
                                    probe = Some(rearm_probe(&client, false));
                                }
                                // A probe already in flight (armed after a
                                // handled or undispositioned acknowledgement,
                                // or a rejection): its generation check
                                // re-arms it for this settle.
                            }
                            // A settle with no run behind it (spurious, one
                            // already covered by an in-flight probe, or
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
                    // The settle probe and the acceptance both race EOF
                    // through the pending map: a result that already
                    // resolved must still close out the turn. Give each a
                    // short drain before falling to crash bookkeeping.
                    if let Some(mut fut) = probe.take() {
                        if let Ok(ProbeRound::Quiescent(stats)) =
                            tokio::time::timeout(EOF_DRAIN, &mut fut).await
                        {
                            if let Some(usage) =
                                stats.as_ref().and_then(|s| context_usage_event(s))
                            {
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
                    if let Some(mut armed_prompt) = armed.take() {
                        // A resolved REJECTION carries pi's own reason; a
                        // resolved acceptance (or a request failed by the
                        // EOF cleanup) falls through to the crash message.
                        // A Stop that killed pi before the answer still wins
                        // (ACP parity) — one Interrupted Done, never two.
                        if let Ok(Err(e)) =
                            tokio::time::timeout(EOF_DRAIN, &mut armed_prompt.acceptance).await
                        {
                            done_current = true;
                            let (status, error) = if interrupted {
                                (DoneStatus::Interrupted, None)
                            } else {
                                (DoneStatus::Errored, Some(e.to_string()))
                            };
                            if interrupted {
                                done_after_interrupt = true;
                            }
                            let _ = send(
                                &event_tx,
                                AgentEvent::Done {
                                    status,
                                    result: None,
                                    error,
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
                    // A changed model or thinking level applies on the live
                    // process before the message it was sent with.
                    if let Some(next) = &msg.config {
                        // Best-effort: a rejected switch logs inside and the
                        // current configuration runs (startup parity).
                        let _ = configure_live(&client, next).await;
                    }
                    if run_active || armed.is_some() {
                        // Live turn: the prompt rides steer behavior — pi
                        // queues it while streaming (delivered after the
                        // current assistant turn's tool calls, all queued
                        // steers together in mode "all") and starts a run
                        // when idle, atomically against settlement. The
                        // staged attachments inline as images, exactly like
                        // a turn-start prompt.
                        generation += 1; // the prompt goes out
                        delivery_seq += 1;
                        let delivery = delivery_seq;
                        sent_deliveries.push_back(DeliveryRecord {
                            id: delivery,
                            text: msg.prompt.clone(),
                            message: None,
                        });
                        // Images load BEFORE the call is queued (B5): the
                        // steer calls run concurrently, and a call that
                        // loads its images inside the future can be beaten
                        // to the wire by a later, text-only steer — pi would
                        // receive the burst out of order. Loading here keeps
                        // the FuturesUnordered's completion order and the
                        // wire's send order the same: mailbox order.
                        let images = load_image_contents(&msg.attachments).await;
                        steer_calls.push(steer_request(&client, msg, delivery, images));
                    } else if probe.is_some() || !steer_calls.is_empty() || !driver_steers.is_empty() {
                        // The settle probe or a steer acknowledgement is
                        // still winding down: FIFO behind it.
                        driver_steers.push_back(msg);
                    } else {
                        if !deliver_parked_steer(
                            &client,
                            &mut armed,
                            &mut sent_deliveries,
                            &mut delivery_seq,
                            &mut awaiting_prompt_echo,
                            &mut run_active,
                            &mut done_current,
                            &mut generation,
                            msg,
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
                        && armed.is_none()
                        && probe.is_none()
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
                // An armed probe does NOT mean the turn settled (B3): the
                // probe also runs while work is live (a handled prompt
                // during an extension run, a rejection mid-run). Only a
                // probe with nothing else in flight is "already settled" —
                // otherwise Stop must clear pi's queue and abort exactly
                // like any live turn, or a steer pi never consumed would
                // survive into the aborted run.
                let settled = !run_active
                    && !saw_activity
                    && armed.is_none()
                    && steer_calls.is_empty();
                if probe.is_some() && settled {
                    // The turn already settled; the probe was the only thing
                    // in flight. Close out as interrupted.
                    let _ = probe.take();
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
                let in_flight =
                    run_active || armed.is_some() || saw_activity || !steer_calls.is_empty();
                if in_flight {
                    // clear_queue FIRST: steers pi accepted but never
                    // consumed must not survive into the abort — they are
                    // cancelled with the turn (the engine's at-least-once
                    // ledger drops them on the interrupted exit, their user
                    // entries already sit in the transcript), never
                    // delivered into the aborted run. abort() waits for
                    // idle before responding; neither request blocks the
                    // loop — the agent_settled abort triggers (or the
                    // signal escalation below) ends the run. The wire keeps
                    // the order: both ride the client's single writer.
                    let client = client.clone();
                    tokio::spawn(async move {
                        if let Err(e) = client.request("clear_queue", json!({})).await {
                            tracing::debug!(target: "roboco_harness::pi", "clear_queue failed (escalation will reap): {e}");
                        }
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

/// Arm a prompt and return its acceptance future. Every prompt — turn
/// starts included — goes out with `streamingBehavior: "steer"`: pi starts
/// a run when idle and queues the prompt when an extension run is
/// streaming, atomically against settlement, so a prompt can never strand
/// against the end of a run. The acceptance is unbounded on purpose: pi's
/// preflight (input handlers, `before_agent_start` hooks including the
/// Roboco MCP bridge, and pre-prompt compaction) may take arbitrarily
/// long, and only Stop interrupts a slow acceptance.
fn prompt_acceptance(
    client: &PiClient,
    text: &str,
    images: Vec<Value>,
) -> BoxFuture<'static, Result<Value, HarnessError>> {
    let client = client.clone();
    let text = text.to_owned();
    let mut params = json!({ "message": text, "streamingBehavior": "steer" });
    if !images.is_empty() {
        params["images"] = Value::Array(images);
    }
    Box::pin(async move { client.request("prompt", params).await })
}

/// One mid-run steer: the same `prompt` with steer behavior (pi queues it
/// while streaming and starts a run when idle). Carries the mailbox
/// message so a rejection can hand it back to the queue verbatim, its
/// delivery-record id so the acknowledgement (or a rejection) can retire
/// it, and the message's attachments staged as inline images — a steer
/// delivers its images exactly like a turn-start prompt (the path refs
/// ride the text as a fallback either way). The images arrive LOADED (the
/// caller loads them before queueing, so a burst's wire order is its
/// mailbox order — see the steering arm).
fn steer_request(
    client: &PiClient,
    msg: SteerMessage,
    delivery: u64,
    images: Vec<Value>,
) -> SteerCall {
    let client = client.clone();
    Box::pin(async move {
        let mut params = json!({
            "message": msg.prompt.clone(),
            "streamingBehavior": "steer",
        });
        if !images.is_empty() {
            params["images"] = Value::Array(images);
        }
        let result = client.request("prompt", params).await;
        (delivery, msg, result)
    })
}

/// Emit the `Steered` boundary that confirms a mailbox message's delivery:
/// the engine retires its at-least-once ledger entry here and splits the
/// assistant entry at the boundary.
async fn confirm_delivery(
    event_tx: &mpsc::Sender<Result<AgentEvent, HarnessError>>,
    assistant_message_id: &mut String,
) -> bool {
    let (prev, next) = rotate(assistant_message_id);
    send(
        event_tx,
        AgentEvent::Steered {
            assistant_message_id: Some(prev),
            next_assistant_message_id: Some(next),
        },
    )
    .await
}

/// A parked session's next message: apply its run config, then arm the
/// prompt with the message's attachments inlined. The delivery boundary
/// (`Steered`) is owed only once pi ACCEPTS the prompt — at the
/// acknowledgement for a run or a handled command, at the user message
/// echo for a queued steer — so a rejected prompt leaves the message
/// undelivered and recoverable.
#[allow(clippy::too_many_arguments)]
async fn deliver_parked_steer(
    client: &PiClient,
    armed: &mut Option<Armed>,
    sent_deliveries: &mut VecDeque<DeliveryRecord>,
    delivery_seq: &mut u64,
    awaiting_prompt_echo: &mut bool,
    run_active: &mut bool,
    done_current: &mut bool,
    generation: &mut u64,
    msg: SteerMessage,
) -> bool {
    if let Some(next) = &msg.config {
        // Best-effort: a rejected switch logs inside and the current
        // configuration runs (startup parity).
        let _ = configure_live(client, next).await;
    }
    let images = load_image_contents(&msg.attachments).await;
    // The delivery record is created when the input goes out: pi's echo
    // consumes it whether or not the acknowledgement has been processed
    // yet (upstream PROTOCOL.md: the ACK is not a consumption receipt).
    *delivery_seq += 1;
    let delivery = *delivery_seq;
    sent_deliveries.push_back(DeliveryRecord {
        id: delivery,
        text: msg.prompt.clone(),
        message: None,
    });
    *armed = Some(Armed {
        acceptance: prompt_acceptance(client, &msg.prompt, images),
        message: Some(msg),
        delivery: Some(delivery),
    });
    // Set per disposition once pi answers: a started run echoes its own
    // user message; a queued acceptance delivers at the echo instead.
    *awaiting_prompt_echo = false;
    *run_active = true;
    *done_current = false;
    *generation += 1; // the prompt goes out
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

#[cfg(test)]
mod tests {
    use super::steering_mode_configured;
    use super::{
        ForkBoundary, SessionEntries, branch_user_entries, fork_boundary, newest_user_entry,
        parse_entries,
    };
    use std::path::Path;

    fn write_settings(dir: &Path, body: &str) {
        std::fs::create_dir_all(dir).unwrap();
        std::fs::write(dir.join("settings.json"), body).unwrap();
    }

    /// One session tree as a get_entries payload: `ids` chains the entries
    /// in order (the bool marks user entries).
    fn tree(ids: &[(&str, bool)], leaf: Option<&str>) -> Option<SessionEntries> {
        let mut parent: Option<&str> = None;
        let mut entries = Vec::new();
        for (id, user) in ids {
            entries.push(serde_json::json!({
                "type": "message",
                "id": id,
                "parentId": parent,
                "message": { "role": if *user { "user" } else { "assistant" } },
            }));
            parent = Some(id);
        }
        parse_entries(&serde_json::json!({
            "entries": entries,
            "leafId": leaf,
        }))
    }

    /// The ref walk names the newest user entry on the branch: the turn's
    /// steer when one landed mid-run, else its prompt.
    #[test]
    fn the_ref_walk_names_the_last_user_entry_of_the_turn() {
        // u1 → a1 → s2 (a steer) → a2: the turn consumed s2 last.
        let entries = tree(
            &[("u1", true), ("a1", false), ("s2", true), ("a2", false)],
            Some("a2"),
        )
        .expect("parseable");
        assert_eq!(newest_user_entry(&entries).as_deref(), Some("s2"));
        // Without the steer, the prompt IS the turn's last user entry.
        let entries = tree(&[("u1", true), ("a1", false)], Some("a1")).expect("parseable");
        assert_eq!(newest_user_entry(&entries).as_deref(), Some("u1"));
        // A full listing of a stretch with no user entry of its own (an
        // extension-initiated run) degrades to the OLDER turn's entry —
        // the since cursor is what scopes the walk to one turn.
        let entries = tree(
            &[("u1", true), ("a1", false), ("a2", false)],
            Some("a2"),
        )
        .expect("parseable");
        assert_eq!(newest_user_entry(&entries).as_deref(), Some("u1"));
        // A since-bounded listing (the stretch ends at the cursor) names
        // nothing: the walk leaves the listing before any user entry.
        let entries = tree(&[("a2", false), ("a3", false)], Some("a3")).expect("parseable");
        assert_eq!(newest_user_entry(&entries), None);
        // An empty session has no leaf to walk from.
        let entries = tree(&[], None).expect("parseable");
        assert_eq!(newest_user_entry(&entries), None);
    }

    /// A corrupt parent cycle cannot loop the walks: they terminate (the
    /// guard bounds the pass) even though the parent chain never ends.
    #[test]
    fn corrupt_parent_cycles_terminate() {
        let data = serde_json::json!({
            "entries": [
                { "type": "message", "id": "x", "parentId": "y", "message": { "role": "user" } },
                { "type": "message", "id": "y", "parentId": "x", "message": { "role": "assistant" } },
            ],
            "leafId": "y",
        });
        let entries = parse_entries(&data).expect("parseable");
        // The walk from the leaf meets the user entry before the cycle
        // guard trips, and never loops past it.
        assert_eq!(newest_user_entry(&entries).as_deref(), Some("x"));
        assert!(branch_user_entries(&entries).len() <= 2);
    }

    /// The branch walk lists the active branch's user entries, newest
    /// first — the order the fork boundary search steps through.
    #[test]
    fn the_branch_walk_lists_user_entries_newest_first() {
        let entries = tree(
            &[
                ("u1", true),
                ("a1", false),
                ("u2", true),
                ("a2", false),
                ("u3", true),
                ("a3", false),
            ],
            Some("a3"),
        )
        .expect("parseable");
        assert_eq!(
            branch_user_entries(&entries),
            vec!["u3".to_owned(), "u2".to_owned(), "u1".to_owned()]
        );
    }

    /// The fork boundary: the newest turn forks the whole session (no
    /// RPC), an earlier one names the NEXT turn's user entry, and a ref
    /// that is not on the branch refuses (the budgeted-paste fallback).
    #[test]
    fn the_fork_boundary_names_the_next_turn() {
        let users: Vec<String> = ["u3", "u2", "u1"]
            .iter()
            .map(|id| id.to_string())
            .collect();
        // The boundary is the latest turn: the --fork copy already ends
        // there, no RPC fork.
        assert_eq!(fork_boundary(&users, "u3"), ForkBoundary::NewestTurn);
        // An earlier boundary names the turn after it.
        assert_eq!(fork_boundary(&users, "u2"), ForkBoundary::RootAt("u3".to_owned()));
        assert_eq!(fork_boundary(&users, "u1"), ForkBoundary::RootAt("u2".to_owned()));
        // A stale ref refuses rather than forking somewhere wrong.
        assert_eq!(fork_boundary(&users, "gone"), ForkBoundary::OffBranch);
    }

    /// No settings anywhere: the driver selects the mode itself.
    #[test]
    fn steering_mode_is_unconfigured_without_settings() {
        let home = tempfile::tempdir().unwrap();
        let project = tempfile::tempdir().unwrap();
        assert!(!steering_mode_configured(
            Some(home.path()),
            &project.path().display().to_string(),
        ));
    }

    /// A mode in the agent directory (the user's own, or `/steering` written
    /// globally) is an explicit choice: never overwritten.
    #[test]
    fn agent_dir_settings_count_as_configured() {
        let home = tempfile::tempdir().unwrap();
        let project = tempfile::tempdir().unwrap();
        write_settings(home.path(), r#"{"steeringMode": "one-at-a-time"}"#);
        assert!(steering_mode_configured(
            Some(home.path()),
            &project.path().display().to_string(),
        ));
    }

    /// A mode in the project `.pi/settings.json` counts the same way.
    #[test]
    fn project_settings_count_as_configured() {
        let home = tempfile::tempdir().unwrap();
        let project = tempfile::tempdir().unwrap();
        write_settings(&project.path().join(".pi"), r#"{"steeringMode": "all"}"#);
        assert!(steering_mode_configured(
            Some(home.path()),
            &project.path().display().to_string(),
        ));
    }

    /// Settings without the key (or unreadable JSON) are not a choice.
    #[test]
    fn settings_without_the_key_are_not_configured() {
        let home = tempfile::tempdir().unwrap();
        let project = tempfile::tempdir().unwrap();
        write_settings(home.path(), r#"{"defaultModel": "x/y"}"#);
        assert!(!steering_mode_configured(
            Some(home.path()),
            &project.path().display().to_string(),
        ));
        write_settings(home.path(), "not json");
        assert!(!steering_mode_configured(
            Some(home.path()),
            &project.path().display().to_string(),
        ));
    }
}
