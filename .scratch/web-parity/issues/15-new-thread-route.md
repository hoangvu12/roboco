# 15 — New-thread route

**What to build:** The blank-canvas route the web does not have. Starting a new
chat centres the composer vertically on a full-width **background hero** —
artwork cover-fitted into the top 72% of the viewport, with a feathered
rounded-rect **hole cut around the composer** so the pill reads as a window, not
a sticker. A floating 20px row of device and project chips sits above the pill;
the checkout and ref chips take over the footer slot below it. Sending the first
message does not remount the composer: **one** composer entity glides from the
hero to the bottom of the chat column over a 420 ms coordinated timeline, with
the transcript, selectors, footer and hero dissolve each on their own staged
window. After this ticket, "New chat" looks like the desktop's new-chat canvas
and the handoff into a live chat is one continuous motion.

**Blocked by:** 10 (Pickers and menus), 13 (Composer core).

**Status:** ready-for-agent

**Research:** `../../web-client/research/04-composer.md` §3.5, §3.6, §3.7 (the
`new_chat` branch), §3.20 (Layer A), §3.23, §4 (`route_chrome_opacities`,
`FlipMorph::new_thread_transition`, `dock_height`, `stage`, `Glide`), §7.1;
`../../web-client/research/01-shell-chrome.md` §3.16 (`render_main`'s bottom
chrome stack), §3.16.1 (new-thread hero), §3.31
(`new_thread_background_mask.rs`), §3.32 (`new_thread_background_effects.rs`),
§3.30 (`new_thread_background_image.rs`), §4.15;
`../../web-client/research/12-settings-shell-appearance.md` §3.0
(`newThreadComposerBackground`, `newThreadBackgroundEffect`), §3.0.3
(`DEFAULT_NEW_THREAD_BACKGROUND_FILE`);
`../../web-client/research/05-pickers-popovers.md` §3.14 (the chips the rows
mount).

