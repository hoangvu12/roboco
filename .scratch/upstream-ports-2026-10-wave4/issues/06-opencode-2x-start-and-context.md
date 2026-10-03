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

- [x] Pre-stream harness failures render as assistant entries
- [x] Cold version probe retried (30s budget), cache cleared on success,
      MCP-less start as last resort
- [x] 2.x context attributed from step.ended with cached step model;
      zero-window clearing; reconnect survival
- [ ] Tests green
- [x] Port commit records upstream SHAs

## Comments

**Port mapping (upstream 27480d99 #686 + a86f0587 #634):**

- `#686` → `Server::spawn` in `crates/harness/src/opencode/mod.rs`:
  sets both `OPENCODE_PASSWORD` (2.x reads it first; a user's own value
  401-locked us out of our server) and the legacy
  `OPENCODE_SERVER_PASSWORD`; the version probe now runs through
  `opencode_version()` — the shared cached probe first, then a 30s
  `COLD_VERSION_TIMEOUT` retry past a cached cold-start failure
  (OS malware scan on a freshly upgraded binary), invalidating the stale
  cache entry on success; an unknown version starts WITHOUT the injected
  `roboco mcp` block (warned) instead of failing the run. The engine's
  `drive_run` start-failure arm (`crates/engine/src/sessions.rs`) writes
  the error as a complete assistant `MessagePart::Error` entry before the
  Error/Done events, so the transcript shows the reason, not a bare
  "Run failed".
- `#634` → 2.x bus normalization: `session.step.started` records each
  session's latest model identity (`V2ModelIdentity`, malformed entries
  leave the cache untouched); `session.step.ended` feeds a synthetic
  usage `message.updated` with that model attached (providerID/modelID)
  so the context window resolves; the cumulative `session.usage.updated`
  frames are ignored (they measure spend, not occupancy, and drove the
  ring past 100%). The per-session model cache lives in `bus_task` so it
  survives bus reconnects, and overflow (4096) clears it instead of
  failing the run (unlike the pending-tools cache).
- `executable.rs`: `parse_version` made `pub(crate)` for the retry path.
- Rebrand: the injected server stays `roboco mcp` (upstream's `zeron`
  MCP block), `OPENCODE_CLIENT=roboco`, test fixtures read
  `config.mcp.servers.roboco` / `roboco_proto::McpServer`.
- Tests: opencode mod tests gain `unknown_version_starts_without_mcp_…`
  and `cold_first_version_probe_still_injects_mcp` (node fixtures; the
  cold one asserts both password vars carry ours); opencode tests.rs
  gains the long-context catalog model, the step-based
  `v2_wire_streams_text_and_settles_on_execution_success` sequence
  (per-step context events, zero-usage steps unreported), the
  normalize tests for attribution/malformed input, and
  `v2_session_model_overflow_drops_the_cache…`; the engine e2e gains
  `start_failure_lands_in_the_transcript` (a FailsToStart harness whose
  error must land as an assistant entry).

**Deviations:** upstream's #634 landed WITHOUT the explicit zero-window
`clearing described in its intermediate squashed commits — the final
squash ("opencode: keep 1.x context windows unchanged") reverted it, so
1.x windows and the shared doc `0 == clear` semantics are untouched here
as well; the acceptance line above is satisfied to the extent upstream
landed it (attribution + reconnect survival). No CI/edge/iOS hunks
existed in either commit.

Verification: `rustfmt --edition 2024` on the five touched files (drift
hunks reverted by hand); reading-only verification per the wave's build
economy; test execution deferred to the wave-final batched pass (user
directive).
