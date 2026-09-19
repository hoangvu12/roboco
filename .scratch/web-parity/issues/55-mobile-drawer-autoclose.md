# 55 — Phone drawer closes on navigate

**What to build:** On a phone, tapping a chat row or a settings page inside
the sidebar drawer navigates AND closes the drawer, instead of leaving it
covering the destination. One pathname-change effect in `AppShell` covers
every entry surface (chat links, archived links, settings links, the account
menu's navigate, and any future one), and it never fires at desktop widths.
After this ticket, "tap a chat in the drawer → drawer closes, chat opens."

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

**Research:** `../research-2026-09-19/mobile-layer.md` M7(a)–(d) + "Current
mobile layer inventory" rows (JS branches: sidebar toggle
`app-shell.tsx:196-202`; CSS blocks: phone layout `app.css:6738-6851`, left
drawer `:6816-6832`).

**Desktop reference (for lookups only):** none — the desktop sidebar is a
persistent column; it never "closes" on selection ("there is nothing to
port", M7(b)). The spec is the research's mobile-native effect, verbatim
below.

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/app-shell.tsx` | edit | the pathname-change close-on-navigate effect (M7(b) verbatim, §2), beside the existing drawer plumbing (`:112`, `:196-207`, `:351-359`, `:584-588`) |
| `web/packages/app/tests/app-shell-drawer.test.ts` | new | the close-on-navigate cases (§3) |

No CSS changes, no changes to `chat-list.tsx` / `settings-nav.tsx` /
`archived-section.tsx` / `account-row.tsx` (the rejected alternative — §5).

---

## 1. Context a fresh session needs

- The drawer's open flag is `AppShell`'s local `sidebarOpen`
  (`app-shell.tsx:112`), closed today by exactly three affordances: the
  backdrop click (`app-shell.tsx:584-588`, `setSidebarOpen(false)` at
  `:587`), the Escape ladder (`app-shell.tsx:351-359`, via `onCloseDrawer`
  → `:205`), and the toggle (`app-shell.tsx:196-202`, `:198`).
- Chat rows navigate via plain router `Link`s: `<Link to="/chat/$chatId" …>`
  (`components/chat-list.tsx:476-477`; archived rows
  `components/archived-section.tsx:145-146`). Settings rows likewise
  (`components/settings-nav.tsx:58-68`). The user menu's Settings item uses
  `navigate` (`components/account-row.tsx:69-71`). **No selection handler
  anywhere references the drawer** — `setSidebarOpen(false)` has exactly the
  three call sites above (M7(a)).
- `AppShell` already subscribes to the pathname:
  `useRouterState({ select: (s) => s.location.pathname })`
  (`app-shell.tsx:131`) — the effect's dependency exists; nothing new to
  wire.
- The width guard to reuse: the sidebar toggle asks
  `window.matchMedia(\`(max-width: ${PHONE_MAX_WIDTH}px)\`).matches\`
  (`app-shell.tsx:197`) — the same breakpoint the stylesheet keys on
  (`PHONE_MAX_WIDTH` 768, `state/layout.ts:43`; the dead-band comment at
  `:193-196` documents why the media query — not `innerWidth` — is the
  truth). The effect must use the same guard so desktop never closes.
- Root cause (M7(c)): the drawer predates the router-driven sidebar content
  and was only ever closed by its own affordances; navigation links were
  never taught about it.
- **Decision-5 amendment (user directive, 2026-09-19):** spec decision 5
  (`../../spec.md:29-31`) declared phone widths out of scope; this ticket
  amends that for this behavior — phone work is in scope, and desktop
  (≥769px) behavior is untouched (the column persists by design).
- What 49/50 provide: nothing this ticket requires — that is why it is
  unblocked. If 49's `state/media.ts` (`useIsPhone()`) has landed by the
  time this runs, the effect MAY consume it, but must not require it.

## 2. Spec

### 2.1 The close-on-navigate effect (research M7(b), verbatim)

Mobile-native (the desktop sidebar is a persistent column; it never
"closes" on selection — there is nothing to port). Recommended shape, one
site, covers every entry surface (chat Link, settings Link, account menu
navigate, future ones):

```
AppShell: useEffect(() => {
  if (!sidebarOpen) return;
  if (window.matchMedia(`(max-width: ${PHONE_MAX_WIDTH}px)`).matches) {
    setSidebarOpen(false);
  }
}, [pathname]);            // close-on-navigate at phone
```

guarded by the same matchMedia as the toggle (`app-shell.tsx:197`) so
desktop never closes. Alternative (threaded `onNavigate` prop into
`SidebarBody`/`SettingsNavBody`) touches three files for the same result —
not recommended.

Place it next to the existing route-driven effect that keys on `pathname`
(`app-shell.tsx:142-153`) — same dependency, same subscription; keep it a
separate effect (different concern, different guard).

**States:**

| state | condition | effect |
| --- | --- | --- |
| closes | drawer open (`sidebarOpen`) AND phone (matchMedia ≤768) AND `pathname` changed | `setSidebarOpen(false)` |
| no-op | desktop width (matchMedia false) | the sidebar column persists — never closes |
| no-op | drawer already closed | early return (`if (!sidebarOpen) return`) |
| same-path navigation | tapping the row of the already-active chat/section — `pathname` unchanged | the effect does not refire; the drawer stays open (known, accepted: the tap is a navigation no-op; backdrop/cluster/Escape still close it) |
| external nav | the account menu's `navigate` (`account-row.tsx:71`), deep links, future entry surfaces | covered automatically — every navigation changes `pathname`, and this is the one site |

**Interactions:** no new controls — the effect ADDS a close path on top of
the existing three (toggle `:196-202`, Escape `:351-359`, backdrop
`:584-588`); it replaces none of them.

**Motion:** none (the drawer's existing slide handles the visual exit).

**Text:** none.

**Data:** reads `pathname` (`app-shell.tsx:131`) and `sidebarOpen` (`:112`);
writes `setSidebarOpen`. No RPC.

## 3. Pure logic to port

The rule is one effect; its conditions in prose (mobile-native — the
research's pure-logic section: "the M7 close-on-navigate effect (an
app-shell-level test asserting `sidebarOpen` flips false on pathname change
only under a mocked 375px matchMedia). No desktop test names map to these —
that is the point"):

- close iff: `sidebarOpen && isPhone(matchMedia) && pathnameChanged`;
- the phone read happens INSIDE the effect body (at navigation time, not
  render time), mirroring the toggle's on-click read (`app-shell.tsx:197`);
- deps: `[pathname]` — `sidebarOpen` is read via the state value (or a
  functional guard); do not close on drawer-open changes alone.

**New web unit tests** (`web/packages/app/tests/app-shell-drawer.test.ts`):

- `drawer closes on pathname change at phone widths` — mock
  `window.matchMedia` to match `(max-width: 768px)`; assert `sidebarOpen`
  flips false when `pathname` changes.
- `drawer stays open on pathname change at desktop widths` — matchMedia
  false; no close.
- `no close when the drawer is already closed` — the early return.
- `same-path navigation does not refire the effect` — re-notifying the same
  pathname leaves the drawer open (the recorded known limitation, asserted
  as such).

If the guard is extracted as a pure helper
(`shouldCloseDrawer(pathChanged, sidebarOpen, isPhone)`), test it directly
and keep the component test for the wiring.

Desktop tests: none map — the desktop sidebar is a persistent column that
never closes on selection (M7(b)); the rule is mobile-native and says so.

## 4. Gaps this ticket closes

From research M7(d), verbatim:

| item | kind | expected | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| drawer close on select | behavior | phone drawer closes when a row navigates | no close call (`chat-list.tsx:476`; `settings-nav.tsx:59-68`; `app-shell.tsx:112/196-207`) | pathname-change effect in `AppShell` |

From the research's consolidated gap table, the M7 row (drawer close on
select) — same content.

## 5. Do not

- Do not thread `onNavigate` props through `SidebarBody`/`SettingsNavBody`/
  `chat-list` — the research's rejected alternative (three files for the
  same result).
- Do not close the ENGINE drawer (`drawerOpen`) or the phone pane drawer
  (ticket 52's surface) here — `sidebarOpen` only.
- Do not change desktop behavior: at ≥769 the sidebar is a persistent
  column and selecting a chat/settings page never closes anything.
- Do not change the existing affordances (toggle `:196-207`, Escape
  `:351-359`, backdrop `:584-588`) — the effect adds a close path, it does
  not replace any.
- Do not gate the effect on `state/media.ts` (49) — this ticket has no
  blockers; raw `window.matchMedia` per the spec, consuming `useIsPhone()`
  only if it already exists.
- Do not re-add INVENTED UI: no close buttons on rows, no per-row handlers.

## 6. Acceptance

- [ ] tap a chat in the drawer → drawer closes, chat opens (375px).
- [ ] tap a settings page in the drawer → drawer closes, page opens
      (including the account menu's Settings item via `navigate`).
- [ ] at ≥769px, selecting a chat/settings page never closes the sidebar
      column.
- [ ] same-path re-tap leaves the drawer open (the recorded known
      limitation); backdrop, cluster, and Escape still close it.
- [ ] Unit tests: `drawer closes on pathname change at phone widths`,
      `drawer stays open on pathname change at desktop widths`,
      `no close when the drawer is already closed`,
      `same-path navigation does not refire the effect` →
      `web/packages/app/tests/app-shell-drawer.test.ts`.
- [ ] Screenshot pair: `use-browser` at 375×667 against `web_smoke`, states:
      "drawer open over chat A" → "tapped chat B: drawer closed, chat B
      open"; "drawer open, settings nav" → "tapped Appearance: closed, page
      open". No desktop-width visual change (no CSS touched).
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px (no CSS changes at all).

## Comments

(empty; appended during implementation)
