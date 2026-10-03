// @vitest-environment jsdom

/**
 * Ticket wpn-07 — the background-pause arm of the desktop's `motion::resolve`
 * (`reduced || (pause_in_background && !active)`), web-shaped. The web used to
 * omit the arm outright ("a browser cannot observe app-window focus", the
 * rationale reduced-motion.ts carried); this ticket reverses that decision:
 * `document.hasFocus()` observes document focus, so the arm folds into
 * `effectiveReducedMotion` and a focus/visibility monitor carries flips to the
 * root `data-animations-paused` attr (the stylesheet's pause scope — never
 * `data-reduced-motion`, whose `animation: none` snaps spinners to their rest
 * state) and to the module's subscribers.
 *
 * Seams: the resolver takes the focus read as an injected function (the same
 * construction seam `ToolRevealClock` takes for `reduced`), and the monitor is
 * driven by dispatching the real window/document events — every read resolves
 * through the controllable `document.hasFocus()`, never the event payloads
 * (blur fires spuriously on URL-bar/devtools clicks).
 */

import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  effectiveReducedMotion,
  initBackgroundPauseMonitor,
  subscribeToBackgroundPause,
} from "../src/lib/reduced-motion";
import { uiSettings } from "../src/state/ui-settings";

/** The controllable document-focus state every read in this file flows through. */
let focused = true;

beforeEach(() => {
  focused = true;
  document.hasFocus = () => focused;
  uiSettings.updateImmediate({ pauseAnimationsInBackground: false, reduceMotion: "system" });
});

afterEach(() => {
  document.documentElement.removeAttribute("data-animations-paused");
});

describe("effectiveReducedMotion — the background-pause arm", () => {
  it("setting on + unfocused document → effective true over the system-follow pin", () => {
    uiSettings.updateImmediate({ pauseAnimationsInBackground: true });
    focused = false;
    expect(effectiveReducedMotion("system", () => focused)).toBe(true);
  });

  it("setting on + unfocused document → effective true even over an off pin", () => {
    // The arm ORs in after the pin (motion::resolve's 4th arm): an "off" pin
    // means "play even if the system asks for less motion", not "play while
    // the tab is backgrounded".
    uiSettings.updateImmediate({ pauseAnimationsInBackground: true });
    focused = false;
    expect(effectiveReducedMotion("off", () => focused)).toBe(true);
  });

  it("setting on + focused document → the arm adds nothing (resolve's answer unchanged)", () => {
    uiSettings.updateImmediate({ pauseAnimationsInBackground: true });
    focused = true;
    expect(effectiveReducedMotion("system", () => focused)).toBe(false);
    expect(effectiveReducedMotion("off", () => focused)).toBe(false);
  });

  it("setting off → the arm never fires, focused or not", () => {
    focused = false;
    expect(effectiveReducedMotion("system", () => focused)).toBe(false);
    expect(effectiveReducedMotion("off", () => focused)).toBe(false);
  });

  it("an on pin reduces regardless of focus or the setting", () => {
    focused = false;
    expect(effectiveReducedMotion("on", () => focused)).toBe(true);
  });
});

describe("the focus/visibility monitor", () => {
  it("a genuine blur with the setting on pauses; refocus resumes (attr removed)", () => {
    uiSettings.updateImmediate({ pauseAnimationsInBackground: true });
    const teardown = initBackgroundPauseMonitor();
    expect(document.documentElement.hasAttribute("data-animations-paused")).toBe(false);
    focused = false;
    window.dispatchEvent(new Event("blur"));
    expect(document.documentElement.hasAttribute("data-animations-paused")).toBe(true);
    focused = true;
    window.dispatchEvent(new Event("focus"));
    expect(document.documentElement.hasAttribute("data-animations-paused")).toBe(false);
    teardown();
  });

  it("a spurious blur (hasFocus() still true) pauses nothing — the read decides, not the payload", () => {
    // The URL-bar/devtools case: blur fires while the document still holds
    // focus, so nothing pauses.
    uiSettings.updateImmediate({ pauseAnimationsInBackground: true });
    const teardown = initBackgroundPauseMonitor();
    focused = true;
    window.dispatchEvent(new Event("blur"));
    expect(document.documentElement.hasAttribute("data-animations-paused")).toBe(false);
    teardown();
  });

  it("visibilitychange re-reads focus too — the tab switch the blur event may not have carried", () => {
    uiSettings.updateImmediate({ pauseAnimationsInBackground: true });
    const teardown = initBackgroundPauseMonitor();
    focused = false;
    document.dispatchEvent(new Event("visibilitychange"));
    expect(document.documentElement.hasAttribute("data-animations-paused")).toBe(true);
    teardown();
  });

  it("the setting off never pauses, and a settings write while unfocused lands immediately", () => {
    // The row's write path: the monitor rides the settings store too, so a
    // toggle that lands while the document is unfocused pauses at once.
    focused = false;
    const teardown = initBackgroundPauseMonitor();
    expect(document.documentElement.hasAttribute("data-animations-paused")).toBe(false);
    uiSettings.updateImmediate({ pauseAnimationsInBackground: true });
    expect(document.documentElement.hasAttribute("data-animations-paused")).toBe(true);
    teardown();
  });

  it("a pause flip re-notifies subscribers — the effective flag re-resolves", () => {
    uiSettings.updateImmediate({ pauseAnimationsInBackground: true });
    const readings: boolean[] = [];
    const unsubscribe = subscribeToBackgroundPause(() => {
      readings.push(effectiveReducedMotion());
    });
    const teardown = initBackgroundPauseMonitor();
    focused = false;
    window.dispatchEvent(new Event("blur"));
    focused = true;
    window.dispatchEvent(new Event("focus"));
    expect(readings).toEqual([true, false]);
    unsubscribe();
    teardown();
  });
});
