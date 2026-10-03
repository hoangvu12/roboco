/**
 * The hero background renderer (ticket 65 — production integration of the
 * Stage-A design, `.scratch/web-parity/research-2026-09-20/background-raster-design.md` §2).
 *
 * Two implementations behind one interface, driving the SAME two canvases
 * the component has always rendered (reveal at element opacity 0.5, cutout
 * at 1 — the DOM structure, classes, and readiness contract are unchanged):
 *
 * - **WebGL** — the desktop's `ImageAlphaMask` fragment shader ported to
 *   GLSL ES 1.00 (`image_mask_alpha` over `heroMaskGeometry`'s parameters):
 *   the artwork/effect raster is uploaded ONCE per source change as a
 *   texture (`texImage2D`), and every render is ~12 uniform uploads + one
 *   draw call per pass — zero CPU pixel work, zero per-frame allocation
 *   (the old CPU path filled two full `Float32Array`+`ImageData` grids per
 *   remask, ≈17.5 MB of transient allocation at 1440×760, and the dock
 *   glide could drive one per frame). The mask math is the validated
 *   Stage-A model (the oracle suites in `tests/new-thread-background.test.ts`
 *   compare it against `cutoutHoleAlpha`/`cutoutBottomFadeAlpha`/
 *   `cutoutMaskAlpha`/`cutoutMaskRaster` to 1e-6).
 *
 * - **CPU fallback** — today's exact code path (`cutoutMaskRaster` +
 *   `putImageData` + `destination-in`), selected when WebGL is unavailable
 *   (context creation, shader compile/link, fragment `highp`). Never worse
 *   than the status quo: the scheduler drives it in the same coalesced
 *   cadence, and the ticket-57 raster-window CSS engages through
 *   `data-renderer="cpu-fallback"`.
 *
 * Scheduling is NOT this module's job — `HeroRenderScheduler`
 * (`lib/sidebar-tween.ts`) owns when `render()` is called. Geometry is
 * sampled by the caller (hero + composer measured together, window space)
 * and handed in; this module only paints.
 */

import {
  cutoutMaskRaster,
  heroMaskGeometry,
  fittedGeometry,
  type Rect,
} from "./new-thread-background";
import {
  NEW_THREAD_ADJUSTMENT_DEFAULT,
  type NewThreadBackgroundAdjustment,
} from "../state/ui-settings";

/** The renderer's geometry contract — measured by the scheduler's sample. */
export interface HeroRenderGeometry {
  readonly hero: Rect;
  readonly composer: Rect;
  readonly dpr: number;
}

/** The artwork/effect source: the decoded image or the rasterized effect canvas. */
export interface HeroArtworkSource {
  readonly drawable: CanvasImageSource;
  readonly width: number;
  readonly height: number;
}

/** One renderer behind the two pass canvases. */
export interface HeroBackgroundRenderer {
  /** `"webgl"` while the GL passes are live; `"cpu-fallback"` otherwise. */
  readonly kind: "webgl" | "cpu-fallback";
  /** Upload (or clear) the source; renders use the latest source. */
  setSource(source: HeroArtworkSource | null): void;
  /**
   * The current crop (`NewThreadBackgroundAdjustment`, upstream #660): the
   * cover fit gains a zoom multiplier and a focal-aligned position.
   */
  setAdjustment(adjustment: NewThreadBackgroundAdjustment): void;
  /**
   * The departing leg of a wallpaper crossfade (upstream #598): blended
   * under the current source at `(1 − mix)`/`mix`, keeping the departing
   * image's own framing (`previous_adjustment`). Null (or `mix` ≥ 1)
   * disables the leg.
   */
  setDeparting(
    source: HeroArtworkSource | null,
    mix: number,
    adjustment?: NewThreadBackgroundAdjustment,
  ): void;
  /** Paint both passes at the given geometry (no source → clear). */
  render(geometry: HeroRenderGeometry): void;
  /** Release every GPU/CPU resource; renders after it are inert. Safe to call twice. */
  dispose(): void;
}

export interface CreateHeroBackgroundRendererOptions {
  /**
   * The raster window's width (ticket 57a) — the CPU path records the CSS
   * width its bitmap was painted at so the fallback's raster-window CSS can
   * hold it centered during a sidebar tween. The GPU path re-renders per
   * frame and never freezes a window, so it does not call this.
   */
  readonly setRasterWidth?: (cssWidth: number) => void;
}

