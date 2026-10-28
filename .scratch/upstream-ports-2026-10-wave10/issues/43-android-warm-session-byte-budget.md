# 43 — Mobile client: limit warm-session residency by estimated size, not count (#626)

**Lane:** 3 Android parity. **Effort:** M.

**What to build:** `crates/mobile-client/src/session/mod.rs:210` keeps
`WARM_SESSION_CAP = 6` sessions warm by count. Six long transcripts can
pin a lot of memory on a phone. Port #626's byte budget: estimate each
warm session's size (transcript rows + layout caches) and evict LRU over
a budget, keeping the count cap as a ceiling. Adapt it to Roboco's
mobile-client (it has fleet/rooms state the PR doesn't know about).
Keep the active session and the tablet's visible session exempt.

**Upstream:** open PR #626 (+342/−25).

**Roboco files:** `crates/mobile-client/src/session/mod.rs` (+ tests),
any Android memory-trim hook (`onTrimMemory`) that should shrink the
budget.

**Excluded:** iOS hunks.

**Proto change:** no.

**Blocked by:** 01.

**Status:** done (2026-10-28, `wave10/android`) — the Kotlin hook compiles in CI only

**Source:** `2026-10-28.md` §A smaller (Android); `part-prs-B.md` #626.

- [x] Eviction by estimated bytes, LRU, with the active/visible exemption (mobile-client tests)
- [x] `onTrimMemory` shrinks the budget (JVM test, if wired)
- [ ] Mobile bench shows no open-latency regression for the six most recent sessions (not run — see comments)
- [x] Commit records PR #626 (head commit read)

## Comments

2026-10-28 (wave10/android, lane 3): ported open upstream PR #626 (head
`86952e4da77d8eb2f4ff70f5dd4c09ab2b9a3a86`, read with `gh pr diff 626`) by
intent onto `roboco-mobile-client`.

- Roboco keeps no Loro doc on the phone, so the estimate is the session's
  serialized transcript (`serde_json` per entry, cached per
  `transcript_revision`) × 2, floored at 512 KB per open session (#626's
  `DOC_RESIDENT_FLOOR_BYTES`); `WARM_SESSION_BYTE_BUDGET` 32 MB as #626.
- `SessionRegistry::evict` → `evict_within(budget)`: sized outside the
  registry lock, #626's `plan_evictions` (LRU, the count cap kept as a
  ceiling, sessions that can't go still count, **the most recently used
  one is never picked**), re-checked under the lock before removal. The
  exemptions are Roboco's: view-attached (the open session and the
  tablet's shown one), streaming, pending sends.
- `Client::trim_memory(critical)` (FFI `CoreClient.trimMemory`): a quarter
  budget, or none when critical. Android `RobocoApplication.onTrimMemory`
  → `trimIsCritical(level)` (RUNNING_LOW → shrink; RUNNING_CRITICAL and
  the background levels → critical; UI_HIDDEN is the lifecycle's
  `onBackground`), run off main.
- Not ported: #626's `crates/client` room flush and `doc_host` constant
  move (no Loro rooms on Roboco's phone); the fleet/rooms state is
  untouched (only session cores are sized).
- Tests: `cargo test -p roboco-mobile-client --lib session::tests` (#626's
  five plan cases + the estimate floor), `--test session_engine
  a_critical_trim_keeps_only_the_shown_and_the_most_recent_sessions` and
  the existing `the_warm_set_keeps_at_most_six_detached_sessions`;
  `-p roboco-mobile --lib`; JVM `MemoryTrimTest`.
- Bench not run: the transcript bench is instrumented (CI emulator
  only). The per-open cost is one cached size lookup per open session
  (a re-serialization only for a session whose transcript changed).
