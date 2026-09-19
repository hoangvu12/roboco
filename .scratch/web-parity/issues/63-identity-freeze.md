# 63 — Sidebar close with a subagent tab: freeze the identity text (no live truncation)

**What to build:** Closing the sidebar while a subagent (or any right-pane) tab is open no longer squeezes/truncates the titlebar identity text during the animation — the row's free space stays invariant like the desktop's single-clock model. The tab chips themselves are already fixed-width (112px both clients — NOT the culprit).

**Blocked by:** 57 (the tween rework changes the clock architecture this ticket builds on — land 57 first, then apply the freeze/invariant on the new mechanism; if 57's option A lands, verify whether this ticket's split-clock cause still exists before implementing).

**Status:** ready-for-agent

**Research:** `../research-2026-09-20/nav-picker-diff-subagent.md` — S4 (the split-clock mechanism: endpoint JS vars + independent CSS transitions at app-shell.tsx:574-599 vs the desktop's single per-frame `sidebar_now(t)` scalar keeping the row's free space invariant, shell.rs:1946-1962, :3814-3826; tabs.rs:179-253; the identity's live truncation at app.css:534-571; fixes: JS-tween port or identity-freeze).

**Desktop reference (for lookouts only):** `crates/ui/src/shell.rs:1946-1962, :3814-3826` — one tweened scalar drives EVERY consumer in the same frame, so the titlebar row's input deltas are equal and opposite (free space invariant).

## 1. Context a fresh session needs

- The web's sidebar close runs TWO clocks: the shell writes endpoint CSS variables (JS) while independent CSS transitions interpolate the sidebar/pane widths (app-shell.tsx:574-599). The band's endpoint delta can exceed the row-inset delta transiently (clamped wide pane), and the only shrinkable child — the titlebar identity (app.css:534-571) — truncates live during the mismatch window.
- The tab chips are NOT the problem (fixed 112px on both clients).
- Ticket 57 replaces the tween mechanism; this ticket's cause may vanish (single clock) or persist (CSS-var endpoints still split) — verify against the landed 57 shape first.

## 2. Spec

After 57 lands, choose the smaller fix consistent with its mechanism:
1. **Identity freeze**: during the sidebar tween (the same signal 57 uses), the identity's max-width clamps to its PRE-tween value (one class flip; text truncation only re-evaluates on settle). Simplest, no visual change at rest.
2. **Or invariant row inputs** (the desktop's model): make the titlebar row's geometry inputs (rowLeft inset + pane band width) derive from ONE tweened source so their deltas cancel (free space constant through the animation).

Prefer 1 unless 57's mechanism already provides the single source (then 2 is free). Record the choice + verification in Comments.

## 3. Pure logic to port

None beyond a tween-active predicate (57 likely exports it — reuse; do not fork).

## 4. Gaps this ticket closes

| item | kind | desktop value (file:line) | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| Identity truncation during sidebar close | behavior | free space invariant (single clock; shell.rs:1946-1962, :3814-3826) | live truncation under split clocks (app-shell.tsx:574-599, app.css:534-571) | §2 |

## 5. Do not

- Do not touch the tab strip's chip widths (already parity).
- Do not re-architect the titlebar row (34/50 own its geometry).
- Do not change the tween's timing.
- Do not touch the desktop Rust.

## 6. Acceptance

- [x] Open a chat with a subagent tab → close the sidebar: the titlebar text does NOT re-truncate mid-animation (code-verified gate + manual check note).
- [x] With no pane open, sidebar close unchanged.
- [x] `pnpm -r build` + `pnpm test` green.

## Comments

(User report 2026-09-20 #8.)

**Step 0 — verification on the landed 57 mechanism (2026-09-20, branch wp2r2/63-identity-freeze):** the split-clock cause STILL exists, structurally. 57a unified the *clock* (one flip commit, the browser interpolates, zero React frames) but not the *inputs*: `--rb-titlebar-row-left` (app-shell.tsx, from `titlebarRowLeft({sidebar: sidebarForGeometry})`) and `--rb-pane-band` (from `titlebarPaneBandWidth({viewport, paneWidth, rowLeft})` with `paneWidth = resolvePaneWidth(..., sidebarForGeometry)`) are both endpoint-computed at the flip commit — 57a's diff touched only the hero's width transition and the raster window, not the row. The row's padding-left (app.css `.titlebar`) and the pane band's width transition DO now ride the same resize curve (timing lockstep), but lockstep is not the desktop's invariance: the free space moves as `(ΔP − ΔB)·f(t)`, and in the clamped-pane case ΔB (the `rightPaneMaxWidth` clamp loosening, up to the full sidebar width) exceeds ΔP (the inset retreat, sidebar+16 minus the 136/152 content-start floor), so the identity — the row's only shrinkable child — still re-truncates progressively through the 200ms. Fix **option 1 (identity freeze)** chosen: option 2 (invariant row inputs from one tweened source) would need a per-frame JS writer for both vars — exactly the pump 57 removed.

**Implementation (option 1):** `lib/identity-freeze.ts` — the arm/settle window gates the clamp. The window is 57a's own signal, extended (not forked) with `SidebarTweenSignal.subscribe()`: every settle path (transitionend, the cap, the drag/reduce disarms) funnels through `settle()`, so the freeze releases with whichever lands first. At arm (the flip commit's layout effects, while the row's transitions still sit at their pre-tween values) the identity's laid-out width is captured and written as `--rb-identity-freeze-width` + `data-rb-identity-frozen`; the stylesheet pins the box (`flex: none` + `max-width: var(...)`, reproducing the pre-tween box exactly, truncated or not). At settle the clamp lifts and truncation re-evaluates exactly once — the sanctioned snap. One read + one write per edge, zero per-frame work. `dockGlideActive()` does NOT move the row (the dock glide's channels are the conversation column's opacity/rise/width set; the row's inputs change only via sidebar flips, pane store toggles, and viewport resizes), so the sidebar signal is the only window.

**Manual check note:** screenshot pairs waived per the ticket instructions — verification is `pnpm -r build` + `pnpm test` (the new `tests/identity-freeze.test.ts` drives the window gate: one read at arm, the max-width constant through the window, release at settle; plus the CSS artifact and wiring greps). Known bounded trade-off recorded: with a *clamped* pane open, the transient deficit the freeze refuses to take out of the identity instead displaces the trailing band/toggle rightward (≤ ΔB−ΔP, tens of px, clipped at the window edge — `body { overflow: hidden }`, no scrollbar) for the 200ms and snaps back at the settle release, in the same commit that re-truncates the text once. With no pane open the freeze is inert (the box is content-sized; the fill absorbs the glide) — sidebar close unchanged.
