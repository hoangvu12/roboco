# 30 — Markdown in user and agent message bubbles (#1067, transcript slice)

**Lane:** 2 Messages. **Effort:** M.

**What to build:** User bubbles and agent-sent bubbles render Markdown:
lists, inline code, file links ↗, emphasis, block quotes. That's the
target look from the user's 10-28 screenshot, with #1052's caption
above it. Port `render_user_markdown` / `plain_markdown`, the
`crates/markdown` `parse_message` entry point and its `parser.rs`
changes (+46), and the `message-markdown-fixture` example. Selection,
copy and the bubble's hover/edit affordances keep working. Copy yields
the raw text.

**Upstream:** `46d4e42b3` (#1067): `crates/markdown/src/{lib,parser}.rs`,
`crates/ui/src/markdown/mod.rs`, `crates/ui/src/transcript.rs` (+486/−215),
`crates/ui/examples/message-markdown-fixture.rs`.

**Roboco files:** `crates/markdown/src/{lib,parser}.rs`,
`crates/ui/src/markdown/mod.rs`, `crates/ui/src/transcript.rs`,
`crates/ui/Cargo.toml`.

**Excluded:** **the `crates/ui/src/rail.rs` hunk (+343, the rail's
preview card).** It rides the rail rewrite in ticket 63, because the
card exists only after #958/#1049. Screenshots (binary). Mobile is
unaffected: the iPhone keeps user bubbles plain, and `parse_message` is
unused by `crates/mobile`.

**Proto change:** no.

**Blocked by:** 29 (caption + bubble layout), 02 (the same
`crates/markdown/src/parser.rs`; the nesting cap and guard must cover
`parse_message` too).

**Status:** ready-for-agent

**Source:** `2026-10-28.md` late window (#1067); `pass2-features-desktop.md` §4.

- [ ] A user bubble with a list, inline code and a file link renders them; copy gives the raw text (UI test)
- [ ] An agent-sent bubble under its caption renders Markdown
- [ ] A hostile-nesting message in a bubble doesn't crash (reuses 02's guard; test)
- [ ] Commit records `46d4e42b3` with the rail hunk deferred to ticket 63

## Comments