// ---------------------------------------------------------------------------
// The WebGL pass (one context per canvas — the desktop shader, GLSL ES 1.00)
// ---------------------------------------------------------------------------

const VERTEX_SOURCE = `
attribute vec2 a_pos;
void main() {
  gl_Position = vec4(a_pos, 0.0, 1.0);
}
`;

/**
 * `image_mask_alpha` (shaders.wgsl) over `heroMaskGeometry` — the exact
 * smoothstep-over-SDF hole ramp, the shared bottom fade, and the `min`
 * composition inside ONE mask, sampling the source through the cover-fit
 * transform. Output is premultiplied (the context is created
 * `premultipliedAlpha: true`); the reveal pass's 0.5 element opacity stays
 * in the DOM, exactly like the CPU path.
 */
const FRAGMENT_SOURCE = `
precision highp float;
uniform vec2 u_heroOrigin;
uniform float u_cssHeight;
uniform float u_dpr;
uniform vec2 u_maskCenter;
uniform vec2 u_maskHalf;
uniform float u_radius;
uniform float u_feather;
uniform float u_clearance;
uniform vec2 u_fade;
uniform vec2 u_fittedOrigin;
uniform float u_fittedScale;
uniform vec2 u_sourceSize;
uniform sampler2D u_source;
uniform vec2 u_departingOrigin;
uniform float u_departingScale;
uniform vec2 u_departingSize;
uniform sampler2D u_departing;
uniform float u_mix;

float ss(float v, float edge) {
  float t = clamp(v / edge, 0.0, 1.0);
  return t * t * (3.0 - 2.0 * t);
}

void main() {
  // Window-space fragment: gl_FragCoord is bottom-left, the window is
  // top-left — the y-flip is host-side (u_cssHeight).
  vec2 frag = gl_FragCoord.xy / u_dpr;
  vec2 w = vec2(u_heroOrigin.x + frag.x, u_heroOrigin.y + u_cssHeight - frag.y);
  vec2 q = abs(w - u_maskCenter) - u_maskHalf + u_radius;
  float d = length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - u_radius;
  float hole = u_feather <= 0.0 ? 1.0 : ss(d - u_clearance, u_feather);
  float fade = u_fade.y <= 0.0 ? 1.0 : ss(u_fade.x - w.y, u_fade.y);
  float m = min(hole, fade);
  // The crossfade (upstream #598): the departing artwork keeps its own
  // fitted transform; u_mix >= 1 (or an empty departing texture) reduces
  // the blend to the current source alone.
  vec4 s = texture2D(u_source, (w - u_fittedOrigin) / u_fittedScale / u_sourceSize);
  if (u_mix < 1.0) {
    vec4 departing = texture2D(u_departing, (w - u_departingOrigin) / u_departingScale / u_departingSize);
    s = mix(departing, s, u_mix);
  }
  float a = s.a * m;
  gl_FragColor = vec4(s.rgb * a, a);
}
`;

const UNIFORM_NAMES = [
  "u_heroOrigin",
  "u_cssHeight",
  "u_dpr",
  "u_maskCenter",
  "u_maskHalf",
  "u_radius",
  "u_feather",
  "u_clearance",
  "u_fade",
  "u_fittedOrigin",
  "u_fittedScale",
  "u_sourceSize",
  "u_departingOrigin",
  "u_departingScale",
  "u_departingSize",
  "u_departing",
  "u_mix",
] as const;

const GL_ATTRIBUTES: WebGLContextAttributes = {
  alpha: true,
  premultipliedAlpha: true,
  preserveDrawingBuffer: false,
  antialias: false,
  depth: false,
  stencil: false,
};

