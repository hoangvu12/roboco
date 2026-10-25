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

**Status:** ready-for-agent

**Verification budget:** wiregen, ui notice/transcript tests, harness mock
tests. Source: `.scratch/upstream-drift/2026-10-25.md` § B4.

- [ ] run_errors classifier + mock `/error` + desktop cards ported
- [ ] mobile layout rows ported (for ticket 18)
- [ ] wiregen fresh
- [ ] Commit records upstream SHA `f30a8641b` (#967, slice)

## Comments
