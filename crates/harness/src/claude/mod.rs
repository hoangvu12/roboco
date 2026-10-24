//! Claude Code harness: spawns the installed `claude` CLI and speaks its
//! stream-json protocol directly — no adapter process in between. Resurrected
//! from the pre-ACP driver (see docs/research/harness.md) and modernized
//! against CLI 2.1.228.
//!
//! - stdout JSONL frames are normalized into [`AgentEvent`]s (init dedupe,
//!   subagent tagging, typed tool decoding, error-code mapping).
//! - PERMISSIONS ride the stdio control channel: `--permission-prompt-tool
//!   stdio` (undocumented — absent from `claude --help`, but it is the same
//!   transport the Claude Agent SDK's `query()` drives, and was re-validated
//!   live against 2.1.228: `can_use_tool` control requests arrive and
//!   allow/deny responses are honored). The alternative channel — an MCP
//!   permission tool — needs a server process and was rejected. Tool calls
//!   auto-allow (roboco sessions run unattended, parity with the ACP
//!   harness's preferred-allow behavior); `AskUserQuestion` round-trips
//!   through [`RunControls::request_input`].
//! - DONE is the CLI's own `result` frame, eagerly: background work (a
//!   spawned subagent) never holds the turn. The CLI natively runs a second
//!   wake turn when a background task finishes — a fresh `init` (same
//!   session id, deduped) plus another `result` — and both are forwarded;
//!   the engine's parked-session resume path turns them into the
//!   done→Working→done wake.
//! - SUBAGENT frames arrive on the same stdout tagged with a top-level
//!   `parent_tool_use_id`; they are wrapped in [`AgentEvent::Subagent`] and
//!   NEVER folded into the parent feed (a background subagent interleaves
//!   with the parent's own stream — folding them in split contiguous text
//!   around phantom tool calls).
//! - A `result` that is not a turn's end: every stdin user line carries a
//!   uuid, and the CLI reports each one's `command_lifecycle`. A result
//!   while one of ours is still written/queued is a boundary (a `now` steer
//!   aborting the turn, resume settling dead background tasks) — that
//!   message's turn ends with its own result. See [`Commands`].
//! - Steering: queued [`SteerMessage`]s are written to stdin as user lines at
//!   any time; the CLI folds them into the running turn at its own step
//!   boundary. A steer's changed model/effort/options apply to the live
//!   process first (`set_model`, `apply_flag_settings`) — never a restart.
//! - Turn stop ([`crate::TurnControl::stop_turn`]): the `interrupt` control
//!   request with `cancel_queued` ends the turn and the CLI keeps reading
//!   stdin. The run opens with an `initialize` declaring
//!   `perTaskStopAffordance`, which makes that interrupt spare background
//!   agents (the CLI otherwise stops them all); background shells survive
//!   either way. `background_tasks_changed` reports the live set to the host.
//! - Interrupt: cancelling [`RunControls::interrupt`] tears the runtime down —
//!   the interrupt control request, then SIGTERM and SIGKILL.
//! - TURN REFS (t3-orchestration-v2 ticket 18): every `result` closes its
//!   turn with a provider turn reference — the session id plus the uuid of
//!   the last top-level (untagged) assistant/user frame before it — so a
//!   fork can continue the provider session at exactly that turn.
//! - NATIVE FORK (same ticket): a fork's first run resumes the SOURCE
//!   session with `--fork-session` (plus the hidden
//!   `--resume-session-at <uuid>` when the boundary is an earlier turn,
//!   inclusive), giving the fork a NEW session id that carries the copied
//!   history — nothing is pasted. A CLI that rejects the flags (it exits
//!   before ever speaking), or a source session the CLI's store no longer
//!   has, falls back to a fresh session with the budgeted transcript: a
//!   fork never fails the send.

pub mod catalog;
mod discovery;
mod normalize;
mod wire;

use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use async_trait::async_trait;
use futures::StreamExt;
use futures::stream::BoxStream;
use serde_json::Value;
use tokio::io::{AsyncBufReadExt, AsyncReadExt as _, AsyncWriteExt, BufReader};
use tokio::sync::mpsc;

use roboco_proto::{
    AgentEvent, DoneStatus, HarnessId, Model, ReasoningLevel, RunRequest, SlashCommand,
    SteeringMode, UserInputAnswer, UserInputQuestion,
};

use crate::process::{Child, ChildStdin, Command, Stdio};
use crate::{Harness, HarnessError, RunControls, Signal, send_signal, shutdown_child};
use catalog::{apply_ultrathink, to_effort};
use normalize::Normalizer;
use wire::{ControlRequestFrame, Frame, allow_response, control_response_line};

/// Locate the device's installed Claude Code CLI: our own PATH, then the
/// login-shell PATH snapshot (the user's shell init shapes PATH in ways a
/// GUI/service launch never sees — see [`crate::shell_env`]), then known
/// install locations as a last resort. The `CLAUDE_CODE_EXECUTABLE` override
/// is applied by [`ClaudeHarness::resolve_executable`], so availability and
/// launches agree on one resolution order. Resolved per call — cheap after
/// the snapshot is cached.
fn resolve_claude_executable() -> Option<PathBuf> {
    let mut extra = Vec::new();
    if let Some(home) = crate::executable::home_dir() {
        extra.push(home.join(".claude").join("local").join("claude"));
        extra.push(home.join(".local").join("bin").join("claude"));
    }
    extra.push(PathBuf::from("/opt/homebrew/bin/claude"));
    extra.push(PathBuf::from("/usr/local/bin/claude"));
    crate::executable::find_on_paths("claude", extra)
}

/// The inline `--mcp-config` JSON for an injected server (the CLI accepts a
/// JSON string as well as a file path).
fn mcp_config_arg(mcp: &roboco_proto::McpServer) -> String {
    serde_json::json!({
        "mcpServers": {
            &mcp.name: {
                "command": mcp.command,
                "args": mcp.args,
                "env": mcp.env,
            }
        }
    })
    .to_string()
}

/// The launch probe's reading of the buffered stdout so far (B11/B12): an
/// errored first `result` BEFORE any assistant output refuses the launch
/// — the CLI accepted the flags but then failed the resumed session (a
/// `--resume-session-at` uuid validated late, or a session the account
/// can no longer read). Any assistant output accepts it (the launch is
/// serving the conversation). A clean `result` before any assistant
/// output also accepts: the launch itself is fine, whatever the empty
/// turn means. Only complete lines are judged — the tail of a partial
/// line rides to the session loop and is judged there.
enum LaunchReading {
    Undecided,
    Accepted,
    Refused,
}

fn launch_reading(head: &[u8]) -> LaunchReading {
    for line in std::str::from_utf8(head)
        .unwrap_or_default()
        .lines()
        .filter(|line| !line.trim().is_empty())
    {
        let Ok(value) = serde_json::from_str::<serde_json::Value>(line) else {
            continue;
        };
        match value.get("type").and_then(serde_json::Value::as_str) {
            Some("assistant") => return LaunchReading::Accepted,
            Some("result") => {
                let errored = value
                    .get("is_error")
                    .and_then(serde_json::Value::as_bool)
                    .unwrap_or(false)
                    || value.get("subtype").and_then(serde_json::Value::as_str) == Some("error");
                return if errored {
                    LaunchReading::Refused
                } else {
                    LaunchReading::Accepted
                };
            }
            _ => {}
        }
    }
    LaunchReading::Undecided
}

/// Bound on the launch probe (B11/B12): the probe buffers the stream until
/// the launch proves itself, so a wedged CLI must not hold it forever —
/// past the bound the buffered stream is handed over and the session loop
/// deals with whatever follows. Generous: a healthy launch speaks within
/// milliseconds; only Stop (the other escape) is faster.
const LAUNCH_PROBE_BOUND: Duration = Duration::from_secs(60);

/// Whether the CLI's own session store knows `session_id` — one
/// `<config>/projects/*/{session_id}.jsonl` file (the same walk
/// `Normalizer::for_resume` restores subagent chips from). A resume the
/// CLI would refuse is what triggers the account-failover fallback
/// (ticket 09): the driver starts a fresh session with the engine's
/// budgeted history instead of passing an id `--resume` will reject.
async fn session_known(config_root: &std::path::Path, session_id: &str) -> bool {
    if session_id.is_empty()
        || !session_id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-')
    {
        return false;
    }
    let config_root = config_root.to_owned();
    let session_id = session_id.to_owned();
    tokio::task::spawn_blocking(move || {
        let Ok(projects) = std::fs::read_dir(config_root.join("projects")) else {
            return false;
        };
        projects
            .flatten()
            .any(|project| project.path().join(format!("{session_id}.jsonl")).exists())
    })
    .await
    .unwrap_or(false)
}