**Desktop reference (for lookups only):**
`crates/ui/src/composer.rs::Composer::render` (7898–7929, 7947–7982),
`::route_chrome_opacities` (88), `::dock_height` (7490),
`::set_dock_frame` (4140), `::dock_clearance_correction` (7583),
`crates/ui/src/composer_dock.rs`, `crates/ui/src/composer_dock/panel_handoff.rs`,
`crates/ui/src/shell.rs` (697–699, 844–914, 5806–6154, 837),
`crates/ui/src/new_thread_background_mask.rs`,
`crates/ui/src/new_thread_background_effects.rs`,
`crates/ui/src/pickers.rs::render_new_thread_target_selectors` (2426),
`::render_new_thread_git_selectors`.

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/new-thread-background.tsx` | new | `NewThreadBackground` — the two stacked hero layers, the bottom fade, the cutout mask |
| `web/packages/app/src/components/composer/new-thread-selectors.tsx` | new | `NewThreadTargetSelectors` (device + project chips), `NewThreadGitSelectors` (checkout + ref chips) |
| `web/packages/app/src/lib/composer-dock.ts` | new | `Glide`, `stage`, `DockVisuals`, `DockState` (`tick`, `layoutWidth`, `transcriptWidth`), `PanelHandoff`, `dockHeight`, `dockClearanceCorrection`, `routeChromeOpacities`, `bottomStackMeasurementMatches` |
| `web/packages/app/src/lib/new-thread-background.ts` | new | `newThreadBackgroundOpacity`, `newThreadBackgroundHeight`, `heroMaskGeometry` (feather/clearance/cleared-rect), `readinessOpacity` |
| `web/packages/app/src/components/composer.tsx` | edit | the `new_chat` branch (always expanded), the floating selector row slot, Layer A of the footer slot, publishing the pill's `SurfaceBounds` |
| `web/packages/app/src/components/composer-footer.tsx` | edit | Layer A / Layer B crossfade inside the 24px slot |
| `web/packages/app/src/routes/index-page.tsx` | edit | the blank-canvas route: hero + vertically centred composer |
| `web/packages/app/src/routes/chat-page.tsx` | edit | the bottom chrome stack measurement + `dockClearanceCorrection` |
| `web/packages/app/src/components/app-shell.tsx` | edit | mount **one** composer across both routes so it is never remounted |
| `web/packages/app/src/state/appearance.ts` | edit | read `newThreadComposerBackground` + `newThreadBackgroundEffect` from the ticket-03 settings store |
| `web/packages/app/src/styles/app.css` | edit | `.new-thread-hero`, `.new-thread-hero-art`, `.new-thread-hero-art--reveal`, `.new-thread-hero-art--cutout`, `.dock-target-selectors`, `.new-thread-git-selectors`, `.persistent-composer` |
| `web/packages/app/tests/composer-dock.test.ts` | new | the dock/handoff/route-chrome unit tests |
| `web/packages/app/tests/new-thread-background.test.ts` | new | the hero height/opacity/mask unit tests |

---

## 1. Context a fresh session needs

- The composer is **a single entity that lives across both routes** — the
  new-thread canvas and an established chat — and it **morphs** between them
  rather than remounting. The caret, selection, hitboxes and text all travel
  with the pixels. This is the whole point of the ticket: mounting two
  composers and crossfading them is the wrong shape.
- The desktop achieves that by re-anchoring the child in **prepaint**: the
  composer stays in the same layout slot, and only its prepaint origin moves.
  On the web the equivalent is one React subtree whose wrapper carries a
  `transform: translate(x, y)` driven by a spring, with `width` animated
  separately.
- The hero is **shell chrome, not composer chrome**, but the composer publishes
  its measured surface bounds for the hero's cutout mask, and the dock frame's
  `dissolve` channel drives the hero's opacity. That coupling is why they are
  one ticket.
- On the web today none of this exists: `routes/index-page.tsx` has no hero,
  the composer is mounted per-route, and there is no dock state at all.
- Screenshot warning: `.scratch/web-client/parity/desktop-02-newchat.png` is a
  **byte-identical duplicate** of `desktop-01.png` — it shows the
  established-chat route, not the new-thread canvas. Everything below is
  transcribed from source only. **The first acceptance step of this ticket is
  taking a fresh desktop capture of the blank canvas** with
  `.scratch/web-client/parity/shot.ps1`, and confirming the hero artwork, its
  opacity, the mask cutout around the pill, and the composer's actual resting
  vertical position before implementing.
- Vocabulary: **chat**, **harness**, **engine**, **space**.

---

## 2. Spec

### 2.1 Route chrome crossfade (`route_chrome_opacities`, composer.rs:88)

`new_thread_chrome` is a 0→1 scalar for "how much of the new-thread route is
showing". Both opacity ramps derive from it:

```
new_thread = clamp((clamp(c,0,1) - 0.5) * 2, 0, 1)
session    = clamp(((1 - clamp(c,0,1)) - 0.5) * 2, 0, 1)
```

Assertions: `1.0 → (1,0)`, `0.5 → (0,0)`, `0.0 → (0,1)`, and across 21 samples
**at least one of the two is always exactly 0** — the two ramps never overlap.
That is what avoids duplicate picker ids and duplicate popovers while the
surrounding geometry still collapses continuously. Desktop test:
`route_chrome_crossfade_never_duplicates_picker_controls` (9340).

**Consequence for the web:** never render both the new-thread selector row and
the session footer at once. Gate each on `opacity > 0`, and unmount (not just
hide) the hidden one so its popover triggers cannot be found twice.

---

### 2.2 New-thread selector row (`Composer::render`, 7898–7929)

Two variants.

**A. `dock_frame.is_some()` — the normal case** (the shell always installs a
dock frame) (7898–7913): the container becomes `relative` and gets an
**absolutely positioned** row so the selectors never change the composer's
height.

| property | value |
|---|---|
| id | `"dock-target-selectors"` |
| position | absolute; `top` **−28**; `left`/`right` `Theme::SPACE_LG + 10` = **26** |
| height | `NEW_THREAD_SELECTOR_ROW_HEIGHT` (**20**) |
| layout | flex, `items_start`, `justify_end` |
| opacity | `new_thread_chrome_opacity` |

**B. No dock frame, `new_thread_chrome > 0`** (7914–7926): an **in-flow** row of
height `20 × new_thread_chrome`, `margin-bottom −Theme::SPACE_SM × (1 −
new_thread_chrome)` (so the column gap collapses continuously), `px` 10, flex
`items_start justify_end`, opacity `new_thread_chrome_opacity`.

**Contents** — `pickers.rs::render_new_thread_target_selectors` (2426): a
`flex_none` row, `gap` **4**, with two footer chips, in order:
1. **device chip** — `monitor` icon, label = device name or **`"This device"`**;
   text turns `theme.warning.opacity(0.8)` when the device is offline. Opens the
   Device popover (**224** wide).
2. **project chip** — `folder` icon, label = space display name or
   **`"No project"`**. Opens the Space popover (**280** wide), right-aligned.

Chip style = `Pickers::footer_chip`-shaped trigger, same metrics as
`footer_label` (ticket 13 §2.14): height 20, max-width 160, min-width 0, flex
row `items_center` gap 6, padding-x 8, `ui_rems(12)` MEDIUM,
`theme.text_muted.opacity(0.6)`, 12px icon in the same colour, truncating label.
The chips and their popovers are **ticket 10**; this ticket owns the row that
places them.

Desktop test: `new_thread_selectors_restore_the_compact_floating_row` (9334).

---

### 2.3 New-thread git selectors — footer slot, Layer A

The session footer slot (ticket 13 §2.14) is a 24px `relative` box with two
absolutely-inset layers that never overlap.

**Layer A — new-thread git selectors** (when `new_thread_chrome_opacity > 0`):
absolute `inset_0`, `px` **10**, flex `items_center`,
`opacity(new_thread_chrome_opacity)`, children =
`pickers.render_new_thread_git_selectors(cx)` — a `w_full min_w_0` flex row,
`gap` **4**, with a **checkout-kind chip** and a **branch chip**; renders
**nothing** when the space has no git.

The new-session draft branch of `render_footer` has the same shape as the
established-chat footer but the two labels are **clickable chips** opening the
Checkout (**224** wide) and Branch (**320** wide) popovers; device and project
live in the floating row above the pill instead. Popovers: ticket 10.

---

### 2.4 New-thread pill behaviour

- `expanded = expanded_mode || new_chat` (composer.rs:7488) — **the new-thread
  canvas always renders expanded** regardless of the flip state, and a mode flip
  there is **never morphed** (7284–7300).
- `surface_radius` = `COMPOSER_RADIUS − 4 × dock_amount` = **26** at the hero,
  **22** when docked (7603).
- The pill publishes its measured bounds into `surface_bounds` via a canvas
  absolutely `inset_0` inside `#composer-surface` (composer.rs §3.6 child 2).
  **Prepaint completes before any paint**, so the hero never reads last frame's
  geometry. Web: measure the pill's `getBoundingClientRect()` in a layout effect
  (or a `ResizeObserver`) and write it into a shared ref the hero reads in the
  same commit — never a state round-trip that lands a frame late.

---

### 2.5 New-thread route transition (`FlipMorph::new_thread_transition`)

The coordinated route morph uses `motion::NEW_THREAD_TRANSITION` = **420 ms**,
`EASE_RESORT` = `EASE_OUT_QUINT` = `cubic-bezier(0.22, 1, 0.36, 1)`.

```
raw(now)      = clamp((now - startMs) / 420, 0, 1)
progress(now) = EASE_RESORT(raw(now))
height(target, now) = lerp(from, target, progress(now))
```

Assertions: the route morph is **0 at t=0, strictly between at 250 ms, and
exactly at target at 420 ms in both directions**. Desktop test:
`new_thread_route_changes_use_the_coordinated_timeline` (9321).

`dock_height(amount)` (7490–7500) — the interpolated pill height across the
route:

