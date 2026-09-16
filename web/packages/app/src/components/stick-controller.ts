import {
  AT_BOTTOM_PX,
  GLIDE_MAX_VIEWPORTS,
  SPRING_FRAME_MS,
  SPRING_MAX_CATCHUP_FRAMES,
  SPRING_SETTLE_GRACE_MS,
  StickSpring,
  jumpVisibility,
  shouldAnchorLiveStream,
  shouldRestick,
} from "../lib/stick-spring";

/**
 * The DOM driver for the stick-to-bottom spring — the web peer of the
 * desktop transcript's `handle_scroll`/`step_spring`/`engage_pin`
 * (crates/ui/src/transcript.rs):
 *
 * - Escape: a scroll the controller didn't write that moves AWAY from the
 *   bottom breaks the pin (wheel, touch, keys, scrollbar drag all surface as
 *   scroll events; content growth never fires one). A no-op scroll attempt
 *   at the end keeps the pin, exactly like the desktop's distance check.
 * - Re-stick: arriving at the bottom, or moving toward it inside the 70px
 *   band, re-engages the pin with a glide (`shouldRestick` direction guard).
 * - Teleport: jumps longer than 2.5 viewports snap to within that range and
 *   glide the rest; `prefers-reduced-motion` snaps instead of gliding.
 * - Live anchor: a live stream resting at the end hard-anchors instantly as
 *   its measured height grows (`shouldAnchorLiveStream`).
 * - Settle grace: 500ms after landing the spring state parks; a layout kick
 *   inside the grace reuses it.
 */
export class StickController {
  #el: HTMLElement | null = null;
  #spring = new StickSpring();
  #streaming = false;
  #pinned = true;
  #raf = 0;
  #lastTick: number | null = null;
  #settledAt: number | null = null;
  #kick = false;
  /** Our own scrollTop writes, so the scroll handler can tell ours from the user's. */
  #expected: number | null = null;
  #prevDistance = 0;
  #jumpShown = false;
  readonly #onJumpVisibility: (shown: boolean) => void;
  readonly #reduced: MediaQueryList | null;

  constructor(options: { onJumpVisibility: (shown: boolean) => void; reducedMotion?: MediaQueryList | null }) {
    this.#onJumpVisibility = options.onJumpVisibility;
    this.#reduced =
      options.reducedMotion ??
      (typeof globalThis.matchMedia === "function" ? globalThis.matchMedia("(prefers-reduced-motion: reduce)") : null);
  }

  get pinned(): boolean {
    return this.#pinned;
  }

  attach(el: HTMLElement): void {
    this.detach();
    this.#el = el;
    this.#pinned = true;
    this.#prevDistance = this.#distance();
    this.#jumpShown = false;
    el.addEventListener("scroll", this.#onScroll, { passive: true });
  }

  detach(): void {
    const el = this.#el;
    if (el !== null) {
      el.removeEventListener("scroll", this.#onScroll);
    }
    this.#el = null;
    if (this.#raf !== 0) {
      cancelAnimationFrame(this.#raf);
      this.#raf = 0;
    }
  }

  setStreaming(streaming: boolean): void {
    this.#streaming = streaming;
  }

  /** Content or viewport resized: one observation frame (desktop wake_spring). */
  kick(): void {
    if (
      this.#settledAt !== null &&
      this.#lastTick !== null &&
      performance.now() - this.#settledAt >= SPRING_SETTLE_GRACE_MS
    ) {
      this.#spring.reset();
      this.#lastTick = null;
    }
    this.#settledAt = null;
    this.#kick = true;
    this.#schedule();
  }

  /** Snap to the end without motion (initial load, chat switch). */
  snapToEnd(): void {
    const el = this.#el;
    if (el === null) {
      return;
    }
    this.#pinned = true;
    this.#spring.reset();
    this.#write(this.#maxScroll());
    this.#prevDistance = 0;
    this.#setJumpShown(false);
  }

  /** Re-engage the bottom pin with a glide; long jumps teleport first. */
  jumpToBottom(): void {
    const el = this.#el;
    if (el === null) {
      return;
    }
    this.#pinned = true;
    this.#setJumpShown(false);
    const max = this.#maxScroll();
    if (this.#reduced?.matches) {
      this.#write(max);
      this.#prevDistance = 0;
      return;
    }
    const viewport = el.clientHeight;
    const distance = max - el.scrollTop;
    const glideMax = GLIDE_MAX_VIEWPORTS * viewport;
    if (viewport > 0 && distance > glideMax) {
      this.#write(max - glideMax);
    }
    this.kick();
  }

  /**
   * A viewport-preserving scroll write from the virtualizer's anchor restore:
   * marked as ours so the scroll handler never reads it as a user escape.
   */
  writePreserving(scrollTop: number): void {
    this.#write(scrollTop);
  }

  /** Current distance from the end in px. */
  #distance(): number {    const el = this.#el;
    return el === null ? 0 : Math.max(0, el.scrollHeight - el.clientHeight - el.scrollTop);
  }

  #maxScroll(): number {
    const el = this.#el;
    return el === null ? 0 : Math.max(0, el.scrollHeight - el.clientHeight);
  }

  #write(scrollTop: number): void {
    const el = this.#el;
    if (el === null) {
      return;
    }
    this.#expected = scrollTop;
    el.scrollTop = scrollTop;
  }

