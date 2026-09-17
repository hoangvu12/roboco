import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  MENU_OUT_MS,
  PopupLifecycle,
  REAP_GRACE_MS,
} from "../src/lib/popup-lifecycle";

/**
 * The `Popup<T>` lifecycle — ports of the desktop's `popover.rs` test module:
 * `trigger_press_note_distinguishes_dismiss_from_open` (`:1553`) plus the
 * reap/finish/escape contracts of `popover.rs:68-206` and
 * `pickers.rs:871-890`. Fake timers drive the clock (the lifecycle reads
 * `Date.now` through its injected `now`).
 */

describe("PopupLifecycle", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  const makePopup = (options: { onClosedByEscape?: () => void } = {}) =>
    new PopupLifecycle<number>({ ...options, now: () => Date.now() });

  it("trigger_press_note_distinguishes_dismiss_from_open (popover.rs:1553)", () => {
    const popup = makePopup();

    // Fresh open: press finds nothing mounted → click opens.
    popup.noteTriggerPress();
    expect(popup.takePressWasOpen()).toBe(false);
    popup.open(1);

    // Trigger click while open: the card's mouse-down-out begins the close
    // on the press (either handler order) — the note still reads mounted, so
    // the click must NOT reopen.
    popup.noteTriggerPress();
    popup.beginClose();
    expect(popup.takePressWasOpen()).toBe(true);
    // Out-handler first, trigger note second: mid-exit still counts.
    popup.open(1);
    popup.beginClose();
    popup.noteTriggerPress();
    expect(popup.takePressWasOpen()).toBe(true);

    // The note is consumed — a later click starts clean.
    expect(popup.takePressWasOpen()).toBe(false);

    // Kind-keyed popups: a press on a DIFFERENT trigger doesn't count, so
    // that click switches menus instead of swallowing.
    const keyed = makePopup();
    keyed.open(1);
    keyed.noteTriggerPressMatching((kind) => kind === 2);
    expect(keyed.takePressWasOpen()).toBe(false);
    keyed.noteTriggerPressMatching((kind) => kind === 1);
    expect(keyed.takePressWasOpen()).toBe(true);
  });

  it("renders the three states: closed, open, closing", () => {
    const popup = makePopup();
    expect(popup.status()).toBe("closed");
    expect(popup.isOpen()).toBe(false);
    expect(popup.isClosing()).toBe(false);
    expect(popup.get()).toBe(null);
    expect(popup.asOpen()).toBe(null);

    popup.open(7);
    expect(popup.status()).toBe("open");
    expect(popup.isOpen()).toBe(true);
    expect(popup.get()).toBe(7);
    expect(popup.asOpen()).toBe(7);

    popup.beginClose();
    expect(popup.status()).toBe("closing");
    expect(popup.isOpen()).toBe(false);
    expect(popup.isClosing()).toBe(true);
    // Logic paths read closed; render paths keep the value.
    expect(popup.asOpen()).toBe(null);
    expect(popup.get()).toBe(7);
  });

  it("beginClose returns true only the first time per open session", () => {
    const popup = makePopup();
    expect(popup.beginClose()).toBe(false);
    popup.open(1);
    expect(popup.beginClose()).toBe(true);
    expect(popup.beginClose()).toBe(false);
  });

  it("reap unmounts after MENU_OUT + grace (popover.rs:174-206)", () => {
    const popup = makePopup();
    popup.open(1);
    popup.beginClose();
    vi.advanceTimersByTime(MENU_OUT_MS + REAP_GRACE_MS - 1);
    expect(popup.status()).toBe("closing");
    vi.advanceTimersByTime(1);
    expect(popup.status()).toBe("closed");
    expect(popup.get()).toBe(null);
  });

  it("finishClose drops state only once the exit has run its course", () => {
    const popup = makePopup();
    popup.open(1);
    popup.beginClose();
    vi.advanceTimersByTime(50);
    popup.finishClose(); // exit not complete yet — stays mounted
    expect(popup.status()).toBe("closing");
    vi.advanceTimersByTime(MENU_OUT_MS + REAP_GRACE_MS);
    expect(popup.status()).toBe("closed");
  });

  it("a reopen during the exit cancels the reap and reads open", () => {
    const popup = makePopup();
    popup.open(1);
    popup.beginClose();
    popup.open(2);
    vi.advanceTimersByTime(MENU_OUT_MS + REAP_GRACE_MS + 1000);
    expect(popup.status()).toBe("open");
    expect(popup.get()).toBe(2);
    expect(popup.asOpen()).toBe(2);
  });

  it("a stale finishClose after a reopen leaves the newer phase alone", () => {
    const popup = makePopup();
    popup.open(1);
    popup.beginClose();
    vi.advanceTimersByTime(50);
    popup.open(2);
    popup.finishClose();
    expect(popup.status()).toBe("open");
    expect(popup.get()).toBe(2);
  });

  it("escape fires the focus-return slot; plain dismissal does not (pickers.rs:871-890)", () => {
    const onClosedByEscape = vi.fn();
    const popup = makePopup({ onClosedByEscape });

    popup.open(1);
    popup.closeByEscape();
    expect(onClosedByEscape).toHaveBeenCalledTimes(1);
    expect(popup.status()).toBe("closing");

    // Outside clicks deliberately do not trigger the focus return.
    popup.open(1);
    popup.dismiss();
    expect(onClosedByEscape).toHaveBeenCalledTimes(1);

    // Escape while already closing does not re-fire.
    popup.closeByEscape();
    expect(onClosedByEscape).toHaveBeenCalledTimes(1);
  });

  it("exitProgress runs 0→1 across the exit span", () => {
    const popup = makePopup();
    popup.open(1);
    expect(popup.exitProgress()).toBe(0);
    popup.beginClose();
    const since = Date.now();
    expect(popup.exitProgress(since)).toBe(0);
    expect(popup.exitProgress(since + MENU_OUT_MS / 2)).toBe(0.5);
    expect(popup.exitProgress(since + MENU_OUT_MS)).toBe(1);
    expect(popup.exitProgress(since + 10_000)).toBe(1);
  });

  it("exitProgress reads 1 once fully closed", () => {
    const popup = makePopup();
    popup.open(1);
    popup.beginClose();
    vi.advanceTimersByTime(MENU_OUT_MS + REAP_GRACE_MS);
    expect(popup.exitProgress()).toBe(1);
  });
});
