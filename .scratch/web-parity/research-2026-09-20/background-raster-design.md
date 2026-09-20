# New-chat background raster continuity — Stage-A design/prototype (ticket 65)

**Date:** 2026-09-20 · **Branch:** `wp-fu/65` · **Baseline:** `381c768d` (web-parity/followup)

**Gate verdict: the §2.5 Stage-A gate DOES NOT PASS yet.** Items 1–3 are delivered
below (a concrete renderer algorithm, its motion-scheduling state machine, and a
numeric oracle comparison implemented as real vitest coverage). Items 4 and 5 and
the §2.7 runtime matrix are **PENDING authorized runtime access** — this authoring
session had no browser/dev-server/`web_smoke` available and ran none, per the
ticket's hard rules. Production integration must not start until the pending
evidence lands; the exact capture/measurement procedures are specified in §5–§7.

Scope control: no production file was edited. The only code added is test-scoped:
`web/packages/app/tests/helpers/hero-renderer-prototype.ts` (the candidate
renderer math + scheduler prototype) and new suites in
`web/packages/app/tests/new-thread-background.test.ts`. Ticket 64's files
(`sidebar-tween.ts`, `dock-glide.ts`) are untouched.

## 1. What is being fixed (from ticket 65 §1/§4)

- **65a — unbounded CPU remasking during dock motion.** The composer-surface
  ResizeObserver reaches the full CPU remask (`new-thread-background.tsx:377-393`)
  because `HeroRemaskGate` suppresses geometry notes only while
  `sidebarTweenSignal` is active (`sidebar-tween.ts:51-55,132-134`) and the dock
  glide arms a different signal. Per full remask the current code allocates, per
  pass, a `Float32Array` and an `ImageData` of cssW×cssH each (≈ 4.4 MB + 4.4 MB
  at 1440×760), fills every pixel, `putImageData`s it, and re-composites the
  cover-fit artwork — twice (reveal + cutout). That is ≈ 17.5 MB of transient
  allocation per remask; a 28-frame dock glide can drive one per frame per
  observer. (Static count from the source, not a measurement — see §6.)
- **65b — growth-edge exposure.** During the sidebar tween the readiness layer
  holds the pre-flip raster centered (`app.css:2737-2741`); when the hero grows,
  up to `(sidebarWidth)/2` of page background shows at each edge until the settle
  remask (ticket 57's recorded trade-off, reopened by the user report).
- **65c — evidence gap.** Existing gate tests prove scheduling, not rendered
  alignment.

## 2. §2.5 item 1 — chosen renderer/backend

### Choice: a WebGL fragment-shader port of the desktop mask, keeping the two existing canvases

The candidate renderer separates the **cached source artwork** (a texture,
uploaded once per artwork/effect change) from the **dynamic mask geometry** (a
uniform bundle, updated per frame from current hero/composer bounds). Each pass
canvas gets a small WebGL context running one fragment shader — the direct port
of the desktop's `ImageAlphaMask` shader (`image_mask_alpha`) over
`heroMaskGeometry`'s parameters:

```glsl
// GLSL ES 1.00 (runs on WebGL1 and WebGL2 unchanged). Sketch — the exact
// double-checked reference implementation is the test-scoped prototype in
// tests/helpers/hero-renderer-prototype.ts, validated by the oracle tests.
precision highp float;
uniform vec2 u_heroOrigin;     // window-space hero origin
uniform float u_dpr;
uniform vec4 u_maskBounds;     // window-space cleared (cutout) / parked (reveal) rect
uniform float u_radius;        // clamped to half each mask dimension (ImageAlphaMask::scale)
uniform float u_feather;       // clamp(hero.height * 0.52, 120, 280); reveal: 1
uniform float u_clearance;     // 8; reveal: 0
uniform vec2 u_fade;           // (hero.bottom, max(hero.height, 1)) — shared by both passes
uniform vec2 u_fittedOrigin;   // window-space cover-fit origin
uniform float u_fittedScale;   // window px per source px
uniform sampler2D u_source;

float ss(float v) { float t = clamp(v, 0.0, 1.0); return t * t * (3.0 - 2.0 * t); }

void main() {
  vec2 w = u_heroOrigin + gl_FragCoord.xy / u_dpr;   // window-space fragment (y-flip handled host-side)
  vec2 q = abs(w - (u_maskBounds.xy + u_maskBounds.zw * 0.5)) - u_maskBounds.zw * 0.5 + u_radius;
  float d = length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - u_radius;
  float hole = ss((d - u_clearance) / u_feather);
  float fade = ss((u_fade.x - w.y) / u_fade.y);
  float m = min(hole, fade);                          // ONE mask, never a product
  vec2 uv = (w - u_fittedOrigin) / u_fittedScale;     // source px (÷ source size, y-flip host-side)
  vec4 s = texture2D(u_source, uv / u_sourceSize);
  gl_FragColor = vec4(s.rgb * s.a * m, s.a * m);      // premultiplied out
}
```

Per frame per canvas: upload ~10 uniforms, issue one draw call. Steady-state
**CPU allocation is zero**; the SDF+smoothstep+texture fetch per fragment is
trivial GPU work at hero sizes (≤ 2880×1520 fragments at DPR 2).

Why this backend over the alternatives (details in §8): it is the only candidate
that evaluates the *exact* moving mask per frame with no per-frame CPU pixel
work — the desktop's own architecture ("supply mask to fitted image paint",
`new_thread_background_mask.rs:74-81`). The DOM structure, element opacities
(reveal 0.5 / cutout 1.0), readiness layer, and resolved-artwork selection are
unchanged; the two canvases stay two canvases, so the CSS and the fallback share
one structure.