  #onScroll = (): void => {
    const el = this.#el;
    if (el === null) {
      return;
    }
    const distance = this.#distance();
    const ours = this.#expected !== null && Math.abs(el.scrollTop - this.#expected) <= 1.5;
    this.#expected = null;
    if (ours) {
      this.#prevDistance = distance;
      return;
    }
    if (this.#pinned) {
      // User input moving away from the bottom breaks the pin. Content growth
      // never lands here — it doesn't fire the scroll handler.
      if (distance > this.#prevDistance + 1 && distance > AT_BOTTOM_PX) {
        this.#pinned = false;
        this.#spring.reset();
        this.#lastTick = null;
        this.#settledAt = null;
      }
    } else if (distance <= AT_BOTTOM_PX || shouldRestick(distance, this.#prevDistance)) {
      // Arriving at the bottom, or returning toward it inside the band,
      // re-engages the pin with a glide.
      this.#pinned = true;
      this.kick();
    }
    this.#prevDistance = distance;
    this.#setJumpShown(jumpVisibility(this.#jumpShown, distance));
  };

  #setJumpShown(shown: boolean): void {
    if (shown !== this.#jumpShown) {
      this.#jumpShown = shown;
      this.#onJumpVisibility(shown);
    }
  }

  #schedule(): void {
    if (this.#raf === 0 && this.#el !== null) {
      this.#raf = requestAnimationFrame(this.#tick);
    }
  }

  #tick = (): void => {
    this.#raf = 0;
    const el = this.#el;
    if (el === null || !this.#pinned) {
      this.#lastTick = null;
      return;
    }
    const now = performance.now();
    if (this.#settledAt !== null && now - this.#settledAt >= SPRING_SETTLE_GRACE_MS) {
      this.#spring.reset();
      this.#lastTick = null;
      this.#settledAt = null;
    }
    const frames =
      this.#lastTick === null
        ? 1
        : Math.min((now - this.#lastTick) / SPRING_FRAME_MS, SPRING_MAX_CATCHUP_FRAMES);
    this.#lastTick = now;
    this.#kick = false;

    const target = this.#maxScroll();
    let distance = target - el.scrollTop;

    // A live stream resting at the end keeps the end anchored instantly.
    if (shouldAnchorLiveStream(true, distance, this.#streaming)) {
      if (distance > 0) {
        this.#write(target);
        distance = 0;
      }
      this.#settledAt ??= now;
      this.#prevDistance = 0;
      this.#setJumpShown(jumpVisibility(this.#jumpShown, 0));
      return;
    }

    if (this.#reduced?.matches) {
      if (distance > 0) {
        this.#write(target);
      }
      this.#prevDistance = 0;
      return;
    }

    // Long jumps teleport to within glide range first (mugen springToBottom).
    const viewport = el.clientHeight;
    const glideMax = GLIDE_MAX_VIEWPORTS * viewport;
    if (viewport > 0 && distance > glideMax) {
      this.#write(target - glideMax);
      distance = glideMax;
    }
    const pos = target - distance;
    const next = this.#spring.step(pos, target, frames);
    if (next > pos) {
      this.#write(next);
    }
    const remaining = Math.max(0, target - next);
    this.#prevDistance = remaining;
    this.#setJumpShown(jumpVisibility(this.#jumpShown, remaining));
    if (remaining <= 0.5) {
      // Land on the end exactly; remeasured rows must not restart the glide.
      if (el.scrollTop !== target) {
        this.#write(target);
      }
      this.#settledAt ??= now;
    } else {
      this.#settledAt = null;
    }
    // Keep driving while moving; a settled spring wakes on the next kick.
    if (next > pos || StickSpring.needsFrame(remaining)) {
      this.#schedule();
    }
  };
}
