# New-thread background & route transitions — web parity defects S1–S6

Date: 2026-09-19. Branch `web-parity/wave-1`, HEAD `bc3a3945`. Read-only source
research; every claim cites `file:line`. Web = `web/packages/app/src`, desktop =
`crates/ui/src`. Read alongside (do not contradict):

- `.scratch/web-parity/issues/15-new-thread-route.md` (the implemented ticket;
  its Comments list deviations 1–7 — this file builds on, not re-litigates, them)
- `.scratch/web-parity/issues/32-pairing-remount.md` (the phase-keyed remount
  defect — a DIFFERENT remount than S5's, see §S5 "ruled out")
- `.scratch/web-parity/issues/06-titlebar-main-chrome.md`,
  `07-right-pane-host.md` (titlebar cluster / pane host context)
- `.scratch/web-parity/research-2026-09-17/hero-context-meter.md` (desktop hero
  spec, Part 1; still accurate — re-verified line numbers below)

---

## S1 — "the background on the new chat page looks off, the gradient and stuff feels off"

### (a) Web defect trace

The hero: `components/new-thread-background.tsx:173-212`, geometry in
`lib/new-thread-background.ts`.

1. **Overall opacity is forced to the opaque branch.**
   `components/new-thread-background.tsx:77-81`:
   ```ts
   // The web is forced opaque (the 2026-09-17 defrost decision…)
   const frost = newThreadBackgroundOpacity(false);
   const heroOpacity = (1 - Math.min(Math.max(dissolve, 0), 1)) * frost;
   ```
   `newThreadBackgroundOpacity(false)` = **1.0** (`lib/new-thread-background.ts:57-59`).
   The desktop multiplies `artwork_opacity * new_thread_background_opacity(theme.is_frost())`
   (`shell.rs:5893`) → **0.84** whenever the resolved surface is frosted
   (`shell.rs:697, 844-850`). The desktop's surface resolves per theme variant
   (`roboco_theme` `crates/theme/src/lib.rs:55-83`: `SurfacePreference::ThemeDefault`
   → the variant's `recommended_surface_treatment`), so on frosted desktop themes
   the hero is 16 % softer than the web's. This is ticket-15 deviation 6
   (15-new-thread-route.md:808) — a deliberate decision, listed here because it is
   the first thing "the gradient and stuff feels off" can mean.

2. **The cutout feather is a differently-anchored Gaussian.** The web's hole mask
   is an SVG `<mask>` = white rect + blurred black rounded rect
   (`lib/new-thread-background.ts:161-176`), with:
   - hole rect = the cleared rect **expanded by clearance 8**, corner radius
     `26 + 8 = 34` (`heroCutoutHole`, `lib/new-thread-background.ts:132-145`);
   - `featherSigma = feather / 2.563` (`lib/new-thread-background.ts:148-150`).

   The desktop's per-pixel mask (`new_thread_background_mask.rs:12-47`, the
   gpui `ImageAlphaMask` shader, per ticket 15 §2.8):
   ```
   d    = rounded-rect SDF of the CLEARED rect (radius 26)
   alpha= smoothstep(0, feather, d − clearance)     // 0 for d ≤ 8,
                                                    // ramp over d ∈ [8, 8+feather]
   alpha= min(alpha, smoothstep(0, hero_height, hero.bottom − y))
   ```
   with `feather = clamp(hero_height · 0.52, 120, 280)` (mask.rs:36-40) —
   **120–280 px wide**. Consequences of the Gaussian substitution:

   | quantity | desktop | web |
   |---|---|---|
   | hard-transparent region | cleared rect + 8 px out | ≈ expanded rect eroded by ~2σ (≈ 8 − 0.78·feather) — **smaller** |
   | ramp midpoint (50 % alpha) | 8 + feather/2 px out (≈ 148 px at feather 280) | 8 px out (the expanded rect's edge) |
   | ramp shape | smoothstep (compact support, ends at 8+feather) | Gaussian (symmetric ±2σ tails) |

   So the web's hole is a tight symmetric blur hugging the pill, while the
   desktop's is a hard 8 px margin followed by an enormous one-sided gradient
   dome — the dominant "gradient feels off" mechanism. Blessed by ticket 15
   deviation 5 (15-new-thread-route.md:807) but visibly divergent.

3. **Mask compositing is multiply, not min.** The web composes the bottom fade
   and the hole by mask multiplication (fade mask on the cutout layer × hole
   mask on the image child, `components/new-thread-background.tsx:166-171` +
   `app.css:2546-2548, 2595-2601`); the shader takes `min(hole_alpha,
   bottom_fade_alpha)` (ticket 15 §2.8). Mid-ramp `a·b < min(a,b)` — the hole's
   edges go slightly darker than the desktop near the hero's bottom.

4. **The bottom fade itself MATCHES.** `BOTTOM_FADE_GRADIENT`
   (`lib/new-thread-background.ts:184-185`) = smoothstep stops
   1 / 0.156 / 0.5 / 0.844 / 0 at 0/25/50/75/100 % — exactly the desktop's
   `bottom_fade = (hero.bottom, max(height, 1))` (mask.rs:45, asserted by
   `new_thread_main_fade_uses_the_full_height_at_every_window_size`,
   mask.rs:224-234). Geometry matches too: height `min(vh·0.72, 760)`
   (`new-thread-background.ts:62-64` = shell.rs:852-855), two passes at
   reveal 0.5 / cutout 1 (`app.css:2542-2548` = mask.rs:8), cover-fit
   center-crop (`app.css:2537-2538` = mask.rs:59-73), full-canvas width.

5. **The pill's 16 px backdrop blur is absent.** On the desktop the pill is
   `frost::frosted(surface_radius, 16.0, body)` (composer.rs:7879; frost.rs:27-33)
   with `bg theme.composer_sidebar_tint()` when frosted (composer.rs:7714) — the
   "window into the artwork" look (prior research hero-context-meter.md §1.2:
   "The artwork itself is not blurred"). Forced-opaque web has no backdrop blur
   behind the pill — again deviation 6 fallout.

### (b) Desktop reference — the hero's exact values

| item | value | source |
|---|---|---|
| hero opacity (frosted) | **0.84** | `NEW_THREAD_BACKGROUND_FROSTED_OPACITY`, shell.rs:697; fn shell.rs:844-850 |
| hero opacity (opaque) | 1.0 | shell.rs:844-850 |
| hero element opacity | `(1 − dissolve) × artwork_readiness × bg_opacity(is_frost)` | shell.rs:880, 5860-5864, 5893 |
| height | `min(max(vh,0) × 0.72, 760)` | shell.rs:852-855, 698-699; tests 8276-8281 |
| width | `max(viewport − sidebar_now(), 0)` — sidebar_now is the ANIMATED width | shell.rs:5890 |
| position | `absolute; top 0; left 0; overflow hidden` inside `#chat-dropzone` (no titlebar pad on the chat route) | shell.rs:873-879, 5990-5997 |
| mount condition | `!has_selection || dock_frame.active` — decided with the frame ticked IN THE SAME RENDER | shell.rs:5883 |
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

### (c) Root cause

Two sanctioned approximations (deviations 5+6) plus one unsanctioned detail:
the Gaussian feather anchors its 50 % point at the pill's 8 px margin instead of
half a feather out, and multiply-vs-`min` compositing — together with the forced
1.0 (vs 0.84 frosted) hero opacity and the missing 16 px pill backdrop blur,
the hero reads "off" next to a frosted desktop.

### (d) Gap rows

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| Hero feather ramp | WRONG SHAPE | smoothstep over [8, 8+feather] from the cleared rect, feather 120–280 (mask.rs:36-40) | Gaussian σ=feather/2.563 centered on the rect expanded by 8, radius 34 (new-thread-background.ts:132-150) | render the hole as an SVG radial/linear smoothstep ramp (or canvas alpha mask) anchored 8 px out with the full feather width; keep σ only as a reduced-motion fallback |
| Hard-transparent margin | WRONG VALUE | alpha 0 for d ≤ 8 (clearance) | eroded ≈ 8 − 2σ (Gaussian tails) | same — hard 8 px ring at radius 26 before the ramp |
| fade × hole compositing | WRONG OP | `min(hole, fade)` | multiply (element masks) | approximate min by taking the per-stop min in one combined mask (or accept, note in ticket) |
| hero overall opacity | KNOWN DEVIATION | 0.84 under frost × readiness (shell.rs:5893) | forced 1.0 (new-thread-background.tsx:77-81) | decision item: either honor `--rb-surface` frost when it exists, or record as accepted (deviation 6) |
| pill backdrop blur | KNOWN DEVIATION | `frosted(26−4·amount, 16)` + composer tint (composer.rs:7879, 7714) | none (opaque pill) | tied to the same frost decision |
| bottom fade gradient | MATCHES | mask.rs:42-46 | BOTTOM_FADE_GRADIENT (new-thread-background.ts:184-185) | none |

---

## S2 — "the background effect dont seem to work like the desktop app"

### (a) Web defect trace

- The effect is threaded (`data-effect` on the hero,
  `components/new-thread-background.tsx:177`) but rendered as CSS stand-ins:
  `app.css:2550-2586` —
  - `scanlines` = `repeating-linear-gradient(to bottom, rgb(0 0 0 / 0.16) 0 1px, transparent 1px 3px)` overlay;
  - `dither` = `filter: contrast(1.4) saturate(0.7)`;
  - `halftone` = `filter: contrast(1.25) brightness(1.05) grayscale(0.25)`;
  - `ascii` = `filter: grayscale(1) contrast(1.6) brightness(1.1)`.
  The CSS comment itself flags the scope cut: "dither/ascii/halftone ship as
  CSS-filter stand-ins evoking the palette/dot/character reduction — flagged in
  the ticket's Comments as the follow-up a canvas port owes" (app.css:2551-2558;
  ticket 15 deviation 4, 15-new-thread-route.md:806). None of these are the
  desktop's transforms; `scanlines` is closest but its 1px/3px 0.16-black stripes
  ≠ the desktop's per-channel gain on every 3rd row.
- The effects are **static rasters on the desktop — there is no animated
  background effect.** `new_thread_background_effects.rs` has no time-varying
  code; the only time-based piece in the whole hero is the 120 ms readiness fade
  (effects.rs:11-33). A ticket must not invent animation.
- The appearance-dependent variants (light/dark paper) are not keyed on the web —
  the desktop rasters one variant per `(effect, light)` except Dither/None
  (effects.rs:53-61).

### (b) Desktop reference — the four rasters, verbatim

Common pipeline (`effects.rs`):
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

**Dither** (effects.rs:81-83, 200-221, 303-313):
- 2×2 cells, stepped by 2; one quantize per cell sampled at the cover-fit
  center `(x+1, y+1)`;
- Bayer 4×4: `[[0,8,2,10],[12,4,14,6],[3,11,1,9],[15,7,13,5]]` indexed by
  `BAYER[y/2 % 4][x/2 % 4]`;
- `dither_color([r,g,b,a], threshold)` (effects.rs:303-313):
  `peak = max(r,g,b)`; `bright = peak/255 > (threshold+0.5)/16`;
  `gain = bright ? 255/peak : 0.08`; output `round(c·gain)`, alpha kept;
- the 2×2 cell is filled with the quantized color.

**Halftone** (effects.rs:84-86, 165-198):
- 4×4 cells; `paper = light ? 255 : 0`;
- per cell: `luma = cover_sample(x, y)` (inverted when light);
  `radius = 2.0 · (0.3 + 0.7 · sqrt(luma/255))`;
  dot color from `cover_index(x+2, y+2)`; per sub-pixel
  `distance = hypot(dx−1.5, dy−1.5)`,
  `coverage = clamp(radius + 0.5 − distance, 0, 1) · alpha/255`;
  `blend = source·0.60 + (dot·coverage + paper·(1−coverage))·0.40`.

**Ascii** (effects.rs:87, 123-164):
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

**Scanlines** (effects.rs:88, 109-122):
- `gain = y % 3 == 0 ? 0.52 : 1.0`;
- light: `v + (255−v)·(1−gain)`; dark: `v·gain`; alpha preserved.

Cover-fit sampling (`cover_index`, effects.rs:227-240): the same
`max(w/src_w, h/src_h)` scale + centered crop as the hero paint, clamped to the
source bounds.

### (c) Root cause

The web never ported the rasters — CSS filters stand in (ticket 15 deviation 4,
later softened by ticket 28's CSS scope cut at app.css:2550-2586). A faithful
port is a `canvas`/ImageData implementation of the four per-pixel transforms
above (they are pure functions of the ≤2048px source and the appearance).

### (d) Gap rows

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| dither | MISSING | Bayer 4×4 2×2-cell quantize, gain 255/peak or 0.08 (effects.rs:200-221, 303-313) | `contrast(1.4) saturate(0.7)` (app.css:2573-2576) | ImageData port of `dither_pixels` + `dither_color` |
| halftone | MISSING | 4×4 cells, radius 2·(0.3+0.7·√luma), 60/40 blend (effects.rs:165-198) | `contrast(1.25) brightness(1.05) grayscale(0.25)` (app.css:2578-2581) | ImageData port of `halftone_pixels` |
| ascii | MISSING | 6×8 glyph cells, √-indexed 10-glyph table, 60/40 mix (effects.rs:123-164) | `grayscale(1) contrast(1.6) brightness(1.1)` (app.css:2583-2586) | ImageData port of `ascii_pixels` |
| scanlines | WRONG VALUES | every 3rd row gain 0.52 (dark) / toward-white (light), per-channel (effects.rs:109-122) | 1px black 0.16 every 3px overlay (app.css:2560-2571) | ImageData port of `scanline_pixels` |
| appearance variants | MISSING | light/dark paper per effect; Dither/None appearance-independent (effects.rs:53-61) | one variant, no light/dark key | key the raster on resolved appearance; re-raster on change |
| one-pending-job | MISSING | single in-flight raster per (effect, light) (effects.rs:65-67) | n/a | memoize the promise while rasterizing |
| effect is animated? | N/A | static rasters; only the 120 ms readiness fade is time-based (effects.rs:11-33) | — | do NOT invent animation |

---

## S3 — "'New thread composer background' option dont have icon?"

### (a) Web defect trace

- The row: `routes/settings-appearance.tsx:230-241` — when no background is
  available it renders `<RowTile icon="fileImage" />` (:236); when one is, the
  36 px image thumb (:231-234). `RowTile` (`components/settings-widgets.tsx:31-37`)
  renders `<Icon name="fileImage" size={16}/>` in the 36×10px tile
  (`app.css:10200-10214`, matching `widgets.rs:237-253`).
- **The `fileImage` glyph paints nothing.** The generated asset
  (`web/packages/icons/src/generated/index.ts:51`) is
  `fill: "none"` with a body whose `<path>`s carry **no stroke attributes**:
  `<path d="M14 2H7a3 3…"/><path d="M14 2v6h6M5 20l5-6…"/><circle …/>`.
  The `Icon` component sets only `fill={asset.fill}` and no stroke
  (`web/packages/icons/src/index.tsx:34-53`). Result: `fill:none` + no stroke =
  invisible — the icon is in the DOM but paints nothing.
- Cause: the generator's `parse()` keeps only `viewBox` and `fill` from the root
  `<svg>` and drops `stroke`, `stroke-width`, `stroke-linecap`,
  `stroke-linejoin` (`web/packages/icons/scripts/generate.mjs:26-38`). Most
  desktop assets repeat `stroke="currentColor"` per-path so they survive; the
  file-type family carries stroke **only at the root**. Verified broken set
  (generated body contains no `stroke`, root `fill:"none"`):
  **`fileCode`, `fileData`, `fileImage`, `fileMarkdown`, `fileStyle`**
  (generated/index.ts:50-53, 51; sources `crates/ui/assets/icons/file-*.svg`,
  e.g. `file-image.svg`: `<svg … fill="none" stroke="currentColor"
  stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">`).
  Usages: `fileImage` settings-appearance.tsx:236; `fileCode`
  `components/files/file-viewer.tsx:232`.
- Also minor: the available-state thumb's `img` is 34×34 with `margin: 1px`
  inside a 36px border-box tile → 34+2 overflows the 34px content box
  (`app.css:10264-10280`); the desktop puts the 34px img in the 36px box with
  no margin (appearance.rs:2139-2152).

### (b) Desktop reference — the row, verbatim

| element | spec | source |
|---|---|---|
| tile (background set AND file exists) | 36 px box, radius 10, overflow hidden, `1px hairline(0.10)` border, `img 34 px` radius 9 `object-fit Cover` | settings/appearance.rs:2133-2152 |
| tile (unset / unavailable) | `widgets::row_tile(theme, icons::FILE_IMAGE)` | appearance.rs:2154; icons.rs:147 (`"file-image"`); widgets.rs:237-253 |
| row tile | 36×36, radius 10, 1px `theme.border`, `ink(0.03)`, centered 16px icon `theme.text_muted` | widgets.rs:235-253 |
| row title | `"New thread composer background"` | appearance.rs:2184 |
| meta (available) | `{name}` · `"Softened automatically on frosted themes."` | appearance.rs:2156-2164 |
| meta (unavailable) | `"Image unavailable"` · `"Choose a replacement or remove it."` | appearance.rs:2165-2170 |
| meta (unset) | `"Add an image behind the composer on empty new threads."` | appearance.rs:2171-2175 |
| actions | installed → `"Replace image"` + `"Remove"` (danger); else `"Choose image"`; gap 6, ml 10 | appearance.rs:2187-2229 |
| effect row (only when available) | `row_tile(TUNING)`, title `"Background effect"`, `NewThreadBackgroundEffect::ALL` choices | appearance.rs:2233-2262; icons.rs:129 |

### (c) Root cause

`generate.mjs parse()` drops the root SVG stroke attributes; the `file-*`
asset family (stroke only at the root) renders invisible. The row's logic is
otherwise a faithful port.

### (d) Gap rows

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| `fileImage` glyph | BROKEN | root-stroked asset, 1.5 stroke (crates/ui/assets/icons/file-image.svg) | generated body has no stroke; Icon sets no stroke (generated/index.ts:51; icons/index.tsx:34-53) | generator: carry root `stroke*` attrs onto every path (or emit them on the `<svg>`); regenerate; add a check that every asset's root paints |
| `fileCode` glyph | BROKEN | same | generated/index.ts:50 | same (blast radius: files/file-viewer.tsx:232) |
| `fileData`/`fileMarkdown`/`fileStyle` | BROKEN (latent) | same | generated/index.ts:52-53 | same |
| thumb inner layout | WRONG VALUE | 34px img, no margin, in 36px bordered box (appearance.rs:2141-2150) | 34px img + 1px margin (app.css:10274-10280) | drop the margin; center via flex |

---

## S4 — "closing/opening sidebar makes the new page snapping, not sliding smoothly; no background on the top left nav when the sidebar is closed"

### (a) Web defect trace — (a) the snap

1. **The hero's width snaps.** `heroWidth = Math.max(viewport − sidebarNow, 0)`
   (`routes/chat-page.tsx:566`) where `sidebarNow = sidebarTarget(sidebar)`
   (:431) — `sidebarTarget` returns the **target** width
   (`collapsed ? 0 : width`, `state/layout.ts:424-426`), not an animated one.
   The hero's inline `width` (`components/new-thread-background.tsx:178`) has
   no transition (`app.css:2495-2501`) → on toggle the artwork's box jumps to
   the new width in one frame while `.sidebar`'s CSS width glides 200 ms
   (`app.css:1159`, `--rb-motion-resize`).
2. **The cutout hole goes stale for the whole 200 ms.** The mask regenerates
   per React commit (rAF-scheduled) and on `#composer-surface` **resize** only
   (`components/new-thread-background.tsx:109-160`; `ResizeObserver` at
   :148-155). During the pure-CSS sidebar transition there are **no React
   commits** (nothing re-renders: the bottom stack's height, the settings, the
   viewport all hold) and the composer's size does not change (width capped at
   768) → the hole stays at the pill's old rect while the pill —
   `.persistent-composer`, `margin-inline: auto` (`app.css:2480-2485`) — glides
   with the column. The pill visibly slides out of its hole; the hole catches
   up at the next unrelated commit.
3. The composer itself does glide (its wrapper is flex-centered, transform dx=0
   at rest), so the visible artifact is the artwork box + hole snapping around
   a sliding pill.

Desktop: `hero_width = (viewport_width − sidebar_now()).max(0)`
(shell.rs:5890) where `sidebar_now() = eval_tween(sidebar_tween, sidebar_target())
+ edge bounce` (shell.rs:3790-3794) — the **tweened** width, re-evaluated every
frame (`eval_tween` sets `motion_active` → rAF, shell.rs:3753-3767) → the hero
box glides; and the mask consumes the same frame's composer bounds "including
on sidebar resize" (mask.rs:49-51; test `background_paint_sees_same_frame_composer_bounds_even_when_painted_first`,
mask.rs:91-177).