```
hero    = composer_total_height(content_height)
session = session_expanded
            ? clamp(content_height + 20, 76 - 16, 260) + 46 + 2
            : COMPACT_TOTAL_HEIGHT (49)
dock_height(amount) = lerp(hero, session, amount)
```

The `76 − 16 = 60` floor on the session side is what lets a short draft in an
established chat sit skinnier than the hero's 76px textarea floor.

`dock_clearance_correction` = `dock_height(frame.docked ? 1 : 0) + strips −
pill_height` (composer.rs:7583) — the shell reserves the **destination**
footprint, never the animated height, so the transcript's clearance does not
pump during the route change.

---

### 2.6 Composer dock (`composer_dock.rs`, `panel_handoff.rs`)

The shell mounts **one** composer entity and re-anchors it in prepaint.

**`Glide` — critically damped spring** (composer_dock.rs:23)

```
advance(target, dt, duration):
  omega        = 12.0 / duration            // DOCK_GLIDE_TIME_CONSTANTS
  displacement = value - target
  c            = velocity + omega * displacement
  decay        = exp(-omega * dt)
  value        = target + (displacement + c*dt) * decay
  velocity     = (velocity - omega*c*dt) * decay
  if !active(): snap to target with zero velocity

active(): |value - target| > 0.0005  ||  |velocity| > 0.005
duration: docked ? 0.420s : 0.470s   (× speed_scale)
```

Constants (`crates/proto/src/motion.rs:476–484`, already shipped in
`roboco_proto::motion` precisely so the web glides identically):
`DOCK_GLIDE_TIME_CONSTANTS` **12.0**, `DOCK_SECONDS` **0.420**,
`UNDOCK_SECONDS` **0.470**, `SETTLE_POSITION` **0.0005**, `SETTLE_VELOCITY`
**0.005**.

No oscillation; both position AND velocity survive a retarget, so a mid-flight
reversal is continuous. Frame-rate independent and monotone for a normal dock.

**`stage(value, start, end)`** = smoothstep:
`t = clamp((value-start)/(end-start), 0, 1); t*t*(3-2*t)`.

**`DockFrame`** — `{ amount (0 = hero, 1 = established thread), docked, active,
visuals }`. `visuals` is four independently staged channels; each is blended
`lerp(from, to, stage(time, start, end))`:

| channel | docking (→ thread) window | undocking (→ hero) window |
|---|---|---|
| `transcript` | 0.20 → 0.65 | 0.00 → 0.25 |
| `selectors` | 0.55 → 0.78 | 0.50 → 0.95 |
| `footer` | 0.78 → 1.00 | 0.00 → 0.18 |
| `dissolve` | 0.06 → 0.88 | 0.08 → 0.85 |

Settled values: docked → `transcript 1, selectors 0, footer 1, dissolve 1`;
hero → the inverse. Invariant asserted by
`choreography_is_direction_specific_and_selectors_never_duplicate`:
**`selectors == 0 || footer == 0` at every point on both timelines.**

**`return_from_panel(time)`** — a shorter **0.320 s** clock used when returning
to the hero *while a panel handoff is running*: `transcript` and `footer` decay
over `stage(t, 0.0, 0.18)`, `selectors` rises over `stage(t, 0.26, 0.85)`,
`dissolve` decays over `stage(t, 0.26, 0.80)`.

**`PanelHandoff`** (panel_handoff.rs) — a fade-through whenever navigation
changes the conversation column's **horizontal frame** (e.g. the right pane
opening/closing across a route change). Duration **0.320 s**.

```
opacity(p) = from_opacity * (1 - ease(p, 0.00, 0.18)) + ease(p, 0.26, 1.00)
ease(v, s, e) = smoothstep(clamp((v-s)/(e-s), 0, 1))
```

So the column is fully invisible between p ≈ 0.18 and p ≈ 0.26 — **the geometry
switch happens at p ≥ 0.22, while nothing is visible.** Reversal preserves the
current opacity; disabling (`enabled = false`) resets everything to opacity 1.
**Ordinary resizing and same-column navigation never fade.**

**`DockedComposer::prepaint`** — the child stays in the same layout slot; only
its prepaint origin moves:
- target `x` = the slot's left; target `y` = `bounds.top()` when docked, else
  `(viewport_height − bounds.height) * 0.5 + 8` — **anchored by the TOP of the
  input surface, not its shrinking bottom**
- during a panel handoff at `progress >= 0.22`, both axes **snap** to the new
  position with a `travel` offset of **12** (docking) / **8** (undocking) px
  decaying over `stage(progress, 0.22, 1.0)`
- reduced motion or "not moving" → snap
- otherwise → `Glide::advance` on both axes
- request another animation frame while moving

**`DockState::transcript_width`** — during a panel-handoff *exit* from the
thread, the retained transcript pixels keep the **source** column width until
the handoff ends (letting them reflow into the hero's wider layout before fading
creates an exit flash).

**`DockState::layout_width`** — the composer's own width also glides, **except**
inside the invisible interval (`progress >= 0.22`) where it snaps.

**`DockState::tick`** — advances the glide(s) and the handoff clock by `dt` and
returns the frame; the caller schedules another frame while anything is active.

**Composer side — `set_dock_frame(frame)`** (composer.rs:4140): storing a frame
with `active == true` **kills both `flip_morph` and `height_morph`** (the shared
clock owns the height). A change in `frame.amount` sets `dock_height_changed`,
which also suppresses both morphs for that frame.

---

### 2.7 The bottom chrome stack (`shell.rs::render_main`, 6073–6125)

The conversation column's chat route is `#chat-dropzone`: `relative`, `flex_1`,
`min-width:0`, `height:100%`, flex column. Its children **in order**:

1. **New-thread hero** (`new_thread_background_layer`, 6023) — rendered when
   `!has_selection || dock_frame.active`. Deliberately **outside** the
   transcript edge-fade scope so it paints under the overlaid titlebar instead
   of going transparent across the titlebar band (6020–6022). See §2.8.
