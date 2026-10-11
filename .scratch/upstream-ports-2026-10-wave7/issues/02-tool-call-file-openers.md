# 02 — Tool-call file openers: files panel + default editor (upstream #522)

**What to build:** Port `1074bc54` (#522): clicking the file badge on a
Read/Write/Edit/Patch tool row opens that file in the right-hand files
panel instead of only expanding the row — routed through the transcript's
existing workspace-link handler (same roots as Markdown file links: the
chat's checkout, its parent's, this device's projects; absolute host
files open read-only), stopping propagation so the accordion doesn't
toggle, with inert badges for paths the handler can't open. Second half:
an "Open in default editor" button in the file viewer's breadcrumb
toolbar next to "Reveal file in tree" (`open_with_system`), shown only
for workspaces on this device.

**Why now (verified 2026-10-11):** `chip_header_row`
(`crates/ui/src/transcript.rs:8698`) has no file-opener parameter and no
`ToolFileOpener` exists; the anchor `toolbar_button("files-reveal-active",
"Reveal file in tree")` (`crates/ui/src/files/preview.rs:2503`) has no
system-open button beside it; no `open_with_system` anywhere in roboco's
ui. The substrate is present — `file_link_roots`
(`crates/ui/src/transcript.rs:3432`) is the link route the badges ride.
Recommended on 2026-10-09, skipped by the perf wave.

**Steps:**
1. `git show 1074bc54` — two files (`transcript.rs`, `files/preview.rs`),
   ~276 lines with tests.
2. Port `ToolFileOpener` and thread it into `chip_header_row`; badges for
   openable paths route through the workspace-link handler with
   stop-propagation; inert otherwise.
3. Port the "Open in default editor" toolbar button beside
   "Reveal file in tree", gated on this-device workspaces.
4. Port the tests (badge routing, inert badges, editor button visibility).

**Excluded upstream hunks:** none expected; both files exist in roboco.
Adapt any `zeron_`/`ZERON_` identifiers per AGENTS.md.

**Blocked by:** None.

**Status:** done (ported in 90eb9c0c on wave7/ports)

**Verification budget:** `cargo check -p roboco-ui` + the ported
transcript/preview test files. Source:
`.scratch/upstream-drift/2026-10-09.md` § `1074bc54`,
`.scratch/upstream-drift/2026-10-11.md` follow-up 1.

- [x] `ToolFileOpener` ported and threaded through `chip_header_row`
- [x] "Open in default editor" button beside reveal-in-tree
- [x] Tests ported and passing
- [x] Commit records upstream SHA `1074bc54` (#522)

## Comments

Ported in 90eb9c0c. Roboco's surfaces matched upstream's pre-change code
(the badge block, `chip_header_row`/`chip_header`/`tool_chip` signatures,
`render_breadcrumb` reveal slot, `LinkUi`/`activate_link`/`LinkTarget`/
`LinkAction` API), so the port is near-verbatim. Adaptations: roboco's
`FilesRequestContext` carries an `engine: EngineKey` field (test constructs
`EngineKey::local()`), and `render_tool_group` is roboco's equivalent of
upstream's fold row fn (file_opener threaded to tool_chip + chip_header,
None for subagent chips). 1598/1598 ui lib tests.
