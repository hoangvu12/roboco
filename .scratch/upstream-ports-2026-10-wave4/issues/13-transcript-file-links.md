# 13 — Transcript file links: open encoded and out-of-folder paths, real names

**What to build:** Two related link fixes: (1) agent links often
percent-encode a path or point outside every checkout, and both
dead-ended — decode the destination once (a broken escape keeps the raw
spelling), widen resolution from the linking chat's checkout to its
parent's and this device's project roots, and let an absolute path no root
owns open read-only as a host file (absolute breadcrumb, "Read-only:
outside this chat's folder." line, no workspace watch, `.md` rendered in
the preview, local links left as plain text); a missing file reads "File
not found." instead of the RPC framing. Engine side: accept an absolute
path on chat file targets — inside the chat's root it reads like the
equivalent relative path (editable, checkout-bound); outside it is a host
file through the same size/encoding/binary checks, reported with the new
`outsideWorkspace` read-only reason and no checkout identity; writes and
space targets keep rejecting absolute paths. Resolved links grow a
trailing open glyph (reserved NBSP slot so copy/selection see the raw
markdown) and a file context menu (Open in Roboco, Open with default app,
Show in folder, Copy file path — system rows only when the owning chat is
on this device); the transcript memoizes roots per chat from an app-state
revision. (2) unlabeled file links show the final path component (keeping
`:line`/`#L` suffixes) with the full path in the tooltip and on copy;
code-span/bare-path labels disambiguate same-named files within a part;
`[label](path)` links render unchanged.

**Blocked by:** None.

**Status:** ready-for-human

**Upstream SHAs:** `2a38baa4` (#606, 18 files) + `bc136867` (#633, 4
files). Path map: upstream `crates/markdown/src/parser.rs` → our
`crates/ui/src/markdown/parser.rs`; `markdown/inline_code_links.rs` is
born in these commits — create it; the rest (`files/{client,preview,
image_preview,markdown_preview,mod}.rs`, `markdown/{link_interaction,
link_presentation,render,mod}.rs`, `workspace_links.rs`, `transcript.rs`,
`shell.rs`, `state.rs`, `engine/src/workspace_files.rs` +
`engine/tests/workspace_files.rs`, `proto/src/entities.rs`) exist here 1:1.
The proto `outsideWorkspace` reason is a wire change: run `wiregen` FIRST,
then build the web side on the fresh types (web-codegen gate).
**Web parity (deliverable):** `web/packages/app/src/lib/links.ts` already
carries the workspace-link resolution with percent decode/encode — extend
it for out-of-folder host-file reads (absolute breadcrumb, read-only
presentation) against the regenerated wire types, and label unlabeled
links by file name in `components/markdown.tsx` + `lib/markdown.ts`
(full path on title/copy), matching the desktop renderer.

**Verification budget:** `cargo check -p roboco-engine -p roboco-ui -j 3`;
targeted nextest workspace_files + ui link tests; `pnpm -r build` after
`wiregen --check` passes.

- [x] Encoded links decode; out-of-folder links open read-only as host
      files with breadcrumb + context menu (desktop; web gets read-only +
      breadcrumb — its links keep the generic two-row menu)
- [x] Engine accepts absolute chat-target paths (outsideWorkspace reason,
      writes/spaces still reject)
- [x] Unlabeled links show file names; same-name disambiguation; labels
      unchanged for authored links (desktop; web labels `[](dest)` links —
      same-name disambiguation is inert there, it only feeds the
      inline-code rewriter web does not have)
- [x] Wire types regenerated; engine + ui tests green — types regenerated
      and `cargo check -p roboco` green; test execution deferred to the
      wave-final batched pass (user directive)
- [x] Web: out-of-folder links open read-only; unlabeled links show file
      names with full path on title/copy
- [x] Port commit records upstream SHAs

## Comments

Implementation landed on `wave4/files`.

