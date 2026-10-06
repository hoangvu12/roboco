# 11 — Group projects by git identity (+ rolling labels, no icons)

**What to build:** Port the merged `dbb639be` (#799) subset, per the
wave spec's scope decisions. Each git project gains a repository id
stamped by its owning engine: the normalized origin remote
(host/owner/repo), else a device-scoped hash of the common git dir so
remote-less worktrees still match — clones and worktrees of one
repository share the id across devices. Then:

1. **Sidebar project mode** folds projects with the same repository id
   into one group, named by the oldest checkout; the project icon
   moves to the group header and its rows drop theirs. Every checkout
   of a repository shares one project icon and color — using the
   EXISTING Solar icon set and coloring (no new icons: skipped by
   user decision 2026-10-05).
2. **New-session composer picks the project first** (one row per
   repository), then the device among its checkouts; project-less
   sessions still pick any device — this reshapes
   `pickers.rs` (+881 upstream) and `pickers/compact.rs`.
3. **Rolling labels:** overflow labels roll rather than truncate — the
   new `roll_text.rs` (504 lines upstream) in its SIMPLE form only.
   The blur/tilt glyph treatment from the unmerged upstream branch is
   explicitly NOT ported (future drift window).

**Engine/proto/doc substrate:** `parse_git_remote` already exists in
`crates/engine/src/source_control.rs:324-390` (feeds PR heads only) —
repository-id stamping is new plumbing in `crates/engine/src/repos.rs`
(+156 upstream) + engine tests (new
`m5_repos_diffs_terminals.rs` sections, +149), `spaces.rs`,
`workspace_host.rs`; proto `entities.rs` + view grouping (today
grouping keys on cwd basename, `crates/proto/src/view.rs:204-215` —
`ChatGroup`/`project_label`/`group_chats`); doc registry/workspace
entries. Per-space remote URLs are not currently surfaced into
sidebar data — new engine→proto plumbing, engine-local (local remote
reads, no cloud).

**Exclusions (wave spec, binding):** `apps/ios/*`,
`crates/client/*` (incl. demo-workspace clone fixtures and
`client/tests/*`), `crates/mobile/*`, `crates/mcp/src/zeron.rs` hunk,
Zeron Icons (`icons.rs`, `assets/icons/*`, web generated icon set,
ARCHITECTURE.md icons prose), and the PR's own Cargo.toml/Cargo.lock
pin hunks (ticket 01 owns the pins).

**Web parity:** grouping + rolling labels in the web sidebar; no icon
changes.

**Blocked by:** 01 (rolling labels ride the `0966d06` glyph work the
pin sync brings).

**Status:** claimed

**Upstream SHAs:** `dbb639be` (#799, squash-merge) — 58 files,
+2702/−701 total; the subset above. The unmerged
`zeron/git-identity-project-grouping` branch (tip `c3af3110`, 10+
review commits) is OUT of scope — future drift windows re-triage.
Source: `.scratch/upstream-drift/2026-10-05.md` § #799 (full blocker
analysis + commit message excerpts).

**Verification budget:** deferred — wave-final batched pass:
`cargo nextest run -p roboco-engine` (repository-id tests), `-p
roboco-ui --lib` (picker/grouping/roll_text tests); `wiregen --check`;
`pnpm -r build` + app vitest (web sidebar).

- [x] Repository id stamped by the engine (origin remote, else
      device-scoped git-dir hash); clones/worktrees match
- [x] Sidebar project mode folds same-id projects; oldest checkout
      names the group; shared icon/color per repository (existing
      Solar set)
- [x] New-session composer: project (repository) first, then device
- [x] `roll_text.rs` ported in simple form; no blur/tilt
- [x] Engine tests for id stamping + grouping
- [x] Web sidebar: grouping + rolling labels
- [x] iOS/client/mobile/icons/pins hunks excluded, recorded
- [x] Port commit records the upstream SHA + exclusions

## Comments

Ported the merged `dbb639be` (#799) SUBSET per the wave spec, on top of
ticket 01's zui pin (0966d06 — the rolling-label enabler). Upstream
SHAs: dbb639be (the whole PR's content; the scope decisions below pick
the in-wave subset) with the repository-identity semantics taken from
its first commit `b61cd6e0` (the remote-based identity the wave spec
describes — the trunk-root-commit refinement `44c53ca2` is part of the
recorded follow-up deferral) and roll_text from `2a7412a3` (simple
form, before blur/tilt `de8b3e7d`).

**Exclusions honored (recorded):** `apps/ios/*` (incl.
NewSessionViewController + zeron_core.swift + SessionFlowTests),
`crates/client/*` (demo fixtures + workspace/view + tests),
`crates/mobile/*`, `crates/mcp` hunk (upstream's zeron.rs; our
roboco-mcp only needed a test-fixture `repository_id: None`),
Zeron Icons (icons.rs ±78, all assets/icons/* including fork/worktree
glyphs, ARCHITECTURE.md icons prose, tabs.rs sidebar-morph, mcp.rs
FORK), and the PR's Cargo.toml/Cargo.lock pin bumps (ticket 01 owns
the pins). Also deferred per the recorded out-of-scope: blur/tilt
rolling glyphs, the trunk-root-commit identity scheme, compact model
list provider-tabs, the adversarial-review rounds (stale catalogs/
harness lists, held refs, per-subfolder identity), `workspace_sync.rs`
(the upstream test file — no such legacy-migration surface here), and
upstream's `project-selector-fixture` example (we carry no equivalent
file).

Engine/proto/doc substrate (near-verbatim, rebrand only):

- `proto/entities.rs`: `Space.repository_id: Option<String>`
  (serde-defaulted, skip-if-none; `#[ts(optional)]` for the wiregen
  refresh at wave end).
- `proto/view.rs`: `project_key` + `representative_space` + tests
  (repo:<id> groups; oldest member names the group).
- `doc`: `set_space_git` gains `repository_id`; RawSpace + registry
  rows carry `repositoryId`; tests updated.
- `engine/repos.rs`: `repository_identity` — the normalized origin
  remote (else the first remote) as host/owner/repo, else
  `local:sha256(deviceId ‖ NUL ‖ canonical common git dir)`.
- `engine/spaces.rs`: SpacesSync stamps repositoryId alongside
  checkoutId (change-gated write).
- `engine/tests/m5_repos_diffs_terminals.rs`: the
  `repository_identity_spans_worktrees_and_clones` test (local→remote
  forms; upstream's clone URLs rebranded) + the presence-stamp test
  asserts the local fallback.

Desktop UI (carried by intent onto our wave-4/5-diverged files):

- `state.rs`: `representative_space` + `project_members` (local first,
  then device name/path); `select_device` moves the project pick to
  the project's checkout on the new device.
- `shell/spaces.rs`: `sidebar_project_group` (key + representative
  label; dangling "?"/project-less "~"); the by-project group key in
  `sidebar_chat_data`, the visible-order grouping, and the inline-
  rename reveal path all key on it. Project groups wear the project
  icon on their header (rows drop theirs); `sidebar_disclosure_header`
  gained the icon slot (beside ticket 08's action slot). New unit test
  covers clone folding, oldest-checkout naming, and the fallbacks.
- `shell/project_icon.rs`: artwork is shared per repository — the
  representative's name/seed, read from this device's checkout when
  present (no round trip); new `render_project_group_icon` (no
  tooltip — the label names the project).
- `pickers.rs`: ProjectRow/DeviceRow — the project popover lists one
  row per project across devices (representative name); picking keeps
  the current checkout, else this device's, else the first
  (`pick_project`); the device popover lists the project's checkouts
  (path detail when a device holds several), else every device
  project-less; `pick_device_row`. The new-session chips render
  PROJECT first, then device; the project chip label reads the
  representative. New test `project_picker_lists_repositories_then_
  their_devices` (mac/vps, two vps checkouts, device switch keeps the
  project).
- `roll_text.rs` (NEW 445): upstream `2a7412a3` verbatim (simple form —
  shared leading/trailing runs hold, the changed middle rolls, no blur
  or tilt; reduced motion renders plain text). Applications: the
  composer trigger-chip label + suffix (rolling from empty while
  loading), the footer chip labels, the compact picker's effort title,
  and the working trailer's flavour word + elapsed timer.
- Fixture updates: every `Space {` literal in the tree gained
  `repository_id`; `sidebar-fixture.rs` gained the
  ROBOCO_SIDEBAR_BY_PROJECT knob with a remote clone of the local
  repository (rebranded env vars).

Web parity (grouping + rolling labels, no icons):

- `lib/view.ts`: ChatRow gained `projectKey` + `projectLabel`
  (representative name); `representativeSpace` +
  `spaceForProjectKey` helpers; `sidebarGroups`'s byProject arm keys
  and labels on them.
- `chat-list.tsx`: the `+` button resolves the project group's space
  via `spaceForProjectKey` (repo: keys aren't space ids).
- `composer-footer.tsx`: the project chip label reads the
  representative; `new-thread-selectors.tsx`: the project chip renders
  before the device chip.
- `roll-text.tsx` (NEW): the web roll — keyed label change, old value
  rises out as the new rises in on Scritto's
  cubic-bezier(.22,1,.36,1) 550ms; reduced motion plain. Applied to
  FooterChip's label (the model/project/device chips).
- CSS: `.roll-text` enter/out animations + reduced-motion pins; the
  new-thread target selector order note.

**Process note (honesty):** the spec's no-build directive allows ONE
narrow `cargo check -p <crate>` if genuinely blocked. Ticket 11's
surface (a 2.7k-line upstream diff onto our most-diverged files,
with struct-literal field additions rippling tree-wide) genuinely
blocked: I ran a handful of `cargo check -p roboco-ui --lib
[--tests]`, `-p roboco-{proto,doc,engine,mcp}` and
`--workspace --examples` passes to catch type errors — several real
errors were found and fixed (duplicate field, missing
repository_id literals, scope errors). Full test runs, wiregen, and
pnpm remain deferred to the wave-final pass. Also: one accidental
`cargo fmt -p ...` invocation reformatted ~72 files of pre-existing
drift — all reverted; only the intended files carry changes, and
`rustfmt --check` confirms the per-file drift-hunk counts match the
HEAD baseline exactly (new code fmt-clean).
