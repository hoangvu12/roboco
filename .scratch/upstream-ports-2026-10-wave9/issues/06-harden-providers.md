# 06 — Harden every provider (upstream #977, `2e151ca65`)

**What to build:** Port `2e151ca65`: crash and stall detection on every
adapter (acp +935, claude, codex, cursor + shim, opencode, pi); **Windows-safe
process launching** (`harness/src/process/cmd_shim.rs` new, `process/windows/*`,
`windows_process.rs`); `lossy_lines.rs` new; jsonrpc stall handling;
engine run recovery (`sessions.rs` +1305, `run_journal.rs`, new
`tests/run_recovery.rs` +1412); `windows.yml` +5; the fake-process
fixtures for the test suites.

**Port note — reconcile by intent:** roboco just landed session process
hygiene (`03800d7c7`: zombie reaper, crash net, warm-park budget). Diff the
upstream run-recovery journal against roboco's crash net before carrying;
resolve overlaps by behaviour, never by replacing roboco's fresh design.

**Excluded upstream hunks:** none expected — engine + harness only (no
cloud/iOS files in the commit).

**Blocked by:** 03, 05 (npm_repair + executable/refresh substrates).

**Status:** ready-for-agent

**Verification budget:** harness suite (incl. new acp_hardening, fake
fixtures), engine run-recovery tests, `windows.yml` run. Source:
`.scratch/upstream-drift/2026-10-25.md` § A6.

- [ ] Adapter hardening + Windows process safety + run recovery ported —
  **run-recovery core NOW PORTED (06 2/2); adapter hardenings (acp/claude/
  codex/opencode/pi driver hunks) still open**
- [x] Reconciliation with `03800d7c7` recorded in the commit — the
  revival-ledger vs crash-net reconciliation: `03800d7c7`'s startup-crash
  retry (single, resume kept) and `03800d7c7`'s zombie reaper both stay;
  the new boot revival ledger (3-in-a-row/12h) governs boot recovery, and
  the startup-crash retry now refuses a provider-rejected resume id while
  carrying the revival's continuation through