2. **Transcript underlay** (6024–6065) — `absolute; inset 0; bottom = term_h`,
   containing the edge-faded transcript with **asymmetric bands**:
   `inset_top = TITLEBAR_HEIGHT (38)`, `band_top = TRANSCRIPT_FADE_BAND (24)`,
   `band_bottom = max(bottom_stack − term_h − STATUS_STRIP_HEIGHT, 1)` — opaque
   from the composer **pill's** top, zero at the underlay's bottom edge.
   Always on (gating on measured scroll state left the top unfaded for one
   frame on chat switch). *(Ticket 18 owns the transcript itself; this ticket
   only supplies `bottom_stack`.)*
3. `<div flex_1 min_h_0 />` — a spacer with **no id and no listeners**, so
   pointer and wheel events over it fall through to the list below.
4. **Bottom chrome stack** (6073–6125): `flex-none; relative; flex column`.
   - A paint-time canvas measuring the stack's height into `bottom_stack`
     **plus `composer.dock_clearance_correction()`**, and recording
     `bottom_stack_has_composer`; requests another frame on change (6084–6101).
   - `render_status_strip` — 24px reserved (ticket 06).
   - **The docked composer** — rendered when `has_spaces || no_project ||
     has_appshots`. `#persistent-composer`: `relative; width composer_width;
     opacity = composer_dock.opacity(); margin-x auto`, containing the composer
     and, when a chat is selected, the jump-to-bottom pill (ticket 20).
   - the terminal container (ticket 26).
5. **Drop overlay** — ticket 17.

**Outlet selection** (5897–5978) — the half this ticket needs:

| condition | outlet |
|---|---|
| `has_selection \|\| departing_transcript` | the transcript, with `position:relative; top: 8·(1 − dock_frame.transcript())`, `opacity = dock_frame.transcript()` (or 0 while `!transcript_geometry_ready`); a **departing** transcript is also fixed at `transcript_width` and covered by an `absolute inset-0` occluding veil so it is visual history, not an interaction surface |
| `!has_spaces && !no_project` | the onboarding card (ticket 06) |
| otherwise | empty |

`transcript_geometry_ready` is
`bottom_stack_measurement_matches(measured_has_composer, expected_has_composer)`
= `measured == expected` (shell.rs:837). Assertions:
`(false,false)` true, `(true,true)` true, `(false,true)` false,
`(true,false)` false.

---

### 2.8 The background hero

`shell.rs:697–699, 844–914`.

| constant | value |
|---|---|
| `NEW_THREAD_BACKGROUND_FROSTED_OPACITY` | **0.84** |
| `NEW_THREAD_BACKGROUND_VIEWPORT_RATIO` | **0.72** |
| `NEW_THREAD_BACKGROUND_MAX_HEIGHT` | **760** |
| `CUTOUT_REVEAL_OPACITY` | **0.5** (`new_thread_background_mask.rs:8`) |

```
new_thread_background_opacity(is_frost) = is_frost ? 0.84 : 1.0
new_thread_background_height(vh)        = min(max(vh,0) * 0.72, 760)
```

Asserted (shell.rs:8265–8281): `400 → 288`, `600 → 432`, `1000 → 720`,
`1200 → 760`.

**Element** (873–913): `absolute; top 0; left 0; width hero_width; height
hero_height; overflow hidden; opacity (1 − dissolve) · opacity`.
`hero_width = max(viewport_width − sidebar_now(), 0)` — the hero uses the **full
conversation canvas even while the right pane clips it**; navigation must never
rescale the artwork (870–872). Two stacked layers are painted, one masked as a
cutout and one not, at opacities `CUTOUT_REVEAL_OPACITY` (0.5) and `1.0`.

**`SurfaceBounds`** (`new_thread_background_mask.rs:10`) — the shared cell the
composer writes its measured bounds into during prepaint, read by the background
during paint.

**Two passes**, both painting the same cover-fit image:

| field | `cutout = true` (main, opacity 1) | `cutout = false` (reveal, opacity 0.5) |
|---|---|---|
| mask `bounds` | `cleared` (below) | an exclusion rect parked **entirely below the image** — `origin (hero.left, hero.bottom + 1px)`, same size — so the reveal pass has *only* the bottom fade |
| `radius` | `COMPOSER_RADIUS` = **26.0** | **0** |
| `feather` | `clamp(hero_height · 0.52, 120, 280)` | **1** |
| `clearance` | **8** | **0** |
| `bottom_fade` | `(hero.bottom, max(hero_height, 1))` | **identical** |

```
cleared = Bounds {
    origin: composer.origin,
    size:   (composer.width, max(composer.bottom, hero.bottom) − composer.top)
}
```
i.e. the hole is composer-width, starts at the composer's top, and **extends
down to the hero's bottom whenever the hero is taller** — a taller image must
not fade back in beneath the composer's rounded lower edge.

**Per-pixel mask math** (the gpui shader):
```
if feather <= 0: return 1                        // zero feather disables the mask
half = mask.bounds.size * 0.5
c    = mask.bounds.origin + half
q    = abs(position − c) − half + radius
d    = length(max(q, 0)) + min(max(q.x, q.y), 0) − radius   // rounded-rect SDF, d<0 inside
alpha = smoothstep(0, feather, d − clearance)
if bottom_feather > 0:
    alpha = min(alpha, smoothstep(0, bottom_feather, bottom_y − position.y))
smoothstep(0, e, x) = t = clamp(x/e, 0, 1); t·t·(3 − 2t)
```

Consequences for the main pass: inside the composer rounded rect → alpha **0**;
out to `clearance = 8 px` → still 0 (a hard transparent margin); from 8 px to
`8 + feather` → smoothstep 0 → 1; beyond → 1. And for **both** passes:
`alpha *= smoothstep(0, hero_height, hero.bottom − y)` — alpha 1 at the hero's
top, 0 at its bottom, across the **entire** hero height.

