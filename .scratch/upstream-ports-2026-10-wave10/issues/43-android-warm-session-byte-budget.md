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

**Status:** ready-for-agent

**Source:** `2026-10-28.md` §A smaller (Android); `part-prs-B.md` #626.

- [ ] Eviction by estimated bytes, LRU, with the active/visible exemption (mobile-client tests)
- [ ] `onTrimMemory` shrinks the budget (JVM test, if wired)
- [ ] Mobile bench shows no open-latency regression for the six most recent sessions
- [ ] Commit records PR #626 (head commit read)

## Comments
