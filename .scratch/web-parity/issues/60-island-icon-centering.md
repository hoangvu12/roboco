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