**Fit** (`paint`, `:52–83`): early-return if any of `hero.width`, `hero.height`,
`source.width`, `source.height` is ≤ 0;
`scale = max(hero.width / src.width, hero.height / src.height)` (**cover**);
`fitted_size = src_size · scale`;
`fitted.origin = hero.center() − fitted_size/2` (**centred crop**);
`corner_radii = 0`, no grayscale.

**CSS mapping** — two absolutely-inset layers in an `overflow:hidden` hero:
```css
.new-thread-hero-art          { background-image:url(…); background-size:cover; background-position:50% 50%; }
.new-thread-hero-art--reveal  { opacity: .5; }
.new-thread-hero-art--cutout  { opacity: 1; }
```
- `background-size: cover` + `background-position: center` == the fit steps.
- **Bottom fade (both layers):** a `linear-gradient(to bottom, #000 0,
  transparent 100%)` mask — but with **smoothstep** stops
  (t = .25 → .156, .5 → .5, .75 → .844).
- **Cutout hole (main layer only):** intersect the bottom fade with a second
  mask that is a rounded rect of radius **26px**, at the composer's box,
  expanded by **8px**, feathered over **`clamp(0.52 × heroHeight, 120px,
  280px)`** — implement as an SVG `<mask>` with a rounded `<rect>` +
  `feGaussianBlur`, or an element mask with `mask-composite: subtract`. The hole
  must extend from the composer's top **down past the hero's bottom**.
- **No blend mode.** The mask multiplies source alpha; the artwork resolves into
  the real canvas (translucent themes included) with no theme-coloured overlay
  bleaching or darkening it. This file references **no theme roles at all**.

**Artwork readiness fade** (`new_thread_background_effects.rs::Readiness`,
`:11–32`):
```
opacity(image, reduced, now):
  1. image == None                     → clear state, return 0.0
  2. nothing stored, or a DIFFERENT id → store (image, now)   // restart the clock
  3. if reduced                        → return 1.0
  4. elapsed = now − stored_start
  5. return smoothstep(elapsed / 120 ms)
```
Restarts only when the image **id** changes (the same artwork does not re-fade);
reduced motion snaps to 1. Asserted: exactly **0.5 at 60 ms**.
CSS: a 120 ms opacity transition keyed on the image URL/id (`key=` forces a
restart), easing ≈ smoothstep → `cubic-bezier(0.45, 0, 0.55, 1)`; under
`prefers-reduced-motion: reduce` set `opacity: 1` with no transition.

**Decode contract** (`new_thread_background_image.rs`): decode by **sniffing the
magic bytes**, not the extension and not the MIME string — inspect the exact
bytes that will be saved (TOCTOU). Attachment staging accepts broader formats,
notably SVG, so **staging is not validation**. Web equivalent: decode via
`createImageBitmap(blob)` / `<img>` and accept only what actually decodes;
**SVG is allowed as an attachment but must be rejected as a background.**

**Settings** (from the ticket-03 client settings store):

| setting | JSON key | type | default | source |
|---|---|---|---|---|
| `new_thread_composer_background` | `newThreadComposerBackground` | `{ path: string, name: string } \| null` — `path` is the managed copy inside the device-local data dir, `name` the original file name shown in Appearance settings; `skip_serializing_if` null | `null` | settings.rs:86–93, 644–646 |
| `new_thread_background_effect` | `newThreadBackgroundEffect` | `"none" \| "dither" \| "ascii" \| "halftone" \| "scanlines"` | `"none"` | settings.rs:97–122 |

Bundled fallback artwork: `DEFAULT_NEW_THREAD_BACKGROUND_FILE` =
**`"default-new-thread-background.png"`** (settings.rs:62), stored under
`NEW_THREAD_BACKGROUND_DIR` = `"new-thread-backgrounds"` (settings.rs:61) and
compiled in as `DEFAULT_NEW_THREAD_BACKGROUND_BYTES` (settings.rs:63–64). The
web serves this asset from the bundle; the hero renders it when
`newThreadComposerBackground` is null. A background is only "available" when one
is installed **and** its file still exists.

**Effects.** `NewThreadBackgroundEffect::ALL = [None, Dither, Ascii, Halftone,
Scanlines]`, default `None`; `Dither` and `None` are appearance-independent,
the other three raster one variant per appearance. The desktop rasterizes
off-thread with a process-wide 4-entry FIFO cache — **that cache carries no
user-visible behaviour and must not be ported as a rule.** For this ticket,
implement `effect === "none"` faithfully and render the other four as a
best-effort CSS filter or a pre-rendered variant served by the engine; if
neither is available, render `none` and note it in Comments. The only part of
the caching worth reproducing is the *pending* semantics: don't kick off a
second decode/effect job for an artwork already in flight.

---

### 2.9 How the composer moves between the canvas and the chat

Put together, one route change runs like this:

1. Navigation sets the dock target (`amount` 0 → 1 for entering a chat).
2. `DockState::tick(dt)` advances the `Glide` (0.420 s docking, 0.470 s
   undocking) on both axes and, separately, the composer's `layout_width`.
3. The four `visuals` channels are sampled from the same normalized clock on
   their own staged windows (§2.6 table): the transcript fades in over
   0.20→0.65, the selectors fade out over 0.55→0.78, the footer fades in over
   0.78→1.00, the hero dissolves over 0.06→0.88.
4. The composer receives `set_dock_frame`, which **kills its own flip and
   height morphs** for the duration — the shared clock owns the height, which
   is `dock_height(amount)`.
5. The shell reserves `dock_height(destination) + strips` (via
   `dock_clearance_correction`) rather than the animated height, so the
   transcript's bottom clearance does not pump.
6. If the column's **horizontal** frame also changed, a `PanelHandoff` runs in
   parallel: the column fades to invisible by p ≈ 0.18, the geometry switches at
   p ≥ 0.22 while nothing is visible, and it fades back in by p = 1.0 —
   plus a 12px (docking) / 8px (undocking) travel offset decaying over
   `stage(progress, 0.22, 1.0)`.
