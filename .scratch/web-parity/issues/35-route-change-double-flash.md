# 35 — Route-change double flash

**What to build:** Navigating from the new chat page to an existing chat — and
back — hands the background over in **one** continuous dissolve. Today the
background hard-vanishes at the transition start, reappears a few frames later,
and replays its 120 ms readiness fade *while the dock's dissolve channel is
already fading the hero out* — two opposing fades on one element, i.e. two
flashes. After this ticket the hero layer never unmounts mid-transition, the
artwork resolves once and survives route changes, the readiness clock never
restarts for the same image, and the blob URL identity is stable across mounts.
User symptom: "the background flashing twice when I go from new chat page to an
existing chat page".

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

**Research:** `../research-2026-09-19/new-thread-background-and-transitions.md`
S5(a)–(d), consolidated gap rows G16–G19, "Pure logic to port" item 5,
"Desktop-only items NOT to port".

**Desktop reference (for lookups only):**
`crates/ui/src/shell.rs` (5865–5895 — the same-render tick + hero layer mount;
5846–5859 — artwork prewarm on both routes; 1032, 5860–5864 —
`Shell::new_thread_artwork_ready`; 880, 5893 — the one opacity product;
5884-5886 — rAF while fading), `crates/ui/src/new_thread_background_effects.rs::Readiness`
(11–33; the test at 319–336), `crates/ui/src/new_thread_background_image.rs`
(the decode contract).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/state/appearance.ts` | edit | `useNewThreadBackground` → a shell-scoped artwork source (module store keyed on the setting; the url/id/effect survive route changes; the readiness clock lives beside them) |
| `web/packages/app/src/lib/background-blob-store.ts` | edit | a module-level singleton accessor so every resolve shares one `cachedUrl` (today each `idbBackgroundBlobStore()` call is a fresh closure, `:61-94`) |
| `web/packages/app/src/lib/new-thread-background.ts` | edit | `resolveNewThreadBackground`/`resolveInstalledBackground` consume the singleton store (the per-call default at `:256-257`/`:269`); the resolution SEMANTICS do not change |
| `web/packages/app/src/routes/chat-page.tsx` | edit | `heroVisible` (:565) — the first navigation render reads the freshly ticked dock frame (the mutable `dockRef.current.frame`), not the one-render-stale React state |
| `web/packages/app/src/routes/index-page.tsx` | edit | `NewThreadCanvas` consumes the hoisted artwork source (its `useNewThreadBackground` call no longer resets per mount) |
| `web/packages/app/src/components/new-thread-background.tsx` | edit | the readiness wrapper feeds from the store-owned clock (`data-ready`); the null-paint early return (:162-164) only fires when nothing is resolved at all |
| `web/packages/app/tests/new-thread-background-store.test.ts` | new | the store-level readiness/identity tests + the host-level "hero survives the route change" case |

---

## 1. Context a fresh session needs

- Both routes render ONE `ConversationPage` (ticket 15 deviation 1:
  `router.tsx:27-28` uses the same component reference for `/` and
  `/chat/$chatId`, keeping one fiber — the composer is never remounted). The
  hero is NOT part of that guarantee today: it is conditionally mounted at
  `routes/chat-page.tsx:722-728` and its artwork state lives inside the
  unmounted subtree.
- The mount decision: `heroVisible = (!hasSelection || dockFrame.active)`
  (`routes/chat-page.tsx:565`) where `dockFrame` is React state
  (`useState` at `:446`). The dock's tick that flips `active` runs in the
  layout effect **after** the render (`:491-505`) — so the navigation render
  sees the stale settled frame.
- The artwork is resolved per hero mount: `NewThreadCanvas`
  (`routes/index-page.tsx:19-43`) calls `useNewThreadBackground`
  (`state/appearance.ts:86-105`), which starts at `url = null` (`:90`) and
  asynchronously resolves via `resolveNewThreadBackground` (IndexedDB read +
  object URL, `lib/new-thread-background.ts:253-290`,
  `lib/background-blob-store.ts:78-94`).
- The readiness fade is a `useLayoutEffect`-driven state inside
  `NewThreadBackground` (`components/new-thread-background.tsx:85-104`,
  CSS `app.css:2510-2523`) keyed on the artwork id — a remount resets it, so
  the 120 ms fade replays.
- The defect mechanism, frame by frame (verbatim from the research):
  1. **Frame 1 (the navigation render):** `heroVisible = (!hasSelection ||
     dockFrame.active)` = `(false || false)` = **false** → `<NewThreadCanvas>`
     unmounts (`chat-page.tsx:565, 722-728`). The tick that flips `active` runs
     in the layout effect *after* the render (`chat-page.tsx:491-505`); its
     `setDockFrameState` re-renders and **remounts** the hero.
  2. **The remount destroys the artwork state.** `NewThreadCanvas` mounts fresh
     → `useNewThreadBackground` starts with `url = null`
     (`state/appearance.ts:90`) → `NewThreadBackground` renders **null** while
     resolving (`components/new-thread-background.tsx:162-164`) → the async
     `resolveNewThreadBackground` (IndexedDB read + object URL,
     `lib/new-thread-background.ts:253-290`,
     `lib/background-blob-store.ts:78-94`) resolves a few painted frames
     later.
  3. So the painted sequence is: **flash 1** — the background hard-vanishes at
     the transition start (hero absent); **flash 2** — it reappears and the
     readiness fade ramps 0→1 over 120 ms (`new-thread-background.tsx:85-104`;
     CSS `app.css:2510-2523`) *while the dock's `dissolve` channel is already
     fading the hero out* (docking window 0.06→0.88) — two opposing fades on
     one element; then the intended dissolve finishes.
  4. **Amplifier — a new object URL per resolution:**
     `resolveNewThreadBackground` default-constructs a **new**
     `idbBackgroundBlobStore()` per call (`lib/new-thread-background.ts:256-257`),
     and each store instance caches its own `cachedUrl`
     (`background-blob-store.ts:61-94`) → every mount mints a *different*
     blob: URL → `artwork.id` changes → the keyed readiness wrapper remounts
     and the 120 ms fade restarts even for the same image (the desktop's
     `Readiness` restarts **only when the image id changes**, effects.rs:22-24).
     Also an unrevoked-object-URL leak.
- Ruled out for this navigation (do not chase): `page-fade` keyed on phase
  (`routes/root-layout.tsx:80`) — `phase` stays `"ready"` across `/` ↔
  `/chat/$id`; the keyed remount fires only in the pairing flow (ticket 32,
  `root-layout.tsx:53`, `pair-page.tsx:31-48`). `transcriptGeometryReady`
  (`chat-page.tsx:466, 582`) is an intended guard affecting content, not the
  background. No CSS animation restarts except the readiness transition.
- The desktop never drops the layer and never re-resolves — see §2 for the
  verbatim rules. The `dissolve` channel (docking window 0.06→0.88 on the
  0.420 s clock) is ticket 15's ported dock choreography and ticket 36's
  matrix — this ticket must not touch the channel values, only the layer's
  survival beneath them.
- Vocabulary (`CONTEXT.md`): **chat**, **space**, **engine**, **harness**.

---

## 2. Spec

### 2.1 Shell-scoped artwork + Readiness (the hoist)

The desktop's background-continuity rules, verbatim:

| rule | value | source |
|---|---|---|
| layer mount | `(!has_selection \|\| dock_frame.active)` — decided with the frame ticked in the SAME render (`tick` at 5865-5868 precedes the layer at 5883) | shell.rs:5865-5895 |
| artwork prewarm | decode + effect run on BOTH routes, "not contingent on a hero measurement or a navigation gesture" | shell.rs:5846-5859; effects.rs:292-302 |
| readiness owner | `Shell::new_thread_artwork_ready` — lives on the shell, survives every route change; restarts only on a NEW image id | shell.rs:1032, 5860-5864; effects.rs:11-33 |
| hero fade | element opacity `(1 − dissolve) × artwork_readiness × bg_opacity` — one number per frame | shell.rs:880, 5893 |
| rAF while fading | `if artwork.is_some() && artwork_opacity < 1.0 { window.request_animation_frame() }` | shell.rs:5884-5886 |
| readiness test | `cold_artwork_fades_in_once_and_warm_navigation_does_not_restart_it` (0.5 at 60 ms; same id → 1; new id → 0; reduced → 1) | effects.rs:319-336 |

**What to build:**

- **Visibility, same-render tick.** The first navigation render's
  `heroVisible` must come from the freshly ticked dock frame, not the stale
  React state. Two viable shapes (implementer's choice, note it in Comments):
  compute the first render's visibility from the mutable dock
  (`dockRef.current.frame`) — i.e. read `dockRef.current.frame.active` at
  render time for the mount decision while `dockFrame` state keeps driving
  the visuals — or tick the dock before the router-subscription render path
  lands. Either way the hero **never unmounts mid-transition**: no painted
  frame exists with the layer absent while `artwork` is resolved. (The
  per-commit layout effect at `chat-page.tsx:491-505` already ticks on route
  changes — the fix is making the render consume that tick's result, not the
  previous render's.)
- **Artwork at shell scope.** Hoist the resolution out of the hero component
  into a module store (or `ConversationPage`-scope source keyed on the
  setting) so `url`/`id`/`effect` survive route changes:
  `useNewThreadBackground` becomes a thin subscription to it. Prewarm on both
  routes — the resolve (and, once ticket 33 lands, the effect raster's
  `prepare`) runs from the store as soon as the setting exists, never
  contingent on hero geometry or a navigation gesture.
- **Readiness survives navigation.** The `Readiness` clock
  (`lib/new-thread-background.ts:200-219`, already ported and exact) moves to
  the store's scope; the component's `data-ready` is fed from it. The same id
  never re-fades; a NEW id starts the 120 ms fade from 0; reduced motion
  snaps to 1. The keyed-wrapper CSS stays.
- **Blob URL identity.** `idbBackgroundBlobStore` becomes a module-level
  singleton (one `cachedUrl` per blob revision; revoked only on
  put/delete — `background-blob-store.ts:61-104` already implements the
  revocation, it just needs ONE instance). `resolveNewThreadBackground`'s
  default parameter (`lib/new-thread-background.ts:256-257`) and
  `resolveInstalledBackground`'s (`:269`) bind to the singleton. Result: the
  resolved URL (hence `artwork.id`) is stable across mounts; no leak.
- **One fade at a time.** With the above three, the readiness is already 1
  for warm artwork when the dissolve channel starts fading the hero out —
  the element opacity is the desktop's single product
  `(1 − dissolve) × readiness × bg_opacity`, no competing transitions.

### 2.2 States

| state | condition | what changes |
| --- | --- | --- |
| nothing installed / unresolved | store's `url === null` | hero renders nothing (the only null-paint case) — same as a cold boot |
| cold artwork (new id) | the resolved id differs from the last | readiness 0 → 120 ms smoothstep fade → 1 (0.5 at 60 ms) |
| warm navigation | same resolved id across `/` ↔ `/chat/$id` | readiness stays 1; only `dissolve` moves the opacity |
| background replaced | a new setting resolves to a new id | old fades per dissolve; new id starts its own readiness |
| reduced motion | `prefers-reduced-motion` | readiness snaps to 1 (already ported) |

### 2.3 Data

Reads: `newThreadComposerBackground`, `newThreadBackgroundEffect`
(`state/ui-settings.ts`), the IndexedDB blob (`lib/background-blob-store.ts`),
the dock frame. Writes: none (the store caches the resolved URL and readiness;
the settings writes for install/remove stay where they are — ticket 28's UI,
ticket 39's row fixes, ticket 48's resolution semantics).

---

## 3. Pure logic to port

From the research's "Pure logic to port" item 5:

- **Shell-scoped artwork + Readiness**: mirror
  `cold_artwork_fades_in_once_and_warm_navigation_does_not_restart_it`
  (effects.rs:320) at the store level, plus a host test that the hero layer
  survives `/` → `/chat/$id` without unmounting (no painted frame with
  artwork === null).
- The `Readiness` class itself is already ported (`lib/new-thread-background.ts:200-219`)
  — the work is its owner and inputs, not its math.

---

## 4. Gaps this ticket closes

S5(d), verbatim:

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| hero layer never drops across a route change | BROKEN | layer decided with the same-render tick (shell.rs:5865-5895) | `heroVisible` from stale state → 1-render unmount→remount (chat-page.tsx:446, 565, 722-728) | compute the first navigation render's visibility from the mutable dock (`dockRef.current.frame`) or tick before render (router-subscription render path), so the hero never unmounts mid-transition |
| artwork resolved at shell scope | BROKEN | prewarmed on both routes (shell.rs:5846-5859) | resolved per hero mount, async, paints null (appearance.ts:86-105; new-thread-background.tsx:162-164) | hoist `useNewThreadBackground` to `ConversationPage`/app scope (or a module store keyed on the setting) so the url survives route changes |
| readiness survives navigation | BROKEN | shell-owned Readiness; same id never re-fades (effects.rs:11-33; test 319-336) | remount resets `ready` → 120 ms fade replays (new-thread-background.tsx:85-104) | keep the Readiness clock outside the unmounted subtree (module/store), feed `data-ready` from it |
| blob URL identity | BROKEN | image id stable per artwork | new store + new object URL per resolve → id changes every mount (new-thread-background.ts:256-257; background-blob-store.ts:61-94) | module-level singleton store; keep one URL per blob revision |
| double fade (readiness × dissolve) | BROKEN | one opacity product (shell.rs:880, 5893) | readiness fade-in fights the dissolve fade-out | resolved by the three rows above |

---

## 5. Do not

- **Do not fix the pairing remount here** — ticket **32** owns the
  `page-fade`-keyed remount (`root-layout.tsx:53, 80`; the pairing flow's
  client recreation). It is a DIFFERENT remount; S5 explicitly ruled it out
  for this navigation.
- **Do not remove `transcriptGeometryReady`** (`chat-page.tsx:466, 582`) — an
  intended guard that hides the transcript one commit; it affects content,
  not the background.
- **Do not re-litigate ticket 15's deviation 1** (one `ConversationPage` on
  both routes, `router.tsx:27-28`) — build on it; the hoist rides the same
  fiber.
- **Do not change the dock choreography or the dissolve channel values**
  (docking window 0.06→0.88, undock 0.08→0.85) — ticket **36** owns the
  transition matrix; ticket 15's ported clock stays.
- **Do not change the hero's paint** — ticket **33** owns the mask ramp,
  frosted opacity branch, and effect rasters.
- **Do not change the resolution semantics** — ticket **48** owns what the
  active background resolves to (installed when it decodes, else the bundled
  default). This ticket changes WHERE the resolution lives and how its result
  is cached, not what it returns. Ticket **42** owns the settings row's
  `fileImage` glyph fixes; the Appearance row itself is not this ticket's.
- **Do not add animation**: no new fades, no crossfade layers — the fix is
  removing the second fade, not adding a third thing to coordinate. Prewarm
  is decode + raster only.
- **Desktop-only, not to port**: the 4-entry FIFO artwork cache and its
  eviction; the BGRA RenderImage channel order (canvas ImageData is RGBA);
  `ROBOCO_MOTION_SCALE` multipliers.

---

## 6. Acceptance

- [ ] Host test: across a `/` → `/chat/$id` navigation (and the reverse) the
      hero layer never unmounts and no painted frame exists with
      `artwork === null` while a background is resolvable — mounted
      `ConversationPage` driven through both route states, asserting the
      `NewThreadBackground` element's identity/continuity.
- [ ] The resolved URL is identical across mounts (one `id` per blob
      revision); replacing the background retires the previous object URL
      (`URL.revokeObjectURL` observed) — no unrevoked-URL growth over
      repeated navigations.
- [ ] Readiness at the store level (port of
      `cold_artwork_fades_in_once_and_warm_navigation_does_not_restart_it`):
      cold id → 0.5 at 60 ms, 1 at ≥120 ms; same id after warm navigation →
      1 immediately; a NEW id → 0; reduced motion → 1.
- [ ] Exactly one fade: during the dock's dissolve window the readiness is
      already 1 (warm) — verified by asserting the element opacity equals the
      dissolve product alone, with no readiness transition running.
- [ ] The desktop's rAF-while-fading rule holds: the hero keeps requesting
      frames while `artwork != null && opacity < 1` (the existing dock pump
      covers the dissolve; the readiness ramp rides the store's own frame
      source).
- [ ] Screenshot pair, desktop vs web, states: mid-transition frame (~200 ms
      into `/` → `/chat/$id` — a single continuous dissolve, no gap);
      established chat settled; `/` again after undock (hero back at full
      opacity with no re-fade).
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

### Implementer note (2026-09-19)

Landed on `wp2r2/35-route-change-double-flash`. Screenshot pairs waived per
the handoff (verification = build + vitest only).

**The same-render tick — shape chosen:** the render-phase tick. The route
change's tick now runs in `ConversationPage`'s render body (guarded on
`dockRef.current.frame.docked !== hasSelection`, idempotent under StrictMode
double-render) and publishes through a render-phase state update (the
sanctioned derive-state-during-render shape — React discards the first pass,
so no commit ever contains the hero unmounted). `heroVisible` reads the
freshly ticked MUTABLE frame via `heroLayerMounted(hasSelection, dockRef.current.frame)`
(new pure helper in `lib/composer-dock.ts`, shell.rs:5883 verbatim; the page
adds the web's phone-layer exclusion on top) while `dockFrame` state keeps
driving the visuals. The per-commit layout effect keeps only its first-pass
initialization tick; its route-change clause stays as a dead safety net.
One ordering nuance vs the old flow: the route-change tick now runs BEFORE
the same commit's `observePane` (it used to run after), so a
`#panelDeparture`/`#panelReturn` flag would read the pane state as of the
previous commit. That case requires the panel handoff to arm on the very
commit the docked flag flips with a width change in the same
`observePane` sample — which the measured-async `columnWidth` makes
unreachable today (research S6: the handoff never arms; ticket 36 owns the
arming input). No channel values or choreography were touched.

