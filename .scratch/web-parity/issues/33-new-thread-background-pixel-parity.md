# 33 — New-thread background pixel parity

**What to build:** The new-chat canvas background becomes pixel-identical to the
desktop. The cutout hole around the composer renders with the desktop's
per-pixel smoothstep SDF ramp (a hard 8 px transparent margin, then a
120–280 px one-sided dome) instead of the tight Gaussian blur; the bottom fade
and the hole compose by `min` instead of mask multiplication; the hero's
frosted branch (0.84 opacity) and the pill's frosted tint engage off the
resolved surface treatment instead of a hard-coded `false`; and the four
background effects — dither, ascii, halftone, scanlines — render as true
per-pixel canvas rasters, replacing the CSS-filter stand-ins. User symptoms
this closes: "the background on the new chat page looks off, the gradient and
stuff feels off" and "the background effect dont seem to work like the desktop
app".

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

**Research:** `../research-2026-09-19/new-thread-background-and-transitions.md`
S1(a)–(d), S2(a)–(d), consolidated gap rows G1–G9, "Pure logic to port" item 1,
"Desktop-only items NOT to port".

**Desktop reference (for lookups only):**
`crates/ui/src/shell.rs::new_thread_background` (857–914),
`::new_thread_background_opacity` (844–850), `NEW_THREAD_BACKGROUND_FROSTED_OPACITY`
(697), the hero layer mount (5865–5895), element opacity (880, 5860–5864, 5893);
`crates/ui/src/new_thread_background_mask.rs` (the whole module — the two-pass
feathered cutout); `crates/ui/src/new_thread_background_effects.rs` (the four
rasters, the `(effect, light)` cache, `Readiness`, `prepare`);
`crates/ui/src/composer.rs` (7879 pill frost, 7714 `composer_sidebar_tint`,
62 `COMPOSER_RADIUS`); `crates/ui/src/frost.rs::frosted` (27–33);
`crates/theme/src/lib.rs` (55–83 — `SurfacePreference` resolution).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/lib/new-thread-background.ts` | edit | the cutout mask geometry: replace `heroCutoutHole`/`featherSigma`/`cutoutMaskDataUri`'s Gaussian approximation with the smoothstep SDF ramp (`cutoutMaskAlpha` / a raster-producing pure fn); keep `BOTTOM_FADE_GRADIENT`, `heroMaskGeometry`, `newThreadBackgroundOpacity`, `Readiness` untouched |
| `web/packages/app/src/lib/new-thread-background-effects.ts` | new | `ditherPixels` + `ditherColor` + `BAYER`, `halftonePixels`, `asciiPixels` + `GLYPHS`, `scanlinePixels`, `coverIndex`, the `(effect, light)` raster cache with one-pending-job semantics, a `prepare`-style prewarm entry, the off-main-thread rasterizer driver |
| `web/packages/app/src/components/new-thread-background.tsx` | edit | `NewThreadBackground` — the mask application (single combined min-composited mask), the resolved-surface opacity input replacing `newThreadBackgroundOpacity(false)`, painting the rasterized artwork when an effect is active |
| `web/packages/app/src/styles/app.css` | edit | `.new-thread-hero[data-effect=…]` blocks (2560–2586 — **delete** the CSS-filter stand-ins and the scanlines overlay), `.composer-pill` frosted branch (3161–3172) |
| `web/packages/app/tests/new-thread-background.test.ts` | edit | the mask ramp / min-compositing / opacity-branch cases |
| `web/packages/app/tests/new-thread-background-effects.test.ts` | new | the four rasters — ports of the desktop effects tests |

---

## 1. Context a fresh session needs

- The surface is the **new-chat canvas hero** (route `/`, no chat selected): the
  full-conversation-canvas artwork behind the vertically centered composer pill,
  with a feathered rounded-rect hole cut around the pill so it reads as a window
  into the artwork, not a sticker. It is shell chrome, not composer chrome:
  `aria-hidden`, `pointer-events: none`, painted under the overlaid titlebar.
- Current wiring: the ui-settings store (`newThreadComposerBackground`,
  `newThreadBackgroundEffect` — `state/ui-settings.ts:528-533`, the effect union
  at `:120`) → `useNewThreadBackground` (`state/appearance.ts:86-105`) →
  `NewThreadCanvas` (`routes/index-page.tsx:19-43`) → `NewThreadBackground`
  (`components/new-thread-background.tsx:64-212`) → `.new-thread-hero*`
  (`app.css:2488-2601`). `ConversationPage` (`routes/chat-page.tsx`) mounts it
  at `:722-728` while `heroVisible` (`:565`).
- Two stacked passes paint the same cover-fit artwork
  (`components/new-thread-background.tsx:186-209`): the **reveal** pass at
  opacity 0.5 with only the shared bottom fade, and the **cutout** pass at
  opacity 1 whose mask additionally clears the hole. The CSS
  `background-size: cover; background-position: 50% 50%`
  (`app.css:2534-2540, 2595-2601`) is the desktop's exact fit math.
- The hole today is an SVG `<mask>` = white rect + Gaussian-blurred black
  rounded rect (`lib/new-thread-background.ts:161-176`), hole rect = the cleared
  rect expanded by clearance 8, corner radius 26+8=34
  (`heroCutoutHole`, `lib/new-thread-background.ts:132-145`), σ = feather/2.563
  (`:148-150`). This is ticket 15 deviation 5 (a blessed approximation) — this
  ticket replaces it with the real ramp.
- Compositing today is **multiply**: the fade mask sits on the cutout layer and
  the hole mask on the image child, and element masks multiply
  (`components/new-thread-background.tsx:166-171`; the comment at
  `app.css:2525-2532`). The desktop takes `min(hole_alpha, bottom_fade_alpha)`.
- The hero is **forced opaque**: `components/new-thread-background.tsx:77-81`
  hard-codes `newThreadBackgroundOpacity(false)`. The defrost decision itself
  lives elsewhere and this ticket must NOT undo it:
  `lib/appearance-store.ts:93-95` (`resolveSurfaceTreatment()` returns
  `"opaque"` — the 2026-09-17 product decision) and the preference healing at
  `state/ui-settings.ts:523-527` ("Frosted is no longer selectable"). The
  resolved treatment lands on `<html>` as `data-surface` (`theme.ts:75`).
- The effects today are threaded but faked: `data-effect` on the hero
  (`components/new-thread-background.tsx:177`) renders CSS stand-ins
  (`app.css:2550-2586`) — scanlines as a 1px/3px 0.16-black repeating gradient,
  dither/halftone/ascii as `filter:` recipes. Ticket 15 deviation 4 flagged
  exactly this as "the follow-up a canvas port owes".
- The readiness fade (120 ms on image-id change, never re-fades the same id,
  reduced snaps) is already ported (`components/new-thread-background.tsx:85-104`
  + the `Readiness` class at `lib/new-thread-background.ts:200-219`). **Ticket 35
  owns hoisting it to shell scope — leave its owner alone here.**
- Tokens: the hero's masks reference **no theme roles** — the desktop's mask
  multiplies source alpha and nothing else, no overlay, no blend
  (`shell.rs:881-882`). The pill's frosted branch uses `--rb-*` tokens only
  (glass overlay alpha idiom, `--rb-shadow-*`).
- Verified current state of the pill (a research discrepancy this ticket
  resolves): `.composer-pill` (`app.css:3161-3172`) **already carries
  `backdrop-filter: blur(16px)`** — its comment says "the 16px frost blur
  retained on the translucent input tint". The research's "no backdrop blur
  behind the pill" is stale on the blur itself; what is actually missing is the
  **frosted-branch tint** (`theme.composer_sidebar_tint()`, composer.rs:7714)
  keyed on the resolved surface treatment. The pill radius already rides the
  inline `26 − 4·dockAmount` (ticket 15).
- Vocabulary (`CONTEXT.md`): **chat** (not session/thread), **space** (not
  project), **engine**, **harness** (not provider).

---

## 2. Spec

### 2.1 `NewThreadBackground` — the hero's exact values

The desktop reference table, verbatim:

| item | value | source |
|---|---|---|
| hero opacity (frosted) | **0.84** | `NEW_THREAD_BACKGROUND_FROSTED_OPACITY`, shell.rs:697; fn shell.rs:844-850 |
| hero opacity (opaque) | 1.0 | shell.rs:844-850 |
| hero element opacity | `(1 − dissolve) × artwork_readiness × bg_opacity(is_frost)` | shell.rs:880, 5860-5864, 5893 |
| height | `min(max(vh,0) × 0.72, 760)` | shell.rs:852-855, 698-699; tests 8276-8281 |
| width | `max(viewport − sidebar_now(), 0)` — sidebar_now is the ANIMATED width | shell.rs:5890 |
| position | `absolute; top 0; left 0; overflow hidden` inside `#chat-dropzone` (no titlebar pad on the chat route) | shell.rs:873-879, 5990-5997 |
| mount condition | `!has_selection \|\| dock_frame.active` — decided with the frame ticked IN THE SAME RENDER | shell.rs:5883 |
| passes | `[reveal (opacity 0.5, no hole), cutout (opacity 1, hole)]` — same paint order | shell.rs:883-912; CUTOUT_REVEAL_OPACITY mask.rs:8 |
| cutout mask bounds | `cleared = (composer.origin, composer.width, max(composer.bottom, hero.bottom) − composer.top)` | mask.rs:16-22 |
| cutout radius | `COMPOSER_RADIUS = 26` | mask.rs:31-34; composer.rs:62 |
| cutout feather | `clamp(hero_height × 0.52, 120, 280)` | mask.rs:36-40 |
| cutout clearance | 8 px hard-transparent margin | mask.rs:41 |
| reveal pass | exclusion parked at `(hero.left, hero.bottom + 1)`, radius 0, feather 1, clearance 0 | mask.rs:26-41 |
| shared bottom fade | `alpha = min(hole, smoothstep(0, hero_height, hero.bottom − y))` — full height, BOTH passes | mask.rs:42-46; ticket 15 §2.8 |
| per-pixel ramp | `smoothstep(0, feather, d − clearance)` over the rounded-rect SDF | ticket 15 §2.8 (gpui shader) |
| fit | cover scale `max(w/src_w, h/src_h)`, centered, `corner_radii 0`, no grayscale | mask.rs:59-73 |
| artwork readiness | 120 ms smoothstep on image-id change; same id never re-fades; reduced → 1 | effects.rs:11-33 (Readiness) |
| no overlay/blend | mask multiplies source alpha only, no theme roles | shell.rs:881-882 |