function compileProgram(gl: WebGLRenderingContext): WebGLProgram | null {
  const compile = (kind: number, source: string): WebGLShader | null => {
    const shader = gl.createShader(kind);
    if (shader === null) {
      return null;
    }
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      gl.deleteShader(shader);
      return null;
    }
    return shader;
  };
  const vertex = compile(gl.VERTEX_SHADER, VERTEX_SOURCE);
  const fragment = compile(gl.FRAGMENT_SHADER, FRAGMENT_SOURCE);
  if (vertex === null || fragment === null) {
    return null;
  }
  const program = gl.createProgram();
  if (program === null) {
    return null;
  }
  gl.attachShader(program, vertex);
  gl.attachShader(program, fragment);
  gl.linkProgram(program);
  gl.deleteShader(vertex);
  gl.deleteShader(fragment);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    gl.deleteProgram(program);
    return null;
  }
  return program;
}

/** One canvas's GL state: program, uniforms, quad buffer, source textures. */
class WebGlPass {
  private constructor(
    readonly gl: WebGLRenderingContext,
    private readonly canvas: HTMLCanvasElement,
  ) {}

  #program: WebGLProgram | null = null;
  #uniforms: Record<string, WebGLUniformLocation | null> = {};
  #texture: WebGLTexture | null = null;
  #sourceSize: { readonly width: number; readonly height: number } | null = null;
  #departingTexture: WebGLTexture | null = null;
  #departingSize: { readonly width: number; readonly height: number } | null = null;
  #mix = 1.0;

  /**
   * Create a pass, or null when WebGL/shader/precision is unusable. The
   * context mode of the canvas is committed here — callers must only hand
   * canvases they own for the renderer's lifetime.
   */
  static create(canvas: HTMLCanvasElement): WebGlPass | null {
    const gl = (canvas.getContext("webgl2", GL_ATTRIBUTES) ??
      canvas.getContext("webgl", GL_ATTRIBUTES)) as WebGLRenderingContext | null;
    if (gl === null) {
      return null;
    }
    // The SDF needs highp: a degraded fragment precision would shift the
    // feather's 120-280px ramp by device pixels (the design's §2 precision
    // gate — the numeric oracles were validated at double precision).
    const highp = gl.getShaderPrecisionFormat(gl.FRAGMENT_SHADER, gl.HIGH_FLOAT);
    if (highp === null || highp.precision <= 0) {
      return null;
    }
    const pass = new WebGlPass(gl, canvas);
    return pass.#init() ? pass : null;
  }

  /** Program + quad + uniform locations; reusable after a context restore. */
  #init(): boolean {
    const gl = this.gl;
    const program = compileProgram(gl);
    if (program === null) {
      return false;
    }
    this.#program = program;
    this.#uniforms = {};
    for (const name of UNIFORM_NAMES) {
      this.#uniforms[name] = gl.getUniformLocation(program, name);
    }
    const buffer = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    const attribute = gl.getAttribLocation(program, "a_pos");
    gl.enableVertexAttribArray(attribute);
    gl.vertexAttribPointer(attribute, 2, gl.FLOAT, false, 0, 0);
    gl.useProgram(program);
    gl.uniform1i(this.#uniforms.u_source!, 0);
    gl.uniform1i(this.#uniforms.u_departing!, 1);
    this.#texture = null;
    this.#departingTexture = null;
    return true;
  }

  isContextLost(): boolean {
    return this.gl.isContextLost();
  }

  /** (Re-)upload the source; null clears the texture. */
  setSource(source: HeroArtworkSource | null): void {
    const gl = this.gl;
    if (this.#texture !== null) {
      gl.deleteTexture(this.#texture);
      this.#texture = null;
    }
    this.#sourceSize = null;
    if (source === null || source.width <= 0 || source.height <= 0) {
      return;
    }
    const texture = gl.createTexture();
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, texture);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    try {
      // `CanvasImageSource` is the public type; the GL upload's
      // `TexImageSource` excludes SVGImageElement, which the hero never
      // paints (decoded images and raster canvases only).
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, source.drawable as TexImageSource);
    } catch {
      gl.deleteTexture(texture);
      return;
    }
    this.#texture = texture;
    this.#sourceSize = { width: source.width, height: source.height };
  }

  /**
   * The departing leg (upstream #598): uploads the outgoing artwork to the
   * second sampler and records the blend factor. `mix` ≥ 1 skips the blend.
   */
  #departingAdjustment: NewThreadBackgroundAdjustment = NEW_THREAD_ADJUSTMENT_DEFAULT;
  #uploadedDeparting: CanvasImageSource | null = null;