/// The uuid of the LAST top-level message record (`assistant` or `user`,
/// no parent tool-use id, not a sidechain) in the CLI's own session file
/// for `session_id` — how a fork launch tells the session's LATEST turn
/// (`--fork-session` alone continues the whole session) from a boundary at
/// an EARLIER turn (the hidden `--resume-session-at` cuts it there,
/// inclusive). `None` when the file is missing, unreadable, or holds no
/// message records: the boundary is then treated as an earlier turn — the
/// flag combination the CLI may refuse, which is exactly what the fork's
/// budgeted-paste fallback covers.
async fn session_last_message_uuid(
    config_root: &std::path::Path,
    session_id: &str,
) -> Option<String> {
    use std::io::BufRead as _;
    if session_id.is_empty()
        || !session_id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-')
    {
        return None;
    }
    let config_root = config_root.to_owned();
    let session_id = session_id.to_owned();
    tokio::task::spawn_blocking(move || {
        let Ok(projects) = std::fs::read_dir(config_root.join("projects")) else {
            return None;
        };
        // Session ids are global; the project directory encoding does not
        // matter here (the same walk `session_known` and
        // `Normalizer::for_resume` use).
        let file = projects.flatten().find_map(|project| {
            std::fs::File::open(project.path().join(format!("{session_id}.jsonl"))).ok()
        })?;
        // Long sessions run to hundreds of MB: only lines that can carry a
        // uuid are parsed, off the runtime, and only the LAST top-level
        // message record is kept.
        let mut reader = std::io::BufReader::with_capacity(1 << 16, file);
        let mut line = Vec::new();
        let mut last = None;
        loop {
            line.clear();
            match reader.read_until(b'\n', &mut line) {
                Ok(0) | Err(_) => break,
                Ok(_) => {}
            }
            if !line.windows(6).any(|window| window == b"\"uuid\"") {
                continue;
            }
            let Ok(text) = std::str::from_utf8(&line) else {
                continue;
            };
            // Torn lines and older/newer unknown shapes are harmless.
            let Ok(record) = serde_json::from_str::<Value>(text) else {
                continue;
            };
            if !matches!(
                record.get("type").and_then(Value::as_str),
                Some("assistant") | Some("user")
            ) || record.get("isSidechain").and_then(Value::as_bool) == Some(true)
                || !record.get("parent_tool_use_id").is_none_or(Value::is_null)
            {
                continue;
            }
            if let Some(uuid) = record
                .get("uuid")
                .and_then(Value::as_str)
                .filter(|uuid| !uuid.is_empty())
            {
                last = Some(uuid.to_owned());
            }
        }
        last
    })
    .await
    .unwrap_or(None)
}

/// The `--model` value: the 1M context window is selected via a model-id
/// suffix (`sonnet[1m]`), exactly how the CLI itself does it.
fn model_arg(request: &RunRequest) -> Option<String> {
    let model = request.model.as_ref()?;
    let one_m = request
        .model_options
        .get("contextWindow")
        .and_then(Value::as_str)
        == Some("1m");
    Some(if one_m {
        format!("{model}[1m]")
    } else {
        model.clone()
    })
}

/// Every flag-layer setting a request launches with, for
/// `apply_flag_settings` on a live process: `null` drops the override back
/// to the user's own settings, exactly as a launch without the flag
/// (verified live on 2.1.286 through `get_settings`).
fn flag_settings(request: &RunRequest) -> Value {
    let on = |set: bool| if set { Value::Bool(true) } else { Value::Null };
    serde_json::json!({
        "effortLevel": to_effort(request.reasoning, request.model.as_deref()),
        "fastMode": on(option_is_on(&request.model_options, "fastMode")),
        "alwaysThinkingEnabled": on(option_is_on(&request.model_options, "thinking")),
        "ultracode": on(request.reasoning == Some(ReasoningLevel::Ultracode)),
    })
}

fn option_is_on(options: &serde_json::Map<String, Value>, key: &str) -> bool {
    match options.get(key) {
        Some(Value::Bool(b)) => *b,
        Some(Value::String(s)) => s == "on" || s == "true",
        _ => false,
    }
}

/// The Claude Code harness. Construct with [`ClaudeHarness::new`]; tests point
/// it at a fake CLI with [`ClaudeHarness::with_executable`].
pub struct ClaudeHarness {
    executable: Option<PathBuf>,
    /// Grace between the interrupt control request and SIGTERM.
    interrupt_grace: Duration,
    /// Grace between SIGTERM and SIGKILL.
    kill_grace: Duration,
    initialize: discovery::InitializeCache,
    models_cache: crate::catalog::Catalog,
    workspace_commands: crate::skills::CommandDiscovery,
    /// Test override for the CLI's config root — the directory whose
    /// `projects/` holds the session store the fork launch reads (a
    /// missing source session and the latest-turn boundary). Unset in
    /// production: `CLAUDE_CONFIG_DIR` (or `~/.claude`) applies.
    config_root: Option<PathBuf>,
}

impl Default for ClaudeHarness {
    fn default() -> Self {
        Self {
            executable: None,
            interrupt_grace: Duration::from_secs(2),
            kill_grace: Duration::from_secs(3),
            initialize: discovery::InitializeCache::default(),
            models_cache: crate::catalog::Catalog::default(),
            workspace_commands: crate::skills::CommandDiscovery::default(),
            config_root: None,
        }
    }
}

impl ClaudeHarness {
    pub fn new() -> Self {
        Self::default()
    }

    /// Use a fixed CLI binary instead of PATH/known-location resolution.
    pub fn with_executable(mut self, path: impl Into<PathBuf>) -> Self {
        self.executable = Some(path.into());
        self
    }

    /// Point the session store at a fixed config root instead of
    /// `CLAUDE_CONFIG_DIR` / `~/.claude` (tests drive the fork launch
    /// against a crafted store; the env var is process-global and would
    /// race parallel tests).
    pub fn with_config_root(mut self, root: impl Into<PathBuf>) -> Self {
        self.config_root = Some(root.into());
        self
    }

    /// Tune the interrupt→SIGTERM→SIGKILL escalation timing.
    pub fn with_graces(mut self, interrupt_grace: Duration, kill_grace: Duration) -> Self {
        self.interrupt_grace = interrupt_grace;
        self.kill_grace = kill_grace;
        self
    }

    fn resolve_executable(&self) -> Result<PathBuf, HarnessError> {
        if let Some(p) = &self.executable {
            return crate::executable::validate_native_override(p);
        }
        if let Some(p) = std::env::var_os("CLAUDE_CODE_EXECUTABLE")
            && !p.is_empty()
        {
            return crate::executable::validate_native_override(&PathBuf::from(p));
        }
        resolve_claude_executable().ok_or_else(|| {
            HarnessError::NotInstalled(
                "claude (searched PATH, the login shell's PATH, ~/.claude/local, \
                 ~/.local/bin, /opt/homebrew/bin, /usr/local/bin, and \
                 fnm/nvm/volta/pnpm/bun install dirs; Windows also checks USERPROFILE \
                 and explicit NVM_SYMLINK/VOLTA_HOME/PNPM_HOME; set \
                 CLAUDE_CODE_EXECUTABLE to override)"
                    .into(),
            )
        })
    }

    /// The configuration arguments a run launches with — everything the
    /// process bakes in at spawn except the session to resume. Two requests
    /// with equal arguments (and cwd) are the same runtime.
    fn launch_args(request: &RunRequest) -> Vec<String> {
        let mut args: Vec<String> = [
            "--print",
            "--input-format",
            "stream-json",
            "--output-format",
            "stream-json",
            // Required by the CLI alongside `-p --output-format stream-json`.
            "--verbose",
            "--include-partial-messages",
            // Newer Claude models emit no readable thinking text unless a
            // summary is asked for (raw reasoning stays provider-private).
            "--thinking-display",
            "summarized",
            // Route permission prompts to the stdio control channel so
            // `can_use_tool` (and AskUserQuestion in particular) reaches us.
            // Undocumented flag; validated live against 2.1.228.
            "--permission-prompt-tool",
            "stdio",
        ]
        .map(str::to_owned)
        .into();
        if let Some(model) = model_arg(request) {
            args.push("--model".into());
            args.push(model);
        }
        if let Some(effort) = to_effort(request.reasoning, request.model.as_deref()) {
            args.extend(["--effort".into(), effort.into()]);
        }
        if request.auto_approve {
            args.extend(
                [
                    "--permission-mode",
                    "bypassPermissions",
                    "--dangerously-skip-permissions",
                ]
                .map(str::to_owned),
            );
        } else {
            args.extend(["--permission-mode".into(), "default".into()]);
        }
        let mut settings = serde_json::Map::new();
        if option_is_on(&request.model_options, "fastMode") {
            settings.insert("fastMode".into(), Value::Bool(true));
        }
        if option_is_on(&request.model_options, "thinking") {
            settings.insert("alwaysThinkingEnabled".into(), Value::Bool(true));
        }
        if request.reasoning == Some(ReasoningLevel::Ultracode) {
            settings.insert("ultracode".into(), Value::Bool(true));
        }
        if !settings.is_empty() {
            args.push("--settings".into());
            args.push(Value::Object(settings).to_string());
        }
        args
    }