**Children (in order)** — unchanged from ticket 15: the readiness wrapper keyed
on the artwork id, then the reveal pass, then the cutout pass with the image
child. What changes is the mask each pass consumes (§2.2) and the image source
(§2.4).

**States** (what changes):

| state | condition | what changes |
| --- | --- | --- |
| opaque surface | resolved treatment is `"opaque"` | hero opacity multiplier 1.0; pill keeps the opaque branch (today's `.composer-pill` background) |
| frosted surface | resolved treatment is `"frosted"` | hero opacity multiplier **0.84**; pill takes the frosted tint branch (§2.3) |
| effect active | `newThreadBackgroundEffect ≠ "none"` | the painted image is the RASTERIZED artwork (§2.4), still cover-fit, still masked |
| effect `"none"` | default | the raw artwork, as today |

**Interactions:** none — the hero is not an interaction surface
(`pointer-events: none`, `aria-hidden`).

**Motion:** only the readiness fade (120 ms smoothstep, image-id keyed, reduced
snaps) — already ported; nothing else in the hero is time-based. Do not add
motion.

**Data:** reads `newThreadComposerBackground` + `newThreadBackgroundEffect` via
`useNewThreadBackground` (`state/appearance.ts:86-105`); reads the resolved
surface treatment (`data-surface` on `<html>`, set by `theme.ts:75`) and the
resolved appearance (light/dark) for the raster keying. Writes nothing.

### 2.2 The cutout hole — the smoothstep SDF ramp

The desktop's per-pixel mask (the gpui `ImageAlphaMask` shader,
`new_thread_background_mask.rs:12-47`, per ticket 15 §2.8), verbatim:

