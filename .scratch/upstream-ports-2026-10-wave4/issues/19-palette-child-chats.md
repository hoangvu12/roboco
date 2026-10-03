# 19 — Command palette: exclude child chats

**What to build:** Child chats (agent-spawned side chats) no longer appear
as separate palette results — only their parents do.

**Blocked by:** None. (Side chats + child-chat concept shipped in wave 3.)

**Status:** ready-for-human

**Upstream SHAs:** `c74978ab` (#651) — 1 file,
`crates/ui/src/shell/command_palette.rs`. **Web parity (deliverable):**
`web/packages/app/src/lib/command-palette.ts` builds the chat rows —
filter out chats with a `parentChatId` (child chats) the same way; the
proto field already exists on web.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
command_palette tests; web `pnpm -r build`.

- [x] Child chats excluded from palette results (desktop + web)
- [x] Tests green (both desktop gpui tests and the web vitest ports written;
      execution deferred to the wave-final batched pass — user directive)
- [x] Port commit records upstream SHA

## Comments

- Ported `c74978ab` (#651) by intent into
  `crates/ui/src/shell/command_palette.rs` (`command_entries`): a
  `parent_chat_id.is_none()` filter before the query filter, so child
  chats (side chats and agent-spawned workers) no longer appear as
  separate palette results — only their parents do. Comment notes the
  sidebar parity (upstream's mentions "MCP workers"; ours says
  agent-spawned workers to match our Chat.parent_chat_id doc).
- Both upstream gpui tests ported into the module's `mod tests`:
  `history_excludes_child_chats_with_and_without_search` and
  `child_chats_do_not_consume_history_result_slots`, using the
  `right_tab_mouse_regressions`-style Shell fixture (our 3-field
  `EngineBootConfig` — upstream's edge_url/edge_token/org_id/
  workos_client_id dropped with the removed cloud) and `roboco_proto`
  instead of `zeron_proto`.
- Web parity: `web/packages/app/src/lib/command-palette.ts` —
  `paletteChats` skips chats with `parentChatId != null` (the proto field
  already ships on web); both regressions mirrored in
  `web/packages/app/tests/command-palette.test.ts` (exclusion with/without
  search, children cannot consume the 30-row cap).
- Exclusions: none — the upstream diff is the one-line filter + tests.
- Verification: `rustfmt --edition 2024 --check` clean;
  `pnpm -r build` from web/ after all four tickets' web edits (one build
  per chunk); `cargo check -p roboco -j 3` at chunk end; test execution
  deferred to the wave-final batched pass (user directive).

- Wave-final batched verification (2026-10-03, merged main `cf94f415`): one
  batched pass over all lanes — ui lib 1521/1521; engine 529/530 (the one
  failure is the documented pre-existing
  `previews::preview_watch_follows_the_session_checkout_and_owning_device`
  baseline); harness 504/509 (the five failures are the documented
  environmental `#!/usr/bin/python3` fixture shebang and uid-1001
  user-database quirks; CI runs them); mcp 26/26; voice 18/18; theme 31/31;
  `wiregen --check` and `roboco-theme-export --check` fresh; web `pnpm -r
  build` green, app vitest 2122/2122, engine-client vitest green. The
  deferred test-execution criterion is demonstrated; closed by the
  wave-final pass.
