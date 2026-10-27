# 56 — Devin Fusion in the model picker (A29)

**Lane:** 4a Features: engine + agents. **Effort:** M.

**What to build:** One picker entry stands for every Devin Fusion pair.
Hovering it opens a card laid out like Devin's own panel: Lead, Effort,
Sidekick, Fast Mode. Devin model variants are folded, reasoning words
are parsed (`split_effort`, `speed_option`), and rows that share a label
show their description (`mark_ambiguous`). Today Roboco's
`crates/harness/src/acp/devin_models.rs` is 195 lines against 763
upstream. It has no `FUSION`, `configured_in_place`, `SettingScope` or
`mark_ambiguous`. Wave-3 09 left it out as "unrelated squash content",
which was an agent's judgment and not a user decline.

**Upstream:** `731697b66` (#498 squash) + its later refinements on
upstream/main: `crates/harness/src/acp/devin_models.rs:91-101`,
`acp/mod.rs` `devin_effort_values`/`selection`, `crates/ui/src/pickers.rs:785`
(`mark_ambiguous`), `:838-850` (`SettingScope`, `configured_in_place`),
`render_config_card`, `card_order`. Open PR #446 has the same feature.
Take main's shape.

**Roboco files:** `crates/harness/src/acp/{devin_models,mod}.rs`,
`crates/ui/src/pickers.rs` (+ `pickers/compact.rs`).

**Excluded:** none.

**Proto change:** no (model catalog rows already carry options. Flag it
if a field is needed).

**Blocked by:** 08 (`devin_models.rs` probe fix).

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A29; `pass2-features-desktop.md` N5.

- [ ] Devin shows one Fusion entry with the hover card; picks apply Lead/Effort/Sidekick/Fast (harness + picker tests, the existing lead/sidekick fixtures at `pickers.rs:8574-8603`)
- [ ] Ambiguous labels show descriptions
- [ ] Commit records `731697b66` (Devin slice) and the upstream/main refinements

## Comments
