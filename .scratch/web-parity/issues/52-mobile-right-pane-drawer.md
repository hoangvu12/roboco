# 52 — Mobile right pane becomes a right drawer

**What to build:** On a phone, opening the right pane (Files, Terminal, a
commit diff) no longer squashes the chat into a half-height column with
invisible tabs: the pane slides in from the **right** as an overlay drawer —
the mirror of the left sidebar drawer — with the surface tab strip visible as
a 38px header row above the pane body. The titlebar's pane toggle stays the
one open/close control at every width, a backdrop tap closes the drawer, and
expanded/takeover is the drawer at full width. After this ticket, "open the
files pane" at 375px reads as a drawer and the tabs are usable.

**Blocked by:** 49 (Responsive dialog/drawer primitive — supplies the sheet
form the strip's `+` menu reuses, and whose `PickerCard` phone branch is the
established pattern), 50 (Mobile layout system — owns the phone titlebar
inset AND the pane **geometry inputs** via its `sidebarForGeometry` branch,
`app-shell.tsx:179`, `:435-441`, `:514-519`; this ticket consumes them and
builds the pane's phone **form**).

**Status:** ready-for-agent

**Research:** `../research-2026-09-19/mobile-layer.md` M2(a)–(d) (plus M3's
shared-fix note) + "Current mobile layer inventory" rows (JS branches: sidebar
toggle `app-shell.tsx:196-202`, phone flag `chat-page.tsx:440-443`; CSS
blocks: phone layout `app.css:6738-6851`).

**Desktop reference (for lookups only):**
`crates/ui/src/shell.rs::right_pane_container` (`:3826-3855` — the
right-anchored column, outer width on the resize tween, inner right-anchored
at content width) and `::render_right_tab_strip` (`:6687`; chips `:6690-6691`);
`crates/ui/src/shell/tabs.rs:222-297` (the titlebar band's trailing section —
web ports `titlebar.tsx:118-150`, `right-tab-strip.tsx:80-100`). **The desktop
has no phone analog** — its minimum window geometry (`CHAT_PANEL_MIN` 300 +
`SIDEBAR_MIN`) makes a 375px window impossible; the phone pane is the
research's mobile-native target (M2(b)): a right-side overlay drawer modeled
on the repo's own left drawer.

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/styles/app.css` | edit | phone block: **delete** the stacking rules (`.shell` column `:6744-6746`; the stacked-pane rules `:6766-6791`); **new** `.right-pane` phone drawer rules (mirror of `:6816-6832`), `.pane-backdrop` (mirror of `:6834-6844`), the 38px in-drawer strip header, `.right-pane-expanded` phone width |
| `web/packages/app/src/components/app-shell.tsx` | edit | consume 50's phone-corrected pane geometry (verify `--rb-pane-now`/`--rb-pane-open`/`--rb-pane-band` are non-zero at phone — the branch itself is 50's, do not re-land it); render the phone pane backdrop |
| `web/packages/app/src/components/right-pane.tsx` | edit | `RightPane`: skip the inline column width at phone (`:120`), keep `aria-hidden` (`:122`), mount the in-drawer strip header above `.right-pane-body` (`:139`) |
| `web/packages/app/src/components/right-tab-strip.tsx` | edit (mount site only) | `RightTabStrip` rendered inside the drawer header at phone — chip geometry verbatim (`:30-41`), no strip-internal changes |
| `web/packages/app/src/components/titlebar.tsx` | edit | at phone the band renders no pane tabs (the strip lives in the drawer); the `Toggle panel` control (`:148`) stays mounted |
| `web/packages/app/tests/layout.test.ts` | edit | new phone-input cases for `resolvePaneWidth` / `titlebarPaneBandWidth` (§3) |

---

## 1. Context a fresh session needs

- The right pane is the shell's **third column**: `RightPane` is mounted as a
  flex sibling after `.main` (`app-shell.tsx:666-668`), an `<aside
  class="right-pane">` whose inline width rides the open/close glide
  (`right-pane.tsx:118-123`, width at `:120`), with `.right-pane-inner`
  right-anchored at the held content width (`:130-134`) and
  `.right-pane-body` inside (`:139`). Surfaces stay mounted through the
  closing glide.
- All pane **geometry** is computed from the desktop model, where the sidebar
  is an in-flow column: `paneOpenWidth = resolvePaneWidth({ ...pane, open:
  true }, viewport, sidebarWidth)` (`app-shell.tsx:179`), with `sidebarWidth
  = sidebarTarget(sidebar)` — the persisted **dragged** width (e.g. 304,
  `app-shell.tsx:124`; `state/layout.ts:424-426`) — even at phone, where the
  sidebar is a fixed overlay out of flow (`app.css:6816-6827`).
- `resolvePaneWidth` → `Math.min(pane.width, rightPaneMaxWidth(viewport,
  sidebar))` (`state/right-pane.ts:707-719`), and `rightPaneMaxWidth =
  max(0, viewport − sidebar − 300)` (`state/layout.ts:54-56`, `CHAT_PANEL_MIN`
  300). On a 375px window with sidebar 304 this is **0**, so `--rb-pane-now`
  and `--rb-pane-open` (`app-shell.tsx:503-510`) are 0.
- The titlebar band consumes those numbers twice: `--rb-pane-band` is
  written from `titlebarPaneBandWidth({viewport, paneWidth, rowLeft,
  takeover})` (`app-shell.tsx:514-519` over `state/layout.ts:404-415`), which
  subtracts `rowLeft` — itself `max(sidebar + 16, 136+)` = 320 on that phone
  (`app-shell.tsx:435-441`; `state/layout.ts:370-384`, non-takeover arm
  `:383`) — so `avail = 375 − 320 − 6 − 16 = 33` and the band resolves to
  `max(0, min(0 − 6, 33) − 28) = 0`. The CSS consumes it:
  `.titlebar-pane-band { width: var(--rb-pane-band) }` (`app.css:503-510`)
  and `.titlebar-pane-band-inner { width: max(0px, calc(var(--rb-pane-open) −
  6px − 28px)) }` (`app.css:526-529`). Zero-width band ⇒ "i dont see the
  tabs" (M2(a)).
- The phone block today instead **stacks** the pane under the chat:
  `.shell { flex-direction: column }` (`app.css:6744-6746`), `.right-pane {
  width: auto !important; flex: 1; min-height: 0 }` + `.right-pane[
  aria-hidden="true"] { display: none }` (`app.css:6766-6775`), inner reset
  `:6777-6786`, and takeover `.shell-pane-takeover .main { display: none }`
  (`app.css:6788-6791`).
- The pattern to mirror — the **left drawer**: `.sidebar` fixed
  `top/bottom/left 0`, `width: min(20rem, 85vw)`, `z-index: 30`,
  `transform: translateX(-100%)`, `transition: transform
  var(--rb-motion-menu-in) var(--rb-ease-ease-out)` (`app.css:6816-6832`,
  transition at `:6824`); backdrop fixed `inset: 0`, `z-index: 20`, shown
  under `.shell-sidebar-open` (`app.css:6834-6844`); desktop reset at
  `:6853-6857`.
- The z-ladder is fixed and documented: the phone sidebar stays at 30,
  deliberately **under** the titlebar (40) — "its cluster must stay clickable
  to close what it opened" (`app.css:233-255`, esp. `:247-248`). No new
  tiers.
- The pane toggle: `HeaderIconButton "Toggle panel"` in the titlebar's
  trailing group (`titlebar.tsx:148`), wired at `app-shell.tsx:538`
  (`rightPaneStore.toggle`); one-control-two-meanings is the established
  phone pattern (the sidebar toggle, `app-shell.tsx:193-202`).
- The tab strip: `RightTabStrip` (`right-tab-strip.tsx:80`) is mounted by
  `Titlebar` via the `paneTabs` prop (`app-shell.tsx:544-546`); chip
  geometry is the desktop's — 112px chips, 4px strip gap
  (`right-tab-strip.tsx:30-33` = `shell.rs:6690-6691`); the `+` menu card is
  168px (`PLUS_MENU_W`, `right-tab-strip.tsx:41`).
- Expanded/takeover semantics: `toggleExpanded` flips `expanded`
  (`state/right-pane.ts:299-301`); `resolvePaneWidth` returns
  `rightPaneTakeoverWidth(viewport, sidebar)` when expanded
  (`state/right-pane.ts:715-717`).
- The Escape ladder treats the phone sidebar + engine drawer as one surface
  (`state/escape.ts:81-89`, priority 12; registered at
  `app-shell.tsx:351-359`) — the pane drawer registers the same way.
- **Decision-5 amendment (user directive, 2026-09-19):** spec decision 5
  (`../../spec.md:29-31`) declared phone widths out of scope; this ticket
  amends that for this surface — phone work is in scope, desktop (≥769px)
  behavior is untouched.
- What 49/50 provide by the time this runs: **50** has landed the phone
  geometry inputs — its `sidebarForGeometry = phone ? 0 : sidebarWidth`
  branch feeds `rowLeft` (`app-shell.tsx:435-441`) **and** the pane inputs
  (`:179`, `:514-519`), so `rightPaneMaxWidth(375, 0) = 75` and
  `--rb-pane-open`/`--rb-pane-band` stop collapsing (50 §2.6: "The pane's
  phone FORM — right-side drawer, in-drawer tab strip — is ticket 52's;
  this ticket only fixes the geometry inputs"). 50's arithmetic note also
  corrects the research's rowLeft values: phone rowLeft is **136** (chat
  selected, `+` visible) / **104** (otherwise, and on the settings route) —
  `TITLEBAR_CONTENT_START` computes to 104, not 136, so the research's
  "168/136" double-counts the 32px slot. Use 136/104. **49** has landed
  `state/media.ts` (`useIsPhone()`), `RbResponsiveDialog`, and the
  `PickerCard` phone branch. 49's `RbResponsiveDialog` is **not** used for
  this drawer — it is the bottom-sheet form for transient pickers;
  navigation panes are side drawers (the research's open question Q2
  split). 49 explicitly defers the strip's `+` menu phone form here
  ("later (52) — rides the right-pane phone-drawer work"); reuse 49's
  landed sheet pattern for it (§2.2), do not invent a second one.

## 2. Spec

### 2.1 The pane drawer at ≤768 (research M2(b), verbatim)

Desktop reference being adapted: the pane is a right-anchored column
(`shell.rs:3826-3855` `right_pane_container` — outer width rides the resize
tween, inner right-anchored at the content width) and its tabs live in the
titlebar band (`shell/tabs.rs:222-297` trailing section; web port
`titlebar.tsx:118-150`, `right-tab-strip.tsx:80-100`). The desktop has NO
phone layout (minimum window sizes make one impossible), so the phone pane
is a mobile-native pattern modeled on the repo's own left drawer:

**Layout** (copied from M2(b)):

| property | value | source |
| --- | --- | --- |
| mount | the pane column at ≤768 leaves the shell flow and becomes a right-side overlay drawer | M2(b) |
| position | `fixed; top: 0; right: 0; bottom: 0` | M2(b) |
| width | `min(30rem, 88vw)` | M2(b) |
| closed transform | `translateX(100%)` | M2(b) |
| transition | `transform var(--rb-motion-menu-in) var(--rb-ease-ease-out)` — the exact mirror of the left drawer (`app.css:6816-6832`, which uses `left: 0; width: min(20rem, 85vw); translateX(-100%)`) | M2(b) |
| z-index | `30` — same backdrop treatment (`app.css:6834-6844`); `--rb-z-titlebar` stays 40 above it per `app.css:247-248`'s documented ladder decision | M2(b) |
| backdrop | mirror of `.sidebar-backdrop` (`app.css:6834-6844`): fixed, `inset: 0`, z 20, shown only while the pane drawer is open at ≤768; hidden ≥769 (the `:6853-6857` pattern) | M2(b) |
| open state | the existing `aria-hidden` attribute drives it: `[aria-hidden="true"]` → `translateX(100%)`; otherwise `translateX(0)` — the same mounted-hidden shape as the left sidebar (`app.css:6823` transform vs `app.css:6773` display none — **prefer the transform form** so the open/close plays the same slide the left drawer does) | M2(b) |
| stacking rules | the phone block's stacking rules (`app.css:6744-6746`, `6766-6791`) are **deleted** in favor of the drawer; `.shell` stays a row | M2(b) |
| inner reset | re-home `:6777-6786` (`.right-pane-inner { position: static; width: 100% !important; border-left: none }`, `.right-pane-body { padding-top: 0 }`) inside the drawer rules — they are the drawer's content reset, not the stacked layout | M2(a)/(b) |
| expanded (takeover) | drawer at 100vw, same toggle semantics (`state/right-pane.ts:299-301`); `.shell-pane-takeover .main { display: none }` (`app.css:6788-6791`) is deleted — the drawer covers the chat | M2(d) row 4 |

**Children (in order, inside the drawer):** the 38px strip header (§2.2),
then `.right-pane-inner` > `.right-pane-body` with today's surface content
(untouched).

**States**:

| state | condition | what changes |
| --- | --- | --- |
| closed | `!pane.open` (`aria-hidden="true"`) | `translateX(100%)`; surfaces stay mounted through the closing glide (existing `right-pane.tsx:124-139` rule) |
| open | `pane.open` | `translateX(0)`; backdrop visible; strip header renders |
| expanded | `pane.expanded` | drawer width 100vw |
| gliding | transform transition in flight | 140ms `--rb-motion-menu-in` ease-out (the left drawer's curve, `app.css:6824`) |

**Interactions:**
- The pane toggle (`titlebar.tsx:148`, `HeaderIconButton "Toggle panel"`)
  stays in the titlebar's trailing group and remains the open/close control
  at every width — the phone equivalent of the one-control-two-meanings
  sidebar toggle (`app-shell.tsx:193-202`). (M2(b), verbatim rule.)
- Backdrop tap → `rightPaneStore.close(paneChatId)` (mirrors the sidebar
  backdrop wiring, `app-shell.tsx:584-588`).
- Escape → register on the ladder like the phone sidebar
  (`app-shell.tsx:351-359`; `state/escape.ts:81-89`, priority 12).
- Tab chips switch surfaces; the `+` menu's phone sheet form is this
  ticket's (§2.2), reusing 49's landed pattern.

**Motion:** the open/close slide above. Reduced motion: none is specced for
this drawer — the left drawer's slide (`app.css:6824`) is likewise not in
the reduced-motion snap list (inventory: reduced-motion ×11); mirror it. If
the implementer adds a snap, add it to both drawers in one rule and record
it in Comments.

**Text:** existing strings only — "Toggle panel" (`titlebar.tsx:148`),
"Collapse panel"/"Expand panel" (`titlebar.tsx:141`). No new strings.

**Data:** reads `useRightPane(panelKey(paneChatId, canvasSpace))`
(`app-shell.tsx:175`, existing); writes `rightPaneStore.toggle /
toggleExpanded / close` (existing). No new RPC.

### 2.2 The in-drawer tab strip header (research M2(b), verbatim)

- The surface tab strip must be VISIBLE on phone. Render `RightTabStrip` as
  a **38px header row INSIDE the drawer** (above `.right-pane-body`),
  reusing the desktop's chip geometry verbatim (`right-tab-strip.tsx:30-41`:
  112px chips, 4px gap, `shell.rs:6690-6691`).
- The alternative (recompute `--rb-pane-band` for phone and keep the strip
  in the titlebar) fights the 38px band the identity also needs (M3). See
  the research's open question Q1 — option (a) (strip inside the right
  drawer) is the recorded choice.
- At phone the titlebar band renders **no pane tabs** (pass no `paneTabs` /
  gate on phone at `app-shell.tsx:544-546`; `titlebar.tsx:135` already
  renders no children while shut). The band vars still receive
  phone-corrected numbers (50's branch) so nothing downstream reads
  0-by-accident.
- The strip's `+` menu (`AddSurfaceMenu`, the 168px card — `PLUS_MENU_W`,
  `right-tab-strip.tsx:41`) and the surface picker it opens get their phone
  form HERE (49's explicit deferral: "later (52) — rides the right-pane
  phone-drawer work"): render the card body in 49's landed bottom-sheet form
  (`.rb-drawer-card` + 49's Drawer mounting) when the strip lives in the
  drawer, trigger unchanged. One adoption site inside `RightTabStrip`; no
  new sheet primitive.

### 2.3 The pane width math at phone (research M2(d) rows 2–3 — 50's inputs, consumed here)

- The phone-corrected sidebar term (`sidebarForGeometry = phone ? 0 :
  sidebarWidth`) feeding `paneOpenWidth` (`app-shell.tsx:179`), the band
  inputs (`:514-519`), and `rowLeft` is **ticket 50's** — landed before this
  ticket runs. This ticket's job on that seam: verify `--rb-pane-now` /
  `--rb-pane-open` (`app-shell.tsx:503-510`) carry real numbers at phone
  (`rightPaneMaxWidth(375, 0) = 75`, expanded → `rightPaneTakeoverWidth(375,
  0)` = 375) and consume them; do not re-land the branch.
- The drawer's CSS width is **not** `paneOpenWidth`: the drawer rule owns
  `min(30rem, 88vw)`. `RightPane` must skip the inline column width at phone
  (`right-pane.tsx:120`) — the CSS `!important` defense (`app.css:6767`
  today) carries over to the drawer rule, or the component branches.

## 3. Pure logic to port

1. **The phone sidebar term is 50's, already landed** (its
   `sidebarForGeometry` branch feeds `rowLeft`, `paneOpenWidth`, and the
   band inputs — 50 §2.6). What this ticket owns in logic:
   - `RightPane` skips the inline column width at phone
     (`right-pane.tsx:120`) so the drawer rule (`min(30rem, 88vw)`) owns
     the width.
   - Verification math (for the tests below, using 50's corrected rowLeft
     136): `titlebarPaneBandWidth({viewport: 375, paneWidth: 75, rowLeft:
     136, takeover: false})` = `max(0, min(75 − 6, 375 − 136 − 6 − 16) −
     28)` = **41** (vs 0 today with `{375, 0, 320, false}`).
2. **New web unit tests** (`web/packages/app/tests/layout.test.ts`) —
   mobile-native, no desktop test maps (the research's pure-logic section:
   "each gets a new unit test named after the function it branches"). 50
   already lands the adjacent cases (`titlebarRowLeft phone sidebar is out
   of flow` and the `rightPaneMaxWidth(375, 0) = 75` phone-input case) — do
   not duplicate those; add:
   - `resolvePaneWidth phone inputs: sidebar term is zero` — the phone term
     0 yields `min(520, 75)` = 75; the expanded arm yields 375.
   - `titlebarPaneBandWidth phone inputs no longer collapse the band` —
     `{375, 75, 136, false}` → 41 (and `{375, 0, 320, false}` → 0 as the
     today-shape documentation case).
3. Desktop tests: none map — these are mobile-native rules; that is the
   point (the research says so explicitly).

## 4. Gaps this ticket closes

From research M2(d), verbatim:

| item | kind | expected | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| pane mount at phone | geometry | right-side overlay drawer, slides from right | stacked flex row under `.main` (`app.css:6744-6746`, `6766-6775`) | phone rules → fixed right drawer mirroring `app.css:6816-6832` |
| pane tab strip at phone | component | visible 38px strip with the surface chips | width 0 via `--rb-pane-band`/`--rb-pane-open` = 0 (`app-shell.tsx:179`, `514-519`; `state/layout.ts:404-415`; `app.css:503-529`) | render `RightTabStrip` in the drawer header; OR feed phone-corrected pane widths into the band vars |
| pane width resolution at phone | logic | pane width from the viewport (sidebar is out of flow) | `rightPaneMaxWidth(375, 304) = 0` (`state/layout.ts:54-56` via `app-shell.tsx:179`) | pass `sidebarWidth = 0` at phone (see M3's shared fix) |
| takeover at phone | behavior | drawer full-bleed / expanded state | `.shell-pane-takeover .main { display: none }` (`app.css:6788-6791`) | expanded = drawer at 100vw, same toggle semantics (`state/right-pane.ts:299-301`) |

From the research's consolidated gap table, the M2 rows (pane mount, tab
strip, pane width at phone) — same content. The M3 rowLeft row belongs to
ticket 50, not this one.

## 5. Do not

- Do not change desktop (≥769px) behavior: the in-flow pane column, the
  `usePaneGlide` width glide, the seam, the titlebar band, and
  `resolvePaneWidth` at desktop inputs are untouched. Every new rule lives
  inside the `@media (max-width: 768px)` block.
- 50 owns the phone geometry inputs (`sidebarForGeometry` for rowLeft AND
  the pane, `app-shell.tsx:179`/`:435-441`/`:514-519`) and the layout
  baseline — consume and verify; do not re-land the branch.
- 49 owns the responsive dialog/drawer primitive — do NOT route this side
  drawer through `RbResponsiveDialog` (that is the bottom-sheet form for
  transient pickers; navigation panes are side drawers, the research's Q2
  split). The `+` menu's phone form reuses 49's landed sheet pattern
  (49's explicit deferral to this ticket); do not invent a second sheet
  primitive. The chat context menu's phone arm is NOT this ticket's
  (49 defers it to "52/54 per the wave plan", but M2's research section
  does not cover it — leave it floating and record ownership in Comments).
- Do not add z-index tiers outside the ladder (`app.css:233-255`): the
  drawer sits at 30 under the titlebar (40), its backdrop at 20 — the same
  pair as the left drawer.
- Tickets 07/22/24/25/26/27 own the pane surfaces' content — only the mount
  chrome changes; `.right-pane-body`'s children are theirs.
- Do not re-add the pane drag seam at phone (retired by `app.css:6757-6760`);
  the desktop's minimum-geometry model (pane seams, takeover tween) is
  desktop-only (research "Desktop-only items").
- Takeover stays the same store semantics (`state/right-pane.ts:299-301`) —
  do not invent a separate phone takeover state.
- Do not re-add INVENTED UI: no new close buttons, no drawer-header captions
  beyond the strip itself.

## 6. Acceptance

- [ ] At 375px, open the files pane → it slides in from the RIGHT over the
      chat (140ms `--rb-motion-menu-in` ease-out, mirroring the left
      drawer), tab strip visible above it (38px header row, 112px chips),
      and the `+` menu opens in 49's sheet form.
- [ ] The titlebar's "Toggle panel" control opens/closes the drawer at every
      width; the chat title stays readable next to the cluster (50's inset)
      and the titlebar band renders no tabs at phone.
- [ ] Backdrop tap and Escape close the drawer; the chat underneath stays
      interactive after close.
- [ ] Expanded (takeover) at phone = the drawer at 100vw, same
      "Collapse panel"/"Expand panel" toggle semantics.
- [ ] At ≥769px nothing changes — in-flow column, band in the titlebar,
      seam mounted.
- [ ] Unit tests: `resolvePaneWidth phone inputs: sidebar term is zero`,
      `titlebarPaneBandWidth phone inputs no longer collapse the band` →
      `web/packages/app/tests/layout.test.ts`.
- [ ] Screenshot: `use-browser` at 375×667 against `web_smoke`, states:
      "files pane open, tab strip visible" (slides from RIGHT), "pane
      closed, chat full-width", "expanded takeover"; plus a ≥769px capture
      (desktop pair) unchanged.
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists (the drawer's
      `min(30rem, 88vw)` / `100vw` widths have no token; the 38px header and
      112px chips reuse the strip's existing constants).

## Comments

### Implementer note (2026-09-19)

Landed on `wp2r2/52-mobile-right-pane-drawer`. **Screenshot pairs are
waived per the run's hard rule** (no `web_smoke`, no browser/CDP, no dev
server); verification was `pnpm -r build` + `pnpm test` from
`web/packages/app`, both green — see the branch's commit for the tails.

What landed, per the spec:

- **CSS (`app.css`)** — the phone block's stacking rules are deleted
  (`.shell` column, the stacked `.right-pane` row + its `display:none`
  close, `.shell-pane-takeover .main`); `.right-pane` is now the right
  drawer mirroring `.sidebar`'s: fixed top/right/bottom 0,
  `min(30rem, 88vw) !important` (the `!important` is the inline-width
  defense the old rule carried), `translateX(100%)` closed driven by the
  existing `aria-hidden`, `transition: transform menu-in ease-out`, z 30
  under the titlebar's 40, `border-left` + open shadow like the left
  drawer's `border-right`/shadow; `.right-pane.right-pane-expanded` →
  `100vw !important`. The stacked inner rules re-homed as the drawer's
  content reset (`position: static; width: 100% !important; height: auto;
  flex: 1` — the stacked form's `border-top` is dropped, the divider is the
  38px header's `border-bottom`); `.right-pane-body { padding-top: 0 }`.
  New `.right-pane-strip-header` (38px via `--rb-titlebar-height`) + the
  strip's `flex: 1` inside it; new `.pane-backdrop` (z 20, shown by
  `.right-pane:not([aria-hidden="true"]) ~ .pane-backdrop` — the backdrop
  is a later shell sibling of the pane, same show shape as
  `.shell-sidebar-open .sidebar-backdrop`) and the ≥769 reset extends the
  existing one to `.pane-backdrop`. A `.rb-drawer-card.right-plus-menu-sheet`
  rule (phone block beside 49's sheet) carries the anchored card's rhythm
  (gap 2, `--rb-space-xs` pad, `--rb-overlay` bg, 13px) inside the shared
  sheet.
- **`right-pane.tsx`** — `useIsPhone()` branch: the inline column width is
  skipped at phone (§2.3) and `RightTabStrip` mounts in the 38px header
  above `.right-pane-inner`, chip geometry verbatim, strip-internal
  behavior untouched. The glide's inline inner-width writes and the rAF
  tween are left as-is: the CSS `width: 100% !important` outweighs them at
  phone, so no JS branching on the glide path (36 owns that machinery).
- **`app-shell.tsx`** — consumes 50's `sidebarForGeometry` inputs as-is
  (nothing re-landed; the new unit tests pin the outputs:
  `--rb-pane-open` 75 / band 41 at 375, expanded 375); `paneTabs` is gated
  on `!phone` at its mount site (§2.2's own prescription — the band keeps
  the expand control, renders no tabs); the `.pane-backdrop` div is mounted
  (tap → `rightPaneStore.close(paneChatId)`); a phone-gated Escape-ladder
  registration at `ESCAPE_PRIORITY.webDrawer` (12) closes the pane drawer —
  desktop-pane Escape is untouched.
- **`right-tab-strip.tsx`** — the `+` menu's phone arm (49's explicit
  deferral HERE): the card body renders through `RbDrawerSheet` (49's
  landed sheet, `cardClassName="right-plus-menu-sheet"`), trigger unchanged
  (same button, same press-was-open toggle, controlled `open`). The desktop
  anchored portal is untouched; the rows are one shared `AddSurfaceRows`.
  The sheet's window listeners (outside-press/Escape) are skipped at phone
  — Base UI's modal owns them, and the anchored card's outside test would
  read the sheet's own portal as "outside". The menu also closes with the
  pane (a `paneOpen` effect): at phone the strip header stays mounted
  through the drawer's close glide, and the sheet would otherwise linger
  over the closed drawer when the ladder's drawer rung consumed the
  Escape before the sheet's own handler.
- **`tests/layout.test.ts`** — both specced cases added under a new
  `phone pane drawer inputs (ticket 52)` describe: `resolvePaneWidth phone
  inputs: sidebar term is zero` (75 / expanded 375) and
  `titlebarPaneBandWidth phone inputs no longer collapse the band` (41, and
  the today-shape `{375, 0, 320}` → 0 documentation case). 50's adjacent
  cases are untouched.

Deviations / notes for the record:

- **`titlebar.tsx` needed no edit.** §2.2's prescription is the app-shell
  mount-site gate, and `Toggle panel`/`Collapse panel`/`Expand panel` stay
  mounted by construction (they live outside the `paneTabs` slot). The
  file-table row is satisfied vacuously; nothing in the component is
  phone-aware.
- **Reduced motion: no new rule added** (so none recorded for both
  drawers). The ticket's premise ("the left drawer's slide is likewise not
  in the snap list") is stale against the current sheet: `.sidebar` and
  `.right-pane` are BOTH already in the pre-existing reduced-motion snap
  lists (the shell-tween list and the `:12952` pane list), so the right
  drawer's slide already snaps under reduce with the left drawer's, in the
  same pre-existing rules.
- **Two drawers open at once** (sidebar + pane, both z 30, both rung 12):
  not specced, not exclusivity-wired — Escape peels one per press in
  registration order (sidebar first, it registered first), each backdrop
  closes its own drawer. Recorded as a known corner, deliberately not
  invented around.
- **36 merger note:** the takeover glide (`useTakeoverStableWidth`,
  `--rb-main-stable`, `shell-pane-gliding`) is untouched per the Do-nots.
  At phone it can pin `.main-inner` to the desktop-modeled conversation
  width (375 − dragged-sidebar − pane) for the 200ms glide window —
  invisible while the drawer covers the chat, a brief squeeze in the
  sliver it does not. If 36 wants to arm/suppress the stable width at
  phone, that seam is theirs.
- **The chat context menu's phone arm is left floating, NOT this
  ticket's** (49 deferred it to "52/54 per the wave plan", but M2's
  research section does not cover it). Ownership: ticket 54 or the next
  mobile wave ticket that specs it.

