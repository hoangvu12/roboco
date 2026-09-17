/**
 * The `Popup<T>` lifecycle — port of the desktop's `popover::Popup`
 * (`crates/ui/src/popover.rs:68-206`): a three-state machine
 * (`closed` → `open` → `closing` → `closed`) that keeps a floating card
 * mounted through its `MENU_OUT` exit animation, plus the trigger-press
 * note that stops a dismissing click from reopening the popup in the same
 * gesture.
 *
 * Logic paths (which menu is open, key handlers) read `asOpen()` so a
 * closing popup already reads as closed; render paths read `get()` so the
 * card keeps painting through its exit animation.
 */

import { useMemo, useRef, useSyncExternalStore } from "react";

/** `motion::MENU_OUT.total()` — the exit animation's span (100ms, EASE). */
export const MENU_OUT_MS = 100;
/** `reap_popup`'s grace beyond the exit span (`popover.rs:186-206`). */
export const REAP_GRACE_MS = 20;

export type PopupStatus = "closed" | "open" | "closing";

export interface PopupSnapshot<T> {
  readonly status: PopupStatus;
  readonly value: T | null;
}

export interface PopupLifecycleOptions {
  /**
   * Escape-path callback — the desktop's `animate_close` emits a
   * `ReturnComposerFocus` signal when the popup was open (`pickers.rs:866`).
   * Distinct from plain dismissal, which never returns focus.
   */
  readonly onClosedByEscape?: () => void;
  /** Injectable clock for tests; defaults to `performance.now`. */
  readonly now?: () => number;
  /** Test speed scale (the desktop's `motion::speed_scale`); default 1. */
  readonly speedScale?: number;
}

const CLOSED_SNAPSHOT: PopupSnapshot<never> = { status: "closed", value: null };

/**
 * The state machine. `beginClose()` stamps the exit start and schedules the
 * reap (finish after `MENU_OUT * speedScale + 20ms`); `open()`/`dismiss()`
 * cancel a pending reap first. A `finishClose()` that fires after the popup
 * was reopened in the meantime leaves the newer phase alone.
 */
export class PopupLifecycle<T> {
  #inner: { value: T; closingSince: number | null } | null = null;
  #pressedWhileOpen = false;
  #reapTimer: ReturnType<typeof setTimeout> | null = null;
  #snapshot: PopupSnapshot<T> = CLOSED_SNAPSHOT as PopupSnapshot<T>;
  readonly #listeners = new Set<() => void>();
  readonly #now: () => number;
  readonly #speedScale: number;
  readonly #onClosedByEscape: (() => void) | undefined;

  constructor(options: PopupLifecycleOptions = {}) {
    this.#now = options.now ?? (() => performance.now());
    this.#speedScale = options.speedScale ?? 1;
    this.#onClosedByEscape = options.onClosedByEscape;
  }

  /** Mount the popup in the open state; a pending exit is cancelled. */
  open(value: T): void {
    this.#cancelReap();
    this.#inner = { value, closingSince: null };
    this.#commit();
  }

  /** Open and interactive (not closing). */
  isOpen(): boolean {
    return this.#inner !== null && this.#inner.closingSince === null;
  }

  /** Playing the exit animation — still mounted, still painting. */
  isClosing(): boolean {
    return this.#inner !== null && this.#inner.closingSince !== null;
  }

  status(): PopupStatus {
    if (this.#inner === null) {
      return "closed";
    }
    return this.#inner.closingSince === null ? "open" : "closing";
  }

  /** When the exit phase began (ms on the injected clock); null unless closing. */
  closingSince(): number | null {
    return this.#inner?.closingSince ?? null;
  }

  /** The value while mounted — open OR closing. Render paths use this. */
  get(): T | null {
    return this.#inner?.value ?? null;
  }

