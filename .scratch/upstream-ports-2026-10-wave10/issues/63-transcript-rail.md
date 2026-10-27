# 63 — Transcript rail: one tick per prompt, prompts land below the titlebar, the rail scrolls, Markdown preview card (wave-9 12 + #1067 rail hunk)

**Lane:** 4b Features: desktop UI. **Effort:** M.

**What to build:** Carried from wave-9 ticket 12, now including the
follow-up. Roboco still buckets ticks past 12 (`tick_buckets`/`bucket_of`,
`rail.rs:145-159,462-469`), so most prompts in a long chat are
unreachable. Take upstream's final `crates/ui/src/rail.rs` (1015 lines vs
Roboco's 807) by intent:
- one tick per prompt;
- a click lands the prompt just below the titlebar;
- long chats scroll the rail;
- the `transcript.rs` hooks and `examples/rail-fixture.rs`.
Then apply #1067's rail hunk (+343), deferred from ticket 30: the
rail's preview card renders the prompt as Markdown. Keep ticket 29's
parser for the prompt text (no raw routing headers in the rail). #793
(the last bucket points at its first prompt) disappears with buckets.
Confirm, and port its test if it still applies.

**Upstream:** `ddf931624` (#958), `d460e8fe1` (#1049), the `rail.rs` hunk
of `46d4e42b3` (#1067). Skip `a4187fd51` (reverted upstream).

**Roboco files:** `crates/ui/src/rail.rs`, `crates/ui/src/transcript.rs`,
`crates/ui/examples/rail-fixture.rs`, `crates/ui/Cargo.toml`.

**Excluded:** screenshots.

**Proto change:** no.

**Blocked by:** 57 (pin compat), 30 (#1067 transcript slice), 29 (header parser).

**Status:** ready-for-agent

**Source:** wave-9 `issues/12-transcript-rail.md`; `part-07.md`
`ddf931624`/`d460e8fe1`; `2026-10-28.md` late window #1067.

- [ ] 50 prompts → 50 ticks; a click lands below the titlebar; the rail scrolls (rail-fixture + tests)
- [ ] The preview card shows Markdown and no routing header
- [ ] #793 obsolete or fixed
- [ ] Commit records `ddf931624`, `d460e8fe1`, the `46d4e42b3` rail hunk; absorbs wave-9 12

## Comments