  setDeparting(
    source: HeroArtworkSource | null,
    mix: number,
    adjustment: NewThreadBackgroundAdjustment = NEW_THREAD_ADJUSTMENT_DEFAULT,
  ): void {
    const gl = this.gl;
    this.#mix = Math.min(Math.max(mix, 0), 1);
    this.#departingAdjustment = adjustment;
    // The mix advances every frame while a crossfade runs; only a SOURCE
    // change pays the texture upload (identity compares the drawables, which
    // the memoized decode/raster keeps stable).
    if (source !== null && source.drawable === this.#uploadedDeparting) {
      return;
    }
    this.#uploadedDeparting = null;
    if (this.#departingTexture !== null) {
      gl.deleteTexture(this.#departingTexture);
      this.#departingTexture = null;
    }
    this.#departingSize = null;
    if (source === null || source.width <= 0 || source.height <= 0 || this.#mix >= 1) {
      this.#mix = 1;
      return;
    }
    this.#uploadedDeparting = source.drawable;
    const texture = gl.createTexture();
    gl.activeTexture(gl.TEXTURE1);
    gl.bindTexture(gl.TEXTURE_2D, texture);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    try {
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, source.drawable as TexImageSource);
    } catch {
      gl.deleteTexture(texture);
      gl.activeTexture(gl.TEXTURE0);
      this.#mix = 1;
      return;
    }
    this.#departingTexture = texture;
    this.#departingSize = { width: source.width, height: source.height };
    gl.activeTexture(gl.TEXTURE0);
  }