    fn build_command(&self, exe: &PathBuf, request: &RunRequest) -> Command {
        let mut cmd = Command::new(exe);
        crate::compose_child_path(&mut cmd, exe);
        // Crash ownership: the kernel kills this CLI if the engine dies.
        crate::process::dies_with_parent(&mut cmd);
        cmd.args(Self::launch_args(request));
        if let Some(resume) = &request.resume {
            cmd.arg(format!("--resume={resume}"));
        }
        if !request.cwd.is_empty() {
            cmd.current_dir(&request.cwd);
        }
        cmd.stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        cmd
    }

    /// Share the complete initialize response between model and command discovery.
    /// No user message is written; the short-lived child is retired after initialize.
    async fn initialize(&self) -> Result<Value, HarnessError> {
        self.initialize
            .get(
                || self.model_context().map(|c| c.unwrap().key()),
                || self.probe_initialize(None),
            )
            .await
    }

    async fn discover_commands(
        &self,
        cwd: Option<&std::path::Path>,
    ) -> Result<Vec<SlashCommand>, HarnessError> {
        let response = match cwd {
            Some(cwd) => self.probe_initialize(Some(cwd)).await?,
            None => self.initialize().await?,
        };
        Ok(parse_initialize_commands(&response))
    }

    async fn probe_initialize(&self, cwd: Option<&std::path::Path>) -> Result<Value, HarnessError> {
        let exe = self.resolve_executable()?;
        let mut cmd = Command::new(&exe);
        if let Some(cwd) = cwd {
            cmd.current_dir(cwd);
        }
        crate::compose_child_path(&mut cmd, &exe);
        crate::process::dies_with_parent(&mut cmd);
        cmd.args([
            "--print",
            "--input-format",
            "stream-json",
            "--output-format",
            "stream-json",
            // Mandatory with --print + stream-json output; without it the
            // CLI exits immediately with a usage error.
            "--verbose",
        ]);
        cmd.stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .kill_on_drop(true);
        let mut child = cmd.spawn().map_err(|e| {
            if e.kind() == std::io::ErrorKind::NotFound {
                HarnessError::NotInstalled(crate::executable::binary_hint(&exe))
            } else {
                HarnessError::Io(e)
            }
        })?;
        let (Some(mut stdin), Some(stdout)) = (child.stdin.take(), child.stdout.take()) else {
            shutdown_child(&mut child, self.kill_grace).await;
            return Err(HarnessError::Protocol("claude child has no stdio".into()));
        };
        const PROBE_ID: &str = "roboco-command-probe";
        let discovery = async {
            let request = serde_json::json!({
                "type": "control_request",
                "request_id": PROBE_ID,
                "request": { "subtype": "initialize" },
            });
            stdin
                .write_all(format!("{request}\n").as_bytes())
                .await
                .map_err(HarnessError::Io)?;
            stdin.flush().await.map_err(HarnessError::Io)?;
            let mut lines = BufReader::new(stdout).lines();
            while let Some(line) = lines.next_line().await.map_err(HarnessError::Io)? {
                let Ok(frame) = serde_json::from_str::<Value>(&line) else {
                    continue;
                };
                if frame.get("type").and_then(Value::as_str) != Some("control_response") {
                    continue;
                }
                let response = frame.get("response").cloned().unwrap_or(Value::Null);
                if response.get("request_id").and_then(Value::as_str) != Some(PROBE_ID) {
                    continue;
                }
                if response.get("subtype").and_then(Value::as_str) == Some("error") {
                    let msg = response
                        .get("error")
                        .and_then(Value::as_str)
                        .unwrap_or("initialize control request failed");
                    return Err(HarnessError::Protocol(msg.into()));
                }
                return Ok(response);
            }
            Err(HarnessError::Protocol(
                "claude exited before answering the initialize control request".into(),
            ))
        };
        let result = tokio::time::timeout(Duration::from_secs(10), discovery).await;
        shutdown_child(&mut child, self.kill_grace).await;
        match result {
            Ok(inner) => inner,
            Err(_) => Err(HarnessError::Protocol("Claude initialize timed out".into())),
        }
    }
}

/// `commands` out of an `initialize` control_response payload
/// (`response.response.commands`: name / description / argumentHint).
fn parse_initialize_commands(response: &Value) -> Vec<SlashCommand> {
    response
        .get("response")
        .and_then(|r| r.get("commands"))
        .and_then(Value::as_array)
        .map(|a| a.as_slice())
        .unwrap_or_default()
        .iter()
        .filter_map(|c| {
            let name = c.get("name").and_then(Value::as_str)?.trim();
            if name.is_empty() {
                return None;
            }
            Some(SlashCommand {
                name: name.to_owned(),
                description: c
                    .get("description")
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_owned(),
                input_hint: c
                    .get("argumentHint")
                    .and_then(Value::as_str)
                    .map(str::trim)
                    .filter(|h| !h.is_empty())
                    .map(str::to_owned),
            })
        })
        .collect()
}

#[async_trait]
impl Harness for ClaudeHarness {
    fn id(&self) -> HarnessId {
        HarnessId::ClaudeCode
    }
    fn display_name(&self) -> &str {
        "Claude Code"
    }
    fn supports_steering(&self) -> bool {
        true
    }
    fn steering_mode(&self) -> SteeringMode {
        SteeringMode::StepBoundary
    }
    fn reasoning_levels(&self) -> &[ReasoningLevel] {
        &[
            ReasoningLevel::Low,
            ReasoningLevel::Medium,
            ReasoningLevel::High,
            ReasoningLevel::XHigh,
            ReasoningLevel::Max,
        ]
    }
    fn installed(&self) -> bool {
        // The launch resolver, not bare discovery: a valid CLAUDE_CODE_EXECUTABLE
        // (or a test `executable`) must report installed, and an invalid one
        // must not — availability and launches share one resolution.
        self.resolve_executable().is_ok()
    }
    fn executable_path(&self) -> Option<std::path::PathBuf> {
        self.resolve_executable().ok()
    }
    /// Done is the CLI's own terminal frame, for wake turns too.
    fn deterministic_turn_end(&self) -> bool {
        true
    }
    /// The `interrupt` control request ends the turn and the CLI keeps
    /// reading stdin; background agents and shells run on.
    fn stops_turn_in_place(&self) -> bool {
        true
    }
    /// Same launch arguments and cwd. The CLI ignores the sandbox level, and
    /// option spellings that launch the same flags are the same runtime.
    fn same_runtime(&self, live: &RunRequest, next: &RunRequest) -> bool {
        live.cwd == next.cwd && Self::launch_args(live) == Self::launch_args(next)
    }
    /// Model, context window, effort, fast mode, thinking and ultracode all
    /// change on the live process (`set_model`, `apply_flag_settings`). Only
    /// the directory and the permission mode it launched with need a new one.
    fn reconfigures_in_place(&self, live: &RunRequest, next: &RunRequest) -> bool {
        live.cwd == next.cwd && live.auto_approve == next.auto_approve
    }

    /// Credential and executable identity scopes both initialize and catalog caches.
    fn model_context(&self) -> Result<Option<crate::ModelContext>, HarnessError> {
        crate::model_context::context(self.id(), &self.resolve_executable()?, &[]).map(Some)
    }
    fn fallback_models(&self) -> Vec<Model> {
        catalog::configured_models()
    }
    async fn model_catalog(&self, force: bool) -> Result<crate::ModelCatalog, HarnessError> {
        self.model_context()?.unwrap().log();
        self.models_cache
            .get_with_timeout(
                force,
                Duration::from_secs(35),
                || self.model_context().map(|c| c.unwrap().key()),
                || async {
                    let response = self.initialize().await?;
                    catalog::with_discovered_models(catalog::configured_models(), &response)
                },
            )
            .await
    }
    async fn models(&self) -> Result<Vec<Model>, HarnessError> {
        self.resolve_executable()?;
        match self.model_catalog(false).await {
            Ok(catalog) => Ok(catalog.models),
            Err(error) => {
                tracing::warn!(%error, source = "static", "Claude model discovery failed");
                Ok(self.fallback_models())
            }
        }
    }