```
d    = rounded-rect SDF of the CLEARED rect (radius 26)
alpha= smoothstep(0, feather, d − clearance)     // 0 for d ≤ 8,
                                                // ramp over d ∈ [8, 8+feather]
alpha= min(alpha, smoothstep(0, hero_height, hero.bottom − y))
```

with `feather = clamp(hero_height · 0.52, 120, 280)` (mask.rs:36-40).

The visible difference from today's Gaussian, verbatim from the research:

| quantity | desktop | web |
|---|---|---|
| hard-transparent region | cleared rect + 8 px out | ≈ expanded rect eroded by ~2σ (≈ 8 − 0.78·feather) — **smaller** |
| ramp midpoint (50 % alpha) | 8 + feather/2 px out (≈ 148 px at feather 280) | 8 px out (the expanded rect's edge) |
| ramp shape | smoothstep (compact support, ends at 8+feather) | Gaussian (symmetric ±2σ tails) |

So the web's hole must become a **hard 8 px margin followed by an enormous
one-sided gradient dome**, not a tight symmetric blur.

**Implementation contract:**

- Export a pure function from `lib/new-thread-background.ts` that evaluates the
  shader exactly: given the hero rect, the composer rect, and a pixel (or an
  ImageData-sized grid), return `alpha = min(hole, fade)` where `hole` is the
  smoothstep-over-SDF ramp above and `fade` is
  `smoothstep(0, hero_height, hero.bottom − y)` — the **min happens inside one
  combined mask**, which is exactly the desktop's single shader (and closes the
  multiply-vs-`min` gap: mid-ramp `a·b < min(a,b)`, so multiplication darkens
  the hole's edges near the hero's bottom).
