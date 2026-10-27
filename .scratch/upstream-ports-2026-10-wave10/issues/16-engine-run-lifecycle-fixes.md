# 16 — Engine run lifecycle: retry/revival race guards, steer backpressure, parallel shutdown, boot salvage sweep (A37 engine slice, A17, A22, A16)

**Lane:** 1c Fixes: agents + orchestration. **Effort:** M (upper end).

**What to build** (`crates/engine/src/{sessions,doc_host}.rs`):
1. **A37 engine: race guards around start retries and revivals.**
   - Roboco checks Stop only during the backoff sleep
     (`sessions.rs:4800-4831`), and checks a live run only once, outside
     the run lock (`:1205-1210`).
   - Port `user_stops`/`stops_seen`/`abandon_retry`, the `superseded`
     check (a newer user message wins over a revival), and the re-check
     under the run lock (`dispatch_inner` ~`:940-1230`, `:4785-4866`,
     `note_user_stop` `:1614-1660`).
   - Tests: `a_stop_between_a_failed_start_and_its_retry_ends_the_turn`,
     `a_shutdown_during_a_start_retry_starts_nothing_and_revives_on_boot`,
     `a_revival_yields_to_a_message_sent_while_it_was_on_its_way`.
   - Unit tests: `tail_of`/`turn_tail_*`,
     `only_transient_start_failures_retry`,
     `resume_rejection_is_read_narrowly`,
     `a_leading_path_is_not_a_native_command`, `revival_prompt_carries_the_request…`.
2. **A17: steer burst backpressure.**
   - A full 32-slot steer mailbox makes `try_send` fail. The send then
     falls back instead of reaching the live turn (`sessions.rs:1090,1522`).
   - Use `steer_tx.reserve().await` before
     `registry.while_update_clear(...)`, with `permit.send` inside the gate.
   - Add upstream's `command_drain_lock` on `ChatDocHandle` (upstream
     `doc_host.rs:586,4886,5201,5265`). Run/Steer serialize on it; Interrupt
     and input controls bypass it, so a stalled provider can still be stopped.
   - Take the current upstream/main shape, not #498's. Watch for
     deadlocks against Roboco's `drain_lock`, the Stop/`await_earlier_controls`
     path and the CLI-update gate. Port the burst-steer message_queue test.
3. **A22 (open PR #792): shutdown stops live runs one by one,** about
   3 s each (`sessions.rs:2664-2668`). Settle them concurrently. Keep
   wave-9 06's shutdown-cut marking per run.
4. **A16 (#544 slice; narrows wave-7 16): boot salvage opens every
   journaled chat.**
   - `spawn_transcript_salvage` (`doc_host.rs:901`, from `lib.rs:269`) and
     `salvage_chat_transcript` (`:929`) call `open()` on every host chat
     with a journal. The result is a startup memory/CPU/IO spike, and
     fresh handles shielded from eviction for 30 s.
   - Inspect the stored snapshot plus pending updates with a raw
     `LoroDoc`; `open()` only when there's something to salvage, and
     retry a failed salvage later.

**Upstream:** `2e151ca65` (#977) engine slice; `731697b66` (#498)
residual + current upstream/main `command_drain_lock`; open PR #792;
`ed3df8fb1` (#544) portable slice.

**Roboco files:** `crates/engine/src/{sessions,doc_host,lib}.rs`,
`crates/engine/tests/{run_recovery,message_queue}.rs`.

**Excluded:** #544's cloud ~90% (sync budget, `sync_jobs`, chat2
checkpoint/rows HTTP, edge nudges, `ChatSyncState`); the sign-out
revival gate (Roboco keeps `forgo_revival()`).

**Proto change:** no.

**Blocked by:** 01. Lands before 47 and 55 (they edit `sessions.rs`).

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A37 (Engine/ACP), A17, A22, A16;
`pass2-big-1.md` gap 4; `part-04.md` `731697b66`, `ed3df8fb1`.

- [ ] The three race tests and the unit tests ported and green (`--test run_recovery`, `--lib`)
- [ ] A 64-steer burst reaches the live turn in order; Stop still interrupts a stalled provider (message_queue tests)
- [ ] Shutdown with N live runs takes about one grace period, not N
- [ ] Boot with 200 journaled, clean chats opens none of them (test counts `open()` calls)
- [ ] `message_queue`, `restart_resume`, `session_process_hygiene` green
- [ ] Commit records `2e151ca65` (engine slice), `731697b66`, PR #792, `ed3df8fb1` (slice)

## Comments