  /** Paint one pass's frame; no source (or a lost context) clears the canvas. */
  render(geometry: HeroRenderGeometry, cutout: boolean, adjustment: NewThreadBackgroundAdjustment): void {
    const gl = this.gl;
    const source = this.#sourceSize;
    if (source === null || this.#program === null || gl.isContextLost()) {
      this.clear();
      return;
    }
    gl.useProgram(this.#program);
    const hero = geometry.hero;
    const canvasWidth = Math.max(1, Math.round(hero.width * geometry.dpr));
    const canvasHeight = Math.max(1, Math.round(hero.height * geometry.dpr));
    if (this.canvas.width !== canvasWidth) {
      this.canvas.width = canvasWidth;
    }
    if (this.canvas.height !== canvasHeight) {
      this.canvas.height = canvasHeight;
    }
    gl.viewport(0, 0, canvasWidth, canvasHeight);
    const mask = heroMaskGeometry(hero, geometry.composer, cutout);
    const bounds = mask.bounds;
    const radius = Math.min(
      Math.max(mask.radius, 0),
      Math.max(bounds.width, 0) * 0.5,
      Math.max(bounds.height, 0) * 0.5,
    );
    // The focal fit (upstream #660): the validated Stage-A cover fit, scaled
    // by the zoom multiplier and positioned by the normalized focal point.
    const fitted = fittedGeometry(source.width, source.height, hero, adjustment) ?? {
      left: hero.x,
      top: hero.y,
      width: hero.width,
      height: hero.height,
      overflowX: 0,
      overflowY: 0,
    };
    const u = this.#uniforms;
    gl.uniform2f(u.u_heroOrigin!, hero.x, hero.y);
    gl.uniform1f(u.u_cssHeight!, canvasHeight / geometry.dpr);
    gl.uniform1f(u.u_dpr!, geometry.dpr);
    gl.uniform2f(u.u_maskCenter!, bounds.x + bounds.width * 0.5, bounds.y + bounds.height * 0.5);
    gl.uniform2f(u.u_maskHalf!, bounds.width * 0.5, bounds.height * 0.5);
    gl.uniform1f(u.u_radius!, radius);
    gl.uniform1f(u.u_feather!, mask.feather);
    gl.uniform1f(u.u_clearance!, mask.clearance);
    gl.uniform2f(u.u_fade!, mask.bottomFade.end, mask.bottomFade.height);
    gl.uniform2f(u.u_fittedOrigin!, fitted.left, fitted.top);
    gl.uniform1f(u.u_fittedScale!, fitted.width / source.width);
    gl.uniform2f(u.u_sourceSize!, source.width, source.height);
    gl.uniform1f(u.u_mix!, this.#mix);
    const departing = this.#departingSize;
    if (departing !== null && this.#mix < 1) {
      const departingFit =
        fittedGeometry(departing.width, departing.height, hero, this.#departingAdjustment) ??
        fitted ?? {
          left: hero.x,
          top: hero.y,
          width: hero.width,
          height: hero.height,
        };
      gl.uniform2f(u.u_departingOrigin!, departingFit.left, departingFit.top);
      gl.uniform1f(u.u_departingScale!, departingFit.width / departing.width);
      gl.uniform2f(u.u_departingSize!, departing.width, departing.height);
    } else {
      gl.uniform2f(u.u_departingOrigin!, fitted.left, fitted.top);
      gl.uniform1f(u.u_departingScale!, Math.max(fitted.width / source.width, 1e-6));
      gl.uniform2f(u.u_departingSize!, source.width, source.height);
    }
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, this.#texture);
    gl.activeTexture(gl.TEXTURE1);
    gl.bindTexture(gl.TEXTURE_2D, this.#departingTexture);
    gl.activeTexture(gl.TEXTURE0);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
  }

  clear(): void {
    const gl = this.gl;
    gl.viewport(0, 0, this.canvas.width, this.canvas.height);
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);
  }

  /**
   * Re-init after `webglcontextrestored`: recompile the program state and
   * re-upload the source (GL resources died with the context). The RENDERER
   * repaints at its own last geometry — the pass does not know its role.
   */
  restore(source: HeroArtworkSource | null): void {
    if (this.gl.isContextLost() || !this.#init()) {
      return;
    }
    this.setSource(source);
  }

  dispose(): void {
    const gl = this.gl;
    if (this.#texture !== null) {
      gl.deleteTexture(this.#texture);
      this.#texture = null;
    }
    if (this.#departingTexture !== null) {
      gl.deleteTexture(this.#departingTexture);
      this.#departingTexture = null;
    }
    this.#departingSize = null;
    if (this.#program !== null) {
      gl.deleteProgram(this.#program);
      this.#program = null;
    }
    // Deliberately NOT `WEBGL_lose_context`-ing here: a StrictMode
    // double-mount re-runs the mount effect on the SAME canvas elements,
    // and a canvas whose context was killed can never hand a working one
    // back — the second renderer would paint nothing. Deleting the
    // program/texture and leaving the context alive lets the re-mount
    // recompile fresh state on it; a REAL unmount detaches the canvases,
    // and the browser releases the contexts when it collects them.
  }
}

// ---------------------------------------------------------------------------
// The CPU fallback (today's exact path, shared by the fallback renderer)
// ---------------------------------------------------------------------------

interface CpuPaintDeps {
  readonly revealCanvas: HTMLCanvasElement;
  readonly cutoutCanvas: HTMLCanvasElement;
  /** The mask-grid scratch canvas (the old `maskCanvasRef`), created lazily. */
  readonly maskCanvas: () => HTMLCanvasElement | null;
  readonly setRasterWidth?: (cssWidth: number) => void;
}

/** One CPU pass: focal-fit draw + the pure mask grid through destination-in. */
function paintCpuPass(
  deps: CpuPaintDeps,
  canvas: HTMLCanvasElement,
  hero: Rect,
  composer: Rect,
  cutout: boolean,
  source: HeroArtworkSource,
  dpr: number,
  adjustment: NewThreadBackgroundAdjustment,
  departing: { source: HeroArtworkSource; mix: number; adjustment: NewThreadBackgroundAdjustment } | null,
): void {
  const cssWidth = Math.max(1, Math.round(hero.width));
  const cssHeight = Math.max(1, Math.round(hero.height));
  // The raster window's width (ticket 57a): the readiness layer holds THIS
  // box during a sidebar tween, so the bitmap never scales — recorded for
  // the fallback's CSS to read back.
  deps.setRasterWidth?.(cssWidth);
  const rasterWidth = Math.max(1, Math.round(hero.width * dpr));
  const rasterHeight = Math.max(1, Math.round(hero.height * dpr));
  if (canvas.width !== rasterWidth) {
    canvas.width = rasterWidth;
  }
  if (canvas.height !== rasterHeight) {
    canvas.height = rasterHeight;
  }
  const context = canvas.getContext("2d");
  if (context === null) {
    return;
  }
  context.clearRect(0, 0, rasterWidth, rasterHeight);
  // The focal fit (upstream #660): the centered cover fit, scaled by the
  // zoom multiplier and positioned by the normalized focal point, drawn in
  // raster space (window units × dpr, origin relative to the hero).
  const drawFitted = (
    fittedSource: HeroArtworkSource,
    fitted: { left: number; top: number; width: number; height: number },
    alpha: number,
  ): void => {
    context.globalAlpha = alpha;
    context.drawImage(
      fittedSource.drawable,
      (fitted.left - hero.x) * dpr,
      (fitted.top - hero.y) * dpr,
      fitted.width * dpr,
      fitted.height * dpr,
    );
    context.globalAlpha = 1;
  };
  const fitted =
    fittedGeometry(source.width, source.height, hero, adjustment) ?? {
      left: hero.x,
      top: hero.y,
      width: hero.width,
      height: hero.height,
      overflowX: 0,
      overflowY: 0,
    };
  // The crossfade (upstream #598): both legs draw before the shared mask
  // applies, so the mask composes the blended result once.
  if (departing !== null && departing.mix < 1) {
    const departingFit =
      fittedGeometry(departing.source.width, departing.source.height, hero, departing.adjustment) ??
      fitted;
    drawFitted(departing.source, departingFit, 1 - departing.mix);
  }
  drawFitted(source, fitted, departing !== null && departing.mix < 1 ? departing.mix : 1);
  // The pure shader grid at CSS resolution, applied through
  // destination-in (dest alpha ×= mask alpha — the desktop's mask multiply).
  const grid = cutoutMaskRaster(hero, composer, cutout, cssWidth, cssHeight);
  const maskCanvas = deps.maskCanvas();
  if (maskCanvas === null) {
    return;
  }
  if (maskCanvas.width !== cssWidth) {
    maskCanvas.width = cssWidth;
  }
  if (maskCanvas.height !== cssHeight) {
    maskCanvas.height = cssHeight;
  }
  const maskContext = maskCanvas.getContext("2d");
  if (maskContext === null) {
    return;
  }
  const maskImageData = new ImageData(cssWidth, cssHeight);
  const maskData = maskImageData.data;
  for (let i = 0, offset = 0; i < grid.length; i++, offset += 4) {
    maskData[offset] = 255;
    maskData[offset + 1] = 255;
    maskData[offset + 2] = 255;
    maskData[offset + 3] = grid[i]! * 255;
  }
  maskContext.putImageData(maskImageData, 0, 0);
  context.globalCompositeOperation = "destination-in";
  context.drawImage(maskCanvas, 0, 0, rasterWidth, rasterHeight);
  context.globalCompositeOperation = "source-over";
}

// ---------------------------------------------------------------------------
// The renderer
// ---------------------------------------------------------------------------

/**
 * WebGL support probe on a throwaway canvas — a canvas that ever held a
 * WebGL context can never hand back a 2d one, so the capability check runs
 * BEFORE either pass canvas commits its context mode. A partial failure
 * after a green probe is all but impossible (two contexts is nowhere near
 * the browser's context limit); if it happens anyway, the live pass is
 * disposed and the CPU fallback takes over (a stuck canvas simply paints
 * nothing — `getContext("2d")` returning null).
 */
function webglSupported(): boolean {
  if (typeof document === "undefined" || typeof WebGLRenderingContext === "undefined") {
    return false;
  }
  const probe = document.createElement("canvas");
  const gl = (probe.getContext("webgl2", GL_ATTRIBUTES) ??
    probe.getContext("webgl", GL_ATTRIBUTES)) as WebGLRenderingContext | null;
  if (gl === null) {
    return false;
  }
  const highp = gl.getShaderPrecisionFormat(gl.FRAGMENT_SHADER, gl.HIGH_FLOAT);
  if (highp === null || highp.precision <= 0) {
    return false;
  }
  const program = compileProgram(gl);
  if (program !== null) {
    gl.deleteProgram(program);
  }
  return program !== null;
}

/**
 * Create the hero background renderer for the two existing pass canvases.
 * WebGL is attempted first (probe + context + shader + `highp`); ANY
 * failure selects the CPU fallback — today's exact paint path — with
 * `kind: "cpu-fallback"` so the component's `data-renderer` attribute can
 * engage the raster-window CSS.
 *
 * Context loss: `webglcontextlost` is `preventDefault`ed and
 * `webglcontextrestored` re-inits (recompile + re-upload + repaint at the
 * last geometry); while lost, renders clear (the cold-effect look). This
 * deviates from the design's "repeated loss pins the CPU fallback for the
 * mount" — a canvas that ever held a WebGL context can never hand back a
 * 2d one, so a mid-session GL→CPU pin is impossible on the same element;
 * a remount recreates the canvases and re-attempts GL.
 */
export function createHeroBackgroundRenderer(
  revealCanvas: HTMLCanvasElement,
  cutoutCanvas: HTMLCanvasElement,
  options: CreateHeroBackgroundRendererOptions = {},
): HeroBackgroundRenderer {
  // The mask-grid scratch canvas, created lazily ONCE (the factory stays
  // callable in environments without a DOM; the paint path is what needs it).
  let scratchMaskCanvas: HTMLCanvasElement | null = null;
  const maskCanvas = (): HTMLCanvasElement | null => {
    if (typeof document === "undefined") {
      return null;
    }
    scratchMaskCanvas ??= document.createElement("canvas");
    return scratchMaskCanvas;
  };
  const cpuDeps: CpuPaintDeps = {
    revealCanvas,
    cutoutCanvas,
    maskCanvas,
    setRasterWidth: options.setRasterWidth,
  };

  if (webglSupported()) {
    const revealPass = WebGlPass.create(revealCanvas);
    const cutoutPass = revealPass === null ? null : WebGlPass.create(cutoutCanvas);
    if (revealPass !== null && cutoutPass !== null) {
      return new WebGlRenderer(revealPass, cutoutPass, cpuDeps);
    }
    // A half-initialized pair (one context live, one failed) releases the
    // live one and falls back wholly — the two passes never split backends.
    revealPass?.dispose();
    cutoutPass?.dispose();
  }
  return new CpuFallbackRenderer(cpuDeps);
}

class CpuFallbackRenderer implements HeroBackgroundRenderer {
  readonly kind = "cpu-fallback" as const;
  #source: HeroArtworkSource | null = null;
  #adjustment: NewThreadBackgroundAdjustment = NEW_THREAD_ADJUSTMENT_DEFAULT;
  #departing: {
    source: HeroArtworkSource;
    mix: number;
    adjustment: NewThreadBackgroundAdjustment;
  } | null = null;
  #disposed = false;

  constructor(private readonly deps: CpuPaintDeps) {}

  setSource(source: HeroArtworkSource | null): void {
    if (this.#disposed) {
      return;
    }
    this.#source = source;
  }

  setAdjustment(adjustment: NewThreadBackgroundAdjustment): void {
    if (this.#disposed) {
      return;
    }
    this.#adjustment = adjustment;
  }

  setDeparting(
    source: HeroArtworkSource | null,
    mix: number,
    adjustment: NewThreadBackgroundAdjustment = NEW_THREAD_ADJUSTMENT_DEFAULT,
  ): void {
    if (this.#disposed) {
      return;
    }
    this.#departing =
      source === null || mix >= 1
        ? null
        : { source, mix: Math.min(Math.max(mix, 0), 1), adjustment };
  }

  render(geometry: HeroRenderGeometry): void {
    if (this.#disposed) {
      return;
    }
    const source = this.#source;
    const { hero, composer } = geometry;
    if (
      source === null ||
      source.width <= 0 ||
      source.height <= 0 ||
      hero.width <= 0 ||
      hero.height <= 0 ||
      composer.width <= 0
    ) {
      this.#clear();
      return;
    }
    paintCpuPass(
      this.deps,
      this.deps.revealCanvas,
      hero,
      composer,
      false,
      source,
      geometry.dpr,
      this.#adjustment,
      this.#departing,
    );
    paintCpuPass(
      this.deps,
      this.deps.cutoutCanvas,
      hero,
      composer,
      true,
      source,
      geometry.dpr,
      this.#adjustment,
      this.#departing,
    );
  }

  #clear(): void {
    const clear = (canvas: HTMLCanvasElement): void => {
      if (canvas.width !== 0) {
        canvas.width = 0;
      }
    };
    clear(this.deps.revealCanvas);
    clear(this.deps.cutoutCanvas);
  }

  dispose(): void {
    if (this.#disposed) {
      return;
    }
    this.#disposed = true;
    this.#clear();
  }
}

class WebGlRenderer implements HeroBackgroundRenderer {
  #source: HeroArtworkSource | null = null;
  #adjustment: NewThreadBackgroundAdjustment = NEW_THREAD_ADJUSTMENT_DEFAULT;
  #departing: {
    source: HeroArtworkSource;
    mix: number;
    adjustment: NewThreadBackgroundAdjustment;
  } | null = null;
  #lastGeometry: HeroRenderGeometry | null = null;
  #disposed = false;
  #disposeListeners: Array<() => void> = [];

  constructor(
    private readonly revealPass: WebGlPass,
    private readonly cutoutPass: WebGlPass,
    private readonly cpuDeps: CpuPaintDeps,
  ) {
    const bind = (pass: WebGlPass, canvas: HTMLCanvasElement): void => {
      const onLost = (event: Event): void => {
        event.preventDefault();
      };
      const onRestored = (): void => {
        // Recompile + re-upload, then repaint both passes at the last
        // geometry (either canvas's restore invalidates the shared source
        // upload path — repaint through the renderer so both roles run).
        pass.restore(this.#source);
        const geometry = this.#lastGeometry;
        if (geometry !== null) {
          this.render(geometry);
        }
      };
      canvas.addEventListener("webglcontextlost", onLost);
      canvas.addEventListener("webglcontextrestored", onRestored);
      this.#disposeListeners.push(() => {
        canvas.removeEventListener("webglcontextlost", onLost);
        canvas.removeEventListener("webglcontextrestored", onRestored);
      });
    };
    bind(this.revealPass, this.cpuDeps.revealCanvas);
    bind(this.cutoutPass, this.cpuDeps.cutoutCanvas);
  }

  get kind(): "webgl" {
    return "webgl";
  }

  setSource(source: HeroArtworkSource | null): void {
    if (this.#disposed) {
      return;
    }
    this.#source = source;
    this.revealPass.setSource(source);
    this.cutoutPass.setSource(source);
  }

  setAdjustment(adjustment: NewThreadBackgroundAdjustment): void {
    if (this.#disposed) {
      return;
    }
    this.#adjustment = adjustment;
  }

  setDeparting(
    source: HeroArtworkSource | null,
    mix: number,
    adjustment: NewThreadBackgroundAdjustment = NEW_THREAD_ADJUSTMENT_DEFAULT,
  ): void {
    if (this.#disposed) {
      return;
    }
    this.#departing =
      source === null || mix >= 1
        ? null
        : { source, mix: Math.min(Math.max(mix, 0), 1), adjustment };
    const departing = this.#departing;
    this.revealPass.setDeparting(departing?.source ?? null, departing?.mix ?? 1, departing?.adjustment);
    this.cutoutPass.setDeparting(departing?.source ?? null, departing?.mix ?? 1, departing?.adjustment);
  }

  render(geometry: HeroRenderGeometry): void {
    if (this.#disposed) {
      return;
    }
    const { hero, composer } = geometry;
    if (hero.width <= 0 || hero.height <= 0 || composer.width <= 0) {
      this.#lastGeometry = null;
      this.revealPass.clear();
      this.cutoutPass.clear();
      return;
    }
    if (this.#source === null || this.#source.width <= 0 || this.#source.height <= 0) {
      this.#lastGeometry = null;
      this.revealPass.clear();
      this.cutoutPass.clear();
      return;
    }
    this.#lastGeometry = geometry;
    this.revealPass.render(geometry, false, this.#adjustment);
    this.cutoutPass.render(geometry, true, this.#adjustment);
  }

  dispose(): void {
    if (this.#disposed) {
      return;
    }
    this.#disposed = true;
    for (const dispose of this.#disposeListeners.splice(0)) {
      dispose();
    }
    this.revealPass.dispose();
    this.cutoutPass.dispose();
  }
}