  /** The value only while genuinely open — logic paths use this. */
  asOpen(): T | null {
    return this.isOpen() ? (this.#inner as { value: T }).value : null;
  }

  /**
   * Enter the exit phase (the reap is scheduled). Returns `true` when this
   * call started it; `false` if already closing or closed.
   */
  beginClose(): boolean {
    if (this.#inner === null || this.#inner.closingSince !== null) {
      return false;
    }
    this.#inner = { value: this.#inner.value, closingSince: this.#now() };
    this.#scheduleReap();
    this.#commit();
    return true;
  }

  /**
   * Plain dismissal — begin the exit with no focus-return contract (outside
   * clicks keep focus wherever they landed; `pickers.rs:871-890`).
   */
  dismiss(): void {
    this.beginClose();
  }

  /**
   * The escape path (`pickers.rs::animate_close`): fire the focus-return
   * slot when the popup was open, then dismiss.
   */
  closeByEscape(): void {
    if (this.isOpen()) {
      this.#onClosedByEscape?.();
    }
    this.dismiss();
  }

  /** Drop the state only if the exit has run its course; a popup reopened
   * (or re-closed) since the matching `beginClose` is left alone. */
  finishClose(): void {
    const since = this.#inner?.closingSince;
    if (since === null || since === undefined) {
      return;
    }
    if (this.#now() - since >= this.#exitSpanMs()) {
      this.#cancelReap();
      this.#inner = null;
      this.#commit();
    }
  }

  /**
   * Record, from the trigger's pointerdown, whether this popup was still
   * mounted (open OR mid-exit). The card's outside-press guard fires on the
   * same press and begins the close, so by click time the popup already
   * reads as closed — the click handler alone cannot tell "this press
   * dismissed it; stay closed" from "open fresh" (`popover.rs:152-169`).
   */
  noteTriggerPress(): void {
    this.noteTriggerPressMatching(() => true);
  }

  /** The multi-trigger variant: only a press on the OWNING trigger counts,
   * so clicking a different trigger switches menus instead of swallowing. */
  noteTriggerPressMatching(owns: (value: T) => boolean): void {
    this.#pressedWhileOpen = this.#inner !== null && owns(this.#inner.value);
  }

  /** Consume the press note; `true` when the press found the popup mounted. */
  takePressWasOpen(): boolean {
    const wasOpen = this.#pressedWhileOpen;
    this.#pressedWhileOpen = false;
    return wasOpen;
  }

  /**
   * Raw wall-clock progress of the exit phase: `0` at `beginClose()`, `1`
   * after `MENU_OUT * speedScale`. Monotonic by construction — unlike a CSS
   * animation's own clock it can never replay from 0 mid-exit, so a popup
   * interrupted mid-exit and resumed reads a true start point. The EASE
   * curve is applied where this is consumed (the CSS animation's timing
   * function), exactly as the desktop applies `MENU_OUT.progress` at paint.
   */
  exitProgress(now: number = this.#now()): number {
    const since = this.#inner?.closingSince;
    if (since === null || since === undefined) {
      return this.isOpen() ? 0 : 1;
    }
    const total = this.#exitSpanMs();
    if (total <= 0) {
      return 1;
    }
    return Math.min(1, Math.max(0, (now - since) / total));
  }

  /** Referentially-stable snapshot for `useSyncExternalStore`. */
  snapshot(): PopupSnapshot<T> {
    return this.#snapshot;
  }

  subscribe(listener: () => void): () => void {
    this.#listeners.add(listener);
    return () => {
      this.#listeners.delete(listener);
    };
  }

  #exitSpanMs(): number {
    return MENU_OUT_MS * this.#speedScale;
  }

  #scheduleReap(): void {
    this.#cancelReap();
    this.#reapTimer = setTimeout(
      () => this.finishClose(),
      this.#exitSpanMs() + REAP_GRACE_MS,
    );
  }

  #cancelReap(): void {
    if (this.#reapTimer !== null) {
      clearTimeout(this.#reapTimer);
      this.#reapTimer = null;
    }
  }

  #commit(): void {
    this.#snapshot =
      this.#inner === null
        ? (CLOSED_SNAPSHOT as PopupSnapshot<T>)
        : { status: this.status(), value: this.#inner.value };
    for (const listener of [...this.#listeners]) {
      listener();
    }
  }
}

/**
 * One popup lifecycle per menu, as a stable instance that re-renders its
 * owner on every state change. The escape callback stays fresh across
 * renders without restarting the machine.
 */
export function usePopup<T>(options: PopupLifecycleOptions = {}): PopupLifecycle<T> {
  const optionsRef = useRef(options);
  optionsRef.current = options;
  const popup = useMemo(
    () =>
      new PopupLifecycle<T>({
        now: options.now,
        speedScale: options.speedScale,
        onClosedByEscape: () => optionsRef.current.onClosedByEscape?.(),
      }),
    // The clock/speed are test seams fixed at mount; everything else reads
    // the options ref. eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );
  useSyncExternalStore(
    (listener) => popup.subscribe(listener),
    () => popup.snapshot(),
    () => popup.snapshot(),
  );
  return popup;
}
