# 05 — Claude logins that stay signed in (upstream #974, `dd0c36fa3`) — WIRE

**What to build:** Port `dd0c36fa3`: serialized Claude token refresh (new
`harness/src/claude/refresh_lock.rs`) so a sign-in survives; sturdier agent
CLI updates (engine `harness_updates.rs` +419, `executable.rs` +143); engine
`agent_accounts.rs` +445; npm_repair follow-on (+48); proto `agent.rs` +15;
ui `settings/harnesses.rs` + `shell/harness_updates.rs`.

**Port note:** engine-local agent accounts; adapt any account-broker hunk
out. **Wire change — run `cargo run -p wiregen -- --check`.**

**Excluded upstream hunks:** cloud/client hunks if any ride the account
surface.

**Blocked by:** 03 (npm_repair substrate).

**Status:** ready-for-human

**Verification budget:** `cargo check -p roboco-engine -p roboco-harness`,
engine + harness account tests, wiregen. Source:
`.scratch/upstream-drift/2026-10-25.md` § A5.

- [x] Refresh lock + CLI update hardening + wire types ported
- [x] wiregen fresh
- [x] Commit records upstream SHA `dd0c36fa3` (#974)

## Comments

- **2026-10-25 — ported on `wave9/engine`.** Carried:
  - `harness/src/claude/refresh_lock.rs` (new, 348 lines): honours the CLI's
    own cross-process refresh lock (`<config>/.oauth_refresh.lock` + the
    legacy `<realpath(config)>.lock`); `settle` before any Claude child is
    signalled or sent EOF (probe retirement, stdin Close, SIGTERM/SIGKILL
    escalation, final shutdown) — wired into `claude/mod.rs`.
    `config_dir_for` (per-run env map) dropped: Roboco's `RunRequest` has no
    `env` field; the process env locates the config dir.
  - `agent_accounts.rs`: swap/adopt/sign-out hold both locks around the
    read-snapshot-write; the Keychain lookup is present/absent/unknown
    (never snapshot an unknown read); a stale live read never replaces a
    newer slot (`claude_login_is_older`); a slot refresh only runs when
    provably not the live grant (`claude_slot_is_not_live`); the rotated
    refresh token's lifetime is recorded; switching to a spent login is
    refused ("sign in to it again"). 6 new login_tests ported and passing.
  - `harness_updates.rs` (+371): version re-read once the exclusive lease is
    held (no silent downgrades), 5-minute binary watcher +
    `recheck_changed_binaries_soon` on watch open, one updater per
    installation owner, failure `kind` (check vs install) + Retry update,
    npm-prefixed reinstall — applied wholesale after rebrand (pre-image
    matched Roboco's file).
  - `executable.rs`: version probes time out at 5s, failures retry after
    60s, one in-flight probe per identity (no global lock across a probe).
  - `sessions.rs`: a parked session whose CLI binary changed on disk is
    replaced before its next turn (`binary_identity` stamped on RunHandle,
    `binary_changed` with the same guards as a login change) — adapted into
    Roboco's fingerprint-based warm-routing (upstream's `credential_changed`
    path is Roboco's `credential_fingerprint` compare).
  - proto `HarnessUpdateFailureKind` (wire: `"check" | "install"`), ui
    `settings/harnesses.rs` + `shell/harness_updates.rs` ("Update failed",
    "Retry update").
- **Verification:** `cargo test -p roboco-harness --lib claude` 55 passed,
  `claude::refresh_lock` 7 passed, `codex::npm_repair` 3 passed,
  `executable` 17 passed, `--test claude_refresh_lock` 1 passed;
  `cargo test -p roboco-engine --lib agent_accounts` 90 passed (6 new),
  `harness_updates` 36 passed; `--test e2e` 28 passed, `--test
  restart_resume` 13 passed; `cargo check -p roboco-ui` clean (17m);
  `cargo run -p wiregen` regenerated `HarnessUpdateFailureKind.ts` +
  `HarnessUpdateFailure.ts`, `wiregen -- --check` passes. macOS-gated
  Keychain `Lookup` paths compile on Linux only (no macOS CI) — human
  review on macOS remains open (same caveat as ticket 04).
