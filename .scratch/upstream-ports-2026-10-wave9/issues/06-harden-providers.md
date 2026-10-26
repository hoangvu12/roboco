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
  **PARTIAL (see Comments); remainder mapped for a follow-up turn**
- [ ] Reconciliation with `03800d7c7` recorded in the commit — **partial:**
  the Tracked/pid-identity API is threaded through roboco's crash-net
  (`torn_down`) and one reaper interplay is reconciled (see Comments); the
  run-recovery core reconciliation is still open
- [ ] Commit records upstream SHA `2e151ca65` (#977)

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

- **2026-10-25 — REMAINING SCOPE (follow-up turn, mapped):**
  1. **Engine run-recovery core** (the ticket's center):
     `engine/src/sessions.rs` (+1305 upstream): cut-off-turn revival
     without re-sending (continuation sends, native-command/question/
     finished exclusions, revival ledger 3-in-a-row/12h), graceful-shutdown
     marking (`{chat}.shutdown`), transient start-failure backoff
     (250ms/1s/3s), resume-gone → fresh-session tombstone, sign-out
     `forgo_revival()`, dead-command sweep race, signed-out stop rule;
     `engine/src/run_journal.rs` (shutdown/stopped marker paths);
     `engine/src/lib.rs` 3way hunk; `engine/tests/run_recovery.rs` (new,
     1412 lines) + `restart_resume.rs`/`message_queue.rs` test updates.
     Reconcile with `03800d7c7`'s crash net (the revival ledger vs the
     crash net's startup retry) by behaviour.
  2. **acp/mod.rs** (+935): bounded turn stops + dialog answers,
     handshake crash watch, session restore per advertised capability
     (load vs resume, 90s budget), auth_required single sign-in,
     model/config switch bounds, model-discovery drain, Grok prompting
     client; `tests/acp.rs` updates, `tests/acp_hardening.rs` (new) +
     `fixtures/fake-hardened-acp.py`, `fixtures/fake-acp.sh` update.
  3. **claude** result classification (`claude/{normalize,mod,wire}.rs`:
     is_error/terminal_reason/api_error_status → Errored, aborts →
     Interrupted, surrogate repair, never-drop-a-result,
     api_retry/compaction liveness), `tests/claude.rs` (+366),
     `fixtures/fake-claude.sh`.
  4. **codex** elicitation + stall hardening (`codex/{mod,normalize}.rs`,
     `codex/elicitation.rs` new), `tests/codex.rs` (+227),
     `fixtures/fake-codex.sh`.
  5. **opencode** startup retries + readiness (+1586),
     `opencode/tests.rs` + `tests/opencode.rs`.
  6. **pi** rpc/sessions/ui hardening + the Rust `pi_rpc` peer
     (`tests/pi_rpc.rs` + `fixtures/pi_rpc.rs`), PROTOCOL.md.
