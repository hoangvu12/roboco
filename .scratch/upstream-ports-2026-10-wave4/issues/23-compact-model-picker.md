# 23 — Compact model picker and effort controls

**What to build:** An optional compact model picker for the composer (on
by default, General settings): effort slider with fast-mode toggle,
provider button + provider page (Starred first), per-model effort memory,
Cursor effort options on the slider, page-scoped keyboard shortcuts, and
per-edge/per-pixel edge fades on the picker surfaces. Follow-up #721: the
compact panel and composer chip name the selected model through one
resolver (the panel no longer reads "Select model" while the chip names
the pick), plus a regression test that settings toggles survive
navigation saves. Drift fold (post-window, see
`.scratch/upstream-drift/2026-10-02.md`): three same-surface follow-ups
landed upstream after the wave's closing fetch and ride this ticket —
#744 (composer's session branch label uses free width, fades on
overflow), #745 (`F` toggles fast mode in the compact picker), #749 (fix
compact picker browsing across providers).

**Blocked by:** None. (Composer + pickers substrate shipped; the effort
ladder and model context exist on both desktop and web.)

**Status:** ready-for-agent

**Upstream SHAs:** `a73fa8fe` (#471) — 27 files incl. new
`crates/ui/src/pickers/compact.rs` (our `pickers.rs` gains the
`pickers/compact.rs` submodule), `glass.rs` (upstream's new glass helpers
— map onto our `frost.rs`, do not duplicate), edge_fade.rs `fade_scroll_x`
+ per-pixel ramps, icons, haptics, popover, settings/composer.rs,
shell/tabs.rs, state.rs, plus screenshot docs (skip the
`docs/screenshots/compact-picker/` artifacts). `ae4181f5` (#721) —
`pickers.rs`, `pickers/compact.rs`, `shell.rs`. Drift additions, all
desktop-only (`crates/ui`, no edge/): `c14d4579` (#744) — `pickers.rs`
(26+/7-); `542febc1` (#745) — `pickers.rs` + `pickers/compact.rs`
(22+/2-); `e94c49ad` (#749) — `pickers.rs` + `pickers/compact.rs`
(38+/10-). Port them on top of the #471/#721 surfaces this ticket
builds — they are fixes to the picker this ticket creates, not
independent work. **Web parity
(deliverable):** `web/packages/app/src/components/composer-pickers.tsx`
+ `lib/model-rows.ts` + `lib/picker-search.ts` — port the compact layout
(effort slider + fast toggle on the picker surface, per-model effort
memory persisted through `state/ui-settings.ts`, starred-first provider
page). Page-scoped shortcuts map onto the web keymap.

**Zui pin caveat:** upstream's message cites `zui 667d0aa` for the
per-edge/per-pixel fades. Our pin is `c2d273dc`. Read the `edge_fade.rs`
paint hunks first: if the required gpui APIs are not in our pin, land a
`hoangvu12/zui` branch with the needed change and bump pins + patch revs
together (AGENTS.md rule) — as a separate prerequisite commit, not part
of this ticket's diff.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
pickers/compact + composer tests; `pnpm -r build` for web parity.

- [ ] Compact picker with effort slider, fast toggle, per-model memory,
      provider page (starred first), page-scoped shortcuts
- [ ] Panel and chip agree on the model name (#721)
- [ ] Drift follow-ups: branch label width/fade (#744), F fast-mode
      toggle (#745), provider browsing fix (#749)
- [ ] Settings-survive-navigation regression test
- [ ] Edge fades follow the picked approach (zui check recorded)
- [ ] Tests green
- [ ] Web: compact picker layout + effort memory + starred-first page
- [ ] Port commit records upstream SHAs

## Comments