    /// Slash commands from the CLI's `initialize` control-request handshake —
    /// the same channel the Claude Agent SDK's `query()` opens. The response
    /// carries every command with description + argument hint and involves no
    /// model turn (verified live, 2.1.228: the control_response is the first
    /// stdout line, well before any API traffic). Cached on success.
    async fn skills(
        &self,
        cwd: &std::path::Path,
    ) -> Result<Option<Vec<roboco_proto::invocation::Skill>>, HarnessError> {
        let (skills, commands) = tokio::try_join!(
            crate::skills::discover(self.id(), cwd),
            self.workspace_commands
                .get(cwd, self.discover_commands(Some(cwd)))
        )?;
        // The native advertised catalog controls availability (including plugin
        // enablement and skillOverrides). Shared Agent Skills can use file delivery.
        Ok(Some(
            skills
                .into_iter()
                .filter_map(|mut skill| {
                    if crate::skills::is_shared_skill(&skill.path) {
                        // Shared files are not Claude command definitions. A
                        // same-named built-in must not replace their identity.
                        Some(skill)
                    } else if roboco_proto::invocation::valid_skill_command_name(&skill.name)
                        && commands.iter().any(|command| command.name == skill.name)
                    {
                        skill.command = Some(roboco_proto::invocation::SkillCommand {
                            name: skill.name.clone(),
                            harness: self.id(),
                        });
                        Some(skill)
                    } else {
                        None
                    }
                })
                .collect(),
        ))
    }

    async fn commands(&self) -> Result<Vec<SlashCommand>, HarnessError> {
        self.discover_commands(None).await
    }

    async fn commands_for(&self, cwd: &std::path::Path) -> Result<Vec<SlashCommand>, HarnessError> {
        self.workspace_commands
            .get(cwd, self.discover_commands(Some(cwd)))
            .await
    }

    async fn run(
        &self,
        request: RunRequest,
        controls: RunControls,
    ) -> Result<BoxStream<'static, Result<AgentEvent, HarnessError>>, HarnessError> {
        self.run_with_mode(request, controls, false).await
    }

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

impl ClaudeHarness {
    async fn run_with_mode(
        &self,
        mut request: RunRequest,
        controls: RunControls,
        title_only: bool,
    ) -> Result<BoxStream<'static, Result<AgentEvent, HarnessError>>, HarnessError> {
        let exe = self.resolve_executable()?;
        let config_root = self.config_root.clone().unwrap_or_else(|| {
            crate::model_context::root(
                "CLAUDE_CONFIG_DIR",
                crate::executable::home_or_current_dir().join(".claude"),
            )
        });
        // A resume the CLI will REFUSE — its session store has no such id —
        // starts a fresh session instead when the engine attached a
        // fallback history (the account-failover continuation, ticket 09):
        // the budgeted transcript rides in front of the prompt, so the
        // fresh session still knows the conversation. Plain resumes carry
        // no fallback and keep today's behavior: pass `--resume` and let
        // the CLI report the bad id itself.
        if let (Some(resume), Some(history)) = (
            request.resume.clone(),
            request.resume_fallback_history.clone(),
        ) && !session_known(&config_root, &resume).await
        {
            tracing::debug!(
                target: "roboco_harness::claude",
                "resume of {resume} has no session file; starting fresh with the budgeted history"
            );
            request.resume = None;
            request.prompt = crate::prompt_with_history(&history, &request.prompt);
        }
        // A native fork (t3-orchestration-v2 ticket 18): the CLI continues
        // the SOURCE session as a NEW session id — `--resume <source>
        // --fork-session` — so the copied history rides the provider and
        // nothing is pasted. A boundary at an earlier turn also passes the
        // hidden `--resume-session-at <uuid>` (inclusive); the latest turn
        // needs no cut, and the unverified flag combination is exactly
        // what the fallback below covers. A source session the CLI's store
        // no longer has skips the fork outright; a CLI that REJECTS the
        // flags — it exits before it ever speaks — retries once as a fresh
        // session with the budgeted transcript in front of the prompt. A
        // fork never fails the send.
        let mut fork_flags: Option<Vec<String>> = None;
        // Only set when the fork launch is ATTEMPTED — the budgeted
        // transcript the retry pastes if the CLI rejects the flags. A
        // missing session never attempts; its fallback pastes at once,
        // exactly like the refused resume above.
        let mut fork_fallback: Option<String> = None;
        if let Some(fork) = request.provider_fork.clone() {
            if session_known(&config_root, &fork.session_id).await {
                // The source session doubles as the launch's resume id, and
                // the restored spawn chips (subagent transcript tabs) come
                // from its history — the forked copy continues it.
                request.resume = Some(fork.session_id.clone());
                let latest = session_last_message_uuid(&config_root, &fork.session_id).await;
                let mut flags = vec!["--fork-session".to_string()];
                if latest.as_deref() != Some(fork.last_turn.as_str()) {
                    flags.push(format!("--resume-session-at={}", fork.last_turn));
                }
                fork_flags = Some(flags);
                fork_fallback = fork.fallback_history;
            } else {
                tracing::debug!(
                    target: "roboco_harness::claude",
                    "fork source {} has no session file; starting fresh with the budgeted history",
                    fork.session_id
                );
                request.provider_fork = None;
                if let Some(history) = fork.fallback_history {
                    request.prompt = crate::prompt_with_history(&history, &request.prompt);
                }
            }
        }
        let images = load_image_blocks(&request.attachments).await;
        // One launch — or two, when the CLI refuses the fork flags: the
        // second drops every session flag and pastes the budgeted
        // transcript (the fallback the engine attached for exactly this
        // refusal).
        for attempt in 0..2 {
            if attempt == 1 {
                request.resume = None;
                request.provider_fork = None;
                fork_flags = None;
                if let Some(history) = fork_fallback.take() {
                    request.prompt = crate::prompt_with_history(&history, &request.prompt);
                }
                tracing::debug!(
                    target: "roboco_harness::claude",
                    "the CLI refused the fork launch; starting fresh with the budgeted history"
                );
            }
            let mut cmd = self.build_command(&exe, &request);
            if let Some(flags) = &fork_flags {
                cmd.args(flags.clone());
            }
            if title_only {
                cmd.args([
                    "--system-prompt",
                    crate::TITLE_INSTRUCTIONS,
                    "--tools",
                    "",
                    "--strict-mcp-config",
                    "--mcp-config",
                    "{\"mcpServers\":{}}",
                    "--setting-sources",
                    "",
                ]);
            } else if let Some(mcp) = &request.mcp {
                // Roboco's own server rides beside the user's configured servers
                // (no `--strict-mcp-config`): the CLI merges an inline JSON
                // config with settings-sourced ones.
                cmd.args(["--mcp-config", &mcp_config_arg(mcp)]);
            }
            let normalizer = if let Some(session_id) = &request.resume {
                Normalizer::for_resume(&config_root, session_id).await
            } else {
                Normalizer::new()
            };
            crate::process::dies_with_parent(&mut cmd);
            let mut child = cmd.spawn().map_err(|e| {
                if e.kind() == std::io::ErrorKind::NotFound {
                    HarnessError::NotInstalled(crate::executable::binary_hint(&exe))
                } else {
                    HarnessError::Io(e)
                }
            })?;

            let stdin = child
                .stdin
                .take()
                .ok_or_else(|| HarnessError::Protocol("claude child has no stdin".into()))?;
            let mut stdout = child
                .stdout
                .take()
                .ok_or_else(|| HarnessError::Protocol("claude child has no stdout".into()))?;
            let stderr_tail = crate::StderrTail::default();
            if let Some(stderr) = child.stderr.take() {
                let tail = stderr_tail.clone();
                tokio::spawn(async move {
                    let mut lines = BufReader::new(stderr).lines();
                    while let Ok(Some(line)) = lines.next_line().await {
                        tracing::debug!(target: "roboco_harness::claude", "stderr: {line}");
                        tail.push(&line);
                    }
                });
            }

            let (stdin_tx, stdin_rx) = mpsc::unbounded_channel::<StdinMsg>();
            tokio::spawn(stdin_writer(stdin, stdin_rx));
            if !title_only {
                // The opening control request: `perTaskStopAffordance` tells the
                // CLI this host stops tasks individually, so an interrupt ends
                // the turn and spares background agents.
                let _ = stdin_tx.send(StdinMsg::Line(wire::initialize_request_line(
                    "roboco_initialize",
                )));
            }

            // The first prompt as the first stdin user line (streaming-input
            // mode) — sent BEFORE the launch probe below: a CLI that lazily
            // validates its resume flags fails them only once a prompt
            // arrives, so the probe must be reading when that result lands.
            // Ultrathink rides every user message — steers included.
            // Staged image attachments are inlined as base64 image content blocks
            // ahead of the text (verified against the real CLI); their path refs
            // also ride the prompt text, so a skipped/unreadable file degrades to
            // the old-app behavior (the agent opens the path with its Read tool).
            // The line carries a uuid: the CLI then reports its
            // `command_lifecycle`, which decides when a `result` is a steer
            // boundary instead of the turn's end. A refused launch's retry
            // delivers the budgeted transcript here — it is already in front
            // of the prompt.
            let first_command = uuid::Uuid::new_v4().to_string();
            let first = wire::prompt_line(
                &apply_ultrathink(request.reasoning, &request.prompt),
                &first_command,
                &images,
            );
            let _ = stdin_tx.send(StdinMsg::Line(first));

            // The launch's acceptance probe (ticket 18): the CLI proves the
            // launch as it speaks — anything it writes is buffered in `head`
            // and handed to the session loop unchanged, so nothing is lost.
            // The probe reads until one of:
            // - any ASSISTANT output: the launch is good — hand over.
            // - an ERRORED first `result` BEFORE any assistant output: the
            //   CLI accepted the flags but then failed the resume — a bad
            //   `--resume-session-at` uuid it validates late (B11), or a
            //   resumed session the account can no longer read (B12: the
            //   failover continuation's cross-account resume). Same
            //   refusal as silence: the retry below starts fresh with the
            //   budgeted transcript, because a fork/resume never fails the
            //   send.
            // - silence: EOF before the CLI ever spoke — the flags were
            //   rejected up front (the original ticket-18 shape).
            // - Stop mid-probe: hand the child to the session loop, which
            //   sees the token and ends the run interrupted.
            // Every launch the probe covers carries a fallback history the
            // retry pastes: a fork (the engine's budgeted transcript), or a
            // resume with `resume_fallback_history` (the failover
            // continuation). Plain launches and titles are handed over
            // unread, as before.
            let probe_launch = !title_only
                && (fork_flags.is_some()
                    || (request.resume.is_some() && request.resume_fallback_history.is_some()));
            let mut head: Vec<u8> = Vec::new();
            if probe_launch {
                enum Probe {
                    Accepted,
                    Silent,
                    Refused,
                    Interrupted,
                }
                let launch_fallback = fork_fallback
                    .take()
                    .or_else(|| request.resume_fallback_history.take());
                let mut probe = [0u8; 1024];
                let bound = tokio::time::Instant::now() + LAUNCH_PROBE_BOUND;
                let outcome = loop {
                    tokio::select! {
                        biased;
                        _ = controls.interrupt.cancelled() => break Probe::Interrupted,
                        _ = tokio::time::sleep_until(bound) => break Probe::Accepted,
                        read = stdout.read(&mut probe) => match read {
                            Ok(0) | Err(_) => {
                                break if head.is_empty() {
                                    Probe::Silent
                                } else {
                                    Probe::Accepted
                                }
                            }
                            Ok(n) => {
                                head.extend_from_slice(&probe[..n]);
                                match launch_reading(&head) {
                                    LaunchReading::Refused => break Probe::Refused,
                                    // Assistant output spoke for the launch:
                                    // hand the buffered stream over.
                                    LaunchReading::Accepted => break Probe::Accepted,
                                    LaunchReading::Undecided => {}
                                }
                            }
                        },
                    }
                };
                match outcome {
                    // Interrupted: the fallback stays unspent; the session
                    // loop ends the run interrupted.
                    Probe::Accepted | Probe::Interrupted => {}
                    Probe::Silent | Probe::Refused => {
                        shutdown_child(&mut child, self.kill_grace).await;
                        request.resume = None;
                        request.provider_fork = None;
                        fork_flags = None;
                        if let Some(history) = launch_fallback {
                            request.prompt = crate::prompt_with_history(&history, &request.prompt);
                        }
                        tracing::debug!(
                            target: "roboco_harness::claude",
                            "the CLI refused the launch (silent, or an error before any \
                             assistant output); starting fresh with the budgeted history"
                        );
                        continue;
                    }
                }
            }

            // The probe's consumed byte rides ahead of the pipe (no output
            // is lost); an empty head is every unprobed launch.
            let stdout_lines = BufReader::new(std::io::Cursor::new(head).chain(stdout)).lines();
            let (event_tx, event_rx) = mpsc::channel::<Result<AgentEvent, HarnessError>>(256);
            tokio::spawn(run_session(Session {
                normalizer,
                title_only,
                child,
                stdout_lines,
                first_command,
                stdin_tx,
                event_tx,
                controls,
                config: request,
                interrupt_grace: self.interrupt_grace,
                kill_grace: self.kill_grace,
                stderr_tail,
            }));

            return Ok(futures::stream::unfold(event_rx, |mut rx| async move {
                rx.recv().await.map(|ev| (ev, rx))
            })
            .boxed());
        }
        unreachable!("the launch loop returns (or errors) on its final pass")
    }
}