**Sidebar collapse motion (desktop, verbatim):** `toggle_sidebar`
(shell.rs:1947-1957) captures `from = sidebar_now()` (a mid-animation reversal
starts from what is painted), clears bounce/edge state, flips
`settings.sidebar_collapsed`, arms `WidthTween::new(from, sidebar_target())` —
a oneshot evaluated from render (`eval_tween`, shell.rs:3753-3767): 200 ms
`RESIZE` = `EASE_OUT` = cubic-bezier(0, 0, 0.58, 1) (proto/motion.rs:302, 217),
reduced motion → target. From→to: `sidebar_target()` = `collapsed ? 0 :
settings.sidebar_width` (0 ↔ the dragged width, clamped 224–400 by
settings.rs:30-32). The web's CSS `transition: width var(--rb-motion-resize)
var(--rb-ease-ease-out)` on `.sidebar` (app.css:1159) is this exact curve — the
missing half is feeding the animated value to the hero and the mask.

### (a) Web defect trace — (b) the missing top-left background

- The web titlebar is transparent with no fill (`app.css:292-316`, comment
  "NO FILL, NO BLUR, NO BORDER") and the cluster has no backing panel
  (`app.css:323-333`). With the sidebar open, the cluster sits over the sidebar
  tone (`wash(0.05)` + hairline, `app.css:1142-1158` ≈ desktop `sidebar_tone`
  shell.rs:8026-8042). With the sidebar closed on the new-thread canvas, the
  cluster sits directly over the raw hero artwork.
- The desktop shows a **frosted island** exactly there: `render_titlebar_cluster`
  (shell.rs:3973-4088) computes

  ```
  island_target = 1.0 iff  Route::Chat
                          && selected_chat.is_none()          // the new-thread canvas
                          && settings.sidebar_collapsed
                          && new_thread_composer_background is Some
                             && its path is_file()             // installed, not the bundled default
  ```

  (shell.rs:3983-3994) — i.e. precisely the user's state (new chat page, sidebar
  closed, a background image installed). The island:

  | property | value | source |
  |---|---|---|
  | wrapper | `absolute; left 6; right 0; top island_top; h island_height; opacity island` inside the cluster row | shell.rs:4017-4024 |
  | vertical geometry | `height = 28 + 4·progress` (28→32), `center = (TITLEBAR_HEIGHT + TITLEBAR_TOP_PAD)·0.5 = 21`, `top = center − height/2` | `titlebar_island_vertical_geometry`, shell.rs:829-835 |
  | content | `frost::frosted(12.0, 20.0, div().size_full().rounded(12).bg(theme.glass_overlay()).shadow_sm())` — 12 px radius, **20 px** backdrop blur, glass-overlay tint, small shadow | shell.rs:4025-4035; frost.rs:27-33, 85-96 |
  | motion | a persistent manual `WidthTween` (200 ms RESIZE ease-out), reversal from the painted value, reduced motion snaps; initial presentation settled | shell.rs:3995-4005, 3753-3767 |
  | test | `island_stays_centered_on_controls_while_expanding` (height ∈ [28,32], center constant 21, 4 px of air around the 24 px controls) | shell.rs:8251-8263 |

- `grep island` over `web/packages/app/src` → **no hits**. The web never ported
  it (it postdates ticket 06's research; ticket 06 §2.2 child 1 said "Frosted
  island (desktop-only in practice — see §5)" — that judgment is now wrong for
  the new-thread canvas with a background, which is exactly the user's report).

### (c) Root cause

(a) The hero's width and hole mask are keyed on the sidebar **target** width and
per-commit remasking; nothing animates them across the CSS transition.
(b) The titlebar island (the desktop's frost panel behind the cluster on the
canvas with a collapsed sidebar and an installed background) was never ported.

### (d) Gap rows

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| hero width during sidebar toggle | MISSING | glides: `viewport − sidebar_now()` (tweened, per frame) (shell.rs:5890, 3790-3794) | snaps: `viewport − sidebarTarget()` (chat-page.tsx:431, 566) | run the 200 ms `evalWidthTween` (already ported, state/layout.ts:171-179) on a rAF when `collapsed` flips; set heroWidth from it each frame |
| hole mask during sidebar toggle | MISSING | same-frame composer bounds "including on sidebar resize" (mask.rs:49-51) | per-commit + resize-only observer (new-thread-background.tsx:109-160) | re-run `remask` every frame of the same rAF loop; observe the wrapper (position) or poll during motion |
| titlebar island | MISSING | frosted 12/20 glass panel, 28→32 px, target = canvas + collapsed + background installed (shell.rs:3983-4035, 829-835) | none (no `island` in web src) | port as an absolutely-positioned `backdrop-filter: blur(20px)` panel behind `.titlebar-cluster`, gated on the same four conditions, 200 ms ease-out opacity/height tween |
| island conditions | MISSING | installed background only, NOT the bundled default (shell.rs:3986-3989) | n/a | gate on `newThreadComposerBackground !== null && resolves` |
| sidebar toggle curve | MATCHES | 200 ms `EASE_OUT` from painted width (shell.rs:1947-1957; proto/motion.rs:302, 217) | CSS width transition, same curve (app.css:1159) | none |

---

## S5 — "the background flashing twice when I go from new chat page to an existing chat page"

### (a) Web defect trace — the mechanism, frame by frame

Navigation `/` → `/chat/$chatId` re-renders `ConversationPage` (same fiber,
router.tsx:27-28) with `hasSelection = true` but the **React-state** dock frame
still the settled canvas frame `{docked: false, active: false}`
(chat-page.tsx:446, 565):

1. **Frame 1 (the navigation render):** `heroVisible = (!hasSelection ||
   dockFrame.active)` = `(false || false)` = **false** → `<NewThreadCanvas>`
   unmounts (chat-page.tsx:565, 722-728). The tick that flips `active` runs in
   the layout effect *after* the render (chat-page.tsx:491-505); its
   `setDockFrameState` re-renders and **remounts** the hero.
2. **The remount destroys the artwork state.** `NewThreadCanvas` mounts fresh →
   `useNewThreadBackground` starts with `url = null` (state/appearance.ts:90) →
   `NewThreadBackground` renders **null** while resolving
   (components/new-thread-background.tsx:162-164) → the async
   `resolveNewThreadBackground` (IndexedDB read + object URL,
   lib/new-thread-background.ts:253-290, background-blob-store.ts:78-94)
   resolves a few painted frames later.
3. So the painted sequence is: **flash 1** — the background hard-vanishes at
   the transition start (hero absent); **flash 2** — it reappears and the
   readiness fade ramps 0→1 over 120 ms (new-thread-background.tsx:85-104;
   CSS app.css:2510-2523) *while the dock's `dissolve` channel is already
   fading the hero out* (docking window 0.06→0.88) — two opposing fades on one
   element; then the intended dissolve finishes.
4. **Amplifier — a new object URL per resolution:** `resolveNewThreadBackground`
   default-constructs a **new** `idbBackgroundBlobStore()` per call
   (lib/new-thread-background.ts:256-257), and each store instance caches its
   own `cachedUrl` (background-blob-store.ts:61-94) → every mount mints a
   *different* blob: URL → `artwork.id` changes → the keyed readiness wrapper
   remounts and the 120 ms fade restarts even for the same image (the desktop's
   `Readiness` restarts **only when the image id changes**, effects.rs:22-24).
   Also an unrevoked-object-URL leak.

Ruled out for this navigation:
- `page-fade` keyed on phase (root-layout.tsx:80) — `phase` stays `"ready"`
  across `/` ↔ `/chat/$id`; the keyed remount fires only in the pairing flow
  (ticket 32, root-layout.tsx:53, pair-page.tsx:31-48). Not S5.
- `transcriptGeometryReady` (chat-page.tsx:466, 582) hides the transcript for
  one commit — intended guard, affects content not the background.
- CSS animation restart — only the readiness transition restarts (covered).

### (b) Desktop reference — background continuity across the route change

The desktop **never drops the layer and never re-resolves the artwork**:

| rule | value | source |
|---|---|---|
| layer mount | `(!has_selection \|\| dock_frame.active)` — decided with the frame ticked in the SAME render (`tick` at 5865-5868 precedes the layer at 5883) | shell.rs:5865-5895 |
| artwork prewarm | decode + effect run on BOTH routes, "not contingent on a hero measurement or a navigation gesture" | shell.rs:5846-5859; effects.rs:292-302 |
| readiness owner | `Shell::new_thread_artwork_ready` — lives on the shell, survives every route change; restarts only on a NEW image id | shell.rs:1032, 5860-5864; effects.rs:11-33 |
| hero fade | element opacity `(1 − dissolve) × artwork_readiness × bg_opacity` — one number per frame | shell.rs:880, 5893 |
| rAF while fading | `if artwork.is_some() && artwork_opacity < 1.0 { window.request_animation_frame() }` | shell.rs:5884-5886 |
| readiness test | `cold_artwork_fades_in_once_and_warm_navigation_does_not_restart_it` (0.5 at 60 ms; same id → 1; new id → 0; reduced → 1) | effects.rs:319-336 |

Dock choreography state machine (both S5/S6 reference; `composer_dock.rs`):

| element | spec | source |
|---|---|---|
| glide | critically damped, `ω = 12/duration`, durations 0.420 s dock / 0.470 s undock, settle 0.0005 / 0.005; position AND velocity survive retarget | composer_dock.rs:23-55; proto/motion.rs:476-484 |
| phase clock | `tick(docked, reduced, now)`; a route flip resets dt to 0 (click-after-idle is the start of motion, not elapsed time) and captures the previous visuals as the choreography `from` | composer_dock.rs:243-267 |
| four channels (docking) | transcript 0.20→0.65, selectors 0.55→0.78, footer 0.78→1.00, dissolve 0.06→0.88 | composer_dock.rs:117-123 |
| four channels (undocking) | transcript 0.00→0.25, selectors 0.50→0.95, footer 0.00→0.18, dissolve 0.08→0.85 | composer_dock.rs:127-132 |
| active flag | `phase.active() \|\| choreography.is_some()` — the hero layer holds until both end | composer_dock.rs:304-309 |
| composer anchor | x = slot left; y = `bounds.top` docked / `(vh − h)·0.5 + 8` hero (top-anchored) | composer_dock.rs:377-385 |
| width | `layout_width` glides on the same clock; snaps inside the handoff's invisible interval (p ≥ 0.22) | composer_dock.rs:220-241 |
| pill height | `dock_height(amount)` = lerp(hero, session, amount); shell reserves the DESTINATION footprint via `dock_clearance_correction` | composer.rs:7489-7500, 4159; shell.rs:6084-6101 |
| morph suppression | `set_dock_frame` (active) kills the composer's own flip+height morphs — one clock owns the height | composer.rs:4140-4149 |
| radius | `COMPOSER_RADIUS − 4·amount` = 26 → 22 | composer.rs:7603 |
| transcript | `opacity = transcript()`, `top: 8·(1 − transcript())`, hidden until `transcript_geometry_ready` | shell.rs:5911-5920 |
| reduced motion | glide, channels, handoff, readiness all snap; handoff disabled (opacity 1) | composer_dock.rs:246-248, 250-251; panel_handoff.rs:32-35 |

### (c) Root cause

The hero's mount decision reads a one-render-stale React dock frame
(`dockFrame.active`) instead of the freshly ticked frame, and the artwork
resolution + readiness state live *inside* the hero component (unmounted on
every route change) instead of at shell scope with prewarm. Plus the
per-resolution object URL restarts the readiness fade.

### (d) Gap rows

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| hero layer never drops across a route change | BROKEN | layer decided with the same-render tick (shell.rs:5865-5895) | `heroVisible` from stale state → 1-render unmount→remount (chat-page.tsx:446, 565, 722-728) | compute the first navigation render's visibility from the mutable dock (`dockRef.current.frame`) or tick before render (router-subscription render path), so the hero never unmounts mid-transition |
| artwork resolved at shell scope | BROKEN | prewarmed on both routes (shell.rs:5846-5859) | resolved per hero mount, async, paints null (appearance.ts:86-105; new-thread-background.tsx:162-164) | hoist `useNewThreadBackground` to `ConversationPage`/app scope (or a module store keyed on the setting) so the url survives route changes |
| readiness survives navigation | BROKEN | shell-owned Readiness; same id never re-fades (effects.rs:11-33; test 319-336) | remount resets `ready` → 120 ms fade replays (new-thread-background.tsx:85-104) | keep the Readiness clock outside the unmounted subtree (module/store), feed `data-ready` from it |
| blob URL identity | BROKEN | image id stable per artwork | new store + new object URL per resolve → id changes every mount (new-thread-background.ts:256-257; background-blob-store.ts:61-94) | module-level singleton store; keep one URL per blob revision |
| double fade (readiness × dissolve) | BROKEN | one opacity product (shell.rs:880, 5893) | readiness fade-in fights the dissolve fade-out | resolved by the three rows above |

---

## S6 — "the transition between new chat page to a chat that has a sidebar should be different — look at all the cases from the desktop app, port 1:1"

("a chat that has a sidebar" = a chat whose right pane has stored-open tabs.)

### (a) Web defect trace

The handoff **never arms on the web** for the flagship case. The arming input
differs:

| | desktop | web |
|---|---|---|
| call | `observe_pane(selected_chat.is_some(), right_target_width, on_chat && !reduced, render_time)` (shell.rs:7883-7889) | `dock.observePane(hasSelection, columnWidth ?? 0, !dockReduced, nowMs)` (chat-page.tsx:497) |
| width input | `right_now(cx)` — the pane's width **computed synchronously from state** (`eval_tween(right_tween, right_target)`, shell.rs:3796-3802, 1929-1945) | `columnWidth` — a **ResizeObserver-measured** value (chat-page.tsx:462-481), stale at the navigation commit |

Sequence for new-thread → chat-with-pane (web):
1. navigation commit: `observePane(true, W_canvas)` with `previous = (false,
   W_canvas)` → docked differs but width equal and no handoff running → **no
   arm** (composer-dock.ts:270-277);
2. the ResizeObserver fires with the narrowed width → next commit:
   `observePane(true, W_new)` → `previous = (true, W_canvas)` → docked equal →
   **no arm** — ever.

Consequences (all verified in code):
- no 0.320 s fade-through: the composer's inline `opacity:
   dockRef.current.opacity()` stays 1 (chat-page.tsx:780);
- no invisible interval → `layoutWidth` **glides** (composer-dock.ts:389-410,
   else-branch) from the 768 cap toward the narrower column while the chat
   column itself has already snapped (the pane column mounts at full width,
   right-pane.tsx:117-141 — a fresh element has nothing to transition from) →
   the 768 px composer **overflows the narrowed column** and is covered by the
   pane for ~0.42 s (`.main` clips only under `.shell-pane-gliding`/takeover,
   app.css:1930-1933; the pane is later in DOM and paints over it);
- no 12 px travel offset (composer-dock.ts:502-511 dead in practice);
- no `panelDeparture` fast dissolve (composer-dock.ts:448-454 — requires
   `pane.progress` set at the tick; it never is);
- `transcriptWidth` retention is **never wired** (the method exists,
   composer-dock.ts:358-367; grep: zero call sites) → the departing transcript
   on the reverse trip reflows into the canvas width while fading — the exact
   "exit flash" the desktop's retention exists to prevent
   (composer_dock.rs:193-204);
- chat→chat: no fade (matches the desktop,
   `ordinary_resizing_and_same_column_navigation_do_not_fade`,
   panel_handoff.rs:92-98) — but the transcript swaps through a **fresh
   `TranscriptStore` per chatId** (chat-page.tsx:183-192) with an async
   first load → a blank-then-rows content flash where the desktop's single
   `Transcript` entity swaps its doc synchronously (shell.rs:1826-1856,
   5911-5921);
- extra divergence the other way: chat→chat where the destination chat's pane
   flag differs — the web **animates** the pane close (the `.right-pane` element
   persists, inline width → 0 rides the 200 ms CSS transition, right-pane.tsx:120
   + app.css pane transition) while the desktop **snaps**: the chat-switch path
   clears `right_tween`/`right_takeover_content_tween`/`main_takeover_tween`
   with the comment "restore THAT chat's panel state (per-session open flags;
   **snap, no tween** — the panels belong to the destination chat)"
   (shell.rs:1820-1845).

### (b) Desktop reference — the transition matrix

Common to every case: the dock tick on the docked flip (shell.rs:5865-5868),
`set_dock_frame` kills the composer's own morphs (composer.rs:4140-4149), the
choreography captures the previous visuals (composer_dock.rs:263-266), and
`dock_clearance_correction` reserves the destination footprint
(composer.rs:4159; shell.rs:6084-6101).

| case | dock | panel handoff (0.320 s) | right pane | background (hero) | what the user sees |
|---|---|---|---|---|---|
| **A. new-thread → chat, NO pane** | tick(true): 0.420 s glide + docking channels (transcript 0.20→0.65, selectors 0.55→0.78, footer 0.78→1.00) | not armed (right_now 0→0, width equal) | absent (width 0) | dissolve 0.06→0.88 on the 0.420 clock | pill glides center→bottom (height via `dock_height`, radius 26→22), transcript rises in (8 px), hero dissolves |
| **B. new-thread → chat WITH stored-open pane** | tick(true) | **armed**: `observe_pane` sees the docked flip AND `right_now` 0→pane in the same frame (shell.rs:7883-7889) | **snaps** to the target (`right_tween = None` on chat switch, shell.rs:1842; container = `eval_tween(None, target)` = target, shell.rs:3826-3844) | `panel_departure`: dissolve ramps 0→1 over `stage(p, 0, 0.18)` of the 0.320 s clock — a FAST kill (composer_dock.rs:247, 284-289) | composer fades to 0 by p≈0.18 (panel_handoff.rs:18-22), geometry switches at p ≥ 0.22 **while invisible** (layout_width snaps, composer_dock.rs:230-234), fades back 0.26→1 with a **12 px** travel offset decaying over `stage(p, 0.22, 1)` (composer_dock.rs:399-406); the pane pops in but nothing visible reflows; the transcript fades in on the docking channel |
| **C. chat → chat (same column)** | no tick (docked stays true); nothing moves | never arms (panel_handoff.rs:36-41 needs the docked flip; test :92-98) | flags snap per destination chat (shell.rs:1820-1845) | none (hero unmounted) | the transcript content swaps in place — ONE `Transcript` entity, doc swapped synchronously from state (shell.rs:5911-5921) |
| **D. chat → new-thread, NO pane on source** | tick(false): 0.470 s glide + undocking channels (transcript 0.00→0.25, selectors 0.50→0.95, footer 0.00→0.18) | not armed (width 0→0) | absent | dissolve decays 0.08→0.85 → the hero fades IN | transcript fades out with its 8 px rise, hero unfolds, selectors return, composer glides up (anchored by the surface top) |
| **D'. chat WITH pane → new-thread** | tick(false) | **armed** (docked flip + width pane→0) | snaps closed (shell.rs:1842) | `panel_return`: the short 0.320 s clock — `return_from_panel` visuals: transcript+footer decay `stage(t, 0, 0.18)`, selectors rise `stage(t, 0.26, 0.85)`, dissolve decays `stage(t, 0.26, 0.80)` (composer_dock.rs:136-147, 279-283); `amount` held at the painted value until p ≥ 0.22 then 0 (composer_dock.rs:294-303) | the departing transcript is retained at the SOURCE column width until the handoff ends (`transcript_width`, composer_dock.rs:195-204, shell.rs:7898-7903, 5915) and covered by an occluding veil (shell.rs:5925-5927); the composer carries an **8 px** travel |
| **reversal mid-flight** (either direction) | glide preserves position+velocity (composer_dock.rs:38-49; test :655-666) | reversal preserves the current opacity (panel_handoff.rs:36-41; test :77-89) | — | — | continuous — no restart-from-zero |
| **reduced motion** | everything snaps (composer_dock.rs:250-251, 680-686) | disabled — `enabled=false` resets to opacity 1 (panel_handoff.rs:32-35) | snaps | readiness snaps to 1 (effects.rs:25-27) | instant state swap |

Background handoff per case is the `dissolve` channel above; the hero layer is
held by `dock_frame.active` (shell.rs:5883) so it never drops mid-case (see S5).

### (c) Root cause

(1) The handoff's width input is a measured-async value instead of the
synchronous pane width, so the flagship case never arms and the web substitutes
a width *glide* that overflows the snapped column; (2) the hero mount/artwork
state is per-component (S5 — poisons cases A/D too); (3) `transcriptWidth`
retention exists but is unwired; (4) chat→chat swaps transcript content through
an async fresh store; (5) one inverted case — the web animates a pane flag
change the desktop deliberately snaps.

### (d) Gap rows

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| handoff width input | BROKEN | synchronous `right_now(cx)` (shell.rs:7883-7889, 3796-3802) | measured `columnWidth`, one commit stale (chat-page.tsx:497, 462-481) | feed the computed pane width (`paneWidth` from app-shell scope, or `viewport − sidebar − columnWidth` computed synchronously) into `observePane` |
| composer fade-through | MISSING | opacity 1→0→1 over 0.320 s, geometry at p ≥ 0.22 (panel_handoff.rs:18-22; composer_dock.rs:399-406) | opacity stays 1 (chat-page.tsx:780) | falls out of the row above — the ported `PanelHandoff` already computes it |
| width snap in invisible interval | MISSING | `layout_width` snaps at p ≥ 0.22 (composer_dock.rs:230-234) | width glides 768→narrow, composer overflows/covered ~0.42 s (composer-dock.ts:389-410; app.css:1930-1933) | ditto — handoff progress gates the snap |
| 12/8 px travel | MISSING | 12 docking / 8 undocking, decaying `stage(p, 0.22, 1)` (composer_dock.rs:399-406) | dead code (composer-dock.ts:502-511) | ditto |
| panel_departure fast dissolve | MISSING | dissolve 0→1 over `stage(p, 0, 0.18)` (composer_dock.rs:247, 284-289) | never triggers (composer-dock.ts:448-454 needs pane.progress) | ditto |
| pane column on route change | MATCHES (snap) | snaps — `right_tween = None` on chat switch (shell.rs:1820-1845) | mounts at full width (right-pane.tsx:117-141) | none (the handoff covers it on the desktop) |
| departing transcript width retention | MISSING | `transcript_width` holds the source column until the handoff ends (composer_dock.rs:195-204; shell.rs:7898-7903) | method ported but never called (composer-dock.ts:358-367; no call sites) | call it from the page: fix `.chat-body`'s width to the retained value while `departing` |
| chat→chat transcript swap | DIVERGENT | one entity, synchronous doc swap (shell.rs:5911-5921) | fresh `TranscriptStore` + async load → blank flash (chat-page.tsx:183-192) | keep the previous rows mounted (crossfade or cache-first paint) until the new store's first frame |
| chat→chat pane flag change | INVERTED | snap, no tween (shell.rs:1820-1845) | 200 ms CSS close glide (right-pane.tsx:120) | suppress the transition when the pane key (chatId) changed |
| hero continuity (cases A/D) | BROKEN | see S5 | see S5 | see S5 rows |

---

## Consolidated gap table

| # | item | kind | desktop (file:line) | web (file:line) | fix |
|---|---|---|---|---|---|
| G1 | hero feather ramp shape/anchor | wrong | smoothstep [8, 8+feather], feather 120–280 (mask.rs:36-40) | Gaussian σ=feather/2.563 at +8 (new-thread-background.ts:132-150) | smoothstep-anchored mask |
| G2 | fade × hole compositing op | wrong | `min` (ticket 15 §2.8) | multiply (new-thread-background.tsx:166-171) | combined single mask |
| G3 | hero opacity under frost | deviation | 0.84 (shell.rs:697, 5893) | forced 1.0 (new-thread-background.tsx:77-81) | decision item (deviation 6) |
| G4 | pill 16 px backdrop blur | deviation | composer.rs:7879 | absent | tied to G3 |
| G5 | dither raster | missing | effects.rs:200-221, 303-313 | CSS filter (app.css:2573-2576) | ImageData port |
| G6 | halftone raster | missing | effects.rs:165-198 | CSS filter (app.css:2578-2581) | ImageData port |
| G7 | ascii raster | missing | effects.rs:123-164 | CSS filter (app.css:2583-2586) | ImageData port |
| G8 | scanlines raster | wrong values | effects.rs:109-122 | 1px/3px 0.16 overlay (app.css:2560-2571) | ImageData port |
| G9 | effect appearance variants | missing | (effect, light) keying (effects.rs:53-61) | none | key on resolved appearance |
| G10 | `fileImage` glyph paints | broken | root-stroked asset (assets/icons/file-image.svg) | no stroke in body/Icon (generated/index.ts:51; icons/index.tsx:34-53; generate.mjs:26-38) | generator carries root stroke attrs; regenerate |
| G11 | fileCode/fileData/fileMarkdown/fileStyle | broken (latent) | same | generated/index.ts:50-53 | same |
| G12 | background thumb inner box | wrong value | 34 px img, no margin (appearance.rs:2139-2152) | +1 px margin (app.css:10274-10280) | drop margin |
| G13 | hero width during sidebar toggle | missing | tweened `sidebar_now()` (shell.rs:5890, 3790-3794) | target width (chat-page.tsx:431, 566) | 200 ms rAF lerp (`evalWidthTween`) |
| G14 | hole mask during sidebar toggle | missing | same-frame bounds incl. sidebar resize (mask.rs:49-51) | per-commit + size-only observer (new-thread-background.tsx:109-160) | remask every frame of G13's loop |
| G15 | titlebar island | missing | frosted 12/20 panel, 28→32 px, 4 conditions (shell.rs:3983-4035, 829-835) | none | port behind `.titlebar-cluster` |
| G16 | hero layer drops 1 render on route change | broken | same-render tick (shell.rs:5865-5895) | stale `dockFrame.active` (chat-page.tsx:446, 565, 722-728) | read the mutable dock for first-render visibility |
| G17 | artwork resolved per mount | broken | prewarmed both routes (shell.rs:5846-5859) | per-mount async (appearance.ts:86-105) | hoist to page/shell scope |
| G18 | readiness restarts per mount | broken | shell-owned, id-keyed (effects.rs:11-33) | component state (new-thread-background.tsx:85-104) | store-scoped clock |
| G19 | blob URL identity/leak | broken | stable image id | new store + URL per resolve (new-thread-background.ts:256-257; background-blob-store.ts:61-94) | singleton store, stable URL |
| G20 | handoff arming input | broken | synchronous `right_now` (shell.rs:7883-7889) | measured stale `columnWidth` (chat-page.tsx:497) | computed pane width |
| G21 | composer fade-through / travel / fast dissolve / width snap | missing | panel_handoff.rs:18-22; composer_dock.rs:220-241, 284-289, 399-406 | dead paths (composer-dock.ts:389-410, 448-454, 502-511; chat-page.tsx:780) | falls out of G20 |
| G22 | departing transcript width retention | missing | composer_dock.rs:195-204; shell.rs:7898-7903 | unwired (composer-dock.ts:358-367) | call `transcriptWidth`, fix `.chat-body` width |
| G23 | chat→chat transcript swap | divergent | synchronous single entity (shell.rs:5911-5921) | fresh async store (chat-page.tsx:183-192) | keep old rows until first frame |
| G24 | chat→chat pane flag change | inverted | snap (shell.rs:1820-1845) | 200 ms glide (right-pane.tsx:120) | suppress transition on key change |

---

## Pure logic to port + desktop test names

1. **The four effect rasters** (`new_thread_background_effects.rs`):
   `dither_pixels` (:200-221) + `dither_color` (:303-313) + the BAYER table
   (:201), `halftone_pixels` (:165-198), `ascii_pixels` (:123-164) + GLYPHS
   (:126-137), `scanline_pixels` (:109-122), `cover_index` (:227-240).
   Web home: a `lib/new-thread-background-effects.ts` (pure, ImageData in/out)
   + an off-main-thread rasterizer (Worker/`createImageBitmap`). Desktop tests
   to mirror: `every_effect_is_generated_once_independently_of_viewport`
   (effects.rs:384), `light_treatments_use_light_paper_without_inverting_source_hues`
   (:423), `raster_treatments_preserve_source_dimensions_and_alpha` (:485),
   `appearance_changes_cache_both_variants_and_share_unchanged_dither` (:447),
   `prewarming_decodes_off_thread_and_reuses_artwork_without_hero_geometry`
   (:339).
2. **Titlebar island**: `island_target` rule (shell.rs:3983-3994) +
   `titlebar_island_vertical_geometry` (:829-835). Mirror test:
   `island_stays_centered_on_controls_while_expanding` (shell.rs:8252).
3. **Animated hero width / mask tracking across a sidebar toggle**:
   reuse `evalWidthTween` (state/layout.ts:171-179, already ported from
   shell.rs:3753-3767). Desktop assertions to mirror:
   `new_thread_background_height` table (shell.rs:8276-8281) and the
   same-frame-bounds contract
   (`background_paint_sees_same_frame_composer_bounds_even_when_painted_first`,
   mask.rs:91).
4. **Handoff arming with a synchronous pane width** — the arming predicate is
   already ported (`PanelHandoff.sample`, composer-dock.ts:262-289); the HOST
   wiring needs the computed width. Mirror tests (already in
   `tests/composer-dock.test.ts` per ticket 15; add a host-level case):
   `panel_handoff_hides_background_during_geometry_switch_in_both_sidebar_states`
   (composer_dock.rs:456), `panel_return_sizes_while_hidden_and_finishes_controls_with_input`
   (:502), `ordinary_resizing_and_same_column_navigation_do_not_fade`
   (panel_handoff.rs:92).
5. **Shell-scoped artwork + Readiness**: mirror
   `cold_artwork_fades_in_once_and_warm_navigation_does_not_restart_it`
   (effects.rs:320) at the store level, plus a host test that the hero layer
   survives `/` → `/chat/$id` without unmounting (no painted frame with
   artwork === null).
6. **`transcript_width` wiring**: mirror
   `panel_exit_retains_source_transcript_width_only_until_handoff_ends`
   (composer_dock.rs:488) — already ported pure; add the host call.
7. **Icon generator**: parse() carries root `stroke`/`stroke-width`/
   `stroke-linecap`/`stroke-linejoin` (generate.mjs:26-38); a freshness test
   asserting every asset whose root has stroke produces a body that paints.

## Desktop-only items NOT to port

- The 4-entry FIFO artwork cache and its eviction (no user-visible behavior;
  ticket 15 §5) — port only the one-pending-job semantics (effects.rs:65-67).
- `measured_dock_retargets_without_a_first_frame_jump` (composer_dock.rs:527) —
  gpui `TestAppContext` harness; its observable rule is already covered by
  `initial_and_reduced_motion_frames_snap` (ticket 15 §3).
- macOS traffic-light spacer / fullscreen cluster inset
  (`titlebar_spacer`, shell.rs:3857-3871) and Linux caption reservations —
  the web cluster is flat 10 (ticket 06 §2.2).
- Native window drag / double-click zoom (`titlebar_drag_region`,
  shell.rs:3917-3963) — ticket 06 §5.
- `motion::settle_down` (motion.rs:339-350) — dead code, no call sites
  (hero-context-meter.md §1.5); do not invent a hero entrance fade.
- `frost.rs`'s pass-through-when-opaque and scene-layer draw-order machinery
  (frost.rs:85-96) — only relevant if the web ever un-defrosts; CSS
  `backdrop-filter` composes in one layer already.
- `ROBOCO_MOTION_SCALE` (`motion::speed_scale`) multipliers — the web omits
  them (hard-coded durations); acceptable unless the env knob matters.
- The BGRA RenderImage detail (effects.rs:214) — canvas ImageData is RGBA;
  port the math, not the channel order.