- Consume it per pass: the reveal pass gets `fade` only (its exclusion is parked
  below the image — a no-op ramp), the cutout pass gets `min(hole, fade)`. The
  two-layer DOM structure stays.
- Rendering shape: either (a) paint each pass onto a `<canvas>` (draw the
  cover-fit artwork, then multiply the canvas's per-pixel alpha by the pure
  function's output), or (b) keep CSS `mask-image` fed by a canvas-produced
  raster. **Do not** feed a per-frame `toDataURL()` PNG into `mask-image` — the
  mask regenerates per commit and per frame during motion (ticket 34); PNG
  encoding per frame is too slow. A painted canvas (a) is the faithful peer of
  the desktop's shader and is preferred.
- `heroMaskGeometry` (the two-pass parameter table: bounds/radius/feather/
  clearance/bottomFade) is already ported field-for-field — keep it as the
  single source the ramp consumes. The SVG Gaussian trio
  `heroCutoutHole`/`featherSigma`/`cutoutMaskDataUri` is **deleted** (or reduced
  to a `prefers-reduced-motion`/no-canvas fallback, at the implementer's
  judgment — note it in Comments if kept).
- The mask is regenerated from the measured `#composer-surface`
  (`components/new-thread-background.tsx:109-160`, rAF-scheduled per commit +
  ResizeObserver on the surface) — that cadence is ticket 15's same-frame
  contract and stays; ticket 34 extends it to every frame of the sidebar
  tween.

### 2.3 The frosted branch — 0.84 hero, frosted pill

- `components/new-thread-background.tsx:77-81` stops hard-coding
  `newThreadBackgroundOpacity(false)`. The multiplier becomes
  `newThreadBackgroundOpacity(resolvedTreatment === "frosted")`, where the
  resolved treatment is read off the document root (`data-surface`,
  `theme.ts:75`). The element opacity stays
  `(1 − dissolve) × readiness × bg_opacity`.
- This **does not un-defrost the app**: `resolveSurfaceTreatment()`
  (`lib/appearance-store.ts:93-95`) still returns `"opaque"` and the settings
  healing (`state/ui-settings.ts:523-527`) is untouched — the hero simply stops
  pre-deciding the answer, so the 0.84 branch is live the day the resolution
  yields frosted (the research's S1(d) row is a decision item; this ticket
  implements its first option, "honor the surface frost when it exists").
- The pill: `.composer-pill` (`app.css:3161-3172`) already has the 16 px
  `backdrop-filter: blur(16px)` and the opaque tint
  (`color-mix(in srgb, var(--rb-input) 82%, var(--rb-bg))`). Add the frosted
  branch scoped to the resolved treatment — the desktop's
  `frost::frosted(surface_radius, 16.0, body)` with
  `bg theme.composer_sidebar_tint()` (composer.rs:7879, 7714) — i.e. under
  `[data-surface="frosted"]`, the pill's background takes the composer-tint
  token recipe over the glass idiom, radius `26 − 4·amount` (already inline).
  Use `--rb-*` tokens only; no literal hex.

### 2.4 The four effect rasters

The effects are **static per-pixel transforms of the ≤2048 px decoded
artwork** — pure functions of the source and the appearance. There is no
animated background effect; do not invent one.

Common pipeline (`effects.rs`), verbatim:

- decode by magic-byte sniffing (`new_thread_background_image.rs`, ticket 15
  §2.8), `thumbnail(2048, 2048)`, keep luma8 + rgba8 (effects.rs:265-280);
- cache: process-wide 4-entry FIFO per path (effects.rs:243-263); ONE pending
  job per `(effect, light)` key — 100 calls while pending yield one raster
  (effects.rs:53-67; test `every_effect_is_generated_once_independently_of_viewport`,
  effects.rs:384-421);
- rasterized on the background executor, `cx.refresh_windows()` when ready
  (effects.rs:69-105); raster output is **BGRA** (RenderImage contract,
  effects.rs:214);
- prewarmed on BOTH routes, never contingent on hero geometry
  (`prepare`, effects.rs:292-302; shell.rs:5846-5859).

Web mapping of the pipeline: the decode/thumbnail step is ticket 15's
`createImageBitmap` contract; the module-level raster cache is keyed
`(effect, light)` (Dither and None are appearance-independent —
effects.rs:53-61); rasterize off the main thread (`createImageBitmap` +
`OffscreenCanvas`, or a Worker — implementer's choice, name the file in
Comments if a worker file is added); the "4-entry FIFO per path" cache is a
desktop-only concern (see §5) — port only the one-pending-job semantics
(memoize the promise while rasterizing). The prewarm-on-both-routes call site
rides ticket 35's hoisted artwork store; this ticket's `prepare` API must be
callable without any hero geometry.

**Dither** (effects.rs:81-83, 200-221, 303-313), verbatim:

- 2×2 cells, stepped by 2; one quantize per cell sampled at the cover-fit
  center `(x+1, y+1)`;
- Bayer 4×4: `[[0,8,2,10],[12,4,14,6],[3,11,1,9],[15,7,13,5]]` indexed by
  `BAYER[y/2 % 4][x/2 % 4]`;
- `dither_color([r,g,b,a], threshold)` (effects.rs:303-313):
  `peak = max(r,g,b)`; `bright = peak/255 > (threshold+0.5)/16`;
  `gain = bright ? 255/peak : 0.08`; output `round(c·gain)`, alpha kept;
- the 2×2 cell is filled with the quantized color.

**Halftone** (effects.rs:84-86, 165-198), verbatim:

- 4×4 cells; `paper = light ? 255 : 0`;
- per cell: `luma = cover_sample(x, y)` (inverted when light);
  `radius = 2.0 · (0.3 + 0.7 · sqrt(luma/255))`;
  dot color from `cover_index(x+2, y+2)`; per sub-pixel
  `distance = hypot(dx−1.5, dy−1.5)`,
  `coverage = clamp(radius + 0.5 − distance, 0, 1) · alpha/255`;
  `blend = source·0.60 + (dot·coverage + paper·(1−coverage))·0.40`.

**Ascii** (effects.rs:87, 123-164), verbatim:

- 6×8 cells (5-col glyph + 1 spacing column, 7 rows + 1 spacing row);
- 10 glyphs as 7-row 5-bit columns (effects.rs:126-137):
  ```
  [0,0,0,0,0,0,0], [0,0,0,0,0,4,0], [0,4,0,0,4,0,0], [0,0,0,14,0,0,0],
  [0,0,14,0,14,0,0], [0,4,4,31,4,4,0], [0,21,14,31,14,21,0],
  [10,10,31,10,31,10,10], [17,2,4,4,8,16,17], [14,17,23,21,23,16,14]
  ```
- sample at cell centers `(x/6·6+3, y/8·8+4)` (clamped);
  `ink_density = light ? 255−luma : luma`;
  `index = sqrt(density/255)·9`;
  `ink = x%6 < 5 && y%8 < 7 && GLYPHS[index][y%8] & (1 << (4 − x%6))`;
  `mix = base·0.60 + (ink ? glyph_sample·0.40 : paper·0.40)` — a colored image
  stays beneath the glyph texture in both themes (effects.rs:152-161).

**Scanlines** (effects.rs:88, 109-122), verbatim:

- `gain = y % 3 == 0 ? 0.52 : 1.0`;
- light: `v + (255−v)·(1−gain)`; dark: `v·gain`; alpha preserved.

Cover-fit sampling (`cover_index`, effects.rs:227-240): the same
`max(w/src_w, h/src_h)` scale + centered crop as the hero paint, clamped to the
source bounds.

When an effect is active, the rasterized ImageData becomes the image both hero
passes paint (cover-fit, masked exactly as §2.1) — the CSS stand-ins at
`app.css:2560-2586` (the scanlines `::after` overlays and the three `filter:`
recipes) are **deleted**. The raster is keyed on the resolved appearance
(`useResolvedAppearance`, `state/appearance.ts:55-59`); Dither and None do not
re-raster on an appearance flip; the others re-raster and both variants are
kept warm (desktop test `appearance_changes_cache_both_variants_and_share_unchanged_dither`).

---

## 3. Pure logic to port

From the research's "Pure logic to port" item 1:

- **The four effect rasters** (`new_thread_background_effects.rs`):
  `dither_pixels` (:200-221) + `dither_color` (:303-313) + the BAYER table
  (:201), `halftone_pixels` (:165-198), `ascii_pixels` (:123-164) + GLYPHS
  (:126-137), `scanline_pixels` (:109-122), `cover_index` (:227-240).
  Web home: `lib/new-thread-background-effects.ts` (pure, ImageData in/out)
  + an off-main-thread rasterizer (Worker/`createImageBitmap`).
- **The mask ramp** (this ticket's §2.2): the smoothstep-over-SDF alpha with
  `min(hole, fade)` in one function, consuming `heroMaskGeometry`.
- Desktop tests to mirror as web unit tests:
  - `every_effect_is_generated_once_independently_of_viewport` (effects.rs:384)
  - `light_treatments_use_light_paper_without_inverting_source_hues` (:423)
  - `raster_treatments_preserve_source_dimensions_and_alpha` (:485)
  - `appearance_changes_cache_both_variants_and_share_unchanged_dither` (:447)
  - `prewarming_decodes_off_thread_and_reuses_artwork_without_hero_geometry` (:339)
- Mask-level web cases (no desktop name — the shader is asserted via the S5/S1
  geometry tests): alpha 0 for d ≤ 8; 0.5 at 8 + feather/2; 1 at ≥ 8 + feather;
  the ramp's compact support; `min` (not product) at a pixel where both hole
  and fade are mid-ramp; feather clamps at 120/280 for short/tall heroes.

---

## 4. Gaps this ticket closes

S1(d), verbatim:

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| Hero feather ramp | WRONG SHAPE | smoothstep over [8, 8+feather] from the cleared rect, feather 120–280 (mask.rs:36-40) | Gaussian σ=feather/2.563 centered on the rect expanded by 8, radius 34 (new-thread-background.ts:132-150) | render the hole as an SVG radial/linear smoothstep ramp (or canvas alpha mask) anchored 8 px out with the full feather width; keep σ only as a reduced-motion fallback |
| Hard-transparent margin | WRONG VALUE | alpha 0 for d ≤ 8 (clearance) | eroded ≈ 8 − 2σ (Gaussian tails) | same — hard 8 px ring at radius 26 before the ramp |
| fade × hole compositing | WRONG OP | `min(hole, fade)` | multiply (element masks) | approximate min by taking the per-stop min in one combined mask (or accept, note in ticket) |
| hero overall opacity | KNOWN DEVIATION | 0.84 under frost × readiness (shell.rs:5893) | forced 1.0 (new-thread-background.tsx:77-81) | decision item: either honor `--rb-surface` frost when it exists, or record as accepted (deviation 6) |
| pill backdrop blur | KNOWN DEVIATION | `frosted(26−4·amount, 16)` + composer tint (composer.rs:7879, 7714) | none (opaque pill) | tied to the same frost decision |
| bottom fade gradient | MATCHES | mask.rs:42-46 | BOTTOM_FADE_GRADIENT (new-thread-background.ts:184-185) | none |

S2(d), verbatim:

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| dither | MISSING | Bayer 4×4 2×2-cell quantize, gain 255/peak or 0.08 (effects.rs:200-221, 303-313) | `contrast(1.4) saturate(0.7)` (app.css:2573-2576) | ImageData port of `dither_pixels` + `dither_color` |
| halftone | MISSING | 4×4 cells, radius 2·(0.3+0.7·√luma), 60/40 blend (effects.rs:165-198) | `contrast(1.25) brightness(1.05) grayscale(0.25)` (app.css:2578-2581) | ImageData port of `halftone_pixels` |
| ascii | MISSING | 6×8 glyph cells, √-indexed 10-glyph table, 60/40 mix (effects.rs:123-164) | `grayscale(1) contrast(1.6) brightness(1.1)` (app.css:2583-2586) | ImageData port of `ascii_pixels` |
| scanlines | WRONG VALUES | every 3rd row gain 0.52 (dark) / toward-white (light), per-channel (effects.rs:109-122) | 1px black 0.16 every 3px overlay (app.css:2560-2571) | ImageData port of `scanline_pixels` |
| appearance variants | MISSING | light/dark paper per effect; Dither/None appearance-independent (effects.rs:53-61) | one variant, no light/dark key | key the raster on resolved appearance; re-raster on change |
| one-pending-job | MISSING | single in-flight raster per (effect, light) (effects.rs:65-67) | n/a | memoize the promise while rasterizing |
| effect is animated? | N/A | static rasters; only the 120 ms readiness fade is time-based (effects.rs:11-33) | — | do NOT invent animation |

(Consolidated rows G1–G9 are these rows; the pill-blur row's "web value" is
stale — the 16 px blur exists at `app.css:3169-3170`; the remaining work is the
frosted tint branch, §2.3.)

---

## 5. Do not

- **Do not re-add the CSS-filter stand-ins** once the rasters land — the
  scanlines `::after` overlays and the dither/halftone/ascii `filter:` recipes
  at `app.css:2560-2586` are INVENTED approximations (ticket 15 deviation 4);
  delete them rather than layering the rasters on top.
- **Do not invent animation.** The effects are static rasters; the only
  time-based piece in the whole hero is the 120 ms readiness fade
  (effects.rs:11-33). No shimmer, no drift, no scanline crawl.
- **Do not port the 4-entry FIFO artwork cache and its eviction** — desktop-only
  (no user-visible behavior); port only the one-pending-job semantics
  (effects.rs:65-67).
- **Do not port the BGRA channel order** (effects.rs:214) — canvas ImageData is
  RGBA; port the math, not the layout.
- **Do not un-defrost the app**: `resolveSurfaceTreatment()`
  (`lib/appearance-store.ts:93-95`) and the surface healing
  (`state/ui-settings.ts:523-527`) stay exactly as they are. The hero merely
  consumes the resolved treatment instead of pre-deciding `false`.
- **Do not port `frost.rs`'s pass-through-when-opaque and scene-layer
  draw-order machinery** (frost.rs:85-96) — CSS `backdrop-filter` composes in
  one layer already.
- **Do not port `motion::settle_down` or invent a hero entrance fade** — dead
  desktop code, no call sites.
- **Do not touch** what other tickets in this batch own: **33** is this ticket;
  **34** owns the hero's width source during a sidebar toggle and the titlebar
  island (leave `heroWidth` and the remask cadence alone — 34 extends the
  cadence to every tween frame); **35** owns hoisting the artwork resolution,
  readiness clock, and blob-URL identity to shell scope (leave
  `useNewThreadBackground`'s owner and `resolveNewThreadBackground`'s call
  pattern alone); **36** owns the route transition matrix (do not change the
  `dissolve` channel or dock wiring); **42** owns the `file-*` icon family
  fixes (the generator's root-stroke bug and the `fileImage` glyph the
  Appearance row shows — do not touch `web/packages/icons` or
  `routes/settings-appearance.tsx` here; the row's thumb-margin polish is the
  Appearance row's own concern, not this ticket's); **48** owns the
  active-background resolution semantics including the default
  fallback (consume the resolved artwork; do not change what resolves).
- **Do not touch** `BOTTOM_FADE_GRADIENT` or the height math — they MATCH
  (S1(a) item 4, mask.rs:45, asserted by
  `new_thread_main_fade_uses_the_full_height_at_every_window_size`).
- `ROBOCO_MOTION_SCALE` multipliers are desktop-only — the web keeps
  hard-coded durations.

---

## 6. Acceptance

- [ ] Cutout mask: alpha is exactly 0 for SDF distance d ≤ 8 from the cleared
      rect (radius 26); 0.5 at d = 8 + feather/2; 1 at d ≥ 8 + feather; feather
      = clamp(0.52·heroHeight, 120, 280) — unit-tested on the pure function.
- [ ] One combined mask: a pixel where hole and fade are both mid-ramp yields
      `min(hole, fade)`, not their product — unit-tested.
- [ ] Hero opacity: multiplier 0.84 when the resolved treatment is `"frosted"`,
      1.0 when `"opaque"`; element opacity `(1 − dissolve) × readiness ×
      multiplier` — unit-tested + verified computed with
      `data-surface="frosted"` forced on the root for the screenshot state.
- [ ] Pill: frosted branch shows the composer-tint recipe + the existing
      16 px `backdrop-filter` blur (verified computed); opaque branch unchanged.
- [ ] Effects: `dither`/`halftone`/`ascii`/`scanlines` paint the rasterized
      artwork — the CSS stand-in selectors at `app.css:2560-2586` are gone and
      `grep 'contrast(1.4)' web/packages/app/src/styles/app.css` returns
      nothing.
- [ ] Rasters: appearance-keyed — light and dark variants differ per the
      paper rules; Dither raster is shared across appearances; appearance flip
      re-rasters without a flash; one pending job per `(effect, light)`.
- [ ] Unit tests: `every_effect_is_generated_once_independently_of_viewport`,
      `light_treatments_use_light_paper_without_inverting_source_hues`,
      `raster_treatments_preserve_source_dimensions_and_alpha`,
      `appearance_changes_cache_both_variants_and_share_unchanged_dither`,
      `prewarming_decodes_off_thread_and_reuses_artwork_without_hero_geometry`
      → `web/packages/app/tests/new-thread-background-effects.test.ts`;
      mask ramp/min/opacity cases →
      `web/packages/app/tests/new-thread-background.test.ts`.
- [ ] Screenshot pair, desktop vs web, states: canvas with the default
      artwork (no effect); canvas with each of the four effects installed; the
      same four under light appearance; frosted-branch state
      (`data-surface="frosted"` forced) next to a frosted desktop theme.
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

(empty; appended during implementation)