Port mapping (upstream `2a38baa4` #606 + `bc136867` #633 → roboco):

- `crates/proto/src/entities.rs` — `WorkspaceReadOnlyReason::OutsideWorkspace`
  + serde round-trip test (wire change; `wiregen` regenerated
  `WorkspaceReadOnlyReason.ts`).
- `crates/engine/src/workspace_files.rs` + `engine/tests/workspace_files.rs` —
  chat-target absolute reads: `WorkspaceRelativePath::from_resolved`,
  `resolve_absolute_read`/`read_absolute_file_blocking` (canonicalize,
  inside-root = editable checkout-bound; outside = host file, stronger
  reason wins, empty checkout id, original absolute path kept), image reads
  bypass/re-check the checkout guard, writes and space targets unchanged.
  6 unit tests + 1 RPC e2e test (unix-gated like upstream).
- `crates/ui/src/markdown/parser.rs` — `InlineStyle::file_label` (bc136867) +
  the pre-parse angle-bracket rewrite for absolute destinations containing
  literal spaces (fences/indented code/code spans/bracketed/unclosed left
  alone; `Insertions` maps ranges back), corpus + `destination_rewrite_tests`
  (the already-bracketed test uses a bracketed source, matching its name —
  upstream's shipped test body lost the brackets, the bracketed spelling is
  the stronger assertion).
- `crates/ui/src/markdown/inline_code_links.rs` — new (upstream file 1:1,
  `link-menu-open-zeron` selector rebranded): inline-code spans naming real
  local files become Markdown links with file-name labels; per-part LRU
  memo keyed by link-roots revision.
- `crates/ui/src/workspace_links.rs` — upstream final state 1:1 with
  `zeron-file:` → `roboco-file:`: classify/decode-once grammar, outside
  absolute links, `first_root_owning`, `FileLinkRoot`/`FileLink`,
  `PathProbes`, `resolve_inline_code_path`, `without_location`.
- `crates/ui/src/markdown/{link_interaction,link_presentation,render,mod}.rs`
  — link_interaction/link_presentation copied from upstream post state with
  rebrand ("Open in Roboco", `open_web_links_in_roboco`, menu selectors);
  render grows `LinkUi{source_local,file_roots}`+`file_link()`,
  `FlatText::file_glyphs`, file_label relabel with `OriginalText` copy
  source, `FileLinkGlyphs` wiring.
- `crates/ui/src/files/*` — outside files: no watch (`path_is_outside`),
  image reads skip the checkout probe, `.md` previews strip local links,
  absolute breadcrumb, `outside_read_only_row`, `read_error_message`
  ("File not found."), `outsideWorkspace` read-only wording.
- `crates/ui/src/{state,transcript,shell}.rs` — `link_roots_revision` +
  `AppState::file_link_roots`/`chat_is_local`, transcript memoizes roots per
  chat and threads `inline_code_tree` through markdown/live rows,
  `link_ui(&mut self, cx)`; `Shell::open_workspace_file_link` resolves via
  the ordered roots (chat → parent → projects; project roots open by
  absolute path through the linking chat).
- Web parity: `lib/links.ts` resolution grammar ported (decode-once,
  outside links, new line/fragment parsing, mention rules, `withoutLocation`);
  `lib/markdown.ts` `fileLabel` + `[](dest)` unlabeled fallback +
  `fileNameLabel`; `components/markdown.tsx` file-name labels with the full
  decoded path on hover/copy and out-of-folder opens; `files-client.ts`
  outside image reads + "File not found."; `file-viewer.tsx` absolute
  breadcrumb + outside read-only row (+ `files-outside-banner` css);
  `markdown-view.tsx` outside documents keep local links as plain text;
  `markdown.test.ts` workspace-link tables ported (67 tests).

Exclusions / deliberate deviations:

- Upstream's engine test asserted against `zeron_rpc`/`zeron_proto` →
  `roboco_rpc`/`roboco_proto`; `EngineBootConfig` on web-less Roboco drops the
  edge/workos fields upstream's shell test passed (edge removed by ADR 0003).
- The shell test's `workspace_link` init stays `session_links`-shaped (no
  `file_roots` — `link_ui` fills them per chat).
- Web multi-root widening (parent/project roots) is not threaded through
  `MarkdownSurface` — the web surface keeps its single chat cwd, matching the
  ticket's named web scope (out-of-folder host-file reads); noted for a
  future ticket if remote-parent links matter on web.
- Web selection-copy keeps the displayed label (DOM selection cannot map
  back to the raw markdown the way the desktop's OffsetMap does); the
  full-path copy action covers the ticket's "on copy".

Verification: `cargo run --locked -p wiregen` (regenerated
`WorkspaceReadOnlyReason.ts`: +"outsideWorkspace"); `pnpm -r build` from
web/ (green after fixing one TS spread-order error);
`pnpm --filter @roboco/app exec vitest run tests/markdown.test.ts` (67
passed) and `tests/files-client.test.ts tests/markdown-doc.test.ts` (32
passed); `rustfmt --edition 2024` on touched files (reverted the drift it
introduced into untouched mod children); `cargo check -p roboco -j 3`
(green, no new warnings). Test execution deferred to the wave-final
batched pass (user directive). One wiregen run failed earlier with a
transient ENOSPC (concurrent lane build, disk since recovered); the retry
succeeded — the second run stayed within the 2-run budget.

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
