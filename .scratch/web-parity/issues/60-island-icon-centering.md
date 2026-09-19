# 60 — Collapsed-sidebar nav: island icons centered like the desktop

**What to build:** With the sidebar closed on the new-chat page, the small nav's icons center in the island exactly like the desktop — the current 19px leftward bias (vs the desktop's 2px) disappears. Root cause: the web keeps the hidden `+` slot's geometry (24px + 8px gap) mounted at `visibility:hidden`, so the island's `right:0` anchor rides 32px past the last visible control.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

**Research:** `../research-2026-09-20/nav-picker-diff-subagent.md` — S1 (island [16,124] vs desktop [16,92], taffy padding-box insets, the `+` slot at titlebar.tsx:266-277 / app.css:517-526, the `right:0` island anchor at app.css:405-412, the desktop's `show_plus.then` at shell.rs:4093-4104).

**Desktop reference (for lookups only):** `crates/ui/src/shell.rs:4093-4104` (the `+` is conditionally NOT rendered — no reserved slot), the island geometry spec in ticket 34.

## 1. Context a fresh session needs

- Ticket 34 landed the titlebar island + the cluster's controls; ticket 35/36 landed the hero/dock work. The `+` (new chat) button in the cluster is kept MOUNTED with alpha 0 + `visibility:hidden` (titlebar.tsx:266-277; app.css:517-526) — it still occupies layout (24px + the 8px gap), so `.titlebar-island`'s `right: 0` (app.css:405-412) anchors 32px past the last VISIBLE control and the icons sit left of center.
- The desktop renders the `+` only when `show_plus` says so (shell.rs:4093-4104) — no phantom geometry.

## 2. Spec

Pick the smaller-risk fix (the research offers both; implement one, note the choice):

1. **Zero the phantom slot** (desktop-parity): when the `+` is hidden, it contributes NO geometry (condition the element out of the flex row — render nothing, like the desktop's `show_plus.then`), so the island's `right:0` anchors to the last visible control and the icons center at the desktop's [16,92].
2. **Or inset the island's right edge** by the phantom slot's 32px when the `+` is hidden (pure CSS conditional class).

Prefer 1 (matches the desktop's model exactly; the `+`'s appear/disappear fade can stay a mount transition). Verify against ticket 34's island geometry (28→32px panel, center 21) and the research's measured centers: island [16,124] → target [16,92].

## 3. Pure logic to port

None beyond the conditional render. If titlebar-island.test.ts asserts the current geometry, update it to the centered targets.

## 4. Gaps this ticket closes

| item | kind | desktop value (file:line) | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| Island icon centers with sidebar closed | layout | [16,92] (no phantom `+` slot; shell.rs:4093-4104) | [16,124] (`+` keeps geometry at alpha 0; titlebar.tsx:266-277, app.css:517-526) | §2 |

## 5. Do not

- Do not change the island's panel geometry/frost values (ticket 34's spec; 56 owns the blur deletion separately).
- Do not rework the cluster's other buttons (50 owns phone rowLeft).
- Do not touch the desktop Rust.

## 6. Acceptance

- [ ] Sidebar closed on the new-thread page: the island's icons visually centered (measured centers within 2px of the desktop's, per the research's method).
- [ ] The `+` still appears when it should (its show condition unchanged) with its fade intact.
- [ ] `pnpm -r build` + `pnpm test` green.

## Comments

(User report 2026-09-20 #2.)

### Implementer note (2026-09-20)

Landed **option 1** (§2) on `wp2r2/60-island-icon-centering`, based on
`web-parity/wave-2 @ dc591f16`. `pnpm -r build` green; app vitest **1296
passed (1293 base + 3 new)** from both `web\` and `web\packages\app`;
engine-client 43 passed. (The first root `pnpm test` hit the cold-worktree
cargo build of `web_conformance` — conformance errored and smoke's 600 s
beforeAll hook timed out; the same build run directly finishes in ~5 min and
the suite is green on re-run. Environmental, not code.)

- `components/titlebar.tsx` — the `+` is conditionally rendered
  (`onNewSession != null && newSessionAlpha > 0.01`, mirroring
  `show_plus.then(...)` at shell.rs:4093-4104): at alpha 0 it contributes NO
  geometry, so the cluster's shrink-to-fit width ends at the last visible
  control and the island's `right: 0` anchors there. New
  `titlebarIslandHorizontalGeometry(showsNewSession)` ports the span math;
  `state/layout.ts` gains `TITLEBAR_ISLAND_INSET = 6` (shell.rs:4027-4028).
  The `data-alpha`/`aria-hidden`/`tabIndex` trappings are gone — a rendered
  `+` is a plain tappable control, an unrendered one is out of the tree.
- `styles/app.css` — the appear fade is now the mount animation
  `rb-titlebar-plus-in` on the same RESIZE curve the row's left padding
  rides; the `data-alpha`/`visibility` transition machinery is deleted
  (nothing renders at alpha 0). The reduced-motion block moves
  `.titlebar-new-session` from `transition: none` to `animation: none`
  (its fade is an animation now).
- `tests/titlebar-island.test.ts` — 3 new assertions: hidden `+` → island
  **[16, 92]** width 76; shown `+` → [16, 124]; pill center **54** over the
  [10, 92] visible controls (center 51) — the desktop's own 3 px pill/glyph
  relationship (research §S1(b): glyph 52 under pill 54), replacing the
  pre-fix 19 px bias (pill center 70).

**Deviation from the pre-fix fade model:** the disappear is now the unmount
itself — no fade-out. The ticket's option 1 frames the fade as "a mount
transition", and the desktop's own `show_plus` gate pops the `+` out the
same way; the island and the `+` are mutually exclusive by construction
(research §S1(c)), so nothing else is mid-fade when it goes. The show
condition (`titlebarNewSessionAlpha` + the null-handler gate) and the
row-left slot math are untouched.

Screenshot pairs waived this session (headless; build + tests verification
only) — the geometry contract is unit-covered by the new assertions.
