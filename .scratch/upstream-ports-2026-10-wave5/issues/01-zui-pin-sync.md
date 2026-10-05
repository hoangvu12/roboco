# 01 — zui + gpui-component pin sync (prep)

**What to build:** Sync Roboco's GPUI pins to upstream zeronsh/zui
`0966d065` and gpui-component `4764fd00`, per the AGENTS.md pin rule
(top-level `gpui` pin rev must equal the `[patch]` rev — one GPUI copy
in the graph).

**Why this is now cheap (verified 2026-10-05):** upstream zui main
(`0966d065`) already contains everything our fork ever carried — the
Windows backdrop renderer `c2d273dc` (merged via zeronsh/zui#10), the
Windows drag threshold, and per-edge fade bands (`667d0aa`, the commit
our pinned `1e1da652` was cherry-picked from). The only new work is
exactly one commit: `0966d06` "Transformed and blurred monochrome
glyphs" — the enabler ticket 11's rolling labels need. gpui-component
`4764fd00` is 5 upstream pin-commits ahead of our `94c1bbaf` (no fork
work) and already exists on `hoangvu12/gpui-component`.

**Steps:**
1. Push upstream zui's `0966d065` to our fork as a new branch: clone
   `zeronsh/zui` (a blobless clone exists at
   `/tmp/zui-sync-analysis/zeronsh-zui`), add `hoangvu12` as a push
   remote (`git@github.com:hoangvu12/zui.git` or the https remote with
   existing credentials), `git push hoangvu12
   0966d065b23e0b7c9b53c2e186705b8bce16fe2d:refs/heads/roboco/sync-2026-10`.
   The old `roboco/edge-fade-bands` branch stays for history.
2. `Cargo.toml`: replace rev `1e1da65271c093cb1115e8d4e2ab18721db5c8b1`
   with `0966d065b23e0b7c9b53c2e186705b8bce16fe2d` EVERYWHERE it
   appears (top-level `gpui`/`gpui_platform`/`gpui_tokio` + every other
   hoangvu12/zui dep + the entire `[patch."https://github.com/zeronsh/zui"]`
   section, ~14 entries), and `gpui-base` rev
   `94c1bbaf6311b9f36f5e7438aaf5aeadd38740da` →
   `4764fd002ab5894ec9413511d01d37481b2129ab`.
3. Refresh `Cargo.lock` for the changed git deps (lock-only `cargo
   update -p` invocations are allowed — they do not build; if the
   updater insists on more, defer the lock refresh to the wave-final
   pass and say so in the commit message).
4. Compatibility check by reading, not building: our `1e1da652` was
   cherry-picked from upstream `667d0aa` (ours +32/−9, upstream
   +39/−11 in `crates/gpui/src/window.rs`) — diff our consumer
   `crates/ui/src/edge_fade.rs` (`fade_scroll_x`) against
   `git show 667d0aa -- crates/gpui/src/window.rs` and adapt the
   consumer if the fade-band API surface moved.
5. Update the GPUI-fork section of `AGENTS.md` to the new pins (it is
   stale: it names `c2d273dc` while the manifest pins `1e1da652`).

**Blocked by:** None. First ticket.

**Status:** ready-for-agent

**Verification budget:** deferred — wave-final batched pass: `cargo
check --workspace --examples` (first compile; a pin-rule violation
shows up as ~50 GPUI type mismatches — two GPUI copies in the graph).
Source: `.scratch/upstream-drift/2026-10-05.md` (§ #799 blockers, pin
analysis).

- [ ] `roboco/sync-2026-10` branch on hoangvu12/zui at `0966d065`
- [ ] All zui pin revs (top-level + `[patch]`) == `0966d065…` — one GPUI copy
- [ ] `gpui-base` at `4764fd00…` (hoangvu12/gpui-component)
- [ ] `Cargo.lock` refreshed (or deferred, recorded)
- [ ] `edge_fade.rs` consumer verified against upstream `667d0aa` API
- [ ] `AGENTS.md` GPUI section updated to the new revs
- [ ] Commit records the fork-state rationale (all prior fork work
      upstream-merged; only `0966d06` is new)
