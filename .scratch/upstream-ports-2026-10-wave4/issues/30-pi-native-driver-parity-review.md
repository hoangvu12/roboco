# 30 — Review: upstream's native Pi RPC driver vs ours (parity + adoptables)

**What to build:** NOT a port — our native Pi driver shipped roboco-first
(`1ef237d6` wire/normalize/lifecycle, `86cb3339` catalog/set_model/title,
`70e8e8d0` MCP extension bridge) and ticket 21 recorded "when they write
it, their MCP wiring will port cleanly onto our native PiHarness."
Upstream #630 has now landed the same architecture (native RPC mode, per-
run mjs MCP delegation). Produce a comparison decision record: read
upstream's `crates/harness/src/pi/*` (mod/catalog/normalize/rpc/sessions/
ui/mcp) and its engine-side changes, diff the behavior surface against
ours, and adopt only what we lack. Known candidates from the commit
message:

1. **120s startup budget for cold extensions** — ours defaults to 60s
   (`with_startup_timeout` exists; check what callers pass; extensions
   can start cold).
2. **Dropped input-resolver semantics** — upstream's engine
   input-cancellation task turned a dropped resolver into an empty
   answer; they keep it an error for harnesses (opencode rejects on
   error). Check our `respond_input`/resolver drain paths (sessions.rs
   ~789, ~2637) for the same trap.
3. **`pi/PROTOCOL.md`** — port as an internal reference doc (rebranded)
   if ours lacks an equivalent digest.
4. Anything else materially better in their sessions.rs (resume pointer
   handling, steering bursts in the same model step, nested-agent env
   scrub on the native path — ours scrubs in `acp/child.rs` for ACP
   children; verify the pi native spawn path also removes
   `CLAUDECODE`-style markers).

**Blocked by:** None. (Run first — read-only, informs nothing else.)

**Status:** ready-for-agent

**Upstream SHAs:** `df0cd298` (#630) — reference only. **Do not** port the
commit, its `acp/child.rs` → `process/owned.rs` move, the ACP retirement,
or its CI/iOS files. Any adoptable fix lands as a small separate commit
citing the specific sub-fix, with `Status` here flipped to resolved and
the decision recorded below.

**Verification budget:** reading + `git show`; any adoptable patch gets
its own targeted test run (pi harness tests). No structural convergence,
no driver rewrite — ours is the shipped one.

- [ ] Decision record written (adopted / already-covered / rejected per
      candidate)
- [ ] Any adopted fix lands separately with its own tests
- [ ] Ticket resolved with findings under `## Comments`

## Comments
