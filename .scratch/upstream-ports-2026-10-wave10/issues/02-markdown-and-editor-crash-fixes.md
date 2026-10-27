# 02 — Crash fixes: Markdown nesting cap + streaming parity, files-editor stale highlights (A36, A36b, A5)

**Lane:** 1a Fixes: crashes + security. **Effort:** M (three S–M pieces).

**What to build:**
1. **A36: nesting cap + panic guard** (desktop and Android share
   `roboco_markdown`). A deeply nested message (`>` × 20 000 from an agent
   or a paste) overflows the stack. Roboco's parser recurses with no cap
   (`parse_started_block` ↔ `parse_block_sequence`), and a stack overflow
   is not caught by `catch_unwind`. Port upstream's `MAX_NESTING = 32` on
   block and inline nesting, plus the `catch_unwind` fallback: a
   pulldown-cmark panic renders the message as one plain paragraph.
   Android's `guarded_pass` (`crates/mobile/src/layout/mod.rs:669`) only
   catches ordinary panics.
2. **A36b: streamed Markdown matches the full parse.** Lazy-continuation
   lines after a quote or list. Reference-link definitions inside a quote
   or list, or with a two-line label, which must switch the parser to a
   full reparse. Task markers that pulldown emits after their item closed
   (and marker ranges that start at whitespace). Android keeps the
   streamed tree after the turn (`crates/mobile/src/layout/rows.rs:470-486`),
   so today the wrong render stays until the session is reopened.
   Optional, same commit: make `mend.rs` linear. It indexes open
   delimiters per character; Roboco's `mend.rs:245` `rposition` is
   quadratic on a long emphasis-marker tail.
3. **A5: files editor stale highlights** (#894). An open, clean file
   that changes on disk keeps its old highlight ranges. A range that ends
   inside a multi-byte char (`→`) panics in line layout, and the
   other ranges paint wrong colours. Port `unverified_source`,
   `source_matches_edited_text` and the first-update check in `update()`.

**Upstream:**
- `c02739da3` (#912), **robustness slices only**. Upstream/main line
  refs: `crates/markdown/src/parser.rs:168-186` (guard), `:207-211`
  (`MAX_NESTING`), `:399`, `:453`; `:466-500` (task-marker guard + range
  trim); `:2140-2160` (lazy-continuation reach); `:2284-2320`
  (`has_link_defs` + `strip_container_markers`); `mend.rs:56-100`. Tests:
  `hostile_nesting_and_parser_panics_are_contained`,
  `collapsed_deep_items_keep_word_breaks`,
  `deep_collapsed_content_keeps_its_text`,
  `lazy_continuations_stream_like_a_full_parse`,
  `quoted_and_listed_link_definitions_reparse_in_full`,
  `task_markers_stay_with_their_own_item`,
  `multi_line_reference_labels_reparse_in_full`,
  `streamed_regressions_match_full_parse` (without the math cases).
- `85006f4b4` (#894): `crates/ui/src/files/editor_adapter.rs` and its
  test-only `probe` module, plus the files-surface reload test.

**Roboco files:** `crates/markdown/src/parser.rs` (`:139-145`
`parse_full`/`parse_at`, `:521`/`:629` recursion, `:770` task marker,
`:1250-1258` reparse boundary, `:1312` `has_link_defs`),
`crates/markdown/src/mend.rs`, `crates/ui/src/files/editor_adapter.rs`
(`RobocoInputHighlighter`, `:29`). No Kotlin changes.

**Excluded upstream hunks:** all of zatex math (dollar rewriting,
`PMath`/`MathFace`/`formula`, `crates/text` inline boxes, `renderMath`
settings, the math font), because #912 stays declined. Ticket 30 (#1067)
edits the same parser later. Keep the cap and guard as one function so
30 rebases cleanly.

**Proto change:** no.

**Blocked by:** 01.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A36, A36b, A5; `pass2-features-android.md` N5;
`pass3-ios-2.md` G1; `part-07.md` `85006f4b4`.

- [ ] `>`×20 000 and `-`×20 000 messages render without a crash (parser test + one mobile layout test)
- [ ] A forced parser panic renders plain text (test)
- [ ] The streaming cases parse exactly like a full parse (ported tests, math cases removed)
- [ ] An Android layout test shows a finished turn equal to its full parse
- [ ] Editor reload with a multi-byte char after an external change: no panic, highlights re-derived (test)
- [ ] `cargo test -p roboco-markdown`, `-p roboco-mobile` layout tests, `roboco-ui` files tests green
- [ ] Commit records `c02739da3` (slices; math excluded) and `85006f4b4`

## Comments