enum StdinMsg {
    Line(String),
    /// Close stdin (end of steering input): the CLI finishes the current turn
    /// and exits, which ends the run stream at stdout EOF.
    Close,
}

/// Anthropic's API caps inline images at 5MB of raw bytes; larger files stay
/// path refs only.
const MAX_INLINE_IMAGE_BYTES: u64 = 5 * 1024 * 1024;

/// Media type for an inline image block — extension first, magic bytes as the
/// fallback (pasted screenshots may carry odd names). Only the API-supported
/// inline types map; anything else (svg/bmp/tiff/…) returns `None`.
fn image_media_type(path: &std::path::Path, bytes: &[u8]) -> Option<&'static str> {
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

/// Load `RunRequest::attachments` into inline image blocks, best-effort: an
/// unreadable, oversized, or unsupported file is skipped — its path ref still
/// rides the prompt text — never fatal to the run.
async fn load_image_blocks(paths: &[String]) -> Vec<wire::ImageBlock> {
    use base64::Engine as _;
    let mut blocks = Vec::new();
    for path in paths {
        let bytes = match tokio::fs::read(path).await {
            Ok(bytes) => bytes,
            Err(err) => {
                tracing::warn!(target: "roboco_harness::claude", %path, error = %err, "attachment unreadable; path ref only");
                continue;
            }
        };
        if bytes.len() as u64 > MAX_INLINE_IMAGE_BYTES {
            tracing::debug!(target: "roboco_harness::claude", %path, "attachment over inline cap; path ref only");
            continue;
        }
        let Some(media_type) = image_media_type(std::path::Path::new(path), &bytes) else {
            tracing::debug!(target: "roboco_harness::claude", %path, "attachment not an inline-supported image; path ref only");
            continue;
        };
        blocks.push(wire::ImageBlock {
            media_type: media_type.to_string(),
            data: base64::engine::general_purpose::STANDARD.encode(&bytes),
        });
    }
    blocks
}

/// Owns the child's stdin; a write failure (EPIPE after the child died) is
/// tolerated and logged.
async fn stdin_writer(mut stdin: ChildStdin, mut rx: mpsc::UnboundedReceiver<StdinMsg>) {
    while let Some(msg) = rx.recv().await {
        match msg {
            StdinMsg::Line(line) => {
                let write = async {
                    stdin.write_all(line.as_bytes()).await?;
                    stdin.write_all(b"\n").await?;
                    stdin.flush().await
                };
                if let Err(e) = write.await {
                    tracing::debug!(target: "roboco_harness::claude", "stdin write failed (tolerated): {e}");
                    return;
                }
            }
            StdinMsg::Close => {
                let _ = stdin.shutdown().await;
                return;
            }
        }
    }
}

/// The session's stdout line source: the fork probe's consumed lead byte
/// (chained back ahead of the pipe, so no output is lost) then the child's
/// stdout. The head is empty for every launch that was never probed — a
/// plain, resumed, or title launch behaves exactly as before.
type StdoutLines = tokio::io::Lines<
    BufReader<tokio::io::Chain<std::io::Cursor<Vec<u8>>, crate::process::ChildStdout>>,
>;

struct Session {
    normalizer: Normalizer,
    title_only: bool,
    child: Child,
    stdout_lines: StdoutLines,
    /// The uuid the opening prompt was written with.
    first_command: String,
    stdin_tx: mpsc::UnboundedSender<StdinMsg>,
    event_tx: mpsc::Sender<Result<AgentEvent, HarnessError>>,
    controls: RunControls,
    /// The configuration the process runs with now: its launch request, then
    /// whatever a steer's `config` applied in place.
    config: RunRequest,
    interrupt_grace: Duration,
    kill_grace: Duration,
    /// Rolling stderr tail for the crash message on an unexpected exit.
    stderr_tail: crate::StderrTail,
}