7. Reduced motion, or "not moving", snaps every one of these.

**Motion table**

| what | trigger | spec | from → to | reduced motion |
|---|---|---|---|---|
| composer x/y | route change | critically damped `Glide`, ω = 12/duration, duration 0.420 s docking / 0.470 s undocking | hero centre `(vh − h)·0.5 + 8` → slot top | snap |
| composer width | route change | same `Glide`; snaps inside the handoff's invisible interval | hero column width → chat column width | snap |
| pill height | route change | `dock_height(amount)` on the shared clock; own morphs suppressed | hero height → session height | snap |
| pill radius | route change | tracks `dock_amount` | 26 → 22 | snap |
| route morph (height/chrome) | route change | `NEW_THREAD_TRANSITION` 420 ms `cubic-bezier(0.22, 1, 0.36, 1)` | — | snap |
| transcript opacity + 8px rise | docking | `stage(t, 0.20, 0.65)` / undocking `stage(t, 0.00, 0.25)` | 0 → 1 | snap |
| selector row opacity | docking | `stage(t, 0.55, 0.78)` / undocking `stage(t, 0.50, 0.95)` | 1 → 0 | snap |
| footer opacity | docking | `stage(t, 0.78, 1.00)` / undocking `stage(t, 0.00, 0.18)` | 0 → 1 | snap |
| hero dissolve | docking | `stage(t, 0.06, 0.88)` / undocking `stage(t, 0.08, 0.85)` | 0 → 1 | snap |
| panel handoff opacity | column's horizontal frame changes | 0.320 s, `opacity(p) = from·(1 − ease(p,0,0.18)) + ease(p,0.26,1)` | 1 → 0 → 1 | disabled (opacity 1) |
| return-from-panel | returning to the hero mid-handoff | 0.320 s; transcript+footer decay `stage(t,0,0.18)`, selectors rise `stage(t,0.26,0.85)`, dissolve decays `stage(t,0.26,0.80)` | — | snap |
| hero artwork readiness | image id changes | 120 ms `cubic-bezier(0.45, 0, 0.55, 1)` (smoothstep) | 0 → 1 | opacity 1, no transition |

---

## 3. Pure logic to port

Put these in `lib/composer-dock.ts` and `lib/new-thread-background.ts`.

### `routeChromeOpacities(newThreadChrome) -> [newThread, session]` (composer.rs:88) — §2.1.
### `dockHeight(amount, contentHeight, sessionExpanded)` (7490–7500) — §2.5.
### `dockClearanceCorrection(frame, strips, pillHeight)` (7583) — §2.5.
### `FlipMorph.newThreadTransition(from, startMs)` — 420 ms `EASE_RESORT`, §2.5.
### `Glide.advance(target, dt, duration)` + `Glide.active()` (composer_dock.rs:23) — §2.6.
### `stage(value, start, end)` — smoothstep, §2.6.
### `DockVisuals.settled(docked)` / `.advance(docked, time)` / `.returnFromPanel(time)` (composer_dock.rs:104, 114, 136) — the four channel windows, §2.6.
### `PanelHandoff.opacity(p)` + reversal + `enabled = false` reset (panel_handoff.rs) — §2.6.
### `DockState.tick(dt)`, `.layoutWidth()`, `.transcriptWidth()` — §2.6.
### `bottomStackMeasurementMatches(measured, expected)` (shell.rs:837) — `measured === expected`.
### `newThreadBackgroundOpacity(isFrost)` (shell.rs:844) — `isFrost ? 0.84 : 1.0`.
### `newThreadBackgroundHeight(vh)` (shell.rs:852) — `min(max(vh,0) * 0.72, 760)`.
### `heroMaskGeometry(hero, composer, cutout)` — returns `{ bounds, radius, feather, clearance, bottomFade }` per the §2.8 table, including the `cleared` rect formula.
### `readinessOpacity(imageId, reduced, now, stored)` — §2.8.
### `lerp(from, to, t) = from + (to − from)·t` (motion.rs:430).

### Desktop tests that become web unit tests

| desktop test | source | what it pins |
|---|---|---|
| `route_chrome_crossfade_never_duplicates_picker_controls` | composer.rs:9340 | across 21 samples at least one ramp is exactly 0 |
| `new_thread_selectors_restore_the_compact_floating_row` | composer.rs:9334 | the floating row's geometry survives a round trip |
| `new_thread_route_changes_use_the_coordinated_timeline` | composer.rs:9321 | 0 at t=0, strictly between at 250 ms, exact at 420 ms, both directions |
| `choreography_is_direction_specific_and_selectors_never_duplicate` | composer_dock.rs:639 | `selectors == 0 \|\| footer == 0` at every point on both timelines |
| `reversal_preserves_position_and_velocity` | composer_dock.rs:655 | `Glide` mid-flight reversal is continuous |
| `normal_dock_is_monotone_and_frame_rate_independent` | composer_dock.rs:668 | `Glide` monotone, same result at different `dt` |
| `initial_and_reduced_motion_frames_snap` | composer_dock.rs:680 | first frame + reduced motion snap |
| `idle_time_is_not_consumed_by_a_new_target` | composer_dock.rs:624 | a retarget does not swallow elapsed idle time |
| `panel_handoff_hides_background_during_geometry_switch_in_both_sidebar_states` | composer_dock.rs:456 | opacity 0 across p ≈ 0.18–0.26 |
| `panel_exit_retains_source_transcript_width_only_until_handoff_ends` | composer_dock.rs:488 | `transcriptWidth` |
| `panel_return_sizes_while_hidden_and_finishes_controls_with_input` | composer_dock.rs:502 | `layoutWidth` snaps inside the invisible interval |
| `ordinary_resizing_and_same_column_navigation_do_not_fade` | panel_handoff.rs | no fade without a horizontal frame change |
| `background_paint_sees_same_frame_composer_bounds_even_when_painted_first` | new_thread_background_mask.rs:91 | the hero never reads last frame's composer bounds |
| `mask_tracks_current_surface_in_window_space_without_rounding` | :180 | mask follows the live surface |
| `taller_background_stays_cleared_below_the_composer` | :201 | the `cleared` rect extends to the hero bottom |
| `new_thread_cutout_reveal_preserves_the_bottom_fade_and_image_extent` | :211 | reveal pass `radius == 0`, `clearance == 0`, same bottom fade |
| `new_thread_main_fade_uses_the_full_height_at_every_window_size` | :224 | `feather == 440·0.52` at a 440px hero; `feather == 280` (ceiling) at 691.2px; `clearance == 8` |

