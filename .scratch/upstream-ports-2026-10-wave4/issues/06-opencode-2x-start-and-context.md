# 06 — OpenCode 2.x: start failures, cold version probe, context usage

**What to build:** Three 2.x behaviors: (1) a run whose harness fails
before streaming now writes the error as an assistant entry instead of a
bare "Run failed"; (2) the `opencode --version` probe that picks the MCP
config shape caches failures per binary — a cold first exec (OS malware
scan on a freshly upgraded binary) insta-failed every later run — so the
run path retries past the cached failure with a 30s budget, clears the
stale cache entry on success, and only if that also fails starts the
server without the MCP block plus a warning; (3) 2.x usage frames omit the
model, so context capacity never resolved — remember each session's latest
step model, feed context (and last-step usage) from
`session.step.ended` (that step's own tokens, not the cumulative session
total which drove the ring past 100%), treat an advertised model without a
limit as an explicit zero window, clear stored capacity on explicit zero,
and let the per-session model cache survive bus reconnects while
overflowing clears it instead of failing the run.

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `27480d99` (#686) + `a86f0587` (#634) —
`crates/engine/src/...` + `crates/harness/src/opencode/mod.rs` (4+2 files).
Our opencode harness is `crates/harness/src/opencode/{mod,tests}.rs`;
the version-probe/MCP-shape code and usage normalization map onto it.
Renames: the injected server is `roboco mcp` (upstream: `zeron` MCP block).

**Verification budget:** `cargo check -p roboco-engine -p roboco-harness -j 3`;
targeted nextest opencode suites (harness + engine e2e opencode tests);
fake-server fixtures as upstream did.

- [ ] Pre-stream harness failures render as assistant entries
- [ ] Cold version probe retried (30s budget), cache cleared on success,
      MCP-less start as last resort
- [ ] 2.x context attributed from step.ended with cached step model;
      zero-window clearing; reconnect survival
- [ ] Tests green
- [ ] Port commit records upstream SHAs

## Comments
