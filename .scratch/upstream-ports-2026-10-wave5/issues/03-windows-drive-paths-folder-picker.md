# 03 — Windows drive paths in the project folder picker

**What to build:** Port upstream `edac0d7d` (#727) so the add-space
folder browser understands Windows drive paths — on desktop AND web.
Upstream's commit message is our bug verbatim: the folder-browser
helpers (`breadcrumbs`, `parent_path`, `child_path`, `path_under`)
only understood `/` separators; `D:\` produced a bogus `/D:\` crumb
whose click failed in the engine with "os error 123"; going up from
`D:\Foo` fell back to the drive list; typed drive paths (`D:`, `D:\`,
`D:/x`) were treated as folder-name filters instead of path jumps.

**How (from the upstream commit):** detect drive-rooted paths **by
shape, not cfg** — a non-Windows client can browse a remote Windows
device (our engine-local pairing story) — and use `\` separators
there; drive roots get no duplicate crumb; typed drive-rooted queries
are recognized as path jumps, normalized to backslashes so the
breadcrumb trail matches, committing on a trailing `\` or `/`.

**Roboco gaps (verified):** `typed_path_target`
(`crates/ui/src/pickers.rs:373-395`) has no drive-letter branch;
`breadcrumbs` (`:399`) roots at `/` and splits on `/` only;
`child_path` (`:321`) mixes separators on drive paths; call sites at
`crates/ui/src/shell/spaces.rs:5211,5258,6026`; drive rows already
exist (`list_drives` RPC, `windows_drives()`, `DriveEntry` proto,
`spaces.rs:5094-5134`).

**Web twin is mandatory:** `web/packages/app/src/lib/add-space.ts:16-130`
is a recorded line-for-line port of the desktop helpers — the fix lands
in both, same ticket (wave-4 web-parity policy).

**Tests:** extend `typed_path_target` tests at `pickers.rs:8087+` with
drive cases (typed `D:`, `D:\x`, `D:/x`; crumb trails; parent of
`D:\Foo` → `D:\`).

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `edac0d7d` (#727) — `crates/ui/src/pickers.rs`
(+107), `crates/ui/src/shell/spaces.rs` (+40) → same paths here; web
twin here. Source: `.scratch/upstream-drift/2026-10-05.md` § #727.

**Verification budget:** deferred — wave-final batched pass:
`cargo nextest run -p roboco-ui --lib` (picker helper tests) and
`pnpm -r build` (web twin typechecks).

- [ ] Drive-rooted paths detected by shape (no cfg) in all four helpers
- [ ] No bogus `/D:\` crumb; drive roots get no duplicate crumb
- [ ] Parent navigation reaches `D:\` instead of the drive list
- [ ] Typed `D:`, `D:\x`, `D:/x` jump as paths, normalized, commit on
      trailing separator
- [ ] `add-space.ts` web twin fixed in the same ticket
- [ ] Drive-case tests added
- [ ] Port commit records the upstream SHA