/// The per-run event loop: one task multiplexing stdout frames, the steering
/// mailbox, the interrupt token, and consumer liveness.
async fn run_session(session: Session) {
    let Session {
        normalizer: mut norm,
        title_only,
        mut child,
        mut stdout_lines,
        first_command,
        stdin_tx,
        event_tx,
        controls,
        mut config,
        interrupt_grace,
        kill_grace,
        stderr_tail,
    } = session;
    let RunControls {
        execution_lease: _execution_lease,
        request_input,
        mut steering,
        interrupt,
        turn,
    } = controls;
    let request_input = Arc::new(request_input);

    let mut pending_steers = std::collections::VecDeque::new();
    let mut reconfigures = 0u64;
    // Top-level tool calls in flight: a steer must not abort them (see
    // `wire::steer_message_line`).
    let mut open_tools = std::collections::HashSet::new();
    let mut steering_open = true;
    let mut interrupted = false;
    let mut interrupt_sent = false;
    let mut any_done = false;
    // A turn stop in flight (`TurnControl::stop_turn`): its request id, and
    // once the CLI has answered, how long a turn-ending `result` may still
    // take. The CLI answers a stop between turns without any result — the
    // deadline settles that shape so the host never waits on it.
    const STOP_SETTLE: Duration = Duration::from_secs(3);
    let mut stops_sent = 0u64;
    let mut stopping: Option<String> = None;
    let mut stop_settle_at: Option<tokio::time::Instant> = None;
    // A turn end held for steers when the stop came: that turn's end, sent
    // as stopped once the stop settles — never before. Released early, the
    // host started the replacement prompt while the stopped turn could still
    // report, and that late result settled the replacement.
    let mut stopped_end: Option<AgentEvent> = None;
    // When the stopped turn's one Done went out, and whether anything has
    // happened since — a message of ours written, or new output streamed. A
    // result in that window with neither can only be the stopped turn
    // reporting late, and is dropped. Anything else is reconciled like any
    // result (held for unconfirmed messages, settled by their fallback):
    // dropping on time alone lost a quick replacement's own end.
    let mut stop_done_at: Option<tokio::time::Instant> = None;
    let mut active_since_stop = false;
    let mut commands = Commands::default();
    commands.written(first_command);
    // A `result` that is a steer boundary rather than the turn's end (see
    // [`Commands`]). It is superseded — dropped — once the waiting message
    // starts its own turn, which ends with its own result; it is released
    // as the turn's end if that message is cancelled before it starts.
    let mut held_done: Option<AgentEvent> = None;
    // Last stdout activity, for the held result's settle fallback.
    let mut last_frame_at = tokio::time::Instant::now();
    let mut done_after_interrupt = false;
    let mut escalation: Option<tokio::task::JoinHandle<()>> = None;
    // The runtime's process tree when its teardown began (see
    // [`crate::shutdown_agent`]).
    #[cfg_attr(not(unix), allow(unused_mut))]
    let mut torn_down: Vec<i32> = Vec::new();

    'main: loop {
        tokio::select! {
            line = stdout_lines.next_line() => match line {
                Ok(Some(line)) => {
                    let line = line.trim();
                    if line.is_empty() {
                        continue;
                    }
                    // The CLI is still producing: whatever it is doing is not
                    // the quiet end a held turn end's fallback waits for.
                    last_frame_at = tokio::time::Instant::now();
                    let frame = match wire::parse_frame(line) {
                        Ok(frame) => frame,
                        Err(e) => {
                            tracing::debug!(target: "roboco_harness::claude", "unparseable frame (skipped): {e}");
                            continue;
                        }
                    };
                    if let Frame::ControlResponse(response) = &frame {
                        if stopping.as_deref() == Some(response.response.request_id.as_str()) {
                            stop_settle_at = Some(tokio::time::Instant::now() + STOP_SETTLE);
                        }
                        continue;
                    }
                    if let Frame::System(system) = &frame
                        && system.subtype == "background_tasks_changed"
                    {
                        turn.set_background(system.tasks.as_ref().map_or(0, Vec::len));
                    }
                    if let Frame::ControlRequest(req) = frame {
                        if title_only {
                            let line = control_response_line(&req.request_id, serde_json::json!({
                                "behavior": "deny", "message": "Tools are disabled for title generation"
                            }));
                            let _ = stdin_tx.send(StdinMsg::Line(line));
                        } else {
                            handle_control_request(req, &request_input, &stdin_tx);
                        }
                        continue;
                    }
                    // Only the CLI confirms that a prompt joined its
                    // conversation — its lifecycle or its replay, whichever
                    // comes first. Writing stdin must not split ongoing text.
                    // A confirmation covers its steer and every earlier one:
                    // superseded steers are never replayed themselves. A
                    // message a turn took up (started, or replayed) also
                    // supersedes a result held for it: that result was only
                    // the boundary, and the message's turn ends with its own.
                    let (confirmed, taken_up) = match &frame {
                        Frame::CommandLifecycle(cmd) => {
                            let started = commands.lifecycle(&cmd.command_uuid, &cmd.state);
                            let confirmed = started || Commands::is_terminal(&cmd.state);
                            (confirmed.then(|| cmd.command_uuid.clone()), started)
                        }
                        Frame::User(user) if user.parent_tool_use_id.is_none() => {
                            let replayed = user.uuid.clone().filter(|id| pending_steers.contains(id));
                            let taken_up = replayed.is_some();
                            (replayed, taken_up)
                        }
                        _ => (None, false),
                    };
                    if taken_up {
                        held_done = None;
                        active_since_stop = true;
                    }
                    if let Some(at) = confirmed
                        .and_then(|id| pending_steers.iter().position(|p| *p == id))
                    {
                        for _ in 0..=at {
                            pending_steers.pop_front();
                            let (prev, next) = norm.rotate_for_steer();
                            if event_tx.send(Ok(AgentEvent::Steered {
                                assistant_message_id: Some(prev), next_assistant_message_id: Some(next),
                            })).await.is_err() { break 'main; }
                        }
                    }
                    // Every waiting message was cancelled before it started:
                    // the held result was the turn's real end after all.
                    if matches!(frame, Frame::CommandLifecycle(_))
                        && !commands.waiting()
                        && let Some(done) = held_done.take()
                    {
                        if event_tx.send(Ok(done)).await.is_err() {
                            break 'main;
                        }
                        any_done = true;
                    }
                    // The stopped turn reporting late: a result with nothing
                    // since the stop's Done. Judged on the frame, before it
                    // expands — its own Usage is not new activity — and
                    // dropped whole, usage included.
                    if matches!(frame, Frame::Result(_))
                        && !interrupted
                        && stopping.is_none()
                        && !active_since_stop
                        && stop_done_at.is_some_and(|at| at.elapsed() < STOP_SETTLE)
                    {
                        tracing::debug!(
                            target: "roboco_harness::claude",
                            "late result of a stopped turn dropped"
                        );
                        continue;
                    }
                    for ev in norm.normalize(frame, interrupted || stopping.is_some()) {
                        match &ev {
                            AgentEvent::ToolCall { id, .. } => {
                                open_tools.insert(id.clone());
                            }
                            AgentEvent::ToolResult { id, .. } => {
                                open_tools.remove(id);
                            }
                            AgentEvent::Done { .. } => open_tools.clear(),
                            _ => {}
                        }
                        let is_done = matches!(ev, AgentEvent::Done { .. });
                        // A `now` steer ends the turn it interrupts with a
                        // result frame, and a message queued behind a turn
                        // that just ended starts another: either way that
                        // result is a steer boundary, not the end of the turn.
                        let boundary = if commands.reported {
                            commands.waiting()
                        } else {
                            !pending_steers.is_empty()
                        };
                        // New content is a new turn's: its result is its own.
                        if stop_done_at.is_some()
                            && match &ev {
                                AgentEvent::TextDelta { text }
                                | AgentEvent::ReasoningDelta { text } => !text.is_empty(),
                                AgentEvent::ToolCall { .. }
                                | AgentEvent::ToolResult { .. }
                                | AgentEvent::InputRequested { .. }
                                | AgentEvent::Subagent { .. } => true,
                                _ => false,
                            }
                        {
                            active_since_stop = true;
                        }
                        if is_done && !interrupted && stopping.is_none() && boundary {
                            held_done = Some(ev);
                            continue;
                        }
                        if is_done {
                            held_done = None;
                            // The stopped turn has ended; the runtime lives on.
                            if stopping.take().is_some() {
                                stopped_end = None;
                                stop_done_at = Some(tokio::time::Instant::now());
                                active_since_stop = false;
                            }
                            stop_settle_at = None;
                        }
                        if event_tx.send(Ok(ev)).await.is_err() {
                            break 'main; // consumer gone — reap below
                        }
                        if is_done {
                            any_done = true;
                            if interrupted {
                                done_after_interrupt = true;
                                break 'main;
                            }
                        }
                    }
                }
                Ok(None) => break 'main, // stdout EOF: the CLI exited
                Err(e) => {
                    let _ = event_tx.send(Err(HarnessError::Io(e))).await;
                    break 'main;
                }
            },

            steer = steering.recv(), if steering_open && !interrupted => match steer {
                Some(msg) => {
                    // A message of ours now owns whatever result comes next.
                    active_since_stop = true;
                    // A changed configuration applies before the prompt, on
                    // the same stdin the CLI reads in order.
                    if let Some(next) = msg.config {
                        reconfigures += 1;
                        if model_arg(&next) != model_arg(&config) {
                            let line = wire::set_model_request_line(
                                &format!("roboco_model_{reconfigures}"),
                                model_arg(&next).as_deref(),
                            );
                            if stdin_tx.send(StdinMsg::Line(line)).is_err() { break 'main; }
                        }
                        if flag_settings(&next) != flag_settings(&config) {
                            let line = wire::apply_flag_settings_line(
                                &format!("roboco_settings_{reconfigures}"),
                                flag_settings(&next),
                            );
                            if stdin_tx.send(StdinMsg::Line(line)).is_err() { break 'main; }
                        }
                        config = *next;
                    }
                    let id = uuid::Uuid::new_v4().to_string();
                    let images = load_image_blocks(&msg.attachments).await;
                    let line = wire::steer_message_line_with_images(
                        &apply_ultrathink(config.reasoning, &msg.prompt),
                        &id,
                        open_tools.is_empty(),
                        &images,
                    );
                    pending_steers.push_back(id.clone());
                    commands.written(id);
                    if stdin_tx.send(StdinMsg::Line(line)).is_err() { break 'main; }
                }
                None => {
                    // Mailbox closed: end the input so the run can finish
                    // after the current turn.
                    steering_open = false;
                    let _ = stdin_tx.send(StdinMsg::Close);
                }
            },

            // End the in-flight turn, not the session: the CLI stays up, so
            // its background agents and shells keep running. Steers written
            // before the stop are cancelled with it (`cancel_queued`) — a
            // stopped turn must not continue into them.
            _ = turn.stop_requested(), if !interrupted => {
                stops_sent += 1;
                let id = format!("roboco_stop_{stops_sent}");
                pending_steers.clear();
                commands.cancel_waiting();
                stopping = Some(id.clone());
                stop_settle_at = None;
                stop_done_at = None;
                if stdin_tx.send(StdinMsg::Line(wire::stop_turn_request_line(&id))).is_err() {
                    break 'main;
                }
                // A turn end held for steer replays is that turn's real end:
                // its steers were just cancelled, so it ends stopped — once
                // the stop settles. A turn the CLI had already begun for
                // those steers reports first, and that report is the end.
                if let Some(mut done) = held_done.take() {
                    if let AgentEvent::Done { status, .. } = &mut done {
                        *status = DoneStatus::Interrupted;
                    }
                    stopped_end = Some(done);
                }
            },

            // The CLI answered the stop and no turn ended: it was between
            // turns. Settle the stop so the host sees the turn over.
            _ = tokio::time::sleep_until(stop_settle_at.unwrap_or_else(tokio::time::Instant::now)),
                if stop_settle_at.is_some() => {
                stop_settle_at = None;
                if stopping.take().is_some() {
                    let done = stopped_end.take().unwrap_or(AgentEvent::Done {
                        status: DoneStatus::Interrupted,
                        result: None,
                        error: None,
                        session_id: norm.session_id.clone(),
                    });
                    if event_tx.send(Ok(done)).await.is_err() {
                        break 'main;
                    }
                    any_done = true;
                    stop_done_at = Some(tokio::time::Instant::now());
                    active_since_stop = false;
                }
            },

            _ = interrupt.cancelled(), if !interrupt_sent => {
                interrupt_sent = true;
                interrupted = true;
                // The runtime is going: so is everything it started.
                #[cfg(unix)]
                if let Some(pid) = child.id() {
                    torn_down = crate::process::descendants(pid).await;
                }
                let _ = stdin_tx.send(StdinMsg::Line(wire::interrupt_request_line("int_1")));
                // Escalate if the CLI doesn't wind down within the grace
                // periods: SIGTERM (kills bash trees, runs SessionEnd hooks),
                // then SIGKILL. Aborted once the child is reaped.
                if let Some(pid) = crate::process::signal_target(&child) {
                    escalation = Some(tokio::spawn(async move {
                        tokio::time::sleep(interrupt_grace).await;
                        send_signal(&pid, Signal::Term);
                        tokio::time::sleep(kill_grace).await;
                        send_signal(&pid, Signal::Kill);
                    }));
                }
            },

            _ = tokio::time::sleep_until(
                last_frame_at + commands.held_settle().unwrap_or_default()
            ), if held_done.is_some() && commands.held_settle().is_some() => {
                // The steers were absorbed into the turn that just ended.
                commands.cancel_waiting();
                while pending_steers.pop_front().is_some() {
                    let (prev, next) = norm.rotate_for_steer();
                    if event_tx.send(Ok(AgentEvent::Steered {
                        assistant_message_id: Some(prev), next_assistant_message_id: Some(next),
                    })).await.is_err() { break 'main; }
                }
                let done = held_done.take().expect("guarded by if");
                if event_tx.send(Ok(done)).await.is_err() {
                    break 'main;
                }
                any_done = true;
            },

            _ = event_tx.closed() => break 'main,
        }
    }

    // A turn end still held when the CLI exited is the run's real end.
    if let Some(done) = held_done.take()
        && !event_tx.is_closed()
        && event_tx.send(Ok(done)).await.is_ok()
    {
        any_done = true;
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
                    session_id: norm.session_id.clone(),
                }))
                .await;
        } else if !interrupted && !any_done {
            let status = child.try_wait().ok().flatten();
            let _ = event_tx
                .send(Ok(AgentEvent::Done {
                    status: DoneStatus::Errored,
                    result: None,
                    error: Some(crate::crash_message("claude", status, &stderr_tail)),
                    session_id: norm.session_id.clone(),
                }))
                .await;
        }
    }

    crate::shutdown_agent(&mut child, torn_down, kill_grace).await;
    if let Some(handle) = escalation {
        handle.abort();
    }
}