Also assert `newThreadBackgroundHeight`: `400 → 288`, `600 → 432`,
`1000 → 720`, `1200 → 760` (shell.rs:8265–8281), and
`bottomStackMeasurementMatches`: `(false,false)` true, `(true,true)` true,
`(false,true)` false, `(true,false)` false (shell.rs:8267–8270), and
`readinessOpacity` = exactly **0.5 at 60 ms**
(new_thread_background_effects.rs:328–330).

**Not ported:** `measured_dock_retargets_without_a_first_frame_jump`
(composer_dock.rs:527) is a gpui `TestAppContext` harness test with no web
equivalent — its *observable* rule (the first measured frame must not jump) is
covered by `initial_and_reduced_motion_frames_snap`.

---

## 4. Gaps this ticket closes

From `04-composer.md` §5 and `01-shell-chrome.md`, verbatim, filtered.

| item | kind | desktop | web | fix |
|---|---|---|---|---|
| New-thread selector row | MISSING | 20px floating row, `justify_end`, device + project chips | — | Port with the new-thread route |
| New-thread 420 ms coordinated transition | MISSING | `FlipMorph::new_thread_transition` + `route_chrome_opacities` crossfade | none | Port with the new-thread route |
| Composer dock (`Glide`, `PanelHandoff`, `DockFrame`) | MISSING | 0.420/0.470 s critically damped glide, 0.320 s fade-through, 4 staged visual channels | none | Port; the `DOCK_GLIDE_*` constants are already in `roboco_proto::motion` precisely so the web glides identically |
| New-thread background hero | MISSING | full-canvas artwork at 72% of viewport height (max 760), frosted opacity 0.84, two passes with a feathered rounded cutout around the composer | none — `01-shell-chrome.md` §3.16.1, mask module §3.31 | Port |
| `new_thread_composer_background` / `new_thread_background_effect` settings | MISSING | new-thread background image + effect | none (`12-settings-shell-appearance.md` §5) | Read from the ticket-03 store; the Appearance UI for setting them is ticket 28 |
| Session footer slot — Layer A | MISSING | new-thread git selectors absolutely inset in the same 24px slot as the session footer, never overlapping it | — | Port |
| Bottom chrome stack measurement | MISSING | a paint-time measurement of the stack height **plus `dock_clearance_correction`**, feeding the transcript's bottom fade band and `transcript_geometry_ready` | none | Port |
| Single composer across routes | MISSING | one entity re-anchored in prepaint; no remount | the composer is mounted per route | Hoist to `app-shell.tsx` |

---

## 5. Do not

- **Do not** mount two composers and crossfade them. One entity, re-anchored.
  A remount loses the caret, the selection, the draft and the popup state.
- **Do not** rescale the hero artwork when the right pane opens. `hero_width`
  is the **full** conversation canvas even while the pane clips it.
- **Do not** put the hero inside the transcript's edge-fade scope. It paints
  under the overlaid titlebar deliberately.
- **Do not** apply a blend mode or a theme-coloured overlay to the artwork. The
  mask multiplies source alpha and nothing else; the mask module references no
  theme roles at all.
- **Do not** accept an SVG as a background. Staging accepts it as an
  *attachment*; the background must be a decoded raster.
- **Do not** port the 4-entry FIFO artwork cache or its eviction rule — it has
  no user-visible behaviour. Port only the "one decode in flight per artwork"
  idea if you rasterize effects client-side at all.
- **Do not** reserve the animated height in the bottom stack. Reserve the
  **destination** footprint via `dockClearanceCorrection`, or the transcript
  clearance pumps.
- **Do not** render the new-thread selector row and the session footer at the
  same time. `routeChromeOpacities` guarantees one is exactly 0; unmount the
  other so its popover triggers cannot be found twice.
- **Do not** build the device/project/checkout/branch chips or their popovers —
  ticket 10. This ticket owns the rows that place them.
- **Do not** build the pill geometry, the flip morph, the send path, the
  established-chat footer or the context ring — ticket 13.
- **Do not** build the transcript, its edge fade, the status strip, the
  onboarding card, the jump-to-bottom pill, or the terminal container — tickets
  06, 18, 20, 26. This ticket touches the bottom stack only to supply
  `bottom_stack` and `dockClearanceCorrection`.
- **Do not** touch the phone layer. Decision 5: phone widths are out of scope.

---

## 6. Acceptance

- [ ] **First step:** a fresh desktop capture of the blank new-thread canvas
      exists at `.scratch/web-client/parity/desktop-02-newchat.png`, taken with
      `.scratch/web-client/parity/shot.ps1`, and it is **not** byte-identical to
      `desktop-01.png`. The hero artwork, its opacity, the mask cutout around
      the pill and the composer's resting vertical position are confirmed
      against it before implementing. (The existing file is a duplicate of 01 —
      `04-composer.md` §7.1.)
- [ ] The blank canvas renders the hero: full conversation-canvas width, height
      `min(vh × 0.72, 760)`, anchored top-left, `overflow: hidden`, two stacked
      layers at opacity 1 (cutout) and 0.5 (reveal), and 0.84 overall opacity
      under a frosted theme.
- [ ] The hero fades out toward its own bottom across its **entire** height
      (smoothstep stops: .25 → .156, .5 → .5, .75 → .844), on **both** layers.