### Source upload / cache lifetime

- `setSource(drawable, w, h)` uploads the decoded artwork (`effect: none`) or the
  effect raster canvas (installed effect) via `texImage2D`, once per
  artwork/effect change — the same cadence as today's repaint trigger. Two
  contexts → two uploads (no cross-context sharing; bounded and simple).
- Old texture deleted on replacement and on `dispose()` (source replacement /
  unmount). `WEBGL_lose_context` is used at dispose for prompt release.
- The cold-effect contract is preserved: an installed effect paints nothing until
  its raster resolves (today's `Empty`-equivalent), then one `noteArtwork()`
  re-uploads and renders at current geometry.

### Buffer allocation rules

- Canvas backing stores resize only when the rounded device dims change (today's
  guard), including per-frame during a sidebar tween on the GPU path — realloc is
  GPU-side and the frame re-renders anyway; this is on the §6 measurement list.
- Per frame: uniform uploads only. No `ImageData`, no `Float32Array`, no mask
  canvas, no `putImageData` on the GPU path.
- Memory at rest: two canvas drawing buffers (same as today) + two source
  textures (one extra copy of the artwork per context vs today — recorded as the
  deliberate trade; measured in §6).

### Failure fallback

Any of: `getContext("webgl2"/"webgl")` returns null, shader compile/link failure,
fragment `highp` unavailable (`getShaderPrecisionFormat` — SDF precision would
otherwise degrade the feather), or an unrestored `webglcontextlost` → the
component uses the **CPU fallback renderer: today's exact code path**
(`cutoutMaskRaster` + `destination-in`), driven by the same scheduler in its
`absorb-sidebar` cadence (§3) — i.e., never worse than the status quo: sidebar
glides keep the ticket-57 raster window, dock motion is coalesced to ≤ 1 full
remask per frame, settle always repaints. `webglcontextlost` is
`preventDefault`ed and `webglcontextrestored` re-uploads the source and
re-renders; repeated loss pins the fallback for the mount.

### Exact production files and interfaces (post-gate integration surface)

| File | Change | Interface |
| --- | --- | --- |
| `web/packages/app/src/lib/new-thread-background-renderer.ts` | **new** | `HeroGeometrySample { hero: Rect; composer: Rect; dpr: number }`; `HeroArtworkSource { drawable: CanvasImageSource; width: number; height: number }`; `HeroBackgroundRenderer { readonly kind: "webgl" \| "cpu-fallback"; setSource(source \| null): void; render(geometry): void; dispose(): void }`; `createHeroBackgroundRenderer(revealCanvas, cutoutCanvas): HeroBackgroundRenderer`. Mask parameters are computed with the **existing** `heroMaskGeometry` (§2.4: the pure helpers are the numeric reference or the implementation). |
| `web/packages/app/src/lib/sidebar-tween.ts` | edit | `HeroRenderScheduler` (the `HeroRemaskGate` successor — the gate's lifecycle, generalized): `constructor({ sample, render, uploadSource?, motionCadence?, sidebar?, dock? })`, `noteGeometry()`, `noteArtwork()`, `noteSettle()`, `dispose()`. `remaskDue`/`HeroRemaskGate`/`SidebarTweenSignal`/`sidebarTweenSignal` stay exported with unchanged semantics (the `absorb-sidebar` cadence *is* today's gate behavior; ticket 64's consumers unaffected). |
| `web/packages/app/src/lib/dock-glide.ts` | edit | `DockGlideSignal.subscribe(listener): () => void` — mirrors `SidebarTweenSignal` (research §3.64: "Any added subscription must be shared and tested with ticket 65"). `onDockGlideFrame(listener): () => void` — a listener registry invoked synchronously at the end of `writeDockGlideVars` (see §3.2). No chat-page.tsx edit required. |
| `web/packages/app/src/components/new-thread-background.tsx` | edit | The inline `paint()`/remask body moves behind `createHeroBackgroundRenderer`; the component owns scheduler lifecycle, keeps the DOM/classes, sets `data-renderer={renderer.kind}` on the hero root, and routes both ROs + the settle commit + artwork deps into the scheduler. |
| `web/packages/app/src/styles/app.css` | edit | Gate the ticket-57 raster-window override to the fallback: `.new-thread-hero[data-sidebar-tween="1"][data-renderer="cpu-fallback"] .new-thread-hero-readiness { … }`. The GPU path re-renders per frame, so the fixed window (and its growth bands) does not engage. The hero's width transition and readiness rules are unchanged. |
| `web/packages/app/tests/new-thread-background.test.ts` | edit | The Stage-A oracle suites (already landed, test-scoped) plus post-gate integration cases. |
| `web/packages/app/tests/sidebar-tween.test.ts` | edit | Post-gate: the overlapping sidebar/dock coalescing cases against the real scheduler (the Stage-A prototype's versions are in this change). |
| `web/packages/app/tests/dock-glide.test.ts` | edit | Post-gate: `subscribe` edges and the `onDockGlideFrame` dispatch contract. |

## 3. §2.5 item 2 — arm/frame/artwork-change/settle/cancel sequence

### 3.1 Signal composition

`motionActive = sidebarTweenSignal.isActive() || dockGlideSignal.isActive()` —
this selects the dynamic renderer cadence; it never means "skip geometry
painting". The scheduler subscribes to both signals' arm/settle edges
(synchronous notification, as `SidebarTweenSignal.subscribe` already does).
Neither signal clears the other's tracking: every edge re-evaluates both states
(`#syncCadence`), and a settle only final-paints when *both* are inactive.
Overlap and reversal need no generation counters: a render is skipped only when
**the same frame and the same sampled geometry** were already rendered, so a
stale callback can never paint stale geometry — it either re-samples current
bounds or is cancelled at `dispose()`.

### 3.2 How transform-only composer placement reaches the renderer after dock prepaint

The dock pump (`chat-page.tsx:840-899`) already runs the desktop's per-paint
order and calls `writeDockGlideVars` **after** `dock.prepaint(...)` and the
`wrapper.style.transform = translate(dx, dy)` write. The design adds the
renderer frame hook *inside* `writeDockGlideVars` (`dock-glide.ts`): after
writing the vars it synchronously invokes the registered frame listeners. The
scheduler's listener samples `getBoundingClientRect()` on the hero and
`#composer-surface` **there** — a forced synchronous layout that observes this
frame's transform write (the same same-frame contract the existing remask
already relies on). This covers the frames `ResizeObserver` cannot see
(transform-only movement) and needs no edit to `chat-page.tsx` and no second
rAF loop during dock motion.

Rejected alternative: sampling from a renderer-owned rAF during the dock glide.
The pump self-re-registers at the end of its callback, so a renderer rAF
registered at arm time runs *before* the pump on every frame — it would read
last frame's transform (a one-frame-stale hole), violating the same-frame
contract.

### 3.3 Frame cadence selection

- Dock glide active → the pump's post-prepaint hook owns the cadence (§3.2).
- Sidebar tween active and dock inactive → the renderer's own rAF samples per
  frame (the hero's width is a CSS transition; any point in the frame sees the
  current value).
- Both active → the hook wins; the rAF stands down. Dock settles while the
  sidebar still runs → the rAF resumes on that edge.
- No motion → geometry notes (both ROs) render synchronously, coalesced to one
  render per frame per geometry (§3.4); no rAF loop exists.

### 3.4 The sequence, step by step

| Moment | Behavior |
| --- | --- |
| **Arm** (either signal) | Cadence sync only — no render (the arm commit's geometry is already painted). |
| **Frame** (motion active) | One render per frame at the sampled current geometry: hero rect (current cover-fit crop) and composer rect (current hole) measured together. Geometry notes during motion are absorbed — the frame cadence owns them. |
| **Geometry note, settled** | Sample both rects; render only if this frame has no render at that geometry yet. Both ROs in one frame coalesce to ≤ 1 render; a note whose geometry is unchanged renders nothing. |
| **Artwork/effect change** | `uploadSource()` (texture upload) + one forced render at the *current* geometry, even mid-motion (the raster re-fixes the window — today's `remaskDue(…, "artwork") === true` contract). |
| **Settle** (last signal falls, or the component's flag-fall commit) | Cancel the rAF; exactly one final paint at the measured end geometry, coalesced with any render already done this frame (the dock pump's converged frame already rendered it through the hook). One motion settling never releases the other's cadence. |
| **Reversal** | A re-arm edge keeps the cadence; rendering continues from the actual current bounds; no final paint until the last settle. |
| **Cancel / drag / reduced-motion** | All existing disarm paths funnel through `settle()` → the settle row applies. Reduced motion never arms the sidebar signal; the dock's reduced path snaps — notes render immediately. |
| **Unmount / source replacement** | `dispose()`: cancel the pending rAF, unsubscribe all three hooks, delete GL resources (via the renderer's `dispose`). Every scheduler entry point is a no-op afterwards — no stale callbacks. |

### 3.5 Prototype evidence (executable, landed with this change)

The scheduler above is implemented test-scoped
(`tests/helpers/hero-renderer-prototype.ts` → `HeroRenderScheduler`,
`ManualFramePump`, `ProposedDockSignal`) and driven in
`tests/new-thread-background.test.ts` ("ticket 65 stage-A render scheduler
prototype (gate item 2)", 8 tests) against the **real** `SidebarTweenSignal`:

- sidebar-only glide: both ROs' notes absorbed, exactly one render per frame at
  that frame's geometry, one coalesced final paint at settle, rAF loop stands
  down;
- dock-only glide with **zero** resize notifications (transform-only move): the
  hook drives one render per frame, each at that frame's composer placement —
  the hole never goes stale; converged-frame settle coalesces to zero extra
  renders; a teardown settle (no converged frame) still lands exactly one final
  paint;
- overlap in both orders (sidebar settles mid-dock; dock settles mid-sidebar):
  single cadence, no double render per frame, one final paint;
- mid-motion reversal: re-arm paints nothing, cadence continues, one final paint
  at the true endpoint;
- artwork change mid-motion: one re-upload + immediate render at current
  geometry, coalesced with the frame's hook render;
- settled notes coalesce across both observers; `dispose()` cancels the pending
  frame and makes every entry point a no-op; a scheduler constructed mid-glide
  picks up the live cadence immediately.

## 4. §2.5 item 3 — numeric oracle comparison (implemented)

The candidate renderer math is prototyped test-scoped with the production
structure — uniform bundle precomputed per geometry/source change
(`computeHeroShaderUniforms`), per-pixel window-space fragment evaluation
(`shaderMaskTerms`/`shaderMaskAlpha`/`shaderSourceCoord`), and the two-pass
source-over composite (`renderHeroPixel`) — and compared against the production
oracle (`heroMaskGeometry`, `cutoutHoleAlpha`, `cutoutBottomFadeAlpha`,
`cutoutMaskAlpha`, `cutoutMaskRaster`) in `tests/new-thread-background.test.ts`
("ticket 65 stage-A shader model vs the pure-mask oracle (gate item 3)", 11
tests). Fixtures span five hero/composer geometries — fractional origins, the
120px and 280px feather clamps, a radius-clamping tiny composer, fractional
dimensions with the composer extending past the hero's bottom — at DPR 1, 1.5,
and 2:

- **Cover-fit mapping**: the uniform-driven window→source transform equals the
  component's device-space `drawImage` dest-rect math at every probed point
  (corners, center, fractional) to 1e-8; the fitted image covers the canvas with
  one dimension exact (cover, never contain).
- **Hole ramp**: candidate == `cutoutHoleAlpha` to 1e-10 and == the exact
  smoothstep constants at the hole center, on the edge, inside/at the hard 8px
  clearance, feather quarter/mid/three-quarter, feather end, past the dome, plus
  the 45° corner diagonal (the `hypot` branch) — for every fixture.
- **Bottom fade**: candidate == `cutoutBottomFadeAlpha` to 1e-10 and == the
  exact constants at 0/25/50/75/100% of the hero height, on both passes.
- **Overlapping ramps**: candidate == `cutoutMaskAlpha` == `min(hole, fade)` to
  1e-10 where both ramps are mid-flight; each fixture proves the min picks the
  hole somewhere and the fade somewhere, and that the value exceeds the
  (forbidden) product.
- **Reveal pass**: hole == 1 across the hero (parked exclusion), mask == fade ==
  `cutoutMaskAlpha(…, false)`.
- **Whole grids**: candidate == `cutoutMaskRaster` at every pixel of 60×40 grids
  at scales 0.5/1/2, both passes — to 1e-6 (the grid is a `Float32Array` and GPU
  fragment math is single-precision too; the scalar comparisons above are exact
  to 1e-10, so the 1e-6 here is precisely the float32 rounding bound).
- **Two-pass source-over result**: candidate `renderHeroPixel` ==
  `referenceHeroPixel` (oracle alphas + the component's cover-fit mapping,
  composited as the two stacked canvases over the opaque page) to 1e-6 per
  channel at every in-hero probe — including the documented hole-interior
  behavior: cutout alpha 0, so the pixel is exactly
  `source·(fade/2) + page·(1 − fade/2)`.
- **Growth coverage (the ticket's named test)**: across the sidebar-collapse
  glide (1216→1440 in fractional steps), every probed hero pixel samples inside
  the source image (no page-background band can exist), while the ticket-57
  fixed 1216px window's exposed band is quantified — `(W−1216)/2` per side,
  112px at the endpoint — the exact deviation the per-frame renderer eliminates.
- **Transform-only hole tracking**: as the composer translates at constant size
  (RO-silent), the candidate's cleared region tracks each frame's rect exactly
  (0 at the current center and 4px outside the edge; the vacated frame-0 edge
  recovers), matching `cutoutMaskAlpha` at the same current bounds.

Result: **19 new tests, all green; full suite `pnpm test` 1406/1406 (89 files);
`pnpm -r build` green.** These prove the candidate's math and scheduling are
numerically equivalent to the existing contract. They do **not** prove rendered
pixels in a browser or frame-time cost — that is items 4/5.

## 5. §2.5 item 4 — PENDING: matched capture/recording

**Status: PENDING — requires an authorized running app; not collectable in this
session (no browsers/dev servers/`web_smoke` may be started by the agent).**

Exact capture procedure (coordinator/human-run, after the candidate is
integrated behind the gate):

1. Builds: (a) current production build (CPU masks + `HeroRemaskGate`); (b) the
   candidate build (this design integrated). Web: `pnpm build` in `web/`, then
   `cargo build -p roboco-engine --example web_smoke` and open it in Chrome/Edge.
   Desktop: the Rust app for the matched native recording
   (`.scratch/web-client/parity/shot.ps1` for stills).
2. Environments: viewport 1440×900 and 1280×800; DPR 1 and 2; light and dark;
   default artwork and an installed 3008×2000 JPEG; effect `none` and one
   installed effect. Record build identity (git rev), browser/OS, device, and
   preference state with each capture.
3. Actions, each screen-recorded at 60 fps on web (both builds) **and** desktop:
   (a) sidebar collapse (hero grows) and expand, default and wide sidebar;
   (b) canvas→chat and chat→canvas, empty and 3-line composer, right pane closed
   and open at destination; (c) rapid reversal mid-glide, sidebar toggle during
   the dock glide, seam-drag takeover mid-tween, reduced-motion toggled
   mid-motion; (d) cold artwork arriving mid-motion (install during a glide) and
   an effect change mid-motion.
4. Frame inspection: extract frames at ~25/50/75% of each motion plus the settle
   frame. Per frame verify: (a) no page-color bands — the hero's edge columns
   never equal the page background token; (b) hole alignment — the cleared edge
   tracks the composer's painted edge ±1px with the 8px clearance; (c) feather
   width — the dome's ramp span at the composer's mid-height equals
   `8 + clamp(hero.height×0.52, 120, 280)` px ±1px at every frame (never
   scaled); (d) crop continuity — the fitted crop origin moves continuously and
   the settle frame shows no crop jump (Δ ≤ 1px between the last motion frame
   and the settled frame).

## 6. §2.5 item 5 — PENDING: measured workload comparison

**Status: PENDING — same runtime constraint as §5.**

Exact measurement procedure (same builds/environments as §5; 5 runs per action,
report median + p95):

- Metrics per action: renderer update count (instrument `render()`); mask-raster
  invocations (CPU path counter); allocated bytes — explicit accounting (canvas
  backing `w×h×4` ×2; source textures `srcW×srcH×4` ×2 contexts; CPU-path
  transient `cssW×cssH×8` per remask) plus `performance.memory.usedJSHeapSize`
  deltas; source uploads (`texImage2D` count); layout/paint time and main-thread
  frames > 50 ms (Chrome DevTools Performance trace); renderer memory at rest.
- Static baseline known without a browser (from source): today each full remask
  runs 2 passes × (full-grid `Float32Array` + `ImageData` fill + `putImageData`
  + cover-fit `drawImage` + `destination-in` composite) ≈ 17.5 MB transient at
  1440×760, and the composer RO can drive one per frame during the dock glide
  (≤ 2/frame with both observers). The candidate's steady state is 0 allocations
  and 2 draw calls per frame.
- Proposed thresholds (validated or revised at the gate): dock-glide renderer
  updates ≤ frames + 1; zero `ImageData`/`Float32Array` allocations on the GPU
  path; no renderer-attributed > 50 ms frame; renderer memory ≤ today's canvas
  backing + 2 source textures. Known risk to measure: per-frame canvas backing
  realloc during the sidebar tween on the GPU path, and the forced-layout cost
  of the two `getBoundingClientRect` samples per frame.

## 7. §2.7 runtime matrix — all PENDING

| Scenario | Status | Procedure |
| --- | --- | --- |
| Sidebar collapse and expansion (default/wide sidebar, light/dark, installed/default artwork) | PENDING | §5.3(a) + §5.4 inspection |
| Docking and undocking (empty/multiline composer; small/large viewport; pane closed/open) | PENDING | §5.3(b) + §6 metrics |
| Overlap and interruption (rapid reverse; sidebar during dock; drag takeover; reduced-motion toggle) | PENDING | §5.3(c); scheduler logic pre-covered by the §3.5 prototype tests |
| Cold artwork and effect changes mid-motion | PENDING | §5.3(d); readiness contract unchanged by this design |

## 8. Rejected alternatives (and why)

- **CSS `mask-composite: intersect` layers**: the composite is a *product*
  (source-in), and the contract requires `min(hole, fade)` inside one mask —
  mid-ramp pixels would darken to `hole×fade`. The exact SDF smoothstep dome is
  also not expressible as CSS gradients, and the ticket forbids an invented
  blur/gradient.
- **Constant pre-masked bitmap + generic transform**: ticket-rejected — scaling
  a pre-masked image changes the radius/feather, and a frozen crop is not the
  cover-fit formula.
- **CPU dynamic mask per frame (coalesced)**: keeps the ≈ 17.5 MB transient per
  remask and the full-grid fill on the main thread; §2.5 requires measured
  frame-time evidence for any per-frame CPU mask, which cannot be collected in
  this session. Retained as the *fallback* (never worse than today), not the
  candidate.
- **Renderer-owned rAF sampling during the dock glide**: runs before the pump's
  transform write every frame → one-frame-stale hole (§3.2).
- **Merging both passes into one canvas/context**: would re-prove the element
  opacity composition for no gain; two canvases keep the DOM/CSS and the CPU
  fallback structurally identical.

## 9. Gate status and what unblocks it

Delivered now (no browser required): the concrete algorithm (§2), the scheduling
contract with executable prototype evidence (§3), and the numeric oracle
comparison against the existing pure helpers (§4 — 19 tests, green; full suite
1406/1406; `pnpm -r build` green).

Still required for the gate to pass: §5's matched captures, §6's measured
workload comparison, and the §7 matrix — each with the exact procedure recorded
above. Until they land: **Stage A stays pending and production integration does
not start.** If the captures or measurements falsify the candidate (e.g.
per-frame realloc or forced-layout cost exceeds the bound), the constraint that
failed will be documented here and the design revised — not shipped with
silently weakened geometry requirements.