/// How far the CLI has taken each stdin user message we wrote, by the uuid
/// it was written with. A `result` frame ends the TURN only when no message
/// is still waiting to be taken up. A waiting one makes the result a steer
/// boundary — a `now` steer aborting the turn it interrupts, or a message
/// queued behind a turn that just finished — and that message's own turn
/// ends with its own result. Deciding this from the CLI's own lifecycle
/// rather than from quiet time: a held result released by a quiet timer
/// marked the steered turn done mid-way through any long tool call.
#[derive(Default)]
struct Commands {
    /// Uuid → how far it got. Ended commands are dropped.
    open: std::collections::HashMap<String, CommandState>,
    /// The CLI reports `command_lifecycle`. Older CLIs never do: their only
    /// confirmation is the replay, and the settle fallback covers the rest.
    reported: bool,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum CommandState {
    /// On stdin; the CLI has not acknowledged it yet.
    Written,
    /// The CLI read it and will start or cancel it.
    Queued,
    /// A turn took it up.
    Started,
}

impl Commands {
    /// Without lifecycle reports, how long a held result waits in silence
    /// for the replay that supersedes it.
    const UNREPORTED_SETTLE: Duration = Duration::from_secs(5);
    /// A lifecycle-reporting CLI acknowledges a message as soon as it reads
    /// it. A hold resting only on unacknowledged messages is a safety net
    /// against a message the CLI never reports, not a timing guess.
    const UNACKNOWLEDGED_SETTLE: Duration = Duration::from_secs(30);

    fn written(&mut self, id: String) {
        self.open.insert(id, CommandState::Written);
    }

    fn is_terminal(state: &str) -> bool {
        matches!(state, "completed" | "cancelled" | "canceled" | "failed")
    }

    /// Apply one `command_lifecycle` frame. True when a turn took the
    /// command up (`started`).
    fn lifecycle(&mut self, id: &str, state: &str) -> bool {
        self.reported = true;
        if Self::is_terminal(state) {
            self.open.remove(id);
            return false;
        }
        let next = match state {
            "started" => CommandState::Started,
            "queued" => CommandState::Queued,
            _ => return false,
        };
        if let Some(current) = self.open.get_mut(id)
            && *current != CommandState::Started
        {
            *current = next;
        }
        next == CommandState::Started
    }

    /// A message is written or queued but no turn has taken it up.
    fn waiting(&self) -> bool {
        self.open.values().any(|s| *s != CommandState::Started)
    }

    /// A stop's `cancel_queued` drops every message not yet taken up.
    fn cancel_waiting(&mut self) {
        self.open.retain(|_, s| *s == CommandState::Started);
    }