- [ ] The cutout layer has a rounded-rect hole at the composer's box: radius 26,
      expanded by 8px of hard transparency, feathered over
      `clamp(0.52 × heroHeight, 120, 280)`, extending from the composer's top
      **past the hero's bottom**. The reveal layer has no hole.
- [ ] Changing the artwork fades the new image in over 120 ms
      (`cubic-bezier(0.45, 0, 0.55, 1)`); the same artwork does not re-fade;
      reduced motion snaps to opacity 1.
- [ ] With no `newThreadComposerBackground` set, the bundled
      `default-new-thread-background.png` renders. An SVG chosen as a background
      is rejected.
- [ ] On the canvas the composer is vertically centred at
      `(viewportHeight − pillHeight) × 0.5 + 8` (anchored by the **top** of the
      surface), always renders **expanded**, and its radius is 26.
- [ ] A 20px floating row sits above the pill: absolute, `top: -28px`,
      `left/right: 26px`, `justify-content: flex-end`, gap 4, holding the device
      chip (`monitor`, `"This device"` fallback, warning tint when offline) and
      the project chip (`folder`, `"No project"` fallback).
- [ ] The footer slot on the canvas holds the checkout and ref chips
      (`padding-x: 10px`, gap 4), and renders nothing when the space has no git.
- [ ] The selector row and the session footer are **never** both mounted.
- [ ] Sending the first message does **not** remount the composer: the caret and
      any remaining draft survive, and the pill glides from the hero position to
      the bottom of the chat column over the critically damped 0.420 s spring
      while its radius goes 26 → 22 and its height interpolates via
      `dockHeight`.
- [ ] During that handoff the transcript fades in over 0.20→0.65, the selectors
      out over 0.55→0.78, the footer in over 0.78→1.00, and the hero dissolves
      over 0.06→0.88 — measured, not eyeballed, via the unit test.
- [ ] A route change that also changes the column's horizontal frame runs the
      0.320 s panel handoff: the column is invisible across p ≈ 0.18–0.26 and
      the geometry switches at p ≥ 0.22. Ordinary resizing and same-column
      navigation do **not** fade.
- [ ] The transcript's bottom fade band and `transcript_geometry_ready` come
      from the measured bottom stack **plus** `dockClearanceCorrection`, and the
      clearance does not pump during the route change.
- [ ] `prefers-reduced-motion: reduce` snaps the glide, the route morph, the
      four channels, the handoff and the artwork fade.
- [ ] Unit tests (each named after the desktop test it mirrors, across
      `tests/composer-dock.test.ts` and `tests/new-thread-background.test.ts`):
      `route_chrome_crossfade_never_duplicates_picker_controls`,
      `new_thread_selectors_restore_the_compact_floating_row`,
      `new_thread_route_changes_use_the_coordinated_timeline`,
      `choreography_is_direction_specific_and_selectors_never_duplicate`,
      `reversal_preserves_position_and_velocity`,
      `normal_dock_is_monotone_and_frame_rate_independent`,
      `initial_and_reduced_motion_frames_snap`,
      `idle_time_is_not_consumed_by_a_new_target`,
      `panel_handoff_hides_background_during_geometry_switch_in_both_sidebar_states`,
      `panel_exit_retains_source_transcript_width_only_until_handoff_ends`,
      `panel_return_sizes_while_hidden_and_finishes_controls_with_input`,
      `ordinary_resizing_and_same_column_navigation_do_not_fade`,
      `background_paint_sees_same_frame_composer_bounds_even_when_painted_first`,
      `mask_tracks_current_surface_in_window_space_without_rounding`,
      `taller_background_stays_cleared_below_the_composer`,
      `new_thread_cutout_reveal_preserves_the_bottom_fade_and_image_extent`,
      `new_thread_main_fade_uses_the_full_height_at_every_window_size`,
      plus the `newThreadBackgroundHeight` table (400/600/1000/1200 →
      288/432/720/760), `bottomStackMeasurementMatches` (4 cases), and
      `readinessOpacity` = 0.5 at 60 ms.
- [ ] Screenshot pair, desktop vs web, states: (a) blank new-thread canvas, no
      draft, default artwork — the fresh capture from step 1; (b) the same
      canvas with a four-line draft (taller pill, larger cutout); (c) the same
      canvas with a space that has git — checkout and ref chips in the footer
      slot; (d) mid-transition at ~250 ms of the 420 ms timeline; (e) the
      established chat immediately after the handoff (radius 22, footer
      visible, hero gone).
- [ ] `pnpm -r build` green; `web/packages/app` vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

### Research addendum (2026-09-17)

Deep-dive notes: `.scratch/web-parity/research-2026-09-17/hero-context-meter.md`
(Parts 1 and 3). Four gaps the spec leaves open, all verified against source:

- **Hero typing animation**: the hero pill auto-grows with the 180 ms
  `COLLAPSE`/`EASE_OUT` height morph as the user types multi-line drafts
  (composer.rs:7548-7562 — only *mode flips* are suppressed on the hero,
  composer.rs:7300), and the background cutout mask must track the animating
  pill bounds every frame (mask.rs:49-58, same-frame `SurfaceBounds`). The
  ticket's acceptance (b) reads as a static height.
- **No composer entrance animation exists on the desktop**: the only hero
  entrance is the artwork's 120 ms readiness fade. `motion::settle_down`
  (motion.rs:339-350), documented as the intended
  logo+selectors+composer fade, is dead code with no call sites — do not
  port it or invent a fade.
- **The hero hosts the composer-column chrome too**: failure notice,
  queue-degraded caption, and the queue tray render on the new-thread canvas
  (composer.rs:7347-7469); needs a cross-ticket composition note with 13.
- **Send-transition trigger chain**: `ComposerEvent::NewThreadTransitionStarted`
  emitted on the new-chat send → `select_chat` commits → shell notified →
  dock ticks (composer.rs:6249-6258; shell.rs:1289-1295), with the arming
  guards `!reduced_motion && last_rendered_height > 0` and
  `expanded_mode = returning_to_new_thread` at composer.rs:5826-5875.
