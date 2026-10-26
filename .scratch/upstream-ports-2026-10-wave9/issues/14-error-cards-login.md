# 14 — Error cards: "Log in" for signed-out/limit-reached runs (upstream #967 slice, `f30a8641b`) — WIRE

**What to build:** Port the portable slice of `f30a8641b`: new
`crates/proto/src/run_errors.rs` (+122) — `provider_problem` classifies a
run's failure as signed-out or limit-reached (one classifier for every
client); mock agent gains `/error <message>` (harness `mock.rs` +32);
desktop transcript error cards get the red-wash treatment with
"Signed out"/"Limit reached" labels and a **Log in** button
(ui `notice.rs`, `transcript.rs`, `shell.rs`,
`settings/{harnesses,accounts}.rs`); `crates/mobile/src/layout/rows.rs`
(+68) renders error-card rows — **substrate for Android ticket 18**.

**Port note:** Log in opens roboco's engine-local agent accounts
(Settings → Accounts) — no Cloud, no device routing. Roboco's Limited
banner (orchestration v2) is the failure-driven counterpart; reconcile the
presentation, keep the engine contract. **Wire change — wiregen gate.**

**Excluded upstream hunks:** iOS app files, `crates/client` demo hunks, the
iOS bottom-bar and iOS session-delete parts (Android parity = ticket 18).

**Blocked by:** none (desktop side).

**Status:** ready-for-human (ported in the wave9/ui commit "Error cards: Log in for signed-out and limit-reached runs")

**Verification budget:** wiregen, ui notice/transcript tests, harness mock
tests. Source: `.scratch/upstream-drift/2026-10-25.md` § B4.

- [x] run_errors classifier + mock `/error` + desktop cards ported
- [x] mobile layout rows ported (for ticket 18)
- [x] wiregen fresh
- [x] Commit records upstream SHA `f30a8641b` (#967, slice)

## Comments

- 2026-10-26: Ported in the wave9/ui commit "Error cards: Log in for
  signed-out and limit-reached runs" (upstream `f30a8641b`, #967, portable
  slice; SHA recorded in the phase-end reference commit). Carried: new
  `crates/proto/src/run_errors.rs` (`ProviderProblem` +
  `provider_problem` classifier, near-verbatim; the credit-exhaustion
  guard kept with a product-neutral comment, fixtures adapted to Roboco's
  real message shapes — "Claude Code isn't signed in. Open Settings →
  Accounts …", "Signed out — sign in again" — with upstream's
  Cloud-credit fixtures dropped); mock `/error <message>` prompt (Error
  event with `kind: None` — Roboco's AgentErrorKind is additive — plus
  Errored Done) and its `scripted_error` test; `notice.rs` gained
  `notice_chip_with_action` (borderless 0.1-wash tile, lighter-red
  message, copy pinned in the header, action under the message);
  `error_chip` labels "Signed out"/"Limit reached" and renders the
  compact red-tinted Log in button, emitting `TranscriptEvent::SignIn`;
  `notice_row` in `crates/mobile/src/layout/rows.rs` (RowKind::Notice,
  DangerWash/DangerSoft color roles, WidgetKind::Action `sign-in`) replaces
  the error `chip_row`, with the upstream layout test ported (entries built
  with Roboco's extra doc fields). Desktop `sign_in_for_chat` **adapted**:
  Roboco keeps Accounts as its own Settings section (upstream folded it
  into Providers with an embedded per-provider Accounts page +
  pending_sign-in machinery) and has no cloud/device routing for logins —
  so Log in opens Settings → Agents and calls the new
  `AccountsPage::start_first_login` (first `login_options` provider)
  directly, gated on `signs_in(harness)`. The engine contract is
  untouched: Roboco's structured `AgentErrorKind` (orchestration v2)
  stays additive; `run_errors` classifies the message text every client
  reads, as upstream intends. Excluded by intent: all iOS app files
  (`apps/ios/**`, bottom bar, session delete — Android parity = ticket
  18), `crates/client` demo hunks (no such crate here), upstream's
  `sign_in_opens_the_agent_once_its_rows_land` harnesses-page test (that
  page's pending-sign-in machinery does not exist in Roboco's
  adaptation). Wiregen: regenerated (no TS surface change — run_errors
  has no ts-rs derive, matching upstream) and `--check` fresh.
  Verification: proto `run_errors::tests` 1/1; harness
  `mock::tests::error_prompt_names_the_failure` 1/1; mobile layout suite
  56 passed/1 ignored (incl. the ported
  `errors_render_as_cards_with_a_log_in_button_when_signing_in_fixes_them`);
  ui `transcript::` 138/138, `settings::` 170/170, `shell::` 236/236,
  `notice` 8/8; `cargo check -p roboco-ui`/`-p roboco-mobile` clean.
  Note: mid-verification `cargo run -p wiregen` failed compiling
  roboco-engine with errors naming fields Roboco's proto does not have —
  that was cross-worktree artifact aliasing in the shared
  CARGO_TARGET_DIR (the engine lane's proto artifacts owned the shared
  unit hash); re-stamping my own proto (`touch` + rebuild) restored a
  consistent build. No Roboco code was at fault.
