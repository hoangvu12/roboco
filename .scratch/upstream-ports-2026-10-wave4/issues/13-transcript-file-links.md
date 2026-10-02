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

**Status:** ready-for-agent

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

- [ ] Encoded links decode; out-of-folder links open read-only as host
      files with breadcrumb + context menu
- [ ] Engine accepts absolute chat-target paths (outsideWorkspace reason,
      writes/spaces still reject)
- [ ] Unlabeled links show file names; same-name disambiguation; labels
      unchanged for authored links
- [ ] Wire types regenerated; engine + ui tests green
- [ ] Web: out-of-folder links open read-only; unlabeled links show file
      names with full path on title/copy
- [ ] Port commit records upstream SHAs

## Comments