**The store:** `NewThreadArtworkStore` in `state/appearance.ts` — module
scope (`newThreadArtworkStore`), keyed on the settings triple
(`(path, name)` value-compared + the effect). It owns the resolve
(`resolveNewThreadBackground` → the singleton blob store), the 120 ms
`Readiness` clock, the prewarm, and the rAF-while-fading loop
(shell.rs:5884-5886: frames are requested while `url !== null && value < 1`;
the loop stops at 1). Snapshot = `{url, id, effect, ready}` (identity-stable
for `useSyncExternalStore`); `readinessValue()` exposes the clock's value for
tests and the one-fade assertion. The clock restarts only on a new id; an
effect flip publishes + re-prewarms but never re-fades (the readiness is
id-keyed, like the desktop's). Prewarm = decode + raster only, per §5:
`prepareNewThreadBackgroundEffects(effect, appearance, url)` (ticket 33's
hero-geometry-free prepare — the call site ticket 34's merger note assigned
to this ticket) plus `new Image().decode()` for the raw artwork (the
`none` effect's hero paints the image itself; a warm decode keeps a
remounting hero from painting empty canvases — the undock direction's
residual 1-frame canvas gap also hides under `(1 − dissolve) ≈ 0`).

**The blob singleton:** `idbBackgroundBlobStore()` now returns ONE
process-wide instance (memory stand-in when IndexedDB is absent), so every
resolve shares one `cachedUrl` — the artwork id is stable per blob revision
and a put/delete retires the URL exactly once. The resolvers' default
parameters already called the accessor, so they now bind the singleton with
no signature change; `settings-appearance.tsx`'s module-level
`backgroundBlobs` const silently became the same instance (its
install/remove now retire the URL the hero resolved).

**Deliberate details / deviations:**

- `data-ready` is fed from the store clock as `readiness > 0` (the first
  frame after a cold id's arrival); the keyed-wrapper CSS stays, so the
  visible 120 ms ramp is still the CSS transition (the landed
  approximation), starting ~1 frame after arrival instead of the old 2 —
  the desktop's smoothstep VALUES are asserted at the store level instead.
- A background replaced with the SAME path/name keeps the old URL: the
  settings write is a no-op (`UiSettingsStore.update` short-circuits on an
  identical serialization), and the desktop's path-keyed artwork cache
  behaves the same. A different name re-resolves → new URL → new id → its
  own readiness (spec §2.2's "background replaced" row).
- The "host test" is the page's pure render body driven through both route
  states (the vitest environment is node — no React mounting exists in this
  repo's suite): a real `DockState` (+ per-commit `prepaint`, which warms
  the dock's clock exactly like the layout effect does) stepped through
  `/` → `/chat/$id` → `/` with a real store instance, asserting the mount
  rule, the artwork-id continuity, readiness 1 through the dissolve window
  (element opacity = the dissolve product alone), and the stale-frame
  contrast that documents the old defect.
- Ticket 48 is NOT in this base (`resolveActiveNewThreadBackground` absent);
  the store consumes `resolveNewThreadBackground` as-is — 48 can re-point
  the store's injected default resolver without touching its shape.

**Acceptance status:** host test ✓ (route-change survival + identity);
URL identity/revocation ✓ (singleton + revokeObjectURL spy, no re-resolve
across remounts); store-level readiness port ✓ (0.5@60ms, 1@≥120, warm → 1,
new id → 0, reduced → 1, loop stops at 1); one-fade ✓ (readiness 1 during
the dissolve window); rAF-while-fading ✓ (the store's own frame source);
build + 1244 tests green (1235 base + 9 new, no regressions); no new
literal hex/px.