    /// The quiet time after which a held result is released as the turn's
    /// end; `None` = only the CLI's own lifecycle settles it.
    fn held_settle(&self) -> Option<Duration> {
        if !self.reported {
            Some(Self::UNREPORTED_SETTLE)
        } else if self.open.values().any(|s| *s == CommandState::Queued) {
            // A queued message always starts or is cancelled.
            None
        } else {
            Some(Self::UNACKNOWLEDGED_SETTLE)
        }
    }
}

type RequestInputFn = Box<
    dyn Fn(Vec<UserInputQuestion>) -> tokio::sync::oneshot::Receiver<Vec<UserInputAnswer>>
        + Send
        + Sync,
>;

/// Serve one `can_use_tool` control request. Every tool is auto-approved
/// (unattended parity — the CLI still blocks until SOME response arrives, so
/// every request must be answered); `AskUserQuestion` is intercepted —
/// surface the questions through the engine's input bridge (which owns the
/// `InputRequested`/`InputResolved` lifecycle), wait for the user's answers
/// (in a subtask so the frame loop keeps flowing), and hand them back keyed
/// by question text, as the tool expects.
fn handle_control_request(
    req: ControlRequestFrame,
    request_input: &Arc<RequestInputFn>,
    stdin_tx: &mpsc::UnboundedSender<StdinMsg>,
) {
    if req.request.subtype != "can_use_tool" {
        tracing::debug!(
            target: "roboco_harness::claude",
            "unhandled control_request subtype: {}", req.request.subtype
        );
        return;
    }
    if req.request.tool_name != "AskUserQuestion" {
        let line = control_response_line(&req.request_id, allow_response(req.request.input));
        let _ = stdin_tx.send(StdinMsg::Line(line));
        return;
    }
    let request_input = Arc::clone(request_input);
    let stdin_tx = stdin_tx.clone();
    tokio::spawn(async move {
        let request_id = req.request_id;
        let input = req.request.input;
        let questions = parse_questions(&input);
        // The engine's input bridge is the SOLE emitter of
        // `InputRequested`/`InputResolved`: it mints the request id, parks the
        // resolver for `respond_input`, and surfaces both events. Emitting our
        // own copy here (keyed by Claude's control-request id) folded a SECOND
        // input part into the doc whose id no resolver knew — the QuestionPanel
        // answered that unanswerable twin and the run never resumed.
        //
        // A dropped sender (caller went away) degrades to empty answers so the
        // agent is unblocked rather than wedged.
        let answers = (request_input)(questions.clone()).await.unwrap_or_default();
        let updated = updated_input_with_answers(&input, &questions, &answers);
        let line = control_response_line(&request_id, allow_response(updated));
        let _ = stdin_tx.send(StdinMsg::Line(line));
    });
}

/// Parse Claude's `AskUserQuestion` tool input into [`UserInputQuestion`]s
/// (tolerant of `header`/`title`, `question`/`prompt`, string or object
/// options — option descriptions are dropped, the wire type carries labels).
fn parse_questions(input: &Value) -> Vec<UserInputQuestion> {
    let raw = input.get("questions").and_then(Value::as_array);
    raw.map(|a| a.as_slice())
        .unwrap_or_default()
        .iter()
        .map(|q| {
            let field =
                |keys: [&str; 2]| keys.iter().find_map(|k| q.get(*k).and_then(Value::as_str));
            UserInputQuestion {
                id: uuid::Uuid::new_v4().to_string(),
                header: field(["header", "title"]).unwrap_or("Question").into(),
                question: field(["question", "prompt"]).unwrap_or("").into(),
                multi_select: ["multiSelect", "multi_select"]
                    .iter()
                    .find_map(|k| q.get(*k).and_then(Value::as_bool))
                    .unwrap_or(false),
                options: q
                    .get("options")
                    .and_then(Value::as_array)
                    .map(|a| a.as_slice())
                    .unwrap_or_default()
                    .iter()
                    .map(|op| match op {
                        Value::String(s) => s.clone(),
                        other => other
                            .get("label")
                            .or_else(|| other.get("value"))
                            .and_then(Value::as_str)
                            .unwrap_or("")
                            .into(),
                    })
                    .collect(),
            }
        })
        .collect()
}

/// Merge the user's answers back into the tool input, keyed by question text
/// (single-select ⇒ a string, multi-select ⇒ an array), as the tool expects.
fn updated_input_with_answers(
    input: &Value,
    questions: &[UserInputQuestion],
    answers: &[UserInputAnswer],
) -> Value {
    let mut updated = match input {
        Value::Object(map) => map.clone(),
        _ => serde_json::Map::new(),
    };
    let mut by_question = serde_json::Map::new();
    for q in questions {
        let labels: Vec<String> = answers
            .iter()
            .find(|a| a.question_id == q.id)
            .map(|a| a.labels.clone())
            .unwrap_or_default();
        let value = if q.multi_select {
            Value::Array(labels.into_iter().map(Value::String).collect())
        } else {
            Value::String(labels.into_iter().next().unwrap_or_default())
        };
        by_question.insert(q.question.clone(), value);
    }
    updated.insert("answers".into(), Value::Object(by_question));
    Value::Object(updated)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn parses_questions_tolerantly() {
        let input = json!({
            "questions": [
                {
                    "header": "Choice",
                    "question": "Pick one",
                    "options": ["A", {"label": "B", "description": "second"}],
                    "multiSelect": false
                },
                { "title": "Alt", "prompt": "Pick many", "multi_select": true }
            ]
        });
        let qs = parse_questions(&input);
        assert_eq!(qs.len(), 2);
        assert_eq!(qs[0].header, "Choice");
        assert_eq!(qs[0].options, vec!["A".to_string(), "B".to_string()]);
        assert!(!qs[0].multi_select);
        assert_eq!(qs[1].header, "Alt");
        assert_eq!(qs[1].question, "Pick many");
        assert!(qs[1].multi_select);
    }

    #[test]
    fn answers_key_by_question_text() {
        let input =
            json!({"questions": [{"header": "H", "question": "Pick one", "options": ["A", "B"]}]});
        let qs = parse_questions(&input);
        let answers = vec![UserInputAnswer {
            question_id: qs[0].id.clone(),
            labels: vec!["B".into()],
        }];
        let updated = updated_input_with_answers(&input, &qs, &answers);
        assert_eq!(updated["answers"]["Pick one"], json!("B"));
        // Original input is preserved alongside the answers.
        assert!(updated["questions"].is_array());
    }

    /// The fork launch's latest-turn detection (ticket 18): the session
    /// store's last TOP-LEVEL message record — tagged subagent records and
    /// non-message records (summaries, tags) never count.
    #[tokio::test]
    async fn the_session_scan_takes_the_last_top_level_message_record() {
        let dir = tempfile::tempdir().unwrap();
        let project = dir.path().join("projects/probe");
        std::fs::create_dir_all(&project).unwrap();
        std::fs::write(
            project.join("sess-scan.jsonl"),
            concat!(
                r#"{"type":"user","uuid":"u-1","parent_tool_use_id":null,"message":{"role":"user","content":"first"}}"#,
                "\n",
                // A tagged subagent record and a non-message record.
                r#"{"type":"assistant","uuid":"sub-1","parent_tool_use_id":"toolu_x","isSidechain":true,"message":{"role":"assistant","content":[]}}"#,
                "\n",
                r#"{"type":"summary","uuid":"trailing","summary":"s"}"#,
                "\n",
                r#"{"type":"assistant","uuid":"msg-last","parent_tool_use_id":null,"message":{"role":"assistant","content":[]}}"#,
                "\n",
                // Records after the last message never count either.
                r#"{"type":"summary","uuid":"after","summary":"s"}"#,
                "\n",
                // A torn tail line is harmless.
                "{torn",
            ),
        )
        .unwrap();
        assert_eq!(
            session_last_message_uuid(dir.path(), "sess-scan")
                .await
                .as_deref(),
            Some("msg-last")
        );
        // No session file (and unreadable ids): no latest turn — the
        // launch treats the boundary as an earlier one.
        assert_eq!(session_last_message_uuid(dir.path(), "no-such").await, None);
        assert_eq!(session_last_message_uuid(dir.path(), "").await, None);
        assert_eq!(
            session_last_message_uuid(dir.path(), "../escape").await,
            None
        );
    }
}

#[cfg(test)]
mod mcp_injection_tests {
    use super::*;

    #[test]
    fn mcp_config_arg_spells_the_server_the_way_the_cli_reads_it() {
        let mcp = roboco_proto::McpServer {
            name: "roboco".into(),
            command: "/opt/roboco/roboco".into(),
            args: vec!["mcp".into()],
            env: [("ROBOCO_CHAT_ID".to_owned(), "chat-1".to_owned())]
                .into_iter()
                .collect(),
        };
        let parsed: Value = serde_json::from_str(&mcp_config_arg(&mcp)).unwrap();
        assert_eq!(
            parsed["mcpServers"]["roboco"]["command"],
            "/opt/roboco/roboco"
        );
        assert_eq!(
            parsed["mcpServers"]["roboco"]["args"],
            serde_json::json!(["mcp"])
        );
        assert_eq!(
            parsed["mcpServers"]["roboco"]["env"]["ROBOCO_CHAT_ID"],
            "chat-1"
        );
    }
}
