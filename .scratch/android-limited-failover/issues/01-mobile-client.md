# 01 — mobile-client: the Limited chain (composer limit + failover wrappers)

Status: done

## What

`crates/mobile-client` (the phone-side RPC client):

1. `session/composer.rs`: `ComposerState` gains `limit:
   Option<SessionLimitInfo>` and `scheduled_resume_fire_at_ms:
   Option<i64>`, derived in `build_composer` from the engine's session
   row via `roboco_proto::view::effective_indicator == Indicator::Limited`
   (Errored + a limit record; no staleness gate on Errored rows — proto
   `view.rs` is the one rule).
   `SessionLimitInfo { harness: String, window: String,
   resets_at_ms: Option<i64>, account_id: Option<String> }` is a plain
   client-side copy of the proto record (harness as the wire string).
2. New `failover.rs` module on `Client`:
   - `resume_account_candidates(chat_id)` → `LIST_RESUME_ACCOUNT_CANDIDATES`
     `{chatId: raw}` (resolve via `Client::resolve`, decode
     `roboco_proto::ResumeAccountCandidates`).
   - `resume_on_another_account(chat_id, account_id: Option<&str>)` →
     `RESUME_ON_ANOTHER_ACCOUNT` (`accountId` omitted when `None`) →
     `roboco_proto::ResumeOnAccountOutcome`.
   - `schedule_resume_at_reset(chat_id)` / `cancel_scheduled_resume(chat_id)`
     → `MUTATE` `{op: "scheduleResumeAtReset"|"cancelScheduledResume",
     chatId}` (plain `Ok(())`, the workspace Mutate pattern).
3. Tests: the composer limit derivation (Limited row → fields; Working
   row → None; cleared when the row has no limit) and decode-shape tests
   for the failover replies (camelCase, tagged outcome).

## Why

The engine ships `limit` on every session row it already pushes to the
phone; the mobile client drops it. The failover RPCs exist on the engine
and the web client but have no phone-side wrapper.

## Definition of done

`cargo test -p roboco-mobile-client` green with the new tests;
`cargo check -p roboco-mobile-client` clean.

## Comments

- Implemented: `ComposerState.limit`/`scheduled_resume_fire_at_ms` derived
  in `build_composer` via `effective_indicator == Limited`; new
  `crates/mobile-client/src/failover.rs` with the two failover RPC
  wrappers + the two Mutate ops. Tests: the composer limit derivation
  (`a_limited_row_carries_its_limit_and_scheduled_resume`) and the reply
  decode shapes. Rider: fixed the pre-existing missing
  `running_subagents` in `session/commands.rs`'s test fixture (E0063 on
  clean main).
- Note: `cargo test -p roboco-mobile-client --test real_engine` fails on
  clean main in this sandbox (network-flaky, 3 failures with and without
  the change); the lib suite is green.
