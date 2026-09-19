# 61 — Model picker: the first-connect re-trigger lattice (dead picker until refresh)

**What to build:** Opening the model picker right after the browser first connects to the engine always yields a WORKING picker — the catalog loads or shows a real error with retry, never a dead/empty card — without requiring a page refresh. Ticket 38 fixed the boot race itself (verified 3/3 orderings by the research); this ticket closes the four remaining holes in the re-trigger lattice.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

**Research:** `../research-2026-09-20/nav-picker-diff-subagent.md` — S2 (the four holes, with file:line, and the desktop's every-open force discipline at pickers.rs:4164-4168).

**Desktop reference (for lookups only):** `crates/ui/src/pickers.rs:4164-4168` (`ensure_harnesses` runs on EVERY open + per render — the client never waits for an event to re-kick).

## 1. Context a fresh session needs

- Ticket 38 landed: error-aware loading semantics (lib/catalog-loading.ts), the status-change heal (`picker-catalog.ts` `#retryOfflineSlots` + `slotNeedsRetry`), the per-commit Idle cadence, the window-focus re-arm. The research verified the boot race itself is fixed (a repro passes 3/3 mount-before-dial orderings).
- The REMAINING holes (research S2, file:line in the research file):
  1. The open-force effect's deps `[catalog, opened]` — opening an ALREADY-OPEN card does not refire the force (the desktop force-runs on every open).
  2. `#harnessesInFlight` swallows re-kicks up to the 30s unary timeout — a wedged in-flight request blocks every later heal attempt.
  3. Only the FIRST dial emits a `"connecting"` status (client.ts:309-311) — later reconnects never re-emit, so the status-change heal's non-connected trigger never fires on them.
  4. Only the literal `"Engine is offline; reconnecting"` error message re-arms (client.ts:241 literal compare) — any other error string leaves the slot permanently Error with no retry path.

## 2. Spec

1. **Every-open force**: the picker's open path force-runs `ensure`-equivalent logic on EVERY open (match the desktop's pickers.rs:4164-4168 discipline — cheap, unconditional, guarded by `shouldReload`'s Ready-shortcut so it stays a no-op when warm).
2. **In-flight escape**: `#harnessesInFlight` gains a bounded lifetime (research suggests the family precedent: a 10s ceiling like the registry's identity-call cap) — an in-flight load older than the bound is considered lost and the slot re-kickable; the 30s unary timeout stops gating the lattice.
3. **Reconnect emission**: every dial (not only the first) emits the status the heal listens on (client.ts:309-311) — or the heal subscribes to a signal that re-fires on every reconnect (whichever matches the client's event model; keep it minimal).
4. **Error re-arm by status, not string**: replace the literal message compare with the connection-status/typed-error check the engine already exposes (the `slotNeedsRetry` predicate's offlineOnly arm keys on state, not text).

## 3. Pure logic to port

- The in-flight lifetime rule + the every-open force cadence — extend lib/catalog-loading.ts (pure, table-driven tests exist) and picker-catalog's tests with the four holes as named cases (one per hole).

## 4. Gaps this ticket closes

| item | kind | desktop value (file:line) | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| Force on every open | behavior | pickers.rs:4164-4168 | deps `[catalog, opened]` skips re-open | §2.1 |
| Wedged in-flight blocks heal | behavior | no such gate | #harnessesInFlight, 30s swallow | §2.2 |
| Reconnects re-emit status | behavior | n/a (no dial concept) | first dial only, client.ts:309-311 | §2.3 |
| Error re-arm | behavior | state-based | literal message compare (client.ts:241) | §2.4 |

## 5. Do not

- Do not rework the catalog store's landed 38 logic beyond the four holes.
- Do not add polling loops (the cadence stays event/interaction-driven).
- Do not touch the Rust engine.

## 6. Acceptance

- [ ] The four named unit cases green (one per hole) in the catalog/picker suites.
- [ ] Manual sequence (code-traced or fake-session test): mount page → engine connects AFTER mount → open picker immediately → harnesses/models load (or a real error + working Retry). No refresh.
- [ ] `pnpm -r build` + `pnpm test` green.

## Comments

(User report 2026-09-20 #5 — "when i first connect to the engine, the model picker just dont work at all, until i hit refresh".)
