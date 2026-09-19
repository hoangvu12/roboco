# 56 — Always-opaque web: delete every frosted surface and backdrop-filter

**What to build:** The web client renders OPAQUE surfaces everywhere, permanently. Every `backdrop-filter` / `-webkit-backdrop-filter` use and every frosted-surface branch is deleted (the user directive: "the web, just make it always opaque, no need for frosted"). This is both a visual simplification and the P0 performance fix — each blurred backdrop re-filters every frame over moving content (streaming transcript text), the single largest recurring paint cost in the app.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

**Research:** `../research-2026-09-20/performance-audit.md` — "Internet research digest" (backdrop-filter rule), audit section D (the 12-site enumeration), fix plan P0, "What NOT to do".

**Desktop reference (for lookups only):** the desktop's opaque branch of each surface (the web already mirrors it — `.composer-pill` comment "the web is forced opaque (the 2026-09-17 defrost decision)" at styles/app.css ~3100-3105).

## 1. Context a fresh session needs

- The web app (`web/packages/app`, CSS in `src/styles/app.css`) currently mixes an opaque default with a landed ticket-33 frosted branch and 12 total backdrop-filter sites (research section D enumerates every one with file:line — the pill, titlebar island, queue tray, wizard, popover/palette/modal/rail/jump-pill/tool-badge, plus 3 dead frosted branches).
- `state/appearance.ts` / `lib/appearance-store.ts` already forces the resolved surface treatment to opaque on the web (`appearance-store.ts:87-94` per the research) — the frosted branch in app.css is the vestige.
- Deleting a blur does not change layout — backdrop-filter is paint-only — so no geometry re-verification is needed; only the surface colors need to read as the opaque branch (which each site already carries as its base).

## 2. Spec

1. Delete from `styles/app.css` every `backdrop-filter` and `-webkit-backdrop-filter` declaration (the research's D table lists all 12 sites with file:line — delete the declarations, keep the rules' other properties).
2. Delete the ticket-33 frosted branch: `html[data-surface="frosted"] .composer-pill { background: color-mix(in srgb, var(--rb-bg) 15%, transparent); }` (app.css ~3124-3145) and any sibling `[data-surface="frosted"]` rules (grep).
3. Delete the dead frosted branches the audit found (3 sites where the selector no longer matches anything — verify each is dead before deleting; if alive, delete the branch anyway per the directive).
4. Do NOT introduce any new `backdrop-filter` anywhere.
5. Where a surface's only visual distinction was the blur (e.g. the titlebar island panel, the dialog glass), keep the opaque background color that already exists in the same rule; if the rule's background becomes visually wrong without blur (washes tuned for translucency), adjust the alpha of the EXISTING color-mix/rgb wash toward the opaque branch values the desktop uses — no new hex, `--rb-*`/rgb-wash idiom only.

## 3. Pure logic to port

None — CSS deletion pass. Existing tests keep passing; no test changes expected (no test asserts backdrop-filter).

## 4. Gaps this ticket closes

| item | kind | expected | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| Web is always opaque | directive | zero backdrop-filter uses | 12 sites (performance-audit.md §D) | delete all |
| Frosted pill branch | perf/visual | opaque flatten only | app.css ~3124-3145 | delete branch |

## 5. Do not

- Do not touch the desktop Rust (`crates/ui`) — the desktop keeps its frost.
- Do not remove hover fades, reduced-motion parity, or any transition timing (other tickets own those).
- Do not delete the `--rb-shadow-*` tokens or box-shadows (paint-only, no per-frame backdrop cost; ticket 41 owns the jump-pill shadow).
- Do not change `resolveSurfaceTreatment` in lib/appearance-store.ts beyond what's needed if it becomes dead code — check its callers first; if the web-only forced-opaque logic simplifies, simplify it, but keep the function if the theme artifact/typecheck depends on its shape.

## 6. Acceptance

- [ ] `grep -c "backdrop-filter" web/packages/app/src/styles/app.css` → 0 (both prefixed and unprefixed).
- [ ] `grep -c "data-surface=\"frosted\"" web/packages/app/src/styles/app.css` → 0.
- [ ] Visual pass (code inspection): pill, island, dialogs, popovers, jump pill all render with their existing opaque backgrounds.
- [ ] `pnpm -r build` green; `pnpm test` green (1293 at base).
- [ ] No new literal hex.

## Comments

(User directive 2026-09-20: "i think the web, just make it always opaque, no need for froasted".)
