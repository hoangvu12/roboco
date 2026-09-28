// @vitest-environment jsdom

/**
 * Web parity for upstream zeron d92d56a2 — the sidebar's "Star on GitHub"
 * banner (`render_github_star_banner` on the desktop). The chip mounts with
 * the rebranded link and the dismiss affordance; dismissal — through the
 * close box or by following the link — flips `githubStarBannerDismissed` in
 * the ui-settings store, lands under `roboco.ui-settings.v1`, and a store
 * built fresh over the same storage (the next launch) still reads it, so
 * the banner never returns.
 *
 * The ui-settings singleton is real (module-level, localStorage-backed), the
 * settings-dialog.test.ts idiom; the banner is the only thing mounted. No
 * JSX (createElement), per-file jsdom pragma.
 */

import { act, createElement } from "react";
import { createRoot } from "react-dom/client";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { StarBanner } from "../src/components/star-banner";
import {
  UI_SETTINGS_STORAGE_KEY,
  UiSettingsStore,
  uiSettings,
} from "../src/state/ui-settings";

/** The rebrand contract: the banner never points at zeronsh. */
const GITHUB_REPO_URL = "https://github.com/hoangvu12/roboco";

beforeAll(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  // jsdom does not implement navigation; the anchor's default action would
  // spew "Not implemented" into the console for every link click. Cancelling
  // the default action in the capture phase suppresses exactly that — React's
  // onClick (root, bubble phase) still runs.
  document.addEventListener("click", (event) => event.preventDefault(), { capture: true });
});

afterAll(() => {
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
});

beforeEach(() => {
  localStorage.removeItem(UI_SETTINGS_STORAGE_KEY);
  // Fresh banner state per test — the store's snapshot is module state, so
  // removing the key alone cannot reset what the last test dismissed.
  uiSettings.updateImmediate({ githubStarBannerDismissed: false });
});

/** Every mounted root's unmount — drained so a later test's store write
 *  never re-renders a stale root outside act. */
const unmounts: Array<() => void> = [];

afterEach(() => {
  while (unmounts.length > 0) {
    unmounts.pop()!();
  }
  document.body.replaceChildren();
});

function mountBanner(): HTMLDivElement {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  unmounts.push(() =>
    act(() => {
      root.unmount();
    }),
  );
  act(() => {
    root.render(createElement(StarBanner));
  });
  return container;
}

describe("the sidebar's Star on GitHub banner (upstream d92d56a2)", () => {
  it("renders the chip, the rebranded link, and the dismiss affordance", () => {
    const container = mountBanner();
    const chip = container.querySelector<HTMLElement>("#github-star-banner");
    expect(chip).not.toBeNull();

    const link = container.querySelector<HTMLAnchorElement>(".star-banner-link");
    expect(link).not.toBeNull();
    // The rebrand: hoangvu12/roboco, never the upstream repository.
    expect(link!.getAttribute("href")).toBe(GITHUB_REPO_URL);
    expect(link!.textContent).toContain("Star on GitHub");
    expect(link!.target).toBe("_blank");

    const dismiss = container.querySelector<HTMLButtonElement>(".star-banner-dismiss");
    expect(dismiss).not.toBeNull();
    expect(dismiss!.getAttribute("aria-label")).toBe("Dismiss");
  });

  it("stays hidden once dismissed — the store flag drives the render", () => {
    uiSettings.updateImmediate({ githubStarBannerDismissed: true });
    const container = mountBanner();
    expect(container.querySelector("#github-star-banner")).toBeNull();
  });

  it("the close box dismisses for good and the write lands in localStorage", () => {
    const container = mountBanner();
    const dismiss = container.querySelector<HTMLButtonElement>(".star-banner-dismiss");
    expect(dismiss).not.toBeNull();

    act(() => {
      dismiss!.click();
    });
    expect(container.querySelector("#github-star-banner")).toBeNull();
    expect(uiSettings.getSnapshot().githubStarBannerDismissed).toBe(true);

    // The persisted write: the next launch (a store built fresh over the
    // same storage) heals the flag true, so the banner never returns.
    const stored = JSON.parse(localStorage.getItem(UI_SETTINGS_STORAGE_KEY)!);
    expect(stored.githubStarBannerDismissed).toBe(true);
    const nextLaunch = new UiSettingsStore({ storage: window.localStorage });
    expect(nextLaunch.getSnapshot().githubStarBannerDismissed).toBe(true);
  });

  it("following the link dismisses too (the desktop's on-click pairs the two)", () => {
    const container = mountBanner();
    const link = container.querySelector<HTMLAnchorElement>(".star-banner-link");
    expect(link).not.toBeNull();

    act(() => {
      link!.click();
    });
    expect(container.querySelector("#github-star-banner")).toBeNull();
    expect(uiSettings.getSnapshot().githubStarBannerDismissed).toBe(true);
  });
});
