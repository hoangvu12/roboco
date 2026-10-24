# 02 — FFI: expose the Limited surface to Kotlin

Status: done

Blocked by: 01

## What

`crates/mobile/src/client_ffi` (the UniFFI surface Kotlin sees):

1. `failover.rs` (new, `pub use` in `mod.rs`):
   - `#[uniffi::Record] ResumeCandidates { chat_id, harness,
     limited_account_id: Option<String>, candidates: Vec<AgentAccount>,
     signed_out: Vec<AgentAccount> }` — reusing the existing FFI
     `AgentAccount` record (its usage windows, email, plan label and
     switchable flag are what the picker rows render).
   - `#[uniffi::Enum] ResumeSwitchOutcome { Switched { account_id,
     account_email }, NoCandidates, Failed { reason } }`.
   - `CoreClient::list_resume_account_candidates(chat_id)`,
     `CoreClient::resume_on_another_account(chat_id, account_id:
     Option<String>)`, `CoreClient::schedule_resume_at_reset(chat_id)`,
     `CoreClient::cancel_scheduled_resume(chat_id)` — async, on the
     client runtime (`on_runtime`), mapping `ClientError` into
     `CoreResult` like the accounts calls.
   - `pub fn resume_continue_prompt() -> String` — the one shared
     spelling of the continue prompt.
2. `session.rs`: FFI `ComposerState` gains `limit: Option<SessionLimit>`
   and `scheduled_resume_fire_at_ms: Option<i64>`; new
   `#[uniffi::Record] SessionLimit { harness, window, resets_at_ms:
   Option<i64>, account_id: Option<String> }`; conversion from
   `mc::ComposerState`.
3. Conversion tests in the client_ffi style (wire JSON → proto → FFI
   records; the tagged outcome's three arms).

## Definition of done

`cargo test -p roboco-mobile` green; `cargo check` clean. The generated
Kotlin surface grows the records/methods (verified by the Gradle build in
ticket 03).

## Comments

- Implemented: `crates/mobile/src/client_ffi/failover.rs`
  (`ResumeCandidates`, `ResumeSwitchOutcome`, the four `CoreClient`
  methods, `resume_continue_prompt()`); `ComposerState.limit` +
  `scheduled_resume_fire_at_ms` + the `SessionLimit` record in
  `client_ffi/session.rs`; refactored the accounts snapshot conversion to
  a reusable `From<roboco_proto::AgentAccount>` the failover rows share.
  Three conversion tests.

## Comments (review round)

- The FFI `harness_wire` copies deduplicated onto the accounts module's
  (now `pub(crate)`); the `From<ResumeAccountCandidates>` conversion
  keeps `Into::into` directly.
- Added the composer-limit conversion test the standards review asked
  for (`composer_state_carries_the_limit_and_scheduled_resume`).