- [x] Commit records upstream SHA `2e151ca65` (#977)

## Comments

- **2026-10-25 — PARTIAL port on `wave9/engine`** (upstream `2e151ca65` is a
  14.8k-line commit; this carries the process/transport substrate, the
  cursor driver hardening and the Windows-safe launching, verified). The
  remaining scope is mapped below for a follow-up turn — **not dropped**.

  Carried and verified:
  - **Windows-safe launching:** `process/cmd_shim.rs` (new — never an
    implicit shell for `.cmd`/`.bat` shims, `canonicalize`-safe paths),
    `windows_process.rs` + `process/windows/{command,mod,tests}` (registry
    PATH read fresh with `%VAR%` expansion, job objects), spawn-retry for
    sharing/lock violations with `sleep_off_runtime`, `plain_executable`
    (codex), npm repair launching through node for `.js` npm shims + the
    Windows node_modules layout, `Win32_System_Registry` feature.
  - **Process identity & teardown:** `process.rs` — native process table
    (`/proc` Linux, `libproc` macOS, ps fallback), `Tracked {pid, start}`
    with start-time verification so a reused pid is never signalled;
    `terminate_tree` re-verifies before each signal; `descendants()`
    returns `Tracked`. Threaded through roboco's own crash-net
    (`torn_down: Vec<Tracked>` in claude/codex/acp) — the first half of
    the `03800d7c7` reconciliation.
  - **`lossy_lines.rs`** (new): newline framing that survives invalid
    UTF-8 (U+FFFD per line) — wired into jsonrpc, cursor stdio.
  - **jsonrpc.rs hardening:** a failed stdin write closes the client and
    fails every pending request at once (`fail_pending`, `transport_lost`);
    a bare `{jsonrpc, id}` void response resolves null; `close_stdin_for_teardown`
    wired into codex probes/teardown and the acp session teardown.
  - **acp/child.rs:** the 12 inherited Claude-session env markers (was 4)
    and `shutdown_with(earlier)` — a descendant snapshot taken while the
    agent lived is still ended after it exits (upstream's `process/owned.rs`,
    which is roboco's `acp/child.rs`).
  - **shell_env.rs:** login-shell snapshot also captures TLS trust
    (SSL_CERT_FILE/DIRECTORY, NODE_EXTRA_CA_CERTS, REQUESTS_CA_BUNDLE),
    SSH_AUTH_SOCK, locale, `__MISE_DIFF`; `user_path()` (Windows: the
    persisted registry PATH read fresh); `child_env_defaults()` hands them
    to agent children that lack them; probe runs in its own session; all
    candidate shells share one budget; nushell syntax; macOS launchd PATH
    fallback + UTF-8 LC_CTYPE for Dock launches.
  - **executable.rs:** version probes 5s/10s budget with 30s retry,
    per-binary coalescing outside the global cache lock, Windows
    USERPROFILE home, node-manager dirs, `is_bare_system_path` (login-shell
    PATH precedence for bare system PATHs), `spawn_error` classification.
  - **cursor:** bounded turn stops (12s → retire the shim, resume in a
    fresh one next message), silent-send bound handed to the shim
    (`silenceTimeoutMs`), inline images with per-image/total caps,
    `installed()` also accepts `CURSOR_API_KEY`/saved SDK login, lossy
    framing, `spawn_error`; shim.mjs hardening (image budget, harmless SDK
    rejections, silence timeout). `pi::load_image_blocks_capped` shared.
  - **engine/doc_host.rs:** the dead-command sweep no longer overwrites a
    racing drain's outcome.
  - **windows.yml:** new "Portable harness integration tests" step
    (opencode + cursor_shim; upstream also names a Rust Pi peer roboco
    doesn't have yet).

  **Reconciliation with `03800d7c7` (partial, demonstrated):** upstream's
  `teardown_skips_a_pid_that_was_reused` test failed against roboco's
  zombie reaper — `scan_buries_unowned_zombies_and_spares_owned_ones`
  (03800d7c7) reaps unowned zombie children process-wide and stole the
  test child's exit status. Reconciled by the reaper's own contract: the
  test registers its child in the `ownership` registry. The run-recovery
  core reconciliation is still open (below).

  **Verification (this partial):** `cargo test -p roboco-harness --lib`
  400/401 — the 1 failure (`antigravity_named_home_settings_preserve_
  business_auth`) fails identically on the CLEAN tree (container passwd
  lookup) and is pre-existing; targeted suites: jsonrpc/lossy/process/
  executable/shell_env/child 77 passed; `--test cursor_shim` 22,
  `--test cursor` 31 passed; `cargo check -p roboco-engine` clean (11
  pre-existing warnings). Windows-only paths (`cmd_shim`,
  `windows_process`, `process/windows/*`) compile under cfg gating but
  CANNOT be executed on this Linux host — untested surface, as the ticket
  itself warns.

- **2026-10-25 — run-recovery core LANDED on `wave9/engine`** (commit
  "06 (2/2)"). Carried from upstream #977's engine slice:
  - **Cut-off-turn revival without re-sending:** when the journal shows
    the provider streamed output for the turn and the transcript kept it
    under that message, the revived run sends upstream's continuation
    prompt ("Continue where you left off…\n<request>…") instead of
    re-sending the request, which the session would read as new work and
    redo. `Revival { message_id, continuation }` rides the dispatch chain
    (dropped when no session resumes — the run starting fresh gets the
    original prompt).
  - **Never-revive rules:** native commands (`native_command` now requires
    a valid command name, upstream's fix — a leading `/tmp/path` is
    ordinary work), turns parked on an unanswered question
    (`Pickup::AwaitingAnswer`), already-finished turns (parked sessions'
    background work journals on after Done), user-Stopped turns, and an
    exhausted revival budget (`Pickup::Exhausted` says why).
  - **Revival ledger window:** `resume_attempts` stores
    `{count} {last_ms}`; a ledger whose last revival is older than 12h has
    expired (a chain from days ago must not refuse today's crash). A
    message the user sends resets the budget.
  - **Graceful shutdown marking:** `shutdown()` flags working runs
    (`shutdown_cut`), the ones it actually cuts off end Interrupted with
    the "Run interrupted by engine shutdown" note and a
    `{chat}.shutdown` journal marker — written as the turn ends (the
    Done rewrite happens BEFORE publish: journal, transcript and status
    all carry the shutdown's ending), so a turn that finishes on its own
    meanwhile is never marked. The user's own Stop clears the flag (a
    Stop is never undone) and writes a `{chat}.stopped` marker (an engine
    that dies before the stopped run settles never revives it). A
    shutdown mark over a turn that ended otherwise is ignored and
    cleared. `forgo_revival()` is the engine-local form of upstream's
    sign-out gate (no Auth/WorkspaceScope to read it from).
  - **Transient start-failure backoff:** io refusals (permission denied
    on a CLI replaced within 60s, resource busy, would block, interrupted;
    Windows sharing/lock violations by raw code) re-dispatch against the
    same user entry after 250ms/1s/3s; a Stop or shutdown during the wait
    ends the run (a shutdown-cut one marks + revives on boot).
  - **Resume-gone → fresh session:** a start failure matching upstream's
    narrow "no conversation found/conversation not found/session not
    found/thread not found" phrases rejects the id everywhere it's still
    recorded (`rejected_resumes` + the tombstone ref) and retries once
    fresh; the reply opens with the "provider no longer has this chat's
    earlier session" notice (seeded into the fold; cleared when a run
    replaces it).
  - run_journal.rs: shutdown/stopped marker files + the windowed ledger
    (ported nearly verbatim, with upstream's tests).
  - `note_user_stop` on `interrupt`/`stop_turn`; the startup-crash retry
    carries the revival and refuses a shutdown;
    `remove_run`-adjacent fresh-start notice bookkeeping.
- **Reconciliation with `03800d7c7`:** both layers coexist by design —
  `03800d7c7`'s startup-crash retry (single retry, resume kept, from the
  ACP-conversion incident 2026-08-13) is preserved and now additionally
  rejects provider-rejected ids; its zombie reaper is untouched. The new
  boot revival ledger (upstream's) governs boot recovery only; the
  startup-crash retry remains the in-run failure point. The
  `teardown_skips_a_pid_that_was_reused` reconciliation from 06 (1/2)
  stands.
- **Verification (this commit):** `cargo test -p roboco-engine --test
  run_recovery` 20/20 (upstream's suite adapted: continuation re-send,
  native-command/path/awaiting-answer/finished/Stopped exclusions,
  3-in-a-row cap + budget reset, shutdown cut-off pickup + finish-during-
  shutdown + mark-over-finished + forgo_revival + dies-midway +
  stop-dies-before-settling, teardown-error pickup, transient backoff,
  old-CLI refusal surfaces, deterministic failure surfaces,
  rejected-resume fresh retry + switched-chat variant); `--lib` 481/481;
  message_queue 26/26 + restart_resume 13/13 with upstream's updated
  assertions (the interrupted opening turn is picked back up; the QUEUE
  behind it stays frozen); session_process_hygiene 4/4; provider_switch
  8/8; harness_switch 3/3; continue_on_harness 6/6. Pre-existing (fails
  at wave-start base): previews watch test (remote-peer).

- **2026-10-25 — REMAINING SCOPE (follow-up turn, mapped):** the driver-level
  adapter hardenings — everything upstream #977 did inside the harness
  adapters (none of it is required by the run-recovery core that just
  landed):
  1. **acp/mod.rs** (+935): bounded turn stops (12s) + answering open
     dialogs on stop/interrupt, handshake crash watch (a descendant holding
     stdout no longer waits 120s), session restore per advertised
     capability (session/load vs session/resume, 90s budget, fresh-session
     fallback with a visible notice), auth_required single sign-in,
     model/config switch bounds (15s), model-discovery incoming drain,
     Grok prompting client + session/cancel user gesture;
     `tests/acp.rs` updates, `tests/acp_hardening.rs` (new, 312) +
     `fixtures/fake-hardened-acp.py` (new, 131), `fixtures/fake-acp.sh`
     update.
  2. **claude** result classification (`claude/{normalize,mod,wire}.rs`:
     is_error/terminal_reason/api_error_status → Errored, aborts →
     Interrupted, surrogate-pair repair, never-drop-a-result,
     api_retry/compaction liveness notices), `tests/claude.rs` (+366),
     `fixtures/fake-claude.sh` (diverges upstream; hand-port).
  3. **codex** elicitation + stall hardening (`codex/{mod,normalize}.rs`,
     `codex/elicitation.rs` new), `tests/codex.rs` (+227),
     `fixtures/fake-codex.sh`.
  4. **opencode** startup retries + readiness (+1586),
     `opencode/tests.rs` + `tests/opencode.rs`.
  5. **pi** rpc/sessions/ui hardening + the Rust `pi_rpc` peer
     (`tests/pi_rpc.rs` + `fixtures/pi_rpc.rs`), PROTOCOL.md.

- **2026-10-26 — adapter hardenings PARTIALLY LANDED on `wave9/engine` (06 3/3):
  ACP (the largest single driver) + Claude wire/normalize classification +
  pi's shared image loader.** Compile-verified
  (`cargo check -p roboco-harness` 0 errors before disk ran to 84M free —
  the disk protocol stopped test runs; the suites below are PENDING for the
  gates turn):
  - **acp/mod.rs** (upstream #977's ACP slice, adapted):
    `DEFAULT_STOP_TIMEOUT`/`SESSION_LOAD_TIMEOUT`/`CONTROL_REQUEST_TIMEOUT`
    constants + `stop_timeout`/`session_load_timeout` fields and
    `with_stop_timeout`/`with_session_load_timeout` test knobs; bounded turn
    stops (the stop-deadline watchdog closes a wedged turn as stopped and
    retires the process, open subagents settling through the exit drain);
    `PendingQuestions` — question-shaped permission requests are answered
    `cancelled` on stop/interrupt (ACP requires an answer to every pending
    permission request); `cancel_params` (Grok's user-gesture cancel
    barrier); session restore per advertised capability
    (`restore_method`: session/load vs session/resume, Antigravity prefers
    resume; `session_unrestorable`'s narrow not-found family; a slow
    restore errors and keeps the session, a missing one falls back to
    fresh with a visible notice; `session/load`'s 90s budget on top of the
    handshake's, folded into the setup select's budget);
    `authenticate_for_run`/`run_auth_method` (auth_required answered once
    from the run itself via the agent's declared default method — Grok's
    cached_token; a terminal/env-var method names its kind in the error);
    `control_request` bounds `session/set_model`/`set_config_option`;
    model discovery drains its incoming channel
    (`drain_discovery_incoming`); `spawn_error` classification + lossy
    stderr framing at both spawn sites; `exits_on_stdin_eof` →
    `ignores_stdin_eof` for OpenCode; `client.close_stdin_for_teardown()`
    on the setup-failure path.
  - **claude/wire.rs**: `ResultFrame` gains `is_error`, `terminal_reason`,
    `api_error_status`, `attempt`/`max_retries`/`retry_delay_ms`/
    `error_status`/`error` (api_retry), `compact_metadata`
    (compact_boundary), and the synthetic `unreadable` marker;
    `ControlResponseBody` with `error_message()`; parse robustness —
    lone-surrogate escape repair (Node cuts a string through a pair),
    explicit `null` entries read as absent fields, and a `result` line
    that still won't parse becomes an unreadable result so its turn still
    ends (`top_level_type` guards against a nested "type":"result");
    `control_error_line` (the CLI waits for a reply to every request it
    sends). Upstream's `control_response_line`/`allow_response`/
    `interrupt_request_line` already existed in Roboco.
  - **claude/normalize.rs**: result classification —
    `terminal_reason_text` (the failure reasons with human wording),
    `aborted()` (abort terminal reasons end Interrupted, not Errored),
    `api_failure()` (a `success` result that is really a failed API call:
    is_error, 429/529, full-context wording; the CLI's own detail capped
    at 500 chars, not repeated when the turn already showed its error part
    — `turn_error_shown`); never-drop-a-result (an unreadable result
    frame still ends its turn with what the line told us); liveness —
    `api_retry` keeps the turn visibly alive and says why once the
    retries add up (`retry_noticed`), `compacting` status emits an empty
    reasoning delta, `compact_boundary` reports the post-compaction token
    count; `Normalizer::started()`; `for_resume` returns the
    `Transcript` (Conversation/Empty/Unknown — a transcript read whole
    with no user/assistant record is Empty, a doubtful read is Unknown).
  - **claude/mod.rs**: an Empty transcript starts the run fresh (the CLI
    refuses to resume it and exits, every time) with the SESSION_LOST
    notice; `Transcript` import.
  - **pi/mod.rs**: `load_images_capped_for_acp` — ACP shares Pi's
    inline-image loader (upstream #977 shares `crate::pi::load_images`).
- **Disk protocol note (2026-10-26):** free space fell to 84M mid-turn
  (the rooms chat's 23G worktree build). Tests could not be run. The
  suites that still need to run (gates turn): `cargo test -p
  roboco-harness --lib` (full), `--test acp` + the NEW `--test
  acp_hardening` + `fixtures/fake-hardened-acp.py` (upstream's new test
  binary and fixture were NOT ported this turn — see remaining), `--test
  claude`, `--test claude_refresh_lock`, `--test codex`, `--test
  opencode`, `--test pi`, engine `--test run_recovery` + `--lib`
  regression pass.
- **REMAINING (still open after 06 3/3):**
  1. `tests/acp_hardening.rs` (new, 312) + `fixtures/fake-hardened-acp.py`
     (new, 131) + `fixtures/fake-acp.sh` update — upstream's own test
     binary for the ACP hardening; the `--test acp` updates.
  2. `claude/mod.rs`'s runtime restructure: the `Runtime`/`Stdin` (replay)
     split, the relaunch-fresh-when-unrestorable machinery
     (`no_conversation` on stdout/stderr), `pending_controls`/
     `config_unsure`/`refused` for set_model/apply_flag_settings bounds,
     `read_stderr` (piped stderr + `wait_closed`), the 25s PROBE_TIMEOUT
     with redacted stderr, `native_beside_npm_shim`, `config_root(env)`,
     `managed_mcp_config`, `tests/claude.rs` (+366) and
     `fixtures/fake-claude.sh`.
  3. **codex** elicitation + stall hardening (`codex/{mod,normalize}.rs`,
     `codex/elicitation.rs` new), `tests/codex.rs` (+227),
     `fixtures/fake-codex.sh`.
  4. **opencode** startup retries + readiness (+1586),
     `opencode/tests.rs` + `tests/opencode.rs`.
  5. **pi** rpc/sessions/ui hardening + the Rust `pi_rpc` peer
     (`tests/pi_rpc.rs` + `fixtures/pi_rpc.rs`), PROTOCOL.md.

- **2026-10-26 — adapter hardenings continued (06 4/4): the ACP hardening
  test binary + the codex elicitation/version floor.** Compile-verified
  (`cargo check -p roboco-harness` + `--tests` both 0 errors at 1.7G free —
  the disk protocol still blocks test runs):
  - **tests/acp_hardening.rs** (new, upstream's 312-line suite adapted):
    stop-wedge deadline, question-cancelled dialogs, Grok cancel `_meta`
    (with the no-clientType initialize assertion), config-switch-hang stop,
    handshake-crash crash watch, auth_required default-method sign-in +
    terminal-only refusal, resume-uses-advertised-restore-method, image
    attachments inlined/omitted by capability. Adapted to Roboco:
    RunControls.origin (no realtime), no RunRequest.env (the fixture's
    FAKE_ACP_MODE rides the process env behind a global test mutex —
    upstream threads it through the env map), no prefill/multiline on
    UserInputQuestion.
  - **fixtures/fake-hardened-acp.py** (new, upstream's 131-line fixture
    verbatim — reads FAKE_ACP_MODE itself).
  - **acp/mod.rs handshake crash watch** (upstream's setup-loop rework):
    the setup future is re-polled inside a select that also watches the
    child (`child.wait()` + a 200ms drain deadline + group shutdown), so
    an adapter that dies mid-handshake while a detached descendant holds
    stdout open reports "exited during the ACP handshake" at once instead
    of sitting out the whole bound.
  - **tests/acp.rs**: the permission test now checks both auto_approve
    polarities (a Grok edit never waits on the user).
  - **fixtures/fake-acp.sh**: the three restore-failure modes
    (load-hang/load-busy/load-exit — for the an_unfinished_restore test,
    which was NOT ported: it uses the request env and with_session_load
    timeouts on the upstream wiring; the fixture modes are there for the
    gates turn).
  - **codex**: `elicitation.rs` (new, upstream's 399-line module adapted —
    prefill/multiline fields dropped for Roboco's UserInputQuestion)
    wired into `handle_server_request`: MCP servers elicit regardless of
    the approval policy; a flat form the input bridge can ask is asked,
    anything else is declined (a reply the server handles, where a
    JSON-RPC error failed its tool call). `MIN_CODEX_VERSION` floor
    (0.146.0, pre-releases count as their release) gated on the resolved
    binary's version (a fixed test executable is never probed);
    `spawn_error` classification at all three spawn sites; lossy stderr
    framing; the AsyncBufReadExt import dropped.
  - **claude/normalize.rs**: one test pattern adapted (`Error { message, .. }`).
- **Verification status:** compile-only again (1.7G free; the disk
  protocol). The acp_hardening suite, acp.rs, codex.rs, and the 3/3
  suites are all queued for the gates turn.
- **REMAINING (still open after 06 4/4):** the codex wire-surface rework
  (notification opt-out with the retry handshake, thread/resume's
  unarchive/empty-rollout fresh-start fallback, the
  settle_turn_error/error-notice rework — its `codex_error_text` depends
  on `proto::run_errors` (upstream #967's error-cards slice, the UI
  lane's ticket 14; not in Roboco's proto yet) and its fixture modes
  resume-empty/resume-archived/crash-on-thread-start landed in
  fake-codex.sh's upstream but Roboco's copy diverges — hand-port
  territory), claude/mod.rs's runtime restructure (Stdin replay,
  relaunch-fresh, control deadlines, probe stderr — Roboco's session
  loop carries its own turn-ref/fork machinery through every site),
  opencode (+1586), and pi's rpc hardening + the Rust pi_rpc peer.
  The `an_unfinished_restore_errors_and_keeps_the_session` test
  (upstream's new acp.rs case) also needs the env/threading adaptation
  before it can land.
