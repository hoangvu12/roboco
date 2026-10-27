# 50 — `show_image`: agents put local images in the chat; folder-jailed image reads (#1036, part 1) — PROTO

**Lane:** 4a Features: engine + agents. **Effort:** M.

**What to build:** the engine and MCP half of #1036.
- MCP tool **`show_image {path}`** in `crates/roboco-mcp/src/tools.rs`.
  It publishes the image into the calling chat as the generated-image
  part (`PublishImage`), so Android shows it with no app change (it
  reuses the generated-image row) and desktop shows it through ticket
  27's renderer.
- **`ReadChatImageChunk`:** chunked, folder-jailed reads of images a
  chat references (`engine/src/uploads.rs` +424). It refuses paths
  outside the chat's folder and the filesystem root. Port the portable
  root-refusal test fix, which matters on Windows (`C:\`, `\\?\C:\`).
- `docs/mcp.md` documents the tool.

**Upstream:** `5d2b91901` (#1036): `engine/src/{uploads,rpc}.rs`,
`rpc/src/lib.rs` (method), `proto/agent.rs` (+12), `mcp/src/tools.rs`,
`docs/mcp.md`.

**Roboco files:** `crates/engine/src/{uploads,rpc}.rs`,
`crates/rpc/src/lib.rs`, `crates/proto/src/agent.rs`,
`crates/roboco-mcp/src/tools.rs`, `docs/mcp.md`.

**Excluded:** `pull_request_media.rs` (Roboco has no PR board),
cloud/edge hunks.

**Proto change:** yes (`ReadChatImageChunk`, `PublishImage`, additive
`agent.rs` fields).

**Blocked by:** 27 (desktop image renderer).

**Status:** ready-for-agent

**Source:** `2026-10-28.md` §B #1036; `part-07.md` `5d2b91901`.

- [ ] `show_image` from an agent shows the image on desktop and Android (engine e2e + mobile layout test)
- [ ] Reads outside the chat folder and of the root are refused, on Windows too (ported tests)
- [ ] Large images stream in chunks
- [ ] Commit records `5d2b91901` (engine/MCP half)

## Comments
