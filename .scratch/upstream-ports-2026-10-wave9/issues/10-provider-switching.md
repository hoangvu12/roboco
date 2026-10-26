# 10 — Provider switching between turns (upstream #966 slice, `cc7da5c2a`) — WIRE

**What to build:** Port the portable slice of the #966 mega-commit: the user
can switch a chat's agent account/provider between turns — engine
(`sessions.rs`/`runner.rs`/`deferred.rs`/`rpc.rs` + the new
`tests/provider_switch.rs` / `provider_switch_live.rs`), harness adapter
hunks (acp/claude/codex/cursor/opencode/pi `mod.rs`), and `proto/agent.rs`'s
`HarnessId::ALL`/`as_str`/`from_slug` helpers. **Wire change — wiregen gate.**

**Port note — reconcile by intent:** roboco's orchestration v2 just built
failure-driven account failover (`account_failover.rs`, resume-on-another-
account). Same substrate, different trigger (user-driven vs failure-driven);
port the switch as a sibling of the failover, not a replacement.

**Excluded upstream hunks:** everything else in #966 — durable-object token
vault, Stripe billing, cloud machines, credential vault + `env` broker
map, iOS app files (17), `crates/client` (16), the iOS model picker
(Android parity is ticket 20).

**Blocked by:** 06 (both rework `engine/src/sessions.rs`) — satisfied: the
partial 06 landed as `16012431c`.

**Status:** ready-for-human

**Verification budget:** engine provider_switch tests (non-live), wiregen.
Source: `.scratch/upstream-drift/2026-10-25.md` § A9.

- [x] Between-turns switch + HarnessId helpers ported, reconciled with failover
- [x] wiregen fresh
- [x] Commit records upstream SHA `cc7da5c2a` (#966, slice)

## Comments

- **2026-10-25 — ported on `wave9/engine`.** Reconciled with what Roboco
  already had: the one-way `SwitchHarness` RPC, the harness-gated
  `resume_for`, the budgeted seam history (t3-orchestration-v2 ticket 24)
  and orchestration v2's account failover. The port upgrades the switch to
  upstream #966's shape as a SIBLING of that machinery:
  - **proto:** `HarnessId::{ALL, as_str, from_slug}` (upstream's helpers).
  - **doc:** `ProviderSessionMark` (session id + cwd + `seen` watermark)
    with `provider_sessions`/`set_provider_session`/
    `mark_provider_switched`; `insert_message_before_last` (the seam lands
    ABOVE the message that triggered the switch); the `HarnessSwitch` seam
    part gains `from_harness` (additive — the seam names both providers,
    upstream's from/to). Wire regenerated (`MessagePart.ts` +`fromHarness?`);
    `wiregen -- --check` fresh.
  - **engine:** `switch_harness` records the OUTGOING provider's session
    mark (seen through its last reply — never the unanswered message that
    triggered the switch) and stamps `from_harness` on its seam;
    `note_provider_switch` at dispatch covers claim-on-first-command sends
    that never called the RPC (upstream writes every seam this way — the
    walk stops at a fork seam, so a side chat's parent switches are not its
    own); a switch BACK resumes the provider's own session
    (`own_provider_session`, cwd-gated) and is handed only what it missed —
    the history after its watermark, under upstream's "While you were away…"
    lead; a fresh session on a switched chat gets the seam-bounded history
    under "…you are now taking over" (the budget's lead is parameterized).
    `note_provider_session` advances the watermark at SessionStarted/Done —
    **only for sessions whose history delivery makes it sound** (a resume,
    or a wrapped first send): upstream would watermark a native-command
    first turn and silently lose the deferred history; Roboco's
    `session_delivered_history` guard keeps the old seam-bounded path for
    that case (covered by harness_switch's deferred-history test).
    `journal_harness_session` now tags the governing SessionStarted's
    harness (upstream's third tuple element) and `resume_for` refuses a
    foreign id from the journal/untagged row — the harness-blind journal
    scan was the last hole a foreign session could slip through.
  - The watermarks are the switch-back mechanism's reconciliation with
    Roboco's account failover: same doc/session substrate, different
    trigger (user-driven vs failure-driven).
- **Verification:** `--test provider_switch` 8/8 (ported upstream's suite,
  adapted to the RPC-driven switch: drop-foreign-resume + history once,
  switch-back resume + missed-only, restart survival, retried-dispatch one
  seam, budget cap, foreign-harness dispatch-driven seam, failed-switch
  resume, every switch one seam, side-chat seams);
  `--test harness_switch` 3/3 (the switch-back test updated to the new
  resume semantics — renamed
  `switching_back_resumes_the_providers_own_session_with_what_it_missed`);
  `--test continue_on_harness` 6/6; `cargo test -p roboco-engine --lib`
  479/479 (the journal scan test extended for the harness tag);
  `cargo test -p roboco-doc --lib` 154/154 (incl. the seam round-trip with
  from_harness); `cargo check -p roboco-ui` clean; wiregen fresh.
  **Pre-existing, verified at wave-start base `d42b1819e`:**
  `--test pi_resume` fails there too (fixture never replies within 10s on
  this host) — not from this port.
- **Deliberately excluded:** everything else in #966 — the durable-object
  token vault, Stripe billing, cloud machines, credential vault + the
  `RunRequest.env` broker map (hence ALL upstream harness adapter hunks —
  they only thread that env), `crates/client` (16 files), the iOS app (17)
  and its model picker, `runner.rs`/`deferred.rs`/`session_activity.rs`/
  `cloud_*`/`credentials.rs`/`managed.rs` (upstream's session-driver
  restructure and cloud substrate — Roboco keeps its sessions.rs shape),
  `provider_switch_live.rs` (live-CLI harness, ignored upstream convention).
