# 38 — Model picker loading and parity

**What to build:** The model picker chip stops showing an eternal spinner.
Loading becomes **Idle/Loading-only, never Error** (an errored catalog shows
the chip's real label — remembered label, else the raw model id); the catalog
heals itself the way the desktop's per-render `ensure_harnesses` does, so a
load that fails after connect (or races the boot dial) recovers on the next
status change/focus instead of latching; the card-level skeleton takeover
never sits unscheduled; and the invented native `title` tooltip on the chip
is deleted. The identity card's geometry is already a faithful port of the
desktop card — this ticket **verifies** it and leaves it alone. After this
ticket, a refresh, a flaky engine, or a slow cold engine resolves the chip
like the desktop does.

**Blocked by:** None — can start immediately (parallel with 37; 37 owns the
chip's *placement* in the pill via the actions-row fix — the S3 "Chip
placement in pill" row closes there, cross-referenced below, not here).

**Status:** ready-for-agent

**Research:** `../research-2026-09-19/composer-model-picker-send.md` S2
((a)–(d)), S3 ((a)–(d)), "Consolidated gap table" rows 4–10, "Pure logic to
port + desktop test names" items 1, 2, 4.

**Desktop reference (for lookups only):**
`crates/ui/src/pickers.rs` — the chip loading semantics `catalog_loading` /
`models_loading` / `chip_icon_loading` / `chip_label_loading`
(pickers.rs:4207-4221), the errored-catalog label resolution `model_label`
(pickers.rs:4186-4206), `no_agents_available()` (pickers.rs:825-833), the
per-render eager kick `ensure_harnesses` + `prefetch_models`
(pickers.rs:4164-4168, 1028-1044), forced reload + stale-while-revalidate
(pickers.rs:1032-1036, 1050-1053), the opencode ladder (pickers.rs:1138-1147),
the trigger chip `trigger_chip` (pickers.rs:2229-2336), the identity card
`render_harness_model_popover` (pickers.rs:3143-3464) and its takeovers
(pickers.rs:3153-3180, 3195-3223), the desktop test module (pickers.rs:4992+).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/composer-pickers.tsx` | edit | the chip's `iconLoading` (composer-pickers.tsx:210), `labelLoading` (composer-pickers.tsx:205-209), the error-label resolution, the `noAgents` gate (composer-pickers.tsx:117); the `title={…}` deletion (composer-pickers.tsx:245); the card's Idle-slot scheduling (composer-pickers.tsx:577-585) |
| `web/packages/app/src/state/picker-catalog.ts` | edit | `#retryOfflineSlots` (picker-catalog.ts:395-409) — treat offline errors on a not-yet-connected client as retryable on any status change; a re-kick cadence honoring the Idle-only rule |
| `web/packages/app/src/lib/catalog-loading.ts` | new | `catalogLoading`, `modelsLoading`, `shouldReload(slot, force)` — the pure predicates over `LoadableList` |
| `web/packages/app/tests/catalog-loading.test.ts` | new | `chip_loading_states_exclude_error` + the `shouldReload` table tests |

---

## 1. Context a fresh session needs

- The chip and card are `ComposerPickers` in
  `components/composer-pickers.tsx` (ticket 10's rewrite): one chip
  (brand/spinner slot, label/ghost-bar slot, yielding suffix) opening one
  304px card on `PopoverCardFlush`, `placement="anchorAboveEnd"`
  (composer-pickers.tsx:232) — matching the desktop's
  `anchored_menu_above_end`-equivalent flush-right placement. The card's
  keyboard handling is a window-capture listener (ticket 10 deviation 4) —
  keep it.
- The chip's loading slots today (`composer-pickers.tsx`):
  - `iconLoading = !harnesses.loaded && chatConfig === null &&
    defaults.harness === null && !noAgents` (composer-pickers.tsx:210).
  - `labelLoading = draft.model !== null && modelLabel === draft.model &&
    rememberedLabel === null && (!harnesses.loaded || modelsList.loading)`
    (composer-pickers.tsx:205-209).
  - `noAgents = harnesses.loaded && harnesses.error === null &&
    offered.length === 0` (composer-pickers.tsx:117).
- The catalog is `state/picker-catalog.ts` (`PickerCatalog`): slots are
  `LoadableList` (`loaded` is only set by `listWithRows`, never by
  `listWithError` at picker-catalog.ts:75-77), so `!harnesses.loaded` is true
  for the catalog's **Idle, Loading AND Error** slots — the moment the
  harness slot carries an error, the chip renders the glyph spinner +
  skeleton bar **forever**; an errored catalog is visually indistinguishable
  from a loading one.
- Who kicks the catalog, and when:
  1. `ConversationPage` effect: `void session.catalog.loadHarnesses()` on
     `[session, chatId]` (chat-page.tsx:117-124) — once per page mount /
     session or chat change. Non-forced: `shouldLoad = !loaded || error !==
     null || force` (picker-catalog.ts:203).
  2. Card open: `loadHarnesses({force: true})` + `prefetchModels(true)`
     (composer-pickers.tsx:220-225).
  3. Heal on engine (re)connect: the catalog subscribes `client.onStatus` in
     its constructor (picker-catalog.ts:123-135) and re-kicks errored,
     not-loaded slots on every `"connected"` event (`#retryOfflineSlots`,
     picker-catalog.ts:395-409).
- The boot race the heal exists for: `EngineClient.call` throws
  `RpcError("transport", "Engine is offline; reconnecting")` whenever the
  socket is not established (engine-client/src/client.ts:235-242), and the
  registry only calls `client.connect()` **after** the async IndexedDB cache
  seed resolves (engine-client/src/registry.ts:277-283). Meanwhile the root
  layout flips the app phase to `"ready"` as soon as the cache seeds rows —
  `anythingLive = engines.some(e => e.state === "connected" || e.chats.loaded
  || e.spaces.loaded)` (root-layout.tsx:50-53) — so `ConversationPage` mounts
  and fires `loadHarnesses()` **while the client is still pre-dial**; the
  call throws instantly and the slot latches `Error("Engine is offline;
  reconnecting")`. The `"connected"` heal then re-kicks it — that is the
  intended path, and it is why a plain refresh usually ends up working.
- Coverage holes in that heal (any of these leaves the eternal spinner): the
  heal fires **only on a `connected` status event**, so a failure that lands
  while the connection is up (the 30s unary call timeout, client.ts:81,
  545-560 — a slow cold engine) sets `Error` with no future event to re-kick
  it; `#retryOfflineSlots` never touches a slot stuck in `loading: true`
  (picker-catalog.ts:399 checks `!this.#harnessesInFlight`); the opencode
  model ladder can legitimately hold `loading` for ~36s+ (2 retries at 2s/4s
  around 30s calls, picker-catalog.ts:263-304) and reads as "always loading"
  in the meantime. `setTargetDevice` is never called from the composer path
  (only the two settings pages, settings-accounts.tsx:199,
  settings-agents.tsx:139).
- The card-level takeover is error-aware (error → ErrorRow with Retry,
  composer-pickers.tsx:586-592) but the loading takeover is not gated on an
  in-flight fetch: `if (!harnesses.loaded && harnessError === null) return
  SkeletonMenuRows` (composer-pickers.tsx:577-585) — an **Idle** slot also
  shows skeletons forever if nothing kicks it.
- Ticket 13 already deleted `composerReady` (§2.16 fix 2): the textarea,
  paperclip and send button stay usable while the catalog loads or errors —
  do not re-gate them.
- RPC surface: `ListHarnesses` / `ListModels` via the catalog; the opencode
  retry ladder is ported (ticket 10). The desktop's engine-side harness
  registry never forces a lazy resolve ("never leave ListHarnesses slow" per
  crates/engine/src/registry.rs:3-4, 414-418, 670) — refresh-heals land rows
  fast.
- Vocabulary: **chat**, **space**, **engine**, **harness** (not provider).

---

## 2. Spec

### 2.1 The chip's loading gates — Idle/Loading only, never Error

Desktop (`pickers.rs`):

- Chip loading is **Idle/Loading only, never Error**:
  `catalog_loading = matches!(self.harnesses, Loadable::Idle |
  Loadable::Loading)` (pickers.rs:4207); `models_loading` requires the
  harness's model slot to be neither `Ready` nor `Error` (pickers.rs:4208-
  4213); `chip_icon_loading = effective_harness.is_none() && !no_agents &&
  catalog_loading` (pickers.rs:4216-4217); `chip_label_loading = !no_agents
  && model_label.is_empty() && (catalog_loading || models_loading)`
  (pickers.rs:4220-4221).
- An errored catalog still renders a **label** on the chip: `model_label`
  resolves remembered label → configured id (pickers.rs:4186-4206) — never a
  spinner, never a placeholder word.
- `no_agents_available() = harnesses.ready().is_some_and(offered empty)` —
  "False while the catalog is still loading or failed" (pickers.rs:825-833).

**States** (chip; current web values from the research, to be replaced):

| state | condition | what changes |
|---|---|---|
| icon loading | `chip_icon_loading` — no effective harness, not no-agents, catalog Idle/Loading | brand slot → mini glyph spinner |
| label loading | `chip_label_loading` — empty model label and (catalog Idle/Loading OR model slot not Ready/Error) | label slot → `SkeletonBar(56)` |
| catalog error | harness slot has an error | **the real label renders** (remembered label → configured/raw id); never spinner/skeleton; the error surfaces via the card's ErrorRow |
| no agents | `harnesses.ready()` and offered empty | label `"No agents available"`; icon `terminal` tinted `text_muted` (unchanged — the web's `noAgents` gate composer-pickers.tsx:117 already matches) |

**Web change.** Add `catalogLoading = !harnesses.loaded &&
harnesses.error === null` and a `modelsLoading` that treats an errored model
slot as loaded; gate `iconLoading`/`labelLoading` on them (replacing the
error-blind `!harnesses.loaded` arms at composer-pickers.tsx:205-210); on
error, resolve the label like pickers.rs:4186-4206 (remembered label →
configured id) so the chip shows a real name. The pure predicates live in
`lib/catalog-loading.ts` (§3).

**Trigger chip reference** (`trigger_chip`, pickers.rs:2229-2336, geometry
already landed per ticket 10 §2.1 — unchanged by this ticket): `h 32`,
`max_w 248`, `min_w_0`, flex row, `items_center`, `gap 6`, `px 10`,
`rounded 8`, `ui_rems(12)` MEDIUM; text `hover_blend(text.opacity(0.9) →
text)` (2273-2281); bg open snaps to `element_hover` (2282-2286); brand icon
16px; `icon_loading` → mini glyph spinner (2296-2304); `label_loading` →
`skeleton_bar(56)` (2315-2317); suffix `flex_shrink(1000)`, `text_muted@
0.7`, active tint `text@0.85` (2326-2334).

**Interactions.** Unchanged (ticket 10 §2.5): click → toggle open (forced
reload + prefetch on open); keyboard rides the window-capture listener.
**Text.** No new strings. The no-agents copy stays verbatim
(`"No agents available"` + the settings body). The error never renders a
placeholder word on the chip — label or raw id only.

### 2.2 The card's takeover states — never an unscheduled skeleton

Desktop: the loading/error takeover is `h 216`, `p 8`, 5 skeleton rows /
retry row (pickers.rs:3153-3180); the Loading/Idle takeover is the same
skeleton (pickers.rs:3154-3165) but the desktop never sits in it — its
render-loop kick + heal land rows within a frame of connect.

**Web change.**

- The error takeover (ErrorRow + Retry, composer-pickers.tsx:586-592) stays.
- The loading takeover (composer-pickers.tsx:577-585) gets a kick behind it:
  when the card opens with an **Idle** harness slot, schedule a non-forced
  `loadHarnesses()` (not just the current `{force: true}` at
  composer-pickers.tsx:220-225 — a forced load already covers open; the
  Idle-slot case is the card rendered by any other path with nothing
  scheduled). Idle ⇒ skeletons with a load pending; Loading ⇒ skeletons with
  the in-flight call; Error ⇒ the retry row. No state shows skeletons with
  nothing scheduled.

**Data** (reads / writes / RPC): reads the catalog's harness slot + the
effective harness's model slot; writes nothing; RPC `ListHarnesses` /
`ListModels` through `PickerCatalog` (stale-while-revalidate: loaded rows
stay on screen while the fresh catalog lands — ticket 10's landed `force`
semantics).

### 2.3 The re-kick cadence — the desktop's per-render `ensure_*` discipline

Desktop:

- **Per-render eager kick**: the pickers' render calls
  `self.ensure_harnesses(false, cx)` + `self.prefetch_models(false, cx)`
  every frame (pickers.rs:4164-4168) — `ensure_harnesses` non-forced only
  loads from `Idle`; `Loading` never re-fires; `Ready`/`Error` need `force`
  (pickers.rs:1028-1044). The registry-connected re-kick comes for free
  because every state change re-renders the composer and thus the pickers.
- Forced reload on open + stale-while-revalidate (pickers.rs:1032-1036):
  ready rows stay on screen while the fresh catalog lands (the `if !
  matches!(…Ready)` guard at pickers.rs:1050-1053).
- The opencode model ladder: two extra attempts at 2s/4s keeping one Loading
  slot alive (pickers.rs:1138-1147).

**Web change.** The web has no per-frame render loop; port the *discipline*
on a cheap cadence:

- A per-commit effect in `ComposerPickers` (or the composer) that calls a
  non-forced `loadHarnesses()` — a no-op unless the slot is Idle, per
  `shouldReload` (§3). React re-renders on every relevant state change, which
  is the web peer of the desktop's per-render re-render kick.
- Boot-race hardening in `#retryOfflineSlots` (picker-catalog.ts:395-409):
  treat `Error("Engine is offline; reconnecting")` on a not-yet-connected
  client as retryable and re-arm it on the **next status change of any
  kind**, not just `"connected"` — a failure that lands while the connection
  is already up (the 30s unary timeout) gets healed by the cadence/focus
  re-kick or the card-open force, and surfaces on the chip as a label (2.1)
  so it is never read as eternal loading.
- Optional extra triggers (pick any that keep it cheap): window focus
  re-kick of errored slots; a low-frequency timer. All must route through
  `shouldReload` so a `Ready` catalog is never re-kicked non-forced and a
  `Loading` slot is never double-fired (`#harnessesInFlight` guard stands).

### 2.4 The identity card — a faithful port, verify only

The research verified the card value-by-value; **this ticket changes none of
it.** Copy of the desktop reference (research S3 (b)) for the verification
pass — `render_harness_model_popover` (pickers.rs:3143-3464) on
`popover_frame_flush(304.0, …)` (pickers.rs:4252-4259; frame max-height 640
per ticket 09):

| region | geometry | source |
|---|---|---|
| card shell | radius 12, 1px `hairline(0.10)`, `shadow_lg`, glass overlay over 44px blur (frost.rs:23), flush = no inset | popover.rs:306-331 (per web app.css:4360-4382 — matches) |
| width | 304 fixed | pickers.rs:4258; web composer-pickers.tsx:236 (`width={304}`) |
| tab strip | `h 40`, `px 6`, `border_b_1 hairline(0.08)`, row `items_center`, `gap 2`; tabs 32×32 `rounded 8`; favorites star 15px; harness brands 16px tinted; viewed marker 2px accent at `bottom -4` (left/right 6); disabled (locked) tabs `opacity 0.35` with the click handler attached | pickers.rs:3232-3312 |
| search row | `h 40`, `px 10`, `border_b_1 hairline(0.08)`, `gap 8`; magnifier 14px `text_muted@0.7`; input `ui_rems(13)`, placeholder "Search models…" | pickers.rs:3317-3339 |
| list host | `id model-list-scroll-host`, relative, `flex_none`, `h LIST_HEIGHT 216`, `py 6`, `bg ink(0.02)`; uniform_list virtualized, `px 6` | pickers.rs:3147, 3405-3430 |
| empty states | searching → "No models found"; favorites → "No starred models yet — hit a row's star"; slot error → retry row; else 5 skeleton rows | pickers.rs:3373-3402 |
| model row | `px 8`, `py 5` (compact) / `py 6` (favorites), `rounded 6`, flex row `items_center` `gap 10`; label `ui_rems(12.5)` MEDIUM `theme.text`; attribution `ui_rems(11)` `text_muted@0.7`; selected = `card_selected_bg()` + inset 1px `hairline(0.09)` ring; keyboard cursor `ink(0.05)`; **hover moves the cursor**; ⌘N kbd chip for ix < 9; star button 22×22 `rounded 6`, star 13px `theme.warning`/`text_muted@0.45`, `hover ink(0.08)`, stops propagation | pickers.rs:3470-3659 (per ticket 10 §2.3.4; web app.css:4096-4234 matches) |
| traits tray | `flex_none`, `border_t_1 hairline(0.08)`, `max_h 236`, `overflow_y_scroll`, `px 6 pb 6`; sections `gap 2`; rows `py 5`, `rounded 6`, `ui_rems(12.5)`; `"Default"` badge = bare 10px semibold `text_muted@0.6`; **no check marks**; keyboard nav never enters | pickers.rs:3439-3454, 3666-3774, 3780 |
| no-agents takeover | `p 16` column centered `gap 8`: terminal 20px, "No agents available" 13px `theme.text`, body 12px `text_muted` centered | pickers.rs:3195-3223 |
| loading/error takeover | `h 216`, `p 8`, 5 skeleton rows / retry row | pickers.rs:3153-3180 |

Trigger → data → loading chain (desktop): chip click → `toggle` (open, prime
rail Favorites-if-favorites else Harness, anchor cursor on the selected row,
reset scroll, focus the search input, **force-reload harnesses + prefetch
every offered harness's models**) — pickers.rs:1003-1019 area + the
toggle-step list in ticket 10 §2.5; the render loop keeps both catalogs warm
every frame (pickers.rs:4164-4168). The web already ports the toggle steps
(ticket 10); this ticket adds the render-loop-warmth equivalent (2.3).

**Verification only:** assert the landed values still hold (card 304px,
max-height 640, radius 12, blur 44; tab strip / search row / list band /
traits tray per the table above; `.popover-card` app.css:4360-4382,
`.model-*` app.css:3916-4284). If any drifted, fix the drift — do not
redesign.

---

## 3. Pure logic to port

Put these in `web/packages/app/src/lib/catalog-loading.ts` (new).

### 1. `catalogLoading` / `modelsLoading` classification (fixes S2's visuals)

A pure predicate over `LoadableList` mirroring pickers.rs:4207-4213 —
`loading = !loaded && error === null` for harnesses; models loading = slot
present, not `Ready`, not `Error`. Port the desktop test
`chip_loading_states_exclude_error` (write it mirroring pickers.rs:4207-4221's
shape; the desktop's own tests live around pickers.rs:4992+ —
`default_model_is_first_catalog_row`,
`offered_harnesses_follow_the_catalog_enabled_flags` already have web mirrors
per ticket 10).

### 2. `shouldReload(slot, force)` — the ensure-kick discipline

Port `ensure_harnesses`'s Idle/Loading/Ready|Error+force table
(pickers.rs:1037-1041) as a pure `shouldReload(slot, force)` used by whatever
cadence the web picks:

| slot | `force: false` | `force: true` |
|---|---|---|
| Idle | load | load |
| Loading | never re-fires | never re-fires (in-flight guard stands) |
| Ready | no | load (stale-while-revalidate) |
| Error | no (healed by the retryable-offline rule / focus / open) | load |

Desktop tests to mirror: the stale-while-revalidate behavior comments
(pickers.rs:1032-1036) and the opencode ladder (picker-catalog.ts already
ports it; test names exist in ticket 10's Comments —
`tests/picker-catalog.test.ts` covers force/targetDeviceId/invalidate-rekick/
offline-retry).

### 3. Reuse the existing suites

`tests/composer-flip.test.ts`, `tests/composer-send.test.ts`,
`tests/model-rows.test.ts`, `tests/traits-summary.test.ts`,
`tests/picker-catalog.test.ts` — all green at HEAD per ticket Comments; this
ticket must not disturb them.

---

## 4. Gaps this ticket closes

From the research's S2 (d), verbatim:

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| Chip icon loading gate | WRONG | `catalog_loading` = Idle\|Loading only (pickers.rs:4207, 4216-4217) | `!harnesses.loaded` spans Error too (composer-pickers.tsx:210) | `const catalogLoading = !harnesses.loaded && harnesses.error === null`; gate iconLoading/labelLoading on it; on error show the remembered/raw label like pickers.rs:4186-4206 |
| Chip label loading gate | WRONG | `models_loading` excludes Ready\|Error (pickers.rs:4208-4213) | `modelsList.loading` only, but `!harnesses.loaded` arm is error-blind (composer-pickers.tsx:205-209) | same catalogLoading + a modelsLoading that treats an errored slot as loaded |
| Catalog re-kick cadence | MISSING | every pickers render, Idle-only, idempotent (pickers.rs:4164-4168) | page mount + card open only (chat-page.tsx:117-124; composer-pickers.tsx:220-225) | re-kick idle slots on a cheap cadence (composer render effect or focus/interval), porting ensure_*'s Idle-only rule |
| Post-connect error has no heal | MISSING | render-loop kick + every open forces (pickers.rs:4164-4168, 1032-1036) | heal only on a `connected` event (picker-catalog.ts:395-409) | re-kick errored slots on window focus / a timer, or surface the error state on the chip so the user can Retry |
| Boot race: initial load pre-dial | BEHAVIOR | desktop renders its own registry; no cache-seed phase flip | page mounts while client pre-dial (root-layout.tsx:50-53; registry.ts:277-283) → instant transport error (client.ts:235-242) | keep the heal, but also treat `Error("Engine is offline…")` on a not-yet-connected client as retryable (re-arm on the next status change of any kind, not just `connected`) |
| Card takeover for an Idle slot | PARTIAL | loading takeover = Loading\|Idle with the render kick behind it (pickers.rs:3154-3165) | skeletons for Idle with nothing scheduled (composer-pickers.tsx:577-585) | schedule a non-forced load when the card opens with an Idle slot (not just force) |

From the research's S3 (d), verbatim (row 4 is cross-referenced to 37 —
this ticket closes the first three and verifies the last two):

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| Chip native tooltip | INVENTED | no tooltip on the chip (pickers.rs:2229-2336) | `title={…}` (composer-pickers.tsx:245) | delete the `title` |
| Chip on error | WRONG | label from remembered/configured id (pickers.rs:4186-4206) | spinner + skeleton (composer-pickers.tsx:205-210) | S2 fix |
| Card in stuck-loading | WRONG | skeleton takeover exists but never persists (render kick, pickers.rs:4164-4168) | same takeover, persistent (composer-pickers.tsx:577-585) | S2 fix |
| Chip placement in pill | WRONG | bottom-pinned actions row (composer.rs:7752-7758) | mid-pill over text (S1) | S1 fix — **ticket 37** |
| Card geometry (spot check) | MATCHES | 304/640/r12/blur44 (pickers.rs:4252-4259; popover.rs:306-331) | width 304, maxHeight 640 (composer-pickers.tsx:236-237); `.popover-card` r12/blur44 (app.css:4360-4376) | — |
| Tab strip / search / list / tray | MATCHES | pickers.rs:3232-3312 / 3317-3339 / 3405-3430 / 3439-3454 | app.css:3916-3924 / 3986-4013 / 4017-4033 / 4243-4284 | — |

Consolidated table (research "Consolidated gap table"), filtered to this
ticket — rows 4–10:

| # | item | kind | desktop value (file:line) | web value (file:line) | fix |
|---|---|---|---|---|---|
| 4 | Chip icon loading gate | WRONG | Idle\|Loading only (pickers.rs:4207, 4216-4217) | `!harnesses.loaded` spans Error (composer-pickers.tsx:210) | error-aware `catalogLoading` |
| 5 | Chip label loading gate | WRONG | excludes Ready\|Error (pickers.rs:4208-4221) | error-blind (composer-pickers.tsx:205-209) | same |
| 6 | Chip label on error | MISSING | remembered/configured id (pickers.rs:4186-4206) | spinner/skeleton (composer-pickers.tsx:247-263) | resolve the label; error surfaces via the card |
| 7 | Catalog re-kick cadence | MISSING | every pickers render, Idle-only (pickers.rs:4164-4168) | page mount + card open (chat-page.tsx:117-124; composer-pickers.tsx:220-225) | cheap periodic/focus re-kick with the Idle-only rule |
| 8 | Post-connect error heal | MISSING | render kick + forced open (pickers.rs:4164-4168, 1032-1036) | only on a `connected` event (picker-catalog.ts:395-409) | retry errored slots on focus/timer; treat offline-errors as retryable on any status change |
| 9 | Card takeover for Idle slot | PARTIAL | kick always pending (pickers.rs:3154-3165) | nothing scheduled (composer-pickers.tsx:577-585) | schedule a load on open with an Idle slot |
| 10 | Chip native `title` tooltip | INVENTED | none (pickers.rs:2229-2336) | composer-pickers.tsx:245 | delete |

---

## 5. Do not

- **Do not** fix the chip's placement in the pill — ticket 37 owns the
  actions-row containing block; the S3 "Chip placement in pill" row closes
  there.
- **Do not** touch the send path, chat creation, navigation ids, or id
  minting — ticket 39.
- **INVENTED — do not re-add** a native `title=` tooltip on the chip
  (composer-pickers.tsx:245): the desktop chip has **no tooltip**
  (pickers.rs:2229-2336; ticket 10 §2.1 "No tooltip"). Delete it outright —
  no replacement tooltip, no aria-title invention.
- **Do not** restyle the identity card — its geometry is a faithful port
  (research S3 (b) table; MATCHES gap rows). This ticket verifies values; a
  drifted value gets restored to the spec, never redesigned.
- **Do not** show a placeholder word on the chip in any state — never
  `"Model"`, never `"Default model"` (ticket 10 §2.1); an errored catalog
  resolves remembered label → configured id → raw id, else the no-agents
  state.
- **Do not** re-gate the composer's usability on the catalog — ticket 13's
  §2.16 fix 2 stands: the textarea, paperclip, and send stay usable while
  the catalog loads or errors.
- **Do not** port the desktop's per-render kick as a per-frame rAF loop —
  port the *discipline* (Idle-only, idempotent, `shouldReload`) on a cheap
  cadence; a rAF-per-frame RPC check is not the desktop's behavior (its
  `ensure_*` is a no-op unless Idle).
- **Do not** double-fire loads: the `#harnessesInFlight` guard and the
  Loading-never-refires rule stand; the opencode ladder's ~36s legitimate
  Loading window is not a bug — do not "fix" it with a timeout.
- **Do not** undo ticket 10's landed decisions: the window-capture keyboard
  listener (deviation 4), the `--rb-z-menu` ladder, the
  `anchorAboveEnd`/Base UI placement, the stable empty-slot identity fix
  (React error #185), the staged-rows verification precedent (deviation 1).
- **Desktop-only, do not attempt:** `ROBOCO_SLOW_CATALOG_MS`,
  `ROBOCO_HARNESS` env knobs and the mock-harness rig (ticket 10's Do-not
  forbids `ROBOCO_HARNESS=mock`), the gpui render loop itself.
- **Do not** add a Retry affordance to the composer chip — the error
  surfaces via the card's ErrorRow + Retry (composer-pickers.tsx:586-592)
  and the cadence heals.

---

## 6. Acceptance

- [ ] A catalog slot that carries an error shows the chip with its **real
      label** (remembered label, else the configured/raw model id) — no
      spinner, no skeleton bar; opening the card shows the ErrorRow + Retry.
- [ ] Chip loading states fire only for Idle/Loading catalogs: unit test
      `chip_loading_states_exclude_error` (mirroring pickers.rs:4207-4221)
      asserts an errored slot never sets icon/label loading, and a
      Ready/Error model slot never sets models loading.
- [ ] A page load while the engine is still dialing (cache-seeded mount)
      resolves the chip after connect: the offline error on a not-yet-
      connected client re-arms on the next status change of any kind — not
      only `"connected"` — and the plain-refresh heal still works.
- [ ] An error that lands while connected (kill the engine mid-`ListHarnesses`,
      or let the 30s call timeout fire) heals via the re-kick cadence or the
      card-open force; in the interim the chip shows the label, not a
      spinner.
- [ ] An Idle slot with the card open schedules a non-forced load —
      skeletons never sit unscheduled (unit: opening with an Idle slot
      triggers exactly one `loadHarnesses()` call).
- [ ] Non-forced re-kicks are no-ops on Ready and Loading slots
      (`shouldReload` table unit tests); the opencode ladder still holds one
      Loading slot through its 2s/4s retries.
- [ ] The native `title` tooltip is gone: grep `composer-pickers.tsx` for
      `title=` — no hit on the chip.
- [ ] Card geometry unchanged (spot-check): 304px width, 640px max-height,
      radius 12 / blur 44 card shell, 40px tab strip, 40px search row
      ("Search models…"), 216px list band, pinned traits tray with the
      "Default" badge and no check marks.
- [ ] Unit tests: `chip_loading_states_exclude_error` →
      `web/packages/app/tests/catalog-loading.test.ts`; `shouldReload` table
      (Idle/Loading/Ready/Error × force) → same file; existing
      `tests/picker-catalog.test.ts` cases (force / targetDeviceId /
      invalidate-rekick / offline-retry) stay green.
- [ ] Screenshot pair, desktop vs web, states: (a) chip mid-load (spinner +
      skeleton bar) with a slow catalog; (b) chip with an errored catalog
      showing the remembered label; (c) card open with rows loaded; (d) card
      showing the ErrorRow + Retry after a failed load; (e) card's no-agents
      takeover.
- [ ] `pnpm -r build` green; `web/packages/app` vitest green (including
      `tests/model-rows.test.ts` / `tests/traits-summary.test.ts` /
      `tests/picker-catalog.test.ts` untouched-or-extended, not broken).
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

### Implementation (2026-09-19)

**What landed.** The loading/error semantics, the re-kick discipline, the
boot-race heal, and the tooltip deletion — no CSS, no card geometry, no
send path, no chip placement (37's landed work untouched):

- **§2.1 chip gates** — `lib/catalog-loading.ts` (new) ports the pure
  predicates: `catalogLoading` (`!loaded && error === null`, pickers.rs:
  4207), `modelsLoading` (slot absent/Idle/Loading — an errored model
  slot reads as settled, pickers.rs:4208-4213), and `shouldReload`
  (pickers.rs:1037-1041's Idle/Loading/Ready|Error+force table).
  `composer-pickers.tsx` gates `iconLoading`/`labelLoading` on them
  (replacing the error-blind `!harnesses.loaded` arms) and adds one
  `harnesses.error === null` arm to `labelLoading`: with an errored
  harness catalog the (never-requested, thus "loading") model slot must
  not resurrect the skeleton — that is the web's counterpart of the
  desktop's `model_label.is_empty()` guard (pickers.rs:4186-4206), where
  the resolved raw id makes the label non-empty and kills the ghost bar.
  `resolveChipLabel` itself needed no change (it already resolves
  remembered label → configured/raw id); the error simply stops being
  masked. The `noAgents` gate (composer-pickers.tsx:117) verified against
  `no_agents_available()` — matches, unchanged.
- **§2.3 re-kick cadence** — a per-commit effect in `ComposerPickers`
  (`[catalog, harnesses]` deps: every slot-identity move) calls a
  non-forced `loadHarnesses()` gated by `shouldReload(slot, false)` — a
  no-op unless Idle, exactly `ensure_harnesses(false)`. Plus the ticket's
  optional window-focus trigger: focus re-arms an errored, row-less slot
  (reset → Idle, then the non-forced kick), never touching Ready/Loading
  (gap row 8's first arm; the card-open force stays the forced path).
- **§2.3 boot-race heal** — `picker-catalog.ts` `#retryOfflineSlots`
  grows an `offlineOnly` arm: `connected` still heals every errored,
  not-loaded slot (the plain-refresh heal, unchanged); any OTHER status
  change re-arms only slots carrying the literal pre-dial error
  (`"Engine is offline; reconnecting"`, client.ts:241 — matched by exact
  message via `isOfflineError`). A timeout/teardown error that landed
  while connected is NOT re-armed there (heals via cadence/focus/open,
  per the ticket); a re-arm while still disconnected may re-latch once
  per status event — bounded, instant, client-side rejections.
- **§2.2 Idle-slot scheduling** — no JSX change to the takeover itself
  (Idle|Loading ⇒ skeletons, Error ⇒ ErrorRow per pickers.rs:3153-3180):
  the "kick behind it" is the cadence effect above plus the open-force —
  every Idle slot is one commit away from a pending load, and a load that
  flips to Loading is in-flight by construction. No state shows
  unscheduled skeletons.
- **§2.4 verification-only pass** — card geometry re-verified at HEAD,
  no drift: width 304 (`.identity-card`), max-height 640 (inline),
  radius 12 (`--rb-radius-card`), 44px blur (`.popover-card`), 40px tab
  strip (32×32 r8 tabs, 2px marker) / 40px search row ("Search models…")
  / 216px list band / traits tray (236px cap, "Default" badge, no check
  marks) — all present in `app.css`/`composer-pickers.tsx`.
- **S3 row 10** — the invented native `title` on the chip deleted
  outright; no replacement, no aria invention (grep `title=` in
  composer-pickers.tsx: no hit).

**Tests.** New `tests/catalog-loading.test.ts` (8):
`chip_loading_states_exclude_error` (mirrors the pickers.rs:4207-4221
composition over the label resolution — errored slots never load, and
the errored-catalog case where the model slot was never requested still
resolves "not loading" via the label) plus the `shouldReload` table
(Idle/Loading/Ready/Error × force, the rows-kept error variant, and the
settled-cadence no-op sweep). Extended `tests/picker-catalog.test.ts`
(+3): offline re-arm on a non-`connected` status change; a timeout error
NOT re-armed by one (heals on `connected`); single-flight open-force +
idle-cadence (exactly one `ListHarnesses`). The FakeClient gained an
`emitStatus(state)` seam (`emitConnected` delegates). Base suites
untouched and green (1205 → 1216).

**Deviations / judgment calls.**

1. `labelLoading` keeps ticket 10's landed "raw id reads as unresolved"
   shape (`modelLabel === draft.model && rememberedLabel === null`) rather
   than porting the desktop's `model_label.is_empty()` literally — the
   research flags only the error-blind arms, and the new
   `harnesses.error === null` arm restores the error-path behavior the
   acceptance demands. (Literal port would show raw ids mid-load on a
   fresh refresh; landed behavior skeletons them — kept.)
2. The focus re-kick resets to Idle before loading (the Error → Idle →
   Loading walk) so it routes through `shouldReload`'s Idle row rather
   than force-revalidating; a Ready catalog is never re-kicked by focus.
3. The `emitStatus("connecting")` heal test resolves via a successful
   fake call (the fake cannot model an still-offline socket): what it
   pins is the re-arm discipline, not the transport failure.
4. The §6 screenshot pair (states a–e, desktop halves) needs a live
   capture session — left to the merger, as with tickets 08/10/12/13/37.
   The chip's error-state label and the ErrorRow path are covered by the
   unit gates above.

**Verification.**

- `pnpm -r build` (from `web/`) green — proto, engine-client, app
  (tsc --noEmit + vite build, 4.25s).
- `web/packages/app` `pnpm test` green: 74 files / 1216 tests, including
  `catalog-loading` (8, new), `picker-catalog` (15, +3), `model-rows`,
  `traits-summary`, `composer-flip`, `composer-send` untouched.
