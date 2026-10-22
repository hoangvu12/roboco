# 04 — Composer: usage reset times on hover (upstream #873, `09beace00`)

**What to build:** Port `09beace00` (#873): the composer's accounts
popover drew the same usage meters as Settings → Accounts but dropped
their reset-time tooltip. Share the tooltip text via `usage_resets` and
attach it to the popover meters too.

**Why now:** two files (+60): `crates/ui/src/account_usage.rs` (+28) and
`crates/ui/src/settings/accounts.rs` (+48). Roboco holds both files
(engine-local agent accounts — same lineage the Android accounts wave
built on).

**Steps:**
1. `git show 09beace00`.
2. Port the `usage_resets` plumbing and the hover tooltip onto roboco's
   meters. **Verify first** that roboco's account snapshot carries reset
   timestamps; if the field is absent, port the engine-side plumbing or
   adapt to whatever reset data roboco's accounts model exposes — do not
   invent a cloud account fetch.

**Excluded upstream hunks:** none expected; any hunk that reads a cloud
account service adapts to the engine-local accounts model instead.

**Blocked by:** None.

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-ui` + account usage
tests. Source: `.scratch/upstream-drift/2026-10-22.md` § account-adjacent
pair.

- [ ] Reset-time tooltip shared between popover and Settings meters
- [ ] Commit records upstream SHA `09beace00` (#873)

## Comments
