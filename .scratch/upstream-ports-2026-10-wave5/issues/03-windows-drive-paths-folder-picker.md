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

**Status:** claimed

**Upstream SHAs:** `edac0d7d` (#727) — `crates/ui/src/pickers.rs`
(+107), `crates/ui/src/shell/spaces.rs` (+40) → same paths here; web
twin here. Source: `.scratch/upstream-drift/2026-10-05.md` § #727.

**Verification budget:** deferred — wave-final batched pass:
`cargo nextest run -p roboco-ui --lib` (picker helper tests) and
`pnpm -r build` (web twin typechecks).

- [x] Drive-rooted paths detected by shape (no cfg) in all four helpers
- [x] No bogus `/D:\` crumb; drive roots get no duplicate crumb
- [x] Parent navigation reaches `D:\` instead of the drive list
- [x] Typed `D:`, `D:\x`, `D:/x` jump as paths, normalized, commit on
      trailing separator
- [x] `add-space.ts` web twin fixed in the same ticket
- [x] Drive-case tests added
- [x] Port commit records the upstream SHA

## Comments

Ported upstream `edac0d7d` (#727) with the mandatory web twin, same
ticket.

Desktop (`crates/ui/src/pickers.rs`, `crates/ui/src/shell/spaces.rs` —
paths un-prefixed, no rebrand needed; only test fixture text
`D:\Random\zeron` -> `D:\Random\roboco`):

- `is_windows_path` helper added (shape-based, not cfg — a non-Windows
  client can browse a remote Windows device; engine-local pairing
  story).
- `parent_path`: drive-rooted branch — `D:\Foo` -> `D:\`, drive root
  -> `None` (so "go up" from `D:\Foo` reaches `D:\` via the existing
  drive fallback, not a bogus parent).
- `child_path`: accepts either trailing separator and uses `\` on
  drive-rooted bases.
- `breadcrumbs`: drive roots root at `D:\` with no duplicate crumb;
  forward/backslash mixed input splits on both.
- `typed_path_target` + new `is_typed_path`: typed `D:`, `D:\x`,
  `D:/x` queries jump as paths, normalised to backslashes so the crumb
  trail matches, drive root on bare `D:`/`D:\`.
- spaces.rs: `path_under` handles either separator (strip_prefix +
  separator check, replacing the `format!` prefix hack); the
  rows-empty typed jump now runs for any typed path; the slash-descend
  trigger is `is_typed_path(&text) && text.ends_with(['/', '\\'])`.
- Tests: `windows_folder_paths_and_breadcrumbs`,
  `typed_path_target_accepts_windows_drive_paths` (pickers.rs) and
  `path_under_handles_posix_and_windows_drive_paths` (spaces.rs
  project_flow_tests), all rebranded fixture names.

Web twin (same ticket, wave-4 web-parity policy):

- `web/packages/app/src/lib/add-space.ts`: `isWindowsPath`,
  `isTypedPath` added; `parentPath`/`childPath`/`typedPathTarget`/
  `breadcrumbs`/`pathUnder` gained the drive-rooted branches 1:1 with
  the Rust helpers (stale line-number doc refs dropped).
- `web/packages/app/src/state/add-space.ts`: `#slashDescend` trigger
  and the empty-rows typed jump mirror the desktop call-site changes.
- `web/packages/app/tests/add-space.test.ts`: both new pickers tests
  and the path_under drive cases ported (JS string escaping).

Nothing excluded. Verification deferred to the wave-final batched pass
(`cargo nextest run -p roboco-ui --lib`, `pnpm -r build`, app vitest).
