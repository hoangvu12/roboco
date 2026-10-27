# 71 — Panels: content columns share the width; continuous transitions, layout model (wave-7 08 part 1, #677)

**Lane:** 4b Features: desktop UI. **Effort:** M.

**What to build:** the layout model and motion of #677. Content columns
(files / transcript / queue panes) share the available width instead of
each clamping on its own, with continuous transitions while dragging.
Port the new `shell/panel_layout.rs` (`horizontal_panel_fit`,
`HorizontalPanelFit`, `PANEL_RESIZE`, `files_settled_width`), the
`motion.rs` additions (`panel_content_tween`), and the panel-lifecycle
suite of `shell/panel_interaction_tests.rs`, which was held for this
port. Wire it at the shell level, keeping the existing per-surface
behaviour until 72 adopts the model.

**Upstream:** `dace1a8ce` (#677), layout/motion/tests hunks.

**Roboco files:** `crates/ui/src/shell/{panel_layout (new),panel_interaction_tests}.rs`,
`crates/ui/src/{motion,shell}.rs`.

**Excluded:** none known.

**Proto change:** no.

**Blocked by:** 57 (pin compat). **Then:** 72.

**Status:** ready-for-agent

**Source:** wave-7 `issues/08-panels-shared-width-transitions.md`;
`part-06.md` `dace1a8ce`.

- [ ] `panel_layout` unit tests (fit, settle, resize bounds) ported and green
- [ ] Panel-lifecycle tests ported
- [ ] No visible change yet for surfaces 72 adopts (UI suite green)
- [ ] Commit records `dace1a8ce` (part 1)

## Comments
